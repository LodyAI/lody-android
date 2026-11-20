package app.lody.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.lody.android.R
import app.lody.android.designsystem.*
import app.lody.android.runtime.ProviderChoice

/**
 * One editable value: label on the left, the value (or a secondary placeholder) and a chevron on
 * the right. A null [onClick] renders a read-only row without the chevron.
 */
@Composable
internal fun ProviderValueRow(label: String, value: String?, onClick: (() -> Unit)?, enabled: Boolean = true, placeholder: String? = null,
    valueLines: Int = 1, labelColor: Color = LodyTheme.colors.textPrimary) {
    val colors = LodyTheme.colors
    val active = onClick != null && enabled
    val stacked = valueLines > 1
    val shown = value?.takeIf { it.isNotBlank() }
    val valueText: @Composable (Modifier) -> Unit = { modifier ->
        Text(shown ?: placeholder.orEmpty(), modifier, style = LodyTheme.typography.body, maxLines = valueLines, overflow = TextOverflow.Ellipsis,
            textAlign = if (stacked) TextAlign.Start else TextAlign.End,
            color = if (!enabled) colors.textDisabled else if (shown == null) colors.textTertiary else colors.textSecondary)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = LodySize.listRow)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = LodyLayout.listRowPaddingX, vertical = LodyLayout.listRowPaddingY),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
        if (stacked) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
            Text(label, style = LodyTheme.typography.body, color = if (enabled) labelColor else colors.textDisabled)
            if (shown != null || placeholder != null) valueText(Modifier)
        } else {
            Text(label, style = LodyTheme.typography.body, color = if (enabled) labelColor else colors.textDisabled, maxLines = 1)
            valueText(Modifier.weight(1f))
        }
        if (onClick != null) Icon(LodyIcons.Forward, null, Modifier.size(LodyIconSize.sm), tint = if (active) colors.textSecondary else colors.textDisabled)
    }
}

/** A choice row whose menu is anchored to the row; the current choice carries a check mark. */
@Composable
internal fun ProviderChoiceField(label: String, value: String, choices: List<ProviderChoice>, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ProviderValueRow(label, choices.find { it.id == value }?.name ?: value, onClick = { open = true }, enabled = enabled && choices.isNotEmpty(),
            placeholder = stringResource(R.string.settings_agent_not_set))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            choices.forEach { item ->
                DropdownMenuItem(text = { Text(item.name) }, onClick = { open = false; onChange(item.id) }, trailingIcon = {
                    if (item.id == value) Icon(LodyIcons.Check, stringResource(R.string.settings_agent_selected), Modifier.size(LodyIconSize.sm), tint = LodyTheme.colors.accentFg)
                })
            }
        }
    }
}

@Composable
internal fun ProviderSwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = LodySize.listRow).padding(horizontal = LodyLayout.listRowPaddingX, vertical = LodySpacing.xs),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = LodyTheme.typography.body, color = if (enabled) LodyTheme.colors.textPrimary else LodyTheme.colors.textDisabled)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun ProviderFootnote(text: String, color: Color = LodyTheme.colors.textSecondary) {
    Text(text, Modifier.padding(horizontal = LodyLayout.screenPaddingX, vertical = LodySpacing.xs), style = LodyTheme.typography.footnote, color = color)
}

/** Cancel · title · Done, as every editing sheet's header. */
@Composable
internal fun ProviderSheetHeader(title: String, doneEnabled: Boolean, onCancel: () -> Unit, onDone: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = LodySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_agent_cancel)) }
        Text(title, Modifier.weight(1f), style = LodyTheme.typography.headline, color = LodyTheme.colors.textPrimary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onDone != null) TextButton(enabled = doneEnabled, onClick = onDone) { Text(stringResource(R.string.settings_agent_done)) }
        else Spacer(Modifier.width(LodySize.controlLg))
    }
}

/**
 * Edits one text value of the draft. Done hands the text back; nothing is saved until the
 * editor's Save. [secret] values start empty and are masked; [fullHeight] suits long prompts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderTextSheet(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit, secret: Boolean = false,
    multiline: Boolean = false, fullHeight: Boolean = false, hint: String? = null, allowEmpty: Boolean = true) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val supporting: (@Composable () -> Unit)? = hint?.let { h -> @Composable { Text(h) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LodyTheme.colors.bgBase) {
        Column((if (fullHeight) Modifier.fillMaxHeight() else Modifier).fillMaxWidth().imePadding().padding(bottom = LodySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
            ProviderSheetHeader(title, allowEmpty || text.isNotEmpty(), onDismiss) { onDone(text) }
            OutlinedTextField(text, { text = it },
                Modifier.fillMaxWidth().padding(horizontal = LodyLayout.screenPaddingX).focusRequester(focus).then(if (fullHeight) Modifier.weight(1f) else Modifier),
                singleLine = !multiline, minLines = if (multiline && !fullHeight) 3 else 1,
                supportingText = supporting,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = !secret && multiline))
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}
