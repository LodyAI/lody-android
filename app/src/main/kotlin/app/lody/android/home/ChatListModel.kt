package app.lody.android.home

import app.lody.android.runtime.CatalogSession
import app.lody.android.runtime.CatalogSnapshot
import app.lody.android.runtime.StatusTier
import app.lody.android.runtime.StatusViewLayout
import app.lody.android.runtime.StatusViewRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What a chat (or a collapsed group) wants from the user, if anything. Web `SessionRowStatusIndicator` order. */
enum class ChatAttention { Running, Waiting, Failed, Unread }

/**
 * One Home row. [place] is the project (or repo) name, empty for unassigned chats; [activityAt]
 * is epoch ms (last message, else creation) and drives buckets and the trailing age. [prStatus]
 * is the latest pull request's status (open / merged / closed / draft), empty when there is none.
 */
data class ChatItem(
    val id: String,
    val title: String,
    val attention: ChatAttention?,
    val place: String = "",
    val placeIsRepo: Boolean = false,
    val repository: String? = null,
    val branch: String = "",
    val prStatus: String = "",
    val activityAt: Long? = null,
    /** CI on the latest PR's head: pass / fail / pending, empty when unknown. */
    val prCi: String = "",
    val projectId: String = "",
    val pinned: Boolean = false,
    val pinSectionId: String? = null,
    /** From the runtime's Status view layout; null when the list has no layout row for it. */
    val tier: StatusTier? = null,
    /** While in Later: when it wakes (the row's countdown). */
    val snoozedUntil: Long? = null,
    val woken: Boolean = false,
    /** Later / Settle / Move to Focus / reorder in Focus. */
    val canManage: Boolean = false,
    /** Pin, unpin and pin placement. */
    val canPrefer: Boolean = false,
    /** The chat that opened this one (resolved like iOS `SessionRows.tree`), if any. */
    val openerId: String? = null,
    /** Set by [ChatListModel.nest]: shown nested under this row id. */
    val nestedUnder: String? = null,
    val lastChild: Boolean = false,
    /** Has nested rows right below it (the family toggle's row). */
    val treeParent: Boolean = false,
    /** How many rows are nested under this one; the family toggle's count. */
    val childCount: Int = 0,
    /** Hidden children only; never replaces the parent row status. */
    val childAttention: ChatAttention? = null,
    val ownerId: String? = null,
) {
    val unread: Boolean get() = attention == ChatAttention.Unread
}

/** Activity buckets, matching iOS `InboxSections.activityBucket`. */
enum class TimeBucket { Today, Yesterday, Week, Month, Older }

data class TimeSection(val bucket: TimeBucket, val chats: List<ChatItem>)

/** Whose chats Home lists: only the signed-in user's, or the workspace's. iOS: `HomeScope`. */
enum class HomeScope(val key: String) { Mine("mine"), Team("team") }

/**
 * How Home groups chats. Time and project groups are newest activity first; the Status view
 * (Focus / Later / Settled) is listed exactly as the runtime's layout orders it. iOS: `HomeGrouping`.
 */
enum class HomeGrouping(val key: String) { Time("time"), Project("project"), Status("status") }

/**
 * One Home group; the screen localizes [header]. A [collapsed] group shows only its label; its
 * [chats] stay for the project index's status tint.
 */
data class HomeSection(
    val id: String,
    val header: Header,
    val chats: List<ChatItem>,
    val repository: String? = null,
    val collapsed: Boolean = false,
    /** A pin section's rows that the view scope hid (named under its rows while expanded). */
    val hiddenCount: Int = 0,
) {
    val rootCount: Int get() = chats.count { it.nestedUnder == null }
    /** Settled folds behind "Show all" past [ChatListModel.SETTLED_LIMIT] conversations. */
    val folds: Boolean get() = !collapsed && (header as? Header.Tier)?.tier == StatusTier.Settled && rootCount > ChatListModel.SETTLED_LIMIT
    fun visibleChats(settledExpanded: Boolean): List<ChatItem> {
        if (collapsed) return emptyList()
        if (!folds || settledExpanded) return chats
        val roots = chats.filter { it.nestedUnder == null }.take(ChatListModel.SETTLED_LIMIT).mapTo(mutableSetOf()) { it.id }
        return chats.filter { it.id in roots || it.nestedUnder in roots }
    }

    sealed interface Header {
        data class Bucket(val bucket: TimeBucket) : Header
        data class Project(val name: String) : Header
        /** A pin section: [sectionId] null is the default section (titled "Pinned"). */
        data class Pinned(val sectionId: String? = null, val name: String? = null) : Header
        data object Chats : Header
        /** A Status view tier (never [StatusTier.Pinned]; pinned rows sit under [Pinned]). */
        data class Tier(val tier: StatusTier) : Header
    }
}

data class ChatGroup(val id: String, val name: String, val kind: Kind, val chats: List<ChatItem>) {
    enum class Kind { Pinned, Chats, Project }

    /** Failed and waiting need a decision, so they outrank work in progress and unread. */
    val mostUrgent: ChatAttention?
        get() = listOf(ChatAttention.Failed, ChatAttention.Waiting, ChatAttention.Running, ChatAttention.Unread)
            .firstOrNull { wanted -> chats.any { it.attention == wanted } }
}

/**
 * Home list projection. Mirrors the iOS `InboxSections.projects` grouping: pinned chats
 * first, projects by name, unassigned chats last; archived chats are hidden.
 */
object ChatListModel {
    const val PINNED_ID = "pinned"
    const val CHATS_ID = "chats"

    /** Groups beyond this many chats show a "Show N more" row (iOS shows the same 5). */
    const val VISIBLE_CHATS = 5

    private val running = setOf("running", "initializing", "processing", "in_progress", "queued")
    private val waiting = setOf("requestPermission", "waiting")

    fun attention(status: String): ChatAttention? = when (status) {
        in running -> ChatAttention.Running
        in waiting -> ChatAttention.Waiting
        "error" -> ChatAttention.Failed
        else -> null
    }

    /**
     * The latest finished turn failed (web `hasFailedLatestTurn`): the failure marker still names
     * the turn handled last. A later handled or canceled turn moves that pointer on.
     */
    fun latestTurnFailed(session: CatalogSession): Boolean =
        session.lastFailedUserMsgId != null && session.lastFailedUserMsgId == session.lastHandledUserMsgId

    /**
     * Status first; a failed latest turn shows unless the agent is running again, and an idle chat
     * still flags an open question (`awaitingUserSince`) or an unread reply.
     */
    fun attention(session: CatalogSession): ChatAttention? {
        val status = attention(session.status)
        return when {
            status == ChatAttention.Failed -> status
            status != ChatAttention.Running && latestTurnFailed(session) -> ChatAttention.Failed
            session.awaitingUserSince != null && status != ChatAttention.Running -> ChatAttention.Waiting
            status != null -> status
            isUnread(session) -> ChatAttention.Unread
            else -> null
        }
    }

    /** The catalog's CI letters as web reads them: s pass; f / e fail; p / x pending. */
    fun ci(letter: String?): String = when (letter) {
        "s" -> "pass"
        "f", "e" -> "fail"
        "p" -> "pending"
        "x" -> "expected"
        else -> ""
    }

    /**
     * Home in the view menu's grouping; archived chats are hidden. With [owner] ("My tasks"),
     * only chats whose session meta `userId` is that user are listed.
     */
    fun home(
        snapshot: CatalogSnapshot,
        grouping: HomeGrouping,
        owner: String? = null,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        /** The view menu's Archived switch: only archived chats, in activity buckets (iOS `InboxSections.archived`). */
        archived: Boolean = false,
    ): List<HomeSection> {
        // Keep the whole family and parent context when filtering to the owner's chats.
        val scoped = owner?.let { id ->
            val mine = snapshot.sessions.filter { it.userId == id }.mapTo(mutableSetOf()) { it.id }
            val included = families(snapshot, archived).filter { family -> family.any { it.id in mine } }.flatten().mapTo(mutableSetOf()) { it.id }
            val all = snapshot.sessions.associateBy { it.id }
            snapshot.copy(sessions = snapshot.sessions.filter { conversation(it.id, all) in included })
        } ?: snapshot
        // Archived chats: no Pinned group and no project or status grouping.
        if (archived) return timeline(scoped, today, zone, archived = true)
            .map { HomeSection("bucket-${it.bucket}", HomeSection.Header.Bucket(it.bucket), it.chats) }
        // The runtime's layout owns the Pinned area in every grouping and the whole Status view.
        val layout = snapshot.statusView?.let { if (owner != null) it.mine else it.team }
        if (layout != null) {
            val items = homeItems(snapshot).associateBy { it.id }
            val pinned = layout.pinned.map { group ->
                HomeSection(pinSectionKey(group.sectionId), HomeSection.Header.Pinned(group.sectionId, group.name), rows(group.rows, items), hiddenCount = group.hiddenCount)
            }
            val rest = if (grouping == HomeGrouping.Status) tiers(layout, items) else {
                val lifted = layout.pinned.flatMap { group -> group.rows.flatMap { listOf(it) + it.children } }.mapTo(mutableSetOf()) { it.id }
                val listed = scoped.sessions.filterNot { it.id in lifted || conversation(it.id, all(scoped)) in lifted }
                // Time and project groups keep their native order; each row still carries its layout facts.
                val laidOut = flatten(layout.focus + layout.later + layout.settled).associateBy { it.id }
                grouped(scoped.copy(sessions = listed), grouping, today, zone).map { section ->
                    section.copy(chats = section.chats.map { chat -> laidOut[chat.id]?.let { laid(chat, it).copy(activityAt = chat.activityAt) } ?: chat })
                }
            }
            return pinned + rest
        }
        val pinnedIds = scoped.sessions.filter { it.pinned }.mapTo(mutableSetOf()) { it.id }
        val pinned = families(scoped).filter { family -> family.any { it.id in pinnedIds } }.flatten()
        val lifted = pinned.mapTo(mutableSetOf()) { it.id }
        val rest = grouped(scoped.copy(sessions = scoped.sessions.filterNot { it.id in lifted }), grouping, today, zone)
        return if (pinned.isEmpty()) rest else listOf(HomeSection(PINNED_ID, HomeSection.Header.Pinned(), pinned)) + rest
    }

    /**
     * Without a runtime layout (an older runtime or cache) the Status view cannot be shown, and the
     * native client never classifies tiers itself: it lists by time until a layout arrives.
     */
    private fun grouped(snapshot: CatalogSnapshot, grouping: HomeGrouping, today: LocalDate, zone: ZoneId): List<HomeSection> =
        when (grouping) {
            HomeGrouping.Time, HomeGrouping.Status -> timeline(snapshot, today, zone).map { HomeSection("bucket-${it.bucket}", HomeSection.Header.Bucket(it.bucket), it.chats) }
            HomeGrouping.Project -> byProject(snapshot)
        }

    /** Stable section ids: the default pin section keeps the former Pinned group's id (and its collapse). */
    fun pinSectionKey(sectionId: String?) = if (sectionId == null) PINNED_ID else "pin-$sectionId"

    fun tierKey(tier: StatusTier) = "status-${tier.key}"

    /** Later and Settled start collapsed (device-local; the stored set records changes from this default). */
    fun collapsedByDefault(sectionId: String) = sectionId == tierKey(StatusTier.Later) || sectionId == tierKey(StatusTier.Settled)

    /** Focus always lists (it is where rows are dropped back); Later and Settled only when nonempty. */
    private fun tiers(layout: StatusViewLayout, items: Map<String, ChatItem>): List<HomeSection> =
        listOf(StatusTier.Focus to layout.focus, StatusTier.Later to layout.later, StatusTier.Settled to layout.settled)
            .filter { (tier, rows) -> tier == StatusTier.Focus || rows.isNotEmpty() }
            .map { (tier, rows) -> HomeSection(tierKey(tier), HomeSection.Header.Tier(tier), rows(rows, items)) }

    /**
     * Layout rows in layout order, each root followed by its opened children one level down (as
     * [nest] shapes families). Rows the catalog no longer carries are skipped; unread and the
     * activity time come from the layout (a woken conversation stays unread until read).
     */
    private fun rows(rows: List<StatusViewRow>, items: Map<String, ChatItem>): List<ChatItem> = rows.flatMap { row ->
        val root = items[row.id] ?: return@flatMap emptyList()
        val kids = row.children.flatMap { listOf(it) + flatten(it.children) }.mapNotNull { kid -> items[kid.id]?.let { kid to it } }
        listOf(laid(root, row).copy(nestedUnder = null, lastChild = false, treeParent = kids.isNotEmpty(), childCount = kids.size)) +
            kids.mapIndexed { index, (kid, item) ->
                laid(item, kid).copy(nestedUnder = root.id, lastChild = index == kids.lastIndex, treeParent = false, childCount = 0,
                    place = item.place.takeUnless { it == root.place }.orEmpty())
            }
    }

    private fun flatten(rows: List<StatusViewRow>): List<StatusViewRow> = rows.flatMap { listOf(it) + flatten(it.children) }

    private fun laid(item: ChatItem, row: StatusViewRow): ChatItem = item.copy(
        attention = when {
            item.attention == null && row.unread -> ChatAttention.Unread
            item.attention == ChatAttention.Unread && !row.unread -> null
            else -> item.attention
        },
        activityAt = row.activityAt.takeIf { it > 0 } ?: item.activityAt,
        tier = row.tier,
        snoozedUntil = row.snoozedUntil,
        woken = row.woken,
        canManage = row.canManage,
        canPrefer = row.canPrefer,
    )

    private fun all(snapshot: CatalogSnapshot) = snapshot.sessions.associateBy { it.id }

    /** The workspace-scope layout row for a conversation (a Tab resolves to its conversation), if listed. */
    fun layoutRow(snapshot: CatalogSnapshot, id: String): StatusViewRow? {
        val layout = snapshot.statusView?.team ?: return null
        val conversation = conversation(id, all(snapshot))
        return flatten(layout.pinned.flatMap { it.rows } + layout.focus + layout.later + layout.settled).firstOrNull { it.id == conversation }
    }

    /**
     * The read receipt opening [id] owes: up to its last message, or its wake-up time when a snooze
     * expired later (a woken conversation stays unread until read). Null once already read.
     */
    fun readReceipt(snapshot: CatalogSnapshot, id: String?): Pair<String, Long>? {
        val session = snapshot.sessions.firstOrNull { it.id == id } ?: return null
        val woken = layoutRow(snapshot, session.id)?.takeIf { it.id == session.id }?.activityAt
        val at = listOfNotNull(session.lastMessageAt, woken).maxOrNull() ?: return null
        if ((session.lastReadAt ?: Long.MIN_VALUE) >= at) return null
        return session.id to at
    }

    /** One group per project, most recently active first; unassigned chats form the "Chats" group. */
    fun byProject(snapshot: CatalogSnapshot): List<HomeSection> {
        val projects = snapshot.projects.associateBy { it.id }
        return families(snapshot)
            .groupBy { family -> family.first().let { if (isChat(it.projectId)) CHATS_ID else it.projectId } }
            .map { (key, chats) ->
                val header = if (key == CHATS_ID) HomeSection.Header.Chats else HomeSection.Header.Project(projects[key]?.name.orEmpty())
                HomeSection("project-$key", header, chats.flatten(),
                    projects[key]?.repoFullName?.takeIf { it.isNotBlank() }
                        ?: key.removePrefix("github:").takeIf { key.startsWith("github:") && '/' in it }
                        ?: chats.firstOrNull()?.firstOrNull()?.repository,
                )
            }
    }

    /** Settled conversations beyond this many fold behind "Show all" (the shared default, as iOS). */
    const val SETTLED_LIMIT = 20

    /** Non-archived (or, with [archived], only archived) rows, newest activity first, carrying their project for the detail line. */
    private fun homeItems(snapshot: CatalogSnapshot, archived: Boolean = false): List<ChatItem> {
        val projects = snapshot.projects.associateBy { it.id }
        val all = snapshot.sessions.associateBy { it.id }
        val attention = conversationAttention(snapshot.sessions)
        return snapshot.sessions
            .filter { it.archived == archived && !isTab(it) }
            .map { session ->
                val project = projects[session.projectId]?.takeUnless { isChat(it.id) }
                item(session, project?.name.orEmpty(), project?.repoFullName != null, attention[session.id]).copy(openerId = opener(session, all), repository = project?.repoFullName)
            }
            .sortedByDescending { it.activityAt ?: Long.MIN_VALUE }
    }

    /** Build families before any display bucket; only the root participates in grouping. */
    private fun families(snapshot: CatalogSnapshot, archived: Boolean = false): List<List<ChatItem>> =
        nest(homeItems(snapshot, archived)).groupBy { it.nestedUnder ?: it.id }.values.toList()

    /**
     * A Tab or side chat (`parentSessionId`) belongs to its parent conversation, not the list
     * (web `sessionListAtom` drops them from the sidebar entirely; iOS `SessionRows.isTab`).
     */
    fun isTab(session: CatalogSession): Boolean = !session.parentSessionId.isNullOrEmpty()

    /** Display-only aggregation; closing a Tab suppresses unread, not ongoing work. */
    private fun conversationAttention(sessions: List<CatalogSession>): Map<String, ChatAttention?> {
        val all = sessions.associateBy { it.id }
        val priority = listOf(ChatAttention.Failed, ChatAttention.Waiting, ChatAttention.Running, ChatAttention.Unread)
        val result = sessions.filterNot(::isTab).associate { it.id to attention(it) }.toMutableMap()
        for (tab in sessions) {
            if (!isTab(tab) || tab.archived || tab.childSessionPlacement == "side-panel") continue
            val owner = all[conversation(tab.id, all)] ?: continue
            if (isTab(owner) || owner.archived || owner.machineId != tab.machineId) continue
            val candidate = attention(tab) ?: continue
            val current = result[owner.id]
            if (current == null || priority.indexOf(candidate) < priority.indexOf(current)) result[owner.id] = candidate
        }
        return result
    }

    /**
     * Only opened chats nest under their opener (web `buildOpenedBySessionTree`). A chat opened
     * from inside a Tab nests under the conversation that Tab belongs to.
     */
    private fun opener(session: CatalogSession, all: Map<String, CatalogSession>): String? =
        listOfNotNull(session.openedBySessionId, session.openedByRootSessionId)
            .map { conversation(it, all) }
            .firstOrNull { it != session.id && all[it]?.archived == session.archived }

    /** The listed conversation a session id stands for: a Tab resolves to its parent. */
    private fun conversation(id: String, all: Map<String, CatalogSession>): String {
        var current = id
        val seen = mutableSetOf(current)
        while (true) {
            val parent = all[current]?.parentSessionId?.takeIf { it.isNotEmpty() } ?: return current
            if (!seen.add(parent)) return current
            current = parent
        }
    }

    /**
     * Nests each chat under its root opener when that opener is in the same group (one level, as
     * on web and iOS). A family takes its freshest member's place; members keep their order.
     */
    fun nest(chats: List<ChatItem>): List<ChatItem> {
        val byId = chats.associateBy { it.id }
        fun root(chat: ChatItem): String {
            val seen = mutableSetOf(chat.id)
            var current = chat
            while (true) {
                val next = current.openerId?.let(byId::get) ?: return current.id
                if (!seen.add(next.id)) return chat.id
                current = next
            }
        }
        val order = mutableListOf<String>()
        val children = mutableMapOf<String, MutableList<ChatItem>>()
        for (chat in chats) {
            val id = root(chat)
            if (id !in children) {
                children[id] = mutableListOf()
                order += id
            }
            if (id != chat.id) children.getValue(id) += chat
        }
        return order.flatMap { id ->
            val kids = children.getValue(id)
            listOf(byId.getValue(id).copy(treeParent = kids.isNotEmpty(), childCount = kids.size, nestedUnder = null, lastChild = false)) +
                kids.mapIndexed { index, kid ->
                    // A child almost always shares its opener's project: show it only when it differs.
                    val place = kid.place.takeUnless { it == byId.getValue(id).place }.orEmpty()
                    kid.copy(nestedUnder = id, lastChild = index == kids.lastIndex, treeParent = false, childCount = 0, place = place)
                }
        }
    }

    fun isUnread(session: CatalogSession): Boolean {
        if (session.isTabClosed) return false
        val message = session.lastMessageAt ?: return false
        return session.lastReadAt?.let { message > it } ?: true
    }

    fun activityAt(session: CatalogSession): Long? =
        session.lastMessageAt ?: runCatching { Instant.parse(session.createdAt).toEpochMilli() }.getOrNull()

    fun isChat(projectId: String) = projectId.endsWith(":unassigned")

    fun groups(snapshot: CatalogSnapshot): List<ChatGroup> {
        val attention = conversationAttention(snapshot.sessions)
        val visible = snapshot.sessions.filter { !it.archived && !isTab(it) }.sortedByDescending { activityAt(it) ?: Long.MIN_VALUE }
        val (pinned, rest) = visible.partition { it.pinned }
        val groups = mutableListOf<ChatGroup>()
        if (pinned.isNotEmpty()) groups += ChatGroup(PINNED_ID, "", ChatGroup.Kind.Pinned, pinned.map { item(it, displayAttention = attention[it.id]) })
        snapshot.projects
            .filterNot { isChat(it.id) }
            .sortedBy { it.name.lowercase() }
            .forEach { project ->
                val chats = rest.filter { it.projectId == project.id }
                if (chats.isNotEmpty()) groups += ChatGroup(project.id, project.name, ChatGroup.Kind.Project, chats.map { item(it, displayAttention = attention[it.id]) })
            }
        val chats = rest.filter { isChat(it.projectId) }
        if (chats.isNotEmpty()) groups += ChatGroup(CHATS_ID, "", ChatGroup.Kind.Chats, chats.map { item(it, displayAttention = attention[it.id]) })
        return groups
    }

    /** Home "by time": non-archived (or, with [archived], only archived) chats in activity buckets, newest first. */
    fun timeline(snapshot: CatalogSnapshot, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), archived: Boolean = false): List<TimeSection> {
        return families(snapshot, archived)
            .groupBy { bucket(it.first().activityAt, today, zone) }
            .toSortedMap()
            .map { (bucket, chats) -> TimeSection(bucket, chats.flatten()) }
    }

    fun bucket(createdAt: String, today: LocalDate, zone: ZoneId): TimeBucket =
        bucket(runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrNull(), today, zone)

    fun bucket(at: Long?, today: LocalDate, zone: ZoneId): TimeBucket {
        val date = at?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return TimeBucket.Older
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0 -> TimeBucket.Today
            days == 1L -> TimeBucket.Yesterday
            days <= 7 -> TimeBucket.Week
            days <= 30 -> TimeBucket.Month
            else -> TimeBucket.Older
        }
    }

    /** Units of the trailing age; under a week it is now / minutes / hours / days, then a date. */
    sealed interface Age {
        data object Now : Age
        data class Minutes(val value: Long) : Age
        data class Hours(val value: Long) : Age
        data class Days(val value: Long) : Age
        data class Date(val date: LocalDate) : Age
    }

    /** Mirrors iOS `LodyRelativeTime.compact`. */
    fun age(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Age {
        val elapsed = now - at
        return when {
            elapsed < MINUTE -> Age.Now
            elapsed < HOUR -> Age.Minutes(elapsed / MINUTE)
            elapsed < DAY -> Age.Hours(elapsed / HOUR)
            elapsed < 7 * DAY -> Age.Days(elapsed / DAY)
            else -> Age.Date(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
        }
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun searchTimeline(sections: List<TimeSection>, query: String): List<TimeSection> {
        val term = query.trim()
        if (term.isEmpty()) return sections
        return sections.mapNotNull { section ->
            section.chats.filter { it.title.contains(term, ignoreCase = true) || it.place.contains(term, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?.let { section.copy(chats = nest(it)) }
        }
    }

    /**
     * Hides the nested chats of [folded] openers and summarizes children separately.
     */
    fun fold(chats: List<ChatItem>, folded: Set<String>): List<ChatItem> {
        if (folded.isEmpty()) return chats
        val family = chats.filter { it.nestedUnder in folded }.groupBy { it.nestedUnder }
        return chats.mapNotNull { chat ->
            when {
                chat.nestedUnder in folded -> null
                chat.treeParent && chat.id in folded -> chat.copy(childAttention = mostUrgent(family[chat.id].orEmpty()))
                else -> chat
            }
        }
    }

    private fun mostUrgent(chats: List<ChatItem>): ChatAttention? =
        listOf(ChatAttention.Failed, ChatAttention.Waiting, ChatAttention.Running, ChatAttention.Unread)
            .firstOrNull { wanted -> chats.any { it.attention == wanted } }

    /** Keeps only matching chats; groups without matches drop out. */
    fun search(groups: List<ChatGroup>, query: String): List<ChatGroup> {
        val term = query.trim()
        if (term.isEmpty()) return groups
        return groups.mapNotNull { group ->
            group.chats.filter { it.title.contains(term, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?.let { group.copy(chats = it) }
        }
    }

    private fun item(session: CatalogSession, place: String = "", placeIsRepo: Boolean = false, displayAttention: ChatAttention? = attention(session)) = ChatItem(
        id = session.id,
        ownerId = session.userId,
        title = session.title,
        attention = displayAttention,
        place = place,
        placeIsRepo = placeIsRepo,
        branch = session.branchName.orEmpty(),
        prStatus = session.pullRequests.lastOrNull()?.status.orEmpty(),
        activityAt = activityAt(session),
        prCi = ci(session.pullRequests.lastOrNull()?.ci),
        projectId = session.projectId,
        pinned = session.pinned,
        pinSectionId = session.pinSectionId,
    )
}
