package app.vyral.night.data.sources

import app.vyral.night.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class RadioJavanSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {
    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.RADIO_JAVAN
    override val displayName: String get() = config.label.ifBlank { SourceKind.RADIO_JAVAN.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok("Unofficial provider; checked on first request")

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
        target: TrackMatcher.Target?,
    ): List<Song> = RadioJavanService.search(query).take(limit).map { track ->
        Song(
            videoId = SourceRegistry.trackKey(config.id, track.id),
            title = track.title,
            artist = track.artist,
            thumbnailUrl = track.artworkUrl,
            durationText = track.durationSec?.let { seconds -> "%d:%02d".format(seconds / 60, seconds % 60) },
            sourceQuality = "HIGH",
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        RadioJavanService.stream(trackId)?.let { stream ->
            SourceStream(
                url = stream.url,
                format = StreamFormat(codec = stream.codec),
                durationSec = stream.durationSec,
            )
        }
}

internal data class RadioJavanTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int?,
    val artworkUrl: String?,
)

internal data class RadioJavanStream(val url: String, val codec: String?, val durationSec: Int?)

internal object RadioJavanService {
    private const val API = "https://play.radiojavan.com/api/p"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 8_000
                socketTimeoutMillis = 8_000
            }
            expectSuccess = false
        }
    }

    suspend fun search(query: String): List<RadioJavanTrack> {
        val response = client.get("$API/search") { parameter("query", query) }
        check(response.status == HttpStatusCode.OK) { "Radio Javan search returned HTTP ${response.status.value}" }
        return parseRadioJavanTracks(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    suspend fun stream(trackId: String): RadioJavanStream? {
        val response = client.get("$API/mp3") { parameter("id", trackId) }
        check(response.status == HttpStatusCode.OK) { "Radio Javan stream lookup returned HTTP ${response.status.value}" }
        return parseRadioJavanStream(json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }
}

internal fun parseRadioJavanTracks(root: JsonObject): List<RadioJavanTrack> =
    (root["mp3s"] as? JsonArray).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        if (row.string("type") != "mp3") return@mapNotNull null
        val id = row.numberString("id")?.toLongOrNull()?.takeIf { it > 0 }?.toString()
            ?: return@mapNotNull null
        val title = row.string("song")?.takeIf(String::isNotBlank)
            ?: row.string("title")?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        RadioJavanTrack(
            id = id,
            title = title,
            artist = row.string("artist").orEmpty(),
            durationSec = row.numberString("duration")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
            artworkUrl = row.string("photo")?.httpsUrl(),
        )
    }

internal fun parseRadioJavanStream(root: JsonObject): RadioJavanStream? {
    val url = root.string("hq_link")?.httpsUrl() ?: root.string("link")?.httpsUrl() ?: return null
    val codec = url.substringBefore('?').substringAfterLast('.', "").lowercase().let {
        when (it) {
            "m4a", "aac" -> "aac"
            "mp3" -> "mp3"
            else -> null
        }
    }
    return RadioJavanStream(
        url = url,
        codec = codec,
        durationSec = root.numberString("duration")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
    )
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.numberString(name: String): String? {
    val value: JsonElement = this[name] ?: return null
    return value.jsonPrimitive.contentOrNull
        ?: value.jsonPrimitive.intOrNull?.toString()
        ?: value.jsonPrimitive.doubleOrNull?.toString()
}

private fun String.httpsUrl(): String? = toHttpUrlOrNull()
    ?.takeIf { it.isHttps && it.username.isEmpty() && it.host.isNotBlank() }
    ?.toString()
