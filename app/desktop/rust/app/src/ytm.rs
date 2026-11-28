mod album;
mod artist;
mod home;
mod matching;
mod parsing;
mod playlist;
mod search;
pub use artist::artist;
mod radio;
pub use radio::{ServerQueue, radio, server_queue};
#[cfg(test)]
use radio::{parse_server_queue, radio_tracks};
mod library;
use library::item_token;
pub use library::{saved_albums, saved_playlists};
mod accounts;
pub use accounts::{Channel, account, channels};
mod history;
pub use history::{history, uploads};
mod actions;
#[cfg(test)]
use accounts::parse_channels;
#[cfg(test)]
use actions::parse_collaboration;
pub use actions::{
    CollabChange, Collaboration, Watchtime, add_videos, collaboration, create_playlist,
    create_playlist_with, delete_playlist, dislike, edit_playlist, remove_videos, report_play,
    report_watchtime, save_collection, set_collaboration, set_like,
};
pub use album::{album, paired_song, searched_song};
#[cfg(test)]
use album::{album_songs, parse_album, parse_paired_song, swap_songs};
pub use home::{
    HomePage, PODCASTS, explore_home, home, home_more, mood_art, mood_page, moods, podcasts,
};
#[cfg(test)]
use home::{parse_moods, podcast_search_shelf};
#[cfg(test)]
use library::{grid_continuation, grid_next};
pub use matching::{cover_song, video_of};
use matching::{same_song, same_title, song_title, without_artist};
use parsing::*;
#[cfg(test)]
use playlist::parse_playlist;
pub use playlist::{playlist, playlist_more, playlist_start};
use search::search_as;
pub use search::{
    Filter, Suggestion, forget_search, search_cards, search_songs, search_tracks_signed_in,
    suggestions, youtube_search,
};
#[cfg(test)]
use search::{parse_suggestions, youtube_videos};

use serde_json::{Value, json};

use crate::backend::{ArtistPage, Collection};
use crate::model::{
    Card, Chip, Link, Mood, MoodPage, MoodSection, Shelf, ShelfKind, Target, Thumb, Track,
};
use crate::ytmapi::Api;

fn header(two: &Value) -> Option<&Value> {
    let c = &two["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"][0];
    [
        &c["musicResponsiveHeaderRenderer"],
        &c["musicEditablePlaylistDetailHeaderRenderer"]["header"]["musicResponsiveHeaderRenderer"],
    ]
    .into_iter()
    .find(|h| !h.is_null())
}

#[cfg(test)]
mod tests;
