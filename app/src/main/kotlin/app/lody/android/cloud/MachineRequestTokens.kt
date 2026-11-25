package app.lody.android.cloud

import app.lody.android.BackendEnvironment
import app.lody.android.auth.Credential
import app.lody.android.chat.GitHubHttp
import app.lody.android.chat.GitHubHttpClient
import app.lody.android.runtime.RuntimeScope
import org.json.JSONObject
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Preview and history proofs are minted for the verified account in the current workspace.
 * A lost connection is never replayed.
 */
internal class MachineRequestTokens(private val http: GitHubHttp = GitHubHttpClient(retryOnConnectionFailure = false)) {
    class Failure(code: String) : Exception(code)

    /** Returns a scoped proof, an issued grant, or its typed transport or HTTP outcome. */
    suspend fun mint(credential: Credential, scope: RuntimeScope, operation: String, intent: JSONObject): Any {
        if (intent.optString("workspaceId") != scope.workspaceId) throw Failure("workspace_changed")
        val cloudHistory = operation == "historyToken" && intent.opt("cloudHistory") == true
        val path = when (operation) {
            "previewToken" -> "session-preview/request-token"
            "historyToken" -> {
                for (key in listOf("machineId", "sessionId")) {
                    val value = intent.opt(key) as? String ?: throw Failure("invalid_intent")
                    if (value.isEmpty() || value.length > 256) throw Failure("invalid_intent")
                }
                val edit = intent.has("requestId") || intent.has("intentDigest")
                if (edit && (!rpcId.matches(intent.optString("requestId")) || !digest.matches(intent.optString("intentDigest"))))
                    throw Failure("invalid_intent")
                if (intent.length() != (if (edit) 5 else if (cloudHistory) 4 else 3) || (edit && cloudHistory))
                    throw Failure("invalid_intent")
                when {
                    edit -> "session-history/request-edit-token"
                    cloudHistory -> "session-history/roost-read-grant"
                    else -> "session-history/request-token"
                }
            }
            else -> throw Failure("invalid_operation")
        }
        val payload = if (cloudHistory) JSONObject(intent.toString()).apply { remove("cloudHistory") } else intent
        val (status, body) = try {
            http.request("POST", "${BackendEnvironment.site}/api/$path", credential.token, payload)
        } catch (error: IOException) {
            if (!cloudHistory || error is SSLException) throw error
            return JSONObject().put("failure", "transport")
        }
        if (status !in 200..299) {
            if (operation == "previewToken") return status
            if (cloudHistory) return JSONObject().put("status", status)
            throw Failure("history_token_denied")
        }
        val response = JSONObject(body)
        if (cloudHistory) return response
        if (operation == "previewToken") {
            if (response.optString("requesterUserId") != scope.accountId) throw Failure("requester_mismatch")
        } else if (response.length() != 1) throw Failure("invalid_token")
        val token = response.opt("requestToken") as? String ?: throw Failure("invalid_token")
        if (token.isEmpty() || token.length > 8192) throw Failure("invalid_token")
        return token
    }

    private companion object {
        val rpcId = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-4[a-fA-F0-9]{3}-[89aAbB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}")
        val digest = Regex("[a-f0-9]{64}")
    }
}
