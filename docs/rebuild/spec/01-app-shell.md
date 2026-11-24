# Spec 01: app shell

What the user sees around every page: navigation, player chrome, themes, sizes, layout changes by screen width,
keyboard behaviour. Written from the current desktop app (PC layout), the web player (live at web.nuvx.lol on 2026-11-22) and the
Android phone app. Behaviour only: implementers build this from the text, not from the old code
(`docs/rebuild/DECISIONS.md`, R1). Visual parity is checked side by side in plan step 11.

## 1. Two shells

| Shell | Used by | Navigation | Player |
|---|---|---|---|
| **PC shell** | Desktop apps (Windows, Linux, macOS) | Fixed sidebar on the left | **Player bar across the top**, which is also the window's title bar (§2.3) |
| **Web shell** | Web at 484 px wide and up | Fixed sidebar on the left | Floating player pill centred at the bottom (§2.6) |
| **Phone shell** | Android and iOS apps on phones | Floating glass tab bar at the bottom | Mini-player pill above the tab bar |
| Narrow web | Web below 484 px | Top bar with a menu button; sidebar opens as a drawer | Mini-player bar at the bottom (only while something is loaded) |

Tablets and Android TV, Auto and Wear OS are specified later (plan step 13); until then tablets use the sidebar shell.

## 2. PC and web shells (sidebar)

### 2.1 Layout

- Sidebar on the left, full window height, **260 px** wide (web between 484 and 768 px: about 34 % of the window
  width). It uses the glass material (§5.3).
- Page content to the right of it, scrolling vertically; side padding (gutter) **25 px**, **40 px** at 1000 px wide
  and up.
- **PC:** the player bar spans the full window width at the top, **56 px** tall, above both the sidebar and the content
  (§2.3). **Web:** a player pill floats over the content instead (§2.6).
- Optional side panel on the right (§2.4), **300 px** wide.
- Behind everything: an animated soft gradient ("mesh backdrop") taken from the current artwork's colours; with
  nothing playing it is a dark, slightly green-tinted gradient in the dark theme.

### 2.2 Sidebar contents, top to bottom

1. App mark and the word "nuvx" (bold, 19 px, slightly tight letter spacing); clicking it goes to Home.
2. Main items, each an icon plus label: **Search, Home, New, Podcasts, Radio**.
3. Section header **Library** (small, emphasised, secondary colour), then: **Recently Played, Artists, Albums, Songs,
   Made for You**.
4. Section header **Playlists**: **All Playlists**, then the user's playlists by title.
5. Section header **Spotify** (only when Spotify is connected): the imported Spotify playlists.
6. Bottom block: **Back to nuvx.lol** (web only), **Guide**, **nuvx Extension** (web only), then a row with
   **Downloads** (link with an up-right arrow) on the left and a **Settings** gear button on the right; the account
   button sits in this block.

The list scrolls on its own when it is taller than the window, without a visible scrollbar. The current page's item
has a filled rounded background (8 px radius, key colour at 14 % opacity). Hovering an item shows a faint fill.

### 2.3 PC player bar (title bar)

The top **56 px** of the window is one bar that holds the player and serves as the custom title bar.

- **Window frame:** dragging empty bar space moves the window; double-clicking it maximises and restores. On Windows
  and Linux the minimise, maximise and close buttons sit at the right end (three buttons, **46 px** wide each, full bar
  height); on macOS the traffic-light buttons sit at the left end and the bar's contents start after them. In full
  screen the window buttons are hidden.
- **Look:** in the light theme the bar has a solid surface fill; in the dark theme it is transparent over the backdrop.
  A **1 px** hairline runs along its bottom edge (white at 8 % in dark, the divider colour in light).
- **Left group**, button centres **31.5 px** apart, **30 px** hit targets: **Shuffle up next** (a one-shot action that
  shuffles the upcoming queue, not a toggle), **Previous**, **Play/Pause** (play and pause glyphs cross-morph in
  0.18 s; a small spinning ring replaces them while buffering), **Next**, **Repeat** (cycles off → all → one; the glyph
  changes to repeat-one). The group sits midway between the bar's left edge (at least **22 px** in) and the centre
  display.
- **Centre display**, centred **1.1 %** of the window width left of the window's centre, **34.4 %** of the window width
  wide, at most **720 px**; if it would be narrower than **160 px** it is hidden. Contents:
  - square artwork at its left end, rounded on the left corners only; a music-note placeholder when nothing is loaded;
    hovering darkens it and shows a maximise glyph; clicking it opens the full-screen player ("Open now playing");
  - title (semibold 12.5 px) and artists (regular 11 px, secondary colour) to the right of the artwork; the title and
    each artist name are clickable;
  - a thin seek line along the display's bottom edge; hovering or dragging it thickens it, fades the title and artists
    and shows elapsed time on the left and "-remaining" on the right (semibold 11 px); dragging seeks;
  - at its right end a **favourite** star ("Add to favourites" / "Remove from favourites", filled when on, with a short
    pop) and a **mini player** button ("Mini player" / "Close mini player"; tinted with the accent colour while the mini
    player window is open).
- **Right group**, centres **35 px** apart: **volume** (glyph shows the level in four steps, or muted; clicking mutes
  and unmutes), **Lyrics**, **Up next**; then the **account** avatar, a **32 px** circle (the account's initial on a
  colour, or a person glyph when signed out), **16 px** from the right edge of the bar's content area (before the
  window buttons). Clicking the avatar opens the account menu.
- **States:** hovering a control fades its glyph from the secondary to the primary text colour in 0.12 s; with nothing
  loaded the transport controls are drawn at **45 %** opacity and do nothing.

### 2.4 Side panel

Opened by the player's Lyrics or Up Next buttons (PC bar or web pill); shows that panel (**Lyrics** or **Up Next**) at 300 px on the right.
Pressing the active button again or Escape closes it. Below 484 px it covers the full width.

### 2.5 Full-screen player

Opened from the player artwork; always uses the dark palette over the artwork's colours. Escape closes it.

### 2.6 Web player pill

On the web (not the PC apps) the player is a pill floating over the content, centred horizontally in the content
area, **20 px** above the bottom edge, **56 px** tall, up to **668 px** wide (narrower when the window is). Left to
right: **Shuffle, Previous, Play/Pause, Next, Repeat**, the centre display (artwork, title, artist, progress; the app
mark when nothing is loaded; clicking it opens the full-screen player), then **Lyrics**, **Up Next** and the volume
control. Lyrics is hidden while a live radio stream plays; both buttons show as pressed while their panel is open.

## 3. Phone shell (Android and iOS apps)

### 3.1 Tab bar

- Floating glass bar at the bottom, **19 dp** from the screen sides, lifted **6 dp** above the system gesture area
  (at least **15 dp** from the bottom edge).
- Tabs left to right: **Home, Library, Radio, Explore**, then **Search** as a separate round glass button beside
  the bar.
- Each tab: icon (**28 dp**, symbol glyphs **22 dp**) over a label (**10 sp**, letter spacing 0.5), **6.5 dp**
  vertical padding, **1 dp** between icon and label. Unselected tabs use the content colour at **65 %** opacity.
- The selected tab sits on a glass pill (**4 dp** inset) that slides between tabs with a spring (damping ratio
  **0.72**, stiffness **320**); tabs scale slightly when pressed.
- Tapping the selected tab again "reselects" it (the page scrolls back to its top); selecting a tab plays a light
  haptic tick.
- With reduced transparency or "reduce dynamic blur" on, the glass becomes an opaque surface.

### 3.2 Collapsing on scroll

Scrolling page content down by **50 dp** (accumulated in one direction) collapses the bar into its inline form (a
compact pill with the mini-player inline); scrolling back up by 50 dp expands it again. Changing scroll direction
resets the count.

### 3.3 Mini-player

A glass pill attached above the tab bar while something is loaded: artwork, title and artist, play/pause. Tapping it
opens the full-screen player.

## 4. Narrow web (below 484 px)

- Top bar, **52 px** tall: menu button on the left (label "Open menu" / "Close menu"), app mark and "nuvx" centred.
- The sidebar (§2.2) slides in from the left as a drawer, **min(300 px, 82 % of the width)** wide, with a shadow and
  a dimmed scrim over the page; tapping the scrim or an item closes it. Slide uses the panel easing (§5.4).
- Mini-player bar at the bottom, **52 px**, only while something is loaded: tapping it opens the full-screen player;
  its button plays and pauses.

## 5. Visual tokens

### 5.1 Typography

Typefaces stay as they are today (owner decision 2026-11-22): **SF Pro Display** in the Android, Wear OS and desktop
apps; on the web, the body face is **DM Sans** with SF Pro Display for the display styles, falling back to the system
UI font.

Type scale (weight, size / line height in px):

| Style | Spec | Style | Spec |
|---|---|---|---|
| Header | 700 34/40 | Body | 400 13/16 |
| Large title | 400 26/32 (700 emphasised) | Body emphasised / headline | 600 13/16 / 700 13/16 |
| Title 1 | 700 22/26 | Body tall | 400 13/18 |
| Title 2 | 400 17/22 (700 emphasised) | Body reduced | 400 14/20 |
| Title 3 | 400 15/20 (600 emphasised) | Callout | 400 12/15 (500 medium, 600 emphasised) |
| Subhead emphasised | 600 11/14 | Footnote | 400 11/14 |
| Caption 2 | 500 10/13 | | |

Page titles use the header style ("Home" on the home page).

### 5.2 Colours

Monochrome palette: the accent ("key colour") is near-black in light mode and near-white in dark mode. Hex values
with alpha (RRGGBBAA).

| Role | Light | Dark |
|---|---|---|
| Page background | #fbfbfb | #000000 |
| Text primary / secondary / tertiary | #000000e0 / #000000a6 / #0000007a | #ffffffeb / #ffffffa3 / #ffffff66 |
| Fill quaternary / quinary | #0000001a / #0000000d | #ffffff1a / #ffffff0d |
| Key colour / hover / pressed | #111111 / #000000 / #222222 | #f2f2f2 / #ffffff / #d6d6d6 |
| Grays 1–6 | #8e8e8e #aeaeae #c7c7c7 #d1d1d1 #e5e5e5 #f2f2f2 | #989898 #636363 #484848 #3a3a3a #2c2c2c #1c1c1c |
| Divider | #00000026 | #ffffff1a |
| Row hover | #0000000f | #ffffff0f |
| Shelf background (translucent / opaque) | #0000000d / #f2f2f2 | #ffffff0d / #2b2b2b |
| Pill button (text on background) | #ffffff on #000000 | #000000 on #ffffff |
| Segmented control (track / selected) | #7676761f / #ffffff | #7676763d / #636363 |
| Modal scrim | #00000073 | #00000073 |
| Skeleton fill / shimmer | #0000000f / #ffffff99 | #ffffff12 / #ffffff14 |

Text on artwork-tinted areas mixes white (dark tint) or black (light tint) with the tint colour: secondary 64 % / 60 %,
tertiary 40 %. The full-screen player always uses the dark text colours.

### 5.3 Glass material

| | Light | Dark |
|---|---|---|
| Background | #f5f5f58c | #26262699 |
| Inner stroke | black at 5 % | white at 25 % |
| Shadow | #0000001a | #00000033 |
| Backdrop | saturate 220 %, blur 16 px | same |

With "increase contrast": opaque background (#f2f2f2 light, #0e0e0e dark), no blur, stronger secondary text and
dividers. With "reduce transparency": no blur.

### 5.4 Shape and motion

- Radii: artwork 5 / 7 / 10 px (small / medium / large), navigation item 8 px, track row 6 px, pills fully round.
- Panels (drawer, side panel): 0.3 s, easing cubic-bezier(0.215, 0.61, 0.355, 1). Fades: 0.1 s ease-in.
  Disclosure arrows: 0.25 s.
- With "reduce motion": no slides, springs or backdrop animation; state changes are immediate or simple fades.

### 5.5 Layering (bottom to top)

Page content → floating chrome (sidebar, player, bars) → context menus → modal dialogs.

## 5.6 Icons

All icons are Apple **SF Symbols**, the same symbol names on every platform (owner decision 2026-11-17, reaffirmed
2026-11-22). iOS and macOS may use the system's symbols; every other platform uses SVG exports of the owner's SFStudio
library (`rebuild/crates/nuvx-ui/assets/sf-symbols/`, re-exported with `rebuild/tools/export_sf_symbols.py`).
The app mark is licht's nuvx mark from `core/brand`. Glyphs take the
current text colour; their layers keep SF Symbols' 85 % opacity. Android draws them in a bold weight; matching that
weight on PC and web is checked in plan step 11.

| Meaning | SF Symbol |
|---|---|
| Search | magnifyingglass |
| Home | house.fill |
| New / Explore | square.grid.2x2.fill |
| Podcasts | microphone |
| Radio | dot.radiowaves.left.and.right |
| Recently Played | clock.arrow.trianglehead.counterclockwise.rotate.90 |
| Artists | person |
| Albums | square.stack.3d.up.fill |
| Songs, artwork placeholder | music.note |
| Made for You | sparkles |
| All Playlists, Library tab | square.stack.fill |
| Settings | gearshape |
| Downloads | arrow.down.circle |
| Guide | book |
| External link | arrow.up.right |
| Shuffle | shuffle |
| Previous / Next | backward.fill / forward.fill |
| Play / Pause | play.fill / pause.fill |
| Repeat / repeat one | repeat / repeat.1 |
| Volume (muted, then three levels, then full) | speaker.slash.fill, speaker.fill, speaker.wave.1.fill, speaker.wave.2.fill, speaker.wave.3.fill |
| Lyrics (closed / open) | quote.bubble / quote.bubble.fill |
| Up Next | list.bullet |
| Favourite (off / on) | star / star.fill |
| Account (signed out) | person.crop.circle.fill |
| Open full-screen | arrow.up.left.and.arrow.down.right |
| Mini player | pip.enter |
| Menu / close | line.3.horizontal / xmark |
| Window: minimise / maximise / restore / close | minus / square / square.on.square / xmark |

## 6. Keyboard (PC and web shells)

| Keys | Action |
|---|---|
| Space | Play / pause (not while typing, and not when a button, link or slider has focus) |
| `/` or Ctrl/Cmd+F | Go to Search and focus the search field (Ctrl/Cmd+F works even while typing) |
| Escape | Close the full-screen player; otherwise close the side panel (not when a menu or dialog is open) |
| Ctrl/Cmd+→ / ← | Next / previous track |
| Ctrl/Cmd+↑ / ↓ | Volume up / down by 10 % |

Shortcuts with Alt held are ignored.

## 7. Pages reachable from the shell

Home, New, Podcasts, Radio, Search, Album, Artist, Playlist, Browse (a YouTube Music browse page), Library sections
(as §2.2), Spotify and Spotify playlist, Settings (with Discord and Listen Together sub-pages), Listen Together invite
links, Downloads, Guide, the extension page (web only), and a not-found page. Opening the app goes to Home. Each page
shows a loading skeleton shaped like its content until data arrives. Page specs follow in later steps.

## 8. Accessibility

Every icon-only control has a spoken label (the names in this spec: "Shuffle", "Previous", "Play"/"Pause", "Next",
"Repeat"/"Repeat all"/"Repeat one", "Lyrics", "Up Next", "Settings", "Open menu"/"Close menu", "Open full screen
player", "nuvx, Home"). The sidebar is a navigation landmark ("Main navigation"). The current page's item is marked
as current. Focus is visible (key colour at 60 %). Increase contrast, reduce transparency and reduce motion are
honoured as above.

## 9. Acceptance tests for the implementation

1. PC at 1280 × 800: the player bar spans the top, 56 px tall, with the window buttons at its right end; the sidebar
   below it is 260 px wide; the centre display is about 440 px wide (34.4 %). Web at 1280 × 800: the player pill is
   56 px tall, 20 px from the bottom, centred in the content area. At 600 px wide the sidebar is about 203 px; at
   400 px the top bar and drawer appear instead.
2. Clicking each sidebar item navigates to its page and marks only that item current.
3. Each keyboard shortcut in §6 does its action and none fires while typing in a text field (except Ctrl/Cmd+F).
4. Light and dark themes follow the system setting and use the §5.2 values; increase contrast removes the blur.
5. Lyrics / Up Next open the 300 px side panel, the same button or Escape closes it.
8. PC: dragging the bar's empty space moves the window, double-click maximises, the window buttons work; every icon
   is the SF Symbol listed in §5.6.
6. Phone shell: five tabs as in §3.1; the selection pill springs between tabs; reselecting scrolls to top; 50 dp of
   downward scroll collapses the bar, 50 dp upward expands it.
7. Reduce motion turns off the slides, springs and backdrop animation.

## Open points

- Android tablet and landscape layouts were not reviewed.
- iOS app's shell was not reviewed separately; assumed to match the Android phone shell.
- Context menus, dialogs and toasts are specified with the pages that use them.
