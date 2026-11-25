//! Anonymous desktop installation presence for the public live client counter.
//!
//! The Worker accepts native applications without an Origin header, but it only
//! counts installations that actively POST /api/presence. Desktop previously
//! never sent these pings, so Windows, Linux and macOS always showed as 0 PCs.

use std::path::Path;
use std::time::Duration;

use aes_gcm::aead::{OsRng, rand_core::RngCore};
use serde_json::json;
use tokio::sync::oneshot;

const DEFAULT_SERVER: &str = "https://nuvx-listen.lylicht.workers.dev";
const INSTALL_ID_FILE: &str = "presence_id";
const DEFAULT_INTERVAL: Duration = Duration::from_secs(300);
const RETRY_INTERVAL: Duration = Duration::from_secs(60);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(4);

pub struct Presence {
    stop: Option<oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Presence {
    /// Start only in the primary desktop process, never in sign-in or
    /// Turnstile child windows. A stable random ID makes repeat pings count as
    /// one installation without sending user accounts or device details.
    pub fn start(data_dir: &Path) -> Option<Self> {
        let id = match install_id(data_dir) {
            Ok(id) => id,
            Err(error) => {
                log::warn!("presence: couldn't persist anonymous installation ID: {error}");
                new_id()
            }
        };
        let (stop, receiver) = oneshot::channel();
        let thread = match std::thread::Builder::new().name("nuvx-presence".into()).spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                Ok(runtime) => runtime,
                Err(error) => {
                    log::warn!("presence: couldn't start network task: {error}");
                    return;
                }
            };
            runtime.block_on(run(id, receiver));
        }) {
            Ok(thread) => thread,
            Err(error) => {
                log::warn!("presence: couldn't start thread: {error}");
                return None;
            }
        };
        Some(Self { stop: Some(stop), thread: Some(thread) })
    }
}

impl Drop for Presence {
    fn drop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn endpoint() -> String {
    let server = std::env::var("NUVX_PARTY_SERVER").unwrap_or_else(|_| DEFAULT_SERVER.into());
    format!("{}/api/presence", server.trim_end_matches('/'))
}

async fn ping(client: &reqwest::Client, endpoint: &str, id: &str, open: bool) -> anyhow::Result<Duration> {
    let response = client
        .post(endpoint)
        .json(&json!({ "id": id, "platform": "pc", "open": open }))
        .send()
        .await?
        .error_for_status()?;
    // The server supplies its preferred heartbeat period (currently 300 s).
    let body: serde_json::Value = response.json().await?;
    let seconds = body["intervalSec"].as_u64().unwrap_or(DEFAULT_INTERVAL.as_secs()).clamp(60, 3600);
    Ok(Duration::from_secs(seconds))
}

async fn run(id: String, mut stop: oneshot::Receiver<()>) {
    let client = match crate::proxy::apply(reqwest::Client::builder())
        .user_agent(concat!("nuvx-desktop/", env!("CARGO_PKG_VERSION")))
        .timeout(REQUEST_TIMEOUT)
        .build()
    {
        Ok(client) => client,
        Err(error) => {
            log::warn!("presence: couldn't create HTTP client: {error}");
            return;
        }
    };
    let endpoint = endpoint();
    let mut next = Duration::ZERO;
    let mut registered = false;
    loop {
        tokio::select! {
            _ = &mut stop => {
                if registered {
                    // Remove the installation immediately when the app exits;
                    // a crash is cleaned up by the Worker's presence TTL.
                    if let Err(error) = ping(&client, &endpoint, &id, false).await {
                        log::debug!("presence: offline ping failed: {error}");
                    }
                }
                return;
            }
            _ = tokio::time::sleep(next) => {
                match ping(&client, &endpoint, &id, true).await {
                    Ok(interval) => {
                        registered = true;
                        next = interval;
                    }
                    Err(error) => {
                        log::debug!("presence: online ping failed: {error}");
                        next = RETRY_INTERVAL;
                    }
                }
            }
        }
    }
}

fn install_id(data_dir: &Path) -> std::io::Result<String> {
    std::fs::create_dir_all(data_dir)?;
    let file = data_dir.join(INSTALL_ID_FILE);
    if let Ok(id) = std::fs::read_to_string(&file) {
        let id = id.trim();
        if is_uuid(id) {
            return Ok(id.to_ascii_lowercase());
        }
    }
    let id = new_id();
    std::fs::write(file, &id)?;
    Ok(id)
}

fn is_uuid(id: &str) -> bool {
    let bytes = id.as_bytes();
    bytes.len() == 36
        && bytes.iter().enumerate().all(|(i, byte)| {
            if matches!(i, 8 | 13 | 18 | 23) {
                *byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
}

fn new_id() -> String {
    let mut bytes = [0_u8; 16];
    OsRng.fill_bytes(&mut bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40; // RFC 4122 version 4
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // RFC 4122 variant
    let hex = bytes.iter().map(|byte| format!("{byte:02x}")).collect::<Vec<_>>().concat();
    format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn install_id_is_valid_and_persists_between_launches() {
        let path = std::env::temp_dir().join(format!("nuvx-presence-test-{}", new_id()));
        let id = install_id(&path).unwrap();
        assert!(is_uuid(&id));
        assert_eq!(id.as_bytes()[14], b'4');
        assert!(matches!(id.as_bytes()[19], b'8' | b'9' | b'a' | b'b'));
        assert_eq!(install_id(&path).unwrap(), id);
        std::fs::remove_dir_all(path).unwrap();
    }

    #[test]
    fn invalid_or_non_uuid_install_ids_are_rejected() {
        assert!(!is_uuid("party_device"));
        assert!(!is_uuid("not-a-uuid"));
        assert!(!is_uuid("00000000-0000-0000-0000-000000000000extra"));
    }

    #[test]
    fn generated_install_ids_are_distinct() {
        assert_ne!(new_id(), new_id());
    }
}
