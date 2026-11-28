package app.vyral.night.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertTrue
import org.junit.Test

class SfIconLayerRegistryTest {
    @Test
    fun `every registered SF symbol has independently animated vector layers`() {
        val owner = SfIcons::class.java.getField("INSTANCE").get(null)
        val icons = SfIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .map { it.invoke(owner) as ImageVector }
            .distinctBy(ImageVector::name)

        assertTrue("Expected the SF symbol registry", icons.size > 100)
        val insufficient = icons.mapNotNull { icon ->
            val layers = NyvoIconLayers.split(icon)
            if (layers.size < 2 || layers.any { countPaths(it.root) != 1 }) {
                "${icon.name}: ${layers.size} layers"
            } else null
        }
        assertTrue("Symbols without separate animated layers: ${insufficient.joinToString()}", insufficient.isEmpty())
    }

    private fun countPaths(group: VectorGroup): Int = group.sumOf { node ->
        when (node) {
            is VectorGroup -> countPaths(node)
            is VectorPath -> 1
        }
    }
}
