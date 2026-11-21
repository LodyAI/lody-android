package app.lody.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lody.android.R
import app.lody.android.create.PickerOption
import app.lody.android.create.RUN_CONFIG_ON
import app.lody.android.create.RunConfigRow
import app.lody.android.create.RunConfigSheet
import app.lody.android.designsystem.*
import app.lody.android.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The draft value a sheet edits; every sheet only changes the draft, and Save persists it. */
private enum class EditorSheet { Name, Command, Arguments, ApiKey, CustomEndpoint, CodexBaseUrl, TitleGeneration, Usage }

private const val TITLE_DEFAULT = "__default__"

/**
 * Header, Basics, Command (Custom), Account, Title generation, Environment variables, Delete.
 * System prompt, description, runtime paths, Pi extensions and binary controls are not shown,
 * but their loaded values are sent back unchanged on save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderEditorScreen(editor: ProviderEditor, catalog: List<ProviderCatalogEntry>, canManage: Boolean, busy: Boolean, error: String?, service: AgentProviderService, scope: RuntimeScope,
    machine: ProviderMachine, presets: List<ProviderPreset>, quotas: List<UsageQuota>, onBack: () -> Unit, onSave: (ProviderFields) -> Unit, onDelete: () -> Unit) {
    val machineId = machine.id
    val base = editor.base
    val tasks = rememberCoroutineScope()
    var name by rememberSaveable(editor.id) { mutableStateOf(base?.name.orEmpty()) }
    val prompt = base?.prompt.orEmpty()
    var selection by remember(editor.id) { mutableStateOf(catalog.firstOrNull()?.id.orEmpty()) }
    var cliType by rememberSaveable(editor.id) { mutableStateOf(base?.cliType ?: catalog.firstOrNull()?.cliType.orEmpty()) }
    var agentType by rememberSaveable(editor.id) { mutableStateOf(base?.agentType ?: catalog.firstOrNull()?.agentType.orEmpty()) }
    var command by rememberSaveable(editor.id) { mutableStateOf(base?.command.orEmpty()) }
    var arguments by rememberSaveable(editor.id) { mutableStateOf(base?.args?.joinToString("\n").orEmpty()) }
    var detail by remember(editor.id) { mutableStateOf<ProviderDetail?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshProgress by remember { mutableStateOf<RuntimeEvent.ProviderRefreshProgress?>(null) }
    var refreshResult by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(service, scope, editor.id) {
        service.refreshProgress(scope).collect { if (refreshing && it.id == editor.id && it.machineId == machineId) refreshProgress = it }
    }
    var capabilities by remember { mutableStateOf(ProviderCapabilities()) }
    var description by remember { mutableStateOf("") }
    var envPatch by remember(editor.id) { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var paths by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var titles by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var extensions by remember { mutableStateOf<List<String>>(emptyList()) }
    var codexAuth by remember { mutableStateOf<ProviderCodexAuth?>(null) }
    var presetId by remember { mutableStateOf<String?>(null) }
    var presetMode by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    var endpointSheet by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    suspend fun load() {
        loading = true; failed = false
        try {
            val next = service.detail(scope, machineId, editor.id)
            detail = next; capabilities = next.capabilities; description = next.description
            paths = next.runtimePaths; titles = next.titleValues; presetId = next.presetId; extensions = next.piExtensions; codexAuth = next.codexAuth
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    LaunchedEffect(editor.id) { if (base != null) load() }
    val preset = presets.find { it.id == presetId }
    val mode = preset?.modes?.find { it.id == presetMode } ?: preset?.modes?.firstOrNull()
    // Read-only providers and machines hide Save and More; busy or offline states only disable them.
    val manageable = !machine.readOnly && base?.readOnly != true
    val editable = canManage && manageable && !loading
    val fields = ProviderFields(name, prompt, cliType, agentType, if (cliType == "custom") command else "", if (cliType != "custom") emptyList() else if (base != null && arguments == base.args.joinToString("\n")) base.args else if (arguments.isEmpty()) emptyList() else arguments.split('\n'),
        ProviderAdvanced(detailRevision = detail?.revision, description = description.takeIf { detail != null || it.isNotEmpty() }, envPatch = envPatch.takeIf { it.isNotEmpty() }, runtimePaths = paths.takeIf { detail != null || it.isNotEmpty() }, titleValues = titles.takeIf { detail != null || it.isNotEmpty() }, piExtensions = extensions.takeIf { detail?.supportsPiExtensions == true }, codexAuth = codexAuth, presetId = presetId.takeIf { base == null }, presetModeId = mode?.id, presetEndpoint = endpoint))
    val loaded = detail
    val notSet = stringResource(R.string.settings_agent_not_set)
    val defaultLabel = stringResource(R.string.create_default)
    val enabledLabel = stringResource(R.string.settings_agent_enabled)
    val disabledLabel = stringResource(R.string.settings_agent_disabled)
    val modelLabel = stringResource(R.string.settings_agent_model)
    val modeLabel = stringResource(R.string.settings_agent_mode)
    val titleSelectors = remember(capabilities, modelLabel, modeLabel) {
        val options = capabilities.options.toMutableList()
        if (capabilities.models.isNotEmpty() && options.none { it.id == "model" }) {
            options.add(0, ProviderOption("model", modelLabel, "select", null, null, capabilities.models))
        }
        if (capabilities.modes.isNotEmpty() && options.none { it.id == "mode" }) {
            options.add(ProviderOption("mode", modeLabel, "select", null, null, capabilities.modes))
        }
        options
    }
    val showsTitle = preset == null && !capabilities.sessionTitle && titleSelectors.isNotEmpty()
    val titleSummary = remember(titleSelectors, titles, enabledLabel, disabledLabel) {
        titleSelectors.mapNotNull { option ->
            val override = titles[option.id] ?: return@mapNotNull null
            when {
                option.type == "boolean" -> (override as? Boolean)?.let { "${option.name}: ${if (it) enabledLabel else disabledLabel}" }
                override is String && override.isNotEmpty() -> option.choices.find { it.id == override }?.name ?: override
                else -> null
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
    fun refreshCapabilities() = tasks.launch {
        refreshing = true; refreshProgress = null; refreshResult = null
        refreshResult = try {
            val result = service.refreshModels(scope, machineId, editor.id)
            capabilities = result.capabilities
            when { result.authRequired -> R.string.settings_agent_auth_required; result.success -> R.string.settings_agent_refresh_done; else -> R.string.settings_agent_write_unknown }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { R.string.settings_agent_write_unknown }
        finally { refreshing = false; refreshProgress = null }
    }
    fun testConnection() = tasks.launch {
        loading = true; failed = false
        try { capabilities = service.probe(scope, machineId, editor.id).capabilities }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Scaffold(containerColor = LodyTheme.colors.bgBase, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(LodyIcons.Back, stringResource(R.string.settings_agent_back)) }
            Text(stringResource(if (base == null) R.string.settings_agent_add else R.string.settings_agent_edit), Modifier.weight(1f), style = LodyTheme.typography.headline)
            // Read-only providers and machines offer neither Save nor More.
            if (manageable) {
                TextButton(enabled = editable && fields.valid && (base == null || detail != null), onClick = { onSave(fields) }) { Text(stringResource(R.string.settings_agent_save)) }
                if (base != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(LodyIcons.More, stringResource(R.string.settings_agent_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(enabled = editable && detail != null && !refreshing, text = { Text(stringResource(R.string.settings_agent_refresh_models)) },
                            onClick = { menu = false; refreshCapabilities() })
                        DropdownMenuItem(enabled = editable && detail != null, text = { Text(stringResource(R.string.settings_agent_test)) }, onClick = { menu = false; testConnection() })
                        DropdownMenuItem(enabled = editable, text = { Text(stringResource(R.string.settings_agent_delete), color = LodyTheme.colors.dangerDefault) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = LodySpacing.xxl)) {
            if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            // 1. Header card.
            Column(Modifier.fillMaxWidth().padding(LodyLayout.screenPaddingX).background(LodyTheme.colors.bgRaised, RoundedCornerShape(LodyRadius.lg)).padding(LodySpacing.lg),
                verticalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
                    AgentMark(cliType, agentType, LodyTheme.colors.textPrimary, Modifier.size(LodySize.avatarMd))
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { catalog.find { it.id == selection }?.name ?: agentType }, style = LodyTheme.typography.headline, color = LodyTheme.colors.textPrimary, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                            PresenceDot(machine.online)
                            Text(machine.name.ifBlank { machine.id }, style = LodyTheme.typography.footnote, color = LodyTheme.colors.textSecondary, maxLines = 1)
                        }
                    }
                }
                if (base != null) ProviderQuotaLine(quotas, onClick = { sheet = EditorSheet.Usage })
                if (refreshing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                    CircularProgressIndicator(Modifier.size(LodyIconSize.sm), color = LodyTheme.colors.textSecondary, strokeWidth = LodyStroke.strong)
                    Text(refreshLine(refreshProgress), style = LodyTheme.typography.footnote, color = LodyTheme.colors.textSecondary)
                } else refreshResult?.let { Text(stringResource(it), style = LodyTheme.typography.footnote, color = LodyTheme.colors.textSecondary) }
                if (error != null) Text(providerError(error), style = LodyTheme.typography.footnote, color = LodyTheme.colors.dangerDefault)
                if (failed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_agent_load_failed), Modifier.weight(1f), style = LodyTheme.typography.footnote, color = LodyTheme.colors.textSecondary)
                    TextButton(onClick = { tasks.launch { load() } }, enabled = !loading) { Text(stringResource(R.string.settings_agent_retry)) }
                }
            }

            // 2. Basics.
            SettingsHeading(stringResource(R.string.settings_agent_basics))
            if (base == null) ProviderChoiceField(stringResource(R.string.settings_agent_type), selection,
                catalog.map { ProviderChoice(it.id, it.name) } + presets.map { ProviderChoice("preset:${it.id}", it.name) }, editable) { id ->
                selection = id; envPatch = emptyMap(); paths = emptyMap(); titles = emptyMap(); endpoint = null; presetMode = null; codexAuth = null; extensions = emptyList()
                val p = presets.find { "preset:${it.id}" == id }; presetId = p?.id
                val c = catalog.find { it.id == id }
                cliType = p?.cliType ?: c?.cliType.orEmpty(); agentType = p?.agentType ?: c?.agentType.orEmpty()
                name = p?.name ?: c?.name.orEmpty()
            } else ProviderValueRow(stringResource(R.string.settings_agent_type), providerKind(base, catalog), onClick = null)
            ProviderValueRow(stringResource(R.string.settings_agent_name), name, onClick = { sheet = EditorSheet.Name }, enabled = editable, placeholder = notSet)

            // 3. Command (Custom only).
            if (cliType == "custom") {
                SettingsHeading(stringResource(R.string.settings_agent_command))
                ProviderValueRow(stringResource(R.string.settings_agent_command), command, onClick = { sheet = EditorSheet.Command }, enabled = editable, placeholder = notSet)
                ProviderValueRow(stringResource(R.string.settings_agent_arguments), arguments.replace('\n', ' '), onClick = { sheet = EditorSheet.Arguments }, enabled = editable, placeholder = notSet)
            }

            // 4. Account.
            val codex = (detail?.supportsCodexProfiles ?: machine.supportsCodexProfiles) && agentType == "codex"
            val endpoints = detail?.endpoints.orEmpty()
            if (preset != null || codex || endpoints.isNotEmpty()) SettingsHeading(stringResource(R.string.settings_agent_account))
            if (preset != null) {
                val tokenSet = envPatch[preset.tokenEnvKey]?.isNotEmpty() == true || (preset.tokenEnvKey in detail?.envKeys.orEmpty() && !envPatch.containsKey(preset.tokenEnvKey))
                ProviderValueRow(stringResource(R.string.settings_agent_api_key_short), stringResource(if (tokenSet) R.string.settings_agent_set else R.string.settings_agent_not_set),
                    onClick = { sheet = EditorSheet.ApiKey }, enabled = editable)
                if (preset.modes.isNotEmpty()) ProviderChoiceField(stringResource(R.string.settings_agent_endpoint), mode?.id.orEmpty(), preset.modes.map { ProviderChoice(it.id, it.name) }, editable) { presetMode = it; endpoint = null }
                if (mode?.endpoints?.isNotEmpty() == true) ProviderChoiceField(stringResource(R.string.settings_agent_endpoint), endpoint ?: mode.endpoint.orEmpty(), mode.endpoints.map { ProviderChoice(it.url, it.name) }, editable) { endpoint = it }
                if (mode?.customEndpoint == true) ProviderValueRow(stringResource(R.string.settings_agent_endpoint), endpoint ?: mode.endpoint.orEmpty(), onClick = { sheet = EditorSheet.CustomEndpoint }, enabled = editable, placeholder = notSet)
            }
            if (codex) {
                ProviderChoiceField(stringResource(R.string.settings_agent_auth_method), codexAuth?.mode ?: "chatgpt", listOf(ProviderChoice("chatgpt", "ChatGPT"), ProviderChoice("api-key", stringResource(R.string.settings_agent_api_key_short))), editable) {
                    codexAuth = ProviderCodexAuth(it, codexAuth?.profileId ?: java.util.UUID.randomUUID().toString(), if (it == "api-key") codexAuth?.baseUrl ?: "https://api.openai.com/v1" else null)
                }
                if (codexAuth?.mode == "api-key") ProviderValueRow(stringResource(R.string.settings_agent_endpoint), codexAuth?.baseUrl, onClick = { sheet = EditorSheet.CodexBaseUrl }, enabled = editable, placeholder = notSet)
            }
            endpoints.forEach { (key, original) -> ProviderValueRow(key, envPatch[key] ?: original, onClick = { endpointSheet = key }, enabled = editable, placeholder = notSet) }

            // 5. Title generation.
            if (showsTitle) {
                Spacer(Modifier.height(LodySpacing.md))
                ProviderValueRow(
                    stringResource(R.string.settings_agent_title_generation),
                    titleSummary,
                    onClick = { sheet = EditorSheet.TitleGeneration },
                    enabled = editable,
                    placeholder = defaultLabel,
                )
                ProviderFootnote(stringResource(R.string.settings_agent_title_generation_hint))
            }

            // 6. Environment variables.
            ProviderEnvironment(detail?.envKeys.orEmpty().filterNot { it in preset?.protectedEnvKeys.orEmpty() }, envPatch.filterKeys { it !in preset?.protectedEnvKeys.orEmpty() }, editable) { changed ->
                envPatch = envPatch.filterKeys { it in preset?.protectedEnvKeys.orEmpty() } + changed
            }
            if (extensions.isNotEmpty()) ProviderFootnote(stringResource(R.string.settings_agent_pi_extensions, extensions.size))
            if ("sorbetCredentials" in detail?.unavailableActions.orEmpty() || "sorbetProviders" in detail?.unavailableActions.orEmpty()) ProviderFootnote(stringResource(R.string.settings_agent_sorbet_unavailable))

            // 7. Delete.
            if (base != null && manageable) {
                Spacer(Modifier.height(LodyLayout.sectionGap))
                ProviderValueRow(stringResource(R.string.settings_agent_delete), null, onClick = onDelete, enabled = editable, labelColor = LodyTheme.colors.dangerDefault)
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        EditorSheet.Name -> ProviderTextSheet(stringResource(R.string.settings_agent_name), name, close, { name = it; close() }, allowEmpty = false)
        EditorSheet.Command -> ProviderTextSheet(stringResource(R.string.settings_agent_command), command, close, { command = it; close() })
        EditorSheet.Arguments -> ProviderTextSheet(stringResource(R.string.settings_agent_arguments), arguments, close, { arguments = it; close() },
            multiline = true, hint = stringResource(R.string.settings_agent_arguments_hint))
        EditorSheet.ApiKey -> if (preset != null) ProviderTextSheet(stringResource(R.string.settings_agent_api_key_short), "", close,
            { envPatch = envPatch + (preset.tokenEnvKey to it); close() }, secret = true, hint = stringResource(R.string.settings_agent_api_key), allowEmpty = false)
        EditorSheet.CustomEndpoint -> ProviderTextSheet(stringResource(R.string.settings_agent_endpoint), endpoint ?: mode?.endpoint.orEmpty(), close, { endpoint = it; close() })
        EditorSheet.CodexBaseUrl -> ProviderTextSheet(stringResource(R.string.settings_agent_endpoint), codexAuth?.baseUrl.orEmpty(), close, { codexAuth = codexAuth?.copy(baseUrl = it); close() })
        EditorSheet.TitleGeneration -> if (showsTitle) {
            ModalBottomSheet(onDismissRequest = close, containerColor = LodyTheme.colors.bgBase) {
                RunConfigSheet(
                    title = stringResource(R.string.settings_agent_title_generation),
                    rows = titleSelectors.map { option ->
                        if (option.type == "boolean") {
                            RunConfigRow(option.id, option.name, emptyList(), toggle = titles[option.id] as? Boolean ?: option.booleanValue ?: false)
                        } else {
                            val selectedId = titles[option.id] as? String
                            val defaultOption = PickerOption(TITLE_DEFAULT, defaultLabel, selected = selectedId == null)
                            RunConfigRow(
                                option.id,
                                option.name,
                                listOf(defaultOption) + option.choices.map { PickerOption(it.id, it.name, selected = it.id == selectedId) },
                            )
                        }
                    },
                    defaultLabel = defaultLabel,
                ) { rowId, pickedId ->
                    val option = titleSelectors.find { it.id == rowId } ?: return@RunConfigSheet
                    titles = when {
                        option.type == "boolean" -> titles + (rowId to (pickedId == RUN_CONFIG_ON))
                        pickedId == TITLE_DEFAULT -> titles - rowId
                        else -> titles + (rowId to pickedId)
                    }
                }
            }
        }
        EditorSheet.Usage -> ProviderQuotaSheet(name.ifBlank { base?.name.orEmpty() }, quotas, offline = !machine.online,
            refresh = if (editable && machine.online && loaded != null && loaded.supportsRateLimitsRefresh && "rate-limits-refresh" !in loaded.unavailableActions)
                suspend { service.operation(scope, machineId, editor.id, "rate-limits-refresh", cliType, agentType).success } else null, onDismiss = close)
        null -> Unit
    }
    endpointSheet?.let { key ->
        ProviderTextSheet(key, envPatch[key] ?: detail?.endpoints?.get(key).orEmpty(), { endpointSheet = null }, { envPatch = envPatch + (key to it); endpointSheet = null })
    }
}

/** The provider's kind for its row subtitle: catalog name, "Custom", or the agent type. */
@Composable
internal fun providerKind(provider: AgentProvider, catalog: List<ProviderCatalogEntry>): String =
    if (provider.cliType == "custom") stringResource(R.string.settings_agent_kind_custom)
    else catalog.find { it.cliType == provider.cliType && it.agentType == provider.agentType }?.name ?: provider.agentType
