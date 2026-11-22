# Rust core candidates (2026-11-22)

Which logic still exists in more than one language, and in what order it should move into `core/rust/nuvx-core`.
Feeds Tasks 05–16. Line counts come from file names matching each area (tests and vendored code excluded), so
they include some UI code. Treat them as relative sizes, not exact figures.

## Where the core can reach today

| Platform | Links nuvx-core? | How |
|---|---|---|
| Desktop (Rust) | yes | direct dependency |
| iOS | **yes, indirectly** | `app/ios/native-core` depends on nuvx-core and already exports to Swift through UniFFI 0.32. A new export there needs no new build setup. |
| Android phone | **yes** (2026-11-22) | `core/rust/nuvx-ffi` (UniFFI → Kotlin over JNA), built by Gradle with cargo-ndk. First export: loudness gain. |
| Android TV, Wear | no | Same crate; not wired yet. |
| Web | **no** | No WASM. Needs wasm-bindgen; `"type": "module"` loader caveat in `RUST_ANYTHING_EVALUATION.md`. |

Web is the remaining gate: until it has WASM, every port below helps desktop, iOS and the phone only.

## Ranked candidates

| # | Area | Already in nuvx-core | Other copies still live | Why this rank |
|---|---|---|---|---|
| 1 | **Lyrics parsing** (LRC, enhanced LRC, TTML, QRC, credits) | yes, `lyrics/` (not swap-ready, see below) | Kotlin `core/shared/.../lyrics` **and** a diverged Kotlin copy in `app/ios/shared/.../lyrics` (`EnhancedLrc.kt` 105 differing lines, `LrcLib.kt` 256); Swift `core/apple`; TS `app/web/app/src/lyrics/{parse,ttml}.ts` | Rust is written and tested; four other copies already disagree. Pure functions, fixtures exist in `app/web/fixtures/lyrics`. Easiest first consumer for the Android and web bindings. |
| 2 | **Update tag/version rules** | yes, `release.rs` + `core/contracts/update-order.json` | Kotlin `AppUpdateChecker.kt`, KMP iOS checker, Swift | Small; shared cases already exist. Good smoke test for a new binding. |
| 3 | **Backup format** | yes, `backup.rs` + `contracts/backup-v1.json` | Kotlin `core/shared` + `app/ios/shared`, Swift (~2.3k lines) | Data compatibility matters; contract file already pins it. |
| 4 | **Equalizer / loudness gain** | yes, `eq.rs`, `loudness.rs` | Android Kotlin (~1.9k lines); iOS native-core switch-over is the open 05e slice | Finish 05e first (needs Apple CI), then Android once bound. |
| 5 | **Queue / shuffle / repeat** | no | Kotlin Android + `core/shared` + `sharedUi`, Swift, TS, desktop Rust (~5.9k lines) | Deterministic and easy to test. Task 12. Persisted queue prefs must stay readable. |
| 6 | **Listen Together protocol + state** | partly (models) | Kotlin Android, KMP iOS, Swift, TS web; server is TS `core/worker` (~10k lines) | High drift risk on the wire format. Move message types and state rules, not sockets. Task 13. |
| 7 | **YouTube Music (InnerTube) response parsing** | no | Kotlin `core/shared`, KMP iOS (`InnertubeParser.kt` + vendored innertubex), Swift, TS web, desktop Rust `src/ytm` (~19k lines) | Biggest win: YouTube changes break every platform at once and get fixed five times. Biggest risk too, so it goes after the bindings are proven. Start from the desktop Rust parser, not a fresh port. Task 10. |
| 8 | **Stream recovery** | yes (rules, 61 lines) | Kotlin, Swift (~3.3k lines incl. glue) | Only the decision rules are portable; the resolving itself is network and player glue. |

## Lyrics: Kotlin vs nuvx-core, measured

Fixtures `app/web/fixtures/lyrics/lrclib-get.json` (line-synced LRC) and `lrc-red-document.ttml` (word-synced TTML),
parsed by Android's `LrcLib.parseLrc` / `TtmlLyrics.parse` and by `nuvx_core::lyrics::{lrc::parse_lrc, ttml::parse_ttml}`:

- Line start times and text: **identical** for every lyric line in both fixtures.
- Instrumental gap lines differ: Kotlin adds one at 0 ms in the LRC; Rust adds three mid-song (192 580, 202 120,
  218 080 ms) and one at the end of the TTML.
- Word timing is shaped differently: Kotlin `LyricWord`s with syllables, Rust `Part`s (more, smaller pieces).

Both differences are visible in the lyrics view, so Android keeps its parser until one gap rule and one word model
are chosen (an owner decision on which app's behaviour is right), pinned in a `core/contracts/lyrics-*.json` case
file, and both sides pass it.

## Leave native (glue, not duplicated logic)

Playback (Media3 / AVAudioSession), downloads and cache storage, Canvas rendering, notifications, Cast, Auto, Wear,
all UI. Scrobbling is small (~1.9k lines) and mostly HTTP; revisit after #7.

## Suggested next steps

1. ~~Task 06 for Android~~: done with loudness gain (`core/contracts/loudness-gain.json`, identical by construction).
2. Same lyrics export through `native-core` for iOS; replace the Swift and KMP-iOS parsers (needs Apple CI).
3. WASM for web with the same lyrics module.
4. Then #2–#5 through the same bindings, one per change, each pinned by a `core/contracts` case file.
