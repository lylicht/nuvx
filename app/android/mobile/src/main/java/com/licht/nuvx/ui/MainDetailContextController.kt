package com.licht.nuvx.ui

import com.licht.nuvx.R
import com.licht.nuvx.data.YtMusicRepository
import com.licht.nuvx.data.model.DetailPage
import com.licht.nuvx.data.model.DetailLoadId
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.model.UiState
import com.licht.nuvx.playback.BoundedContextRequests
import com.licht.nuvx.playback.contextEntryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class MainDetailContextController(
    private val scope: CoroutineScope,
    private val _detailStack: MutableStateFlow<List<DetailPage>>,
    private val localPageState: suspend (String) -> UiState<List<Song>>?,
    private val withArtwork: (List<Song>, String?) -> List<Song>,
    private val text: (Int) -> String,
) {
    private val fillInJobs = mutableMapOf<DetailLoadId, Job>()

    fun fillIn(detailLoadId: DetailLoadId, browseId: String, token: String, artworkFallback: String?) {
        fillInJobs[detailLoadId]?.cancel()
        fillInJobs[detailLoadId] = scope.launch {
            delay(150)
            var next: String? = token
            while (next != null) {
                val fetched = YtMusicRepository.moreSongs(next).getOrNull() ?: return@launch
                currentCoroutineContext().ensureActive()
                val stack = _detailStack.value
                if (stack.none { it.detailLoadId === detailLoadId && it.browseId == browseId }) return@launch
                var progressed = false
                val updated = stack.map { current ->
                    if (current.detailLoadId !== detailLoadId || current.browseId != browseId) return@map current
                    val existing = (current.songs as? UiState.Success)?.data ?: return@map current
                    val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
                    val added = fetched.songs
                        .filter { known.add(it.contextEntryKey()) }
                        .let { withArtwork(it, artworkFallback) }
                    val knownVideoIds = (existing + added).mapTo(HashSet()) { it.videoId }
                    val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                    val addedSuggested = fetched.suggested
                        .filter { it.videoId !in knownVideoIds && knownSuggested.add(it.videoId) }
                        .let { withArtwork(it, artworkFallback) }
                    if (added.isEmpty() && addedSuggested.isEmpty()) return@map current
                    progressed = true
                    current.copy(
                        songs = UiState.Success(existing + added),
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                        continuation = fetched.continuation,
                    )
                }
                if (!progressed) return@launch
                _detailStack.value = updated
                next = fetched.continuation
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (fillInJobs[detailLoadId] === job) fillInJobs.remove(detailLoadId)
            }
        }
    }

    fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) {
        val detailLoadId = snapshot.request.detailLoadId ?: return
        val stack = _detailStack.value
        val index = stack.indexOfLast(snapshot::canUpdateDetail)
        if (index < 0) return
        val page = stack[index]
        if (snapshot.serviceOwned) {
            if (snapshot.continuationToken != null) fillInJobs.remove(detailLoadId)?.cancel()
        } else if (snapshot.released && snapshot.continuationToken != null &&
            fillInJobs[detailLoadId]?.isActive != true
        ) {
            fillIn(detailLoadId, page.browseId, snapshot.continuationToken, page.thumbnailUrl)
        }
        val existing = (page.songs as? UiState.Success)?.data ?: return
        val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
        val added = snapshot.songs
            .filter { known.add(it.contextEntryKey()) }
            .let { withArtwork(it, page.thumbnailUrl) }
        val continuation = if (snapshot.songs.size >= existing.size) snapshot.continuationToken else page.continuation
        if (added.isEmpty() && page.continuation == continuation) return
        _detailStack.value = stack.toMutableList().also {
            it[index] = page.copy(
                songs = UiState.Success(existing + added),
                continuation = continuation,
            )
        }
    }

    fun takePlaybackContext(page: DetailPage): DetailPage? {
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == page.browseId && it.detailLoadId === page.detailLoadId }
        if (index < 0) return null
        val mirrored = BoundedContextRequests.snapshot.value
            ?.takeIf { it.canUpdateDetail(stack[index]) }
        if (mirrored != null) {
            applyBoundedContextSnapshot(mirrored)
            return _detailStack.value.getOrNull(index)
        }
        return stack[index]
    }

    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        scope.launch {
            val result = when (val local = localPageState(browseId)) {
                is UiState.Success -> Result.success(local.data)
                is UiState.Error -> Result.failure(IllegalStateException(local.message))
                UiState.Loading -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
                null -> YtMusicRepository.allSongs(browseId)
            }
            onResult(result.map { withArtwork(it, artworkFallback) })
        }
    }
}
