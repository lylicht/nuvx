package com.licht.nuvx.ui

import com.licht.nuvx.data.model.BrowseType
import com.licht.nuvx.data.model.DetailPage
import com.licht.nuvx.data.model.DetailLoadId
import com.licht.nuvx.data.model.PlaybackSourceType
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.model.UiState
import com.licht.nuvx.playback.QueueSource
import com.licht.nuvx.playback.contextEntryKey

internal class MainDetailPlaybackController(
    private val playFrom: (List<Song>, Int, QueueSource, Boolean, String?, DetailLoadId?) -> Unit,
    private val takePlaybackContext: (DetailPage) -> DetailPage?,
    private val controlsLocked: () -> Boolean,
    private val casting: () -> Boolean,
    private val refusedByHost: () -> Unit,
) {
    fun play(page: DetailPage, songs: List<Song>, index: Int) {
        if (controlsLocked()) {
            refusedByHost()
            return
        }
        val source = QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId)
        val selected = songs.getOrNull(index)
        val pageSongKeys = (page.songs as? UiState.Success)?.data.orEmpty().mapTo(HashSet()) { it.contextEntryKey() }
        val selectedKey = selected?.contextEntryKey()
        val songKeys = songs.map { it.contextEntryKey() }
        val pageKeysInSongOrder = (page.songs as? UiState.Success)?.data.orEmpty()
            .map { it.contextEntryKey() }
            .filter { it in songKeys }
        val inPageOrder = songKeys == pageKeysInSongOrder
        val useBounded =
            (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) &&
                selectedKey?.let(pageSongKeys::contains) == true &&
                !casting()
        val latestPage = if (useBounded && inPageOrder) {
            takePlaybackContext(page)?.takeIf { latest ->
                (latest.songs as? UiState.Success)?.data?.any { it.contextEntryKey() == selectedKey } == true
            }
        } else {
            null
        }
        val latestSongs = latestPage
            ?.let { (it.songs as? UiState.Success)?.data.orEmpty() }
            ?.map { albumSong(page, it) }
            ?.takeIf { it.isNotEmpty() }
        val queue = latestSongs ?: songs.map { albumSong(page, it) }
        if (queue.isEmpty()) return
        val queueIndex = selectedKey?.let { key ->
            queue.indexOfFirst { it.contextEntryKey() == key }
        }?.takeIf { it >= 0 } ?: index
        val playbackPage = latestPage ?: page
        val detailLoadId = playbackPage.detailLoadId.takeIf {
            playbackPage.continuation != null &&
                (latestPage != null || songs.all { it.contextEntryKey() in pageSongKeys })
        }
        playFrom(
            queue,
            queueIndex.coerceIn(queue.indices),
            source,
            useBounded,
            playbackPage.continuation,
            detailLoadId,
        )
    }

    fun albumSong(page: DetailPage, song: Song): Song =
        if (page.type == BrowseType.ALBUM) song.copy(albumName = song.albumName ?: page.title) else song
}
