# Provenance

How this tree was written (plan step 4, 2026-11-22). See `docs/rebuild/DECISIONS.md` R1 in the nuvx repository.

## Authors and process

1. **First pass: a separate agent** working only in an isolated folder (`nuvx-rebuild-work`) holding the specs
   (`spec/00-features.md`, `spec/01-app-shell.md`, `spec/DECISIONS.md`) and the GPL text. It did not open the nuvx,
   BitChord or Convx sources. One scripted edit tried a relative path that resolved to
   `C:\Users\bxane\Documents\Claude\nuvx\lib.rs`; that file does not exist and nothing was read. It wrote the
   workspace, `crates/nuvx-ui/src/shell.rs` (window-free logic and tests) and the first UI with a bottom player pill.
2. **Second pass: the main agent (Claude), at the owner's request ("do not create subagents, make everything
   here")**. This agent wrote the specs and has read the existing nuvx sources and BitChord's code during the
   provenance audit. It rewrote `crates/nuvx-ui/src/lib.rs`, `crates/nuvx-ui/assets/app.css` and
   `apps/desktop/src/main.rs` for the PC title-bar player (spec 01 §2.3) and the SF Symbols, and extended
   `shell.rs`. It worked from the specs and public Dioxus/tao APIs, not from the old code, but it is **not** an
   implementer who never saw the old code: this tree is not a two-team (clean-room style) implementation.

## Inputs

- Specs: `docs/rebuild/spec/00-features.md`, `docs/rebuild/spec/01-app-shell.md`, `docs/rebuild/DECISIONS.md`.
- Crates from crates.io: `dioxus` 0.7.10 (desktop feature, webview renderer; tao for the window) and `serde` 1.
- Assets:
  - `crates/nuvx-ui/assets/sf-symbols/`: Apple SF Symbols exported as SVG from the owner's SFStudio library
    (https://github.com/lylicht/SFStudio, commit in `SOURCE-COMMIT`) by `tools/export_sf_symbols.py`. Apple licenses
    SF Symbols for Apple platforms; their use elsewhere is the owner's decision (2026-11-17, reaffirmed 2026-11-22).
  - `crates/nuvx-ui/assets/brand/nuvx_mark.svg`: licht's nuvx mark from the nuvx repository (`core/brand`),
    recoloured to `currentColor`.
- No fonts are bundled; the CSS asks for "SF Pro Display" and falls back to system fonts.
