use std::collections::HashMap;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU8, Ordering};

const ENGLISH: u8 = 0;
const PERSIAN: u8 = 1;

static ACTIVE: AtomicU8 = AtomicU8::new(ENGLISH);

/// Keep the persisted preference separate from the resolved system locale.
pub(super) fn set_preference(preference: &str) {
    let language = resolve_preference(preference, &sys_locale::get_locale().unwrap_or_default());
    ACTIVE.store(language, Ordering::Relaxed);
}

fn resolve_preference(preference: &str, system: &str) -> u8 {
    if preference.is_empty() || preference.eq_ignore_ascii_case("system") {
        resolve_locale(system)
    } else {
        resolve_locale(preference)
    }
}

fn resolve_locale(locale: &str) -> u8 {
    let language = locale
        .split(['-', '_'])
        .next()
        .unwrap_or_default()
        .to_ascii_lowercase();
    if language == "fa" { PERSIAN } else { ENGLISH }
}

pub(super) fn preference_label(preference: &str) -> &'static str {
    match preference {
        "en" => text("English"),
        "fa" => text("Persian"),
        _ => text("System default"),
    }
}

pub(super) fn is_rtl() -> bool {
    ACTIVE.load(Ordering::Relaxed) == PERSIAN
}

/// Translates application-owned copy; unlisted text and all user/catalogue content pass through.
pub(super) fn text(english: &str) -> &str {
    text_in(ACTIVE.load(Ordering::Relaxed), english)
}

fn text_in(language: u8, english: &str) -> &str {
    if language != PERSIAN {
        return english;
    }
    if let Some(translation) = persian_strings().get(english) {
        return translation;
    }
    match english {
        "Home" => "خانه",
        "Explore" => "کاوش",
        "Library" => "کتابخانه",
        "Search" => "جست‌وجو",
        "Radio" => "رادیو",
        "Podcasts" => "پادکست‌ها",
        "Settings" => "تنظیمات",
        "Appearance" => "ظاهر",
        "Account" => "حساب",
        "Account & integrations" => "حساب و یکپارچه‌سازی‌ها",
        "Playback" => "پخش",
        "Equalizer" => "اکولایزر",
        "Downloads" => "بارگیری‌ها",
        "Lyrics" => "متن ترانه",
        "Artists" => "هنرمندان",
        "Search & content" => "جست‌وجو و محتوا",
        "Network" => "شبکه",
        "Sync & Connect" => "همگام‌سازی و اتصال",
        "Your data" => "داده‌های شما",
        "Import" => "درون‌ریزی",
        "Keyboard shortcuts" => "میان‌برهای صفحه‌کلید",
        "Window" => "پنجره",
        "Mode" => "حالت",
        "Font" => "قلم",
        "App language" => "زبان برنامه",
        "System default" => "پیش‌فرض سیستم",
        "English" => "English",
        "Persian" => "فارسی",
        "Reduce animation" => "کاهش پویانمایی",
        "Animated background" => "پس‌زمینهٔ متحرک",
        "Animated artwork" => "تصویر هنری متحرک",
        "Pulse with the music" => "هماهنگی با ضرب‌آهنگ موسیقی",
        "Player at bottom" => "پخش‌کننده در پایین",
        "Nothing here yet" => "هنوز چیزی اینجا نیست",
        "Retry" => "تلاش دوباره",
        "Cancel" => "لغو",
        "Save" => "ذخیره",
        "Close" => "بستن",
        "Back" => "بازگشت",
        "Forward" => "جلو",
        "Play" => "پخش",
        "Pause" => "مکث",
        "Now playing" => "در حال پخش",
        "Albums" => "آلبوم‌ها",
        "Playlists" => "فهرست‌های پخش",
        "Songs" => "آهنگ‌ها",
        "History" => "تاریخچه",
        "System follows your system's setting. Now playing stays dark, over the album art." => {
            "برنامه از تنظیمات سیستم پیروی می‌کند. صفحهٔ پخش روی تصویر آلبوم تیره می‌ماند."
        }
        "Use the system language automatically, or choose English or Persian." => {
            "زبان سیستم را خودکار دنبال کنید یا انگلیسی و فارسی را برگزینید."
        }
        _ => english,
    }
}

fn persian_strings() -> &'static HashMap<&'static str, &'static str> {
    static STRINGS: OnceLock<HashMap<&'static str, &'static str>> = OnceLock::new();
    STRINGS.get_or_init(|| {
        // Parsed once; leaked strings make lookups allocation-free for the immediate-mode UI.
        let mut strings = serde_json::from_str::<HashMap<String, String>>(include_str!("fa.json"))
            .expect("the bundled Persian catalogue must be valid");
        strings.extend(
            serde_json::from_str::<HashMap<String, String>>(include_str!("desktop-fa.json"))
                .expect("the bundled desktop Persian catalogue must be valid"),
        );
        strings
            .into_iter()
            .map(|(key, value)| {
                (
                    Box::leak(key.into_boxed_str()) as &'static str,
                    Box::leak(value.into_boxed_str()) as &'static str,
                )
            })
            .collect()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn system_persian_locale_is_detected_from_bcp47_and_posix_tags() {
        assert_eq!(resolve_locale("fa-IR"), PERSIAN);
        assert_eq!(resolve_locale("fa_IR.UTF-8"), PERSIAN);
        assert_eq!(resolve_locale("en-GB"), ENGLISH);
        assert_eq!(resolve_locale(""), ENGLISH);
    }

    #[test]
    fn user_override_wins_over_system_detection() {
        assert_eq!(resolve_preference("en", "fa-IR"), ENGLISH);
        assert_eq!(resolve_preference("fa", "en-US"), PERSIAN);
        assert_eq!(resolve_preference("FA-ir", "en-US"), PERSIAN);
        assert_eq!(resolve_preference("system", "fa_IR.UTF-8"), PERSIAN);
        assert_eq!(resolve_preference("xx", "fa-IR"), ENGLISH);
        assert_eq!(text_in(ENGLISH, "Explore"), "Explore");
        assert_eq!(text_in(PERSIAN, "Explore"), "کاوش");
        assert_eq!(text_in(PERSIAN, "Settings"), "تنظیمات");
        assert_eq!(text_in(PERSIAN, "Shuffle"), "پخش تصادفی");
        assert_eq!(
            text_in(PERSIAN, "Uncatalogued user content"),
            "Uncatalogued user content"
        );
    }

    #[test]
    fn desktop_catalogue_is_nonempty_and_preserves_placeholders() {
        let catalogue: HashMap<String, String> =
            serde_json::from_str(include_str!("desktop-fa.json")).unwrap();
        assert!(catalogue.len() >= 75);
        for (english, persian) in catalogue {
            assert!(!persian.trim().is_empty(), "{english}");
            assert!(!persian.contains("\\'"), "{english}");
            for marker in ["%1$s", "%2$s", "%1$d", "{count}"] {
                assert_eq!(
                    english.matches(marker).count(),
                    persian.matches(marker).count(),
                    "{english}"
                );
            }
        }
    }

    #[test]
    fn persian_selection_mirrors_the_rendered_settings_row() {
        struct Restore(u8);
        impl Drop for Restore {
            fn drop(&mut self) {
                ACTIVE.store(self.0, Ordering::Relaxed);
            }
        }
        let _restore = Restore(ACTIVE.load(Ordering::Relaxed));
        let ctx = egui::Context::default();
        crate::theme::install_fonts(&ctx, "inter");
        for (preference, expected_title, rtl) in
            [("fa", "تنظیمات", true), ("en", "Settings", false)]
        {
            set_preference(preference);
            assert_eq!(is_rtl(), rtl);
            let mut control = egui::Rect::NOTHING;
            let mut output = ctx.run_ui(Default::default(), |ui| {
                super::super::pages::setting_row(ui, "Settings", None, |ui| {
                    control = ui
                        .allocate_exact_size(egui::vec2(100.0, 30.0), egui::Sense::hover())
                        .0;
                });
            });
            let title = output
                .shapes
                .iter()
                .find_map(|clipped| match &clipped.shape {
                    egui::Shape::Text(text) if text.galley.job.text == expected_title => Some(text),
                    _ => None,
                })
                .expect("translated title must be present in the rendered egui output");
            if rtl {
                assert!(
                    control.right() < title.pos.x,
                    "Persian control must be left of its label"
                );
            } else {
                assert!(
                    control.left() > title.pos.x + title.galley.size().x,
                    "English control must be right of its label"
                );
            }
            output.textures_delta.clear();
        }
    }
}
