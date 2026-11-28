package app.vyral.desktop.recognition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DesktopSongRecognitionTest {
    @Test
    fun `maps a Shazam track and its song metadata`() {
        val response = Json.parseToJsonElement(
            """
            {
              "track": {
                "title": "Recognized Track",
                "subtitle": "Recognized Artist",
                "images": {"coverart": "https://example.invalid/cover.jpg"},
                "sections": [{
                  "type": "SONG",
                  "metadata": [{"title": "Album", "text": "Recognized Album"}]
                }]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val result = parseShazamRecognition(response)

        assertNotNull(result)
        assertEquals("Recognized Track", result?.title)
        assertEquals("Recognized Artist", result?.artist)
        assertEquals("Recognized Album", result?.album)
        assertEquals("https://example.invalid/cover.jpg", result?.artworkUrl)
    }

    @Test
    fun `missing track is a no-match rather than an empty fake result`() {
        assertNull(parseShazamRecognition(Json.parseToJsonElement("{} ").jsonObject))
    }

    @Test
    fun `downsamples one second of pcm while preserving a constant signal`() {
        val input = ByteBuffer.allocate(44_100 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44_100) { input.putShort(1_200) }

        val output = resampleMonoPcm16Le(input.array(), 44_100)

        assertEquals(16_000 * 2, output.size)
        val samples = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertTrue((0 until samples.limit()).all { samples.get(it).toInt() == 1_200 })
    }

    @Test
    fun `recognizes only known system monitor labels`() {
        assertTrue(isSystemAudioCaptureDevice("Monitor of Built-in Audio Analog Stereo"))
        assertTrue(isSystemAudioCaptureDevice("Stereo Mix (Realtek Audio)"))
        assertTrue(isSystemAudioCaptureDevice("What U Hear"))
        assertTrue(!isSystemAudioCaptureDevice("Microphone Array"))
        assertTrue(!isSystemAudioCaptureDevice("Monitor Speakers"))
    }

    @Test
    fun `downmixes interleaved stereo pcm to mono`() {
        val stereo = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(10_000).putShort(2_000)
            .putShort((-10_000).toShort()).putShort(6_000)
            .array()

        val mono = downmixPcm16Le(stereo, channels = 2)
        val samples = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        assertEquals(2, samples.limit())
        assertEquals(6_000, samples.get(0).toInt())
        assertEquals(-2_000, samples.get(1).toInt())
    }

    @Test
    fun `fingerprint generation is stable and emits the Shazam signature format`() {
        val pcm = ByteBuffer.allocate(16_000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16_000) { index ->
            val sample = (kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 16_000) * 20_000).toInt().toShort()
            pcm.putShort(sample)
        }
        val input = pcm.array()

        val first = DesktopShazamSignature.fromI16(input)
        val second = DesktopShazamSignature.fromI16(input)

        assertTrue(first.startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertEquals(first, second)
        input.fill(0)
    }
}
