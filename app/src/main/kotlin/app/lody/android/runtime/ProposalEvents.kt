package app.lody.android.runtime

import org.json.JSONObject

/**
 * An Agent's proposal carried by a `system_notice` (web `AgentRoleProposalNotice` /
 * `ScheduleProposalNotice` / `ScheduleEditProposalNotice`), as the shared runtime presents it
 * (`protocol/proposals.ts`). [kind] is `agent_role`, `schedule` or `schedule_edit`; [outcome]
 * `saved` / `created` / `applied` / `dismissed` once decided.
 */
data class ProposalCard(
    /** The history entry and item the decision is recorded on. */
    val entryId: String,
    val itemId: String,
    val kind: String,
    val outcome: String? = null,
    // Agent Role
    val action: String? = null,
    val requesterUserId: String? = null,
    val fields: RoleProposalFields = RoleProposalFields(),
    val before: RoleProposalFields? = null,
    // Schedule
    val title: String? = null,
    val prompt: String? = null,
    val rule: ScheduleRule? = null,
    val destination: String? = null,
    val scheduleId: String? = null,
    /** `schedule_edit`: only these fields change, and only against the revision the Agent read. */
    val patch: ScheduleEditPatch? = null,
) {
    val isRole: Boolean get() = kind == "agent_role"
    val isEdit: Boolean get() = kind == "schedule_edit"
    val decided: Boolean get() = outcome != null
}

data class ScheduleEditPatch(val title: String? = null, val prompt: String? = null, val rule: ScheduleRule? = null)

/** One line of web's `diffPromptLines`: `same`, `add`, `del` or a folded `gap`. */
data class PromptDiffLine(val kind: String, val text: String)

/**
 * What an undecided edit proposal would change in the live schedule (runtime `inspectProposal`).
 * [state] is `ready`, `unavailable`, `stale`, `not_owner`, `machine_unsupported` or
 * `target_unavailable`; only `ready` applies, and only the owner may dismiss a live schedule's.
 */
data class ProposalInspection(
    val state: String,
    val isOwner: Boolean,
    val currentTitle: String? = null,
    val currentRule: ScheduleRule? = null,
    val timeZone: String,
    val promptDiff: List<PromptDiffLine>? = null,
) {
    companion object {
        fun parse(json: JSONObject): ProposalInspection = ProposalInspection(
            state = json.getString("state"),
            isOwner = json.optBoolean("isOwner"),
            currentTitle = json.opt("currentTitle") as? String,
            currentRule = json.optJSONObject("currentRule")?.let { runCatching { ScheduleJson.rule(it) }.getOrNull() },
            timeZone = json.optString("timeZone"),
            promptDiff = json.optJSONArray("promptDiff")?.let { lines ->
                (0 until lines.length()).mapNotNull { lines.optJSONObject(it) }.mapNotNull { line ->
                    line.optString("kind").takeIf { it in setOf("same", "add", "del", "gap") }?.let { PromptDiffLine(it, line.optString("text")) }
                }
            },
        )
    }
}

data class RoleProposalFields(val name: String? = null, val description: String? = null, val promptPrefix: String? = null)

object ProposalEvents {
    /** The proposal a `system_notice` item carries, or null for any other item. */
    fun parse(entryId: String, item: JSONObject): ProposalCard? {
        if (item.optString("type") != "system_notice") return null
        val itemId = item.optString("itemId").ifEmpty { return null }
        val value = item.optJSONObject("proposal") ?: return null
        val kind = value.optString("kind").takeIf { it in setOf("agent_role", "schedule", "schedule_edit") } ?: return null
        val outcome = value.text("outcome")?.takeIf { it in setOf("saved", "created", "applied", "dismissed") }
        if (kind == "schedule_edit") {
            val patch = value.optJSONObject("patch") ?: return null
            return ProposalCard(
                entryId, itemId, kind, outcome,
                title = value.text("title") ?: return null,
                scheduleId = value.text("scheduleId") ?: return null,
                patch = ScheduleEditPatch(
                    patch.text("title"), patch.text("prompt"),
                    patch.optJSONObject("rule")?.let { rule -> runCatching { ScheduleJson.rule(rule) }.getOrNull() },
                ),
            )
        }
        return if (kind == "agent_role") ProposalCard(
            entryId, itemId, kind, outcome,
            action = value.text("action")?.takeIf { it == "create" || it == "update" } ?: return null,
            requesterUserId = value.text("requesterUserId") ?: return null,
            fields = fields(value.optJSONObject("fields")) ?: return null,
            before = fields(value.optJSONObject("before")),
        ) else ProposalCard(
            entryId, itemId, kind, outcome,
            title = value.text("title") ?: return null,
            prompt = value.text("prompt"),
            rule = value.optJSONObject("rule")?.let { rule -> runCatching { ScheduleJson.rule(rule) }.getOrNull() },
            destination = value.text("destination"),
            scheduleId = value.text("scheduleId"),
        )
    }

    private fun fields(json: JSONObject?): RoleProposalFields? = json?.let {
        RoleProposalFields(it.text("name"), it.text("description"), it.text("promptPrefix"))
    }

    private fun JSONObject.text(key: String): String? = (opt(key) as? String)
}
