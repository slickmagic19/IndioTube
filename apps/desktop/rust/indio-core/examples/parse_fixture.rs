// Parse a captured InnerTube response with the Rust core, so the output can be
// compared with the JavaScript parser on exactly the same bytes.
//
//   cargo run --example parse_fixture -- <file.json> <mode> [id]
//
// Modes: search, feed, watch, comments, channel, playlists, tabs, playlist.
use indio_core::channel::{
    parse_channel_page, parse_channel_playlists_page, parse_channel_tabs,
};
use indio_core::innertube::{parse_feed, parse_search};
use indio_core::playlist::parse_playlist;
use indio_core::watch::{parse_comments, parse_watch};

fn main() {
    let path = std::env::args().nth(1).expect("usage: parse_fixture <file.json> <mode> [id]");
    let mode = std::env::args().nth(2).unwrap_or_else(|| "search".to_string());
    let id = std::env::args().nth(3);
    let raw = std::fs::read_to_string(&path).expect("read fixture");
    let root: serde_json::Value = serde_json::from_str(&raw).expect("parse fixture json");

    let out = match mode.as_str() {
        "feed" => serde_json::to_value(parse_feed(&root)).unwrap(),
        "watch" => serde_json::to_value(parse_watch(&root, id.as_deref().unwrap_or(""))).unwrap(),
        "comments" => serde_json::to_value(parse_comments(&root)).unwrap(),
        "channel" => serde_json::to_value(parse_channel_page(&root, id.as_deref())).unwrap(),
        "playlists" => serde_json::to_value(parse_channel_playlists_page(&root)).unwrap(),
        "tabs" => serde_json::to_value(parse_channel_tabs(&root)).unwrap(),
        "playlist" => serde_json::to_value(parse_playlist(&root)).unwrap(),
        _ => serde_json::to_value(parse_search(&root)).unwrap(),
    };
    println!("{}", out);
}
