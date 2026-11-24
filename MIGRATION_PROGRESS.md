# Migration progress

Checkpoint for the Rust-core migration. The detailed, machine-readable ledger is
[`docs/rust-migration/STATUS.json`](docs/rust-migration/STATUS.json); the contract and rules are in
[`docs/rust-migration/CONTRACT.md`](docs/rust-migration/CONTRACT.md); what moves next and why is in
[`docs/rust-migration/CANDIDATES.md`](docs/rust-migration/CANDIDATES.md).

## Scope (what this migration is and is not)

nuvx is a YouTube Music player. Portable rules move into one Rust core (`core/rust/nuvx-core`) that every app
calls; native UIs (Compose, SwiftUI, egui, React/CSS) and platform glue (Media3, AVAudioSession, service workers)
stay. Nothing a user sees changes. There is no VPN, tunnel, DNS or routing engine in nuvx, so those parts of the
generic master prompt do not apply.

## Done

| Date | Step | Evidence |
|---|---|---|
| 2026-11-19 | Tasks 00–02; nuvx-core with lyrics, LAN Sync crypto, model, playlist files, EQ, loudness, backup, release order, recovery rules; desktop uses it | STATUS.json 05a–05e |
| 2026-11-22 | Android phone calls nuvx-core through `core/rust/nuvx-ffi` (UniFFI → Kotlin over JNA, cargo-ndk in Gradle) | LoudnessGainTest 4/4 through Rust on the JVM; RustCoreDeviceTest on the x86_64 emulator |
| 2026-11-22 | Loudness gain: one millibel function in nuvx-core, Kotlin formula removed | core/contracts/loudness-gain.json on Rust, desktop, Android |
| 2026-11-22 | Update rules (is newer, old builds, release order): Kotlin copies removed, Android calls nuvx-core | AppUpdateCheckerTest 26/26 through Rust; desktop update tests 7/7; new contract case for builds without a build time |

| 2026-11-22 | Lyrics gap rule: Android's, chosen by the owner; nuvx-core and the desktop follow it | core/contracts/lyrics-gaps.json: Android LyricGapsContractTest 2/2 (11 cases, unchanged code), nuvx-core 28/28, desktop 267/267 |

| 2026-11-22 | Lyrics word model: Android's, chosen by the owner; nuvx-core implements it and the desktop karaoke groups words by it | core/contracts/lyrics-words.json: Android LyricWordsContractTest (8 cases, unchanged code), nuvx-core 29/29, desktop 267/267 |

| 2026-11-22 | nuvx-ffi moved into `core/shared` (phone and TV get it); Android's lyrics word builder runs in nuvx-core | core/shared JVM tests 157/157 through Rust; phone unit tests (only the pre-existing locale failures); RustCoreDeviceTest on the emulator incl. words; phone and TV debug + minified release APKs build with 4 ABIs |

| 2026-11-22 | LRC: one parser for every app (nuvx-core); owner decisions on repeated stamps, credit lines, last word; desktop offset and `<R>` bugs fixed | core/contracts/lyrics-lrc.json: nuvx-core 32/32, core/shared 158/158 (incl. LyricLrcContractTest and provider tests), desktop 267/267, RustCoreDeviceTest on the emulator |

| 2026-11-22 | TTML: one parser for every app (nuvx-core); owner decisions on singer sides and CJK words; desktop sides from the core | core/contracts/lyrics-ttml.json (7 cases): nuvx-core 33/33, core/shared 159/159, Android lyrics tests (WordSyncTest, LrcRedTest, ...) 92/92 through Rust, desktop 267/267 |

## Measured, not moved (and why)

- Android's enhanced-LRC parser (`EnhancedLrc`, word-timed LRC from some providers): gaps and words agree with
  nuvx-core, the rest is not compared yet.
- Stream recovery: only two numbers are shared; Android's skip reasons are tied to Media3 error codes (glue).

## Known regressions

None found. Intended desktop change: lyrics gaps now follow Android's rule. Pre-existing on main, not from this work:
desktop `cargo clippy -D warnings` reports ~25 findings in untouched files with the local rustc 1.100; `LocaleStringsTest` fails for 17 languages (3 strings
added in 3d6aaf69 without translations).

## Not verified

- Minified release build calling Rust at runtime (R8 build passes; runtime needs a signed release with playback).
- ARM device run (emulator is x86_64; the owner's phone is off-limits for device tests).
- iOS and web bridges (not built yet; iOS needs the Apple CI workflow).
- Desktop karaoke on screen: word grouping and the new singer sides are covered by shared tests, not checked visually.

## Blockers

See `docs/rust-migration/BLOCKERS.csv`. New: none.

## Next executable action

EnhancedLrc comparison and contract, then Android calls the Rust parser; 06c: expose the same exports through `app/ios/native-core`
(UniFFI, verified by the Apple builds workflow); then backup format (contract `core/contracts/backup-v1.json`).
