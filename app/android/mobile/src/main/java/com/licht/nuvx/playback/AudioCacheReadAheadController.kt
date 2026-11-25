package com.licht.nuvx.playback

import android.net.Uri
import com.licht.nuvx.data.TrackLog
import com.licht.nuvx.data.innertube.StreamResolver
import com.licht.nuvx.data.sources.SourceRegistry
import com.licht.nuvx.data.sources.SourceResolver
import com.licht.nuvx.data.sources.SourceStream
import com.licht.nuvx.data.sources.TrackMatcher
import com.licht.nuvx.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class AudioCacheReadAheadController(
    private val scope: CoroutineScope,
    private val pendingQueue: () -> List<String>,
    private val setPendingQueue: (List<String>) -> Unit,
    private val tag: String,
    private val cacheKeyOf: (Uri) -> String,
    private val heldByYouTube: (String?) -> Boolean,
    private val dropYouTubeEntry: (String, String) -> Unit,
    private val fetch: suspend (String, Uri, Long, Long) -> Unit,
    private val fetchWhole: suspend (String) -> Unit,
    private val preloadBytes: Long,
    private val prefetchDelayMs: Long,
    private val queueLookahead: Int,
    private val queueResolveStaggerMs: Long,
) {
    private var job: Job? = null
    private var directJob: Job? = null

    fun prefetchQueue(upcoming: List<AudioCache.Upcoming>) {
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue()) return
        android.util.Log.d(
            "BCFetchDebug",
            "prefetchQueue: head ${pendingQueue().firstOrNull()} -> ${mediaIds.firstOrNull()}",
        )
        setPendingQueue(mediaIds)
        job?.cancel()
        val firstStreamable = upcoming.firstOrNull { item ->
            val id = item.mediaId
            id !in Downloads.saved.value && (
                SourceRegistry.parseTrackKey(id) != null ||
                    (!id.startsWith("content://") && !id.startsWith("file://"))
                )
        }
        val sourceUpcoming = firstStreamable?.takeIf { SourceRegistry.parseTrackKey(it.mediaId) != null }
        if (sourceUpcoming != null) {
            val uri = sourcePlaybackUri(sourceUpcoming)
            job = scope.launch(TrackLog.about(sourceUpcoming.mediaId)) {
                delay(prefetchDelayMs)
                fetch(cacheKeyOf(uri), uri, 0, preloadBytes)
            }
        }
        val videoIds = mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
            .filter { it !in Downloads.saved.value }
        val substitutable = SourceResolver.canSubstituteForYouTube()
        if (sourceUpcoming == null) job = videoIds.firstOrNull()?.let { next ->
            val target = upcoming.firstOrNull { it.mediaId == next }?.target
            val pinnedToYouTube = OriginalVersion.isPinned(next)
            scope.launch {
                val youTubeFilled = heldByYouTube(cacheKeyOf(Uri.parse("nuvx://watch?v=$next")))
                val warmed = if (substitutable && target != null && !pinnedToYouTube && !youTubeFilled) {
                    runCatching { SourceResolver.prefetchSubstitute(target) }
                        .onFailure { TrackLog.d(tag, "warm-up substitute failed for $next: ${it.message}", about = next) }
                        .getOrNull()
                        ?.also { StreamChoice.remember(next, it, substituted = true) }
                } else {
                    null
                }
                val cacheBytes = (!substitutable || warmed != null) && !pinnedToYouTube
                if (cacheBytes) {
                    launch(TrackLog.about(next)) {
                        delay(prefetchDelayMs)
                        fetchWatch(next, 0, preloadBytes)
                        fetchWhole(next)
                    }
                }
                launch {
                    delay(prefetchDelayMs)
                    for (id in videoIds.take(queueLookahead + 1).let { if (cacheBytes) it.drop(1) else it }) {
                        if (id == next && warmed != null) continue
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { TrackLog.d(tag, "queue warm-up skipped $id: ${it.message}", about = id) }
                        delay(queueResolveStaggerMs)
                    }
                }
            }
        }
    }

    fun prefetchDirect(upcoming: AudioCache.Upcoming) {
        directJob?.cancel()
        val id = upcoming.mediaId
        val sourceTrack = SourceRegistry.parseTrackKey(id)
        if (id in Downloads.saved.value || (sourceTrack == null && OriginalVersion.isPinned(id))) {
            directJob = null
            return
        }
        directJob = scope.launch(TrackLog.about(id)) {
            if (sourceTrack != null) {
                val uri = sourcePlaybackUri(upcoming)
                delay(prefetchDelayMs)
                fetch(cacheKeyOf(uri), uri, 0, preloadBytes)
                return@launch
            }
            if (StreamChoice.of(id) == null) {
                val addon = runCatching { DirectSourceGate.lookup(id, upcoming.target) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                val stream = addon ?: runCatching {
                    val url = StreamResolver.resolve(id)
                    SourceStream(url, headers = StreamResolver.mediaHeadersFor(url))
                }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        TrackLog.d(tag, "direct read-ahead could not resolve $id: ${it.message}", about = id)
                    }
                    .getOrNull()
                    ?: return@launch
                if (StreamChoice.of(id) == null) {
                    val key = cacheKeyOf(Uri.parse("nuvx://watch?v=$id"))
                    if (addon != null && heldByYouTube(key)) dropYouTubeEntry(key, id)
                    if (addon != null && heldByYouTube(key)) return@launch
                    StreamChoice.remember(id, stream, substituted = addon != null)
                    if (addon == null) DirectSourceGate.startedOnYouTube(id, upcoming.target)
                }
            }
            fetchWatch(id, 0, preloadBytes)
        }
    }

    fun cancelDirect() {
        directJob?.cancel()
        directJob = null
    }

    fun cancelQueue() {
        job?.cancel()
        job = null
    }

    private fun sourcePlaybackUri(upcoming: AudioCache.Upcoming): Uri {
        val (source, track) = requireNotNull(SourceRegistry.parseTrackKey(upcoming.mediaId))
        val target = upcoming.target
        return Uri.parse(SourceRegistry.trackUri(source, track)).buildUpon()
            .appendQueryParameter("n", target.title)
            .appendQueryParameter("a", target.artist)
            .apply { target.durationSec?.let { appendQueryParameter("d", it.toString()) } }
            .apply { target.album?.let { appendQueryParameter("l", it) } }
            .apply { target.isExplicit?.let { appendQueryParameter("e", if (it) "1" else "0") } }
            .apply { if (target.isVideo) appendQueryParameter("m", "1") }
            .apply {
                appendQueryParameter(
                    SourceResolver.MIX_PARAM,
                    SourceResolver.mixOf(SourceRegistry.config(source)?.pickedQuality),
                )
            }
            .build()
    }

    private suspend fun fetchWatch(videoId: String, position: Long, length: Long) {
        val uri = Uri.parse("nuvx://watch?v=$videoId")
        fetch(cacheKeyOf(uri), uri, position, length)
    }
}
