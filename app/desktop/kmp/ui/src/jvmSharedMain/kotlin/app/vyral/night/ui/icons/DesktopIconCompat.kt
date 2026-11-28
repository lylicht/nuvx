package app.vyral.night.ui.icons

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import app.vyral.desktop.ui.icons.SpringSymbolIcon
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton as MaterialIconButton

// Compatibility names deliberately stay local to the desktop compilation graph.
val LocalVyralReduceMotion = staticCompositionLocalOf { false }
private val LocalActivation = staticCompositionLocalOf<Int?> { null }

/**
 * A click target for desktop controls whose shape is not Material's fixed icon-button layout.
 * Its activation is shared with descendant [NyvoIcon]s, so mouse, touch and keyboard activation
 * all drive the same layered symbol spring.
 */
@Composable
fun NyvoClickable(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    var activation by remember { mutableIntStateOf(0) }
    val action = onClick
    val clickTarget = if (action == null) modifier else {
        modifier.clickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            role = role,
            onClick = { activation++; action() },
        )
    }
    Box(
        modifier = clickTarget,
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(
            LocalActivation provides if (onClick == null || !enabled) 0 else activation,
            content = content,
        )
    }
}

@Composable
fun NyvoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    var activation by remember { mutableIntStateOf(0) }
    MaterialIconButton(
        onClick = { activation++; onClick() },
        modifier = modifier, enabled = enabled, interactionSource = interactionSource,
    ) {
        CompositionLocalProvider(LocalActivation provides activation) { content() }
    }
}

@Composable
fun NyvoIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val controlActivation = LocalActivation.current
    var pointerActivation by remember { mutableIntStateOf(0) }
    val pointer = if (controlActivation == null && !LocalVyralReduceMotion.current) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pointerActivation++
                waitForUpOrCancellation()
            }
        }
    } else Modifier
    SpringSymbolIcon(imageVector, contentDescription, controlActivation ?: pointerActivation, tint, modifier.then(pointer))
}

/** Raster/service artwork is not misrepresented as a layered SF Symbol. */
@Composable
fun NyvoIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = MaterialIcon(painter, contentDescription, modifier, tint)
