package app.lody.android.chat

import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lody.android.runtime.DeviceCommand
import app.lody.android.runtime.DeviceControlReply
import app.lody.android.runtime.DevicePlatform
import app.lody.android.runtime.RuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.URI

/** Background health check status for a cached managed browser preview link. */
enum class BrowserLinkStatus {
    IDLE,
    CHECKING,
    NEEDS_REGENERATE,
    REGENERATING,
    UNCONFIRMED,
}

/**
 * Bounded in-memory cache that keeps device previews (iOS Simulator / Android Emulator) and
 * managed browser previews alive across conversation switches until explicitly stopped, evicted,
 * or cleared on logout / workspace switch.
 *
 * Capability URLs and preview tokens stay strictly in memory and are never persisted to disk.
 */
class PreviewCache(
    private val parentScope: CoroutineScope,
    private val maxDevices: Int = MAX_DEVICES,
    private val maxBrowsers: Int = MAX_BROWSERS,
) {
    data class SessionKey(val scope: RuntimeScope, val sessionId: String)
    data class DeviceKey(val scope: RuntimeScope, val sessionId: String, val platform: DevicePlatform)

    class DeviceEntry(
        val session: DevicePreviewSession,
        val bridge: DeviceViewerBridge = DeviceViewerBridge(),
        internal val entryScope: CoroutineScope,
    ) {
        var viewerState by mutableStateOf(ViewerState("connecting"))
        internal var webView: WebView? = null
        internal var webViewContext: MutableContextWrapper? = null
        internal var webViewViewerUrl: String? = null
        internal var webViewOperationId: String? = null

        fun destroyWebView() {
            val view = webView
            webView = null
            webViewContext = null
            webViewViewerUrl = null
            webViewOperationId = null
            bridge.reset()
            viewerState = ViewerState("connecting")
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
        }

        internal fun dispose() {
            destroyWebView()
            session.close()
            entryScope.cancel()
        }
    }

    class BrowserEntry(
        url: String,
        expiresAtMs: Long,
        label: String = "",
    ) {
        var url by mutableStateOf(url)
        var expiresAtMs by mutableStateOf(expiresAtMs)
        var currentUrl by mutableStateOf(url)
        var title by mutableStateOf(label)
        var linkStatus by mutableStateOf(BrowserLinkStatus.IDLE)
        internal var webView: WebView? = null
        internal var webViewContext: MutableContextWrapper? = null
        internal var loadedUrl: String? = null

        fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs > nowMs

        fun updateLink(newUrl: String, newExpiresAtMs: Long) {
            url = newUrl
            expiresAtMs = newExpiresAtMs
        }

        fun destroyWebView() {
            val view = webView
            webView = null
            webViewContext = null
            loadedUrl = null
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
        }
    }

    private val devices = LinkedHashMap<DeviceKey, DeviceEntry>(maxDevices + 1, 0.75f, true)
    private val browsers = LinkedHashMap<SessionKey, BrowserEntry>(maxBrowsers + 1, 0.75f, true)

    fun device(
        scope: RuntimeScope,
        sessionId: String,
        platform: DevicePlatform,
        call: suspend (DeviceCommand) -> DeviceControlReply,
    ): DeviceEntry {
        clearOutside(scope)
        val key = DeviceKey(scope, sessionId, platform)
        devices[key]?.let { return it }
        val entryScope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) + parentScope.coroutineContext.minusKey(kotlinx.coroutines.Job))
        lateinit var entry: DeviceEntry
        val session = DevicePreviewSession(
            platform = platform,
            call = call,
            tasks = entryScope,
            onStopped = { entry.destroyWebView() },
            onOperationChanged = { entry.destroyWebView() },
        )
        entry = DeviceEntry(session = session, entryScope = entryScope)
        devices[key] = entry
        evictDevicesIfNeeded(preserving = key)
        return entry
    }

    fun cachedDevice(scope: RuntimeScope, sessionId: String, platform: DevicePlatform): DeviceEntry? =
        devices[DeviceKey(scope, sessionId, platform)]

    fun removeDevice(scope: RuntimeScope, sessionId: String, platform: DevicePlatform) {
        devices.remove(DeviceKey(scope, sessionId, platform))?.dispose()
    }

    fun browser(scope: RuntimeScope, sessionId: String): BrowserEntry? =
        browsers[SessionKey(scope, sessionId)]

    fun storeBrowser(
        scope: RuntimeScope,
        sessionId: String,
        url: String,
        expiresAtMs: Long,
        label: String = "",
    ): BrowserEntry {
        clearOutside(scope)
        val key = SessionKey(scope, sessionId)
        val existing = browsers[key]
        if (existing != null) {
            existing.updateLink(url, expiresAtMs)
            if (existing.title.isBlank() && label.isNotBlank()) existing.title = label
            return existing
        }
        val entry = BrowserEntry(url = url, expiresAtMs = expiresAtMs, label = label)
        browsers[key] = entry
        evictBrowsersIfNeeded(preserving = key)
        return entry
    }

    fun removeBrowser(scope: RuntimeScope, sessionId: String) {
        browsers.remove(SessionKey(scope, sessionId))?.destroyWebView()
    }

    fun clearOutside(scope: RuntimeScope) {
        val staleDevices = devices.keys.filter { it.scope != scope }
        for (key in staleDevices) devices.remove(key)?.dispose()
        val staleBrowsers = browsers.keys.filter { it.scope != scope }
        for (key in staleBrowsers) browsers.remove(key)?.destroyWebView()
    }

    fun clearAll() {
        val allDevices = devices.values.toList()
        devices.clear()
        for (entry in allDevices) entry.dispose()
        val allBrowsers = browsers.values.toList()
        browsers.clear()
        for (entry in allBrowsers) entry.destroyWebView()
    }

    private fun evictDevicesIfNeeded(preserving: DeviceKey) {
        while (devices.size > maxDevices) {
            val candidate = devices.entries.firstOrNull { it.key != preserving && !it.value.session.running }?.key
                ?: devices.keys.firstOrNull { it != preserving }
                ?: break
            devices.remove(candidate)?.dispose()
        }
    }

    private fun evictBrowsersIfNeeded(preserving: SessionKey) {
        while (browsers.size > maxBrowsers) {
            val candidate = browsers.keys.firstOrNull { it != preserving } ?: break
            browsers.remove(candidate)?.destroyWebView()
        }
    }

    companion object {
        const val MAX_DEVICES = 4
        const val MAX_BROWSERS = 4
        const val DEFAULT_BROWSER_TTL_MS = 60L * 60_000L
        private const val TOKEN_PARAM = "__lody_preview_token"

        fun resolveExpiresAt(expiresAtMs: Long?, nowMs: Long = System.currentTimeMillis()): Long =
            if (expiresAtMs != null && expiresAtMs > 0L) expiresAtMs else nowMs + DEFAULT_BROWSER_TTL_MS

        /**
         * Combines a freshly generated tunnel URL (new origin + fresh token) with the path,
         * non-token query parameters, and fragment from [previousPageUrl].
         */
        fun mergeRegeneratedUrl(newBaseUrl: String, previousPageUrl: String?): String {
            if (previousPageUrl.isNullOrBlank()) return newBaseUrl
            return try {
                val next = URI(newBaseUrl)
                val prev = URI(previousPageUrl)
                val prevPath = prev.rawPath?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: next.rawPath ?: "/"
                val freshTokenParams = next.rawQuery.orEmpty().split("&").filter { it.startsWith("$TOKEN_PARAM=") }
                val preservedParams = prev.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() && !it.startsWith("$TOKEN_PARAM=") }
                val mergedQuery = (preservedParams + freshTokenParams).joinToString("&").ifEmpty { null }
                val merged = URI(
                    next.scheme,
                    null,
                    next.host,
                    next.port,
                    null,
                    null,
                    null,
                )
                val sb = StringBuilder(merged.toASCIIString()).append(prevPath)
                if (!mergedQuery.isNullOrEmpty()) sb.append("?").append(mergedQuery)
                if (!prev.rawFragment.isNullOrEmpty()) sb.append("#").append(prev.rawFragment)
                val candidate = URI(sb.toString())
                if (candidate.scheme == next.scheme && candidate.host == next.host && candidate.port == next.port) {
                    candidate.toASCIIString()
                } else {
                    newBaseUrl
                }
            } catch (_: Exception) {
                newBaseUrl
            }
        }
    }
}
