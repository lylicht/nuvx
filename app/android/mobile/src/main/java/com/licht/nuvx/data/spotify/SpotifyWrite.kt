package com.licht.nuvx.data.spotify.write

import com.licht.nuvx.data.Http
import com.licht.nuvx.data.canvas.SpotifyToken
import com.licht.nuvx.data.model.SPOTIFY_MISSING_PREFIX
import com.licht.nuvx.data.model.SPOTIFY_PENDING_PREFIX
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.sources.TrackMatcher
import com.licht.nuvx.data.spotify.SpotifyLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/** A Spotify Web API track hit, kept separate from the PATHFINDER-backed library track. */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

/**
 * Single-song Spotify writes over the Web API REST endpoints, authenticated with the
 * bearer token harvested from the saved sp_dc session. No nuvx/YTM sign-in, OAuth or
 * PATHFINDER hash is involved.
 */
object SpotifyWrite {

    private const val TRACK_URI = "spotify:track:"
    private const val SEARCH_LIMIT = 10
    private const val PLAYLIST_CHUNK = 100
    private const val SAVED_CHUNK = 50
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val base62 = Regex("^[A-Za-z0-9]{1,22}$")

    /** Overridable in tests; never changed at runtime. */
    internal var apiBase = "https://api.spotify.com/v1"
    internal var httpClient: OkHttpClient = Http.client
    internal var tokenProvider: suspend () -> String? = { SpotifyToken.accessToken() }

    suspend fun searchTracks(query: String): List<SpotifyTrack> {
        if (query.isBlank()) return emptyList()
        val url = endpoint("search").newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        val (code, body) = execute { token ->
            Request.Builder().url(url).get().authorized(token).build()
        } ?: return emptyList()
        if (code !in 200..299 || body.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val items = runCatching { root.obj("tracks")?.arr("items") }.getOrNull().orEmpty()
        return items.mapNotNull { item -> parseTrack(item as? JsonObject ?: return@mapNotNull null) }
    }

    suspend fun trackIdFor(song: Song): String? {
        directTrackId(song)?.let { return it }

        val target = TrackMatcher.targetOf(song)
        val seen = HashSet<String>()
        for (query in TrackMatcher.queries(target)) {
            if (query.isBlank() || !seen.add(query)) continue
            val tracks = searchTracks(query)
            if (tracks.isEmpty()) continue
            val best = TrackMatcher.best(tracks.map { it.asCandidate() }, target)
            val id = best?.videoId?.takeIf(::isValidTrackId)
            if (id != null) return id
        }
        return null
    }

    /** Returns the number of ids accepted by Spotify, or 0 if nothing could be written. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Int {
        // LIKED_ID is a library UI sentinel, not a playlist id the Web API understands.
        if (!isValidTrackId(playlistId) || playlistId == SpotifyLibrary.LIKED_ID) return 0
        val ids = trackIds.distinct().filter(::isValidTrackId)
        if (ids.isEmpty()) return 0

        val url = endpoint("playlists/$playlistId/tracks")
        var sent = 0
        for (chunk in ids.chunked(PLAYLIST_CHUNK)) {
            val body = playlistRequestBody(chunk)
            val (code, _) = execute { token ->
                Request.Builder()
                    .url(url)
                    // POST appends; PUT would replace the playlist's existing items.
                    .post(body.toRequestBody(mediaType))
                    .authorized(token)
                    .build()
            } ?: continue
            if (code in 200..299) sent += chunk.size
        }
        return sent
    }

    /** Saves to Liked Songs; returns the number of ids accepted by Spotify. */
    suspend fun saveTracks(trackIds: List<String>): Int {
        val ids = trackIds.distinct().filter(::isValidTrackId)
        if (ids.isEmpty()) return 0

        var sent = 0
        for (chunk in ids.chunked(SAVED_CHUNK)) {
            val url = endpoint("me/tracks").newBuilder()
                .addQueryParameter("ids", chunk.joinToString(","))
                .build()
            val (code, _) = execute { token ->
                Request.Builder()
                    .url(url)
                    .put("{}".toRequestBody(mediaType))
                    .authorized(token)
                    .build()
            } ?: continue
            if (code in 200..299) sent += chunk.size
        }
        return sent
    }

    internal fun isValidTrackId(id: String): Boolean = base62.matches(id.trim())

    internal fun directTrackId(song: Song): String? {
        val raw = song.videoId.trim()
        val candidate = when {
            raw.startsWith(TRACK_URI) -> raw.substringAfter(TRACK_URI, "")
            raw.startsWith("spotify:") -> raw.substringAfterLast(':')
            raw.startsWith(SPOTIFY_MISSING_PREFIX) -> raw.substringAfter(SPOTIFY_MISSING_PREFIX, "")
            raw.startsWith(SPOTIFY_PENDING_PREFIX) -> raw.substringAfter(SPOTIFY_PENDING_PREFIX, "")
            else -> return null
        }.trim()
        return candidate.takeIf(::isValidTrackId)
    }

    internal fun playlistRequestBody(trackIds: List<String>): String = buildJsonObject {
        putJsonArray("uris") {
            trackIds.filter(::isValidTrackId).forEach { add("$TRACK_URI$it") }
        }
    }.toString()

    private fun SpotifyTrack.asCandidate() = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = imageUrl,
        durationText = durationMs.takeIf { it > 0 }?.let { ms ->
            "%d:%02d".format(Locale.ROOT, ms / 60_000, (ms / 1_000) % 60)
        },
        albumName = album,
    )

    private fun parseTrack(item: JsonObject): SpotifyTrack? {
        val id = item.str("id")?.takeIf(::isValidTrackId) ?: return null
        val title = item.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val artist = item.arr("artists")
            ?.mapNotNull { it.jsonObject.str("name")?.takeIf { name -> name.isNotBlank() } }
            ?.joinToString(", ")
            .orEmpty()
        val album = item.obj("album")
        return SpotifyTrack(
            id = id,
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = item.int("duration_ms") ?: 0,
            imageUrl = album?.arr("images")?.mapNotNull { it.jsonObject.str("url") }?.firstOrNull(),
        )
    }

    private fun endpoint(path: String) = "${apiBase.trimEnd('/')}/$path".toHttpUrl()

    private fun Request.Builder.authorized(token: String): Request.Builder = this
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .header("app-platform", "WebPlayer")
        .header("User-Agent", USER_AGENT)

    private suspend fun execute(build: (String) -> Request): Pair<Int, String?>? {
        var token = tokenProvider() ?: return null
        var result = call(build(token))
        if (result.first == 401) {
            SpotifyToken.invalidate()
            token = tokenProvider() ?: return result
            result = call(build(token))
        }
        return result
    }

    private suspend fun call(request: Request): Pair<Int, String?> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                response.code to response.body?.string()
            }
        }.getOrElse { -1 to null }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.arr(key: String): JsonArray? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
}
