use std::path::PathBuf;

use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::platform::run_return::EventLoopExtRunReturn;
use tao::window::WindowBuilder;
use wry::{PageLoadEvent, WebContext, WebViewBuilder};

#[derive(Clone, Copy)]
pub enum Site {
    Google,
    Spotify,
}

impl Site {
    fn flag(self) -> &'static str {
        match self {
            Site::Google => FLAG,
            Site::Spotify => SPOTIFY_FLAG,
        }
    }

    fn title(self) -> &'static str {
        match self {
            Site::Google => "Sign in to YouTube Music",
            Site::Spotify => "Sign in to Spotify",
        }
    }

    fn start(self) -> &'static str {
        match self {
            Site::Google => "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F",
            Site::Spotify => "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
        }
    }

    fn home(self) -> &'static str {
        match self {
            Site::Google => "https://music.youtube.com/",
            Site::Spotify => "https://open.spotify.com/",
        }
    }

    fn session_cookie(self) -> &'static str {
        match self {
            Site::Google => "SAPISID",
            Site::Spotify => "sp_dc",
        }
    }
}

pub enum Outcome {
    Cookie(String),
    Cancelled,
    Failed(String),
}

#[cfg(target_os = "macos")]
const SAFARI_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

pub const FLAG: &str = "--login";
pub const SPOTIFY_FLAG: &str = "--login-spotify";

pub fn spawn(site: Site, profile_dir: PathBuf, done: impl FnOnce(Outcome) + Send + 'static) {
    std::thread::Builder::new()
        .name("login".into())
        .spawn(move || {
            let res = std::env::current_exe().and_then(|exe| {
                let mut cmd = std::process::Command::new(exe);
                crate::proxy::pass_to(&mut cmd);
                cmd.arg(site.flag())
                    .arg(&profile_dir)
                    .stdin(std::process::Stdio::null())
                    .stderr(std::process::Stdio::null())
                    .output()
            });
            done(match res {
                Err(e) => Outcome::Failed(format!("could not start sign-in window: {e}")),
                Ok(out) => {
                    let text = String::from_utf8_lossy(&out.stdout);
                    let line = text.lines().last().unwrap_or_default();
                    if let Some(c) = line.strip_prefix("cookie ") {
                        Outcome::Cookie(c.to_owned())
                    } else if let Some(e) = line.strip_prefix("failed ") {
                        Outcome::Failed(e.to_owned())
                    } else if line == "cancelled" {
                        Outcome::Cancelled
                    } else {
                        Outcome::Failed(format!("sign-in window exited unexpectedly ({})", out.status))
                    }
                }
            })
        })
        .expect("spawn login thread");
}

pub fn child_main(site: Site, profile_dir: PathBuf) {
    match run(site, profile_dir) {
        Ok(Outcome::Cookie(c)) => println!("cookie {c}"),
        Ok(Outcome::Cancelled) => println!("cancelled"),
        Ok(Outcome::Failed(e)) => println!("failed {e}"),
        Err(e) => println!("failed {e:#}"),
    }
}

pub trait BuildIn<'a> {
    fn build_in(self, window: &'a tao::window::Window) -> wry::Result<wry::WebView>;
}

impl<'a> BuildIn<'a> for WebViewBuilder<'a> {
    fn build_in(self, window: &'a tao::window::Window) -> wry::Result<wry::WebView> {
        #[cfg(target_os = "linux")]
        {
            use tao::platform::unix::WindowExtUnix;
            use wry::WebViewBuilderExtUnix;
            self.build_gtk(window.default_vbox().expect("tao makes a vbox by default"))
        }
        #[cfg(not(target_os = "linux"))]
        self.build(window)
    }
}

#[cfg(not(target_os = "macos"))]
fn cookies_for(webview: &wry::WebView, url: &str) -> wry::Result<Vec<wry::cookie::Cookie<'static>>> {
    webview.cookies_for_url(url)
}

#[cfg(target_os = "macos")]
fn cookies_for(webview: &wry::WebView, url: &str) -> wry::Result<Vec<wry::cookie::Cookie<'static>>> {
    let host = url::Url::parse(url).ok().and_then(|u| u.host_str().map(str::to_owned)).unwrap_or_default();
    Ok(webview.cookies()?.into_iter().filter(|c| c.domain().is_some_and(|d| domain_matches(&host, d))).collect())
}

#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn domain_matches(host: &str, domain: &str) -> bool {
    let domain = domain.trim_start_matches('.').to_ascii_lowercase();
    let host = host.to_ascii_lowercase();
    !domain.is_empty() && (host == domain || host.ends_with(&format!(".{domain}")))
}

pub fn forget(profile_dir: &std::path::Path) {
    for tries in 1..=10 {
        match std::fs::remove_dir_all(profile_dir) {
            Ok(()) => return,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return,
            Err(e) if tries == 10 => log::warn!("couldn't delete the sign-in window's profile {}: {e}", profile_dir.display()),
            Err(_) => std::thread::sleep(std::time::Duration::from_millis(500)),
        }
    }
}

#[cfg(target_os = "macos")]
fn store_id(profile_dir: &std::path::Path) -> [u8; 16] {
    use sha1::Digest;
    let file = profile_dir.join("store-id");
    if let Ok(saved) = std::fs::read(&file)
        && let Ok(id) = <[u8; 16]>::try_from(saved.as_slice())
    {
        return id;
    }
    let seed = format!("{} {:?} {}", profile_dir.display(), std::time::SystemTime::now(), std::process::id());
    let mut id = [0u8; 16];
    id.copy_from_slice(&sha1::Sha1::digest(seed)[..16]);
    let _ = std::fs::create_dir_all(profile_dir);
    let _ = std::fs::write(&file, id);
    id
}


enum LoginEvent {
    HomeLoaded,
    Verified { cookie: String, result: Result<(), String> },
}

/// Include both music.youtube.com and www.youtube.com cookies. A session can
/// have host-specific cookies on either origin in addition to .youtube.com ones.
fn login_cookies(webview: &wry::WebView, site: Site) -> wry::Result<Vec<(String, String)>> {
    let urls: &[&str] = match site {
        Site::Google => &["https://music.youtube.com/", "https://www.youtube.com/"],
        Site::Spotify => &["https://open.spotify.com/"],
    };
    let mut collected = Vec::new();
    for url in urls {
        for cookie in cookies_for(webview, url)? {
            if !collected.iter().any(|(name, _): &(String, String)| name == cookie.name()) {
                collected.push((cookie.name().to_owned(), cookie.value().to_owned()));
            }
        }
    }
    Ok(collected)
}

/// The landing page alone never proves that an authenticated session was
/// captured. In particular, a saved WebKitGTK profile can revisit the home
/// page with expired cookies. Do not close the sign-in window on that alone.
fn candidate_cookie(site: Site, cookies: &[(String, String)]) -> Option<String> {
    match site {
        Site::Google => {
            let signed = ["SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID"]
                .iter()
                .any(|key| cookies.iter().any(|(name, value)| name == key && !value.is_empty()));
            signed.then(|| cookies.iter()
                .filter(|(_, value)| !value.is_empty())
                .map(|(name, value)| format!("{name}={value}"))
                .collect::<Vec<_>>()
                .join("; "))
        }
        Site::Spotify => cookies.iter()
            .find(|(name, value)| name == "sp_dc" && !value.is_empty())
            .map(|(_, value)| value.clone()),
    }
}

/// Validate in a worker thread: blocking network I/O on the WebKit/tao event
/// loop freezes the login page, especially on slower Linux connections.
fn verify_cookie(site: Site, cookie: &str, profile_dir: &std::path::Path) -> Result<(), String> {
    let data_dir = profile_dir.parent().unwrap_or(profile_dir);
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("couldn't start account check: {e}"))?;
    rt.block_on(async {
        match site {
            Site::Google => crate::ytmapi::Api::new(data_dir)
                .session_from_cookie(cookie)
                .await
                .map(|_| ()),
            Site::Spotify => crate::transfer::spotify::Client::connect(Some(cookie), data_dir)
                .await
                .map(|_| ()),
        }
    })
    .map_err(|e| format!("{e:#}"))
}

fn run(site: Site, profile_dir: PathBuf) -> anyhow::Result<Outcome> {
    let mut event_loop = EventLoopBuilder::<LoginEvent>::with_user_event().build();

    let window = WindowBuilder::new()
        .with_title(site.title())
        .with_inner_size(tao::dpi::LogicalSize::new(480.0, 720.0))
        .build(&event_loop)?;

    let proxy = event_loop.create_proxy();
    let on_load = proxy.clone();
    #[cfg(target_os = "macos")]
    let store = store_id(&profile_dir);
    let mut context = WebContext::new(Some(profile_dir.clone()));
    let builder = WebViewBuilder::new_with_web_context(&mut context);
    #[cfg(target_os = "macos")]
    let builder = {
        use wry::WebViewBuilderExtDarwin;
        builder.with_data_store_identifier(store).with_user_agent(SAFARI_UA)
    };
    let webview = crate::proxy::webview(builder, matches!(site, Site::Google))
        .with_url(site.start())
        .with_on_page_load_handler(move |ev, url| {
            if matches!(ev, PageLoadEvent::Finished) && url.starts_with(site.home()) {
                let _ = on_load.send_event(LoginEvent::HomeLoaded);
            }
        })
        .build_in(&window)?;

    let mut outcome = Outcome::Cancelled;
    let mut pending: Option<String> = None;
    let mut last_error: Option<String> = None;

    event_loop.run_return(|event, _, flow| {
        *flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(LoginEvent::HomeLoaded) if pending.is_none() => {
                match login_cookies(&webview, site) {
                    Ok(cookies) => {
                        if let Some(cookie) = candidate_cookie(site, &cookies) {
                            window.set_title(&format!("{} — verifying account…", site.title()));
                            pending = Some(cookie.clone());
                            let result_proxy = proxy.clone();
                            let profile_dir = profile_dir.clone();
                            std::thread::spawn(move || {
                                let result = verify_cookie(site, &cookie, &profile_dir);
                                let _ = result_proxy.send_event(LoginEvent::Verified { cookie, result });
                            });
                        }
                    }
                    Err(e) => {
                        last_error = Some(format!("couldn't read the sign-in cookies: {e}"));
                        window.set_title("Sign-in cookies unavailable — try again");
                    }
                }
            }
            Event::UserEvent(LoginEvent::Verified { cookie, result })
                if pending.as_deref() == Some(cookie.as_str()) =>
            {
                pending = None;
                match result {
                    Ok(()) => {
                        outcome = Outcome::Cookie(cookie);
                        *flow = ControlFlow::Exit;
                    }
                    Err(e) => {
                        // Keep the browser visible so the user can retry. If they
                        // close it, the parent app will also show the error.
                        log::warn!("{} sign-in validation failed: {e}", site.title());
                        last_error = Some(e);
                        window.set_title("Sign-in not confirmed — reload or try again");
                    }
                }
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                if let Some(e) = last_error.take() {
                    outcome = Outcome::Failed(format!("account could not be verified: {e}"));
                }
                *flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
    drop(webview);
    Ok(outcome)
}

#[cfg(test)]
mod tests {
    use super::{Site, candidate_cookie, domain_matches};

    #[test]
    fn does_not_close_for_a_landing_page_without_session_cookies() {
        let without = vec![("CONSENT".into(), "yes".into()), ("PREF".into(), "light".into())];
        assert!(candidate_cookie(Site::Google, &without).is_none());
        assert!(candidate_cookie(Site::Spotify, &without).is_none());
    }

    #[test]
    fn captures_secure_google_cookies_and_spotify_dc_cookie() {
        let google = vec![
            ("__Secure-3PAPISID".into(), "signed".into()),
            ("SID".into(), "session".into()),
        ];
        assert_eq!(candidate_cookie(Site::Google, &google).as_deref(), Some("__Secure-3PAPISID=signed; SID=session"));
        let spotify = vec![("sp_dc".into(), "session".into()), ("sp_t".into(), "device".into())];
        assert_eq!(candidate_cookie(Site::Spotify, &spotify).as_deref(), Some("session"));
    }

    #[test]
    fn parent_domain_cookies_go_to_subdomains() {
        assert!(domain_matches("music.youtube.com", ".youtube.com"));
        assert!(domain_matches("music.youtube.com", "youtube.com"));
        assert!(domain_matches("music.youtube.com", "music.youtube.com"));
        assert!(!domain_matches("music.youtube.com", "accounts.google.com"));
        assert!(!domain_matches("music.youtube.com", "tube.com"));
        assert!(!domain_matches("evilyoutube.com", ".youtube.com"));
    }
}
