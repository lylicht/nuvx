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

## Measured, not moved (and why)

- Lyrics parsing on Android: gaps and word model now agree with nuvx-core. Android still runs its Kotlin parsers
  because they live in `core/shared`, which does not load nuvx-ffi yet (only the phone app module does).
- Stream recovery: only two numbers are shared; Android's skip reasons are tied to Media3 error codes (glue).

## Known regressions

None found. Intended desktop change: lyrics gaps now follow Android's rule. Pre-existing on main, not from this work:
desktop `cargo clippy -D warnings` reports ~25 findings in untouched files with the local rustc 1.100; `LocaleStringsTest` fails for 17 languages (3 strings
added in 3d6aaf69 without translations).

## Not verified

- Minified release build calling Rust at runtime (R8 build passes; runtime needs a signed release with playback).
- ARM device run (emulator is x86_64; the owner's phone is off-limits for device tests).
- iOS and web bridges (not built yet; iOS needs the Apple CI workflow).
- Desktop karaoke grouping on screen: covered by the shared join-rule tests, not checked visually with CJK lyrics.

## Blockers

See `docs/rust-migration/BLOCKERS.csv`. New: none.

## Next executable action

06b: wire Android TV and Wear to nuvx-ffi; 06c: expose the same exports through `app/ios/native-core`
(UniFFI, verified by the Apple builds workflow); then backup format (contract `core/contracts/backup-v1.json`).
