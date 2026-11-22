# Independent rebuild: step plan

One step at a time; each ends with something that works and is tested, recorded here. Decisions: `DECISIONS.md`.
Rules for every step: R1 (spec and implementation separated), new code only under `rebuild/`.

| # | Step | Side | State |
|---|---|---|---|
| 1 | Provenance audit (`docs/provenance/PROVENANCE.md`) | spec | done 2026-11-22 |
| 2 | Decisions: process, YouTube Music access, UI framework (`DECISIONS.md`) | spec | done 2026-11-22 |
| 3 | Spec: app shell (navigation, sidebar, themes, layout breakpoints) from the current apps' behaviour | spec | done 2026-11-22 (`spec/01-app-shell.md`) |
| 4 | `rebuild/` Cargo workspace + Dioxus desktop shell built from spec 3 (window, navigation, theme), CI | implementation | next |
| 5 | Spec: playback model (states, queue, shuffle, repeat, gapless, errors) as testable cases | spec | |
| 6 | Audio engine + queue crates from spec 5 (Symphonia/CPAL), unit and state-machine tests | implementation | |
| 7 | YouTube Music through InnerTubeX (R2): Rust-facing interface spec, then Android (JNI) and iOS (Swift) bridges | both | |
| 8 | Desktop: bundled Java runtime + JNI to InnerTubeX; web: owner-run InnerTubeX relay (R2); size and start-up measured | implementation | |
| 9 | Desktop: search → play end to end | implementation | |
| 10 | Specs + implementation: library, playlists, lyrics, settings (one step each) | both | |
| 11 | Visual parity: screenshots of the current apps vs the rebuild, side by side | both | |
| 12 | Android, then iOS, then web builds of the same app, with native media controls | implementation | |
| 13 | TV, Auto, Wear OS, CarPlay plans and builds | both | |
| 14 | Data migration from the current apps | both | |
| 15 | Release readiness: tests, licenses, provenance, packaging | both | |

Open items found so far (outside the rebuild): SF Pro fonts bundled outside Apple platforms; the license map understates
BitChord provenance.
