package app.lody.android.home

import app.lody.android.runtime.CatalogJson
import app.lody.android.runtime.CatalogProject
import app.lody.android.runtime.CatalogSession
import app.lody.android.runtime.CatalogSnapshot
import app.lody.android.runtime.PinSection
import app.lody.android.runtime.StatusTier
import app.lody.android.runtime.StatusView
import app.lody.android.runtime.StatusViewCommand
import app.lody.android.runtime.StatusViewJson
import app.lody.android.runtime.StatusViewTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Home renders the runtime's Status view layout exactly as given (the shared `StatusViewLayout`
 * JSON, synthetic here) and turns drags and menu items into the shared planner's commands.
 */
class StatusViewTest {
    private fun session(id: String, at: Long, vararg extra: Pair<String, Any>) = CatalogSession(id, "m", "p", "Chat $id", "idle", false, false, "2026-11-01T00:00:00Z", lastMessageAt = at)

    private fun row(id: String, tier: String, vararg fields: Pair<String, Any?>) = JSONObject().put("id", id).put("tier", tier)
        .put("canManage", true).put("canPrefer", true).apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

    /** The catalog `statusView` the runtime computes; order deliberately disagrees with activity time. */
    private val layoutJson = JSONObject()
        .put("pinned", JSONArray()
            .put(JSONObject().put("sectionId", JSONObject.NULL).put("name", JSONObject.NULL).put("rows", JSONArray().put(row("pin", "pinned"))).put("hiddenCount", 0))
            .put(JSONObject().put("sectionId", "maint").put("name", "Maintenance").put("rows", JSONArray()).put("hiddenCount", 2)))
        .put("focus", JSONArray()
            .put(row("old", "focus", "children" to JSONArray().put(row("kid", "focus"))))
            .put(row("woke", "focus", "woken" to true, "unread" to true, "activityAt" to 9_000))
            .put(row("new", "focus")))
        .put("later", JSONArray().put(row("snoozed", "later", "snoozedUntil" to 50_000)))
        .put("settled", JSONArray())
        .put("canManageSections", true)
        .put("nextChangeAt", 50_000)

    private val sessions = listOf(
        session("pin", 1).copy(pinned = true),
        session("old", 2),
        session("kid", 3).copy(openedBySessionId = "old"),
        session("woke", 4).copy(lastReadAt = 4, snoozedUntil = 9_000),
        session("new", 8),
        session("snoozed", 7),
        session("tab", 9).copy(parentSessionId = "old"),
    )

    private fun snapshot(view: StatusView? = StatusViewJson.statusView(JSONObject().put("mine", layoutJson).put("team", layoutJson).put("nowMs", 1_000).put("sectionsLoaded", true))) = CatalogSnapshot(
        1, listOf(CatalogProject("p", "m", "Lody", "/l")), sessions, listOf("m"), statusView = view,
        pinSections = listOf(PinSection("maint", "Maintenance", "a0")),
    )

    private fun home(grouping: HomeGrouping, view: StatusView? = snapshot().statusView) =
        ChatListModel.home(snapshot(view), grouping, today = LocalDate.parse("2026-11-01"), zone = ZoneOffset.UTC)

    @Test fun statusGroupingListsPinSectionsThenTiersInLayoutOrder() {
        val sections = home(HomeGrouping.Status)
        assertEquals(listOf("pinned", "pin-maint", "status-focus", "status-later"), sections.map { it.id })
        assertEquals(HomeSection.Header.Pinned("maint", "Maintenance"), sections[1].header)
        assertEquals(2, sections[1].hiddenCount)
        // Layout order, not newest activity; the opened child stays under its opener.
        assertEquals(listOf("old", "kid", "woke", "new"), sections[2].chats.map { it.id })
        assertEquals(listOf(null, "old", null, null), sections[2].chats.map { it.nestedUnder })
        assertEquals(1, sections[2].chats.first().childCount)
        // A woken conversation is unread from the layout even though the catalog says read.
        val woke = sections[2].chats.first { it.id == "woke" }
        assertEquals(ChatAttention.Unread, woke.attention)
        assertEquals(9_000L, woke.activityAt)
        assertEquals(50_000L, sections[3].chats.single().snoozedUntil)
        assertEquals(StatusTier.Later, sections[3].chats.single().tier)
    }

    @Test fun focusAlwaysListsAndLaterAndSettledStartCollapsed() {
        val empty = StatusViewJson.statusView(JSONObject().put("mine", JSONObject()).put("team", JSONObject()))
        assertEquals(listOf("status-focus"), home(HomeGrouping.Status, empty).map { it.id })
        assertTrue(ChatListModel.collapsedByDefault("status-later"))
        assertTrue(ChatListModel.collapsedByDefault("status-settled"))
        assertFalse(ChatListModel.collapsedByDefault("status-focus"))
        assertFalse(ChatListModel.collapsedByDefault("pinned"))
    }

    @Test fun otherGroupingsTakeOnlyThePinnedAreaFromTheLayout() {
        val sections = home(HomeGrouping.Time)
        assertEquals(listOf("pinned", "pin-maint"), sections.take(2).map { it.id })
        val rest = sections.drop(2).flatMap { it.chats }
        // Time buckets keep native newest-first order and never repeat a pinned row.
        assertEquals(listOf("new", "snoozed", "woke", "old", "kid"), rest.map { it.id })
        assertTrue(rest.first { it.id == "snoozed" }.canManage)
        assertEquals(StatusTier.Later, rest.first { it.id == "snoozed" }.tier)
    }

    @Test fun withoutALayoutStatusFallsBackToTimeAndNeverClassifiesNatively() {
        val sections = home(HomeGrouping.Status, view = null)
        assertEquals("pinned", sections.first().id)
        assertTrue(sections.drop(1).all { it.header is HomeSection.Header.Bucket })
        assertTrue(sections.flatMap { it.chats }.all { it.tier == null })
    }

    @Test fun layoutAndSessionFieldsSurviveTheCatalogCache() {
        val view = snapshot().statusView!!
        assertEquals(view, StatusViewJson.statusView(JSONObject(StatusViewJson.putStatusView(view).toString())))
        assertEquals(listOf(PinSection("maint", "Maintenance", "a0")), StatusViewJson.pinSections(JSONArray(StatusViewJson.putPinSections(snapshot().pinSections).toString())))
        val meta = session("x", 1).copy(orderKey = "a1", pinSectionId = "maint", snoozedUntil = 5, settledAt = 6, focusRestoredAt = 7)
        assertEquals(meta, CatalogJson.session(session("x", 1), JSONObject(CatalogJson.putSession(JSONObject(), meta).toString())).copy(lastMessageAt = 1))
        // A malformed layout drops to null instead of rejecting the catalog.
        assertNull(StatusViewJson.statusView(JSONObject().put("mine", JSONObject().put("focus", JSONArray().put(JSONObject().put("tier", "focus")))).put("team", JSONObject())))
        assertNull(StatusViewJson.statusView(JSONObject().put("mine", JSONObject().put("focus", JSONArray().put(row("a", "nowhere")))).put("team", JSONObject())))
    }

    @Test fun commandsCarryTheSharedPlannerShapes() {
        fun tree(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { tree(value.opt(it)) }
            is JSONArray -> (0 until value.length()).map { tree(value.opt(it)) }
            is Number -> value.toLong()
            else -> value
        }
        fun same(expected: String, command: StatusViewCommand) = assertEquals(tree(JSONObject(expected)), tree(StatusViewJson.command(command)))
        same("""{"type":"move","sessionId":"a","to":{"area":"pinned","sectionId":null},"beforeId":"b"}""", StatusViewCommand.Move("a", StatusViewTarget.Pinned(null), "b"))
        same("""{"type":"move","sessionId":"a","to":{"area":"focus"},"beforeId":null}""", StatusViewCommand.Move("a", StatusViewTarget.Focus, null))
        same("""{"type":"snooze","sessionId":"a","until":5,"includeOpened":true}""", StatusViewCommand.Snooze("a", 5, true))
        same("""{"type":"setPinned","sessionId":"a","pinned":false}""", StatusViewCommand.SetPinned("a", false))
        same("""{"type":"moveSection","sectionId":"s","beforeId":null}""", StatusViewCommand.MoveSection("s", null))
        assertEquals(listOf("tomorrowMorning"), StatusViewJson.presets(JSONObject("""{"nowMs":1,"presets":[{"id":"tomorrowMorning","until":9,"countdown":"9h"},{"id":"bad"},{"until":3}]}""")).map { it.id })
    }

    @Test fun openingAConversationOwesReceiptsAsTheSharedUnreadRuleReadsThem() {
        val data = snapshot()
        val now = 10_000L
        assertEquals(listOf("new" to 8L), ChatListModel.readReceipts(data, "new", now))
        // A woken conversation is read up to its wake-up time (its expired snooze, on the server clock).
        assertEquals(listOf("woke" to 9_000L), ChatListModel.readReceipts(data, "woke", now))
        // Before the wake-up the snooze is not unread yet: only the last message counts.
        assertEquals(emptyList<Pair<String, Long>>(), ChatListModel.readReceipts(data, "woke", 8_999))
        val read = data.copy(sessions = data.sessions.map { if (it.id == "new") it.copy(lastReadAt = 8) else it })
        assertEquals(emptyList<Pair<String, Long>>(), ChatListModel.readReceipts(read, "new", now))
        assertEquals(emptyList<Pair<String, Long>>(), ChatListModel.readReceipts(data, null, now))
        // A chat without messages owes nothing: its creation time is no read receipt.
        val fresh = data.copy(sessions = data.sessions + session("fresh", 0).copy(lastMessageAt = null))
        assertEquals(emptyList<Pair<String, Long>>(), ChatListModel.readReceipts(fresh, "fresh", now))
        // Viewing a Tab of a woken conversation also clears the conversation's wake-up, not its messages.
        val wokenRoot = data.copy(sessions = data.sessions.map { if (it.id == "old") it.copy(snoozedUntil = 9_500, lastReadAt = 2) else it })
        assertEquals(listOf("tab" to 9L, "old" to 9_500L), ChatListModel.readReceipts(wokenRoot, "tab", now))
        assertEquals(listOf("tab" to 9L), ChatListModel.readReceipts(data, "tab", now))
    }

    @Test fun anOpenTabsUnreadOutputIsNotClearedByTheRootsLayoutRow() {
        // The layout says "old" is read; its open Tab has unread output.
        val tab = session("tab", 9).copy(parentSessionId = "old", lastReadAt = 1)
        val data = snapshot().let { it.copy(sessions = it.sessions.map { s -> if (s.id == "tab") tab else s }) }
        val old = ChatListModel.home(data, HomeGrouping.Status).flatMap { it.chats }.first { it.id == "old" }
        assertEquals(ChatAttention.Unread, old.attention)
        val closed = data.copy(sessions = data.sessions.map { if (it.id == "tab") tab.copy(isTabClosed = true) else it })
        assertNull(ChatListModel.home(closed, HomeGrouping.Status).flatMap { it.chats }.first { it.id == "old" }.attention)
    }

    @Test fun anEmptyDefaultPinSectionIsNotShownButNamedOnesAre() {
        fun pinned(rows: JSONArray, hidden: Int) = JSONObject(layoutJson.toString()).put("pinned", JSONArray()
            .put(JSONObject().put("sectionId", JSONObject.NULL).put("name", JSONObject.NULL).put("rows", rows).put("hiddenCount", hidden))
            .put(JSONObject().put("sectionId", "maint").put("name", "Maintenance").put("rows", JSONArray()).put("hiddenCount", 0)))
        fun ids(layout: JSONObject) = home(HomeGrouping.Status, StatusViewJson.statusView(JSONObject().put("mine", layout).put("team", layout))).map { it.id }
        assertEquals(listOf("pin-maint", "status-focus", "status-later"), ids(pinned(JSONArray(), 0)))
        // Rows the scope hid keep the default section, which names them.
        assertEquals("pinned", ids(pinned(JSONArray(), 1)).first())
        assertEquals("pinned", ids(pinned(JSONArray().put(row("pin", "pinned")), 0)).first())
    }

    @Test fun aNewChatShowsAtTheTopOfFocusBeforeTheLayoutListsIt() {
        val data = snapshot().let { it.copy(sessions = it.sessions + session("pending", 20).copy(lastMessageAt = null)) }
        val focus = ChatListModel.home(data, HomeGrouping.Status).first { it.id == "status-focus" }
        assertEquals("pending", focus.chats.first().id)
        assertFalse(focus.chats.first().canManage)
        // Pinned ones may be waiting for their section to be read, so they stay hidden; so do others' chats in My tasks.
        val pinned = snapshot().let { it.copy(sessions = it.sessions + session("pending", 20).copy(pinned = true)) }
        assertFalse(ChatListModel.home(pinned, HomeGrouping.Status).flatMap { it.chats }.any { it.id == "pending" })
        val others = snapshot().let { it.copy(sessions = it.sessions + session("pending", 20).copy(userId = "someone")) }
        assertFalse(ChatListModel.home(others, HomeGrouping.Status, owner = "me").flatMap { it.chats }.any { it.id == "pending" })
    }

    @Test fun statusActionsCarryTheWholeOpenedTreeAndUndoRestoresEachSession() {
        val sessions = listOf(
            session("root", 1), session("child", 2).copy(openedBySessionId = "root", snoozedUntil = 50_000),
            session("grandchild", 3).copy(openedBySessionId = "child", settledAt = 4), session("gone", 4).copy(openedBySessionId = "root", archived = true),
            session("tab", 5).copy(parentSessionId = "root"),
        )
        assertEquals(listOf("child", "grandchild"), ChatListModel.openedTree(sessions, "root").map { it.id })
        val undo = ChatListModel.restore(sessions, listOf("root", "child", "grandchild"), StatusTier.Focus, now = 10_000)
        assertEquals(listOf(StatusViewCommand.MoveToFocus("root", false), StatusViewCommand.Snooze("child", 50_000, false), StatusViewCommand.Settle("grandchild", false)), undo)
        // A snooze that expired since is restored as Focus, never as a past wake-up the runtime would refuse.
        assertEquals(listOf(StatusViewCommand.MoveToFocus("child", false)), ChatListModel.restore(sessions, listOf("child"), StatusTier.Later, now = 60_000))
        // A root that was Settled by inactivity alone is settled again.
        assertEquals(listOf(StatusViewCommand.Settle("root", false)), ChatListModel.restore(sessions, listOf("root"), StatusTier.Settled, now = 10_000))
    }

    @Test fun laterCountdownsMatchTheSharedFormatter() {
        val minute = 60_000L; val hour = 60 * minute; val day = 24 * hour
        // Expected values from the shared formatSnoozeCountdown (see runtime/test/status-view.test.mjs).
        val cases = listOf(
            0L to null, 30_000L to Countdown.Soon, minute to Countdown.Minutes(1), minute + 1 to Countdown.Minutes(2),
            59 * minute to Countdown.Minutes(59), (59.5 * minute).toLong() to Countdown.Hours(1), hour to Countdown.Hours(1),
            hour + 1 to Countdown.Hours(2), 3 * hour to Countdown.Hours(3), (23.5 * hour).toLong() to Countdown.Days(1),
            (2.4 * day).toLong() to Countdown.Days(2), (2.6 * day).toLong() to Countdown.Days(3),
        )
        for ((left, expected) in cases) assertEquals("left=$left", expected, Countdown.of(1_000_000_000L + left, 1_000_000_000L))
    }

    @Test fun theStatusClockFollowsTheLatestLiveLayout() {
        app.lody.android.runtime.StatusClock.reset()
        assertEquals(1_000L, app.lody.android.runtime.StatusClock.now(1_000))
        app.lody.android.runtime.StatusClock.observe(nowMs = 5_000, receivedAt = 4_000)
        assertEquals(2_000L, app.lody.android.runtime.StatusClock.now(1_000))
        // A layout without a clock instant (older runtime) leaves it alone.
        app.lody.android.runtime.StatusClock.observe(nowMs = 0, receivedAt = 9_000)
        assertEquals(2_000L, app.lody.android.runtime.StatusClock.now(1_000))
        app.lody.android.runtime.StatusClock.reset()
    }

    @Test fun onlyTheExactServerTimeReadLeavesTheStreamGateway() {
        val worker = "https://api.example.org"
        assertTrue(app.lody.android.cloud.ServerTimeAccess.allows("https://api.example.org/api/time", "GET", worker))
        assertTrue(app.lody.android.cloud.ServerTimeAccess.allows("https://api.example.org/api/time", "GET", "$worker/"))
        for (url in listOf("https://api.example.org/api/time?x=1", "https://api.example.org/api/times", "https://evil.example.org/api/time", "https://api.example.org/api/other"))
            assertFalse(url, app.lody.android.cloud.ServerTimeAccess.allows(url, "GET", worker))
        assertFalse(app.lody.android.cloud.ServerTimeAccess.allows("https://api.example.org/api/time", "POST", worker))
        assertFalse(app.lody.android.cloud.ServerTimeAccess.allows("http://api.example.org/api/time", "GET", "http://api.example.org"))
    }

    @Test fun runtimeFixtureRendersInLayoutOrderForBothScopes() {
        val fixtures = JSONObject(java.io.File(System.getProperty("runtime.fixtures")).readText())
        val event = app.lody.android.runtime.RuntimeProtocol.event(fixtures.getJSONObject("catalogStatusView")) as app.lody.android.runtime.RuntimeEvent.CatalogUpdated
        val catalog = event.snapshot
        fun ids(owner: String?) = ChatListModel.home(catalog, HomeGrouping.Status, owner).associate { section -> section.id to section.chats.map { it.id } }
        assertEquals(
            mapOf("pinned" to listOf("session-1"), "pin-section-a" to listOf("session-2"), "status-focus" to listOf("session-4", "session-5", "session-6", "session-8"),
                "status-later" to listOf("session-3"), "status-settled" to listOf("session-7")),
            ids(null),
        )
        assertEquals(listOf("session-4", "session-5", "session-6"), ids("user-1")["status-focus"])
        assertEquals(1, ChatListModel.home(catalog, HomeGrouping.Status, "user-1").first { it.id == "pin-section-a" }.hiddenCount)
        assertEquals(listOf(PinSection("section-a", "Release", "80")), catalog.pinSections)
        assertTrue(catalog.statusView!!.sectionsLoaded && catalog.statusView!!.team.canManageSections)
    }

    @Test fun onlyAKnownRoleWithoutWriteAccessMakesTheWorkspaceReadOnly() {
        fun readOnly(role: String?) = app.lody.android.auth.WorkspaceMember("u", "U", role = role).readOnly
        assertTrue(readOnly("viewer"))
        assertTrue(readOnly("guest, viewer"))
        assertFalse(readOnly("member"))
        assertFalse(readOnly("viewer,admin"))
        assertFalse(readOnly("Owner"))
        // Unknown or unread roles keep writes offered; the server still enforces them.
        assertFalse(readOnly(null))
        assertFalse(readOnly(" "))
    }

    private fun slots() = StatusMoves.slots(home(HomeGrouping.Status).map { it.copy(collapsed = false) })

    private fun moved(id: String, target: String): List<ListSlot> {
        val reordered = StatusMoves.swap(slots(), ListSlot.rowKey(id), target)
        return StatusMoves.belowHeader(reordered, id, target)
    }

    @Test fun dragsBecomeMovesNamingTheRowLandedBefore() {
        val original = slots()
        // Reorder inside Focus: "new" over "old" lands in front of it.
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Focus, "old"), StatusMoves.drop(moved("new", ListSlot.rowKey("old")), original, "new"))
        assertEquals(StatusViewCommand.Move("old", StatusViewTarget.Pinned("maint"), null),
            StatusMoves.drop(moved("old", "header/pin-maint"), original, "old"))
        assertEquals(StatusViewCommand.Move("snoozed", StatusViewTarget.Focus, "old"),
            StatusMoves.drop(moved("snoozed", "header/status-focus"), original, "snoozed"))
        // Later has no manual order; a drop there or back in place changes nothing.
        assertNull(StatusMoves.drop(moved("new", ListSlot.rowKey("snoozed")), original, "new"))
        assertNull(StatusMoves.drop(original, original, "new"))
        // Headers never move.
        assertEquals(original, StatusMoves.swap(original, "header/status-focus", ListSlot.rowKey("new")))
    }

    @Test fun theFirstHeaderAcceptsRowsAtTheTopOfItsSection() {
        val original = slots()
        assertTrue(StatusMoves.swap(original, ListSlot.rowKey("pin"), "header/pinned") === original)
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Pinned(null), "pin"),
            StatusMoves.drop(moved("new", "header/pinned"), original, "new"))
    }

    @Test fun anEmptyFocusOffersALandingSlotThatFollowsItsRows() {
        val empty = JSONObject(layoutJson.toString()).put("focus", JSONArray())
        // Only sessions the layout lists, so none shows as a not-yet-read-back new chat.
        val data = snapshot(StatusViewJson.statusView(JSONObject().put("mine", empty).put("team", empty))).let { it.copy(sessions = it.sessions.filter { s -> s.id in setOf("pin", "snoozed") }) }
        val sections = ChatListModel.home(data, HomeGrouping.Status, today = LocalDate.parse("2026-11-01"), zone = ZoneOffset.UTC).map { it.copy(collapsed = false) }
        val original = StatusMoves.slots(sections)
        assertTrue(original.any { it is ListSlot.Gap })
        // A Later row dragged onto the gap lands in Focus, and the gap leaves.
        val moved = StatusMoves.settle(StatusMoves.swap(original, ListSlot.rowKey("snoozed"), "gap/status-focus"))
        assertFalse(moved.any { it is ListSlot.Gap })
        assertEquals(StatusViewCommand.Move("snoozed", StatusViewTarget.Focus, null), StatusMoves.drop(moved, original, "snoozed"))
        // Dragged on into Later again, the gap comes back.
        assertTrue(StatusMoves.settle(StatusMoves.swap(moved, ListSlot.rowKey("snoozed"), "header/status-later")).any { it is ListSlot.Gap })
    }

    @Test fun aDropUnderACollapsedSectionHeaderLandsAtItsTop() {
        val withRows = JSONObject(layoutJson.toString()).put("pinned", JSONArray()
            .put(JSONObject().put("sectionId", JSONObject.NULL).put("name", JSONObject.NULL).put("rows", JSONArray().put(row("pin", "pinned"))).put("hiddenCount", 0)))
        val sections = home(HomeGrouping.Status, StatusViewJson.statusView(JSONObject().put("mine", withRows).put("team", withRows)))
            .map { it.copy(collapsed = it.id == "pinned") }
        val original = StatusMoves.slots(sections)
        val moved = StatusMoves.belowHeader(original, "new", "header/pinned")
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Pinned(null), "pin"), StatusMoves.drop(moved, original, "new"))
    }

    @Test fun aCancelledGestureNeverWritesAndAMovedPressIsNoMenu() {
        val move = StatusViewCommand.Move("a", StatusViewTarget.Focus, null)
        assertEquals(StatusMoves.Release.Revert, StatusMoves.release(ended = false, swapped = true, travelled = 200f, slop = 8f, command = move))
        assertEquals(StatusMoves.Release.Revert, StatusMoves.release(ended = false, swapped = false, travelled = 0f, slop = 8f, command = null))
        assertEquals(StatusMoves.Release.Menu, StatusMoves.release(ended = true, swapped = false, travelled = 3f, slop = 8f, command = null))
        assertEquals(StatusMoves.Release.Revert, StatusMoves.release(ended = true, swapped = false, travelled = 40f, slop = 8f, command = null))
        assertEquals(StatusMoves.Release.Commit, StatusMoves.release(ended = true, swapped = true, travelled = 40f, slop = 8f, command = move))
    }

    @Test fun aHeldDropIsReleasedWhenTheCatalogShowsItLanded() {
        val original = slots()
        val command = StatusViewCommand.Move("new", StatusViewTarget.Focus, "old")
        assertFalse(StatusMoves.landed(original, command))
        assertTrue(StatusMoves.landed(StatusMoves.swap(original, ListSlot.rowKey("new"), ListSlot.rowKey("old")), command))
    }

    @Test fun movesRespectTheRowsPermissions() {
        val original = slots()
        val readOnly = original.map { if (it is ListSlot.Row && it.chat.id == "new") it.copy(chat = it.chat.copy(canPrefer = false, canManage = false)) else it }
        assertNull(StatusMoves.drop(StatusMoves.swap(readOnly, ListSlot.rowKey("new"), ListSlot.rowKey("old")), readOnly, "new"))
        assertNull(StatusMoves.drop(StatusMoves.swap(readOnly, ListSlot.rowKey("new"), "header/status-focus"), readOnly, "new"))
    }

    @Test fun menuAndAccessibilityMovesMatchDragOutcomes() {
        val focus = home(HomeGrouping.Status).first { it.id == "status-focus" }
        val chat = { id: String -> focus.chats.first { it.id == id } }
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Focus, "old"), StatusMoves.toTop(focus, chat("new")))
        assertNull(StatusMoves.toTop(focus, chat("old")))
        assertEquals(StatusViewCommand.Move("woke", StatusViewTarget.Focus, "old"), StatusMoves.by(focus, chat("woke"), -1))
        assertEquals(StatusViewCommand.Move("woke", StatusViewTarget.Focus, null), StatusMoves.by(focus, chat("woke"), 1))
        assertEquals(StatusViewCommand.Move("old", StatusViewTarget.Focus, "new"), StatusMoves.by(focus, chat("old"), 1))
        assertNull(StatusMoves.by(focus, chat("new"), 1))
        val later = home(HomeGrouping.Status).first { it.id == "status-later" }
        assertNull(StatusMoves.by(later, later.chats.single(), -1))
        val sections = home(HomeGrouping.Status)
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Pinned(null), "pin"), StatusMoves.toSection(sections, chat("new"), null))
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Pinned("maint"), null), StatusMoves.toSection(sections, chat("new"), "maint"))
    }

    @Test fun sectionsMoveAmongUserSectionsOnly() {
        val order = listOf("a", "b", "c")
        assertEquals(StatusViewCommand.MoveSection("b", "a"), StatusMoves.section(order, "b", -1))
        assertEquals(StatusViewCommand.MoveSection("b", null), StatusMoves.section(order, "b", 1))
        assertEquals(StatusViewCommand.MoveSection("a", "c"), StatusMoves.section(order, "a", 1))
        assertNull(StatusMoves.section(order, "a", -1))
        assertNull(StatusMoves.section(order, "c", 1))
    }
}
