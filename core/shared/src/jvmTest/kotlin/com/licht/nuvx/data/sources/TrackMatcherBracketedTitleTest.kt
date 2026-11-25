package com.licht.nuvx.data.sources

import com.licht.nuvx.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackMatcherBracketedTitleTest {
    private fun song(title: String, artist: String) = Song(
        videoId = "x",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        fromAutoplay = false,
    )

    @Test
    fun searchesAndMatchesTitlesMadeEntirelyOfBrackets() {
        val target = TrackMatcher.Target("(Nice Dream)", "Radiohead")
        assertEquals(listOf("nice dream radiohead", "nice dream"), TrackMatcher.queries(target))
        assertTrue(TrackMatcher.matches(song("Nice Dream", "Radiohead"), target.title, target.artist))
        assertTrue(TrackMatcher.matches(song("(Nice Dream)", "Radiohead"), "Nice Dream", "Radiohead"))
        assertTrue(TrackMatcher.matches(song("(sic)", "Slipknot"), "sic", "Slipknot"))
    }

    @Test
    fun bracketOnlyTitlesKeepVersionMarkersAndIgnoreUploadLabels() {
        assertTrue(TrackMatcher.matches(song("Nice Dream (Live)", "Radiohead"), "(Nice Dream) (Live)", "Radiohead"))
        assertFalse(TrackMatcher.matches(song("Nice Dream", "Radiohead"), "(Nice Dream) (Live)", "Radiohead"))
        assertTrue(TrackMatcher.queries(TrackMatcher.Target("(Official Video)", "Radiohead")).isEmpty())
    }
}
