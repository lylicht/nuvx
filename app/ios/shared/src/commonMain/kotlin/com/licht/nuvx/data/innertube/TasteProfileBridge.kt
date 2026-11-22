package com.licht.nuvx.data.innertube

import com.licht.nuvx.data.ContentFilter
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Taste profile for "Keep recommendations on-topic", built from the account's
 * YouTube Music history (FEmusic_history) and cached briefly. Failures and
 * signed-out sessions yield an empty profile, which disables topical
 * filtering instead of breaking the feed.
 */
object TasteProfileBridge {

    private const val TTL_MINUTES = 30
    private const val HISTORY_LIMIT = 50

    private var cachedAt: TimeMark? = null
    private var cached: ContentFilter.TasteProfile? = null

    suspend fun profile(seedTitle: String? = null, seedArtist: String? = null): ContentFilter.TasteProfile {
        val at = cachedAt
        val base = if (at != null && at.elapsedNow().inWholeMinutes < TTL_MINUTES) {
            cached
        } else {
            val plays = mutableListOf<Pair<String, String?>>()
            try {
                if (Innertube.cookie != null) {
                    InnertubeParser.collectSongsDeep(Innertube.browse("FEmusic_history"))
                        .distinctBy { it.videoId }
                        .take(HISTORY_LIMIT)
                        .forEach { plays += it.title to it.artist }
                }
            } catch (_: Throwable) {
                // Keep the stale/empty profile: topical filtering degrades to a no-op.
            }
            ContentFilter.buildProfile(plays).also {
                cached = it
                cachedAt = TimeSource.Monotonic.markNow()
            }
        } ?: ContentFilter.EMPTY_PROFILE
        if (seedTitle.isNullOrBlank() && seedArtist.isNullOrBlank()) return base
        val seed = ContentFilter.buildProfile(listOf(seedTitle.orEmpty() to seedArtist))
        return base.copy(artists = base.artists + seed.artists, tokens = base.tokens + seed.tokens)
    }
}
