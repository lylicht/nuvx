//! Consent-based, authenticated local audio transfers over the existing LAN Sync envelope.
//! File paths stay on the receiver; the peer sees only a transfer id and safe display metadata.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

pub const CHUNK: usize = 192 * 1024;
pub const MAX_TRANSFER: u64 = 500 * 1024 * 1024;
const MAX_ACTIVE_PER_DEVICE: usize = 4;
const MAX_ACTIVE: usize = 16;
const MAX_RECORDS: usize = 96;
const OFFER_TTL_MS: i64 = 30 * 60 * 1000;
const RECORD_TTL_MS: i64 = 24 * 60 * 60 * 1000;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Status {
    Pending,
    Accepted,
    Receiving,
    Sending,
    Declined,
    Complete,
    Cancelled,
    Failed,
}

impl Status {
    fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Accepted => "accepted",
            Self::Receiving => "receiving",
            Self::Sending => "sending",
            Self::Declined => "declined",
            Self::Complete => "complete",
            Self::Cancelled => "cancelled",
            Self::Failed => "failed",
        }
    }
    fn active(self) -> bool {
        matches!(
            self,
            Self::Pending | Self::Accepted | Self::Receiving | Self::Sending
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Direction {
    Upload,
    Download,
}

impl Direction {
    fn as_str(self) -> &'static str {
        match self {
            Self::Upload => "upload",
            Self::Download => "download",
        }
    }
}

struct Transfer {
    id: String,
    device: String,
    direction: Direction,
    name: String,
    size: u64,
    mime: &'static str,
    sha256: String,
    title: String,
    artist: String,
    status: Status,
    received: u64,
    updated_ms: i64,
    temporary: Option<PathBuf>,
    source: Option<PathBuf>,
}

#[derive(Serialize, Deserialize)]
struct TransferRecord {
    id: String,
    device: String,
    direction: Direction,
    name: String,
    size: u64,
    mime: String,
    sha256: String,
    title: String,
    artist: String,
    status: Status,
    received: u64,
    updated_ms: i64,
    source: Option<PathBuf>,
}

#[derive(Serialize, Deserialize)]
struct TransferStore {
    version: u32,
    transfers: Vec<TransferRecord>,
}

const TRANSFER_STORE_VERSION: u32 = 1;
const MAX_STORE_BYTES: u64 = 256 * 1024;

#[derive(Default)]
pub struct Transfers {
    items: HashMap<(String, String), Transfer>,
    received_changed: bool,
    incoming_dir: Option<PathBuf>,
    store_path: Option<PathBuf>,
}

impl Transfers {
    /// Restore resumable state from the app-private staging folder. Partial files are kept only when their
    /// derived name, regular-file type, and saved offset all agree with the bounded transfer record.
    pub fn restore(incoming: &Path) -> Self {
        let mut transfers = Self {
            incoming_dir: Some(incoming.to_owned()),
            store_path: Some(incoming.join("transfers.json")),
            ..Self::default()
        };
        if ensure_directory(incoming).is_err() {
            log::warn!("lan sync: transfer staging folder is not a safe directory");
            return transfers;
        }

        let store_path = transfers.store_path.as_ref().expect("configured above");
        let records = std::fs::symlink_metadata(store_path)
            .ok()
            .filter(|meta| meta.is_file() && !is_link(meta) && meta.len() <= MAX_STORE_BYTES)
            .and_then(|_| std::fs::read(store_path).ok())
            .and_then(|bytes| serde_json::from_slice::<TransferStore>(&bytes).ok())
            .filter(|store| store.version == TRANSFER_STORE_VERSION)
            .map(|store| store.transfers)
            .unwrap_or_default();

        for record in records.into_iter().take(MAX_RECORDS) {
            let Some(transfer) = restore_record(record, incoming) else {
                continue;
            };
            let key = (transfer.device.clone(), transfer.id.clone());
            transfers.items.insert(key, transfer);
        }
        transfers.expire(crate::lan_sync::host::now_ms());
        transfers.remove_orphan_parts();
        transfers.persist();
        transfers
    }

    pub fn offer(
        &mut self,
        device: &str,
        body: &Value,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        self.expire(now_ms);
        let id = body
            .get("id")
            .and_then(Value::as_str)
            .filter(|s| valid_id(s))
            .ok_or((422, "bad_offer"))?;
        let size = body
            .get("size")
            .and_then(Value::as_u64)
            .filter(|s| (1..=MAX_TRANSFER).contains(s))
            .ok_or((413, "too_large"))?;
        let sha = body
            .get("sha256")
            .and_then(Value::as_str)
            .filter(|s| valid_hash(s))
            .ok_or((422, "bad_offer"))?
            .to_ascii_lowercase();
        let name = body
            .get("name")
            .and_then(Value::as_str)
            .and_then(safe_audio_name)
            .ok_or((422, "unsupported_file"))?;
        let mime = audio_mime(&name).ok_or((422, "unsupported_file"))?;
        if body.get("mime").and_then(Value::as_str) != Some(mime) {
            return Err((422, "mime_mismatch"));
        }
        let key = (device.to_owned(), id.to_owned());
        if let Some(existing) = self.items.get(&key) {
            if existing.size == size && existing.sha256 == sha && existing.name == name {
                return Ok(json!({ "id": id, "status": existing.status.as_str() }));
            }
            return Err((409, "id_conflict"));
        }
        self.check_capacity(device)?;
        let title = clean_text(body.get("title").and_then(Value::as_str).unwrap_or(""));
        let artist = clean_text(body.get("artist").and_then(Value::as_str).unwrap_or(""));
        self.items.insert(
            key,
            Transfer {
                id: id.to_owned(),
                device: device.to_owned(),
                direction: Direction::Upload,
                name,
                size,
                mime,
                sha256: sha,
                title,
                artist,
                status: Status::Pending,
                received: 0,
                updated_ms: now_ms,
                temporary: None,
                source: None,
            },
        );
        self.persist();
        Ok(json!({ "id": id, "status": "pending" }))
    }

    /// Register a local file selected by this user for a specific paired recipient.
    pub fn offer_local(
        &mut self,
        device: &str,
        id: &str,
        path: &Path,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        self.expire(now_ms);
        if !valid_id(id) {
            return Err((422, "bad_offer"));
        }
        let meta = std::fs::symlink_metadata(path).map_err(|_| (404, "not_found"))?;
        if !meta.is_file() || is_link(&meta) || !(1..=MAX_TRANSFER).contains(&meta.len()) {
            return Err((422, "unsupported_file"));
        }
        let name = path
            .file_name()
            .and_then(|s| s.to_str())
            .and_then(safe_audio_name)
            .ok_or((422, "unsupported_file"))?;
        let mime = audio_mime(&name).ok_or((422, "unsupported_file"))?;
        let source = path.canonicalize().map_err(|_| (404, "not_found"))?;
        if !audio_header_matches(&source, mime) {
            return Err((422, "invalid_audio"));
        }
        let sha = hash_file(&source).map_err(|_| (500, "unreadable"))?;
        let title = Path::new(&name)
            .file_stem()
            .and_then(|s| s.to_str())
            .map(clean_text)
            .unwrap_or_default();
        let key = (device.to_owned(), id.to_owned());
        if self.items.contains_key(&key) {
            return Err((409, "id_conflict"));
        }
        self.check_capacity(device)?;
        self.items.insert(
            key,
            Transfer {
                id: id.to_owned(),
                device: device.to_owned(),
                direction: Direction::Download,
                name,
                size: meta.len(),
                mime,
                sha256: sha,
                title,
                artist: String::new(),
                status: Status::Pending,
                received: 0,
                updated_ms: now_ms,
                temporary: None,
                source: Some(source),
            },
        );
        self.persist();
        Ok(json!({ "id": id, "status": "pending" }))
    }

    pub fn list(&mut self, device: &str, now_ms: i64) -> Value {
        self.expire(now_ms);
        let transfers: Vec<Value> = self
            .items
            .values()
            .filter(|t| t.device == device)
            .map(Transfer::public)
            .collect();
        json!({ "transfers": transfers })
    }

    pub fn status(
        &mut self,
        device: &str,
        id: &str,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        self.expire(now_ms);
        self.get(device, id).map(Transfer::public)
    }

    pub fn is_download(&self, device: &str, id: &str) -> Option<bool> {
        self.items
            .get(&(device.to_owned(), id.to_owned()))
            .map(|transfer| transfer.direction == Direction::Download)
    }

    pub fn accept(
        &mut self,
        device: &str,
        id: &str,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        let t = self.get_mut(device, id)?;
        if t.status != Status::Pending {
            return Err((409, "not_pending"));
        }
        t.status = Status::Accepted;
        t.updated_ms = now_ms;
        self.persist();
        Ok(json!({ "id": id, "status": "accepted" }))
    }

    pub fn decline(
        &mut self,
        device: &str,
        id: &str,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        let t = self.get_mut(device, id)?;
        if t.status != Status::Pending {
            return Err((409, "not_pending"));
        }
        t.status = Status::Declined;
        t.updated_ms = now_ms;
        self.persist();
        Ok(json!({ "id": id, "status": "declined" }))
    }

    pub fn receive_chunk(
        &mut self,
        incoming: &Path,
        received: &Path,
        device: &str,
        id: &str,
        body: &Value,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        let offset = body
            .get("offset")
            .and_then(Value::as_u64)
            .ok_or((422, "bad_piece"))?;
        let data = body
            .get("data")
            .and_then(Value::as_str)
            .and_then(super::crypto::unb64)
            .ok_or((422, "bad_piece"))?;
        if data.is_empty() || data.len() > CHUNK {
            return Err((422, "bad_piece"));
        }
        let t = self.get_mut(device, id)?;
        if t.direction != Direction::Upload {
            return Err((409, "wrong_direction"));
        }
        if !matches!(t.status, Status::Accepted | Status::Receiving) {
            return Err((403, "not_accepted"));
        }
        if offset < t.received {
            // Retry safety: only accept a byte-identical retransmission of an already-written chunk.
            let Some(path) = &t.temporary else {
                return Err((409, "start_over"));
            };
            let mut old = vec![0; data.len()];
            let same = std::fs::File::open(path)
                .and_then(|mut f| {
                    f.seek(SeekFrom::Start(offset))?;
                    f.read_exact(&mut old)
                })
                .is_ok()
                && old == data;
            if !same {
                return Err((409, "start_over"));
            }
            return Ok(json!({ "received": t.received, "status": t.status.as_str() }));
        }
        if offset != t.received || t.received + data.len() as u64 > t.size {
            return Err((409, "start_over"));
        }
        std::fs::create_dir_all(incoming).map_err(|_| (500, "unwritable"))?;
        let path = t
            .temporary
            .clone()
            .unwrap_or_else(|| incoming.join(format!("{}.part", transfer_token(device, id))));
        let first_chunk = t.temporary.is_none();
        let mut file = if first_chunk {
            std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
        } else {
            std::fs::OpenOptions::new().write(true).open(&path)
        }
        .map_err(|_| (500, "unwritable"))?;
        if file
            .seek(SeekFrom::Start(t.received))
            .and_then(|_| file.write_all(&data))
            .and_then(|_| file.sync_data())
            .is_err()
        {
            let _ = file.set_len(t.received);
            if first_chunk {
                let _ = std::fs::remove_file(&path);
            }
            return Err((500, "unwritable"));
        }
        t.temporary = Some(path);
        t.received += data.len() as u64;
        t.status = Status::Receiving;
        t.updated_ms = now_ms;
        if t.received < t.size {
            let received = t.received;
            let reply = json!({ "received": received, "status": "receiving" });
            self.persist();
            return Ok(reply);
        }
        let temp = t.temporary.as_ref().expect("a full file has a temp path");
        if hash_file(temp).map_or(true, |hash| hash != t.sha256) {
            t.fail_and_remove();
            t.status = Status::Failed;
            t.updated_ms = now_ms;
            self.persist();
            return Err((422, "hash_mismatch"));
        }
        if !audio_header_matches(temp, t.mime) {
            t.fail_and_remove();
            t.status = Status::Failed;
            t.updated_ms = now_ms;
            self.persist();
            return Err((422, "invalid_audio"));
        }
        if ensure_directory(received).is_err() {
            t.fail_and_remove();
            t.status = Status::Failed;
            t.updated_ms = now_ms;
            self.persist();
            return Err((500, "unwritable"));
        }
        let target = received.join(received_name(&t.name, &transfer_token(device, id)));
        let target_is_same_transfer = match std::fs::symlink_metadata(&target) {
            Ok(meta) if meta.is_file() && !is_link(&meta) && meta.len() == t.size => {
                hash_file(&target).is_ok_and(|hash| hash == t.sha256)
            }
            Ok(_) => false,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
            Err(_) => false,
        };
        let target_exists = std::fs::symlink_metadata(&target).is_ok();
        if target_exists && !target_is_same_transfer {
            t.fail_and_remove();
            t.status = Status::Failed;
            t.updated_ms = now_ms;
            self.persist();
            return Err((409, "target_conflict"));
        }
        if !target_exists {
            let copy = (|| {
                let mut input = std::fs::File::open(temp)?;
                let mut output = std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&target)?;
                std::io::copy(&mut input, &mut output)?;
                output.sync_all()
            })();
            if copy.is_err() {
                let _ = std::fs::remove_file(&target);
                t.fail_and_remove();
                t.status = Status::Failed;
                t.updated_ms = now_ms;
                self.persist();
                return Err((500, "unwritable"));
            }
        }
        if let Some(temp) = t.temporary.take() {
            let _ = std::fs::remove_file(temp);
        }
        t.temporary = None;
        t.status = Status::Complete;
        t.updated_ms = now_ms;
        self.received_changed = true;
        self.persist();
        Ok(json!({ "done": true, "status": "complete" }))
    }

    pub fn download_chunk(
        &mut self,
        device: &str,
        id: &str,
        query: &str,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        let offset = query
            .split('&')
            .find_map(|kv| kv.strip_prefix("offset="))
            .and_then(|v| v.parse::<u64>().ok())
            .ok_or((422, "bad_piece"))?;
        let t = self.get_mut(device, id)?;
        if t.direction != Direction::Download {
            return Err((409, "wrong_direction"));
        }
        if !matches!(t.status, Status::Accepted | Status::Sending) {
            return Err((403, "not_accepted"));
        }
        if offset > t.received || offset > t.size {
            return Err((409, "start_over"));
        }
        let path = t.source.as_ref().ok_or((404, "not_found"))?;
        let meta = std::fs::symlink_metadata(path).map_err(|_| (404, "not_found"))?;
        if !meta.is_file() || is_link(&meta) || meta.len() != t.size {
            t.status = Status::Failed;
            t.updated_ms = now_ms;
            t.source = None;
            self.persist();
            return Err((409, "source_changed"));
        }
        let mut file = std::fs::File::open(path).map_err(|_| (404, "not_found"))?;
        file.seek(SeekFrom::Start(offset))
            .map_err(|_| (500, "unreadable"))?;
        let mut data = vec![0; CHUNK.min((t.size - offset) as usize)];
        let count = file.read(&mut data).map_err(|_| (500, "unreadable"))?;
        data.truncate(count);
        if data.is_empty() && offset != t.size {
            return Err((500, "unreadable"));
        }
        if offset == t.received {
            t.received += data.len() as u64;
        }
        t.status = Status::Sending;
        t.updated_ms = now_ms;
        let response = json!({
            "id": id,
            "offset": offset,
            "size": t.size,
            "data": super::crypto::b64(&data),
            "sha256": t.sha256
        });
        self.persist();
        Ok(response)
    }

    pub fn complete_download(
        &mut self,
        device: &str,
        id: &str,
        body: &Value,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        let t = self.get_mut(device, id)?;
        if t.direction != Direction::Download || t.status != Status::Sending {
            return Err((409, "not_sending"));
        }
        if body.get("sha256").and_then(Value::as_str) != Some(t.sha256.as_str()) {
            return Err((422, "hash_mismatch"));
        }
        if t.received != t.size {
            return Err((409, "incomplete"));
        }
        let source = t.source.as_ref().ok_or((404, "not_found"))?;
        if hash_file(source).map_err(|_| (500, "unreadable"))? != t.sha256 {
            t.status = Status::Failed;
            t.updated_ms = now_ms;
            return Err((409, "source_changed"));
        }
        t.status = Status::Complete;
        t.updated_ms = now_ms;
        t.source = None;
        self.persist();
        Ok(json!({ "done": true, "status": "complete" }))
    }

    pub fn cancel(
        &mut self,
        device: &str,
        id: &str,
        now_ms: i64,
    ) -> Result<Value, (u16, &'static str)> {
        let t = self.get_mut(device, id)?;
        if t.status == Status::Complete {
            return Err((409, "already_complete"));
        }
        t.fail_and_remove();
        t.status = Status::Cancelled;
        t.updated_ms = now_ms;
        self.persist();
        Ok(json!({ "ok": true, "status": "cancelled" }))
    }

    pub fn pending(&mut self, now_ms: i64) -> Vec<Value> {
        self.expire(now_ms);
        let mut items: Vec<_> = self
            .items
            .values()
            .filter(|t| t.status == Status::Pending)
            .map(Transfer::public)
            .collect();
        items.sort_by_key(|v| v["updatedMs"].as_i64().unwrap_or_default());
        items
    }

    pub fn all(&mut self, now_ms: i64) -> Vec<Value> {
        self.expire(now_ms);
        self.items.values().map(Transfer::public).collect()
    }

    pub fn remove_device(&mut self, device: &str) {
        let before = self.items.len();
        self.items.retain(|(owner, _), transfer| {
            if owner == device {
                transfer.fail_and_remove();
                false
            } else {
                true
            }
        });
        if self.items.len() != before {
            self.persist();
        }
    }

    pub fn take_received_changed(&mut self) -> bool {
        std::mem::take(&mut self.received_changed)
    }

    fn persist(&self) {
        let Some(path) = self.store_path.as_ref() else {
            return;
        };
        let store = TransferStore {
            version: TRANSFER_STORE_VERSION,
            transfers: self
                .items
                .values()
                .map(|t| TransferRecord {
                    id: t.id.clone(),
                    device: t.device.clone(),
                    direction: t.direction,
                    name: t.name.clone(),
                    size: t.size,
                    mime: t.mime.to_owned(),
                    sha256: t.sha256.clone(),
                    title: t.title.clone(),
                    artist: t.artist.clone(),
                    status: t.status,
                    received: t.received,
                    updated_ms: t.updated_ms,
                    source: t.source.clone(),
                })
                .collect(),
        };
        let result = (|| {
            let bytes = serde_json::to_vec(&store)?;
            let temp = path.with_extension("json.tmp");
            std::fs::write(&temp, bytes)?;
            std::fs::rename(temp, path)?;
            Ok::<_, std::io::Error>(())
        })();
        if let Err(error) = result {
            log::warn!("lan sync: could not persist transfer resume state: {error}");
        }
    }

    fn remove_orphan_parts(&self) {
        let Some(incoming) = self.incoming_dir.as_deref() else {
            return;
        };
        let keep: std::collections::HashSet<_> = self
            .items
            .values()
            .filter_map(|t| {
                t.temporary
                    .as_ref()?
                    .file_name()?
                    .to_str()
                    .map(str::to_owned)
            })
            .collect();
        let Ok(entries) = std::fs::read_dir(incoming) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if entry.file_type().is_ok_and(|kind| kind.is_file())
                && path
                    .extension()
                    .is_some_and(|extension| extension == "part")
                && path
                    .file_stem()
                    .and_then(|stem| stem.to_str())
                    .is_some_and(|stem| {
                        stem.len() == 24 && stem.bytes().all(|b| b.is_ascii_hexdigit())
                    })
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| !keep.contains(name))
            {
                let _ = std::fs::remove_file(path);
            }
        }
    }

    fn get(&self, device: &str, id: &str) -> Result<&Transfer, (u16, &'static str)> {
        self.items
            .get(&(device.to_owned(), id.to_owned()))
            .ok_or((404, "not_found"))
    }
    fn get_mut(&mut self, device: &str, id: &str) -> Result<&mut Transfer, (u16, &'static str)> {
        self.items
            .get_mut(&(device.to_owned(), id.to_owned()))
            .ok_or((404, "not_found"))
    }
    fn check_capacity(&self, device: &str) -> Result<(), (u16, &'static str)> {
        let active = self.items.values().filter(|t| t.status.active()).count();
        let active_peer = self
            .items
            .values()
            .filter(|t| t.device == device && t.status.active())
            .count();
        if active >= MAX_ACTIVE
            || active_peer >= MAX_ACTIVE_PER_DEVICE
            || self.items.len() >= MAX_RECORDS
        {
            Err((429, "busy"))
        } else {
            Ok(())
        }
    }
    fn expire(&mut self, now_ms: i64) {
        let before = self.items.len();
        self.items.retain(|_, t| {
            let age = now_ms.saturating_sub(t.updated_ms);
            if t.status.active() && age > OFFER_TTL_MS {
                t.fail_and_remove();
                false
            } else {
                age <= RECORD_TTL_MS
            }
        });
        if self.items.len() != before {
            self.persist();
        }
    }
}

fn restore_record(record: TransferRecord, incoming: &Path) -> Option<Transfer> {
    if !valid_id(&record.id)
        || !valid_device_id(&record.device)
        || !(1..=MAX_TRANSFER).contains(&record.size)
        || record.received > record.size
        || !valid_hash(&record.sha256)
    {
        return None;
    }
    let name = safe_audio_name(&record.name)?;
    if name != record.name {
        return None;
    }
    let mime = audio_mime(&name)?;
    if record.mime != mime {
        return None;
    }

    let mut transfer = Transfer {
        id: record.id,
        device: record.device,
        direction: record.direction,
        name,
        size: record.size,
        mime,
        sha256: record.sha256.to_ascii_lowercase(),
        title: clean_text(&record.title),
        artist: clean_text(&record.artist),
        status: record.status,
        received: record.received,
        updated_ms: record.updated_ms,
        temporary: None,
        source: None,
    };

    match transfer.direction {
        Direction::Upload => {
            if record.source.is_some() {
                return None;
            }
            if transfer.status.active() && transfer.received > 0 {
                if transfer.status != Status::Receiving {
                    transfer.status = Status::Failed;
                    transfer.received = 0;
                } else {
                    let path = incoming.join(format!(
                        "{}.part",
                        transfer_token(&transfer.device, &transfer.id)
                    ));
                    let restored = std::fs::symlink_metadata(&path).ok().filter(|meta| {
                        meta.is_file() && !is_link(meta) && meta.len() >= transfer.received
                    });
                    if let Some(meta) = restored {
                        if meta.len() > transfer.received {
                            let truncated = std::fs::OpenOptions::new()
                                .write(true)
                                .open(&path)
                                .and_then(|file| file.set_len(transfer.received))
                                .is_ok();
                            if !truncated {
                                transfer.status = Status::Failed;
                                transfer.received = 0;
                                let _ = std::fs::remove_file(&path);
                            } else {
                                transfer.temporary = Some(path);
                            }
                        } else {
                            transfer.temporary = Some(path);
                        }
                    } else {
                        transfer.status = Status::Failed;
                        transfer.received = 0;
                    }
                }
            } else if transfer.status == Status::Receiving {
                transfer.status = Status::Failed;
                transfer.received = 0;
            }
        }
        Direction::Download => {
            if transfer.status.active() {
                if let Some(source) = record.source.as_deref() {
                    let checked = std::fs::symlink_metadata(source)
                        .ok()
                        .filter(|meta| {
                            meta.is_file() && !is_link(meta) && meta.len() == transfer.size
                        })
                        .and_then(|_| source.canonicalize().ok());
                    if let Some(source) = checked {
                        transfer.source = Some(source);
                    } else {
                        transfer.status = Status::Failed;
                        transfer.received = 0;
                    }
                } else {
                    transfer.status = Status::Failed;
                    transfer.received = 0;
                }
            }
        }
    }
    Some(transfer)
}

fn ensure_directory(path: &Path) -> std::io::Result<()> {
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.is_dir() && !is_link(&meta) => Ok(()),
        Ok(_) => Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "transfer staging path is not a real directory",
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            std::fs::create_dir_all(path)?;
            let meta = std::fs::symlink_metadata(path)?;
            if meta.is_dir() && !is_link(&meta) {
                Ok(())
            } else {
                Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "transfer staging path is not a real directory",
                ))
            }
        }
        Err(error) => Err(error),
    }
}

impl Transfer {
    fn public(&self) -> Value {
        json!({
            "id": self.id, "direction": self.direction.as_str(), "name": self.name, "size": self.size, "mime": self.mime,
            "sha256": self.sha256, "title": self.title, "artist": self.artist,
            "status": self.status.as_str(), "received": self.received,
            "deviceId": self.device, "updatedMs": self.updated_ms
        })
    }

    fn fail_and_remove(&mut self) {
        if let Some(path) = self.temporary.take() {
            let _ = std::fs::remove_file(path);
        }
        self.source = None;
    }
}

pub fn valid_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit())
}

fn valid_device_id(id: &str) -> bool {
    (16..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_hexdigit())
}

pub fn new_id() -> String {
    hex(&super::crypto::random_bytes::<16>())
}

fn valid_hash(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit())
}

pub(super) fn audio_mime(name: &str) -> Option<&'static str> {
    match Path::new(name)
        .extension()?
        .to_str()?
        .to_ascii_lowercase()
        .as_str()
    {
        "mp3" => Some("audio/mpeg"),
        "m4a" | "mp4" => Some("audio/mp4"),
        "aac" => Some("audio/aac"),
        "flac" => Some("audio/flac"),
        "ogg" => Some("audio/ogg"),
        "wav" => Some("audio/wav"),
        "webm" => Some("audio/webm"),
        _ => None,
    }
}

pub(super) fn compatible_audio_mime(name: &str, reported: &str) -> Option<&'static str> {
    let canonical = audio_mime(name)?;
    let compatible = match canonical {
        "audio/wav" => matches!(reported, "audio/wav" | "audio/x-wav" | "audio/wave"),
        "audio/flac" => matches!(reported, "audio/flac" | "audio/x-flac"),
        "audio/ogg" => matches!(reported, "audio/ogg" | "application/ogg"),
        _ => reported == canonical,
    };
    compatible.then_some(canonical)
}

pub(super) fn safe_audio_name(raw: &str) -> Option<String> {
    if raw.len() > 240 || raw.chars().any(char::is_control) || raw.contains(['/', '\\']) {
        return None;
    }
    let base = raw.trim();
    let path = Path::new(base);
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    audio_mime(&format!("x.{ext}"))?;
    let stem = path.file_stem()?.to_string_lossy();
    let stem: String = stem
        .chars()
        .filter(|c| c.is_alphanumeric() || matches!(c, ' ' | '-' | '_' | '(' | ')' | '.'))
        .collect();
    let stem = stem.trim().trim_matches('.');
    if stem.is_empty() || stem.len() > 180 {
        return None;
    }
    Some(format!("{stem}.{ext}"))
}

fn clean_text(raw: &str) -> String {
    raw.chars()
        .filter(|c| !c.is_control())
        .take(200)
        .collect::<String>()
        .trim()
        .to_owned()
}

fn transfer_token(device: &str, id: &str) -> String {
    hex(&Sha256::digest(format!("{device}/{id}").as_bytes()))[..24].to_owned()
}

pub(super) fn hash_file(path: &Path) -> std::io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0; CHUNK];
    loop {
        let count = file.read(&mut buf)?;
        if count == 0 {
            break;
        }
        hasher.update(&buf[..count]);
    }
    Ok(hex(&hasher.finalize()))
}

pub(super) fn is_link(meta: &std::fs::Metadata) -> bool {
    if meta.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if meta.file_attributes() & 0x400 != 0 {
            return true;
        }
    }
    false
}

pub(super) fn received_name(name: &str, token: &str) -> String {
    let path = Path::new(name);
    format!(
        "{}-{}.{}",
        path.file_stem().unwrap_or_default().to_string_lossy(),
        &token[..8],
        path.extension().and_then(|s| s.to_str()).unwrap_or("audio")
    )
}

pub(super) fn audio_header_matches(path: &Path, mime: &str) -> bool {
    let Ok(mut file) = std::fs::File::open(path) else {
        return false;
    };
    let mut head = [0; 16];
    let Ok(len) = file.read(&mut head) else {
        return false;
    };
    let bytes = &head[..len];
    match mime {
        "audio/mpeg" => bytes.starts_with(b"ID3") || bytes.starts_with(&[0xff, 0xfb]),
        "audio/mp4" => bytes.get(4..8) == Some(b"ftyp"),
        "audio/aac" => bytes.starts_with(&[0xff, 0xf1]) || bytes.starts_with(&[0xff, 0xf9]),
        "audio/flac" => bytes.starts_with(b"fLaC"),
        "audio/ogg" => bytes.starts_with(b"OggS"),
        "audio/wav" => bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WAVE"),
        "audio/webm" => bytes.starts_with(&[0x1a, 0x45, 0xdf, 0xa3]),
        _ => false,
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn offer(bytes: &[u8], name: &str) -> Value {
        json!({
            "id": "0123456789abcdef0123456789abcdef",
            "name": name,
            "size": bytes.len(),
            "mime": audio_mime(name).unwrap(),
            "sha256": hex(&Sha256::digest(bytes)),
            "title": "A song",
            "artist": "An artist"
        })
    }

    fn piece(offset: usize, bytes: &[u8]) -> Value {
        json!({ "offset": offset, "data": super::super::crypto::b64(bytes) })
    }

    #[test]
    fn accepts_platform_wav_and_flac_mime_aliases_but_rejects_mismatches() {
        assert_eq!(
            compatible_audio_mime("song.wav", "audio/x-wav"),
            Some("audio/wav")
        );
        assert_eq!(
            compatible_audio_mime("song.wav", "audio/wave"),
            Some("audio/wav")
        );
        assert_eq!(
            compatible_audio_mime("song.flac", "audio/x-flac"),
            Some("audio/flac")
        );
        assert_eq!(compatible_audio_mime("song.wav", "audio/mpeg"), None);
    }

    #[test]
    fn offers_need_valid_audio_metadata_and_are_scoped_to_the_paired_device() {
        let mut transfers = Transfers::default();
        let bytes = b"ID3-audio";
        let mut malformed = offer(bytes, "song.mp3");
        malformed["name"] = json!("../../secret.mp3");
        assert_eq!(
            transfers.offer("phone-a", &malformed, 1).unwrap_err().1,
            "unsupported_file"
        );

        assert_eq!(
            transfers
                .offer("phone-a", &offer(bytes, "song.mp3"), 1)
                .unwrap()["status"],
            "pending"
        );
        assert_eq!(
            transfers.list("phone-b", 2)["transfers"]
                .as_array()
                .unwrap()
                .len(),
            0
        );
        assert_eq!(
            transfers
                .status("phone-b", "0123456789abcdef0123456789abcdef", 2)
                .unwrap_err()
                .0,
            404
        );
        assert_eq!(
            transfers
                .status("phone-a", "0123456789abcdef0123456789abcdef", 2)
                .unwrap()["status"],
            "pending"
        );
    }

    #[test]
    fn receiver_accepts_before_chunks_then_verifies_hash_and_audio_header() {
        let root = std::env::temp_dir().join(format!("vyral-transfer-{}", std::process::id()));
        let incoming = root.join("incoming");
        let received = root.join("received");
        std::fs::create_dir_all(&received).unwrap();
        let mut transfers = Transfers::default();
        let bytes = b"ID3-audio";
        let id = "0123456789abcdef0123456789abcdef";
        transfers
            .offer("phone", &offer(bytes, "song.mp3"), 1)
            .unwrap();
        assert_eq!(
            transfers
                .receive_chunk(&incoming, &received, "phone", id, &piece(0, bytes), 2)
                .unwrap_err()
                .1,
            "not_accepted"
        );
        transfers.accept("phone", id, 3).unwrap();
        assert_eq!(
            transfers
                .receive_chunk(&incoming, &received, "phone", id, &piece(0, bytes), 4)
                .unwrap()["status"],
            "complete"
        );
        let transfer = transfers.status("phone", id, 5).unwrap();
        assert_eq!(transfer["received"], bytes.len());
        assert_eq!(transfer["status"], "complete");
        let saved = received.join(received_name("song.mp3", &transfer_token("phone", id)));
        assert_eq!(std::fs::read(saved).unwrap(), bytes);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn restores_partial_upload_and_continues_from_the_saved_offset() {
        let root =
            std::env::temp_dir().join(format!("vyral-transfer-resume-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let incoming = root.join("incoming");
        let received = root.join("received");
        let device = "abcdef0123456789";
        let id = "0123456789abcdef0123456789abcdef";
        let now = crate::lan_sync::host::now_ms();
        let mut bytes = vec![b'x'; CHUNK + 73];
        bytes[..3].copy_from_slice(b"ID3");

        let mut first = Transfers::restore(&incoming);
        first
            .offer(device, &offer(&bytes, "song.mp3"), now)
            .unwrap();
        first.accept(device, id, now + 1).unwrap();
        let progress = first
            .receive_chunk(
                &incoming,
                &received,
                device,
                id,
                &piece(0, &bytes[..CHUNK]),
                now + 2,
            )
            .unwrap();
        assert_eq!(progress["received"], CHUNK);
        drop(first);

        let mut resumed = Transfers::restore(&incoming);
        let status = resumed.status(device, id, now + 3).unwrap();
        assert_eq!(status["status"], "receiving");
        assert_eq!(status["received"], CHUNK);
        let result = resumed
            .receive_chunk(
                &incoming,
                &received,
                device,
                id,
                &piece(CHUNK, &bytes[CHUNK..]),
                now + 4,
            )
            .unwrap();
        assert_eq!(result["status"], "complete");
        let saved = received.join(received_name("song.mp3", &transfer_token(device, id)));
        assert_eq!(std::fs::read(saved).unwrap(), bytes);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn restores_selected_source_and_continues_download_offset() {
        let root =
            std::env::temp_dir().join(format!("vyral-transfer-send-resume-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let data = root.join("data");
        let source_dir = root.join("source");
        std::fs::create_dir_all(&source_dir).unwrap();
        let source = source_dir.join("song.mp3");
        let device = "fedcba9876543210";
        let id = "abcdef0123456789abcdef0123456789";
        let now = crate::lan_sync::host::now_ms();
        let mut bytes = vec![b'x'; CHUNK + 41];
        bytes[..3].copy_from_slice(b"ID3");
        std::fs::write(&source, &bytes).unwrap();

        let mut first = Transfers::restore(&data);
        first.offer_local(device, id, &source, now).unwrap();
        first.accept(device, id, now + 1).unwrap();
        let first_chunk = first
            .download_chunk(device, id, "offset=0", now + 2)
            .unwrap();
        assert_eq!(first_chunk["offset"], 0);
        drop(first);

        let mut resumed = Transfers::restore(&data);
        assert_eq!(
            resumed.status(device, id, now + 3).unwrap()["received"],
            CHUNK
        );
        let next = resumed
            .download_chunk(device, id, &format!("offset={CHUNK}"), now + 4)
            .unwrap();
        assert_eq!(next["offset"], CHUNK);
        assert_eq!(
            super::super::crypto::unb64(next["data"].as_str().unwrap()).unwrap(),
            bytes[CHUNK..]
        );
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn user_selected_host_file_is_offered_only_to_its_paired_recipient_and_downloaded_after_accept()
    {
        let root = std::env::temp_dir().join(format!("vyral-selected-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let source = root.join("selected.mp3");
        let bytes = b"ID3-selected-song";
        std::fs::write(&source, bytes).unwrap();
        let id = "0123456789abcdef0123456789abcdef";
        let mut transfers = Transfers::default();
        assert_eq!(
            transfers.offer_local("phone", id, &source, 1).unwrap()["status"],
            "pending"
        );
        assert_eq!(
            transfers.list("other", 2)["transfers"]
                .as_array()
                .unwrap()
                .len(),
            0
        );
        assert_eq!(
            transfers
                .download_chunk("phone", id, "offset=0", 2)
                .unwrap_err()
                .1,
            "not_accepted"
        );
        transfers.accept("phone", id, 3).unwrap();
        let response = transfers
            .download_chunk("phone", id, "offset=0", 4)
            .unwrap();
        assert_eq!(response["sha256"], hex(&Sha256::digest(bytes)));
        assert_eq!(
            super::super::crypto::unb64(response["data"].as_str().unwrap()).unwrap(),
            bytes
        );
        assert_eq!(
            transfers.status("phone", id, 5).unwrap()["direction"],
            "download"
        );
        assert_eq!(
            transfers
                .complete_download("phone", id, &json!({ "sha256": "bad" }), 5)
                .unwrap_err()
                .1,
            "hash_mismatch"
        );
        assert_eq!(
            transfers
                .complete_download(
                    "phone",
                    id,
                    &json!({ "sha256": hex(&Sha256::digest(bytes)) }),
                    6
                )
                .unwrap()["status"],
            "complete"
        );
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn wrong_payload_hash_and_audio_signature_fail_and_delete_partial_file() {
        let root = std::env::temp_dir().join(format!("vyral-bad-file-{}", std::process::id()));
        let received = root.join("received");
        let id = "0123456789abcdef0123456789abcdef";
        let mut transfers = Transfers::default();
        let mut mismatch = offer(b"ID3-audio", "song.mp3");
        mismatch["sha256"] = json!(hex(&Sha256::digest(b"other bytes")));
        transfers.offer("phone", &mismatch, 1).unwrap();
        transfers.accept("phone", id, 2).unwrap();
        assert_eq!(
            transfers
                .receive_chunk(&root, &received, "phone", id, &piece(0, b"ID3-audio"), 3)
                .unwrap_err()
                .1,
            "hash_mismatch"
        );
        assert_eq!(
            transfers.status("phone", id, 4).unwrap()["status"],
            "failed"
        );
        assert!(std::fs::read_dir(&root).map_or(true, |mut items| items.next().is_none()));

        let id2 = "fedcba9876543210fedcba9876543210";
        let mut invalid = offer(b"not an mp3", "fake.mp3");
        invalid["id"] = json!(id2);
        transfers.offer("phone", &invalid, 5).unwrap();
        transfers.accept("phone", id2, 6).unwrap();
        assert_eq!(
            transfers
                .receive_chunk(&root, &received, "phone", id2, &piece(0, b"not an mp3"), 7)
                .unwrap_err()
                .1,
            "invalid_audio"
        );
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn retries_are_idempotent_and_cancel_discards_partial_data() {
        let root =
            std::env::temp_dir().join(format!("vyral-transfer-cancel-{}", std::process::id()));
        let mut transfers = Transfers::default();
        let bytes = b"ID3-audio";
        let id = "0123456789abcdef0123456789abcdef";
        transfers
            .offer("phone", &offer(bytes, "song.mp3"), 1)
            .unwrap();
        transfers.accept("phone", id, 2).unwrap();
        let part = piece(0, &bytes[..4]);
        assert_eq!(
            transfers
                .receive_chunk(&root, &root, "phone", id, &part, 3)
                .unwrap()["received"],
            4
        );
        assert_eq!(
            transfers
                .receive_chunk(&root, &root, "phone", id, &part, 4)
                .unwrap()["received"],
            4
        );
        assert_eq!(
            transfers.cancel("phone", id, 5).unwrap()["status"],
            "cancelled"
        );
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn peers_send_and_receive_selected_audio_concurrently_in_both_directions() {
        let root = std::env::temp_dir().join(format!("vyral-full-duplex-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let source_a = root.join("a.mp3");
        let source_b = root.join("b.mp3");
        let mut bytes_a = vec![b'a'; CHUNK + 37];
        let mut bytes_b = vec![b'b'; CHUNK * 2 + 19];
        bytes_a[..3].copy_from_slice(b"ID3");
        bytes_b[..3].copy_from_slice(b"ID3");
        std::fs::write(&source_a, &bytes_a).unwrap();
        std::fs::write(&source_b, &bytes_b).unwrap();

        let id_a = "0123456789abcdef0123456789abcdef";
        let id_b = "fedcba9876543210fedcba9876543210";
        let mut host_a = Transfers::default();
        let mut host_b = Transfers::default();
        assert_eq!(
            host_a.offer_local("device-b", id_a, &source_a, 1).unwrap()["status"],
            "pending"
        );
        assert_eq!(
            host_b.offer_local("device-a", id_b, &source_b, 1).unwrap()["status"],
            "pending"
        );
        host_a.accept("device-b", id_a, 2).unwrap();
        host_b.accept("device-a", id_b, 2).unwrap();

        // Interleave the two independently accepted downloads, just as each peer's
        // transfer worker does while playback control continues over the same session.
        let mut received_by_b = Vec::new();
        let mut received_by_a = Vec::new();
        let mut offset_a = 0;
        let mut offset_b = 0;
        while offset_a < bytes_a.len() as u64 || offset_b < bytes_b.len() as u64 {
            if offset_a < bytes_a.len() as u64 {
                let part = host_a
                    .download_chunk("device-b", id_a, &format!("offset={offset_a}"), 3)
                    .unwrap();
                let chunk = super::super::crypto::unb64(part["data"].as_str().unwrap()).unwrap();
                offset_a += chunk.len() as u64;
                received_by_b.extend(chunk);
            }
            if offset_b < bytes_b.len() as u64 {
                let part = host_b
                    .download_chunk("device-a", id_b, &format!("offset={offset_b}"), 3)
                    .unwrap();
                let chunk = super::super::crypto::unb64(part["data"].as_str().unwrap()).unwrap();
                offset_b += chunk.len() as u64;
                received_by_a.extend(chunk);
            }
        }

        assert_eq!(received_by_b, bytes_a);
        assert_eq!(received_by_a, bytes_b);
        let hash_a = hex(&Sha256::digest(&bytes_a));
        let hash_b = hex(&Sha256::digest(&bytes_b));
        assert_eq!(
            host_a
                .complete_download("device-b", id_a, &json!({ "sha256": hash_a }), 4)
                .unwrap()["status"],
            "complete"
        );
        assert_eq!(
            host_b
                .complete_download("device-a", id_b, &json!({ "sha256": hash_b }), 4)
                .unwrap()["status"],
            "complete"
        );
        let _ = std::fs::remove_dir_all(root);
    }
}
