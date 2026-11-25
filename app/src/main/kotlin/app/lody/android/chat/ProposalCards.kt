package app.lody.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import app.lody.android.R
import app.lody.android.designsystem.LodyButton
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodyLoadingIndicator
import app.lody.android.designsystem.LodyRadius
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyStroke
import app.lody.android.designsystem.LodyTextButton
import app.lody.android.designsystem.LodyTheme
import app.lody.android.runtime.ProposalCard
import app.lody.android.runtime.ProposalInspection
import app.lody.android.runtime.RuntimeException
import app.lody.android.runtime.TurnItem
import app.lody.android.schedules.ruleSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Long proposed text stays readable without turning the card into a page. */
private const val PreviewLimit = 600
private const val DiffLineLimit = 40

/**
 * Save / create or dismiss an Agent's proposal; throws the runtime's failure. Null where there
 * is no runtime (previews, the stream probe): the cards then only describe the proposal.
 */
typealias ProposalDecider = suspend (ProposalCard, Boolean) -> Unit

/** Reads an edit proposal's live schedule; throws the runtime's failure. Null where there is no runtime. */
typealias ProposalInspector = suspend (ProposalCard) -> ProposalInspection

/**
 * An Agent's Role, schedule or schedule edit proposals (web `AgentRoleProposalNotice` /
 * `ScheduleProposalNotice` / `ScheduleEditProposalNotice`): what saving, creating or applying
 * would do, then Dismiss and Save / Create / Apply; once decided, a receipt. [meId] is the
 * signed-in user: a Role proposal is decided only by the member who asked. An edit reads its live
 * schedule through [inspect] and applies only while that is still the revision it was written for.
 */
@Composable
fun ProposalCards(
    proposals: List<ProposalCard>, meId: String?, decide: ProposalDecider?, modifier: Modifier = Modifier, inspect: ProposalInspector? = null,
    /** Opens a schedule's editor (a created or updated schedule, or the one an edit targets). */
    openSchedule: ((String) -> Unit)? = null,
    /** Bumped when the conversation returns to the screen: edits read their schedule again. */
    reads: Int = 0,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = TranscriptMargin), verticalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
        proposals.forEach { ProposalCardView(it, meId, decide, inspect, openSchedule, reads) }
    }
}

@Composable
private fun ProposalCardView(card: ProposalCard, meId: String?, decide: ProposalDecider?, inspect: ProposalInspector?, openSchedule: ((String) -> Unit)?, rereads: Int) {
    val colors = LodyTheme.colors
    val scope = rememberCoroutineScope()
    var pending by remember(card.entryId, card.itemId) { mutableStateOf(false) }
    var failure by remember(card.entryId, card.itemId) { mutableStateOf<String?>(null) }
    // An edit's live schedule, read on appearance and again after a refused decision ([reads]).
    var live by remember(card.entryId, card.itemId) { mutableStateOf<ProposalInspection?>(null) }
    var reads by remember(card.entryId, card.itemId) { mutableStateOf(0) }
    // A decision recorded elsewhere (or by this card) ends any failure shown for it.
    LaunchedEffect(card.outcome) { if (card.decided) failure = null }
    LaunchedEffect(card.entryId, card.itemId, card.decided, reads, rereads) {
        if (!card.isEdit || card.decided || inspect == null) return@LaunchedEffect
        try { live = inspect(card) }
        catch (error: CancellationException) { throw error }
        catch (error: RuntimeException) { failure = error.fault.code }
        catch (error: Exception) { failure = "failed" }
    }
    val allowed = decide != null && !meId.isNullOrEmpty() && (!card.isRole || card.requesterUserId == meId)
    // Web: an edit applies only to a ready schedule, and only its owner may ignore it (anyone may ignore one that is gone).
    val canConfirm = allowed && (!card.isEdit || live?.state == "ready")
    val canDismiss = allowed && (!card.isEdit || live?.let { it.isOwner || it.state == "unavailable" } == true)
    val shape = RoundedCornerShape(LodyRadius.xl)
    val submit: (Boolean) -> Unit = { accept ->
        if (!pending && (if (accept) canConfirm else canDismiss)) {
            pending = true
            failure = null
            scope.launch {
                try { decide?.invoke(card, accept) }
                catch (error: CancellationException) { throw error }
                catch (error: RuntimeException) { failure = error.fault.code; if (card.isEdit) reads++ }
                catch (error: Exception) { failure = "failed"; if (card.isEdit) reads++ }
                finally { pending = false }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(colors.bgRaised, shape)
            .border(LodyStroke.default, colors.borderSubtle, shape)
            .padding(LodySpacing.md),
        verticalArrangement = Arrangement.spacedBy(LodySpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
            Icon(if (card.isRole) LodyIcons.Agent else LodyIcons.Clock, contentDescription = null,
                tint = colors.iconSecondary, modifier = Modifier.size(LodyIconSize.sm))
            Text(headline(card), style = LodyTheme.typography.headline,
                color = if (card.decided) colors.textSecondary else colors.textPrimary)
        }
        when {
            card.isRole -> RoleFields(card)
            card.isEdit -> EditFields(card, live)
            else -> ScheduleFields(card)
        }
        val note = when {
            failure != null -> failureText(failure!!)
            card.decided -> null
            card.isEdit -> live?.let { editReason(it.state) }
            decide != null && !allowed -> stringResource(R.string.chat_proposal_role_owner_only)
            card.isRole && card.action == "create" -> stringResource(R.string.chat_proposal_role_defaults)
            card.isRole -> stringResource(R.string.chat_proposal_role_scope)
            else -> null
        }
        note?.let {
            Text(it, style = LodyTheme.typography.footnote, color = if (failure != null) colors.dangerDefault else colors.textSecondary)
        }
        val scheduleId = card.scheduleId
        if (card.decided && openSchedule != null && scheduleId != null && card.outcome in setOf("created", "applied")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LodyTextButton(onClick = { openSchedule(scheduleId) }) {
                    Text(stringResource(R.string.chat_proposal_open), color = colors.textPrimary)
                }
            }
        }
        if (!card.decided && decide != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                LodyTextButton(onClick = { submit(false) }, enabled = canDismiss && !pending) {
                    Text(stringResource(R.string.chat_proposal_dismiss), color = colors.textPrimary)
                }
                if (card.isEdit && openSchedule != null && scheduleId != null) {
                    LodyTextButton(onClick = { openSchedule(scheduleId) }) {
                        Text(stringResource(R.string.chat_proposal_open), color = colors.textPrimary)
                    }
                }
                Spacer(Modifier.width(LodySpacing.sm))
                LodyButton(
                    onClick = { submit(true) }, enabled = canConfirm && !pending, shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.bgInverse, contentColor = colors.textInverse),
                ) {
                    // Spinner replaces the label while pending; the invisible label keeps the button's width.
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(when {
                                card.isRole -> R.string.chat_proposal_save
                                card.isEdit -> R.string.chat_proposal_edit_apply
                                else -> R.string.chat_proposal_create
                            }),
                            Modifier.alpha(if (pending) 0f else 1f),
                        )
                        if (pending) LodyLoadingIndicator(Modifier.size(LodyIconSize.sm), color = colors.textInverse)
                    }
                }
            }
        }
    }
}

@Composable
private fun headline(card: ProposalCard): String = stringResource(
    when {
        card.outcome == "dismissed" -> R.string.chat_proposal_dismissed
        card.isRole && card.outcome == "saved" -> R.string.chat_proposal_role_saved
        card.isRole && card.action == "update" -> R.string.chat_proposal_role_update
        card.isRole -> R.string.chat_proposal_role_create
        card.isEdit && card.outcome == "applied" -> R.string.chat_proposal_edit_applied
        card.isEdit -> R.string.chat_proposal_edit_title
        card.outcome == "created" -> R.string.chat_proposal_schedule_created
        else -> R.string.chat_proposal_schedule_title
    },
)

private fun clipped(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.length > PreviewLimit) trimmed.take(PreviewLimit) + "…" else trimmed
}

@Composable
private fun Field(label: String, value: String, before: String? = null) {
    val colors = LodyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
        Text(label, style = LodyTheme.typography.caption, color = colors.textSecondary)
        before?.let {
            Text(it, style = LodyTheme.typography.subhead, color = colors.textSecondary, textDecoration = TextDecoration.LineThrough)
        }
        Text(value, style = LodyTheme.typography.subhead, color = colors.textPrimary)
    }
}

@Composable
private fun RoleFields(card: ProposalCard) {
    val empty = stringResource(R.string.chat_proposal_empty)
    val shown = { value: String? -> if (value.isNullOrEmpty()) empty else clipped(value) }
    listOf(
        Triple(R.string.chat_proposal_role_name, card.fields.name, card.before?.name),
        Triple(R.string.chat_proposal_role_description, card.fields.description, card.before?.description),
        Triple(R.string.chat_proposal_role_prompt_prefix, card.fields.promptPrefix, card.before?.promptPrefix),
    ).forEach { (label, value, before) ->
        if (value != null) Field(stringResource(label), shown(value), if (card.before != null) shown(before) else null)
    }
}

@Composable
private fun ScheduleFields(card: ProposalCard) {
    card.title?.let { Field(stringResource(R.string.chat_proposal_schedule_name), it) }
    card.rule?.let { Field(stringResource(R.string.chat_proposal_schedule_when), ruleSummary(it)) }
    destinationText(card.destination)?.let { Field(stringResource(R.string.chat_proposal_schedule_send_to), it) }
    if (!card.decided) card.prompt?.let { Field(stringResource(R.string.chat_proposal_schedule_prompt), clipped(it)) }
}

/**
 * Web `ScheduleEditProposalNotice`: the schedule's name, each changed field as its current value
 * (struck through when it differs) above the proposed one, and the prompt as a line diff, or the
 * proposed text when it cannot be compared. A receipt keeps only the name.
 */
@Composable
private fun EditFields(card: ProposalCard, live: ProposalInspection?) {
    val patch = card.patch ?: return
    val name = stringResource(R.string.chat_proposal_schedule_name)
    val current = live?.currentTitle ?: card.title.orEmpty()
    when {
        card.decided -> Field(name, if (card.outcome == "applied") patch.title ?: current else current)
        patch.title != null -> Field(name, patch.title, live?.currentTitle?.takeIf { it != patch.title })
        else -> Field(name, current)
    }
    if (card.decided) return
    patch.rule?.let { rule ->
        val after = ruleSummary(rule)
        val before = live?.currentRule?.let { ruleSummary(it) }
        Field(stringResource(R.string.chat_proposal_schedule_when), after, before?.takeIf { it != after })
    }
    patch.prompt?.let { prompt ->
        val diff = live?.promptDiff
        if (diff.isNullOrEmpty()) Field(stringResource(R.string.chat_proposal_schedule_prompt), clipped(prompt))
        else PromptDiff(diff)
    }
}

@Composable
private fun PromptDiff(lines: List<app.lody.android.runtime.PromptDiffLine>) {
    val colors = LodyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
        Text(stringResource(R.string.chat_proposal_schedule_prompt), style = LodyTheme.typography.caption, color = colors.textSecondary)
        lines.take(DiffLineLimit).forEach { line ->
            when (line.kind) {
                "add" -> Text("+ " + line.text, style = LodyTheme.typography.codeSmall, color = colors.successDefault)
                "del" -> Text("− " + line.text, style = LodyTheme.typography.codeSmall, color = colors.textSecondary, textDecoration = TextDecoration.LineThrough)
                "gap" -> Text("⋯", style = LodyTheme.typography.codeSmall, color = colors.textTertiary)
                else -> Text("  " + line.text, style = LodyTheme.typography.codeSmall, color = colors.textSecondary)
            }
        }
        if (lines.size > DiffLineLimit) Text("⋯", style = LodyTheme.typography.codeSmall, color = colors.textTertiary)
    }
}

@Composable
private fun editReason(state: String): String? = when (state) {
    "unavailable" -> stringResource(R.string.chat_proposal_edit_unavailable)
    "stale" -> stringResource(R.string.chat_proposal_edit_stale)
    "not_owner" -> stringResource(R.string.chat_proposal_edit_not_owner)
    "machine_unsupported" -> stringResource(R.string.chat_proposal_error_schedule_unsupported)
    "target_unavailable" -> stringResource(R.string.chat_proposal_edit_target_unavailable)
    else -> null
}

@Composable
private fun destinationText(destination: String?): String? = when (destination) {
    "new_session" -> stringResource(R.string.chat_proposal_destination_new)
    "own_session" -> stringResource(R.string.chat_proposal_destination_own)
    "existing_session" -> stringResource(R.string.chat_proposal_destination_existing)
    else -> null
}

@Composable
private fun failureText(code: String): String = stringResource(
    when (code) {
        "proposal_not_requester" -> R.string.chat_proposal_role_owner_only
        "proposal_unavailable" -> R.string.chat_proposal_error_unavailable
        "role_duplicate" -> R.string.chat_proposal_error_role_duplicate
        "role_read_only" -> R.string.chat_proposal_error_role_read_only
        "role_forbidden" -> R.string.chat_proposal_error_role_forbidden
        "role_target_unavailable" -> R.string.chat_proposal_error_role_target
        "schedule_target_unavailable" -> R.string.chat_proposal_error_schedule_target
        "schedule_not_owner" -> R.string.chat_proposal_error_schedule_owner
        "schedule_machine_unsupported" -> R.string.chat_proposal_error_schedule_unsupported
        "schedule_trigger_invalid" -> R.string.chat_proposal_error_schedule_trigger
        "schedule_edit_stale" -> R.string.chat_proposal_edit_stale
        // Unknown and transport failures may have landed: say so instead of inviting a blind retry.
        else -> R.string.chat_proposal_error_failed
    },
)

/**
 * A file the agent shared (`lody_upload_files`, web `SessionFileCard`): a type glyph, its name,
 * and its type and size. Tapping opens it ([LocalSessionFiles]): Markdown in a sheet, other files
 * in an app that views them, with a spinner while it downloads.
 */
@Composable
fun AgentFileCard(item: TurnItem.File, modifier: Modifier = Modifier) {
    val colors = LodyTheme.colors
    val shape = RoundedCornerShape(LodyRadius.lg)
    val context = androidx.compose.ui.platform.LocalContext.current
    val extension = item.fileName.substringAfterLast('.', "").uppercase()
    val detail = listOfNotNull(
        extension.ifEmpty { null },
        item.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
    ).joinToString(" · ")
    val type = item.mimeType.orEmpty()
    val icon = when {
        type.startsWith("image/") -> LodyIcons.Image
        type.startsWith("text/") || extension.lowercase() in setOf("md", "txt", "log", "json", "yaml", "yml", "xml", "html", "csv") -> LodyIcons.FileCode
        else -> LodyIcons.File
    }
    val files = LocalSessionFiles.current
    val ref = item.ref
    val openLabel = androidx.compose.ui.res.stringResource(app.lody.android.R.string.chat_file_open, item.fileName)
    Row(
        modifier.fillMaxWidth().padding(horizontal = TranscriptMargin).padding(bottom = LodySpacing.md)
            .clip(shape).background(colors.bgRaised, shape).border(LodyStroke.default, colors.borderSubtle, shape)
            .then(if (files != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = openLabel) { files.open(ref) } else Modifier)
            .padding(LodySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = colors.iconSecondary,
            modifier = Modifier.background(colors.fillTertiary, RoundedCornerShape(LodyRadius.md)).padding(LodySpacing.sm).size(LodyIconSize.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
            Text(item.fileName, style = LodyTheme.typography.callout, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (detail.isNotEmpty()) Text(detail, style = LodyTheme.typography.caption, color = colors.textSecondary, maxLines = 1)
        }
        if (files != null && ref.id in files.busy) LodyLoadingIndicator(Modifier.size(LodyIconSize.sm), color = colors.iconTertiary)
    }
}
