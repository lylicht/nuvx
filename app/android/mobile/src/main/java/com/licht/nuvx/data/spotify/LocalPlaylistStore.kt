package com.licht.nuvx.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.licht.nuvx.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

/** An earlier state of a nuvx playlist, kept so it can be put back (playlist menu, Earlier versions). */
data class PlaylistVersion(val title: String, val songs: List<Song>, val savedAt: Long)

object LocalPlaylistStore {
    private const val PREF_NAME = "nuvx_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private const val HISTORY_PREFS = "nuvx_playlist_history"
    private const val KEY_HISTORY = "history_json"

    // shortcut: whole song lists per version, 10 per playlist; store diffs if very large playlists make this slow.
    private const val MAX_VERSIONS = 10

    private lateinit var prefs: SharedPreferences
    private var historyPrefs: SharedPreferences? = null

    /** Newest first, per playlist id. */
    private var history: Map<String, List<PlaylistVersion>> = emptyMap()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        historyPrefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        load()
        loadHistory()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    songs.add(songFrom(songsArray.getJSONObject(j)))
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        commit(updated)
        return playlist
    }

    fun createNuvxPlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val playlist = LocalPlaylist(id = "nuvx_local_" + java.util.UUID.randomUUID(), title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value
        commit(updated)
        return playlist
    }

    fun addSong(id: String, song: Song): Boolean {
        val current = getPlaylist(id) ?: return false
        if (current.songs.any { it.videoId == song.videoId }) return false
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = it.songs + song) else it }
        commit(updated)
        return true
    }

    fun removeSong(id: String, videoId: String): Boolean {
        val current = getPlaylist(id) ?: return false
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = it.songs.filterNot { song -> song.videoId == videoId }) else it }
        commit(updated)
        return true
    }

    /** Undo of [removeSong]: puts [song] back at [at] unless it is already there again. */
    fun putBack(id: String, song: Song, at: Int): Boolean {
        val current = getPlaylist(id) ?: return false
        if (current.songs.any { it.videoId == song.videoId }) return false
        val songs = current.songs.toMutableList().apply { add(at.coerceIn(0, size), song) }
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = songs) else it }
        commit(updated)
        return true
    }

    fun replaceSongs(id: String, songs: List<Song>): Boolean {
        val current = getPlaylist(id) ?: return false
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = songs) else it }
        commit(updated)
        return true
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        commit(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        commit(updated)
    }

    /** Puts back a playlist removed by [deletePlaylist] at its old position (undo). */
    fun restore(playlist: LocalPlaylist, at: Int) {
        if (_playlists.value.any { it.id == playlist.id }) return
        val updated = _playlists.value.toMutableList().apply { add(at.coerceIn(0, size), playlist) }
        commit(updated)
    }

    /** Adds restored playlists; one whose id already exists is skipped, never overwritten. Returns how many were added. */
    fun mergeIn(incoming: List<LocalPlaylist>): Int {
        val have = _playlists.value.mapTo(HashSet()) { it.id }
        val added = incoming.filter { it.id.isNotBlank() && have.add(it.id) }
        if (added.isEmpty()) return 0
        val updated = _playlists.value + added
        commit(updated)
        return added.size
    }

    /**
     * Replaces every playlist with [updated] (a sync result), but only if the list is still [expected], so an edit
     * made while the sync ran is never overwritten; the next sync picks it up. Returns false when skipped.
     */
    @Synchronized
    fun replaceAllIfUnchanged(expected: List<LocalPlaylist>, updated: List<LocalPlaylist>): Boolean {
        if (_playlists.value != expected) return false
        if (updated == expected) return true
        commit(updated)
        return true
    }

    /** Earlier states of playlist [id], newest first. */
    fun versions(id: String): List<PlaylistVersion> = getPlaylist(id)?.let { history[it.id] }.orEmpty()

    /**
     * Puts playlist [id] back to `versions(id)[index]`. The state it replaces becomes the newest version, so
     * restoring `versions(id)[0]` right after undoes it. Returns the restored playlist, or null if nothing changed.
     */
    fun restoreVersion(id: String, index: Int): LocalPlaylist? {
        val current = getPlaylist(id) ?: return null
        val version = history[current.id]?.getOrNull(index) ?: return null
        val restored = current.copy(title = version.title, songs = version.songs)
        if (restored == current) return null
        commit(_playlists.value.map { if (it.id == current.id) restored else it })
        return restored
    }

    /** The one place playlists change: keeps the old state of every playlist that changed, then saves. */
    @Synchronized
    private fun commit(updated: List<LocalPlaylist>) {
        val before = _playlists.value.associateBy { it.id }
        val now = System.currentTimeMillis()
        val next = HashMap<String, List<PlaylistVersion>>()
        for (p in updated) {
            val kept = history[p.id].orEmpty()
            val old = before[p.id]
            next[p.id] = if (old == null || (old.title == p.title && old.songs == p.songs)) {
                kept
            } else {
                (listOf(PlaylistVersion(old.title, old.songs, now)) + kept).take(MAX_VERSIONS)
            }
        }
        // Deleted playlists drop their history.
        history = next.filterValues { it.isNotEmpty() }
        _playlists.value = updated
        persist(updated)
        persistHistory()
    }

    private fun loadHistory() {
        val raw = historyPrefs?.getString(KEY_HISTORY, null) ?: return
        runCatching {
            val obj = JSONObject(raw)
            history = obj.keys().asSequence().associateWith { id ->
                val arr = obj.getJSONArray(id)
                (0 until arr.length()).map { i ->
                    val v = arr.getJSONObject(i)
                    val songs = v.getJSONArray("songs")
                    PlaylistVersion(v.optString("title"), (0 until songs.length()).map { songFrom(songs.getJSONObject(it)) }, v.optLong("savedAt"))
                }
            }
        }
    }

    private fun persistHistory() {
        val target = historyPrefs ?: return
        runCatching {
            val obj = JSONObject()
            for ((id, versions) in history) {
                obj.put(id, JSONArray(versions.map { v ->
                    JSONObject().put("title", v.title).put("savedAt", v.savedAt).put("songs", JSONArray(v.songs.map(::songJson)))
                }))
            }
            target.edit().putString(KEY_HISTORY, obj.toString()).apply()
        }
    }

    private fun songJson(song: Song) = JSONObject()
        .put("videoId", song.videoId)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("thumbnailUrl", song.thumbnailUrl)
        .put("durationText", song.durationText)
        .put("artistId", song.artistId)
        .put("albumId", song.albumId)
        .put("albumName", song.albumName)
        .put("isVideo", song.isVideo)

    private fun songFrom(o: JSONObject): Song {
        fun opt(key: String) = o.optString(key, null).takeIf { !it.isNullOrBlank() && it != "null" }
        return Song(
            videoId = o.optString("videoId", ""),
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            thumbnailUrl = opt("thumbnailUrl"),
            durationText = opt("durationText"),
            artistId = opt("artistId"),
            albumId = opt("albumId"),
            albumName = opt("albumName"),
            isVideo = o.optBoolean("isVideo", false),
        )
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    songsArray.put(songJson(song))
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
