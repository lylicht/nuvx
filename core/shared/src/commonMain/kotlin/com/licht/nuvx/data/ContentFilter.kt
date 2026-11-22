// SPDX-License-Identifier: AGPL-3.0-or-later

package com.licht.nuvx.data

import com.licht.nuvx.data.model.HomeShelf
import com.licht.nuvx.data.model.Song

/**
 * Client-side content controls for Home and autoplay.
 *
 * YouTube Music builds the Home feed and autoplay radio from the signed-in
 * account's watch history, and the innertube API offers no server-side way to
 * steer recommendations. These filters therefore run on the client:
 *
 * - "Hide explicit content" drops anything carrying YouTube's explicit badge.
 * - "Keep recommendations on-topic" keeps items that match a taste profile
 *   built from the user's own recent plays (the artists they listen to and
 *   the vocabulary in those titles) plus, for autoplay, the seed track. A
 *   Quran listener keeps getting recitations, a podcast listener stays on
 *   their shows, and so on. It is a heuristic over titles and artist names,
 *   not a content classifier: it can keep an off-topic item with familiar
 *   wording and drop an on-topic item phrased differently. With no listening
 *   history the profile is empty and the topical filter is a no-op.
 *
 * The same rules are mirrored on every platform (Android/iOS Kotlin, desktop
 * Rust, web TypeScript).
 */
object ContentFilter {

    private const val MIN_TOKEN_LENGTH = 4
    private const val MAX_ARTISTS = 40
    private const val MAX_TOKENS = 120

    /** Words too generic to say anything about a topic ("Song (Official Video)"). */
    private val STOP_TOKENS = setOf(
        "official", "video", "lyric", "lyrics", "audio", "remix", "cover", "live",
        "feat", "featuring", "instrumental", "karaoke", "version", "clip", "full",
        "album", "single", "music", "song", "track", "episode",
        "فيديو", "كليب", "اغنية", "أغنية",
    )

    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    data class TasteProfile(
        val artists: Set<String> = emptySet(),
        val tokens: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = artists.isEmpty() && tokens.isEmpty()
    }

    val EMPTY_PROFILE = TasteProfile()

    private fun normalize(text: String): String = text.lowercase().trim().replace(WHITESPACE, " ")

    fun tokensOf(text: String): Set<String> =
        text.lowercase().split(TOKEN_SPLIT)
            .filterTo(mutableSetOf()) { it.length >= MIN_TOKEN_LENGTH && it !in STOP_TOKENS }

    /** Builds a taste profile from recent plays as title/artist pairs. */
    fun buildProfile(items: List<Pair<String, String?>>): TasteProfile {
        val artists = mutableSetOf<String>()
        val tokens = mutableSetOf<String>()
        for ((title, artist) in items) {
            val name = normalize(artist.orEmpty())
            if (name.isNotEmpty() && artists.size < MAX_ARTISTS) artists += name
            for (token in tokensOf("$title ${artist.orEmpty()}")) {
                if (tokens.size >= MAX_TOKENS) break
                tokens += token
            }
        }
        return TasteProfile(artists, tokens)
    }

    /** True when an item shares the profile's artists or title vocabulary. */
    fun matchesProfile(title: String, artist: String?, profile: TasteProfile): Boolean {
        val name = normalize(artist.orEmpty())
        if (name.isNotEmpty() && profile.artists.any { known ->
                known == name ||
                    (known.length >= MIN_TOKEN_LENGTH && known in name) ||
                    (name.length >= MIN_TOKEN_LENGTH && name in known)
            }
        ) return true
        return tokensOf("$title ${artist.orEmpty()}").any { it in profile.tokens }
    }

    /** Applies the two content controls. An empty profile disables topical filtering. */
    fun keep(
        explicit: Boolean?,
        hideExplicit: Boolean,
        focused: Boolean,
        title: String,
        artist: String?,
        profile: TasteProfile,
    ): Boolean {
        if (hideExplicit && explicit == true) return false
        if (focused && !profile.isEmpty && !matchesProfile(title, artist, profile)) return false
        return true
    }

    fun filterSongs(
        songs: List<Song>,
        hideExplicit: Boolean,
        focused: Boolean,
        profile: TasteProfile,
    ): List<Song> {
        if (!hideExplicit && !focused) return songs
        return songs.filter { keep(it.isExplicit, hideExplicit, focused, it.title, it.artist, profile) }
    }

    /** Drops filtered items and any shelf left empty. Both filters off: pass through. */
    fun filterShelves(
        shelves: List<HomeShelf>,
        hideExplicit: Boolean,
        focused: Boolean,
        profile: TasteProfile,
    ): List<HomeShelf> {
        if (!hideExplicit && !focused) return shelves
        return shelves.mapNotNull { shelf ->
            val items = shelf.items.filter { keep(it.isExplicit, hideExplicit, focused, it.title, it.subtitle, profile) }
            if (items.isEmpty()) null else shelf.copy(items = items)
        }
    }

    /** Search query used to rebuild the queue when a radio mix has nothing on-topic: more like the seed itself. */
    fun fallbackQuery(seedTitle: String?, seedArtist: String?): String? =
        listOfNotNull(seedArtist, seedTitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
}
