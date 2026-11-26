use std::collections::HashMap;
use std::io::Write;
use std::fs::OpenOptions;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, RwLock};

use serde::{Deserialize, Serialize};

use crate::model::Track;
use crate::stream::Shared;

#[derive(Clone, Serialize, Deserialize)]
struct Entry {
    path: PathBuf,
    duration_ms: u64,
    loudness_db: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    stands_for: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    song: Option<Track>,
}

struct State {
    index: HashMap<String, Entry>,
    file: PathBuf,
    folder: PathBuf,
    local: HashMap<String, (PathBuf, Track)>,
}

static STATE: LazyLock<RwLock<State>> =
    LazyLock::new(|| RwLock::new(State { index: HashMap::new(), file: PathBuf::new(), folder: default_folder(), local: HashMap::new() }));

pub fn default_folder() -> PathBuf {
    let base = directories::UserDirs::new()
        .and_then(|u| u.audio_dir().map(Path::to_path_buf).or_else(|| Some(u.home_dir().join("Music"))))
        .unwrap_or_else(|| PathBuf::from("."));
    base.join("nuvx")
}

pub fn init(data_dir: &Path, folder: &str) {
    let file = data_dir.join("downloads.json");
    let index = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    let mut st = STATE.write().unwrap();
    st.index = index;
    st.file = file;
    st.folder = if folder.is_empty() { default_folder() } else { PathBuf::from(folder) };
    drop(st);
    refresh_local(None);
}

pub fn folder() -> PathBuf {
    STATE.read().unwrap().folder.clone()
}

pub fn set_folder(folder: PathBuf) {
    {
        let mut st = STATE.write().unwrap();
        st.folder = folder;
        st.local.clear();
    }
    refresh_local(None);
}

pub fn pick_folder(ctx: &egui::Context) {
    let ctx = ctx.clone();
    std::thread::spawn(move || {
        if let Some(dir) = rfd::FileDialog::new().set_title("Download folder").set_directory(folder()).pick_folder() {
            set_folder(dir);
            refresh_local(Some(ctx.clone()));
            ctx.request_repaint();
        }
    });
}

pub fn open_folder() {
    open_dir(&folder());
}

/// Opens `dir` in the system's file manager, creating it first.
pub fn open_dir(dir: &Path) {
    let _ = std::fs::create_dir_all(dir);
    #[cfg(windows)]
    let _ = std::process::Command::new("explorer").arg(dir).spawn();
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(dir).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let _ = std::process::Command::new("xdg-open").arg(dir).spawn();
}

pub fn show_file(path: &Path) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = std::process::Command::new("explorer").raw_arg(format!("/select,\"{}\"", path.display())).spawn();
    }
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg("-R").arg(path).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let uri = url::Url::from_file_path(path).map(|u| u.to_string()).unwrap_or_default();
        let shown = std::process::Command::new("dbus-send")
            .args(["--session", "--print-reply", "--dest=org.freedesktop.FileManager1", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems"])
            .arg(format!("array:string:{uri}"))
            .arg("string:")
            .output()
            .is_ok_and(|o| o.status.success());
        if !shown && let Some(dir) = path.parent() {
            let _ = std::process::Command::new("xdg-open").arg(dir).spawn();
        }
    }
}

pub fn path(video_id: &str) -> Option<PathBuf> {
    STATE.read().unwrap().index.get(video_id).map(|e| e.path.clone()).filter(|p| p.is_file())
}

pub fn list() -> Vec<Track> {
    let st = STATE.read().unwrap();
    let mut out: Vec<(std::time::SystemTime, Track)> = st
        .index
        .values()
        .filter(|e| e.path.is_file())
        .filter_map(|e| {
            let modified = std::fs::metadata(&e.path).and_then(|m| m.modified()).unwrap_or(std::time::UNIX_EPOCH);
            e.song.clone().map(|t| (modified, t))
        })
        .collect();
    out.sort_by(|a, b| b.0.cmp(&a.0));
    out.into_iter().map(|(_, t)| t).collect()
}

pub const LOCAL_PREFIX: &str = "nuvx-file:";
static SCAN_REV: AtomicU64 = AtomicU64::new(0);

/// Scan only the configured download/music folder, without following symlinks.
/// Keep external files separate from the download index so deleting or syncing downloads never affects them.
pub fn refresh_local(ctx: Option<egui::Context>) {
    let rev = SCAN_REV.fetch_add(1, Ordering::SeqCst) + 1;
    let root = folder();
    std::thread::spawn(move || {
        let known: std::collections::HashSet<_> = STATE.read().unwrap().index.values()
            .filter_map(|e| e.path.canonicalize().ok()).collect();
        let mut found = HashMap::new();
        let mut canonical_seen = std::collections::HashSet::new();
        let mut dirs = vec![(root.clone(), 0u8)];
        let mut visited = 0usize;
        'scan: while let Some((dir, depth)) = dirs.pop() {
            if SCAN_REV.load(Ordering::SeqCst) != rev { return; }
            let Ok(entries) = std::fs::read_dir(dir) else { continue; };
            for item in entries.flatten() {
                visited += 1;
                if visited > 50_000 || found.len() >= 5_000 { break 'scan; }
                let Ok(kind) = item.file_type() else { continue; };
                let path = item.path();
                if kind.is_dir() && depth < 24 { dirs.push((path, depth + 1)); continue; }
                if !kind.is_file() || local_format(&path).is_none() { continue; }
                let Ok(canonical) = path.canonicalize() else { continue; };
                if known.contains(&canonical) || !canonical_seen.insert(canonical.clone()) { continue; }
                use sha2::Digest;
                let digest = sha2::Sha256::digest(canonical.to_string_lossy().as_bytes());
                let id = format!("{LOCAL_PREFIX}{}", digest.iter().map(|b| format!("{b:02x}")).collect::<String>());
                let Some(title) = path.file_stem().map(|s| s.to_string_lossy().into_owned()) else { continue; };
                let track = Track { id: id.clone(), title, artists: Vec::new(), album: None, duration: None, thumbs: Vec::new(), track_nr: None, plays: None, music_video: false };
                found.insert(id, (path, track));
            }
        }
        let mut st = STATE.write().unwrap();
        if st.folder == root && SCAN_REV.load(Ordering::SeqCst) == rev { st.local = found; }
        drop(st);
        if let Some(ctx) = ctx { ctx.request_repaint(); }
    });
}

fn local_format(path: &Path) -> Option<crate::stream::Format> {
    match path.extension()?.to_str()?.to_ascii_lowercase().as_str() {
        "mp3" => Some(crate::stream::Format::File { hint: "mp3", mime: Some("audio/mpeg".into()) }),
        "m4a" => Some(crate::stream::Format::Aac),
        _ => None,
    }
}

pub fn local_groups() -> std::collections::BTreeMap<String, Vec<Track>> {
    let st = STATE.read().unwrap();
    let mut groups: std::collections::BTreeMap<String, Vec<Track>> = Default::default();
    let indexed: std::collections::HashSet<&Path> = st.index.values().map(|e| e.path.as_path()).collect();
    let items = st.index.values().filter(|e| e.path.is_file()).filter_map(|e| e.song.as_ref().map(|t| (&e.path, t)))
        .chain(st.local.values().filter(|(p, _)| !indexed.contains(p.as_path())).map(|(p, t)| (p, t)));
    for (path, track) in items {
        let label = path.parent().and_then(|p| p.strip_prefix(&st.folder).ok())
            .map(|p| if p.as_os_str().is_empty() { "Music folder".to_owned() } else { p.display().to_string() })
            .unwrap_or_else(|| "Other downloads".to_owned());
        groups.entry(label).or_default().push(track.clone());
    }
    for tracks in groups.values_mut() { tracks.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()).then(a.id.cmp(&b.id))); }
    groups
}

pub fn open_local(id: &str) -> anyhow::Result<Arc<Shared>> {
    let path = STATE.read().unwrap().local.get(id).map(|(p, _)| p.clone()).ok_or_else(|| anyhow::anyhow!("Local song is missing; refresh the music folder"))?;
    let format = local_format(&path).ok_or_else(|| anyhow::anyhow!("Unsupported local audio format"))?;
    let meta = std::fs::symlink_metadata(&path)?;
    anyhow::ensure!(meta.is_file() && meta.len() <= 256 * 1024 * 1024, "Local file is unavailable or too large");
    use std::io::Read;
    let hint = match &format { crate::stream::Format::File { hint, .. } => *hint, _ => "m4a" };
    let file = std::fs::File::open(&path)?;
    let duration_ms = rodio::Decoder::builder()
        .with_data(std::io::BufReader::new(file))
        .with_byte_len(meta.len()).with_seekable(true).with_hint(hint).build()
        .ok().and_then(|decoder| rodio::Source::total_duration(&decoder))
        .map_or(0, |duration| duration.as_millis().min(u64::MAX as u128) as u64);
    let mut data = Vec::new();
    std::fs::File::open(path)?.take(256 * 1024 * 1024 + 1).read_to_end(&mut data)?;
    anyhow::ensure!(!data.is_empty() && data.len() <= 256 * 1024 * 1024, "Local file is empty or too large");
    Ok(Shared::from_bytes_with_format(data, duration_ms, None, format, None))
}

pub fn duration_ms(video_id: &str) -> Option<u64> {
    STATE.read().unwrap().index.get(video_id).map(|e| e.duration_ms).filter(|ms| *ms > 0)
}

pub fn song_for(video_id: &str) -> Option<Track> {
    let st = STATE.read().unwrap();
    st.index.values().find(|e| e.stands_for.as_deref() == Some(video_id) && e.path.is_file()).and_then(|e| e.song.clone())
}

pub fn open(video_id: &str) -> Option<Arc<Shared>> {
    let entry = STATE.read().unwrap().index.get(video_id).cloned()?;
    let data = std::fs::read(&entry.path).ok()?;
    log::info!("{video_id}: playing the downloaded file");
    let format = format_of(&data);
    Some(Shared::from_bytes_with_format(data, entry.duration_ms, entry.loudness_db, format, None))
}

/// A WebM (Matroska) file carries Opus; everything else nuvx saves is MP4/AAC. Read from the bytes, not the name,
/// so a song saved as WebM (on this computer or copied from a phone) does not get opened as AAC and fail.
fn format_of(data: &[u8]) -> crate::stream::Format {
    if data.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) { crate::stream::Format::Opus } else { crate::stream::Format::Aac }
}

#[cfg(test)]
#[test]
fn webm_downloads_open_as_opus() {
    assert!(matches!(format_of(&[0x1A, 0x45, 0xDF, 0xA3, 0x9F]), crate::stream::Format::Opus));
    assert!(matches!(format_of(b"    ftypM4A "), crate::stream::Format::Aac));
}

/// Downloads stop while less than this is free on the download drive, like on Android: the system and other
/// apps keep room to work.
const MIN_FREE: u64 = 500 * 1024 * 1024;

/// Optional cap on what downloads may use in total, in GB (0 = no limit), like Android's storage limit.
static LIMIT_GB: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
pub const LIMIT_CHOICES: [u32; 6] = [0, 1, 5, 10, 20, 50];

pub fn limit_gb() -> u32 {
    LIMIT_GB.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn set_limit_gb(gb: u32) {
    LIMIT_GB.store(gb, std::sync::atomic::Ordering::Relaxed);
}

/// Bytes the downloaded songs take now.
pub fn used_bytes() -> u64 {
    STATE.read().unwrap().index.values().filter_map(|e| std::fs::metadata(&e.path).ok()).map(|m| m.len()).sum()
}

fn within_limit(limit_gb: u32, used: u64, size: usize) -> bool {
    limit_gb == 0 || used + size as u64 <= u64::from(limit_gb) * 1024 * 1024 * 1024
}

fn room_for(free: Option<u64>, size: usize) -> bool {
    free.is_none_or(|f| f >= MIN_FREE + size as u64)
}

/// Free bytes on the drive holding `dir`, or `None` if the system cannot tell.
fn free_bytes(dir: &Path) -> Option<u64> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let wide: Vec<u16> = dir.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
        let mut free = 0u64;
        // SAFETY: `wide` is NUL-terminated and outlives the call; `free` is a valid out-pointer.
        unsafe { windows::Win32::Storage::FileSystem::GetDiskFreeSpaceExW(windows::core::PCWSTR(wide.as_ptr()), Some(&mut free), None, None) }.ok()?;
        Some(free)
    }
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let c = std::ffi::CString::new(dir.as_os_str().as_bytes()).ok()?;
        let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
        // SAFETY: `c` is a valid C string and `st` a valid out-pointer for the call.
        (unsafe { libc::statvfs(c.as_ptr(), &mut st) } == 0).then(|| st.f_bavail as u64 * st.f_frsize as u64)
    }
}

pub fn save(t: &Track, audio: &[u8], duration_ms: u64, loudness_db: Option<f32>, cover: Option<Vec<u8>>, stands_for: Option<String>) -> anyhow::Result<PathBuf> {
    let root = folder();
    let mine = STATE.read().unwrap().index.get(&t.id).map(|e| e.path.clone());
    // Preserve existing download paths, even after the user changes the default folder.
    let folder = mine.as_ref().and_then(|p| p.parent()).filter(|p| p.is_dir()).map(Path::to_path_buf)
        .unwrap_or_else(|| root.join(t.artists.first().map(|a| artist_folder(&a.name)).unwrap_or_else(|| "Unknown artist".into())));
    std::fs::create_dir_all(&folder)?;
    if !room_for(free_bytes(&folder), audio.len()) {
        anyhow::bail!("Less than 500 MB free on the download drive");
    }
    if !within_limit(limit_gb(), used_bytes(), audio.len()) {
        anyhow::bail!("Downloads reached the storage limit in Settings → Downloads");
    }
    let ext = if audio.get(4..8) == Some(b"ftyp") { "m4a" } else { "webm" };
    let stem = file_stem(t);
    let (path, part, mut part_file) = {
        let mut n = 1;
        loop {
            let name = if n == 1 { format!("{stem}.{ext}") } else { format!("{stem} ({n}).{ext}") };
            let path = folder.join(name);
            if path.exists() && Some(&path) != mine.as_ref() {
                n += 1;
                continue;
            }
            let part = path.with_extension(format!("{ext}.part"));
            match OpenOptions::new().write(true).create_new(true).open(&part) {
                Ok(file) => break (path, part, file),
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => n += 1,
                Err(e) => return Err(e.into()),
            }
        }
    };
    let mut audio = audio.to_vec();
    if audio.get(8..12) == Some(b"dash") {
        audio[8..12].copy_from_slice(b"M4A ");
    }
    if let Err(e) = part_file.write_all(&audio) {
        let _ = std::fs::remove_file(&part);
        return Err(e.into());
    }
    drop(part_file);
    if ext == "m4a"
        && let Err(e) = tag(&part, t, cover)
    {
        log::warn!("couldn't tag {}: {e:#}", path.display());
    }
    if let Err(e) = std::fs::rename(&part, &path) {
        let _ = std::fs::remove_file(&part);
        return Err(e.into());
    }
    let mut st = STATE.write().unwrap();
    let song = Some(t.clone());
    let previous = st.index.insert(t.id.clone(), Entry { path: path.clone(), duration_ms, loudness_db, stands_for, song });
    let persisted = (|| -> anyhow::Result<()> {
        let json = serde_json::to_vec(&st.index)?;
        write_atomically(&st.file, &json)?;
        Ok(())
    })();
    if let Err(e) = persisted {
        match previous {
            Some(entry) => { st.index.insert(t.id.clone(), entry); }
            None => { st.index.remove(&t.id); }
        }
        if !st.index.get(&t.id).is_some_and(|entry| entry.path.as_path() == path.as_path()) {
            let _ = std::fs::remove_file(&path);
        }
        return Err(e);
    }
    Ok(path)
}

fn write_atomically(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let name = path.file_name().and_then(|name| name.to_str()).unwrap_or("downloads.json");
    let tmp = parent.join(format!(".{name}.{}.{}.tmp", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
    let result = (|| {
        let mut file = OpenOptions::new().write(true).create_new(true).open(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        std::fs::rename(&tmp, path)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(tmp);
    }
    result
}

fn artist_folder(name: &str) -> String {
    let clean: String = name.chars().map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { '_' } else { c }).take(60).collect();
    let clean = clean.trim().trim_end_matches(['.', ' ']);
    if clean.is_empty() || clean == "." || clean == ".." { return "Unknown artist".into(); }
    let stem = clean.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL" | "COM1" | "COM2" | "COM3" | "COM4" | "COM5" | "COM6" | "COM7" | "COM8" | "COM9" | "LPT1" | "LPT2" | "LPT3" | "LPT4" | "LPT5" | "LPT6" | "LPT7" | "LPT8" | "LPT9");
    if reserved { format!("_{clean}") } else { clean.to_owned() }
}

fn file_stem(t: &Track) -> String {
    let artists = t.artist_line();
    let name = if artists.is_empty() { t.title.clone() } else { format!("{artists} - {}", t.title) };
    let clean: String = name.chars().map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { '_' } else { c }).collect();
    let clean = clean.trim().trim_end_matches('.').trim();
    let clean: String = clean.chars().take(150).collect();
    if clean.is_empty() { t.id.clone() } else { clean }
}

fn tag(path: &Path, t: &Track, cover: Option<Vec<u8>>) -> anyhow::Result<()> {
    let mut tag = mp4ameta::Tag::read_from_path(path)?;
    tag.set_title(&t.title);
    for (i, a) in t.artists.iter().enumerate() {
        if i == 0 {
            tag.set_artist(&a.name);
        } else {
            tag.add_artist(&a.name);
        }
    }
    if let Some(a) = t.artists.first() {
        tag.set_album_artist(&a.name);
    }
    if let Some(album) = &t.album {
        tag.set_album(&album.name);
    }
    if let Some(n) = t.track_nr {
        tag.set_track_number(n);
    }
    let img = cover.and_then(|c| match c.get(..4) {
        Some([0xff, 0xd8, ..]) => Some(mp4ameta::Img::jpeg(c)),
        Some([0x89, b'P', b'N', b'G']) => Some(mp4ameta::Img::png(c)),
        _ => None,
    });
    if let Some(img) = img {
        tag.set_artwork(img);
    }
    tag.write_to_path(path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Link;

    #[test]
    fn artist_folders_are_safe_components() {
        assert_eq!(artist_folder("AC/DC"), "AC_DC");
        assert_eq!(artist_folder(".."), "Unknown artist");
        assert_eq!(artist_folder("CON"), "_CON");
        assert_eq!(artist_folder("Eminem"), "Eminem");
        assert!(local_format(Path::new("song.MP3")).is_some());
        assert!(local_format(Path::new("song.exe")).is_none());
    }

    #[test]
    fn file_names_are_safe_on_windows() {
        let track = |title: &str, artists: Vec<Link>| Track {
            id: "abc".into(),
            title: title.into(),
            artists,
            album: None,
            duration: None,
            thumbs: Vec::new(),
            track_nr: None,
            plays: None,
            music_video: false,
        };
        let t = track("What? / Why: \"Now\"...", vec![Link { name: "AC/DC".into(), id: None }]);
        assert_eq!(file_stem(&t), "AC_DC - What_ _ Why_ _Now_");
        assert_eq!(file_stem(&track("...", Vec::new())), "abc");
    }
}

#[cfg(test)]
mod space_tests {
    #[test]
    fn stops_below_500_mb_free_and_knows_this_drive() {
        let mb = 1024 * 1024;
        assert!(super::room_for(Some(600 * mb), 50 * 1024 * 1024));
        assert!(!super::room_for(Some(520 * mb), 50 * 1024 * 1024), "the song would leave less than 500 MB");
        assert!(super::room_for(None, 1), "unknown free space does not block");
        assert!(super::free_bytes(&std::env::temp_dir()).is_some_and(|f| f > 0));
        let gb = 1024 * 1024 * 1024;
        assert!(super::within_limit(0, 999 * gb, 1), "no limit");
        assert!(super::within_limit(1, gb - 10, 10));
        assert!(!super::within_limit(1, gb - 10, 11));
    }
}
