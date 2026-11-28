package app.vyral.desktop.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.vyral.desktop.sharedui.resources.Res
import app.vyral.desktop.sharedui.resources.vyral_logo_solid_white
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/** Fixed black startup mark for platforms without the phone's haptic intro. */
@Composable
fun VyralStartupPulse(onFinished: () -> Unit) {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(220), RepeatMode.Reverse),
    )
    val logo = painterResource(Res.drawable.vyral_logo_solid_white)
    LaunchedEffect(Unit) { delay(900); onFinished() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(112.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(logo, "vyral", Modifier.size(72.dp), colorFilter = ColorFilter.tint(Color.Black))
        }
    }
}
