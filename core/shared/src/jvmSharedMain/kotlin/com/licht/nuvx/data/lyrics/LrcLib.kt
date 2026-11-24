package com.licht.nuvx.data.lyrics

import com.licht.nuvx.core.parseLrcLines
import com.licht.nuvx.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.abs

object LrcLib {

    private const val BASE = "https://lrclib.net/api"
    private const val AGENT = "nuvx (https://github.com/lylicht)"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.clean()
            val cleanArtist = artist.clean()
            val seconds = (durationMs / 1000).toInt()

            val exact = runCatching { exactMatch(cleanTitle, cleanArtist, seconds) }.getOrNull()
            val synced = exact ?: runCatching { bestSearchHit(cleanTitle, cleanArtist, seconds) }
                .getOrNull()
            synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        }

    private fun exactMatch(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("duration", seconds.toString())
            .build()
        val body = get(url.toString()) ?: return null
        return (json.parseToJsonElement(body) as? JsonObject)
            ?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
    }

    private fun bestSearchHit(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val body = get(url.toString()) ?: return null
        val hits = json.parseToJsonElement(body) as? JsonArray ?: return null
        return hits.mapNotNull { it as? JsonObject }
            .filter { it["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
            .minByOrNull {
                val d = it["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                abs(d - seconds)
            }
            ?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    // One LRC parser for every app: nuvx-core (core/contracts/lyrics-lrc.json, lyrics-gaps.json) through nuvx-ffi.
    fun parseLrc(lrc: String): List<LyricLine> = parseLrcLines(lrc).map { it.toLyricLine() }

    private fun String.clean(): String = this
        .replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    private val NOISE = Regex(
        """\((?:from|feat\.?|official|lyrical|video|audio|remix)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
