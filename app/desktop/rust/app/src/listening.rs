//! What you played on this computer, by month, for the recap cards at the top of Library (the desktop side of
//! Android's Replay cards). Kept in `listening.json` in the data folder; never sent anywhere.

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};

use serde::{Deserialize, Serialize};

use crate::model::{Link, Thumb, Track};

const FILE: &str = "listening.json";

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Entry {
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub artist_id: Option<String>,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default)]
    pub art: Option<String>,
    pub ms: u64,
    pub plays: u32,
}

/// "YYYY-MM" -> video id -> what was played that month.
type Months = BTreeMap<String, BTreeMap<String, Entry>>;

struct State {
    file: PathBuf,
    months: Months,
    version: u64,
    cache: Option<(u64, String, Vec<Recap>)>,
}

static STATE: LazyLock<Mutex<State>> = LazyLock::new(|| {
    Mutex::new(State {
        file: PathBuf::new(),
        months: Months::new(),
        version: 0,
        cache: None,
    })
});

pub fn init(data_dir: &Path) {
    let file = data_dir.join(FILE);
    let months = std::fs::read(&file)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let mut s = STATE.lock().unwrap();
    s.file = file;
    s.months = months;
    s.version += 1;
}

/// Counts one play of [t] that lasted [ms], in the month of [now_secs].
pub fn record(t: &Track, ms: u64, now_secs: u64) {
    let month = month_of(now_secs);
    let mut s = STATE.lock().unwrap();
    let e = s
        .months
        .entry(month)
        .or_default()
        .entry(t.id.clone())
        .or_default();
    e.title = t.title.clone();
    e.artist = t.artist_line();
    e.artist_id = t.artists.first().and_then(|a| a.id.clone());
    e.album = t.album.as_ref().map(|a| a.name.clone());
    e.art = t.thumbs.first().map(|th| th.url.clone()).or(e.art.take());
    e.ms += ms;
    e.plays += 1;
    s.version += 1;
    if s.file.as_os_str().is_empty() {
        return;
    }
    if let Ok(text) = serde_json::to_vec(&s.months) {
        let tmp = s.file.with_extension("json.tmp");
        // Write then rename, so a crash mid-write never loses the history.
        if std::fs::write(&tmp, text).is_ok() {
            let _ = std::fs::rename(&tmp, &s.file);
        }
    }
}

/// One recap card: a period and what was played in it.
#[derive(Clone, Debug, PartialEq)]
pub struct Recap {
    pub label: &'static str,
    pub ms: u64,
    pub plays: u32,
    /// Most listened first, matching Android Replay's song ranking.
    pub top: Vec<(String, Entry)>,
    pub top_artist: Option<String>,
    pub top_artists: Vec<(String, u64)>,
    pub top_artist_plays: u32,
    pub top_album: Option<String>,
    pub top_albums: Vec<(String, u64)>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ReplayPage {
    Minutes,
    Songs,
    Artists,
    Albums,
    Summary,
}

#[derive(Clone, Debug)]
pub struct ReplayCard {
    pub label: String,
    pub value: String,
    pub detail: String,
    pub art: Option<String>,
    pub page: ReplayPage,
}

impl Recap {
    pub fn tracks(&self, n: usize) -> Vec<Track> {
        self.top
            .iter()
            .take(n)
            .map(|(id, e)| track_of(id, e))
            .collect()
    }
}

/// The recap cards for today, newest period first; periods with nothing played are left out.
pub fn recaps(now_secs: u64) -> Vec<Recap> {
    let month = month_of(now_secs);
    let mut s = STATE.lock().unwrap();
    if let Some((v, m, r)) = &s.cache
        && *v == s.version
        && *m == month
    {
        return r.clone();
    }
    let r = recaps_for(&s.months, &month);
    s.cache = Some((s.version, month, r.clone()));
    r
}

/// Hero cards use the same current-year highlights as Android Replay.
pub fn replay_cards(now_secs: u64) -> Vec<ReplayCard> {
    let month = month_of(now_secs);
    let year = &month[..4];
    let s = STATE.lock().unwrap();
    let keys: Vec<_> = s.months.keys().filter(|k| k.starts_with(year)).collect();
    if keys.is_empty() {
        return Vec::new();
    }
    let recap = summarize("This year", keys.iter().map(|k| &s.months[*k]));
    if recap.plays == 0 {
        return Vec::new();
    }
    let song = recap.top.first().map(|(_, entry)| entry);
    let mut cards = vec![ReplayCard {
        label: "Minutes listened".into(),
        value: format!("{}", recap.ms / 60_000),
        detail: format!("{} plays · {}", recap.plays, year),
        art: song.and_then(|e| e.art.clone()),
        page: ReplayPage::Minutes,
    }];
    if let Some((_, entry)) = recap.top.first() {
        cards.push(ReplayCard {
            label: "Top song".into(),
            value: entry.title.clone(),
            detail: format!("{} · {} plays", entry.artist, entry.plays),
            art: entry.art.clone(),
            page: ReplayPage::Songs,
        });
    }
    if let Some((artist, ms)) = recap.top_artists.first() {
        cards.push(ReplayCard {
            label: "Top artist".into(),
            value: artist.clone(),
            detail: format!("{} min · {} plays", ms / 60_000, recap.top_artist_plays),
            art: song.and_then(|e| e.art.clone()),
            page: ReplayPage::Artists,
        });
    }
    if let Some((album, ms)) = recap.top_albums.first() {
        cards.push(ReplayCard {
            label: "Top album".into(),
            value: album.clone(),
            detail: format!("{} min listened", ms / 60_000),
            art: song.and_then(|e| e.art.clone()),
            page: ReplayPage::Albums,
        });
    }
    cards
}

pub fn replay_summary(now_secs: u64) -> Option<Recap> {
    let month = month_of(now_secs);
    let year = &month[..4];
    let s = STATE.lock().unwrap();
    let keys: Vec<_> = s.months.keys().filter(|k| k.starts_with(year)).collect();
    (!keys.is_empty()).then(|| summarize("This year", keys.iter().map(|k| &s.months[*k])))
}

/// Monthly replay lists used by the explicit Spotify export preview.
pub fn export_by_month() -> Vec<(String, Vec<Track>)> {
    let s = STATE.lock().unwrap();
    s.months
        .iter()
        .map(|(month, entries)| {
            let mut tracks: Vec<_> = entries.iter().collect();
            tracks.sort_by(|(a_id, a), (b_id, b)| {
                b.plays
                    .cmp(&a.plays)
                    .then(b.ms.cmp(&a.ms))
                    .then(a_id.cmp(b_id))
            });
            (
                format!("VYRAL Replay · {month}"),
                tracks
                    .into_iter()
                    .map(|(id, entry)| track_of(id, entry))
                    .collect(),
            )
        })
        .collect()
}

pub fn year_label(now_secs: u64) -> String {
    month_of(now_secs)[..4].to_owned()
}

fn recaps_for(months: &Months, month: &str) -> Vec<Recap> {
    let year: i32 = month[..4].parse().unwrap_or(1970);
    let m: u32 = month[5..7].parse().unwrap_or(1);
    let last_month = if m == 1 {
        format!("{}-12", year - 1)
    } else {
        format!("{year}-{:02}", m - 1)
    };
    let (this_year, last_year) = (format!("{year}-"), format!("{}-", year - 1));
    type Keep<'a> = Box<dyn Fn(&str) -> bool + 'a>;
    let periods: [(&'static str, Keep<'_>); 5] = [
        ("This month", Box::new(|k: &str| k == month)),
        ("Last month", Box::new(move |k: &str| k == last_month)),
        (
            "This year",
            Box::new(move |k: &str| k.starts_with(&this_year)),
        ),
        (
            "Last year",
            Box::new(move |k: &str| k.starts_with(&last_year)),
        ),
        ("All time", Box::new(|_: &str| true)),
    ];
    let mut out: Vec<Recap> = Vec::new();
    let mut seen: Vec<Vec<&String>> = Vec::new();
    for (label, keep) in periods {
        let keys: Vec<&String> = months.keys().filter(|k| keep(k)).collect();
        // A period covering exactly the months of an earlier card (e.g. This year in January) is left out.
        if keys.is_empty() || seen.contains(&keys) {
            continue;
        }
        let r = summarize(label, keys.iter().map(|k| &months[*k]));
        if r.plays > 0 {
            out.push(r);
        }
        seen.push(keys);
    }
    out
}

fn summarize<'a>(
    label: &'static str,
    months: impl Iterator<Item = &'a BTreeMap<String, Entry>>,
) -> Recap {
    let mut tracks: HashMap<&str, Entry> = HashMap::new();
    for month in months {
        for (id, e) in month {
            let t = tracks.entry(id).or_insert_with(|| Entry {
                ms: 0,
                plays: 0,
                ..e.clone()
            });
            t.ms += e.ms;
            t.plays += e.plays;
        }
    }
    let mut artists: HashMap<&str, (u64, u32)> = HashMap::new();
    for e in tracks.values() {
        let artist = artists.entry(e.artist.as_str()).or_default();
        artist.0 += e.ms;
        artist.1 += e.plays;
    }
    let mut ranked_artists: Vec<(String, u64, u32)> = artists
        .into_iter()
        .filter(|(a, _)| !a.is_empty())
        .map(|(a, (ms, plays))| (a.to_owned(), ms, plays))
        .collect();
    ranked_artists.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let top_artist = ranked_artists.first().map(|(name, _, _)| name.clone());
    let top_artist_plays = ranked_artists.first().map_or(0, |(_, _, plays)| *plays);
    let top_artists: Vec<(String, u64)> = ranked_artists
        .into_iter()
        .map(|(name, ms, _)| (name, ms))
        .collect();
    let mut albums: HashMap<&str, u64> = HashMap::new();
    for e in tracks.values() {
        if let Some(album) = e.album.as_deref().filter(|album| !album.is_empty()) {
            *albums.entry(album).or_default() += e.ms;
        }
    }
    let mut top_albums: Vec<(String, u64)> = albums
        .into_iter()
        .map(|(name, ms)| (name.to_owned(), ms))
        .collect();
    top_albums.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let top_album = top_albums.first().map(|(name, _)| name.clone());
    let mut top: Vec<(String, Entry)> = tracks
        .into_iter()
        .map(|(id, e)| (id.to_owned(), e))
        .collect();
    top.sort_by(|a, b| {
        b.1.ms
            .cmp(&a.1.ms)
            .then(b.1.plays.cmp(&a.1.plays))
            .then(a.0.cmp(&b.0))
    });
    Recap {
        label,
        ms: top.iter().map(|t| t.1.ms).sum(),
        plays: top.iter().map(|t| t.1.plays).sum(),
        top,
        top_artist,
        top_artists,
        top_artist_plays,
        top_album,
        top_albums,
    }
}

fn track_of(id: &str, e: &Entry) -> Track {
    let (w, h) = match &e.art {
        Some(u) if u.contains("ytimg.com/vi/") => (480, 360),
        _ => (544, 544),
    };
    Track {
        id: id.to_owned(),
        title: e.title.clone(),
        artists: if e.artist.is_empty() {
            Vec::new()
        } else {
            vec![Link {
                name: e.artist.clone(),
                id: e.artist_id.clone(),
            }]
        },
        album: e.album.clone().map(|name| Link { name, id: None }),
        duration: None,
        thumbs: e
            .art
            .clone()
            .map(|url| {
                vec![Thumb {
                    url,
                    width: w,
                    height: h,
                }]
            })
            .unwrap_or_default(),
        track_nr: None,
        plays: None,
        music_video: false,
    }
}

// shortcut: months are counted in UTC (no time-zone crate); a play near midnight on the 1st can land in the next month.
fn month_of(now_secs: u64) -> String {
    vyral_core::backup::iso_utc(now_secs)[..7].to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn e(title: &str, artist: &str, ms: u64, plays: u32) -> Entry {
        Entry {
            title: title.into(),
            artist: artist.into(),
            ms,
            plays,
            ..Default::default()
        }
    }

    fn months() -> Months {
        let mut m = Months::new();
        m.insert(
            "2026-11".into(),
            BTreeMap::from([
                ("aaaaaaaaaaa".into(), e("A", "X", 600_000, 3)),
                ("bbbbbbbbbbb".into(), e("B", "Y", 200_000, 1)),
            ]),
        );
        m.insert(
            "2026-10".into(),
            BTreeMap::from([("bbbbbbbbbbb".into(), e("B", "Y", 800_000, 4))]),
        );
        m.insert(
            "2025-03".into(),
            BTreeMap::from([("ccccccccccc".into(), e("C", "Z", 100_000, 1))]),
        );
        m
    }

    #[test]
    fn periods_add_up_and_rank_the_most_listened_first() {
        let r = recaps_for(&months(), "2026-11");
        assert_eq!(
            r.iter().map(|r| r.label).collect::<Vec<_>>(),
            [
                "This month",
                "Last month",
                "This year",
                "Last year",
                "All time"
            ]
        );
        assert_eq!(
            (r[0].plays, r[0].ms, r[0].top[0].1.title.as_str()),
            (4, 800_000, "A")
        );
        assert_eq!(r[0].top_artist.as_deref(), Some("X"));
        assert_eq!(
            r[0].top_artists.first().map(|(name, _)| name.as_str()),
            Some("X")
        );
        assert_eq!(
            (
                r[2].plays,
                r[2].top[0].1.title.as_str(),
                r[2].top_artist.as_deref()
            ),
            (8, "B", Some("Y"))
        );
        assert_eq!(r[3].plays, 1);
        assert_eq!(r[4].plays, 9);
    }

    #[test]
    fn january_looks_back_at_december_and_drops_repeats() {
        let mut m = Months::new();
        m.insert(
            "2027-01".into(),
            BTreeMap::from([("aaaaaaaaaaa".into(), e("A", "X", 1_000, 1))]),
        );
        m.insert(
            "2026-12".into(),
            BTreeMap::from([("bbbbbbbbbbb".into(), e("B", "Y", 2_000, 2))]),
        );
        let r = recaps_for(&m, "2027-01");
        // In January, This year is This month and Last year is Last month, so both are left out.
        assert_eq!(
            r.iter().map(|r| r.label).collect::<Vec<_>>(),
            ["This month", "Last month", "All time"]
        );
        assert_eq!(r[1].top[0].1.title, "B");
    }

    #[test]
    fn nothing_played_means_no_cards() {
        assert!(recaps_for(&Months::new(), "2026-11").is_empty());
    }
}
