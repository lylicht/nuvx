# nuvx rebuild: PC shell

Plan step 4 of the rebuild (`docs/rebuild/PLAN.md`): a Dioxus 0.7 desktop app showing the **PC shell** of
`docs/rebuild/spec/01-app-shell.md` — the player bar as the window's title bar, the sidebar, placeholder pages,
side panel, full-screen player, themes and keyboard. No audio and no data yet: every page is a labelled empty
placeholder. How it was written: `PROVENANCE.md`.

License: GPL-3.0-only (`LICENSE`).

## Build and run

Rust 1.85+ (edition 2024; built with 1.100). Windows needs WebView2 (built in on Windows 11), Linux needs
WebKitGTK, macOS nothing extra.

```sh
cargo run -p nuvx-desktop                    # debug
cargo build --release -p nuvx-desktop        # target/release/nuvx(.exe)
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all -- --check
```

## Layout

| Path | What |
|---|---|
| `crates/nuvx-ui/src/shell.rs` | Window-free logic with unit tests: routes, sidebar sections, icons → SF Symbols (embedded), player state, keyboard mapping, widths and bar geometry (and the CSS built from them), theme tokens |
| `crates/nuvx-ui/src/lib.rs` | Dioxus components: title-bar player, window buttons, sidebar, pages, side panel, full-screen player |
| `crates/nuvx-ui/assets/app.css` | Layout, glass, backdrop, motion and accessibility styles |
| `crates/nuvx-ui/assets/sf-symbols/` | SF Symbol SVGs (re-export with `tools/export_sf_symbols.py`, never edit by hand) |
| `crates/nuvx-ui/assets/brand/` | The nuvx mark |
| `apps/desktop` | The `nuvx` binary: borderless window (Windows, Linux) or transparent title bar (macOS) |
| `tools/export_sf_symbols.py` | Exports SF Symbols from an SFStudio checkout |

`nuvx-ui`'s `desktop` feature turns on the native window calls; without it (tests, a later web build) they do nothing.

## Implemented

- **Title-bar player (§2.3):** 56 px bar across the top. Empty space drags the window; a double press maximises and
  restores. Windows/Linux: minimise, maximise/restore (icon follows the state) and close at the right (46 px each);
  macOS: traffic lights at the left. Left group (Shuffle up next, Previous, Play/Pause with cross-morph, Next, Repeat
  off → all → one), centre display (34.4 % of the width up to 720 px, 1.1 % left of centre, hidden under 160 px;
  artwork placeholder with hover maximise that opens the full-screen player, seek line, favourite, mini player),
  right group (volume glyph by level, click mutes; Lyrics; Up next; 32 px account avatar). Transport controls at 45 %
  and inactive while nothing is loaded; hover fades 0.12 s.
- **Sidebar (§2.2):** nuvx mark + name (Home), Search, Home, New, Podcasts, Radio; Library; Playlists; Spotify only
  when connected; bottom block Guide, Downloads ↗, Settings. Current item highlighted. 260 px, 34 % between 484 and
  768 px, drawer with scrim below 484 px (menu button in the bar).
- **Pages (§7):** placeholder for every page; Search has the search field; Settings links to its sub-pages.
- **Side panel (§2.4)** 300 px, **full-screen player (§2.5)** placeholder, always dark.
- **Themes (§5):** light/dark from the system, every §5.2 colour and §5.3 glass value, increase contrast, reduce
  transparency, reduce motion. **Icons (§5.6):** SF Symbols for every control.
- **Keyboard (§6):** Space, `/`, Ctrl/Cmd+F, Escape, Ctrl/Cmd+arrows.

## Not implemented yet

- Audio, queue, real data, accounts: Next/Previous/Shuffle up next do nothing; the avatar opens Settings in place
  of the account menu; the mini player button only toggles its pressed state (no second window); no buffering
  spinner (nothing buffers yet); title, artists and times stay empty.
- Tray, system media controls, notifications, window modes beyond maximise (later steps).
- Phone shell (§3), web build, visual parity against the current desktop app (step 11).

## Acceptance tests (spec 01 §9)

| # | Automated | Checked by hand on Windows 11 (2026-11-22) |
|---|---|---|
| 1 | `width_rules`, `layout_css_uses_the_spec_sizes`, `centre_display_width_and_position`, `bar_css_matches_the_rules` | At 1280 × 800: bar across the top with window buttons at the right, sidebar ≈ 258 px, centre display ≈ 438 px. Narrow widths not tried. |
| 2 | `only_the_open_page_is_current`, `sidebar_items_in_spec_order_without_spotify`, `user_and_spotify_playlists_listed_by_title`, `every_route_has_a_title` | Home and Downloads navigate and highlight. |
| 3 | `shortcuts_map_to_actions`, `only_ctrl_f_fires_while_typing`, `space_ignored_on_controls_and_alt_ignored_everywhere` | Escape closes the side panel. Other keys not tried by hand. |
| 4 | `colour_tokens_match_spec`, `theme_css_has_both_themes_contrast_and_forced_dark` | Dark theme seen; light and contrast not tried. |
| 5 | `panel_toggles_and_escape_closes_full_screen_first` | Lyrics opens the 300 px panel; Escape closes it. |
| 7 | — | Not tried. |
| 8 | `every_spec_icon_meaning_maps_to_its_sf_symbol_file`, `every_asset_file_is_embedded`, `double_click_window` | Double-click on empty bar space maximised; the maximise button shows the restore icon when maximised; every icon seen is the SF Symbol from §5.6. Dragging and minimise not tried. |
