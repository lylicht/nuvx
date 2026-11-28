package app.vyral.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import app.vyral.desktop.ui.icons.VyralSymbols
import app.vyral.night.ui.icons.LocalVyralReduceMotion
import app.vyral.night.ui.icons.NyvoClickable
import app.vyral.night.ui.icons.NyvoIcon
import app.vyral.night.ui.icons.NyvoIconButton
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SymbolRenderingTest {
    @Test fun `rendered transport moves on rapid taps and reduced motion restores its pixels`() = runComposeUiTest {
        var reduced by mutableStateOf(false)
        var activation by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalVyralReduceMotion provides reduced) {
                // Exclude a Material ripple from the captured pixels: this test
                // must measure symbol transforms, not parent indication motion.
                NyvoClickable(
                    onClick = { activation++ },
                    modifier = Modifier.size(48.dp).testTag("button"),
                    indication = null,
                ) {
                    NyvoIcon(VyralSymbols.Pause, "Pause", Modifier.size(32.dp).testTag("symbol"), Color.Black)
                }
            }
        }
        mainClock.autoAdvance = false
        fun pixels(): IntArray {
            val image = onNodeWithTag("symbol", useUnmergedTree = true).captureToImage()
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        val baseline = pixels()
        onNodeWithTag("button").performClick()
        mainClock.advanceTimeBy(64)
        assertFalse(baseline.contentEquals(pixels()), "the actual rendered parts must move")
        repeat(20) {
            onNodeWithTag("button").performClick()
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(2_000)
        assertContentEquals(baseline, pixels(), "rapid taps must settle before testing keyboard activation")
        onNodeWithTag("button").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("button").performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(64)
        assertFalse(baseline.contentEquals(pixels()), "keyboard activation must trigger the same symbol spring")
        runOnIdle { reduced = true }
        mainClock.advanceTimeBy(32)
        assertContentEquals(baseline, pixels(), "reduced animation must remove all part transforms")
        runOnIdle { reduced = false }
        mainClock.advanceTimeBy(32)
        assertContentEquals(baseline, pixels(), "re-enabling motion must not replay an old tap")
        onNodeWithTag("button").performClick()
        mainClock.advanceTimeBy(2_000)
        assertContentEquals(baseline, pixels(), "rapid taps must settle back to rest")
    }

    @Test fun `icon buttons retain desktop keyboard activation`() = runComposeUiTest {
        var clicks = 0
        setContent {
            NyvoIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("button")) {
                NyvoIcon(VyralSymbols.Pause, "Pause", Modifier.size(24.dp).testTag("symbol"))
            }
        }
        mainClock.autoAdvance = false
        val image = onNodeWithTag("symbol", useUnmergedTree = true).captureToImage()
        val baseline = IntArray(image.width * image.height).also { image.readPixels(it) }
        onNodeWithTag("button").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("button").performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(64)
        val animated = onNodeWithTag("symbol", useUnmergedTree = true).captureToImage()
        val animatedPixels = IntArray(animated.width * animated.height).also { animated.readPixels(it) }
        assertFalse(baseline.contentEquals(animatedPixels), "keyboard activation must animate the actual SF symbol paths")
        runOnIdle { assertEquals(1, clicks) }
    }
}
