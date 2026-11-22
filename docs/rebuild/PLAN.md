# Independent rebuild: step plan

One step at a time; each ends with something that works and is tested, recorded here. Decisions: `DECISIONS.md`.
Rules for every step: R1 (specs first; since step 4 written in the main session, see the R1 update), new code only under `rebuild/`.

| # | Step | Side | State |
|---|---|---|---|
| 1 | Provenance audit (`docs/provenance/PROVENANCE.md`) | spec | done 2026-11-22 |
| 2 | Decisions: process, YouTube Music access, UI framework (`DECISIONS.md`) | spec | done 2026-11-22 |
| 2b | Feature inventory from nuvx and BitChord's multiplatform-rebase branch, BitChord backend excluded (`spec/00-features.md`); downloadable models (R4) | spec | done 2026-11-22 |
| 3 | Spec: app shell (navigation, sidebar, themes, layout breakpoints) from the current apps' behaviour | spec | done 2026-11-22 (`spec/01-app-shell.md`) |
| 4 | `rebuild/` Cargo workspace + Dioxus desktop shell built from spec 3 (window, navigation, theme), CI | implementation | done 2026-11-22 (PC title-bar player, SF Symbols; `rebuild/README.md`) |
| 5 | **Desktop first** (owner prompt 2026-11-22): Windows, Linux, macOS only until the desktop milestones are done; mobile and web need the owner's go-ahead | — | decided |
| M2 | Real playback: spec 02 (local library, playback, queue, saved settings) and its implementation | both | in progress |
| M3 | Full interface: Home, Library, Search, full player, mini player, lyrics view, Settings; visual parity with the current desktop app | both | |
| M4 | Music providers: source adapters, YouTube Music through InnerTubeX (R2), accounts, track resolution | both | |
| M5 | Library and advanced audio: playlists, favourites, history, synced lyrics, equalizer, gapless, crossfade, AutoMix (models on demand, R4) | both | |
| M6 | Desktop utilities: downloads, offline, Discord, scrobbling, system media controls, tray, notifications, shortcuts | both | |
| M7 | nuvx-owned backend in Rust (Axum): Listen Together with a new protocol, device sync, auth; two real clients synchronised | both | |
| M8 | Release quality: Windows/Linux/macOS tests, performance, accessibility, provenance and licence audit, packages (EXE/MSI, AppImage/DEB/RPM, DMG), data migration | both | |

Later, with the owner's go-ahead: Android, iOS, web, TV, Auto, Wear OS, CarPlay (spec 00).

Open items found so far (outside the rebuild): SF Pro fonts bundled outside Apple platforms; the license map understates
BitChord provenance.
