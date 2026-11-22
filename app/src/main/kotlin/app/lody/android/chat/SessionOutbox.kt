package app.lody.android.chat

import android.content.Context
import app.lody.android.BackendEnvironment
import app.lody.android.catalog.CatalogState
import app.lody.android.catalog.ConnectionPhase
import app.lody.android.home.AppPreferences
import app.lody.android.runtime.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

class SessionOutbox(
    context: Context,
    private val tasks: CoroutineScope,
    private val catalog: StateFlow<CatalogState>,
    private val turnQuota: suspend (RuntimeScope, String) -> String?,
    private val upload: suspend (RuntimeScope, String, List<PendingAttachment>) -> List<JSONObject>?,
    private val send: suspend (RuntimeScope, SendTextRequest) -> RuntimeResult.Sent,
) {
    data class Foreground(
        val live: Boolean = false,
        val busy: Boolean = false,
        val streaming: Boolean = false,
        val steerable: Boolean = false,
        val queuedBehavior: String = AppPreferences.QUEUE,
    )

    data class Record(
        val scope: RuntimeScope,
        val sessionId: String,
        val turnId: String,
        val text: String,
        val shownText: String,
        val shownReferences: List<MessageReference>,
        val quotes: List<MessageQuoteCard>,
        val files: List<PendingAttachment>,
        val previews: List<MessageAttachment>,
        val at: String,
        val machineId: String,
        val cliType: String,
        val agentType: String,
        val resume: String?,
        val modelId: String?,
        val modeId: String?,
        val effort: String?,
        val explicitChoice: Boolean,
        val role: RoleSend,
        val agentConfigId: String?,
        val configOptionValues: Map<String, Any>,
        val persist: Boolean,
        val phase: String = "waiting",
        val reason: String? = null,
    ) {
        val blocksInput: Boolean
            get() = phase == "waiting" || phase == "sending" || phase == "unknown"

        fun delivery(sessionLive: Boolean): Delivery = when (phase) {
            "waiting" -> if (sessionLive) Delivery.Sending else Delivery.Waiting
            "sending", "uploaded" -> Delivery.Sending
            "queued" -> Delivery.Queued
            "failed" -> Delivery.Failed
            "unknown" -> Delivery.Unknown
            else -> Delivery.Sent
        }
    }

    data class Failure(
        val scope: RuntimeScope,
        val sessionId: String,
        val turnId: String,
        val reason: String?,
        val uploadFailed: Boolean,
        val record: Record,
    )

    private val appContext = context.applicationContext
    private val mutable = MutableStateFlow(loadInitial())
    val state: StateFlow<Map<String, Record>> = mutable.asStateFlow()
    private val failureEvents = MutableSharedFlow<Failure>(extraBufferCapacity = 8)
    val failures: SharedFlow<Failure> = failureEvents.asSharedFlow()

    private val foregrounds = mutableMapOf<String, Foreground>()
    private val inflight = mutableMapOf<String, Job>()
    private var scheduled = false

    init {
        tasks.launch {
            catalog.map { it.workspace?.id to it.phase }.distinctUntilChanged().collect { (_, phase) ->
                if (phase == ConnectionPhase.LIVE) schedule()
            }
        }
    }

    private fun preferences() =
        appContext.getSharedPreferences(FILE + BackendEnvironment.storageSuffix, Context.MODE_PRIVATE)

    fun key(scope: RuntimeScope, sessionId: String) = "${scope.accountId}\u0000${scope.workspaceId}\u0000$sessionId"

    fun record(scope: RuntimeScope, sessionId: String): Record? = mutable.value[key(scope, sessionId)]

    fun setForeground(scope: RuntimeScope, sessionId: String, state: Foreground) {
        val k = key(scope, sessionId)
        if (foregrounds[k] == state) return
        foregrounds[k] = state
        schedule()
    }

    fun leaveForeground(scope: RuntimeScope, sessionId: String) {
        if (foregrounds.remove(key(scope, sessionId)) != null) schedule()
    }

    fun submit(record: Record) {
        val k = key(record.scope, record.sessionId)
        if (k in inflight) return
        val existing = mutable.value[k]
        if (existing != null && existing.blocksInput) return
        put(k, record.copy(phase = "waiting", reason = null))
        schedule()
    }

    fun retry(scope: RuntimeScope, sessionId: String, turnId: String) {
        val k = key(scope, sessionId)
        val existing = mutable.value[k] ?: return
        if (existing.turnId != turnId || existing.phase != "failed" || k in inflight) return
        put(k, existing.copy(phase = "waiting", reason = null))
        schedule()
    }

    fun forget(scope: RuntimeScope, sessionId: String, turnId: String) {
        val k = key(scope, sessionId)
        val existing = mutable.value[k] ?: return
        if (existing.turnId != turnId || k in inflight) return
        if (existing.phase in setOf("unknown", "failed", "waiting")) remove(k)
    }

    fun acknowledge(scope: RuntimeScope, sessionId: String, landedTurnIds: Set<String>) {
        val k = key(scope, sessionId)
        val existing = mutable.value[k] ?: return
        if (existing.turnId in landedTurnIds && k !in inflight) remove(k)
    }

    fun clear() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        foregrounds.clear()
        mutable.value = emptyMap()
        preferences().edit().clear().apply()
    }

    private fun put(k: String, record: Record) {
        mutable.value = mutable.value + (k to record)
        val editor = preferences().edit()
        if (record.persist) editor.putString(k, encode(record).toString()) else editor.remove(k)
        editor.apply()
    }

    private fun remove(k: String) {
        if (k !in mutable.value) return
        mutable.value = mutable.value - k
        preferences().edit().remove(k).apply()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        tasks.launch {
            scheduled = false
            runPending()
        }
    }

    private fun runPending() {
        val catalogState = catalog.value
        val activeWorkspace = catalogState.workspace?.id
        val catalogLive = catalogState.phase == ConnectionPhase.LIVE
        var backgroundActive = inflight.keys.count { it !in foregrounds }
        for ((k, record) in mutable.value) {
            if (record.phase != "waiting" || k in inflight) continue
            val fg = foregrounds[k]
            if (fg != null) {
                if (!fg.live) continue
                start(k, record, fg)
            } else if (catalogLive && record.scope.workspaceId == activeWorkspace && backgroundActive < MAX_BACKGROUND_SENDS) {
                backgroundActive++
                start(k, record, null)
            }
        }
    }

    private fun start(k: String, record: Record, fg: Foreground?) {
        val sending = record.copy(phase = "sending", reason = null)
        put(k, sending)
        val job = tasks.launch {
            try {
                dispatch(k, sending, fg)
            } finally {
                inflight.remove(k)
                schedule()
            }
        }
        inflight[k] = job
    }

    private suspend fun dispatch(k: String, record: Record, fg: Foreground?) {
        val scope = record.scope
        val sessionId = record.sessionId
        if (record.files.isNotEmpty()) {
            val early = try { turnQuota(scope, sessionId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (quotaFailure(early) != null) {
                val failed = record.copy(phase = "failed", reason = early)
                put(k, failed)
                failureEvents.tryEmit(Failure(scope, sessionId, record.turnId, early, uploadFailed = false, record = failed))
                return
            }
        }
        val blocks = if (record.files.isEmpty()) emptyList() else try { upload(scope, sessionId, record.files) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (blocks == null) {
            val failed = record.copy(phase = "failed", reason = "upload_failed")
            put(k, failed)
            failureEvents.tryEmit(Failure(scope, sessionId, record.turnId, "upload_failed", uploadFailed = true, record = failed))
            return
        }
        val latestFg = foregrounds[k] ?: fg
        val guide = latestFg != null && latestFg.streaming && latestFg.steerable && latestFg.queuedBehavior == AppPreferences.GUIDE
        val queue = latestFg != null && (latestFg.busy || latestFg.streaming) && !guide
        val request = SendTextRequest(
            sessionId = sessionId,
            turnId = record.turnId,
            text = record.text,
            machineId = record.machineId,
            cliType = record.cliType,
            agentType = record.agentType,
            resume = record.resume,
            queue = queue,
            modelId = record.modelId,
            modeId = record.modeId,
            effort = record.effort,
            explicitChoice = record.explicitChoice,
            attachments = blocks,
            role = record.role,
            agentConfigId = record.agentConfigId,
            configOptionValues = record.configOptionValues,
            guide = guide,
            quotes = record.quotes,
        )
        var reason: String? = null
        val state = try {
            var sent = send(scope, request)
            while (sent.state == "not_sent" && sent.reason == PREVIOUS_INPUT_PENDING) {
                sent = send(scope, request)
            }
            reason = sent.reason
            sent.state
        } catch (cancelled: CancellationException) {
            if (mutable.value[k]?.turnId == record.turnId) put(k, record.copy(phase = "unknown"))
            throw cancelled
        } catch (_: Exception) {
            "unknown"
        }
        if (mutable.value[k]?.turnId != record.turnId) return
        if (state == "not_sent" && reason == "session_not_ready") {
            put(k, record.copy(phase = "waiting", reason = null))
            delay(1_000L)
            return
        }
        if (state == "not_sent") {
            val failed = record.copy(phase = "failed", reason = reason)
            put(k, failed)
            failureEvents.tryEmit(Failure(scope, sessionId, record.turnId, reason, uploadFailed = false, record = failed))
            return
        }
        val nextPhase = when (state) {
            "queued" -> "queued"
            "uploaded" -> "uploaded"
            "unknown" -> "unknown"
            else -> "sent"
        }
        put(k, record.copy(phase = nextPhase, reason = null))
    }

    private fun loadInitial(): Map<String, Record> {
        val stored = preferences().all
        if (stored.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Record>()
        val editor = preferences().edit()
        var mutated = false
        for ((k, raw) in stored) {
            val text = raw as? String ?: continue
            val decoded = runCatching { decode(JSONObject(text)) }.getOrNull() ?: continue
            val restored = when {
                decoded.phase == "sending" -> { mutated = true; decoded.copy(phase = "unknown") }
                decoded.phase == "waiting" && decoded.previews.isNotEmpty() -> {
                    mutated = true
                    decoded.copy(phase = "failed", reason = "upload_failed")
                }
                else -> decoded
            }
            if (restored.phase != decoded.phase) editor.putString(k, encode(restored).toString())
            result[k] = restored
        }
        if (mutated) editor.apply()
        return result
    }

    private fun encode(record: Record): JSONObject = JSONObject().apply {
        put("accountId", record.scope.accountId)
        put("workspaceId", record.scope.workspaceId)
        put("sessionId", record.sessionId)
        put("turnId", record.turnId)
        put("text", record.text)
        put("shownText", record.shownText)
        put("shownReferences", JSONArray(record.shownReferences.map { JSONObject().put("start", it.start).put("end", it.end) }))
        put("quotes", JSONArray(record.quotes.map { JSONObject().put("id", it.id).put("snapshot", it.snapshot).put("comment", it.comment) }))
        put("previews", JSONArray(record.previews.map { JSONObject().put("name", it.name).put("image", it.image) }))
        put("at", record.at)
        put("machineId", record.machineId)
        put("cliType", record.cliType)
        put("agentType", record.agentType)
        if (record.resume != null) put("resume", record.resume)
        if (record.modelId != null) put("modelId", record.modelId)
        if (record.modeId != null) put("modeId", record.modeId)
        if (record.effort != null) put("effort", record.effort)
        put("explicitChoice", record.explicitChoice)
        when (val r = record.role) {
            RoleSend.Inherit -> put("roleKind", "inherit")
            RoleSend.None -> put("roleKind", "none")
            is RoleSend.Role -> { put("roleKind", "role"); put("roleId", r.id) }
        }
        if (record.agentConfigId != null) put("agentConfigId", record.agentConfigId)
        val options = JSONObject()
        for ((optKey, optValue) in record.configOptionValues) {
            if (optValue is Boolean || optValue is String) options.put(optKey, optValue)
        }
        put("configOptionValues", options)
        put("phase", record.phase)
        if (record.reason != null) put("reason", record.reason)
    }

    private fun decode(json: JSONObject): Record? {
        val accountId = json.optString("accountId").takeIf { it.isNotBlank() } ?: return null
        val workspaceId = json.optString("workspaceId").takeIf { it.isNotBlank() } ?: return null
        val sessionId = json.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
        val turnId = json.optString("turnId").takeIf { it.isNotBlank() } ?: return null
        val machineId = json.optString("machineId").takeIf { it.isNotBlank() } ?: return null
        val cliType = json.optString("cliType").takeIf { it.isNotBlank() } ?: return null
        val agentType = json.optString("agentType").takeIf { it.isNotBlank() } ?: return null
        val refsArray = json.optJSONArray("shownReferences") ?: JSONArray()
        val refs = (0 until refsArray.length()).mapNotNull { index ->
            val item = refsArray.optJSONObject(index) ?: return@mapNotNull null
            MessageReference(item.optInt("start"), item.optInt("end"))
        }
        val quotesArray = json.optJSONArray("quotes") ?: JSONArray()
        val quotes = (0 until quotesArray.length()).mapNotNull { index ->
            val card = quotesArray.optJSONObject(index) ?: return@mapNotNull null
            val id = card.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val snapshot = card.optString("snapshot").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MessageQuoteCard(id, snapshot, card.optString("comment"))
        }
        val previewsArray = json.optJSONArray("previews") ?: JSONArray()
        val previews = (0 until previewsArray.length()).mapNotNull { index ->
            val item = previewsArray.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MessageAttachment(name, item.optBoolean("image"))
        }
        val role = when (json.optString("roleKind", "inherit")) {
            "none" -> RoleSend.None
            "role" -> json.optString("roleId").takeIf { it.isNotBlank() }?.let { RoleSend.Role(it) } ?: RoleSend.Inherit
            else -> RoleSend.Inherit
        }
        val optionsJson = json.optJSONObject("configOptionValues")
        val options = mutableMapOf<String, Any>()
        if (optionsJson != null) {
            val keys = optionsJson.keys()
            while (keys.hasNext()) {
                val optKey = keys.next()
                val v = optionsJson.opt(optKey)
                if (v is Boolean || v is String) options[optKey] = v
            }
        }
        val text = json.optString("text")
        return Record(
            scope = RuntimeScope(accountId, workspaceId),
            sessionId = sessionId,
            turnId = turnId,
            text = text,
            shownText = json.optString("shownText", text),
            shownReferences = refs,
            quotes = quotes,
            files = emptyList(),
            previews = previews,
            at = json.optString("at", Instant.now().toString()),
            machineId = machineId,
            cliType = cliType,
            agentType = agentType,
            resume = (json.opt("resume") as? String)?.takeIf { it.isNotBlank() },
            modelId = json.opt("modelId") as? String,
            modeId = json.opt("modeId") as? String,
            effort = json.opt("effort") as? String,
            explicitChoice = json.optBoolean("explicitChoice", false),
            role = role,
            agentConfigId = (json.opt("agentConfigId") as? String)?.takeIf { it.isNotBlank() },
            configOptionValues = options,
            persist = true,
            phase = json.optString("phase", "waiting"),
            reason = json.opt("reason") as? String,
        )
    }

    companion object {
        private const val FILE = "session-outbox-v1"
        private const val MAX_BACKGROUND_SENDS = 8
    }
}
