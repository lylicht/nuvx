package app.vyral.night.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SongRecognitionTest {
    @Test
    fun captureModesOnlyExposeInputsSupportedByPlatformAndDevice() {
        assertEquals(
            listOf(RecognitionCaptureMode.MICROPHONE),
            recognitionCaptureModes(apiLevel = 28, hasInput = true),
        )
        assertEquals(emptyList<RecognitionCaptureMode>(), recognitionCaptureModes(apiLevel = 28, hasInput = false))
        assertEquals(
            listOf(RecognitionCaptureMode.DEVICE_AUDIO),
            recognitionCaptureModes(apiLevel = 29, hasInput = false),
        )
        assertEquals(
            listOf(RecognitionCaptureMode.MICROPHONE, RecognitionCaptureMode.DEVICE_AUDIO),
            recognitionCaptureModes(apiLevel = 36, hasInput = true),
        )
    }

    @Test
    fun emptyDeviceCaptureIsReportedAsUnavailableBeforeFingerprinting() {
        assertFalse(pcmContainsAudio(ByteArray(RECOGNITION_SAMPLE_RATE * 2)))
        assertFalse(pcmContainsAudio(byteArrayOf(1, 0, 2, 0)))
        assertTrue(pcmContainsAudio(byteArrayOf(64, 0, 0, 0)))
        assertTrue(pcmContainsAudio(byteArrayOf(0, -1)))
    }

    @Test
    fun signatureRequestMapsOnlyTheDerivedSignatureAndEmptyGeolocation() {
        val signature = "data:audio/vnd.shazam.sig;base64,AA=="
        val request = buildShazamSignatureRequest(signature, 10_000, 1_700_000_000)

        assertEquals(JsonObject(emptyMap()), request["geolocation"]?.jsonObject)
        val nested = request["signature"]!!.jsonObject
        assertEquals(signature, nested["uri"]?.jsonPrimitive?.content)
        assertEquals(10_000L, nested["samplems"]?.jsonPrimitive?.long)
        assertEquals(1_700_000_000L, request["timestamp"]?.jsonPrimitive?.long)
        assertEquals("UTC", request["timezone"]?.jsonPrimitive?.content)
        assertFalse(request.toString().contains("latitude"))
        assertFalse(request.toString().contains("longitude"))
    }

    @Test
    fun pipelineTransitionsThroughListeningAndMatchingToSearchableResult() = runBlocking {
        lateinit var pipeline: RecognitionPipeline
        val track = RecognizedSong("Song", "Artist", null, null)
        val capturedPcm = ByteArray(RECOGNITION_SAMPLE_RATE * 2) { 1 }
        pipeline = RecognitionPipeline(
            capture = { progress ->
                assertEquals(RecognitionState.Listening(0), pipeline.state.value)
                progress(1_000)
                assertEquals(RecognitionState.Listening(1_000), pipeline.state.value)
                capturedPcm
            },
            identify = { signature, duration ->
                assertEquals("derived-signature", signature)
                assertEquals(1_000L, duration)
                assertEquals(RecognitionState.Matching, pipeline.state.value)
                assertTrue(capturedPcm.all { it == 0.toByte() })
                track
            },
            signatureFor = { "derived-signature" },
        )

        pipeline.recognize()

        assertEquals(RecognitionState.Found(track), pipeline.state.value)
        assertEquals("Song Artist", track.searchQuery())
    }

    @Test
    fun fingerprintIsStableForDeterministicSyntheticPcm() {
        val sampleCount = RECOGNITION_SAMPLE_RATE * 3
        val pcm = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { index ->
            val sample = (sin(2.0 * PI * 440.0 * index / RECOGNITION_SAMPLE_RATE) * Short.MAX_VALUE)
                .toInt().toShort()
            pcm.putShort(sample)
        }
        val samples = pcm.array()

        val first = ShazamSignatureGenerator.fromI16(samples)
        val second = ShazamSignatureGenerator.fromI16(samples)

        assertTrue(first.startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertEquals(first, second)
    }

    @Test
    fun cancellationStopsCaptureAndNeverCallsLookup() = runBlocking {
        val captureStarted = CompletableDeferred<Unit>()
        var captureReleased = false
        var lookupCalled = false
        val pipeline = RecognitionPipeline(
            capture = {
                try {
                    captureStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    captureReleased = true
                }
            },
            identify = { _, _ ->
                lookupCalled = true
                null
            },
            signatureFor = { "unused" },
        )

        val job = launch { pipeline.recognize() }
        captureStarted.await()
        job.cancelAndJoin()

        assertTrue(captureReleased)
        assertFalse(lookupCalled)
        assertEquals(RecognitionState.Idle, pipeline.state.value)
    }
}
