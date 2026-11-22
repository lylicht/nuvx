package com.licht.nuvx.playback

import com.licht.nuvx.data.WorshipFilter
import com.licht.nuvx.data.YtMusicRepository
import com.licht.nuvx.data.listentogether.ListenTogether
import com.licht.nuvx.data.model.SearchFilter
import com.licht.nuvx.data.model.SearchResult
import com.licht.nuvx.data.model.QueueTier
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.settings.AppSettings
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
    if (AppSettings.worshipMode.value) {
        related = WorshipFilter.filterSongs(related, worshipOnly = true)
        if (related.isEmpty()) related = worshipSearch(seedSong)
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
 * Worship mode fallback: when the radio mix seeded by [seedSong] has no worship
 * items (YouTube blends the account's music history into it), rebuild the queue
 * from a worship search instead.
 */
private suspend fun worshipSearch(seedSong: Song): List<Song> {
    val query = WorshipFilter.fallbackQuery(seedSong.title, seedSong.artist)
    val results = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() ?: return emptyList()
    return WorshipFilter.filterSongs(
        results.filterIsInstance<SearchResult.Track>().map { it.song },
        worshipOnly = true,
    )
}
