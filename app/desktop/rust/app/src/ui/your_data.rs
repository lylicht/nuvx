//! Settings → Your data: what vyral keeps on this computer, what leaves it and when, and the controls for it
//! (the desktop side of the master prompt's Privacy & Data Center, like Android's Settings → Your data).

use egui::Ui;

use super::pages::{section_label, setting_row, settings_card};
use super::widgets::pill;
use super::{Action, App, Page, SettingsPane};

pub(super) fn pane(app: &mut App, ui: &mut Ui) {
    section_label(ui, "On this computer", None);
    settings_card(ui, |ui| {
        setting_row(
            ui,
            "Stays here",
            Some(
                "Playlists made in vyral, downloads, listening history, searches, settings and the keys of paired phones are stored on this computer only. Your YouTube Music sign-in is kept in the system's credential store.",
            ),
            |_| {},
        );
        let mut open = false;
        setting_row(
            ui,
            "Data folder",
            Some("Where vyral keeps its files. Back it up to move everything to another computer."),
            |ui| {
                open = pill(ui, "Open", None, false).clicked();
            },
        );
        if open {
            crate::downloads::open_dir(app.backend.data_dir());
        }
        let n = app.local_playlists.len();
        let desc = format!(
            "Your {n} playlist{} as a file the phone's backup can read, and the other way round. Importing adds playlists you don't have yet.",
            if n == 1 { "" } else { "s" }
        );
        let (mut export, mut import) = (false, false);
        setting_row(ui, "Backup", Some(&desc), |ui| {
            import = pill(ui, "Import…", None, false).clicked();
            export = pill(ui, "Export…", None, false).clicked();
        });
        if export {
            export_backup(app);
        }
        if import {
            import_backup(app);
        }
        let mut import_kmp = false;
        setting_row(
            ui,
            "KMP desktop playlists",
            Some(
                "In KMP, open Settings → Library and export for Rust desktop. Import that JSON here. Existing playlists are not replaced; local files, network sources, accounts and settings are not imported.",
            ),
            |ui| {
                import_kmp = pill(ui, "Import KMP playlists…", None, false).clicked();
            },
        );
        if import_kmp {
            import_backup(app);
        }
        let spotify_connected = crate::transfer::saved_cookie(app.backend.data_dir()).is_some();
        let mut prepare_spotify = false;
        let mut connect_spotify = false;
        setting_row(
            ui,
            "Spotify export",
            Some(if spotify_connected {
                "Review YouTube Music playlists, this device's playlists and Replay before exporting matched songs to your connected Spotify account."
            } else {
                "Connect Spotify in Settings → Import to preview and export your playlists and Replay."
            }),
            |ui| {
                if spotify_connected {
                    ui.add_enabled_ui(!app.spotify_export_busy, |ui| {
                        prepare_spotify = pill(ui, "Prepare preview…", None, false).clicked();
                    });
                } else {
                    connect_spotify = pill(ui, "Connect Spotify", None, false).clicked();
                }
            },
        );
        if connect_spotify {
            app.actions
                .push(Action::Go(Page::Setting(SettingsPane::Import)));
        }
        if prepare_spotify {
            let mut sources: Vec<_> = app
                .local_playlists
                .iter()
                .map(|playlist| crate::transfer::spotify::ExportSource {
                    name: format!("VYRAL · {}", playlist.title),
                    tracks: playlist.tracks.clone(),
                })
                .collect();
            sources.extend(
                crate::listening::export_by_month()
                    .into_iter()
                    .map(|(name, tracks)| crate::transfer::spotify::ExportSource { name, tracks }),
            );
            app.spotify_export_busy = true;
            app.spotify_export_preview = None;
            app.backend
                .request(crate::backend::Req::SpotifyExportPrepare { sources });
        }
        if app.spotify_export_busy {
            ui.add_space(6.0);
            ui.label("Preparing your Spotify export preview…");
        }
        if let Some(preview) = app.spotify_export_preview.clone() {
            ui.add_space(8.0);
            settings_card(ui, |ui| {
                ui.label(format!(
                    "Preview · {} matched tracks · {} unmatched tracks",
                    preview.matched_tracks(),
                    preview.unmatched_tracks
                ));
                ui.add_space(5.0);
                for playlist in preview.playlists.iter().take(12) {
                    ui.label(format!(
                        "{} · {} matches",
                        playlist.name,
                        playlist.track_ids.len()
                    ));
                }
                if preview.playlists.len() > 12 {
                    ui.label(format!(
                        "And {} more playlists",
                        preview.playlists.len() - 12
                    ));
                }
                if !preview.liked_track_ids.is_empty() {
                    ui.label(format!(
                        "YouTube Music Liked Music · {} matches",
                        preview.liked_track_ids.len()
                    ));
                }
                if !preview.warnings.is_empty() {
                    ui.add_space(4.0);
                    ui.label(format!(
                        "{} source/search warning(s):",
                        preview.warnings.len()
                    ));
                    for warning in preview.warnings.iter().take(4) {
                        ui.label(warning);
                    }
                }
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    ui.add_enabled_ui(!app.spotify_export_busy, |ui| {
                        if pill(
                            ui,
                            &format!("Export {} matches", preview.matched_tracks()),
                            None,
                            true,
                        )
                        .clicked()
                        {
                            app.spotify_export_busy = true;
                            app.backend
                                .request(crate::backend::Req::SpotifyExportCommit {
                                    preview: preview.clone(),
                                });
                        }
                        if pill(ui, "Cancel", None, false).clicked() {
                            app.spotify_export_preview = None;
                        }
                    });
                });
            });
        }
        let n = app.recent_searches.len();
        let mut clear = false;
        let desc = if n == 0 {
            "None saved.".to_owned()
        } else {
            format!("{n} saved on this computer.")
        };
        setting_row(ui, "Recent searches", Some(&desc), |ui| {
            ui.add_enabled_ui(n > 0, |ui| clear = pill(ui, "Clear", None, false).clicked());
        });
        if clear {
            app.recent_searches.clear();
            app.toast("Recent searches cleared");
        }
    });
    ui.add_space(18.0);
    section_label(ui, "What leaves it, and when", None);
    settings_card(ui, |ui| {
        for (what, when) in [
            (
                "YouTube Music",
                "Searches, playback and your account, when you use them.",
            ),
            (
                "Listen Together",
                "The shared room, through vyral's relay, only while you are in one.",
            ),
            (
                "Discord and Last.fm",
                "What you play, only if you connect them in Account & integrations.",
            ),
            (
                "Sync & Connect",
                "Playlists and playback, only to phones you paired, on your network, encrypted.",
            ),
            ("Updates", "vyral asks GitHub for the latest release."),
        ] {
            setting_row(ui, what, Some(when), |_| {});
        }
        let paired = app.lan.paired_count();
        let mut manage = false;
        let desc = match paired {
            0 => "No phones paired.".to_owned(),
            1 => "1 phone paired.".to_owned(),
            n => format!("{n} phones paired."),
        };
        setting_row(ui, "Paired devices", Some(&desc), |ui| {
            manage = pill(ui, "Manage", None, false).clicked();
        });
        if manage {
            app.actions
                .push(Action::Go(Page::Setting(SettingsPane::Sync)));
        }
        let mut copy = false;
        setting_row(
            ui,
            "Copy diagnostics",
            Some(
                "For a bug report: version, system, network mode and this run's latest warnings and errors. No account, song history, addresses or folder names. Copied only when you press it; nothing is sent.",
            ),
            |ui| {
                copy = pill(ui, "Copy", None, false).clicked();
            },
        );
        if copy {
            let facts = [
                ("Version", crate::update::current_version().to_string()),
                (
                    "System",
                    format!("{} {}", std::env::consts::OS, std::env::consts::ARCH),
                ),
                ("Renderer", format!("{:?}", crate::renderer::active())),
                ("Network", app.proxy_saved.label().to_owned()),
                (
                    "Signed in",
                    if app.logged_in { "yes" } else { "no" }.to_owned(),
                ),
                // Sync and output state, as counts and yes/no only: no device names, which can identify a person.
                (
                    "Local Wi-Fi Sync",
                    if app.lan.enabled {
                        format!("on, {} paired", app.lan.paired_count())
                    } else {
                        "off".to_owned()
                    },
                ),
                (
                    "Remote control",
                    if app.lan.connect { "allowed" } else { "off" }.to_owned(),
                ),
                (
                    "Output",
                    if app.output_device.is_some() {
                        "chosen device"
                    } else {
                        "system default"
                    }
                    .to_owned(),
                ),
                ("Equalizer", if app.eq.on { "on" } else { "off" }.to_owned()),
                (
                    "Download limit",
                    match crate::downloads::limit_gb() {
                        0 => "off".to_owned(),
                        n => format!("{n} GB"),
                    },
                ),
            ];
            ui.ctx()
                .copy_text(crate::logging::diagnostics(app.backend.data_dir(), &facts));
            app.toast("Diagnostics copied");
        }
    });
    ui.add_space(18.0);
    ui.collapsing(super::locale::text("Symbol attribution"), |ui| {
        ui.label(include_str!("../../assets/licenses/Jetpack-SF-Symbols.txt"));
        ui.hyperlink("https://github.com/cosmictaserdev-creator/SF_Symbol_Catalog");
    });
}

fn export_backup(app: &mut App) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    let stamp = vyral_core::backup::iso_utc(now);
    let name = format!("vyral-backup-{}.json", &stamp[..10]);
    let Some(path) = rfd::FileDialog::new()
        .set_title("Export backup")
        .set_file_name(name)
        .add_filter("vyral backup", &["json"])
        .save_file()
    else {
        return;
    };
    let lists: Vec<_> = app
        .local_playlists
        .iter()
        .map(|p| vyral_core::backup::Playlist {
            id: p.id.clone(),
            title: p.title.clone(),
            tracks: p.tracks.clone(),
        })
        .collect();
    let text = vyral_core::backup::export(
        &lists,
        &crate::update::current_version().to_string(),
        &stamp,
    );
    match std::fs::write(&path, text) {
        Ok(()) => app.toast("Backup saved"),
        Err(e) => app.toast(&format!("Couldn't save the backup: {e}")),
    }
}

fn import_backup(app: &mut App) {
    let Some(path) = rfd::FileDialog::new()
        .set_title("Import backup")
        .add_filter("vyral backup", &["json"])
        .pick_file()
    else {
        return;
    };
    // Read at most one byte over the limit so a huge file is refused without loading it all.
    let mut bytes = Vec::new();
    let read = std::fs::File::open(&path).and_then(|f| {
        std::io::Read::read_to_end(
            &mut std::io::Read::take(f, vyral_core::backup::MAX_BYTES as u64 + 1),
            &mut bytes,
        )
    });
    if let Err(e) = read {
        return app.toast(&format!("Couldn't read that file: {e}"));
    }
    let lists = match vyral_core::backup::import(&String::from_utf8_lossy(&bytes)) {
        Ok(lists) => lists,
        Err(why) => return app.toast(&why),
    };
    let mut added = 0;
    for list in lists {
        if app.local_playlists.iter().any(|p| p.id == list.id) {
            continue;
        }
        app.local_playlists.push(super::LocalPlaylist {
            id: list.id.clone(),
            title: list.title,
            tracks: list.tracks,
            artwork: None,
        });
        app.refresh_local_playlist(&list.id);
        added += 1;
    }
    app.toast(&match added {
        0 => "Nothing new in that backup".to_owned(),
        1 => "Added 1 playlist".to_owned(),
        n => format!("Added {n} playlists"),
    });
}
