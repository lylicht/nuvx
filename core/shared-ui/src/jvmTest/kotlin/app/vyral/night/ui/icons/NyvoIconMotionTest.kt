package app.vyral.night.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

class NyvoIconMotionTest {
    @Test
    fun `multi-path vector is retained as independently drawn layers`() {
        val vector = ImageVector.Builder("test", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(2f, 2f); lineTo(8f, 2f); lineTo(8f, 8f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 16f); lineTo(22f, 16f); lineTo(22f, 22f); close()
            }
        }.build()
        val layers = NyvoIconLayers.split(vector)
        assertTrue("Each vector path should become a separate animated layer", layers.size > 1)
        assertTrue(layers.all { layer -> pathCount(layer.root) == 1 })
    }

    @Test
    fun `single compound SF style path splits into independently animated fill and outline`() {
        val vector = ImageVector.Builder("compound", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1f,
            ) {
                moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 21f); close()
                moveTo(8f, 8f); lineTo(8f, 16f); lineTo(16f, 16f); close()
            }
        }.build()

        val layers = NyvoIconLayers.split(vector)
        assertEquals(2, layers.size)
        assertTrue(layers.all { pathCount(it.root) == 1 })
        val paths = layers.map { it.root.first { node -> node is VectorPath } as VectorPath }
        assertTrue(paths[0].fill != null && paths[0].stroke == null)
        assertTrue(paths[1].fill == null && paths[1].stroke != null)
        assertEquals(vector.root.first { it is VectorPath }.let { (it as VectorPath).pathData }, paths[0].pathData)
        assertEquals(paths[0].pathData, paths[1].pathData)
    }

    @Test
    fun `reduced motion disables icon bounce`() {
        assertFalse(NyvoIconMotion.enabled(true))
        assertTrue(NyvoIconMotion.enabled(false))
    }

    private fun pathCount(group: VectorGroup): Int = group.sumOf { node ->
        when (node) {
            is VectorGroup -> pathCount(node)
            is VectorPath -> 1
        }
    }
}
