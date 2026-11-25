package app.lody.android.runtime

import org.json.JSONObject

/**
 * Web's system rows other than operations and proposals (`SystemNoticeView`,
 * `WorktreeScriptNoticeView`), as the shared runtime summarizes them. Unknown notice names,
 * `task_proposal` and malformed items are not rows, as on web.
 */
sealed interface SystemNotice {
    val itemId: String

    /** `chat_failed`: the turn could not run; [message] is the raw diagnostic. */
    data class Failure(override val itemId: String, val reason: String?, val code: String?, val message: String?) : SystemNotice
    /** `agent_warning`: [source] names the Lody path that raised it (stop failures, interrupted work). */
    data class Warning(override val itemId: String, val source: String?, val message: String) : SystemNotice
    /** `session_fork_origin`: the conversation this one was forked from. */
    data class ForkOrigin(override val itemId: String, val sourceSessionId: String, val sourceTitle: String) : SystemNotice
    /** `resume_from_external_chat_history`, optionally cut to fit. */
    data class ResumedHistory(override val itemId: String, val truncated: Boolean) : SystemNotice
    /** `worktree_script`: [phase] `setup` / `cleanup`, [status] `in_progress` / `completed` / `failed`. */
    data class WorktreeScript(override val itemId: String, val phase: String, val status: String, val steps: List<ScriptStep>) : SystemNotice

    data class ScriptStep(val command: String, val status: String, val output: String)
}

object SystemNoticeEvents {
    private val Statuses = setOf("in_progress", "completed", "failed")

    fun parse(item: JSONObject): SystemNotice? {
        val itemId = item.optString("itemId").ifEmpty { return null }
        if (item.optString("type") == "worktree_script") {
            val phase = item.optString("phase").takeIf { it == "setup" || it == "cleanup" } ?: return null
            val status = item.optString("status").takeIf { it in Statuses } ?: return null
            val steps = item.optJSONArray("steps")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { step ->
                    val command = step.opt("command") as? String ?: return@mapNotNull null
                    val state = step.optString("status").takeIf { it in Statuses } ?: return@mapNotNull null
                    SystemNotice.ScriptStep(command, state, step.optString("output"))
                }
            }.orEmpty()
            return SystemNotice.WorktreeScript(itemId, phase, status, steps)
        }
        if (item.optString("type") != "system_notice" || item.has("proposal")) return null
        val meta = item.optJSONObject("meta") ?: JSONObject()
        fun text(key: String) = (meta.opt(key) as? String)?.takeIf { it.isNotBlank() }
        return when (item.optString("name")) {
            "chat_failed" -> SystemNotice.Failure(itemId, text("reason"), text("code"), text("message"))
            "agent_warning" -> text("message")?.let { SystemNotice.Warning(itemId, text("source"), it.trim()) }
            "session_fork_origin" -> {
                val id = text("sourceSessionId") ?: return null
                SystemNotice.ForkOrigin(itemId, id, text("sourceTitle") ?: return null)
            }
            "resume_from_external_chat_history" -> SystemNotice.ResumedHistory(itemId, meta.optBoolean("truncated"))
            else -> null
        }
    }
}
