package app.lody.android.chat

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lody.android.designsystem.LodyTheme
import app.lody.android.diagnostics.CreateSessionProbeActivity
import app.lody.android.runtime.PromptDiffLine
import app.lody.android.runtime.ProposalCard
import app.lody.android.runtime.ProposalInspection
import app.lody.android.runtime.ScheduleEditPatch
import app.lody.android.runtime.ScheduleRule
import app.lody.android.runtime.SystemNotice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Web's schedule edit proposal and system rows through the production transcript, with synthetic
 * runtime answers: the live schedule decides which actions are enabled, Apply records the outcome
 * as a receipt, and notices render (an unknown one never reaches here). Screenshots go to the
 * app's external files directory for review.
 */
@RunWith(AndroidJUnit4::class)
class SystemCardsDeviceTest {
    private val daily = ScheduleRule("daily", hour = 9, minute = 0, timeZone = "UTC")

    private fun edit(itemId: String, outcome: String? = null) = ProposalCard(
        "system", itemId, "schedule_edit", outcome, title = "Daily review", scheduleId = "daily-review",
        patch = ScheduleEditPatch("Morning review", "Summarize open tasks.\nPost with owners.", ScheduleRule("weekdays", hour = 8, minute = 30)),
    )

    private fun inspection(itemId: String) = when (itemId) {
        "stale" -> ProposalInspection("stale", true, "Daily review", daily, "UTC")
        "teammate" -> ProposalInspection("not_owner", false, "Daily review", daily, "UTC")
        else -> ProposalInspection("ready", true, "Daily review", daily, "UTC", listOf(
            PromptDiffLine("same", "Summarize open tasks."), PromptDiffLine("del", "Post in chat."), PromptDiffLine("add", "Post with owners."),
        ))
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
        return found
    }

    private fun waitFor(message: String, predicate: (List<AccessibilityNodeInfo>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!predicate(nodes()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate(nodes()))
    }

    private fun text(value: String) = waitFor("Missing text: $value") { all -> all.any { it.text?.toString()?.contains(value) == true } }

    private fun button(label: String, index: Int = 0): AccessibilityNodeInfo {
        var node = nodes().filter { it.text?.toString() == label }[index]
        while (!node.isClickable && node.parent != null) node = node.parent
        return node
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = instrumentation.targetContext.getExternalFilesDir("system-cards") ?: return
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun editProposalsFollowTheLiveScheduleAndApplyIntoAReceipt() {
        val decisions = mutableListOf<Pair<String, Boolean>>()
        val opened = mutableListOf<String>()
        ActivityScenario.launch(CreateSessionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { LodyTheme {
                    var outcome by remember { mutableStateOf<String?>(null) }
                    ChatTranscript(
                        listOf(ChatEntry.Operations("system", emptyList(), listOf(edit("pending", outcome), edit("stale"), edit("teammate")))),
                        meId = "me",
                        decideProposal = { card, accept -> decisions += card.itemId to accept; if (card.itemId == "pending") outcome = if (accept) "applied" else "dismissed" },
                        inspectProposal = { card -> inspection(card.itemId) },
                        openSchedule = { opened += it },
                    )
                } }
            }
            text("Update this scheduled task?")
            text("+ Post with owners.")
            text("This scheduled task was edited after the proposal. Ask the Agent for a new one.")
            text("Only the schedule owner can save changes.")
            assertTrue("Apply is enabled for the ready edit", button("Apply", 0).isEnabled)
            assertFalse("A stale edit cannot apply", button("Apply", 1).isEnabled)
            assertTrue("The owner may ignore a stale edit", button("Dismiss", 1).isEnabled)
            assertFalse("A teammate cannot apply", button("Apply", 2).isEnabled)
            assertFalse("A teammate cannot ignore", button("Dismiss", 2).isEnabled)
            capture("edit-proposals")
            assertTrue(button("Open", 0).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Open names the edited schedule") { opened == listOf("daily-review") }
            assertTrue(button("Apply", 0).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text("Scheduled task updated")
            assertEquals(listOf("pending" to true), decisions)
            // The receipt keeps Open; the other two edits still offer theirs beside Apply.
            assertEquals(3, nodes().count { it.text?.toString() == "Open" })
            capture("edit-applied")
        }
    }

    @Test fun systemNoticesRenderAndOpenTheirDetail() {
        val opened = mutableListOf<String>()
        val actions = mutableListOf<String>()
        ActivityScenario.launch(CreateSessionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { LodyTheme {
                    var retrying by remember { mutableStateOf(false) }
                    ChatTranscript(listOf(ChatEntry.Operations("system", emptyList(), notices = listOf(
                        SystemNotice.ForkOrigin("fork", "source", "Fix the login redirect"),
                        SystemNotice.ResumedHistory("resume", true),
                        SystemNotice.Warning("warn", "sessionStopTimedOut", "SIGTERM sent; still running."),
                        SystemNotice.Failure("auth", "acp_auth_required", null, "Authentication required"),
                        SystemNotice.Failure("failed", "agent_disconnected", null, "socket closed"),
                        SystemNotice.WorktreeScript("script", "setup", "completed", listOf(SystemNotice.ScriptStep("pnpm install", "completed", "Done in 2.1s"))),
                    ))), navigateSession = { opened += it }, failureActions = FailureActions(
                        retryItemId = "failed", retryEnabled = true, retrying = retrying,
                        retry = { actions += "retry"; retrying = true }, signIn = { actions += "signIn" },
                    ))
                } }
            }
            text("This conversation was forked from Fix the login redirect")
            text("(History truncated due to length)")
            text("Stopping took too long")
            text("Agent disconnected")
            text("Agent authentication required")
            text("Sign in to the agent on your computer, then continue.")
            text("worktree setup")
            capture("system-notices")
            assertEquals("Only the latest recoverable failure offers Retry", 1, nodes().count { it.text?.toString() == "Retry" })
            assertTrue(button("Retry").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text("Retrying…")
            assertFalse("Retry is fenced while its turn is pending", button("Retrying…").isEnabled)
            assertTrue(button("Sign in").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Retry then Sign in reach the host") { actions == listOf("retry", "signIn") }
            assertTrue(button("This conversation was forked from Fix the login redirect").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Fork origin opens its source") { opened == listOf("source") }
            assertTrue(button("worktree setup").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text("Done in 2.1s")
            assertTrue(button("Stopping took too long").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text("SIGTERM sent; still running.")
            capture("system-notices-open")
        }
    }

    @Test fun openShowsTheProposedScheduleInItsEditor() {
        val item = app.lody.android.runtime.ScheduleItem(
            "daily-review", "Daily review", "me", true, "m1", "Studio", true, "codex", "Codex", "codex", true, false,
            daily, "new_session", state = app.lody.android.runtime.ScheduleState("running", "runsAt"), createdAt = 1, updatedAt = 1,
        )
        val service = object : app.lody.android.schedules.ScheduleService {
            override suspend fun list(scope: app.lody.android.runtime.RuntimeScope) = app.lody.android.runtime.ScheduleList(listOf(item), "UTC", mapOf("m1" to "UTC"), setOf("m1"))
            override suspend fun detail(scope: app.lody.android.runtime.RuntimeScope, scheduleId: String) =
                app.lody.android.runtime.ScheduleDetail(item, "Summarize open tasks for the team.")
            override suspend fun command(scope: app.lody.android.runtime.RuntimeScope, request: app.lody.android.runtime.ScheduleCommandRequest) = request.scheduleId
        }
        ActivityScenario.launch(CreateSessionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { LodyTheme {
                    var open by remember { mutableStateOf<String?>(null) }
                    androidx.compose.foundation.layout.Box {
                        ChatTranscript(listOf(ChatEntry.Operations("system", emptyList(), listOf(edit("applied", "applied")))), meId = "me",
                            decideProposal = { _, _ -> }, openSchedule = { open = it })
                        open?.let { id ->
                            app.lody.android.schedules.ScheduleEditorOverlay(service, app.lody.android.runtime.RuntimeScope("me", "w"), id, null, null,
                                onClose = { open = null }, onOpenSession = {})
                        }
                    }
                } }
            }
            text("Scheduled task updated")
            assertTrue(button("Open").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text("Summarize open tasks for the team.")
            capture("edit-open-editor")
        }
    }
}
