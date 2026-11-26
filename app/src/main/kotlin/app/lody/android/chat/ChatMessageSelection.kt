package app.lody.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lody.android.R
import app.lody.android.designsystem.LodyButton
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodySize
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyStroke
import app.lody.android.designsystem.LodyTextButton
import app.lody.android.designsystem.LodyTheme
import app.lody.android.runtime.ConversationShareData
import app.lody.android.runtime.ShareCardMessage

/** Pure selection rules for conversation Share as image: unordered toggle set, chronological output. */
internal object ChatMessageSelection {
    /** Candidate turn IDs currently visible in the loaded transcript window, in chronological order. */
    fun selectableIds(data: ConversationShareData, loadedEntryIds: Set<String>): List<String> =
        data.messages.map { it.id }.filter { it in loadedEntryIds }

    /** Selected messages ordered strictly by conversation history order regardless of tap sequence. */
    fun selectedMessages(
        data: ConversationShareData,
        selectedIds: Set<String>,
        loadedEntryIds: Set<String>,
    ): List<ShareCardMessage> =
        data.messages.filter { it.id in selectedIds && it.id in loadedEntryIds }

    fun toggle(selectedIds: Set<String>, entryId: String): Set<String> =
        if (entryId in selectedIds) selectedIds - entryId else selectedIds + entryId

    fun toggleAll(selectedIds: Set<String>, selectableIds: List<String>): Set<String> =
        if (selectableIds.isNotEmpty() && selectableIds.all { it in selectedIds }) emptySet()
        else selectableIds.toSet()
}

/** Transcript-level selection state passed into [ChatTranscript] while choosing turns to share. */
data class TranscriptShareSelection(
    val selectableIds: Set<String>,
    val selectedIds: Set<String>,
    val onToggle: (String) -> Unit,
)

/**
 * Wraps every lazy row emitted for one [ChatEntry] during selection mode so folded multi-row
 * assistant turns highlight and toggle as a single unit, with one passive badge on the first row.
 */
internal class SelectionEntryScope(
    private val delegate: LazyListScope,
    private val entryId: String,
    private val selectable: Boolean,
    private val isSelected: Boolean,
    private val onToggle: (String) -> Unit,
) : LazyListScope by delegate {
    private var badgeShown = false

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        if (contentType == "turn-end") {
            delegate.item(key, contentType, content)
            return
        }
        val id = entryId
        val canSelect = selectable
        val chosen = isSelected
        val toggle = onToggle
        val showBadge = canSelect && !badgeShown
        if (showBadge) badgeShown = true
        delegate.item(key, contentType) {
            SelectableTranscriptRow(
                entryId = id,
                selectable = canSelect,
                isSelected = chosen,
                showBadge = showBadge,
                onToggle = toggle,
            ) {
                content()
            }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val id = entryId
        val canSelect = selectable
        val chosen = isSelected
        val toggle = onToggle
        val firstShowsBadge = canSelect && !badgeShown && count > 0
        if (firstShowsBadge) badgeShown = true
        delegate.items(count, key, contentType) { index ->
            SelectableTranscriptRow(
                entryId = id,
                selectable = canSelect,
                isSelected = chosen,
                showBadge = firstShowsBadge && index == 0,
                onToggle = toggle,
            ) {
                itemContent(index)
            }
        }
    }
}

@Composable
private fun SelectableTranscriptRow(
    entryId: String,
    selectable: Boolean,
    isSelected: Boolean,
    showBadge: Boolean,
    onToggle: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LodyTheme.colors
    val haptics = LocalHapticFeedback.current
    val background = if (selectable && isSelected) colors.accentSubtle.copy(alpha = 0.42f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .then(
                if (selectable) {
                    Modifier
                        .semantics {
                            role = Role.Checkbox
                            selected = isSelected
                        }
                        .interceptRowTap {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggle(entryId)
                        }
                } else {
                    Modifier
                        .alpha(0.5f)
                        .interceptRowTap { }
                },
            )
            .padding(start = LodySpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = LodySpacing.lg)
                .size(SelectionBadgeSize),
            contentAlignment = Alignment.Center,
        ) {
            if (showBadge) {
                SelectionCheckBadge(isSelected = isSelected)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

/** Passive checkmark circle; row-wide tap handles toggling so back-swipe edges stay clear. */
@Composable
private fun SelectionCheckBadge(isSelected: Boolean) {
    val colors = LodyTheme.colors
    if (isSelected) {
        Box(
            modifier = Modifier
                .size(SelectionBadgeSize)
                .clip(CircleShape)
                .background(colors.accentSolid),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LodyIcons.Check,
                contentDescription = null,
                tint = colors.textOnAccent,
                modifier = Modifier.size(LodyIconSize.xs),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(SelectionBadgeSize)
                .border(LodyStroke.strong, colors.borderStrong, CircleShape),
        )
    }
}

/**
 * Intercepts taps on [PointerEventPass.Initial] without consuming pointer down, so [LazyColumn]
 * drags scroll normally while inner buttons, links, disclosures and long-press menus stay inert.
 */
private fun Modifier.interceptRowTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var up: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                break
            }
            if (!change.pressed) {
                up = change
                break
            }
        }
        if (up != null) {
            up.consume()
            onTap()
        }
    }
}

/** Bottom bar replacing the composer while selecting messages for Share as image. */
@Composable
internal fun MessageSelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    hasSelectable: Boolean,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgRaised)
            .testTag("share-selection-toolbar"),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 0.5.dp).background(colors.borderSubtle))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LodySpacing.lg, vertical = LodySpacing.sm),
            verticalArrangement = Arrangement.spacedBy(LodySpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.share_image_selected_count, selectedCount, selectedCount),
                    style = type.subhead,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                LodyTextButton(
                    onClick = onToggleAll,
                    enabled = hasSelectable,
                    modifier = Modifier
                        .heightIn(min = LodySize.controlLg)
                        .testTag("share-selection-toggle-all"),
                ) {
                    Text(
                        text = stringResource(if (allSelected) R.string.share_image_clear_selection else R.string.share_image_select_all),
                        style = type.subhead,
                        color = if (hasSelectable) colors.accentFg else colors.textDisabled,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LodyButton(
                    onClick = onCancel,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.fillSecondary,
                        contentColor = colors.textPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = LodySpacing.lg, vertical = LodySpacing.sm),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = LodySize.controlLg)
                        .testTag("share-selection-cancel"),
                ) {
                    Text(stringResource(R.string.share_image_cancel), style = type.subhead)
                }
                LodyButton(
                    onClick = onPreview,
                    enabled = selectedCount > 0,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentSolid,
                        contentColor = colors.textOnAccent,
                        disabledContainerColor = colors.fillDisabled,
                        disabledContentColor = colors.textDisabled,
                    ),
                    contentPadding = PaddingValues(horizontal = LodySpacing.lg, vertical = LodySpacing.sm),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = LodySize.controlLg)
                        .testTag("share-selection-preview"),
                ) {
                    Text(stringResource(R.string.share_image_preview), style = type.subhead)
                }
            }
        }
    }
}

private val SelectionBadgeSize = 22.dp
