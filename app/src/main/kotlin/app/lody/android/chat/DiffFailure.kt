package app.lody.android.chat

import androidx.annotation.StringRes
import app.lody.android.R

/**
 * The computer sends an in-band reason for a diff it could not build, and each one means
 * something different to the reader: the file stopped differing, there is no baseline, a read
 * failed, the turn's evidence is not ready, or the file has no tracked version. Only an unknown
 * reason is a connection problem. Mirrors iOS `FileDiffController.reasons`.
 */
@StringRes
internal fun diffUnavailableMessage(reason: String?): Int = when (reason) {
    "not_changed" -> R.string.chat_diff_not_changed
    "base_unavailable" -> R.string.chat_diff_no_base
    "transient_io" -> R.string.chat_diff_read_failed
    "turn_unavailable" -> R.string.chat_diff_turn_unavailable
    "unsupported_binary" -> R.string.chat_diff_binary
    "not_tracked" -> R.string.chat_diff_not_tracked
    else -> R.string.chat_diff_unavailable
}

/** A binary or untracked file's "diff" cannot succeed no matter how often it is retried. */
internal fun diffUnavailableRetryable(reason: String?): Boolean = reason != "unsupported_binary" && reason != "not_tracked"

/** The list has one actionable reason; everything else is a load/network failure. */
@StringRes
internal fun workspaceUnavailableMessage(reason: String?): Int = when (reason) {
    "base_unavailable" -> R.string.chat_diff_no_base
    "not_a_git_worktree" -> R.string.chat_diff_scope_not_git
    "no_commits" -> R.string.chat_diff_scope_no_commits
    else -> R.string.chat_diff_workspace_unavailable
}

/** One debug-only log line: which session and path failed, and the reason or runtime fault. */
internal fun diffFailureLog(sessionId: String, path: String?, reason: String?, error: Throwable? = null): String = buildString {
    append("session=").append(sessionId)
    path?.let { append(" path=").append(it) }
    reason?.let { append(" reason=").append(it) }
    error?.let { append(" error=").append(it.message ?: it::class.java.simpleName) }
}
