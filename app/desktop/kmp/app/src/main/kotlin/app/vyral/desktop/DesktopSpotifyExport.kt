package app.vyral.desktop

import app.vyral.night.data.YtMusicRepository
import app.vyral.night.data.model.Song
import app.vyral.night.data.spotify.SpotifyLibrary
import app.vyral.night.data.spotify.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

data class DesktopSpotifyExportPreview(
    val playlists: List<Pair<String, List<SpotifyTrack>>>,
    val likedTracks: List<SpotifyTrack>,
    val unmatchedTracks: Int,
    val warnings: List<String>,
) {
    val matchedTracks: Int get() = playlists.sumOf { it.second.size } + likedTracks.size
}

/** Uses the desktop's existing YT Music, local playlist, replay, and Spotify stores. */
object DesktopSpotifyExport {
    suspend fun prepare(localPlaylists: List<DesktopPlaylist>): DesktopSpotifyExportPreview {
        val warnings = mutableListOf<String>()
        val sources = mutableListOf<Pair<String, List<Song>>>()
        val userPlaylists = YtMusicRepository.userPlaylists().getOrElse {
            warnings += "YouTube Music playlists unavailable: ${it.message ?: "sign-in required"}"
            emptyList()
        }
        userPlaylists.forEach { playlist ->
            YtMusicRepository.playlistEntries(playlist.browseId)
                .onSuccess { sources += "VYRAL · ${playlist.title}" to it }
                .onFailure { warnings += "Could not read '${playlist.title}': ${it.message ?: "request failed"}" }
        }
        localPlaylists.forEach { sources += "VYRAL · ${it.title}" to it.songs }
        val liked = YtMusicRepository.playlistEntries(YtMusicRepository.LIKED_MUSIC).getOrElse {
            warnings += "YouTube Music Liked Music unavailable: ${it.message ?: "sign-in required"}"
            emptyList()
        }
        val replay = DesktopListeningStats.exportByMonth().map { (month, entries) ->
            "VYRAL Replay · $month" to entries.map(DesktopReplayEntry::song)
        }
        val songs = (sources.flatMap { it.second } + liked + replay.flatMap { it.second })
            .filterNot { it.isVideo || it.isVideoOrigin }
        val distinct = songs.distinctBy(::key)
        val semaphore = Semaphore(3)
        val matches = ConcurrentHashMap<String, SpotifyTrack>()
        val unmatched = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            distinct.map { song ->
                async {
                    val id = key(song)
                    val found = semaphore.withPermit {
                        SpotifyLibrary.findTrack(song.title, song.artist, song.albumName)
                    }
                    if (found == null) unmatched += id else matches[id] = found
                }
            }.awaitAll()
        }
        val exportLists = (sources + replay).map { (title, tracks) ->
            title to tracks.mapNotNull { matches[key(it)] }.distinctBy(SpotifyTrack::id)
        }
        return DesktopSpotifyExportPreview(
            playlists = exportLists,
            likedTracks = liked.mapNotNull { matches[key(it)] }.distinctBy(SpotifyTrack::id),
            unmatchedTracks = unmatched.size,
            warnings = warnings,
        )
    }

    suspend fun export(preview: DesktopSpotifyExportPreview): Pair<Int, Int> {
        var created = 0
        preview.playlists.filter { it.second.isNotEmpty() }.forEach { (name, tracks) ->
            val playlist = SpotifyLibrary.createPlaylist(name)
            SpotifyLibrary.addTracks(playlist.id, tracks.map(SpotifyTrack::id))
            created++
        }
        if (preview.likedTracks.isNotEmpty()) SpotifyLibrary.saveTracks(preview.likedTracks.map(SpotifyTrack::id))
        return created to preview.likedTracks.size
    }

    private fun key(song: Song) = listOf(song.title, song.artist, song.albumName.orEmpty())
        .joinToString("\u0000") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
}
