package app.lody.android.chat

import app.lody.android.R
import app.lody.android.runtime.SessionMessage
import app.lody.android.runtime.SystemNotice

/**
 * `chat_failed` copy and recovery, as iOS `ChatFailure` / `SessionEnvelope.latestRetryableError`
 * port them from web `ChatFailedNoticeView`: a title for each known reason, a short guidance line
 * for the reasons with one, and Retry only on the latest failure whose reason a new turn can
 * recover from.
 */
object ChatFailures {
    private val Titles = mapOf(
        "session_archived" to R.string.chat_error_session_archived,
        "agent_type_mismatch" to R.string.chat_error_agent_type_mismatch,
        "session_init_failed" to R.string.chat_error_session_init_failed,
        "session_restore_failed" to R.string.chat_error_session_restore_failed,
        "session_not_found" to R.string.chat_error_session_not_found,
        "memory_pressure" to R.string.chat_error_memory_pressure,
        "acp_not_ready" to R.string.chat_error_acp_not_ready,
        "agent_disconnected" to R.string.chat_error_agent_disconnected,
        "agent_no_output" to R.string.chat_error_agent_no_output,
        "turn_pre_prompt_failed" to R.string.chat_error_turn_pre_prompt_failed,
        "message_delivery_failed" to R.string.chat_error_message_delivery_failed,
        "machine_access_denied" to R.string.chat_error_machine_access_denied,
        "acp_auth_required" to R.string.chat_error_acp_auth_required,
        "acp_internal_error" to R.string.chat_error_acp_internal_error,
        "acp_upstream_api_error" to R.string.chat_error_acp_upstream_api_error,
        "acp_provider_overloaded" to R.string.chat_error_acp_provider_overloaded,
        "acp_session_storage_incompatible" to R.string.chat_error_acp_session_storage_incompatible,
        "acp_resource_not_found" to R.string.chat_error_acp_resource_not_found,
        "acp_request_cancelled" to R.string.chat_error_acp_request_cancelled,
        "acp_method_not_found" to R.string.chat_error_acp_method_not_found,
        "acp_invalid_params" to R.string.chat_error_acp_invalid_params,
        "acp_invalid_request" to R.string.chat_error_acp_invalid_request,
        "acp_parse_error" to R.string.chat_error_acp_parse_error,
        "acp_unknown_error" to R.string.chat_error_acp_unknown_error,
    )
    private val Summaries = mapOf(
        "acp_provider_overloaded" to R.string.chat_error_capacity_summary,
        "acp_auth_required" to R.string.chat_error_auth_summary,
        "machine_access_denied" to R.string.chat_error_access_summary,
    )
    /** Reasons a new turn can recover from (iOS `SessionEnvelope.recoverable`). */
    val Recoverable = setOf(
        "acp_provider_overloaded", "acp_upstream_api_error", "acp_internal_error", "acp_unknown_error",
        "agent_disconnected", "agent_no_output", "acp_not_ready",
    )
    private const val GitMissing = "git_executable_not_found"

    const val CapacityContinuation =
        "Continue working from where you left off. The previous turn stopped because the selected model was at capacity."
    const val ErrorContinuation =
        "Continue working from where you left off. The previous turn stopped because of an agent error."

    fun title(reason: String?, code: String?): Int =
        if (code == GitMissing) R.string.chat_error_git_executable_not_found else Titles[reason] ?: R.string.chat_error_unknown

    fun summary(reason: String?, code: String?): Int? =
        if (code == GitMissing) R.string.chat_error_git_summary else Summaries[reason]

    /** The raw message as a one-line summary, unless it is a JSON payload (that stays in the detail). */
    fun message(raw: String?): String? = raw?.trim()?.lineSequence()?.firstOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith("{") && !it.startsWith("[") }

    /**
     * Web `supportsAuthenticationWhenRequired` and `machineSupportsAcpProtocolAuthentication`:
     * managed builtins sign in through Lody's own flow; ACP-authenticated agents need protocol 2.
     */
    fun canAuthenticate(cliType: String?, agentType: String?, agentConfigId: String?, capabilities: Map<String, Int>): Boolean {
        if (agentConfigId.isNullOrEmpty()) return false
        val acp = cliType == "registry" || cliType == "custom" || (cliType == "builtin" && agentType in setOf("devin", "sorbet"))
        val managed = cliType == "builtin" && agentType in setOf("antigravity", "kimi", "devin", "grok", "claude", "codex")
        if (!acp && !managed) return false
        return !acp || (capabilities["acpProtocolAuthentication"] ?: 0) >= 2
    }

    /** The latest failure Retry may continue: nothing once a later user turn exists. */
    fun retryable(messages: List<SessionMessage>): SystemNotice.Failure? {
        for (message in messages.asReversed()) {
            if (message.role == "user") return null
            val failure = message.notices.lastOrNull { it is SystemNotice.Failure } as? SystemNotice.Failure ?: continue
            return failure.takeIf { it.reason in Recoverable && it.code != GitMissing }
        }
        return null
    }
}
