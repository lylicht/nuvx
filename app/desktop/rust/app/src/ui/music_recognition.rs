use super::*;

#[derive(Default)]
pub(super) struct RecognitionUi {
    open: bool,
    disclosure: bool,
    source: crate::recognition::AudioSource,
    system_playback_available: bool,
    session: Option<crate::recognition::Session>,
}

impl App {
    pub(super) fn open_recognition(&mut self) {
        self.recognition.system_playback_available =
            crate::recognition::system_playback_available();
        if self.recognition.session.is_none() {
            self.recognition.disclosure = true;
        }
        self.recognition.open = true;
    }

    fn recognition_capturing(&self) -> bool {
        self.recognition
            .session
            .as_ref()
            .is_some_and(crate::recognition::Session::capturing)
    }

    pub(super) fn draw_recognition(&mut self, ctx: &egui::Context) {
        if !self.recognition.open {
            return;
        }
        if self.recognition_capturing()
            && (!ctx.input(|input| input.focused)
                || ctx.input(|input| input.viewport().minimized == Some(true)))
        {
            self.close_recognition();
            return;
        }

        let status = self.recognition.session.as_ref().map(SessionStatus::from);
        let elapsed = self
            .recognition
            .session
            .as_ref()
            .map(crate::recognition::Session::elapsed)
            .unwrap_or_default();
        let active = matches!(
            status.as_ref(),
            Some(SessionStatus::Listening | SessionStatus::Matching)
        );
        if active {
            ctx.request_repaint_after(Duration::from_millis(50));
        }

        let accent = self.accent_color();
        let mut start = false;
        let mut retry = false;
        let mut cancel = false;
        let mut search = None::<String>;
        let mut selected_source = None;
        let modal = egui::Modal::new(egui::Id::unique("song-recognition"))
            .frame(notes::surface())
            .backdrop_color(theme::shade(0.62))
            .show(ctx, |ui| {
                ui.set_width(500.0_f32.min((ctx.content_rect().width() - 40.0).max(300.0)));
                egui::Frame::new()
                    .inner_margin(egui::Margin {
                        left: 30,
                        right: 30,
                        top: 28,
                        bottom: 24,
                    })
                    .show(ui, |ui| {
                        ui.spacing_mut().item_spacing.y = 0.0;
                        if self.recognition.disclosure {
                            ui.label(
                            egui::RichText::new(crate::ui::locale::text("Identify a song"))
                                    .font(theme::bold(22.0))
                                    .color(theme::text()),
                            );
                            ui.add_space(14.0);
                            for paragraph in [
                                "Song recognition uses Shazam. After you choose Start listening, VYRAL captures up to 10 seconds from the audio input selected below and creates an audio fingerprint on this device.",
                                "Only the fingerprint and ordinary request metadata, including your IP address, are sent to Shazam for matching. The raw recording is not uploaded or saved, and VYRAL does not request your location. Listening stops automatically; you can cancel at any time.",
                                "If you cancel after sending begins, VYRAL stops waiting, but cannot recall a fingerprint already received by Shazam.",
                                "Leaving the app window ends audio capture. Recognition never runs in the background.",
                            ] {
                                ui.label(
                                    egui::RichText::new(crate::ui::locale::text(paragraph))
                                        .font(theme::regular(13.5))
                                        .color(theme::text_dim()),
                                );
                                ui.add_space(10.0);
                            }
                            ui.label(
                                egui::RichText::new(crate::ui::locale::text("Listen using"))
                                    .font(theme::semibold(14.0))
                                    .color(theme::text()),
                            );
                            ui.add_space(8.0);
                            if source_option(
                                ui,
                                crate::recognition::AudioSource::Nearby,
                                self.recognition.source == crate::recognition::AudioSource::Nearby,
                                true,
                                "Nearby audio",
                                "Identify music audible around you using the microphone.",
                                accent,
                            ) {
                                selected_source = Some(crate::recognition::AudioSource::Nearby);
                            }
                            ui.add_space(6.0);
                            if source_option(
                                ui,
                                crate::recognition::AudioSource::SystemPlayback,
                                self.recognition.source == crate::recognition::AudioSource::SystemPlayback,
                                self.recognition.system_playback_available,
                                "Current-device playback",
                                if self.recognition.system_playback_available {
                                    "Identify audio playing through this device."
                                } else {
                                    "System playback capture is unavailable on this device."
                                },
                                accent,
                            ) {
                                selected_source = Some(crate::recognition::AudioSource::SystemPlayback);
                            }
                            ui.add_space(8.0);
                            ui.horizontal(|ui| {
                                if action_button(ui, "Start listening", true, accent) {
                                    start = true;
                                }
                                ui.add_space(8.0);
                                if action_button(ui, "Cancel", false, accent) {
                                    cancel = true;
                                }
                            });
                        } else {
                            match status.as_ref().unwrap_or(&SessionStatus::Failed(
                                "Recognition is not running.".into(),
                            )) {
                                SessionStatus::Listening => {
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text("Listening…"))
                                            .font(theme::bold(22.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(24.0);
                                    ui.vertical_centered(|ui| {
                                        let center = ui.cursor().center();
                                        crate::ui::glyphs::draw_pulsing(
                                            ui,
                                            center,
                                            54.0,
                                            crate::ui::glyphs::WAVEFORM,
                                            accent,
                                            egui::Id::unique("song-recognition-waveform"),
                                            true,
                                        );
                                        ui.allocate_space(egui::vec2(0.0, 68.0));
                                        let progress = (elapsed.as_secs_f32()
                                            / crate::recognition::MAX_CAPTURE.as_secs_f32())
                                            .clamp(0.0, 1.0);
                                        ui.add(
                                            egui::ProgressBar::new(progress)
                                                .desired_width(280.0)
                                                .fill(accent),
                                        );
                                    });
                                    ui.add_space(16.0);
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text(
                                            if self.recognition.source == crate::recognition::AudioSource::SystemPlayback {
                                                "Listening to this device's playback. Stops after 10 seconds."
                                            } else {
                                                "Listening through the microphone for nearby music. Stops after 10 seconds."
                                            },
                                        ))
                                        .font(theme::regular(13.5))
                                        .color(theme::text_dim()),
                                    );
                                    ui.add_space(20.0);
                                    if action_button(ui, "Cancel", false, accent) {
                                        cancel = true;
                                    }
                                }
                                SessionStatus::Matching => {
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text("Matching song"))
                                            .font(theme::bold(22.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(18.0);
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text(
                                            "Sending the fingerprint to Shazam…",
                                        ))
                                        .font(theme::regular(14.0))
                                        .color(theme::text_dim()),
                                    );
                                    ui.add_space(22.0);
                                    if action_button(ui, "Cancel", false, accent) {
                                        cancel = true;
                                    }
                                }
                                SessionStatus::Match { title, artist } => {
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text("Song found"))
                                            .font(theme::bold(22.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(18.0);
                                    ui.label(
                                        egui::RichText::new(title)
                                            .font(theme::semibold(18.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(4.0);
                                    ui.label(
                                        egui::RichText::new(artist)
                                            .font(theme::regular(14.0))
                                            .color(theme::text_dim()),
                                    );
                                    ui.add_space(22.0);
                                    ui.horizontal(|ui| {
                                        if action_button(ui, "Search in VYRAL", true, accent) {
                                            search = Some(format!("{title} {artist}"));
                                        }
                                        ui.add_space(8.0);
                                        if action_button(ui, "Try again", false, accent) {
                                            retry = true;
                                        }
                                    });
                                }
                                SessionStatus::NoMatch => {
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text("No song found"))
                                            .font(theme::bold(22.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(10.0);
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text(
                                            "Try again with the music closer to the microphone.",
                                        ))
                                        .font(theme::regular(13.5))
                                        .color(theme::text_dim()),
                                    );
                                    ui.add_space(22.0);
                                    ui.horizontal(|ui| {
                                        if action_button(ui, "Try again", true, accent) {
                                            retry = true;
                                        }
                                        ui.add_space(8.0);
                                        if action_button(ui, "Close", false, accent) {
                                            cancel = true;
                                        }
                                    });
                                }
                                SessionStatus::Failed(error) => {
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text("Recognition unavailable"))
                                            .font(theme::bold(22.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(10.0);
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text(error))
                                            .font(theme::regular(13.5))
                                            .color(theme::text_dim()),
                                    );
                                    ui.add_space(22.0);
                                    ui.horizontal(|ui| {
                                        if action_button(ui, "Try again", true, accent) {
                                            retry = true;
                                        }
                                        ui.add_space(8.0);
                                        if action_button(ui, "Close", false, accent) {
                                            cancel = true;
                                        }
                                    });
                                }
                                SessionStatus::Cancelled => {
                                    ui.label(
                                        egui::RichText::new(crate::ui::locale::text("Recognition cancelled"))
                                            .font(theme::bold(20.0))
                                            .color(theme::text()),
                                    );
                                    ui.add_space(18.0);
                                    if action_button(ui, "Close", true, accent) {
                                        cancel = true;
                                    }
                                }
                            }
                        }
                    });
            });

        let dismissed =
            modal.should_close() || ctx.input(|input| input.key_pressed(egui::Key::Escape));
        if let Some(query) = search {
            self.actions.push(Action::Go(Page::Search(query)));
            self.close_recognition();
        } else if cancel || dismissed {
            self.close_recognition();
        } else if let Some(source) = selected_source {
            self.recognition.source = source;
        } else if start || retry {
            if let Some(session) = self.recognition.session.take() {
                session.cancel();
            }
            self.recognition.disclosure = false;
            self.recognition.session = Some(crate::recognition::Session::start(
                &self.backend,
                self.recognition.source,
            ));
        }
    }

    fn close_recognition(&mut self) {
        if let Some(session) = self.recognition.session.take() {
            session.cancel();
        }
        self.recognition.open = false;
        self.recognition.disclosure = false;
    }
}

fn source_option(
    ui: &mut Ui,
    source: crate::recognition::AudioSource,
    selected: bool,
    available: bool,
    title: &str,
    description: &str,
    accent: Color32,
) -> bool {
    let width = ui.available_width();
    let sense = if available {
        egui::Sense::click()
    } else {
        egui::Sense::hover()
    };
    let (rect, response) = ui.allocate_exact_size(egui::vec2(width, 56.0), sense);
    let label = crate::ui::locale::text(title);
    let response = widgets::named(response, label).on_hover_cursor(if available {
        egui::CursorIcon::PointingHand
    } else {
        egui::CursorIcon::Default
    });
    let hover = theme::anim_bool(
        ui.ctx(),
        response.id.with("hover"),
        response.hovered(),
        0.14,
    );
    let selected_t = theme::anim_bool(ui.ctx(), response.id.with("selected"), selected, 0.16);
    let fill = theme::lerp_color(
        theme::glass(0.06 + 0.035 * hover),
        theme::with_alpha(accent, 0.18),
        selected_t,
    );
    ui.painter().rect_filled(rect, 8.0, fill);
    ui.painter().rect_stroke(
        rect,
        8.0,
        egui::Stroke::new(
            if selected { 1.2 } else { 0.7 },
            if selected {
                theme::with_alpha(accent, 0.72)
            } else {
                theme::glass(0.20)
            },
        ),
        egui::StrokeKind::Inside,
    );
    let foreground = if available {
        theme::text()
    } else {
        theme::text_dim()
    };
    let glyph = match source {
        crate::recognition::AudioSource::Nearby => glyphs::WAVEFORM_BADGE_MICROPHONE,
        crate::recognition::AudioSource::SystemPlayback => glyphs::WAVEFORM,
    };
    let rtl = crate::ui::locale::is_rtl();
    let icon_x = if rtl {
        rect.right() - 26.0
    } else {
        rect.left() + 26.0
    };
    glyphs::draw_button(
        ui,
        egui::Rect::from_center_size(egui::pos2(icon_x, rect.center().y), egui::Vec2::splat(22.0)),
        glyph,
        if available { accent } else { theme::text_dim() },
        &response,
    );
    let title_galley = widgets::galley(
        ui,
        label,
        theme::semibold(13.5),
        foreground,
        (rect.width() - 62.0).max(1.0),
    );
    let description_galley = widgets::galley(
        ui,
        crate::ui::locale::text(description),
        theme::regular(11.5),
        theme::text_dim(),
        (rect.width() - 62.0).max(1.0),
    );
    let x = if rtl {
        rect.right() - 48.0 - title_galley.size().x
    } else {
        rect.left() + 48.0
    };
    let title_y = rect.center().y - title_galley.size().y / 2.0 - 6.0;
    ui.painter()
        .galley(egui::pos2(x, title_y), title_galley, foreground);
    let description_x = if rtl {
        rect.right() - 48.0 - description_galley.size().x
    } else {
        rect.left() + 48.0
    };
    ui.painter().galley(
        egui::pos2(description_x, title_y + 19.0),
        description_galley,
        theme::text_dim(),
    );
    available && response.clicked()
}

enum SessionStatus {
    Listening,
    Matching,
    Match { title: String, artist: String },
    NoMatch,
    Failed(String),
    Cancelled,
}

impl From<&crate::recognition::Session> for SessionStatus {
    fn from(session: &crate::recognition::Session) -> Self {
        match session.status() {
            crate::recognition::Status::Listening => Self::Listening,
            crate::recognition::Status::Matching => Self::Matching,
            crate::recognition::Status::Match { title, artist } => Self::Match { title, artist },
            crate::recognition::Status::NoMatch => Self::NoMatch,
            crate::recognition::Status::Failed(error) => Self::Failed(error),
            crate::recognition::Status::Cancelled => Self::Cancelled,
        }
    }
}

fn action_button(ui: &mut Ui, label: &str, primary: bool, accent: Color32) -> bool {
    let text = egui::RichText::new(crate::ui::locale::text(label))
        .font(theme::semibold(14.0))
        .color(if primary {
            Color32::WHITE
        } else {
            theme::text()
        });
    let mut button = egui::Button::new(text).min_size(egui::vec2(118.0, 40.0));
    if primary {
        button = button.fill(accent);
    }
    ui.add(button).clicked()
}
