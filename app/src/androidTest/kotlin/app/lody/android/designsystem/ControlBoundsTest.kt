package app.lody.android.designsystem

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.SideEffect
import android.graphics.Insets
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.lody.android.home.HomeBottomBar
import app.lody.android.home.HomeTab
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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

}
