package app.lody.android.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import app.lody.android.R
import app.lody.android.designsystem.*
import app.lody.android.home.AgentMark
import app.lody.android.runtime.CatalogSnapshot
import app.lody.android.runtime.ScheduleItem

internal val scheduleGroupOrder = listOf("attention", "running", "paused", "ended")

@Composable
private fun tone(group: String) = when (group) {
    "running" -> LodyTheme.colors.accentDefault
    "attention" -> LodyTheme.colors.warningDefault
    else -> LodyTheme.colors.iconSecondary
}

@Composable
private fun toneFill(group: String) = when (group) {
    "running" -> LodyTheme.colors.accentSubtle
    "attention" -> LodyTheme.colors.warningSubtle
    else -> LodyTheme.colors.fillSecondary
}

private fun stateIcon(group: String) = when (group) {
    "attention" -> LodyIcons.Alert
    "paused" -> LodyIcons.Pause
    "ended" -> LodyIcons.Check
    else -> LodyIcons.Clock
}

@Composable
internal fun ScheduleMark(item: ScheduleItem) {
    Box(Modifier.size(LodySize.controlMd)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(LodyRadius.lg)).background(toneFill(item.state.group)), contentAlignment = Alignment.Center) {
            AgentMark(null, item.agentType, LodyTheme.colors.iconPrimary, Modifier.size(LodyIconSize.md))
        }
        Box(
            Modifier.align(Alignment.BottomEnd).offset(LodySpacing.xs, LodySpacing.xs)
                .size(LodySize.badge).clip(CircleShape).background(tone(item.state.group))
                .border(LodyStroke.focus, LodyTheme.colors.bgRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(stateIcon(item.state.group), LodyTheme.colors.textOnAccent, LodyIconSize.xs) }
    }
}

/** Stored status stays separate from the editable draft. All writes retain their existing gates. */
@Composable
internal fun ScheduleHero(item: ScheduleItem, busy: Boolean, onCommand: (String) -> Unit, onOpenSession: (String) -> Unit) {
    val colors = LodyTheme.colors
    val status = statusText(item.state)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(LodyRadius.xl)).background(colors.bgRaised).padding(LodySpacing.lg),
        verticalArrangement = Arrangement.spacedBy(LodySpacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LodySpacing.md), verticalAlignment = Alignment.CenterVertically) {
            ScheduleMark(item)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                Text(status.label, style = LodyTheme.typography.headline, color = if (item.state.group == "attention") colors.warningDefault else colors.textPrimary)
                status.detail?.let { Text(it, style = LodyTheme.typography.footnote, color = colors.textSecondary) }
                Text(ruleSummary(item.rule), style = LodyTheme.typography.footnote, color = colors.textSecondary)
            }
        }
        item.machineName?.let { Text(it, style = LodyTheme.typography.caption, color = colors.textSecondary) }
        item.lastRunAt?.let { Text(stringResource(R.string.schedules_status_last_ran, moment(it)), style = LodyTheme.typography.caption, color = colors.textSecondary) }
        FlowActions(item, busy, onCommand, onOpenSession)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowActions(item: ScheduleItem, busy: Boolean, onCommand: (String) -> Unit, onOpenSession: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
        if (item.isOwner && !item.scheduledMessage) {
            LodyTextButton(onClick = { onCommand("run") }, enabled = !busy && item.machineSupported) {
                Glyph(LodyIcons.Play, LodyTheme.colors.accentFg, LodyIconSize.sm)
                Spacer(Modifier.width(LodySpacing.xs))
                Text(stringResource(R.string.schedules_run_now))
            }
            if (item.rule.kind != "manual" && item.state.group != "ended") {
                LodyTextButton(onClick = { onCommand(if (item.enabled) "pause" else "resume") }, enabled = !busy && (item.enabled || item.machineSupported)) {
                    Glyph(if (item.enabled) LodyIcons.Pause else LodyIcons.Play, LodyTheme.colors.accentFg, LodyIconSize.sm)
                    Spacer(Modifier.width(LodySpacing.xs))
                    Text(stringResource(if (item.enabled) R.string.schedules_pause else R.string.schedules_resume))
                }
            }
        }
        item.lastSessionId?.let { session ->
            LodyTextButton(onClick = { onOpenSession(session) }) {
                Glyph(LodyIcons.Session, LodyTheme.colors.accentFg, LodyIconSize.sm)
                Spacer(Modifier.width(LodySpacing.xs))
                Text(stringResource(R.string.schedules_open_last_chat))
            }
        }
    }
}

/** Confirmation before manually running a schedule whose bound chat is busy or whose status is unknown. */
@Composable
internal fun RunNowConfirmDialog(
    decision: ManualRunCheck,
    snapshot: CatalogSnapshot?,
    detail: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (item, sessionId, isBusy) = when (decision) {
        is ManualRunCheck.ConfirmBusy -> Triple(decision.item, decision.sessionId, true)
        is ManualRunCheck.ConfirmUnknown -> Triple(decision.item, decision.sessionId, false)
        ManualRunCheck.RunImmediately -> return
    }
    val colors = LodyTheme.colors
    val chatTitle = snapshot?.sessions?.firstOrNull { it.id == sessionId }?.title?.trim()
        ?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.schedules_destination_untitled_chat)
    val scheduleTitle = item.title.ifBlank { stringResource(R.string.schedules_untitled) }
    val titleRes = if (isBusy) R.string.schedules_run_now_busy_title else R.string.schedules_run_now_status_unknown_title
    val bodyRes = when {
        isBusy && detail -> R.string.schedules_run_now_busy_detail_help
        isBusy -> R.string.schedules_run_now_busy_help
        detail -> R.string.schedules_run_now_status_unknown_detail_help
        else -> R.string.schedules_run_now_status_unknown_help
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgOverlay,
        title = { Text(stringResource(titleRes, chatTitle), style = LodyTheme.typography.headline, color = colors.textPrimary) },
        text = { Text(stringResource(bodyRes, scheduleTitle), style = LodyTheme.typography.body, color = colors.textSecondary) },
        confirmButton = { LodyTextButton(onClick = onConfirm) { Text(stringResource(R.string.schedules_run_now_confirm), color = colors.accentFg) } },
        dismissButton = { LodyTextButton(onClick = onDismiss) { Text(stringResource(R.string.schedules_cancel), color = colors.textPrimary) } },
    )
}
