//! Channel pages - the Rust port of `parseChannelHeader`, `parseChannelPage`,
//! `parseChannelPlaylistsPage`, `parseChannelTabs`, `parseChannelAbout`,
//! `parseAboutToken` and `parsePlaylistLockup` in electron/inTube.js.
//!
//! Current layout ships a `pageHeaderViewModel`; older responses use
//! `c4TabbedHeaderRenderer`. Both are read, then the microformat fills gaps.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::innertube::{
    at, at_str, extract_continuation, feed_continuation, feed_items, find_all, last_thumb_url,
    parse_any_video, parse_playlist_renderer, parse_video_renderer, text_either, xth_first_url,
    PlaylistItem, VideoItem,
};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Channel {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_id: Option<String>,
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
pub struct ChannelPage {
    pub channel: Channel,
    pub videos: Vec<VideoItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation: Option<String>,
    pub playlists: Vec<PlaylistItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub about_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelPlaylistsPage {
    pub playlists: Vec<PlaylistItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation: Option<String>,
    pub has_tab: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelTab {
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelLink {
    pub title: String,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelAbout {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub country: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subscribers_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_count_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub view_count_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub joined_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub canonical_url: Option<String>,
    pub links: Vec<ChannelLink>,
}

fn trimmed(value: Option<String>) -> Option<String> {
    let t = value?.trim().to_string();
    if t.is_empty() {
        None
    } else {
        Some(t)
    }
}

pub fn parse_channel_header(root: &Value, requested_id: Option<&str>) -> Channel {
    let mut name: Option<String> = None;
    let mut avatar: Option<String> = None;
    let mut subscribers: Option<String> = None;
    let mut video_count: Option<String> = None;
    let mut description: Option<String> = None;

    if let Some(page_header) = at(root, "header.pageHeaderRenderer") {
        name = at_str(
            page_header,
            "pageHeaderViewModel.title.dynamicTextViewModel.text.content",
        );
        avatar = xth_first_url(at(
            page_header,
            "pageHeaderViewModel.image.decoratedAvatarViewModel.avatar",
        ));
        if let Some(rows) = at(
            page_header,
            "pageHeaderViewModel.metadata.contentMetadataViewModel.metadataRows",
        )
        .and_then(Value::as_array)
        {
            let subscriber_re = regex::Regex::new(r"(?i)subscriber").unwrap();
            let video_re = regex::Regex::new(r"(?i)video").unwrap();
            for row in rows {
                let Some(parts) = row.get("metadataParts").and_then(Value::as_array) else { continue };
                for part in parts {
                    let Some(t) = at_str(part, "text.content") else { continue };
                    if t.starts_with('@') {
                        continue;
                    }
                    if subscriber_re.is_match(&t) {
                        subscribers = Some(t);
                    } else if video_re.is_match(&t) {
                        video_count = Some(t);
                    }
                }
            }
        }
        if let Some(dp) = find_all(page_header, "descriptionPreviewViewModel", 10, 2).into_iter().next() {
            description = at_str(&dp, "description.content");
        }
    }

    if let Some(c4) = at(root, "header.c4TabbedHeaderRenderer") {
        if name.is_none() {
            name = text_either(at(c4, "title"));
        }
        if avatar.is_none() {
            avatar = last_thumb_url(at(c4, "avatar"));
        }
        if subscribers.is_none() {
            subscribers = text_either(at(c4, "subscriberCountText"));
        }
        if video_count.is_none() {
            video_count = text_either(at(c4, "videosCountText"));
        }
        if description.is_none() {
            description = at_str(c4, "tagline.channelTaglineRenderer.content");
        }
    }

    let micro = at(root, "microformat.microformatDataRenderer");
    if name.is_none() {
        name = micro.and_then(|m| at_str(m, "title"));
    }
    if description.is_none() {
        description = micro.and_then(|m| at_str(m, "description"));
    }
    if avatar.is_none() {
        avatar = last_thumb_url(micro.and_then(|m| at(m, "thumbnail")));
    }

    let mut channel_id = requested_id.map(str::to_string);
    if let Some(canonical) = micro.and_then(|m| at_str(m, "urlCanonical")) {
        if let Some(last) = canonical.split('/').next_back() {
            if last.starts_with("UC") {
                channel_id = Some(last.to_string());
            }
        }
    }

    let clean_name = name.unwrap_or_default().trim().to_string();
    Channel {
        channel_id,
        name: if clean_name.is_empty() {
            "Channel".to_string()
        } else {
            clean_name
        },
        avatar,
        subscribers_text: subscribers,
        video_count_text: video_count,
        description: trimmed(description),
    }
}

/// The token behind a channel's "About" panel, if one is present.
pub fn parse_about_token(root: &Value, channel_name: &str) -> Option<String> {
    let panels = find_all(root, "engagementPanelSectionListRenderer", 14, 10);
    if panels.is_empty() {
        return None;
    }
    let title = |p: &Value| at_str(p, "header.engagementPanelTitleHeaderRenderer.title.simpleText");
    let panel = panels
        .iter()
        .find(|p| title(p).as_deref() == Some(channel_name))
        .or_else(|| {
            panels.iter().find(|p| {
                title(p)
                    .map(|t| t != "Featured Channels")
                    .unwrap_or(false)
            })
        })?;
    for cc in find_all(panel, "continuationCommand", 14, 6) {
        if let Some(t) = at_str(&cc, "token") {
            return Some(t);
        }
    }
    None
}

pub fn parse_channel_about(root: &Value) -> Option<ChannelAbout> {
    let vm = find_all(root, "aboutChannelViewModel", 16, 3).into_iter().next()?;
    let mut links: Vec<ChannelLink> = Vec::new();
    if let Some(items) = vm.get("links").and_then(Value::as_array) {
        for el in items {
            let Some(link) = el.get("channelExternalLinkViewModel") else { continue };
            let title = at_str(link, "title.content").unwrap_or_default().trim().to_string();
            let url = at_str(
                link,
                "link.commandRuns.0.onTap.innertubeCommand.urlEndpoint.url",
            )
            .or_else(|| {
                at_str(
                    link,
                    "link.commandRuns.0.onTap.innertubeCommand.commandMetadata.webCommandMetadata.url",
                )
            });
            let Some(url) = url else { continue };
            if title.is_empty() || links.iter().any(|l| l.url == url) {
                continue;
            }
            links.push(ChannelLink { title, url });
        }
    }
    let field = |path: &str| trimmed(at_str(&vm, path));
    let canonical = field("canonicalChannelUrl").map(|c| {
        c.strip_prefix("http://")
            .map(|rest| format!("https://{rest}"))
            .unwrap_or(c)
    });
    Some(ChannelAbout {
        description: field("description"),
        country: field("country"),
        subscribers_text: field("subscriberCountText"),
        video_count_text: field("videoCountText"),
        view_count_text: field("viewCountText"),
        joined_text: field("joinedDateText.content"),
        canonical_url: canonical,
        links,
    })
}

fn stamp_channel(v: VideoItem, channel: &Channel) -> VideoItem {
    if v.channel_name.is_some() && v.channel_avatar.is_some() && v.channel_id.is_some() {
        return v;
    }
    VideoItem {
        video_id: v.video_id,
        title: v.title,
        channel_name: v.channel_name.or_else(|| {
            if channel.name != "Channel" {
                Some(channel.name.clone())
            } else {
                None
            }
        }),
        channel_id: v.channel_id.or_else(|| channel.channel_id.clone()),
        channel_avatar: v.channel_avatar.or_else(|| channel.avatar.clone()),
        channel_verified: v.channel_verified,
        thumbnail: v.thumbnail,
        duration_sec: v.duration_sec,
        views_text: v.views_text,
        published_text: v.published_text,
        is_live: v.is_live,
        members_only: v.members_only,
    }
}

pub fn parse_channel_page(root: &Value, requested_id: Option<&str>) -> ChannelPage {
    let channel = parse_channel_header(root, requested_id);
    let mut videos: Vec<VideoItem> = Vec::new();
    let mut continuation = None;
    for entry in feed_items(root) {
        if let Some(item) = entry.get("richItemRenderer") {
            if let Some(v) = item.get("content").and_then(parse_any_video) {
                videos.push(v);
            }
            continue;
        }
        if let Some(gv) = entry.get("gridVideoRenderer") {
            if let Some(v) = parse_video_renderer(gv) {
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
    if videos.is_empty() {
        for gv in find_all(root, "gridVideoRenderer", 12, 60) {
            if let Some(v) = parse_video_renderer(&gv) {
                videos.push(v);
            }
        }
    }
    if continuation.is_none() {
        continuation = feed_continuation(root);
    }
    // Videos on a channel page don't repeat the owner in each item, so stamp
    // the header channel onto them for the cards.
    let stamped = videos
        .into_iter()
        .map(|v| stamp_channel(v, &channel))
        .collect();
    let about_token = parse_about_token(root, &channel.name);
    ChannelPage {
        channel,
        videos: stamped,
        continuation,
        playlists: Vec::new(),
        about_token,
    }
}

/// Playlist lockups sit behind several renderer/array layers (~16 deep), which
/// is deeper than the video scans.
pub fn parse_playlist_lockup(lockup: &Value) -> Option<PlaylistItem> {
    let playlist_id = at_str(lockup, "contentId")?;
    let meta = at(lockup, "metadata.lockupMetadataViewModel")?;
    let title = at_str(meta, "title.content").unwrap_or_default().trim().to_string();
    if title.is_empty() {
        return None;
    }
    let thumb_vm = at(
        lockup,
        "contentImage.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel",
    )
    .or_else(|| at(lockup, "contentImage.thumbnailViewModel"))
    .or_else(|| {
        at(
            meta,
            "image.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel",
        )
    });
    let thumb = thumb_vm
        .and_then(|vm| xth_first_url(at(vm, "image")))
        .or_else(|| xth_first_url(at(meta, "image")));

    let count_re = regex::Regex::new(r"(?i)\b(videos?|episodes?)\b").unwrap();
    let mut count: Option<String> = None;
    if let Some(vm) = thumb_vm {
        for badge in find_all(vm, "thumbnailBadgeViewModel", 8, 6) {
            if let Some(text) = at_str(&badge, "text") {
                if count_re.is_match(&text) && count.is_none() {
                    count = Some(text);
                }
            }
        }
    }
    if let Some(rows) = at(meta, "metadata.contentMetadataViewModel.metadataRows")
        .and_then(Value::as_array)
    {
        for row in rows {
            let Some(parts) = row.get("metadataParts").and_then(Value::as_array) else { continue };
            for part in parts {
                if let Some(t) = at_str(part, "text.content") {
                    if count_re.is_match(&t) && count.is_none() {
                        count = Some(t);
                    }
                }
            }
        }
    }

    Some(PlaylistItem {
        playlist_id,
        title,
        channel_name: None,
        channel_id: None,
        thumbnail: thumb,
        video_count_text: count,
    })
}

fn playlist_continuation(root: &Value) -> Option<String> {
    for grid in find_all(root, "gridRenderer", 24, 6) {
        let Some(items) = grid.get("items").and_then(Value::as_array) else { continue };
        let has_playlists = items.iter().any(|item| {
            at_str(item, "lockupViewModel.contentType")
                .map(|t| t.contains("PLAYLIST"))
                .unwrap_or(false)
        });
        if !has_playlists {
            continue;
        }
        for item in items {
            if let Some(cont) = item.get("continuationItemRenderer") {
                if let Some(t) = extract_continuation(cont) {
                    return Some(t);
                }
            }
        }
    }
    let lockups = find_all(root, "lockupViewModel", 24, 90);
    let has_playlists = lockups
        .iter()
        .any(|l| at_str(l, "contentType").map(|t| t.contains("PLAYLIST")).unwrap_or(false));
    if !has_playlists {
        return None;
    }
    let tokens: Vec<String> = find_all(root, "continuationItemRenderer", 24, 10)
        .iter()
        .filter_map(extract_continuation)
        .collect();
    tokens.last().cloned()
}

pub fn parse_channel_playlists_page(root: &Value) -> ChannelPlaylistsPage {
    let mut out: Vec<PlaylistItem> = Vec::new();
    for pl in find_all(root, "gridPlaylistRenderer", 24, 60) {
        if let Some(p) = parse_playlist_renderer(&pl) {
            out.push(p);
        }
    }
    for lockup in find_all(root, "lockupViewModel", 24, 90) {
        if at_str(&lockup, "contentType")
            .map(|t| t.contains("PLAYLIST"))
            .unwrap_or(false)
        {
            if let Some(p) = parse_playlist_lockup(&lockup) {
                out.push(p);
            }
        }
    }
    let mut seen: Vec<String> = Vec::new();
    let playlists = out
        .into_iter()
        .filter(|p| {
            if seen.contains(&p.playlist_id) {
                false
            } else {
                seen.push(p.playlist_id.clone());
                true
            }
        })
        .collect();
    ChannelPlaylistsPage {
        playlists,
        continuation: playlist_continuation(root),
        has_tab: true,
    }
}

pub fn parse_channel_tabs(root: &Value) -> Vec<ChannelTab> {
    find_all(root, "tabRenderer", 14, 24)
        .iter()
        .filter_map(|tab| {
            let title = at_str(tab, "title").or_else(|| at_str(tab, "title.content"))?;
            Some(ChannelTab {
                title,
                params: at_str(tab, "endpoint.browseEndpoint.params"),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn header_reads_the_page_header_view_model() {
        let root = json!({
            "header": { "pageHeaderRenderer": { "pageHeaderViewModel": {
                "title": { "dynamicTextViewModel": { "text": { "content": "Cat Channel" } } },
                "image": { "decoratedAvatarViewModel": { "avatar": { "avatarViewModel": { "image": {
                    "sources": [
                        { "url": "https://yt3.example/s.jpg", "width": 88 },
                        { "url": "https://yt3.example/l.jpg", "width": 176 }
                    ]
                }}}}},
                "metadata": { "contentMetadataViewModel": { "metadataRows": [
                    { "metadataParts": [
                        { "text": { "content": "@catchannel" } },
                        { "text": { "content": "1.2M subscribers" } },
                        { "text": { "content": "345 videos" } }
                    ]}
                ]}},
                "description": { "descriptionPreviewViewModel": { "description": { "content": " Cats, daily. " } } }
            }}},
            "microformat": { "microformatDataRenderer": {
                "urlCanonical": "https://www.youtube.com/@catchannel"
            }}
        });
        let c = parse_channel_header(&root, Some("UCcat"));
        assert_eq!(c.name, "Cat Channel");
        assert_eq!(c.channel_id.as_deref(), Some("UCcat"));
        assert_eq!(c.avatar.as_deref(), Some("https://yt3.example/l.jpg"));
        assert_eq!(c.subscribers_text.as_deref(), Some("1.2M subscribers"));
        assert_eq!(c.video_count_text.as_deref(), Some("345 videos"));
        assert_eq!(c.description.as_deref(), Some("Cats, daily."));
    }

    #[test]
    fn header_falls_back_to_c4_and_microformat() {
        let root = json!({
            "header": { "c4TabbedHeaderRenderer": {
                "title": { "simpleText": "Old Layout" },
                "avatar": { "thumbnails": [{ "url": "//yt3.example/old.jpg" }] },
                "subscriberCountText": { "simpleText": "10K subscribers" },
                "tagline": { "channelTaglineRenderer": { "content": " tagline " } }
            }},
            "microformat": { "microformatDataRenderer": {
                "urlCanonical": "https://www.youtube.com/channel/UCcanonical"
            }}
        });
        let c = parse_channel_header(&root, Some("UCrequested"));
        assert_eq!(c.name, "Old Layout");
        assert_eq!(c.channel_id.as_deref(), Some("UCcanonical"));
        assert_eq!(c.avatar.as_deref(), Some("https://yt3.example/old.jpg"));
        assert_eq!(c.description.as_deref(), Some("tagline"));
    }

    #[test]
    fn header_defaults_the_name_when_nothing_is_found() {
        let c = parse_channel_header(&json!({}), None);
        assert_eq!(c.name, "Channel");
        assert_eq!(c.channel_id, None);
    }

    #[test]
    fn channel_page_parses_and_stamps_videos() {
        let root = json!({
            "header": { "pageHeaderRenderer": { "pageHeaderViewModel": {
                "title": { "dynamicTextViewModel": { "text": { "content": "Cat Channel" } } },
                "image": { "decoratedAvatarViewModel": { "avatar": { "avatarViewModel": { "image": {
                    "sources": [{ "url": "https://yt3.example/a.jpg" }]
                }}}}}
            }}},
            "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [{
                "tabRenderer": { "content": { "richGridRenderer": { "contents": [
                    { "richItemRenderer": { "content": { "videoRenderer": {
                        "videoId": "v1", "title": { "simpleText": "One" },
                        "lengthText": { "simpleText": "1:00" }
                    }}}},
                    { "continuationItemRenderer": { "continuationEndpoint": {
                        "continuationCommand": { "token": "CH_TOK" }
                    }}}
                ]}}}
            }]}},
            "engagementPanels": [{ "engagementPanelSectionListRenderer": {
                "header": { "engagementPanelTitleHeaderRenderer": {
                    "title": { "simpleText": "Cat Channel" }
                }},
                "content": { "continuationItemRenderer": {
                    "continuationEndpoint": { "continuationCommand": { "token": "ABOUT_TOK" } }
                }}
            }}]
        });
        let page = parse_channel_page(&root, Some("UCcat"));
        assert_eq!(page.videos.len(), 1);
        assert_eq!(page.videos[0].channel_name.as_deref(), Some("Cat Channel"));
        assert_eq!(page.videos[0].channel_id.as_deref(), Some("UCcat"));
        assert_eq!(
            page.videos[0].channel_avatar.as_deref(),
            Some("https://yt3.example/a.jpg")
        );
        assert_eq!(page.continuation.as_deref(), Some("CH_TOK"));
        assert_eq!(page.about_token.as_deref(), Some("ABOUT_TOK"));
        assert!(page.playlists.is_empty());
    }

    #[test]
    fn channel_page_scans_grid_renderers_when_the_grid_path_is_absent() {
        let root = json!({
            "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [{
                "tabRenderer": { "content": { "sectionListRenderer": { "contents": [{
                    "itemSectionRenderer": { "contents": [{ "gridVideoRenderer": {
                        "videoId": "g1", "title": { "simpleText": "Grid one" }
                    }}]}
                }]}}}
            }]}}
        });
        let page = parse_channel_page(&root, Some("UCx"));
        assert_eq!(page.videos.len(), 1);
        assert_eq!(page.videos[0].video_id, "g1");
    }

    #[test]
    fn about_panel_parses_links_and_counts() {
        let root = json!({
            "onResponseReceivedActions": [{ "appendContinuationItemsAction": { "continuationItems": [{
                "aboutChannelRenderer": { "metadata": { "aboutChannelViewModel": {
                    "description": " Cats, daily. ",
                    "country": "United States",
                    "subscriberCountText": "1.2M subscribers",
                    "videoCountText": "345 videos",
                    "viewCountText": "987,654,321 views",
                    "joinedDateText": { "content": "Joined Mar 3, 2011" },
                    "canonicalChannelUrl": "http://www.youtube.com/@catchannel",
                    "links": [
                        { "channelExternalLinkViewModel": {
                            "title": { "content": "Website" },
                            "link": { "commandRuns": [{ "onTap": { "innertubeCommand": {
                                "urlEndpoint": { "url": "https://cats.example" }
                            }}}]}
                        }},
                        { "channelExternalLinkViewModel": {
                            "title": { "content": "Duplicate" },
                            "link": { "commandRuns": [{ "onTap": { "innertubeCommand": {
                                "urlEndpoint": { "url": "https://cats.example" }
                            }}}]}
                        }}
                    ]
                }}}
            }]}}]
        });
        let about = parse_channel_about(&root).unwrap();
        assert_eq!(about.description.as_deref(), Some("Cats, daily."));
        assert_eq!(about.country.as_deref(), Some("United States"));
        assert_eq!(about.video_count_text.as_deref(), Some("345 videos"));
        assert_eq!(about.joined_text.as_deref(), Some("Joined Mar 3, 2011"));
        assert_eq!(
            about.canonical_url.as_deref(),
            Some("https://www.youtube.com/@catchannel")
        );
        assert_eq!(about.links.len(), 1);
        assert_eq!(about.links[0].url, "https://cats.example");
    }

    #[test]
    fn about_token_prefers_the_panel_named_after_the_channel() {
        let root = json!({
            "engagementPanels": [
                { "engagementPanelSectionListRenderer": {
                    "header": { "engagementPanelTitleHeaderRenderer": { "title": { "simpleText": "Featured Channels" } } },
                    "x": { "continuationCommand": { "token": "OTHER" } }
                }},
                { "engagementPanelSectionListRenderer": {
                    "header": { "engagementPanelTitleHeaderRenderer": { "title": { "simpleText": "Cat Channel" } } },
                    "x": { "continuationCommand": { "token": "ABOUT" } }
                }}
            ]
        });
        assert_eq!(parse_about_token(&root, "Cat Channel").as_deref(), Some("ABOUT"));
        // "Featured Channels" is skipped, so the next titled panel wins.
        assert_eq!(parse_about_token(&root, "Missing"), Some("ABOUT".to_string()));
        assert_eq!(parse_about_token(&json!({}), "Cat Channel"), None);
    }

    #[test]
    fn playlists_page_dedupes_renderers_and_lockups() {
        let root = json!({
            "contents": [
                { "gridPlaylistRenderer": {
                    "playlistId": "PL1",
                    "title": { "simpleText": "First" },
                    "videoCountText": { "simpleText": "12 videos" },
                    "thumbnail": { "thumbnails": [{ "url": "https://i.ytimg.com/p1.jpg" }] }
                }},
                { "lockupViewModel": {
                    "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                    "contentId": "PL1",
                    "metadata": { "lockupMetadataViewModel": { "title": { "content": "First" } } }
                }},
                { "lockupViewModel": {
                    "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                    "contentId": "PL2",
                    "metadata": { "lockupMetadataViewModel": {
                        "title": { "content": "Second" },
                        "metadata": { "contentMetadataViewModel": { "metadataRows": [{
                            "metadataParts": [{ "text": { "content": "8 episodes" } }]
                        }]}}
                    }}
                }}
            ],
            "continuationItemRenderer": { "continuationEndpoint": {
                "continuationCommand": { "token": "PL_TOK" }
            }}
        });
        let page = parse_channel_playlists_page(&root);
        assert_eq!(page.playlists.len(), 2);
        assert_eq!(page.playlists[0].playlist_id, "PL1");
        assert_eq!(page.playlists[1].video_count_text.as_deref(), Some("8 episodes"));
        assert_eq!(page.continuation.as_deref(), Some("PL_TOK"));
        assert!(page.has_tab);
    }

    #[test]
    fn tabs_keep_titles_and_params() {
        let root = json!({
            "contents": { "sectionListRenderer": { "contents": [
                { "tabRenderer": { "title": "Home" } },
                { "tabRenderer": { "title": "Playlists", "endpoint": { "browseEndpoint": { "params": "PLAYLIST_PARAMS" } } } },
                { "tabRenderer": {} }
            ]}}
        });
        let tabs = parse_channel_tabs(&root);
        assert_eq!(tabs.len(), 2);
        assert_eq!(tabs[0].title, "Home");
        assert_eq!(tabs[0].params, None);
        assert_eq!(tabs[1].params.as_deref(), Some("PLAYLIST_PARAMS"));
    }
}
