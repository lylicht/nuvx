package app.vyral.desktop

import app.vyral.desktop.ui.icons.SymbolSpring
import app.vyral.desktop.ui.icons.VyralSymbols
import app.vyral.desktop.ui.icons.symbolParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SymbolSpringTest {
    @Test fun `one hundred rapid taps preserve position and settle`() {
        val spring = SymbolSpring(3)
        repeat(100) { tap ->
            spring.advance(tap * 12_000_000L)
            val before = spring.lifts()
            spring.tap()
            assertEquals(before, spring.lifts())
            assertTrue(before.all { it.isFinite() && kotlin.math.abs(it) < 0.26f })
        }
        assertNotEquals(spring.lifts()[0], spring.lifts()[1])
        spring.advance(4_000_000_000L)
        assertTrue(spring.settled())
    }

    @Test fun `reduced motion resets all parts without resuming an old spring`() {
        val spring = SymbolSpring(2)
        spring.advance(0L)
        spring.tap()
        spring.advance(60_000_000L)
        assertTrue(spring.lifts().any { it != 0f })
        spring.reset()
        spring.advance(80_000_000L)
        assertEquals(listOf(0f, 0f), spring.lifts())
        assertTrue(spring.settled())
    }

    @Test fun `pause and skip have distinct original parts not duplicated outlines`() {
        listOf(VyralSymbols.Pause, VyralSymbols.Next, VyralSymbols.Previous,
            app.vyral.night.ui.icons.VyralIcons.Pause).forEach { icon ->
            val parts = symbolParts(icon)
            assertTrue(parts.size >= 2, icon.name)
            assertEquals(parts.size, parts.map { it.name }.distinct().size)
            assertNotEquals(parts[0].root[0], parts[1].root[0])
        }
    }
}
