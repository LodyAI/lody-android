package app.lody.android.chat

import app.lody.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffFailureTest {
    @Test fun eachRuntimeReasonGetsItsOwnMessage() {
        assertEquals(R.string.chat_diff_not_changed, diffUnavailableMessage("not_changed"))
        assertEquals(R.string.chat_diff_no_base, diffUnavailableMessage("base_unavailable"))
        assertEquals(R.string.chat_diff_read_failed, diffUnavailableMessage("transient_io"))
        assertEquals(R.string.chat_diff_turn_unavailable, diffUnavailableMessage("turn_unavailable"))
        assertEquals(R.string.chat_diff_binary, diffUnavailableMessage("unsupported_binary"))
        assertEquals(R.string.chat_diff_not_tracked, diffUnavailableMessage("not_tracked"))
    }

    @Test fun unknownOrMissingReasonReadsAsAConnectionProblem() {
        assertEquals(R.string.chat_diff_unavailable, diffUnavailableMessage(null))
        assertEquals(R.string.chat_diff_unavailable, diffUnavailableMessage("unavailable"))
        assertEquals(R.string.chat_diff_unavailable, diffUnavailableMessage("something_new"))
    }

    @Test fun binaryAndUntrackedFilesOfferNoRetry() {
        assertTrue(diffUnavailableRetryable("not_changed"))
        assertTrue(diffUnavailableRetryable("transient_io"))
        assertTrue(diffUnavailableRetryable(null))
        assertFalse(diffUnavailableRetryable("unsupported_binary"))
        assertFalse(diffUnavailableRetryable("not_tracked"))
    }

    @Test fun workspaceListCallsOutAMissingBaselineOrAnUncomparableWorktree() {
        assertEquals(R.string.chat_diff_no_base, workspaceUnavailableMessage("base_unavailable"))
        assertEquals(R.string.chat_diff_scope_not_git, workspaceUnavailableMessage("not_a_git_worktree"))
        assertEquals(R.string.chat_diff_scope_no_commits, workspaceUnavailableMessage("no_commits"))
        assertEquals(R.string.chat_diff_workspace_unavailable, workspaceUnavailableMessage("transient_io"))
        assertEquals(R.string.chat_diff_workspace_unavailable, workspaceUnavailableMessage(null))
    }

    @Test fun failureLogNamesTheSessionPathReasonAndError() {
        assertEquals(
            "session=s path=gradle/a.kt reason=transient_io error=runtime_failed",
            diffFailureLog("s", "gradle/a.kt", "transient_io", IllegalStateException("runtime_failed")),
        )
        assertEquals("session=s reason=not_changed", diffFailureLog("s", null, "not_changed"))
        assertEquals(
            "session=s path=a.kt error=machine_offline",
            diffFailureLog("s", "a.kt", null, IllegalStateException("machine_offline")),
        )
    }
}
