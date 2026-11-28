package app.vyral.night.ui.tv.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import app.vyral.night.ui.icons.NyvoIcon as Icon
import app.vyral.night.ui.icons.NyvoIconInteractionProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vyral.night.ui.icons.VyralIcons
import app.vyral.night.ui.tv.components.tvClick
import app.vyral.night.ui.tv.components.tvInitialFocus
import app.vyral.night.ui.tv.components.tvLift
import app.vyral.night.ui.tv.components.tvPlatter
import app.vyral.night.ui.tv.theme.TvGlass

/**
 * The phone's action row, as icon-only circles: Shuffle, a larger Play, and —
 * on an artist — a star to subscribe. Play rests in [playColor] (the artist's
 * Apple key colour, else Apple Music red) so that focus, which turns every
 * control white, is never hidden on it.
 *
 * [onFocused] fires as focus arrives in the row, so the page can scroll home:
 * the row sits at the top of the page, and the page should be at its top too.
 */
@Composable
internal fun TvActionRow(
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    playColor: Color? = null,
    subscribed: Boolean? = null,
    onToggleSubscription: () -> Unit = {},
    onFocused: () -> Unit = {},
) {
    Row(
        modifier = modifier.onFocusChanged { if (it.hasFocus) onFocused() },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvCircleAction(
            icon = VyralIcons.Shuffle,
            contentDescription = "Shuffle",
            size = 43.dp,
            enabled = canPlay,
            onClick = onShuffle,
        )
        TvCircleAction(
            icon = VyralIcons.Play,
            contentDescription = "Play",
            size = 58.dp,
            iconScale = 0.5f,
            resting = playColor ?: TvGlass.AppleRed,
            enabled = canPlay,
            onClick = onPlay,
            modifier = Modifier.tvInitialFocus(canPlay),
        )
        if (subscribed != null) {
            TvCircleAction(
                icon = if (subscribed) VyralIcons.Star else VyralIcons.StarBorder,
                contentDescription = if (subscribed) "Unsubscribe" else "Subscribe",
                size = 43.dp,
                onClick = onToggleSubscription,
            )
        }
    }
}

/** One circle in [TvActionRow]: translucent (or [resting]) at rest, the white platter on focus. */
@Composable
private fun TvCircleAction(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resting: Color = Color.White.copy(alpha = 0.16f),
    iconScale: Float = 0.44f,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    var activationSequence by remember { mutableIntStateOf(0) }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .tvLift(interaction, CircleShape, focusedScale = 1.12f, elevation = 13.dp)
            .tvPlatter(interaction, resting = resting)
            .tvClick(interaction, enabled = enabled, onClick = { activationSequence++; onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        NyvoIconInteractionProvider(interaction, activationSequence) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (focused) TvGlass.OnPlatter else Color.White,
                modifier = Modifier.size(size * iconScale),
            )
        }
    }
}
