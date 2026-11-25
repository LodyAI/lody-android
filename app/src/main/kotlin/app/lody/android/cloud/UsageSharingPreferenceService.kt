package app.lody.android.cloud

import app.lody.android.BackendEnvironment
import app.lody.android.auth.Credential
import app.lody.android.chat.GitHubHttp
import app.lody.android.chat.GitHubHttpClient
import org.json.JSONArray
import org.json.JSONObject

internal class UsageSharingPreferenceService(private val http: GitHubHttp = GitHubHttpClient(retryOnConnectionFailure = false)) {
    suspend fun update(credential: Credential, enabled: Boolean?): Boolean {
        val (authStatus, authBody) = http.request("GET", "${BackendEnvironment.site}/api/auth/convex/token", credential.token, null)
        check(authStatus in 200..299)
        val token = JSONObject(authBody).getString("token")
        val args = JSONObject()
        if (enabled != null) args.put("enabled", enabled)
        val kind = if (enabled == null) "query" else "mutation"
        val path = if (enabled == null) "analytics:getUsageSharing" else "analytics:setUsageSharing"
        val request = JSONObject().put("path", path).put("format", "convex_encoded_json").put("args", JSONArray().put(args))
        val (status, body) = http.request("POST", "${BackendEnvironment.convex}/api/$kind", token, request)
        check(status in 200..299)
        val response = JSONObject(body)
        check(response.getString("status") == "success")
        return enabled ?: response.getBoolean("value")
    }
}
