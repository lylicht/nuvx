package com.licht.nuvx.data.innertube

import com.licht.nuvx.data.WorshipFilter
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
     * Worship mode fallback: when the radio mix seeded by [videoId] has no
     * worship items (YouTube blends the account's music history into it),
     * rebuild the queue from a worship search instead.
     */
    private suspend fun worshipSearch(videoId: String, all: List<Song>): List<Song> {
        val seed = all.firstOrNull { it.videoId == videoId }
        val query = WorshipFilter.fallbackQuery(seed?.title, seed?.artist)
        val results = InnertubeParser.parseSearchPage(Innertube.search(query, SearchFilter.SONGS.params))
        return WorshipFilter.filterSongs(
            results.filterIsInstance<SearchResult.Track>().map { it.song },
            worshipOnly = true,
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
                var songs = all
                if (PlatformSettings.getBoolean("worship_mode", false)) {
                    songs = WorshipFilter.filterSongs(all, worshipOnly = true)
                    if (songs.isEmpty()) songs = worshipSearch(videoId, all)
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
