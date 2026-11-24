//! Client-side content controls for Home and autoplay.
//!
//! YouTube Music builds the Home feed and autoplay radio from the signed-in
//! account's watch history, and the innertube API offers no server-side way to
//! steer recommendations. These filters therefore run on the client:
//!
//! - "Hide explicit content" drops anything carrying YouTube's explicit badge.
//!   The badge is only parsed on the autoplay/radio path; home shelves carry
//!   no explicit flag on desktop yet.
//! - "Keep recommendations on-topic" keeps items that match a taste profile
//!   built from the user's own YouTube Music history (the artists they listen
//!   to and the vocabulary in those titles) plus, for autoplay, the seed
//!   track. A Quran listener keeps getting recitations, a podcast listener
//!   stays on their shows, and so on. It is a heuristic over titles and
//!   artist names, not a content classifier: it can keep an off-topic item
//!   with familiar wording and drop an on-topic item phrased differently.
//!   With no listening history the profile is empty and the topical filter
//!   is a no-op.
//!
//! The same rules are mirrored on every platform (Android/iOS Kotlin, desktop
//! Rust, web TypeScript).

use std::collections::HashSet;

use crate::model::{Shelf, ShelfKind, Track};

const MIN_TOKEN_LEN: usize = 4;
const MAX_ARTISTS: usize = 40;
const MAX_TOKENS: usize = 120;

/// Words too generic to say anything about a topic ("Song (Official Video)").
const STOP_TOKENS: &[&str] = &[
    "official", "video", "lyric", "lyrics", "audio", "remix", "cover", "live",
    "feat", "featuring", "instrumental", "karaoke", "version", "clip", "full",
    "album", "single", "music", "song", "track", "episode",
    "فيديو", "كليب", "اغنية", "أغنية",
];

#[derive(Clone, Default)]
pub struct TasteProfile {
    artists: HashSet<String>,
    tokens: HashSet<String>,
}

impl TasteProfile {
    pub fn is_empty(&self) -> bool {
        self.artists.is_empty() && self.tokens.is_empty()
    }

    /// Builds a taste profile from recent plays as (title, artist) pairs.
    pub fn from_plays(plays: impl IntoIterator<Item = (String, String)>) -> Self {
        let mut profile = Self::default();
        for (title, artist) in plays {
            let name = normalize(&artist);
            if !name.is_empty() && profile.artists.len() < MAX_ARTISTS {
                profile.artists.insert(name);
            }
            for token in tokens_of(&format!("{title} {artist}")) {
                if profile.tokens.len() >= MAX_TOKENS {
                    break;
                }
                profile.tokens.insert(token);
            }
        }
        profile
    }

    pub fn from_tracks(tracks: &[Track]) -> Self {
        Self::from_plays(tracks.iter().map(|t| (t.title.clone(), t.artist_line())))
    }

    /// Returns a copy additionally containing one play (e.g. the autoplay seed).
    pub fn plus(&self, title: &str, artist: &str) -> Self {
        let mut profile = self.clone();
        let name = normalize(artist);
        if !name.is_empty() {
            profile.artists.insert(name);
        }
        profile.tokens.extend(tokens_of(&format!("{title} {artist}")));
        profile
    }
}

fn normalize(text: &str) -> String {
    text.to_lowercase().split_whitespace().collect::<Vec<_>>().join(" ")
}

fn tokens_of(text: &str) -> HashSet<String> {
    text.to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|t| t.chars().count() >= MIN_TOKEN_LEN && !STOP_TOKENS.contains(&t))
        .map(str::to_owned)
        .collect()
}

/// True when an item shares the profile's artists or title vocabulary.
pub fn matches_profile(title: &str, artist: &str, profile: &TasteProfile) -> bool {
    let name = normalize(artist);
    if !name.is_empty() {
        for known in &profile.artists {
            if *known == name
                || (known.chars().count() >= MIN_TOKEN_LEN && name.contains(known.as_str()))
                || (name.chars().count() >= MIN_TOKEN_LEN && known.contains(name.as_str()))
            {
                return true;
            }
        }
    }
    tokens_of(&format!("{title} {artist}")).iter().any(|t| profile.tokens.contains(t))
}

/// Applies the two content controls. An empty profile disables topical filtering.
pub fn keep(explicit: bool, hide_explicit: bool, focused: bool, title: &str, artist: &str, profile: &TasteProfile) -> bool {
    if hide_explicit && explicit {
        return false;
    }
    if focused && !profile.is_empty() && !matches_profile(title, artist, profile) {
        return false;
    }
    true
}

/// Drops filtered items and any shelf left empty. Both filters off: pass through.
pub fn filter_shelves(shelves: Vec<Shelf>, hide_explicit: bool, focused: bool, profile: &TasteProfile) -> Vec<Shelf> {
    if !hide_explicit && !focused {
        return shelves;
    }
    shelves
        .into_iter()
        .filter_map(|mut shelf| {
            match &mut shelf.kind {
                // Home shelves carry no explicit flag on desktop yet; see the module docs.
                ShelfKind::Songs { tracks, .. } => {
                    tracks.retain(|t| keep(false, hide_explicit, focused, &t.title, &t.artist_line(), profile))
                }
                ShelfKind::Cards(cards) => cards.retain(|c| keep(false, hide_explicit, focused, &c.title, &c.subtitle, profile)),
            }
            let empty = match &shelf.kind {
                ShelfKind::Songs { tracks, .. } => tracks.is_empty(),
                ShelfKind::Cards(cards) => cards.is_empty(),
            };
            if empty { None } else { Some(shelf) }
        })
        .collect()
}

/// Search query used to rebuild the queue when a radio mix has nothing on-topic: more like the seed itself.
pub fn fallback_query(seed_title: Option<&str>, seed_artist: Option<&str>) -> Option<String> {
    let query = [seed_artist, seed_title]
        .into_iter()
        .flatten()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    if query.is_empty() { None } else { Some(query) }
}
