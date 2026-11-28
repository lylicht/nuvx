package app.vyral.night.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** Uses Haze's fixed-quality blur profile for frequently redrawn glass surfaces. */
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

/** Frost material shared by the app's menus, drawers, and transient panels. */
fun menuFrost(fill: Color, alpha: Float = MENU_FROST_ALPHA): HazeBlurStyle = HazeBlurStyle {
    backgroundColor(fill)
    colorEffects(listOf(HazeColorEffect.tint(fill.copy(alpha = alpha))))
    blurRadius(24.dp)
    noiseFactor(0f)
    fallbackColorEffect(HazeColorEffect.tint(fill))
}

fun Modifier.liveMenuFrost(
    state: HazeState,
    fill: Color,
    alpha: Float = MENU_FROST_ALPHA,
): Modifier = optimizedHazeEffect(
    state = state,
    style = menuFrost(fill, alpha),
    qualityFraction = LIVE_FROST_QUALITY,
    includeAllSources = true,
)

private const val DEFAULT_BLUR_QUALITY = 0.33f
private const val LIVE_FROST_QUALITY = 0.10f

const val MENU_FROST_ALPHA = 0.80f
val MenuFrostDark = Color(0xFF1C1C1E)
