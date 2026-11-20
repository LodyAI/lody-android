package app.lody.android.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.lody.android.R
import app.lody.android.designsystem.*
import app.lody.android.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.net.URI
import java.util.UUID

/** One provider's interactive sign-in. It outlives its sheet so the row can show the flow's status. */
internal class ProviderAuthFlow(private val service: AgentProviderService, private val scope: RuntimeScope, private val machineId: String, private val id: String,
    initialMethod: String, private val tasks: CoroutineScope) {
    var state by mutableStateOf<ProviderAuthState?>(null); private set
    var busy by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var method by mutableStateOf(initialMethod)
    var values by mutableStateOf<Map<String, String>>(emptyMap())
    var code by mutableStateOf("")
    val active: Boolean get() = state?.status !in listOf(null, "authenticated", "cancelled", "error")

    fun act(action: String, response: String? = null) {
        if (busy) return
        val flowId = if (action == "start") UUID.randomUUID().toString() else state?.flowId ?: return
        val request = ProviderAuthRequest(machineId, id, action, flowId,
            methodId = method.takeIf { (action == "start" || state?.status == "method-required") && it.isNotBlank() }, interactionId = state?.interactionId.takeIf { action == "submit" },
            values = values.takeIf { action == "submit" && state?.status == "input-required" }, authorizationCode = code.takeIf { action == "submit" && state?.acceptsAuthorizationCode == true }, responseAction = response)
        if (action == "start") state = ProviderAuthState(flowId, "starting", emptyList(), emptyList(), null, null, null, false, null)
        tasks.launch {
            busy = true; failed = false
            try {
                val next = service.auth(scope, request)
                if (state?.interactionId != next.interactionId || next.status in listOf("authenticated", "cancelled", "error")) { values = emptyMap(); code = "" }
                if (next.methods.isNotEmpty() && next.methods.none { it.id == method }) method = next.methods.first().id
                state = next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
}

@Composable
internal fun rememberProviderAuth(service: AgentProviderService, scope: RuntimeScope, machineId: String, id: String, detail: ProviderDetail, enabled: Boolean): ProviderAuthFlow {
    val tasks = rememberCoroutineScope()
    val flow = remember(id) { ProviderAuthFlow(service, scope, machineId, id, detail.capabilities.authMethods.firstOrNull()?.id.orEmpty(), tasks) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(flow.state?.flowId, enabled, lifecycleOwner) {
        if (enabled && flow.state?.flowId != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (flow.active) {
                delay(1500)
                if (!flow.busy && flow.active) flow.act("status")
            }
        }
    }
    return flow
}

/** The Account row's value: signed in, in progress, unconfirmed, unavailable or "Sign in". */
@Composable
internal fun providerAuthStatus(flow: ProviderAuthFlow, detail: ProviderDetail): String = stringResource(when {
    !detail.supportsAuthentication -> R.string.settings_agent_auth_unavailable
    flow.state?.status == "authenticated" -> R.string.settings_agent_authenticated
    flow.active -> R.string.settings_agent_auth_in_progress
    flow.failed || flow.state?.status == "error" -> R.string.settings_agent_auth_unconfirmed
    else -> R.string.settings_agent_sign_in
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderAuthenticationSheet(flow: ProviderAuthFlow, detail: ProviderDetail, enabled: Boolean, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val busy = flow.busy
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LodyTheme.colors.bgBase) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = LodySpacing.xxl), verticalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
            ProviderSheetHeader(stringResource(R.string.settings_agent_auth), doneEnabled = true, onCancel = onDismiss, onDone = null)
            if (!detail.supportsAuthentication) ProviderFootnote(stringResource(R.string.settings_agent_auth_unsupported))
            else {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = LodyLayout.screenPaddingX))
                if (flow.failed || flow.state?.status == "error") ProviderFootnote(stringResource(R.string.settings_agent_auth_failed))
                val state = flow.state
                val active = flow.active
                val methods = state?.methods?.takeIf { it.isNotEmpty() } ?: detail.capabilities.authMethods
                if ((detail.supportsMethodSelection || state?.status == "method-required" || state?.interactionId != null) && methods.isNotEmpty())
                    ProviderChoiceField(stringResource(R.string.settings_agent_auth_method), flow.method, methods, enabled && !busy && (!active || state?.status == "method-required")) { flow.method = it }
                if (state?.status == "authenticated") ProviderValueRow(stringResource(R.string.settings_agent_auth), stringResource(R.string.settings_agent_authenticated), onClick = null)
                Column(Modifier.padding(horizontal = LodyLayout.screenPaddingX), verticalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                    if (!active) Button(enabled = enabled && !busy, modifier = Modifier.fillMaxWidth(), onClick = { flow.act("start") }) { Text(stringResource(R.string.settings_agent_sign_in)) }
                    if (state != null && active) {
                        state.userCode?.let { Text(it, style = LodyTheme.typography.code, color = LodyTheme.colors.textPrimary) }
                        state.authorizationUrl?.let { url ->
                            val safe = runCatching { URI(url).let { it.scheme == "https" && it.host != null && it.userInfo == null } }.getOrDefault(false)
                            if (safe) OutlinedButton(enabled = enabled && !busy && !state.requiresAuthorizationConsent, modifier = Modifier.fillMaxWidth(), onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.settings_agent_open_sign_in)) }
                        }
                        if (state.acceptsAuthorizationCode) OutlinedTextField(flow.code, { flow.code = it }, enabled = enabled && !busy, label = { Text(stringResource(R.string.settings_agent_authorization_code)) },
                            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                        state.fields.forEach { field ->
                            if (field.type == "select") ProviderChoiceField(field.label, flow.values[field.id].orEmpty(), field.options, enabled && !busy) { flow.values = flow.values + (field.id to it) }
                            else OutlinedTextField(flow.values[field.id].orEmpty(), { flow.values = flow.values + (field.id to it) }, enabled = enabled && !busy, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (field.type == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "secret") KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                            if (state.status == "method-required") TextButton(enabled = enabled && !busy && flow.method.isNotBlank(), onClick = { flow.act(if (state.interactionId == null) "start" else "submit") }) { Text(stringResource(R.string.settings_agent_continue)) }
                            if (state.requiresAuthorizationConsent) TextButton(enabled = enabled && !busy, onClick = { flow.act("submit", "accept") }) { Text(stringResource(R.string.settings_agent_continue)) }
                            if (state.status == "input-required" || state.acceptsAuthorizationCode) TextButton(enabled = enabled && !busy && state.fields.filter { it.required }.all { !flow.values[it.id].isNullOrBlank() } && (!state.acceptsAuthorizationCode || flow.code.isNotBlank()), onClick = { flow.act("submit") }) { Text(stringResource(R.string.settings_agent_continue)) }
                            TextButton(enabled = enabled && !busy, onClick = { flow.act("status") }) { Text(stringResource(R.string.settings_agent_refresh)) }
                            TextButton(enabled = enabled && !busy, onClick = { flow.act("cancel") }) { Text(stringResource(R.string.settings_agent_cancel)) }
                        }
                    }
                }
            }
        }
    }
}
