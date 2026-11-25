// Home in the mobile design language; iOS counterpart: LodyKit HomeController + HomeTabs + ChatListView.
//
// Chats tab: workspace capsule leading, search and view trailing; search expands over the bar and
// hides the capsule. Connection trouble floats as a centred pill over the bar (web parity). Chats
// are grouped by time. A row leads with its title and trails with status or age; an optional second line carries
// project, branch and PR state, chosen in the view menu. A floating bar holds labelled Chats /
// Schedules / Settings tabs and a separate create button: a new chat, or "+" for a new schedule on
// Schedules. The Projects tab is hidden for now; its cards, menus and sheets stay for its return.

package app.lody.android.home

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.lody.android.auth.WorkspaceMember

import app.lody.android.designsystem.LodyFilledIconButton

import app.lody.android.designsystem.LodyIconButton as IconButton

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.background
import app.lody.android.designsystem.LodyRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.platform.LocalDensity
import app.lody.android.designsystem.LodyMotion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import app.lody.android.designsystem.LodySpring
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lody.android.R
import app.lody.android.auth.Workspace
import app.lody.android.runtime.CatalogSnapshot
import app.lody.android.designsystem.LodyElevations
import app.lody.android.designsystem.LodyIconSize
import app.lody.android.designsystem.LodyIcons
import app.lody.android.designsystem.LodyLayout
import app.lody.android.designsystem.LodySize
import app.lody.android.designsystem.LodySpacing
import app.lody.android.designsystem.LodyTheme
import app.lody.android.designsystem.LodyWorkingGrid
import app.lody.android.designsystem.shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** Everything Home renders; HomeActivity derives it from account and catalog state. */
data class ChatListUiState(
    val workspaceName: String,
    val initial: String,
    /** The selected workspace's logo and the user's avatar (HTTPS), null when they have none. */
    val workspaceImage: String? = null,
    val accountImage: String? = null,
    /** Catalog connection; Home floats a pill over the bar unless live. */
    val sync: HomeSyncState,
    val accountName: String,
    /** Search results are by time; the Chats list follows the view menu's grouping of [snapshot]. */
    val timeline: List<TimeSection>,
    val snapshot: CatalogSnapshot?,
    /** The signed-in user, for "My tasks". */
    val userId: String?,
    val projects: List<ChatGroup>,
    val placeholder: String,
    val workspaces: List<Workspace>,
    val workspaceId: String?,
    val members: Map<String, WorkspaceMember> = emptyMap(),
    val accountEmail: String = "",
)

enum class HomeTab(@StringRes val label: Int) {
    Chats(R.string.home_tab_chats),
    Schedules(R.string.home_tab_schedules),
    Settings(R.string.home_tab_settings);

    val icon: ImageVector
        get() = when (this) {
            Chats -> LodyIcons.Sessions
            Schedules -> LodyIcons.Clock
            Settings -> LodyIcons.Settings
        }
}

/** The schedule editor's route for a new schedule (never a schedule id: ids are `[A-Za-z0-9_-]`). */
private const val NEW_SCHEDULE = "+"

private val BarHeight = LodySize.controlLg + LodySpacing.sm

@Composable
fun ChatListScreen(
    state: ChatListUiState,
    onSelectWorkspace: (Workspace) -> Unit,
    onSignOut: () -> Unit,
    onOpenChat: (ChatItem) -> Unit,
    /** Archives or unarchives a conversation (runtime `archiveSession`); null while no workspace is selected. */
    onSetArchived: (suspend (String, Boolean) -> Unit)? = null,
    /** Status view and pin-section writes (runtime `statusViewCommand`); null while no workspace is selected. */
    statusView: StatusViewService? = null,
    onNewChat: () -> Unit,
    /** The Schedules tab's list and writes; none leaves the tab loading. */
    schedules: app.lody.android.schedules.ScheduleService? = null,
    /** Computers, agents, models and modes for the schedule editor. */
    creation: app.lody.android.create.CreationService? = null,
    agentProviders: AgentProviderService? = null,
    roleSettings: RoleSettingsService? = null,
    shortcutSettings: ShortcutSettingsService? = null,
    /** Last accepted settings-page reads (display only). */
    settingsCache: SettingsSnapshotStore? = null,
    githubSettings: GitHubSettingsService? = null,
    projectVisibility: ProjectVisibilityService? = null,
    scope: app.lody.android.runtime.RuntimeScope? = null,
    /** Opens a conversation by id (a schedule's last run). */
    onOpenSession: (String) -> Unit = {},
) {
    val colors = LodyTheme.colors
    val chatsListState = rememberLazyListState()
    val barScroll = remember { HomeBarScrollState() }
    var barCollapsed by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { LodySpacing.xxl.toPx() }
    LaunchedEffect(chatsListState, threshold) {
        snapshotFlow { Triple(chatsListState.firstVisibleItemIndex, chatsListState.firstVisibleItemScrollOffset, chatsListState.isScrollInProgress) }
            .collect { (index, offset, scrolling) ->
                barScroll.update(if (index > 0) threshold + offset + 1 else offset.toFloat(), scrolling, threshold)
                barCollapsed = barScroll.collapsed
            }
    }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Chats) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val notices = androidx.compose.runtime.rememberCoroutineScope()
    val notice = { text: String -> notices.launch { snackbar.showSnackbar(text) }; Unit }
    // Schedules: the list for this workspace and the open editor (a schedule id or [NEW_SCHEDULE]).
    val scheduleList = app.lody.android.schedules.rememberSchedulesState(schedules, scope)
    var agentsOpen by rememberSaveable(scope) { mutableStateOf(false) }
    var preferencesOpen by rememberSaveable { mutableStateOf(false) }
    var rolesOpen by rememberSaveable(scope) { mutableStateOf(false) }
    var shortcutsOpen by rememberSaveable(scope) { mutableStateOf(false) }
    var githubOpen by rememberSaveable(scope) { mutableStateOf(false) }
    var projectsOpen by rememberSaveable(scope) { mutableStateOf(false) }
    val deferredMessage = stringResource(R.string.settings_deferred)
    var scheduleEditor by rememberSaveable(scope) { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, scheduleList, state.sync) {
        if (tab == HomeTab.Schedules) scheduleList?.refresh()
    }
    val closeSearch = { searching = false; query = "" }
    val context = LocalContext.current
    var details by remember { mutableStateOf(ChatRowDetails.load(context)) }
    val toggleDetail = { detail: ChatRowDetail ->
        details = if (detail in details) details - detail else details + detail
        ChatRowDetails.save(context, details)
    }
    var grouping by remember { mutableStateOf(HomeGroupings.load(context)) }
    val selectGrouping = { next: HomeGrouping -> grouping = next; HomeGroupings.save(context, next) }
    var homeScope by remember { mutableStateOf(HomeGroupings.loadScope(context)) }
    val selectScope = { next: HomeScope -> homeScope = next; HomeGroupings.saveScope(context, next) }
    var settledExpanded by rememberSaveable { mutableStateOf(false) }
    val owner = state.userId.takeIf { homeScope == HomeScope.Mine }
    val archivedDone = stringResource(R.string.home_archived_done)
    val archiveFailed = stringResource(R.string.home_archive_failed)
    val unarchived = stringResource(R.string.home_unarchived)
    val unarchiveFailed = stringResource(R.string.home_unarchive_failed)
    // Long-press Archive / Unarchive (iOS row actions): one write per tap, never retried; the
    // catalog then moves the row between the open and archived lists.
    val setArchived: ((ChatItem, Boolean) -> Unit)? = onSetArchived?.let { write -> { chat: ChatItem, value: Boolean ->
        notices.launch {
            val ok = try { write(chat.id, value); true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            snackbar.showSnackbar(when {
                value && ok -> archivedDone
                value -> archiveFailed
                ok -> unarchived
                else -> unarchiveFailed
            })
        }
        Unit
    } }
    // Status view actions: the Later sheet, the sub-conversation question and Undo live in one host.
    val statusList = if (homeScope == HomeScope.Mine) app.lody.android.runtime.StatusViewScope.Mine else app.lody.android.runtime.StatusViewScope.Team
    val snapshotNow by androidx.compose.runtime.rememberUpdatedState(state.snapshot)
    // A role discovered after the runtime started can revoke an already-open action sheet.
    val readOnly = state.userId?.let { state.members[it] }?.readOnly == true
    val readOnlyNow by androidx.compose.runtime.rememberUpdatedState(readOnly)
    val statusActions = rememberStatusActions(statusView.takeUnless { readOnly }, snackbar, statusList) { snapshotNow?.sessions.orEmpty() }
    val statusFailed = stringResource(R.string.status_write_failed)
    // A section name prompt: (section id or null for a new one, its name, the row to move into a new one).
    var sectionDialog by remember { mutableStateOf<Triple<String?, String, String?>?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Section management follows the layout's grant, and waits until the section rows were read.
    val manageSections = state.snapshot?.statusView?.let { it.sectionsLoaded && it.team.canManageSections } == true
    val pinSections = remember(state.snapshot?.pinSections) { listOf<Pair<String?, String?>>(null to null) + state.snapshot?.pinSections.orEmpty().map { it.id to it.name } }
    val statusControls = remember(statusActions, statusView, manageSections, statusList, readOnly, pinSections) {
        if (statusActions == null || statusView == null || readOnly) null else {
            val run: (app.lody.android.runtime.StatusViewCommand, (Boolean) -> Unit) -> Unit = { command, done ->
                notices.launch {
                    if (readOnlyNow) { done(false); return@launch }
                    val ok = try { statusView.run(command, statusList) > 0 }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    done(ok)
                    if (!ok) snackbar.showSnackbar(statusFailed)
                }
            }
            StatusListControls(
                actions = statusActions,
                run = run,
                manageSections = manageSections,
                onNewSection = { moving -> sectionDialog = Triple(null, "", moving) },
                onRenameSection = { id, name -> sectionDialog = Triple(id, name, null) },
                onDeleteSection = { id, name -> deleting = id to name },
                target = { chat ->
                    val tree = snapshotNow?.let { ChatListModel.openedTree(it, chat.id) }.orEmpty()
                    StatusTarget(chat.id, chat.tier, tree.map { it.id }, tree.count { ChatListModel.attention(it) == ChatAttention.Running })
                },
                pinSections = pinSections,
            )
        }
    }
    LaunchedEffect(readOnly) {
        if (readOnly) { sectionDialog = null; deleting = null }
    }
    sectionDialog.takeUnless { readOnly }?.let { (id, name, moving) ->
        SectionNameDialog(stringResource(if (id == null) R.string.status_section_new else R.string.status_section_rename), name, onDismiss = { sectionDialog = null }) { next ->
            sectionDialog = null
            val created = java.util.UUID.randomUUID().toString()
            statusControls?.run?.invoke(
                if (id == null) app.lody.android.runtime.StatusViewCommand.CreateSection(created, next)
                else app.lody.android.runtime.StatusViewCommand.RenameSection(id, next),
            ) { ok ->
                // From a row's menu, the row then moves into the section it named.
                if (ok && id == null && moving != null) statusControls.run(app.lody.android.runtime.StatusViewCommand.Move(moving, app.lody.android.runtime.StatusViewTarget.Pinned(created), null)) {}
            }
        }
    }
    deleting.takeUnless { readOnly }?.let { (id, name) ->
        DeleteSectionDialog(name, onDismiss = { deleting = null }) {
            deleting = null
            statusControls?.run?.invoke(app.lody.android.runtime.StatusViewCommand.DeleteSection(id)) {}
        }
    }
    // The view menu's Archived switch (iOS `HomeArchive`): in memory, so every launch opens on open chats.
    var archived by remember { mutableStateOf(false) }
    // The Schedules view menu's Show finished switch: in memory, so ended schedules start hidden.
    var showFinished by remember { mutableStateOf(false) }
    val sections = remember(state.snapshot, grouping, owner, archived) { state.snapshot?.let { ChatListModel.home(it, grouping, owner, archived = archived) }.orEmpty() }
    val archivedTimeline = remember(state.snapshot, archived) { if (archived) state.snapshot?.let { ChatListModel.timeline(it, archived = true) }.orEmpty() else emptyList() }

    BackHandler(enabled = searching) { closeSearch() }

    if (preferencesOpen) {
        PreferencesScreen(accountId = scope?.accountId, onBack = { preferencesOpen = false })
        return
    }
    if (shortcutsOpen) {
        ShortcutsScreen(state, shortcutSettings, scope, settingsCache, onBack = { shortcutsOpen = false })
        return
    }
    if (githubOpen) {
        GitHubSettingsScreen(githubSettings, scope, onBack = { githubOpen = false })
        return
    }
    if (projectsOpen) {
        ProjectGroupsScreen(state, creation, scope, projectVisibility, settingsCache, onBack = { projectsOpen = false })
        return
    }
    if (rolesOpen) {
        RolesScreen(state, roleSettings, scope, onBack = { rolesOpen = false })
        return
    }
    if (agentsOpen) {
        AgentSettingsScreen(state, agentProviders, scope, onBack = { agentsOpen = false })
        return
    }

    val editing = scheduleEditor
    if (editing != null && scheduleList != null) {
        // Full screen over Home, like a new chat; the list keeps its state underneath.
        app.lody.android.schedules.ScheduleEditorScreen(
            scheduleList, editing.takeUnless { it == NEW_SCHEDULE }, state.snapshot, creation,
            onClose = { scheduleEditor = null }, onNotice = notice,
            onOpenSession = { scheduleEditor = null; onOpenSession(it) },
        )
        return
    }

    CompositionLocalProvider(LocalArchivedPage provides (archived && tab == HomeTab.Chats)) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = homePageColor(),
        // Bars handle their own edges; Scaffold supplies the bottom inset when search hides the bar.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                searching -> SearchTopBar(query, onQueryChange = { query = it }, onClose = closeSearch)
                tab == HomeTab.Chats -> Box {
                    HomeTopBar(state, onSearch = { searching = true }, onSelectWorkspace = onSelectWorkspace) {
                        ViewMenu(homeScope, selectScope, grouping, selectGrouping, details, toggleDetail, archived) { archived = it }
                    }
                    HomeSyncPill(state.sync, Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)))
                }
                // Schedules keeps the Chats header (workspace, sync, search) with its own view menu.
                tab == HomeTab.Schedules -> Box {
                    HomeTopBar(state, onSearch = { searching = true }, onSelectWorkspace = onSelectWorkspace) {
                        ScheduleViewMenu(showFinished) { showFinished = it }
                    }
                    HomeSyncPill(state.sync, Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)))
                }
                else -> TitleTopBar(stringResource(tab.label), onBack = null)
            }
        },
        bottomBar = {
            if (!searching) {
                val adds = tab == HomeTab.Schedules && scheduleList != null
                HomeBottomBar(
                    selected = tab,
                    collapsed = tab == HomeTab.Chats && barCollapsed,
                    onSelect = { next ->
                        // The compact bar shows only the selected tab; tapping it restores the full bar.
                        if (next == tab && barCollapsed) {
                            barScroll.expand()
                            barCollapsed = false
                        } else tab = next
                    },
                    adds = adds,
                    onCreate = { if (state.workspaces.firstOrNull { it.id == state.workspaceId }?.contentMode != app.lody.android.auth.WorkspaceContentMode.PLAINTEXT) notice(state.placeholder) else if (adds) scheduleEditor = NEW_SCHEDULE else onNewChat() },
                )
            }
        },
    ) { padding ->
        val selectedWorkspace = state.workspaces.firstOrNull { it.id == state.workspaceId }
        when {
            tab != HomeTab.Settings && selectedWorkspace?.contentMode != app.lody.android.auth.WorkspaceContentMode.PLAINTEXT -> {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(state.placeholder, style = LodyTheme.typography.body, modifier = Modifier.padding(horizontal = app.lody.android.designsystem.LodySpacing.lg))
                    if (selectedWorkspace?.contentMode == app.lody.android.auth.WorkspaceContentMode.UNKNOWN) {
                        TextButton(onClick = { onSelectWorkspace(selectedWorkspace) }) { Text(stringResource(R.string.workspace_mode_retry)) }
                    }
                }
            }
            tab == HomeTab.Schedules -> app.lody.android.schedules.SchedulesList(
                scheduleList, if (searching) query else "", showFinished, { showFinished = true }, padding,
                onOpen = { scheduleEditor = it.scheduleId },
                onNew = { scheduleEditor = NEW_SCHEDULE },
                onOpenSession = onOpenSession,
                onNotice = notice,
            )
            // First run (web `showChatOnboarding`): a loaded catalog with no computers and no chats at all,
            // archived included, regardless of the view menu's scope. Never while loading, searching or archived.
            tab == HomeTab.Chats && !searching && !archived && state.snapshot?.let { it.machineIds.isEmpty() && it.sessions.isEmpty() } == true ->
                HomeOnboarding(padding)
            searching -> HomeList(
                ChatListModel.searchTimeline(if (archived && tab == HomeTab.Chats) archivedTimeline else state.timeline, query).map { HomeSection("bucket-${it.bucket}", HomeSection.Header.Bucket(it.bucket), it.chats) },
                details, padding, empty = stringResource(R.string.home_search_empty, query.trim()).takeIf { query.isNotBlank() },
                pinsLabels = false, onOpenChat = onOpenChat,
                onSetArchived = setArchived,
                archivedList = archived && tab == HomeTab.Chats,
                owners = state.members.takeIf { homeScope == HomeScope.Team },
            )
            // A project header already names the project, so rows drop it from their detail line.
            tab == HomeTab.Chats -> HomeList(
                sections,
                details,
                padding,
                // The catalog placeholder (loading / offline) wins; a loaded catalog without archived chats says so.
                empty = if (archived && state.snapshot != null) stringResource(R.string.home_archived_empty) else state.placeholder,
                listState = chatsListState,
                projectIndex = !archived && grouping == HomeGrouping.Project,
                owners = state.members.takeIf { homeScope == HomeScope.Team },
                settledExpanded = settledExpanded,
                onToggleSettled = { settledExpanded = !settledExpanded },
                onOpenChat = onOpenChat,
                onSetArchived = setArchived,
                archivedList = archived,
                status = statusControls.takeIf { !archived },
            )
            else -> SettingsHome(state, padding, onSelectWorkspace, onSignOut, onAgents = { agentsOpen = true }, onPreferences = { preferencesOpen = true }, onRoles = { rolesOpen = true }, onShortcuts = { shortcutsOpen = true }, onGitHub = { githubOpen = true }, onProjects = { projectsOpen = true }, onDeferred = { notice(deferredMessage) })
        }
    }
    }
    }
}

// MARK: - Top bars

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    state: ChatListUiState,
    onSearch: () -> Unit,
    onSelectWorkspace: (Workspace) -> Unit,
    menu: @Composable () -> Unit,
) {
    val colors = LodyTheme.colors
    TopAppBar(
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        title = { WorkspaceCapsule(state, onSelectWorkspace) },
        actions = {
            val search = stringResource(R.string.home_search)
            IconButton(onClick = onSearch, modifier = Modifier.semantics { contentDescription = search }) {
                Glyph(LodyIcons.Search, colors.iconPrimary)
            }
            menu()
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = homePageColor(), scrolledContainerColor = homePageColor()),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleTopBar(title: String, onBack: (() -> Unit)?) {
    val colors = LodyTheme.colors
    TopAppBar(
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        title = { Text(title, style = LodyTheme.typography.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                val back = stringResource(R.string.chat_back)
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = back }) {
                    Glyph(LodyIcons.Back, colors.iconPrimary)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = homePageColor(), scrolledContainerColor = homePageColor()),
    )
}

/** Workspace logo + name; tapping switches workspace. Connection state floats in [HomeSyncPill] instead. */
@Composable
private fun WorkspaceCapsule(state: ChatListUiState, onSelect: (Workspace) -> Unit) {
    val colors = LodyTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.home_account)
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "$label, ${state.workspaceName}" }
                .padding(end = LodySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm),
        ) {
            // The workspace's real logo once loaded, its initial until then (as iOS).
            Avatar(state.workspaceImage, state.initial, LodySize.avatarMd)
            // Capped so a long name ellipsizes before the centred sync pill.
            Text(
                state.workspaceName,
                style = LodyTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = LodyLayout.trailingMetaMaxWidth),
            )
        }
        // A menu-width panel (iOS #2574 / #2586): 200–260 dp, each name on one line fading out at
        // its trailing edge, never wrapping.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.bgOverlay,
            modifier = Modifier.widthIn(min = 200.dp, max = 260.dp)) {
            if (state.workspaces.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_no_workspaces), style = LodyTheme.typography.body, color = colors.textSecondary) },
                    onClick = { open = false },
                    enabled = false,
                )
            }
            state.workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = { app.lody.android.designsystem.LodyFadingText(workspace.name, LodyTheme.typography.body, colors.textPrimary, Modifier.fillMaxWidth()) },
                    leadingIcon = { Avatar(workspace.image, workspace.name, LodyIconSize.lg) },
                    trailingIcon = { Row(horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                        if (workspace.contentMode == app.lody.android.auth.WorkspaceContentMode.E2EE) Icon(LodyIcons.Lock, contentDescription = "E2EE", tint = colors.textSecondary, modifier = Modifier.size(LodyIconSize.sm))
                        if (workspace.id == state.workspaceId) Glyph(LodyIcons.Check, colors.accentFg, LodyIconSize.sm)
                    } },
                    onClick = { open = false; if (workspace.id != state.workspaceId || workspace.contentMode != app.lody.android.auth.WorkspaceContentMode.PLAINTEXT) onSelect(workspace) },
                )
            }
        }
    }
}

/**
 * The view menu: scope (my / team tasks) and grouping (time / project / status) as segmented
 * controls, then a multi-select of what the second row line shows.
 */
@Composable
private fun ViewMenu(
    scope: HomeScope,
    onSelectScope: (HomeScope) -> Unit,
    grouping: HomeGrouping,
    onSelectGrouping: (HomeGrouping) -> Unit,
    details: Set<ChatRowDetail>,
    onToggle: (ChatRowDetail) -> Unit,
    archived: Boolean,
    onArchived: (Boolean) -> Unit,
) {
    val colors = LodyTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.home_view)
    val shape = RoundedCornerShape(LodyRadius.lg)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Glyph(LodyIcons.Filter, colors.iconPrimary)
        }
        // Every choice applies in place and the menu stays open, so the list behind it updates
        // live; tapping outside closes it.
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = shape,
            containerColor = colors.bgOverlay,
            tonalElevation = 0.dp,
            // Material elevation casts roughly twice its value as blur; match the md shadow.
            shadowElevation = LodyElevations.md.blur / 2,
            border = BorderStroke(0.5.dp, colors.borderSubtle),
            modifier = Modifier.width(MenuWidth),
        ) {
            MenuHeading(stringResource(R.string.home_view_scope))
            Segmented(HomeScope.entries, scope, { stringResource(it.label) }, onSelectScope)
            MenuHeading(stringResource(R.string.home_view_grouping))
            Segmented(HomeGrouping.entries, grouping, { stringResource(it.label) }, onSelectGrouping)
            HorizontalDivider(Modifier.padding(top = LodySpacing.md), thickness = 0.5.dp, color = colors.borderSubtle)
            MenuHeading(stringResource(R.string.home_view_details))
            ChatRowDetail.entries.forEach { detail ->
                val checked = detail in details
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LodySize.controlMd)
                        .toggleable(value = checked, role = Role.Checkbox) { onToggle(detail) }
                        .padding(horizontal = LodySpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
                ) {
                    Glyph(detail.icon, colors.iconSecondary, LodyIconSize.sm)
                    Text(stringResource(detail.label), style = LodyTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Glyph(LodyIcons.Check, if (checked) colors.accentFg else Color.Transparent, LodyIconSize.sm)
                }
            }
            // Last, as iOS #2563: the Archived switch swaps the list for archived chats.
            HorizontalDivider(Modifier.padding(top = LodySpacing.xs), thickness = 0.5.dp, color = colors.borderSubtle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LodySize.controlMd)
                    .toggleable(value = archived, role = Role.Switch) { onArchived(it) }
                    .padding(horizontal = LodySpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
            ) {
                Glyph(LodyIcons.Inbox, colors.iconSecondary, LodyIconSize.sm)
                Text(stringResource(R.string.home_view_archived), style = LodyTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = archived, onCheckedChange = null)
            }
            Spacer(Modifier.height(LodySpacing.xs))
        }
    }
}

/** The Schedules view menu: one Show finished switch; off hides schedules that will not run again. */
@Composable
private fun ScheduleViewMenu(showFinished: Boolean, onShowFinished: (Boolean) -> Unit) {
    val colors = LodyTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.home_view)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Glyph(LodyIcons.Filter, colors.iconPrimary)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(LodyRadius.lg),
            containerColor = colors.bgOverlay,
            tonalElevation = 0.dp,
            shadowElevation = LodyElevations.md.blur / 2,
            border = BorderStroke(0.5.dp, colors.borderSubtle),
            modifier = Modifier.width(MenuWidth),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LodySize.controlMd)
                    .toggleable(value = showFinished, role = Role.Switch) { onShowFinished(it) }
                    .padding(horizontal = LodySpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
            ) {
                Glyph(LodyIcons.Check, colors.iconSecondary, LodyIconSize.sm)
                Text(stringResource(R.string.schedules_view_show_finished), style = LodyTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = showFinished, onCheckedChange = null)
            }
        }
    }
}

private val MenuWidth = 280.dp

/** Whether the Chats page lists archived chats; it then uses the grey archived page colour. */
private val LocalArchivedPage = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun homePageColor() = if (LocalArchivedPage.current) LodyTheme.colors.bgArchived else LodyTheme.colors.bgBase

/**
 * A single choice as a segmented control (the iOS one's shape on design tokens): a sunken
 * track with the chosen segment raised on the overlay colour.
 */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val colors = LodyTheme.colors
    val track = RoundedCornerShape(LodyRadius.md)
    val thumb = RoundedCornerShape(LodyRadius.sm)
    Row(
        modifier = Modifier
            .padding(horizontal = LodySpacing.lg)
            .fillMaxWidth()
            .height(LodySize.controlMd)
            .background(colors.fillSecondary, track)
            .padding(LodySpacing.xxs)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val chosen = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (chosen) Modifier.dropShadow(thumb, LodyElevations.sm.shadow()).background(colors.bgOverlay, thumb) else Modifier)
                    .clip(thumb)
                    .selectable(selected = chosen, role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = LodyTheme.typography.label,
                    color = if (chosen) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = LodyTheme.typography.footnote,
        color = LodyTheme.colors.textTertiary,
        modifier = Modifier
            .semantics { heading() }
            .padding(start = LodySpacing.lg, end = LodySpacing.lg, top = LodySpacing.md, bottom = LodySpacing.xs),
    )
}

private val HomeScope.label: Int
    get() = when (this) {
        HomeScope.Mine -> R.string.home_scope_mine
        HomeScope.Team -> R.string.home_scope_team
    }

private val HomeGrouping.label: Int
    get() = when (this) {
        HomeGrouping.Time -> R.string.home_grouping_time
        HomeGrouping.Project -> R.string.home_grouping_project
        HomeGrouping.Status -> R.string.home_grouping_status
    }

/** The search field takes the whole bar, so the workspace capsule steps aside while searching. */
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val placeholder = stringResource(R.string.home_search_placeholder)
    val close = stringResource(R.string.home_search_close)
    val clear = stringResource(R.string.home_search_clear)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(homePageColor())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = LodySpacing.xs, vertical = LodySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = close }) {
            Glyph(LodyIcons.Back, colors.iconPrimary)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(LodySize.controlMd)
                .clip(CircleShape)
                .background(colors.bgSunken)
                .padding(horizontal = LodySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm),
        ) {
            Glyph(LodyIcons.Search, colors.iconSecondary, LodyIconSize.sm)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentDefault),
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(placeholder, style = type.body, color = colors.textPlaceholder)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { onQueryChange("") }.semantics { contentDescription = clear }) {
                    Glyph(LodyIcons.Close, colors.iconSecondary, LodyIconSize.sm)
                }
            }
        }
        Spacer(Modifier.width(LodySpacing.sm))
    }
}

// MARK: - Bottom bar

/** Labelled tabs in a floating capsule plus a separate new-chat button. */
@Composable
internal fun HomeBottomBar(selected: HomeTab, collapsed: Boolean, onSelect: (HomeTab) -> Unit, adds: Boolean = false, onCreate: () -> Unit) {
    val colors = LodyTheme.colors
    // On Schedules the button adds a schedule ("+"); elsewhere it starts a chat.
    val create = stringResource(if (adds) R.string.schedules_new else R.string.home_new_chat)
    val progress by animateFloatAsState(
        if (collapsed) 1f else 0f,
        tween(LodyMotion.slowMillis, easing = LodyMotion.standard), label = "home-bar-collapse",
    )
    val shrink = (progress * 2).coerceAtMost(1f)
    val height = BarHeight + (LodySize.controlLg - BarHeight) * shrink
    val labelHeight = with(LocalDensity.current) { LodyTheme.typography.micro.lineHeight.toDp() }
    BoxWithConstraints(
        Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        )
            .padding(horizontal = LodyLayout.screenPaddingX, vertical = LodySpacing.xs)
            .height(BarHeight),
    ) {
        val expandedWidth = maxWidth - BarHeight - LodySpacing.md
        val compactWidth = LodySize.controlLg
        val width = expandedWidth + (compactWidth - expandedWidth) * progress
        Row(
            modifier = Modifier.align(Alignment.CenterStart).width(width).height(height)
                .dropShadow(CircleShape, LodyElevations.md.shadow())
                .clip(CircleShape).background(colors.bgOverlay).padding(LodySpacing.xs)
                .selectableGroup(),
        ) {
            HomeTab.entries.forEach { tab ->
                val isSelected = tab == selected
                if (!isSelected && progress >= 1f) return@forEach
                val tint = if (isSelected) colors.accentFg else colors.iconSecondary
                val label = stringResource(tab.label)
                Column(
                    modifier = Modifier.weight(if (isSelected) 1f else (1 - progress).coerceAtLeast(0.001f))
                        .fillMaxHeight().clip(CircleShape)
                        .graphicsLayer { alpha = if (isSelected) 1f else 1 - shrink }
                        .background(if (isSelected) colors.fillSelected else Color.Transparent)
                        .selectable(selected = isSelected, enabled = !collapsed || isSelected, role = Role.Tab) { onSelect(tab) }
                        .then(if (collapsed && !isSelected) Modifier.clearAndSetSemantics {} else Modifier.semantics { contentDescription = label }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Glyph(tab.icon, tint)
                    Box(Modifier.height(labelHeight * (1 - shrink)).clip(CircleShape)) {
                        Text(label, style = LodyTheme.typography.micro, color = tint, maxLines = 1,
                            modifier = Modifier.graphicsLayer { alpha = 1 - shrink }.clearAndSetSemantics {})
                    }
                }
            }
        }
        LodyFilledIconButton(
            onClick = onCreate,
            modifier = Modifier.align(Alignment.CenterEnd).size(height)
                .dropShadow(CircleShape, LodyElevations.md.shadow())
                .semantics { contentDescription = create },
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.bgOverlay, contentColor = colors.iconPrimary,
            ),
        ) {
            androidx.compose.animation.Crossfade(adds, label = "create-glyph") { plus ->
                Glyph(if (plus) LodyIcons.Plus else LodyIcons.Compose, colors.iconPrimary, LodyIconSize.lg)
            }
        }
    }
}

// MARK: - Lists

/**
 * Flat groups with a text header. Each family (a root row and its opened children) is one list
 * item, so it moves as a unit. With [status], rows offer the Status view actions: a long press
 * opens the action menu and moving while pressed turns into a drag; swipes reveal Settle / Later
 * (Archive on Settled) trailing and pin / unpin leading. Settled folds after
 * [ChatListModel.SETTLED_LIMIT] conversations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeList(
    sections: List<HomeSection>,
    details: Set<ChatRowDetail>,
    padding: PaddingValues,
    empty: String?,
    listState: LazyListState = rememberLazyListState(),
    settledExpanded: Boolean = false,
    onToggleSettled: () -> Unit = {},
    owners: Map<String, WorkspaceMember>? = null,
    pinsLabels: Boolean = true,
    projectIndex: Boolean = false,
    /** A long press offers Archive, or in an [archivedList] Unarchive only (iOS row actions). */
    onSetArchived: ((ChatItem, Boolean) -> Unit)? = null,
    archivedList: Boolean = false,
    status: StatusListControls? = null,
    onOpenChat: (ChatItem) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyText(empty.orEmpty(), padding)
        return
    }
    val now = rememberNow()
    // Resolved here: LazyListScope builders are not composable.
    val titles = sections.associate { it.id to it.header.title() }
    val showLess = stringResource(R.string.home_group_show_less)
    val focusDrop = stringResource(R.string.status_focus_drop)
    val hiddenLabels = sections.filter { it.hiddenCount > 0 }.associate { it.id to pluralStringResource(R.plurals.status_section_hidden, it.hiddenCount, it.hiddenCount) }
    val context = LocalContext.current
    var folded by remember { mutableStateOf(FoldedOpeners.load(context)) }
    val toggleOpener = { id: String ->
        folded = if (id in folded) folded - id else folded + id
        FoldedOpeners.save(context, folded)
    }
    // Home labels collapse their group; search labels never do. Archived buckets reuse activity ids.
    // The stored set records changes from each group's default (Later and Settled start collapsed).
    var collapsedGroups by remember { mutableStateOf(CollapsedGroups.load(context)) }
    val groupKey = { id: String -> if (archivedList) "archived:$id" else id }
    val toggleGroup = { id: String ->
        val key = groupKey(id)
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
        CollapsedGroups.save(context, collapsedGroups)
    }
    val shown = remember(sections, folded, collapsedGroups, pinsLabels, archivedList) {
        sections.map {
            val changed = groupKey(it.id) in collapsedGroups
            it.copy(chats = ChatListModel.fold(it.chats, folded), collapsed = pinsLabels && changed != (!archivedList && ChatListModel.collapsedByDefault(it.id)))
        }
    }
    val reorder = status != null && pinsLabels && !archivedList
    // Drags reorder [ListSlot]s locally; the dropped order is held until the catalog reads back.
    val computed = remember(shown, settledExpanded) { StatusMoves.slots(shown, settledExpanded) }
    var drag by remember { mutableStateOf<HeldOrder?>(null) }
    val held = drag
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    BackHandler(enabled = held?.dragging == true) { drag = null }
    LaunchedEffect(windowFocused) {
        if (!windowFocused && drag?.dragging == true) drag = null
    }
    // The dropped order holds until the catalog shows the row where it landed, or until two
    // catalogs arrived after the write without it (another change won); a failed write releases.
    LaunchedEffect(computed) {
        val current = drag ?: return@LaunchedEffect
        val command = current.command ?: return@LaunchedEffect
        when {
            StatusMoves.landed(computed, command) -> drag = null
            current.written -> drag = if (current.misses + 1 >= HOLD_MISSES) null else current.copy(misses = current.misses + 1)
        }
    }
    val slots = held?.slots ?: computed
    val slop = LocalViewConfiguration.current.touchSlop
    // Unfolded, so a folding family keeps its children composed while they animate away.
    val families = remember(sections) { sections.flatMap { it.chats }.groupBy { it.nestedUnder ?: it.id } }
    val haptics = LocalHapticFeedback.current
    val direction = LocalLayoutDirection.current
    // The list sits below the app bar. Only the bottom bar overlaps its viewport.
    val reorderState = rememberReorderableLazyListState(listState, scrollThresholdPadding = PaddingValues(bottom = padding.calculateBottomPadding())) { from, to ->
        val current = drag?.takeIf { it.dragging } ?: return@rememberReorderableLazyListState
        val target = to.key as String
        val next = StatusMoves.swap(current.slots, from.key as String, target)
        val moved = next !== current.slots
        drag = current.copy(
            slots = if (moved) StatusMoves.settle(next) else current.slots,
            moved = current.moved || moved,
            target = target,
        )
        if (moved) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val sectionOrder = shown.mapNotNull { (it.header as? HomeSection.Header.Pinned)?.sectionId }
    // Keep the sticky slot below the app bar; content padding alone scrolls away.
    ProjectIndexList(shown, titles, listState, projectIndex, padding.calculateBottomPadding(),
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            var open: HomeSection? = null
            fun trailer(section: HomeSection) {
                if (section.folds) item(key = "${section.id}/more") {
                    MoreRow(
                        if (settledExpanded) showLess else pluralStringResource(R.plurals.home_group_show_all, section.rootCount, section.rootCount),
                        onToggleSettled,
                        Modifier.animateItem(),
                    )
                }
                if (!section.collapsed) hiddenLabels[section.id]?.let { label -> item(key = "${section.id}/hidden") { MoreRow(label, {}, Modifier.animateItem(), enabled = false) } }
            }
            slots.forEach { slot ->
                when (slot) {
                    is ListSlot.Header -> {
                        open?.let(::trailer)
                        val section = shown.firstOrNull { it.id == slot.section.id } ?: slot.section
                        open = section
                        // Project groups lead their label with the project's logo.
                        val logo = section.repository.takeIf { section.header is HomeSection.Header.Project }
                        val header = @Composable {
                            SectionTitle(
                                titles[section.id].orEmpty(), logo, collapsed = if (pinsLabels) section.collapsed else null, onToggle = { toggleGroup(section.id) },
                                count = section.rootCount.takeIf { section.header is HomeSection.Header.Tier || (section.header is HomeSection.Header.Pinned && section.collapsed) },
                                menu = status?.takeIf { reorder && it.manageSections && section.header is HomeSection.Header.Pinned }?.let { controls ->
                                    { close -> SectionMenu(section, sectionOrder, controls, close) }
                                },
                            )
                        }
                        if (pinsLabels) {
                            stickyHeader(key = slot.key) {
                                // Headers are drop targets, never dragged: crossing one moves a row into the next section.
                                if (reorder) ReorderableItem(reorderState, slot.key, animateItemModifier = Modifier) { header() } else header()
                            }
                        } else {
                            item(key = slot.key) { header() }
                        }
                    }
                    is ListSlot.Gap -> {
                        // An empty Focus's landing slot: a drop target when rows can move, else just its hint.
                        item(key = slot.key) {
                            if (reorder) ReorderableItem(reorderState, slot.key) { MoreRow(focusDrop, {}, enabled = false) }
                            else MoreRow(focusDrop, {}, Modifier.animateItem(), enabled = false)
                        }
                    }
                    is ListSlot.Row -> {
                        val section = open ?: return@forEach
                        // A dropped row stays out of a collapsed section; only a dragged one must stay composed.
                        if (section.collapsed && held?.dragging != true) return@forEach
                        val root = slot.chat
                        val kids = families[root.id].orEmpty().filter { it.nestedUnder == root.id }
                        item(key = slot.key) {
                            val family = @Composable { press: (() -> Modifier)?, dragging: Boolean ->
                                FamilyRows(
                                    section, root, kids, details, now, owners, folded, toggleOpener, archivedList, onSetArchived,
                                    status.takeIf { pinsLabels }, shown, menuFor == root.id, { menuFor = if (it) root.id else null },
                                    press, dragging, onOpenChat,
                                )
                            }
                            if (reorder) {
                                ReorderableItem(reorderState, slot.key) { dragging ->
                                    val movable = root.canManage || root.canPrefer
                                    val gesture = remember { DragGesture() }
                                    family(if (!movable) null else { -> Modifier.draggableHandle(
                                        onDragStarted = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuFor = null
                                            // From the order on screen (a previous drop may still be held).
                                            drag = HeldOrder(slots, slots, dragging = true)
                                        },
                                        onDragStopped = {
                                            val current = drag?.takeIf { it.dragging } ?: return@draggableHandle
                                            // The reorder library needs its requested index during the gesture.
                                            // A header drop becomes the top of that section on release.
                                            val landed = StatusMoves.belowHeader(current.slots, root.id, current.target)
                                            val moved = current.moved || landed !== current.slots
                                            val command = if (moved) StatusMoves.drop(landed, current.original, root.id) else null
                                            // A cancelled gesture (back swipe, notification shade) never writes or opens the menu.
                                            when (StatusMoves.release(gesture.ended && windowInfo.isWindowFocused, moved, gesture.travelled, slop, command)) {
                                                StatusMoves.Release.Menu -> { drag = null; menuFor = root.id }
                                                StatusMoves.Release.Revert -> drag = null
                                                StatusMoves.Release.Commit -> {
                                                    val dropped = current.copy(slots = landed, dragging = false, command = command)
                                                    drag = dropped
                                                    status.run(command!!) { ok ->
                                                        if (drag === dropped) drag = if (ok) dropped.copy(written = true) else null
                                                    }
                                                }
                                            }
                                        },
                                        dragGestureDetector = gesture,
                                    ) }, dragging)
                                }
                            } else family(null, false)
                        }
                    }
                }
            }
            open?.let(::trailer)
        }
    }
}

/** A drag in flight, or a dropped [command] awaiting the catalog that shows it ([misses] catalogs without it so far). */
private data class HeldOrder(
    val slots: List<ListSlot>,
    val original: List<ListSlot>,
    val dragging: Boolean,
    val moved: Boolean = false,
    val target: String? = null,
    val command: app.lody.android.runtime.StatusViewCommand.Move? = null,
    val written: Boolean = false,
    val misses: Int = 0,
)

/** Catalogs a written drop may wait through for its own read back before the order is released. */
private const val HOLD_MISSES = 2

/**
 * The long-press drag detector, recording how the gesture finished: the drag library reports a
 * normal release, a cancel and a disposal through the same `onDragStopped`.
 */
private class DragGesture : sh.calvin.reorderable.DragGestureDetector {
    var ended = false
        private set
    var travelled = 0f
        private set

    override suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detect(
        onDragStart: (androidx.compose.ui.geometry.Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset) -> Unit,
    ) = detectDragGesturesAfterLongPress(
        onDragStart = { ended = false; travelled = 0f; onDragStart(it) },
        onDragEnd = { ended = true; onDragEnd() },
        onDragCancel = { ended = false; onDragCancel() },
        onDrag = { change, amount -> travelled += amount.getDistance(); onDrag(change, amount) },
    )
}

/**
 * What the Home list needs for Status view actions: the action host (Later sheet, questions,
 * Undo), a raw command runner reporting success, and whether section management is offered.
 */
internal class StatusListControls(
    val actions: StatusActions,
    val run: (app.lody.android.runtime.StatusViewCommand, (Boolean) -> Unit) -> Unit,
    val manageSections: Boolean,
    /** New section…; with a row id, that row then moves into it (the row menu's entry). */
    val onNewSection: (String?) -> Unit,
    val onRenameSection: (String, String) -> Unit,
    val onDeleteSection: (String, String) -> Unit,
    /** A row's action target, its opened sub-conversations counted across tiers. */
    val target: (ChatItem) -> StatusTarget = { StatusTarget(it.id, it.tier) },
    /** Every pin section by id and name, the default (null, null) first, even while it is hidden. */
    val pinSections: List<Pair<String?, String?>> = listOf(null to null),
)

@Composable
private fun SectionMenu(section: HomeSection, order: List<String>, controls: StatusListControls, close: () -> Unit) {
    val header = section.header as HomeSection.Header.Pinned
    val colors = LodyTheme.colors
    @Composable fun entry(text: Int, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(stringResource(text), style = LodyTheme.typography.body, color = if (enabled) colors.textPrimary else colors.textTertiary) },
        enabled = enabled, onClick = { close(); onClick() },
    )
    entry(R.string.status_section_new) { controls.onNewSection(null) }
    val id = header.sectionId ?: return
    entry(R.string.status_section_rename) { controls.onRenameSection(id, header.name.orEmpty()) }
    val up = StatusMoves.section(order, id, -1)
    val down = StatusMoves.section(order, id, 1)
    entry(R.string.status_action_move_up, up != null) { up?.let { controls.run(it) {} } }
    entry(R.string.status_action_move_down, down != null) { down?.let { controls.run(it) {} } }
    entry(R.string.status_section_delete) { controls.onDeleteSection(id, header.name.orEmpty()) }
}

/** A root row, its swipe actions and action menu, then its opened children (with their own). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamilyRows(
    section: HomeSection,
    root: ChatItem,
    kids: List<ChatItem>,
    details: Set<ChatRowDetail>,
    now: Long,
    owners: Map<String, WorkspaceMember>?,
    folded: Set<String>,
    toggleOpener: (String) -> Unit,
    archivedList: Boolean,
    onSetArchived: ((ChatItem, Boolean) -> Unit)?,
    status: StatusListControls?,
    sections: List<HomeSection>,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    press: (() -> Modifier)?,
    dragging: Boolean,
    onOpenChat: (ChatItem) -> Unit,
) {
    val rowDetails = { chat: ChatItem -> if (section.header is HomeSection.Header.Project && chat.nestedUnder == null) details - ChatRowDetail.Project else details }
    val owner = { chat: ChatItem -> owners?.let { it[chat.ownerId] ?: WorkspaceMember(chat.ownerId.orEmpty(), "") } }
    val labels = RowLabels.current()
    val move = { command: app.lody.android.runtime.StatusViewCommand? -> command?.let { status?.run?.invoke(it) {} }; Unit }
    val rootActions = rowActions(root, section, sections, status, onSetArchived, archivedList, labels)
    val up = status?.let { StatusMoves.by(section, root, -1) }
    val down = status?.let { StatusMoves.by(section, root, 1) }
    val expanded = root.treeParent && root.id !in folded
    val collapseLabel = stringResource(R.string.home_tree_collapse)
    val semantics = buildList {
        if (expanded) add(CustomAccessibilityAction(collapseLabel) { toggleOpener(root.id); true })
        if (up != null) add(CustomAccessibilityAction(labels.moveUp) { move(up); true })
        if (down != null) add(CustomAccessibilityAction(labels.moveDown) { move(down); true })
        if (rootActions.hasMenu) add(CustomAccessibilityAction(labels.actions) { onMenu(true); true })
    }
    Column(Modifier.graphicsLayer { if (dragging) { shadowElevation = 12f; alpha = 0.96f } }.background(if (dragging) LodyTheme.colors.bgRaised else Color.Transparent)) {
        // Keep the active long-press handle composed when a drag starts.
        SwipeActions(rootActions.leading, rootActions.trailing) {
            ChatRow(
                root, rowDetails(root), now, owner(root),
                expanded = if (root.treeParent) root.id !in folded else null,
                onToggle = { toggleOpener(root.id) },
                menuOpen = menuOpen,
                onMenu = if (rootActions.hasMenu) onMenu else null,
                press = press,
                actions = semantics,
                menu = { close -> RowMenu(section, root, rootActions, sections, status, archivedList, close) },
            ) { onOpenChat(root) }
        }
        // Opened children move with their opener (no drag or Move up / down) but take every other action.
        // Expanded, the count line gives way to them and the first child carries the collapse chevron.
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                kids.forEachIndexed { index, kid ->
                    var kidMenu by remember { mutableStateOf(false) }
                    val kidActions = rowActions(kid, section, sections, status, onSetArchived, archivedList, labels)
                    val collapse: (() -> Unit)? = if (index == 0) { { toggleOpener(root.id) } } else null
                    val kidSemantics = buildList {
                        if (collapse != null) add(CustomAccessibilityAction(collapseLabel) { collapse(); true })
                        if (kidActions.hasMenu) add(CustomAccessibilityAction(labels.actions) { kidMenu = true; true })
                    }
                    SwipeActions(kidActions.leading.takeIf { !dragging }, kidActions.trailing.takeIf { !dragging }.orEmpty()) {
                        ChatRow(
                            kid, rowDetails(kid), now, owner(kid), collapseFamily = collapse,
                            menuOpen = kidMenu, onMenu = if (kidActions.hasMenu) { open: Boolean -> kidMenu = open } else null,
                            actions = kidSemantics,
                            menu = { close -> RowMenu(section, kid, kidActions, sections, status, archivedList, close) },
                        ) { onOpenChat(kid) }
                    }
                }
            }
        }
    }
}

/** What one row offers: status actions, pin, archive, and the swipes revealing them. */
private class RowActions(
    val target: StatusTarget,
    /** Later and the tier-dependent Settle / Move to Focus. */
    val status: Boolean,
    val settle: Boolean,
    val focus: Boolean,
    val pin: (() -> Unit)?,
    val archive: (() -> Unit)?,
    val leading: SwipeAction?,
    val trailing: List<SwipeAction>,
) {
    val hasMenu get() = status || pin != null || archive != null
}

@Composable
private fun rowActions(
    chat: ChatItem,
    section: HomeSection,
    sections: List<HomeSection>,
    controls: StatusListControls?,
    onSetArchived: ((ChatItem, Boolean) -> Unit)?,
    archivedList: Boolean,
    labels: RowLabels,
): RowActions {
    val archive = onSetArchived?.let { action -> { action(chat, !archivedList) } }
    val actions = controls?.actions
    val tier = chat.tier
    // A row the layout carries under a pinned opener is not pinned itself: its own snooze / settle
    // metadata decides Settle and Move to Focus there.
    val carried = tier == app.lody.android.runtime.StatusTier.Pinned && !chat.pinned
    val status = actions != null && chat.canManage && tier != null && (tier != app.lody.android.runtime.StatusTier.Pinned || carried)
    val settled = if (carried) chat.ownSettled else tier == app.lody.android.runtime.StatusTier.Settled
    val later = if (carried) chat.ownSnoozedUntil != null else tier == app.lody.android.runtime.StatusTier.Later
    val target = controls?.target?.invoke(chat) ?: StatusTarget(chat.id, tier)
    val pin = controls?.takeIf { chat.canPrefer }?.let { c -> { c.run(app.lody.android.runtime.StatusViewCommand.SetPinned(chat.id, !chat.pinned)) {} } }
    val trailing = buildList {
        if (status && !settled) {
            add(SwipeAction(labels.settle, LodyIcons.Check) { actions!!.settle(target) })
            add(SwipeAction(labels.later, LodyIcons.Clock) { actions!!.later(target) })
        } else if (archive != null && controls != null && settled) add(SwipeAction(labels.archive, LodyIcons.Inbox, danger = true, onClick = archive))
    }
    val leading = pin?.let { SwipeAction(if (chat.pinned) labels.unpin else labels.pin, LodyIcons.Pin, onClick = it) }
    return RowActions(target, status, settle = status && !settled, focus = status && (settled || later), pin, archive, leading, trailing)
}

@Composable
private fun RowMenu(
    section: HomeSection,
    chat: ChatItem,
    row: RowActions,
    sections: List<HomeSection>,
    status: StatusListControls?,
    archivedList: Boolean,
    close: () -> Unit,
) {
    val colors = LodyTheme.colors
    @Composable fun entry(text: String, icon: ImageVector?, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(text, style = LodyTheme.typography.body, color = colors.textPrimary) },
        leadingIcon = icon?.let { { Glyph(it, colors.iconSecondary, LodyIconSize.sm) } },
        onClick = { close(); onClick() },
    )
    var sectionsOpen by remember { mutableStateOf(false) }
    val actions = status?.actions
    if (row.status && actions != null) {
        // Later stays on Later rows too: it re-sets the wake-up time.
        entry(stringResource(R.string.status_later), LodyIcons.Clock) { actions.later(row.target) }
        if (row.focus) entry(stringResource(R.string.status_action_focus), LodyIcons.Undo) { actions.focus(row.target) }
        if (row.settle) entry(stringResource(R.string.status_action_settle), LodyIcons.Check) { actions.settle(row.target) }
    }
    row.pin?.let { entry(stringResource(if (chat.pinned) R.string.status_action_unpin else R.string.status_action_pin), LodyIcons.Pin, it) }
    // Move to section lists every pin section (the default "Pinned" included) and New section…
    if (status != null && chat.canPrefer) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.status_action_move_section), style = LodyTheme.typography.body, color = colors.textPrimary) },
            trailingIcon = { Glyph(LodyIcons.Forward, colors.iconTertiary, LodyIconSize.sm) },
            onClick = { sectionsOpen = !sectionsOpen },
        )
        if (sectionsOpen) {
            val current = (section.header as? HomeSection.Header.Pinned)?.sectionId.takeIf { chat.pinned && section.header is HomeSection.Header.Pinned }
            status.pinSections.filter { (sectionId, _) -> !(chat.pinned && sectionId == current) }.forEach { (sectionId, name) ->
                entry("  " + (name ?: stringResource(R.string.home_group_pinned)), null) {
                    StatusMoves.toSection(sections, chat, sectionId)?.let { status.run(it) {} }
                }
            }
            if (status.manageSections) entry("  " + stringResource(R.string.status_section_new), LodyIcons.Plus) { status.onNewSection(chat.id) }
        }
    }
    StatusMoves.toTop(section, chat)?.takeIf { status != null && chat.nestedUnder == null }?.let { command -> entry(stringResource(R.string.status_action_move_top), null) { status!!.run(command) {} } }
    row.archive?.let { archive -> ArchiveItem(archivedList) { close(); archive() } }
}

@Composable
private fun ArchiveItem(archived: Boolean, onClick: () -> Unit) {
    val colors = LodyTheme.colors
    DropdownMenuItem(
        text = { Text(stringResource(if (archived) R.string.home_unarchive else R.string.home_archive), style = LodyTheme.typography.body, color = colors.textPrimary) },
        leadingIcon = { Glyph(LodyIcons.Inbox, colors.iconSecondary, LodyIconSize.sm) },
        onClick = onClick,
    )
}

/** Localized row action labels, resolved once per row. */
private data class RowLabels(
    val later: String, val settle: String, val pin: String, val unpin: String, val archive: String,
    val moveUp: String, val moveDown: String, val actions: String,
) {
    companion object {
        @Composable fun current() = RowLabels(
            stringResource(R.string.status_later), stringResource(R.string.status_action_settle),
            stringResource(R.string.status_action_pin), stringResource(R.string.status_action_unpin),
            stringResource(R.string.home_archive), stringResource(R.string.status_action_move_up),
            stringResource(R.string.status_action_move_down), stringResource(R.string.bar_more),
        )
    }
}

private class SwipeAction(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * Swipe to reveal row actions, then tap one (no full-swipe commit), as the Tab panel's Close. The
 * same actions stay in the long-press menu and TalkBack's actions.
 */
@Composable
private fun SwipeActions(leading: SwipeAction?, trailing: List<SwipeAction>, content: @Composable () -> Unit) {
    if (leading == null && trailing.isEmpty()) { content(); return }
    val slot = LodySize.listRow + LodySpacing.lg
    val slotPx = with(LocalDensity.current) { slot.toPx() }
    val startMax = if (leading != null) slotPx else 0f
    val endMax = trailing.size * slotPx
    var reveal by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val offset by animateFloatAsState(if (dragging) drag else reveal, tween(if (dragging) 0 else 160), label = "rowSwipe")
    val colors = LodyTheme.colors
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds()) {
        if (leading != null && offset > 1f) Row(Modifier.align(Alignment.CenterStart).fillMaxHeight()) { SwipeButton(leading, slot) { reveal = 0f; leading.onClick() } }
        if (trailing.isNotEmpty() && offset < -1f) Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
            trailing.forEach { action -> SwipeButton(action, slot) { reveal = 0f; action.onClick() } }
        }
        Box(Modifier.fillMaxWidth().offset { IntOffset((if (rtl) -offset else offset).roundToInt(), 0) }.background(homePageColor())
            .pointerInput(startMax, endMax) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = reveal; dragging = true },
                    onDragEnd = {
                        reveal = when {
                            drag > startMax / 2 && startMax > 0 -> startMax
                            drag < -endMax / 2 && endMax > 0 -> -endMax
                            else -> 0f
                        }
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, amount -> change.consume(); drag = (drag + if (rtl) -amount else amount).coerceIn(-endMax, startMax) },
                )
            }) {
            content()
            // While revealed, a tap on the row only closes the actions.
            if (reveal != 0f) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) { reveal = 0f })
        }
    }
}

@Composable
private fun SwipeButton(action: SwipeAction, width: Dp, onClick: () -> Unit) {
    val colors = LodyTheme.colors
    Column(
        Modifier.width(width).fillMaxHeight().background(if (action.danger) colors.dangerDefault else colors.bgSunken).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (action.danger) Color.White else colors.textPrimary
        Glyph(action.icon, tint, LodyIconSize.sm)
        Text(action.label, style = LodyTheme.typography.footnote, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeSection.Header.title(): String = when (this) {
    is HomeSection.Header.Bucket -> stringResource(bucket.label)
    is HomeSection.Header.Project -> name
    is HomeSection.Header.Pinned -> name ?: stringResource(R.string.home_group_pinned)
    HomeSection.Header.Chats -> stringResource(R.string.home_group_chats)
    is HomeSection.Header.Tier -> stringResource(
        when (tier) {
            app.lody.android.runtime.StatusTier.Later -> R.string.status_later
            app.lody.android.runtime.StatusTier.Settled -> R.string.status_settled
            else -> R.string.status_focus
        },
    )
}

@Composable
private fun MoreRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(
        title,
        style = LodyTheme.typography.body,
        color = LodyTheme.colors.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LodySize.listRow)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = LodyLayout.childIndent, end = LodyLayout.screenPaddingX)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

private val TimeBucket.label: Int
    get() = when (this) {
        TimeBucket.Today -> R.string.home_bucket_today
        TimeBucket.Yesterday -> R.string.home_bucket_yesterday
        TimeBucket.Week -> R.string.home_bucket_week
        TimeBucket.Month -> R.string.home_bucket_month
        TimeBucket.Older -> R.string.home_bucket_older
    }

/** Projects as cards (iOS `ProjectCardView`), newest activity first. Unused while the Projects tab is hidden. */
@Composable
private fun ProjectCardList(cards: List<ProjectCard>, padding: PaddingValues, empty: String, onOpen: (ProjectCard) -> Unit) {
    if (cards.isEmpty()) {
        EmptyText(empty, padding)
        return
    }
    val now = System.currentTimeMillis()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + LodySpacing.xs,
            bottom = padding.calculateBottomPadding() + LodySpacing.lg,
            start = LodyLayout.screenPaddingX, end = LodyLayout.screenPaddingX,
        ),
        verticalArrangement = Arrangement.spacedBy(LodySpacing.sm),
    ) {
        items(cards, key = { it.id }) { card -> ProjectCardView(card, now, Modifier.animateItem()) { onOpen(card) } }
    }
}

/**
 * A 40 dp logo centred on both lines (the GitHub owner's avatar, else the name's initial). Line 1:
 * the name and when it last moved. Line 2: the computer (local) or "GitHub", and the most pressing
 * state of its open chats: needs you, running, just finished, open PRs, else how many there are.
 */
@Composable
private fun ProjectCardView(card: ProjectCard, now: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val shape = RoundedCornerShape(LodyRadius.xl)
    val (summary, count) = card.summary
    val res = LocalContext.current.resources
    val summaryText = when (summary) {
        ProjectCard.Summary.Attention -> res.getQuantityString(R.plurals.home_project_attention, count, count)
        ProjectCard.Summary.Running -> res.getQuantityString(R.plurals.home_project_running, count, count)
        ProjectCard.Summary.Finished -> res.getQuantityString(R.plurals.home_project_finished, count, count)
        ProjectCard.Summary.PullRequests -> res.getQuantityString(R.plurals.home_project_prs, count, count)
        ProjectCard.Summary.Open -> res.getQuantityString(R.plurals.home_project_open, count, count)
        ProjectCard.Summary.Empty -> ""
    }
    val summaryColor = when (summary) {
        ProjectCard.Summary.Attention -> colors.warningDefault
        ProjectCard.Summary.Running, ProjectCard.Summary.Finished -> colors.accentFg
        ProjectCard.Summary.PullRequests, ProjectCard.Summary.Open -> colors.textSecondary
        ProjectCard.Summary.Empty -> colors.textTertiary
    }
    val place = card.machine ?: if (card.github) "GitHub" else ""
    // The logo is centred on both lines; the text column carries the name and the state.
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgSunken, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = LodySpacing.lg, vertical = LodySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
    ) {
        // GitHub: the repository owner's avatar (its initial until it loads). Otherwise the
        // project name's first letter.
        val initial = (if (card.github) card.title.substringAfterLast('/') else card.title).take(1).uppercase()
        if (card.github && card.avatar != null) {
            RepositoryIcon(card.title, LodySize.avatarLg, opacity = 0.8f)
        } else {
            Box(Modifier.size(LodySize.avatarLg).background(colors.fillSecondary, CircleShape), contentAlignment = Alignment.Center) {
                Text(initial, style = type.headline, color = colors.textSecondary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                Text(card.title, style = type.headline, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                card.updatedAt?.let { Text(ageText(it, now), style = type.footnote, color = colors.textTertiary) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.sm)) {
                Text(place, style = type.footnote, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when (summary) {
                    ProjectCard.Summary.Running -> LodyWorkingGrid()
                    ProjectCard.Summary.Attention -> Glyph(LodyIcons.Hand, colors.warningDefault, LodyIconSize.sm)
                    ProjectCard.Summary.Finished -> Box(Modifier.size(LodySpacing.sm).background(colors.accentDefault, CircleShape))
                    ProjectCard.Summary.PullRequests -> Glyph(LodyIcons.PullRequest, colors.iconSecondary, LodyIconSize.sm)
                    else -> Unit
                }
                if (summaryText.isNotEmpty()) Text(summaryText, style = type.subhead, color = summaryColor, maxLines = 1)
            }
        }
    }
}

/** The Projects view menu: scope (shared with Chats), source, and whether empty projects show. */
@Composable
private fun ProjectsViewMenu(
    scope: HomeScope,
    onSelectScope: (HomeScope) -> Unit,
    source: ProjectSource,
    onSelectSource: (ProjectSource) -> Unit,
    hideEmpty: Boolean,
    onHideEmpty: (Boolean) -> Unit,
) {
    val colors = LodyTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.home_view)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Glyph(LodyIcons.Filter, colors.iconPrimary)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(LodyRadius.lg),
            containerColor = colors.bgOverlay,
            tonalElevation = 0.dp,
            shadowElevation = LodyElevations.md.blur / 2,
            border = BorderStroke(0.5.dp, colors.borderSubtle),
            modifier = Modifier.width(MenuWidth),
        ) {
            MenuHeading(stringResource(R.string.home_view_scope))
            Segmented(HomeScope.entries, scope, { stringResource(it.label) }, onSelectScope)
            MenuHeading(stringResource(R.string.home_projects_source))
            Segmented(ProjectSource.entries, source, { stringResource(it.label) }, onSelectSource)
            HorizontalDivider(Modifier.padding(top = LodySpacing.md), thickness = 0.5.dp, color = colors.borderSubtle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LodySize.controlMd)
                    .toggleable(value = hideEmpty, role = Role.Checkbox) { onHideEmpty(!hideEmpty) }
                    .padding(horizontal = LodySpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LodySpacing.md),
            ) {
                Text(stringResource(R.string.home_projects_hide_empty), style = LodyTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Glyph(LodyIcons.Check, if (hideEmpty) colors.accentFg else Color.Transparent, LodyIconSize.sm)
            }
            Spacer(Modifier.height(LodySpacing.xs))
        }
    }
}

private val ProjectSource.label: Int
    get() = when (this) {
        ProjectSource.All -> R.string.home_projects_source_all
        ProjectSource.GitHub -> R.string.home_projects_source_github
        ProjectSource.Local -> R.string.home_projects_source_local
    }

// MARK: - Rows

@Composable
internal fun SectionTitle(
    text: String,
    repository: String? = null,
    collapsed: Boolean? = null,
    onToggle: () -> Unit = {},
    /** Status tiers (and collapsed pin sections) name how many conversations they hold. */
    count: Int? = null,
    /** A pin section's header menu (New section, Rename, Move up / down, Delete). */
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    // `collapsed` is null where the group cannot fold (search). The chevron points down while open.
    val angle by animateFloatAsState(if (collapsed == true) 0f else 90f, LodySpring.snappy(), label = "group")
    val action = stringResource(if (collapsed == true) R.string.home_group_expand else R.string.home_group_collapse)
    val state = stringResource(if (collapsed == true) R.string.home_tree_collapsed else R.string.home_tree_expanded)
    val counted = count?.let { pluralStringResource(R.plurals.status_tier_count, it, it) }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(homePageColor())
            .padding(end = if (menu != null) LodySpacing.xs else LodyLayout.screenPaddingX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .then(if (collapsed != null) Modifier.clickable(role = Role.Button, onClickLabel = action, onClick = onToggle) else Modifier)
                .semantics(mergeDescendants = true) {
                    heading()
                    if (collapsed != null) stateDescription = state
                    if (counted != null) contentDescription = "$text, $counted"
                }
                .padding(start = LodyLayout.screenPaddingX, top = LodySpacing.lg, bottom = LodySpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The project's logo in its original colours, unlike the muted row marks.
            if (!repository.isNullOrBlank()) RepositoryIcon(repository, muted = false)
            Text(text, style = LodyTheme.typography.subhead, color = LodyTheme.colors.textSecondary)
            if (count != null) Text(count.toString(), style = LodyTheme.typography.footnote.copy(fontFeatureSettings = "tnum"), color = LodyTheme.colors.textTertiary)
            if (collapsed != null) Glyph(LodyIcons.Forward, LodyTheme.colors.textSecondary, FamilyChevron, Modifier.rotate(angle))
        }
        // The menu is centred on the text line without adding height, so labels with and without
        // a menu keep the same spacing; its button keeps its full touch target.
        if (menu != null) Box(
            Modifier.padding(top = LodySpacing.lg, bottom = LodySpacing.xs).height(0.dp).wrapContentHeight(unbounded = true),
        ) {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(LodySize.controlMd)) {
                Icon(LodyIcons.More, stringResource(R.string.status_section_actions), tint = LodyTheme.colors.iconSecondary, modifier = Modifier.size(LodyIconSize.sm))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LodyTheme.colors.bgOverlay) { menu { menuOpen = false } }
        }
    }
}

/**
 * Line 1: status slot, title, age. Line 2 (only when an enabled detail has a value): repo or folder
 * mark + project, branch, and the PR mark trailing. iOS counterpart: `ChatRowContent`.
 *
 * [expanded] is set on a row with nested chats: whether they are shown. While folded, a "› 4 opened
 * chats" line below the row stands for the family; expanded, it gives way to the children and
 * [collapseFamily] (the first child's leading chevron) folds them again. Nested rows read one size
 * down and have rounded branches.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatItem,
    details: Set<ChatRowDetail>,
    now: Long,
    owner: WorkspaceMember? = null,
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
    collapseFamily: (() -> Unit)? = null,
    /** The long-press action menu: open state, its toggle (null: no menu) and its items. */
    menuOpen: Boolean = false,
    onMenu: ((Boolean) -> Unit)? = null,
    menu: @Composable (close: () -> Unit) -> Unit = {},
    /** A drag handle that replaces the plain long press (a long press without a move opens the menu). */
    press: (() -> Modifier)? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LodyTheme.colors
    val type = LodyTheme.typography
    val place = chat.place.takeIf { ChatRowDetail.Project in details && it.isNotEmpty() }
    val branch = chat.branch.takeIf { ChatRowDetail.Branch in details && it.isNotEmpty() }
    val pr = chat.prStatus.takeIf { ChatRowDetail.PullRequest in details && it in PrStatuses }
    // A PR mark alone stays on the title line, before the age; it is always the trailing mark of its line.
    val twoLine = place != null || branch != null
    val nested = chat.nestedUnder != null
    val rail = colors.borderDefault
    var titleHeight by remember { mutableFloatStateOf(0f) }
    val first = collapseFamily != null
    Column(
        modifier
            .fillMaxWidth()
            // A little air between an expanded family and the next conversation; children stay tight.
            .padding(bottom = if (nested && chat.lastChild) LodySpacing.sm else 0.dp)
            .drawBehind {
                if (nested) {
                    val turn = if (twoLine) LodySpacing.sm.toPx() + titleHeight / 2 else size.height / 2
                    if (first) drawTrunkBelowChevron(rail, chat.lastChild, turn) else drawRail(rail, chat.lastChild, turn)
                }
            },
    ) {
        // A popup: it takes no space in the column and anchors to this row.
        if (onMenu != null) DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }, containerColor = colors.bgOverlay) {
            menu { onMenu(false) }
        }
        Box(if (first) Modifier.height(IntrinsicSize.Min) else Modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (nested) LodySize.controlMd else LodySize.listRow)
                    .then(press?.let { handle -> Modifier.clickable(onClick = onClick).then(handle()) }
                        ?: Modifier.combinedClickable(onClick = onClick, onLongClick = onMenu?.let { { it(true) } }))
                    .then(if (actions.isNotEmpty()) Modifier.semantics { customActions = actions } else Modifier)
                    .padding(horizontal = LodyLayout.screenPaddingX)
                    .padding(vertical = if (twoLine) LodySpacing.sm else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (nested) Spacer(Modifier.width(TreeLevel))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
                    Row(Modifier.onSizeChanged { titleHeight = it.height.toFloat() }, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chat.title,
                            style = when {
                                nested -> type.callout.copy(fontWeight = if (chat.unread) type.bodyEmphasis.fontWeight else type.callout.fontWeight)
                                chat.unread -> type.bodyEmphasis
                                else -> type.body
                            },
                            // Archived chats read in tertiary ink on the grey page (iOS #2541).
                            color = if (LocalArchivedPage.current) colors.textTertiary else colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (chat.attention != null) {
                            Box(Modifier.padding(start = LodySpacing.sm).size(LodyIconSize.md), contentAlignment = Alignment.Center) { AttentionGlyph(chat.attention) }
                        }
                        if (chat.attention == null && pr != null && !twoLine) PullRequestMark(pr, chat.prCi, Modifier.padding(start = LodySpacing.sm))
                        val wakes = chat.snoozedUntil?.takeIf { chat.tier == app.lody.android.runtime.StatusTier.Later && it > now }
                        if (wakes != null) {
                            // A Later row's wake-up countdown parks at the trailing edge in neutral ink, after
                            // any status mark (an unread or waiting Later row still shows when it returns).
                            val left = countdownText(wakes, now)
                            val described = stringResource(R.string.status_returns_in, left)
                            Row(Modifier.padding(start = LodySpacing.sm).semantics(mergeDescendants = true) { contentDescription = described },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.xxs)) {
                                Glyph(LodyIcons.Clock, colors.iconTertiary, LodyIconSize.xs)
                                Text(left, style = type.footnote.copy(fontFeatureSettings = "tnum"), color = colors.textTertiary, maxLines = 1)
                            }
                        }
                        if (chat.attention == null && wakes == null) chat.activityAt?.let { at ->
                            Text(
                                ageText(at, now),
                                style = type.footnote.copy(fontFeatureSettings = "tnum"),
                                color = colors.textTertiary,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(start = if (pr != null && !twoLine) LodySpacing.xs else LodySpacing.sm),
                            )
                        }
                        if (owner != null) {
                            Spacer(Modifier.width(LodySpacing.sm))
                            Avatar(owner.image, owner.name.ifBlank { "?" }, LodyIconSize.md)
                        }
                    }
                    if (twoLine) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Project and branch share the leading space so the PR mark stays pinned trailing.
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs)) {
                                if (place != null) {
                                    if (chat.placeIsRepo) RepositoryIcon(chat.repository)
                                    else Glyph(LodyIcons.Folder, colors.iconSecondary, LodyIconSize.sm)
                                    Text(place, style = type.footnote, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                                if (branch != null) {
                                    Glyph(LodyIcons.Branch, colors.iconTertiary, LodyIconSize.sm, Modifier.padding(start = if (place != null) LodySpacing.xs else 0.dp))
                                    Text(branch, style = type.footnote, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                            }
                            if (pr != null) PullRequestMark(pr, chat.prCi, Modifier.padding(start = LodySpacing.sm))
                        }
                    }
                }
            }
            if (collapseFamily != null) {
                // The first child's leading gutter folds the family; its chevron sits on the trunk, level with the title.
                val chevronTop = with(LocalDensity.current) { (LodySpacing.sm.toPx() + titleHeight / 2).toDp() } - FamilyChevron / 2
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(LodyLayout.childIndent)
                        .clickable(onClick = collapseFamily)
                        .clearAndSetSemantics {},
                    contentAlignment = if (twoLine) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    Glyph(
                        LodyIcons.Forward, colors.iconSecondary, FamilyChevron,
                        Modifier.padding(start = LodyLayout.screenPaddingX).offset(y = if (twoLine) chevronTop else 0.dp).rotate(90f),
                    )
                }
            }
        }
        if (expanded != null) {
            AnimatedVisibility(!expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                FamilyToggle(chat.childCount, chat.childAttention, onToggle)
            }
        }
    }
}

/** The family chevron sits at the opener's title edge; the rail runs down its centre (iOS `ChatRowContent`). */
private val FamilyChevron = LodyIconSize.xs
private val RailWidth = LodySpacing.xxs / 2
private val RailX = LodyLayout.screenPaddingX + FamilyChevron / 2

/** One nesting level: how far a nested row's title steps in (iOS `ChatRowContent.childLevel`). */
private val TreeLevel = LodyLayout.childIndent - LodyLayout.screenPaddingX

/**
 * Children curve from the family trunk into their title. Intermediate rows continue the trunk;
 * the final child ends at its branch. Geometry follows the measured title height.
 */
private fun DrawScope.drawRail(color: Color, last: Boolean, turn: Float) {
    val trunk = RailX.toPx()
    val fork = maxOf(0f, turn - LodySpacing.md.toPx())
    val path = Path().apply {
        moveTo(trunk, 0f)
        lineTo(trunk, fork)
        cubicTo(trunk, turn - LodyRadius.sm.toPx(), trunk + LodySpacing.xs.toPx(), turn,
            (LodyLayout.childIndent - LodySpacing.sm).toPx(), turn)
        if (!last) {
            moveTo(trunk, fork)
            lineTo(trunk, size.height)
        }
    }
    drawPath(path, color, style = Stroke(width = RailWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The first child's trunk: it starts below the collapse chevron and continues only when more children follow. */
private fun DrawScope.drawTrunkBelowChevron(color: Color, last: Boolean, turn: Float) {
    if (last) return
    val trunk = RailX.toPx()
    drawLine(color, Offset(trunk, turn + FamilyChevron.toPx() / 2 + LodySpacing.xs.toPx()), Offset(trunk, size.height),
        strokeWidth = RailWidth.toPx(), cap = StrokeCap.Round)
}

/**
 * A folded family's line under the opener (Slack's "7 replies"): chevron + "7 opened chats",
 * aligned with the opener's title. The whole line expands the family and announces its count and
 * folded state; the separate opener row opens the conversation. Expanded, the line hides and the
 * first child's chevron folds the family again.
 */
@Composable
private fun FamilyToggle(count: Int, childAttention: ChatAttention?, onToggle: () -> Unit) {
    val colors = LodyTheme.colors
    val action = stringResource(R.string.home_tree_expand)
    val label = pluralStringResource(R.plurals.home_tree_count, count, count)
    val description = label + ", " + stringResource(R.string.home_tree_collapsed)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LodySize.controlSm)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button; onClick(action) { onToggle(); true } },
    ) {
        Row(
            Modifier
                .padding(start = LodyLayout.screenPaddingX - LodySpacing.xs)
                .heightIn(min = LodySize.controlSm)
                .clip(RoundedCornerShape(LodyRadius.sm))
                .padding(start = LodySpacing.xs, end = LodySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LodySpacing.xs),
        ) {
            Glyph(LodyIcons.Forward, colors.iconSecondary, FamilyChevron)
            Text(
                label,
                style = LodyTheme.typography.footnote.copy(fontFeatureSettings = "tnum"),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        if (childAttention != null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = LodyLayout.screenPaddingX).size(LodyIconSize.md), contentAlignment = Alignment.Center) {
                AttentionGlyph(childAttention)
            }
        }
    }
}

private val PrStatuses = setOf("open", "merged", "closed", "draft")

/** Web `SessionPrIcon`: open, merged, closed and draft each have their own Lucide mark and colour. */
@Composable
private fun PullRequestMark(status: String, ci: String, modifier: Modifier = Modifier) {
    val label = when (status) {
        "merged" -> R.string.home_pr_merged
        "closed" -> R.string.home_pr_closed
        "draft" -> R.string.home_pr_draft
        else -> R.string.home_pr_open
    }
    val ciLabel = when (ci) {
        "pass" -> stringResource(R.string.home_ci_pass)
        "fail" -> stringResource(R.string.home_ci_fail)
        "pending" -> stringResource(R.string.home_ci_pending)
        "expected" -> stringResource(R.string.home_ci_expected)
        else -> null
    }
    val description = listOfNotNull(stringResource(label), ciLabel).joinToString(", ")
    app.lody.android.designsystem.LodyPullRequestIcon(status, ci, modifier.semantics { contentDescription = description })
}

/** Wall clock for row ages, refreshed each minute while the list is on screen. */
@Composable
private fun rememberNow(): Long {
    // The server clock the Status view layout was computed on (device clock until a live catalog).
    var now by remember { mutableStateOf(app.lody.android.runtime.StatusClock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now % 60_000L)
            now = app.lody.android.runtime.StatusClock.now()
        }
    }
    return now
}

@Composable
private fun ageText(at: Long, now: Long): String = when (val age = ChatListModel.age(at, now)) {
    ChatListModel.Age.Now -> stringResource(R.string.home_age_now)
    is ChatListModel.Age.Minutes -> stringResource(R.string.home_age_minutes, age.value)
    is ChatListModel.Age.Hours -> stringResource(R.string.home_age_hours, age.value)
    is ChatListModel.Age.Days -> stringResource(R.string.home_age_days, age.value)
    is ChatListModel.Age.Date -> {
        val locale = LocalConfiguration.current.locales[0]
        remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }.format(age.date)
    }
}

@Composable
private fun SettingsRow(title: String, icon: ImageVector?, tint: Color, onClick: (() -> Unit)?, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LodySize.listRow)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = LodyLayout.screenPaddingX),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LodyLayout.listRowGap),
    ) {
        leading?.invoke()
        Text(title, style = LodyTheme.typography.body, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (icon != null) Glyph(icon, if (icon == LodyIcons.Check) LodyTheme.colors.accentFg else tint)
    }
}

@Composable
private fun EmptyText(text: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = LodyLayout.screenPaddingX), contentAlignment = Alignment.Center) {
        Text(text, style = LodyTheme.typography.body, color = LodyTheme.colors.textSecondary)
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color, size: Dp = LodyIconSize.md, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/** Web `SessionRowStatusIndicator`: alert when failed, hand when waiting, working grid, unread dot. */
@Composable
internal fun AttentionGlyph(attention: ChatAttention) {
    val colors = LodyTheme.colors
    when (attention) {
        ChatAttention.Failed -> Glyph(LodyIcons.Alert, colors.dangerDefault, LodyIconSize.sm)
        ChatAttention.Waiting -> Glyph(LodyIcons.Hand, colors.accentDefault, LodyIconSize.sm)
        ChatAttention.Running -> LodyWorkingGrid()
        ChatAttention.Unread -> Box(Modifier.size(LodySpacing.sm).background(colors.accentDefault, CircleShape))
    }
}
