package app.vyral.night

import app.vyral.night.ui.screens.parseLanPairingQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanPairingPayloadTest {
    @Test
    fun `accepts version one pairing payload with pinned HTTPS address`() {
        val pin = "a".repeat(64)
        val payload = """{"type":"vyral-lan-pairing","version":1,"url":"https://192.168.1.24:47731","tlsFingerprint":"$pin"}"""

        assertEquals("https://192.168.1.24:47731" to pin, parseLanPairingQr(payload))
    }

    @Test
    fun `rejects wrong type version fingerprint and non HTTPS address`() {
        val pin = "a".repeat(64)
        assertNull(parseLanPairingQr("""{"type":"other","version":1,"url":"https://192.168.1.24:47731","tlsFingerprint":"$pin"}"""))
        assertNull(parseLanPairingQr("""{"type":"vyral-lan-pairing","version":2,"url":"https://192.168.1.24:47731","tlsFingerprint":"$pin"}"""))
        assertNull(parseLanPairingQr("""{"type":"vyral-lan-pairing","version":1,"url":"https://192.168.1.24:47731","tlsFingerprint":"bad"}"""))
        assertNull(parseLanPairingQr("""{"type":"vyral-lan-pairing","version":1,"url":"http://192.168.1.24:47731","tlsFingerprint":"$pin"}"""))
    }
}
