package app.lody.android.designsystem

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.SideEffect
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.layout.positionOnScreen
import app.lody.android.R
import app.lody.android.auth.Workspace
import app.lody.android.home.ChatListUiState
import app.lody.android.home.HomeBottomBar
import app.lody.android.home.HomeSyncState
import app.lody.android.home.SectionTitle
import app.lody.android.home.SettingsHome
import app.lody.android.home.HomeTab
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lody.android.diagnostics.ComposerMotionProbeActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Guards against native minimum touch targets expanding the existing composer/control row. */
@RunWith(AndroidJUnit4::class)
class ControlBoundsTest {
    @Test fun expressiveControlsKeepTheirExistingOccupiedBounds() {
        val laidOut = CountDownLatch(3)
        val sizes = arrayOfNulls<IntSize>(3)
        val positions = FloatArray(3)
        val expected = IntArray(3)
        ActivityScenario.launch(ComposerMotionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                LodyTheme {
                    val density = LocalDensity.current
                    SideEffect {
                        expected[0] = with(density) { LodySize.controlSm.roundToPx() }
                        expected[1] = with(density) { LodySize.controlLg.roundToPx() }
                        expected[2] = expected[1]
                    }
                    fun bounds(index: Int) = Modifier.onGloballyPositioned {
                        sizes[index] = it.size
                        positions[index] = it.positionInRoot().x
                        laidOut.countDown()
                    }
                    Row {
                        LodyFilledIconButton({}, bounds(0).size(LodySize.controlSm)) {
                            Icon(LodyIcons.Send, null, Modifier.size(LodyIconSize.sm))
                        }
                        LodyIconButton({}, bounds(1)) {
                            Icon(LodyIcons.Plus, null, Modifier.size(LodyIconSize.md))
                        }
                        LodyFilledIconButton({}, bounds(2).size(LodySize.controlLg), enabled = false) {
                            Icon(LodyIcons.Compose, null, Modifier.size(LodyIconSize.lg))
                        }
                    }
                }
            } }
            assertTrue("All controls laid out", laidOut.await(5, TimeUnit.SECONDS))
            expected.forEachIndexed { i, size -> assertEquals("Control $i keeps its bounds", IntSize(size, size), sizes[i]) }
            assertEquals("Small send control does not displace its neighbor", expected[0].toFloat(), positions[1] - positions[0], 0.5f)
            assertEquals("No implicit spacing is added", expected[1].toFloat(), positions[2] - positions[1], 0.5f)
        }
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test fun homeBottomBarReservesTheLargerOfNavigationAndCutoutInsets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        var composeView: View? = null
        var baseHeight = 0
        var expectedHeight = -1
        var actualHeight = -1
        var measured = CountDownLatch(1)
        ActivityScenario.launch(ComposerMotionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                LodyTheme {
                    val density = LocalDensity.current
                    val view = LocalView.current
                    SideEffect {
                        composeView = view
                        baseHeight = with(density) {
                            (LodySize.controlLg + LodySpacing.sm).roundToPx() + LodySpacing.xs.roundToPx() * 2
                        }
                        ready.countDown()
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.onGloballyPositioned {
                            actualHeight = it.size.height
                            if (actualHeight == expectedHeight) measured.countDown()
                        }) {
                            HomeBottomBar(HomeTab.Chats, collapsed = false, onSelect = {}, onCreate = {})
                        }
                    }
                }
            } }
            assertTrue("Home bar composed", ready.await(5, TimeUnit.SECONDS))
            // Insets are synthetic pixels; no device model, keyboard or navigation setting is assumed.
            for ((navigation, cutout) in listOf(24 to 80, 96 to 40, 0 to 0)) {
                val laidOut = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    expectedHeight = baseHeight + maxOf(navigation, cutout)
                    measured = laidOut
                    composeView!!.dispatchApplyWindowInsets(WindowInsets.Builder()
                        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
                        .setVisible(WindowInsets.Type.navigationBars(), true)
                        .setDisplayCutout(DisplayCutout(Insets.of(0, 0, 0, cutout), null, null, null, null))
                        .build())
                }
                assertTrue("Home bar uses max(nav=$navigation, cutout=$cutout) without accumulating padding: expected $expectedHeight, got $actualHeight",
                    laidOut.await(5, TimeUnit.SECONDS))
            }
        }
    }

    /** A pin section's ⋯ menu must not make its label taller than a status tier's label. */
    @Test fun sectionMenuKeepsGroupLabelHeightAndOpens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val laidOut = CountDownLatch(2)
        val opened = CountDownLatch(1)
        val heights = IntArray(2)
        var menuCenter = floatArrayOf(0f, 0f)
        ActivityScenario.launch(ComposerMotionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                LodyTheme {
                    val density = LocalDensity.current
                    fun bounds(index: Int) = Modifier.onGloballyPositioned {
                        heights[index] = it.size.height
                        if (index == 1) {
                            val origin = it.positionOnScreen()
                            val inset = with(density) { (LodySpacing.xs + 24.dp).toPx() }
                            menuCenter = floatArrayOf(origin.x + it.size.width - inset, origin.y + it.size.height / 2f)
                        }
                        laidOut.countDown()
                    }
                    Column {
                        Box(bounds(0)) { SectionTitle("Focus", collapsed = true, count = 3) }
                        Box(bounds(1)) {
                            SectionTitle("Pinned", collapsed = true, count = 3, menu = { SideEffect { opened.countDown() } })
                        }
                    }
                }
            } }
            assertTrue("Both labels laid out", laidOut.await(5, TimeUnit.SECONDS))
            assertEquals("A label with a menu keeps the plain label height", heights[0], heights[1])
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, menuCenter[0], menuCenter[1], 0))
            }
            assertTrue("The label menu opens", opened.await(5, TimeUnit.SECONDS))
        }
    }

    /** Preferences and workspace General keep single-line row metrics and navigation without subtitles. */
    @Test fun settingsPreferencesAndGeneralUseSingleLineRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val preferencesLabel = context.getString(R.string.settings_preferences)
        val generalLabel = context.getString(R.string.settings_workspace_general)
        val agentsLabel = context.getString(R.string.settings_agents)
        val workspaceHeading = context.getString(R.string.home_settings_workspace)
        val workspaces = listOf(Workspace("w1", "Baseline Workspace"), Workspace("w2", "Second Workspace"))
        val state = ChatListUiState(
            workspaceName = "Baseline Workspace",
            initial = "B",
            sync = HomeSyncState.Live,
            accountName = "Settings Preview",
            timeline = emptyList(),
            snapshot = null,
            userId = "u1",
            projects = emptyList(),
            placeholder = "",
            workspaces = workspaces,
            workspaceId = "w1",
            accountEmail = "baseline@example.test",
        )
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
            val roots = ui.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ui.rootInActiveWindow) }
            roots.forEach(::visit)
            return result
        }
        fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
        fun waitFor(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                find(label)?.let { return it }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            fail("Missing $label; visible texts: ${nodes().mapNotNull { it.text?.toString() }}")
            error("unreachable")
        }
        fun clickableRow(label: String): AccessibilityNodeInfo {
            var node = waitFor(label)
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue("$label row must be clickable", node.isClickable)
            return node
        }
        fun descendantTexts(root: AccessibilityNodeInfo): List<String> {
            val texts = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            visit(root)
            return texts
        }
        val openedPreferences = CountDownLatch(1)
        val composed = CountDownLatch(1)
        ActivityScenario.launch(ComposerMotionProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                LodyTheme {
                    SideEffect { composed.countDown() }
                    SettingsHome(
                        state = state,
                        padding = PaddingValues(),
                        onSelectWorkspace = {},
                        onSignOut = {},
                        onAgents = {},
                        onPreferences = { openedPreferences.countDown() },
                        onRoles = {},
                        onShortcuts = {},
                        onGitHub = {},
                        onProjects = {},
                        onDeferred = {},
                    )
                }
            } }
            assertTrue("SettingsHome composed", composed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            val preferencesRow = clickableRow(preferencesLabel)
            val generalRow = clickableRow(generalLabel)
            val agentsRow = clickableRow(agentsLabel)
            assertEquals(listOf(preferencesLabel), descendantTexts(preferencesRow))
            assertEquals(listOf(generalLabel), descendantTexts(generalRow))
            val preferencesBounds = Rect().also(preferencesRow::getBoundsInScreen)
            val generalBounds = Rect().also(generalRow::getBoundsInScreen)
            val agentsBounds = Rect().also(agentsRow::getBoundsInScreen)
            assertEquals("Preferences matches single-line Agents row height", agentsBounds.height(), preferencesBounds.height())
            assertEquals("General matches single-line Agents row height", agentsBounds.height(), generalBounds.height())

            assertTrue(preferencesRow.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue("Preferences row still triggers navigation", openedPreferences.await(5, TimeUnit.SECONDS))

            assertTrue(generalRow.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor(workspaceHeading)
            waitFor("Second Workspace")
        }
    }

}
