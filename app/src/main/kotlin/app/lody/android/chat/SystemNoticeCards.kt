package app.lody.android.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lody.android.R
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodyLoadingIndicator
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyTheme
import app.lody.android.runtime.SystemNotice

/**
 * Web's system rows (`SystemNoticeView`, `AgentWarningNoticeBody`, `WorktreeScriptNoticeView`) as
 * quiet one-line rows: a failure in the danger tone, everything else in secondary ink (no warning
 * colour in a conversation). A warning, failure or script opens its detail in place; a fork
 * origin opens the conversation it came from through [navigate].
 */
/**
 * What a failure row may offer (iOS `ChatErrorCell`): Retry on the latest recoverable failure
 * ([retryItemId]), and Sign in on an authentication failure when the session's agent can start
 * the machine's sign-in flow ([signIn]).
 */
class FailureActions(
    val retryItemId: String? = null,
    val retryEnabled: Boolean = false,
    val retrying: Boolean = false,
    val retry: () -> Unit = {},
    val signIn: (() -> Unit)? = null,
)

@Composable
internal fun SystemNoticeCards(notices: List<SystemNotice>, navigate: (String) -> Unit, modifier: Modifier = Modifier, failures: FailureActions? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = TranscriptMargin), verticalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
        notices.forEach { notice ->
            when (notice) {
                is SystemNotice.Failure -> {
                    val retry = failures?.takeIf { it.retryItemId == notice.itemId }
                    val signIn = failures?.signIn?.takeIf { notice.reason == "acp_auth_required" }
                    NoticeRow(
                        LodyIcons.Alert, stringResource(ChatFailures.title(notice.reason, notice.code)), danger = notice.reason != "acp_provider_overloaded",
                        summary = ChatFailures.summary(notice.reason, notice.code)?.let { stringResource(it) } ?: ChatFailures.message(notice.message)
                            ?: stringResource(R.string.chat_error_summary),
                        detail = listOfNotNull(notice.reason, notice.code, notice.message).joinToString("\n\n").ifEmpty { null },
                        id = notice.itemId,
                        actions = if (retry == null && signIn == null) null else ({
                            Row(horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                                if (retry != null) app.lody.android.designsystem.LodyTextButton(onClick = retry.retry, enabled = retry.retryEnabled && !retry.retrying) {
                                    Text(stringResource(if (retry.retrying) R.string.chat_error_retrying else R.string.chat_error_retry))
                                }
                                if (signIn != null) app.lody.android.designsystem.LodyTextButton(onClick = signIn) {
                                    Text(stringResource(R.string.chat_error_sign_in))
                                }
                            }
                        }),
                    )
                }
                is SystemNotice.Warning -> {
                    val (label, guidance) = warningCopy(notice.source)
                    NoticeRow(
                        LodyIcons.Alert, label, summary = guidance ?: notice.message.lineSequence().first(),
                        detail = guidance?.let { "$it\n\n${notice.message}" } ?: notice.message, id = notice.itemId,
                    )
                }
                is SystemNotice.ForkOrigin -> NoticeRow(
                    LodyIcons.Branch, stringResource(R.string.chat_notice_fork_origin, notice.sourceTitle), id = notice.itemId,
                    onClick = { navigate(notice.sourceSessionId) },
                )
                is SystemNotice.ResumedHistory -> NoticeRow(
                    LodyIcons.Refresh,
                    stringResource(R.string.chat_notice_resume_history) +
                        if (notice.truncated) " " + stringResource(R.string.chat_notice_resume_history_truncated) else "",
                    id = notice.itemId,
                )
                is SystemNotice.WorktreeScript -> {
                    val phase = stringResource(if (notice.phase == "cleanup") R.string.chat_worktree_cleanup else R.string.chat_worktree_setup)
                    val waiting = stringResource(R.string.chat_worktree_waiting)
                    NoticeRow(
                        LodyIcons.Terminal,
                        if (notice.status == "failed") stringResource(R.string.chat_worktree_failed, phase) else phase,
                        danger = notice.status == "failed", running = notice.status == "in_progress", id = notice.itemId,
                        detail = notice.steps.joinToString("\n\n") { step ->
                            "$ ${step.command}\n" + step.output.trim().ifEmpty { if (step.status == "in_progress") waiting else "" }
                        }.ifEmpty { null },
                        // Web opens a running or failed script; a clean one collapses.
                        initiallyOpen = notice.status == "failed",
                    )
                }
            }
        }
    }
}

@Composable
private fun warningCopy(source: String?): Pair<String, String?> = when (source) {
    "sessionCancelFailed" -> stringResource(R.string.chat_warning_stop_request_failed) to stringResource(R.string.chat_warning_stop_request_failed_guidance)
    "sessionStopTimedOut" -> stringResource(R.string.chat_warning_stop_timed_out) to stringResource(R.string.chat_warning_stop_timed_out_guidance)
    "sessionTerminationFailed" -> stringResource(R.string.chat_warning_stop_termination_failed) to stringResource(R.string.chat_warning_stop_termination_failed_guidance)
    "nativeWorkInterrupted" -> stringResource(R.string.chat_warning_native_work_interrupted) to stringResource(R.string.chat_warning_native_work_interrupted_guidance)
    else -> stringResource(R.string.chat_warning_title) to null
}

@Composable
private fun NoticeRow(
    icon: ImageVector,
    title: String,
    id: String,
    summary: String? = null,
    detail: String? = null,
    danger: Boolean = false,
    running: Boolean = false,
    initiallyOpen: Boolean = false,
    onClick: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = LodyTheme.colors
    var open by rememberSaveable(id) { mutableStateOf(initiallyOpen) }
    val tint = if (danger) colors.dangerDefault else colors.iconTertiary
    val action = onClick ?: detail?.let { { open = !open } }
    Column(
        Modifier.fillMaxWidth()
            .then(if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier)
            .padding(vertical = LodySpacing.xs),
        verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
            if (running) LodyLoadingIndicator(Modifier.padding(top = 2.dp).size(LodyIconSize.sm), color = colors.iconTertiary)
            else Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(LodyIconSize.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
                Text(title, style = LodyTheme.typography.subhead, color = if (danger) colors.dangerDefault else colors.textSecondary)
                summary?.takeIf { it.isNotBlank() && !open }?.let {
                    Text(it, style = LodyTheme.typography.footnote, color = colors.textSecondary, maxLines = 2)
                }
                if (open && detail != null) SelectionContainer {
                    Text(detail, style = LodyTheme.typography.codeSmall, color = colors.textSecondary)
                }
                actions?.invoke()
            }
        }
    }
}
