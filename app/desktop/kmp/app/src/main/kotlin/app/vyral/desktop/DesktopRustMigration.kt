package app.vyral.desktop

import app.vyral.night.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * An explicit, portable bridge into Rust's existing backup importer.
 * No credentials, preferences, files, or either application's live storage are moved.
 */
internal object DesktopRustMigration {
    data class Export(val text: String, val playlists: Int, val songs: Int, val unsupported: Int)

    fun encode(playlists: List<DesktopPlaylist>, exportedAt: String): Export {
        var songs = 0
        var unsupported = 0
        require(playlists.size <= 2_000) { "Too many playlists for the portable backup format" }
        val document = buildJsonObject {
            put("app", "vyral")
            put("version", 1)
            put("versionName", DesktopUpdateChecker.currentVersion)
            put("exportedAt", exportedAt)
            put("settings", buildJsonObject {})
            put("listening", buildJsonArray {})
            put("playlists", buildJsonArray {
                playlists.forEach { playlist ->
                    require(playlist.id.isNotBlank() && playlist.id.length <= 190)
                    require(playlist.songs.size <= 20_000)
                    add(buildJsonObject {
                        // Stable namespacing prevents collisions with Rust-created playlists.
                        put("id", "vyral-kmp:${playlist.id}")
                        put("title", playlist.title)
                        put("songs", buildJsonArray {
                            playlist.songs.forEach { song ->
                                if (!portable(song)) {
                                    unsupported++
                                } else {
                                    songs++
                                    add(buildJsonObject {
                                        put("videoId", song.videoId)
                                        put("title", song.title)
                                        put("artist", song.artist)
                                        song.thumbnailUrl?.let { put("thumbnailUrl", it) }
                                        song.durationText?.let { put("durationText", it) }
                                        song.artistId?.let { put("artistId", it) }
                                        song.albumId?.let { put("albumId", it) }
                                        song.albumName?.let { put("albumName", it) }
                                        put("isVideo", song.isVideo)
                                    })
                                }
                            }
                        })
                    })
                }
            })
        }
        val text = Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), document)
        require(text.toByteArray(Charsets.UTF_8).size <= 20 * 1024 * 1024) {
            "The portable backup exceeds Rust's 20 MB import limit"
        }
        return Export(text, playlists.size, songs, unsupported)
    }

    private fun portable(song: Song): Boolean =
        song.localPath.isNullOrBlank() && song.localUri.isNullOrBlank() &&
            song.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))

    /** Write a new file only: neither an existing backup nor either live library is overwritten. */
    fun writeNew(path: Path, export: Export) {
        // A temporary file keeps a failed write from leaving a partial JSON file.
        val absolute = path.toAbsolutePath()
        require(!Files.exists(absolute)) { DesktopStrings["migration_file_exists", "Choose a new filename; existing files are not overwritten."] }
        val temporary = Files.createTempFile(absolute.parent, ".vyral-kmp-export-", ".json")
        try {
            Files.writeString(temporary, export.text)
            // Deliberately no REPLACE_EXISTING. A normal same-directory move is
            // preferable here: ATOMIC_MOVE may replace a racing target on Windows.
            Files.move(temporary, absolute)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
