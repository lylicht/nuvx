use super::*;

pub async fn radio(
    api: &Api,
    video_id: &str,
    auth: bool,
    limit: usize,
    hide_explicit: bool,
    focused: bool,
    profile: &crate::content_filter::TasteProfile,
) -> anyhow::Result<Vec<Track>> {
    let body = json!({
        "playlistId": format!("RDAMVM{video_id}"),
        "params": "wAEB8gECeAE%3D",
        "enablePersistentPlaylistPanel": true,
        "isAudioOnly": true,
        "tunerSettingValue": "AUTOMIX_SETTING_NORMAL",
    });
    let v = api.call("next", body, auth).await?;
    let panel = &v["contents"]["singleColumnMusicWatchNextResultsRenderer"]["tabbedRenderer"]["watchNextTabbedResultsRenderer"]["tabs"][0]
        ["tabRenderer"]["content"]["musicQueueRenderer"]["content"]["playlistPanelRenderer"];
    let (mut items, mut token) = panel_page(panel);
    while radio_tracks(&items).count() < limit {
        let Some(t) = token.take() else { break };
        let body = json!({ "continuation": t, "enablePersistentPlaylistPanel": true, "isAudioOnly": true });
        let v = api.call("next", body, auth).await?;
        let (more, next) = panel_page(&v["continuationContents"]["playlistPanelContinuation"]);
        if more.is_empty() {
            break;
        }
        items.extend(more);
        token = next;
    }
    let profile = match items.iter().map(|(t, _, _)| t).find(|t| t.id == video_id) {
        Some(seed) => profile.plus(&seed.title, &seed.artist_line()),
        None => profile.clone(),
    };
    let tracks: Vec<Track> = radio_tracks(&items)
        .filter(|(t, _, explicit)| crate::content_filter::keep(*explicit, hide_explicit, focused, &t.title, &t.artist_line(), &profile))
        .map(|(t, _, _)| t.clone())
        .collect();
    if focused && tracks.is_empty() {
        return topical_fallback(api, video_id, &items, auth, hide_explicit, &profile).await;
    }
    Ok(tracks)
}

/// On-topic fallback: when the radio mix seeded by `video_id` has nothing that
/// matches the user's taste profile (YouTube blends the account's wider
/// history into it), rebuild the queue from a search for more like the seed.
async fn topical_fallback(
    api: &Api,
    video_id: &str,
    items: &[(Track, bool, bool)],
    auth: bool,
    hide_explicit: bool,
    profile: &crate::content_filter::TasteProfile,
) -> anyhow::Result<Vec<Track>> {
    let seed = items.iter().map(|(t, _, _)| t).find(|t| t.id == video_id);
    let Some(query) = crate::content_filter::fallback_query(
        seed.map(|t| t.title.as_str()),
        seed.map(|t| t.artist_line()).as_deref(),
    ) else {
        return Ok(Vec::new());
    };
    let mut tracks = super::search_songs(api, &query, auth).await?;
    tracks.retain(|t| crate::content_filter::keep(false, hide_explicit, true, &t.title, &t.artist_line(), profile));
    Ok(tracks)
}

pub(super) fn radio_tracks(items: &[(Track, bool, bool)]) -> impl Iterator<Item = &(Track, bool, bool)> {
    let songs = items.iter().any(|(_, video, _)| !video);
    items.iter().filter(move |(_, video, _)| !songs || !video)
}

fn has_explicit_badge(r: &Value) -> bool {
    fn find(v: &Value) -> bool {
        match v {
            Value::String(s) => s == "MUSIC_EXPLICIT_BADGE",
            Value::Array(a) => a.iter().any(find),
            Value::Object(o) => o.values().any(find),
            _ => false,
        }
    }
    find(&r["badges"]) || find(&r["subtitleBadges"])
}

pub async fn server_queue(api: &Api) -> anyhow::Result<Option<ServerQueue>> {
    let body = json!({"watchNextType": "WATCH_NEXT_TYPE_GET_QUEUE", "queueContextParams": "", "enablePersistentPlaylistPanel": true, "isAudioOnly": true});
    let v = api.call_signed_in("next", body).await?;
    Ok(parse_server_queue(&v))
}

pub struct ServerQueue {
    pub tracks: Vec<Track>,
    pub index: usize,
    pub watched_at: u64,
}

pub(super) fn parse_server_queue(v: &Value) -> Option<ServerQueue> {
    let queue = &v["contents"]["singleColumnMusicWatchNextResultsRenderer"]["tabbedRenderer"]["watchNextTabbedResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["musicQueueRenderer"];
    let (mut tracks, mut index) = (Vec::new(), None);
    for i in arr(&queue["content"]["playlistPanelRenderer"]["contents"]) {
        let wrapped = &i["playlistPanelVideoWrapperRenderer"]["primaryRenderer"]["playlistPanelVideoRenderer"];
        let r = if wrapped.is_null() { &i["playlistPanelVideoRenderer"] } else { wrapped };
        let Some(t) = panel_item(r) else { continue };
        if r["selected"].as_bool() == Some(true) {
            index = Some(tracks.len());
        }
        tracks.push(t);
    }
    let watched_at = queue["musicQueueConfig"]["musicQueueRestorationInfo"]["lastWatchTimestamp"]["seconds"].as_str().and_then(|s| s.parse().ok()).unwrap_or(0);
    Some(ServerQueue { index: index?, tracks, watched_at })
}

fn panel_page(panel: &Value) -> (Vec<(Track, bool, bool)>, Option<String>) {
    let tracks = arr(&panel["contents"])
        .iter()
        .filter_map(|i| {
            let wrapped = &i["playlistPanelVideoWrapperRenderer"]["primaryRenderer"]["playlistPanelVideoRenderer"];
            let r = if wrapped.is_null() { &i["playlistPanelVideoRenderer"] } else { wrapped };
            Some((panel_item(r)?, is_video(r), has_explicit_badge(r)))
        })
        .collect();
    let token = panel["continuations"][0]["nextRadioContinuationData"]["continuation"].as_str().map(str::to_owned);
    (tracks, token)
}
