use super::{App, LIKED_MUSIC, LibTab, Page};
use crate::backend::Req;
use crate::theme;

impl App {
    pub(super) fn go(&mut self, page: Page) {
        self.np_open = false;
        if page == self.page {
            self.refresh();
            return;
        }
        self.playlist_transition = None;
        self.apple_motion.clear();
        let prev = std::mem::replace(&mut self.page, page);
        self.back.push(prev);
        self.forward.clear();
        self.page_loaded();
    }

    pub(super) fn open_playlist_from_artwork(&mut self, id: String, origin: super::ArtworkOrigin) {
        let page = Page::Playlist(id.clone());
        let source_page = self.page.clone();
        let animated = theme::speed() > 0.0 && !origin.thumbs.is_empty() && page != self.page;
        self.go(page);
        if animated {
            self.playlist_transition = Some(super::collection_transition::PlaylistTransition {
                id,
                source_page,
                source_rect: origin.rect,
                source_radius: origin.radius,
                thumbs: std::sync::Arc::from(origin.thumbs),
                destination_rect: None,
                destination_radius: None,
                reverse: false,
            });
        }
    }

    pub(super) fn nav_back(&mut self) {
        if self.np_open {
            self.np_open = false;
        } else if let Some(p) = self.back.pop() {
            let reverse = self.playlist_transition.as_ref().is_some_and(|transition| {
                !transition.reverse
                    && self.page == Page::Playlist(transition.id.clone())
                    && p == transition.source_page
                    && transition.destination_rect.is_some()
            });
            if reverse {
                if let Some(transition) = &mut self.playlist_transition {
                    transition.reverse = true;
                }
            } else {
                self.playlist_transition = None;
            }
            self.apple_motion.clear();
            self.forward.push(std::mem::replace(&mut self.page, p));
            self.page_loaded();
        }
    }

    pub(super) fn nav_forward(&mut self) {
        if let Some(p) = self.forward.pop() {
            self.playlist_transition = None;
            self.apple_motion.clear();
            self.back.push(std::mem::replace(&mut self.page, p));
            self.page_loaded();
        }
    }

    pub(super) fn refresh(&mut self) {
        self.playlist_transition = None;
        let changed = self.page_changed;
        self.page_loaded();
        self.page_changed = changed;
    }

    pub(super) fn page_loaded(&mut self) {
        self.page_changed = -1.0;
        self.search_synced = match &self.page {
            Page::Search(q) => q.clone(),
            _ => String::new(),
        };
        self.search_text = self.search_synced.clone();
        self.suggestions = Default::default();
        self.suggest_sel = None;
        if !matches!(self.page, Page::Search(_)) {
            self.xfer.picking = None;
        }
        if let Some(id) = self.page_list().map(str::to_owned) {
            self.lists.move_to_front(&id);
        }
        let tiles =
            matches!(&self.page, Page::Search(q) if q.trim().is_empty() && !self.classic_search);
        if (self.page == Page::New || tiles) && self.moods.is_none() && !self.moods_asked {
            self.moods_asked = true;
            self.backend.request(Req::Moods);
        }
        let req = match &self.page.clone() {
            Page::Mood {
                browse_id, params, ..
            } if self
                .mood
                .as_ref()
                .is_none_or(|m| &m.browse_id != browse_id || &m.params != params) =>
            {
                Some(Req::Mood {
                    browse_id: browse_id.clone(),
                    params: params.clone(),
                })
            }
            Page::Home if !self.home_loading => {
                self.home_loading = true;
                Some(Req::Home {
                    params: None,
                    hide_explicit: self.hide_explicit,
                    focused: self.focused_feed,
                })
            }
            Page::New => Some(Req::Explore(self.preferred_artist_ids())),
            Page::Search(q)
                if !q.trim().is_empty() && self.search.as_ref().is_none_or(|s| &s.query != q) =>
            {
                Some(Req::Search(q.clone()))
            }
            Page::Album(id) | Page::Playlist(id) => {
                self.visit_list(id, matches!(self.page, Page::Album(_)));
                None
            }
            Page::Artist(id) => Some(Req::Artist(id.clone())),
            Page::Library(LibTab::History) if self.logged_in => Some(Req::History),
            Page::Library(LibTab::Uploads) if self.logged_in => Some(Req::Uploads),
            Page::Library(LibTab::Songs) => {
                if self.logged_in {
                    self.visit_list(LIKED_MUSIC, false);
                }
                None
            }
            Page::Library(_) if self.logged_in => Some(Req::Library),
            _ => None,
        };
        let cached = match &self.page {
            Page::Home => self.home.is_some(),
            Page::New => self.explore.is_some(),
            Page::Album(id) | Page::Playlist(id) => self.opened(id).is_some(),
            Page::Artist(id) => self.artist.as_ref().is_some_and(|a| &a.id == id),
            Page::Library(LibTab::History) => self.history.is_some(),
            Page::Library(LibTab::Uploads) => self.uploads.is_some(),
            Page::Library(LibTab::Songs) => self.opened(LIKED_MUSIC).is_some(),
            Page::Library(_) => self.library.is_some(),
            _ => false,
        };
        let coming = self.page_list().is_some_and(|id| self.lists.loading(id));
        self.loading = (req.is_some() || coming) && !cached;
        self.load_failed = false;
        match req {
            Some(Req::Library) => self.request_library(),
            Some(r) => self.backend.request(r),
            None => {}
        }
    }

    pub(super) fn page_list(&self) -> Option<&str> {
        match &self.page {
            Page::Album(id) | Page::Playlist(id) => Some(id),
            Page::Library(LibTab::Songs) => Some(LIKED_MUSIC),
            _ => None,
        }
    }
}
