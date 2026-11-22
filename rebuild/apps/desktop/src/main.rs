#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use dioxus::desktop::{Config, LogicalSize, WindowBuilder};

fn main() {
    let window = WindowBuilder::new()
        .with_title("nuvx")
        .with_inner_size(LogicalSize::new(1280.0, 800.0))
        .with_min_inner_size(LogicalSize::new(320.0, 480.0));
    let window = frame(window);
    dioxus::LaunchBuilder::desktop()
        .with_cfg(Config::new().with_window(window).with_menu(None))
        .launch(nuvx_ui::App);
}

/// The player bar is the title bar (spec 01 §2.3): no system frame on Windows and Linux; on macOS the
/// content runs under a transparent title bar so the traffic lights sit at the bar's left end.
#[cfg(not(target_os = "macos"))]
fn frame(window: WindowBuilder) -> WindowBuilder {
    #[cfg(target_os = "windows")]
    let window = {
        use dioxus::desktop::tao::platform::windows::WindowBuilderExtWindows;
        window.with_undecorated_shadow(true)
    };
    window.with_decorations(false)
}

#[cfg(target_os = "macos")]
fn frame(window: WindowBuilder) -> WindowBuilder {
    use dioxus::desktop::tao::platform::macos::WindowBuilderExtMacOS;
    window
        .with_titlebar_transparent(true)
        .with_title_hidden(true)
        .with_fullsize_content_view(true)
}
