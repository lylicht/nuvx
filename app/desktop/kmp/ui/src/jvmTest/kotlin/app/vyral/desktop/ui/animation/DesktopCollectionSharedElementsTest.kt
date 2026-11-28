package app.vyral.desktop.ui.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCollectionSharedElementsTest {
    @Test
    fun localPlaylistCardAndDetailResolveToTheSameIdentity() {
        assertEquals(
            "playlist-id",
            desktopCollectionSharedIdentity("desktop-playlist:playlist-id"),
        )
        assertEquals("playlist-id", desktopCollectionSharedIdentity("playlist-id"))
    }

    @Test
    fun localServiceCardsDoNotShareWithCollectionPages() {
        assertNull(desktopCollectionSharedIdentity("local:downloads"))
    }
}
