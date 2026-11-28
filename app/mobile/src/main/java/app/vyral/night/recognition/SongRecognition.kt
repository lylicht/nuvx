package app.vyral.night.recognition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.vyral.night.BuildConfig
import app.vyral.night.playback.PlaybackService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val RECOGNITION_SAMPLE_RATE = 16_000
internal const val RECOGNITION_MAX_DURATION_MS = 10_000L
private const val PCM_BYTES_PER_SAMPLE = 2
private const val MAX_PCM_BYTES = RECOGNITION_SAMPLE_RATE * PCM_BYTES_PER_SAMPLE * 10
private const val SHAZAM_SIGNATURE_PREFIX = "data:audio/vnd.shazam.sig;base64,"

internal enum class RecognitionCaptureMode { MICROPHONE, DEVICE_AUDIO }

internal fun recognitionCaptureModes(apiLevel: Int, hasInput: Boolean): List<RecognitionCaptureMode> = buildList {
    if (hasInput) add(RecognitionCaptureMode.MICROPHONE)
    if (apiLevel >= Build.VERSION_CODES.Q) add(RecognitionCaptureMode.DEVICE_AUDIO)
}

internal fun pcmContainsAudio(pcm: ByteArray): Boolean {
    var index = 0
    while (index + 1 < pcm.size) {
        val sample = ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
        if (kotlin.math.abs(sample) >= 64) return true
        index += 2
    }
    return false
}

internal fun recognitionCaptureModes(context: Context): List<RecognitionCaptureMode> {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val hasInput = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ||
        runCatching { audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.isNotEmpty() == true }.getOrDefault(false)
    return recognitionCaptureModes(Build.VERSION.SDK_INT, hasInput).filter { mode ->
        mode != RecognitionCaptureMode.DEVICE_AUDIO ||
            (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager) != null
    }
}

internal object RecognitionCaptureProtocol {
    const val ACTION_CAPTURE = "app.vyral.night.action.CAPTURE_DEVICE_AUDIO"
    const val ACTION_CANCEL = "app.vyral.night.action.CANCEL_DEVICE_AUDIO_CAPTURE"
    const val EXTRA_RESULT_CODE = "recognition_result_code"
    const val EXTRA_RESULT_DATA = "recognition_result_data"
    const val EXTRA_RECEIVER = "recognition_receiver"
    const val EXTRA_REQUEST_ID = "recognition_request_id"
    const val EXTRA_PCM = "recognition_pcm"
    const val EXTRA_PROGRESS_MS = "recognition_progress_ms"
    const val EXTRA_ERROR = "recognition_error"
    const val RESULT_PROGRESS = 2
}

internal data class RecognizedSong(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val shazamUrl: String?,
) {
    fun searchQuery(): String = listOf(title, artist).filter(String::isNotBlank).joinToString(" ")
}

internal sealed interface RecognitionState {
    data object Idle : RecognitionState
    data class Listening(val elapsedMs: Long) : RecognitionState
    data object Matching : RecognitionState
    data class Found(val song: RecognizedSong) : RecognitionState
    data object NoMatch : RecognitionState
    data object PermissionDenied : RecognitionState
    data object DeviceCaptureConsentDenied : RecognitionState
    data object AudioUnavailable : RecognitionState
    data object NetworkError : RecognitionState
}

/** Coordinates capture, local signature generation, and the remote lookup; raw PCM never leaves this pipeline. */
internal class RecognitionPipeline(
    private val capture: suspend (onProgress: (Long) -> Unit) -> ByteArray,
    private val identify: suspend (signature: String, durationMs: Long) -> RecognizedSong?,
    private val signatureFor: (ByteArray) -> String = ShazamSignatureGenerator::fromI16,
) {
    private val mutableState = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state = mutableState.asStateFlow()

    suspend fun recognize(captureAudio: suspend (onProgress: (Long) -> Unit) -> ByteArray = capture) {
        var pcm: ByteArray? = null
        mutableState.value = RecognitionState.Listening(0)
        try {
            pcm = try {
                captureAudio { elapsed -> mutableState.value = RecognitionState.Listening(elapsed.coerceIn(0, RECOGNITION_MAX_DURATION_MS)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                mutableState.value = RecognitionState.PermissionDenied
                return
            } catch (_: Exception) {
                mutableState.value = RecognitionState.AudioUnavailable
                return
            }

            val audioPcm = pcm
            if (audioPcm.size < RECOGNITION_SAMPLE_RATE * PCM_BYTES_PER_SAMPLE || audioPcm.size % PCM_BYTES_PER_SAMPLE != 0) {
                mutableState.value = RecognitionState.AudioUnavailable
                return
            }
            val durationMs = (audioPcm.size / PCM_BYTES_PER_SAMPLE * 1_000L / RECOGNITION_SAMPLE_RATE)
                .coerceIn(1L, RECOGNITION_MAX_DURATION_MS)
            val signature = try {
                withContext(Dispatchers.Default) {
                    currentCoroutineContext().ensureActive()
                    signatureFor(audioPcm).also { currentCoroutineContext().ensureActive() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RecognitionState.AudioUnavailable
                return
            } finally {
                audioPcm.fill(0)
                pcm = null
            }

            mutableState.value = RecognitionState.Matching
            mutableState.value = try {
                identify(signature, durationMs)?.let(RecognitionState::Found) ?: RecognitionState.NoMatch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RecognitionState.NetworkError
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = RecognitionState.Idle
            throw cancelled
        } finally {
            pcm?.fill(0)
        }
    }

    fun reset() {
        mutableState.value = RecognitionState.Idle
    }

    fun setPermissionDenied() {
        mutableState.value = RecognitionState.PermissionDenied
    }

    fun setDeviceCaptureConsentDenied() {
        mutableState.value = RecognitionState.DeviceCaptureConsentDenied
    }

    fun setAudioUnavailable() {
        mutableState.value = RecognitionState.AudioUnavailable
    }
}

internal suspend fun captureDeviceAudioPcm(
    context: Context,
    resultCode: Int,
    resultData: Intent,
    onProgress: (Long) -> Unit,
): ByteArray = suspendCancellableCoroutine { continuation ->
    val requestId = UUID.randomUUID().toString()
    val appContext = context.applicationContext
    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                RecognitionCaptureProtocol.RESULT_PROGRESS -> {
                    onProgress(resultData?.getLong(RecognitionCaptureProtocol.EXTRA_PROGRESS_MS) ?: 0L)
                }
                android.app.Activity.RESULT_OK -> {
                    val pcm = resultData?.getByteArray(RecognitionCaptureProtocol.EXTRA_PCM)
                    if (!continuation.isActive) pcm?.fill(0)
                    else if (pcm == null) continuation.resumeWithException(IOException("System audio capture returned no audio"))
                    else continuation.resume(pcm)
                }
                else -> if (continuation.isActive) continuation.resumeWithException(
                    IOException(resultData?.getString(RecognitionCaptureProtocol.EXTRA_ERROR) ?: "System audio capture failed"),
                )
            }
        }
    }
    val intent = Intent(appContext, PlaybackService::class.java)
        .setAction(RecognitionCaptureProtocol.ACTION_CAPTURE)
        .putExtra(RecognitionCaptureProtocol.EXTRA_RESULT_CODE, resultCode)
        .putExtra(RecognitionCaptureProtocol.EXTRA_RESULT_DATA, resultData)
        .putExtra(RecognitionCaptureProtocol.EXTRA_RECEIVER, receiver)
        .putExtra(RecognitionCaptureProtocol.EXTRA_REQUEST_ID, requestId)
    continuation.invokeOnCancellation {
        runCatching {
            appContext.startService(
                Intent(appContext, PlaybackService::class.java)
                    .setAction(RecognitionCaptureProtocol.ACTION_CANCEL)
                    .putExtra(RecognitionCaptureProtocol.EXTRA_REQUEST_ID, requestId),
            )
        }
    }
    try {
        androidx.core.content.ContextCompat.startForegroundService(appContext, intent)
    } catch (error: Exception) {
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}

internal fun buildShazamSignatureRequest(
    signature: String,
    durationMs: Long,
    timestampSeconds: Long,
): JsonObject {
    require(signature.startsWith(SHAZAM_SIGNATURE_PREFIX)) { "Expected a Shazam audio signature" }
    require(durationMs in 1..RECOGNITION_MAX_DURATION_MS) { "Sample duration must be at most ten seconds" }
    require(timestampSeconds > 0) { "Timestamp must be positive" }
    return buildJsonObject {
        put("geolocation", buildJsonObject {})
        put("signature", buildJsonObject {
            put("uri", signature)
            put("samplems", durationMs)
            put("timestamp", timestampSeconds)
        })
        put("timestamp", timestampSeconds)
        put("timezone", "UTC")
    }
}

private object ShazamRecognitionClient {
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        }
    }

    suspend fun identify(signature: String, durationMs: Long): RecognizedSong? {
        val timestamp = System.currentTimeMillis() / 1_000L
        val request = buildShazamSignatureRequest(signature, durationMs, timestamp)
        val firstId = UUID.randomUUID().toString().uppercase(Locale.ROOT)
        val secondId = UUID.randomUUID().toString()
        val response = client.post(
            "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$firstId/$secondId",
        ) {
            parameter("sync", "true")
            parameter("webv3", "true")
            parameter("sampling", "true")
            parameter("connected", "")
            parameter("shazamapiversion", "v3")
            parameter("sharehub", "true")
            parameter("video", "v3")
            header(HttpHeaders.UserAgent, "VYRAL/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})")
            header(HttpHeaders.Accept, "application/json")
            contentType(ContentType.Application.Json)
            setBody(request.toString())
        }

        if (response.status.value == 404) return null
        if (response.status.value == 429) throw IOException("Recognition is temporarily rate limited")
        if (!response.status.isSuccess()) throw IOException("Recognition service unavailable (${response.status.value})")
        val root = runCatching { jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw IOException("Recognition response could not be read", it) }
        val track = root["track"]?.jsonObject ?: return null
        val title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val artist = track["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (title.isBlank() || artist.isBlank()) return null
        val images = track["images"]?.jsonObject
        return RecognizedSong(
            title = title,
            artist = artist,
            artworkUrl = images?.get("coverarthq")?.jsonPrimitive?.contentOrNull
                ?: images?.get("coverart")?.jsonPrimitive?.contentOrNull,
            shazamUrl = track["url"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

internal fun newAndroidRecognitionPipeline(context: Context): RecognitionPipeline = RecognitionPipeline(
    capture = { onProgress -> capturePcm(context, onProgress) },
    identify = ShazamRecognitionClient::identify,
)

private suspend fun capturePcm(context: Context, onProgress: (Long) -> Unit): ByteArray = withContext(Dispatchers.IO) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Microphone permission is not granted")
    }
    val minBufferBytes = AudioRecord.getMinBufferSize(
        RECOGNITION_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    require(minBufferBytes > 0 && minBufferBytes <= MAX_PCM_BYTES) { "Microphone format is unavailable" }

    val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        RECOGNITION_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferBytes.coerceAtLeast(4_096),
    )
    val readBuffer = ByteArray(minBufferBytes.coerceAtLeast(4_096))
    val pcm = ByteArray(MAX_PCM_BYTES)
    var written = 0
    try {
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }
        audioRecord.startRecording()
        val startedAt = SystemClock.elapsedRealtime()
        var lastProgress = -100L
        while (written < pcm.size) {
            currentCoroutineContext().ensureActive()
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(RECOGNITION_MAX_DURATION_MS)
            if (elapsed >= RECOGNITION_MAX_DURATION_MS) break
            val count = audioRecord.read(
                readBuffer,
                0,
                minOf(readBuffer.size, pcm.size - written),
                AudioRecord.READ_NON_BLOCKING,
            )
            if (count > 0) {
                System.arraycopy(readBuffer, 0, pcm, written, count)
                written += count
            } else if (count < 0) {
                throw IOException("Microphone read failed ($count)")
            } else {
                delay(12)
            }
            if (elapsed - lastProgress >= 100) {
                lastProgress = elapsed
                onProgress(elapsed)
            }
        }
        val evenBytes = written - (written % PCM_BYTES_PER_SAMPLE)
        check(evenBytes >= RECOGNITION_SAMPLE_RATE * PCM_BYTES_PER_SAMPLE) { "Not enough audio was captured" }
        pcm.copyOf(evenBytes)
    } finally {
        readBuffer.fill(0)
        pcm.fill(0)
        runCatching { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() }
        audioRecord.release()
    }
}
