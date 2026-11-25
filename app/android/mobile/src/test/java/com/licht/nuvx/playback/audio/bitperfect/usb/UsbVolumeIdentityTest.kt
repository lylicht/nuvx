package com.licht.nuvx.playback.audio.bitperfect.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class UsbVolumeIdentityTest {
    @Test
    fun `persistent hardware volume identity requires a serial and is scoped to the device`() {
        assertNull(UsbDirectDevices.volumePreferenceKey(0x1234, 0x5678, null))
        assertNull(UsbDirectDevices.volumePreferenceKey(0x1234, 0x5678, "  "))

        val key = UsbDirectDevices.volumePreferenceKey(0x1234, 0x5678, " DAC-42 ")
        assertEquals(key, UsbDirectDevices.volumePreferenceKey(0x1234, 0x5678, "DAC-42"))
        assertNotEquals(key, UsbDirectDevices.volumePreferenceKey(0x1234, 0x5678, "DAC-43"))
        assertNotEquals(key, UsbDirectDevices.volumePreferenceKey(0x1235, 0x5678, "DAC-42"))
        assertFalse(requireNotNull(key).contains("DAC-42"))
    }
}
