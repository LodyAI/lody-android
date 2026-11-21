package app.lody.android.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Status view layout the shared runtime computes (`buildStatusViewLayout` in
 * `packages/shared`, carried as catalog `statusView`). Native code renders it as given and never
 * sorts or classifies these rows itself.
 */
enum class StatusTier(val key: String) { Pinned("pinned"), Focus("focus"), Later("later"), Settled("settled") }

data class StatusViewRow(
    val id: String,
    val tier: StatusTier,
    /** Opened-by children rendered under this row, in layout order. */
    val children: List<StatusViewRow> = emptyList(),
    val activityAt: Long = 0,
    val unread: Boolean = false,
    /** Set while the row is in Later: when it wakes (epoch ms, synced server clock). */
    val snoozedUntil: Long? = null,
    /** An expired snooze floating at the top of Focus. */
    val woken: Boolean = false,
    /** Later / Settle / Move to Focus / reorder in Focus. */
    val canManage: Boolean = false,
    /** Pin, unpin and pin placement. */
    val canPrefer: Boolean = false,
)

/** One pin section's rows; [sectionId] null is the default section. */
data class StatusViewPinGroup(
    val sectionId: String?,
    val name: String?,
    val rows: List<StatusViewRow>,
    /** Rows of this section the view scope hid. */
    val hiddenCount: Int = 0,
)

data class StatusViewLayout(
    val pinned: List<StatusViewPinGroup> = emptyList(),
    val focus: List<StatusViewRow> = emptyList(),
    val later: List<StatusViewRow> = emptyList(),
    val settled: List<StatusViewRow> = emptyList(),
    /** May create, rename, reorder and delete pin sections. */
    val canManageSections: Boolean = false,
    /** Earliest future instant at which the layout can change by time alone. */
    val nextChangeAt: Long? = null,
) {
    /** Every listed id, children included, in display order. */
    val ids: List<String> get() = (pinned.flatMap { it.rows } + focus + later + settled).flatMap { listOf(it) + it.children }.map { it.id }
}

/**
 * Catalog `statusView` (lody-ios `CatalogStatusView`): both view scopes, so switching My tasks /
 * Team needs no runtime round trip. [nowMs] is the server-clock instant they were computed at;
 * [sectionsLoaded] stays false until the workspace pin-section rows have been read once.
 */
data class StatusView(val mine: StatusViewLayout, val team: StatusViewLayout, val nowMs: Long = 0, val sectionsLoaded: Boolean = true)

/** A workspace pin section row (`['pinSection', id]` → `{ name, orderKey }`). */
data class PinSection(val id: String, val name: String, val orderKey: String? = null)

/**
 * A Later preset as the runtime resolved it on the server clock: [id] (`oneHour`, `laterToday`,
 * `tomorrowMorning`, `nextWeek`) is localized natively, [until] is when it wakes.
 */
data class SnoozePreset(val id: String, val until: Long, val countdown: String = "")

/** The Status view list a command came from: My tasks or the whole team. */
enum class StatusViewScope(val key: String) { Mine("mine"), Team("team") }

/** Where a move lands (shared `StatusViewCommand.move.to`). */
sealed interface StatusViewTarget {
    data object Focus : StatusViewTarget
    /** [sectionId] null is the default section. */
    data class Pinned(val sectionId: String?) : StatusViewTarget
}

/**
 * The shared planner's command union (`planStatusViewCommand`). The runtime plans against its own
 * replica and writes; native names the neighbour a moved row lands before, never an order key.
 */
sealed interface StatusViewCommand {
    data class Snooze(val sessionId: String, val until: Long, val includeOpened: Boolean) : StatusViewCommand
    data class Settle(val sessionId: String, val includeOpened: Boolean) : StatusViewCommand
    data class MoveToFocus(val sessionId: String, val includeOpened: Boolean) : StatusViewCommand
    data class SetPinned(val sessionId: String, val pinned: Boolean) : StatusViewCommand
    /** [beforeId]: the row it lands in front of; null is the end. */
    data class Move(val sessionId: String, val to: StatusViewTarget, val beforeId: String?) : StatusViewCommand
    data class CreateSection(val sectionId: String, val name: String) : StatusViewCommand
    data class RenameSection(val sectionId: String, val name: String) : StatusViewCommand
    data class DeleteSection(val sectionId: String) : StatusViewCommand
    data class MoveSection(val sectionId: String, val beforeId: String?) : StatusViewCommand
}

/** Lenient decoding: a malformed layout drops to null, never rejecting the catalog or a cache file. */
object StatusViewJson {
    private const val MAX_ROWS = 20_000

    fun statusView(json: JSONObject?): StatusView? {
        if (json == null) return null
        return try {
            StatusView(layout(json.getJSONObject("mine")), layout(json.getJSONObject("team")), json.millis("nowMs") ?: 0, json.opt("sectionsLoaded") != false)
        } catch (_: Exception) { null }
    }

    fun layout(json: JSONObject): StatusViewLayout {
        var budget = MAX_ROWS
        fun rows(array: JSONArray?): List<StatusViewRow> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { index ->
                check(--budget >= 0) { "status_view_too_large" }
                row(array.getJSONObject(index), ::rows)
            }
        }
        val pinned = json.optJSONArray("pinned")?.let { array ->
            (0 until array.length()).map { index ->
                val group = array.getJSONObject(index)
                StatusViewPinGroup(
                    group.text("sectionId"),
                    group.text("name"),
                    rows(group.optJSONArray("rows")),
                    (group.opt("hiddenCount") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                )
            }
        }.orEmpty()
        return StatusViewLayout(
            pinned, rows(json.optJSONArray("focus")), rows(json.optJSONArray("later")), rows(json.optJSONArray("settled")),
            json.opt("canManageSections") == true, json.millis("nextChangeAt"),
        )
    }

    private fun row(json: JSONObject, rows: (JSONArray?) -> List<StatusViewRow>): StatusViewRow {
        val id = json.getString("id").also { require(it.isNotEmpty() && it.length <= 256) }
        val tier = StatusTier.entries.first { it.key == json.getString("tier") }
        return StatusViewRow(
            id, tier, rows(json.optJSONArray("children")),
            json.millis("activityAt") ?: 0,
            json.opt("unread") == true,
            json.millis("snoozedUntil"),
            json.opt("woken") == true,
            json.opt("canManage") == true,
            json.opt("canPrefer") == true,
        )
    }

    fun pinSections(array: JSONArray?): List<PinSection> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("id") ?: return@mapNotNull null
            PinSection(id, item.text("name").orEmpty(), item.text("orderKey"))
        }
    }

    fun putStatusView(view: StatusView): JSONObject = JSONObject().put("mine", putLayout(view.mine)).put("team", putLayout(view.team))
        .put("nowMs", view.nowMs).put("sectionsLoaded", view.sectionsLoaded)

    fun putLayout(layout: StatusViewLayout): JSONObject = JSONObject()
        .put("pinned", JSONArray(layout.pinned.map { group ->
            JSONObject().put("sectionId", group.sectionId ?: JSONObject.NULL).put("name", group.name ?: JSONObject.NULL)
                .put("rows", JSONArray(group.rows.map(::putRow))).put("hiddenCount", group.hiddenCount)
        }))
        .put("focus", JSONArray(layout.focus.map(::putRow)))
        .put("later", JSONArray(layout.later.map(::putRow)))
        .put("settled", JSONArray(layout.settled.map(::putRow)))
        .put("canManageSections", layout.canManageSections)
        .put("nextChangeAt", layout.nextChangeAt ?: JSONObject.NULL)

    private fun putRow(row: StatusViewRow): JSONObject = JSONObject()
        .put("id", row.id).put("tier", row.tier.key).put("children", JSONArray(row.children.map(::putRow)))
        .put("activityAt", row.activityAt).put("unread", row.unread).put("snoozedUntil", row.snoozedUntil ?: JSONObject.NULL)
        .put("woken", row.woken).put("canManage", row.canManage).put("canPrefer", row.canPrefer)

    fun putPinSections(sections: List<PinSection>): JSONArray = JSONArray(sections.map { section ->
        JSONObject().put("id", section.id).put("name", section.name).apply { section.orderKey?.let { put("orderKey", it) } }
    })

    /** The `statusViewCommand` payload: the shared command union's JSON. */
    fun command(command: StatusViewCommand): JSONObject = when (command) {
        is StatusViewCommand.Snooze -> JSONObject().put("type", "snooze").put("sessionId", command.sessionId).put("until", command.until).put("includeOpened", command.includeOpened)
        is StatusViewCommand.Settle -> JSONObject().put("type", "settle").put("sessionId", command.sessionId).put("includeOpened", command.includeOpened)
        is StatusViewCommand.MoveToFocus -> JSONObject().put("type", "moveToFocus").put("sessionId", command.sessionId).put("includeOpened", command.includeOpened)
        is StatusViewCommand.SetPinned -> JSONObject().put("type", "setPinned").put("sessionId", command.sessionId).put("pinned", command.pinned)
        is StatusViewCommand.Move -> JSONObject().put("type", "move").put("sessionId", command.sessionId)
            .put("to", when (val to = command.to) {
                StatusViewTarget.Focus -> JSONObject().put("area", "focus")
                is StatusViewTarget.Pinned -> JSONObject().put("area", "pinned").put("sectionId", to.sectionId ?: JSONObject.NULL)
            })
            .put("beforeId", command.beforeId ?: JSONObject.NULL)
        is StatusViewCommand.CreateSection -> JSONObject().put("type", "createSection").put("sectionId", command.sectionId).put("name", command.name)
        is StatusViewCommand.RenameSection -> JSONObject().put("type", "renameSection").put("sectionId", command.sectionId).put("name", command.name)
        is StatusViewCommand.DeleteSection -> JSONObject().put("type", "deleteSection").put("sectionId", command.sectionId)
        is StatusViewCommand.MoveSection -> JSONObject().put("type", "moveSection").put("sectionId", command.sectionId).put("beforeId", command.beforeId ?: JSONObject.NULL)
    }

    /** `snoozePresets` → `{ nowMs, presets: [{ id, until, countdown }] }`; malformed entries are skipped. */
    fun presets(json: JSONObject): List<SnoozePreset> {
        val array = json.optJSONArray("presets") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("id") ?: return@mapNotNull null
            SnoozePreset(id, item.millis("until") ?: return@mapNotNull null, item.text("countdown").orEmpty())
        }
    }

    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }
    private fun JSONObject.millis(key: String): Long? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }?.toLong()
}
