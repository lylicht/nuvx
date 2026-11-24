//! nuvx app shell (spec 01): the PC shell with the player in the title bar (§2.3), the sidebar (§2.2),
//! and the narrow layout below 484 px (§4).

use std::time::Instant;

use dioxus::html::input_data::MouseButton;
use dioxus::prelude::*;

pub mod shell;

use shell::*;
// explicit import wins over the prelude's own `Action`
use shell::Action;

const APP_CSS: &str = include_str!("../assets/app.css");
/// macOS keeps its own traffic-light buttons at the left of the bar; elsewhere the bar draws its own.
const MAC: bool = cfg!(target_os = "macos");

#[component]
pub fn App() -> Element {
    let route = use_signal(|| Route::Home);
    let player = use_signal(Player::default);
    let mut drawer = use_signal(|| false);
    let css = use_hook(|| format!("{}{}{}{}", theme_css(), layout_css(), bar_css(MAC), APP_CSS));
    // shortcut: no library or Spotify connection yet, so no user or Spotify playlists (plan step 10)
    let sections = use_hook(|| sidebar_sections(&[], None));

    use_future(move || async move {
        let mut keys = document::eval(KEY_LISTENER_JS);
        while let Ok(input) = keys.recv::<KeyInput>().await {
            if let Some(action) = shortcut(&input) {
                dispatch(action, route, player);
            }
        }
    });

    let open = drawer();
    let (panel, full_screen) = {
        let p = player.read();
        (p.panel, p.full_screen)
    };
    rsx! {
        style { "{css}" }
        div { class: "backdrop", aria_hidden: "true" }
        div { class: if open { "app drawer-open" } else { "app" },
            PlayerBar { route, player, drawer }
            div { class: "body",
                div { class: "scrim", onclick: move |_| drawer.set(false) }
                Sidebar { route, drawer, sections }
                main { class: "content",
                    Page { route }
                }
                if let Some(panel) = panel {
                    SidePanel { panel, player }
                }
            }
        }
        if full_screen {
            FullScreen { player }
        }
    }
}

fn dispatch(action: Action, mut route: Signal<Route>, mut player: Signal<Player>) {
    if action == Action::FocusSearch {
        route.set(Route::Search);
        // the field exists only after the next render
        document::eval(
            "requestAnimationFrame(() => requestAnimationFrame(() => document.getElementById('search-field')?.focus()))",
        );
    } else {
        player.write().apply(action);
    }
}

// ---------------------------------------------------------------- PC player bar (§2.3)

#[component]
fn PlayerBar(route: Signal<Route>, player: Signal<Player>, drawer: Signal<bool>) -> Element {
    let mut route = route;
    let mut player = player;
    let mut drawer = drawer;
    let p = player.read().clone();
    let off = !p.loaded;
    let open = drawer();
    let lyrics = p.panel == Some(Panel::Lyrics);
    let up_next = p.panel == Some(Panel::UpNext);
    rsx! {
        header { class: "bar",
            DragArea {}
            div { class: "left-group", role: "group", aria_label: "Playback",
                button {
                    class: "bar-btn menu-btn",
                    aria_label: if open { "Close menu" } else { "Open menu" },
                    aria_expanded: "{open}",
                    onclick: move |_| drawer.set(!open),
                    Glyph { icon: Icon::Menu }
                }
                BarButton {
                    label: "Shuffle up next",
                    icon: Icon::Shuffle,
                    size: "s15",
                    disabled: off,
                    onclick: move |_| player.write().apply(Action::ShuffleUpNext),
                }
                BarButton {
                    label: "Previous",
                    icon: Icon::Previous,
                    size: "s18",
                    disabled: off,
                    onclick: move |_| player.write().apply(Action::Previous),
                }
                button {
                    class: if p.playing { "bar-btn play playing" } else { "bar-btn play" },
                    aria_label: if p.playing { "Pause" } else { "Play" },
                    title: if p.playing { "Pause" } else { "Play" },
                    disabled: off,
                    onclick: move |_| player.write().apply(Action::TogglePlay),
                    span { class: "morph i-play", Glyph { icon: Icon::Play } }
                    span { class: "morph i-pause", Glyph { icon: Icon::Pause } }
                }
                BarButton {
                    label: "Next",
                    icon: Icon::Next,
                    size: "s18",
                    disabled: off,
                    onclick: move |_| player.write().apply(Action::Next),
                }
                BarButton {
                    label: p.repeat.label(),
                    icon: if p.repeat == Repeat::One { Icon::RepeatOne } else { Icon::Repeat },
                    size: "s15",
                    pressed: p.repeat != Repeat::Off,
                    onclick: move |_| player.write().apply(Action::CycleRepeat),
                }
            }
            Centre { player }
            div { class: "right-group", role: "group", aria_label: "Player options",
                button {
                    class: "bar-btn s16",
                    aria_label: if p.muted { "Unmute" } else { "Mute" },
                    title: "Volume {p.volume} %",
                    onclick: move |_| player.write().apply(Action::ToggleMute),
                    Glyph { icon: p.volume_icon() }
                }
                BarButton {
                    label: "Lyrics",
                    icon: if lyrics { Icon::LyricsOpen } else { Icon::Lyrics },
                    size: "s16",
                    pressed: lyrics,
                    onclick: move |_| player.write().apply(Action::TogglePanel(Panel::Lyrics)),
                }
                BarButton {
                    label: "Up next",
                    icon: Icon::UpNext,
                    size: "s16",
                    pressed: up_next,
                    onclick: move |_| player.write().apply(Action::TogglePanel(Panel::UpNext)),
                }
                button {
                    class: "avatar",
                    aria_label: "Account",
                    title: "Account",
                    // shortcut: no accounts yet, so the account menu is the Settings page (plan step 8)
                    onclick: move |_| route.set(Route::Settings),
                    Glyph { icon: Icon::Account }
                }
            }
            if !MAC {
                WindowButtons {}
            }
        }
    }
}

#[component]
fn BarButton(
    label: String,
    icon: Icon,
    size: &'static str,
    #[props(default)] disabled: bool,
    #[props(default)] pressed: bool,
    onclick: EventHandler<MouseEvent>,
) -> Element {
    rsx! {
        button {
            class: "bar-btn {size}",
            aria_label: "{label}",
            title: "{label}",
            aria_pressed: if pressed { "true" },
            disabled,
            onclick: move |e| onclick.call(e),
            Glyph { icon }
        }
    }
}

/// The centre display: artwork, title and artists, seek line, favourite and mini player.
#[component]
fn Centre(player: Signal<Player>) -> Element {
    let mut player = player;
    let p = player.read().clone();
    rsx! {
        div { class: "centre", role: "group", aria_label: "Now playing",
            button {
                class: "art",
                aria_label: "Open now playing",
                title: "Open now playing",
                onclick: move |_| player.write().apply(Action::OpenFullScreen),
                span { class: "art-placeholder", Glyph { icon: Icon::Song } }
                span { class: "art-hover", Glyph { icon: Icon::OpenFullScreen } }
            }
            div { class: "track",
                // shortcut: nothing can load yet, so title, artists and times stay empty (plan step 9)
                if p.loaded {
                    span { class: "time elapsed", "{format_time(0)}" }
                    span { class: "time remaining", "-{format_time(0)}" }
                }
            }
            button {
                class: if p.favourite { "bar-btn s14 fav on" } else { "bar-btn s14 fav" },
                aria_label: if p.favourite { "Remove from favourites" } else { "Add to favourites" },
                title: if p.favourite { "Remove from favourites" } else { "Add to favourites" },
                aria_pressed: "{p.favourite}",
                disabled: !p.loaded,
                onclick: move |_| player.write().apply(Action::ToggleFavourite),
                Glyph { icon: if p.favourite { Icon::FavouriteOn } else { Icon::Favourite } }
            }
            button {
                class: if p.mini_player { "bar-btn s15 mini on" } else { "bar-btn s15 mini" },
                aria_label: if p.mini_player { "Close mini player" } else { "Mini player" },
                title: if p.mini_player { "Close the mini player window" } else { "Open the mini player in its own window" },
                aria_pressed: "{p.mini_player}",
                onclick: move |_| player.write().apply(Action::ToggleMiniPlayer),
                Glyph { icon: Icon::MiniPlayer }
            }
            div {
                class: "seek",
                role: "slider",
                aria_label: "Seek",
                aria_valuemin: "0",
                aria_valuemax: "0",
                aria_valuenow: "0",
                aria_disabled: "{!p.loaded}",
            }
        }
    }
}

/// Empty bar space: drag moves the window, a second press within the double-click time maximises.
#[component]
fn DragArea() -> Element {
    let mut last = use_signal(|| None::<Instant>);
    rsx! {
        div {
            class: "drag",
            onmousedown: move |e: MouseEvent| {
                if e.trigger_button() != Some(MouseButton::Primary) {
                    return;
                }
                let now = Instant::now();
                if is_double_click(last().map(|t| now - t)) {
                    last.set(None);
                    window::toggle_maximized();
                } else {
                    last.set(Some(now));
                    window::drag();
                }
            },
        }
    }
}

/// Minimise, maximise / restore and close at the right end of the bar (Windows and Linux).
#[component]
fn WindowButtons() -> Element {
    let mut maximized = use_signal(window::is_maximized);
    use_future(move || async move {
        let mut resized =
            document::eval("window.addEventListener('resize', () => dioxus.send(0));");
        while resized.recv::<u8>().await.is_ok() {
            maximized.set(window::is_maximized());
        }
    });
    let max = maximized();
    rsx! {
        div { class: "window-buttons",
            button {
                class: "window-btn",
                aria_label: "Minimise",
                title: "Minimise",
                onclick: move |_| window::minimise(),
                Glyph { icon: Icon::WindowMinimise }
            }
            button {
                class: "window-btn",
                aria_label: if max { "Restore" } else { "Maximise" },
                title: if max { "Restore" } else { "Maximise" },
                onclick: move |_| {
                    window::toggle_maximized();
                    maximized.set(window::is_maximized());
                },
                Glyph { icon: if max { Icon::WindowRestore } else { Icon::WindowMaximise } }
            }
            button {
                class: "window-btn close",
                aria_label: "Close",
                title: "Close",
                onclick: move |_| window::close(),
                Glyph { icon: Icon::Close }
            }
        }
    }
}

/// Native window operations. Without the `desktop` feature (tests, a future web build) they do nothing.
mod window {
    #[cfg(feature = "desktop")]
    fn with<T>(f: impl FnOnce(&dioxus::desktop::DesktopContext) -> T) -> T {
        f(&dioxus::desktop::window())
    }

    pub fn drag() {
        #[cfg(feature = "desktop")]
        with(|w| w.drag());
    }

    pub fn toggle_maximized() {
        #[cfg(feature = "desktop")]
        with(|w| w.toggle_maximized());
    }

    pub fn minimise() {
        #[cfg(feature = "desktop")]
        with(|w| w.window.set_minimized(true));
    }

    pub fn close() {
        #[cfg(feature = "desktop")]
        with(|w| w.close());
    }

    pub fn is_maximized() -> bool {
        #[cfg(feature = "desktop")]
        return with(|w| w.window.is_maximized());
        #[cfg(not(feature = "desktop"))]
        false
    }
}

// ---------------------------------------------------------------- sidebar (§2.2)

#[component]
fn Sidebar(route: Signal<Route>, drawer: Signal<bool>, sections: Vec<NavSection>) -> Element {
    let mut route = route;
    let mut drawer = drawer;
    rsx! {
        nav { class: "sidebar glass", aria_label: "Main navigation",
            button {
                class: "brand",
                aria_label: "nuvx, Home",
                onclick: move |_| {
                    route.set(Route::Home);
                    drawer.set(false);
                },
                Mark {}
                span { aria_hidden: "true", "nuvx" }
            }
            div { class: "nav-scroll",
                for section in sections {
                    if let Some(header) = section.header {
                        h2 { class: "nav-header", "{header}" }
                    }
                    ul {
                        for item in section.items {
                            li { key: "{item.label}",
                                NavLink { route, drawer, target: item.route, label: item.label, icon: item.icon }
                            }
                        }
                    }
                }
            }
            div { class: "nav-bottom",
                NavLink { route, drawer, target: Route::Guide, label: "Guide", icon: Icon::Guide }
                div { class: "nav-row",
                    NavLink { route, drawer, target: Route::Downloads, label: "Downloads", icon: Icon::ExternalLink, trailing: true }
                    NavLink { route, drawer, target: Route::Settings, label: "Settings", icon: Icon::Settings, icon_only: true }
                }
            }
        }
    }
}

#[component]
fn NavLink(
    route: Signal<Route>,
    drawer: Signal<bool>,
    target: Route,
    label: String,
    icon: Icon,
    #[props(default)] icon_only: bool,
    /// The icon follows the label (the Downloads link's up-right arrow).
    #[props(default)]
    trailing: bool,
) -> Element {
    let mut route = route;
    let mut drawer = drawer;
    let current = is_current(&target, &route.read());
    let class = match (current, icon_only) {
        (true, true) => "nav-item icon-only current",
        (false, true) => "nav-item icon-only",
        (true, false) => "nav-item current",
        (false, false) => "nav-item",
    };
    rsx! {
        button {
            class: if trailing { "{class} trailing" } else { "{class}" },
            aria_current: if current { "page" },
            aria_label: if icon_only { "{label}" },
            title: if icon_only { "{label}" },
            onclick: move |_| {
                route.set(target.clone());
                drawer.set(false);
            },
            if trailing {
                span { "{label}" }
                Glyph { icon }
            } else {
                Glyph { icon }
                if !icon_only {
                    span { "{label}" }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pages and panels (§2.4, §2.5, §7)

#[component]
fn Page(route: Signal<Route>) -> Element {
    let mut route = route;
    let current = route();
    let title = current.title();
    rsx! {
        section { class: "page", aria_labelledby: "page-title",
            h1 { id: "page-title", class: "page-title", "{title}" }
            p { class: "empty-note", "Empty page: its content arrives in a later build step." }
            if current == Route::Search {
                input {
                    id: "search-field",
                    class: "search-field",
                    r#type: "search",
                    placeholder: "Search",
                    aria_label: "Search",
                }
            }
            if current == Route::Settings {
                div { class: "sub-links",
                    button { class: "chip", onclick: move |_| route.set(Route::SettingsDiscord), "Discord" }
                    button { class: "chip", onclick: move |_| route.set(Route::SettingsListenTogether), "Listen Together" }
                }
            }
            Skeleton {}
        }
    }
}

#[component]
fn Skeleton() -> Element {
    rsx! {
        div { class: "skeleton", aria_hidden: "true",
            div { class: "sk-shelf",
                for i in 0..6 {
                    div { key: "{i}", class: "sk sk-tile" }
                }
            }
            for i in 0..6 {
                div { key: "r{i}", class: "sk sk-row" }
            }
        }
    }
}

#[component]
fn SidePanel(panel: Panel, player: Signal<Player>) -> Element {
    let mut player = player;
    let title = panel.title();
    rsx! {
        aside { class: "side-panel glass", aria_label: title,
            div { class: "panel-head",
                h2 { class: "panel-title", "{title}" }
                button {
                    class: "bar-btn s14",
                    aria_label: "Close {title}",
                    title: "Close",
                    onclick: move |_| player.write().panel = None,
                    Glyph { icon: Icon::Close }
                }
            }
            p { class: "empty-note", "Nothing playing. This panel is empty until playback arrives in a later build step." }
            Skeleton {}
        }
    }
}

#[component]
fn FullScreen(player: Signal<Player>) -> Element {
    let mut player = player;
    rsx! {
        div {
            class: "fullscreen force-dark",
            role: "dialog",
            aria_modal: "true",
            aria_label: "Full screen player",
            button {
                class: "bar-btn s16 fs-close",
                aria_label: "Close full screen player",
                title: "Close",
                onclick: move |_| player.write().full_screen = false,
                Glyph { icon: Icon::Close }
            }
            div { class: "fs-art", Glyph { icon: Icon::Song } }
            p { class: "fs-title", "Nothing playing" }
            p { class: "empty-note", "Empty full-screen player: artwork, lyrics and controls arrive in a later build step." }
        }
    }
}

/// licht's nuvx mark (`assets/brand`), next to the word "nuvx".
#[component]
fn Mark() -> Element {
    rsx! {
        span { class: "mark", "aria-hidden": "true", dangerous_inner_html: include_str!("../assets/brand/nuvx_mark.svg") }
    }
}

/// An SF Symbol (§5.6), coloured by the surrounding text colour.
#[component]
fn Glyph(icon: Icon) -> Element {
    rsx! {
        span { class: "icon", "aria-hidden": "true", dangerous_inner_html: icon.svg() }
    }
}
