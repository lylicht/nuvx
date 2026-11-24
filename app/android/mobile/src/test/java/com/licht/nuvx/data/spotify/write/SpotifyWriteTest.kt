package com.licht.nuvx.data.spotify.write

import com.licht.nuvx.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyWriteTest {

    private lateinit var server: MockWebServer
    private val originalBase = SpotifyWrite.apiBase
    private val originalTokenProvider = SpotifyWrite.tokenProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        SpotifyWrite.apiBase = server.url("/v1").toString()
        SpotifyWrite.tokenProvider = { "test-token" }
    }

    @After
    fun tearDown() {
        SpotifyWrite.apiBase = originalBase
        SpotifyWrite.tokenProvider = originalTokenProvider
        server.shutdown()
    }

    @Test
    fun validatesBase62Ids() {
        assertTrue(SpotifyWrite.isValidTrackId("abc123XYZ"))
        assertFalse(SpotifyWrite.isValidTrackId("abc-123"))
        assertFalse(SpotifyWrite.isValidTrackId(""))
        assertFalse(SpotifyWrite.isValidTrackId("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun resolvesDirectSpotifyIds() {
        fun song(videoId: String) = Song(videoId, "Title", "Artist", null)

        assertEquals("abc123", SpotifyWrite.directTrackId(song("sp:abc123")))
        assertEquals("def456", SpotifyWrite.directTrackId(song("sp-miss:def456")))
        assertEquals("ghi789", SpotifyWrite.directTrackId(song("spotify:track:ghi789")))
        assertNull(SpotifyWrite.directTrackId(song("youtube-id")))
    }

    @Test
    fun buildsPlaylistUriBody() {
        val body = Json.parseToJsonElement(
            SpotifyWrite.playlistRequestBody(listOf("t1", "bad-id", "t2")),
        ).jsonObject
        assertEquals(
            listOf("spotify:track:t1", "spotify:track:t2"),
            body["uris"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun addsToPlaylistWithPost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertEquals(2, SpotifyWrite.addToPlaylist("p1", listOf("t1", "t2")))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/playlists/p1/tracks", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            listOf("spotify:track:t1", "spotify:track:t2"),
            body["uris"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun savesLikedTracksWithIdsQueryAndEmptyJsonBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        assertEquals(2, SpotifyWrite.saveTracks(listOf("t1", "t2")))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/me/tracks", request.path)
        assertEquals("t1,t2", request.requestUrl?.queryParameter("ids"))
        assertEquals("{}", request.body.readUtf8())
    }
}
