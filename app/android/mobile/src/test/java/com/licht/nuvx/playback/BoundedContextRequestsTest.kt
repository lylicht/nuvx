package com.licht.nuvx.playback

import com.licht.nuvx.data.model.PlaybackSourceType
import com.licht.nuvx.data.model.BrowseType
import com.licht.nuvx.data.model.DetailLoadId
import com.licht.nuvx.data.model.DetailPage
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.model.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedContextRequestsTest {

    @After
    fun tearDown() {
        BoundedContextRequests.clear()
    }

    @Test
    fun `take consumes only the matching newest token and keeps snapshot`() {
        val first = request("first")
        val staleToken = BoundedContextRequests.put(first)
        val newestToken = BoundedContextRequests.put(request("newest"))

        assertNull(BoundedContextRequests.take(staleToken))
        val taken = BoundedContextRequests.take(newestToken)

        assertEquals("newest", taken?.songs?.single()?.videoId)
        assertEquals(newestToken, taken?.handoffToken)
        assertEquals("newest", BoundedContextRequests.snapshot.value?.songs?.single()?.videoId)
        assertNull(BoundedContextRequests.take(newestToken))
    }

    @Test
    fun `publish grows the process snapshot and rejects a stale handoff`() {
        val token = BoundedContextRequests.put(request("one", continuation = "next"))
        val taken = requireNotNull(BoundedContextRequests.take(token))
        val second = song("two")

        assertTrue(BoundedContextRequests.publish(taken, listOf(song("one"), second), "last"))
        val snapshot = requireNotNull(BoundedContextRequests.snapshot.value)
        assertEquals(listOf("one", "two"), snapshot.songs.map(Song::videoId))
        assertEquals("last", snapshot.continuationToken)
        assertEquals(token, snapshot.request.handoffToken)

        assertFalse(BoundedContextRequests.publish("stale", listOf(second), null))
        assertEquals("last", BoundedContextRequests.snapshot.value?.continuationToken)
    }

    @Test
    fun `only a service owned snapshot updates the same continuing detail load`() {
        val loadId = DetailLoadId()
        val token = BoundedContextRequests.put(request("one", continuation = "next", detailLoadId = loadId))
        val page = DetailPage(
            browseId = "VLrelease",
            title = "Release",
            subtitle = "",
            thumbnailUrl = null,
            songs = UiState.Success(listOf(song("one"))),
            type = BrowseType.PLAYLIST,
            continuation = "next",
            detailLoadId = loadId,
        )
        val pending = requireNotNull(BoundedContextRequests.snapshot.value)
        assertFalse(pending.canUpdateDetail(page))

        val request = requireNotNull(BoundedContextRequests.take(token))
        assertTrue(BoundedContextRequests.publish(request, listOf(song("one"), song("two")), "later"))
        val active = requireNotNull(BoundedContextRequests.snapshot.value)
        assertTrue(active.canUpdateDetail(page))
        assertFalse(active.canUpdateDetail(page.copy(detailLoadId = DetailLoadId())))
        assertFalse(active.canUpdateDetail(page.copy(continuation = null)))
    }

    private fun request(id: String, continuation: String? = null, detailLoadId: DetailLoadId? = null) =
        BoundedContextRequests.Request(
            songs = listOf(song(id)),
            startIndex = 0,
            source = QueueSource("Release", PlaybackSourceType.BROWSE, "VLrelease"),
            continuationToken = continuation,
            shuffle = false,
            detailLoadId = detailLoadId,
        )

    private fun song(id: String) = Song(
        videoId = id,
        title = id,
        artist = "Artist",
        thumbnailUrl = null,
    )
}
