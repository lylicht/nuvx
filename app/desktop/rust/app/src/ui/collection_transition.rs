use egui::{Rect, Ui, pos2};

use crate::model::Thumb;
use crate::theme;

use super::Page;

const DURATION: f64 = 0.42;
const STATE_ID: &str = "playlist-artwork-transition";

#[derive(Clone)]
pub(super) struct PlaylistTransition {
    pub id: String,
    pub source_page: Page,
    pub source_rect: Rect,
    pub source_radius: f32,
    pub thumbs: std::sync::Arc<[Thumb]>,
    pub destination_rect: Option<Rect>,
    pub destination_radius: Option<f32>,
    pub reverse: bool,
}

#[derive(Clone)]
struct Published {
    page: Page,
    page_changed: f64,
    transition: Option<PlaylistTransition>,
}

pub(super) fn publish(
    ctx: &egui::Context,
    page: &Page,
    page_changed: f64,
    transition: Option<PlaylistTransition>,
) {
    let page_changed = if page_changed < 0.0 {
        ctx.input(|input| input.time)
    } else {
        page_changed
    };
    ctx.data_mut(|data| {
        data.insert_temp(
            egui::Id::unique(STATE_ID),
            Published {
                page: page.clone(),
                page_changed,
                transition,
            },
        )
    });
}

pub(super) fn paint_destination(
    ui: &Ui,
    transition: &mut Option<PlaylistTransition>,
    page_changed: f64,
    id: &str,
    rect: Rect,
    radius: f32,
    now: f64,
) -> bool {
    let Some(transition) = transition
        .as_mut()
        .filter(|transition| !transition.reverse && transition.id == id)
    else {
        return false;
    };
    transition.destination_rect = Some(rect);
    transition.destination_radius = Some(radius);
    let Some(progress) = progress(now, page_changed, theme::speed()) else {
        return false;
    };
    if progress >= 1.0 {
        return false;
    }
    let at = interpolate_rect(transition.source_rect, rect, progress);
    let radius = egui::emath::lerp(transition.source_radius..=radius, progress);
    super::widgets::paint_cover(ui, at, &transition.thumbs, radius, 1.0);
    true
}

pub(super) fn paint_source(ui: &Ui, id: &str, rect: Rect) -> bool {
    let published = ui
        .ctx()
        .data(|data| data.get_temp::<Published>(egui::Id::unique(STATE_ID)));
    let Some(Published {
        page,
        page_changed,
        transition: Some(transition),
    }) = published
    else {
        return false;
    };
    if !transition.reverse || transition.source_page != page || transition.id != id {
        return false;
    }
    let (Some(from), Some(from_radius)) =
        (transition.destination_rect, transition.destination_radius)
    else {
        return false;
    };
    let now = ui.ctx().input(|input| input.time);
    let Some(progress) = progress(now, page_changed, theme::speed()) else {
        return false;
    };
    if progress >= 1.0 {
        return false;
    }
    let at = interpolate_rect(from, rect, progress);
    let radius = egui::emath::lerp(from_radius..=transition.source_radius, progress);
    super::widgets::paint_cover(ui, at, &transition.thumbs, radius, 1.0);
    true
}

fn progress(now: f64, page_changed: f64, speed: f32) -> Option<f32> {
    if speed <= 0.0 || page_changed < 0.0 {
        return None;
    }
    let duration = DURATION * speed as f64;
    Some(crate::theme::ease_in_out(
        ((now - page_changed) / duration) as f32,
    ))
}

fn interpolate_rect(from: Rect, to: Rect, t: f32) -> Rect {
    Rect::from_min_max(
        pos2(
            egui::emath::lerp(from.left()..=to.left(), t),
            egui::emath::lerp(from.top()..=to.top(), t),
        ),
        pos2(
            egui::emath::lerp(from.right()..=to.right(), t),
            egui::emath::lerp(from.bottom()..=to.bottom(), t),
        ),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use egui::{pos2, vec2};

    #[test]
    fn artwork_bounds_interpolate_between_card_and_detail() {
        let card = Rect::from_min_size(pos2(20.0, 30.0), vec2(44.0, 44.0));
        let detail = Rect::from_min_size(pos2(240.0, 80.0), vec2(232.0, 232.0));

        assert_eq!(interpolate_rect(card, detail, 0.0), card);
        assert_eq!(interpolate_rect(card, detail, 1.0), detail);
        assert_eq!(
            interpolate_rect(card, detail, 0.5).center(),
            pos2(199.0, 124.0)
        );
    }

    #[test]
    fn reduced_motion_disables_the_shared_artwork_transition() {
        assert!(progress(0.1, 0.0, 0.0).is_none());
        assert_eq!(progress(0.21, 0.0, 1.0), Some(0.5));
    }

    #[test]
    fn artwork_transition_uses_the_app_motion_speed() {
        assert_eq!(progress(0.1365, 0.0, 0.65), Some(0.5));
    }
}
