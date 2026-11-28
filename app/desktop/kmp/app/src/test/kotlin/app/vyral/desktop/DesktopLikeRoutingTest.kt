package app.vyral.desktop

import app.vyral.night.data.model.LikeStatus
import app.vyral.night.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopLikeRoutingTest {
    private val persistence = DesktopPersistence()

    @AfterTest
    fun clearStoredRatings() {
        persistence.saveLikedIds(emptySet())
        persistence.saveDislikedIds(emptySet())
    }

    @Test
    fun `local file rating is saved on device without calling account endpoint`() {
        val song = Song(
            videoId = "local:C:\\Music\\Night Drive.flac",
            title = "Night Drive",
            artist = "VYRAL",
            thumbnailUrl = null,
            localUri = "file:///C:/Music/Night%20Drive.flac",
            localPath = "C:\\Music\\Night Drive.flac",
        )
        val remoteRequests = mutableListOf<Pair<String, LikeStatus>>()

        val updated = applyDesktopRating(
            song = song,
            status = LikeStatus.LIKE,
            likedIds = emptySet(),
            dislikedIds = emptySet(),
            persistence = persistence,
            sendAccountRating = { id, status -> remoteRequests += id to status },
        )

        assertEquals(setOf(song.videoId), updated.likedIds)
        assertTrue(updated.dislikedIds.isEmpty())
        assertEquals(setOf(song.videoId), persistence.likedIds())
        assertTrue(persistence.dislikedIds().isEmpty())
        assertTrue(remoteRequests.isEmpty())
    }

    @Test
    fun `valid youtube rating is persisted and routed to the account`() {
        val song = Song(
            videoId = "abcdefghijk",
            title = "Remote song",
            artist = "Artist",
            thumbnailUrl = null,
        )
        val remoteRequests = mutableListOf<Pair<String, LikeStatus>>()

        val updated = applyDesktopRating(
            song = song,
            status = LikeStatus.DISLIKE,
            likedIds = setOf(song.videoId),
            dislikedIds = emptySet(),
            persistence = persistence,
            sendAccountRating = { id, status -> remoteRequests += id to status },
        )

        assertTrue(updated.likedIds.isEmpty())
        assertEquals(setOf(song.videoId), updated.dislikedIds)
        assertTrue(persistence.likedIds().isEmpty())
        assertEquals(setOf(song.videoId), persistence.dislikedIds())
        assertEquals(listOf(song.videoId to LikeStatus.DISLIKE), remoteRequests)
    }
}
