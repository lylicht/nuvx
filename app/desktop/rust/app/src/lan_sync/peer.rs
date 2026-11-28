//! Client side of LAN Sync v1. Every request is pinned HTTPS, and paired routes
//! retain the shared AES-GCM envelope in addition to TLS.

use super::{crypto, tls};
use anyhow::{Context, bail, ensure};
use rustls::ClientConfig;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use url::Url;

const MAX_RESPONSE: usize = 2 * 1024 * 1024;
const MAX_HEADER_LINE: usize = 8192;
const DISCOVERY_WORKERS: usize = 48;
const DISCOVERY_TIMEOUT: Duration = Duration::from_millis(300);
const OUTGOING_STORE_VERSION: u32 = 1;
const MAX_OUTGOING_RECORDS: usize = 32;
const MAX_OUTGOING_STORE_BYTES: u64 = 64 * 1024;
static OUTGOING_STORE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutgoingTransfer {
    pub id: String,
    pub host_id: String,
    pub device_id: String,
    pub name: String,
    pub size: u64,
    pub mime: String,
    pub sha256: String,
    modified_ns: Option<u128>,
}

#[derive(Serialize, Deserialize)]
struct OutgoingTransferStore {
    version: u32,
    transfers: Vec<OutgoingTransfer>,
}

#[derive(Clone)]
struct AudioSource {
    path: PathBuf,
    name: String,
    size: u64,
    mime: &'static str,
    sha256: String,
    modified_ns: Option<u128>,
}

/// Pending Rust-to-peer sends survive app restarts without persisting the selected file's path.
pub fn outgoing_transfers(data_dir: &Path) -> Vec<OutgoingTransfer> {
    let _guard = OUTGOING_STORE_LOCK
        .get_or_init(|| Mutex::new(()))
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    load_outgoing_unlocked(data_dir)
}

pub fn remove_outgoing_transfer(data_dir: &Path, id: &str) -> anyhow::Result<()> {
    ensure!(super::files::valid_id(id), "invalid transfer id");
    let _guard = OUTGOING_STORE_LOCK
        .get_or_init(|| Mutex::new(()))
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let mut transfers = load_outgoing_unlocked(data_dir);
    transfers.retain(|transfer| transfer.id != id);
    persist_outgoing_unlocked(data_dir, &transfers)
}

fn upsert_outgoing_transfer(
    data_dir: &Path,
    transfer: &OutgoingTransfer,
) -> anyhow::Result<(OutgoingTransfer, bool)> {
    let _guard = OUTGOING_STORE_LOCK
        .get_or_init(|| Mutex::new(()))
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let mut transfers = load_outgoing_unlocked(data_dir);
    if let Some(existing) = transfers.iter().find(|existing| {
        existing.host_id == transfer.host_id
            && existing.device_id == transfer.device_id
            && same_source_metadata(existing, transfer)
    }) {
        return Ok((existing.clone(), false));
    }
    ensure!(
        !transfers.iter().any(|existing| {
            existing.host_id == transfer.host_id && existing.device_id == transfer.device_id
        }),
        "resume or cancel the unfinished send to this device first"
    );
    ensure!(
        transfers.len() < MAX_OUTGOING_RECORDS,
        "too many unfinished sends; cancel one before sending another"
    );
    transfers.push(transfer.clone());
    persist_outgoing_unlocked(data_dir, &transfers)?;
    Ok((transfer.clone(), true))
}

fn load_outgoing_unlocked(data_dir: &Path) -> Vec<OutgoingTransfer> {
    let path = data_dir.join("lan_sync_outgoing.json");
    let Ok(meta) = std::fs::symlink_metadata(&path) else {
        return Vec::new();
    };
    if !meta.is_file() || super::files::is_link(&meta) || meta.len() > MAX_OUTGOING_STORE_BYTES {
        return Vec::new();
    }
    let Ok(bytes) = std::fs::read(path) else {
        return Vec::new();
    };
    let Ok(store) = serde_json::from_slice::<OutgoingTransferStore>(&bytes) else {
        return Vec::new();
    };
    if store.version != OUTGOING_STORE_VERSION {
        return Vec::new();
    }
    store
        .transfers
        .into_iter()
        .take(MAX_OUTGOING_RECORDS)
        .filter(valid_outgoing_transfer)
        .collect()
}

fn persist_outgoing_unlocked(
    data_dir: &Path,
    transfers: &[OutgoingTransfer],
) -> anyhow::Result<()> {
    std::fs::create_dir_all(data_dir)?;
    let directory = std::fs::symlink_metadata(data_dir)?;
    ensure!(
        directory.is_dir() && !super::files::is_link(&directory),
        "VYRAL data folder must not be a symlink"
    );
    let path = data_dir.join("lan_sync_outgoing.json");
    if transfers.is_empty() {
        match std::fs::symlink_metadata(&path) {
            Ok(meta) => {
                ensure!(
                    meta.is_file() && !super::files::is_link(&meta),
                    "outgoing transfer state is not a regular file"
                );
                std::fs::remove_file(path)?;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        return Ok(());
    }
    ensure!(
        transfers.len() <= MAX_OUTGOING_RECORDS,
        "too many unfinished sends"
    );
    if let Ok(meta) = std::fs::symlink_metadata(&path) {
        ensure!(
            meta.is_file() && !super::files::is_link(&meta),
            "outgoing transfer state is not a regular file"
        );
    }
    let bytes = serde_json::to_vec(&OutgoingTransferStore {
        version: OUTGOING_STORE_VERSION,
        transfers: transfers.to_vec(),
    })?;
    ensure!(
        bytes.len() as u64 <= MAX_OUTGOING_STORE_BYTES,
        "outgoing transfer state is too large"
    );
    let temp = data_dir.join("lan_sync_outgoing.json.tmp");
    if let Ok(meta) = std::fs::symlink_metadata(&temp) {
        ensure!(
            meta.is_file() && !super::files::is_link(&meta),
            "outgoing transfer temporary file is not regular"
        );
    }
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(&temp)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    std::fs::rename(temp, path)?;
    Ok(())
}

fn valid_outgoing_transfer(transfer: &OutgoingTransfer) -> bool {
    super::files::valid_id(&transfer.id)
        && valid_id(&transfer.host_id)
        && valid_id(&transfer.device_id)
        && super::files::safe_audio_name(&transfer.name).as_deref() == Some(transfer.name.as_str())
        && super::files::audio_mime(&transfer.name) == Some(transfer.mime.as_str())
        && (1..=super::files::MAX_TRANSFER).contains(&transfer.size)
        && transfer.sha256.len() == 64
        && transfer.sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn same_source_metadata(left: &OutgoingTransfer, right: &OutgoingTransfer) -> bool {
    left.name == right.name
        && left.size == right.size
        && left.mime == right.mime
        && left.sha256 == right.sha256
        && left.modified_ns == right.modified_ns
}

fn selected_audio(path: &Path) -> anyhow::Result<AudioSource> {
    let metadata = std::fs::symlink_metadata(path)?;
    ensure!(
        metadata.is_file() && !super::files::is_link(&metadata),
        "selected file is not a regular file"
    );
    ensure!(
        (1..=super::files::MAX_TRANSFER).contains(&metadata.len()),
        "selected file exceeds the transfer size limit"
    );
    let path = path.canonicalize()?;
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .and_then(super::files::safe_audio_name)
        .context("select a supported audio file")?;
    let mime = super::files::audio_mime(&name).context("unsupported audio type")?;
    ensure!(
        super::files::audio_header_matches(&path, mime),
        "selected file does not match its audio type"
    );
    let metadata = std::fs::symlink_metadata(&path)?;
    ensure!(
        metadata.is_file() && !super::files::is_link(&metadata),
        "selected file changed while it was being inspected"
    );
    let size = metadata.len();
    let modified_ns = metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|duration| duration.as_nanos());
    let sha256 = super::files::hash_file(&path)?;
    let after_hash = std::fs::symlink_metadata(&path)?;
    ensure!(
        after_hash.is_file()
            && !super::files::is_link(&after_hash)
            && after_hash.len() == size
            && after_hash
                .modified()
                .ok()
                .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|duration| duration.as_nanos())
                == modified_ns,
        "selected file changed while it was being inspected"
    );
    Ok(AudioSource {
        path,
        name,
        size,
        mime,
        sha256,
        modified_ns,
    })
}

impl AudioSource {
    fn matches(&self, transfer: &OutgoingTransfer) -> bool {
        self.name == transfer.name
            && self.size == transfer.size
            && self.mime == transfer.mime
            && self.sha256 == transfer.sha256
            && self.modified_ns == transfer.modified_ns
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Invite {
    #[serde(rename = "type")]
    pub kind: String,
    pub version: u64,
    pub url: String,
    pub tls_fingerprint: String,
}

impl Invite {
    pub fn parse(raw: &str) -> anyhow::Result<Self> {
        let invite: Self = serde_json::from_str(raw).context("invalid VYRAL pairing payload")?;
        ensure!(
            invite.kind == "vyral-lan-pairing",
            "not a VYRAL LAN pairing payload"
        );
        ensure!(invite.version == 1, "unsupported LAN pairing version");
        tls::parse_fingerprint(&invite.tls_fingerprint)?;
        ensure!(
            invite
                .tls_fingerprint
                .bytes()
                .all(|b| !b.is_ascii_uppercase()),
            "TLS fingerprint must use lowercase hexadecimal"
        );
        validate_url(&invite.url)?;
        Ok(invite)
    }

    pub fn encode(&self) -> anyhow::Result<String> {
        Ok(serde_json::to_string(self)?)
    }
}

/// Discovers a nearby VYRAL host by its public four-digit display code. Discovery locates and pins the host;
/// pairing still displays the six-digit SAS and requires approval on the host.
pub fn discover_by_code(code: &str) -> anyhow::Result<Invite> {
    validate_discovery_code(code)?;
    let local = super::host::lan_address()
        .context("connect to a local IPv4 network before searching for a host")?;
    let IpAddr::V4(local) = local else {
        bail!("nearby host discovery requires a local IPv4 address");
    };
    let octets = local.octets();
    let mut targets = Vec::with_capacity(253 * super::host::DISCOVERY_PORT_COUNT as usize);
    for last in 1..=254 {
        if last == octets[3] {
            continue;
        }
        let address = IpAddr::V4(std::net::Ipv4Addr::new(
            octets[0], octets[1], octets[2], last,
        ));
        for port in
            super::host::DEFAULT_PORT..super::host::DEFAULT_PORT + super::host::DISCOVERY_PORT_COUNT
        {
            targets.push(SocketAddr::new(address, port));
        }
    }

    discover_from_targets(&targets, code)
}

fn validate_discovery_code(code: &str) -> anyhow::Result<()> {
    ensure!(
        code.len() == 4 && code.bytes().all(|byte| byte.is_ascii_digit()),
        "enter the four-digit code shown by the host"
    );
    Ok(())
}

fn discover_from_targets(targets: &[SocketAddr], code: &str) -> anyhow::Result<Invite> {
    discover_from_targets_with_workers(targets, code, DISCOVERY_WORKERS)
}

fn discover_from_targets_with_workers(
    targets: &[SocketAddr],
    code: &str,
    workers: usize,
) -> anyhow::Result<Invite> {
    validate_discovery_code(code)?;
    let client = tls::discovery_client()?;
    let next = AtomicUsize::new(0);
    let finished = AtomicBool::new(false);
    let found = Mutex::new(None);
    std::thread::scope(|scope| {
        for _ in 0..workers.min(targets.len()).max(1) {
            let next = &next;
            let finished = &finished;
            let found = &found;
            let client = client.clone();
            scope.spawn(move || {
                loop {
                    if finished.load(Ordering::Relaxed) {
                        break;
                    }
                    let index = next.fetch_add(1, Ordering::Relaxed);
                    let Some(address) = targets.get(index).copied() else {
                        break;
                    };
                    if let Some(invite) = probe_discovery_host(address, code, client.clone()) {
                        let mut result = found.lock().unwrap_or_else(|error| error.into_inner());
                        if result.is_none() {
                            *result = Some(invite);
                            finished.store(true, Ordering::Relaxed);
                        }
                        break;
                    }
                }
            });
        }
    });
    found
        .into_inner()
        .unwrap_or_else(|error| error.into_inner())
        .context("no VYRAL host with that code was found on this IPv4 /24")
}

fn probe_discovery_host(
    address: SocketAddr,
    code: &str,
    client: Arc<ClientConfig>,
) -> Option<Invite> {
    let stream = TcpStream::connect_timeout(&address, DISCOVERY_TIMEOUT).ok()?;
    let mut tls_stream = tls::connect_timeout(stream, client, DISCOVERY_TIMEOUT).ok()?;
    write_http_request(&mut tls_stream, address, "GET", "/lan/hello", &[], &[]).ok()?;
    let mut reader = BufReader::new(tls_stream);
    let (status, hello) = read_http_response(&mut reader).ok()?;
    if status != 200 {
        return None;
    }
    let certificate = reader.get_ref().conn.peer_certificates()?.first()?;
    let fingerprint = tls::fingerprint(certificate.as_ref());
    invite_from_discovery(address, &hello, &fingerprint, code).ok()
}

fn invite_from_discovery(
    address: SocketAddr,
    hello: &Value,
    observed_fingerprint: &str,
    code: &str,
) -> anyhow::Result<Invite> {
    ensure!(hello["app"] == "vyral", "peer is not a VYRAL host");
    ensure!(
        hello["api"].as_u64() == Some(super::host::API_VERSION),
        "unsupported LAN host API"
    );
    ensure!(
        hello["version"].as_u64() == Some(1),
        "unsupported LAN host version"
    );
    ensure!(
        hello["code"].as_str() == Some(code),
        "host discovery code does not match"
    );
    let host_id = hello["id"].as_str().context("host id is missing")?;
    ensure!(valid_id(host_id), "host id is invalid");
    let fingerprint = hello["tlsFingerprint"]
        .as_str()
        .context("host TLS fingerprint is missing")?;
    tls::parse_fingerprint(fingerprint)?;
    ensure!(
        fingerprint.eq_ignore_ascii_case(observed_fingerprint),
        "host hello fingerprint does not match its TLS certificate"
    );
    ensure!(
        hello["port"].as_u64() == Some(address.port() as u64),
        "host hello port does not match the responding port"
    );
    Ok(Invite {
        kind: "vyral-lan-pairing".into(),
        version: 1,
        url: format!("https://{}:{}", address.ip(), address.port()),
        tls_fingerprint: observed_fingerprint.to_ascii_lowercase(),
    })
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedPeer {
    pub url: String,
    pub tls_fingerprint: String,
    pub host_id: String,
    pub host_name: String,
    pub device_id: String,
    pub space_id: String,
    #[serde(skip, default)]
    key: [u8; 32],
}

const PEERS_VAULT: &str = "lan_sync_joined_peers";

pub fn load(data_dir: &Path) -> Vec<PairedPeer> {
    let path = data_dir.join("lan_sync_joined.json");
    let Ok(bytes) = std::fs::read(path) else {
        return Vec::new();
    };
    let Ok(mut peers) = serde_json::from_slice::<Vec<PairedPeer>>(&bytes) else {
        return Vec::new();
    };
    peers.retain_mut(|peer| {
        if !valid_id(&peer.host_id) || !valid_id(&peer.device_id) {
            return false;
        }
        let Some(secret) = crate::vault::get(&peer_key_name(peer), data_dir) else {
            return false;
        };
        let Ok(key) = <[u8; 32]>::try_from(secret) else {
            return false;
        };
        peer.key = key;
        Endpoint::new(&peer.url, &peer.tls_fingerprint).is_ok()
    });
    peers
}

pub fn save(peer: &PairedPeer, data_dir: &Path) -> anyhow::Result<()> {
    ensure!(
        valid_id(&peer.host_id) && valid_id(&peer.device_id),
        "invalid paired host identity"
    );
    crate::vault::set(&peer_key_name(peer), data_dir, &peer.key)?;
    let mut peers = load(data_dir);
    if let Some(existing) = peers.iter_mut().find(|p| p.host_id == peer.host_id) {
        *existing = peer.clone();
    } else {
        peers.push(peer.clone());
    }
    save_list(&peers, data_dir)
}

pub fn remove(host_id: &str, data_dir: &Path) -> anyhow::Result<()> {
    let mut peers = load(data_dir);
    if let Some(index) = peers.iter().position(|p| p.host_id == host_id) {
        let peer = peers.remove(index);
        crate::vault::delete(&peer_key_name(&peer), data_dir);
    }
    save_list(&peers, data_dir)
}

fn save_list(peers: &[PairedPeer], data_dir: &Path) -> anyhow::Result<()> {
    std::fs::create_dir_all(data_dir)?;
    let path = data_dir.join("lan_sync_joined.json");
    let temp = path.with_extension("json.tmp");
    std::fs::write(&temp, serde_json::to_vec(peers)?)?;
    std::fs::rename(temp, path)?;
    Ok(())
}

fn peer_key_name(peer: &PairedPeer) -> String {
    format!("{PEERS_VAULT} {} {}", peer.host_id, peer.device_id)
}

fn valid_id(value: &str) -> bool {
    (16..=64).contains(&value.len()) && value.bytes().all(|b| b.is_ascii_hexdigit())
}

pub struct PairingSession {
    endpoint: Endpoint,
    pairing_id: String,
    key: [u8; 32],
    pub sas: String,
    pub host_id: String,
    pub host_name: String,
}

impl PairingSession {
    /// Starts pairing only after validating and pinning the supplied HTTPS invitation.
    pub fn start(invite: &Invite, device_name: &str) -> anyhow::Result<Self> {
        ensure!(
            !device_name.trim().is_empty() && device_name.chars().count() <= 80,
            "device name must be 1–80 characters"
        );
        let endpoint = Endpoint::new(&invite.url, &invite.tls_fingerprint)?;
        let (_, hello) = endpoint.request("GET", "/lan/hello", &[], None)?;
        ensure!(hello["app"] == "vyral", "peer is not a VYRAL host");
        ensure!(hello["api"].as_u64() == Some(1), "unsupported LAN host API");
        ensure!(
            hello["tlsFingerprint"].as_str() == Some(invite.tls_fingerprint.as_str()),
            "host fingerprint does not match the pairing invitation"
        );
        let host_id = hello["id"]
            .as_str()
            .context("host id is missing")?
            .to_owned();
        let host_name = hello["name"]
            .as_str()
            .context("host name is missing")?
            .to_owned();

        let phone_key = crypto::PhoneKey::new();
        let commitment = crypto::sha256_hex(&phone_key.public);
        let (status, started) = endpoint.request(
            "POST",
            "/lan/pair/start",
            &[],
            Some(&json!({ "deviceName": device_name.trim(), "commit": commitment })),
        )?;
        ensure!(status == 201, "host rejected pairing: {started}");
        let pairing_id = started["pairingId"]
            .as_str()
            .context("pairing id is missing")?
            .to_owned();
        let host_key = started["hostKey"]
            .as_str()
            .and_then(crypto::unb64)
            .context("host public key is invalid")?;
        ensure!(
            started["hostId"].as_str() == Some(host_id.as_str()),
            "host identity changed"
        );
        let (key, sas) = phone_key
            .agree(&host_key)
            .context("host public key is invalid")?;
        let (status, revealed) = endpoint.request(
            "POST",
            "/lan/pair/reveal",
            &[],
            Some(&json!({ "pairingId": pairing_id, "deviceKey": crypto::b64(&phone_key.public) })),
        )?;
        ensure!(
            status == 200 && revealed["ok"] == true,
            "host rejected the pairing reveal"
        );
        Ok(Self {
            endpoint,
            pairing_id,
            key,
            sas,
            host_id,
            host_name,
        })
    }

    /// Call only after the person has compared the displayed six-digit code and confirmed it.
    pub fn claim(&self) -> anyhow::Result<Option<PairedPeer>> {
        let (status, reply) = self.endpoint.request(
            "POST",
            "/lan/pair/claim",
            &[],
            Some(&json!({ "pairingId": self.pairing_id })),
        )?;
        if status == 202 && reply["pending"] == true {
            return Ok(None);
        }
        ensure!(status == 200, "host refused pairing claim: {reply}");
        let device_id = reply["deviceId"].as_str().context("device id is missing")?;
        ensure!(
            reply["hostId"].as_str() == Some(self.host_id.as_str()),
            "host identity changed"
        );
        Ok(Some(PairedPeer {
            url: self.endpoint.url.to_string(),
            tls_fingerprint: self.endpoint.fingerprint.clone(),
            host_id: self.host_id.clone(),
            host_name: reply["hostName"]
                .as_str()
                .unwrap_or(&self.host_name)
                .to_owned(),
            device_id: device_id.to_owned(),
            space_id: reply["spaceId"].as_str().unwrap_or_default().to_owned(),
            key: self.key,
        }))
    }
}

pub struct Client {
    endpoint: Endpoint,
    peer: PairedPeer,
}

impl Client {
    pub fn new(peer: PairedPeer) -> anyhow::Result<Self> {
        let endpoint = Endpoint::new(&peer.url, &peer.tls_fingerprint)?;
        Ok(Self { endpoint, peer })
    }

    pub fn get(&self, path: &str) -> anyhow::Result<Value> {
        self.sealed("GET", path, Value::Null)
    }

    pub fn post(&self, path: &str, body: Value) -> anyhow::Result<Value> {
        self.sealed("POST", path, body)
    }

    pub fn delete(&self, path: &str) -> anyhow::Result<Value> {
        self.sealed("DELETE", path, Value::Null)
    }

    pub fn transfers(&self) -> anyhow::Result<Vec<Value>> {
        self.get("/v1/files/transfers")?["transfers"]
            .as_array()
            .cloned()
            .context("host returned an invalid transfer list")
    }

    pub fn transfer(&self, id: &str) -> anyhow::Result<Value> {
        ensure!(super::files::valid_id(id), "invalid transfer id");
        self.get(&format!("/v1/files/transfers/{id}"))
    }

    pub fn accept_transfer(&self, id: &str) -> anyhow::Result<Value> {
        ensure!(super::files::valid_id(id), "invalid transfer id");
        self.post(&format!("/v1/files/transfers/{id}/accept"), Value::Null)
    }

    pub fn decline_transfer(&self, id: &str) -> anyhow::Result<Value> {
        ensure!(super::files::valid_id(id), "invalid transfer id");
        self.post(&format!("/v1/files/transfers/{id}/decline"), Value::Null)
    }

    pub fn cancel_transfer(&self, id: &str) -> anyhow::Result<Value> {
        ensure!(super::files::valid_id(id), "invalid transfer id");
        self.delete(&format!("/v1/files/transfers/{id}"))
    }

    /// Sends a selected file and persists its transfer identity before contacting the peer.
    pub fn send_file(
        &self,
        path: &Path,
        data_dir: &Path,
        cancelled: &AtomicBool,
    ) -> anyhow::Result<String> {
        let source = selected_audio(path)?;
        let candidate = OutgoingTransfer {
            id: super::files::new_id(),
            host_id: self.peer.host_id.clone(),
            device_id: self.peer.device_id.clone(),
            name: source.name.clone(),
            size: source.size,
            mime: source.mime.to_owned(),
            sha256: source.sha256.clone(),
            modified_ns: source.modified_ns,
        };
        let (transfer, created) = upsert_outgoing_transfer(data_dir, &candidate)?;
        ensure!(
            transfer.host_id == self.peer.host_id
                && transfer.device_id == self.peer.device_id
                && source.matches(&transfer),
            "selected file does not match the saved transfer"
        );
        self.send_file_transfer(&transfer, &source, data_dir, created, cancelled)
    }

    /// Resumes a persisted send after the user selects the same local file again.
    pub fn resume_file(
        &self,
        id: &str,
        path: &Path,
        data_dir: &Path,
        cancelled: &AtomicBool,
    ) -> anyhow::Result<String> {
        ensure!(super::files::valid_id(id), "invalid transfer id");
        let transfer = outgoing_transfers(data_dir)
            .into_iter()
            .find(|transfer| transfer.id == id)
            .context("saved send was not found; start a new send")?;
        ensure!(
            transfer.host_id == self.peer.host_id && transfer.device_id == self.peer.device_id,
            "saved send belongs to a different paired device"
        );
        let source = selected_audio(path)?;
        ensure!(
            source.matches(&transfer),
            "selected file no longer matches the saved transfer"
        );
        self.send_file_transfer(&transfer, &source, data_dir, false, cancelled)
    }

    fn send_file_transfer(
        &self,
        transfer: &OutgoingTransfer,
        source: &AudioSource,
        data_dir: &Path,
        created: bool,
        cancelled: &AtomicBool,
    ) -> anyhow::Result<String> {
        let id = transfer.id.as_str();
        let result = (|| {
            if created {
                let title = Path::new(&source.name)
                    .file_stem()
                    .and_then(|name| name.to_str())
                    .unwrap_or_default();
                let offer = self.post(
                    "/v1/files/transfers/offer",
                    json!({
                        "id": id,
                        "name": source.name,
                        "size": source.size,
                        "mime": source.mime,
                        "sha256": source.sha256,
                        "title": title,
                        "artist": ""
                    }),
                )?;
                ensure!(
                    offer["id"] == id && offer["status"] == "pending",
                    "host did not create the file offer: {offer}"
                );
            }

            let mut status = self.transfer(id)?;
            validate_upload_status(&status, transfer)?;
            for _ in 0..1800 {
                if cancelled.load(Ordering::Relaxed) {
                    bail!("transfer cancelled");
                }
                match status["status"].as_str() {
                    Some("accepted") | Some("receiving") => break,
                    Some("complete") => return Ok(id.to_owned()),
                    Some("declined" | "cancelled" | "failed") => {
                        bail!("the peer ended this transfer")
                    }
                    Some("pending") => std::thread::sleep(Duration::from_secs(1)),
                    _ => bail!("file offer expired or is no longer resumable"),
                }
                status = self.transfer(id)?;
                validate_upload_status(&status, transfer)?;
            }
            ensure!(
                matches!(
                    status["status"].as_str(),
                    Some("accepted" | "receiving" | "complete")
                ),
                "recipient did not accept the file offer in time"
            );
            if status["status"] == "complete" {
                return Ok(id.to_owned());
            }

            let mut input = std::fs::File::open(&source.path)?;
            let mut offset = upload_offset(&status, transfer)?;
            let mut buffer = vec![0; super::files::CHUNK];
            while offset < source.size {
                if cancelled.load(Ordering::Relaxed) {
                    bail!("transfer cancelled");
                }
                input.seek(SeekFrom::Start(offset))?;
                let count = buffer.len().min((source.size - offset) as usize);
                input.read_exact(&mut buffer[..count])?;
                let reply = self.post(
                    &format!("/v1/files/transfers/{id}/chunk"),
                    json!({ "offset": offset, "data": crypto::b64(&buffer[..count]) }),
                )?;
                offset += count as u64;
                ensure!(
                    reply["received"].as_u64() == Some(offset) || reply["done"] == true,
                    "recipient did not acknowledge the uploaded chunk"
                );
            }
            ensure!(
                selected_audio(&source.path)?.matches(transfer),
                "selected file changed during transfer"
            );
            Ok(id.to_owned())
        })();
        if cancelled.load(Ordering::Relaxed) {
            let _ = self.cancel_transfer(id);
            remove_outgoing_transfer(data_dir, id)?;
            return Err(anyhow::anyhow!("transfer cancelled"));
        }
        match result {
            Ok(id) => {
                remove_outgoing_transfer(data_dir, &id)?;
                Ok(id)
            }
            Err(error) => {
                if error.to_string().contains("the peer ended this transfer") {
                    remove_outgoing_transfer(data_dir, id)?;
                }
                Err(error)
            }
        }
    }

    /// Downloads a host-selected file after the local user has accepted its offer.
    pub fn download_file(
        &self,
        id: &str,
        received_dir: &Path,
        cancelled: &AtomicBool,
    ) -> anyhow::Result<PathBuf> {
        ensure!(super::files::valid_id(id), "invalid transfer id");
        let status = self.get(&format!("/v1/files/transfers/{id}"))?;
        ensure!(
            status["direction"] == "download",
            "this is not an incoming file offer"
        );
        let size = status["size"].as_u64().context("file size missing")?;
        ensure!(
            (1..=super::files::MAX_TRANSFER).contains(&size),
            "incoming file exceeds the transfer size limit"
        );
        let name = status["name"]
            .as_str()
            .and_then(super::files::safe_audio_name)
            .context("host offered an unsafe or unsupported filename")?;
        let reported_mime = status["mime"].as_str().context("file type missing")?;
        let mime = super::files::compatible_audio_mime(&name, reported_mime)
            .context("file type does not match its filename")?;
        let expected = status["sha256"]
            .as_str()
            .filter(|v| v.len() == 64 && v.bytes().all(|b| b.is_ascii_hexdigit()))
            .context("file hash missing")?
            .to_ascii_lowercase();
        std::fs::create_dir_all(received_dir)?;
        let directory_meta = std::fs::symlink_metadata(received_dir)?;
        ensure!(
            directory_meta.is_dir() && !super::files::is_link(&directory_meta),
            "received folder must not be a symlink"
        );
        let directory = received_dir.canonicalize()?;
        let temp = directory.join(format!(".vyral-{id}.part"));
        let destination = directory.join(super::files::received_name(&name, id));
        if status["status"] == "complete" {
            ensure!(
                completed_download_matches(&destination, size, &expected, mime),
                "the host reports this transfer complete but the verified local file is missing"
            );
            let _ = std::fs::remove_file(&temp);
            return Ok(destination);
        }
        ensure!(
            status["status"] == "accepted" || status["status"] == "sending",
            "accept the offer before receiving data"
        );
        let mut offset = match std::fs::symlink_metadata(&temp) {
            Ok(meta) if meta.is_file() && !super::files::is_link(&meta) && meta.len() <= size => {
                meta.len()
            }
            Ok(_) => anyhow::bail!("the saved partial file is not a safe resumable file"),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => 0,
            Err(error) => return Err(error.into()),
        };
        let mut remote_completed = false;
        let mut integrity_error = false;
        let mut created_destination = false;
        let result = (|| {
            let mut output = std::fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(false)
                .open(&temp)?;
            output.seek(SeekFrom::Start(offset))?;
            let mut hasher = Sha256::new();
            let mut existing = std::fs::File::open(&temp)?;
            let mut prefix = vec![0; super::files::CHUNK];
            loop {
                let count = existing.read(&mut prefix)?;
                if count == 0 {
                    break;
                }
                hasher.update(&prefix[..count]);
            }
            while offset < size {
                if cancelled.load(Ordering::Relaxed) {
                    bail!("transfer cancelled");
                }
                let chunk = self.get(&format!("/v1/files/transfers/{id}/chunk?offset={offset}"))?;
                let Some(bytes) = chunk["data"].as_str().and_then(crypto::unb64) else {
                    integrity_error = true;
                    bail!("invalid file chunk");
                };
                if chunk["id"].as_str() != Some(id)
                    || chunk["offset"].as_u64() != Some(offset)
                    || chunk["size"].as_u64() != Some(size)
                    || chunk["sha256"].as_str() != Some(expected.as_str())
                    || bytes.is_empty()
                    || bytes.len() > super::files::CHUNK
                    || offset + bytes.len() as u64 > size
                {
                    integrity_error = true;
                    bail!("file chunk metadata or size changed during download");
                }
                hasher.update(&bytes);
                output.write_all(&bytes)?;
                output.sync_data()?;
                offset += bytes.len() as u64;
            }
            output.sync_all()?;
            if hex(&hasher.finalize()) != expected {
                integrity_error = true;
                bail!("downloaded file hash does not match");
            }
            if !super::files::audio_header_matches(&temp, mime) {
                integrity_error = true;
                bail!("downloaded file is not valid audio");
            }
            if cancelled.load(Ordering::Relaxed) {
                bail!("transfer cancelled");
            }
            if !completed_download_matches(&destination, size, &expected, mime) {
                ensure!(
                    std::fs::symlink_metadata(&destination)
                        .is_err_and(|error| error.kind() == std::io::ErrorKind::NotFound),
                    "a different file already uses the transfer's destination"
                );
                let mut input = std::fs::File::open(&temp)?;
                let mut output = std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&destination)?;
                std::io::copy(&mut input, &mut output)?;
                output.sync_all()?;
                created_destination = true;
            }
            let complete = self.post(
                &format!("/v1/files/transfers/{id}/complete"),
                json!({ "sha256": expected }),
            )?;
            ensure!(
                complete["done"] == true,
                "host did not confirm the completed transfer"
            );
            remote_completed = true;
            let _ = std::fs::remove_file(&temp);
            Ok(destination.clone())
        })();
        if !remote_completed && (cancelled.load(Ordering::Relaxed) || integrity_error) {
            let _ = std::fs::remove_file(&temp);
            if created_destination {
                let _ = std::fs::remove_file(&destination);
            }
            let _ = self.cancel_transfer(id);
        }
        result
    }

    pub fn playback(&self) -> anyhow::Result<Value> {
        self.get("/v1/connect")
    }

    pub fn control(&self, action: &str) -> anyhow::Result<Value> {
        ensure!(
            Self::control_action_valid(action),
            "unsupported remote playback action"
        );
        self.post("/v1/connect", json!({ "action": action }))
    }

    fn control_action_valid(action: &str) -> bool {
        ["play", "pause", "toggle", "next", "previous"].contains(&action)
    }

    fn sealed(&self, method: &str, path: &str, body: Value) -> anyhow::Result<Value> {
        ensure!(
            path.starts_with('/') && !path.contains(['\r', '\n', ' ']),
            "invalid route"
        );
        let nonce = crypto::sha256_hex(&crypto::random_bytes::<16>());
        let plain =
            json!({ "m": method, "p": path, "b": body, "n": nonce, "t": super::host::now_ms() });
        let ciphertext = crypto::seal(
            &self.peer.key,
            self.peer.device_id.as_bytes(),
            plain.to_string().as_bytes(),
        );
        let (outer_status, response) = self.endpoint.request_bytes(
            "POST",
            "/lan/v1",
            &[("x-vyral-device", self.peer.device_id.as_str())],
            ciphertext.as_bytes(),
        )?;
        ensure!(
            outer_status == 200,
            "paired request was rejected: {response}"
        );
        let sealed = response.as_str().context("invalid sealed response")?;
        let opened = crypto::open(
            &self.peer.key,
            format!("resp:{}", self.peer.device_id).as_bytes(),
            sealed,
        )
        .context("could not authenticate the host response")?;
        let envelope: Value = serde_json::from_slice(&opened)?;
        ensure!(
            envelope["n"].as_str() == Some(nonce.as_str()),
            "response nonce mismatch"
        );
        let status = envelope["s"]
            .as_u64()
            .context("response status is missing")?;
        let reply = envelope["b"].clone();
        ensure!(status < 400, "host refused request ({status}): {reply}");
        Ok(reply)
    }
}

fn validate_upload_status(status: &Value, transfer: &OutgoingTransfer) -> anyhow::Result<()> {
    ensure!(
        status["id"].as_str() == Some(transfer.id.as_str())
            && status["direction"] == "upload"
            && status["name"].as_str() == Some(transfer.name.as_str())
            && status["size"].as_u64() == Some(transfer.size)
            && status["mime"].as_str() == Some(transfer.mime.as_str())
            && status["sha256"]
                .as_str()
                .is_some_and(|hash| hash.eq_ignore_ascii_case(&transfer.sha256)),
        "host transfer metadata no longer matches the saved send"
    );
    let received = status["received"]
        .as_u64()
        .context("host transfer offset is missing")?;
    ensure!(
        received <= transfer.size,
        "host transfer offset exceeds the selected file"
    );
    match status["status"].as_str() {
        Some(
            "pending" | "accepted" | "receiving" | "complete" | "declined" | "cancelled" | "failed",
        ) => {}
        _ => anyhow::bail!("host returned an unknown transfer status"),
    }
    if status["status"] == "complete" {
        ensure!(
            received == transfer.size,
            "host marked an incomplete upload as complete"
        );
    }
    Ok(())
}

fn upload_offset(status: &Value, transfer: &OutgoingTransfer) -> anyhow::Result<u64> {
    validate_upload_status(status, transfer)?;
    let offset = status["received"]
        .as_u64()
        .context("host transfer offset is missing")?;
    ensure!(
        offset <= transfer.size,
        "host transfer offset exceeds file size"
    );
    Ok(offset)
}

fn completed_download_matches(path: &Path, size: u64, sha256: &str, mime: &str) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|metadata| {
        metadata.is_file()
            && !super::files::is_link(&metadata)
            && metadata.len() == size
            && super::files::audio_header_matches(path, mime)
            && super::files::hash_file(path).is_ok_and(|actual| actual == sha256)
    })
}

struct Endpoint {
    url: Url,
    address: SocketAddr,
    fingerprint: String,
    client: Arc<ClientConfig>,
}

impl Endpoint {
    fn new(raw_url: &str, fingerprint: &str) -> anyhow::Result<Self> {
        let url = validate_url(raw_url)?;
        let host = url.host_str().context("host address is missing")?;
        let ip: IpAddr = host
            .parse()
            .context("LAN pairing URL must use a private IP address")?;
        ensure!(
            super::host::is_local_peer(ip),
            "LAN pairing URL must use a private network address"
        );
        let port = url
            .port()
            .context("LAN pairing URL must include its port")?;
        let address = SocketAddr::new(ip, port);
        let fingerprint = fingerprint.to_ascii_lowercase();
        let client = tls::pinned_client(&fingerprint)?;
        Ok(Self {
            url,
            address,
            fingerprint,
            client,
        })
    }

    fn request(
        &self,
        method: &str,
        path: &str,
        headers: &[(&str, &str)],
        body: Option<&Value>,
    ) -> anyhow::Result<(u16, Value)> {
        let body = body.map_or_else(Vec::new, |v| v.to_string().into_bytes());
        self.request_bytes(method, path, headers, &body)
    }

    fn request_bytes(
        &self,
        method: &str,
        path: &str,
        headers: &[(&str, &str)],
        body: &[u8],
    ) -> anyhow::Result<(u16, Value)> {
        ensure!(
            path.starts_with('/') && !path.contains(['\r', '\n', ' ']),
            "invalid request path"
        );
        let stream = TcpStream::connect_timeout(&self.address, Duration::from_secs(4))
            .with_context(|| format!("could not reach LAN host {}", self.address))?;
        let mut tls = tls::connect(stream, self.client.clone())?;
        write_http_request(&mut tls, self.address, method, path, headers, body)?;
        let mut reader = BufReader::new(tls);
        read_http_response(&mut reader)
    }
}

fn write_http_request(
    writer: &mut impl Write,
    address: SocketAddr,
    method: &str,
    path: &str,
    headers: &[(&str, &str)],
    body: &[u8],
) -> anyhow::Result<()> {
    ensure!(
        path.starts_with('/') && !path.contains(['\r', '\n', ' ']),
        "invalid request path"
    );
    write!(
        writer,
        "{method} {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: {}\r\n",
        body.len()
    )?;
    for (name, value) in headers {
        ensure!(
            !name.contains(['\r', '\n', ':']) && !value.contains(['\r', '\n']),
            "invalid request header"
        );
        write!(writer, "{name}: {value}\r\n")?;
    }
    write!(writer, "\r\n")?;
    writer.write_all(body)?;
    writer.flush()?;
    Ok(())
}

fn read_http_response<R: Read>(reader: &mut BufReader<R>) -> anyhow::Result<(u16, Value)> {
    let mut status_line = String::new();
    reader
        .by_ref()
        .take(MAX_HEADER_LINE as u64)
        .read_line(&mut status_line)?;
    let mut parts = status_line.split_whitespace();
    ensure!(parts.next() == Some("HTTP/1.1"), "invalid host response");
    let status = parts
        .next()
        .and_then(|status| status.parse::<u16>().ok())
        .context("invalid HTTP status")?;
    let mut content_length = None;
    let mut connection_closed = false;
    let mut headers_complete = false;
    for _ in 0..64 {
        let mut line = String::new();
        let read = reader
            .by_ref()
            .take(MAX_HEADER_LINE as u64)
            .read_line(&mut line)?;
        ensure!(read > 0, "incomplete HTTP headers");
        if line.trim().is_empty() {
            headers_complete = true;
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            match name.trim().to_ascii_lowercase().as_str() {
                "content-length" => content_length = value.trim().parse::<usize>().ok(),
                "transfer-encoding" => bail!("chunked LAN responses are not supported"),
                "connection" => connection_closed = value.trim().eq_ignore_ascii_case("close"),
                _ => {}
            }
        }
    }
    ensure!(headers_complete, "too many host response headers");
    let length = content_length.context("host response has no content length")?;
    ensure!(
        length <= MAX_RESPONSE,
        "host response exceeds the size limit"
    );
    let mut bytes = vec![0; length];
    reader.read_exact(&mut bytes)?;
    let response = serde_json::from_slice(&bytes)
        .or_else(|_| std::str::from_utf8(&bytes).map(|text| Value::String(text.to_owned())))
        .context("invalid host response")?;
    ensure!(connection_closed, "LAN host kept a request connection open");
    Ok((status, response))
}

fn validate_url(raw: &str) -> anyhow::Result<Url> {
    let url = Url::parse(raw).context("invalid HTTPS LAN URL")?;
    ensure!(url.scheme() == "https", "LAN Sync requires HTTPS");
    ensure!(
        url.username().is_empty() && url.password().is_none(),
        "credentials are not allowed in the LAN URL"
    );
    ensure!(
        url.query().is_none() && url.fragment().is_none(),
        "query and fragment are not allowed in the LAN URL"
    );
    ensure!(url.path() == "/", "LAN URL must point to the host root");
    ensure!(url.port().is_some(), "LAN URL must include its port");
    let host = url.host_str().context("LAN host is missing")?;
    let ip: IpAddr = host
        .parse()
        .context("LAN URL must use a private IP address")?;
    ensure!(
        super::host::is_local_peer(ip),
        "LAN URL must use a private network address"
    );
    Ok(url)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::net::TcpListener;
    use std::sync::Mutex;
    use std::sync::atomic::AtomicBool;

    fn invite(url: &str) -> String {
        json!({
            "type": "vyral-lan-pairing",
            "version": 1,
            "url": url,
            "tlsFingerprint": "a".repeat(64)
        })
        .to_string()
    }

    #[test]
    fn saved_outgoing_transfer_survives_restart_and_reuses_its_id() {
        let data_dir = std::env::temp_dir().join(format!(
            "vyral-outgoing-resume-{}-{}",
            std::process::id(),
            super::super::host::now_ms()
        ));
        let transfer = OutgoingTransfer {
            id: "0123456789abcdef0123456789abcdef".into(),
            host_id: "abcdef0123456789abcdef01".into(),
            device_id: "fedcba9876543210fedcba9876543210".into(),
            name: "song.flac".into(),
            size: 12_345,
            mime: "audio/flac".into(),
            sha256: "a".repeat(64),
            modified_ns: Some(123_456),
        };
        let (saved, created) = upsert_outgoing_transfer(&data_dir, &transfer).unwrap();
        assert!(created);
        assert_eq!(saved, transfer);
        let restored = outgoing_transfers(&data_dir);
        assert_eq!(restored, vec![transfer.clone()]);
        let mut retry = transfer.clone();
        retry.id = "11111111111111111111111111111111".into();
        let (reused, created) = upsert_outgoing_transfer(&data_dir, &retry).unwrap();
        assert!(!created);
        assert_eq!(reused.id, transfer.id);
        remove_outgoing_transfer(&data_dir, &transfer.id).unwrap();
        assert!(outgoing_transfers(&data_dir).is_empty());
        let _ = std::fs::remove_dir_all(data_dir);
    }

    #[test]
    fn resume_checks_the_host_offset_and_all_file_identity_fields() {
        let transfer = OutgoingTransfer {
            id: "0123456789abcdef0123456789abcdef".into(),
            host_id: "abcdef0123456789abcdef01".into(),
            device_id: "fedcba9876543210fedcba9876543210".into(),
            name: "song.flac".into(),
            size: (super::super::files::CHUNK + 41) as u64,
            mime: "audio/flac".into(),
            sha256: "a".repeat(64),
            modified_ns: Some(123_456),
        };
        let status = json!({
            "id": transfer.id,
            "direction": "upload",
            "name": transfer.name,
            "size": transfer.size,
            "mime": transfer.mime,
            "sha256": transfer.sha256,
            "status": "receiving",
            "received": super::super::files::CHUNK
        });
        assert_eq!(
            upload_offset(&status, &transfer).unwrap(),
            super::super::files::CHUNK as u64
        );
        let mut wrong_hash = status.clone();
        wrong_hash["sha256"] = json!("b".repeat(64));
        assert!(upload_offset(&wrong_hash, &transfer).is_err());
        let mut beyond_file = status.clone();
        beyond_file["received"] = json!(transfer.size + 1);
        assert!(upload_offset(&beyond_file, &transfer).is_err());
        let mut incomplete_complete = status;
        incomplete_complete["status"] = json!("complete");
        assert!(upload_offset(&incomplete_complete, &transfer).is_err());
    }

    #[test]
    fn restart_resume_rejects_a_reselected_file_that_changed() {
        let root = std::env::temp_dir().join(format!(
            "vyral-outgoing-source-{}-{}",
            std::process::id(),
            super::super::host::now_ms()
        ));
        let data_dir = root.join("state");
        let source_path = root.join("song.flac");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(&source_path, b"fLaC old!!!").unwrap();
        let source = selected_audio(&source_path).unwrap();
        let transfer = OutgoingTransfer {
            id: "0123456789abcdef0123456789abcdef".into(),
            host_id: "abcdef0123456789abcdef01".into(),
            device_id: "fedcba9876543210fedcba9876543210".into(),
            name: source.name.clone(),
            size: source.size,
            mime: source.mime.into(),
            sha256: source.sha256.clone(),
            modified_ns: source.modified_ns,
        };
        upsert_outgoing_transfer(&data_dir, &transfer).unwrap();
        let restored = outgoing_transfers(&data_dir).pop().unwrap();
        assert!(selected_audio(&source_path).unwrap().matches(&restored));
        std::fs::write(&source_path, b"fLaC new!!!").unwrap();
        assert!(!selected_audio(&source_path).unwrap().matches(&restored));
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn pairing_payload_requires_https_private_ip_and_exact_type() {
        let parsed = Invite::parse(&invite("https://192.168.1.25:47731")).unwrap();
        assert_eq!(parsed.url, "https://192.168.1.25:47731");
        assert!(qrcode::QrCode::new(parsed.encode().unwrap().as_bytes()).is_ok());
        assert!(Invite::parse(&invite("http://192.168.1.25:47731")).is_err());
        assert!(Invite::parse(&invite("https://example.com:47731")).is_err());
        assert!(Invite::parse(&invite("https://192.168.1.25:47731/path")).is_err());
        assert!(Invite::parse(&invite("https://8.8.8.8:47731")).is_err());
    }

    #[test]
    fn discovery_requires_the_entered_code_actual_port_and_observed_tls_fingerprint() {
        let address = "192.168.1.25:47732".parse().unwrap();
        let fingerprint = "ab".repeat(32);
        let hello = json!({
            "app": "vyral",
            "api": super::super::host::API_VERSION,
            "version": 1,
            "id": "0123456789abcdef01234567",
            "code": "0420",
            "port": 47732,
            "tlsFingerprint": &fingerprint,
        });
        let invite = invite_from_discovery(address, &hello, &fingerprint, "0420").unwrap();
        assert_eq!(invite.url, "https://192.168.1.25:47732");
        assert!(invite_from_discovery(address, &hello, &fingerprint, "0421").is_err());
        assert!(invite_from_discovery(address, &hello, &"cd".repeat(32), "0420").is_err());
        let wrong_port: SocketAddr = "192.168.1.25:47731".parse().unwrap();
        assert!(invite_from_discovery(wrong_port, &hello, &fingerprint, "0420").is_err());
    }

    #[test]
    fn remote_controls_are_limited_to_supported_playback_actions() {
        for action in ["play", "pause", "toggle", "next", "previous"] {
            assert!(Client::control_action_valid(action));
        }
        assert!(!Client::control_action_valid("shell"));
    }

    #[test]
    fn pinned_tls_pairing_and_remote_playback_round_trip() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let generated = rcgen::generate_simple_self_signed(vec!["vyral.local".into()]).unwrap();
        let certificate = generated.cert.der().as_ref().to_vec();
        let key = rustls::pki_types::PrivateKeyDer::Pkcs8(
            rustls::pki_types::PrivatePkcs8KeyDer::from(generated.signing_key.serialize_der()),
        );
        let server = Arc::new(
            rustls::ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(
                    vec![rustls::pki_types::CertificateDer::from(certificate.clone())],
                    key,
                )
                .unwrap(),
        );
        let fingerprint = tls::fingerprint(&certificate);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let fallback_listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let fallback_address = fallback_listener.local_addr().unwrap();
        let fallback_thread = std::thread::spawn(move || {
            if let Ok((stream, _)) = fallback_listener.accept() {
                drop(stream);
            }
        });
        let data_dir = std::env::temp_dir().join(format!(
            "vyral-peer-test-{}-{}",
            std::process::id(),
            super::super::host::now_ms()
        ));
        std::fs::create_dir_all(&data_dir).unwrap();
        std::fs::write(
            data_dir.join("lan_sync.json"),
            json!({
                "host_id": "0123456789abcdef01234567",
                "space_id": "89abcdef0123456789abcdef",
                "port": address.port(),
            })
            .to_string(),
        )
        .unwrap();
        let inner = Arc::new(Mutex::new(super::super::host::Inner::load(
            &data_dir,
            "Android VYRAL".into(),
            Some(HashMap::new()),
        )));
        {
            let mut host = inner.lock().unwrap();
            host.tls_fingerprint = fingerprint.clone();
            host.connect_allowed = true;
            host.connect_now = json!({
                "track": { "id": "track-id", "title": "Current song", "artist": "Artist" },
                "playing": true,
                "positionMs": 1234
            });
        }
        let stop = Arc::new(AtomicBool::new(false));
        let server_thread = {
            let inner = inner.clone();
            let server = server.clone();
            let stop = stop.clone();
            std::thread::spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let inner = inner.clone();
                            let server = server.clone();
                            std::thread::spawn(move || {
                                let _ = super::super::host::tls_connection(
                                    stream,
                                    server,
                                    &inner,
                                    &|| {},
                                );
                            });
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(10));
                        }
                        Err(_) => break,
                    }
                }
            })
        };
        let code = inner.lock().unwrap().find_code.clone();
        let invite = discover_from_targets_with_workers(&[fallback_address, address], &code, 1)
            .expect("scanner should continue to the host's fallback port");
        fallback_thread.join().unwrap();
        let session = PairingSession::start(&invite, "Rust desktop").unwrap();
        assert!(
            session.claim().unwrap().is_none(),
            "discovery code must not approve pairing"
        );
        let pending = inner.lock().unwrap().pending().pop().unwrap();
        assert_eq!(pending.code, session.sas);
        inner.lock().unwrap().approve(&pending.id).unwrap();
        let peer = session.claim().unwrap().unwrap();
        let client = Client::new(peer.clone()).unwrap();
        let playback = client.playback().unwrap();
        assert_eq!(playback["track"]["title"], "Current song");
        assert_eq!(playback["playing"], true);
        client.control("next").unwrap();
        assert!(matches!(
            inner.lock().unwrap().connect_commands.last(),
            Some(super::super::connect::Command::Next)
        ));
        let source = data_dir.join("selected.flac");
        let mut audio = vec![0x5a; super::super::files::CHUNK + 97];
        audio[..4].copy_from_slice(b"fLaC");
        std::fs::write(&source, &audio).unwrap();
        let transfer_id = super::super::files::new_id();
        {
            let mut host = inner.lock().unwrap();
            host.file_send_allowed = true;
            host.offer_local_file(&peer.device_id, &transfer_id, &source)
                .unwrap();
        }
        client.accept_transfer(&transfer_id).unwrap();
        let first = client
            .get(&format!("/v1/files/transfers/{transfer_id}/chunk?offset=0"))
            .unwrap();
        let first_bytes = crypto::unb64(first["data"].as_str().unwrap()).unwrap();
        assert_eq!(first["offset"], 0);
        assert_eq!(first_bytes.len(), super::super::files::CHUNK);
        let received_dir = data_dir.join("Received");
        std::fs::create_dir_all(&received_dir).unwrap();
        std::fs::write(
            received_dir.join(format!(".vyral-{transfer_id}.part")),
            first_bytes,
        )
        .unwrap();
        drop(client); // Simulate the peer process exiting after its first durable chunk.
        let resumed_client = Client::new(peer.clone()).unwrap();
        let received = resumed_client
            .download_file(&transfer_id, &received_dir, &AtomicBool::new(false))
            .unwrap();
        assert_eq!(std::fs::read(received).unwrap(), audio);
        stop.store(true, Ordering::Relaxed);
        server_thread.join().unwrap();
        let _ = std::fs::remove_dir_all(data_dir);
    }
}
