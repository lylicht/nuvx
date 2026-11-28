use super::*;

pub fn icon_at(ui: &Ui, center: Pos2, glyph: char, size: f32, color: Color32) {
    if let Some(vector) = sf_glyph(glyph, false) {
        super::super::glyphs::draw(ui, center, size, vector, color);
        return;
    }
    let g = ui
        .painter()
        .layout_no_wrap(glyph.to_string(), theme::icon_font(size), color);
    let ink = g.mesh_bounds;
    let pos = if ink.is_positive() {
        center - ink.center().to_vec2()
    } else {
        center - g.size() / 2.0
    };
    ui.painter().galley(pos, g, color);
}

pub fn icon_for_response(
    ui: &Ui,
    center: Pos2,
    glyph: char,
    size: f32,
    color: Color32,
    active: bool,
    response: &Response,
) {
    if let Some(vector) = sf_glyph(glyph, active) {
        super::super::glyphs::draw_button(
            ui,
            Rect::from_center_size(center, Vec2::splat(size)),
            vector,
            color,
            response,
        );
    } else {
        icon_at(ui, center, glyph, size, color);
    }
}

fn sf_glyph(glyph: char, active: bool) -> Option<super::super::glyphs::Glyph> {
    use super::super::glyphs as sf;
    Some(match glyph {
        theme::icon::LIBRARY => sf::LIBRARY,
        theme::icon::SEARCH => sf::SEARCH,
        theme::icon::CHEVRON_LEFT => sf::CHEVRON_LEFT,
        theme::icon::CHEVRON_RIGHT => sf::CHEVRON_RIGHT,
        theme::icon::CHEVRON_UP => sf::CHEVRON_UP,
        theme::icon::CHEVRON_DOWN => sf::CHEVRON_DOWN,
        theme::icon::PLAY => sf::PLAY,
        theme::icon::PAUSE => sf::PAUSE,
        theme::icon::SHUFFLE => sf::SHUFFLE,
        theme::icon::HEART if active => sf::HEART_FILLED,
        theme::icon::HEART => sf::HEART,
        theme::icon::THUMBS_DOWN => sf::THUMBS_DOWN,
        theme::icon::LIST_MUSIC => sf::QUEUE,
        theme::icon::X => sf::CLOSE,
        theme::icon::HISTORY => sf::HISTORY,
        theme::icon::TRASH_2 => sf::TRASH,
        theme::icon::ELLIPSIS_VERTICAL => sf::ELLIPSIS_VERTICAL,
        theme::icon::LIST_PLUS => sf::LIST_PLUS,
        theme::icon::LIST_MINUS => sf::LIST_MINUS,
        theme::icon::LIST_CHECKS => sf::LIST_CHECKS,
        theme::icon::RADIO => sf::RADIO,
        theme::icon::LOG_OUT => sf::LOG_OUT,
        theme::icon::MUSIC => sf::MUSIC_NOTE,
        theme::icon::SPARKLES => sf::SPARKLES,
        theme::icon::CHECK => sf::CHECK,
        theme::icon::CIRCLE_USER => sf::PERSON_CIRCLE,
        theme::icon::UPLOAD => sf::UPLOAD,
        theme::icon::USERS => sf::USERS,
        theme::icon::COPY => sf::COPY,
        theme::icon::LINK => sf::LINK,
        theme::icon::CROWN => sf::CROWN,
        theme::icon::HEADPHONES => sf::HEADPHONES,
        theme::icon::DOWNLOAD => sf::DOWNLOAD,
        theme::icon::FOLDER_OPEN => sf::FOLDER,
        theme::icon::PIN => sf::PIN,
        theme::icon::PIN_OFF => sf::PIN_OFF,
        theme::icon::MAXIMIZE_2 => sf::MAXIMIZE_2,
        _ => return None,
    })
}

pub fn heart(ui: &Ui, center: Pos2, size: f32, fill: f32, outline: Color32, fill_color: Color32) {
    let glyph = if fill >= 0.5 {
        super::super::glyphs::HEART_FILLED
    } else {
        super::super::glyphs::HEART
    };
    super::super::glyphs::draw(
        ui,
        center,
        size,
        glyph,
        if fill >= 0.5 { fill_color } else { outline },
    );
}

pub fn icon_button(ui: &mut Ui, glyph: char, size: f32, name: &str, active: bool) -> Response {
    let name = crate::ui::locale::text(name);
    let d = (size + 16.0).max(32.0);
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(d), Sense::click());
    let resp = named(resp, name)
        .on_hover_text(name)
        .on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    if h > 0.0 {
        ui.painter()
            .circle_filled(rect.center(), d / 2.0, theme::glass(0.10 * h));
    }
    let color = if active {
        theme::text()
    } else {
        theme::lerp_color(theme::text_dim(), theme::text(), h)
    };
    icon_for_response(ui, rect.center(), glyph, size, color, active, &resp);
    resp
}

pub fn pill(ui: &mut Ui, label: &str, glyph: Option<char>, primary: bool) -> Response {
    let label = crate::ui::locale::text(label);
    let text = galley(
        ui,
        label,
        theme::semibold(14.0),
        Color32::PLACEHOLDER,
        f32::INFINITY,
    );
    let icon_w = if glyph.is_some() { 24.0 } else { 0.0 };
    let (rect, resp) =
        ui.allocate_exact_size(vec2(text.size().x + icon_w + 36.0, 38.0), Sense::click());
    let resp = named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    let (fill, fg) = if primary {
        if theme::light() {
            (
                theme::lerp_color(theme::text(), Color32::BLACK, h),
                Color32::WHITE,
            )
        } else {
            (
                theme::lerp_color(Color32::from_gray(240), Color32::WHITE, h),
                Color32::from_gray(12),
            )
        }
    } else {
        (theme::glass(0.10 + 0.08 * h), theme::text())
    };
    let r = rect.shrink(if resp.is_pointer_button_down_on() {
        1.0
    } else {
        0.0
    });
    ui.painter().rect_filled(r, 19.0, fill);
    let mut x = r.left() + 18.0;
    if let Some(g) = glyph {
        icon_for_response(ui, pos2(x + 8.0, r.center().y), g, 17.0, fg, false, &resp);
        x += icon_w;
    }
    ui.painter()
        .galley(pos2(x, r.center().y - text.size().y / 2.0), text, fg);
    resp
}

pub fn chip(ui: &mut Ui, label: &str, selected: bool) -> Response {
    let label = crate::ui::locale::text(label);
    let text = galley(
        ui,
        label,
        theme::semibold(13.5),
        Color32::PLACEHOLDER,
        f32::INFINITY,
    );
    let (rect, resp) = ui.allocate_exact_size(vec2(text.size().x + 28.0, 34.0), Sense::click());
    let resp = named(resp, label).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.15);
    let s = theme::anim_bool(ui.ctx(), resp.id.with("s"), selected, 0.2);
    use crate::preset::ChipStyle;
    let style = crate::preset::active().app.chip;
    let radius = (crate::preset::active().chrome.radius * 0.55).clamp(4.0, 17.0);
    let (on_fill, on_fg) = match style {
        ChipStyle::Accent => {
            let a = ui
                .ctx()
                .data(|d| d.get_temp::<Color32>(egui::Id::unique("accent")))
                .unwrap_or(theme::RED);
            (
                a,
                if a.r() as u32 + a.g() as u32 + a.b() as u32 > 480 {
                    Color32::from_gray(15)
                } else {
                    Color32::WHITE
                },
            )
        }
        _ => (theme::text(), theme::on_text()),
    };
    let off_fill = if style == ChipStyle::Outline {
        theme::glass(0.04 * h)
    } else {
        theme::glass(0.09 + 0.06 * h)
    };
    let fill = theme::lerp_color(off_fill, on_fill, s);
    ui.painter().rect_filled(rect, radius, fill);
    if style == ChipStyle::Outline {
        ui.painter().rect_stroke(
            rect,
            radius,
            egui::Stroke::new(1.0, theme::glass(0.3 * (1.0 - s))),
            egui::StrokeKind::Inside,
        );
    }
    let fg = theme::lerp_color(theme::text(), on_fg, s);
    ui.painter()
        .galley(rect.center() - text.size() / 2.0, text, fg);
    resp
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ui::glyphs;

    #[test]
    fn keyboard_and_padding_clicks_activate_the_same_vector_control() {
        let ctx = egui::Context::default();
        let mut target = None;
        let mut clicks = 0;
        {
            let mut frame = |time: f64, events: Vec<egui::Event>, focus: bool| {
                let mut output = ctx.run_ui(
                    egui::RawInput {
                        time: Some(time),
                        events,
                        ..Default::default()
                    },
                    |ui| {
                        let response = icon_button(ui, theme::icon::PAUSE, 16.0, "Pause", false);
                        if focus {
                            response.request_focus();
                        }
                        clicks += usize::from(response.clicked());
                        target = Some((response.id, response.rect));
                    },
                );
                output.textures_delta.clear();
            };
            frame(0.0, vec![], true);
            for (index, pressed) in [true, false].into_iter().enumerate() {
                frame(
                    0.016 * (index + 1) as f64,
                    vec![egui::Event::Key {
                        key: egui::Key::Space,
                        physical_key: None,
                        pressed,
                        repeat: false,
                        modifiers: egui::Modifiers::NONE,
                    }],
                    false,
                );
            }
        }
        assert_eq!(clicks, 1, "Space should activate a focused icon button");
        let (id, rect) = target.unwrap();
        assert!(rect.width() >= 32.0 && rect.height() >= 32.0);
        assert!(
            ctx.data(|d| d.get_temp::<f64>(id.with("vyral-glyph-last-tap")))
                .is_some()
        );
        // Outside the 16-point symbol, inside its 32-point hit target.
        let point = rect.center() + vec2(13.0, 0.0);
        for (index, pressed) in [true, false].into_iter().enumerate() {
            let mut output = ctx.run_ui(
                egui::RawInput {
                    time: Some(0.1 + index as f64 * 0.016),
                    events: vec![
                        egui::Event::PointerMoved(point),
                        egui::Event::PointerButton {
                            pos: point,
                            button: egui::PointerButton::Primary,
                            pressed,
                            modifiers: egui::Modifiers::NONE,
                        },
                    ],
                    ..Default::default()
                },
                |ui| {
                    clicks += usize::from(
                        icon_button(ui, theme::icon::PAUSE, 16.0, "Pause", false).clicked(),
                    );
                },
            );
            output.textures_delta.clear();
        }
        assert_eq!(clicks, 2, "padding should activate the control too");
    }

    #[test]
    fn available_sf_glyphs_use_layered_vectors() {
        assert_eq!(sf_glyph(theme::icon::SEARCH, false), Some(glyphs::SEARCH));
        assert_eq!(sf_glyph(theme::icon::PLAY, false), Some(glyphs::PLAY));
        assert_eq!(
            sf_glyph(theme::icon::HEART, true),
            Some(glyphs::HEART_FILLED)
        );
        assert_eq!(sf_glyph(theme::icon::HEART, false), Some(glyphs::HEART));

        let icons = [
            theme::icon::LIBRARY,
            theme::icon::SEARCH,
            theme::icon::CHEVRON_LEFT,
            theme::icon::CHEVRON_RIGHT,
            theme::icon::CHEVRON_UP,
            theme::icon::CHEVRON_DOWN,
            theme::icon::PLAY,
            theme::icon::PAUSE,
            theme::icon::SHUFFLE,
            theme::icon::HEART,
            theme::icon::THUMBS_DOWN,
            theme::icon::LIST_MUSIC,
            theme::icon::X,
            theme::icon::HISTORY,
            theme::icon::TRASH_2,
            theme::icon::ELLIPSIS_VERTICAL,
            theme::icon::LIST_PLUS,
            theme::icon::LIST_MINUS,
            theme::icon::LIST_CHECKS,
            theme::icon::RADIO,
            theme::icon::LOG_OUT,
            theme::icon::MUSIC,
            theme::icon::SPARKLES,
            theme::icon::CHECK,
            theme::icon::CIRCLE_USER,
            theme::icon::UPLOAD,
            theme::icon::USERS,
            theme::icon::COPY,
            theme::icon::LINK,
            theme::icon::CROWN,
            theme::icon::HEADPHONES,
            theme::icon::DOWNLOAD,
            theme::icon::FOLDER_OPEN,
            theme::icon::PIN,
            theme::icon::PIN_OFF,
            theme::icon::MAXIMIZE_2,
        ];
        for glyph in icons {
            let vector = sf_glyph(glyph, false).unwrap_or_else(|| panic!("unmapped glyph {glyph}"));
            assert!(
                vector.contains("data-source=\"f2d22ed50937183de95b6e356e30ed2841e136ad\""),
                "vector is not from the pinned companion library: {glyph}"
            );
        }
    }
}
