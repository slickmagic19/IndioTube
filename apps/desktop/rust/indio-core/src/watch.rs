//! Watch page, chapters, the "Up next" continuation and comments - the Rust
//! port of `parseWatch`, `parseWatchContinuation`, `parseChapters` and
//! `parseComments` in electron/inTube.js.
//!
//! All four are pure functions over an InnerTube `next` response. Comments are
//! assembled from `frameworkUpdates.entityBatchUpdate` entities plus the
//! continuation items, which is what the current layout actually ships.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::innertube::{
    at, at_str, continuation_action_items, extract_continuation, find_all, has_members_badge,
    is_verified, last_thumb_url, parse_any_video, parse_lockup, parse_video_renderer, runs_text,
    VideoItem,
};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Chapter {
    pub title: String,
    pub start_ms: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WatchInfo {
    pub video_id: String,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_avatar: Option<String>,
    pub channel_verified: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subscribers_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub views_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub published_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub likes_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub related: Vec<VideoItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub related_token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comments_token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comments_count_text: Option<String>,
    pub members_only: bool,
    pub chapters: Vec<Chapter>,
    pub is_live: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WatchContinuation {
    pub related: Vec<VideoItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Comment {
    pub id: String,
    pub author: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub avatar: Option<String>,
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub likes_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub published_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reply_count_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub replies_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommentsPage {
    pub comments: Vec<Comment>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation: Option<String>,
}

fn text_or_none(value: Option<String>) -> Option<String> {
    let trimmed = value?.trim().to_string();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

/// "… along with 12K other people" or a like-button label; whichever shows a
/// number first.
fn extract_likes(scope: &Value) -> Option<String> {
    let re = regex::Regex::new(r"along with ([\d.,]+[KMB]?) other").unwrap();
    for ad in find_all(scope, "accessibilityData", 12, 60) {
        if let Some(label) = at_str(&ad, "label") {
            if let Some(caps) = re.captures(&label) {
                return Some(caps[1].to_string());
            }
        }
    }
    for btn in find_all(scope, "segmentedLikeDislikeButtonViewModel", 12, 4) {
        for tb in find_all(&btn, "toggleButtonViewModel", 8, 8) {
            let text = at_str(&tb, "defaultButtonViewModel.buttonViewModel.title")
                .or_else(|| at_str(&tb, "toggledButtonViewModel.buttonViewModel.title"));
            if let Some(text) = text {
                if text.chars().any(|c| c.is_ascii_digit()) {
                    return Some(text);
                }
            }
        }
    }
    None
}

pub fn parse_chapters(root: &Value) -> Vec<Chapter> {
    let Some(bar) = find_all(root, "multiMarkersPlayerBarRenderer", 12, 4).into_iter().next() else {
        return Vec::new();
    };
    let Some(maps) = bar.get("markersMap").and_then(Value::as_array) else {
        return Vec::new();
    };
    let candidates: Vec<&Value> = maps
        .iter()
        .filter(|m| at(m, "value.chapters").and_then(Value::as_array).is_some())
        .collect();
    let chosen = candidates
        .iter()
        .find(|m| at_str(m, "key").as_deref() == Some("DESCRIPTION_CHAPTERS"))
        .or_else(|| candidates.first());
    let Some(chosen) = chosen else { return Vec::new() };
    let Some(items) = at(chosen, "value.chapters").and_then(Value::as_array) else {
        return Vec::new();
    };

    let mut out: Vec<Chapter> = Vec::new();
    for el in items {
        let Some(ch) = el.get("chapterRenderer") else { continue };
        let title_el = at(ch, "title");
        let title = runs_text(title_el)
            .or_else(|| title_el.and_then(|t| at_str(t, "simpleText")))
            .unwrap_or_default()
            .trim()
            .to_string();
        let start = at_str(ch, "timeRangeStartMillis")
            .and_then(|v| v.parse::<f64>().ok());
        let Some(start) = start else { continue };
        if title.is_empty() || !start.is_finite() || start < 0.0 {
            continue;
        }
        if !out.iter().any(|c| c.start_ms == start) {
            out.push(Chapter { title, start_ms: start });
        }
    }
    out.sort_by(|a, b| a.start_ms.partial_cmp(&b.start_ms).unwrap_or(std::cmp::Ordering::Equal));
    out
}

fn related_from_results(result_list: &[Value]) -> Vec<VideoItem> {
    let mut related = Vec::new();
    for el in result_list {
        let mut matched = false;
        for key in ["compactVideoRenderer", "videoRenderer"] {
            if let Some(r) = el.get(key) {
                if let Some(v) = parse_video_renderer(r) {
                    related.push(v);
                }
                matched = true;
                break;
            }
        }
        if matched {
            continue;
        }
        if let Some(lv) = el.get("lockupViewModel") {
            if let Some(v) = parse_lockup(lv) {
                related.push(v);
            }
        }
    }
    related
}

pub fn parse_watch(root: &Value, video_id: &str) -> WatchInfo {
    let primary_nodes = find_all(root, "videoPrimaryInfoRenderer", 10, 2);
    let primary = primary_nodes.first();
    let secondary_nodes = find_all(root, "videoSecondaryInfoRenderer", 10, 2);
    let secondary = secondary_nodes.first();

    let title = primary
        .and_then(|p| {
            let title_el = at(p, "title");
            runs_text(title_el).or_else(|| at_str(p, "title.simpleText"))
        })
        .or_else(|| {
            at_str(
                root,
                "contents.twoColumnWatchNextResults.results.results.contents.0.videoPrimaryInfoRenderer.title.simpleText",
            )
        })
        .unwrap_or_default()
        .trim()
        .to_string();

    let view_count_renderer = primary.and_then(|p| at(p, "viewCount.videoViewCountRenderer"));
    let views_text = view_count_renderer
        .and_then(|v| {
            let vc = at(v, "viewCount");
            runs_text(vc).or_else(|| at_str(v, "viewCount.simpleText"))
        })
        .or_else(|| {
            view_count_renderer
                .and_then(|v| runs_text(at(v, "shortViewCount")))
        });
    let published_text = primary.and_then(|p| {
        at_str(p, "dateText.simpleText").or_else(|| at_str(p, "relativeDateText.simpleText"))
    });

    let owner_nodes = find_all(root, "videoOwnerRenderer", 10, 2);
    let owner = secondary
        .and_then(|s| at(s, "owner.videoOwnerRenderer"))
        .or_else(|| owner_nodes.first());

    let channel_name = owner.and_then(|o| {
        let title_el = at(o, "title");
        runs_text(title_el).or_else(|| at_str(o, "title.simpleText"))
    });
    let channel_id = owner
        .and_then(|o| at_str(o, "title.runs.0.navigationEndpoint.browseEndpoint.browseId"))
        .or_else(|| owner.and_then(|o| at_str(o, "navigationEndpoint.browseEndpoint.browseId")));
    let channel_avatar = last_thumb_url(owner.and_then(|o| at(o, "thumbnail")));
    let subscribers_text = owner.and_then(|o| {
        let el = at(o, "subscriberCountText")?;
        at_str(el, "simpleText").or_else(|| runs_text(Some(el)))
    });

    let description = secondary
        .and_then(|s| at_str(s, "attributedDescription.content"))
        .or_else(|| secondary.and_then(|s| runs_text(at(s, "description"))))
        .or_else(|| secondary.and_then(|s| at_str(s, "description.simpleText")));

    let likes_text = extract_likes(primary.unwrap_or(root));

    // Members-only videos come back UNPLAYABLE from the logged-out API.
    let members_re = regex::Regex::new(r"(?i)\bmembers?[- ]only\b|\bchannel members?\b").unwrap();
    let reason = at_str(root, "playabilityStatus.reason");
    let members_only = reason.as_deref().map(|r| members_re.is_match(r)).unwrap_or(false)
        || primary.map(|p| has_members_badge(p, 8)).unwrap_or(false)
        || at(root, "playerOverlays")
            .map(|p| has_members_badge(p, 10))
            .unwrap_or(false);

    let mut result_list: Vec<Value> = at(
        root,
        "contents.twoColumnWatchNextResults.secondaryResults.secondaryResults.results",
    )
    .and_then(Value::as_array)
    .cloned()
    .unwrap_or_default();
    if result_list.is_empty() {
        for sec in find_all(root, "itemSectionRenderer", 12, 20) {
            if let Some(contents) = sec.get("contents").and_then(Value::as_array) {
                result_list.extend(contents.iter().cloned());
            }
        }
    }
    let related = related_from_results(&result_list);

    // The Up next rail ends with a continuation item; its token is what lets
    // the watch page append more recommendations on demand.
    let mut related_token = None;
    for el in result_list.iter().rev() {
        if let Some(cont) = el.get("continuationItemRenderer") {
            related_token = extract_continuation(cont);
            if related_token.is_some() {
                break;
            }
        }
    }
    if related_token.is_none() {
        if let Some(sec) = at(
            root,
            "contents.twoColumnWatchNextResults.secondaryResults.secondaryResults",
        ) {
            for cont in find_all(sec, "continuationItemRenderer", 8, 4) {
                related_token = extract_continuation(&cont);
                if related_token.is_some() {
                    break;
                }
            }
        }
    }

    let mut comments_token = None;
    let mut comments_count = None;
    for cp in find_all(root, "commentsEntryPointHeaderRenderer", 14, 2) {
        if comments_token.is_none() {
            comments_token = extract_continuation(&cp)
                .or_else(|| at_str(&cp, "continuationEndpoint.continuationCommand.token"));
        }
        if comments_count.is_none() {
            comments_count = at(&cp, "commentCount")
                .and_then(|cc| runs_text(Some(cc)).or_else(|| at_str(cc, "simpleText")))
                .or_else(|| at_str(&cp, "commentsCount.simpleText"));
        }
    }
    if comments_token.is_none() {
        for cs in find_all(root, "commentsSectionRenderer", 14, 2) {
            comments_token = at_str(
                &cs,
                "continuations.0.continuationEndpoint.continuationCommand.token",
            );
        }
    }
    if comments_token.is_none() {
        if let Some(panels) = root.get("engagementPanels").and_then(Value::as_array) {
            for p in panels {
                let Some(panel) = p.get("engagementPanelSectionListRenderer") else { continue };
                let Some(id) = at_str(panel, "panelIdentifier") else { continue };
                if !id.contains("comments") {
                    continue;
                }
                for cc in find_all(panel, "continuationCommand", 12, 2) {
                    if comments_token.is_none() {
                        comments_token = at_str(&cc, "token");
                    }
                }
                if comments_count.is_none() {
                    comments_count = at(
                        panel,
                        "header.engagementPanelTitleHeaderRenderer.contextualInfo",
                    )
                    .and_then(|ci| runs_text(Some(ci)).or_else(|| at_str(ci, "simpleText")));
                }
            }
        }
    }

    // A live badge on the primary info (or the "N watching" counter) is
    // authoritative even when the chat panel is collapsed.
    let live_badge = find_all(primary.unwrap_or(root), "metadataBadgeRenderer", 8, 20)
        .iter()
        .any(|badge| {
            at_str(badge, "style").as_deref() == Some("BADGE_STYLE_TYPE_LIVE_NOW")
                || at_str(badge, "label")
                    .map(|l| l.to_uppercase() == "LIVE")
                    .unwrap_or(false)
        });
    let is_live = live_badge
        || view_count_renderer
            .map(|v| at_str(v, "isLive").as_deref() == Some("true"))
            .unwrap_or(false);

    let mut seen: Vec<String> = Vec::new();
    let related: Vec<VideoItem> = related
        .into_iter()
        .filter(|v| !v.members_only)
        .filter(|v| v.video_id != video_id)
        .filter(|v| {
            if seen.contains(&v.video_id) {
                false
            } else {
                seen.push(v.video_id.clone());
                true
            }
        })
        .collect();

    let verified = owner
        .map(|o| {
            let mut nodes: Vec<&Value> = Vec::new();
            if let Some(b) = o.get("badges") {
                nodes.push(b);
            }
            if let Some(t) = o.get("attributedTitle") {
                nodes.push(t);
            }
            is_verified(&nodes)
        })
        .unwrap_or(false);

    let chapters = parse_chapters(root);

    WatchInfo {
        video_id: video_id.to_string(),
        title,
        channel_id,
        channel_name,
        channel_avatar,
        channel_verified: verified,
        subscribers_text,
        views_text,
        published_text,
        likes_text,
        description: text_or_none(description),
        related,
        related_token,
        comments_token,
        comments_count_text: text_or_none(comments_count),
        members_only,
        chapters,
        is_live,
    }
}

/// A continuation of the watch page's secondary results (the Up next rail).
/// The appended items are bare lockups/renderers inside
/// `appendContinuationItemsAction`, not wrapped in a rich grid.
pub fn parse_watch_continuation(root: &Value) -> WatchContinuation {
    let mut videos: Vec<VideoItem> = Vec::new();
    let mut continuation = None;
    for el in continuation_action_items(root) {
        if let Some(cont) = el.get("continuationItemRenderer") {
            if let Some(t) = extract_continuation(cont) {
                continuation = Some(t);
            }
            continue;
        }
        let mut video = None;
        for key in ["compactVideoRenderer", "videoRenderer", "playlistVideoRenderer"] {
            if let Some(renderer) = el.get(key) {
                video = parse_video_renderer(renderer);
                if video.is_some() {
                    break;
                }
            }
        }
        if video.is_none() {
            if let Some(lv) = el.get("lockupViewModel") {
                video = parse_lockup(lv);
            }
        }
        if video.is_none() {
            if let Some(rich) = el.get("richItemRenderer") {
                video = rich.get("content").and_then(parse_any_video);
            }
        }
        if let Some(v) = video {
            if !v.video_id.is_empty() {
                videos.push(v);
            }
        }
    }
    let mut seen: Vec<String> = Vec::new();
    let related = videos
        .into_iter()
        .filter(|v| !v.members_only)
        .filter(|v| {
            if seen.contains(&v.video_id) {
                false
            } else {
                seen.push(v.video_id.clone());
                true
            }
        })
        .collect();
    WatchContinuation {
        related,
        continuation,
    }
}

// ---- comments -----------------------------------------------------------------

fn collect_comment_entities(root: &Value) -> Vec<(String, Value)> {
    let mut out = Vec::new();
    let mutations = at(root, "frameworkUpdates.entityBatchUpdate.mutations")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    for m in mutations {
        let Some(payload) = at(&m, "payload.commentEntityPayload") else { continue };
        if let Some(id) = at_str(payload, "properties.commentId") {
            out.push((id, payload.clone()));
        }
    }
    out
}

fn collect_continuation_items(root: &Value) -> Vec<Value> {
    let mut out = Vec::new();
    let push_items = |node: &Value, out: &mut Vec<Value>| {
        for path in [
            "appendContinuationItemsAction.continuationItems",
            "reloadContinuationItemsCommand.continuationItems",
        ] {
            if let Some(items) = at(node, path).and_then(Value::as_array) {
                out.extend(items.iter().cloned());
            }
        }
    };
    if let Some(endpoints) = root.get("onResponseReceivedEndpoints").and_then(Value::as_array) {
        for ep in endpoints {
            push_items(ep, &mut out);
            if let Some(cmds) = at(ep, "commandExecutorCommand.commands").and_then(Value::as_array) {
                for cmd in cmds {
                    push_items(cmd, &mut out);
                }
            }
        }
    }
    if out.is_empty() {
        if let Some(actions) = root.get("onResponseReceivedActions").and_then(Value::as_array) {
            for ep in actions {
                if let Some(items) = at(ep, "appendContinuationItemsAction.continuationItems")
                    .and_then(Value::as_array)
                {
                    out.extend(items.iter().cloned());
                }
            }
        }
    }
    if out.is_empty() {
        // Sometimes the section is inlined (initial render).
        for t in find_all(root, "commentThreadRenderer", 14, 40) {
            out.push(serde_json::json!({ "commentThreadRenderer": t }));
        }
    }
    out
}

fn reply_token(thread: &Value) -> Option<String> {
    let replies = thread.get("replies")?;
    if let Some(direct) = at_str(
        replies,
        "commentRepliesRenderer.contents.0.continuationItemRenderer.continuationEndpoint.continuationCommand.token",
    ) {
        return Some(direct);
    }
    for cont in find_all(replies, "continuationItemRenderer", 8, 4) {
        if let Some(t) = extract_continuation(&cont) {
            return Some(t);
        }
    }
    None
}

fn format_replies(raw: Option<String>) -> Option<String> {
    let trimmed = raw?.trim().to_string();
    if trimmed.is_empty() {
        return None;
    }
    let count_re = regex::Regex::new(r"^[\d.,]+[KMB]?$").unwrap();
    Some(if count_re.is_match(&trimmed) {
        format!("{trimmed} replies")
    } else {
        trimmed
    })
}

fn parse_comment_thread(thread: &Value, entities: &[(String, Value)]) -> Option<Comment> {
    let vm = at(thread, "commentViewModel.commentViewModel").or_else(|| thread.get("commentViewModel"))?;
    let comment_id = at_str(vm, "commentId").or_else(|| at_str(vm, "commentKey"))?;
    let entity = entities
        .iter()
        .find(|(id, _)| id == &comment_id)
        .map(|(_, e)| e);

    let author = entity
        .and_then(|e| at_str(e, "author.displayName"))
        .or_else(|| at_str(vm, "author.displayName"))
        .unwrap_or_default();
    let avatar = entity
        .and_then(|e| at_str(e, "author.avatarThumbnailUrl"))
        .and_then(|u| crate::innertube::normalize_image_url(Some(&u)))
        .or_else(|| last_thumb_url(at(vm, "author.avatar")));
    let content_el = at(vm, "content");
    let text = entity
        .and_then(|e| at_str(e, "properties.content.content"))
        .or_else(|| content_el.and_then(|c| at_str(c, "content")))
        .or_else(|| runs_text(content_el))?;
    if text.trim().is_empty() {
        return None;
    }
    let likes = entity
        .and_then(|e| {
            at_str(e, "toolbar.likeCountNotliked").or_else(|| at_str(e, "toolbar.likeCountLiked"))
        })
        .or_else(|| at_str(vm, "likeCount.likeCountEntity.likeCount"))
        .or_else(|| at_str(vm, "likeCount.simpleText"))
        .or_else(|| at_str(vm, "voteCount.simpleText"));
    let published = entity
        .and_then(|e| at_str(e, "properties.publishedTime"))
        .or_else(|| at_str(vm, "publishedTimeText.simpleText"))
        .or_else(|| runs_text(at(vm, "publishedTimeText")))
        .or_else(|| at_str(vm, "publishedTimeText.content"));
    let raw_replies = entity
        .and_then(|e| at_str(e, "toolbar.replyCount"))
        .or_else(|| at_str(vm, "replyCount"))
        .or_else(|| at_str(vm, "repliesCountText.simpleText"));

    Some(Comment {
        id: comment_id,
        author,
        avatar,
        text,
        likes_text: likes,
        published_text: published,
        reply_count_text: format_replies(raw_replies),
        replies_token: reply_token(thread),
    })
}

/// Old layouts ship a flat `commentRenderer` instead of view models.
fn parse_comment_renderer(c: &Value) -> Option<Comment> {
    let text = runs_text(at(c, "contentText"))?;
    if text.trim().is_empty() {
        return None;
    }
    let id = at_str(c, "commentId").unwrap_or_else(|| format!("c{}", js_hash_abs(&text)));
    let author = at_str(c, "authorText.simpleText").unwrap_or_default();
    let avatar = last_thumb_url(at(c, "authorThumbnail"));
    let likes = at_str(c, "voteCount.simpleText");
    let published = runs_text(at(c, "publishedTimeText"));
    let reply_count = at_str(c, "replyCount");
    Some(Comment {
        id,
        author,
        avatar,
        text,
        likes_text: likes,
        published_text: published,
        reply_count_text: reply_count.map(|n| format!("{n} replies")),
        replies_token: None,
    })
}

/// `Math.abs(hashCode(text))` from the JavaScript, over UTF-16 code units.
fn js_hash_abs(text: &str) -> i64 {
    let mut h: i32 = 0;
    for unit in text.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(unit as i32);
    }
    (h as i64).abs()
}

pub fn parse_comments(root: &Value) -> CommentsPage {
    let entities = collect_comment_entities(root);
    let items = collect_continuation_items(root);
    let mut comments = Vec::new();
    let mut continuation = None;
    for el in &items {
        if let Some(thread) = el.get("commentThreadRenderer") {
            if let Some(c) = parse_comment_thread(thread, &entities) {
                comments.push(c);
                continue;
            }
        }
        if let Some(cr) = el.get("commentRenderer") {
            if let Some(c) = parse_comment_renderer(cr) {
                comments.push(c);
                continue;
            }
        }
        if el.get("commentViewModel").is_some() {
            if let Some(c) = parse_comment_thread(el, &entities) {
                comments.push(c);
                continue;
            }
        }
        if let Some(cont) = el.get("continuationItemRenderer") {
            if let Some(t) = extract_continuation(cont) {
                continuation = Some(t);
            }
        }
    }
    if continuation.is_none() && !comments.is_empty() {
        for cont in find_all(root, "continuationItemRenderer", 14, 6) {
            if let Some(t) = extract_continuation(&cont) {
                continuation = Some(t);
                break;
            }
        }
    }
    CommentsPage {
        comments,
        continuation,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn video_owner() -> Value {
        json!({
            "videoOwnerRenderer": {
                "title": { "runs": [{
                    "text": "Cat Channel",
                    "navigationEndpoint": { "browseEndpoint": { "browseId": "UCcat" } }
                }]},
                "thumbnail": { "thumbnails": [{ "url": "//yt3.example/avatar.jpg" }] },
                "subscriberCountText": { "simpleText": "1.2M subscribers" },
                "badges": [{ "metadataBadgeRenderer": { "style": "BADGE_STYLE_TYPE_VERIFIED" } }]
            }
        })
    }

    #[test]
    fn parses_the_watch_header_and_owner() {
        let root = json!({
            "contents": { "twoColumnWatchNextResults": {
                "results": { "results": { "contents": [{
                    "videoPrimaryInfoRenderer": {
                        "title": { "runs": [{ "text": "Cats being cats" }] },
                        "viewCount": { "videoViewCountRenderer": {
                            "viewCount": { "simpleText": "1,234,567 views" },
                            "isLive": "true"
                        }},
                        "dateText": { "simpleText": "Nov 1, 2026" }
                    }
                }]}},
                "secondaryResults": { "secondaryResults": { "results": [
                    { "compactVideoRenderer": {
                        "videoId": "rel1",
                        "title": { "simpleText": "Related one" },
                        "lengthText": { "simpleText": "4:20" },
                        "ownerText": { "runs": [{ "text": "Other", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCother" }}}] }
                    }},
                    { "continuationItemRenderer": {
                        "continuationEndpoint": { "continuationCommand": { "token": "REL_TOKEN" } }
                    }}
                ]}},
                "secondary": video_owner()
            }},
            "playabilityStatus": { "status": "OK" }
        });
        let w = parse_watch(&root, "vid1");
        assert_eq!(w.title, "Cats being cats");
        assert_eq!(w.views_text.as_deref(), Some("1,234,567 views"));
        assert_eq!(w.published_text.as_deref(), Some("Nov 1, 2026"));
        assert_eq!(w.channel_name.as_deref(), Some("Cat Channel"));
        assert_eq!(w.channel_id.as_deref(), Some("UCcat"));
        assert_eq!(w.channel_avatar.as_deref(), Some("https://yt3.example/avatar.jpg"));
        assert_eq!(w.subscribers_text.as_deref(), Some("1.2M subscribers"));
        assert!(w.channel_verified);
        assert!(w.is_live);
        assert_eq!(w.related.len(), 1);
        assert_eq!(w.related[0].video_id, "rel1");
        assert_eq!(w.related_token.as_deref(), Some("REL_TOKEN"));
        assert_eq!(w.comments_token, None);
        assert!(!w.members_only);
    }

    #[test]
    fn drops_the_playing_video_and_members_only_related_items() {
        let root = json!({
            "contents": { "twoColumnWatchNextResults": {
                "secondaryResults": { "secondaryResults": { "results": [
                    { "compactVideoRenderer": { "videoId": "vid1", "title": { "simpleText": "self" } } },
                    { "compactVideoRenderer": {
                        "videoId": "m1", "title": { "simpleText": "members" },
                        "badges": [{ "metadataBadgeRenderer": { "label": "Members only" } }]
                    }},
                    { "compactVideoRenderer": { "videoId": "ok1", "title": { "simpleText": "keep" } } },
                    { "compactVideoRenderer": { "videoId": "ok1", "title": { "simpleText": "dupe" } } }
                ]}}
            }}
        });
        let w = parse_watch(&root, "vid1");
        assert_eq!(w.related.len(), 1);
        assert_eq!(w.related[0].video_id, "ok1");
    }

    #[test]
    fn reads_likes_from_the_accessibility_label() {
        let root = json!({
            "videoPrimaryInfoRenderer": {
                "title": { "simpleText": "t" },
                "likeButton": { "toggleButtonRenderer": { "accessibilityData": {
                    "label": "Like this video along with 12K other people"
                }}}
            }
        });
        assert_eq!(parse_watch(&root, "v").likes_text.as_deref(), Some("12K"));
    }

    #[test]
    fn reads_likes_from_the_segmented_button_when_the_label_is_missing() {
        let root = json!({
            "videoPrimaryInfoRenderer": {
                "title": { "simpleText": "t" },
                "segmentedLikeDislikeButtonViewModel": { "likeButtonViewModel": {
                    "toggleButtonViewModel": { "defaultButtonViewModel": {
                        "buttonViewModel": { "title": "458" }
                    }}
                }}
            }
        });
        assert_eq!(parse_watch(&root, "v").likes_text.as_deref(), Some("458"));
    }

    #[test]
    fn flags_members_only_from_the_playability_reason() {
        let root = json!({ "playabilityStatus": { "reason": "This video is available to this channel's members only" } });
        let w = parse_watch(&root, "v");
        assert!(w.members_only);
        assert!(w.related.is_empty());
    }

    #[test]
    fn picks_the_comments_token_and_count_from_the_entry_point() {
        let root = json!({
            "contents": { "itemSectionRenderer": { "contents": [
                { "commentsEntryPointHeaderRenderer": {
                    "headerText": { "simpleText": "Comments" },
                    "commentCount": { "simpleText": "42" },
                    "continuationEndpoint": { "continuationCommand": { "token": "CMT_TOKEN" } }
                }}
            ]}}
        });
        let w = parse_watch(&root, "v");
        assert_eq!(w.comments_token.as_deref(), Some("CMT_TOKEN"));
        assert_eq!(w.comments_count_text.as_deref(), Some("42"));
    }

    #[test]
    fn falls_back_to_the_comments_panel_for_the_token() {
        let root = json!({
            "engagementPanels": [
                { "engagementPanelSectionListRenderer": { "panelIdentifier": "engagement-panel-searchable-transcript" } },
                { "engagementPanelSectionListRenderer": {
                    "panelIdentifier": "engagement-panel-comments-section",
                    "header": { "engagementPanelTitleHeaderRenderer": {
                        "contextualInfo": { "runs": [{ "text": "1.1K" }] }
                    }},
                    "content": { "sectionListRenderer": { "contents": [{
                        "itemSectionRenderer": { "contents": [{
                            "continuationItemRenderer": { "continuationEndpoint": {
                                "continuationCommand": { "token": "PANEL_TOKEN" }
                            }}
                        }]}
                    }]}}
                }}
            ]
        });
        let w = parse_watch(&root, "v");
        assert_eq!(w.comments_token.as_deref(), Some("PANEL_TOKEN"));
        assert_eq!(w.comments_count_text.as_deref(), Some("1.1K"));
    }

    #[test]
    fn trims_the_description_and_prefers_attributed_text() {
        let root = json!({
            "videoSecondaryInfoRenderer": {
                "attributedDescription": { "content": "  Line one\nLine two  " },
                "description": { "runs": [{ "text": "ignored" }] }
            }
        });
        assert_eq!(parse_watch(&root, "v").description.as_deref(), Some("Line one\nLine two"));
    }

    #[test]
    fn chapters_are_deduped_and_sorted() {
        let root = json!({
            "playerOverlays": { "playerOverlayRenderer": { "decoratedPlayerBarRenderer": {
                "decoratedPlayerBarRenderer": { "playerBar": { "multiMarkersPlayerBarRenderer": {
                    "markersMap": [
                        { "key": "AUTO_CHAPTERS", "value": { "chapters": [
                            { "chapterRenderer": { "title": { "simpleText": "auto" }, "timeRangeStartMillis": 5000 } }
                        ]}},
                        { "key": "DESCRIPTION_CHAPTERS", "value": { "chapters": [
                            { "chapterRenderer": { "title": { "runs": [{ "text": " Intro " }] }, "timeRangeStartMillis": 3000 } },
                            { "chapterRenderer": { "title": { "runs": [{ "text": "Start" }] }, "timeRangeStartMillis": 0 } },
                            { "chapterRenderer": { "title": { "runs": [{ "text": "Intro again" }] }, "timeRangeStartMillis": 3000 } },
                            { "chapterRenderer": { "title": { "runs": [{ "text": "" }] }, "timeRangeStartMillis": 9000 } }
                        ]}}
                    ]
                }}}}
            }}
        });
        let chapters = parse_chapters(&root);
        assert_eq!(chapters.len(), 2);
        assert_eq!(chapters[0].title, "Start");
        assert_eq!(chapters[0].start_ms, 0.0);
        assert_eq!(chapters[1].title, "Intro");
        assert_eq!(chapters[1].start_ms, 3000.0);
    }

    #[test]
    fn watch_continuation_parses_appended_lockups() {
        let root = json!({
            "onResponseReceivedEndpoints": [{
                "appendContinuationItemsAction": { "continuationItems": [
                    { "lockupViewModel": {
                        "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                        "contentId": "more1",
                        "metadata": { "lockupMetadataViewModel": {
                            "title": { "content": "More one" },
                            "metadata": { "contentMetadataViewModel": { "metadataRows": [] } }
                        }}
                    }},
                    { "compactVideoRenderer": { "videoId": "more2", "title": { "simpleText": "More two" } } },
                    { "continuationItemRenderer": { "continuationEndpoint": {
                        "continuationCommand": { "token": "NEXT_TOKEN" }
                    }}}
                ]}
            }]
        });
        let c = parse_watch_continuation(&root);
        assert_eq!(c.related.len(), 2);
        assert_eq!(c.related[0].video_id, "more1");
        assert_eq!(c.continuation.as_deref(), Some("NEXT_TOKEN"));
    }

    #[test]
    fn comments_come_from_entities_and_continuation_items() {
        let root = json!({
            "frameworkUpdates": { "entityBatchUpdate": { "mutations": [
                { "payload": { "commentEntityPayload": {
                    "properties": { "commentId": "c1", "content": { "content": "Nice video!" },
                                    "publishedTime": "2 days ago" },
                    "author": { "displayName": "@catfan", "avatarThumbnailUrl": "//yt3.example/c.jpg" },
                    "toolbar": { "likeCountNotliked": "12", "replyCount": "3" }
                }}},
                { "payload": { "commentEntityPayload": {
                    "properties": { "commentId": "c2", "content": { "content": "second" }, "publishedTime": "1 hour ago" },
                    "author": { "displayName": "@other" },
                    "toolbar": { "likeCountLiked": "4" }
                }}}
            ]}},
            "onResponseReceivedEndpoints": [{
                "appendContinuationItemsAction": { "continuationItems": [
                    { "commentThreadRenderer": { "commentViewModel": { "commentViewModel": { "commentId": "c1" } } } },
                    { "commentThreadRenderer": {
                        "commentViewModel": { "commentId": "c2" },
                        "replies": { "commentRepliesRenderer": { "contents": [{
                            "continuationItemRenderer": { "continuationEndpoint": {
                                "continuationCommand": { "token": "REPLY_TOKEN" }
                            }}
                        }]}}
                    }},
                    { "continuationItemRenderer": { "continuationEndpoint": {
                        "continuationCommand": { "token": "MORE_COMMENTS" }
                    }}}
                ]}
            }]
        });
        let page = parse_comments(&root);
        assert_eq!(page.comments.len(), 2);
        assert_eq!(page.comments[0].id, "c1");
        assert_eq!(page.comments[0].author, "@catfan");
        assert_eq!(
            page.comments[0].avatar.as_deref(),
            Some("https://yt3.example/c.jpg")
        );
        assert_eq!(page.comments[0].text, "Nice video!");
        assert_eq!(page.comments[0].likes_text.as_deref(), Some("12"));
        assert_eq!(page.comments[0].published_text.as_deref(), Some("2 days ago"));
        assert_eq!(page.comments[0].reply_count_text.as_deref(), Some("3 replies"));
        assert_eq!(page.comments[1].likes_text.as_deref(), Some("4"));
        assert_eq!(page.comments[1].reply_count_text, None);
        assert_eq!(page.comments[1].replies_token.as_deref(), Some("REPLY_TOKEN"));
        assert_eq!(page.continuation.as_deref(), Some("MORE_COMMENTS"));
    }

    #[test]
    fn legacy_comment_renderers_still_parse_and_get_a_stable_id() {
        let root = json!({
            "onResponseReceivedActions": [{ "appendContinuationItemsAction": { "continuationItems": [
                { "commentRenderer": {
                    "contentText": { "runs": [{ "text": "old layout" }] },
                    "authorText": { "simpleText": "Legacy" },
                    "voteCount": { "simpleText": "7" },
                    "publishedTimeText": { "runs": [{ "text": "3 years ago" }] },
                    "replyCount": "5"
                }},
                { "commentThreadRenderer": { "commentViewModel": { "commentViewModel": { "commentId": "inline" } },
                    "contentText": { "runs": [{ "text": "inline text" }] } }}
            ]}}]
        });
        let page = parse_comments(&root);
        assert_eq!(page.comments[0].author, "Legacy");
        assert!(page.comments[0].id.starts_with('c'));
        assert_eq!(page.comments[0].id, "c".to_string() + &js_hash_abs("old layout").to_string());
        assert_eq!(page.comments[0].reply_count_text.as_deref(), Some("5 replies"));
    }
}
