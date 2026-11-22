//! The Android apps' door into nuvx-core. Each export is a thin call into the core; no logic lives here.
//!
//! Contract for every export (Task 06):
//! - Values are copied across; Kotlin owns nothing on the Rust side, so there is nothing to free.
//! - A Rust panic is caught by UniFFI and reaches Kotlin as an exception; it never unwinds into the JVM.
//! - Calls are synchronous and thread-safe (pure functions), so any thread may call them.
//! - `core_api_version` changes when an export's signature or meaning changes.

uniffi::setup_scaffolding!();

/// Bumped when an export changes shape or meaning, so the app can tell which library it loaded.
#[uniffi::export]
pub fn core_api_version() -> u32 {
    1
}

/// Volume normalization gain in millibels. See `nuvx_core::loudness::norm_gain_mb`.
#[uniffi::export]
pub fn loudness_gain_mb(loudness_db: f64, target_lufs: i32) -> i32 {
    nuvx_core::loudness::norm_gain_mb(target_lufs, loudness_db)
}
