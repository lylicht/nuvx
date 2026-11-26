use egui::{RichText, Ui, vec2};

use super::pages::{ListCtx, Rows, list, page_title, placeholder, thousands, CARD};
use super::widgets::{self, card, chip, pill};
use super::{Action, App, LibTab, Page, Selection};
use crate::model::{Card, Thumb, Track};
use crate::theme::{self, icon};
fn select_pill(ui: &mut Ui, sel: &mut Selection, page: &Page) {
    let on = sel.on(page);
    if pill(ui, if on { "Done" } else { "Select" }, Some(icon::LIST_CHECKS), false).clicked() {
        if on { sel.clear() } else { sel.start(page) }
    }
}

fn view_pill(ui: &mut Ui, list: &mut bool) {
    if pill(ui, if *list { "Grid view" } else { "List view" }, None, false).clicked() {
        *list = !*list;
    }
}

fn card_row(ui: &mut Ui, c: &Card, acts: &mut Vec<Action>) {
    use egui::{Rect, Sense, pos2};
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 56.0), Sense::click());
    let resp = widgets::named(resp, &c.title).on_hover_cursor(egui::CursorIcon::PointingHand);
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
    if h > 0.0 {
        ui.painter().rect_filled(rect, 10.0, theme::glass(0.08 * h));
    }
    let art = Rect::from_center_size(pos2(rect.left() + 32.0, rect.center().y), vec2(44.0, 44.0));
    widgets::paint_cover(ui, art, &c.thumbs, if c.round { 22.0 } else { 6.0 }, 1.0);
    let x = art.right() + 14.0;
    let w = rect.right() - x - 10.0;
    widgets::text_at(ui, pos2(x, rect.center().y - 19.0), &c.title, theme::semibold(14.0), theme::text(), w);
    widgets::text_at(ui, pos2(x, rect.center().y + 2.0), &c.subtitle, theme::regular(13.0), theme::text_dim(), w);
    if resp.clicked() {
        acts.push(Action::Open(c.target.clone()));
    }
}

/// Replay highlight cards, matching Android's current-year Library cards.
fn recap_row(app: &mut App, ui: &mut Ui) {
    use egui::{Align2, Sense, pos2};
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let recaps = crate::listening::recaps(now);
    let cards = crate::listening::replay_cards(now);
    if cards.is_empty() {
        return;
    }
    ui.add_space(14.0);
    egui::ScrollArea::horizontal().id_salt("recaps").scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden).show(ui, |ui| {
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 12.0;
            for card in &cards {
                let (rect, resp) = ui.allocate_exact_size(vec2(300.0, 180.0), Sense::click());
                let resp = widgets::named(resp, &format!("{} recap", card.label)).on_hover_cursor(egui::CursorIcon::PointingHand);
                if !ui.is_rect_visible(rect) {
                    continue;
                }
                let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.18);
                let thumbs = card.art.as_ref().map(|url| vec![Thumb { url: url.clone(), width: 544, height: 544 }]).unwrap_or_default();
                widgets::paint_cover(ui, rect, &thumbs, 18.0, 1.15 + 0.05 * h);
                ui.painter().rect_filled(rect, 18.0, theme::shade(0.58 - 0.08 * h));
                ui.painter().rect_stroke(rect, 18.0, egui::Stroke::new(1.0, theme::glass(0.16)), egui::StrokeKind::Inside);
                let pad = 18.0;
                let w = rect.width() - 2.0 * pad;
                let white = egui::Color32::WHITE;
                let dim = theme::with_alpha(white, 0.72);
                ui.painter().text(pos2(rect.left() + pad, rect.top() + pad), Align2::LEFT_TOP, card.label.to_uppercase(), theme::semibold(11.5), dim);
                ui.painter().text(pos2(rect.right() - pad, rect.top() + pad), Align2::RIGHT_TOP, "nuvx", theme::semibold(12.0), dim);
                widgets::text_at(ui, pos2(rect.left() + pad, rect.top() + 48.0), &card.value, theme::semibold(27.0), white, w);
                widgets::text_at(ui, pos2(rect.left() + pad, rect.bottom() - 32.0), &card.detail, theme::regular(13.0), dim, w);
                if resp.clicked() {
                    app.go(Page::Replay(card.page));
                }
            }
        });
    });
    if recaps.is_empty() {
        return;
    }
    egui::CollapsingHeader::new("Listening statistics").show(ui, |ui| {
        for recap in &recaps {
            egui::CollapsingHeader::new(recap.label).id_salt(("listening-stats", recap.label)).show(ui, |ui| {
                ui.label(RichText::new("Top songs").font(theme::semibold(15.0)));
                for (i, (_, entry)) in recap.top.iter().take(10).enumerate() {
                    ui.label(format!("{}. {} · {} plays · {}m {}s", i + 1, entry.title, entry.plays, entry.ms / 60_000, entry.ms / 1_000 % 60));
                }
                ui.label(RichText::new("Top artists by listening time").font(theme::semibold(15.0)));
                for (i, (artist, ms)) in recap.top_artists.iter().take(10).enumerate() {
                    ui.label(format!("{}. {} · {}m {}s", i + 1, artist, ms / 60_000, ms / 1_000 % 60));
                }
            });
        }
    });
    ui.add_space(6.0);
}

fn local_music(app: &mut App, ui: &mut Ui) {
    ui.add_space(8.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new("Local music").font(theme::semibold(18.0)));
        if pill(ui, "Refresh folders", None, false).clicked() {
            crate::downloads::refresh_local(Some(ui.ctx().clone()));
        }
        if pill(ui, "Choose folder", Some(icon::FOLDER_OPEN), false).clicked() {
            crate::downloads::pick_folder(ui.ctx());
        }
    });
    let groups = crate::downloads::local_groups();
    if groups.is_empty() {
        ui.label(RichText::new("No local songs found. Choose a download/music folder containing MP3 or M4A files, or refresh after adding songs.").color(theme::text_dim()));
    }
    for (folder, songs) in groups {
        egui::CollapsingHeader::new(format!("{folder} · {} songs", songs.len())).id_salt(("local-folder", &folder)).show(ui, |ui| {
            if pill(ui, "Play folder", Some(icon::PLAY), true).clicked() {
                app.actions.push(Action::Play(songs.clone(), 0));
            }
            for (i, song) in songs.iter().enumerate() {
                if ui.button(&song.title).clicked() {
                    app.actions.push(Action::Play(songs.clone(), i));
                }
            }
        });
    }
}

pub(super) fn library(app: &mut App, ui: &mut Ui, tab: LibTab, now: f64) {
    page_title(ui, "Library");
    // Recaps come from this computer's own history, so they show signed out too.
    recap_row(app, ui);
    local_music(app, ui);
    if !app.logged_in && (tab != LibTab::Playlists || (!app.create_playlists_locally && app.local_playlists.is_empty())) {
        ui.add_space(10.0);
        ui.label(RichText::new("Sign in to see your playlists, liked songs and history.").color(theme::text_dim()));
        ui.add_space(12.0);
        if pill(ui, "Sign in with Google", Some(icon::CIRCLE_USER), true).clicked() {
            app.start_sign_in(ui.ctx());
        }
        return;
    }
    ui.add_space(12.0);
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 10.0;
        for (t, label) in [(LibTab::Playlists, "Playlists"), (LibTab::Songs, "Liked songs"), (LibTab::Albums, "Albums"), (LibTab::History, "History"), (LibTab::Uploads, "Uploads")] {
            if chip(ui, label, tab == t).clicked() {
                if tab == t {
                    app.refresh();
                } else {
                    app.page = Page::Library(t);
                    app.page_loaded();
                }
            }
        }
    });
    ui.add_space(12.0);
    let as_list = app.lib_list;
    let grid = |ui: &mut Ui, cards: &[Card], acts: &mut Vec<Action>| {
        if as_list {
            for c in cards {
                card_row(ui, c, acts);
            }
            return;
        }
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing = vec2(20.0, 22.0);
            for c in cards {
                card(ui, c, CARD, false, acts);
            }
        });
    };
    let rows = Rows::new(app, now);
    let plain = |_: usize, _: &Track| None;
    match tab {
        LibTab::History => {
            if app.history.is_none() {
                placeholder(app, ui, now, 1, 48.0);
                return;
            }
            let Some(h) = &app.history else { return };
            list(ui, &rows, &app.liked, h, true, plain, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
        }
        LibTab::Uploads => {
            ui.horizontal(|ui| {
                if pill(ui, "Upload music", Some(icon::UPLOAD), true).clicked() {
                    app.pick_uploads(ui.ctx());
                }
                if app.uploads.as_ref().is_some_and(|u| !u.is_empty()) && pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() {
                    let mut t = app.uploads.clone().unwrap_or_default();
                    super::shuffle(&mut t);
                    app.actions.push(Action::Play(t, 0));
                }
            });
            ui.add_space(6.0);
            ui.label(RichText::new("Or drop audio files anywhere on the window. MP3, M4A, FLAC, OGG and WMA.").font(theme::regular(13.0)).color(theme::text_dim()));
            ui.add_space(12.0);
            if app.uploads.is_none() {
                placeholder(app, ui, now, 1, 48.0);
                return;
            }
            let Some(up) = &app.uploads else { return };
            if up.is_empty() {
                ui.add_space(20.0);
                ui.label(RichText::new("No uploads yet").color(theme::text_dim()));
            }
            list(ui, &rows, &app.liked, up, true, plain, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
        }
        LibTab::Songs => {
            if app.opened(super::LIKED_MUSIC).is_none() {
                placeholder(app, ui, now, 1, 48.0);
                return;
            }
            let more = app.receiving(super::LIKED_MUSIC);
            let Some(o) = app.lists.get(super::LIKED_MUSIC) else { return };
            let liked = &o.c.tracks;
            if !liked.is_empty() {
                ui.horizontal(|ui| {
                    if pill(ui, "Play", Some(icon::PLAY), true).clicked() {
                        app.actions.push(Action::Play(liked.clone(), 0));
                    }
                    if pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() {
                        let mut t = liked.clone();
                        super::shuffle(&mut t);
                        app.actions.push(Action::Play(t, 0));
                    }
                    select_pill(ui, &mut app.selection, &app.page);
                });
                ui.add_space(10.0);
            }
            list(ui, &rows, &app.liked, liked, true, plain, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
            if more {
                widgets::skeleton_rows(ui, now, 3);
            }
        }
        _ => {
            if app.library.is_none() && tab != LibTab::Playlists {
                placeholder(app, ui, now, 2, CARD);
                return;
            }
            match tab {
                LibTab::Playlists => {
                    ui.horizontal(|ui| {
                        if (app.logged_in || app.create_playlists_locally) && pill(ui, "New playlist", None, false).clicked() {
                            app.actions.push(Action::NewPlaylist(Vec::new()));
                        }
                        view_pill(ui, &mut app.lib_list);
                    });
                    ui.add_space(14.0);
                    let mut cards = app.local_playlist_cards();
                    cards.extend(app.library.as_ref().map(|lib| lib.playlists.iter()
                        .filter(|card| !matches!(&card.target, crate::model::Target::Playlist(id) if id.starts_with("nuvx-local:")))
                        .cloned().collect::<Vec<_>>()).unwrap_or_default());
                    grid(ui, &cards, &mut app.actions)
                }
                LibTab::Albums => {
                    view_pill(ui, &mut app.lib_list);
                    ui.add_space(14.0);
                    if let Some(lib) = &app.library { grid(ui, &lib.albums, &mut app.actions) }
                }
                LibTab::Songs | LibTab::History | LibTab::Uploads => unreachable!(),
            }
        }
    }
}

pub(super) fn replay(app: &mut App, ui: &mut Ui, page: crate::listening::ReplayPage) {
    use crate::listening::ReplayPage as Story;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let Some(recap) = crate::listening::replay_summary(now) else {
        page_title(ui, "Your Replay");
        ui.label("There is no listening history for this year yet.");
        if pill(ui, "Back to Library", None, false).clicked() { app.nav_back(); }
        return;
    };
    let mut pages = vec![Story::Minutes, Story::Songs];
    if !recap.top_artists.is_empty() { pages.push(Story::Artists); }
    if !recap.top_albums.is_empty() { pages.push(Story::Albums); }
    pages.push(Story::Summary);
    let index = pages.iter().position(|p| *p == page).unwrap_or(0);
    let year = crate::listening::year_label(now);

    ui.horizontal(|ui| {
        if pill(ui, "‹ Library", None, false).clicked() { app.nav_back(); }
        ui.label(RichText::new(format!("Replay · {year}")).font(theme::semibold(22.0)));
        if pill(ui, "Close", None, false).clicked() { app.nav_back(); }
    });
    ui.add_space(12.0);
    ui.horizontal_wrapped(|ui| {
        for p in &pages {
            let label = match p {
                Story::Minutes => "Minutes",
                Story::Songs => "Top songs",
                Story::Artists => "Top artists",
                Story::Albums => "Top albums",
                Story::Summary => "Summary",
            };
            if chip(ui, label, *p == page).clicked() { set_replay_page(app, *p); }
        }
    });
    ui.add_space(14.0);

    let (headline, detail, art) = match page {
        Story::Minutes => (
            format!("{} min", thousands(recap.ms / 60_000)),
            format!("{} plays · your listening in {year}", recap.plays),
            recap.top.first().and_then(|(_, e)| e.art.clone()),
        ),
        Story::Songs => (
            "Your most-listened songs".to_owned(),
            recap.top.first().map(|(_, e)| e.title.clone()).unwrap_or_default(),
            recap.top.first().and_then(|(_, e)| e.art.clone()),
        ),
        Story::Artists => (
            "Your top artists".to_owned(),
            recap.top_artist.clone().unwrap_or_default(),
            recap.top.first().and_then(|(_, e)| e.art.clone()),
        ),
        Story::Albums => (
            "Your top albums".to_owned(),
            recap.top_album.clone().unwrap_or_default(),
            recap.top.first().and_then(|(_, e)| e.art.clone()),
        ),
        Story::Summary => (
            format!("Your {year} in music"),
            format!("{} minutes · {} plays", thousands(recap.ms / 60_000), recap.plays),
            recap.top.first().and_then(|(_, e)| e.art.clone()),
        ),
    };
    egui::Frame::new()
        .fill(theme::surface_fill())
        .corner_radius(18)
        .inner_margin(egui::Margin::same(24))
        .show(ui, |ui| {
            ui.set_min_height(220.0);
            ui.horizontal(|ui| {
                let thumbs = art.map(|url| vec![Thumb { url, width: 544, height: 544 }]).unwrap_or_default();
                let (rect, _) = ui.allocate_exact_size(vec2(190.0, 190.0), egui::Sense::hover());
                widgets::paint_cover(ui, rect, &thumbs, 16.0, 1.0);
                ui.add_space(20.0);
                ui.vertical(|ui| {
                    ui.label(RichText::new(match page {
                        Story::Minutes => "MINUTES LISTENED",
                        Story::Songs => "TOP SONG",
                        Story::Artists => "TOP ARTIST",
                        Story::Albums => "TOP ALBUM",
                        Story::Summary => "NUVX REPLAY",
                    }).font(theme::semibold(12.0)).color(theme::text_dim()));
                    ui.add_space(10.0);
                    ui.label(RichText::new(headline).font(theme::bold(30.0)));
                    ui.add_space(8.0);
                    ui.label(RichText::new(detail).font(theme::regular(15.0)).color(theme::text_dim()));
                });
            });
        });
    ui.add_space(18.0);

    match page {
        Story::Minutes | Story::Summary => {
            ui.label(RichText::new("Your highlights").font(theme::semibold(18.0)));
            if let Some((_, song)) = recap.top.first() { ui.label(format!("Top song · {}", song.title)); }
            if let Some(artist) = &recap.top_artist { ui.label(format!("Top artist · {artist}")); }
            if let Some(album) = &recap.top_album { ui.label(format!("Top album · {album}")); }
        }
        Story::Songs => {
            ui.label(RichText::new("Most listened").font(theme::semibold(18.0)));
            for (index, (_, entry)) in recap.top.iter().take(10).enumerate() {
                let title = format!("{}. {} · {} plays", index + 1, entry.title, entry.plays);
                if ui.button(title).clicked() {
                    app.actions.push(Action::Play(recap.tracks(50), index));
                }
            }
        }
        Story::Artists => {
            ui.label(RichText::new("Listening time by artist").font(theme::semibold(18.0)));
            for (index, (artist, ms)) in recap.top_artists.iter().take(10).enumerate() {
                ui.label(format!("{}. {} · {} min", index + 1, artist, ms / 60_000));
            }
        }
        Story::Albums => {
            ui.label(RichText::new("Listening time by album").font(theme::semibold(18.0)));
            for (index, (album, ms)) in recap.top_albums.iter().take(10).enumerate() {
                ui.label(format!("{}. {} · {} min", index + 1, album, ms / 60_000));
            }
        }
    }
    ui.add_space(18.0);
    ui.horizontal(|ui| {
        if ui.add_enabled(index > 0, egui::Button::new("‹ Previous")).clicked() {
            set_replay_page(app, pages[index - 1]);
        }
        ui.label(format!("{} / {}", index + 1, pages.len()));
        if ui.add_enabled(index < pages.len() - 1, egui::Button::new("Next ›")).clicked() {
            set_replay_page(app, pages[index + 1]);
        }
    });
}

fn set_replay_page(app: &mut App, page: crate::listening::ReplayPage) {
    app.page = Page::Replay(page);
    app.page_loaded();
}

pub(super) fn downloads(app: &mut App, ui: &mut Ui, now: f64) {
    page_title(ui, "Downloads");
    let songs = crate::downloads::list();
    ui.add_space(4.0);
    ui.label(
        RichText::new(format!("{} songs \u{2022} {}", songs.len(), crate::downloads::folder().display()))
            .font(theme::regular(13.0))
            .color(theme::text_dim()),
    );
    ui.add_space(12.0);
    if songs.is_empty() {
        ui.label(RichText::new("No downloads yet").font(theme::semibold(16.0)).color(theme::text()));
        ui.add_space(4.0);
        ui.label(
            RichText::new("Right-click a song and choose Download to keep it on this computer, playable offline.")
                .font(theme::regular(13.0))
                .color(theme::text_dim()),
        );
        ui.add_space(10.0);
        if pill(ui, "Open folder", Some(icon::FOLDER_OPEN), false).clicked() {
            crate::downloads::open_folder();
        }
        return;
    }
    ui.horizontal(|ui| {
        if pill(ui, "Play", Some(icon::PLAY), true).clicked() {
            app.actions.push(Action::Play(songs.clone(), 0));
        }
        if pill(ui, "Shuffle", Some(icon::SHUFFLE), false).clicked() {
            let mut t = songs.clone();
            super::shuffle(&mut t);
            app.actions.push(Action::Play(t, 0));
        }
        if pill(ui, "Open folder", Some(icon::FOLDER_OPEN), false).clicked() {
            crate::downloads::open_folder();
        }
    });
    ui.add_space(12.0);
    let rows = Rows::new(app, now);
    list(ui, &rows, &app.liked, &songs, true, |_, _| None, ListCtx { sel: &mut app.selection, page: &app.page, own: false }, &mut app.actions);
}
