package app.vyral.desktop.recognition

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.roundToInt

internal data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
)

internal enum class RecognitionDialogPhase {
    DISCLOSURE,
    LISTENING,
    MATCHING,
    FOUND,
    NO_MATCH,
    ERROR,
}

private data class CapturedAudio(
    val pcm16le: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
)

/** An input the user explicitly chose for one recognition attempt. */
internal data class DesktopRecognitionInput(
    val id: String,
    val label: String,
    val systemAudio: Boolean,
    internal val mixerInfo: Mixer.Info? = null,
) {
    companion object {
        val DEFAULT_MICROPHONE = DesktopRecognitionInput(
            id = "microphone:default",
            label = "",
            systemAudio = false,
        )
    }
}

/** User-triggered recognition for the KMP desktop client. Captured PCM stays in memory. */
internal object DesktopSongRecognition {
    private const val SAMPLE_RATE = 16_000
    private const val CAPTURE_LIMIT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 15_000L

    private val activeLine = AtomicReference<TargetDataLine?>(null)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }

    fun cancelCapture() {
        activeLine.getAndSet(null)?.let { line ->
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    /**
     * Only advertises system audio when the OS audio provider exposes a capture endpoint whose
     * name identifies it as a monitor/loopback input. Ordinary microphones remain the fallback.
     */
    fun availableInputs(): List<DesktopRecognitionInput> = buildList {
        add(DesktopRecognitionInput.DEFAULT_MICROPHONE)
        runCatching {
            AudioSystem.getMixerInfo().forEach { info ->
                val exposesCapture = runCatching {
                    AudioSystem.getMixer(info).targetLineInfo.any { line ->
                        TargetDataLine::class.java.isAssignableFrom(line.lineClass) ||
                            line.lineClass.isAssignableFrom(TargetDataLine::class.java)
                    }
                }.getOrDefault(false)
                if (exposesCapture && isSystemAudioCaptureDevice(info.name)) {
                    add(
                        DesktopRecognitionInput(
                            id = "system:${info.name}",
                            label = info.name,
                            systemAudio = true,
                            mixerInfo = info,
                        ),
                    )
                }
            }
        }
    }.distinctBy(DesktopRecognitionInput::id)

    suspend fun recognize(
        input: DesktopRecognitionInput = DesktopRecognitionInput.DEFAULT_MICROPHONE,
        onMatching: suspend () -> Unit,
    ): RecognizedSong? = withContext(Dispatchers.IO) {
        val audio = capturePcm(input)
        var signature: String? = null
        try {
            currentCoroutineContext().ensureActive()
            val monoPcm = downmixPcm16Le(audio.pcm16le, audio.channels)
            audio.pcm16le.fill(0)
            val signaturePcm = try {
                resampleMonoPcm16Le(monoPcm, audio.sampleRate)
            } finally {
                monoPcm.fill(0)
            }
            try {
                signature = DesktopShazamSignature.fromI16(signaturePcm)
            } finally {
                signaturePcm.fill(0)
            }
            audio.pcm16le.fill(0)
            currentCoroutineContext().ensureActive()
            onMatching()
            lookup(signature, audio.durationMs)
        } finally {
            audio.pcm16le.fill(0)
            signature = null
            cancelCapture()
        }
    }

    private suspend fun capturePcm(input: DesktopRecognitionInput): CapturedAudio {
        val (line, sampleRate) = openInputLine(input)
        check(activeLine.compareAndSet(null, line)) {
            line.close()
            "Audio capture is already active"
        }

        val channels = line.format.channels
        val frameSize = line.format.frameSize
        val maxBytes = (sampleRate.toLong() * frameSize * CAPTURE_LIMIT_MS / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val pcm = ByteArray(maxBytes)
        val readBuffer = ByteArray(8_192)
        var written = 0
        val deadline = System.nanoTime() + CAPTURE_LIMIT_MS * 1_000_000L
        try {
            currentCoroutineContext().ensureActive()
            line.start()
            while (written < maxBytes && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                val available = (line.available() / frameSize * frameSize)
                    .coerceAtMost(readBuffer.size)
                if (available <= 0) {
                    delay(12)
                    continue
                }
                val count = line.read(readBuffer, 0, available)
                if (count < 0) error("Audio input stopped unexpectedly")
                if (count > 0) {
                    val safeCount = count.coerceAtMost(maxBytes - written)
                    readBuffer.copyInto(pcm, destinationOffset = written, endIndex = safeCount)
                    written += safeCount
                    readBuffer.fill(0, 0, count.coerceAtMost(readBuffer.size))
                }
            }
            currentCoroutineContext().ensureActive()
            check(written >= sampleRate * frameSize / 2) {
                "The input did not capture enough audio. Check the selected input device."
            }
            return CapturedAudio(
                pcm16le = pcm.copyOf(written),
                sampleRate = sampleRate,
                channels = channels,
                durationMs = written.toLong() * 1_000L / (sampleRate * frameSize),
            )
        } finally {
            readBuffer.fill(0)
            pcm.fill(0)
            activeLine.compareAndSet(line, null)
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    private fun openInputLine(input: DesktopRecognitionInput): Pair<TargetDataLine, Int> {
        var lastFailure: Throwable? = null
        // Prefer the common desktop recording rates; convert to the signature's 16 kHz mono.
        for (sampleRate in intArrayOf(48_000, 44_100, SAMPLE_RATE)) {
            for (channels in if (input.systemAudio) intArrayOf(2, 1) else intArrayOf(1, 2)) {
                val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
                val info = DataLine.Info(TargetDataLine::class.java, format)
                try {
                    val line = (input.mixerInfo?.let { mixerInfo ->
                        AudioSystem.getMixer(mixerInfo).getLine(info)
                    } ?: AudioSystem.getLine(info)) as TargetDataLine
                    try {
                        line.open(format)
                    } catch (failure: Exception) {
                        runCatching { line.close() }
                        throw failure
                    }
                    return line to sampleRate
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
        }
        throw IllegalStateException(
            if (input.systemAudio) {
                "The selected system audio monitor is unavailable. Choose another input or enable a system audio capture device."
            } else {
                "No microphone input is available. Connect or enable an input device and try again."
            },
            lastFailure,
        )
    }

    private suspend fun lookup(signature: String?, sampleDurationMs: Long): RecognizedSong? {
        val signatureUri = signature ?: error("Could not create an audio fingerprint")
        val timestamp = System.currentTimeMillis() / 1_000L
        val payload = buildJsonObject {
            put("geolocation", buildJsonObject {})
            put(
                "signature",
                buildJsonObject {
                    put("samplems", sampleDurationMs)
                    put("timestamp", timestamp)
                    put("uri", signatureUri)
                },
            )
            put("timestamp", timestamp)
            put("timezone", "UTC")
        }
        val response = client.post(
            "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
                "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}",
        ) {
            parameter("sync", "true")
            parameter("webv3", "true")
            parameter("sampling", "true")
            parameter("connected", "")
            parameter("shazamapiversion", "v3")
            parameter("sharehub", "true")
            parameter("video", "v3")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            header("User-Agent", "VYRAL/1.0 (app.vyral.night; desktop)")
            setBody(payload)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) {
            error("Shazam recognition failed (HTTP ${response.status.value})")
        }
        return parseShazamRecognition(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }
}

/** Restrict loopback choices to known monitor labels instead of guessing from any capture device. */
internal fun isSystemAudioCaptureDevice(name: String): Boolean {
    val normalized = name.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    return listOf(
        "monitor of ",
        "monitor source",
        "stereo mix",
        "what u hear",
        "wave out mix",
        "loopback",
    ).any { marker -> normalized.contains(marker) }
}

internal fun downmixPcm16Le(input: ByteArray, channels: Int): ByteArray {
    require(channels > 0 && input.size % (channels * 2) == 0) {
        "Captured audio did not contain complete PCM frames"
    }
    if (channels == 1) return input.copyOf()
    val frameCount = input.size / (channels * 2)
    val source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val output = ByteArray(frameCount * 2)
    val target = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (frame in 0 until frameCount) {
        var sum = 0L
        val first = frame * channels
        for (channel in 0 until channels) sum += source.get(first + channel).toLong()
        target.put(frame, (sum / channels).toShort())
    }
    return output
}

internal fun parseShazamRecognition(root: JsonObject): RecognizedSong? {
    val track = root["track"] as? JsonObject ?: return null
    val title = track.string("title")?.takeIf(String::isNotBlank) ?: return null
    val artist = track.string("subtitle").orEmpty()
    val sections = track["sections"] as? JsonArray
    val songSection = sections.orEmpty()
        .firstOrNull { (it as? JsonObject)?.string("type") == "SONG" } as? JsonObject
    val metadata = songSection?.get("metadata") as? JsonArray
    val album = metadata.orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { it.string("title").equals("Album", ignoreCase = true) }
        ?.string("text")
    val images = track["images"] as? JsonObject
    return RecognizedSong(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = images?.string("coverarthq") ?: images?.string("coverart"),
    )
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList().orEmpty()

internal fun resampleMonoPcm16Le(input: ByteArray, inputRate: Int): ByteArray {
    val bytesPerSample = 2
    val targetSampleRate = 16_000
    require(inputRate > 0 && input.size >= bytesPerSample && input.size % bytesPerSample == 0) {
        "Captured audio was empty or incomplete"
    }
    if (inputRate == targetSampleRate) return input.copyOf()
    val inputCount = input.size / bytesPerSample
    val outputCount = (inputCount.toLong() * targetSampleRate / inputRate).toInt().coerceAtLeast(1)
    val output = ByteArray(outputCount * bytesPerSample)
    val source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val target = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val sourceSamplesPerOutput = inputRate.toDouble() / targetSampleRate
    for (index in 0 until outputCount) {
        val start = index * sourceSamplesPerOutput
        val end = (index + 1) * sourceSamplesPerOutput
        var sourceIndex = start.toInt()
        var weightedSum = 0.0
        var totalWeight = 0.0
        while (sourceIndex < end && sourceIndex < inputCount) {
            val weight = minOf(end, sourceIndex + 1.0) - maxOf(start, sourceIndex.toDouble())
            weightedSum += source.get(sourceIndex).toInt() * weight
            totalWeight += weight
            sourceIndex++
        }
        val sample = if (totalWeight > 0.0) (weightedSum / totalWeight).roundToInt() else 0
        target.put(index, sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }
    return output
}
