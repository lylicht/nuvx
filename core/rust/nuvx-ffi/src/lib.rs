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
    2
}

/// Volume normalization gain in millibels. See `nuvx_core::loudness::norm_gain_mb`.
#[uniffi::export]
pub fn loudness_gain_mb(loudness_db: f64, target_lufs: i32) -> i32 {
    nuvx_core::loudness::norm_gain_mb(target_lufs, loudness_db)
}

/// Whether a release should be offered over the installed one. See `nuvx_core::release::release_is_newer`.
#[uniffi::export]
pub fn release_is_newer(
    candidate_version: String,
    candidate_tag: String,
    candidate_published_at: String,
    current_version: String,
    current_tag: String,
    current_build_time: String,
    allow_nightly: bool,
) -> bool {
    nuvx_core::release::release_is_newer(
        &candidate_version,
        &candidate_tag,
        &candidate_published_at,
        &current_version,
        &current_tag,
        &current_build_time,
        allow_nightly,
    )
}

/// A pre-licht release that must never be offered. See `nuvx_core::release::is_old_build`.
#[uniffi::export]
pub fn is_old_build(name: Option<String>, tag: String, prerelease: bool) -> bool {
    nuvx_core::release::is_old_build(name.as_deref(), &tag, prerelease)
}

/// Which of two releases to prefer: negative, zero or positive like a comparator.
/// See `nuvx_core::release::compare_release_order`.
#[uniffi::export]
pub fn compare_release_order(
    a_version: String,
    a_tag: String,
    a_time: String,
    b_version: String,
    b_tag: String,
    b_time: String,
) -> i32 {
    nuvx_core::release::compare_release_order(
        &a_version, &a_tag, &a_time, &b_version, &b_tag, &b_time,
    ) as i32
}

/// One timed run of a lyric line: start and end in ms and the raw text, spaces included.
#[derive(uniffi::Record)]
pub struct CoreRun {
    pub start_ms: i64,
    pub end_ms: i64,
    pub raw: String,
}

/// A syllable of a word: its time and its UTF-16 range in the word's text.
#[derive(uniffi::Record)]
pub struct CoreSyllable {
    pub start_ms: i64,
    pub end_ms: i64,
    pub start_char: i32,
    pub end_char: i32,
}

#[derive(uniffi::Record)]
pub struct CoreWord {
    pub start_ms: i64,
    pub end_ms: i64,
    pub text: String,
    pub syllables: Vec<CoreSyllable>,
}

fn ms(v: i64) -> i32 {
    v.clamp(i32::MIN.into(), i32::MAX.into()) as i32
}

/// Android's word model for a line's runs. See `nuvx_core::lyrics::words::words_from_runs`.
#[uniffi::export]
pub fn words_from_runs(runs: Vec<CoreRun>, explicit_spacing: bool) -> Vec<CoreWord> {
    let runs: Vec<(i32, i32, &str)> = runs
        .iter()
        .map(|r| (ms(r.start_ms), ms(r.end_ms), r.raw.as_str()))
        .collect();
    nuvx_core::lyrics::words::words_from_runs(&runs, explicit_spacing)
        .into_iter()
        .map(|w| CoreWord {
            start_ms: w.start.into(),
            end_ms: w.end.into(),
            text: w.text,
            syllables: w
                .syllables
                .into_iter()
                .map(|s| CoreSyllable {
                    start_ms: s.start.into(),
                    end_ms: s.end.into(),
                    start_char: s.start_char as i32,
                    end_char: s.end_char as i32,
                })
                .collect(),
        })
        .collect()
}
