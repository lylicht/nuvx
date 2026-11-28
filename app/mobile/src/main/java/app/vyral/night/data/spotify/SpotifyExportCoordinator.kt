package app.vyral.night.data.spotify

import app.vyral.night.data.YtMusicRepository
import app.vyral.night.data.model.Song
import app.vyral.night.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A fully resolved preview. Spotify is not changed until [export] is called. */
data class SpotifyExportPreview(
    val playlists: List<SpotifyExportPlaylist>,
    val likedTracks: List<SpotifyTrack>,
    val monthlyReplay: List<SpotifyExportPlaylist>,
    val unmatchedTracks: Int,
    val warnings: List<String>,
) {
    val matchedTracks: Int get() = playlists.sumOf { it.tracks.size } + likedTracks.size + monthlyReplay.sumOf { it.tracks.size }
    val writes: Int get() = playlists.count { it.tracks.isNotEmpty() } + monthlyReplay.count { it.tracks.isNotEmpty() } + if (likedTracks.isNotEmpty()) 1 else 0
}

data class SpotifyExportPlaylist(
    val name: String,
    val tracks: List<SpotifyTrack>,
)

data class SpotifyExportResult(
    val playlistsCreated: Int,
    val likedTracksSaved: Int,
)

/** Builds a complete local preview, then performs only explicit user-requested Spotify writes. */
object SpotifyExportCoordinator {
    private data class SourceList(val name: String, val songs: List<Song>)

    suspend fun prepare(): SpotifyExportPreview = withContext(Dispatchers.IO) {
        val sources = mutableListOf<SourceList>()
        val warnings = mutableListOf<String>()
        val userPlaylists = YtMusicRepository.userPlaylists().getOrElse {
            warnings += "YouTube Music playlists were unavailable: ${it.message ?: "sign-in required"}"
            emptyList()
        }
        userPlaylists.forEach { playlist ->
            YtMusicRepository.playlistEntries(playlist.browseId)
                .onSuccess { songs -> sources += SourceList("VYRAL · ${playlist.title}", songs) }
                .onFailure { warnings += "Could not read '${playlist.title}': ${it.message ?: "request failed"}" }
        }

        LocalPlaylistStore.playlists.value.forEach { playlist ->
            sources += SourceList("VYRAL · ${playlist.title}", playlist.songs)
        }

        val liked = YtMusicRepository.playlistEntries(YtMusicRepository.LIKED_MUSIC).getOrElse {
            warnings += "YouTube Music Liked Music was unavailable: ${it.message ?: "sign-in required"}"
            emptyList()
        }
        val buckets = ListeningStats.exportAll()
        val history = buckets.map { bucket ->
            SourceList(
                "VYRAL Replay · ${bucket.month}",
                bucket.tracks.sortedWith(compareByDescending<app.vyral.night.data.stats.TrackEntry> { it.last }
                    .thenByDescending { it.plays }
                    .thenByDescending { it.ms })
                    .map { entry ->
                        Song(
                            videoId = entry.id,
                            title = entry.title,
                            artist = entry.artist,
                            thumbnailUrl = entry.art,
                            albumName = entry.album,
                        )
                    },
            )
        }

        val allSongs = (sources.flatMap { it.songs } + liked + history.flatMap { it.songs })
            .filterNot { it.isVideo || it.isVideoOrigin }
        val keys = allSongs.distinctBy(::songKey)
        val semaphore = Semaphore(3)
        val resolvedByKey = ConcurrentHashMap<String, SpotifyTrack>()
        val unmatched = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            keys.map { song ->
                async {
                    val key = songKey(song)
                    val match = semaphore.withPermit {
                        SpotifyLibrary.findTrack(song.title, song.artist, song.albumName)
                    }
                    if (match == null) unmatched += key else resolvedByKey[key] = match
                }
            }.awaitAll()
        }
        fun resolve(songs: List<Song>) = songs.mapNotNull { resolvedByKey[songKey(it)] }
        SpotifyExportPreview(
            playlists = sources.map { SpotifyExportPlaylist(it.name, resolve(it.songs)) },
            likedTracks = resolve(liked).distinctBy { it.id },
            monthlyReplay = history.map { SpotifyExportPlaylist(it.name, resolve(it.songs).distinctBy { track -> track.id }) },
            unmatchedTracks = unmatched.size,
            warnings = warnings,
        )
    }

    suspend fun export(preview: SpotifyExportPreview): SpotifyExportResult {
        var created = 0
        preview.playlists.filter { it.tracks.isNotEmpty() }.forEach { playlist ->
            val destination = SpotifyLibrary.createPlaylist(playlist.name)
            SpotifyLibrary.addTracks(destination.id, playlist.tracks.map { it.id })
            created++
        }
        preview.monthlyReplay.filter { it.tracks.isNotEmpty() }.forEach { replay ->
            val destination = SpotifyLibrary.createPlaylist(replay.name)
            SpotifyLibrary.addTracks(destination.id, replay.tracks.map { it.id })
            created++
        }
        if (preview.likedTracks.isNotEmpty()) SpotifyLibrary.saveTracks(preview.likedTracks.map { it.id })
        return SpotifyExportResult(created, preview.likedTracks.size)
    }

    private fun songKey(song: Song): String = listOf(song.title, song.artist, song.albumName.orEmpty())
        .joinToString("\u0000") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
}
