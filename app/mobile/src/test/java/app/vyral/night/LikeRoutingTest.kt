package app.vyral.night

import app.vyral.night.data.LikeRouting
import app.vyral.night.data.model.LikeStatus
import app.vyral.night.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LikeRoutingTest {
    @Test
    fun localFavoriteUsesOnlyTheDeviceStore() {
        val song = song(localUri = "content://media/external/audio/42")
        var deviceToggles = 0
        var accountToggles = 0

        LikeRouting.toggle(
            song = song,
            onDeviceToggle = { deviceToggles++; true },
            onAccountToggle = { accountToggles++ },
        )

        assertEquals(1, deviceToggles)
        assertEquals(0, accountToggles)
    }

    @Test
    fun remoteFavoriteUsesTheConnectedAccount() {
        val song = song(localUri = null)
        val ratedIds = mutableListOf<String>()
        var deviceToggles = 0

        LikeRouting.toggle(
            song = song,
            onDeviceToggle = { deviceToggles++; true },
            onAccountToggle = ratedIds::add,
        )

        assertEquals(listOf(song.videoId), ratedIds)
        assertEquals(0, deviceToggles)
    }

    @Test
    fun localFavoriteStateComesOnlyFromTheDeviceStore() {
        val song = song(localUri = "content://media/external/audio/42")

        assertEquals(
            LikeStatus.LIKE,
            LikeRouting.status(song, setOf(song.localUri!!), LikeStatus.INDIFFERENT),
        )
        assertEquals(
            LikeStatus.INDIFFERENT,
            LikeRouting.status(song, emptySet(), LikeStatus.LIKE),
        )
    }

    @Test
    fun remoteFavoriteStateComesFromTheConnectedAccount() {
        val song = song(localUri = null)

        assertEquals(LikeStatus.LIKE, LikeRouting.status(song, emptySet(), LikeStatus.LIKE))
        assertEquals(LikeStatus.INDIFFERENT, LikeRouting.status(song, emptySet(), LikeStatus.INDIFFERENT))
    }

    private fun song(localUri: String?) = Song(
        videoId = "song-id",
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        localUri = localUri,
    )
}
