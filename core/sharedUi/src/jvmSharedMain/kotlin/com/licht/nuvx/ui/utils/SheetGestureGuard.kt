package com.licht.nuvx.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Velocity

class SheetTouchGuard {
    private var falseGrabPointerId: PointerId? = null

    internal fun markFalseGrab(id: PointerId) {
        falseGrabPointerId = id
    }

    internal fun clearFalseGrab(id: PointerId? = null) {
        if (id == null || falseGrabPointerId == id) falseGrabPointerId = null
    }

    internal fun isFalseGrab(id: PointerId): Boolean = falseGrabPointerId == id
}

val LocalSheetTouchGuard = staticCompositionLocalOf<SheetTouchGuard?> { null }

@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.guardSheetFromContentTouches(
    sheetState: SheetState,
    guard: SheetTouchGuard? = null,
): Modifier = pointerInput(sheetState, guard) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!down.isConsumed) {
                guard?.clearFalseGrab()
                return@awaitEachGesture
            }
            val offset = runCatching { sheetState.requireOffset() }.getOrNull()
            if (offset == null || offset > 1f) {
                guard?.clearFalseGrab()
                return@awaitEachGesture
            }
            guard?.markFalseGrab(down.id)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (change.positionChange() != Offset.Zero) {
                        change.consume()
                        break
                    }
                }
            } finally {
                guard?.clearFalseGrab(down.id)
            }
        }
    }

@Composable
fun Modifier.sheetGuardedClickable(
    interactionSource: MutableInteractionSource,
    indication: Indication? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val guard = LocalSheetTouchGuard.current
    val currentOnClick by rememberUpdatedState(onClick)
    val base = clickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClick = onClick,
    )
    if (guard == null || !enabled) return base
    return base.pointerInput(guard, interactionSource) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!down.isConsumed || !guard.isFalseGrab(down.id)) return@awaitEachGesture
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            var released = false
            try {
                var totalDrag = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        val inside = change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat()
                        if (inside && !change.isConsumed) {
                            change.consume()
                            interactionSource.tryEmit(PressInteraction.Release(press))
                            released = true
                            currentOnClick()
                        }
                        break
                    }
                    if (change.isConsumed) break
                    totalDrag += change.positionChange()
                    if (totalDrag.getDistance() > slop) break
                }
            } finally {
                if (!released) interactionSource.tryEmit(PressInteraction.Cancel(press))
            }
        }
    }
}

fun Modifier.containSheetGestures(): Modifier =
    nestedScroll(SwallowNestedScroll).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            drag(start.id) { it.consume() }
        }
    }

private object SwallowNestedScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}
