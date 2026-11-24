# Source provenance audit

Step 1 of the independent-rebuild plan. Written 2026-11-22 against `main` (`origin/main` at the time of the scan).
This is an engineering audit, not a legal review.

## Upstreams compared

| Project | Repository | License | Created | Commit scanned |
|---|---|---|---|---|
| BitChord | https://github.com/kushagrasinghx/BitChord | GPL-3.0 | 2026-08-11 | `21356206de5594ca0bdaf4680ca2db68a2287729` |
| Convx | https://github.com/cosmictaserdev-creator/Convx | GPL-3.0 | 2026-07-23 | `f3dd4f71d603f8eb5828696fa7bab18c6172fb55` |

nuvx's history starts with one squashed commit (`627264e3`, "Initial commit: nuvx", 2026-11-09), and the README states
the repository is forked from BitChord. Git history therefore cannot show which files came from where; the trees were
compared directly.

## Method

`scripts/provenance/provenance.py` takes every source file (Kotlin, Java, Swift, Rust, TypeScript/JavaScript, C/C++,
Go, Python, CSS; vendored and third-party folders skipped), keeps its meaningful lines (25+ characters, not imports,
package lines or comments; brand names normalised), and measures what share of them also occur anywhere in each
upstream. `scripts/provenance/assets.py` hashes images, fonts, models and resource files and reports byte-identical
copies. Per-file results: `source-overlap.csv`, `asset-overlap.csv`.

**Limits.** Shared lines show shared code, not who copied whom; BitChord is older and the README names it as the
origin. Renamed identifiers lower the score. **Translated code is invisible to this scan:** a Kotlin file ported to Rust
or Swift scores 0 %, so a low score is not evidence of independence.

## Results

Lines in files sharing 50 % or more of their meaningful lines with an upstream:

| Area | Language | Lines | ≥ 50 % shared | 15–50 % |
|---|---|---|---|---|
| `app/android` | Kotlin, C++ | 64,807 | 91 % | 4 % |
| `app/ios` | Kotlin (KMP), Swift, Rust | 37,472 | 7 % | 10 % |
| `core/apple` | Swift | 30,581 | 13 % | 0 % |
| `app/desktop` | Rust | 21,363 | 0 % | 0 % |
| `app/web` | TypeScript | 13,964 | 86 % | 3 % |
| `core/shared` | Kotlin (KMP) | 10,793 | 94 % | 1 % |
| `core/sharedUi` | Kotlin (Compose) | 9,190 | 98 % | 1 % |
| `core/native` | C++ | 1,258 | 100 % | 0 % |
| `core/rust`, `core/worker`, `core/sync`, website, scripts | Rust, TypeScript | ~3,900 | 0 % | ~0 % |
| **All** | | **193,416** | **51 %** | **4 %** |

1,029 of 2,094 source files share 50 % or more with BitChord; 120 with Convx. Nine files are mainly Convx-shared and
not BitChord-shared: Android song recognition (`recognition/*`, including a Shazam signature generator), the intro
screen `NuvxIntro.kt`, iOS KMP `KuGou.kt`, and `core/apple/.../YouTubeSolver/yt.solver.core.js`.

The existing license map (`app/android/legal-assets/licenses/LICENSES.md`) names only `GlassSelectionPill.kt` as
BitChord-derived and `IosOverscroll.kt` carries a Convx header; both understate the measured overlap.

### Code ported to Rust in this migration (derived, not independent)

The Rust migration work of 2026-11-19 to 2026-11-22 used existing nuvx Kotlin as its reference. Where that Kotlin is
BitChord code, the Rust is a port of it, even though the scan shows 0 %:

| Rust (nuvx-core) | Modelled on | That file's BitChord overlap |
|---|---|---|
| `lyrics/words.rs` (word model) | `SyllableRuns.kt` `wordsFromRuns` | present in BitChord (`shared/.../lyrics/SyllableRuns.kt`) |
| `lyrics/sides.rs` (singer sides) | `LyricAlignments.kt` `lineAlignments` | 100 % |
| `lyrics/timeline.rs`, `lrc.rs` gap rules | `LyricGaps.kt`, `LrcLib.kt` | 100 %, 95 % |
| `loudness.rs` `norm_gain_mb` rounding | `PlaybackLoudnessController.kt` | 56 % |
| release-order rules used by Android | `AppUpdateChecker.kt` | 50 % |

The behaviour contracts in `core/contracts/lyrics-*.json` describe behaviour (inputs and outputs), not code.

### Other declared third-party code (already attributed)

Mornye-adapted bit-perfect output (LGPL-3.0-only), Orchard-derived analyzer (AGPL-3.0), AutoMix engine
(AGPL-3.0), Kira's Shaka SABR adapter (MIT), liquid_glass_easy motion (MIT), vendored Metrolist `innertubex` and
`quackjs` (iOS KMP), NewPipeExtractor (GPL-3.0, Android). See `LICENSES.md` and `THIRD_PARTY_NOTICES.txt`.

## Assets

477 asset files scanned; 20 byte-identical to an upstream. No PNG, SVG, ICO or JPG matches: the nuvx logo, icons and
images are not BitChord's or Convx's files.

| Asset | Where in nuvx | Concern |
|---|---|---|
| SF Pro Display (`.otf`, `.woff2`), 14 files | Android, Wear OS, desktop, web | Apple's font, copied from BitChord. Apple's font license generally limits SF Pro to use on Apple platforms; bundling it in Android, Windows/Linux and web apps is a likely license problem today, independent of any rebuild. Needs checking against Apple's terms. |
| `spotify_logo.webp` | `core/sharedUi` | Spotify trademark; use must follow Spotify's brand guidelines. |
| `vazirmatn_regular.ttf` | `core/sharedUi` | Vazirmatn, SIL OFL: redistribution allowed with its license. |
| AutoMix `.onnx` models (2 identical) | web, Android | Already documented as MIT in the license map. |

## What this means for an independent rebuild

- Android, the shared Kotlin modules, `core/native` and the web app are predominantly BitChord code. An independent
  rebuild cannot start from them; they can only serve as behaviour references through written specifications.
- The Rust desktop, `core/rust`, iOS Swift and the Cloudflare worker show little textual overlap, but translation is
  not detected; their origin needs a manual review (author history, design notes) before they can be called independent.
- Rust written in this migration from BitChord-derived Kotlin (table above) is derived and stays GPL-3.0 with
  attribution; an independent implementation of those features must be written from the behaviour contracts by
  someone who has not worked from that code.
- Independent of the rebuild: the SF Pro fonts and the license map's understatement of BitChord provenance should be
  fixed in the current app.

## Not done in this audit

No legal review; no manual review of low-overlap areas; Metrolist, NewPipe and other vendored trees were not scanned
against their upstreams; BitChord's own provenance (what it took from others) was not examined.
