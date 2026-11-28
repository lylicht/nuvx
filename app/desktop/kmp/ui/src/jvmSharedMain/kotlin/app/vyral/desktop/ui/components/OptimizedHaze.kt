package app.vyral.desktop.ui.components

import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** Uses a fixed Haze quality profile for frequently redrawn desktop glass surfaces. */
fun Modifier.optimizedHazeEffect(
    state: HazeState,
    style: HazeBlurStyle = HazeBlurStyle {},
    qualityFraction: Float = DEFAULT_BLUR_QUALITY,
    includeAllSources: Boolean = false,
): Modifier = hazeBlur(
    input = if (includeAllSources) {
        HazeInput.Sources(state = state, selection = HazeSourceSelection.All)
    } else {
        HazeInput.Sources(state = state)
    },
    style = style,
    performanceMode = HazePerformanceMode.Fixed(qualityFraction),
)

private const val DEFAULT_BLUR_QUALITY = 0.33f
