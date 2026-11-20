package app.lody.android.chat

import app.lody.android.runtime.DeviceCommand
import app.lody.android.runtime.DeviceControlReply
import app.lody.android.runtime.DevicePlatform
import app.lody.android.runtime.DevicePreviewState
import app.lody.android.runtime.PreviewDevice
import app.lody.android.runtime.PreviewJson
import app.lody.android.runtime.RuntimeScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewCacheTest {
    private val scopeA = RuntimeScope(accountId = "u1", workspaceId = "w1")
    private val scopeB = RuntimeScope(accountId = "u1", workspaceId = "w2")
    private val pixel = PreviewDevice("Pixel_8", "Pixel 8", "API 35", "phone", "Booted", true)

    private fun readyPreview(op: String = "op-1") =
        DevicePreviewState(op, "Pixel_8", "ready", "remote", "https://sim-one.trycloudflare.com/v")

    @Test
    fun deviceSessionIsReusedAcrossConversationSwitchesUntilExplicitStop() = runTest {
        val cache = PreviewCache(this)
        val calls = mutableListOf<DeviceCommand>()
        var stopped = false
        val first = cache.device(scopeA, "s1", DevicePlatform.IOS) { cmd ->
            calls += cmd
            when (cmd) {
                is DeviceCommand.Start -> { stopped = false; DeviceControlReply(true, preview = readyPreview()) }
                is DeviceCommand.Status -> DeviceControlReply(true, preview = if (stopped) null else readyPreview())
                is DeviceCommand.Stop -> { stopped = true; DeviceControlReply(true) }
                DeviceCommand.Devices -> DeviceControlReply(true, devices = listOf(pixel))
            }
        }
        first.session.start(pixel)
        advanceUntilIdle()
        assertEquals("ready", first.session.phase)

        // Switching to another conversation and returning keeps the same live entry.
        val other = cache.device(scopeA, "s2", DevicePlatform.IOS) { DeviceControlReply(true) }
        assertNotSame(first, other)
        val returned = cache.device(scopeA, "s1", DevicePlatform.IOS) { error("must reuse cached entry") }
        assertSame(first, returned)
        assertEquals("ready", returned.session.phase)

        // Explicit stop tears down the session cleanly.
        returned.session.stop()
        advanceUntilIdle()
        assertEquals("closed", returned.session.phase)
        assertTrue(calls.any { it is DeviceCommand.Stop })
        cache.clearAll()
    }

    @Test
    fun deviceCacheEvictsLeastRecentlyUsedEntriesBeyondLimit() = runTest {
        val cache = PreviewCache(backgroundScope, maxDevices = 2)
        val e1 = cache.device(scopeA, "s1", DevicePlatform.ANDROID) { DeviceControlReply(true) }
        val e2 = cache.device(scopeA, "s2", DevicePlatform.ANDROID) { DeviceControlReply(true) }
        // Touch s1 so s2 becomes LRU.
        assertSame(e1, cache.device(scopeA, "s1", DevicePlatform.ANDROID) { DeviceControlReply(true) })
        val e3 = cache.device(scopeA, "s3", DevicePlatform.ANDROID) { DeviceControlReply(true) }

        assertSame(e1, cache.cachedDevice(scopeA, "s1", DevicePlatform.ANDROID))
        assertNull(cache.cachedDevice(scopeA, "s2", DevicePlatform.ANDROID))
        assertSame(e3, cache.cachedDevice(scopeA, "s3", DevicePlatform.ANDROID))
        assertNotSame(e2, cache.cachedDevice(scopeA, "s2", DevicePlatform.ANDROID))
    }

    @Test
    fun browserCacheStoresValidityEvictsLruAndClearsOnScopeChangeOrLogout() = runTest {
        val cache = PreviewCache(backgroundScope, maxBrowsers = 2)
        val now = 1_700_000_000_000L
        val b1 = cache.storeBrowser(scopeA, "s1", "https://one.trycloudflare.com/?__lody_preview_token=t1", now + 60_000, "App 1")
        val b2 = cache.storeBrowser(scopeA, "s2", "https://two.trycloudflare.com/?__lody_preview_token=t2", now - 1_000, "App 2")
        assertTrue(b1.isValid(now))
        assertFalse(b2.isValid(now))

        // Touch s1 so s2 is evicted when s3 is added.
        assertSame(b1, cache.browser(scopeA, "s1"))
        val b3 = cache.storeBrowser(scopeA, "s3", "https://three.trycloudflare.com/?__lody_preview_token=t3", now + 60_000, "App 3")
        assertSame(b1, cache.browser(scopeA, "s1"))
        assertNull(cache.browser(scopeA, "s2"))
        assertSame(b3, cache.browser(scopeA, "s3"))

        // Switching workspace clears previous workspace entries.
        cache.storeBrowser(scopeB, "s4", "https://four.trycloudflare.com/?__lody_preview_token=t4", now + 60_000, "App 4")
        assertNull(cache.browser(scopeA, "s1"))
        assertNull(cache.browser(scopeA, "s3"))
        assertTrue(cache.browser(scopeB, "s4") != null)

        // Logout clears everything.
        cache.clearAll()
        assertNull(cache.browser(scopeB, "s4"))
    }

    @Test
    fun mergeRegeneratedUrlPreservesBrowsedRouteAndSwapsFreshToken() {
        val merged = PreviewCache.mergeRegeneratedUrl(
            newBaseUrl = "https://new-tunnel.trycloudflare.com/?__lody_preview_token=fresh123",
            previousPageUrl = "https://old-tunnel.trycloudflare.com/dashboard/items?tab=open&__lody_preview_token=stale&sort=asc#row-4",
        )
        assertEquals(
            "https://new-tunnel.trycloudflare.com/dashboard/items?tab=open&sort=asc&__lody_preview_token=fresh123#row-4",
            merged,
        )
    }

    @Test
    fun mergeRegeneratedUrlRejectsProtocolRelativePaths() {
        val base = "https://new-tunnel.trycloudflare.com/?__lody_preview_token=fresh123"
        val merged = PreviewCache.mergeRegeneratedUrl(
            newBaseUrl = base,
            previousPageUrl = "https://old-tunnel.trycloudflare.com//evil.example/steal?x=1",
        )
        assertEquals("https://new-tunnel.trycloudflare.com/?x=1&__lody_preview_token=fresh123", merged)
    }

    @Test
    fun previewJsonParsesBrowserExpiresAtAndActiveFlags() {
        val activeReply = PreviewJson.browser(
            JSONObject()
                .put("url", "https://ok.trycloudflare.com/?__lody_preview_token=abc")
                .put("expiresAt", 1_700_003_600_000L)
                .put("active", true),
        )
        assertEquals("https://ok.trycloudflare.com/?__lody_preview_token=abc", activeReply.url)
        assertEquals(1_700_003_600_000L, activeReply.expiresAt)
        assertEquals(true, activeReply.active)

        val inactiveReply = PreviewJson.browser(JSONObject().put("active", false))
        assertNull(inactiveReply.url)
        assertEquals(false, inactiveReply.active)

        val unconfirmedReply = PreviewJson.browser(JSONObject().put("error", "timeout").put("message", "timed out"))
        assertEquals("timeout", unconfirmedReply.error)
        assertNull(unconfirmedReply.active)
    }
}
