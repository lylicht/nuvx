package com.licht.nuvx.playback

import com.licht.nuvx.data.ContentFilter
import com.licht.nuvx.data.YtMusicRepository
import com.licht.nuvx.data.listentogether.ListenTogether
import com.licht.nuvx.data.model.SearchFilter
import com.licht.nuvx.data.model.SearchResult
import com.licht.nuvx.data.model.QueueTier
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.settings.AppSettings
import com.licht.nuvx.data.stats.ListeningStats
import com.licht.nuvx.data.sources.SourceRegistry
import com.licht.nuvx.data.sources.TrackMatcher
import com.licht.nuvx.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException

const val MAX_QUEUED_AUTOPLAY = 10

fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): Result<List<Song>> {
    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    val videos = seedSong.isVideo && seed == seedSong.videoId && !MusicOnlyPlayback.isActive()
    var related = YtMusicRepository.radio(seed, preferVideo = videos).getOrElse { return Result.failure(it) }
    val hideExplicit = AppSettings.hideExplicit.value
    val focused = AppSettings.focusedFeed.value
    if (hideExplicit || focused) {
        val profile =
            if (focused) ListeningStats.tasteProfile(listOf(seedSong.title to seedSong.artist))
            else ContentFilter.EMPTY_PROFILE
        related = ContentFilter.filterSongs(related, hideExplicit, focused, profile)
        if (focused && related.isEmpty()) related = onTopicSearch(seedSong, hideExplicit, profile)
    }
    val extra = QueueBuilder.extend(existing, related, limit, preferVideos = videos)
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}

/**
 * On-topic fallback: when the radio mix seeded by [seedSong] has nothing that
 * matches the user's taste profile (YouTube blends the account's wider history
 * into it), rebuild the queue from a search for more like the seed instead.
 */
private suspend fun onTopicSearch(
    seedSong: Song,
    hideExplicit: Boolean,
    profile: ContentFilter.TasteProfile,
): List<Song> {
    val query = ContentFilter.fallbackQuery(seedSong.title, seedSong.artist) ?: return emptyList()
    val results = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() ?: return emptyList()
    return ContentFilter.filterSongs(
        results.filterIsInstance<SearchResult.Track>().map { it.song },
        hideExplicit = hideExplicit, focused = true, profile = profile,
    )
}
