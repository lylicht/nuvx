package app.vyral.night.ui.player

import app.vyral.night.ui.icons.VyralIcons
import kotlin.test.Test
import androidx.compose.ui.graphics.vector.PathNode
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerPlayPauseVectorTest {
    @Test
    fun sfPlayPauseVectorsRecursivelyExposeLayerPaths() {
        listOf(VyralIcons.PlayArrow, VyralIcons.Pause).forEach { symbol ->
            val paths = vectorMorphPathData(symbol)

            assertNotNull(paths)
            assertTrue(paths.isNotEmpty())
            assertTrue(paths.all { layer -> layer.nodes.any { it is PathNode.MoveTo } })
        }
    }

    @Test
    fun sfSkipVectorsExposeSeparateArrowContoursWithoutFlatRootAssumptions() {
        listOf(VyralIcons.FastForward, VyralIcons.FastRewind).forEach { symbol ->
            val layer = vectorMorphPathData(symbol)?.singleOrNull()

            assertNotNull(layer)
            assertTrue(layer.nodes.count { it is PathNode.MoveTo } >= 2)
            assertTrue(layer.scale.x.isFinite() && layer.scale.y.isFinite())
        }
    }
}
