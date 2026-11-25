package com.licht.nuvx.ui.screens

import com.licht.nuvx.data.model.BrowseType
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.settings.SongSort
import org.junit.Assert.assertEquals
import org.junit.Test

class AddedOrderTest {
    private val songs = listOf(
        Song(videoId = "old", title = "Old", artist = "Artist", thumbnailUrl = null, fromAutoplay = false),
        Song(videoId = "new", title = "New", artist = "Artist", thumbnailUrl = null, fromAutoplay = false),
    )

    @Test
    fun playlistDateAddedUsesAppendOrderInsteadOfFileDates() {
        val order = addedOrderOf("local:offline:mix", BrowseType.PLAYLIST)

        assertEquals(listOf("old", "new"), songs.sortedForDetail(SongSort.DATE_ADDED_ASC, order).map { it.videoId })
        assertEquals(listOf("new", "old"), songs.sortedForDetail(SongSort.DATE_ADDED_DESC, order).map { it.videoId })
    }

    @Test
    fun likedPagesAreAlreadyNewestFirst() {
        val order = addedOrderOf("VLLM", BrowseType.PLAYLIST)

        assertEquals(listOf("new", "old"), songs.sortedForDetail(SongSort.DATE_ADDED_ASC, order).map { it.videoId })
        assertEquals(listOf("old", "new"), songs.sortedForDetail(SongSort.DATE_ADDED_DESC, order).map { it.videoId })
    }

    @Test
    fun albumsKeepFileDateSorting() {
        assertEquals(AddedOrder.FILE_DATES, addedOrderOf("MPREbAlbum", BrowseType.ALBUM))
    }
}
