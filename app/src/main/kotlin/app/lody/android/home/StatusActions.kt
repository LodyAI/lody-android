package app.lody.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.lody.android.R
import app.lody.android.designsystem.*
import app.lody.android.runtime.SnoozePreset
import app.lody.android.runtime.StatusTier
import app.lody.android.runtime.StatusViewCommand
import app.lody.android.runtime.StatusViewScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Status view writes for Home and the conversation page; the runtime plans and writes each one. */
interface StatusViewService {
    /** Returns the writes made; 0 means the runtime refused it or nothing changed. */
    suspend fun run(command: StatusViewCommand, list: StatusViewScope?): Int
    suspend fun presets(): List<SnoozePreset>
}

/**
 * The conversation a status action applies to: its tier now and its opened sub-conversations at
 * any depth and tier ([opened], [working] of them running), for the "apply to opened
 * sub-conversations too?" question and for an Undo that restores each session as it was.
 */
data class StatusTarget(val id: String, val tier: StatusTier?, val opened: List<String> = emptyList(), val working: Int = 0) {
    val children: Int get() = opened.size
}

/** Runs status actions with their sheet, question and Undo notice; see [StatusActionHost]. */
@Stable
class StatusActions internal constructor(private val start: (Pending) -> Unit, val run: (StatusViewCommand, String?) -> Unit) {
    internal enum class Kind { Later, Settle, Focus }
    internal data class Pending(val kind: Kind, val target: StatusTarget, val until: Long? = null)

    fun later(target: StatusTarget) = start(Pending(Kind.Later, target))
    fun settle(target: StatusTarget) = start(Pending(Kind.Settle, target))
    fun focus(target: StatusTarget) = start(Pending(Kind.Focus, target))
}

/**
 * Hosts the Later bottom sheet, the sub-conversation question and the result notices. Settle and
 * Later confirm with Undo, which restores the previous tier; a failed write says so and is never
 * retried.
 */
@Composable
fun rememberStatusActions(
    service: StatusViewService?,
    snackbar: SnackbarHostState,
    list: StatusViewScope?,
    /** The catalog sessions as they are now: Undo reads each session's state before the action. */
    sessions: () -> List<app.lody.android.runtime.CatalogSession> = { emptyList() },
): StatusActions? {
    if (service == null) return null
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<StatusActions.Pending?>(null) }
    var asking by remember { mutableStateOf<StatusActions.Pending?>(null) }
    val failed = stringResource(R.string.status_write_failed)
    val undo = stringResource(R.string.status_undo)
    val done = mapOf(
        StatusActions.Kind.Later to stringResource(R.string.status_snoozed_done),
        StatusActions.Kind.Settle to stringResource(R.string.status_settled_done),
        StatusActions.Kind.Focus to stringResource(R.string.status_focused_done),
    )
    // An Undo restores session by session; a command that changes nothing is no failure here.
    val undoAll: (List<StatusViewCommand>) -> Unit = { commands ->
        scope.launch {
            val ok = try { commands.forEach { service.run(it, list) }; true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!ok) snackbar.showSnackbar(failed)
        }
    }
    val run: (StatusViewCommand, String?) -> Unit = { command, notice ->
        scope.launch {
            val ok = try { service.run(command, list) > 0 }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!ok) snackbar.showSnackbar(failed) else if (notice != null) snackbar.showSnackbar(notice)
        }
    }
    fun execute(pending: StatusActions.Pending, includeOpened: Boolean) {
        val target = pending.target
        val command = when (pending.kind) {
            StatusActions.Kind.Later -> StatusViewCommand.Snooze(target.id, pending.until ?: return, includeOpened)
            StatusActions.Kind.Settle -> StatusViewCommand.Settle(target.id, includeOpened)
            StatusActions.Kind.Focus -> StatusViewCommand.MoveToFocus(target.id, includeOpened)
        }
        // Undo puts every affected session back as it was, each on its own, read before the write.
        val restore = if (pending.kind == StatusActions.Kind.Focus) emptyList() else ChatListModel.restore(
            sessions(), listOf(target.id) + if (includeOpened) target.opened else emptyList(), target.tier, app.lody.android.runtime.StatusClock.now(),
        )
        scope.launch {
            // Zero writes is a refusal (or nothing to change): the row stays where it was.
            val ok = try { service.run(command, list) > 0 }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!ok) { snackbar.showSnackbar(failed); return@launch }
            val result = snackbar.showSnackbar(done.getValue(pending.kind), actionLabel = undo.takeIf { restore.isNotEmpty() }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undoAll(restore)
        }
    }
    val proceed = { pending: StatusActions.Pending -> if (pending.target.children > 0) asking = pending else execute(pending, false) }
    val actions = remember(service, list) {
        StatusActions(start = { pending -> if (pending.kind == StatusActions.Kind.Later) sheet = pending else proceed(pending) }, run = run)
    }
    sheet?.let { pending ->
        LaterSheet(service, onDismiss = { sheet = null }) { until -> sheet = null; proceed(pending.copy(until = until)) }
    }
    asking?.let { pending -> FollowDialog(pending, onDismiss = { asking = null }) { include -> asking = null; execute(pending, include) } }
    return actions
}

@Composable
private fun FollowDialog(pending: StatusActions.Pending, onDismiss: () -> Unit, onChoose: (Boolean) -> Unit) {
    val (title, only, all) = when (pending.kind) {
        StatusActions.Kind.Later -> Triple(R.string.status_sub_snooze_title, R.string.status_sub_snooze_only, R.string.status_sub_snooze_all)
        StatusActions.Kind.Settle -> Triple(R.string.status_sub_settle_title, R.string.status_sub_settle_only, R.string.status_sub_settle_all)
        StatusActions.Kind.Focus -> Triple(R.string.status_sub_focus_title, R.string.status_sub_focus_only, R.string.status_sub_focus_all)
    }
    val target = pending.target
    val working = if (target.working > 0) pluralStringResource(R.plurals.status_sub_working, target.working, target.working) else stringResource(R.string.status_sub_idle)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LodyTheme.colors.bgOverlay,
        title = { Text(stringResource(title)) },
        text = { Text(pluralStringResource(R.plurals.status_sub_description, target.children, target.children) + " " + working) },
        confirmButton = { LodyTextButton(onClick = { onChoose(true) }) { Text(stringResource(all)) } },
        dismissButton = { LodyTextButton(onClick = { onChoose(false) }) { Text(stringResource(only)) } },
    )
}

/**
 * The Later sheet: the runtime's four presets, each with its resolved time, then a custom date and
 * time inside the same sheet. A time that is not in the future is refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaterSheet(service: StatusViewService, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val colors = LodyTheme.colors
    val context = LocalContext.current
    var presets by remember { mutableStateOf<List<SnoozePreset>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        presets = try { service.presets() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { unavailable = true; emptyList() }
    }
    val zone = ZoneId.systemDefault()
    val format = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.bgBase) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = LodySpacing.lg)) {
            Text(stringResource(R.string.status_later), style = LodyTheme.typography.headline, color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = LodyLayout.screenPaddingX, vertical = LodySpacing.sm))
            if (unavailable) Text(stringResource(R.string.status_later_presets_unavailable), style = LodyTheme.typography.footnote, color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = LodyLayout.screenPaddingX))
            presets.orEmpty().forEach { preset ->
                val label = presetLabel(preset.id) ?: return@forEach
                SheetRow(label, Instant.ofEpochMilli(preset.until).atZone(zone).format(format)) { onPick(preset.until) }
            }
            if (presets == null) Box(Modifier.fillMaxWidth().padding(LodySpacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(LodyIconSize.md)) }
            SheetRow(stringResource(R.string.status_preset_custom), null) { custom = !custom }
            if (custom) CustomTime(android.text.format.DateFormat.is24HourFormat(context), zone, onPick)
        }
    }
}

@Composable
private fun presetLabel(id: String): String? = when (id) {
    "oneHour" -> stringResource(R.string.status_preset_one_hour)
    "laterToday" -> stringResource(R.string.status_preset_later_today)
    "tomorrowMorning" -> stringResource(R.string.status_preset_tomorrow)
    "nextWeek" -> stringResource(R.string.status_preset_next_week)
    else -> null
}

@Composable
private fun SheetRow(title: String, detail: String?, onClick: () -> Unit) {
    val colors = LodyTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = LodySize.listRow).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LodyLayout.screenPaddingX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = LodyTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = LodyTheme.typography.footnote, color = colors.textTertiary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomTime(is24Hour: Boolean, zone: ZoneId, onPick: (Long) -> Unit) {
    val colors = LodyTheme.colors
    val start = remember { Instant.ofEpochMilli(app.lody.android.runtime.StatusClock.now()).atZone(zone).toLocalDateTime().plusHours(1).withMinute(0) }
    // The date picker works in UTC midnights; the wall time is read in the device zone.
    val date = rememberDatePickerState(initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val time = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = is24Hour)
    val day = date.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val chosen = day?.atTime(time.hour, time.minute)?.atZone(zone)?.toInstant()?.toEpochMilli()
    // Past times are refused on the server clock the runtime plans with.
    val future = chosen != null && chosen > app.lody.android.runtime.StatusClock.now()
    Column(Modifier.fillMaxWidth().padding(horizontal = LodySpacing.sm)) {
        Text(stringResource(R.string.status_later_pick), style = LodyTheme.typography.subhead, color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = LodySpacing.md, vertical = LodySpacing.xs))
        DatePicker(date, title = null, headline = null, showModeToggle = false)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(time) }
        if (chosen != null && !future) Text(stringResource(R.string.status_later_future), style = LodyTheme.typography.footnote, color = colors.dangerDefault,
            modifier = Modifier.padding(horizontal = LodySpacing.md))
        Row(Modifier.fillMaxWidth().padding(LodySpacing.sm), horizontalArrangement = Arrangement.End) {
            LodyTextButton(onClick = { if (future) onPick(chosen!!) }, enabled = future) { Text(stringResource(R.string.status_later_confirm)) }
        }
    }
}

/** A section name prompt (New section, Rename); blank names are not accepted. */
@Composable
internal fun SectionNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LodyTheme.colors.bgOverlay,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, placeholder = { Text(stringResource(R.string.status_section_name)) })
        },
        confirmButton = { LodyTextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.status_section_save)) } },
        dismissButton = { LodyTextButton(onClick = onDismiss) { Text(stringResource(R.string.status_cancel)) } },
    )
}

@Composable
internal fun DeleteSectionDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LodyTheme.colors.bgOverlay,
        title = { Text(name) },
        text = { Text(stringResource(R.string.status_section_delete_message)) },
        confirmButton = { LodyTextButton(onClick = onDelete) { Text(stringResource(R.string.status_section_delete), color = LodyTheme.colors.dangerDefault) } },
        dismissButton = { LodyTextButton(onClick = onDismiss) { Text(stringResource(R.string.status_cancel)) } },
    )
}

/** A Later countdown's unit and amount (`<1m` is [Soon]); null once the wake-up has passed. */
internal sealed interface Countdown {
    data object Soon : Countdown
    data class Minutes(val value: Int) : Countdown
    data class Hours(val value: Int) : Countdown
    data class Days(val value: Int) : Countdown

    companion object {
        /** Shared `formatSnoozeCountdown`, rule for rule: minutes and hours round up, days to nearest. */
        fun of(until: Long, now: Long): Countdown? {
            val left = (until - now).toDouble()
            return when {
                left <= 0 -> null
                left < MINUTE -> Soon
                left < 59.5 * MINUTE -> Minutes(kotlin.math.ceil(left / MINUTE).toInt())
                left < 23.5 * HOUR -> Hours(kotlin.math.ceil(left / HOUR).toInt())
                else -> Days(maxOf(1, kotlin.math.round(left / DAY).toInt()))
            }
        }
    }
}

/** The compact Later countdown (`3d`, `2h`, `45m`, `<1m`), as the desktop row shows it. */
@Composable
internal fun countdownText(until: Long, now: Long): String = when (val left = Countdown.of(until, now)) {
    is Countdown.Days -> stringResource(R.string.status_countdown_days, left.value)
    is Countdown.Hours -> stringResource(R.string.status_countdown_hours, left.value)
    is Countdown.Minutes -> stringResource(R.string.status_countdown_minutes, left.value)
    Countdown.Soon, null -> stringResource(R.string.status_countdown_soon)
}

private const val MINUTE = 60_000.0
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
