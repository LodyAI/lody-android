package app.lody.android.cloud

import java.net.URI
import java.net.URLDecoder

/** Only a successful official grant response may construct this permission. */
internal class StreamAccess(val workspaceId: String, gateway: String) {
    val origin: String
    private val roostHistory = Regex(
        "${Regex.escape(workspaceId)}:rh:[a-zA-Z0-9_-]{1,256}:[a-zA-Z0-9_-]{1,256}:" +
            "[a-zA-Z0-9_-]{43}:(?:root|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})"
    )
    init {
        require(workspaceId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        val uri = URI(gateway)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null && uri.path in setOf("", "/"))
        val host = uri.host ?: throw IllegalArgumentException()
        require(host.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,}")) && !host.endsWith(".local") && !host.endsWith(".internal"))
        origin = "https://${host.lowercase()}"
    }
    /** CSP sources from the validated HTTPS grant, including its WebSocket counterpart. */
    val connectSources: String get() = "$origin wss://${URI(origin).rawAuthority}"
    fun allows(url: String, method: String): Boolean = try {
        val uri = URI(url)
        val parts = uri.rawPath.split('/')
        val stream = if (parts.size >= 4) URLDecoder.decode(parts[3], "UTF-8") else ""
        val read = method in setOf("GET", "HEAD", "OPTIONS")
        val gatewayMatches = uri.scheme == "https" && "https://${uri.host}" == origin &&
            uri.port == -1 && uri.userInfo == null && uri.fragment == null && parts.getOrNull(1) == "ds"
        val id = "[a-zA-Z0-9_-]{1,128}"
        val workspace = Regex.escape(workspaceId)
        val permitted = when {
            stream == "$workspaceId:meta" -> read || method == "POST"
            stream.matches(Regex("$workspace:mf:$id")) -> read || method == "POST"
            // Agent Roles: the workspace Flock is read, created when missing and appended by Role edits.
            stream == "$workspaceId:wf:workspace" -> read || method in setOf("PUT", "POST")
            // Prompt shortcuts: workspace-scoped index Flock and body docs.
            stream == "$workspaceId:shortcut-index" -> read || method in setOf("PUT", "POST")
            stream.matches(Regex("$workspace:shortcut:[^:/]{1,600}")) -> read || method in setOf("PUT", "POST")
            // Schedules: the Flock registry and one schedule's Loro doc are each created, read and appended.
            stream == "$workspaceId:sr" -> read || method in setOf("PUT", "POST")
            stream.matches(Regex("$workspace:schedule:$id")) -> read || method in setOf("PUT", "POST")
            stream.matches(Regex("$workspace:s:$id")) -> read || method in setOf("PUT", "POST")
            stream.matches(Regex("$workspace:rpc:req:$id")) -> method in setOf("POST", "OPTIONS")
            stream.matches(Regex("$workspace:rpc:res:$id:$id")) -> read || method == "PUT"
            else -> false
        }
        gatewayMatches && when (parts.getOrNull(2)) {
            "lody" -> permitted && parts.size in 4..5 && (parts.size == 4 || (parts[4] == "bootstrap" && read))
            "lody-rh" -> read && parts.size == 4 && roostHistory.matches(stream)
            else -> false
        }

    } catch (_: Exception) { false }
}

/**
 * The one request the network runtime makes outside its stream gateway: the server time
 * (`GET <worker>/api/time`, no query) that calibrates the Status view clock. Exact match only.
 */
internal object ServerTimeAccess {
    fun url(worker: String): String = worker.trimEnd('/') + "/api/time"
    fun allows(url: String, method: String, worker: String): Boolean = method == "GET" && url == url(worker) && worker.startsWith("https://")
}
