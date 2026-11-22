package app.lody.android.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.lody.android.R
import app.lody.android.designsystem.LodyButton
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodyRadius
import app.lody.android.designsystem.LodySize
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyStroke
import app.lody.android.designsystem.LodyTextButton
import app.lody.android.designsystem.LodyTheme
import app.lody.android.runtime.MessageQuoteBlock
import app.lody.android.runtime.MessageQuoteCard
import app.lody.android.runtime.MessageReference

/**
 * Message quotes with comments, as Web sends them (`@lody/shared` message-quote.ts): the
 * runtime writes the final text and its `message_quote` spans; this formats the same text for
 * the optimistic bubble and splits a turn's text around its quote blocks for display.
 */
internal object MessageQuotes {
    /** Web `MAX_SELECTION_TEXT`, in UTF-16 code units (Kotlin `String.length`). */
    const val MAX_CHARS = 50_000
    private const val HEADER = "<message-quote>\nQuoted message (context):\n"
    private const val FOOTER = "\n</message-quote>"

    /** The session's computer accepts quote spans (`protocolCapabilities.messageQuotes` >= 1). */
    fun supported(capabilities: Map<String, Int>) = (capabilities["messageQuotes"] ?: 0) >= 1

    fun format(snapshot: String): String =
        HEADER + snapshot.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("\n") { "> $it" } + FOOTER

    /** The turn as the runtime composes it: the main text, then each card's block and comment, joined by blank lines. */
    fun compose(text: String, cards: List<MessageQuoteCard>): Pair<String, List<MessageQuoteBlock>> {
        val out = StringBuilder(text)
        val blocks = cards.map { card ->
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            out.append(format(card.snapshot))
            val block = MessageQuoteBlock(start, out.length, card.snapshot)
            card.comment.trim().takeIf { it.isNotEmpty() }?.let { out.append("\n\n").append(it) }
            block
        }
        return out.toString() to blocks
    }

    sealed interface Segment {
        /** Text around the quotes, blank lines at its edges removed, with its references rebased; [comment] when it directly follows a quote. */
        data class Plain(val text: String, val references: List<MessageReference>, val comment: Boolean = false) : Segment
        data class Quote(val snapshot: String) : Segment
    }

    /** [text] split at its quote blocks, in order; without quotes, the text as one segment. */
    fun segments(text: String, references: List<MessageReference>, quotes: List<MessageQuoteBlock>): List<Segment> {
        val valid = quotes.filter { it.start >= 0 && it.end <= text.length && it.end > it.start }.sortedBy { it.start }
            .fold(emptyList<MessageQuoteBlock>()) { kept, quote -> if (kept.isNotEmpty() && quote.start < kept.last().end) kept else kept + quote }
        if (valid.isEmpty()) return listOf(Segment.Plain(text, references))
        val result = ArrayList<Segment>()
        fun plain(from: Int, to: Int) {
            var start = from
            var end = to
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (start >= end) return
            val inside = references.filter { it.start >= start && it.end <= end }.map { MessageReference(it.start - start, it.end - start) }
            result += Segment.Plain(text.substring(start, end), inside, result.lastOrNull() is Segment.Quote)
        }
        var cursor = 0
        valid.forEach { quote ->
            plain(cursor, quote.start)
            result += Segment.Quote(quote.snapshot)
            cursor = quote.end
        }
        plain(cursor, text.length)
        return result
    }
}

/**
 * The transcript's one live selection. Each answer block owns its own [SelectionContainer], which
 * only clears for taps inside itself; this lets a new selection, or a tap anywhere else in the
 * transcript ([clearsTranscriptSelection]), clear the previous one.
 */
@Stable
internal class TranscriptSelection {
    private var active: SelectionState? = null

    fun selected(state: SelectionState) {
        if (active !== state) active?.clear()
        active = state
    }

    fun released(state: SelectionState) {
        if (active === state) active = null
    }

    fun clear() {
        active?.clear()
        active = null
    }
}

internal val LocalTranscriptSelection = staticCompositionLocalOf<TranscriptSelection?> { null }

/**
 * Clears [selection] on a tap anywhere in this area. Observed before the children so a link or
 * another block still gets the tap; drags (scrolling) and long presses (selecting) never clear.
 */
internal fun Modifier.clearsTranscriptSelection(selection: TranscriptSelection) = pointerInput(selection) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var tapped = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                if (!change.pressed) { tapped = true; break }
            }
        }
        if (tapped) selection.clear()
    }
}

/** Quotes a selected passage into the conversation composer; null where there is no composer. */
internal val LocalMessageQuoter = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * Makes [content]'s text selectable within this one block, with Quote beside the system Copy and
 * Select all. The passage comes from the selection state, so the clipboard is never touched.
 * Without a quoter (previews, no composer) the content renders as before.
 */
@Composable
internal fun QuotableSelection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val quote = LocalMessageQuoter.current
    if (quote == null) {
        Box(modifier) { content() }
        return
    }
    val state = rememberSelectionState()
    val transcript = LocalTranscriptSelection.current
    if (transcript != null) {
        LaunchedEffect(state, transcript) {
            snapshotFlow { state.selectedTexts.isNotEmpty() }.collect { if (it) transcript.selected(state) else transcript.released(state) }
        }
        DisposableEffect(state, transcript) { onDispose { transcript.released(state) } }
    }
    val label = stringResource(R.string.quote_action)
    Box(modifier.appendTextContextMenuComponents {
        item(key = QuoteMenuKey, label = label) {
            val text = state.selectedTexts.joinToString("\n") { it.text }
            close()
            state.clear()
            quote(text)
        }
    }) {
        SelectionContainer(state) { content() }
    }
}

private val QuoteMenuKey = Any()

/** A quoted snapshot: a leading bar and secondary text, clipped to [maxLines] when given. */
@Composable
internal fun QuoteSnapshot(snapshot: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, onOverflow: (Boolean) -> Unit = {}) {
    val colors = LodyTheme.colors
    Row(modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
        Box(Modifier.width(QuoteBarWidth).fillMaxHeight().background(colors.borderStrong, RoundedCornerShape(LodyRadius.full)))
        Text(
            snapshot, style = LodyTheme.typography.reading, color = colors.textSecondary,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { onOverflow(it.hasVisualOverflow) },
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private val QuoteBarWidth = LodyStroke.focus + LodyStroke.default

/**
 * Writes or edits a quote card's comment. The snapshot above it is read only (collapsed to a few
 * lines, expandable); the comment field takes focus at once and may stay empty. Dismissing
 * discards a new quote and leaves an edited card unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuoteSheet(snapshot: String, comment: String, editing: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, onRemove: () -> Unit) {
    val colors = LodyTheme.colors
    var text by remember(snapshot) { mutableStateOf(comment) }
    var expanded by remember(snapshot) { mutableStateOf(false) }
    var overflowing by remember(snapshot) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.bgBase,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = LodySpacing.lg).padding(bottom = LodySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(LodySpacing.md),
        ) {
            // The keyboard comes up with the sheet; a field not yet attached just stays unfocused.
            LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { focus.requestFocus() } }
            val shown = stringResource(R.string.quote_snapshot)
            Column(Modifier.heightIn(max = QuoteSheetSnapshotMax).verticalScroll(rememberScrollState()).semantics { contentDescription = shown }) {
                QuoteSnapshot(snapshot, maxLines = if (expanded) Int.MAX_VALUE else 4, onOverflow = { if (!expanded) overflowing = it })
            }
            if (overflowing || expanded) LodyTextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.message_show_less else R.string.message_show_more), color = colors.textSecondary)
            }
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text(stringResource(R.string.quote_comment_placeholder), color = colors.textPlaceholder) },
                textStyle = LodyTheme.typography.body.copy(color = colors.textPrimary),
                minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (editing) LodyTextButton(onClick = onRemove) {
                    Text(stringResource(R.string.quote_remove), color = colors.dangerDefault)
                }
                Spacer(Modifier.weight(1f))
                LodyButton(
                    onClick = { onSave(text.trim()) }, shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentSolid, contentColor = colors.textOnAccent),
                ) { Text(stringResource(if (editing) R.string.quote_save else R.string.quote_add)) }
            }
        }
    }
}

private val QuoteSheetSnapshotMax = LodySize.controlLg * 6

/** A quote block's full snapshot in a sheet, selectable and copyable; user messages are not quotable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuoteSnapshotSheet(snapshot: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = LodyTheme.colors.bgBase) {
        SelectionContainer(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = LodySpacing.lg).padding(bottom = LodySpacing.xl).navigationBarsPadding(),
        ) { QuoteSnapshot(snapshot) }
    }
}

/**
 * A quote card in the composer's attachment strip: the quote glyph, a short excerpt of the
 * snapshot and the comment's first line (or "Add comment"), with a remove control. Tapping it
 * reopens the comment sheet.
 */
@Composable
internal fun QuoteCardChip(card: MessageQuoteCard, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LodyTheme.colors
    val remove = stringResource(R.string.chat_attach_remove)
    val label = stringResource(R.string.quote_action)
    val comment = card.comment.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    Row(
        modifier
            .widthIn(max = QuoteCardMaxWidth)
            .background(colors.fillSecondary, RoundedCornerShape(LodyRadius.md))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "$label: ${card.snapshot.take(80)}" }
            .padding(start = LodySpacing.sm, top = LodySpacing.xs, bottom = LodySpacing.xs, end = LodySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs),
    ) {
        Icon(LodyIcons.Quote, contentDescription = null, tint = colors.iconSecondary, modifier = Modifier.size(LodyIconSize.sm))
        Column(Modifier.weight(1f, fill = false)) {
            Text(card.snapshot.replace(Regex("\\s+"), " ").trim(), style = LodyTheme.typography.footnote, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                comment ?: stringResource(R.string.quote_comment_placeholder),
                style = LodyTheme.typography.footnote, color = if (comment != null) colors.textPrimary else colors.textPlaceholder,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(LodySize.controlSm)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "$remove $label" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(LodyIcons.Close, contentDescription = null, tint = colors.iconTertiary, modifier = Modifier.size(LodyIconSize.xs))
        }
    }
}

private val QuoteCardMaxWidth = LodySize.controlLg * 5
