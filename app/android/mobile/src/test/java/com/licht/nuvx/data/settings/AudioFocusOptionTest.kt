package com.licht.nuvx.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusOptionTest {
    @Test
    fun autoPauseRequestsFocusAndKeepPlayingDoesNot() {
        assertTrue(AudioFocusOption.AUTO_PAUSE.requestsFocus)
        assertFalse(AudioFocusOption.KEEP_PLAYING.requestsFocus)
    }
}
