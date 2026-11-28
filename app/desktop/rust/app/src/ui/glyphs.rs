use egui::{Color32, ColorImage, Id, Pos2, Rect, TextureHandle, TextureOptions, Ui, pos2, vec2};
use std::sync::Arc;
use tiny_skia::{
    BlendMode, FillRule, LineCap, LineJoin, Paint, PathBuilder, Pixmap, Stroke, Transform,
};

pub type Glyph = &'static str;

pub const SHUFFLE: Glyph = include_str!("../../assets/icon/catalog/shuffle.svg");
pub const PREVIOUS: Glyph = include_str!("../../assets/icon/catalog/prev.svg");
pub const PLAY: Glyph = include_str!("../../assets/icon/catalog/play.svg");
pub const PAUSE: Glyph = include_str!("../../assets/icon/catalog/pause.svg");
pub const NEXT: Glyph = include_str!("../../assets/icon/catalog/next.svg");
pub const REPEAT: Glyph = include_str!("../../assets/icon/catalog/repeat.svg");
pub const REPEAT_ONE: Glyph = include_str!("../../assets/icon/catalog/repeat_one.svg");
pub const STAR: Glyph = include_str!("../../assets/icon/catalog/star.svg");
pub const STAR_FILLED: Glyph = include_str!("../../assets/icon/catalog/star_filled.svg");
pub const LYRICS: Glyph = include_str!("../../assets/icon/catalog/lyrics.svg");
pub const LYRICS_OFF: Glyph = include_str!("../../assets/icon/catalog/lyrics_off.svg");
pub const QUEUE: Glyph = include_str!("../../assets/icon/catalog/queue.svg");
pub const VIDEO: Glyph = include_str!("../../assets/icon/catalog/video.svg");
pub const MUSIC_NOTE: Glyph = include_str!("../../assets/icon/catalog/music_note.svg");
pub const PODCAST: Glyph = include_str!("../../assets/icon/catalog/podcast.svg");
pub const MAXIMIZE_2: Glyph = include_str!("../../assets/icon/catalog/maximize2.svg");
pub const MINI_PLAYER: Glyph = include_str!("../../assets/icon/catalog/miniplayer.svg");
pub const VOLUME: [Glyph; 4] = [
    include_str!("../../assets/icon/catalog/volume_0.svg"),
    include_str!("../../assets/icon/catalog/volume_1.svg"),
    include_str!("../../assets/icon/catalog/volume_2.svg"),
    include_str!("../../assets/icon/catalog/volume_3.svg"),
];

pub const HOME: Glyph = include_str!("../../assets/icon/catalog/home.svg");
pub const EXPLORE: Glyph = include_str!("../../assets/icon/catalog/explore.svg");
pub const LIBRARY: Glyph = include_str!("../../assets/icon/catalog/library.svg");
pub const SEARCH: Glyph = include_str!("../../assets/icon/catalog/search.svg");
pub const DOWNLOAD: Glyph = include_str!("../../assets/icon/catalog/download.svg");
pub const SETTINGS: Glyph = include_str!("../../assets/icon/catalog/settings.svg");
pub const PERSON_CIRCLE: Glyph = include_str!("../../assets/icon/catalog/person_circle.svg");
pub const SPARKLES: Glyph = include_str!("../../assets/icon/catalog/sparkles.svg");
pub const PERSON: Glyph = include_str!("../../assets/icon/catalog/person.svg");
pub const PLAY_OUTLINE: Glyph = include_str!("../../assets/icon/catalog/play_outline.svg");
pub const PENCIL: Glyph = include_str!("../../assets/icon/catalog/pencil.svg");
pub const PAINTBRUSH: Glyph = include_str!("../../assets/icon/catalog/paintbrush.svg");
pub const WIFI: Glyph = include_str!("../../assets/icon/catalog/wifi.svg");
pub const IMPORT: Glyph = include_str!("../../assets/icon/catalog/import.svg");
pub const KEYBOARD: Glyph = include_str!("../../assets/icon/catalog/keyboard.svg");
pub const HISTORY: Glyph = include_str!("../../assets/icon/catalog/history.svg");
pub const RADIO: Glyph = include_str!("../../assets/icon/catalog/radio.svg");
pub const WAVEFORM: Glyph = include_str!("../../assets/icon/catalog/waveform.svg");
pub const WAVEFORM_BADGE_MICROPHONE: Glyph =
    include_str!("../../assets/icon/catalog/waveform_badge_microphone.svg");
pub const HEART: Glyph = include_str!("../../assets/icon/catalog/heart.svg");
pub const HEART_FILLED: Glyph = include_str!("../../assets/icon/catalog/heart_filled.svg");
pub const THUMBS_DOWN: Glyph = include_str!("../../assets/icon/catalog/thumbs_down.svg");
pub const CLOSE: Glyph = include_str!("../../assets/icon/catalog/close.svg");
pub const TRASH: Glyph = include_str!("../../assets/icon/catalog/trash.svg");
pub const ELLIPSIS_VERTICAL: Glyph =
    include_str!("../../assets/icon/catalog/ellipsis_vertical.svg");
pub const LIST_PLUS: Glyph = include_str!("../../assets/icon/catalog/list_plus.svg");
pub const LIST_MINUS: Glyph = include_str!("../../assets/icon/catalog/list_minus.svg");
pub const LIST_CHECKS: Glyph = include_str!("../../assets/icon/catalog/list_checks.svg");
pub const LOG_OUT: Glyph = include_str!("../../assets/icon/catalog/logout.svg");
pub const CHECK: Glyph = include_str!("../../assets/icon/catalog/check.svg");
pub const UPLOAD: Glyph = include_str!("../../assets/icon/catalog/upload.svg");
pub const USERS: Glyph = include_str!("../../assets/icon/catalog/users.svg");
pub const COPY: Glyph = include_str!("../../assets/icon/catalog/copy.svg");
pub const LINK: Glyph = include_str!("../../assets/icon/catalog/link.svg");
pub const HEADPHONES: Glyph = include_str!("../../assets/icon/catalog/headphones.svg");
pub const FOLDER: Glyph = include_str!("../../assets/icon/catalog/folder.svg");
pub const PIN: Glyph = include_str!("../../assets/icon/catalog/pin.svg");
pub const PIN_OFF: Glyph = include_str!("../../assets/icon/catalog/pin_off.svg");
pub const CHEVRON_LEFT: Glyph = include_str!("../../assets/icon/catalog/chevron_left.svg");
pub const CHEVRON_RIGHT: Glyph = include_str!("../../assets/icon/catalog/chevron_right.svg");
pub const CHEVRON_UP: Glyph = include_str!("../../assets/icon/catalog/chevron_up.svg");
pub const CHEVRON_DOWN: Glyph = include_str!("../../assets/icon/catalog/chevron_down.svg");
pub const CROWN: Glyph = include_str!("../../assets/icon/catalog/crown.svg");

#[cfg(test)]
pub(super) fn is_layered_and_visible(glyph: Glyph) -> bool {
    let Some(shape) = parse(glyph) else {
        return false;
    };
    if shape.groups < 2 {
        return false;
    }
    raster(&shape, 31, 31, 1.0, |_| 0.0, |_| 1.0)
        .is_some_and(|image| image.pixels.iter().any(|pixel| pixel.a() > 200))
}
pub const SPOTIFY: Glyph = include_str!("../../assets/icon/glyphs/spotify.svg");
pub const DISCORD: Glyph = include_str!("../../assets/icon/glyphs/discord.svg");
pub const YOUTUBE_MUSIC: Glyph = include_str!("../../assets/icon/glyphs/youtube_music.svg");
pub const MARK: Glyph = include_str!("../../assets/icon/mark.svg");

pub const APPLE_GLYPH: f32 = 20.0;
pub const LUCIDE_GLYPH: f32 = 17.0;

struct Layer {
    path: tiny_skia::Path,
    alpha: f32,
    group: u32,
    erase: bool,
    fill_rule: FillRule,
    stroke: Option<Stroke>,
}

struct Shape {
    view: [f32; 4],
    groups: u32,
    layers: Vec<Layer>,
    variable: Vec<usize>,
}

fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let i = tag.find(&format!(" {name}=\""))? + name.len() + 3;
    Some(&tag[i..i + tag[i..].find('"')?])
}

fn parse(svg: &str) -> Option<Shape> {
    let root = &svg[..svg.find('>')?];
    let view: Vec<f32> = attr(root, "viewBox")?
        .split_whitespace()
        .map(|x| x.parse().ok())
        .collect::<Option<_>>()?;
    if view.len() != 4 || view.iter().any(|v| !v.is_finite()) || view[2] <= 0.0 || view[3] <= 0.0 {
        return None;
    }
    let path_tags: Vec<_> = svg.split("<path").skip(1).collect();
    let declared_groups = attr(root, "data-groups")
        .and_then(|g| g.parse().ok())
        .unwrap_or(1);
    let split_ungrouped_paths = declared_groups <= 1 && path_tags.len() > 1;
    let mut layers = Vec::new();
    for (index, tag) in path_tags.into_iter().enumerate() {
        let tag = &tag[..tag.find('>')?];
        let source = path(attr(tag, "d")?)?;
        let source = attr(tag, "transform")
            .and_then(matrix)
            .map_or(Some(source.clone()), |transform| {
                source.clone().transform(transform)
            })?;
        let fill = attr(tag, "fill").unwrap_or("#fff") != "none";
        let stroke = attr(tag, "stroke")
            .filter(|color| *color != "none")
            .map(|_| Stroke {
                width: attr(tag, "stroke-width")
                    .and_then(|width| width.parse().ok())
                    .unwrap_or(1.0),
                line_cap: match attr(tag, "stroke-linecap") {
                    Some("round") => LineCap::Round,
                    Some("square") => LineCap::Square,
                    _ => LineCap::Butt,
                },
                line_join: match attr(tag, "stroke-linejoin") {
                    Some("round") => LineJoin::Round,
                    Some("bevel") => LineJoin::Bevel,
                    _ => LineJoin::Miter,
                },
                ..Stroke::default()
            });
        layers.push(Layer {
            path: source,
            alpha: attr(
                tag,
                if fill {
                    "fill-opacity"
                } else {
                    "stroke-opacity"
                },
            )
            .and_then(|a| a.parse().ok())
            .unwrap_or(1.0),
            group: attr(tag, "data-group")
                .and_then(|g| g.parse().ok())
                .unwrap_or(if split_ungrouped_paths {
                    index as u32
                } else {
                    0
                }),
            erase: attr(tag, "data-erase").is_some(),
            fill_rule: match attr(tag, "fill-rule") {
                Some("evenodd") => FillRule::EvenOdd,
                _ => FillRule::Winding,
            },
            stroke,
        });
    }
    let groups = if split_ungrouped_paths {
        layers.len() as u32
    } else {
        declared_groups
    };
    let variable = attr(root, "data-variable").map_or_else(Vec::new, |v| {
        v.split_whitespace()
            .filter_map(|i| i.parse().ok())
            .collect()
    });
    Some(Shape {
        view: view.try_into().ok()?,
        groups,
        layers,
        variable,
    })
}

fn matrix(value: &str) -> Option<Transform> {
    let values: Vec<f32> = value
        .strip_prefix("matrix(")?
        .strip_suffix(')')?
        .split_whitespace()
        .map(|part| part.parse().ok())
        .collect::<Option<_>>()?;
    let [a, b, c, d, e, f]: [f32; 6] = values.try_into().ok()?;
    Some(Transform::from_row(a, b, c, d, e, f))
}

fn number(b: &[u8], i: &mut usize) -> Option<f32> {
    let start = *i;
    if matches!(b.get(*i), Some(b'-' | b'+')) {
        *i += 1;
    }
    let mut dot = false;
    while let Some(&c) = b.get(*i) {
        match c {
            b'0'..=b'9' => {}
            b'.' if !dot => dot = true,
            b'e' | b'E' => {
                *i += 1;
                if matches!(b.get(*i), Some(b'-' | b'+')) {
                    *i += 1;
                }
                continue;
            }
            _ => break,
        }
        *i += 1;
    }
    std::str::from_utf8(&b[start..*i]).ok()?.parse().ok()
}

fn path(d: &str) -> Option<tiny_skia::Path> {
    let b = d.as_bytes();
    let mut pb = PathBuilder::new();
    let (mut i, mut cmd) = (0, b'M');
    let mut n = [0f32; 6];
    loop {
        while matches!(b.get(i), Some(c) if c.is_ascii_whitespace() || *c == b',') {
            i += 1;
        }
        let Some(&c) = b.get(i) else { break };
        if c.is_ascii_alphabetic() {
            cmd = c;
            i += 1;
            if c == b'Z' {
                pb.close();
                continue;
            }
        }
        let args = match cmd {
            b'M' | b'L' => 2,
            b'C' => 6,
            _ => return None,
        };
        for v in n.iter_mut().take(args) {
            while matches!(b.get(i), Some(c) if c.is_ascii_whitespace() || *c == b',') {
                i += 1;
            }
            *v = number(b, &mut i)?;
        }
        match cmd {
            b'M' => {
                pb.move_to(n[0], n[1]);
                cmd = b'L';
            }
            b'L' => pb.line_to(n[0], n[1]),
            _ => pb.cubic_to(n[0], n[1], n[2], n[3], n[4], n[5]),
        }
    }
    pb.finish()
}

fn shape(ui: &Ui, glyph: Glyph) -> Option<Arc<Shape>> {
    let id = Id::unique(("vyral-glyph-shape", glyph.as_ptr()));
    if let Some(s) = ui.ctx().data(|d| d.get_temp::<Arc<Shape>>(id)) {
        return Some(s);
    }
    let s = Arc::new(parse(glyph)?);
    ui.ctx().data_mut(|d| d.insert_temp(id, s.clone()));
    Some(s)
}

const LIFT_ROOM: f32 = 0.26;

fn raster(
    shape: &Shape,
    w: u32,
    h: u32,
    k: f32,
    lift: impl Fn(u32) -> f32,
    alpha: impl Fn(usize) -> f32,
) -> Option<ColorImage> {
    let mut pix = Pixmap::new(w, h)?;
    let [vx, vy, vw, vh] = shape.view;
    let ox = (w as f32 - vw * k) / 2.0 - vx * k;
    let oy = (h as f32 - vh * k) / 2.0 - vy * k;
    for (i, l) in shape.layers.iter().enumerate() {
        let mut paint = Paint {
            anti_alias: true,
            ..Paint::default()
        };
        if l.erase {
            paint.blend_mode = BlendMode::Clear;
        } else {
            paint.set_color_rgba8(
                255,
                255,
                255,
                ((l.alpha * alpha(i)).clamp(0.0, 1.0) * 255.0).round() as u8,
            );
        }
        let t = Transform::from_row(k, 0.0, 0.0, k, ox, oy - lift(l.group) * vw.max(vh) * k);
        if let Some(stroke) = &l.stroke {
            pix.stroke_path(&l.path, &paint, stroke, t, None);
        } else {
            pix.fill_path(&l.path, &paint, l.fill_rule, t, None);
        }
    }
    Some(ColorImage::from_rgba_premultiplied(
        [w as usize, h as usize],
        pix.data(),
    ))
}

const OPTIONS: TextureOptions = TextureOptions {
    magnification: egui::TextureFilter::Linear,
    minification: egui::TextureFilter::Linear,
    wrap_mode: egui::TextureWrapMode::ClampToEdge,
    mipmap_mode: None,
};

fn bounce_texture_key(id: Id, glyph: Glyph) -> Id {
    id.with(("vyral-glyph-bounce-tex", glyph.as_ptr()))
}

fn paint(
    ui: &Ui,
    rect: Rect,
    glyph: Glyph,
    tint: Color32,
    bounce: Option<(Id, SpringMotion)>,
    pulse: Option<(Id, f32)>,
) {
    if tint.a() == 0 {
        return;
    }
    let Some(shape) = shape(ui, glyph) else {
        return;
    };
    let ppp = ui.ctx().pixels_per_point();
    let [_, _, vw, vh] = shape.view;
    let k = (rect.width() / vw).min(rect.height() / vh) * ppp;
    let pad = if bounce.is_some() {
        (LIFT_ROOM * vw.max(vh) * k).ceil()
    } else {
        0.0
    };
    let (w, h) = (
        (vw * k).ceil() as u32,
        (vh * k).ceil() as u32 + 2 * pad as u32,
    );
    let key = match (&bounce, pulse) {
        (Some((id, _)), _) => bounce_texture_key(*id, glyph),
        (None, Some((id, _))) => id.with(("vyral-glyph-pulse-tex", glyph.as_ptr(), w, h)),
        (None, None) => Id::unique(("vyral-glyph", glyph.as_ptr(), w, h)),
    };
    let cached = ui.ctx().data(|d| d.get_temp::<TextureHandle>(key));
    let tex = match (cached, bounce.is_some() || pulse.is_some()) {
        (Some(t), false) => t,
        (cached, _) => {
            let lift = |g: u32| {
                bounce.as_ref().map_or(0.0, |(_, motion)| {
                    motion.lift(g.min(shape.groups.saturating_sub(1)))
                })
            };
            let alpha = |i| pulse.map_or(1.0, |(_, t)| variable_alpha(t, i, &shape.variable));
            let Some(img) = raster(&shape, w.max(1), h.max(1), k, lift, alpha) else {
                return;
            };
            match cached {
                Some(mut t) => {
                    t.set(img, OPTIONS);
                    t
                }
                None => {
                    let t = ui.ctx().load_texture("vyral-glyph", img, OPTIONS);
                    ui.ctx().data_mut(|d| d.insert_temp(key, t.clone()));
                    t
                }
            }
        }
    };
    let size = vec2(w as f32, h as f32) / ppp;
    let min = ((rect.center() - size / 2.0) * ppp).round() / ppp;
    let dst = Rect::from_min_size(min, size);
    ui.painter().image(
        tex.id(),
        dst,
        Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)),
        tint,
    );
}

pub fn draw(ui: &Ui, center: Pos2, size: f32, glyph: Glyph, tint: Color32) {
    let rect = Rect::from_center_size(center, vec2(size, size));
    paint(ui, rect, glyph, tint, None, None);
}

pub fn draw_in(ui: &Ui, rect: Rect, glyph: Glyph, tint: Color32) {
    paint(ui, rect, glyph, tint, None, None);
}

/// Use the control's response, not the ink bounds, for pointer and keyboard feedback.
pub fn draw_button(ui: &Ui, rect: Rect, glyph: Glyph, tint: Color32, response: &egui::Response) {
    if response.clicked() {
        bounce(ui.ctx(), response.id);
    }
    if response.has_focus() {
        ui.painter().rect_stroke(
            response.rect.shrink(1.0),
            6.0,
            egui::Stroke::new(1.5, tint),
            egui::StrokeKind::Inside,
        );
    }
    draw_bouncing(
        ui,
        rect.center(),
        rect.width().min(rect.height()),
        glyph,
        tint,
        response.id,
    );
}

pub fn draw_pulsing(
    ui: &Ui,
    center: Pos2,
    size: f32,
    glyph: Glyph,
    tint: Color32,
    id: Id,
    active: bool,
) {
    let rect = Rect::from_center_size(center, vec2(size, size));
    let t = animation_phase(
        active && crate::theme::speed() > 0.0,
        ui.input(|i| i.time),
        None,
    );
    if t.is_some() {
        ui.ctx().request_repaint();
    }
    paint(ui, rect, glyph, tint, None, t.map(|t| (id, t)));
}

pub fn draw_bouncing(ui: &Ui, center: Pos2, size: f32, glyph: Glyph, tint: Color32, id: Id) {
    let t = spring_motion(ui.ctx(), id);
    if t.is_some() {
        ui.ctx().request_repaint();
    }
    paint(
        ui,
        Rect::from_center_size(center, vec2(size, size)),
        glyph,
        tint,
        t.map(|t| (id, t)),
        None,
    );
}

pub fn bounce(ctx: &egui::Context, id: Id) {
    if crate::theme::speed() == 0.0 {
        return;
    }
    let now = ctx.input(|i| i.time);
    let key = id.with("vyral-glyph-spring");
    ctx.data_mut(|d| {
        // A crossfading control may paint two symbols for the same activation.
        if d.get_temp::<f64>(id.with("vyral-glyph-last-tap")) == Some(now) {
            return;
        }
        d.insert_temp(id.with("vyral-glyph-last-tap"), now);
        let mut motion = d
            .get_temp::<SpringMotion>(key)
            .unwrap_or_else(|| SpringMotion::new(now));
        motion.advance(now);
        motion.impulse();
        d.insert_temp(key, motion);
    });
    ctx.request_repaint();
}

fn animation_phase(enabled: bool, elapsed: f64, duration: Option<f32>) -> Option<f32> {
    let time = elapsed as f32;
    (enabled && time.is_finite() && time >= 0.0 && duration.is_none_or(|d| time < d))
        .then_some(time)
}

#[derive(Clone, Copy, Default)]
struct SpringPart {
    position: f32,
    velocity: f32,
}

/// Closed-form damped springs, rather than a restarted cubic easing timeline.
/// Each original path has its own frequency/impulse. A tap changes velocity,
/// never displacement, so repeated taps cannot snap a symbol back to rest.
#[derive(Clone)]
struct SpringMotion {
    at: f64,
    parts: [SpringPart; 32],
}

impl SpringMotion {
    fn new(at: f64) -> Self {
        Self {
            at,
            parts: [SpringPart::default(); 32],
        }
    }

    fn advance(&mut self, now: f64) {
        if !now.is_finite() || now < self.at {
            return;
        }
        let dt = (now - self.at) as f32;
        self.at = now;
        for (index, part) in self.parts.iter_mut().enumerate() {
            let omega = 23.0 / (1.0 + (index % 6) as f32 * 0.12);
            let decay = omega * 0.56;
            let damped = (omega * omega - decay * decay).sqrt();
            let envelope = (-decay * dt).exp();
            let (sin, cos) = (damped * dt).sin_cos();
            let a = part.position;
            let b = (part.velocity + decay * a) / damped;
            part.position = envelope * (a * cos + b * sin);
            part.velocity =
                envelope * ((b * damped - decay * a) * cos - (a * damped + decay * b) * sin);
        }
    }

    fn impulse(&mut self) {
        for (index, part) in self.parts.iter_mut().enumerate() {
            // Bound spring energy, not just velocity. Repeated taps can otherwise
            // accumulate displacement even when each individual impulse is small.
            let omega = 23.0 / (1.0 + (index % 6) as f32 * 0.12);
            let limit = (omega * omega * (0.24_f32.powi(2) - part.position.powi(2)))
                .max(0.0)
                .sqrt()
                .min(5.0);
            part.velocity =
                (part.velocity + 4.0 / (1.0 + (index % 6) as f32 * 0.15)).clamp(-limit, limit);
        }
    }

    fn lift(&self, group: u32) -> f32 {
        self.parts[group as usize % self.parts.len()]
            .position
            .clamp(-LIFT_ROOM, LIFT_ROOM)
    }

    fn settled(&self) -> bool {
        self.parts
            .iter()
            .all(|p| p.position.abs() < 0.0001 && p.velocity.abs() < 0.001)
    }
}

fn spring_motion(ctx: &egui::Context, id: Id) -> Option<SpringMotion> {
    spring_motion_enabled(ctx, id, crate::theme::speed() > 0.0)
}

fn spring_motion_enabled(ctx: &egui::Context, id: Id, enabled: bool) -> Option<SpringMotion> {
    let key = id.with("vyral-glyph-spring");
    let now = ctx.input(|i| i.time);
    ctx.data_mut(|d| {
        if !enabled {
            d.remove::<SpringMotion>(key);
            return None;
        }
        let mut motion = d.get_temp::<SpringMotion>(key)?;
        motion.advance(now);
        if motion.settled() {
            d.remove::<SpringMotion>(key);
            None
        } else {
            d.insert_temp(key, motion.clone());
            Some(motion)
        }
    })
}

const VARIABLE_SLOT: f32 = 1.0 / 3.0;
const VARIABLE_DIM: f32 = 0.3;

fn variable_alpha(t: f32, layer: usize, order: &[usize]) -> f32 {
    let Some(k) = order.iter().position(|&l| l == layer) else {
        return 1.0;
    };
    let t = t.rem_euclid(VARIABLE_SLOT * (order.len() + 1) as f32);
    let f = t / VARIABLE_SLOT - k as f32;
    let f = if f > 1.0 { 2.0 - f } else { f }.clamp(0.0, 1.0);
    VARIABLE_DIM + (1.0 - VARIABLE_DIM) * f * f * (3.0 - 2.0 * f)
}

pub fn volume_step(volume: f32) -> usize {
    if volume <= 0.0 {
        0
    } else if volume < 1.0 / 3.0 {
        1
    } else if volume < 2.0 / 3.0 {
        2
    } else {
        3
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reduced_motion_disables_glyph_animation() {
        assert_eq!(animation_phase(false, 0.2, None), None);
        assert_eq!(animation_phase(false, 0.2, Some(0.7)), None);
        assert_eq!(animation_phase(true, 0.2, None), Some(0.2));
        assert_eq!(animation_phase(true, 0.2, Some(0.7)), Some(0.2));
        assert_eq!(animation_phase(true, 0.7, Some(0.5)), None);
        for time in [f64::NAN, f64::INFINITY, -1.0] {
            assert_eq!(animation_phase(true, time, None), None);
        }
    }

    #[test]
    fn svg_layers_start_in_a_staggered_order() {
        let svg = r##"<svg viewBox="0 0 24 24" data-groups="2"><path d="M1 1L2 2Z" fill="#fff" data-group="0"/><path d="M3 3L4 4Z" fill="#fff" data-group="1"/></svg>"##;
        let shape = parse(svg).expect("valid layered SVG");
        assert_eq!(shape.groups, 2);
        assert_eq!(
            shape
                .layers
                .iter()
                .map(|layer| layer.group)
                .collect::<Vec<_>>(),
            [0, 1]
        );
        let mut motion = SpringMotion::new(0.0);
        motion.impulse();
        motion.advance(0.03);
        assert_ne!(motion.lift(0), motion.lift(1));
    }

    #[test]
    fn separate_ungrouped_svg_paths_become_layers() {
        let svg = r##"<svg viewBox="0 0 24 24" data-groups="1"><path d="M1 1L2 2Z"/><path d="M3 3L4 4Z"/></svg>"##;
        let shape = parse(svg).expect("valid SVG");
        assert_eq!(shape.groups, 2);
        assert_eq!(
            shape
                .layers
                .iter()
                .map(|layer| layer.group)
                .collect::<Vec<_>>(),
            [0, 1]
        );
    }

    #[test]
    fn invalid_viewboxes_are_rejected() {
        for view in [
            "0 0 0 24",
            "0 0 24 -1",
            "0 0 NaN 24",
            "0 0 inf 24",
            "0 0 24",
            "0 0 24 24 24",
        ] {
            assert!(
                parse(&format!(
                    r#"<svg viewBox="{view}"><path d="M0 0 L1 1 Z"/></svg>"#
                ))
                .is_none(),
                "{view}"
            );
        }
    }

    #[test]
    fn every_glyph_parses_and_draws() {
        let all = [
            SHUFFLE,
            PREVIOUS,
            PLAY,
            PAUSE,
            NEXT,
            REPEAT,
            REPEAT_ONE,
            STAR,
            STAR_FILLED,
            LYRICS,
            LYRICS_OFF,
            QUEUE,
            VIDEO,
            MUSIC_NOTE,
            PODCAST,
            MAXIMIZE_2,
            HOME,
            EXPLORE,
            LIBRARY,
            SEARCH,
            DOWNLOAD,
            SETTINGS,
            PERSON_CIRCLE,
            SPARKLES,
            MARK,
            SPOTIFY,
            DISCORD,
            YOUTUBE_MUSIC,
            PERSON,
            PLAY_OUTLINE,
            PENCIL,
            PAINTBRUSH,
            WIFI,
            IMPORT,
            KEYBOARD,
            HISTORY,
            RADIO,
            WAVEFORM,
            WAVEFORM_BADGE_MICROPHONE,
        ];
        for g in all.into_iter().chain(VOLUME) {
            let s = parse(g).unwrap_or_else(|| panic!("{}", &g[..80]));
            assert!(!s.layers.is_empty());
            let img = raster(
                &s,
                24,
                24,
                24.0 / s.view[2].max(s.view[3]),
                |_| 0.0,
                |_| 1.0,
            )
            .unwrap();
            assert!(img.pixels.iter().any(|p| p.a() > 200), "{}", &g[..80]);
        }
    }

    #[test]
    fn every_non_brand_ui_glyph_preserves_companion_paths_without_fake_stroke_layers() {
        let icons = [
            SHUFFLE,
            PREVIOUS,
            PLAY,
            PAUSE,
            NEXT,
            REPEAT,
            REPEAT_ONE,
            STAR,
            STAR_FILLED,
            LYRICS,
            LYRICS_OFF,
            QUEUE,
            VIDEO,
            MUSIC_NOTE,
            PODCAST,
            MAXIMIZE_2,
            MINI_PLAYER,
            HOME,
            EXPLORE,
            LIBRARY,
            SEARCH,
            DOWNLOAD,
            SETTINGS,
            PERSON_CIRCLE,
            SPARKLES,
            PERSON,
            PLAY_OUTLINE,
            PENCIL,
            PAINTBRUSH,
            WIFI,
            IMPORT,
            KEYBOARD,
            HISTORY,
            RADIO,
            WAVEFORM,
            WAVEFORM_BADGE_MICROPHONE,
            HEART,
            HEART_FILLED,
            THUMBS_DOWN,
            CLOSE,
            TRASH,
            ELLIPSIS_VERTICAL,
            LIST_PLUS,
            LIST_MINUS,
            LIST_CHECKS,
            LOG_OUT,
            CHECK,
            UPLOAD,
            USERS,
            COPY,
            LINK,
            HEADPHONES,
            FOLDER,
            PIN,
            PIN_OFF,
            CHEVRON_LEFT,
            CHEVRON_RIGHT,
            CHEVRON_UP,
            CHEVRON_DOWN,
            CROWN,
        ];
        for glyph in icons.into_iter().chain(VOLUME) {
            let root = &glyph[..glyph.find('>').unwrap_or(glyph.len())];
            let symbol = attr(root, "data-symbol").unwrap_or("unknown symbol");
            let shape = parse(glyph).unwrap_or_else(|| panic!("invalid glyph: {symbol}"));
            assert!(!shape.layers.is_empty(), "{symbol}");
            assert!(
                glyph.contains("data-source=\"f2d22ed50937183de95b6e356e30ed2841e136ad\""),
                "{symbol}"
            );
            assert!(
                shape.layers.iter().all(|layer| layer.stroke.is_none()),
                "synthetic stroke: {symbol}"
            );
            for layer in glyph.split("<path").skip(1) {
                assert!(
                    attr(layer, "id").is_some(),
                    "missing layer identifier: {symbol}"
                );
            }
        }
        for glyph in [PAUSE, PREVIOUS, NEXT, WAVEFORM_BADGE_MICROPHONE] {
            assert!(
                is_layered_and_visible(glyph),
                "transport parts must be separate"
            );
        }
    }

    #[test]
    fn variable_color_uses_explicit_layer_order() {
        let order = [3, 2, 1, 0];
        let samples = [
            (0.0, [0.3, 0.3, 0.3, 0.3]),
            (0.167, [0.3, 0.3, 0.3, 0.65]),
            (0.5, [0.3, 0.3, 0.65, 0.65]),
            (1.0, [0.3, 1.0, 0.3, 0.3]),
            (1.125, [0.522, 0.778, 0.3, 0.3]),
        ];
        for (t, want) in samples {
            for (layer, w) in want.iter().enumerate() {
                assert!(
                    (variable_alpha(t, layer, &order) - w).abs() < 0.01,
                    "t={t} layer={layer}"
                );
            }
        }
        assert_eq!(variable_alpha(0.5, 0, &[]), 1.0);
    }

    #[test]
    fn springs_move_real_parts_independently_and_settle() {
        let mut motion = SpringMotion::new(0.0);
        motion.impulse();
        motion.advance(0.06);
        assert!(motion.lift(0) > 0.0);
        assert_ne!(motion.lift(0), motion.lift(1));
        motion.advance(2.0);
        assert!(motion.settled());
    }

    #[test]
    fn one_hundred_rapid_taps_preserve_position_remain_bounded_and_settle() {
        let mut motion = SpringMotion::new(0.0);
        for tap in 0..100 {
            motion.advance(tap as f64 * 0.012);
            let positions = motion.parts.map(|part| part.position);
            motion.impulse();
            assert_eq!(
                positions,
                motion.parts.map(|part| part.position),
                "tap snapped displacement"
            );
            assert!(
                motion
                    .parts
                    .iter()
                    .all(|p| p.position.is_finite() && p.position.abs() < LIFT_ROOM)
            );
        }
        motion.advance(4.0);
        assert!(motion.settled());
    }

    #[test]
    fn rasterized_pause_parts_have_different_motion_not_two_complete_outlines() {
        let shape = parse(PAUSE).unwrap();
        assert_eq!(shape.layers.len(), 2);
        assert_ne!(shape.layers[0].path, shape.layers[1].path);
        let k = 40.0 / shape.view[2].max(shape.view[3]);
        let still = raster(&shape, 60, 60, k, |_| 0.0, |_| 1.0).unwrap();
        let moving = raster(
            &shape,
            60,
            60,
            k,
            |g| if g == 0 { 0.1 } else { 0.0 },
            |_| 1.0,
        )
        .unwrap();
        assert_ne!(still.pixels, moving.pixels);
    }

    #[test]
    fn symbol_replacements_keep_separate_animated_textures() {
        let id = Id::unique("symbol-replacement");
        let play_texture = bounce_texture_key(id, PLAY);
        let pause_texture = bounce_texture_key(id, PAUSE);
        assert_ne!(play_texture, pause_texture);
    }

    #[test]
    fn reduced_motion_clears_live_springs_instead_of_resuming_them() {
        let ctx = egui::Context::default();
        let id = Id::unique("reduced-motion-test");
        let mut motion = SpringMotion::new(0.0);
        motion.impulse();
        ctx.data_mut(|d| d.insert_temp(id.with("vyral-glyph-spring"), motion));
        assert!(spring_motion_enabled(&ctx, id, false).is_none());
        assert!(spring_motion_enabled(&ctx, id, true).is_none());
    }
}
