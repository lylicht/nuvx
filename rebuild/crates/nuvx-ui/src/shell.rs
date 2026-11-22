//! Shell logic that runs without a window (spec 01): routes, sidebar items, icons, player state,
//! keyboard shortcuts, width and title-bar geometry, theme tokens.

use serde::Deserialize;
use std::time::Duration;

// ---------------------------------------------------------------- routes (§7)

/// Every page reachable from the desktop shell. The extension page is web only and absent here.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Route {
    Home,
    New,
    Podcasts,
    Radio,
    Search,
    Album(String),
    Artist(String),
    Playlist(String),
    Browse(String),
    RecentlyPlayed,
    Artists,
    Albums,
    Songs,
    MadeForYou,
    AllPlaylists,
    Spotify,
    SpotifyPlaylist(String),
    Settings,
    SettingsDiscord,
    SettingsListenTogether,
    ListenTogetherInvite(String),
    Downloads,
    Guide,
    NotFound,
}

impl Route {
    pub fn title(&self) -> &'static str {
        match self {
            Route::Home => "Home",
            Route::New => "New",
            Route::Podcasts => "Podcasts",
            Route::Radio => "Radio",
            Route::Search => "Search",
            Route::Album(_) => "Album",
            Route::Artist(_) => "Artist",
            Route::Playlist(_) => "Playlist",
            Route::Browse(_) => "Browse",
            Route::RecentlyPlayed => "Recently Played",
            Route::Artists => "Artists",
            Route::Albums => "Albums",
            Route::Songs => "Songs",
            Route::MadeForYou => "Made for You",
            Route::AllPlaylists => "All Playlists",
            Route::Spotify => "Spotify",
            Route::SpotifyPlaylist(_) => "Spotify Playlist",
            Route::Settings => "Settings",
            Route::SettingsDiscord => "Discord",
            Route::SettingsListenTogether => "Listen Together",
            Route::ListenTogetherInvite(_) => "Listen Together Invite",
            Route::Downloads => "Downloads",
            Route::Guide => "Guide",
            Route::NotFound => "Page Not Found",
        }
    }

    /// The sidebar item that stays highlighted while a sub-page is open.
    fn nav_parent(&self) -> Option<Route> {
        match self {
            Route::SettingsDiscord | Route::SettingsListenTogether => Some(Route::Settings),
            _ => None,
        }
    }
}

/// Whether the sidebar item for `item` is the current one while `current` is shown.
pub fn is_current(item: &Route, current: &Route) -> bool {
    item == current || current.nav_parent().as_ref() == Some(item)
}

// ---------------------------------------------------------------- icons (§5.6)

/// Every icon meaning in the shell; each maps to one SF Symbol.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Icon {
    Search,
    Home,
    New,
    Podcasts,
    Radio,
    RecentlyPlayed,
    Artists,
    Albums,
    /// Songs, and the artwork placeholder.
    Song,
    MadeForYou,
    Playlists,
    Settings,
    Downloads,
    Guide,
    ExternalLink,
    Shuffle,
    Previous,
    Next,
    Play,
    Pause,
    Repeat,
    RepeatOne,
    VolumeMuted,
    Volume1,
    Volume2,
    Volume3,
    VolumeFull,
    Lyrics,
    LyricsOpen,
    UpNext,
    Favourite,
    FavouriteOn,
    Account,
    OpenFullScreen,
    MiniPlayer,
    Menu,
    Close,
    WindowMinimise,
    WindowMaximise,
    WindowRestore,
}

impl Icon {
    pub const ALL: [Icon; 40] = [
        Icon::Search,
        Icon::Home,
        Icon::New,
        Icon::Podcasts,
        Icon::Radio,
        Icon::RecentlyPlayed,
        Icon::Artists,
        Icon::Albums,
        Icon::Song,
        Icon::MadeForYou,
        Icon::Playlists,
        Icon::Settings,
        Icon::Downloads,
        Icon::Guide,
        Icon::ExternalLink,
        Icon::Shuffle,
        Icon::Previous,
        Icon::Next,
        Icon::Play,
        Icon::Pause,
        Icon::Repeat,
        Icon::RepeatOne,
        Icon::VolumeMuted,
        Icon::Volume1,
        Icon::Volume2,
        Icon::Volume3,
        Icon::VolumeFull,
        Icon::Lyrics,
        Icon::LyricsOpen,
        Icon::UpNext,
        Icon::Favourite,
        Icon::FavouriteOn,
        Icon::Account,
        Icon::OpenFullScreen,
        Icon::MiniPlayer,
        Icon::Menu,
        Icon::Close,
        Icon::WindowMinimise,
        Icon::WindowMaximise,
        Icon::WindowRestore,
    ];

    /// The SF Symbol name from the §5.6 table.
    pub fn symbol(self) -> &'static str {
        match self {
            Icon::Search => "magnifyingglass",
            Icon::Home => "house.fill",
            Icon::New => "square.grid.2x2.fill",
            Icon::Podcasts => "microphone",
            Icon::Radio => "dot.radiowaves.left.and.right",
            Icon::RecentlyPlayed => "clock.arrow.trianglehead.counterclockwise.rotate.90",
            Icon::Artists => "person",
            Icon::Albums => "square.stack.3d.up.fill",
            Icon::Song => "music.note",
            Icon::MadeForYou => "sparkles",
            Icon::Playlists => "square.stack.fill",
            Icon::Settings => "gearshape",
            Icon::Downloads => "arrow.down.circle",
            Icon::Guide => "book",
            Icon::ExternalLink => "arrow.up.right",
            Icon::Shuffle => "shuffle",
            Icon::Previous => "backward.fill",
            Icon::Next => "forward.fill",
            Icon::Play => "play.fill",
            Icon::Pause => "pause.fill",
            Icon::Repeat => "repeat",
            Icon::RepeatOne => "repeat.1",
            Icon::VolumeMuted => "speaker.slash.fill",
            Icon::Volume1 => "speaker.fill",
            Icon::Volume2 => "speaker.wave.1.fill",
            Icon::Volume3 => "speaker.wave.2.fill",
            Icon::VolumeFull => "speaker.wave.3.fill",
            Icon::Lyrics => "quote.bubble",
            Icon::LyricsOpen => "quote.bubble.fill",
            Icon::UpNext => "list.bullet",
            Icon::Favourite => "star",
            Icon::FavouriteOn => "star.fill",
            Icon::Account => "person.crop.circle.fill",
            Icon::OpenFullScreen => "arrow.up.left.and.arrow.down.right",
            Icon::MiniPlayer => "pip.enter",
            Icon::Menu => "line.3.horizontal",
            Icon::Close => "xmark",
            Icon::WindowMinimise => "minus",
            Icon::WindowMaximise => "square",
            Icon::WindowRestore => "square.on.square",
        }
    }

    /// The embedded SVG markup for this icon.
    pub fn svg(self) -> &'static str {
        symbol_svg(self.symbol()).unwrap_or_default()
    }
}

macro_rules! sf_symbols {
    ($($name:literal),* $(,)?) => {
        /// The SF Symbol SVGs from `assets/sf-symbols`, embedded in the binary (name, markup).
        pub const SF_SYMBOLS: &[(&str, &str)] = &[
            $(($name, include_str!(concat!("../assets/sf-symbols/", $name, ".svg")))),*
        ];
    };
}

sf_symbols!(
    "arrow.down.circle",
    "arrow.up.left.and.arrow.down.right",
    "arrow.up.right",
    "backward.fill",
    "book",
    "clock.arrow.trianglehead.counterclockwise.rotate.90",
    "dot.radiowaves.left.and.right",
    "forward.fill",
    "gearshape",
    "house.fill",
    "line.3.horizontal",
    "list.bullet",
    "magnifyingglass",
    "microphone",
    "minus",
    "music.note",
    "pause.fill",
    "person.crop.circle.fill",
    "person",
    "pip.enter",
    "play.fill",
    "quote.bubble.fill",
    "quote.bubble",
    "repeat.1",
    "repeat",
    "shuffle",
    "sparkles",
    "speaker.fill",
    "speaker.slash.fill",
    "speaker.wave.1.fill",
    "speaker.wave.2.fill",
    "speaker.wave.3.fill",
    "square",
    "square.grid.2x2.fill",
    "square.on.square",
    "square.stack.3d.up.fill",
    "square.stack.fill",
    "star.fill",
    "star",
    "xmark",
);

pub fn symbol_svg(name: &str) -> Option<&'static str> {
    SF_SYMBOLS.iter().find(|(n, _)| *n == name).map(|(_, s)| *s)
}

// ---------------------------------------------------------------- sidebar (§2.2)

#[derive(Clone, Debug, PartialEq)]
pub struct Playlist {
    pub id: String,
    pub title: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NavItem {
    pub label: String,
    pub route: Route,
    pub icon: Icon,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NavSection {
    pub header: Option<&'static str>,
    pub items: Vec<NavItem>,
}

fn item(label: &str, route: Route, icon: Icon) -> NavItem {
    NavItem {
        label: label.to_owned(),
        route,
        icon,
    }
}

/// The scrolling part of the sidebar. `spotify` is `None` while Spotify is not connected.
/// The bottom block (Guide, Downloads, Settings, account) is fixed and lives in the component;
/// its web-only items ("Back to nuvx.lol", "nuvx Extension") are never shown on desktop.
pub fn sidebar_sections(playlists: &[Playlist], spotify: Option<&[Playlist]>) -> Vec<NavSection> {
    let mut sections = vec![
        NavSection {
            header: None,
            items: vec![
                item("Search", Route::Search, Icon::Search),
                item("Home", Route::Home, Icon::Home),
                item("New", Route::New, Icon::New),
                item("Podcasts", Route::Podcasts, Icon::Podcasts),
                item("Radio", Route::Radio, Icon::Radio),
            ],
        },
        NavSection {
            header: Some("Library"),
            items: vec![
                item(
                    "Recently Played",
                    Route::RecentlyPlayed,
                    Icon::RecentlyPlayed,
                ),
                item("Artists", Route::Artists, Icon::Artists),
                item("Albums", Route::Albums, Icon::Albums),
                item("Songs", Route::Songs, Icon::Song),
                item("Made for You", Route::MadeForYou, Icon::MadeForYou),
            ],
        },
        NavSection {
            header: Some("Playlists"),
            items: std::iter::once(item("All Playlists", Route::AllPlaylists, Icon::Playlists))
                .chain(
                    playlists
                        .iter()
                        .map(|p| item(&p.title, Route::Playlist(p.id.clone()), Icon::Playlists)),
                )
                .collect(),
        },
    ];
    if let Some(spotify) = spotify {
        sections.push(NavSection {
            header: Some("Spotify"),
            items: spotify
                .iter()
                .map(|p| {
                    item(
                        &p.title,
                        Route::SpotifyPlaylist(p.id.clone()),
                        Icon::Playlists,
                    )
                })
                .collect(),
        });
    }
    sections
}

// ---------------------------------------------------------------- player (§2.3–2.5)

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Repeat {
    #[default]
    Off,
    All,
    One,
}

impl Repeat {
    pub fn next(self) -> Self {
        match self {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Repeat::Off => "Repeat",
            Repeat::All => "Repeat all",
            Repeat::One => "Repeat one",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Panel {
    Lyrics,
    UpNext,
}

impl Panel {
    pub fn title(self) -> &'static str {
        match self {
            Panel::Lyrics => "Lyrics",
            Panel::UpNext => "Up Next",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    TogglePlay,
    Next,
    Previous,
    /// One-shot: shuffles the upcoming queue.
    ShuffleUpNext,
    VolumeUp,
    VolumeDown,
    ToggleMute,
    FocusSearch,
    Escape,
    CycleRepeat,
    TogglePanel(Panel),
    OpenFullScreen,
    ToggleFavourite,
    ToggleMiniPlayer,
}

impl Action {
    /// Transport controls do nothing while nothing is loaded (§2.3, states).
    pub fn is_transport(self) -> bool {
        matches!(
            self,
            Action::TogglePlay | Action::Next | Action::Previous | Action::ShuffleUpNext
        )
    }
}

/// Local player chrome state. No audio yet.
#[derive(Clone, Debug, PartialEq)]
pub struct Player {
    /// Something is loaded. Always false until playback lands (plan step 6).
    pub loaded: bool,
    pub playing: bool,
    pub repeat: Repeat,
    /// Percent, 0–100.
    pub volume: u8,
    pub muted: bool,
    pub favourite: bool,
    /// Mini player window open. shortcut: only a pressed state, no window yet (plan step 11).
    pub mini_player: bool,
    pub panel: Option<Panel>,
    pub full_screen: bool,
}

impl Default for Player {
    fn default() -> Self {
        Player {
            loaded: false,
            playing: false,
            repeat: Repeat::Off,
            volume: 80,
            muted: false,
            favourite: false,
            mini_player: false,
            panel: None,
            full_screen: false,
        }
    }
}

pub const VOLUME_STEP: u8 = 10;

impl Player {
    /// Applies a player action. `FocusSearch` is navigation and is handled by the caller.
    pub fn apply(&mut self, action: Action) {
        if action.is_transport() && !self.loaded {
            return;
        }
        match action {
            Action::TogglePlay => self.playing = !self.playing,
            // shortcut: no queue yet, so these do nothing until playback lands (plan step 6)
            Action::Next | Action::Previous | Action::ShuffleUpNext | Action::FocusSearch => {}
            Action::VolumeUp => {
                self.volume = self.volume.saturating_add(VOLUME_STEP).min(100);
                self.muted = false;
            }
            Action::VolumeDown => self.volume = self.volume.saturating_sub(VOLUME_STEP),
            Action::ToggleMute => self.muted = !self.muted,
            Action::Escape => {
                if self.full_screen {
                    self.full_screen = false;
                } else {
                    self.panel = None;
                }
            }
            Action::CycleRepeat => self.repeat = self.repeat.next(),
            Action::TogglePanel(p) => {
                self.panel = if self.panel == Some(p) { None } else { Some(p) }
            }
            Action::OpenFullScreen => self.full_screen = true,
            Action::ToggleFavourite => self.favourite = !self.favourite,
            Action::ToggleMiniPlayer => self.mini_player = !self.mini_player,
        }
    }

    /// Volume glyph: muted (or 0), then four level steps.
    pub fn volume_icon(&self) -> Icon {
        match (self.muted, self.volume) {
            (true, _) | (_, 0) => Icon::VolumeMuted,
            (_, 1..=25) => Icon::Volume1,
            (_, 26..=50) => Icon::Volume2,
            (_, 51..=75) => Icon::Volume3,
            _ => Icon::VolumeFull,
        }
    }
}

/// Seek-line time label, `m:ss` (or `h:mm:ss`).
pub fn format_time(seconds: u64) -> String {
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

// ---------------------------------------------------------------- keyboard (§6)

/// One key press as reported by the webview listener.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct KeyInput {
    /// `KeyboardEvent.key`.
    pub key: String,
    /// Ctrl or Cmd held.
    pub modifier: bool,
    pub alt: bool,
    /// Focus is in a text field.
    pub typing: bool,
    /// Focus is on a button, link or slider.
    pub control: bool,
}

pub fn shortcut(k: &KeyInput) -> Option<Action> {
    if k.alt {
        return None;
    }
    if k.modifier && k.key.eq_ignore_ascii_case("f") {
        return Some(Action::FocusSearch);
    }
    if k.typing {
        return None;
    }
    match (k.modifier, k.key.as_str()) {
        (false, " ") if !k.control => Some(Action::TogglePlay),
        (false, "/") => Some(Action::FocusSearch),
        (false, "Escape") => Some(Action::Escape),
        (true, "ArrowRight") => Some(Action::Next),
        (true, "ArrowLeft") => Some(Action::Previous),
        (true, "ArrowUp") => Some(Action::VolumeUp),
        (true, "ArrowDown") => Some(Action::VolumeDown),
        _ => None,
    }
}

/// Webview side of the keyboard: reports key presses to Rust. It also stops the webview's own
/// handling (find bar, page scroll) for the presses `shortcut` acts on, so that test must mirror it.
pub const KEY_LISTENER_JS: &str = r#"
document.addEventListener('keydown', (e) => {
  if (e.altKey) return;
  const t = e.target || {};
  const tag = t.tagName || '';
  const type = (t.type || '').toLowerCase();
  const typing = (tag === 'INPUT' && !['range', 'button', 'checkbox', 'radio', 'submit', 'reset'].includes(type))
    || tag === 'TEXTAREA' || !!t.isContentEditable;
  const control = ['BUTTON', 'A', 'SELECT'].includes(tag) || type === 'range'
    || (t.getAttribute && t.getAttribute('role') === 'slider');
  const modifier = e.ctrlKey || e.metaKey;
  const k = e.key;
  if ((modifier && (k === 'f' || k === 'F'))
    || (!typing && ((modifier && k.startsWith('Arrow')) || (!modifier && (k === '/' || (k === ' ' && !control)))))) {
    e.preventDefault();
  }
  dioxus.send({ key: k, modifier, alt: false, typing, control });
});
"#;

// ---------------------------------------------------------------- widths (§2.1, §4)

pub const SIDEBAR_WIDTH: f32 = 260.0;
/// Below this width the top bar and drawer replace the sidebar.
pub const NARROW_BELOW: f32 = 484.0;
/// Between `NARROW_BELOW` and this width the sidebar is a share of the window.
pub const MEDIUM_BELOW: f32 = 768.0;
pub const SIDEBAR_MEDIUM_SHARE: f32 = 0.34;
pub const GUTTER: f32 = 25.0;
pub const GUTTER_WIDE: f32 = 40.0;
pub const GUTTER_WIDE_FROM: f32 = 1000.0;
pub const PANEL_WIDTH: f32 = 300.0;
pub const TOP_BAR_HEIGHT: f32 = 52.0;
pub const DRAWER_MAX: f32 = 300.0;
pub const DRAWER_SHARE: f32 = 0.82;

pub fn is_narrow(window_width: f32) -> bool {
    window_width < NARROW_BELOW
}

/// Sidebar width, or `None` when the drawer replaces it.
pub fn sidebar_width(window_width: f32) -> Option<f32> {
    if is_narrow(window_width) {
        None
    } else if window_width < MEDIUM_BELOW {
        Some(window_width * SIDEBAR_MEDIUM_SHARE)
    } else {
        Some(SIDEBAR_WIDTH)
    }
}

pub fn gutter(window_width: f32) -> f32 {
    if window_width >= GUTTER_WIDE_FROM {
        GUTTER_WIDE
    } else {
        GUTTER
    }
}

pub fn drawer_width(window_width: f32) -> f32 {
    (window_width * DRAWER_SHARE).min(DRAWER_MAX)
}

/// Size variables and the width breakpoints, built from the constants above.
pub fn layout_css() -> String {
    let narrow_max = NARROW_BELOW - 0.02;
    let medium_max = MEDIUM_BELOW - 0.02;
    let share = SIDEBAR_MEDIUM_SHARE * 100.0;
    let drawer_share = DRAWER_SHARE * 100.0;
    format!(
        ":root{{--sidebar-w:{SIDEBAR_WIDTH}px;--gutter:{GUTTER}px;--panel-w:{PANEL_WIDTH}px;\
--topbar-h:{TOP_BAR_HEIGHT}px;--drawer-w:min({DRAWER_MAX}px,{drawer_share}vw);}}\n\
@media (min-width:{GUTTER_WIDE_FROM}px){{:root{{--gutter:{GUTTER_WIDE}px;}}}}\n\
@media (min-width:{NARROW_BELOW}px) and (max-width:{medium_max}px){{:root{{--sidebar-w:{share}vw;}}}}\n\
@media (max-width:{narrow_max}px){{\
.shell{{flex-direction:column;}}\
.topbar{{display:flex;}}\
.sidebar{{position:fixed;top:var(--bar-h);left:0;bottom:0;width:var(--drawer-w);transform:translateX(-105%);\
transition:transform var(--dur-panel) var(--ease-panel);box-shadow:0 0 32px var(--c-scrim);z-index:30;}}\
.drawer-open .sidebar{{transform:none;}}\
.drawer-open .scrim{{display:block;}}\
.side-panel{{position:fixed;inset:var(--bar-h) 0 0 0;width:auto;z-index:25;}}\
.hide-narrow{{display:none !important;}}\
.left-group{{left:var(--bar-edge) !important;}}\
}}\n"
    )
}

// ---------------------------------------------------------------- PC player bar (§2.3)

pub const BAR_HEIGHT: f32 = 56.0;
pub const WINDOW_BUTTON_WIDTH: f32 = 46.0;
pub const WINDOW_BUTTON_COUNT: f32 = 3.0;
/// Left space kept free for the macOS traffic lights (not in the spec; this tree's choice).
pub const MAC_TRAFFIC_LIGHTS: f32 = 78.0;
pub const BAR_MIN_INSET: f32 = 22.0;
pub const LEFT_STEP: f32 = 31.5;
pub const HIT_TARGET: f32 = 30.0;
pub const LEFT_COUNT: usize = 5;
pub const RIGHT_STEP: f32 = 35.0;
pub const AVATAR: f32 = 32.0;
pub const AVATAR_RIGHT_GAP: f32 = 16.0;
/// Percent of the window width.
pub const DISPLAY_SHARE_PCT: f32 = 34.4;
pub const DISPLAY_MAX: f32 = 720.0;
pub const DISPLAY_MIN: f32 = 160.0;
/// Percent of the window width left of centre.
pub const DISPLAY_SHIFT_PCT: f32 = 1.1;

/// Where the bar's own contents may start and how much the window buttons take at the right.
pub fn bar_frame(mac: bool) -> (f32, f32) {
    if mac {
        (MAC_TRAFFIC_LIGHTS, 0.0)
    } else {
        (BAR_MIN_INSET, WINDOW_BUTTON_WIDTH * WINDOW_BUTTON_COUNT)
    }
}

/// Centre display `(left, width)`, or `None` when it would be narrower than 160 px.
pub fn centre_display(window_width: f32) -> Option<(f32, f32)> {
    let width = (window_width * DISPLAY_SHARE_PCT / 100.0).min(DISPLAY_MAX);
    (width >= DISPLAY_MIN).then(|| (display_centre(window_width) - width / 2.0, width))
}

fn display_centre(window_width: f32) -> f32 {
    window_width / 2.0 - window_width * DISPLAY_SHIFT_PCT / 100.0
}

/// Centres of Shuffle up next, Previous, Play/Pause, Next, Repeat: the group sits midway between
/// the left edge and the centre display, never closer than the edge inset.
pub fn left_group_centres(window_width: f32, mac: bool) -> [f32; LEFT_COUNT] {
    let (edge, _) = bar_frame(mac);
    let anchor = centre_display(window_width).map_or(display_centre(window_width), |(l, _)| l);
    let span = LEFT_STEP * (LEFT_COUNT - 1) as f32 + HIT_TARGET;
    let left = ((edge + anchor) / 2.0 - span / 2.0).max(edge);
    std::array::from_fn(|i| left + HIT_TARGET / 2.0 + LEFT_STEP * i as f32)
}

/// Centres of Volume, Lyrics, Up next and the account avatar.
pub fn right_group_centres(window_width: f32, mac: bool) -> [f32; 4] {
    let (_, buttons) = bar_frame(mac);
    let avatar = window_width - buttons - AVATAR_RIGHT_GAP - AVATAR / 2.0;
    std::array::from_fn(|i| avatar - RIGHT_STEP * (3 - i) as f32)
}

/// Bar geometry as CSS, the same rules as the functions above in viewport units.
pub fn bar_css(mac: bool) -> String {
    let (edge, buttons) = bar_frame(mac);
    let (share, shift) = (DISPLAY_SHARE_PCT, DISPLAY_SHIFT_PCT);
    let half_span = (LEFT_STEP * (LEFT_COUNT - 1) as f32 + HIT_TARGET) / 2.0;
    let left_gap = LEFT_STEP - HIT_TARGET;
    let right_gap = RIGHT_STEP - HIT_TARGET;
    let avatar_gap = RIGHT_STEP - HIT_TARGET / 2.0 - AVATAR / 2.0;
    // the display hides below this window width (160 px / 34.4 %)
    let hide_below = DISPLAY_MIN * 100.0 / DISPLAY_SHARE_PCT;
    let width = format!("min({share}vw,{DISPLAY_MAX}px)");
    format!(
        ":root{{--bar-h:{BAR_HEIGHT}px;--bar-edge:{edge}px;--bar-buttons:{buttons}px;\
--display-w:{width};--display-left:calc(50vw - {shift}vw - {width} / 2);}}\n\
.window-btn{{width:{WINDOW_BUTTON_WIDTH}px;}}\n\
.bar-btn{{width:{HIT_TARGET}px;height:{HIT_TARGET}px;}}\n\
.left-group{{gap:{left_gap}px;left:max(var(--bar-edge),calc((var(--bar-edge) + var(--display-left)) / 2 - {half_span}px));}}\n\
.right-group{{gap:{right_gap}px;right:calc(var(--bar-buttons) + {AVATAR_RIGHT_GAP}px);}}\n\
.avatar{{width:{AVATAR}px;height:{AVATAR}px;margin-left:{}px;}}\n\
.centre{{left:var(--display-left);width:var(--display-w);}}\n\
@media (max-width:{hide_below}px){{.centre{{display:none;}}:root{{--display-left:calc(50vw - {shift}vw);}}}}\n",
        avatar_gap - right_gap
    )
}

/// Two presses on empty bar space within this time maximise or restore the window.
pub const DOUBLE_CLICK: Duration = Duration::from_millis(500);

/// `gap` is the time since the previous press on empty bar space, if any.
pub fn is_double_click(gap: Option<Duration>) -> bool {
    gap.is_some_and(|g| g <= DOUBLE_CLICK)
}

// ---------------------------------------------------------------- theme tokens (§5)

/// A colour that differs between the light and dark themes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Token {
    pub name: &'static str,
    pub light: &'static str,
    pub dark: &'static str,
}

const fn t(name: &'static str, light: &'static str, dark: &'static str) -> Token {
    Token { name, light, dark }
}

/// §5.2 colours, §5.3 glass and the §2.3 bar look, exactly as specified.
pub const COLOR_TOKENS: &[Token] = &[
    t("bg", "#fbfbfb", "#000000"),
    t("text-1", "#000000e0", "#ffffffeb"),
    t("text-2", "#000000a6", "#ffffffa3"),
    t("text-3", "#0000007a", "#ffffff66"),
    t("fill-4", "#0000001a", "#ffffff1a"),
    t("fill-5", "#0000000d", "#ffffff0d"),
    t("key", "#111111", "#f2f2f2"),
    t("key-hover", "#000000", "#ffffff"),
    t("key-pressed", "#222222", "#d6d6d6"),
    t("gray-1", "#8e8e8e", "#989898"),
    t("gray-2", "#aeaeae", "#636363"),
    t("gray-3", "#c7c7c7", "#484848"),
    t("gray-4", "#d1d1d1", "#3a3a3a"),
    t("gray-5", "#e5e5e5", "#2c2c2c"),
    t("gray-6", "#f2f2f2", "#1c1c1c"),
    t("divider", "#00000026", "#ffffff1a"),
    t("row-hover", "#0000000f", "#ffffff0f"),
    t("shelf", "#0000000d", "#ffffff0d"),
    t("shelf-opaque", "#f2f2f2", "#2b2b2b"),
    t("pill-fg", "#ffffff", "#000000"),
    t("pill-bg", "#000000", "#ffffff"),
    t("seg-track", "#7676761f", "#7676763d"),
    t("seg-selected", "#ffffff", "#636363"),
    t("scrim", "#00000073", "#00000073"),
    t("skeleton", "#0000000f", "#ffffff12"),
    t("shimmer", "#ffffff99", "#ffffff14"),
    t("glass-bg", "#f5f5f58c", "#26262699"),
    // inner stroke: black at 5 % / white at 25 %
    t("glass-stroke", "#0000000d", "#ffffff40"),
    t("glass-shadow", "#0000001a", "#00000033"),
    // bar: solid surface in light (this tree uses Gray 6), transparent in dark
    t("bar-bg", "#f2f2f2", "transparent"),
    // bar hairline: divider in light, white at 8 % in dark
    t("bar-line", "#00000026", "#ffffff14"),
];

/// "Increase contrast" overrides (§5.3). Glass values are from the spec; the stronger secondary
/// text and divider values are this implementation's choice (the spec gives no numbers).
pub const CONTRAST_TOKENS: &[Token] = &[
    t("glass-bg", "#f2f2f2", "#0e0e0e"),
    t("text-2", "#000000cc", "#ffffffcc"),
    t("divider", "#00000045", "#ffffff40"),
];

/// Theme-independent tokens: typeface, type scale (§5.1), radii and motion (§5.4).
pub const STATIC_TOKENS: &[(&str, &str)] = &[
    (
        "font",
        "\"SF Pro Display\", system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif",
    ),
    ("type-header", "700 34px/40px"),
    ("type-large-title", "400 26px/32px"),
    ("type-large-title-em", "700 26px/32px"),
    ("type-title-1", "700 22px/26px"),
    ("type-title-2", "400 17px/22px"),
    ("type-title-2-em", "700 17px/22px"),
    ("type-title-3", "400 15px/20px"),
    ("type-title-3-em", "600 15px/20px"),
    ("type-subhead-em", "600 11px/14px"),
    ("type-caption-2", "500 10px/13px"),
    ("type-body", "400 13px/16px"),
    ("type-body-em", "600 13px/16px"),
    ("type-headline", "700 13px/16px"),
    ("type-body-tall", "400 13px/18px"),
    ("type-body-reduced", "400 14px/20px"),
    ("type-callout", "400 12px/15px"),
    ("type-callout-medium", "500 12px/15px"),
    ("type-callout-em", "600 12px/15px"),
    ("type-footnote", "400 11px/14px"),
    // §2.3 bar text; line heights are this tree's choice
    ("type-bar-title", "600 12.5px/16px"),
    ("type-bar-artist", "400 11px/14px"),
    ("type-bar-time", "600 11px/14px"),
    ("radius-art-s", "5px"),
    ("radius-art-m", "7px"),
    ("radius-art-l", "10px"),
    ("radius-nav", "8px"),
    ("radius-row", "6px"),
    ("radius-pill", "999px"),
    ("dur-panel", "0.3s"),
    ("ease-panel", "cubic-bezier(0.215, 0.61, 0.355, 1)"),
    ("dur-fade", "0.1s"),
    ("dur-disclosure", "0.25s"),
    ("dur-hover", "0.12s"),
    ("dur-morph", "0.18s"),
    ("disabled-opacity", "0.45"),
];

fn vars(tokens: &[Token], dark: bool) -> String {
    tokens
        .iter()
        .map(|t| format!("--c-{}:{};", t.name, if dark { t.dark } else { t.light }))
        .collect()
}

/// Custom properties for both themes. Dark follows `prefers-color-scheme`; `.force-dark`
/// pins the dark palette (full-screen player, §2.5).
pub fn theme_css() -> String {
    let statics: String = STATIC_TOKENS
        .iter()
        .map(|(n, v)| format!("--{n}:{v};"))
        .collect();
    let (light, dark) = (vars(COLOR_TOKENS, false), vars(COLOR_TOKENS, true));
    let (hc_light, hc_dark) = (vars(CONTRAST_TOKENS, false), vars(CONTRAST_TOKENS, true));
    format!(
        ":root{{color-scheme:light;{statics}{light}}}\n\
@media (prefers-color-scheme:dark){{:root{{color-scheme:dark;{dark}}}}}\n\
@media (prefers-contrast:more){{:root{{{hc_light}}}}}\n\
@media (prefers-contrast:more) and (prefers-color-scheme:dark){{:root{{{hc_dark}}}}}\n\
.force-dark{{color-scheme:dark;{dark}}}\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn labels(sections: &[NavSection]) -> Vec<&str> {
        sections
            .iter()
            .flat_map(|s| s.items.iter().map(|i| i.label.as_str()))
            .collect()
    }

    #[test]
    fn sidebar_items_in_spec_order_without_spotify() {
        let s = sidebar_sections(&[], None);
        let headers: Vec<_> = s.iter().map(|s| s.header).collect();
        assert_eq!(headers, [None, Some("Library"), Some("Playlists")]);
        assert_eq!(
            labels(&s),
            [
                "Search",
                "Home",
                "New",
                "Podcasts",
                "Radio",
                "Recently Played",
                "Artists",
                "Albums",
                "Songs",
                "Made for You",
                "All Playlists"
            ]
        );
        for web_only in ["Back to nuvx.lol", "nuvx Extension"] {
            assert!(!labels(&s).contains(&web_only));
        }
    }

    #[test]
    fn user_and_spotify_playlists_listed_by_title() {
        let mine = [Playlist {
            id: "p1".into(),
            title: "Road trip".into(),
        }];
        let spotify = [Playlist {
            id: "s1".into(),
            title: "Imported".into(),
        }];
        let s = sidebar_sections(&mine, Some(&spotify));
        assert_eq!(s[2].items[1].label, "Road trip");
        assert_eq!(s[2].items[1].route, Route::Playlist("p1".into()));
        assert_eq!(s[3].header, Some("Spotify"));
        assert_eq!(s[3].items[0].route, Route::SpotifyPlaylist("s1".into()));
    }

    #[test]
    fn only_the_open_page_is_current() {
        let s = sidebar_sections(&[], None);
        let all: Vec<&NavItem> = s.iter().flat_map(|s| &s.items).collect();
        for open in &all {
            let current: Vec<_> = all
                .iter()
                .filter(|i| is_current(&i.route, &open.route))
                .collect();
            assert_eq!(current.len(), 1, "{}", open.label);
            assert_eq!(current[0].label, open.label);
        }
        assert!(is_current(&Route::Settings, &Route::SettingsDiscord));
        assert!(!is_current(&Route::Home, &Route::Settings));
    }

    #[test]
    fn every_route_has_a_title() {
        assert_eq!(Route::Home.title(), "Home");
        assert_eq!(Route::MadeForYou.title(), "Made for You");
        assert_eq!(Route::Album("x".into()).title(), "Album");
        assert_eq!(Route::NotFound.title(), "Page Not Found");
    }

    /// The §5.6 table, meaning by meaning.
    #[test]
    fn every_spec_icon_meaning_maps_to_its_sf_symbol_file() {
        let table = [
            (Icon::Search, "magnifyingglass"),
            (Icon::Home, "house.fill"),
            (Icon::New, "square.grid.2x2.fill"),
            (Icon::Podcasts, "microphone"),
            (Icon::Radio, "dot.radiowaves.left.and.right"),
            (
                Icon::RecentlyPlayed,
                "clock.arrow.trianglehead.counterclockwise.rotate.90",
            ),
            (Icon::Artists, "person"),
            (Icon::Albums, "square.stack.3d.up.fill"),
            (Icon::Song, "music.note"),
            (Icon::MadeForYou, "sparkles"),
            (Icon::Playlists, "square.stack.fill"),
            (Icon::Settings, "gearshape"),
            (Icon::Downloads, "arrow.down.circle"),
            (Icon::Guide, "book"),
            (Icon::ExternalLink, "arrow.up.right"),
            (Icon::Shuffle, "shuffle"),
            (Icon::Previous, "backward.fill"),
            (Icon::Next, "forward.fill"),
            (Icon::Play, "play.fill"),
            (Icon::Pause, "pause.fill"),
            (Icon::Repeat, "repeat"),
            (Icon::RepeatOne, "repeat.1"),
            (Icon::VolumeMuted, "speaker.slash.fill"),
            (Icon::Volume1, "speaker.fill"),
            (Icon::Volume2, "speaker.wave.1.fill"),
            (Icon::Volume3, "speaker.wave.2.fill"),
            (Icon::VolumeFull, "speaker.wave.3.fill"),
            (Icon::Lyrics, "quote.bubble"),
            (Icon::LyricsOpen, "quote.bubble.fill"),
            (Icon::UpNext, "list.bullet"),
            (Icon::Favourite, "star"),
            (Icon::FavouriteOn, "star.fill"),
            (Icon::Account, "person.crop.circle.fill"),
            (Icon::OpenFullScreen, "arrow.up.left.and.arrow.down.right"),
            (Icon::MiniPlayer, "pip.enter"),
            (Icon::Menu, "line.3.horizontal"),
            (Icon::Close, "xmark"),
            (Icon::WindowMinimise, "minus"),
            (Icon::WindowMaximise, "square"),
            (Icon::WindowRestore, "square.on.square"),
        ];
        assert_eq!(table.len(), Icon::ALL.len());
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("assets/sf-symbols");
        for (icon, name) in table {
            assert_eq!(icon.symbol(), name);
            assert!(dir.join(format!("{name}.svg")).is_file(), "{name}.svg");
            let svg = icon.svg();
            assert!(
                svg.starts_with("<svg") && svg.contains("currentColor"),
                "{name}"
            );
        }
        for icon in Icon::ALL {
            assert!(symbol_svg(icon.symbol()).is_some(), "{icon:?}");
        }
    }

    #[test]
    fn every_asset_file_is_embedded() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("assets/sf-symbols");
        let files: Vec<String> = std::fs::read_dir(dir)
            .unwrap()
            .filter_map(|e| {
                let name = e.unwrap().file_name().into_string().unwrap();
                name.strip_suffix(".svg").map(str::to_owned)
            })
            .collect();
        assert_eq!(files.len(), Icon::ALL.len());
        assert_eq!(SF_SYMBOLS.len(), files.len());
        for f in files {
            assert!(symbol_svg(&f).is_some(), "{f}");
        }
    }

    #[test]
    fn repeat_cycles_off_all_one_with_labels() {
        let mut p = Player::default();
        let mut seen = vec![p.repeat.label()];
        for _ in 0..3 {
            p.apply(Action::CycleRepeat);
            seen.push(p.repeat.label());
        }
        assert_eq!(seen, ["Repeat", "Repeat all", "Repeat one", "Repeat"]);
    }

    #[test]
    fn transport_does_nothing_until_loaded() {
        let mut p = Player::default();
        p.apply(Action::TogglePlay);
        assert!(!p.playing);
        p.loaded = true;
        p.apply(Action::TogglePlay);
        assert!(p.playing);
        p.apply(Action::TogglePlay);
        assert!(!p.playing);
        let before = p.clone();
        p.apply(Action::ShuffleUpNext);
        assert_eq!(p, before, "shuffle up next is one-shot, not a toggle");
    }

    #[test]
    fn volume_mute_and_level_glyphs() {
        let mut p = Player::default();
        for _ in 0..5 {
            p.apply(Action::VolumeUp);
        }
        assert_eq!(p.volume, 100);
        assert_eq!(p.volume_icon(), Icon::VolumeFull);
        p.apply(Action::VolumeDown);
        assert_eq!(p.volume, 90);
        p.apply(Action::ToggleMute);
        assert_eq!(p.volume_icon(), Icon::VolumeMuted);
        p.apply(Action::ToggleMute);
        for (v, icon) in [
            (0, Icon::VolumeMuted),
            (10, Icon::Volume1),
            (40, Icon::Volume2),
            (70, Icon::Volume3),
            (80, Icon::VolumeFull),
        ] {
            p.volume = v;
            assert_eq!(p.volume_icon(), icon, "{v}");
        }
        p.muted = true;
        p.apply(Action::VolumeUp);
        assert!(!p.muted, "raising the volume unmutes");
        for _ in 0..20 {
            p.apply(Action::VolumeDown);
        }
        assert_eq!(p.volume, 0);
    }

    #[test]
    fn favourite_and_mini_player_toggle() {
        let mut p = Player::default();
        p.apply(Action::ToggleFavourite);
        p.apply(Action::ToggleMiniPlayer);
        assert!(p.favourite && p.mini_player);
        p.apply(Action::ToggleFavourite);
        p.apply(Action::ToggleMiniPlayer);
        assert!(!p.favourite && !p.mini_player);
    }

    #[test]
    fn panel_toggles_and_escape_closes_full_screen_first() {
        let mut p = Player::default();
        p.apply(Action::TogglePanel(Panel::Lyrics));
        assert_eq!(p.panel, Some(Panel::Lyrics));
        p.apply(Action::TogglePanel(Panel::UpNext));
        assert_eq!(p.panel, Some(Panel::UpNext));
        p.apply(Action::TogglePanel(Panel::UpNext));
        assert_eq!(p.panel, None);

        p.apply(Action::TogglePanel(Panel::Lyrics));
        p.apply(Action::OpenFullScreen);
        p.apply(Action::Escape);
        assert!(!p.full_screen);
        assert_eq!(p.panel, Some(Panel::Lyrics));
        p.apply(Action::Escape);
        assert_eq!(p.panel, None);
    }

    #[test]
    fn seek_times() {
        assert_eq!(format_time(0), "0:00");
        assert_eq!(format_time(65), "1:05");
        assert_eq!(format_time(3725), "1:02:05");
    }

    fn key(key: &str, modifier: bool) -> KeyInput {
        KeyInput {
            key: key.into(),
            modifier,
            ..Default::default()
        }
    }

    #[test]
    fn shortcuts_map_to_actions() {
        assert_eq!(shortcut(&key(" ", false)), Some(Action::TogglePlay));
        assert_eq!(shortcut(&key("/", false)), Some(Action::FocusSearch));
        assert_eq!(shortcut(&key("f", true)), Some(Action::FocusSearch));
        assert_eq!(shortcut(&key("F", true)), Some(Action::FocusSearch));
        assert_eq!(shortcut(&key("Escape", false)), Some(Action::Escape));
        assert_eq!(shortcut(&key("ArrowRight", true)), Some(Action::Next));
        assert_eq!(shortcut(&key("ArrowLeft", true)), Some(Action::Previous));
        assert_eq!(shortcut(&key("ArrowUp", true)), Some(Action::VolumeUp));
        assert_eq!(shortcut(&key("ArrowDown", true)), Some(Action::VolumeDown));
        assert_eq!(shortcut(&key("ArrowRight", false)), None);
        assert_eq!(shortcut(&key("f", false)), None);
    }

    #[test]
    fn only_ctrl_f_fires_while_typing() {
        for (k, m) in [
            (" ", false),
            ("/", false),
            ("Escape", false),
            ("ArrowRight", true),
            ("ArrowLeft", true),
            ("ArrowUp", true),
            ("ArrowDown", true),
        ] {
            let typing = KeyInput {
                typing: true,
                ..key(k, m)
            };
            assert_eq!(shortcut(&typing), None, "{k}");
        }
        let typing = KeyInput {
            typing: true,
            ..key("f", true)
        };
        assert_eq!(shortcut(&typing), Some(Action::FocusSearch));
    }

    #[test]
    fn space_ignored_on_controls_and_alt_ignored_everywhere() {
        let on_button = KeyInput {
            control: true,
            ..key(" ", false)
        };
        assert_eq!(shortcut(&on_button), None);
        let alt = KeyInput {
            alt: true,
            ..key("f", true)
        };
        assert_eq!(shortcut(&alt), None);
    }

    #[test]
    fn width_rules() {
        assert_eq!(sidebar_width(1280.0), Some(260.0));
        assert_eq!(sidebar_width(768.0), Some(260.0));
        let at_600 = sidebar_width(600.0).unwrap();
        assert!((at_600 - 203.0).abs() < 2.0, "{at_600}");
        assert_eq!(sidebar_width(484.0).map(|w| w.round()), Some(165.0));
        assert!(is_narrow(400.0) && is_narrow(483.0) && !is_narrow(484.0));
        assert_eq!(sidebar_width(400.0), None);
        assert_eq!(gutter(999.0), 25.0);
        assert_eq!(gutter(1000.0), 40.0);
        assert_eq!(drawer_width(400.0), 300.0);
        assert!((drawer_width(320.0) - 262.4).abs() < 0.01);
    }

    #[test]
    fn layout_css_uses_the_spec_sizes() {
        let css = layout_css();
        for needle in [
            "--sidebar-w:260px",
            "--panel-w:300px",
            "--topbar-h:52px",
            "--drawer-w:min(300px,82vw)",
            "@media (max-width:483.98px)",
            "--sidebar-w:34vw",
        ] {
            assert!(css.contains(needle), "{needle}");
        }
    }

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 0.01
    }

    #[test]
    fn centre_display_width_and_position() {
        let (left, width) = centre_display(1280.0).unwrap();
        assert!(close(width, 440.32), "{width}");
        // centred 1.1 % of 1280 (14.08 px) left of the window centre
        assert!(close(left + width / 2.0, 640.0 - 14.08), "{left}");
        assert!(close(centre_display(3000.0).unwrap().1, 720.0));
        assert!(centre_display(466.0).is_some());
        assert!(centre_display(465.0).is_none(), "under 160 px it is hidden");
    }

    #[test]
    fn groups_at_1280() {
        let left = left_group_centres(1280.0, false);
        let steps: Vec<f32> = left.windows(2).map(|w| w[1] - w[0]).collect();
        assert!(steps.iter().all(|s| close(*s, 31.5)));
        // midway between the 22 px inset and the display's left edge (405.76 px)
        assert!(close(left[2], (22.0 + 405.76) / 2.0), "{left:?}");
        assert!(left[0] - 15.0 >= 22.0);

        let right = right_group_centres(1280.0, false);
        // avatar: 16 px before the 3 × 46 px window buttons; others 35 px apart
        assert!(close(right[3], 1280.0 - 138.0 - 16.0 - 16.0), "{right:?}");
        assert!(close(right[0], 1005.0) && close(right[1], 1040.0) && close(right[2], 1075.0));

        let mac_right = right_group_centres(1280.0, true);
        assert!(close(mac_right[3], 1280.0 - 32.0));
        assert!(left_group_centres(1280.0, true)[0] - 15.0 >= MAC_TRAFFIC_LIGHTS);
    }

    #[test]
    fn bar_css_matches_the_rules() {
        let css = bar_css(false);
        for needle in [
            "--bar-h:56px",
            "--bar-edge:22px",
            "--bar-buttons:138px",
            "--display-w:min(34.4vw,720px)",
            "calc(50vw - 1.1vw - min(34.4vw,720px) / 2)",
            ".window-btn{width:46px;}",
            ".bar-btn{width:30px;height:30px;}",
            ".left-group{gap:1.5px;",
            "/ 2 - 78px)",
            ".right-group{gap:5px;right:calc(var(--bar-buttons) + 16px);}",
            ".avatar{width:32px;height:32px;margin-left:-1px;}",
        ] {
            assert!(css.contains(needle), "{needle}\n{css}");
        }
        assert!(bar_css(true).contains("--bar-edge:78px;--bar-buttons:0px"));
    }

    #[test]
    fn double_click_window() {
        assert!(!is_double_click(None));
        assert!(is_double_click(Some(Duration::from_millis(300))));
        assert!(!is_double_click(Some(Duration::from_millis(800))));
    }

    fn token(name: &str) -> Token {
        *COLOR_TOKENS.iter().find(|t| t.name == name).unwrap()
    }

    #[test]
    fn colour_tokens_match_spec() {
        assert_eq!(token("bg"), t("bg", "#fbfbfb", "#000000"));
        assert_eq!(token("text-2").dark, "#ffffffa3");
        assert_eq!(token("key").light, "#111111");
        assert_eq!(token("glass-bg"), t("glass-bg", "#f5f5f58c", "#26262699"));
        assert_eq!(token("gray-6").dark, "#1c1c1c");
        assert_eq!(token("bar-line"), t("bar-line", "#00000026", "#ffffff14"));
        let names: std::collections::HashSet<_> = COLOR_TOKENS.iter().map(|t| t.name).collect();
        assert_eq!(names.len(), COLOR_TOKENS.len(), "duplicate token");
    }

    #[test]
    fn theme_css_has_both_themes_contrast_and_forced_dark() {
        let css = theme_css();
        assert!(css.contains(":root{color-scheme:light;"));
        assert!(css.contains("--c-bg:#fbfbfb;"));
        assert!(css.contains(
            "@media (prefers-color-scheme:dark){:root{color-scheme:dark;--c-bg:#000000;"
        ));
        assert!(css.contains("@media (prefers-contrast:more){:root{--c-glass-bg:#f2f2f2;"));
        assert!(css.contains("--c-glass-bg:#0e0e0e;"));
        assert!(css.contains(".force-dark{color-scheme:dark;--c-bg:#000000;"));
        assert!(css.contains("--type-header:700 34px/40px;"));
        assert!(css.contains("--font:\"SF Pro Display\", system-ui"));
    }
}
