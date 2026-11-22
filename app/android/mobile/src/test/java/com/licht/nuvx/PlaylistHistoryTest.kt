package com.licht.nuvx

import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.spotify.LocalPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistHistoryTest {
    private val a = Song("aaaaaaaaaaa", "A", "x", null)
    private val b = Song("bbbbbbbbbbb", "B", "x", null)
    private val c = Song("ccccccccccc", "C", "x", null)

    @Test
    fun everyChangeKeepsTheStateBeforeItNewestFirst() {
        val p = LocalPlaylistStore.createNuvxPlaylist("Mix", listOf(a, b))
        try {
            assertTrue(LocalPlaylistStore.versions(p.id).isEmpty())
            LocalPlaylistStore.addSong(p.id, c)
            LocalPlaylistStore.renamePlaylist(p.browseId, "Road mix")
            val versions = LocalPlaylistStore.versions(p.browseId)
            assertEquals(listOf("Mix", "Mix"), versions.map { it.title })
            assertEquals(listOf(listOf(a, b, c), listOf(a, b)), versions.map { it.songs })
        } finally {
            LocalPlaylistStore.deletePlaylist(p.id)
        }
    }

    @Test
    fun restoringAVersionPutsItBackAndCanBeUndone() {
        val p = LocalPlaylistStore.createNuvxPlaylist("Mix", listOf(a, b, c))
        try {
            LocalPlaylistStore.removeSong(p.id, b.videoId)
            LocalPlaylistStore.replaceSongs(p.id, listOf(c, a))
            val restored = LocalPlaylistStore.restoreVersion(p.id, 1)
            assertEquals(listOf(a, b, c), restored?.songs)
            assertEquals(listOf(a, b, c), LocalPlaylistStore.getPlaylist(p.id)?.songs)
            // Undo: the state just replaced is now the newest version.
            LocalPlaylistStore.restoreVersion(p.id, 0)
            assertEquals(listOf(c, a), LocalPlaylistStore.getPlaylist(p.id)?.songs)
        } finally {
            LocalPlaylistStore.deletePlaylist(p.id)
        }
    }

    @Test
    fun historyIsCappedAndDroppedWithThePlaylist() {
        val p = LocalPlaylistStore.createNuvxPlaylist("Mix", emptyList())
        repeat(15) { LocalPlaylistStore.renamePlaylist(p.id, "Name $it") }
        assertEquals(10, LocalPlaylistStore.versions(p.id).size)
        assertEquals("Name 13", LocalPlaylistStore.versions(p.id).first().title)
        assertNull(LocalPlaylistStore.restoreVersion(p.id, 99))
        LocalPlaylistStore.deletePlaylist(p.id)
        val again = LocalPlaylistStore.createNuvxPlaylist("Other", emptyList())
        try {
            assertTrue(LocalPlaylistStore.versions(again.id).isEmpty())
        } finally {
            LocalPlaylistStore.deletePlaylist(again.id)
        }
    }
}
