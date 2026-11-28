package app.vyral.night

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vyral.night.ui.icons.SfIcons
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SfIconLayerCoverageTest {
    @Test
    fun everyRegisteredSfIconHasIndependentlyAnimatedLayers() {
        val catalog = SfIcons::class.java
        val owner = catalog.getField("INSTANCE").get(null)
        val icons = catalog.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .map { it.invoke(owner) as ImageVector }
            .distinctBy(ImageVector::name)
        assertTrue("Expected the SF icon registry to expose its symbols", icons.size > 100)

        val layerizerClass = Class.forName("app.vyral.night.ui.icons.NyvoIconLayers")
        val layerizer = layerizerClass.getField("INSTANCE").get(null)
        val split = layerizerClass.declaredMethods.first {
            it.name.startsWith("split") && it.parameterTypes.contentEquals(arrayOf(ImageVector::class.java))
        }.apply { isAccessible = true }

        var independentlyLayered = 0
        for (icon in icons) {
            val originalPaths = countPaths(icon.root)
            assertTrue("${icon.name} must contain a vector path", originalPaths > 0)
            val layers = split.invoke(layerizer, icon) as List<*>
            assertTrue("${icon.name} must expose at least two motion layers", layers.size >= 2)
            assertTrue("${icon.name} layers must each draw independently", layers.all { countPaths((it as ImageVector).root) == 1 })
            independentlyLayered++
        }
        assertFalse("Every registered icon should have independent layers", independentlyLayered == 0)
    }

    private fun countPaths(group: VectorGroup): Int = group.sumOf { node ->
        when (node) {
            is VectorGroup -> countPaths(node)
            is VectorPath -> 1
        }
    }
}
