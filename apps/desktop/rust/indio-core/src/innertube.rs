//! InnerTube client: the Rust port of electron/inTube.js.
//!
//! Same endpoints, same request shape, same layout-tolerant parsing: known
//! paths first, then a bounded tree scan. Ads and Shorts never reach the UI
//! because their renderers are skipped while parsing.
//!
//! Network access goes through the [`Transport`] trait so the parsers and the
//! request bodies are testable without a live YouTube session; the Tauri shell
//! supplies a real HTTPS transport.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::channel::{
    parse_about_token, parse_channel_about, parse_channel_header, parse_channel_page,
    parse_channel_playlists_page, parse_channel_tabs, ChannelAbout, ChannelPage,
    ChannelPlaylistsPage, ChannelTab,
};
use crate::playlist::{parse_playlist, PlaylistPage};
use crate::watch::{
    parse_comments, parse_watch, parse_watch_continuation, CommentsPage, WatchContinuation, WatchInfo,
};

pub const UA_DESKTOP: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0";

/// Signed-out search for "this week, videos only, sorted by view count" - the
/// closest replacement for the retired FEtrending feed.
pub const TRENDING_PARAMS: &str = "EgYIAxABGAM%3D";
pub const CHANNEL_TAB_VIDEOS: &str = "EgZ2aWRlb3PyBgQKAjoA";
pub const CHANNEL_TAB_LIVE: &str = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D";

// ---- JSON navigation (port of the path/str/runsText helpers) -----------------

/// Dotted JSON path: `tabs.0.tabRenderer.content`. Numeric segments index
/// arrays, everything else indexes objects; anything else yields `None`.
pub fn at<'a>(root: &'a Value, path: &str) -> Option<&'a Value> {
    let mut cur = root;
    for seg in path.split('.') {
        cur = match cur {
            Value::Object(map) => map.get(seg)?,
            Value::Array(arr) => {
                if seg.is_empty() {
                    return None;
                }
                arr.get(seg.parse::<usize>().ok()?)?
            }
            _ => return None,
        };
    }
    Some(cur)
}

/// Terminal must be a primitive: objects and arrays yield `None`.
pub fn at_str(root: &Value, path: &str) -> Option<String> {
    match at(root, path)? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Bounded breadth-first scan for every value stored under `key`.
pub fn find_all(root: &Value, key: &str, max_depth: usize, limit: usize) -> Vec<Value> {
    let mut out = Vec::new();
    let mut current = vec![root.clone()];
    let mut depth = 0;
    while !current.is_empty() && depth <= max_depth && out.len() < limit {
        let mut next = Vec::new();
        for cur in &current {
            match cur {
                Value::Array(arr) => next.extend(arr.iter().cloned()),
                Value::Object(map) => {
                    if let Some(v) = map.get(key) {
                        if out.len() < limit {
                            out.push(v.clone());
                        }
                    }
                    next.extend(map.values().cloned());
                }
                _ => {}
            }
        }
        current = next;
        depth += 1;
    }
    out
}

/// Join the text of a `runs[]` array.
pub fn runs_text(el: Option<&Value>) -> Option<String> {
    let runs = el?.get("runs")?.as_array()?;
    let joined: String = runs
        .iter()
        .filter_map(|r| at_str(r, "text"))
        .collect::<Vec<_>>()
        .join("");
    let trimmed = joined.trim().to_string();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

pub(crate) fn text_either(el: Option<&Value>) -> Option<String> {
    let el = el?;
    at_str(el, "simpleText").or_else(|| runs_text(Some(el)))
}

pub fn normalize_image_url(value: Option<&str>) -> Option<String> {
    let url = value?.trim();
    if url.is_empty() {
        return None;
    }
    if let Some(rest) = url.strip_prefix("//") {
        return Some(format!("https://{rest}"));
    }
    if let Some(rest) = url.strip_prefix("http://") {
        return Some(format!("https://{rest}"));
    }
    Some(url.to_string())
}

/// "1:02:03" / "12:34" / "42" -> seconds.
pub fn parse_duration(text: Option<&str>) -> i64 {
    let Some(text) = text else { return 0 };
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return 0;
    }
    let mut nums = Vec::new();
    for part in trimmed.split(':') {
        match part.trim().parse::<i64>() {
            Ok(n) => nums.push(n),
            Err(_) => return 0,
        }
    }
    match nums.len() {
        3 => nums[0] * 3600 + nums[1] * 60 + nums[2],
        2 => nums[0] * 60 + nums[1],
        1 => nums[0],
        _ => 0,
    }
}

/// "21 million views" -> "21M views" so meta lines stay tight.
pub fn compact_count(label: &str) -> String {
    let re_thousand = regex::Regex::new(r"(?i)\s*thousand\b").unwrap();
    let re_billion = regex::Regex::new(r"(?i)\s*billion\b").unwrap();
    let re_million = regex::Regex::new(r"(?i)\s*million\b").unwrap();
    let re_space = regex::Regex::new(r"\s+").unwrap();
    let s = re_thousand.replace_all(label, "K").to_string();
    let s = re_billion.replace_all(&s, "B").to_string();
    let s = re_million.replace_all(&s, "M").to_string();
    re_space.replace_all(&s, " ").trim().to_string()
}

// ---- search params (protobuf varints, port of buildSearchParams) -------------

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct SearchFilters {
    pub kind: Option<u32>,
    pub sort: Option<u32>,
    pub upload_date: Option<u32>,
    pub duration: Option<u32>,
}

fn write_varint(mut value: u64, out: &mut Vec<u8>) {
    while value >= 0x80 {
        out.push(((value & 0x7f) | 0x80) as u8);
        value >>= 7;
    }
    out.push(value as u8);
}

fn write_field(field_no: u32, value: u64, out: &mut Vec<u8>) {
    out.push(((field_no << 3) & 0xff) as u8);
    write_varint(value, out);
}

/// `None` means "no filter". YouTube orders duration short=1, long=2,
/// medium=3 - not by length.
pub fn build_search_params(filters: SearchFilters) -> Option<String> {
    let mut nested = Vec::new();
    if let Some(v) = filters.upload_date {
        write_field(1, v as u64, &mut nested);
    }
    if let Some(v) = filters.kind {
        write_field(2, v as u64, &mut nested);
    }
    if let Some(v) = filters.duration {
        write_field(3, v as u64, &mut nested);
    }

    let mut message = Vec::new();
    if let Some(v) = filters.sort {
        write_field(1, v as u64, &mut message);
    }
    if !nested.is_empty() {
        message.push(0x12); // field 2, length-delimited
        write_varint(nested.len() as u64, &mut message);
        message.extend_from_slice(&nested);
    }
    if message.is_empty() {
        return None;
    }
    Some(base64::engine::general_purpose::STANDARD.encode(message))
}

// ---- badge + shape helpers ---------------------------------------------------

pub(crate) fn has_members_badge(el: &Value, max_depth: usize) -> bool {
    let texts: Vec<String> = [
        ("metadataBadgeRenderer", "label"),
        ("thumbnailBadgeViewModel", "text"),
        ("badgeViewModel", "title.content"),
        ("badgeViewModel", "badgeText.content"),
        ("badgeViewModel", "text"),
        ("badgeViewModel", "label"),
    ]
    .iter()
    .flat_map(|(key, rest)| {
        find_all(el, key, max_depth, 24)
            .into_iter()
            .filter_map(|b| at_str(&b, rest))
            .collect::<Vec<_>>()
    })
    .collect();
    let members = regex::Regex::new(r"(?i)\bmembers?[- ]only\b|\bchannel members?\b").unwrap();
    let styled = find_all(el, "metadataBadgeRenderer", max_depth, 24)
        .iter()
        .any(|b| at_str(b, "style").as_deref() == Some("BADGE_STYLE_TYPE_MEMBERS_ONLY"));
    styled || texts.iter().any(|t| members.is_match(t))
}

/// Vertical thumbnails (Shorts) and reel endpoints never reach the UI.
fn is_shortish(el: &Value) -> bool {
    if at(el, "navigationEndpoint.reelWatchEndpoint").is_some() {
        return true;
    }
    let thumbs: Vec<Value> = find_all(el, "thumbnails", 6, 6)
        .into_iter()
        .filter_map(|v| v.as_array().cloned())
        .flatten()
        .collect();
    let Some(last) = thumbs.last() else { return false };
    let w = at_str(last, "width")
        .and_then(|v| v.parse::<f64>().ok())
        .unwrap_or(0.0);
    let h = at_str(last, "height")
        .and_then(|v| v.parse::<f64>().ok())
        .unwrap_or(0.0);
    h > w && w > 0.0
}

/// A verified channel carries a check-circle badge in its owner badges.
pub fn is_verified(nodes: &[&Value]) -> bool {
    let re = regex::Regex::new(
        r#"CHECK_CIRCLE_THICK|BADGE_STYLE_TYPE_VERIFIED|"label":"Verified""#,
    )
    .unwrap();
    nodes
        .iter()
        .any(|n| re.is_match(&n.to_string()))
}

pub(crate) fn last_thumb_url(container: Option<&Value>) -> Option<String> {
    let thumbs = container?.get("thumbnails")?.as_array()?;
    thumbs
        .iter()
        .rev()
        .find_map(|t| normalize_image_url(at_str(t, "url").as_deref()))
}

pub(crate) fn xth_first_url(container: Option<&Value>) -> Option<String> {
    let Some(container) = container else { return None };
    let sources: Vec<Value> = find_all(container, "sources", 6, 4)
        .into_iter()
        .filter_map(|v| v.as_array().cloned())
        .flatten()
        .collect();
    sources
        .iter()
        .rev()
        .find_map(|s| normalize_image_url(at_str(s, "url").as_deref()))
}

pub fn extract_continuation(cont: &Value) -> Option<String> {
    at_str(cont, "continuationEndpoint.continuationCommand.token")
        .or_else(|| at_str(cont, "button.buttonRenderer.command.continuationCommand.token"))
}

// ---- models ------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoItem {
    pub video_id: String,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_avatar: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_verified: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thumbnail: Option<String>,
    pub duration_sec: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub views_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub published_text: Option<String>,
    pub is_live: bool,
    pub members_only: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelItem {
    pub channel_id: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub avatar: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subscribers_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_count_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistItem {
    pub playlist_id: String,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thumbnail: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_count_text: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum SearchEntry {
    Video { video: VideoItem },
    Channel { channel: ChannelItem },
    Playlist { playlist: PlaylistItem },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Chip {
    pub title: String,
    pub params: Option<String>,
    pub token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Feed {
    pub chips: Vec<Chip>,
    pub videos: Vec<VideoItem>,
    pub continuation: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResults {
    pub entries: Vec<SearchEntry>,
    pub continuation: Option<String>,
    pub restricted: bool,
}

// ---- renderer parsing --------------------------------------------------------

const DURATION_RE: &str = r"^\d{1,2}:\d{2}(:\d{2})?$";

pub fn parse_video_renderer(v: &Value) -> Option<VideoItem> {
    let video_id = at_str(v, "videoId")?;
    let title_el = at(v, "title");
    let title = runs_text(title_el)
        .or_else(|| title_el.and_then(|t| at_str(t, "simpleText")))
        .unwrap_or_default()
        .trim()
        .to_string();
    if title.is_empty() {
        return None;
    }
    if at(v, "upcomingEventData").is_some() {
        return None;
    }

    let badges = v.get("badges").and_then(Value::as_array).cloned().unwrap_or_default();
    let members_label = badges.iter().any(|b| {
        at_str(b, "metadataBadgeRenderer.label")
            .map(|l| l.to_lowercase().contains("members"))
            .unwrap_or(false)
    });
    let members_only = members_label || has_members_badge(v, 6);
    if is_shortish(v) {
        return None;
    }

    let live = badges
        .iter()
        .any(|b| at_str(b, "metadataBadgeRenderer.style").as_deref() == Some("BADGE_STYLE_TYPE_LIVE_NOW"))
        || at_str(v, "lengthText.simpleText").as_deref() == Some("LIVE");

    let length_el = at(v, "lengthText");
    let duration_text = length_el
        .and_then(|e| at_str(e, "simpleText"))
        .or_else(|| runs_text(length_el));
    let duration = parse_duration(duration_text.as_deref());

    let owner = at(v, "ownerText.runs.0");
    let channel_name = owner.and_then(|o| at_str(o, "text"));
    let channel_id = owner
        .and_then(|o| at_str(o, "navigationEndpoint.browseEndpoint.browseId"))
        .or_else(|| at_str(v, "navigationEndpoint.browseEndpoint.browseId"));
    let channel_avatar = last_thumb_url(at(
        v,
        "channelThumbnailSupportedRenderers.channelThumbnailWithLinkRenderer.thumbnail",
    ))
    .or_else(|| last_thumb_url(at(v, "channelThumbnail").or(Some(v))));

    let thumbnail = last_thumb_url(at(v, "thumbnail"))
        .unwrap_or_else(|| format!("https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"));

    let views = {
        let vc = at(v, "viewCountText");
        vc.and_then(|e| at_str(e, "simpleText"))
            .or_else(|| runs_text(vc))
    };
    let published = at_str(v, "publishedTimeText.simpleText");

    let mut badge_nodes: Vec<&Value> = Vec::new();
    for node in [v.get("ownerBadges"), v.get("badges"), at(v, "videoOwnerRenderer.badges")] {
        if let Some(node) = node {
            badge_nodes.push(node);
        }
    }
    let verified = is_verified(&badge_nodes);

    Some(VideoItem {
        video_id,
        title,
        channel_name,
        channel_id,
        channel_avatar,
        channel_verified: if verified { Some(true) } else { None },
        thumbnail: Some(thumbnail),
        duration_sec: duration,
        views_text: views.map(|s| compact_count(&s)),
        published_text: published,
        is_live: live,
        members_only,
    })
}

/// The newer lockupViewModel item used by current layouts.
pub fn parse_lockup(lockup: &Value) -> Option<VideoItem> {
    let content_type = at_str(lockup, "contentType").unwrap_or_default();
    if !content_type.contains("VIDEO") {
        return None;
    }
    let video_id = at_str(lockup, "contentId")?;
    let meta = at(lockup, "metadata.lockupMetadataViewModel")?;
    let title = at_str(meta, "title.content").unwrap_or_default().trim().to_string();
    if title.is_empty() {
        return None;
    }

    let thumb_container = at(
        meta,
        "image.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel",
    )
    .or_else(|| at(lockup, "contentImage.thumbnailViewModel"))
    .or_else(|| {
        at(
            lockup,
            "contentImage.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel",
        )
    });
    if let Some(tc) = thumb_container {
        if is_shortish(tc) {
            return None;
        }
    }
    // Only the still image: the same container also holds moving-preview
    // sources, and picking the last source overall yielded an animated webp.
    let thumb = xth_first_url(thumb_container.and_then(|tc| at(tc, "image")));

    let mut duration_badge: Option<String> = None;
    let mut is_live = false;
    if let Some(tc) = thumb_container {
        if let Some(overlays) = tc.get("overlays").and_then(Value::as_array) {
            for ov in overlays {
                for badge in find_all(ov, "thumbnailBadgeViewModel", 6, 4) {
                    let Some(text) = at_str(&badge, "text") else { continue };
                    if text.eq_ignore_ascii_case("LIVE") {
                        is_live = true;
                    } else if regex::Regex::new(DURATION_RE).unwrap().is_match(&text) {
                        duration_badge = Some(text);
                    }
                }
            }
        }
    }

    let mut members_only = has_members_badge(lockup, 10);
    let avatar_vm = at(meta, "image.decoratedAvatarViewModel");
    let mut channel_avatar = xth_first_url(avatar_vm.and_then(|a| at(a, "avatar")));
    let channel_id = at_str(
        avatar_vm.unwrap_or(&Value::Null),
        "rendererContext.commandContext.onTap.innertubeCommand.browseEndpoint.browseId",
    );
    let mut channel_name = at_str(avatar_vm.unwrap_or(&Value::Null), "a11yLabel")
        .map(|l| l.trim_start_matches("Go to channel ").trim().to_string())
        .filter(|n| !n.is_empty());

    let mut views: Option<String> = None;
    let mut published: Option<String> = None;
    let views_re = regex::Regex::new(r"(?i)(views?|watching|likes?)").unwrap();
    let ago_re = regex::Regex::new(r"(?i)(ago|Premiered|Streamed|\b\d{4}\b)").unwrap();
    let count_re = regex::Regex::new(r"^[\d.,]+[KMB]?$").unwrap();
    let duration_re = regex::Regex::new(DURATION_RE).unwrap();
    let members_re = regex::Regex::new(r"(?i)\bmembers?[- ]only\b|\bchannel members?\b").unwrap();

    if let Some(rows) = at(meta, "metadata.contentMetadataViewModel.metadataRows").and_then(Value::as_array) {
        for row in rows {
            let Some(parts) = row.get("metadataParts").and_then(Value::as_array) else { continue };
            for part in parts {
                let text = at_str(part, "text.content");
                let a11y = at_str(part, "accessibilityLabel");
                let members_text = text.as_deref().map(|t| members_re.is_match(t)).unwrap_or(false)
                    || a11y.as_deref().map(|t| members_re.is_match(t)).unwrap_or(false);
                if members_text {
                    members_only = true;
                } else if let Some(t) = text.as_deref() {
                    if views_re.is_match(t) {
                        views.get_or_insert_with(|| t.to_string());
                    } else if ago_re.is_match(t) && !duration_re.is_match(t) {
                        published.get_or_insert_with(|| t.to_string());
                    } else if count_re.is_match(t) {
                        views.get_or_insert_with(|| format!("{t} views"));
                    } else if channel_name.is_none() {
                        channel_name = Some(t.to_string());
                    }
                } else if let Some(a) = a11y.as_deref() {
                    if views_re.is_match(a) {
                        views.get_or_insert_with(|| compact_count(a));
                    }
                }
                if channel_avatar.is_none() {
                    channel_avatar = xth_first_url(part.get("avatar"));
                }
            }
        }
    }

    let verified = is_verified(&[meta]);
    let thumbnail = thumb.unwrap_or_else(|| format!("https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"));

    Some(VideoItem {
        video_id,
        title,
        channel_name,
        channel_id,
        channel_avatar,
        channel_verified: if verified { Some(true) } else { None },
        thumbnail: Some(thumbnail),
        duration_sec: parse_duration(duration_badge.as_deref()),
        views_text: views,
        published_text: published,
        is_live,
        members_only,
    })
}

pub fn parse_channel_renderer(c: &Value) -> Option<ChannelItem> {
    let channel_id = at_str(c, "channelId")?;
    let name = text_either(at(c, "title")).unwrap_or_default();
    if name.trim().is_empty() {
        return None;
    }
    Some(ChannelItem {
        channel_id,
        name: name.trim().to_string(),
        avatar: last_thumb_url(at(c, "thumbnail")).filter(|_| !is_shortish(c)),
        subscribers_text: text_either(at(c, "subscriberCountText")),
        video_count_text: text_either(at(c, "videoCountText")),
        description: text_either(at(c, "descriptionSnippet")),
    })
}

pub fn parse_playlist_renderer(p: &Value) -> Option<PlaylistItem> {
    let playlist_id = at_str(p, "playlistId")?;
    let title = text_either(at(p, "title")).unwrap_or_default();
    if title.trim().is_empty() {
        return None;
    }
    let channel = at(p, "shortBylineText.runs.0");
    Some(PlaylistItem {
        playlist_id,
        title: title.trim().to_string(),
        channel_name: channel.and_then(|c| at_str(c, "text")),
        channel_id: channel.and_then(|c| at_str(c, "navigationEndpoint.browseEndpoint.browseId")),
        thumbnail: last_thumb_url(at(p, "thumbnail")),
        video_count_text: text_either(at(p, "videoCountText")),
    })
}

pub fn parse_any_video(content: &Value) -> Option<VideoItem> {
    for key in [
        "videoRenderer",
        "gridVideoRenderer",
        "compactVideoRenderer",
        "playlistVideoRenderer",
    ] {
        if let Some(r) = content.get(key) {
            return parse_video_renderer(r);
        }
    }
    if let Some(lockup) = content.get("lockupViewModel") {
        return parse_lockup(lockup);
    }
    None
}

// ---- feeds and search --------------------------------------------------------

fn feed_contents(root: &Value) -> Vec<Value> {
    let direct = at(
        root,
        "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.richGridRenderer.contents",
    );
    if let Some(arr) = direct.and_then(Value::as_array) {
        return arr.clone();
    }
    let single = at(
        root,
        "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.richGridRenderer.contents",
    );
    if let Some(arr) = single.and_then(Value::as_array) {
        return arr.clone();
    }
    let mut out = Vec::new();
    for grid in find_all(root, "richGridRenderer", 8, 3) {
        if let Some(contents) = grid.get("contents").and_then(Value::as_array) {
            out.extend(contents.iter().cloned());
        }
    }
    out
}

pub(crate) fn feed_continuation(root: &Value) -> Option<String> {
    for cont in find_all(root, "continuationItemRenderer", 14, 12) {
        if let Some(t) = extract_continuation(&cont) {
            return Some(t);
        }
    }
    for cc in find_all(root, "continuationCommand", 14, 12) {
        if let Some(t) = at_str(&cc, "token") {
            return Some(t);
        }
    }
    None
}

pub(crate) fn continuation_action_items(root: &Value) -> Vec<Value> {
    fn add(el: &Value, out: &mut Vec<Value>) {
        if !el.is_object() {
            return;
        }
        for path in [
            "appendContinuationItemsAction.continuationItems",
            "reloadContinuationItemsCommand.continuationItems",
        ] {
            if let Some(items) = at(el, path).and_then(Value::as_array) {
                out.extend(items.iter().cloned());
            }
        }
        if let Some(commands) = at(el, "commandExecutorCommand.commands").and_then(Value::as_array) {
            for cmd in commands {
                add(cmd, out);
            }
        }
    }
    let mut out = Vec::new();
    for key in [
        "onResponseReceivedActions",
        "onResponseReceivedEndpoints",
        "onResponseReceivedCommands",
    ] {
        if let Some(list) = root.get(key).and_then(Value::as_array) {
            for el in list {
                add(el, &mut out);
            }
        }
    }
    out
}

pub fn feed_items(root: &Value) -> Vec<Value> {
    let direct = feed_contents(root);
    if !direct.is_empty() {
        return direct;
    }
    continuation_action_items(root)
}

pub fn recommendable_videos(videos: Vec<VideoItem>) -> Vec<VideoItem> {
    videos.into_iter().filter(|v| !v.members_only).collect()
}

pub fn parse_feed(root: &Value) -> Feed {
    let mut chips = Vec::new();
    let chip_arr = at(
        root,
        "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.richGridRenderer.header.feedFilterChipBarRenderer.contents",
    );
    for chip_el in chip_arr.and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]) {
        let Some(chip) = chip_el.get("chipCloudChipRenderer") else { continue };
        let Some(title) = text_either(at(chip, "text")) else { continue };
        chips.push(Chip {
            title,
            params: at_str(chip, "navigationEndpoint.browseEndpoint.params"),
            token: at_str(chip, "navigationEndpoint.continuationCommand.token"),
        });
    }

    let mut videos = Vec::new();
    let mut continuation = None;
    for entry in feed_items(root) {
        if let Some(item) = entry.get("richItemRenderer") {
            if let Some(v) = item.get("content").and_then(parse_any_video) {
                videos.push(v);
            }
            continue;
        }
        if let Some(cont) = entry.get("continuationItemRenderer") {
            if let Some(t) = extract_continuation(cont) {
                continuation = Some(t);
            }
        }
    }
    if continuation.is_none() {
        continuation = feed_continuation(root);
    }
    Feed {
        chips,
        videos,
        continuation,
    }
}

pub fn parse_search(root: &Value) -> SearchResults {
    let mut entries = Vec::new();
    let mut continuation = None;

    let direct = at(
        root,
        "contents.tabbedSearchResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents",
    )
    .or_else(|| {
        at(
            root,
            "contents.twoColumnSearchResultsRenderer.primaryContents.sectionListRenderer.contents",
        )
    });
    let sections: Vec<Value> = match direct.and_then(Value::as_array) {
        Some(arr) => arr.clone(),
        None => find_all(root, "itemSectionRenderer", 12, 20),
    };

    for section in &sections {
        let item_section = section.get("itemSectionRenderer").unwrap_or(section);
        let Some(items) = item_section.get("contents").and_then(Value::as_array) else { continue };
        for el in items {
            if let Some(vr) = el.get("videoRenderer") {
                if let Some(video) = parse_video_renderer(vr) {
                    entries.push(SearchEntry::Video { video });
                }
                continue;
            }
            if let Some(cr) = el.get("channelRenderer") {
                if let Some(channel) = parse_channel_renderer(cr) {
                    entries.push(SearchEntry::Channel { channel });
                }
                continue;
            }
            if let Some(pr) = el.get("playlistRenderer") {
                if let Some(playlist) = parse_playlist_renderer(pr) {
                    entries.push(SearchEntry::Playlist { playlist });
                }
                continue;
            }
            if let Some(lv) = el.get("lockupViewModel") {
                if let Some(video) = parse_lockup(lv) {
                    entries.push(SearchEntry::Video { video });
                }
                continue;
            }
            if let Some(cont) = el.get("continuationItemRenderer") {
                if let Some(t) = extract_continuation(cont) {
                    continuation = Some(t);
                }
            }
        }
    }

    if continuation.is_none() {
        for cont in find_all(root, "continuationItemRenderer", 12, 10) {
            if let Some(t) = extract_continuation(&cont) {
                continuation = Some(t);
                break;
            }
        }
    }

    // Age-gated searches come back as a background promo with no renderers.
    let restricted = !find_all(root, "backgroundPromoRenderer", 12, 4).is_empty();
    SearchResults {
        entries,
        continuation,
        restricted,
    }
}

// ---- client ------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    pub key: String,
    pub version: String,
    pub visitor: Option<String>,
}

/// `2.YYYYMMDD.00.00`, the WEB client version YouTube expects.
pub fn default_config(today: (i32, u32, u32)) -> Config {
    Config {
        key: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8".to_string(),
        version: format!("2.{:04}{:02}{:02}.00.00", today.0, today.1, today.2),
        visitor: None,
    }
}

pub trait Transport {
    /// POST an InnerTube request and return the raw JSON body.
    fn post(&self, url: &str, headers: &[(String, String)], body: &str) -> Result<String, String>;
}

#[derive(Debug)]
pub struct ClientError(pub String);

impl std::fmt::Display for ClientError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}
impl std::error::Error for ClientError {}

/// How many times one search request is attempted before the UI sees an error.
const SEARCH_ATTEMPTS: u32 = 3;

/// Backoff before retry N is `SEARCH_RETRY_BASE * N`; short enough that a
/// failed search still feels immediate, long enough to outlast a blip.
const SEARCH_RETRY_BASE: Duration = Duration::from_millis(150);

/// Run a fallible request until it succeeds, at most `attempts` times, calling
/// `backoff` with the zero-based number of the attempt that failed. The last
/// error is returned when every attempt fails.
pub fn with_retry<T, E>(
    attempts: u32,
    mut call: impl FnMut() -> Result<T, E>,
    mut backoff: impl FnMut(u32),
) -> Result<T, E> {
    let attempts = attempts.max(1);
    let mut last = None;
    for attempt in 0..attempts {
        match call() {
            Ok(value) => return Ok(value),
            Err(error) => {
                last = Some(error);
                if attempt + 1 < attempts {
                    backoff(attempt);
                }
            }
        }
    }
    Err(last.expect("with_retry runs at least one attempt"))
}

pub struct InnerTube<T: Transport> {
    transport: T,
    config: Config,
    lang: String,
    country: String,
    cache: HashMap<String, (Instant, Value)>,
}

impl<T: Transport> InnerTube<T> {
    pub fn new(transport: T, config: Config, lang: &str, country: &str) -> Self {
        Self {
            transport,
            config,
            lang: lang.to_string(),
            country: country.to_string(),
            cache: HashMap::new(),
        }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    fn headers(&self) -> Vec<(String, String)> {
        let mut h = vec![
            ("user-agent".to_string(), UA_DESKTOP.to_string()),
            ("accept".to_string(), "*/*".to_string()),
            (
                "accept-language".to_string(),
                format!("{},en;q=0.8", self.lang),
            ),
            ("origin".to_string(), "https://www.youtube.com".to_string()),
            ("x-youtube-client-name".to_string(), "1".to_string()),
            (
                "x-youtube-client-version".to_string(),
                self.config.version.clone(),
            ),
            (
                "content-type".to_string(),
                "application/json; charset=utf-8".to_string(),
            ),
        ];
        if let Some(v) = &self.config.visitor {
            h.push(("x-goog-visitor-id".to_string(), v.clone()));
        }
        h
    }

    fn context(&self) -> Value {
        json!({
            "client": {
                "clientName": "WEB",
                "clientVersion": self.config.version,
                "hl": self.lang,
                "gl": self.country,
            },
            "user": {},
        })
    }

    /// POST to an endpoint and parse the JSON body.
    pub fn post(&self, endpoint: &str, body: &Value) -> Result<Value, ClientError> {
        let url = format!(
            "https://www.youtube.com/youtubei/v1/{endpoint}?prettyPrint=false&alt=json&key={}",
            self.config.key
        );
        let raw = self
            .transport
            .post(&url, &self.headers(), &body.to_string())
            .map_err(ClientError)?;
        serde_json::from_str(&raw).map_err(|e| ClientError(format!("bad YouTube response: {e}")))
    }

    pub fn browse(
        &self,
        browse_id: &str,
        params: Option<&str>,
        continuation: Option<&str>,
    ) -> Result<Value, ClientError> {
        let mut body = json!({ "context": self.context() });
        if let Some(token) = continuation {
            body["continuation"] = json!(token);
        } else {
            body["browseId"] = json!(browse_id);
            if let Some(p) = params {
                body["params"] = json!(p);
            }
        }
        self.post("browse", &body)
    }

    pub fn raw_search(&self, query: &str, params: Option<&str>) -> Result<SearchResults, ClientError> {
        let mut body = json!({ "context": self.context(), "query": query });
        if let Some(p) = params {
            body["params"] = json!(p);
        }
        let root = with_retry(
            SEARCH_ATTEMPTS,
            || self.post("search", &body),
            |attempt| std::thread::sleep(SEARCH_RETRY_BASE * (attempt + 1)),
        )?;
        Ok(parse_search(&root))
    }

    pub fn search_continuation(&self, token: &str) -> Result<SearchResults, ClientError> {
        let body = json!({ "context": self.context(), "continuation": token });
        let root = with_retry(
            SEARCH_ATTEMPTS,
            || self.post("search", &body),
            |attempt| std::thread::sleep(SEARCH_RETRY_BASE * (attempt + 1)),
        )?;
        Ok(parse_search(&root))
    }

    /// Cached home feed: ten minutes, the same TTL as the JavaScript side.
    pub fn home(&mut self, params: Option<&str>, force: bool) -> Result<Feed, ClientError> {
        const TTL: Duration = Duration::from_secs(600);
        let key = format!("home:{}", params.unwrap_or(""));
        if force {
            self.cache.remove(&key);
        }
        let cached = self
            .cache
            .get(&key)
            .filter(|(at, _)| at.elapsed() < TTL)
            .map(|(_, data)| data.clone());
        let root = match cached {
            Some(root) => root,
            None => {
                let fresh = self.browse("FEwhat_to_watch", params, None)?;
                self.cache.insert(key, (Instant::now(), fresh.clone()));
                fresh
            }
        };
        let feed = parse_feed(&root);
        Ok(Feed {
            videos: recommendable_videos(feed.videos),
            ..feed
        })
    }

    pub fn home_continuation(&self, token: &str) -> Result<Feed, ClientError> {
        let feed = parse_feed(&self.browse("FEwhat_to_watch", None, Some(token))?);
        Ok(Feed {
            videos: recommendable_videos(feed.videos),
            ..feed
        })
    }

    pub fn invalidate(&mut self) {
        self.cache.clear();
    }

    // ---- watch ----

    fn next_post(&self, body: Value) -> Result<Value, ClientError> {
        self.post("next", &body)
    }

    pub fn watch(&self, video_id: &str) -> Result<WatchInfo, ClientError> {
        let mut body = json!({ "context": self.context(), "videoId": video_id });
        if let Some(v) = &self.config.visitor {
            body["context"]["client"]["visitorData"] = json!(v);
        }
        Ok(parse_watch(&self.next_post(body)?, video_id))
    }

    pub fn watch_continuation(&self, token: &str) -> Result<WatchContinuation, ClientError> {
        let body = json!({ "context": self.context(), "continuation": token });
        Ok(parse_watch_continuation(&self.next_post(body)?))
    }

    pub fn comments(&self, token: &str) -> Result<CommentsPage, ClientError> {
        let body = json!({ "context": self.context(), "continuation": token });
        Ok(parse_comments(&self.next_post(body)?))
    }

    // ---- channel ----

    fn cached_root(&mut self, key: &str, ttl: Duration, force: bool) -> Option<Value> {
        if force {
            self.cache.remove(key);
        }
        self.cache
            .get(key)
            .filter(|(at, _)| at.elapsed() < ttl)
            .map(|(_, data)| data.clone())
    }

    fn store_root(&mut self, key: &str, root: &Value) {
        self.cache
            .insert(key.to_string(), (Instant::now(), root.clone()));
    }

    pub fn channel_videos(
        &mut self,
        channel_id: &str,
        params: Option<&str>,
        force: bool,
    ) -> Result<ChannelPage, ClientError> {
        const TTL: Duration = Duration::from_secs(600);
        let key = format!("channel:{channel_id}:{}", params.unwrap_or(""));
        let root = match self.cached_root(&key, TTL, force) {
            Some(root) => root,
            None => {
                let fresh = self.browse(channel_id, params, None)?;
                self.store_root(&key, &fresh);
                fresh
            }
        };
        Ok(parse_channel_page(&root, Some(channel_id)))
    }

    pub fn channel_continuation(&self, token: &str, channel_id: &str) -> Result<ChannelPage, ClientError> {
        let root = self.browse("", None, Some(token))?;
        Ok(parse_channel_page(&root, Some(channel_id)))
    }

    pub fn channel_tabs(&mut self, channel_id: &str, force: bool) -> Result<Vec<ChannelTab>, ClientError> {
        const TTL: Duration = Duration::from_secs(1800);
        let key = format!("channelTabs:{channel_id}");
        let root = match self.cached_root(&key, TTL, force) {
            Some(root) => root,
            None => {
                let fresh = self.browse(channel_id, None, None)?;
                self.store_root(&key, &fresh);
                fresh
            }
        };
        Ok(parse_channel_tabs(&root))
    }

    pub fn channel_playlists_page(
        &mut self,
        channel_id: &str,
        params: Option<&str>,
        force: bool,
    ) -> Result<ChannelPlaylistsPage, ClientError> {
        const TTL: Duration = Duration::from_secs(1800);
        let key = format!("channelPlaylistsPage:{channel_id}");
        let root = match self.cached_root(&key, TTL, force) {
            Some(root) => root,
            None => {
                let fresh = self.browse(channel_id, params, None)?;
                self.store_root(&key, &fresh);
                fresh
            }
        };
        Ok(parse_channel_playlists_page(&root))
    }

    pub fn channel_playlists_continuation(
        &self,
        token: &str,
    ) -> Result<ChannelPlaylistsPage, ClientError> {
        Ok(parse_channel_playlists_page(&self.browse("", None, Some(token))?))
    }

    /// Resolves the About panel token from the channel page, then fetches it.
    pub fn channel_about(
        &mut self,
        channel_id: &str,
        token: Option<&str>,
        force: bool,
    ) -> Result<Option<ChannelAbout>, ClientError> {
        const TTL: Duration = Duration::from_secs(1800);
        let key = format!("channelAbout:{channel_id}");
        if let Some(root) = self.cached_root(&key, TTL, force) {
            return Ok(parse_channel_about(&root));
        }
        let about_token = match token {
            Some(t) => Some(t.to_string()),
            None => {
                let page = self.browse(channel_id, Some(CHANNEL_TAB_VIDEOS), None)?;
                let name = parse_channel_header(&page, Some(channel_id)).name;
                parse_about_token(&page, &name)
            }
        };
        let Some(about_token) = about_token else { return Ok(None) };
        let root = self.browse("", None, Some(&about_token))?;
        self.store_root(&key, &root);
        Ok(parse_channel_about(&root))
    }

    // ---- playlists ----

    pub fn playlist(&self, playlist_id: &str) -> Result<PlaylistPage, ClientError> {
        let browse_id = if playlist_id.starts_with("VL") {
            playlist_id.to_string()
        } else {
            format!("VL{playlist_id}")
        };
        Ok(parse_playlist(&self.browse(&browse_id, None, None)?))
    }

    pub fn playlist_continuation(&self, token: &str) -> Result<PlaylistPage, ClientError> {
        Ok(parse_playlist(&self.browse("", None, Some(token))?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn search_params_match_the_android_vectors() {
        let p = |f: SearchFilters| build_search_params(f);
        assert_eq!(p(SearchFilters { kind: Some(1), ..Default::default() }).as_deref(), Some("EgIQAQ=="));
        assert_eq!(p(SearchFilters { kind: Some(2), ..Default::default() }).as_deref(), Some("EgIQAg=="));
        assert_eq!(p(SearchFilters { sort: Some(1), ..Default::default() }).as_deref(), Some("CAE="));
        assert_eq!(p(SearchFilters { kind: Some(1), upload_date: Some(2), ..Default::default() }).as_deref(), Some("EgQIAhAB"));
        assert_eq!(p(SearchFilters { duration: Some(2), ..Default::default() }).as_deref(), Some("EgIYAg=="));
        assert_eq!(p(SearchFilters { kind: Some(1), sort: Some(3), ..Default::default() }).as_deref(), Some("CAMSAhAB"));
        assert_eq!(p(SearchFilters::default()), None);
    }

    #[test]
    fn duration_and_counts_parse_like_the_renderer() {
        assert_eq!(parse_duration(Some("1:02:03")), 3723);
        assert_eq!(parse_duration(Some("12:34")), 754);
        assert_eq!(parse_duration(Some("42")), 42);
        assert_eq!(parse_duration(Some("nope")), 0);
        assert_eq!(parse_duration(None), 0);
        assert_eq!(compact_count("21 million views"), "21M views");
        assert_eq!(compact_count("1.2 thousand  subscribers"), "1.2K subscribers");
    }

    fn renderer(extra: Value) -> Value {
        let mut base = json!({
            "videoId": "vid1",
            "title": { "runs": [{ "text": "A video" }] },
            "lengthText": { "simpleText": "10:00" },
            "ownerText": { "runs": [{ "text": "A channel", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC1" } } }] },
            "viewCountText": { "simpleText": "1.2 million views" },
            "publishedTimeText": { "simpleText": "2 days ago" },
            "thumbnail": { "thumbnails": [{ "url": "//i.ytimg.com/vi/vid1/hq.jpg", "width": 480, "height": 360 }] },
        });
        for (k, v) in extra.as_object().unwrap() {
            base[k] = v.clone();
        }
        base
    }

    #[test]
    fn parses_a_video_renderer() {
        let v = parse_video_renderer(&renderer(json!({}))).unwrap();
        assert_eq!(v.video_id, "vid1");
        assert_eq!(v.title, "A video");
        assert_eq!(v.channel_id.as_deref(), Some("UC1"));
        assert_eq!(v.duration_sec, 600);
        assert_eq!(v.views_text.as_deref(), Some("1.2M views"));
        assert_eq!(v.thumbnail.as_deref(), Some("https://i.ytimg.com/vi/vid1/hq.jpg"));
        assert!(!v.is_live);
        assert!(!v.members_only);
    }

    #[test]
    fn skips_upcoming_members_only_and_shorts() {
        assert!(parse_video_renderer(&renderer(json!({ "upcomingEventData": { "startTime": "1" } }))).is_none());
        let members = parse_video_renderer(&renderer(json!({
            "badges": [{ "metadataBadgeRenderer": { "label": "Members only" } }]
        })));
        assert!(members.unwrap().members_only);
        // Portrait thumbnail = Shorts rail.
        let short = renderer(json!({
            "thumbnail": { "thumbnails": [{ "url": "https://i.ytimg.com/vi/s/hq.jpg", "width": 360, "height": 640 }] }
        }));
        assert!(parse_video_renderer(&short).is_none());
    }

    #[test]
    fn marks_live_and_verified_videos() {
        let live = parse_video_renderer(&renderer(json!({
            "badges": [{ "metadataBadgeRenderer": { "style": "BADGE_STYLE_TYPE_LIVE_NOW" } }]
        }))).unwrap();
        assert!(live.is_live);

        let verified = parse_video_renderer(&renderer(json!({
            "ownerBadges": [{ "metadataBadgeRenderer": { "icon": { "iconType": "CHECK_CIRCLE_THICK" } } }]
        }))).unwrap();
        assert_eq!(verified.channel_verified, Some(true));
        assert_eq!(parse_video_renderer(&renderer(json!({}))).unwrap().channel_verified, None);
    }

    #[test]
    fn parses_a_lockup_item() {
        let lockup = json!({
            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
            "contentId": "lock1",
            "metadata": { "lockupMetadataViewModel": {
                "title": { "content": "Lockup video" },
                "image": { "decoratedAvatarViewModel": {
                    "a11yLabel": "Go to channel Lock Channel",
                    "rendererContext": { "commandContext": { "onTap": { "innertubeCommand": { "browseEndpoint": { "browseId": "UC9" } } } } }
                } },
                "metadata": { "contentMetadataViewModel": { "metadataRows": [
                    { "metadataParts": [
                        { "text": { "content": "Lock Channel" } },
                        { "text": { "content": "12K views" } },
                        { "text": { "content": "3 days ago" } }
                    ] }
                ] } }
            } },
            "contentImage": { "thumbnailViewModel": {
                "image": { "sources": [{ "url": "https://i.ytimg.com/vi/lock1/hq.jpg" }] },
                "overlays": [{ "thumbnailBadgeViewModel": { "text": "8:21" } }]
            } }
        });
        let v = parse_lockup(&lockup).unwrap();
        assert_eq!(v.video_id, "lock1");
        assert_eq!(v.title, "Lockup video");
        assert_eq!(v.channel_name.as_deref(), Some("Lock Channel"));
        assert_eq!(v.channel_id.as_deref(), Some("UC9"));
        assert_eq!(v.duration_sec, 501);
        assert_eq!(v.views_text.as_deref(), Some("12K views"));
    }

    #[test]
    fn parses_a_home_feed_with_chips_and_continuation() {
        let root = json!({
            "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [{ "tabRenderer": {
                "content": { "richGridRenderer": {
                    "header": { "feedFilterChipBarRenderer": { "contents": [
                        { "chipCloudChipRenderer": {
                            "text": { "simpleText": "All" },
                            "navigationEndpoint": { "continuationCommand": { "token": "chip-token" } }
                        } }
                    ] } },
                    "contents": [
                        { "richItemRenderer": { "content": { "videoRenderer": renderer(json!({})) } } },
                        { "richItemRenderer": { "content": { "videoRenderer": renderer(json!({
                            "videoId": "member", "membersOnly": true,
                            "badges": [{ "metadataBadgeRenderer": { "label": "Members only" } }]
                        })) } } },
                        { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "next-page" } } } }
                    ]
                } }
            } }] } }
        });
        let feed = parse_feed(&root);
        assert_eq!(feed.chips.len(), 1);
        assert_eq!(feed.chips[0].title, "All");
        assert_eq!(feed.chips[0].token.as_deref(), Some("chip-token"));
        assert_eq!(feed.videos.len(), 2);
        assert_eq!(feed.continuation.as_deref(), Some("next-page"));
        // Members-only items never reach the recommended grid.
        let recommendable = recommendable_videos(feed.videos);
        assert_eq!(recommendable.len(), 1);
    }

    #[test]
    fn reads_continuation_pages_from_action_items() {
        let root = json!({
            "onResponseReceivedActions": [{ "appendContinuationItemsAction": { "continuationItems": [
                { "richItemRenderer": { "content": { "videoRenderer": renderer(json!({ "videoId": "page2" })) } } }
            ] } }]
        });
        let feed = parse_feed(&root);
        assert_eq!(feed.videos.len(), 1);
        assert_eq!(feed.videos[0].video_id, "page2");
    }

    #[test]
    fn parses_search_sections_and_flags_age_restrictions() {
        let root = json!({
            "contents": { "twoColumnSearchResultsRenderer": { "primaryContents": { "sectionListRenderer": { "contents": [
                { "itemSectionRenderer": { "contents": [
                    { "videoRenderer": renderer(json!({})) },
                    { "channelRenderer": {
                        "channelId": "UC7",
                        "title": { "simpleText": "Some channel" },
                        "subscriberCountText": { "simpleText": "10K subscribers" }
                    } },
                    { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "more" } } } }
                ] } }
            ] } } } }
        });
        let results = parse_search(&root);
        assert_eq!(results.entries.len(), 2);
        assert!(matches!(results.entries[0], SearchEntry::Video { .. }));
        assert!(matches!(results.entries[1], SearchEntry::Channel { .. }));
        assert_eq!(results.continuation.as_deref(), Some("more"));
        assert!(!results.restricted);

        let restricted = parse_search(&json!({
            "contents": { "backgroundPromoRenderer": { "title": { "simpleText": "Confirm your age" } } }
        }));
        assert!(restricted.restricted);
        assert!(restricted.entries.is_empty());
    }

    struct FakeTransport {
        calls: RefCell<Vec<String>>,
        urls: RefCell<Vec<String>>,
        response: String,
    }

    impl Transport for FakeTransport {
        fn post(&self, url: &str, headers: &[(String, String)], body: &str) -> Result<String, String> {
            assert!(url.starts_with("https://www.youtube.com/youtubei/v1/"));
            assert!(url.contains("key=AIza"));
            assert!(headers.iter().any(|(k, _)| k == "x-youtube-client-version"));
            self.calls.borrow_mut().push(body.to_string());
            self.urls.borrow_mut().push(url.to_string());
            Ok(self.response.clone())
        }
    }

    #[test]
    fn client_posts_the_expected_body_and_caches() {
        let response = json!({
            "contents": { "twoColumnSearchResultsRenderer": { "primaryContents": { "sectionListRenderer": { "contents": [
                { "itemSectionRenderer": { "contents": [ { "videoRenderer": renderer(json!({})) } ] } }
            ] } } } }
        })
        .to_string();
        let transport = FakeTransport {
            calls: RefCell::new(Vec::new()),
            urls: RefCell::new(Vec::new()),
            response,
        };
        let mut client = InnerTube::new(transport, default_config((2026, 11, 13)), "en", "US");

        let results = client.raw_search("cats", Some("EgIQAQ==")).unwrap();
        assert_eq!(results.entries.len(), 1);
        assert!(client.transport.urls.borrow()[0].contains("/youtubei/v1/search?"));

        let body: Value = serde_json::from_str(&client.transport.calls.borrow()[0]).unwrap();
        assert_eq!(body["query"], "cats");
        assert_eq!(body["params"], "EgIQAQ==");
        assert_eq!(body["context"]["client"]["clientName"], "WEB");
        assert_eq!(body["context"]["client"]["gl"], "US");

        // The home feed is cached for ten minutes: the second call is a no-op.
        let _ = client.home(None, false).unwrap_or(Feed { chips: vec![], videos: vec![], continuation: None });
        let before = client.transport.calls.borrow().len();
        let _ = client.home(None, false);
        assert_eq!(client.transport.calls.borrow().len(), before);
    }

    #[test]
    fn default_config_uses_todays_date() {
        let cfg = default_config((2026, 1, 5));
        assert_eq!(cfg.version, "2.20260105.00.00");
        assert!(cfg.key.starts_with("AIza"));
    }

    #[test]
    fn with_retry_stops_at_the_first_success_and_surfaces_the_last_error() {
        let calls = RefCell::new(0);
        let ok = with_retry(
            3,
            || {
                *calls.borrow_mut() += 1;
                if *calls.borrow() < 2 { Err("blip") } else { Ok("page") }
            },
            |_| {},
        );
        assert_eq!(ok, Ok("page"));
        assert_eq!(*calls.borrow(), 2);

        let failing = RefCell::new(0);
        let error = with_retry::<(), _>(
            3,
            || {
                *failing.borrow_mut() += 1;
                Err(format!("attempt {}", *failing.borrow()))
            },
            |_| {},
        )
        .unwrap_err();
        assert_eq!(error, "attempt 3");
        assert_eq!(*failing.borrow(), 3);
    }

    fn search_response() -> String {
        json!({
            "contents": { "twoColumnSearchResultsRenderer": { "primaryContents": { "sectionListRenderer": { "contents": [
                { "itemSectionRenderer": { "contents": [ { "videoRenderer": renderer(json!({})) } ] } }
            ] } } } }
        })
        .to_string()
    }

    /// Answers with an error for the first `failures` posts, then a search page.
    struct FlakyTransport {
        calls: RefCell<u32>,
        failures: u32,
        response: String,
    }

    impl Transport for FlakyTransport {
        fn post(&self, _url: &str, _headers: &[(String, String)], _body: &str) -> Result<String, String> {
            let mut calls = self.calls.borrow_mut();
            *calls += 1;
            if *calls <= self.failures {
                Err(format!("transient failure {}", *calls))
            } else {
                Ok(self.response.clone())
            }
        }
    }

    #[test]
    fn a_transient_search_failure_is_retried_and_succeeds() {
        let tube = InnerTube::new(
            FlakyTransport {
                calls: RefCell::new(0),
                failures: 1,
                response: search_response(),
            },
            default_config((2026, 11, 14)),
            "en",
            "US",
        );
        let results = tube.raw_search("cats", None).unwrap();
        assert_eq!(results.entries.len(), 1);
        assert_eq!(*tube.transport.calls.borrow(), 2);
    }

    #[test]
    fn search_reports_the_error_after_three_failed_attempts() {
        let tube = InnerTube::new(
            FlakyTransport {
                calls: RefCell::new(0),
                failures: 99,
                response: search_response(),
            },
            default_config((2026, 11, 14)),
            "en",
            "US",
        );
        let error = tube.raw_search("cats", None).unwrap_err();
        assert!(error.to_string().contains("transient failure"));
        assert_eq!(*tube.transport.calls.borrow(), SEARCH_ATTEMPTS);
    }

    #[test]
    fn search_continuations_share_the_same_retry_policy() {
        let tube = InnerTube::new(
            FlakyTransport {
                calls: RefCell::new(0),
                failures: 1,
                response: search_response(),
            },
            default_config((2026, 11, 14)),
            "en",
            "US",
        );
        let results = tube.search_continuation("token").unwrap();
        assert_eq!(results.entries.len(), 1);
        assert_eq!(*tube.transport.calls.borrow(), 2);
    }
}
