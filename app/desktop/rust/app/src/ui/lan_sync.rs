//! Settings → Vyral Sync → Local Wi-Fi Sync, and the per-frame step that keeps Vyral playlists in step with the
//! LAN Sync log (`crate::lan_sync`). Off by default; nothing listens on the network until the user turns it on.

use super::pages::setting_row;
use super::widgets::pill;
use super::{App, LocalPlaylist};
use crate::lan_sync::host::{self, Inner};
use crate::lan_sync::replay::{Meta, Playlist};
use crate::model::{Link, Thumb, Track};
use crate::theme;
use egui::{RichText, Ui};
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};

pub(super) struct LanSync {
    pub enabled: bool,
    /// Vyral Connect: paired phones may control playback here. Off until turned on in its own settings tab.
    pub connect: bool,
    /// Incoming LAN transfers are independently disabled by default.
    pub receive_files: bool,
    /// Sending is opt-in too; only a file picked by the user is offered to a paired device.
    pub send_files: bool,
    inner: Option<Arc<Mutex<Inner>>>,
    stop: Option<Arc<AtomicBool>>,
    port: Option<u16>,
    tls_fingerprint: Option<String>,
    error: Option<String>,
    synced: u64,
    data_dir: PathBuf,
    peers: Vec<crate::lan_sync::peer::PairedPeer>,
    pending_sends: Vec<crate::lan_sync::peer::OutgoingTransfer>,
    discovery_code: String,
    pairing_payload: String,
    pairing: Option<crate::lan_sync::peer::PairingSession>,
    pairing_busy: bool,
    pairing_error: Option<String>,
    peer_events_tx: Sender<PeerEvent>,
    peer_events_rx: Receiver<PeerEvent>,
    remote_playback: HashMap<String, Result<serde_json::Value, String>>,
    remote_transfers: HashMap<String, Result<Vec<serde_json::Value>, String>>,
    remote_busy: std::collections::HashSet<String>,
    remote_poll_at: HashMap<String, std::time::Instant>,
    active_uploads: HashMap<String, Arc<AtomicBool>>,
    active_downloads: HashMap<String, Arc<AtomicBool>>,
}

enum PeerEvent {
    PairStarted(Result<crate::lan_sync::peer::PairingSession, String>),
    Paired(Result<crate::lan_sync::peer::PairedPeer, String>),
    Snapshot(
        String,
        Result<serde_json::Value, String>,
        Result<Vec<serde_json::Value>, String>,
    ),
    Action(String, Result<(), String>),
    Transfer(String, String, Result<String, String>),
}

impl LanSync {
    pub fn new(
        enabled: bool,
        connect: bool,
        receive_files: bool,
        send_files: bool,
        data_dir: PathBuf,
    ) -> Self {
        let peers = crate::lan_sync::peer::load(&data_dir);
        let pending_sends = crate::lan_sync::peer::outgoing_transfers(&data_dir);
        let (peer_events_tx, peer_events_rx) = mpsc::channel();
        Self {
            enabled,
            connect,
            receive_files,
            send_files,
            inner: None,
            stop: None,
            port: None,
            tls_fingerprint: None,
            error: None,
            synced: 0,
            data_dir,
            peers,
            pending_sends,
            discovery_code: String::new(),
            pairing_payload: String::new(),
            pairing: None,
            pairing_busy: false,
            pairing_error: None,
            peer_events_tx,
            peer_events_rx,
            remote_playback: HashMap::new(),
            remote_transfers: HashMap::new(),
            remote_busy: std::collections::HashSet::new(),
            remote_poll_at: HashMap::new(),
            active_uploads: HashMap::new(),
            active_downloads: HashMap::new(),
        }
    }

    fn start_pairing(&mut self, device_name: String) {
        if self.pairing_busy || self.pairing.is_some() {
            return;
        }
        self.pairing_busy = true;
        self.pairing_error = None;
        let raw = self.pairing_payload.clone();
        let tx = self.peer_events_tx.clone();
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::Invite::parse(&raw)
                .and_then(|invite| {
                    crate::lan_sync::peer::PairingSession::start(&invite, &device_name)
                })
                .map_err(|e| format!("{e:#}"));
            let _ = tx.send(PeerEvent::PairStarted(result));
        });
    }

    fn start_pairing_by_code(&mut self, device_name: String) {
        if self.pairing_busy || self.pairing.is_some() {
            return;
        }
        self.pairing_busy = true;
        self.pairing_error = None;
        let code = self.discovery_code.clone();
        let tx = self.peer_events_tx.clone();
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::discover_by_code(&code)
                .and_then(|invite| {
                    crate::lan_sync::peer::PairingSession::start(&invite, &device_name)
                })
                .map_err(|error| format!("{error:#}"));
            let _ = tx.send(PeerEvent::PairStarted(result));
        });
    }

    fn confirm_pairing(&mut self) {
        let Some(session) = self.pairing.take() else {
            return;
        };
        self.pairing_busy = true;
        self.pairing_error = None;
        let tx = self.peer_events_tx.clone();
        let data_dir = self.data_dir.clone();
        std::thread::spawn(move || {
            let result = (|| {
                for _ in 0..120 {
                    if let Some(peer) = session.claim()? {
                        crate::lan_sync::peer::save(&peer, &data_dir)?;
                        return Ok(peer);
                    }
                    std::thread::sleep(std::time::Duration::from_secs(1));
                }
                anyhow::bail!("pairing timed out; approve it on the host and try again")
            })()
            .map_err(|e: anyhow::Error| format!("{e:#}"));
            let _ = tx.send(PeerEvent::Paired(result));
        });
    }

    fn control_peer(&mut self, host_id: String, action: &'static str) {
        let Some(peer) = self.peers.iter().find(|p| p.host_id == host_id).cloned() else {
            return;
        };
        let tx = self.peer_events_tx.clone();
        self.remote_busy.insert(host_id.clone());
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::Client::new(peer)
                .and_then(|client| client.control(action))
                .map(|_| ())
                .map_err(|e| format!("{e:#}"));
            let _ = tx.send(PeerEvent::Action(host_id, result));
        });
    }

    fn send_file_to_peer(&mut self, host_id: String, path: PathBuf, transfer_id: Option<String>) {
        if !self.send_files || self.active_uploads.contains_key(&host_id) {
            return;
        }
        let Some(peer) = self.peers.iter().find(|p| p.host_id == host_id).cloned() else {
            return;
        };
        let cancelled = Arc::new(AtomicBool::new(false));
        let data_dir = self.data_dir.clone();
        self.active_uploads
            .insert(host_id.clone(), cancelled.clone());
        let tx = self.peer_events_tx.clone();
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::Client::new(peer)
                .and_then(|client| {
                    if let Some(id) = transfer_id {
                        client.resume_file(&id, &path, &data_dir, &cancelled)
                    } else {
                        client.send_file(&path, &data_dir, &cancelled)
                    }
                })
                .map_err(|e| format!("{e:#}"));
            let _ = tx.send(PeerEvent::Transfer(host_id, "upload".into(), result));
        });
    }

    fn cancel_saved_send(&mut self, host_id: String, transfer_id: String) {
        if let Some(cancelled) = self.active_uploads.get(&host_id) {
            cancelled.store(true, Ordering::Relaxed);
            return;
        }
        let Some(peer) = self
            .peers
            .iter()
            .find(|peer| peer.host_id == host_id)
            .cloned()
        else {
            return;
        };
        let data_dir = self.data_dir.clone();
        let tx = self.peer_events_tx.clone();
        self.remote_busy.insert(host_id.clone());
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::Client::new(peer)
                .and_then(|client| {
                    client
                        .cancel_transfer(&transfer_id)
                        .map(|_| transfer_id.clone())
                })
                .map_err(|error| format!("{error:#}"));
            let result =
                match crate::lan_sync::peer::remove_outgoing_transfer(&data_dir, &transfer_id) {
                    Ok(()) => result,
                    Err(error) => Err(format!("could not clear saved send: {error:#}")),
                };
            let _ = tx.send(PeerEvent::Transfer(host_id, "upload".into(), result));
        });
    }

    fn receive_file_from_peer(&mut self, host_id: String, transfer_id: String) {
        let transfer_key = format!("{host_id}:{transfer_id}");
        if !self.receive_files || self.active_downloads.contains_key(&transfer_key) {
            return;
        }
        let Some(peer) = self.peers.iter().find(|p| p.host_id == host_id).cloned() else {
            return;
        };
        let cancelled = Arc::new(AtomicBool::new(false));
        self.active_downloads
            .insert(transfer_key.clone(), cancelled.clone());
        let tx = self.peer_events_tx.clone();
        let transfer_key = transfer_key.clone();
        let received = crate::downloads::folder().join("Received");
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::Client::new(peer)
                .and_then(|client| {
                    match client.transfer(&transfer_id)?["status"].as_str() {
                        Some("pending") => {
                            client.accept_transfer(&transfer_id)?;
                        }
                        Some("accepted" | "sending" | "complete") => {}
                        _ => anyhow::bail!("transfer is no longer resumable"),
                    }
                    client
                        .download_file(&transfer_id, &received, &cancelled)
                        .map(|path| path.to_string_lossy().into_owned())
                })
                .map_err(|e| format!("{e:#}"));
            let _ = tx.send(PeerEvent::Transfer(host_id, transfer_key, result));
        });
    }

    fn decline_remote_file(&mut self, host_id: String, transfer_id: String) {
        let Some(peer) = self.peers.iter().find(|p| p.host_id == host_id).cloned() else {
            return;
        };
        let tx = self.peer_events_tx.clone();
        self.remote_busy.insert(host_id.clone());
        std::thread::spawn(move || {
            let result = crate::lan_sync::peer::Client::new(peer)
                .and_then(|client| client.decline_transfer(&transfer_id))
                .map(|_| ())
                .map_err(|e| format!("{e:#}"));
            if let Err(error) = result {
                log::warn!("lan sync: could not decline remote transfer: {error}");
            }
            let _ = tx.send(PeerEvent::Action(host_id, Ok(())));
        });
    }

    fn poll_peers(&mut self) {
        let now = std::time::Instant::now();
        for peer in &self.peers {
            if self.remote_busy.contains(&peer.host_id)
                || self.active_uploads.contains_key(&peer.host_id)
                || self
                    .active_downloads
                    .keys()
                    .any(|key| key.starts_with(&format!("{}:", peer.host_id)))
                || self
                    .remote_poll_at
                    .get(&peer.host_id)
                    .is_some_and(|at| now.duration_since(*at).as_secs_f32() < 1.0)
            {
                continue;
            }
            let host_id = peer.host_id.clone();
            let peer = peer.clone();
            let tx = self.peer_events_tx.clone();
            self.remote_busy.insert(host_id.clone());
            self.remote_poll_at.insert(host_id.clone(), now);
            std::thread::spawn(move || {
                let result = crate::lan_sync::peer::Client::new(peer).map(|client| {
                    let playback = client.playback().map_err(|e| format!("{e:#}"));
                    let transfers = client.transfers().map_err(|e| format!("{e:#}"));
                    (playback, transfers)
                });
                let (playback, transfers) = result.unwrap_or_else(|error| {
                    let error = format!("{error:#}");
                    (Err(error.clone()), Err(error))
                });
                let _ = tx.send(PeerEvent::Snapshot(host_id, playback, transfers));
            });
        }
    }

    fn poll_peer_events(&mut self, ctx: &egui::Context) {
        while let Ok(event) = self.peer_events_rx.try_recv() {
            match event {
                PeerEvent::PairStarted(result) => {
                    self.pairing_busy = false;
                    match result {
                        Ok(session) => self.pairing = Some(session),
                        Err(error) => self.pairing_error = Some(error),
                    }
                }
                PeerEvent::Paired(result) => {
                    self.pairing_busy = false;
                    self.pairing = None;
                    match result {
                        Ok(peer) => {
                            self.remote_playback.insert(
                                peer.host_id.clone(),
                                Ok(serde_json::json!({"track": null, "playing": false})),
                            );
                            self.peers.push(peer);
                            self.pairing_payload.clear();
                            self.pairing_error = None;
                        }
                        Err(error) => self.pairing_error = Some(error),
                    }
                }
                PeerEvent::Snapshot(host_id, playback, transfers) => {
                    self.remote_busy.remove(&host_id);
                    self.remote_playback.insert(host_id.clone(), playback);
                    self.remote_transfers.insert(host_id, transfers);
                }
                PeerEvent::Action(host_id, result) => {
                    if !self.active_uploads.contains_key(&host_id)
                        && !self
                            .active_downloads
                            .keys()
                            .any(|key| key.starts_with(&format!("{host_id}:")))
                    {
                        self.remote_busy.remove(&host_id);
                    }
                    if let Err(error) = result {
                        self.remote_playback.insert(host_id, Err(error));
                    }
                }
                PeerEvent::Transfer(host_id, transfer_id, result) => {
                    if transfer_id == "upload" {
                        self.active_uploads.remove(&host_id);
                        self.pending_sends =
                            crate::lan_sync::peer::outgoing_transfers(&self.data_dir);
                    } else {
                        self.active_downloads.remove(&transfer_id);
                    }
                    if !self.active_uploads.contains_key(&host_id)
                        && !self
                            .active_downloads
                            .keys()
                            .any(|key| key.starts_with(&format!("{host_id}:")))
                    {
                        self.remote_busy.remove(&host_id);
                    }
                    match result {
                        Ok(path) if transfer_id != "upload" => {
                            crate::downloads::refresh_local(Some(ctx.clone()));
                            if let Some(library) = crate::local_music::library().cloned() {
                                library.refresh(ctx.clone());
                            }
                            log::info!("lan sync: received local file at {path}");
                        }
                        Ok(id) => log::info!("lan sync: file transfer {id} completed"),
                        Err(error) => log::warn!("lan sync: file transfer failed: {error}"),
                    }
                }
            }
        }
    }

    fn remove_peer(&mut self, host_id: &str) {
        if let Some(cancel) = self.active_uploads.get(host_id) {
            cancel.store(true, Ordering::Relaxed);
        }
        for (key, cancel) in &self.active_downloads {
            if key.starts_with(&format!("{host_id}:")) {
                cancel.store(true, Ordering::Relaxed);
            }
        }
        if let Err(error) = crate::lan_sync::peer::remove(host_id, &self.data_dir) {
            log::warn!("lan sync: could not remove paired host: {error:#}");
        }
        self.peers.retain(|p| p.host_id != host_id);
        self.remote_playback.remove(host_id);
        self.remote_busy.remove(host_id);
        self.remote_poll_at.remove(host_id);
    }
}

impl Drop for LanSync {
    fn drop(&mut self) {
        if let Some(stop) = &self.stop {
            stop.store(true, Ordering::Relaxed);
        }
        for cancel in self
            .active_uploads
            .values()
            .chain(self.active_downloads.values())
        {
            cancel.store(true, Ordering::Relaxed);
        }
    }
}

fn fingerprint(lists: &[LocalPlaylist]) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    for p in lists {
        p.id.hash(&mut h);
        p.title.hash(&mut h);
        for t in &p.tracks {
            t.id.hash(&mut h);
        }
    }
    h.finish()
}

fn to_sync(p: &LocalPlaylist) -> (Playlist, HashMap<String, Meta>) {
    let meta = p
        .tracks
        .iter()
        .map(|t| {
            (
                t.id.clone(),
                Meta {
                    title: t.title.clone(),
                    artist: t.artist_line(),
                    thumbnail: t
                        .thumbs
                        .iter()
                        .max_by_key(|th| th.width)
                        .map(|th| th.url.clone()),
                },
            )
        })
        .collect();
    (
        Playlist {
            id: p.id.clone(),
            title: p.title.clone(),
            tracks: p.tracks.iter().map(|t| t.id.clone()).collect(),
        },
        meta,
    )
}

/// Keeps the richer local copy of a track when this computer already has it, like `VyralSync.toLocal`.
fn to_local(
    synced: Vec<Playlist>,
    meta: &HashMap<String, Meta>,
    current: &[LocalPlaylist],
) -> Vec<LocalPlaylist> {
    let mut known: HashMap<&str, &Track> = HashMap::new();
    for p in current {
        for t in &p.tracks {
            known.entry(t.id.as_str()).or_insert(t);
        }
    }
    synced
        .into_iter()
        .map(|p| LocalPlaylist {
            artwork: current
                .iter()
                .find(|c| c.id == p.id)
                .and_then(|c| c.artwork.clone()),
            tracks: p
                .tracks
                .iter()
                .map(|id| {
                    known
                        .get(id.as_str())
                        .map(|t| (*t).clone())
                        .unwrap_or_else(|| {
                            let m = meta.get(id).cloned().unwrap_or_default();
                            Track {
                                id: id.clone(),
                                title: m.title,
                                artists: if m.artist.is_empty() {
                                    vec![]
                                } else {
                                    vec![Link {
                                        name: m.artist,
                                        id: None,
                                    }]
                                },
                                album: None,
                                duration: None,
                                thumbs: m
                                    .thumbnail
                                    .map(|url| {
                                        vec![Thumb {
                                            url,
                                            width: 544,
                                            height: 544,
                                        }]
                                    })
                                    .unwrap_or_default(),
                                track_nr: None,
                                plays: None,
                                music_video: false,
                            }
                        })
                })
                .collect(),
            id: p.id,
            title: p.title,
        })
        .collect()
}

impl App {
    fn lan_start(&mut self, ctx: &egui::Context) {
        let inner = Arc::new(Mutex::new(Inner::load(
            self.backend.data_dir(),
            host_name(),
            None,
        )));
        let stop = Arc::new(AtomicBool::new(false));
        let wake = ctx.clone();
        match host::serve(inner.clone(), stop.clone(), move || wake.request_repaint()) {
            Ok((port, fingerprint)) => {
                self.lan.port = Some(port);
                self.lan.tls_fingerprint = Some(fingerprint);
                self.lan.error = None;
                self.lan.inner = Some(inner);
                self.lan.stop = Some(stop);
                self.lan.synced = 0; // first frame: record this computer's playlists in the log
            }
            Err(e) => self.lan.error = Some(format!("Couldn't start: {e}")),
        }
    }

    fn lan_stop(&mut self) {
        if let Some(stop) = self.lan.stop.take() {
            stop.store(true, Ordering::Relaxed);
        }
        self.lan.inner = None;
        self.lan.port = None;
        self.lan.tls_fingerprint = None;
    }

    /// Each frame: start or stop the host, and sync when this computer's playlists or a phone's pushes changed.
    pub(super) fn lan_tick(&mut self, ctx: &egui::Context) {
        if !self.lan.enabled {
            if self.lan.inner.is_some() {
                self.lan_stop();
            }
            return;
        }
        if self.lan.inner.is_none() {
            if self.lan.error.is_none() {
                self.lan_start(ctx);
            }
            return;
        }
        let Some(inner) = self.lan.inner.clone() else {
            return;
        };
        let commands = {
            let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
            g.connect_allowed = self.lan.connect;
            g.file_receive_allowed = self.lan.receive_files;
            g.file_send_allowed = self.lan.send_files;
            g.connect_now = if self.lan.connect {
                self.connect_snapshot()
            } else {
                serde_json::Value::Null
            };
            g.take_connect_commands()
        };
        for command in commands {
            self.connect_apply(command);
        }
        if inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take_received_files_changed()
        {
            crate::downloads::refresh_local(Some(ctx.clone()));
            if let Some(library) = crate::local_music::library().cloned() {
                library.refresh(ctx.clone());
            }
        }
        let fp = fingerprint(&self.local_playlists);
        let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
        if fp == self.lan.synced && !g.remote_changed {
            return;
        }
        let (lists, meta) = g.sync_local(self.local_playlists.iter().map(to_sync).collect());
        drop(g);
        let next = to_local(lists, &meta, &self.local_playlists);
        let after = fingerprint(&next);
        if after != fp {
            let gone: Vec<String> = self
                .local_playlists
                .iter()
                .filter(|p| !next.iter().any(|n| n.id == p.id))
                .map(|p| p.id.clone())
                .collect();
            self.local_playlists = next;
            for id in gone {
                self.lists.forget(&id);
                if let Some(library) = &mut self.library {
                    library
                        .playlists
                        .retain(|card| card.target != super::Target::Playlist(id.clone()));
                }
            }
            for id in self
                .local_playlists
                .iter()
                .rev()
                .map(|p| p.id.clone())
                .collect::<Vec<_>>()
            {
                self.refresh_local_playlist(&id);
            }
        }
        self.lan.synced = after;
    }
}

impl super::App {
    /// What a paired phone sees in Vyral Connect.
    fn connect_snapshot(&self) -> serde_json::Value {
        let track = self.current().map(|t| serde_json::json!({
            "id": t.id, "title": t.title, "artist": t.artist_line(), "durationMs": t.duration.map(|s| u64::from(s) * 1000).or_else(|| self.audio.status.duration().map(|d| d.as_millis() as u64)),
        }));
        serde_json::json!({
            "track": track,
            "playing": self.playing() && self.resume_at.is_none(),
            "ended": self.playback_ended(),
            "positionMs": self.audio.status.position().as_millis() as u64,
        })
    }

    /// The last song in the queue played to its end and nothing follows: the phone's queue takes over from here.
    fn playback_ended(&self) -> bool {
        let Some(total) = self.audio.status.duration() else {
            return false;
        };
        !self.playing()
            && self.resume_at.is_none()
            && !total.is_zero()
            && self.audio.status.position() + std::time::Duration::from_millis(1_500) >= total
            && self.queue.upcoming().is_none()
    }

    fn connect_apply(&mut self, command: crate::lan_sync::connect::Command) {
        use crate::lan_sync::connect::Command;
        match command {
            Command::Toggle => self.toggle(),
            Command::Play => {
                if !self.playing() {
                    self.toggle()
                }
            }
            Command::Pause => {
                if self.playing() {
                    self.toggle()
                }
            }
            Command::Next => self.next(),
            Command::Prev => self.prev(),
            Command::Seek(ms) => self.seek_to(std::time::Duration::from_millis(ms)),
            Command::Load {
                tracks,
                position_ms,
                paused,
            } => {
                let tracks: Vec<crate::model::Track> = tracks
                    .into_iter()
                    .map(|i| crate::model::Track {
                        id: i.id,
                        title: i.title,
                        artists: vec![crate::model::Link {
                            name: i.artist,
                            id: None,
                        }],
                        album: None,
                        duration: None,
                        thumbs: vec![crate::model::Thumb {
                            url: i.art,
                            width: 544,
                            height: 544,
                        }],
                        track_nr: None,
                        plays: None,
                        music_video: false,
                    })
                    .collect();
                let at = std::time::Duration::from_millis(position_ms);
                if !self.queue.replace(tracks, 0, None) {
                    return;
                }
                self.play_pending = None;
                if paused {
                    // Ready where the phone was; Play (from either side) starts it there.
                    self.audio.send(crate::audio::AudioCmd::Stop);
                    self.audio
                        .status
                        .position_ms
                        .store(position_ms, std::sync::atomic::Ordering::Relaxed);
                    self.resume_at = Some(at);
                    self.request_art();
                } else {
                    self.recoveries = 0;
                    self.play_current_at(at, true, false, false);
                }
            }
        }
    }
}

impl LanSync {
    /// How many phones are paired with this computer.
    pub(super) fn paired_count(&self) -> usize {
        self.inner.as_ref().map_or(0, |i| {
            i.lock().unwrap_or_else(|e| e.into_inner()).devices().len()
        })
    }
}

fn host_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| "This computer".into())
}

fn ago(ms: i64) -> String {
    if ms <= 0 {
        return "Never".into();
    }
    let s = (host::now_ms() - ms).max(0) / 1000;
    match s {
        0..=59 => "Just now".into(),
        60..=3599 => format!("{} min ago", s / 60),
        3600..=86_399 => format!("{} h ago", s / 3600),
        _ => format!("{} days ago", s / 86_400),
    }
}

pub(super) fn settings(app: &mut App, ui: &mut Ui) {
    let mut on = app.lan.enabled;
    setting_row(
        ui,
        "Local Wi-Fi Sync",
        Some(
            "Sync Vyral playlists straight with your phone on the same Wi-Fi. No account, no internet, no server.",
        ),
        |ui| {
            super::pages::toggle(ui, &mut on, "Local Wi-Fi Sync");
        },
    );
    if on != app.lan.enabled {
        app.lan.enabled = on;
        app.lan.error = None;
    }
    if !app.lan.enabled {
        return;
    }
    if let Some(e) = app.lan.error.clone() {
        setting_row(ui, "Not running", Some(&e), |ui| {
            if pill(ui, "Try again", None, false).clicked() {
                app.lan.error = None;
            }
        });
        return;
    }
    let Some(inner) = app.lan.inner.clone() else {
        return;
    };
    let address = address_text(app.lan.port);
    let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
    pair_row(
        ui,
        &g.find_code,
        &address,
        app.lan.tls_fingerprint.as_deref().unwrap_or("Starting…"),
    );
    for p in g.pending() {
        let desc = format!(
            "Check that the phone shows the same code before you approve: {} {}",
            &p.code[..3],
            &p.code[3..]
        );
        let mut choice = None;
        setting_row(
            ui,
            &format!("{} wants to sync", p.name),
            Some(&desc),
            |ui| {
                if pill(ui, "Approve", None, true).clicked() {
                    choice = Some(true);
                }
                ui.add_space(6.0);
                if pill(ui, "Decline", None, false).clicked() {
                    choice = Some(false);
                }
            },
        );
        match choice {
            Some(true) => {
                if let Err(e) = g.approve(&p.id) {
                    app.toast(e.to_owned());
                }
            }
            Some(false) => g.deny(&p.id),
            None => {}
        }
    }
    let devices = g.devices();
    let last = g.last_sync_ms();
    let allow_send = app.lan.send_files;
    let mut send_to = None;
    if devices.is_empty() {
        setting_row(ui, "Paired devices", Some("None yet."), |_| {});
    }
    for d in devices {
        let mut remove = false;
        let mut send = false;
        setting_row(
            ui,
            &d.name,
            Some(&format!(
                "Paired device · last seen {}",
                ago(d.seen_ms).to_lowercase()
            )),
            |ui| {
                if allow_send {
                    send = pill(ui, "Send local file", None, true).clicked();
                    ui.add_space(6.0);
                }
                remove = pill(ui, "Remove", None, false).clicked();
            },
        );
        if send {
            send_to = Some(d.id.clone());
        }
        if remove {
            g.revoke(&d.id);
        }
    }
    for transfer in g
        .pending_file_transfers()
        .into_iter()
        .filter(|t| t["direction"] == "upload")
    {
        let id = transfer["id"].as_str().unwrap_or_default().to_owned();
        let device_id = transfer["deviceId"].as_str().unwrap_or_default().to_owned();
        let device_name = g
            .devices()
            .into_iter()
            .find(|d| d.id == device_id)
            .map_or_else(|| "Paired device".to_owned(), |d| d.name);
        let name = transfer["name"].as_str().unwrap_or("Audio file");
        let size = transfer["size"].as_u64().unwrap_or_default();
        let size_text = if size >= 1_000_000_000 {
            format!("{:.1} GB", size as f64 / 1_000_000_000.0)
        } else {
            format!("{:.1} MB", size as f64 / 1_000_000.0)
        };
        let desc = format!("{device_name} offers {name} · {size_text}. Review before accepting.");
        let mut choice = None;
        setting_row(ui, "Incoming file offer", Some(&desc), |ui| {
            if pill(ui, "Accept", None, true).clicked() {
                choice = Some(true);
            }
            ui.add_space(6.0);
            if pill(ui, "Decline", None, false).clicked() {
                choice = Some(false);
            }
        });
        match choice {
            Some(true) => match g.accept_file_transfer(&device_id, &id) {
                Ok(_) => app.toast("Transfer accepted"),
                Err((_, "file_receive_off")) => app.toast("Enable receiving local music first"),
                Err(_) => app.toast("That offer is no longer available"),
            },
            Some(false) => {
                let _ = g.decline_file_transfer(&device_id, &id);
            }
            None => {}
        }
    }
    let transfers = g.file_transfers();
    for transfer in transfers
        .iter()
        .filter(|t| t["direction"] == "download" && t["status"] != "complete")
    {
        let device_id = transfer["deviceId"].as_str().unwrap_or_default();
        let device_name = g
            .devices()
            .into_iter()
            .find(|d| d.id == device_id)
            .map_or_else(|| "Paired device".to_owned(), |d| d.name);
        let name = transfer["name"].as_str().unwrap_or("Audio file");
        let status = transfer["status"].as_str().unwrap_or("pending");
        let sent = transfer["received"].as_u64().unwrap_or_default();
        let size = transfer["size"].as_u64().unwrap_or_default();
        let desc = format!(
            "{device_name} · {status} · {:.1} of {:.1} MB",
            sent as f64 / 1_000_000.0,
            size as f64 / 1_000_000.0
        );
        setting_row(ui, &format!("Sending {name}"), Some(&desc), |ui| {
            if pill(ui, "Cancel", None, false).clicked() {
                let _ =
                    g.cancel_file_transfer(device_id, transfer["id"].as_str().unwrap_or_default());
            }
        });
    }
    drop(g);
    if let Some(device_id) = send_to.filter(|_| app.lan.send_files)
        && let Some(path) = rfd::FileDialog::new()
            .set_title("Choose a local audio file")
            .add_filter(
                "Audio",
                &["aac", "flac", "m4a", "mp3", "ogg", "wav", "webm"],
            )
            .pick_file()
    {
        let transfer_id = crate::lan_sync::files::new_id();
        let inner = inner.clone();
        let ctx = ui.ctx().clone();
        std::thread::spawn(move || {
            let result = inner
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .offer_local_file(&device_id, &transfer_id, &path);
            if let Err((_, error)) = result {
                log::warn!("lan files: could not offer selected audio: {error}");
            }
            ctx.request_repaint();
        });
    }
    let mut now = false;
    setting_row(
        ui,
        "Last sync",
        Some(&format!(
            "{} · Syncs by itself whenever a paired phone connects.",
            ago(last)
        )),
        |ui| {
            now = pill(ui, "Sync now", None, false).clicked();
        },
    );
    if now {
        app.lan.synced = 0;
    }
    ui.ctx()
        .request_repaint_after(std::time::Duration::from_secs(2)); // new pairing requests show up without a click
}

/// How a phone finds this computer: the four-digit code, or the address for networks where the search fails.
fn pair_row(ui: &mut Ui, code: &str, address: &str, tls_fingerprint: &str) {
    let desc = format!(
        "HTTPS pairing endpoint: {address}. Verify this certificate SHA-256 fingerprint on the joining device: {tls_fingerprint}. Then compare the six-digit code before approving."
    );
    setting_row(ui, "Pair a new device", Some(&desc), |ui| {
        ui.label(
            RichText::new(code)
                .font(theme::semibold(22.0))
                .color(theme::text()),
        );
    });
    if address.starts_with("https://") && tls_fingerprint.len() == 64 {
        let invite = crate::lan_sync::peer::Invite {
            kind: "vyral-lan-pairing".into(),
            version: 1,
            url: address.into(),
            tls_fingerprint: tls_fingerprint.into(),
        };
        if let Ok(payload) = invite.encode()
            && let Ok(qr) = qrcode::QrCode::new(payload.as_bytes())
        {
            let module_count = qr.width();
            let quiet_zone = 4usize;
            let cell = 4.0f32;
            let side = cell * (module_count + quiet_zone * 2) as f32;
            let (rect, _) = ui.allocate_exact_size(egui::vec2(side, side), egui::Sense::hover());
            let painter = ui.painter_at(rect);
            painter.rect_filled(rect, 0.0, egui::Color32::WHITE);
            let offset = cell * quiet_zone as f32;
            for (index, color) in qr.to_colors().iter().enumerate() {
                if *color == qrcode::types::Color::Dark {
                    let x = index % module_count;
                    let y = index / module_count;
                    let min =
                        rect.min + egui::vec2(offset + x as f32 * cell, offset + y as f32 * cell);
                    painter.rect_filled(
                        egui::Rect::from_min_size(min, egui::vec2(cell, cell)),
                        0.0,
                        egui::Color32::BLACK,
                    );
                }
            }
            ui.collapsing("Manual pairing payload", |ui| ui.monospace(payload));
        }
    }
}

// shortcut: looks the address up on every repaint of these two pages (rare, and the lookup is local); cache it if it shows in a profile.
fn address_text(port: Option<u16>) -> String {
    match (host::lan_address(), port) {
        (Some(ip), Some(port)) => format!("https://{ip}:{port}"),
        (None, _) => "Not connected to a network".into(),
        _ => "Starting…".into(),
    }
}

/// Settings → Sync & Connect, the Connect half: whether paired phones may play through this computer.
pub(super) fn connect_settings(app: &mut App, ui: &mut Ui) {
    app.lan.poll_peer_events(ui.ctx());
    app.lan.poll_peers();
    let mut on = app.lan.connect;
    setting_row(
        ui,
        "Allow remote control",
        Some(
            "On a paired phone, pick this computer with the speaker button in the player: its music then plays here and the phone's player controls it. Only phones paired above, on this network, encrypted.",
        ),
        |ui| {
            super::pages::toggle(ui, &mut on, "Allow remote control");
        },
    );
    app.lan.connect = on;
    let mut send_files = app.lan.send_files;
    setting_row(
        ui,
        "Allow sending local music",
        Some(
            "You must select each audio file and recipient. Only user-selected files are offered; streamed cache files are never shared.",
        ),
        |ui| {
            super::pages::toggle(ui, &mut send_files, "Allow sending local music");
        },
    );
    app.lan.send_files = send_files;
    let mut receive_files = app.lan.receive_files;
    setting_row(
        ui,
        "Allow receiving local music",
        Some(&format!(
            "Offers require your approval before saving into {}.",
            crate::downloads::folder().join("Received").display()
        )),
        |ui| {
            super::pages::toggle(ui, &mut receive_files, "Allow receiving local music");
        },
    );
    app.lan.receive_files = receive_files;
    // The other way round: a paired phone that plays something shows here, and this computer controls it.
    if let Some(inner) = app.lan.inner.clone() {
        let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
        let phones = g.phones_playing();
        for (d, now) in &phones {
            let song = match (now.title.as_str(), now.artist.as_str()) {
                ("", _) => "A song".to_owned(),
                (t, "") => t.to_owned(),
                (t, a) => format!("{t} · {a}"),
            };
            let desc = format!(
                "{song} · {}",
                if now.playing { "playing" } else { "paused" }
            );
            let mut action = None;
            setting_row(ui, &format!("Playing on {}", d.name), Some(&desc), |ui| {
                if pill(ui, "Next", None, false).clicked() {
                    action = Some("next");
                }
                ui.add_space(6.0);
                if pill(ui, if now.playing { "Pause" } else { "Play" }, None, true).clicked() {
                    action = Some("toggle");
                }
                ui.add_space(6.0);
                if pill(ui, "Previous", None, false).clicked() {
                    action = Some("previous");
                }
            });
            if let Some(a) = action {
                g.phone_command(&d.id, a);
            }
        }
        if !phones.is_empty() {
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_secs(1));
        }
    }
    if app.lan.connect && !app.lan.enabled {
        setting_row(
            ui,
            "Local Wi-Fi Sync is off",
            Some("Turn it on above and pair your phone; Vyral Connect uses that pairing."),
            |_| {},
        );
    }
    ui.add_space(12.0);
    ui.heading("Connect to a VYRAL host");
    ui.label("Enter the host's four-digit discovery code to find it on this local network.");
    ui.label("The code only locates the host. Pairing still requires matching the six-digit SAS and host approval.");
    let start_discovery = ui
        .horizontal(|ui| {
            ui.add(
                egui::TextEdit::singleline(&mut app.lan.discovery_code)
                    .char_limit(4)
                    .hint_text("4-digit code")
                    .desired_width(110.0),
            );
            pill(
                ui,
                if app.lan.pairing_busy {
                    "Searching…"
                } else {
                    "Find nearby host"
                },
                None,
                true,
            )
            .clicked()
        })
        .inner;
    if start_discovery {
        app.lan.start_pairing_by_code(host_name());
    }
    ui.add_space(8.0);
    ui.label("Or paste a QR/manual invite:");
    ui.label("Paste the host's VYRAL LAN pairing JSON. The HTTPS certificate fingerprint is checked before pairing.");
    ui.add(
        egui::TextEdit::singleline(&mut app.lan.pairing_payload)
            .hint_text("{ type, version, url, tlsFingerprint }")
            .desired_width(f32::INFINITY),
    );
    let mut start_pair = false;
    setting_row(
        ui,
        "Pair with Android or another desktop",
        Some("The host must show the same six-digit code and approve this device."),
        |ui| {
            start_pair = pill(
                ui,
                if app.lan.pairing_busy {
                    "Working…"
                } else {
                    "Pair"
                },
                None,
                true,
            )
            .clicked();
        },
    );
    if start_pair {
        app.lan.start_pairing(host_name());
    }
    if let Some(session) = app.lan.pairing.as_ref() {
        let mut confirm = false;
        setting_row(
            ui,
            &format!("Compare this code with {}", session.host_name),
            Some("Continue only if both devices show the same six digits."),
            |ui| {
                ui.label(
                    RichText::new(&session.sas)
                        .font(theme::semibold(22.0))
                        .color(theme::text()),
                );
                ui.add_space(6.0);
                confirm = pill(ui, "Code matches — continue", None, true).clicked();
            },
        );
        if confirm {
            app.lan.confirm_pairing();
        }
    } else if app.lan.pairing_busy {
        setting_row(
            ui,
            "Waiting for approval",
            Some("Approve this matching code on the host. Pairing expires after two minutes."),
            |_| {},
        );
    }
    if let Some(error) = app.lan.pairing_error.clone() {
        setting_row(ui, "Pairing failed", Some(&error), |_| {});
    }
    for peer in app.lan.peers.clone() {
        let playback = app.lan.remote_playback.get(&peer.host_id);
        let (song, playing) = match playback {
            Some(Ok(now)) => {
                let track = &now["track"];
                let title = track["title"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .unwrap_or("Nothing playing");
                let artist = track["artist"].as_str().filter(|s| !s.is_empty());
                (
                    artist.map_or_else(|| title.to_owned(), |a| format!("{title} · {a}")),
                    now["playing"].as_bool().unwrap_or(false),
                )
            }
            Some(Err(error)) => (format!("Unreachable · {error}"), false),
            None => ("Connecting…".to_owned(), false),
        };
        let mut action = None;
        let mut remove = false;
        let mut send = false;
        let pending_sends: Vec<_> = app
            .lan
            .pending_sends
            .iter()
            .filter(|transfer| transfer.host_id == peer.host_id)
            .cloned()
            .collect();
        let upload_cancel = app.lan.active_uploads.get(&peer.host_id).cloned();
        setting_row(
            ui,
            &format!("{} · {song}", peer.host_name),
            Some("Paired VYRAL device · controls use its existing player."),
            |ui| {
                if pill(ui, "Previous", None, false).clicked() {
                    action = Some("previous");
                }
                ui.add_space(4.0);
                if pill(ui, if playing { "Pause" } else { "Play" }, None, true).clicked() {
                    action = Some(if playing { "pause" } else { "play" });
                }
                ui.add_space(4.0);
                if pill(ui, "Next", None, false).clicked() {
                    action = Some("next");
                }
                if let Some(cancel) = &upload_cancel {
                    ui.add_space(6.0);
                    if pill(ui, "Cancel send", None, false).clicked() {
                        cancel.store(true, Ordering::Relaxed);
                    }
                } else if app.lan.send_files && pending_sends.is_empty() {
                    ui.add_space(6.0);
                    send = pill(ui, "Send local file", None, false).clicked();
                }
                ui.add_space(6.0);
                remove = pill(ui, "Unpair", None, false).clicked();
            },
        );
        if let Some(action) = action {
            app.lan.control_peer(peer.host_id.clone(), action);
        }
        if send
            && let Some(path) = rfd::FileDialog::new()
                .set_title("Choose a local audio file")
                .add_filter(
                    "Audio",
                    &["aac", "flac", "m4a", "mp3", "ogg", "wav", "webm"],
                )
                .pick_file()
        {
            app.lan.send_file_to_peer(peer.host_id.clone(), path, None);
        }
        for saved in pending_sends {
            let remote = app
                .lan
                .remote_transfers
                .get(&peer.host_id)
                .and_then(|result| result.as_ref().ok())
                .and_then(|transfers| transfers.iter().find(|transfer| transfer["id"] == saved.id));
            let remote_status = remote
                .and_then(|transfer| transfer["status"].as_str())
                .unwrap_or("waiting for peer");
            let received = remote
                .and_then(|transfer| transfer["received"].as_u64())
                .unwrap_or_default();
            let active = app.lan.active_uploads.contains_key(&peer.host_id);
            let mut resume = false;
            let mut cancel_saved = false;
            setting_row(
                ui,
                &format!("Send {}", saved.name),
                Some(&format!(
                    "{remote_status} · {:.1} of {:.1} MB",
                    received as f64 / 1_000_000.0,
                    saved.size as f64 / 1_000_000.0
                )),
                |ui| {
                    if active {
                        cancel_saved = pill(ui, "Cancel send", None, false).clicked();
                    } else {
                        let can_resume = app.lan.send_files
                            && matches!(
                                remote_status,
                                "waiting for peer" | "pending" | "accepted" | "receiving"
                            );
                        resume = pill(ui, "Resume send", None, can_resume).clicked();
                        ui.add_space(6.0);
                        cancel_saved = pill(ui, "Cancel send", None, false).clicked();
                    }
                },
            );
            if cancel_saved {
                app.lan
                    .cancel_saved_send(peer.host_id.clone(), saved.id.clone());
            }
            if resume
                && let Some(path) = rfd::FileDialog::new()
                    .set_title(format!("Select {} to resume sending", saved.name))
                    .add_filter(
                        "Audio",
                        &["aac", "flac", "m4a", "mp3", "ogg", "wav", "webm"],
                    )
                    .pick_file()
            {
                app.lan
                    .send_file_to_peer(peer.host_id.clone(), path, Some(saved.id.clone()));
            }
        }
        let transfers = app
            .lan
            .remote_transfers
            .get(&peer.host_id)
            .and_then(|v| v.as_ref().ok())
            .cloned()
            .unwrap_or_default();
        for transfer in transfers {
            let Some(id) = transfer["id"].as_str().map(str::to_owned) else {
                continue;
            };
            let name = transfer["name"].as_str().unwrap_or("Audio file");
            let direction = transfer["direction"].as_str().unwrap_or_default();
            let status = transfer["status"].as_str().unwrap_or("pending");
            let size = transfer["size"].as_u64().unwrap_or_default();
            let received = transfer["received"].as_u64().unwrap_or_default();
            if direction == "upload"
                && app
                    .lan
                    .pending_sends
                    .iter()
                    .any(|saved| saved.host_id == peer.host_id && saved.id == id)
            {
                continue;
            } else if direction == "download" && status == "pending" {
                let mut accept = false;
                let mut decline = false;
                setting_row(
                    ui,
                    &format!("Incoming from {}: {name}", peer.host_name),
                    Some(&format!(
                        "{:.1} MB · review before saving",
                        size as f64 / 1_000_000.0
                    )),
                    |ui| {
                        accept =
                            pill(ui, "Accept and download", None, app.lan.receive_files).clicked();
                        ui.add_space(6.0);
                        decline = pill(ui, "Decline", None, false).clicked();
                    },
                );
                if accept && app.lan.receive_files {
                    app.lan
                        .receive_file_from_peer(peer.host_id.clone(), id.clone());
                }
                if decline {
                    app.lan
                        .decline_remote_file(peer.host_id.clone(), id.clone());
                }
            } else if matches!(status, "accepted" | "sending" | "receiving") {
                let transfer_key = format!("{}:{id}", peer.host_id);
                let cancel = app.lan.active_downloads.get(&transfer_key).cloned();
                let mut cancel_clicked = false;
                setting_row(
                    ui,
                    &format!("Transfer {name}"),
                    Some(&format!(
                        "{status} · {:.1} of {:.1} MB",
                        received as f64 / 1_000_000.0,
                        size as f64 / 1_000_000.0
                    )),
                    |ui| {
                        if cancel.is_some() {
                            cancel_clicked = pill(ui, "Cancel", None, false).clicked();
                        } else if app.lan.receive_files {
                            cancel_clicked = pill(ui, "Resume", None, true).clicked();
                        }
                    },
                );
                if cancel_clicked {
                    if let Some(cancel) = app.lan.active_downloads.get(&transfer_key) {
                        cancel.store(true, Ordering::Relaxed);
                    } else if app.lan.receive_files {
                        app.lan
                            .receive_file_from_peer(peer.host_id.clone(), id.clone());
                    }
                }
            }
        }
        if remove {
            app.lan.remove_peer(&peer.host_id);
        }
    }
    if !app.lan.peers.is_empty() {
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_secs(1));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: &str, title: &str) -> Track {
        Track {
            id: id.into(),
            title: title.into(),
            artists: vec![],
            album: Some(Link {
                name: "Album".into(),
                id: None,
            }),
            duration: Some(200),
            thumbs: vec![],
            track_nr: None,
            plays: None,
            music_video: false,
        }
    }

    #[test]
    fn synced_lists_keep_the_richer_local_tracks_and_artwork() {
        let current = vec![LocalPlaylist {
            id: "a".into(),
            title: "A".into(),
            tracks: vec![track("1", "Local")],
            artwork: Some("art".into()),
        }];
        let meta = HashMap::from([(
            "2".to_owned(),
            Meta {
                title: "Remote".into(),
                artist: "R".into(),
                thumbnail: Some("t".into()),
            },
        )]);
        let out = to_local(
            vec![Playlist {
                id: "a".into(),
                title: "A2".into(),
                tracks: vec!["2".into(), "1".into()],
            }],
            &meta,
            &current,
        );
        assert_eq!(out[0].title, "A2");
        assert_eq!(out[0].artwork.as_deref(), Some("art"));
        assert_eq!(out[0].tracks[0].title, "Remote");
        assert_eq!(out[0].tracks[0].artists[0].name, "R");
        assert_eq!(
            out[0].tracks[1].duration,
            Some(200),
            "the local copy keeps its duration and album"
        );
        let (p, m) = to_sync(&out[0]);
        assert_eq!(p.tracks, vec!["2", "1"]);
        assert_eq!(m["2"].thumbnail.as_deref(), Some("t"));
    }
}
