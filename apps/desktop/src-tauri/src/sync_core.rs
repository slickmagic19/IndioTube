//! Snapshot + add-only merge, the Rust port of electron/syncCore.js.
//!
//! This is the shared contract with the Android app: sync never removes or
//! overwrites local data. History resolves by newest `ts` (ties toward the
//! larger position), video lists union by id, playlists match by normalised
//! name, subscriptions union by channel id. Merging the same payload twice is a
//! no-op.

use serde_json::{json, Value};

use crate::library::HISTORY_CAP;

pub const PROTOCOL_VERSION: i64 = 1;

fn norm_name(name: &str) -> String {
    name.trim().to_lowercase().split_whitespace().collect::<Vec<_>>().join(" ")
}

fn is_video(value: &Value) -> bool {
    value
        .get("videoId")
        .and_then(Value::as_str)
        .map(|id| !id.is_empty())
        .unwrap_or(false)
}

fn array(value: Option<&Value>) -> Vec<Value> {
    value.and_then(Value::as_array).cloned().unwrap_or_default()
}

fn num(value: Option<&Value>) -> f64 {
    value.and_then(Value::as_f64).unwrap_or(0.0)
}

pub fn build_snapshot(data: &Value) -> Value {
    let library = data.get("library").cloned().unwrap_or_else(|| json!({}));
    let mut history = array(library.get("history"));
    history.truncate(HISTORY_CAP);
    let playlists: Vec<Value> = array(library.get("playlists"))
        .into_iter()
        .map(|p| {
            json!({
                "id": "",
                "name": p.get("name").cloned().unwrap_or(json!("")),
                "createdAt": p.get("createdAt").cloned().unwrap_or(json!(0)),
                "videos": array(p.get("videos")).into_iter().filter(is_video).collect::<Vec<_>>(),
            })
        })
        .collect();
    json!({
        "indio": "sync",
        "v": PROTOCOL_VERSION,
        "name": data.get("profileName").and_then(Value::as_str).unwrap_or("").chars().take(48).collect::<String>(),
        "library": {
            "watchLater": array(library.get("watchLater")),
            "history": history,
            "playlists": playlists,
            "liked": array(library.get("liked")),
        },
        "subscriptions": array(data.get("subscriptions")),
    })
}

fn union_videos(local: Vec<Value>, incoming: Vec<Value>) -> (Vec<Value>, i64) {
    let mut have: Vec<String> = local
        .iter()
        .filter_map(|v| v.get("videoId").and_then(Value::as_str).map(str::to_string))
        .collect();
    let mut additions = Vec::new();
    let mut added = 0;
    for video in incoming {
        let Some(id) = video.get("videoId").and_then(Value::as_str).map(str::to_string) else {
            continue;
        };
        if have.contains(&id) {
            continue;
        }
        have.push(id);
        additions.push(video);
        added += 1;
    }
    additions.extend(local);
    (additions, added)
}

fn merge_history(local: Vec<Value>, incoming: Vec<Value>) -> (Vec<Value>, i64) {
    let mut merged: Vec<Value> = Vec::new();
    let mut added = 0;
    let push_unique = |entry: Value, merged: &mut Vec<Value>| {
        let Some(id) = entry
            .get("video")
            .and_then(|v| v.get("videoId"))
            .and_then(Value::as_str)
            .map(str::to_string)
        else {
            return;
        };
        if let Some(hit) = merged
            .iter_mut()
            .find(|e| e["video"]["videoId"].as_str() == Some(&id))
        {
            let ts = num(entry.get("ts"));
            let hit_ts = num(hit.get("ts"));
            if ts > hit_ts
                || (ts == hit_ts && num(entry.get("positionMs")) > num(hit.get("positionMs")))
            {
                *hit = entry;
            }
        } else {
            merged.push(entry);
        }
    };
    for entry in local {
        if is_video(entry.get("video").unwrap_or(&Value::Null)) {
            push_unique(entry, &mut merged);
        }
    }
    for entry in incoming {
        if !is_video(entry.get("video").unwrap_or(&Value::Null)) {
            continue;
        }
        let id = entry["video"]["videoId"].as_str().unwrap_or("").to_string();
        let existed = merged
            .iter()
            .any(|e| e["video"]["videoId"].as_str() == Some(&id));
        push_unique(entry, &mut merged);
        if !existed {
            added += 1;
        }
    }
    merged.sort_by(|a, b| num(b.get("ts")).partial_cmp(&num(a.get("ts"))).unwrap_or(std::cmp::Ordering::Equal));
    merged.truncate(HISTORY_CAP);
    (merged, added)
}

fn merge_playlists(
    local: Vec<Value>,
    incoming: Vec<Value>,
    new_id: &mut dyn FnMut() -> String,
) -> (Vec<Value>, i64, i64, i64) {
    let mut playlists: Vec<Value> = local
        .into_iter()
        .map(|mut p| {
            let videos = array(p.get("videos"));
            p["videos"] = Value::Array(videos);
            p
        })
        .collect();
    let mut playlists_added = 0;
    let mut playlists_merged = 0;
    let mut videos_added = 0;

    for inc in incoming {
        let name = inc
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .to_string();
        if name.is_empty() {
            continue;
        }
        let inc_videos: Vec<Value> = array(inc.get("videos")).into_iter().filter(is_video).collect();
        let normalized = norm_name(&name);
        let hit_index = playlists.iter().position(|p| {
            norm_name(p.get("name").and_then(Value::as_str).unwrap_or("")) == normalized
        });
        match hit_index {
            None => {
                let mut seen: Vec<String> = Vec::new();
                let mut videos = Vec::new();
                for video in inc_videos {
                    let id = video["videoId"].as_str().unwrap_or("").to_string();
                    if seen.contains(&id) {
                        continue;
                    }
                    seen.push(id);
                    videos.push(video);
                    videos_added += 1;
                }
                playlists.push(json!({
                    "id": new_id(),
                    "name": name,
                    "createdAt": num(inc.get("createdAt")) as i64,
                    "videos": videos,
                }));
                playlists_added += 1;
            }
            Some(index) => {
                let mut existing: Vec<Value> = array(playlists[index].get("videos"));
                let mut have: Vec<String> = existing
                    .iter()
                    .filter_map(|v| v["videoId"].as_str().map(str::to_string))
                    .collect();
                let mut added = 0;
                for video in inc_videos {
                    let id = video["videoId"].as_str().unwrap_or("").to_string();
                    if have.contains(&id) {
                        continue;
                    }
                    have.push(id);
                    existing.push(video);
                    added += 1;
                }
                if added > 0 {
                    playlists[index]["videos"] = Value::Array(existing);
                    playlists_merged += 1;
                    videos_added += added;
                }
            }
        }
    }
    (playlists, playlists_added, playlists_merged, videos_added)
}

fn merge_subscriptions(local: Vec<Value>, incoming: Vec<Value>) -> (Vec<Value>, i64) {
    let mut have: Vec<String> = local
        .iter()
        .filter_map(|s| s["channelId"].as_str().map(str::to_string))
        .collect();
    let mut additions = Vec::new();
    let mut added = 0;
    for sub in incoming {
        let Some(id) = sub.get("channelId").and_then(Value::as_str).map(str::to_string) else {
            continue;
        };
        if id.is_empty() || have.contains(&id) {
            continue;
        }
        have.push(id);
        additions.push(sub);
        added += 1;
    }
    additions.extend(local);
    (additions, added)
}

/// Returns the next `{ library, subscriptions }` plus the stats the UI shows,
/// and leaves unrelated fields (profileName, downloads, feedback ids) alone.
pub fn merge_incoming(
    data: &Value,
    incoming: &Value,
    new_id: &mut dyn FnMut() -> String,
) -> (Value, Value) {
    let library = data.get("library").cloned().unwrap_or_else(|| json!({}));
    let in_library = incoming.get("library").cloned().unwrap_or_else(|| json!({}));

    let (watch_later, watch_added) = union_videos(
        array(library.get("watchLater")),
        array(in_library.get("watchLater")),
    );
    let (liked, liked_added) =
        union_videos(array(library.get("liked")), array(in_library.get("liked")));
    let (history, history_added) =
        merge_history(array(library.get("history")), array(in_library.get("history")));
    let (playlists, playlists_added, playlists_merged, videos_added) = merge_playlists(
        array(library.get("playlists")),
        array(in_library.get("playlists")),
        new_id,
    );
    let (subscriptions, subs_added) = merge_subscriptions(
        array(data.get("subscriptions")),
        array(incoming.get("subscriptions")),
    );

    let mut next_library = library;
    next_library["watchLater"] = Value::Array(watch_later);
    next_library["liked"] = Value::Array(liked);
    next_library["history"] = Value::Array(history);
    next_library["playlists"] = Value::Array(playlists);

    let stats = json!({
        "historyAdded": history_added,
        "watchLaterAdded": watch_added,
        "likedAdded": liked_added,
        "playlistsAdded": playlists_added,
        "playlistsMerged": playlists_merged,
        "videosAdded": videos_added,
        "subsAdded": subs_added,
    });
    (
        json!({
            "library": next_library,
            "subscriptions": subscriptions,
        }),
        stats,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn video(id: &str) -> Value {
        json!({ "videoId": id, "title": id, "durationSec": 60 })
    }

    #[test]
    fn snapshot_drops_playlist_ids_and_trims_history() {
        let data = json!({
            "profileName": "Desktop",
            "library": {
                "watchLater": [video("w1")],
                "history": [{ "video": video("h1"), "ts": 10, "positionMs": 5, "durationMs": 60_000 }],
                "playlists": [{ "id": "local", "name": "Mix", "createdAt": 3, "videos": [video("p1"), json!({ "nope": true })] }],
                "liked": [video("l1")]
            },
            "subscriptions": [{ "channelId": "UC1", "name": "One" }]
        });
        let snap = build_snapshot(&data);
        assert_eq!(snap["indio"], "sync");
        assert_eq!(snap["library"]["playlists"][0]["id"], "");
        assert_eq!(snap["library"]["playlists"][0]["videos"].as_array().unwrap().len(), 1);
        assert_eq!(snap["library"]["watchLater"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn merge_is_add_only_and_idempotent() {
        let local = json!({
            "library": {
                "watchLater": [video("w1")],
                "history": [{ "video": video("h1"), "ts": 5, "positionMs": 1000, "durationMs": 60_000 }],
                "playlists": [{ "id": "pl1", "name": "Mix", "createdAt": 1, "videos": [video("p1")] }],
                "liked": []
            },
            "subscriptions": [{ "channelId": "UC1" }]
        });
        let incoming = json!({
            "library": {
                "watchLater": [video("w2"), video("w1")],
                "history": [
                    { "video": video("h1"), "ts": 9, "positionMs": 5000, "durationMs": 60_000 },
                    { "video": video("h2"), "ts": 7, "positionMs": 0, "durationMs": 0 }
                ],
                "playlists": [
                    { "id": "", "name": " mix ", "videos": [video("p2"), video("p1")] },
                    { "id": "", "name": "New", "videos": [video("p3")] }
                ],
                "liked": [video("l1")]
            },
            "subscriptions": [{ "channelId": "UC1" }, { "channelId": "UC2" }]
        });
        let mut counter = 0;
        let mut new_id = || {
            counter += 1;
            format!("new-{counter}")
        };
        let (next, stats) = merge_incoming(&local, &incoming, &mut new_id);
        assert_eq!(stats["watchLaterAdded"], 1);
        assert_eq!(stats["historyAdded"], 1);
        assert_eq!(stats["playlistsAdded"], 1);
        assert_eq!(stats["playlistsMerged"], 1);
        assert_eq!(stats["videosAdded"], 2); // p2 into Mix, p3 into New
        assert_eq!(stats["subsAdded"], 1);
        assert_eq!(next["library"]["history"][0]["video"]["videoId"], "h1");
        assert_eq!(next["library"]["history"][0]["ts"], 9);
        assert_eq!(next["library"]["playlists"].as_array().unwrap().len(), 2);
        assert_eq!(next["subscriptions"].as_array().unwrap()[0]["channelId"], "UC2");

        // Second run changes nothing.
        let (_, again) = merge_incoming(&next, &incoming, &mut || "unused".to_string());
        for key in [
            "historyAdded",
            "watchLaterAdded",
            "likedAdded",
            "playlistsAdded",
            "playlistsMerged",
            "videosAdded",
            "subsAdded",
        ] {
            assert_eq!(again[key], 0, "{key} should be 0 on the second merge");
        }
    }

    fn history_entry(id: &str, ts: i64, position_ms: i64) -> Value {
        json!({
            "video": video(id),
            "ts": ts,
            "positionMs": position_ms,
            "durationMs": 60_000,
        })
    }

    /// The two-argument form most test vectors want: a fresh, unstarted row.
    fn watched(id: &str, ts: i64) -> Value {
        history_entry(id, ts, 0)
    }

    #[test]
    fn remote_only_rows_are_prepended_so_fresh_arrivals_sit_on_top() {
        let local = json!({
            "library": { "watchLater": [video("w")], "liked": [video("a")] },
            "subscriptions": [{ "channelId": "UCb", "name": "B" }]
        });
        let incoming = json!({
            "library": {
                "watchLater": [video("x"), video("w")],
                "liked": [video("a"), video("b")]
            },
            "subscriptions": [{ "channelId": "UCb" }, { "channelId": "UCa" }, { "channelId": "UCc" }]
        });
        let (next, stats) = merge_incoming(&local, &incoming, &mut || "id-1".to_string());
        let ids = |path: &[&str]| -> Vec<String> {
            let mut node = &next;
            for key in path {
                node = node.get(key).unwrap_or(&Value::Null);
            }
            node.as_array()
                .unwrap()
                .iter()
                .map(|v| v["videoId"].as_str().unwrap_or_default().to_string())
                .collect()
        };
        assert_eq!(ids(&["library", "liked"]), vec!["b", "a"]);
        assert_eq!(ids(&["library", "watchLater"]), vec!["x", "w"]);
        assert_eq!(stats["likedAdded"], 1);
        assert_eq!(stats["watchLaterAdded"], 1);
        let subs: Vec<String> = next["subscriptions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s["channelId"].as_str().unwrap_or_default().to_string())
            .collect();
        assert_eq!(subs, vec!["UCa", "UCc", "UCb"]);
        assert_eq!(stats["subsAdded"], 2);
    }

    #[test]
    fn history_resolves_by_newest_ts_and_ties_toward_more_progress() {
        let local = json!({ "library": { "history": [history_entry("v1", 100, 9_000)] } });
        let older = json!({ "library": { "history": [history_entry("v1", 50, 40_000)] } });
        let (next, stats) = merge_incoming(&local, &older, &mut || "id".to_string());
        assert_eq!(next["library"]["history"][0]["positionMs"], 9_000);
        assert_eq!(stats["historyAdded"], 0);

        let newer = json!({ "library": { "history": [history_entry("v1", 200, 30_000)] } });
        let (next, _) = merge_incoming(&local, &newer, &mut || "id".to_string());
        assert_eq!(next["library"]["history"][0]["positionMs"], 30_000);
        assert_eq!(next["library"]["history"][0]["ts"], 200);

        let tie = json!({ "library": { "history": [history_entry("v1", 100, 40_000)] } });
        let (next, _) = merge_incoming(&local, &tie, &mut || "id".to_string());
        assert_eq!(next["library"]["history"][0]["positionMs"], 40_000);
    }

    #[test]
    fn merged_history_keeps_the_android_cap_and_drops_the_oldest() {
        let local_rows: Vec<Value> = (0..HISTORY_CAP)
            .map(|i| watched(&format!("l{i}"), 1000 + i as i64))
            .collect();
        let local = json!({ "library": { "history": local_rows } });
        let incoming = json!({ "library": { "history": [watched("new", 9999)] } });
        let (next, _) = merge_incoming(&local, &incoming, &mut || "id".to_string());
        let rows = next["library"]["history"].as_array().unwrap();
        assert_eq!(rows.len(), HISTORY_CAP);
        assert_eq!(rows[0]["video"]["videoId"], "new");
        assert!(!rows
            .iter()
            .any(|e| e["video"]["videoId"] == json!("l0")));
    }

    #[test]
    fn playlist_ids_never_come_from_the_wire_and_names_match_loosely() {
        let local = json!({
            "library": { "playlists": [{ "id": "p1", "name": "  Gym  HITS ", "videos": [video("a")] }] }
        });
        let incoming = json!({
            "library": { "playlists": [{ "id": "ignored", "name": "gym hits", "videos": [video("a"), video("b")] }] }
        });
        let (next, stats) = merge_incoming(&local, &incoming, &mut || "id-1".to_string());
        let playlists = next["library"]["playlists"].as_array().unwrap();
        assert_eq!(playlists.len(), 1);
        assert_eq!(playlists[0]["id"], "p1");
        assert_eq!(stats["playlistsMerged"], 1);
        assert_eq!(stats["videosAdded"], 1);
    }

    #[test]
    fn a_new_playlist_counts_remote_internal_duplicates_once() {
        let local = json!({ "library": { "playlists": [{ "id": "p1", "name": "Old", "videos": [] }] } });
        let incoming = json!({
            "library": { "playlists": [{ "name": "Focus", "videos": [video("f1"), video("f1")] }] }
        });
        let (next, stats) = merge_incoming(&local, &incoming, &mut || "id-2".to_string());
        assert_eq!(next["library"]["playlists"].as_array().unwrap().len(), 2);
        assert_eq!(stats["playlistsAdded"], 1);
        assert_eq!(stats["videosAdded"], 1);
        assert_eq!(next["library"]["playlists"][1]["id"], "id-2");
    }

    #[test]
    fn unsynced_local_fields_survive_and_junk_payloads_are_ignored() {
        let local = json!({
            "profileName": "Desk",
            "library": { "historyPaused": true, "searches": [{ "query": "abc", "ts": 1 }] },
            "downloads": [{ "video": video("d") }]
        });
        let (next, stats) = merge_incoming(
            &local,
            &json!({ "library": { "history": "nope", "liked": null } }),
            &mut || "id".to_string(),
        );
        assert_eq!(
            stats.as_object()
                .unwrap()
                .values()
                .map(|v| v.as_i64().unwrap_or(0))
                .sum::<i64>(),
            0
        );
        assert_eq!(next["library"]["history"], json!([]));
        // Unsynced local keys ride along untouched, exactly like the JS spread.
        assert_eq!(next["library"]["historyPaused"], json!(true));
        assert_eq!(next["library"]["searches"].as_array().unwrap().len(), 1);
        // Profile name and downloads are not part of the merge result; the
        // store layers the merged keys over the live state instead.
        assert!(next.get("profileName").is_none());
        assert!(next.get("downloads").is_none());
    }

    #[test]
    fn snapshot_ships_only_the_synced_subset() {
        let data = json!({
            "profileName": "A very long device name that should be trimmed down by the wire format",
            "library": {
                "liked": [video("a")],
                "history": [watched("h", 5)],
                "searches": [{ "query": "keep off the wire", "ts": 1 }],
                "playlists": [{ "id": "p1", "name": "X", "videos": [video("v")] }]
            },
            "subscriptions": [{ "channelId": "UC1" }]
        });
        let snap = build_snapshot(&data);
        assert_eq!(snap["name"].as_str().unwrap().len(), 48);
        assert!(snap["library"].get("searches").is_none());
        assert_eq!(snap["library"]["playlists"][0]["id"], "");
        assert_eq!(snap["v"], json!(PROTOCOL_VERSION));
    }

    #[test]
    fn norm_name_collapses_whitespace_and_case() {
        assert_eq!(norm_name("  Chill   Beats "), "chill beats");
    }
}
