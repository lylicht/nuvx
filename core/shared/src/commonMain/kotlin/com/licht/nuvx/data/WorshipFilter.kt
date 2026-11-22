// SPDX-License-Identifier: AGPL-3.0-or-later

package com.licht.nuvx.data

import com.licht.nuvx.data.model.HomeShelf
import com.licht.nuvx.data.model.Song

/**
 * Quran-first content preference ("Worship mode").
 *
 * YouTube Music builds the Home feed and autoplay radio from the signed-in
 * account's watch history, and the innertube API offers no server-side way to
 * ask for worship-only content. Worship mode therefore filters on the client:
 * explicit tracks are dropped and only items that look like Quran recitation,
 * dua, adhkar or Islamic talks are kept. The same keyword lists are mirrored
 * on every platform (Android/iOS Kotlin, desktop Rust, web TypeScript).
 */
object WorshipFilter {

    private val ARABIC_TERMS = listOf(
        "قرآن", "قران", "سورة", "سوره", "تلاوة", "تلاوه", "تجويد", "ترتيل",
        "دعاء", "أدعية", "ادعيه", "أذكار", "اذكار", "تسبيح", "رقية",
        "حديث", "تفسير", "محاضرة", "محاضره", "خطبة", "خطب", "دروس", "سيرة",
        "فتوى", "فتاوى", "أذان", "اذان", "رمضان", "الشيخ", "قارئ",
    )

    private val LATIN_TERMS = Regex(
        "\\b(quran|qur'an|qoran|koran|surah|surat|juz|ayah|aya|recitation|reciter|tilawat|tajweed|tarteel|" +
            "sheikh|shaykh|sheik|islam|islamic|muslim|dua|du'a|adhkar|azkar|dhikr|zikr|tasbih|ruqyah|" +
            "hadith|tafsir|tafseer|khutbah|khutba|dars|lecture|seerah|sira|adhan|azan|ramadan|fatwa)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isWorship(title: String, artist: String?): Boolean {
        val text = if (artist.isNullOrBlank()) title else "$title $artist"
        return LATIN_TERMS.containsMatchIn(text) || ARABIC_TERMS.any { it in text }
    }

    /** In worship mode: drop explicit tracks and keep worship items only. Off: pass through. */
    fun filterSongs(songs: List<Song>, worshipOnly: Boolean): List<Song> {
        if (!worshipOnly) return songs
        return songs.filter { it.isExplicit != true && isWorship(it.title, it.artist) }
    }

    /** In worship mode: drop filtered items and any shelf left empty. Off: pass through. */
    fun filterShelves(shelves: List<HomeShelf>, worshipOnly: Boolean): List<HomeShelf> {
        if (!worshipOnly) return shelves
        return shelves.mapNotNull { shelf ->
            val items = shelf.items.filter { it.isExplicit != true && isWorship(it.title, it.subtitle) }
            if (items.isEmpty()) null else shelf.copy(items = items)
        }
    }

    /** Search query used to rebuild the queue when a radio mix has no worship items. */
    fun fallbackQuery(seedTitle: String?, seedArtist: String?): String =
        seedArtist?.takeIf { it.isNotBlank() && isWorship(seedTitle.orEmpty(), it) } ?: "قرآن كريم"
}
