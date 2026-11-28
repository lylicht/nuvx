//! Desktop-owned local library, independent of managed downloads.
//! IDs never encode a usable path: playback only opens files registered by a bounded scan.
use std::collections::HashSet;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::model::{Link, Track};
use crate::stream::{Format, Shared};

pub const PREFIX: &str = "vyral-library:";
const MAX_BYTES: u64 = 256 * 1024 * 1024;
const MAX_ENTRIES: usize = 5_000;
const MAX_VISITED: usize = 50_000;
const MAX_DEPTH: usize = 4;
static LIBRARY: OnceLock<Arc<Library>> = OnceLock::new();

pub fn is_local(id: &str) -> bool {
    id.starts_with(PREFIX) || id.starts_with(crate::downloads::LOCAL_PREFIX)
}

pub fn favorites() -> HashSet<String> {
    library()
        .map(|library| library.config().favorites)
        .unwrap_or_default()
}

pub fn set_favorites(ids: impl IntoIterator<Item = String>, liked: bool) -> anyhow::Result<()> {
    let library =
        library().ok_or_else(|| anyhow::anyhow!("Local music has not been initialized"))?;
    set_favorites_for(library, ids, liked)
}

fn set_favorites_for(
    library: &Library,
    ids: impl IntoIterator<Item = String>,
    liked: bool,
) -> anyhow::Result<()> {
    let mut config = library.config();
    for id in ids {
        if is_local(&id) {
            if liked {
                config.favorites.insert(id);
            } else {
                config.favorites.remove(&id);
            }
        }
    }
    library.configure(config)
}

pub fn pick_folder(ctx: &egui::Context) {
    let Some(library) = library().cloned() else {
        return;
    };
    let ctx = ctx.clone();
    std::thread::spawn(move || {
        let current = library.config().folders.first().cloned();
        let mut picker = rfd::FileDialog::new().set_title("Local music folder");
        if let Some(path) = current.as_deref() {
            picker = picker.set_directory(path);
        }
        if let Some(path) = picker.pick_folder() {
            let mut config = library.config();
            config.folders = vec![path];
            if let Err(error) = library.configure(config) {
                log::warn!("could not save local music folder: {error:#}");
            } else {
                library.refresh(ctx.clone());
            }
            ctx.request_repaint();
        }
    });
}

pub fn use_default_folders(ctx: &egui::Context) {
    if let Some(library) = library() {
        let mut config = library.config();
        config.folders.clear();
        if let Err(error) = library.configure(config) {
            log::warn!("could not restore default local music folders: {error:#}");
        } else {
            library.refresh(ctx.clone());
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Sort {
    #[default]
    Title,
    TitleDescending,
    DateAdded,
    DateModified,
}

impl Sort {
    pub const ALL: [Self; 4] = [
        Self::Title,
        Self::TitleDescending,
        Self::DateAdded,
        Self::DateModified,
    ];
    pub fn label(self) -> &'static str {
        match self {
            Self::Title => "Title A–Z",
            Self::TitleDescending => "Title Z–A",
            Self::DateAdded => "Date added",
            Self::DateModified => "Date modified",
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    /// Empty means the operating system's Music and Downloads folders.
    pub folders: Vec<PathBuf>,
    pub filter_non_music: bool,
    pub sort: Sort,
    pub grid: bool,
    /// Local favorites stay on this device and never require a catalogue account.
    pub favorites: HashSet<String>,
}

#[derive(Clone)]
pub struct Entry {
    pub track: Track,
    pub path: PathBuf,
    pub added: SystemTime,
    pub modified: SystemTime,
}

#[derive(Clone, Default)]
pub struct Snapshot {
    pub entries: Arc<Vec<Entry>>,
    pub scanning: bool,
    pub truncated: bool,
    pub unreadable: usize,
}

pub struct Library {
    file: PathBuf,
    config: RwLock<Config>,
    snapshot: RwLock<Snapshot>,
    revision: AtomicU64,
}

pub fn init(data: &Path, ctx: &egui::Context) {
    let library = LIBRARY.get_or_init(|| Arc::new(Library::load(data.join("local-library.json"))));
    library.refresh(ctx.clone());
}

pub fn library() -> Option<&'static Arc<Library>> {
    LIBRARY.get()
}

pub fn default_folders() -> Vec<PathBuf> {
    let Some(user) = directories::UserDirs::new() else {
        return Vec::new();
    };
    vec![
        user.audio_dir()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| user.home_dir().join("Music")),
        user.download_dir()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| user.home_dir().join("Downloads")),
    ]
}

impl Library {
    fn load(file: PathBuf) -> Self {
        let config = std::fs::read(&file)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Self {
            file,
            config: RwLock::new(config),
            snapshot: RwLock::new(Snapshot::default()),
            revision: AtomicU64::new(0),
        }
    }

    pub fn config(&self) -> Config {
        self.config.read().unwrap().clone()
    }
    pub fn snapshot(&self) -> Snapshot {
        self.snapshot.read().unwrap().clone()
    }

    pub fn configure(&self, config: Config) -> anyhow::Result<()> {
        let parent = self
            .file
            .parent()
            .ok_or_else(|| anyhow::anyhow!("No settings directory"))?;
        std::fs::create_dir_all(parent)?;
        let temporary = parent.join(format!(
            ".local-library-{}-{}.tmp",
            std::process::id(),
            SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos()
        ));
        let write = || -> anyhow::Result<()> {
            use std::io::Write;
            let mut file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)?;
            file.write_all(&serde_json::to_vec_pretty(&config)?)?;
            file.sync_all()?;
            replace_config_file(&temporary, &self.file)?;
            Ok(())
        };
        let result = write();
        let _ = std::fs::remove_file(&temporary); // this operation's own temporary file only
        result?;
        *self.config.write().unwrap() = config;
        Ok(())
    }

    pub fn refresh(self: &Arc<Self>, ctx: egui::Context) {
        let revision = self.revision.fetch_add(1, Ordering::SeqCst) + 1;
        self.snapshot.write().unwrap().scanning = true;
        let library = self.clone();
        let config = self.config();
        std::thread::spawn(move || {
            let roots = if config.folders.is_empty() {
                default_folders()
            } else {
                config.folders.clone()
            };
            let found = scan(&roots, config.filter_non_music, || {
                library.revision.load(Ordering::SeqCst) != revision
            });
            // Hold the snapshot lock while checking the revision; a later scan must never be overwritten.
            let mut snapshot = library.snapshot.write().unwrap();
            if library.revision.load(Ordering::SeqCst) == revision {
                *snapshot = found;
            }
            drop(snapshot);
            ctx.request_repaint();
        });
    }

    pub fn entry(&self, id: &str) -> Option<Entry> {
        self.snapshot
            .read()
            .unwrap()
            .entries
            .iter()
            .find(|e| e.track.id == id)
            .cloned()
    }

    pub fn open(&self, id: &str) -> anyhow::Result<Arc<Shared>> {
        let entry = self
            .entry(id)
            .ok_or_else(|| anyhow::anyhow!("Local song is missing; refresh Local music"))?;
        open_entry(&entry)
    }
}

fn replace_config_file(temporary: &Path, target: &Path) -> std::io::Result<()> {
    #[cfg(windows)]
    if target.exists() {
        // std::fs::rename cannot replace an existing destination on Windows. Keep a same-directory
        // recovery copy until the new settings are in place so a failed replace never loses them.
        let backup = temporary.with_extension("previous");
        std::fs::rename(target, &backup)?;
        match std::fs::rename(temporary, target) {
            Ok(()) => {
                let _ = std::fs::remove_file(backup);
                return Ok(());
            }
            Err(error) => {
                let _ = std::fs::rename(&backup, target);
                return Err(error);
            }
        }
    }
    std::fs::rename(temporary, target)
}

pub fn favorite_tracks() -> Vec<Track> {
    let Some(library) = library() else {
        return Vec::new();
    };
    let favorite_ids = library.config().favorites;
    library
        .snapshot()
        .entries
        .iter()
        .filter(|entry| favorite_ids.contains(&entry.track.id))
        .map(|entry| entry.track.clone())
        .collect()
}

pub fn open(id: &str) -> anyhow::Result<Arc<Shared>> {
    library()
        .ok_or_else(|| anyhow::anyhow!("Local music has not been initialized"))?
        .open(id)
}

pub fn sidecar(id: &str) -> Option<String> {
    let entry = library()?.entry(id)?;
    read_sidecar(&entry.path)
}

fn read_sidecar(path: &Path) -> Option<String> {
    let path = path.with_extension("lrc");
    let meta = std::fs::symlink_metadata(&path).ok()?;
    if !meta.is_file() || is_link(&meta) || meta.len() > 1024 * 1024 {
        return None;
    }
    let mut text = String::new();
    std::fs::File::open(path)
        .ok()?
        .take(1024 * 1024 + 1)
        .read_to_string(&mut text)
        .ok()?;
    (text.len() <= 1024 * 1024).then(|| text.trim_start_matches('\u{feff}').to_owned())
}

pub fn format(path: &Path) -> Option<Format> {
    let (hint, mime) = match path.extension()?.to_str()?.to_ascii_lowercase().as_str() {
        "mp3" => ("mp3", "audio/mpeg"),
        "m4a" | "mp4" => ("m4a", "audio/mp4"),
        "aac" => ("aac", "audio/aac"),
        "flac" => ("flac", "audio/flac"),
        "ogg" => ("ogg", "audio/ogg"),
        "wav" => ("wav", "audio/wav"),
        "webm" => return Some(Format::Opus),
        _ => return None,
    };
    Some(Format::File {
        hint,
        mime: Some(mime.into()),
    })
}

fn is_link(meta: &std::fs::Metadata) -> bool {
    if meta.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if meta.file_attributes() & 0x400 != 0 {
            return true;
        } // includes junctions
    }
    false
}

fn music_candidate(path: &Path, bytes: u64) -> bool {
    if bytes < 240 * 1024
        || path
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("wav"))
    {
        return false;
    }
    let name = path.to_string_lossy().replace('\\', "/").to_lowercase();
    ![
        "/ringtones/",
        "/notifications/",
        "/alarms/",
        "/ui/",
        "/recordings/",
        "/voice recorder/",
        "voice note",
        "voicenote",
        "recording",
        "ringtone",
        "notification",
        "alarm",
    ]
    .iter()
    .any(|marker| name.contains(marker))
}

fn filename(path: &Path) -> (String, String) {
    let name = path.file_stem().unwrap_or_default().to_string_lossy();
    match name.split_once(" - ") {
        Some((artist, title)) if !artist.trim().is_empty() && !title.trim().is_empty() => {
            (title.trim().to_owned(), artist.trim().to_owned())
        }
        _ => (name.into_owned(), "On This Computer".into()),
    }
}

fn identity(path: &Path) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let digest = Sha256::digest(path.as_os_str().as_encoded_bytes());
    let mut id = String::with_capacity(PREFIX.len() + digest.len() * 2);
    id.push_str(PREFIX);
    for byte in digest {
        id.push(HEX[(byte >> 4) as usize] as char);
        id.push(HEX[(byte & 0x0f) as usize] as char);
    }
    id
}

fn scan(roots: &[PathBuf], filter: bool, cancelled: impl Fn() -> bool) -> Snapshot {
    let mut found = Vec::new();
    let mut seen = HashSet::new();
    let mut visited_dirs = HashSet::new();
    let mut pending: Vec<_> = roots.iter().cloned().map(|p| (p, 0)).collect();
    let mut visited = 0;
    let mut unreadable = 0;
    let mut truncated = false;
    let managed_downloads: HashSet<PathBuf> = crate::downloads::list()
        .iter()
        .filter_map(|track| crate::downloads::path(&track.id))
        .filter_map(|path| path.canonicalize().ok())
        .collect();
    'scan: while let Some((directory, depth)) = pending.pop() {
        if cancelled() {
            break;
        }
        let Ok(meta) = std::fs::symlink_metadata(&directory) else {
            unreadable += 1;
            continue;
        };
        if is_link(&meta) || !meta.is_dir() {
            continue;
        }
        let Ok(canonical) = directory.canonicalize() else {
            unreadable += 1;
            continue;
        };
        if !visited_dirs.insert(canonical.clone()) {
            continue;
        }
        let Ok(entries) = std::fs::read_dir(canonical) else {
            unreadable += 1;
            continue;
        };
        for file in entries {
            if cancelled() {
                break 'scan;
            }
            visited += 1;
            if visited > MAX_VISITED || found.len() >= MAX_ENTRIES {
                truncated = true;
                break 'scan;
            }
            let Ok(file) = file else {
                unreadable += 1;
                continue;
            };
            let path = file.path();
            let Ok(meta) = std::fs::symlink_metadata(&path) else {
                unreadable += 1;
                continue;
            };
            if is_link(&meta) {
                continue;
            }
            if meta.is_dir() {
                if depth < MAX_DEPTH {
                    pending.push((path, depth + 1));
                }
                continue;
            }
            if !meta.is_file()
                || meta.len() == 0
                || meta.len() > MAX_BYTES
                || format(&path).is_none()
                || (filter && !music_candidate(&path, meta.len()))
            {
                continue;
            }
            let Ok(path) = path.canonicalize() else {
                continue;
            };
            if managed_downloads.contains(&path) {
                continue;
            }
            if !seen.insert(path.clone()) {
                continue;
            }
            let (title, artist) = filename(&path);
            let modified = meta.modified().unwrap_or(UNIX_EPOCH);
            found.push(Entry {
                track: Track {
                    id: identity(&path),
                    title,
                    artists: vec![Link {
                        name: artist,
                        id: None,
                    }],
                    album: None,
                    duration: None,
                    thumbs: Vec::new(),
                    track_nr: None,
                    plays: None,
                    music_video: false,
                },
                path,
                added: meta.created().unwrap_or(modified),
                modified,
            });
        }
    }
    Snapshot {
        entries: Arc::new(found),
        scanning: false,
        truncated,
        unreadable,
    }
}

pub fn sorted(entries: &[Entry], sort: Sort, query: &str) -> Vec<Entry> {
    let query = query.trim().to_lowercase();
    let mut entries: Vec<_> = entries
        .iter()
        .filter(|e| {
            query.is_empty()
                || e.track.title.to_lowercase().contains(&query)
                || e.track.artist_line().to_lowercase().contains(&query)
        })
        .cloned()
        .collect();
    entries.sort_by(|a, b| {
        let title = || {
            a.track
                .title
                .to_lowercase()
                .cmp(&b.track.title.to_lowercase())
        };
        match sort {
            Sort::Title => title(),
            Sort::TitleDescending => title().reverse(),
            Sort::DateAdded => b.added.cmp(&a.added).then_with(title),
            Sort::DateModified => b.modified.cmp(&a.modified).then_with(title),
        }
        .then(a.track.id.cmp(&b.track.id))
    });
    entries
}

fn open_entry(entry: &Entry) -> anyhow::Result<Arc<Shared>> {
    let meta = std::fs::symlink_metadata(&entry.path)?;
    anyhow::ensure!(
        meta.is_file() && !is_link(&meta) && meta.len() > 0 && meta.len() <= MAX_BYTES,
        "Local audio file is missing, linked, empty or larger than 256 MB"
    );
    let format = format(&entry.path).ok_or_else(|| anyhow::anyhow!("Unsupported local format"))?;
    let mut bytes = Vec::new();
    std::fs::File::open(&entry.path)?
        .take(MAX_BYTES + 1)
        .read_to_end(&mut bytes)?;
    anyhow::ensure!(
        !bytes.is_empty() && bytes.len() as u64 <= MAX_BYTES,
        "Local audio exceeds the size limit"
    );
    let shared = Shared::from_bytes_with_format(bytes, 0, None, format.clone(), None);
    let decoder = crate::stream::decoder(shared, true)?;
    let duration_ms = rodio::Source::total_duration(&decoder).map_or(0, |d| d.as_millis() as u64);
    // Cache the real duration for the playback clock, not an invented filename-based estimate.
    let mut bytes = Vec::new();
    std::fs::File::open(&entry.path)?
        .take(MAX_BYTES + 1)
        .read_to_end(&mut bytes)?;
    anyhow::ensure!(
        !bytes.is_empty() && bytes.len() as u64 <= MAX_BYTES,
        "Local audio exceeds the size limit"
    );
    Ok(Shared::from_bytes_with_format(
        bytes,
        duration_ms,
        None,
        format,
        None,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Temp(PathBuf);
    impl Temp {
        fn new() -> Self {
            let path = std::env::temp_dir().join("opencode").join(format!(
                "vyral-local-{}-{}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            std::fs::create_dir_all(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn wav() -> Vec<u8> {
        let rate = 8_000u32;
        let samples = rate;
        let bytes = samples * 2;
        let mut data = Vec::new();
        data.extend(b"RIFF");
        data.extend((36 + bytes).to_le_bytes());
        data.extend(b"WAVEfmt ");
        data.extend(16u32.to_le_bytes());
        data.extend(1u16.to_le_bytes());
        data.extend(1u16.to_le_bytes());
        data.extend(rate.to_le_bytes());
        data.extend((rate * 2).to_le_bytes());
        data.extend(2u16.to_le_bytes());
        data.extend(16u16.to_le_bytes());
        data.extend(b"data");
        data.extend(bytes.to_le_bytes());
        for i in 0..samples {
            data.extend((((i % 64) as i16 - 32) * 500).to_le_bytes());
        }
        data
    }

    #[test]
    fn scans_independent_roots_deduplicates_and_parses_persian_filenames() {
        let temp = Temp::new();
        let folder = temp.0.join("album");
        std::fs::create_dir(&folder).unwrap();
        std::fs::write(folder.join("night - آهنگ.wav"), wav()).unwrap();
        std::fs::write(folder.join("ignore.txt"), b"not music").unwrap();
        let result = scan(&[folder, temp.0.clone()], false, || false);
        assert_eq!(result.entries.len(), 1);
        assert_eq!(result.entries[0].track.title, "آهنگ");
        assert_eq!(result.entries[0].track.artist_line(), "night");
        let id = result.entries[0].track.id.clone();
        let rescanned = scan(std::slice::from_ref(&temp.0), false, || false);
        assert_eq!(rescanned.entries[0].track.id, id);
        assert!(id.starts_with(PREFIX));
        assert!(!result.entries[0].track.id.contains("album"));
        assert!(!result.truncated);
    }

    #[test]
    fn matches_kmp_filter_and_handles_windows_path_separators() {
        assert!(!music_candidate(
            Path::new(r"C:\Music\Ringtones\sound.mp3"),
            400_000
        ));
        assert!(!music_candidate(Path::new("Music/song.mp3"), 1_000));
        assert!(!music_candidate(Path::new("Music/song.WAV"), 400_000));
        assert!(music_candidate(
            Path::new("Music/night - song.FLAC"),
            400_000
        ));
        for name in [
            "a.aac", "a.flac", "a.m4a", "a.mp3", "a.ogg", "a.wav", "a.webm",
        ] {
            assert!(format(Path::new(name)).is_some(), "{name}");
        }
        assert!(format(Path::new("video.exe")).is_none());
    }

    #[test]
    fn settings_round_trip_without_touching_download_settings() {
        let temp = Temp::new();
        let library = Library::load(temp.0.join("local-library.json"));
        let config = Config {
            folders: vec![temp.0.join("chosen")],
            filter_non_music: true,
            sort: Sort::DateModified,
            grid: true,
            favorites: HashSet::new(),
        };
        library.configure(config.clone()).unwrap();
        let local_id = identity(Path::new("library/artist/song.flac"));
        set_favorites_for(&library, [local_id.clone()], true).unwrap();
        set_favorites_for(&library, ["youtube-video-id".to_owned()], true).unwrap();
        let restored = Library::load(library.file.clone()).config();
        assert_eq!(restored.folders, config.folders);
        assert_eq!(restored.sort, config.sort);
        assert!(restored.grid && restored.filter_non_music);
        assert_eq!(restored.favorites, HashSet::from([local_id.clone()]));
        set_favorites_for(&library, [local_id.clone()], false).unwrap();
        assert!(
            !Library::load(library.file.clone())
                .config()
                .favorites
                .contains(&local_id)
        );
        assert!(!temp.0.join("downloads.json").exists());
    }

    #[test]
    fn actual_wav_plays_seeks_and_keeps_the_original_bytes_and_lrc() {
        use rodio::Source;
        let temp = Temp::new();
        let file = temp.0.join("night - test.wav");
        let original = wav();
        std::fs::write(&file, &original).unwrap();
        std::fs::write(
            file.with_extension("lrc"),
            "\u{feff}[00:00.00]سلام\n[00:00.50]دنیا",
        )
        .unwrap();
        let result = scan(std::slice::from_ref(&temp.0), false, || false);
        let shared = open_entry(&result.entries[0]).unwrap();
        assert_eq!(shared.duration_ms, 1_000);
        let mut decoder = crate::stream::decoder(shared, true).unwrap();
        assert!(decoder.by_ref().take(100).any(|s| s.abs() > 0.01));
        decoder
            .try_seek(std::time::Duration::from_millis(500))
            .unwrap();
        assert!(decoder.next().is_some());
        assert_eq!(std::fs::read(file.clone()).unwrap(), original);
        assert!(read_sidecar(&file).unwrap().starts_with("[00:00.00]سلام"));
        let library = Library::load(temp.0.join("settings.json"));
        assert!(library.open("vyral-library:../../private").is_err());
    }

    #[test]
    fn sorting_and_search_preserve_stable_ids_and_support_added_modified_dates() {
        let temp = Temp::new();
        for name in ["night - Zebra.mp3", "night - Alpha.flac"] {
            std::fs::write(temp.0.join(name), b"fixture").unwrap();
        }
        let result = scan(std::slice::from_ref(&temp.0), false, || false);
        let mut entries = sorted(&result.entries, Sort::Title, "");
        assert_eq!(entries[0].track.title, "Alpha");
        entries[1].added = UNIX_EPOCH + std::time::Duration::from_secs(100);
        entries[0].added = UNIX_EPOCH;
        entries[0].modified = UNIX_EPOCH + std::time::Duration::from_secs(100);
        entries[1].modified = UNIX_EPOCH;
        assert_eq!(
            sorted(&entries, Sort::DateAdded, "")[0].track.title,
            "Zebra"
        );
        assert_eq!(
            sorted(&entries, Sort::DateModified, "")[0].track.title,
            "Alpha"
        );
        assert_eq!(
            sorted(&entries, Sort::TitleDescending, "")[0].track.title,
            "Zebra"
        );
        assert_eq!(sorted(&entries, Sort::Title, "NIGHT").len(), 2);
        assert_eq!(sorted(&entries, Sort::Title, "alpha").len(), 1);
    }
}
