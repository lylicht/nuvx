package com.licht.nuvx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.licht.nuvx.core.coreApiVersion
import com.licht.nuvx.core.loudnessGainMb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Loads the packaged libnuvx_ffi.so for the device's ABI and calls nuvx-core through the UniFFI bindings.
// The full shared cases run on the JVM (LoudnessGainTest); this proves the device build loads and agrees.
@RunWith(AndroidJUnit4::class)
class RustCoreDeviceTest {
    @Test
    fun loadsTheRustCoreAndAgreesWithTheSharedCases() {
        assertEquals(1u, coreApiVersion())
        assertEquals(-300, loudnessGainMb(loudnessDb = 3.0, targetLufs = -14))
        assertEquals(300, loudnessGainMb(loudnessDb = -12.0, targetLufs = -10))
        assertEquals(-1500, loudnessGainMb(loudnessDb = 20.0, targetLufs = -20))
    }
}
