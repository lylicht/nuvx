package app.vyral.night.data.spotify

import app.vyral.night.data.Http
import app.vyral.night.data.canvas.SpotifyCanvasQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

object SpotifyLibrary {
    /** The web player's bearer, and its client token when there is one. */
    class Tokens(val bearer: String, val clientToken: String?)

    /**
     * Where the tokens come from: the phone mints them in a WebView, the desktop in JavaFX's.
     * Null tokens mean signed out or expired.
     */
    fun interface Auth {
        suspend fun tokens(): Tokens?
    }

    /** Installed by each application at start-up; until then every call fails as signed out. */
    @Volatile
    var auth: Auth? = null

    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val SPCLIENT_PLAYLISTS = "https://spclient.wg.spotify.com/playlist/v2/playlist"
    private const val SPCLIENT_ROOTLIST = "https://spclient.wg.spotify.com/playlist/v2/user"
    private const val SPOTIFY_ME = "https://api.spotify.com/v1/me"
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    /** The phone's canvas client sent this one; kept so its requests look unchanged. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var addToPlaylistHash: String? = null
    @Volatile private var addToLibraryHash: String? = null

    /** Match VYRAL track metadata against Spotify's own catalogue search. */
    suspend fun findTrack(title: String, artist: String, album: String? = null): SpotifyTrack? =
        withContext(Dispatchers.IO) {
            val query = listOf(title, artist, album).filterNot { it.isNullOrBlank() }.joinToString(" ")
            if (query.isBlank()) return@withContext null
            val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("type", "track")
                .addQueryParameter("limit", "10")
                .build()
            val root = getJson(url.toString(), authHeaders())
            val candidates = root.obj("tracks")?.arr("items").orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val candidateTitle = item.str("name") ?: return@mapNotNull null
                val artists = item.arr("artists").orEmpty().mapNotNull { it.jsonObject.str("name") }
                val uri = item.str("uri") ?: return@mapNotNull null
                SpotifyTrack(
                    id = uri.substringAfterLast(':'),
                    title = candidateTitle,
                    artist = artists.joinToString(", "),
                    album = item.obj("album")?.str("name"),
                    durationMs = item.int("duration_ms") ?: 0,
                    imageUrl = item.obj("album")?.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
                )
            }
            candidates.firstOrNull { spotifyTrackMatches(it, title, artist) }
        }

    /** Create a private Spotify playlist using the connected web-player session. */
    suspend fun createPlaylist(name: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Playlist name cannot be empty" }
        val headers = authHeaders()
        val username = currentUserId(headers)
        val operation = buildJsonObject {
            putJsonArray("ops") {
                add(buildJsonObject {
                    put("kind", "UPDATE_LIST_ATTRIBUTES")
                    putJsonObject("updateListAttributes") {
                        putJsonObject("newAttributes") {
                            putJsonObject("values") { put("name", name.trim()) }
                        }
                    }
                })
            }
        }
        val created = postJson(SPCLIENT_PLAYLISTS, operation, headers)
        val uri = created.str("uri") ?: error("Spotify did not return the new playlist id")
        val id = uri.substringAfterLast(':').substringAfterLast('/')
        require(id.isNotBlank()) { "Spotify returned an invalid playlist id" }
        val rootlistChange = buildJsonObject {
            putJsonArray("deltas") {
                add(buildJsonObject {
                    putJsonArray("ops") {
                        add(buildJsonObject {
                            put("kind", "ADD")
                            putJsonObject("add") {
                                putJsonArray("items") {
                                    add(buildJsonObject {
                                        put("uri", "spotify:playlist:$id")
                                        putJsonObject("attributes") { put("timestamp", System.currentTimeMillis().toString()) }
                                    })
                                }
                                put("addFirst", true)
                            }
                        })
                    }
                    putJsonObject("info") { putJsonObject("source") { put("client", "WEBPLAYER") } }
                })
            }
        }
        val rootlist = "$SPCLIENT_ROOTLIST/$username/rootlist/changes"
        val changed = postJson(rootlist, rootlistChange, headers)
        check(!changed.str("revision").isNullOrBlank()) { "Spotify created the playlist but did not confirm adding it to your library" }
        SpotifyPlaylist(id, name.trim(), null, null)
    }

    /** Add Spotify track IDs to a playlist in bounded, non-retried mutation batches. */
    suspend fun addTracks(playlistId: String, trackIds: List<String>) = withContext(Dispatchers.IO) {
        require(playlistId.isNotBlank())
        val ids = trackIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return@withContext
        val headers = authHeaders()
        val hash = operationHash("addToPlaylist") ?: addToPlaylistHash
            ?: error("Spotify no longer exposes its playlist-write operation in the web player")
        addToPlaylistHash = hash
        for (batch in ids.chunked(100)) {
            val variables = buildJsonObject {
                putJsonArray("playlistItemUris") { batch.forEach { add("spotify:track:$it") } }
                put("playlistUri", "spotify:playlist:$playlistId")
                putJsonObject("newPosition") {
                    put("moveType", "BOTTOM_OF_PLAYLIST")
                    put("fromUid", JsonNull)
                }
            }
            val response = gqlMutation("addToPlaylist", hash, variables, headers)
            val result = response.obj("data")?.obj("addItemsToPlaylist")
            check(result?.str("__typename") == "AddItemsToPlaylistPayload") {
                "Spotify did not confirm adding tracks to the playlist"
            }
        }
    }

    /** Save matched Spotify tracks into the connected account's Liked Songs. */
    suspend fun saveTracks(trackIds: List<String>) = withContext(Dispatchers.IO) {
        val ids = trackIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return@withContext
        val headers = authHeaders()
        val hash = operationHash("addToLibrary") ?: addToLibraryHash
            ?: error("Spotify no longer exposes its library-write operation in the web player")
        addToLibraryHash = hash
        for (batch in ids.chunked(50)) {
            val variables = buildJsonObject {
                putJsonArray("libraryItemUris") { batch.forEach { add("spotify:track:$it") } }
                put("interactionId", java.util.UUID.randomUUID().toString())
            }
            val response = gqlMutation("addToLibrary", hash, variables, headers)
            val result = response.obj("data")?.obj("addLibraryItems")
            check(result?.str("__typename") == "AddLibraryItemsResponse") {
                "Spotify did not confirm saving tracks to Liked Songs"
            }
        }
    }

    /** Every playlist in the library, however many pages it takes. */
    suspend fun playlists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val (items, total, raw) = playlistPage(headers, offset)
            collected += items
            offset += raw
            if (raw == 0 || offset >= total) break
        }
        // Liked Songs is not a playlist to Spotify's library query, so it is
        // always offered first rather than depending on it turning up there.
        listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + collected
    }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        val collected = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total, raw) = if (playlistId == LIKED_ID) {
                likedPage(headers, offset)
            } else {
                trackPage(headers, playlistId, offset)
            }
            collected += items
            offset += raw
            onPage(collected.toList())
            if (raw == 0 || offset >= total) break
        }
        collected
    }

    private fun playlistPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", 50)
            put("offset", offset)
            put("flatten", true)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", false)
        }
        return parsePlaylistPage(gql("libraryV3", LIBRARY, variables, headers))
    }

    private fun trackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", 100)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", PLAYLIST, variables, headers))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext LIKED_COVER
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 1)
            put("enableWatchFeedEntrypoint", false)
        }
        val root = gql("fetchPlaylist", PLAYLIST, variables, authHeaders())
        largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
    }

    private fun likedPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", 100)
        }
        return parseLikedPage(gql("fetchLibraryTracks", LIKED, variables, headers))
    }

    private suspend fun authHeaders(): Map<String, String> {
        val tokens = auth?.tokens()
            ?: throw IllegalStateException("Spotify sign-in expired")
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
        }
    }

    private fun currentUserId(headers: Map<String, String>): String {
        val root = getJson("$SPOTIFY_ME", headers)
        return root.str("id")?.takeIf(String::isNotBlank) ?: error("Could not read the connected Spotify account")
    }

    private fun getJson(url: String, headers: Map<String, String>): JsonObject {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val body = Http.client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Spotify request failed (${response.code})")
            payload
        }
        return json.parseToJsonElement(body).jsonObject
    }

    private fun postJson(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        // Playlist creation and library mutations are not safe to replay after an ambiguous
        // transport failure; keep these calls strictly single-attempt.
        val client = Http.client.newBuilder().retryOnConnectionFailure(false).build()
        val responseBody = client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Spotify write failed (${response.code})")
            payload
        }
        return json.parseToJsonElement(responseBody).jsonObject
    }

    private fun gqlMutation(
        operation: String,
        hash: String,
        variables: JsonObject,
        headers: Map<String, String>,
    ): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val request = Request.Builder().url(GQL)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val client = Http.client.newBuilder().retryOnConnectionFailure(false).build()
        val text = client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Spotify $operation failed (${response.code})")
            payload
        }
        val root = json.parseToJsonElement(text).jsonObject
        val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
        if (!error.isNullOrBlank()) error("Spotify $operation failed: $error")
        return root
    }

    /** Resolve write-operation hashes from the current web-player scripts, cached per operation. */
    private fun operationHash(operation: String): String? {
        val cached = when (operation) {
            "addToPlaylist" -> addToPlaylistHash
            "addToLibrary" -> addToLibraryHash
            else -> null
        }
        if (cached != null) return cached
        val html = getJsonText("https://open.spotify.com/") ?: return null
        val scripts = SpotifyCanvasQuery.webPlayerScripts(html).take(8)
        val declaration = Regex("""[\"']${Regex.escape(operation)}[\"']\s*,\s*[\"']mutation[\"']\s*,\s*[\"']([0-9a-f]{64})[\"']""")
        for (script in scripts) {
            val source = getJsonText(script) ?: continue
            declaration.find(source)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun getJsonText(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        Http.client.newCall(request).execute().use { response ->
            response.body?.string()?.takeIf { response.isSuccessful && it.isNotBlank() }
        }
    }.getOrNull()

    private fun gql(operation: String, hash: String, variables: JsonObject, headers: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val request = Request.Builder()
            .url(GQL)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val text = Http.client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Spotify $operation failed (${response.code})")
            }
            payload
        }
        val root = json.parseToJsonElement(text).jsonObject
        val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return root
    }
}

/** Deliberately require both the track title and a credited artist to agree before exporting. */
internal fun spotifyTrackMatches(candidate: SpotifyTrack, title: String, artist: String): Boolean {
    val wantedTitle = title.spotifyMatchKey()
    val actualTitle = candidate.title.spotifyMatchKey()
    val wantedArtists = Regex("[,;&/·]|\\s+featuring\\s+|\\s+feat\\.\\s+", RegexOption.IGNORE_CASE)
        .split(artist)
        .map(String::spotifyMatchKey).filter(String::isNotBlank)
    val actualArtists = candidate.artist.split(',', '&', ';', '/')
        .map(String::spotifyMatchKey).filter(String::isNotBlank)
    return wantedTitle.isNotBlank() && actualTitle == wantedTitle &&
        wantedArtists.isNotEmpty() && wantedArtists.any { wanted -> actualArtists.any { got -> got == wanted } }
}

private fun String.spotifyMatchKey(): String = lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
        ?.mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
