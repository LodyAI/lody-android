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
        session("woke", 4).copy(lastReadAt = 4),
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

    @Test fun openingAConversationOwesAReadReceiptUntilItIsRead() {
        val data = snapshot()
        assertEquals("new" to 8L, ChatListModel.readReceipt(data, "new"))
        // A woken conversation is read up to its wake-up time.
        assertEquals("woke" to 9_000L, ChatListModel.readReceipt(data, "woke"))
        val read = data.copy(sessions = data.sessions.map { if (it.id == "new") it.copy(lastReadAt = 8) else it })
        assertNull(ChatListModel.readReceipt(read, "new"))
        assertNull(ChatListModel.readReceipt(data, null))
        // A Tab resolves to its conversation's layout row but never takes its wake-up time.
        assertEquals("old", ChatListModel.layoutRow(data, "tab")?.id)
        assertEquals("tab" to 9L, ChatListModel.readReceipt(data, "tab"))
    }

    /** The runtime's own synthetic catalog (contract fixture `catalogStatusView`) renders in its order. */
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

    private fun moved(id: String, before: String): List<ListSlot> = StatusMoves.swap(slots(), ListSlot.rowKey(id), before)

    @Test fun dragsBecomeMovesNamingTheRowLandedBefore() {
        val original = slots()
        // Reorder inside Focus: "new" over "old" lands in front of it.
        assertEquals(StatusViewCommand.Move("new", StatusViewTarget.Focus, "old"), StatusMoves.drop(moved("new", ListSlot.rowKey("old")), original, "new"))
        // Dropped into an empty named pin section (onto the next header): pinned there, at the end.
        assertEquals(StatusViewCommand.Move("old", StatusViewTarget.Pinned("maint"), null),
            StatusMoves.drop(moved("old", "header/status-focus"), original, "old"))
        // A Later row dropped back into Focus (over the Later header) is restored at Focus's end.
        assertEquals(StatusViewCommand.Move("snoozed", StatusViewTarget.Focus, null),
            StatusMoves.drop(moved("snoozed", "header/status-later"), original, "snoozed"))
        // Later has no manual order; a drop there or back in place changes nothing.
        assertNull(StatusMoves.drop(moved("new", ListSlot.rowKey("snoozed")), original, "new"))
        assertNull(StatusMoves.drop(original, original, "new"))
        // Headers never move.
        assertEquals(original, StatusMoves.swap(original, "header/status-focus", ListSlot.rowKey("new")))
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
