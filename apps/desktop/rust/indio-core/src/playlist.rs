//! Playlist pages - the Rust port of `parsePlaylist` in electron/inTube.js.
//!
//! A playlist header moved from `playlistHeaderRenderer` to
//! `pageHeaderViewModel`, and the items moved from `playlistVideoRenderer` to
//! `lockupViewModel`, so both layouts are read.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::innertube::{
    at, at_str, extract_continuation, find_all, parse_lockup, parse_video_renderer, runs_text,
    VideoItem,
};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistPage {
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_count_text: Option<String>,
    pub videos: Vec<VideoItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation: Option<String>,
}

pub fn parse_playlist(root: &Value) -> PlaylistPage {
    let mut title = String::new();
    let mut author: Option<String> = None;
    let mut count_text: Option<String> = None;

    if let Some(h) = find_all(root, "playlistHeaderRenderer", 12, 2).into_iter().next() {
        title = at_str(&h, "title.simpleText")
            .or_else(|| runs_text(at(&h, "title")))
            .unwrap_or_default();
        author = runs_text(at(&h, "ownerText"));
        count_text = match at_str(&h, "numVideosText.simpleText") {
            Some(n) => Some(format!("{n} videos")),
            None => at_str(&h, "numVideosText.runs.0.text"),
        };
    }

    if title.trim().is_empty() {
        if let Some(ph) = find_all(root, "pageHeaderViewModel", 12, 2).into_iter().next() {
            title = at_str(&ph, "title.dynamicTextViewModel.text.content").unwrap_or_default();
            if let Some(rows) = at(&ph, "metadata.contentMetadataViewModel.metadataRows")
                .and_then(Value::as_array)
            {
                let count_re = regex::Regex::new(r"(?i)\bvideos?$").unwrap();
                let views_re = regex::Regex::new(r"(?i)(views?|watching|likes?)").unwrap();
                let by_re = regex::Regex::new(r"(?i)^by ").unwrap();
                for row in rows {
                    let Some(parts) = row.get("metadataParts").and_then(Value::as_array) else { continue };
                    for part in parts {
                        let t = at_str(part, "text.content").unwrap_or_default().trim().to_string();
                        if t.is_empty() {
                            continue;
                        }
                        if count_re.is_match(&t) {
                            if count_text.is_none() {
                                count_text = Some(t);
                            }
                        } else if t.eq_ignore_ascii_case("playlist") || views_re.is_match(&t) {
                            continue;
                        } else if by_re.is_match(&t) {
                            if author.is_none() {
                                author = Some(by_re.replace(&t, "").trim().to_string())
                                    .filter(|a| !a.is_empty());
                            }
                        } else if author.is_none() {
                            author = Some(t);
                        }
                    }
                }
            }
        }
    }

    if author.is_none() {
        if let Some(o) = find_all(root, "videoOwnerRenderer", 16, 2).into_iter().next() {
            author = runs_text(at(&o, "title"))
                .map(|a| a.trim().to_string())
                .filter(|a| !a.is_empty());
        }
    }
    if title.trim().is_empty() {
        if let Some(m) = find_all(root, "microformatDataRenderer", 10, 1).into_iter().next() {
            title = at_str(&m, "title").unwrap_or_default();
        }
    }

    // Items are plain video lockups today; the old layout used
    // playlistVideoRenderer. Both are read so a layout switch doesn't empty
    // the screen.
    let legacy: Vec<VideoItem> = find_all(root, "playlistVideoRenderer", 16, 200)
        .iter()
        .filter_map(parse_video_renderer)
        .collect();
    let lockups: Vec<VideoItem> = find_all(root, "lockupViewModel", 18, 200)
        .iter()
        .filter_map(parse_lockup)
        .collect();
    let mut seen: Vec<String> = Vec::new();
    let videos: Vec<VideoItem> = (if legacy.is_empty() { lockups } else { legacy })
        .into_iter()
        .filter(|v| {
            if seen.contains(&v.video_id) {
                false
            } else {
                seen.push(v.video_id.clone());
                true
            }
        })
        .collect();

    let mut continuation = None;
    for cont in find_all(root, "continuationItemViewModel", 18, 4) {
        if let Some(t) = at_str(
            &cont,
            "continuationCommand.innertubeCommand.continuationCommand.token",
        ) {
            if continuation.is_none() {
                continuation = Some(t);
            }
        }
    }
    if continuation.is_none() {
        for cont in find_all(root, "continuationItemRenderer", 14, 4) {
            if let Some(t) = extract_continuation(&cont) {
                continuation = Some(t);
                break;
            }
        }
    }

    PlaylistPage {
        title: if title.trim().is_empty() {
            "Playlist".to_string()
        } else {
            title.trim().to_string()
        },
        author,
        video_count_text: count_text,
        videos,
        continuation,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_the_legacy_playlist_header_and_items() {
        let root = json!({
            "contents": [
                { "playlistHeaderRenderer": {
                    "title": { "simpleText": "Cat compilation" },
                    "ownerText": { "runs": [{ "text": "Cat Channel" }] },
                    "numVideosText": { "simpleText": "42" }
                }},
                { "playlistVideoRenderer": {
                    "videoId": "v1",
                    "title": { "simpleText": "Part one" },
                    "lengthText": { "simpleText": "3:00" }
                }},
                { "playlistVideoRenderer": {
                    "videoId": "v1",
                    "title": { "simpleText": "Part one again" }
                }},
                { "continuationItemRenderer": { "continuationEndpoint": {
                    "continuationCommand": { "token": "PL_MORE" }
                }}}
            ]
        });
        let p = parse_playlist(&root);
        assert_eq!(p.title, "Cat compilation");
        assert_eq!(p.author.as_deref(), Some("Cat Channel"));
        assert_eq!(p.video_count_text.as_deref(), Some("42 videos"));
        assert_eq!(p.videos.len(), 1);
        assert_eq!(p.continuation.as_deref(), Some("PL_MORE"));
    }

    #[test]
    fn parses_the_page_header_metadata_row_layout() {
        let root = json!({
            "header": { "pageHeaderRenderer": { "pageHeaderViewModel": {
                "title": { "dynamicTextViewModel": { "text": { "content": "New layout" } } },
                "metadata": { "contentMetadataViewModel": { "metadataRows": [
                    { "metadataParts": [
                        { "text": { "content": "Cat Channel" } },
                        { "text": { "content": "Playlist" } },
                        { "text": { "content": "128 videos" } },
                        { "text": { "content": "1.4M views" } }
                    ]}
                ]}}
            }}},
            "lockupViewModel": {
                "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                "contentId": "v9",
                "metadata": { "lockupMetadataViewModel": {
                    "title": { "content": "Locked in" },
                    "metadata": { "contentMetadataViewModel": { "metadataRows": [] } }
                }}
            },
            "continuationItemViewModel": {
                "continuationCommand": { "innertubeCommand": { "continuationCommand": { "token": "LOCKUP_MORE" } } }
            }
        });
        let p = parse_playlist(&root);
        assert_eq!(p.title, "New layout");
        assert_eq!(p.author.as_deref(), Some("Cat Channel"));
        assert_eq!(p.video_count_text.as_deref(), Some("128 videos"));
        assert_eq!(p.videos.len(), 1);
        assert_eq!(p.videos[0].video_id, "v9");
        assert_eq!(p.continuation.as_deref(), Some("LOCKUP_MORE"));
    }

    #[test]
    fn falls_back_to_the_owner_renderer_and_microformat_title() {
        let root = json!({
            "videoOwnerRenderer": { "title": { "runs": [{ "text": "Owner" }] } },
            "microformatDataRenderer": { "title": "Micro playlist" }
        });
        let p = parse_playlist(&root);
        assert_eq!(p.title, "Micro playlist");
        assert_eq!(p.author.as_deref(), Some("Owner"));
        assert!(p.videos.is_empty());
        assert_eq!(p.continuation, None);
    }

    #[test]
    fn strips_the_byline_prefix_in_the_new_layout() {
        let root = json!({
            "pageHeaderViewModel": {
                "title": { "dynamicTextViewModel": { "text": { "content": "Mixed" } } },
                "metadata": { "contentMetadataViewModel": { "metadataRows": [
                    { "metadataParts": [{ "text": { "content": "by Cat Channel" } }] }
                ]}}
            }
        });
        assert_eq!(parse_playlist(&root).author.as_deref(), Some("Cat Channel"));
    }

    #[test]
    fn defaults_the_title_when_the_page_is_empty() {
        assert_eq!(parse_playlist(&json!({})).title, "Playlist");
    }
}
