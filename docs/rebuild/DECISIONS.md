# Independent rebuild: decisions

Each decision: what, why, what it costs, who decided. Newest last. Background: `docs/provenance/PROVENANCE.md`.

## R1. Specification and implementation are separated (2026-11-22, owner request; process by Claude)

The current tree is about half BitChord code (GPL-3.0), and the agent that ran the migration so far has read it. So:

- **Spec side** (may read the old code): writes behaviour specifications only, in `docs/rebuild/spec/`: what the
  user sees and does, inputs and outputs, edge cases, as prose, tables and test cases. No code, no internal structure,
  no identifiers copied from the old sources.
- **Implementation side** (fresh agents or people): works only from `docs/rebuild/spec/`, public standards and public
  documentation. Must not open the nuvx app sources, BitChord or Convx. Every implementation task brief says so.
- New code lives in its own tree, `rebuild/`, and imports nothing from the existing apps or `core/`.

This is a documented two-team process, not a certified clean-room. Rust written earlier in the migration from
BitChord-derived Kotlin (see the provenance report) is derived and is not reused in `rebuild/`.

## R2. YouTube Music access: keep the current method (2026-11-22, owner)

YouTube Music has no official API for search, browsing or playback. The owner chose to keep using YouTube Music's
web client interface (InnerTube), as nuvx does today, rather than limit the app to official APIs (which would mean no
YouTube Music playback). Consequences:

- This goes against YouTube's terms of service and can break whenever YouTube changes it; the app must fail
  gracefully and the client must be easy to update.
- The client is written independently, from public descriptions of the protocol and observed requests, not from
  nuvx's, Metrolist's, NewPipe's or BitChord's client code.
- No circumvention of DRM or of signed-in access the user does not have.

## R3. UI framework: Dioxus 0.7 with the webview renderer, DOM on the web (2026-11-22, Claude)

Requirements: one shared UI codebase for Windows, Linux, macOS, Android, iOS and web; keep nuvx's look (glass
panels, blur, motion, typographic lyrics); native media controls through platform glue.

| | Dioxus 0.7 | Slint 1.17 | egui 0.36 |
|---|---|---|---|
| Android / iOS | Supported (CLI, system webview) | Supported / iOS tech preview | Supported / not official |
| Web | DOM | Canvas | Canvas |
| nuvx's glass, blur, motion | CSS (`backdrop-filter`, transitions): close match | Limited effects | Hand-drawn |
| One UI codebase everywhere | Yes | Yes | Yes |

Chosen: **Dioxus 0.7 stable** (`dioxus` 0.7.9 at the time of writing), webview renderer on desktop and mobile, DOM
renderer on the web. Its native renderer (Blitz) is experimental and is not used. Costs and risks: a system webview on
every platform (memory, start-up time; to be measured on a low-end Android phone before mobile work goes far); audio,
media sessions and background playback stay native (Rust audio engine plus thin platform glue), not in the webview.
Re-evaluate if webview performance on Android fails the measured budget.

Sources: Dioxus releases (https://github.com/DioxusLabs/dioxus/releases), dioxus 0.7.9 docs
(https://docs.rs/crate/dioxus/latest), Slint 1.15 and 1.17 release notes (https://slint.dev/blog/slint-1.15-released,
https://slint.dev/blog/slint-1.17-released), eframe docs (https://docs.rs/crate/eframe/latest).
