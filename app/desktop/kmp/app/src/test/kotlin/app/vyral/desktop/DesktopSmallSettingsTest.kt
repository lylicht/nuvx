package app.vyral.desktop

import app.vyral.night.data.model.HomeShelf
import app.vyral.night.data.model.ShelfItem
import androidx.compose.ui.graphics.Color
import app.vyral.desktop.brand.VyralAccentTheme
import app.vyral.desktop.brand.VyralLogoStyle
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The small Settings toggles ported from Android, each judged on what it actually changes. */
class DesktopSmallSettingsTest {

    @Test
    fun `desktop interface scale stays in its supported range`() {
        assertEquals(0.85f, normalizeDesktopInterfaceScale(0.1f))
        assertEquals(1.2f, normalizeDesktopInterfaceScale(2f))
        assertEquals(1f, normalizeDesktopInterfaceScale(Float.NaN))
    }

    @Test
    fun `desktop sidebar width stays in its supported range`() {
        assertEquals(200f, normalizeDesktopSidebarWidth(100f))
        assertEquals(320f, normalizeDesktopSidebarWidth(500f))
        assertEquals(220f, normalizeDesktopSidebarWidth(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `artwork tint leads while playing and saved accents return while paused`() {
        assertEquals(
            Color.Red,
            resolveDesktopAccent(VyralAccentTheme.CUSTOM, 210f, Color.Red, VyralLogoStyle.SOLID_WHITE),
        )
        assertEquals(
            Color.hsl(210f, .8f, .52f),
            resolveDesktopAccent(VyralAccentTheme.CUSTOM, 210f, null, VyralLogoStyle.SOLID_WHITE),
        )
        assertEquals(
            Color(VyralLogoStyle.SOLID_WHITE.accentArgb),
            resolveDesktopAccent(VyralAccentTheme.DYNAMIC, 210f, null, VyralLogoStyle.SOLID_WHITE),
        )
    }

    // ── Filter non-music audio ────────────────────────────────────────────

    private val scratch = Files.createTempDirectory("vyral-filter")

    @AfterTest
    fun cleanUp() {
        scratch.toFile().deleteRecursively()
    }

    private fun file(name: String, bytes: Int): java.nio.file.Path {
        val path = scratch.resolve(name)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(bytes))
        return path
    }

    @Test
    fun `an ordinary track survives the filter`() {
        assertTrue(DesktopLocalMusic.isMusic(file("Artist - Song.mp3", 4_000_000)))
    }

    @Test
    fun `a WAV is dropped however big it is`() {
        assertFalse(DesktopLocalMusic.isMusic(file("Artist - Song.wav", 40_000_000)))
    }

    @Test
    fun `a file too small to hold thirty seconds is dropped`() {
        assertFalse(DesktopLocalMusic.isMusic(file("blip.mp3", 4_000)))
    }

    @Test
    fun `ringtones, alarms, notifications and recordings are dropped`() {
        listOf(
            "Ringtones/Marimba.mp3",
            "Notifications/ping.mp3",
            "Alarms/wake.mp3",
            "Recordings/meeting.mp3",
            "my voice note 3.mp3",
        ).forEach { name ->
            assertFalse(DesktopLocalMusic.isMusic(file(name, 4_000_000)), name)
        }
    }

}
