package app.vyral.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo

// The window's own frame, drawn by the application rather than by the system.

/** The strip where the system's title bar would have been. */
@Composable
internal fun DesktopTitleBar() {
    // Collected rather than read: [DesktopTitleBarSetting.active] is a plain call, so a composable
    // that only asks it never learns the answer changed.
    val enabled by DesktopTitleBarSetting.enabled.collectAsState()
    if (!DesktopPlatform.drawsOwnWindowFrame || !enabled) return
    DesktopTitleBarDragArea(
        Modifier
            .fillMaxWidth()
            .height(CAPTION_HEIGHT)
            .desktopWindowGlass(),
    ) {
        Box(Modifier.fillMaxSize()) {
            DesktopWindowButtons(Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Whether there is a title bar at all. */
internal object DesktopTitleBarSetting {

    internal const val KEY = "window_title_bar"

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY, false))

    /** What Settings shows and writes. */
    val enabled: StateFlow<Boolean> = _enabled

    fun set(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY, value)
        _enabled.value = value
    }
}

/** What the caption buttons act on. */
internal class DesktopWindowActions(
    val minimize: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
)

internal val LocalDesktopWindowActions = staticCompositionLocalOf<DesktopWindowActions?> { null }

/** The window, for the drag area. */
internal val LocalDesktopWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/** A region that moves the window, and maximizes it on a double click. */
@Composable
internal fun DesktopTitleBarDragArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val scope = LocalDesktopWindowScope.current
    val actions = LocalDesktopWindowActions.current
    if (!DesktopPlatform.drawsOwnWindowFrame || scope == null) {
        Box(modifier) { content() }
        return
    }
    // Behind the content rather than around it. Wrapped around it, the drag area saw every press
    // in the toolbar too — dragging the volume slider moved the window with it. As a sibling
    // underneath, anything with its own pointer input (buttons, sliders) is hit first and the
    // press never reaches here; only empty toolbar falls through to move the window.
    Box(modifier) {
        Box(Modifier.matchParentSize().captionPress(scope, actions))
        content()
    }
}

/** The caption buttons. In the title bar when there is one, otherwise on the toolbar. */
@Composable
internal fun DesktopWindowButtons(modifier: Modifier = Modifier) {
    if (!DesktopPlatform.drawsOwnWindowFrame) return
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    Row(
        modifier = modifier.height(CAPTION_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowCaptionButton("Minimize", onClick = actions.minimize) { glyph ->
            val reach = ICON_HALF.toPx()
            drawLine(
                glyph, Offset(center.x - reach, center.y), Offset(center.x + reach, center.y),
                CAPTION_STROKE.toPx(), StrokeCap.Square,
            )
        }
        WindowCaptionButton(
            if (maximized) "Restore" else "Maximize",
            onClick = actions.toggleMaximize,
        ) { glyph ->
            val half = ICON_HALF.toPx()
            val stroke = CAPTION_STROKE.toPx()
            if (maximized) {
                val shift = 1.5.dp.toPx()
                val left = center.x - half + shift
                val right = center.x + half + shift
                val top = center.y - half - shift
                val bottom = center.y + half - shift
                drawLine(glyph, Offset(left, top), Offset(right, top), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(left, top), Offset(left, bottom), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(right, top), Offset(right, bottom), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(left, bottom), Offset(right, bottom), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x - half, center.y - half), Offset(center.x + half, center.y - half), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x - half, center.y - half), Offset(center.x - half, center.y + half), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x + half, center.y - half), Offset(center.x + half, center.y + half), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x - half, center.y + half), Offset(center.x + half, center.y + half), stroke, StrokeCap.Square)
            } else {
                drawLine(glyph, Offset(center.x - half, center.y - half), Offset(center.x + half, center.y - half), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x - half, center.y - half), Offset(center.x - half, center.y + half), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x + half, center.y - half), Offset(center.x + half, center.y + half), stroke, StrokeCap.Square)
                drawLine(glyph, Offset(center.x - half, center.y + half), Offset(center.x + half, center.y + half), stroke, StrokeCap.Square)
            }
        }
        WindowCaptionButton("Close", onClick = actions.close, close = true) { glyph ->
            val reach = ICON_HALF.toPx()
            drawLine(glyph, Offset(center.x - reach, center.y - reach), Offset(center.x + reach, center.y + reach), CAPTION_STROKE.toPx(), StrokeCap.Square)
            drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x - reach, center.y + reach), CAPTION_STROKE.toPx(), StrokeCap.Square)
        }
    }
}

@Composable
private fun WindowCaptionButton(
    label: String,
    onClick: () -> Unit,
    close: Boolean = false,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        close && hovered -> Color(0xFFC42B1C)
        hovered -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(width = CAPTION_BUTTON_WIDTH, height = CAPTION_HEIGHT)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .drawBehind {
                drawRect(background)
                glyph(if (close && hovered) Color.White else Color.White.copy(alpha = 0.82f))
            },
        contentAlignment = Alignment.Center,
    ) {}
}

private val CAPTION_HEIGHT = 32.dp
private val CAPTION_BUTTON_WIDTH = 46.dp
private val ICON_HALF = 4.dp
private val CAPTION_STROKE = 1.dp

/**
 * A press on empty caption: moves the window, and a second one in quick succession maximizes it,
 * the way the system caption always has.
 *
 * On Windows the move is handed to the system ([DesktopWindowsFrame.startDrag]), which runs its own
 * move loop — DWM slides the window, Aero Snap works, and no edge is left exposed to paint white.
 * The double click is counted here rather than left to Windows: the area is client area as far as
 * Windows knows, so it never sends the caption's own double click. Linux uses native decorations.
 */
private fun Modifier.captionPress(scope: WindowScope, actions: DesktopWindowActions?): Modifier =
    pointerInput(scope, actions) {
        var lastPressAt = 0L
        var lastPressPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown()
            val double = down.uptimeMillis - lastPressAt <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastPressPosition).getDistance() <= viewConfiguration.touchSlop
            if (double) {
                lastPressAt = 0L
                down.consume()
                actions?.toggleMaximize()
                return@awaitEachGesture
            }
            lastPressAt = down.uptimeMillis
            lastPressPosition = down.position
            if (DesktopWindowsFrame.startDrag()) return@awaitEachGesture
            val window = scope.window
            val windowAtStart = window.location
            val pointerAtStart = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            drag(down.id) {
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@drag
                window.setLocation(
                    windowAtStart.x + pointer.x - pointerAtStart.x,
                    windowAtStart.y + pointer.y - pointerAtStart.y,
                )
            }
        }
    }
