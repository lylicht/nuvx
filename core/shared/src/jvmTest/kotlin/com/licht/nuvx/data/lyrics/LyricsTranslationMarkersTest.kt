package com.licht.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTranslationMarkersTest {
    @Test
    fun `bare counters restore Arabic translation lines`() {
        val body = "سأحبك دائما\n0001\nلا تبكي الليلة\n0002\n[جوقة]"
        assertEquals(
            listOf("سأحبك دائما", "لا تبكي الليلة", "[جوقة]"),
            LyricsTranslation.splitMarked(body),
        )
    }

    @Test
    fun `Arabic Indic counters are recognized`() {
        val body = "سأحبك دائما\n٠٠٠١\nلا تبكي الليلة"
        assertEquals(listOf("سأحبك دائما", "لا تبكي الليلة"), LyricsTranslation.splitMarked(body))
    }

    @Test
    fun `four digit lyric is not mistaken for the next marker`() {
        val body = "الحفلة\n0001\n1999\n0002\nلا أندم على شيء"
        assertEquals(
            listOf("الحفلة", "1999", "لا أندم على شيء"),
            LyricsTranslation.splitMarked(body),
        )
    }

    @Test
    fun `private use markers keep their original behavior`() {
        val body = "I love you\n\uE0000001\uE001\nDo not cry"
        assertEquals(listOf("I love you", "Do not cry"), LyricsTranslation.splitMarked(body))
    }
}
