package com.licht.nuvx.data.innertube

import com.licht.nuvx.data.ContentFilter
import com.licht.nuvx.data.model.SearchFilter
import com.licht.nuvx.data.model.SearchResult
import com.licht.nuvx.data.model.Song
import com.licht.nuvx.data.settings.PlatformSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json





object AutoPlayBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val songListSerializer = ListSerializer(Song.serializer())

    fun interface AutoPlayCallback {
        fun onResult(json: String?, message: String?)
    }

    /**
     * On-topic fallback: when the radio mix seeded by [videoId] has nothing
     * that matches the user's taste profile (YouTube blends the account's
     * wider history into it), rebuild the queue from a search for more like
     * the seed instead.
     */
    private suspend fun onTopicSearch(
        videoId: String,
        all: List<Song>,
        hideExplicit: Boolean,
        profile: ContentFilter.TasteProfile,
    ): List<Song> {
        val seed = all.firstOrNull { it.videoId == videoId }
        val query = ContentFilter.fallbackQuery(seed?.title, seed?.artist) ?: return emptyList()
        val results = InnertubeParser.parseSearchPage(Innertube.search(query, SearchFilter.SONGS.params))
        return ContentFilter.filterSongs(
            results.filterIsInstance<SearchResult.Track>().map { it.song },
            hideExplicit = hideExplicit, focused = true, profile = profile,
        )
    }

    fun related(videoId: String, callback: AutoPlayCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                val response = Innertube.next(videoId)
                val all = InnertubeParser.parseWatchQueue(response)
                    .filterNot { it.isVideo }
                val hideExplicit = PlatformSettings.getBoolean("hide_explicit", false)
                val focused = PlatformSettings.getBoolean("focused_feed", false)
                var songs = all
                if (hideExplicit || focused) {
                    val seed = all.firstOrNull { it.videoId == videoId }
                    val profile =
                        if (focused) TasteProfileBridge.profile(seed?.title, seed?.artist)
                        else ContentFilter.EMPTY_PROFILE
                    songs = ContentFilter.filterSongs(all, hideExplicit, focused, profile)
                    if (focused && songs.isEmpty()) songs = onTopicSearch(videoId, all, hideExplicit, profile)
                }
                Innertube.checkSession(generation)
                callback.onResult(
                    json.encodeToString(songListSerializer, songs),
                    null,
                )
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }
}
