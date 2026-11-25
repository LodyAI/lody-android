package app.lody.android.chat

import app.lody.android.R
import app.lody.android.runtime.SessionMessage
import app.lody.android.runtime.SystemNotice
import org.junit.Assert.*
import org.junit.Test

class ChatFailuresTest {
    private fun system(id: String, vararg notices: SystemNotice) = SessionMessage(id = id, role = "system", text = "", streaming = false, notices = notices.toList())
    private fun user(id: String) = SessionMessage(id = id, role = "user", text = "Hi", streaming = false)
    private fun failure(id: String, reason: String?, code: String? = null) = SystemNotice.Failure(id, reason, code, "raw")

    @Test fun retryContinuesOnlyTheLatestRecoverableFailureBeforeAnyLaterTurn() {
        val recoverable = failure("f1", "agent_disconnected")
        assertEquals(recoverable, ChatFailures.retryable(listOf(user("u1"), system("s1", recoverable))))
        // A later user turn supersedes it; an unrecoverable or Git failure never offers Retry.
        assertNull(ChatFailures.retryable(listOf(system("s1", recoverable), user("u2"))))
        assertNull(ChatFailures.retryable(listOf(system("s1", failure("f2", "session_archived")))))
        assertNull(ChatFailures.retryable(listOf(system("s1", failure("f3", "acp_internal_error", "git_executable_not_found")))))
        // Only the newest failure counts, even when an older one was recoverable.
        assertNull(ChatFailures.retryable(listOf(system("s1", recoverable), system("s2", failure("f4", "acp_auth_required")))))
        assertEquals(recoverable, ChatFailures.retryable(listOf(system("s1", recoverable), system("s2", SystemNotice.ResumedHistory("r", false)))))
    }

    @Test fun titlesFollowTheReasonAndUnknownReasonsStayGeneric() {
        assertEquals(R.string.chat_error_acp_provider_overloaded, ChatFailures.title("acp_provider_overloaded", null))
        assertEquals(R.string.chat_error_git_executable_not_found, ChatFailures.title("acp_internal_error", "git_executable_not_found"))
        assertEquals(R.string.chat_error_unknown, ChatFailures.title("future_reason", null))
        assertEquals(R.string.chat_error_auth_summary, ChatFailures.summary("acp_auth_required", null))
        assertNull(ChatFailures.summary("agent_disconnected", null))
        assertNull(ChatFailures.message("{\"error\":1}"))
        assertEquals("socket closed", ChatFailures.message("socket closed\nstack"))
    }

    @Test fun signInFollowsTheWebAuthenticationGate() {
        assertTrue(ChatFailures.canAuthenticate("builtin", "claude", "c1", emptyMap()))
        assertFalse(ChatFailures.canAuthenticate("builtin", "pi", "c1", emptyMap()))
        assertFalse(ChatFailures.canAuthenticate("custom", "custom-x", "c1", mapOf("acpProtocolAuthentication" to 1)))
        assertTrue(ChatFailures.canAuthenticate("custom", "custom-x", "c1", mapOf("acpProtocolAuthentication" to 2)))
        assertFalse(ChatFailures.canAuthenticate("builtin", "claude", null, emptyMap()))
    }
}
