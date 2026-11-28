package app.vyral.night.ui.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton as MaterialIconButton
import kotlinx.coroutines.delay
import java.util.WeakHashMap

/** Shared by app roots so the icon motion follows VYRAL's persisted setting. */
val LocalVyralReduceMotion = staticCompositionLocalOf { false }

private val LocalVyralIconInteractions = staticCompositionLocalOf<InteractionSource?> { null }
private val LocalVyralIconActivation = staticCompositionLocalOf<State<Int>?> { null }

/** Shares a parent control's press source and semantic activation with its SF glyph. */
@Composable
fun NyvoIconInteractionProvider(
    interactionSource: InteractionSource?,
    activationSequence: Int = 0,
    content: @Composable () -> Unit,
) {
    val activation = rememberUpdatedState(activationSequence)
    CompositionLocalProvider(
        LocalVyralIconInteractions provides interactionSource,
        LocalVyralIconActivation provides activation,
        content = content,
    )
}

@Composable
fun NyvoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    var activationSequence by remember { mutableIntStateOf(0) }
    MaterialIconButton(
        onClick = {
            activationSequence++
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        interactionSource = source,
    ) {
        NyvoIconInteractionProvider(source, activationSequence) {
            content()
        }
    }
}

/** SF-backed vector icon that bounces each catalog path with a short stagger on press. */
@Composable
fun NyvoIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    interactionSource: InteractionSource? = null,
) {
    val tap = rememberNyvoIconTap(interactionSource)
    val layers = remember(imageVector) { NyvoIconLayers.of(imageVector) }

    Box(
        modifier = modifier.then(tap.pointerModifier),
        contentAlignment = Alignment.Center,
    ) {
        layers.forEachIndexed { index, layer ->
            val lift = rememberNyvoIconLift(index, tap.pressSequence, tap.reduceMotion)
            MaterialIcon(
                imageVector = layer,
                contentDescription = if (index == 0) contentDescription else null,
                modifier = Modifier.graphicsLayer {
                    val p = lift.value
                    translationY = -p * NyvoIconMotion.LiftDp.dp.toPx()
                    scaleX = 1f + p * NyvoIconMotion.ScaleOvershoot
                    scaleY = 1f + p * NyvoIconMotion.ScaleOvershoot
                    rotationZ = p * NyvoIconMotion.RotationDegrees
                },
                tint = tint,
            )
        }
    }
}

@Composable
fun NyvoIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    interactionSource: InteractionSource? = null,
) {
    val tap = rememberNyvoIconTap(interactionSource)
    val lift = rememberNyvoIconLift(0, tap.pressSequence, tap.reduceMotion)
    MaterialIcon(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.then(tap.pointerModifier).graphicsLayer {
            translationY = -lift.value * NyvoIconMotion.LiftDp.dp.toPx()
            scaleX = 1f + lift.value * NyvoIconMotion.ScaleOvershoot
            scaleY = 1f + lift.value * NyvoIconMotion.ScaleOvershoot
            rotationZ = lift.value * NyvoIconMotion.RotationDegrees
        },
        tint = tint,
    )
}

private data class NyvoIconTap(val pressSequence: Int, val reduceMotion: Boolean, val pointerModifier: Modifier)

@Composable
private fun rememberNyvoIconTap(interactionSource: InteractionSource?): NyvoIconTap {
    val reduceMotion = LocalVyralReduceMotion.current
    val interactions = interactionSource ?: LocalVyralIconInteractions.current
    val activation = LocalVyralIconActivation.current?.value ?: 0
    var pressSequence by remember { mutableIntStateOf(0) }
    if (interactions != null) {
        LaunchedEffect(interactions, reduceMotion) {
            if (NyvoIconMotion.enabled(reduceMotion)) interactions.interactions.collect { event ->
                if (event is PressInteraction.Press) pressSequence++
            }
        }
    }
    val pointerModifier = if (interactions == null && NyvoIconMotion.enabled(reduceMotion)) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressSequence++
                waitForUpOrCancellation()
            }
        }
    } else Modifier
    LaunchedEffect(activation, reduceMotion) {
        if (activation > 0 && NyvoIconMotion.enabled(reduceMotion)) pressSequence++
    }
    return NyvoIconTap(pressSequence, reduceMotion, pointerModifier)
}

@Composable
private fun rememberNyvoIconLift(index: Int, pressSequence: Int, reduceMotion: Boolean): Animatable<Float, *> {
    val lift = remember(index) { Animatable(0f) }
    LaunchedEffect(pressSequence, reduceMotion) {
        if (!NyvoIconMotion.enabled(reduceMotion)) {
            lift.snapTo(0f)
        } else if (pressSequence > 0) {
            delay(index * NyvoIconMotion.LayerDelayMillis)
            lift.animateTo(1f, spring(
                dampingRatio = NyvoIconMotion.LayerDamping,
                stiffness = NyvoIconMotion.LayerStiffness,
            ))
            lift.animateTo(0f, spring(
                dampingRatio = NyvoIconMotion.ReleaseDamping,
                stiffness = NyvoIconMotion.ReleaseStiffness,
            ))
        }
    }
    return lift
}

internal object NyvoIconMotion {
    const val LayerDelayMillis = 18L
    const val LayerDamping = 0.50f
    const val LayerStiffness = 400f
    const val ReleaseDamping = 0.70f
    const val ReleaseStiffness = 600f
    const val LiftDp = 4f
    const val ScaleOvershoot = 0.035f
    const val RotationDegrees = 1.2f

    fun enabled(reduceMotion: Boolean): Boolean = !reduceMotion
}

/** Converts vector paths, retaining their group transforms, into individually drawn layers. */
internal object NyvoIconLayers {
    private data class LayerPath(val parents: List<VectorGroup>, val path: VectorPath)
    private val cache = WeakHashMap<ImageVector, List<ImageVector>>()

    fun of(vector: ImageVector): List<ImageVector> = synchronized(cache) {
        cache.getOrPut(vector) { split(vector) }
    }

    internal fun split(vector: ImageVector): List<ImageVector> {
        val paths = mutableListOf<LayerPath>()
        collect(vector.root, emptyList(), paths)
        if (paths.isEmpty()) return listOf(vector)
        if (paths.size == 1) {
            // SFStudio's monochrome export frequently stores a complete symbol in one
            // compound path. Its fill and its outline are still separate visual layers:
            // retaining them as separate vectors lets the outline trail the glyph during
            // the staggered spring without breaking compound contours or their holes.
            val only = paths.single()
            val fillLayer = only.path.takeIf { it.fill != null }?.let {
                vectorLayer(vector, only, 0, fill = true, stroke = false)
            }
            val strokeLayer = only.path.takeIf { it.stroke != null }?.let {
                vectorLayer(vector, only, 1, fill = false, stroke = true)
            }
            val compoundLayers = listOfNotNull(fillLayer, strokeLayer)
            if (compoundLayers.size > 1) return compoundLayers
            return listOf(vector)
        }
        return paths.mapIndexed { index, layer ->
            vectorLayer(vector, layer, index, fill = true, stroke = true)
        }
    }

    private fun vectorLayer(
        vector: ImageVector,
        layer: LayerPath,
        index: Int,
        fill: Boolean,
        stroke: Boolean,
    ): ImageVector = Builder(
        name = "${vector.name}.layer$index",
        defaultWidth = vector.defaultWidth,
        defaultHeight = vector.defaultHeight,
        viewportWidth = vector.viewportWidth,
        viewportHeight = vector.viewportHeight,
        autoMirror = vector.autoMirror,
    ).apply {
        layer.parents.forEach { addGroup(it) }
        addPath(layer.path, fill = fill, stroke = stroke)
        repeat(layer.parents.size) { clearGroup() }
    }.build()

    private fun collect(group: VectorGroup, parents: List<VectorGroup>, out: MutableList<LayerPath>) {
        for (node in group) {
            when (node) {
                is VectorGroup -> collect(node, parents + listOf(node), out)
                is VectorPath -> out += LayerPath(parents, node)
            }
        }
    }

    private fun Builder.addGroup(group: VectorGroup) {
        addGroup(
            name = group.name,
            rotate = group.rotation,
            pivotX = group.pivotX,
            pivotY = group.pivotY,
            scaleX = group.scaleX,
            scaleY = group.scaleY,
            translationX = group.translationX,
            translationY = group.translationY,
            clipPathData = group.clipPathData,
        )
    }

    private fun Builder.addPath(path: VectorPath, fill: Boolean, stroke: Boolean) {
        addPath(
            pathData = path.pathData,
            pathFillType = path.pathFillType,
            name = path.name,
            fill = path.fill.takeIf { fill },
            fillAlpha = if (fill) path.fillAlpha else 0f,
            stroke = path.stroke.takeIf { stroke },
            strokeAlpha = if (stroke) path.strokeAlpha else 0f,
            strokeLineWidth = path.strokeLineWidth,
            strokeLineCap = path.strokeLineCap,
            strokeLineJoin = path.strokeLineJoin,
            strokeLineMiter = path.strokeLineMiter,
        )
    }
}
