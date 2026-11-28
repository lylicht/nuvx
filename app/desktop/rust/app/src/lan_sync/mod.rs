//! LAN Sync: phones on the same Wi-Fi sync Vyral playlists straight with this computer. Off until turned on.
pub mod bluetooth;
// Protocol rules shared with every vyral app live in the core.
pub use vyral_core::lan::{connect, crypto, replay};
pub mod files;
pub mod host;
#[cfg(test)]
mod host_tests;
pub mod peer;
pub mod tls;
