// Parse a captured InnerTube response with the Rust core, so the output can be
// compared with the JavaScript parser on exactly the same bytes.
//
//   cargo run --example parse_fixture -- <file.json> search|feed
use indio_core::innertube::{parse_feed, parse_search};

fn main() {
    let path = std::env::args().nth(1).expect("usage: parse_fixture <file.json> [search|feed]");
    let kind = std::env::args().nth(2).unwrap_or_else(|| "search".to_string());
    let raw = std::fs::read_to_string(&path).expect("read fixture");
    let root: serde_json::Value = serde_json::from_str(&raw).expect("parse fixture json");

    if kind == "feed" {
        let feed = parse_feed(&root);
        let items: Vec<serde_json::Value> = feed
            .videos
            .iter()
            .map(|v| {
                serde_json::json!({
                    "videoId": v.video_id,
                    "title": v.title,
                    "channelId": v.channel_id,
                    "durationSec": v.duration_sec,
                    "verified": v.channel_verified.unwrap_or(false),
                    "membersOnly": v.members_only,
                })
            })
            .collect();
        println!(
            "{}",
            serde_json::json!({
                "items": items,
                "chips": feed.chips.len(),
                "continuation": feed.continuation.is_some(),
            })
        );
        return;
    }

    let results = parse_search(&root);
    let items: Vec<serde_json::Value> = results
        .entries
        .iter()
        .map(|e| match e {
            indio_core::innertube::SearchEntry::Video { video } => serde_json::json!({
                "kind": "video",
                "videoId": video.video_id,
                "title": video.title,
                "channelId": video.channel_id,
                "durationSec": video.duration_sec,
                "verified": video.channel_verified.unwrap_or(false),
                "membersOnly": video.members_only,
            }),
            indio_core::innertube::SearchEntry::Channel { channel } => serde_json::json!({
                "kind": "channel",
                "channelId": channel.channel_id,
                "name": channel.name,
            }),
            indio_core::innertube::SearchEntry::Playlist { playlist } => serde_json::json!({
                "kind": "playlist",
                "playlistId": playlist.playlist_id,
                "title": playlist.title,
            }),
        })
        .collect();
    println!(
        "{}",
        serde_json::json!({
            "items": items,
            "continuation": results.continuation.is_some(),
            "restricted": results.restricted,
        })
    );
}
