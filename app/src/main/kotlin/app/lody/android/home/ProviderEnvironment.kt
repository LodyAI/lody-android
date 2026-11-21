package app.lody.android.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.lody.android.R
import app.lody.android.designsystem.*

/** Absent = keep, null = remove, including an empty string = an explicit replacement. */
internal fun validEnvironmentKey(key: String) = key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && key.length <= 128

/** Credentials are supplied only by the user; existing values never enter this component. */
@Composable
internal fun ProviderEnvironment(existingKeys: List<String>, patch: Map<String, String?>, enabled: Boolean, onChange: (Map<String, String?>) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    SettingsHeading(stringResource(R.string.settings_agent_env))
    (existingKeys + patch.keys).distinct().sorted().forEach { key ->
        val value = when {
            !patch.containsKey(key) -> R.string.settings_agent_env_stored
            patch[key] == null -> R.string.settings_agent_env_removed
            key in existingKeys -> R.string.settings_agent_env_will_replace
            else -> R.string.settings_agent_env_new
        }
        ProviderValueRow(key, stringResource(value), onClick = { editing = key }, enabled = enabled)
    }
    ProviderValueRow(stringResource(R.string.settings_agent_env_add), null, onClick = { adding = true }, enabled = enabled, labelColor = LodyTheme.colors.accentFg)
    editing?.let { key ->
        EnvironmentValueSheet(key, existing = key in existingKeys, patch = patch, onDismiss = { editing = null }) { onChange(it); editing = null }
    }
    if (adding) EnvironmentAddSheet(existingKeys, patch, onDismiss = { adding = false }) { onChange(it); adding = false }
}

/** Replace, remove or keep one variable. Stored values are never shown; the field starts empty. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentValueSheet(key: String, existing: Boolean, patch: Map<String, String?>, onDismiss: () -> Unit, onChange: (Map<String, String?>) -> Unit) {
    val replacing = patch.containsKey(key) && patch[key] != null
    var value by remember(key) { mutableStateOf(patch[key].orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LodyTheme.colors.bgBase) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = LodySpacing.xxl), verticalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
            // An untouched empty field never replaces a stored value by accident.
            ProviderSheetHeader(key, doneEnabled = value.isNotEmpty() || replacing || !existing, onCancel = onDismiss) { onChange(patch + (key to value)) }
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().padding(horizontal = LodyLayout.screenPaddingX), singleLine = true,
                label = { Text(stringResource(R.string.settings_agent_env_value)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            if (existing && patch.containsKey(key)) ProviderValueRow(stringResource(R.string.settings_agent_env_keep), null, onClick = { onChange(patch - key) })
            if (!patch.containsKey(key) || patch[key] != null) ProviderValueRow(stringResource(R.string.settings_agent_remove), null,
                onClick = { onChange(if (existing) patch + (key to null) else patch - key) }, labelColor = LodyTheme.colors.dangerDefault)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentAddSheet(existingKeys: List<String>, patch: Map<String, String?>, onDismiss: () -> Unit, onChange: (Map<String, String?>) -> Unit) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LodyTheme.colors.bgBase) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = LodySpacing.xxl), verticalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
            ProviderSheetHeader(stringResource(R.string.settings_agent_env_add), doneEnabled = validEnvironmentKey(key) && key !in existingKeys && key !in patch, onCancel = onDismiss) {
                onChange(patch + (key to value))
            }
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(horizontal = LodyLayout.screenPaddingX), singleLine = true,
                label = { Text(stringResource(R.string.settings_agent_env_key)) }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().padding(horizontal = LodyLayout.screenPaddingX), singleLine = true,
                label = { Text(stringResource(R.string.settings_agent_env_value)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        }
    }
}
