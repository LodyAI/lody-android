package app.lody.android.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.lody.android.R
import app.lody.android.designsystem.LodyTheme
import app.lody.android.diagnostics.CreateSessionProbeActivity
import app.lody.android.runtime.*
import org.junit.Assert.*
import org.junit.Test

class SimulatorKeyboardTest {
    @Test fun unavailableGuestKeyboardShowsAnErrorAndLeavesTheHostImeClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val toggle = context.getString(R.string.preview_ctl_toggle_keyboard)
        val unavailable = context.getString(R.string.preview_control_unavailable)
        fun find(text: String, node: AccessibilityNodeInfo? = ui.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) {
                find(text, node.getChild(index))?.let { return it }
            }
            return null
        }
        ActivityScenario.launch(CreateSessionProbeActivity::class.java).use { scenario ->
            ui.executeAndWaitForEvent({
                scenario.onActivity { activity -> activity.setContent {
                    val scope = rememberCoroutineScope()
                    val session = remember {
                        DevicePreviewSession(DevicePlatform.IOS, { command ->
                            when (command) {
                                DeviceCommand.Devices -> DeviceControlReply(true, devices = listOf(
                                    PreviewDevice("phone", "Fixture phone", "iOS", "iPhone", "Booted", true)))
                                is DeviceCommand.Status -> DeviceControlReply(true, preview =
                                    DevicePreviewState("op", "phone", "ready", "remote", "https://offline.invalid"))
                                else -> error("Unexpected lifecycle request")
                            }
                        }, scope)
                    }
                    LodyTheme { DevicePreviewPage(session) {} }
                } }
            }, { find(toggle) != null }, 10000)
            val bounds = Rect()
            requireNotNull(find(toggle)).getBoundsInScreen(bounds)
            ui.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (find(unavailable) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertNotNull("Disconnected viewer must explain the failed action", find(unavailable))
            scenario.onActivity { activity ->
                assertFalse(activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
            }
        }
    }
}
