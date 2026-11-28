package app.vyral.desktop.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vyral.night.ui.icons.LocalVyralReduceMotion

/** A control supplies its activation counter, covering its whole hit area and keyboard clicks. */
@Composable
fun SpringSymbolIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    activation: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalVyralReduceMotion.current
    val parts = remember(imageVector) { symbolParts(imageVector) }
    val spring = remember(imageVector) { SymbolSpring(parts.size) }
    var lifts by remember(imageVector) { mutableStateOf(List(parts.size) { 0f }) }
    var consumedActivation by remember { mutableStateOf(activation) }
    LaunchedEffect(activation, reduced, imageVector) {
        val newActivation = activation != consumedActivation
        consumedActivation = activation
        if (reduced) {
            spring.reset()
            lifts = List(parts.size) { 0f }
        } else if (newActivation) {
            withFrameNanos { spring.advance(it); spring.tap() }
            do {
                withFrameNanos { spring.advance(it) }
                lifts = spring.lifts()
            } while (!spring.settled())
            lifts = List(parts.size) { 0f }
        }
    }
    Box(modifier.defaultMinSize(24.dp, 24.dp).semantics {
        if (contentDescription != null) this.contentDescription = contentDescription
    }) {
        parts.forEachIndexed { index, part ->
            Image(
                painter = rememberVectorPainter(part),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.matchParentSize().graphicsLayer {
                    translationY = if (reduced) 0f else -lifts[index] * size.maxDimension
                },
            )
        }
    }
}

/** Keep compound holes together; only reviewed disconnected solid transport parts are split. */
internal fun symbolParts(vector: ImageVector): List<ImageVector> {
    val paths = mutableListOf<Pair<List<VectorGroup>, VectorPath>>()
    fun collect(group: VectorGroup, ancestors: List<VectorGroup>) {
        for (node in group) when (node) {
            is VectorGroup -> collect(node, ancestors + listOf(node))
            is VectorPath -> paths.add(ancestors to node)
        }
    }
    collect(vector.root, emptyList())
    val split = vector.name.substringAfter('.').substringBefore('.') in
        setOf("SFPauseFill", "SFForwardEndFill", "SFBackwardEndFill")
    return paths.flatMapIndexed { pathIndex, (ancestors, path) ->
        val contours = mutableListOf<MutableList<PathNode>>()
        path.pathData.forEach { command ->
            if (contours.isEmpty() || (split && command is PathNode.MoveTo)) contours.add(mutableListOf())
            contours.last().add(command)
        }
        contours.mapIndexed { partIndex, commands ->
            ImageVector.Builder(
                name = "${vector.name}-path-$pathIndex-part-$partIndex",
                defaultWidth = vector.defaultWidth,
                defaultHeight = vector.defaultHeight,
                viewportWidth = vector.viewportWidth,
                viewportHeight = vector.viewportHeight,
            ).apply {
                ancestors.forEach { group ->
                    addGroup(
                        name = group.name, rotate = group.rotation, pivotX = group.pivotX, pivotY = group.pivotY,
                        scaleX = group.scaleX, scaleY = group.scaleY, translationX = group.translationX,
                        translationY = group.translationY, clipPathData = group.clipPathData,
                    )
                }
            }.addPath(
                pathData = commands, name = path.name, fill = path.fill, fillAlpha = path.fillAlpha,
                stroke = path.stroke, strokeAlpha = path.strokeAlpha, strokeLineWidth = path.strokeLineWidth,
                strokeLineCap = path.strokeLineCap, strokeLineJoin = path.strokeLineJoin,
                strokeLineMiter = path.strokeLineMiter, pathFillType = path.pathFillType,
            ).apply { repeat(ancestors.size) { clearGroup() } }.build()
        }
    }.ifEmpty { listOf(vector) }
}
