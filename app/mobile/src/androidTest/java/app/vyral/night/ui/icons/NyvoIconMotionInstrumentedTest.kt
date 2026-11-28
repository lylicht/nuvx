package app.vyral.night.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vyral.night.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NyvoIconMotionInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun vectorLayersBounceIndependentlyAndReducedMotionKeepsThemStill() {
        compose.mainClock.autoAdvance = false
        val previousReduceMotion = AppSettings.reduceAnimation.value
        val previousHighPerformance = AppSettings.highPerformanceMode.value
        val symbol = layeredTestSymbol()

        try {
            AppSettings.setReduceAnimation(false)
            compose.setContent {
                val reduceMotion by AppSettings.reduceAnimation.collectAsState()
                CompositionLocalProvider(
                    LocalVyralReduceMotion provides reduceMotion,
                    LocalDensity provides Density(3f),
                ) {
                    NyvoIcon(
                        imageVector = symbol,
                        contentDescription = "Layered test symbol",
                        modifier = Modifier.size(64.dp).background(Color.Black).testTag("layered-icon"),
                        tint = Color.White,
                    )
                }
            }
            compose.waitForIdle()

            val icon = compose.onNodeWithTag("layered-icon", useUnmergedTree = true)
            val restingImage = icon.captureToImage()
            val firstLayerRest = restingImage.alphaCentroidY(0.33f, 0.48f)
            val secondLayerRest = restingImage.alphaCentroidY(0.52f, 0.68f)
            val rest = restingImage.fingerprint()
            icon.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(100)
            val bouncingImage = icon.captureToImage()
            val firstLayerDelta = bouncingImage.alphaCentroidY(0.33f, 0.48f) - firstLayerRest
            val secondLayerDelta = bouncingImage.alphaCentroidY(0.52f, 0.68f) - secondLayerRest
            assertTrue(
                "Both vector paths must lift on press (first=$firstLayerDelta, second=$secondLayerDelta)",
                firstLayerDelta < -1f && secondLayerDelta < -1f,
            )
            assertTrue(
                "The first path must lead the second by the configured stagger (first=$firstLayerDelta, second=$secondLayerDelta)",
                firstLayerDelta < secondLayerDelta - 1f,
            )
            val bounced = bouncingImage.fingerprint()
            assertNotEquals("Pressing an icon must animate its vector layers", rest, bounced)
            icon.performTouchInput { up() }

            compose.runOnIdle { AppSettings.setReduceAnimation(true) }
            compose.mainClock.advanceTimeBy(700)
            val reducedRest = icon.captureToImage().fingerprint()
            icon.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(700)
            val reducedPressed = icon.captureToImage().fingerprint()
            assertEquals("The real reduced-animation setting must suppress icon movement", reducedRest, reducedPressed)
            icon.performTouchInput { up() }
        } finally {
            AppSettings.setReduceAnimation(previousReduceMotion)
            AppSettings.setHighPerformanceMode(previousHighPerformance)
        }
    }

    @Test
    fun parentSemanticAndRapidActivationsAnimateTheChildLayers() {
        compose.mainClock.autoAdvance = false
        val previousReduceMotion = AppSettings.reduceAnimation.value
        val previousHighPerformance = AppSettings.highPerformanceMode.value
        try {
            AppSettings.setReduceAnimation(false)
            compose.setContent {
                val reduceMotion by AppSettings.reduceAnimation.collectAsState()
                CompositionLocalProvider(LocalVyralReduceMotion provides reduceMotion) {
                    NyvoIconButton(
                        onClick = {},
                        modifier = Modifier.testTag("activation-button"),
                    ) {
                        NyvoIcon(
                            imageVector = layeredTestSymbol(),
                            contentDescription = "Activate layered symbol",
                            modifier = Modifier.size(64.dp).background(Color.Black).testTag("activation-icon"),
                            tint = Color.White,
                        )
                    }
                }
            }
            compose.waitForIdle()
            val icon = compose.onNodeWithTag("activation-icon", useUnmergedTree = true)
            val resting = icon.captureToImage().fingerprint()

            val button = compose.onNodeWithTag("activation-button", useUnmergedTree = true)
            button.performClick()
            button.performClick()
            button.performClick()
            compose.mainClock.advanceTimeBy(120)

            assertNotEquals(
                "Parent semantic activations, including rapid repeats, must animate each child vector layer",
                resting,
                icon.captureToImage().fingerprint(),
            )
        } finally {
            AppSettings.setReduceAnimation(previousReduceMotion)
            AppSettings.setHighPerformanceMode(previousHighPerformance)
        }
    }

    private fun layeredTestSymbol(): ImageVector = ImageVector.Builder(
        name = "layered-test-symbol",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(3f, 4f)
            lineTo(9f, 4f)
            lineTo(9f, 10f)
            lineTo(3f, 10f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(15f, 14f)
            lineTo(21f, 14f)
            lineTo(21f, 20f)
            lineTo(15f, 20f)
            close()
        }
    }.build()

    private fun androidx.compose.ui.graphics.ImageBitmap.fingerprint(): Long {
        val pixels = toPixelMap()
        var hash = 1125899906842597L
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                hash = 31 * hash + pixels[x, y].hashCode()
            }
        }
        return hash
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.alphaCentroidY(
        startXFraction: Float,
        endXFraction: Float,
    ): Float {
        val pixels = toPixelMap()
        val startX = (width * startXFraction).toInt().coerceIn(0, width - 1)
        val endX = (width * endXFraction).toInt().coerceIn(startX + 1, width)
        var weightedY = 0f
        var totalWeight = 0f
        for (x in startX until endX) {
            for (y in 0 until height) {
                val pixel = pixels[x, y]
                val weight = pixel.alpha * maxOf(pixel.red, pixel.green, pixel.blue)
                if (weight > 0.08f) {
                    weightedY += y * weight
                    totalWeight += weight
                }
            }
        }
        check(totalWeight > 0f) { "Expected a visible vector layer in x range $startX..$endX" }
        return weightedY / totalWeight
    }
}
