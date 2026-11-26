package app.lody.android.cloud

import app.lody.android.auth.Credential
import app.lody.android.chat.GitHubHttp
import app.lody.android.runtime.RuntimeScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MachineRequestTokensTest {
    private val scope = RuntimeScope("account", "workspace")
    private fun cloudHistoryIntent() = JSONObject()
        .put("workspaceId", "workspace")
        .put("machineId", "machine")
        .put("sessionId", "session")
        .put("cloudHistory", true)

    private fun mint(status: Int): JSONObject = runBlocking {
        MachineRequestTokens(GitHubHttp { _, url, _, _ ->
            assertEquals(true, url.endsWith("/api/session-history/roost-read-grant"))
            status to "No matching routes found"
        }).mint(Credential("token"), scope, "historyToken", cloudHistoryIntent()) as JSONObject
    }

    @Test fun missingCloudReadGrantRouteFallsBackToOwnerRead() {
        assertEquals("owner-required", mint(404).getString("state"))
    }

    @Test fun refusedCloudReadGrantKeepsItsStatus() {
        val refused = mint(403)
        assertEquals(403, refused.getInt("status"))
        assertEquals(false, refused.has("state"))
    }
}
