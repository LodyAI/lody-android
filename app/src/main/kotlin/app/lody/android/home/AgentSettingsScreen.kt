package app.lody.android.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.lody.android.R
import app.lody.android.designsystem.*
import app.lody.android.runtime.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSettingsScreen(home: ChatListUiState, service: AgentProviderService?, scope: RuntimeScope?, onBack: () -> Unit) {
    val state = remember(service, scope) { if (service != null && scope != null) AgentSettingsState(service, scope) else null }
    val tasks = rememberCoroutineScope()
    var machineId by rememberSaveable(scope) { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    val data = state?.snapshot
    val machine = data?.machines?.find { it.id == machineId } ?: data?.machines?.firstOrNull()
    var editor by remember(scope, machine?.id) { mutableStateOf<ProviderEditor?>(null) }
    var deletion by remember(scope, machine?.id) { mutableStateOf<ProviderAction?>(null) }
    var deletionName by remember { mutableStateOf("") }
    var usageFor by remember(scope, machine?.id) { mutableStateOf<AgentProvider?>(null) }
    val currentHome by rememberUpdatedState(home)
    LaunchedEffect(state) {
        snapshotFlow { currentHome.sync }.collect { state?.refresh() }
    }
    LaunchedEffect(state, service, scope) {
        if (state != null && service != null && scope != null) service.changes(scope).collect { state.refresh(it) }
    }
    LaunchedEffect(state, service, scope) {
        if (state != null && service != null && scope != null) service.refreshProgress(scope).collect { state.onRefreshProgress(it) }
    }
    val canManage = state?.canManage(machine) == true
    BackHandler { if (editor != null) editor = null else onBack() }
    val editing = editor
    if (editing != null && machine != null && state != null) {
        ProviderEditorScreen(editing, data?.catalog.orEmpty().filter { it.cliType != "builtin" || it.agentType == "deepseek" || machine.supportsSetup }, canManage,
            busy = state.busy, error = state.error, service = service!!, scope = scope!!, machine = machine, presets = data?.presets.orEmpty(),
            quotas = editing.base?.let { home.snapshot?.agentUsage?.get(machine.id)?.forProvider(it) }.orEmpty(),
            onBack = { editor = null }, onSave = { fields ->
                tasks.launch {
                    if (state.perform(ProviderAction.Save(machine.id, editing.id, editing.base, fields))) editor = null
                }
            }, onDelete = {
                // Confirmation shows over the list; the deletion itself is unchanged.
                editing.base?.let { deletionName = it.name; deletion = ProviderAction.Delete(it); editor = null }
            })
        return
    }
    Scaffold(containerColor = LodyTheme.colors.bgBase, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = LodySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(LodyIcons.Back, stringResource(R.string.settings_agent_back)) }
            Text(stringResource(R.string.settings_agents), Modifier.weight(1f), style = LodyTheme.typography.headline, color = LodyTheme.colors.textPrimary)
            IconButton(onClick = { tasks.launch { state?.refresh() } }, enabled = state != null && !state.busy) {
                Icon(LodyIcons.Refresh, stringResource(R.string.settings_agent_refresh))
            }
            // A new provider on the selected computer.
            IconButton(onClick = { editor = ProviderEditor() }, enabled = canManage && machine?.online == true) {
                Icon(LodyIcons.Plus, stringResource(R.string.settings_agent_add))
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (state?.busy == true) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state?.error != null) item { ProviderNotice(providerError(state.error)) }
            if (state?.authRequired == true) item { ProviderNotice(stringResource(R.string.settings_agent_auth_required)) }
            if (machine == null) {
                item { ProviderNotice(stringResource(if (state?.busy == true) R.string.settings_agent_loading else R.string.settings_agent_no_machines)) }
            } else {
                item {
                    Column(Modifier.padding(vertical = LodySpacing.md)) {
                        SettingsLine(machine.name.ifBlank { machine.id }, LodyIcons.Machine, machineLine(machine),
                            onClick = { picker = true }.takeIf { data?.machines.orEmpty().size > 1 }, leading = { PresenceDot(machine.online) })
                    }
                }
                if (!machine.online || machine.readOnly) item {
                    ProviderNotice(stringResource(if (machine.readOnly) R.string.settings_agent_read_only else R.string.settings_agent_offline_hint))
                }
                item { SettingsHeading(stringResource(R.string.settings_agent_providers)) }
                val providers = data?.providers.orEmpty().filter { it.machineId == machine.id }
                if (providers.isEmpty()) item { ProviderNotice(stringResource(R.string.settings_agent_empty)) }
                items(providers, key = { "provider-${it.id}" }) { provider ->
                    ProviderRow(provider, data?.catalog.orEmpty(), home.snapshot?.agentUsage?.get(machine.id)?.forProvider(provider).orEmpty(),
                        subtitle = if (state?.refreshing == provider.id) refreshLine(state.refreshProgress) else null,
                        actionsEnabled = canManage && !provider.readOnly,
                        onEdit = { editor = ProviderEditor(provider) }, onUsage = { usageFor = provider },
                        onRefresh = { tasks.launch { state?.refreshModels(provider) } },
                        onDelete = { deletionName = provider.name; deletion = ProviderAction.Delete(provider) })
                }
                val setups = data?.setups.orEmpty().filter { it.machineId == machine.id }
                if (setups.isNotEmpty()) item { SettingsHeading(stringResource(R.string.settings_agent_setups)) }
                items(setups, key = { "setup-${it.id}" }) { setup ->
                    Column {
                        SettingsLine(setup.name, LodyIcons.Clock, setupStatus(setup.status), leading = {
                            AgentMark("builtin", setup.agentType, LodyTheme.colors.textSecondary, Modifier.size(LodyIconSize.lg))
                        })
                        if (setup.failureCode != null) ProviderNotice(stringResource(R.string.settings_agent_setup_failed))
                        Row(Modifier.fillMaxWidth().padding(horizontal = LodySpacing.lg), horizontalArrangement = Arrangement.End) {
                            if (setup.canRetry) TextButton(enabled = canManage && machine.supportsSetup, onClick = { tasks.launch { state?.perform(ProviderAction.RetrySetup(setup)) } }) {
                                Text(stringResource(R.string.settings_agent_retry))
                            }
                            if (setup.canDelete) TextButton(enabled = canManage, onClick = { deletionName = setup.name; deletion = ProviderAction.DeleteSetup(setup) }) {
                                Text(stringResource(R.string.settings_agent_remove), color = LodyTheme.colors.dangerDefault)
                            }
                        }
                    }
                }
            }
        }
    }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }, containerColor = LodyTheme.colors.bgBase) {
        SettingsHeading(stringResource(R.string.settings_agent_machines))
        LazyColumn {
            items(data?.machines.orEmpty(), key = { it.id }) { item ->
                SettingsLine(item.name.ifBlank { item.id }, LodyIcons.Machine, machineLine(item),
                    onClick = { machineId = item.id; picker = false }, leading = { PresenceDot(item.online) }, trailing = {
                        if (item.id == machine?.id) Icon(LodyIcons.Check, stringResource(R.string.settings_agent_selected), tint = LodyTheme.colors.accentFg)
                    })
            }
        }
        Spacer(Modifier.height(LodySpacing.xxl))
    }
    usageFor?.let { provider ->
        val quotas = machine?.let { home.snapshot?.agentUsage?.get(it.id)?.forProvider(provider) }.orEmpty()
        // Refresh usage is offered only when the provider's detail says the operation is available.
        var refreshable by remember(provider.id) { mutableStateOf(false) }
        LaunchedEffect(provider.id, canManage, machine?.online) {
            refreshable = false
            if (service != null && scope != null && canManage && !provider.readOnly && machine?.online == true) refreshable = try {
                service.detail(scope, provider.machineId, provider.id).let { it.supportsRateLimitsRefresh && "rate-limits-refresh" !in it.unavailableActions }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { false }
        }
        ProviderQuotaSheet(provider.name, quotas, offline = machine?.online != true,
            refresh = if (refreshable && service != null && scope != null && canManage)
                suspend { service.operation(scope, provider.machineId, provider.id, "rate-limits-refresh", provider.cliType, provider.agentType).success } else null,
            onDismiss = { usageFor = null })
    }
    val pending = deletion
    if (pending != null) AlertDialog(onDismissRequest = { deletion = null },
        title = { Text(stringResource(R.string.settings_agent_delete_title, deletionName)) },
        text = { Text(stringResource(R.string.settings_agent_delete_hint)) },
        confirmButton = { TextButton(enabled = canManage, onClick = { deletion = null; tasks.launch { state?.perform(pending) } }) { Text(stringResource(R.string.settings_agent_delete), color = LodyTheme.colors.dangerDefault) } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.settings_agent_cancel)) } })
}

internal data class ProviderEditor(val base: AgentProvider? = null, val id: String = base?.id ?: UUID.randomUUID().toString())

@Composable
private fun machineLine(machine: ProviderMachine): String =
    stringResource(if (machine.online) R.string.settings_agent_online else R.string.settings_agent_offline) + " · " +
        stringResource(if (machine.readOnly) R.string.settings_agent_teammate_machine else R.string.settings_agent_own_machine)

/** "Reading options for <model> (3/40)" while a refresh reads models; "Refreshing…" before the first. */
@Composable
internal fun refreshLine(progress: RuntimeEvent.ProviderRefreshProgress?): String =
    if (progress == null) stringResource(R.string.settings_agent_refreshing)
    else stringResource(R.string.settings_agent_reading_model_options, progress.modelName, progress.index, progress.total)

@Composable
internal fun PresenceDot(online: Boolean) {
    Box(Modifier.size(LodySize.dot).background(if (online) LodyTheme.colors.accentFg else LodyTheme.colors.textSecondary, CircleShape))
}
@Composable
private fun ProviderNotice(text: String) {
    Text(text, Modifier.padding(horizontal = LodySpacing.xxl, vertical = LodySpacing.md), style = LodyTheme.typography.footnote, color = LodyTheme.colors.textSecondary)
}
@Composable
private fun setupStatus(status: String): String = stringResource(when (status) {
    "queued" -> R.string.settings_agent_setup_queued
    "preparing-runtime" -> R.string.settings_agent_setup_preparing
    "verifying" -> R.string.settings_agent_setup_verifying
    "awaiting-auth" -> R.string.settings_agent_auth_required
    "failed" -> R.string.settings_agent_setup_failed
    else -> R.string.settings_agent_setup_pending
})
@Composable
internal fun providerError(code: String?): String = stringResource(when (code) {
    "provider_load_failed" -> R.string.settings_agent_load_failed
    "setting_conflict", "provider_conflict" -> R.string.settings_agent_conflict
    "setting_read_only", "provider_read_only", "provider_not_owner" -> R.string.settings_agent_read_only
    else -> R.string.settings_agent_write_unknown
})


/** Agent icon, name, kind and the compact quota line; the overflow menu keeps Edit, Refresh and Delete. */
@Composable
private fun ProviderRow(provider: AgentProvider, catalog: List<ProviderCatalogEntry>, quotas: List<UsageQuota>, subtitle: String?, actionsEnabled: Boolean,
    onEdit: () -> Unit, onUsage: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(provider.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = LodySize.listRowTwoLine).clickable(onClick = onEdit)
        .padding(horizontal = LodySpacing.lg, vertical = LodySpacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.md)) {
        AgentMark(provider.cliType, provider.agentType, LodyTheme.colors.textPrimary, Modifier.size(LodyIconSize.lg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
            Text(provider.name, style = LodyTheme.typography.body, color = LodyTheme.colors.textPrimary, maxLines = 1)
            Text(subtitle ?: providerKind(provider, catalog), style = LodyTheme.typography.footnote, color = LodyTheme.colors.textSecondary, maxLines = 1)
            ProviderQuotaLine(quotas, onClick = onUsage)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = actionsEnabled) {
                Icon(LodyIcons.More, stringResource(R.string.settings_agent_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.settings_agent_edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.settings_agent_refresh_models)) }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text(stringResource(R.string.settings_agent_delete), color = LodyTheme.colors.dangerDefault) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
