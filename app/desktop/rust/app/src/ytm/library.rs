use super::*;

fn grid_cards(sections: &[Value]) -> Vec<Card> {
    sections
        .iter()
        .flat_map(|s| arr(&s["gridRenderer"]["items"]))
        .filter_map(|i| two_row(&i["musicTwoRowItemRenderer"]))
        .collect()
}

async fn saved(api: &Api, token: &str) -> anyhow::Result<Vec<Card>> {
    let v = api
        .call_signed_in("browse", json!({ "continuation": token }))
        .await?;
    let sections = arr(&v["continuationContents"]["sectionListContinuation"]["contents"]);
    let mut cards = grid_cards(sections);
    let mut token = sections.iter().find_map(|s| grid_next(&s["gridRenderer"]));
    for _ in 0..100 {
        let Some(t) = token.take() else { break };
        let v = match api
            .call_signed_in("browse", json!({ "continuation": t }))
            .await
        {
            Ok(v) => v,
            Err(e) => {
                log::warn!("library page: {e}");
                break;
            }
        };
        let (more, next) = grid_continuation(&v);
        if more.is_empty() {
            break;
        }
        cards.extend(more);
        token = next;
    }
    Ok(cards)
}

pub(super) fn grid_continuation(v: &Value) -> (Vec<Card>, Option<String>) {
    let items =
        &v["onResponseReceivedActions"][0]["appendContinuationItemsAction"]["continuationItems"];
    if !items.is_null() {
        return (two_rows(arr(items)), item_token(arr(items)));
    }
    let grid = &v["continuationContents"]["gridContinuation"];
    (two_rows(arr(&grid["items"])), grid_next(grid))
}

pub(super) fn grid_next(grid: &Value) -> Option<String> {
    continuation(grid).or_else(|| item_token(arr(&grid["items"])))
}

fn two_rows(items: &[Value]) -> Vec<Card> {
    items
        .iter()
        .filter_map(|i| two_row(&i["musicTwoRowItemRenderer"]))
        .collect()
}

pub(super) fn item_token(items: &[Value]) -> Option<String> {
    items.iter().find_map(|i| {
        i["continuationItemRenderer"]["continuationEndpoint"]["continuationCommand"]["token"]
            .as_str()
            .map(str::to_owned)
    })
}

pub async fn saved_playlists(api: &Api) -> anyhow::Result<Vec<Card>> {
    saved(
        api,
        "4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D",
    )
    .await
}

pub async fn saved_albums(api: &Api) -> anyhow::Result<Vec<Card>> {
    saved(
        api,
        "4qmFsgIoEhRGRW11c2ljX2xpa2VkX2FsYnVtcxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D",
    )
    .await
}
