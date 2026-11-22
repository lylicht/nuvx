//! Quran-first content preference ("Worship mode").
//!
//! YouTube Music builds the Home feed and autoplay radio from the signed-in
//! account's watch history, and the innertube API offers no server-side way to
//! ask for worship-only content. Worship mode therefore filters on the client:
//! explicit tracks are dropped and only items that look like Quran recitation,
//! dua, adhkar or Islamic talks are kept. The same keyword lists are mirrored
//! on every platform (Android/iOS Kotlin, desktop Rust, web TypeScript).

use crate::model::{Shelf, ShelfKind};

const ARABIC_TERMS: &[&str] = &[
    "قرآن", "قران", "سورة", "سوره", "تلاوة", "تلاوه", "تجويد", "ترتيل",
    "دعاء", "أدعية", "ادعيه", "أذكار", "اذكار", "تسبيح", "رقية",
    "حديث", "تفسير", "محاضرة", "محاضره", "خطبة", "خطب", "دروس", "سيرة",
    "فتوى", "فتاوى", "أذان", "اذان", "رمضان", "الشيخ", "قارئ",
];

const LATIN_TERMS: &[&str] = &[
    "quran", "qur'an", "qoran", "koran", "surah", "surat", "juz", "ayah", "aya",
    "recitation", "reciter", "tilawat", "tajweed", "tarteel",
    "sheikh", "shaykh", "sheik", "islam", "islamic", "muslim", "dua", "du'a",
    "adhkar", "azkar", "dhikr", "zikr", "tasbih", "ruqyah",
    "hadith", "tafsir", "tafseer", "khutbah", "khutba", "dars", "lecture",
    "seerah", "sira", "adhan", "azan", "ramadan", "fatwa",
];

pub fn is_worship(title: &str, artist: &str) -> bool {
    let text = format!("{} {}", title.to_lowercase(), artist.to_lowercase());
    ARABIC_TERMS.iter().any(|t| text.contains(t))
        || LATIN_TERMS
            .iter()
            .any(|t| text.split(|c: char| !c.is_alphanumeric()).any(|w| w == *t))
}

/// In worship mode: drop explicit tracks and keep worship items only. Off: pass through.
pub fn keep(explicit: bool, worship_on: bool, title: &str, artist: &str) -> bool {
    !worship_on || (!explicit && is_worship(title, artist))
}

/// In worship mode: drop filtered items and any shelf left empty. Off: pass through.
pub fn filter_shelves(shelves: Vec<Shelf>, worship_on: bool) -> Vec<Shelf> {
    if !worship_on {
        return shelves;
    }
    shelves
        .into_iter()
        .filter_map(|mut shelf| {
            match &mut shelf.kind {
                ShelfKind::Songs { tracks, .. } => tracks.retain(|t| is_worship(&t.title, &t.artist_line())),
                ShelfKind::Cards(cards) => cards.retain(|c| is_worship(&c.title, &c.subtitle)),
            }
            let empty = match &shelf.kind {
                ShelfKind::Songs { tracks, .. } => tracks.is_empty(),
                ShelfKind::Cards(cards) => cards.is_empty(),
            };
            if empty { None } else { Some(shelf) }
        })
        .collect()
}

/// Search query used to rebuild the queue when a radio mix has no worship items.
pub fn fallback_query(seed_title: Option<&str>, seed_artist: Option<&str>) -> String {
    match seed_artist {
        Some(artist) if !artist.is_empty() && is_worship(seed_title.unwrap_or(""), artist) => artist.to_owned(),
        _ => "قرآن كريم".to_owned(),
    }
}
