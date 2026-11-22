package com.licht.nuvx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.licht.nuvx.core.CoreRun
import com.licht.nuvx.core.coreApiVersion
import com.licht.nuvx.core.loudnessGainMb
import com.licht.nuvx.core.wordsFromRuns
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Loads the packaged libnuvx_ffi.so for the device's ABI and calls nuvx-core through the UniFFI bindings.
// The full shared cases run on the JVM (LoudnessGainTest); this proves the device build loads and agrees.
@RunWith(AndroidJUnit4::class)
class RustCoreDeviceTest {
    @Test
    fun loadsTheRustCoreAndAgreesWithTheSharedCases() {
        assertEquals(2u, coreApiVersion())
        assertEquals(-300, loudnessGainMb(loudnessDb = 3.0, targetLufs = -14))
        assertEquals(300, loudnessGainMb(loudnessDb = -12.0, targetLufs = -10))
        assertEquals(-1500, loudnessGainMb(loudnessDb = 20.0, targetLufs = -20))
        // Lyrics word model (core/contracts/lyrics-words.json): touching runs join, a CJK character does not.
        val words = wordsFromRuns(listOf(CoreRun(0, 100, "hel"), CoreRun(100, 200, "lo "), CoreRun(200, 300, "日")), false)
        assertEquals(listOf("hello", "日"), words.map { it.text })
        assertEquals(2, words[0].syllables.size)
    }
}
