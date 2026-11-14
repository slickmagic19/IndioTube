//! Library store: the Rust port of electron/library.js.
//!
//! One JSON file in the app data folder, shaped exactly like the Android wire
//! format so a sync snapshot transfers without translation. Every mutation
//! persists and then pushes the whole state to the change sink, which the shell
//! forwards as `lib:changed`; the renderer re-renders from that.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Map, Value};

pub const HISTORY_CAP: usize = 500;
pub const SEARCH_CAP: usize = 200;
pub const COMPLETED_FRACTION: f64 = 0.92;

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn empty_data() -> Value {
    json!({
        "profileName": "Desktop",
        "library": {
            "watchLater": [],
            "historyPaused": false,
            "history": [],
            "playlists": [],
            "liked": [],
            "searches": [],
            "notInterestedIds": [],
            "interestedChannelIds": [],
            "blockedChannelIds": [],
        },
        "subscriptions": [],
        "downloads": [],
    })
}

fn video_id_of(video: &Value) -> Option<&str> {
    video.get("videoId").and_then(Value::as_str)
}

fn watched_fraction(entry: &Value) -> Option<f64> {
    let position = entry.get("positionMs").and_then(Value::as_f64).unwrap_or(0.0);
    if position <= 0.0 {
        return None;
    }
    let duration = entry
        .get("durationMs")
        .and_then(Value::as_f64)
        .filter(|d| *d > 0.0)
        .or_else(|| {
            entry
                .get("video")
                .and_then(|v| v.get("durationSec"))
                .and_then(Value::as_f64)
                .map(|s| s * 1000.0)
                .filter(|d| *d > 0.0)
        })?;
    Some((position / duration).clamp(0.0, 1.0))
}

fn is_placeholder_title(title: Option<&str>) -> bool {
    match title {
        None => true,
        Some(t) => t.trim().is_empty() || t.trim().to_lowercase().starts_with("loading"),
    }
}

/// Field-by-field merge so the watch page's "Loading…" stub never overwrites a
/// title we already know.
fn merge_video(old: Option<&Value>, next: &Value) -> Value {
    let Some(old) = old else { return next.clone() };
    let mut merged = old.as_object().cloned().unwrap_or_default();
    if let Some(next_obj) = next.as_object() {
        for (key, value) in next_obj {
            merged.insert(key.clone(), value.clone());
        }
    }
    if is_placeholder_title(
        next.get("title")
            .and_then(Value::as_str),
    ) && !is_placeholder_title(old.get("title").and_then(Value::as_str))
    {
        if let Some(title) = old.get("title") {
            merged.insert("title".to_string(), title.clone());
        }
    }
    for key in [
        "channelId",
        "channelName",
        "channelAvatar",
        "viewsText",
        "publishedText",
    ] {
        let empty = merged
            .get(key)
            .map(|v| v.is_null() || v.as_str() == Some(""))
            .unwrap_or(true);
        if empty {
            if let Some(value) = old.get(key).filter(|v| !v.is_null()) {
                merged.insert(key.to_string(), value.clone());
            }
        }
    }
    let duration = merged
        .get("durationSec")
        .and_then(Value::as_f64)
        .unwrap_or(0.0);
    if duration <= 0.0 {
        if let Some(value) = old.get("durationSec") {
            merged.insert("durationSec".to_string(), value.clone());
        }
    }
    Value::Object(merged)
}

pub type ChangeSink = Arc<dyn Fn(Value) + Send + Sync + 'static>;

#[cfg(test)]
fn noop_sink() -> ChangeSink {
    Arc::new(|_| {})
}

pub struct Library {
    path: PathBuf,
    data: Mutex<Value>,
    /// Where a committed state goes. The shell wires this to `app.emit`, so the
    /// store itself stays free of the windowing layer and unit-testable.
    sink: ChangeSink,
    /// A watch-position update changed the state but has not reached the disk.
    dirty: AtomicBool,
}

fn as_array(value: &Value) -> Vec<Value> {
    value.as_array().cloned().unwrap_or_default()
}

impl Library {
    pub fn open(path: PathBuf, sink: ChangeSink) -> Self {
        let mut data = empty_data();
        if let Ok(text) = std::fs::read_to_string(&path) {
            match serde_json::from_str::<Value>(&text) {
                Ok(parsed) if parsed.is_object() => {
                    let mut base = empty_data();
                    if let (Some(base_obj), Some(parsed_obj)) =
                        (base.as_object_mut(), parsed.as_object())
                    {
                        for (key, value) in parsed_obj {
                            base_obj.insert(key.clone(), value.clone());
                        }
                    }
                    if let Some(parsed_lib) = parsed.get("library").and_then(Value::as_object) {
                        if let Some(base_lib) = base.get_mut("library").and_then(Value::as_object_mut) {
                            for (key, value) in parsed_lib {
                                base_lib.insert(key.clone(), value.clone());
                            }
                        }
                    }
                    data = base;
                }
                _ => {
                    let corrupt = path.with_file_name("indiotube-library_corrupt.json");
                    let _ = std::fs::rename(&path, corrupt);
                }
            }
        }
        if data.get("subscriptions").map(Value::is_array) != Some(true) {
            data["subscriptions"] = json!([]);
        }
        if data.get("downloads").map(Value::is_array) != Some(true) {
            data["downloads"] = json!([]);
        }
        let store = Self {
            path,
            data: Mutex::new(data),
            sink,
            dirty: AtomicBool::new(false),
        };
        store.persist();
        store
    }

    fn persist(&self) {
        let data = self
            .data
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let tmp = self.path.with_extension("json.tmp");
        if let Ok(text) = serde_json::to_string(&data) {
            if std::fs::write(&tmp, text).is_ok() {
                let _ = std::fs::rename(&tmp, &self.path);
            }
        }
    }

    pub fn state(&self) -> Value {
        self.data
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// The renderer re-renders from this; Electron's `lib:changed` broadcast.
    fn commit(&self) {
        self.persist();
        (self.sink)(self.state());
    }

    /// A position update is invisible to every screen: it must not re-render the
    /// UI (which would rebuild the feed while a video plays) and it does not
    /// need its own disk write. Mark it and let the flusher batch it.
    fn commit_quiet(&self) {
        self.dirty.store(true, Ordering::SeqCst);
    }

    /// Write a pending quiet update, if any. Returns whether it wrote.
    pub fn flush_if_dirty(&self) -> bool {
        if !self.dirty.swap(false, Ordering::SeqCst) {
            return false;
        }
        self.persist();
        true
    }

    /// Batches quiet writes for the whole session (2 s is short enough that a
    /// crash loses at most one progress tick).
    pub fn spawn_flusher(self: &Arc<Self>) {
        let store = Arc::clone(self);
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_secs(2));
            store.flush_if_dirty();
        });
    }

    /// The sync payload this device offers to others.
    pub fn snapshot_public(&self) -> Value {
        crate::sync_core::build_snapshot(&self.state())
    }

    /// Add-only merge of a peer snapshot; used by the LAN server and by the
    /// "import from another device" path.
    pub fn merge_public(&self, incoming: &Value) -> Result<Value, String> {
        {
            let mut guard = self
                .data
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let (next, stats) = crate::sync_core::merge_incoming(&guard, incoming, &mut uuid_like);
            if let Some(library) = next.get("library") {
                if let (Some(current), Some(merged)) =
                    (guard.get_mut("library").and_then(Value::as_object_mut), library.as_object())
                {
                    for (key, value) in merged {
                        current.insert(key.clone(), value.clone());
                    }
                }
            }
            if let Some(subs) = next.get("subscriptions") {
                guard["subscriptions"] = subs.clone();
            }
            drop(guard);
            self.commit();
            Ok(stats)
        }
    }

    // ---- operations ---------------------------------------------------------

    pub fn run(&self, op: &str, args: &[Value]) -> Result<Value, String> {
        let mut guard = self
            .data
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let data = &mut *guard;
        let arg = |i: usize| args.get(i).cloned().unwrap_or(Value::Null);

        let result = match op {
            "setProfileName" => {
                let name = arg(0);
                let clean = name
                    .as_str()
                    .unwrap_or("")
                    .trim()
                    .chars()
                    .take(48)
                    .collect::<String>();
                let current = data
                    .get("profileName")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string();
                if !clean.is_empty() && clean != current {
                    data["profileName"] = json!(clean);
                }
                Value::Null
            }
            "noteOpen" => {
                let video = arg(0);
                if data["library"]["historyPaused"].as_bool().unwrap_or(false) {
                    Value::Null
                } else {
                    let Some(id) = video_id_of(&video).map(str::to_string) else {
                        return Ok(Value::Null);
                    };
                    let history = as_array(&data["library"]["history"]);
                    let existing = history
                        .iter()
                        .find(|e| e["video"]["videoId"].as_str() == Some(&id))
                        .cloned();
                    let merged = merge_video(existing.as_ref().map(|e| &e["video"]), &video);
                    let entry = json!({
                        "video": merged,
                        "ts": now_ms(),
                        "positionMs": existing.as_ref().and_then(|e| e["positionMs"].as_i64()).unwrap_or(0),
                        "durationMs": existing.as_ref().and_then(|e| e["durationMs"].as_i64()).unwrap_or(0),
                    });
                    let mut next = vec![entry];
                    next.extend(
                        history
                            .into_iter()
                            .filter(|e| e["video"]["videoId"].as_str() != Some(&id)),
                    );
                    next.truncate(HISTORY_CAP);
                    data["library"]["history"] = Value::Array(next);
                    Value::Null
                }
            }
            "notePosition" => {
                let id = arg(0);
                let position = arg(1).as_f64().unwrap_or(0.0);
                let duration = arg(2).as_f64().unwrap_or(0.0);
                if data["library"]["historyPaused"].as_bool().unwrap_or(false) {
                    return Ok(Value::Null);
                }
                let Some(id) = id.as_str() else {
                    return Ok(Value::Null);
                };
                let history = as_array(&data["library"]["history"]);
                let Some(entry) = history
                    .iter()
                    .find(|e| e["video"]["videoId"].as_str() == Some(id))
                    .cloned()
                else {
                    return Ok(Value::Null);
                };
                let old_position = entry["positionMs"].as_f64().unwrap_or(0.0);
                let old_duration = entry["durationMs"].as_f64().unwrap_or(0.0);
                let same_position = (old_position - position).abs() < 5000.0;
                let same_duration = duration <= 0.0 || old_duration == duration;
                if same_position && same_duration {
                    Value::Null
                } else {
                    let mut updated = entry.clone();
                    // Whole milliseconds, exactly like the JavaScript numbers on
                    // the wire (and on Android).
                    updated["positionMs"] = json!(position.round() as i64);
                    updated["durationMs"] = json!(if duration > 0.0 {
                        duration.round() as i64
                    } else {
                        old_duration.round() as i64
                    });
                    updated["ts"] = json!(now_ms());
                    let mut next = vec![updated];
                    next.extend(
                        history
                            .into_iter()
                            .filter(|e| e["video"]["videoId"].as_str() != Some(id)),
                    );
                    data["library"]["history"] = Value::Array(next);
                    Value::Null
                }
            }
            "resumePosition" => {
                let id = arg(0);
                let id = id.as_str().unwrap_or("");
                let history = as_array(&data["library"]["history"]);
                let Some(entry) = history
                    .iter()
                    .find(|e| e["video"]["videoId"].as_str() == Some(id))
                else {
                    return Ok(json!(0));
                };
                let Some(fraction) = watched_fraction(entry) else {
                    return Ok(json!(0));
                };
                if fraction >= COMPLETED_FRACTION {
                    return Ok(json!(0));
                }
                let position = entry["positionMs"].as_f64().unwrap_or(0.0);
                if position <= 20_000.0 {
                    return Ok(json!(0));
                }
                let duration = entry["durationMs"]
                    .as_f64()
                    .filter(|d| *d > 0.0)
                    .unwrap_or_else(|| {
                        entry["video"]["durationSec"].as_f64().unwrap_or(0.0) * 1000.0
                    });
                if duration > 0.0 && position >= duration - 10_000.0 {
                    return Ok(json!(0));
                }
                Value::Number(serde_json::Number::from(position as i64))
            }
            "isWatched" => {
                let id = arg(0);
                let id = id.as_str().unwrap_or("");
                let history = as_array(&data["library"]["history"]);
                let watched = history
                    .iter()
                    .find(|e| e["video"]["videoId"].as_str() == Some(id))
                    .and_then(watched_fraction)
                    .unwrap_or(0.0);
                json!(watched >= COMPLETED_FRACTION)
            }
            "setHistoryPaused" => {
                let paused = arg(0).as_bool().unwrap_or(false);
                if data["library"]["historyPaused"].as_bool().unwrap_or(false) != paused {
                    data["library"]["historyPaused"] = json!(paused);
                }
                Value::Null
            }
            "removeFromHistory" => {
                let raw = arg(0);
                let ids: Vec<String> = match raw {
                    Value::Array(items) => items
                        .iter()
                        .filter_map(|v| v.as_str().map(str::to_string))
                        .collect(),
                    Value::String(s) => vec![s],
                    _ => Vec::new(),
                };
                let before = as_array(&data["library"]["history"]).len();
                let kept: Vec<Value> = as_array(&data["library"]["history"])
                    .into_iter()
                    .filter(|e| {
                        e["video"]["videoId"]
                            .as_str()
                            .map(|id| !ids.iter().any(|x| x == id))
                            .unwrap_or(true)
                    })
                    .collect();
                if kept.len() != before {
                    data["library"]["history"] = Value::Array(kept);
                }
                Value::Null
            }
            "markWatched" => {
                let video = arg(0);
                if data["library"]["historyPaused"].as_bool().unwrap_or(false) {
                    // Nothing changed: answer false so the caller can explain,
                    // and let the normal (already-persisted) state stand.
                    json!(false)
                } else {
                    let Some(id) = video_id_of(&video).map(str::to_string) else {
                        return Ok(json!(false));
                    };
                    let history = as_array(&data["library"]["history"]);
                    let existing = history
                        .iter()
                        .find(|e| e["video"]["videoId"].as_str() == Some(&id));
                    let known = existing
                        .and_then(|e| e["durationMs"].as_f64())
                        .filter(|d| *d > 0.0)
                        .or_else(|| {
                            video
                                .get("durationSec")
                                .and_then(Value::as_f64)
                                .filter(|d| *d > 0.0)
                                .map(|d| d * 1000.0)
                        })
                        .unwrap_or(1000.0);
                    let entry = json!({
                        "video": video,
                        "ts": now_ms(),
                        "positionMs": known.round() as i64,
                        "durationMs": known.round() as i64,
                    });
                    let mut next = vec![entry];
                    next.extend(history.into_iter().filter(|e| e["video"]["videoId"].as_str() != Some(&id)));
                    next.truncate(HISTORY_CAP);
                    data["library"]["history"] = Value::Array(next);
                    json!(true)
                }
            }
            "dismissContinueWatching" | "markUnwatched" => {
                let id = arg(0);
                let id = id.as_str().unwrap_or("");
                let history = as_array(&data["library"]["history"]);
                if let Some(entry) = history
                    .iter()
                    .find(|e| e["video"]["videoId"].as_str() == Some(id))
                {
                    let mut updated = entry.clone();
                    updated["positionMs"] = json!(0);
                    updated["ts"] = json!(now_ms());
                    let mut next = vec![updated];
                    next.extend(
                        history
                            .into_iter()
                            .filter(|e| e["video"]["videoId"].as_str() != Some(id)),
                    );
                    next.truncate(HISTORY_CAP);
                    data["library"]["history"] = Value::Array(next);
                }
                Value::Null
            }
            "markNotInterested" => {
                let video = arg(0);
                if let Some(id) = video_id_of(&video).map(str::to_string) {
                    let mut ids = as_array(&data["library"]["notInterestedIds"]);
                    if !ids.iter().any(|v| v.as_str() == Some(&id)) {
                        ids.push(json!(id));
                        data["library"]["notInterestedIds"] = Value::Array(ids);
                    }
                    if let Some(channel) = video.get("channelId").and_then(Value::as_str) {
                        let kept: Vec<Value> = as_array(&data["library"]["interestedChannelIds"])
                            .into_iter()
                            .filter(|v| v.as_str() != Some(channel))
                            .collect();
                        data["library"]["interestedChannelIds"] = Value::Array(kept);
                    }
                }
                Value::Null
            }
            "undoNotInterested" => {
                let id = arg(0);
                let id = id.as_str().unwrap_or("");
                data["library"]["notInterestedIds"] = Value::Array(
                    as_array(&data["library"]["notInterestedIds"])
                        .into_iter()
                        .filter(|v| v.as_str() != Some(id))
                        .collect(),
                );
                Value::Null
            }
            "markInterested" => {
                let video = arg(0);
                if let Some(id) = video_id_of(&video).map(str::to_string) {
                    data["library"]["notInterestedIds"] = Value::Array(
                        as_array(&data["library"]["notInterestedIds"])
                            .into_iter()
                            .filter(|v| v.as_str() != Some(&id))
                            .collect(),
                    );
                    if let Some(channel) = video.get("channelId").and_then(Value::as_str) {
                        let blocked = as_array(&data["library"]["blockedChannelIds"])
                            .iter()
                            .any(|v| v.as_str() == Some(channel));
                        let mut interested = as_array(&data["library"]["interestedChannelIds"]);
                        if !blocked && !interested.iter().any(|v| v.as_str() == Some(channel)) {
                            interested.push(json!(channel));
                            data["library"]["interestedChannelIds"] = Value::Array(interested);
                        }
                    }
                }
                Value::Null
            }
            "blockChannel" => {
                let channel = arg(0)
                    .get("channelId")
                    .and_then(Value::as_str)
                    .map(str::to_string);
                if let Some(channel) = channel {
                    let mut blocked = as_array(&data["library"]["blockedChannelIds"]);
                    if !blocked.iter().any(|v| v.as_str() == Some(&channel)) {
                        blocked.push(json!(channel.clone()));
                        data["library"]["blockedChannelIds"] = Value::Array(blocked);
                    }
                    data["library"]["interestedChannelIds"] = Value::Array(
                        as_array(&data["library"]["interestedChannelIds"])
                            .into_iter()
                            .filter(|v| v.as_str() != Some(&channel))
                            .collect(),
                    );
                }
                Value::Null
            }
            "unblockChannel" => {
                let channel = arg(0);
                let channel = channel.as_str().unwrap_or("");
                data["library"]["blockedChannelIds"] = Value::Array(
                    as_array(&data["library"]["blockedChannelIds"])
                        .into_iter()
                        .filter(|v| v.as_str() != Some(channel))
                        .collect(),
                );
                Value::Null
            }
            "clearHistory" => {
                data["library"]["history"] = json!([]);
                Value::Null
            }
            "enrichVideo" => {
                let video = arg(0);
                let Some(id) = video_id_of(&video).map(str::to_string) else {
                    return Ok(json!(false));
                };
                let mut changed = false;
                let merge = |old: &Value, changed: &mut bool| -> Value {
                    let mut next = old.clone();
                    let placeholder = is_placeholder_title(old.get("title").and_then(Value::as_str));
                    if placeholder {
                        if let Some(title) = video.get("title").filter(|v| !v.is_null()) {
                            next["title"] = title.clone();
                        }
                    }
                    for key in [
                        "channelId",
                        "channelName",
                        "channelAvatar",
                        "thumbnail",
                        "viewsText",
                        "publishedText",
                    ] {
                        let empty = next
                            .get(key)
                            .map(|v| v.is_null() || v.as_str() == Some(""))
                            .unwrap_or(true);
                        if empty {
                            if let Some(value) = video.get(key).filter(|v| !v.is_null()) {
                                next[key] = value.clone();
                            }
                        }
                    }
                    let duration = next.get("durationSec").and_then(Value::as_f64).unwrap_or(0.0);
                    if duration <= 0.0 {
                        if let Some(value) = video.get("durationSec") {
                            next["durationSec"] = value.clone();
                        }
                    }
                    if next != *old {
                        *changed = true;
                    }
                    next
                };

                let history: Vec<Value> = as_array(&data["library"]["history"])
                    .into_iter()
                    .map(|mut e| {
                        if e["video"]["videoId"].as_str() == Some(&id) {
                            let merged = merge(&e["video"], &mut changed);
                            e["video"] = merged;
                        }
                        e
                    })
                    .collect();
                data["library"]["history"] = Value::Array(history);
                for key in ["watchLater", "liked"] {
                    let list: Vec<Value> = as_array(&data["library"][key])
                        .into_iter()
                        .map(|v| {
                            if v["videoId"].as_str() == Some(&id) {
                                merge(&v, &mut changed)
                            } else {
                                v
                            }
                        })
                        .collect();
                    data["library"][key] = Value::Array(list);
                }
                let playlists: Vec<Value> = as_array(&data["library"]["playlists"])
                    .into_iter()
                    .map(|mut p| {
                        let hit = p["videos"]
                            .as_array()
                            .map(|videos| videos.iter().any(|v| v["videoId"].as_str() == Some(&id)))
                            .unwrap_or(false);
                        if hit {
                            let videos: Vec<Value> = p["videos"]
                                .as_array()
                                .cloned()
                                .unwrap_or_default()
                                .into_iter()
                                .map(|v| {
                                    if v["videoId"].as_str() == Some(&id) {
                                        merge(&v, &mut changed)
                                    } else {
                                        v
                                    }
                                })
                                .collect();
                            p["videos"] = Value::Array(videos);
                        }
                        p
                    })
                    .collect();
                data["library"]["playlists"] = Value::Array(playlists);
                json!(changed)
            }
            "isInWatchLater" => {
                let id = arg(0);
                let present = as_array(&data["library"]["watchLater"])
                    .iter()
                    .any(|v| v["videoId"] == id);
                json!(present)
            }
            "isLiked" => {
                let id = arg(0);
                let present = as_array(&data["library"]["liked"])
                    .iter()
                    .any(|v| v["videoId"] == id);
                json!(present)
            }
            "toggleWatchLater" | "toggleLike" => {
                let key = if op == "toggleWatchLater" { "watchLater" } else { "liked" };
                let video = arg(0);
                let Some(id) = video_id_of(&video).map(str::to_string) else {
                    return Ok(json!(false));
                };
                let list = as_array(&data["library"][key]);
                let present = list.iter().any(|v| v["videoId"].as_str() == Some(&id));
                let next: Vec<Value> = if present {
                    list.into_iter()
                        .filter(|v| v["videoId"].as_str() != Some(&id))
                        .collect()
                } else {
                    let mut out = vec![video];
                    out.extend(list);
                    out
                };
                data["library"][key] = Value::Array(next);
                json!(!present)
            }
            "createPlaylist" => {
                let name = arg(0);
                let clean = name.as_str().unwrap_or("").trim().to_string();
                let name = if clean.is_empty() { "New playlist".to_string() } else { clean };
                let id = uuid_like();
                let playlist = json!({
                    "id": id,
                    "name": name,
                    "createdAt": now_ms(),
                    "videos": [],
                });
                let mut list = vec![playlist.clone()];
                list.extend(as_array(&data["library"]["playlists"]));
                data["library"]["playlists"] = Value::Array(list);
                playlist
            }
            "renamePlaylist" => {
                let id = arg(0);
                let name = arg(1);
                let clean = name.as_str().unwrap_or("").trim().to_string();
                if !clean.is_empty() {
                    let list: Vec<Value> = as_array(&data["library"]["playlists"])
                        .into_iter()
                        .map(|mut p| {
                            if p["id"] == id {
                                p["name"] = json!(clean);
                            }
                            p
                        })
                        .collect();
                    data["library"]["playlists"] = Value::Array(list);
                }
                Value::Null
            }
            "deletePlaylist" => {
                let id = arg(0);
                data["library"]["playlists"] = Value::Array(
                    as_array(&data["library"]["playlists"])
                        .into_iter()
                        .filter(|p| p["id"] != id)
                        .collect(),
                );
                Value::Null
            }
            "addToPlaylist" => {
                let playlist_id = arg(0);
                let video = arg(1);
                let Some(id) = video_id_of(&video).map(str::to_string) else {
                    return Ok(Value::Null);
                };
                let list: Vec<Value> = as_array(&data["library"]["playlists"])
                    .into_iter()
                    .map(|mut p| {
                        if p["id"] == playlist_id {
                            let videos = as_array(&p["videos"]);
                            if !videos.iter().any(|v| v["videoId"].as_str() == Some(&id)) {
                                let mut next = videos;
                                next.push(video.clone());
                                p["videos"] = Value::Array(next);
                            }
                        }
                        p
                    })
                    .collect();
                data["library"]["playlists"] = Value::Array(list);
                Value::Null
            }
            "removeFromPlaylist" => {
                let playlist_id = arg(0);
                let video_id = arg(1);
                let list: Vec<Value> = as_array(&data["library"]["playlists"])
                    .into_iter()
                    .map(|mut p| {
                        if p["id"] == playlist_id {
                            let videos: Vec<Value> = as_array(&p["videos"])
                                .into_iter()
                                .filter(|v| v["videoId"] != video_id)
                                .collect();
                            p["videos"] = Value::Array(videos);
                        }
                        p
                    })
                    .collect();
                data["library"]["playlists"] = Value::Array(list);
                Value::Null
            }
            "noteSearch" => {
                let query = arg(0);
                let q = query.as_str().unwrap_or("").trim().to_string();
                if !q.is_empty() {
                    let lower = q.to_lowercase();
                    let mut next = vec![json!({ "query": q, "ts": now_ms() })];
                    next.extend(
                        as_array(&data["library"]["searches"])
                            .into_iter()
                            .filter(|s| {
                                s["query"]
                                    .as_str()
                                    .map(|x| x.to_lowercase() != lower)
                                    .unwrap_or(true)
                            }),
                    );
                    next.truncate(SEARCH_CAP);
                    data["library"]["searches"] = Value::Array(next);
                }
                Value::Null
            }
            "clearSearches" => {
                data["library"]["searches"] = json!([]);
                Value::Null
            }
            "isSubscribed" => {
                let id = arg(0);
                let present = id
                    .as_str()
                    .map(|id| {
                        as_array(&data["subscriptions"])
                            .iter()
                            .any(|s| s["channelId"].as_str() == Some(id))
                    })
                    .unwrap_or(false);
                json!(present)
            }
            "subscribe" => {
                let sub = arg(0);
                let channel = sub.get("channelId").and_then(Value::as_str).map(str::to_string);
                let Some(channel) = channel else {
                    return Ok(Value::Null);
                };
                let already = as_array(&data["subscriptions"])
                    .iter()
                    .any(|s| s["channelId"].as_str() == Some(&channel));
                if !already {
                    let mut entry = sub.clone();
                    entry["at"] = json!(now_ms());
                    let mut next = vec![entry];
                    next.extend(as_array(&data["subscriptions"]));
                    data["subscriptions"] = Value::Array(next);
                }
                Value::Null
            }
            "unsubscribe" => {
                let channel = arg(0);
                data["subscriptions"] = Value::Array(
                    as_array(&data["subscriptions"])
                        .into_iter()
                        .filter(|s| s["channelId"] != channel)
                        .collect(),
                );
                Value::Null
            }
            "addDownload" => {
                let item = arg(0);
                let Some(id) = item
                    .get("video")
                    .and_then(|v| v.get("videoId"))
                    .and_then(Value::as_str)
                    .map(str::to_string)
                else {
                    return Ok(Value::Null);
                };
                let mut next = vec![item];
                next.extend(
                    as_array(&data["downloads"])
                        .into_iter()
                        .filter(|d| d["video"]["videoId"].as_str() != Some(&id)),
                );
                data["downloads"] = Value::Array(next);
                Value::Null
            }
            "removeDownload" => {
                let id = arg(0);
                data["downloads"] = Value::Array(
                    as_array(&data["downloads"])
                        .into_iter()
                        .filter(|d| d["video"]["videoId"] != id)
                        .collect(),
                );
                Value::Null
            }
            "snapshot" => {
                return Ok(crate::sync_core::build_snapshot(data));
            }
            "mergeIncoming" => {
                let incoming = arg(0);
                let (next, stats) =
                    crate::sync_core::merge_incoming(data, &incoming, &mut uuid_like);
                if let Some(library) = next.get("library") {
                    if let (Some(current), Some(merged)) =
                        (data.get_mut("library").and_then(Value::as_object_mut), library.as_object())
                    {
                        for (key, value) in merged {
                            current.insert(key.clone(), value.clone());
                        }
                    }
                }
                if let Some(subs) = next.get("subscriptions") {
                    data["subscriptions"] = subs.clone();
                }
                stats
            }
            other => return Err(format!("unknown library operation {other}")),
        };

        drop(guard);
        let read_only = matches!(
            op,
            "resumePosition" | "isWatched" | "isInWatchLater" | "isLiked" | "isSubscribed" | "snapshot"
        );
        if !read_only {
            if op == "notePosition" {
                self.commit_quiet();
            } else {
                self.commit();
            }
        }
        Ok(result)
    }
}

/// UUID v4 without a crate: 16 random bytes with the version/variant bits set.
pub fn uuid_like() -> String {
    let mut bytes = [0u8; 16];
    let seed = {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0);
        let addr = &seed_bytes() as *const u8 as usize as u64;
        nanos ^ (addr.rotate_left(17)).wrapping_mul(0x9E37_79B9_7F4A_7C15)
    };
    let mut state = seed | 1;
    for byte in bytes.iter_mut() {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        *byte = (state & 0xff) as u8;
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
    )
}

fn seed_bytes() -> [u8; 8] {
    [0u8; 8]
}

/// Settings defaults, exactly the Electron ones, so a fresh install behaves the
/// same in either shell.
pub fn default_settings(download_dir: &str) -> Value {
    json!({
        "theme": "dark",
        "autoplay": true,
        "defaultQuality": Value::Null,
        "captionsEnabled": false,
        "captionLang": "",
        "sponsorBlock": true,
        "sponsorBlockAutoSkip": true,
        "sponsorBlockCategories": [
            "sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler"
        ],
        "downloadDir": download_dir,
        "downloadHeight": 720,
        "volume": 1,
        "playbackRate": 1,
        "theater": false,
    })
}

pub fn merge_settings(current: &mut Map<String, Value>, patch: &Map<String, Value>) {
    for (key, value) in patch {
        current.insert(key.clone(), value.clone());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One temp folder per test, so the JSON file and the corrupt-file rename
    /// cannot collide across parallel tests.
    struct TmpDir(PathBuf);

    impl TmpDir {
        fn new(tag: &str) -> Self {
            let dir = std::env::temp_dir().join(format!(
                "indio-lib-{}-{}-{}",
                tag,
                std::process::id(),
                now_ms()
            ));
            let _ = std::fs::create_dir_all(&dir);
            Self(dir)
        }

        fn file(&self) -> PathBuf {
            self.0.join("indiotube-library.json")
        }

        fn store(&self) -> Library {
            Library::open(self.file(), noop_sink())
        }
    }

    impl Drop for TmpDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn video(id: &str) -> Value {
        json!({
            "videoId": id,
            "title": format!("T-{id}"),
            "channelId": "UCchan",
            "channelName": "Chan",
            "durationSec": 100,
            "isLive": false,
            "membersOnly": false,
        })
    }

    fn ids(state: &Value, path: &[&str]) -> Vec<String> {
        let mut node: Value = state.clone();
        for key in path {
            node = node.get(*key).cloned().unwrap_or(Value::Null);
        }
        node.as_array()
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .filter_map(|v| v.as_str().map(str::to_string))
            .collect()
    }

    #[test]
    fn recommendation_feedback_matches_android() {
        let tmp = TmpDir::new("feedback");
        let store = tmp.store();
        let v = video("v1");

        store.run("markInterested", &[v.clone()]).unwrap();
        assert!(ids(&store.state(), &["library", "interestedChannelIds"]).contains(&"UCchan".to_string()));

        store.run("markNotInterested", &[v.clone()]).unwrap();
        let state = store.state();
        assert_eq!(ids(&state, &["library", "notInterestedIds"]), vec!["v1"]);
        assert!(ids(&state, &["library", "interestedChannelIds"]).is_empty());

        store.run("undoNotInterested", &[json!("v1")]).unwrap();
        assert!(ids(&store.state(), &["library", "notInterestedIds"]).is_empty());

        // "Don't recommend channel" blocks once and drops the positive pin.
        store.run("markInterested", &[v.clone()]).unwrap();
        store.run("blockChannel", &[v.clone()]).unwrap();
        store.run("blockChannel", &[video("v2")]).unwrap();
        let state = store.state();
        assert_eq!(ids(&state, &["library", "blockedChannelIds"]), vec!["UCchan"]);
        assert!(ids(&state, &["library", "interestedChannelIds"]).is_empty());
        store.run("unblockChannel", &[json!("UCchan")]).unwrap();
        assert!(ids(&store.state(), &["library", "blockedChannelIds"]).is_empty());
    }

    #[test]
    fn mark_watched_completes_and_mark_unwatched_keeps_the_row() {
        let tmp = TmpDir::new("watched");
        let store = tmp.store();
        assert_eq!(store.run("markWatched", &[video("v1")]).unwrap(), json!(true));
        let history = store.state()["library"]["history"].as_array().unwrap().clone();
        assert_eq!(history[0]["video"]["videoId"], "v1");
        assert_eq!(history[0]["positionMs"], 100_000);
        assert_eq!(history[0]["durationMs"], 100_000);
        assert_eq!(store.run("isWatched", &[json!("v1")]).unwrap(), json!(true));
        // A finished video starts over rather than resuming at the end.
        assert_eq!(store.run("resumePosition", &[json!("v1")]).unwrap(), json!(0));

        store.run("markUnwatched", &[json!("v1")]).unwrap();
        let history = store.state()["library"]["history"].as_array().unwrap().clone();
        assert_eq!(history[0]["positionMs"], 0);
        assert_eq!(store.run("isWatched", &[json!("v1")]).unwrap(), json!(false));
    }

    #[test]
    fn short_videos_keep_their_real_duration_when_marked_watched() {
        let tmp = TmpDir::new("short");
        let store = tmp.store();
        store
            .run("noteOpen", &[json!({ "videoId": "s1", "title": "S", "durationSec": 8 })])
            .unwrap();
        store
            .run("notePosition", &[json!("s1"), json!(3_000), json!(8_000)])
            .unwrap();
        store.run("markWatched", &[video("s1")]).unwrap();
        let history = store.state()["library"]["history"].as_array().unwrap().clone();
        // The stored 8 s duration is in milliseconds, not something to scale.
        assert_eq!(history[0]["durationMs"], 8_000);
        assert_eq!(history[0]["positionMs"], 8_000);
    }

    #[test]
    fn resume_position_waits_for_real_progress() {
        let tmp = TmpDir::new("resume");
        let store = tmp.store();
        store.run("noteOpen", &[video("v1")]).unwrap();
        assert_eq!(store.run("resumePosition", &[json!("v1")]).unwrap(), json!(0));
        store
            .run("notePosition", &[json!("v1"), json!(45_000), json!(100_000)])
            .unwrap();
        assert_eq!(store.run("resumePosition", &[json!("v1")]).unwrap(), json!(45_000));
        // Under the 20 s intro threshold nothing is worth resuming.
        store
            .run("notePosition", &[json!("v1"), json!(9_000), json!(100_000)])
            .unwrap();
        assert_eq!(store.run("resumePosition", &[json!("v1")]).unwrap(), json!(0));
    }

    #[test]
    fn history_pause_blocks_writes_but_not_reads() {
        let tmp = TmpDir::new("paused");
        let store = tmp.store();
        store.run("setHistoryPaused", &[json!(true)]).unwrap();
        assert_eq!(store.run("markWatched", &[video("v1")]).unwrap(), json!(false));
        store.run("noteOpen", &[video("v1")]).unwrap();
        assert!(store.state()["library"]["history"].as_array().unwrap().is_empty());
        store.run("setHistoryPaused", &[json!(false)]).unwrap();
        store.run("noteOpen", &[video("v1")]).unwrap();
        assert_eq!(
            store.state()["library"]["history"].as_array().unwrap().len(),
            1
        );
    }

    #[test]
    fn placeholder_titles_never_overwrite_known_ones() {
        let tmp = TmpDir::new("placeholder");
        let store = tmp.store();
        store.run("noteOpen", &[video("v1")]).unwrap();
        store
            .run(
                "noteOpen",
                &[json!({ "videoId": "v1", "title": "Loading…", "durationSec": 0 })],
            )
            .unwrap();
        let entry = store.state()["library"]["history"][0].clone();
        assert_eq!(entry["video"]["title"], "T-v1");
        assert_eq!(entry["video"]["channelName"], "Chan");

        // …and enrichVideo only fills gaps: a title we already know stands.
        let enriched = store
            .run(
                "enrichVideo",
                &[json!({ "videoId": "v1", "title": "Real", "viewsText": "1M views" })],
            )
            .unwrap();
        assert_eq!(enriched, json!(true));
        let entry = store.state()["library"]["history"][0].clone();
        assert_eq!(entry["video"]["title"], "T-v1");
        assert_eq!(entry["video"]["viewsText"], "1M views");

        // A row opened from the watch-page stub has nothing yet, so the real
        // metadata lands on the first enrich.
        store
            .run(
                "noteOpen",
                &[json!({ "videoId": "v2", "title": "Loading…", "durationSec": 0 })],
            )
            .unwrap();
        store
            .run(
                "enrichVideo",
                &[json!({ "videoId": "v2", "title": "Actual", "channelId": "UCv" })],
            )
            .unwrap();
        let rows = store.state()["library"]["history"].as_array().unwrap().clone();
        let row = rows
            .iter()
            .find(|e| e["video"]["videoId"] == json!("v2"))
            .unwrap();
        assert_eq!(row["video"]["title"], "Actual");
        assert_eq!(row["video"]["channelId"], "UCv");
    }

    #[test]
    fn playlists_watch_later_and_subscriptions_toggle() {
        let tmp = TmpDir::new("lists");
        let store = tmp.store();
        assert_eq!(
            store.run("toggleWatchLater", &[video("v1")]).unwrap(),
            json!(true)
        );
        assert_eq!(
            store.run("isInWatchLater", &[json!("v1")]).unwrap(),
            json!(true)
        );
        store.run("toggleWatchLater", &[video("v1")]).unwrap();
        assert_eq!(
            store.run("isInWatchLater", &[json!("v1")]).unwrap(),
            json!(false)
        );

        let created = store.run("createPlaylist", &[json!("  My Mix ")]).unwrap();
        let id = created["id"].as_str().unwrap().to_string();
        assert_eq!(created["name"], "My Mix");
        store.run("addToPlaylist", &[json!(id.clone()), video("v1")]).unwrap();
        store.run("addToPlaylist", &[json!(id.clone()), video("v1")]).unwrap();
        let playlists = store.state()["library"]["playlists"].as_array().unwrap().clone();
        assert_eq!(playlists[0]["videos"].as_array().unwrap().len(), 1);
        store.run("renamePlaylist", &[json!(id.clone()), json!("Other")]).unwrap();
        assert_eq!(store.state()["library"]["playlists"][0]["name"], "Other");
        store
            .run("removeFromPlaylist", &[json!(id.clone()), json!("v1")])
            .unwrap();
        assert!(store.state()["library"]["playlists"][0]["videos"]
            .as_array()
            .unwrap()
            .is_empty());
        store.run("deletePlaylist", &[json!(id)]).unwrap();
        assert!(store.state()["library"]["playlists"].as_array().unwrap().is_empty());

        store
            .run("subscribe", &[json!({ "channelId": "UCchan", "name": "Chan" })])
            .unwrap();
        assert_eq!(
            store.run("isSubscribed", &[json!("UCchan")]).unwrap(),
            json!(true)
        );
        store.run("unsubscribe", &[json!("UCchan")]).unwrap();
        assert_eq!(
            store.run("isSubscribed", &[json!("UCchan")]).unwrap(),
            json!(false)
        );

        store.run("noteSearch", &[json!("rust")]).unwrap();
        store.run("noteSearch", &[json!("RUST")]).unwrap();
        assert_eq!(store.state()["library"]["searches"].as_array().unwrap().len(), 1);
        store.run("clearSearches", &[]).unwrap();
        assert!(store.state()["library"]["searches"].as_array().unwrap().is_empty());
    }

    #[test]
    fn every_mutation_reaches_the_change_sink() {
        // Regression guard for the Electron bug where the listener registry and
        // the subscription action shared a name, so the renderer never saw a
        // mutation. The sink is the only path the UI has.
        let tmp = TmpDir::new("sink");
        let seen: Arc<Mutex<Vec<Vec<String>>>> = Arc::new(Mutex::new(Vec::new()));
        let sink_seen = Arc::clone(&seen);
        let store = Library::open(
            tmp.file(),
            Arc::new(move |state: Value| {
                sink_seen
                    .lock()
                    .unwrap()
                    .push(ids(&state, &["library", "notInterestedIds"]));
            }),
        );
        store.run("markNotInterested", &[video("v1")]).unwrap();
        store.run("markInterested", &[video("v1")]).unwrap();
        assert_eq!(*seen.lock().unwrap(), vec![vec!["v1".to_string()], vec![]]);

        // Read-only ops must not spam the renderer.
        store.run("isWatched", &[json!("v1")]).unwrap();
        store.run("resumePosition", &[json!("v1")]).unwrap();
        store.run("snapshot", &[]).unwrap();
        assert_eq!(seen.lock().unwrap().len(), 2);
    }

    #[test]
    fn state_survives_a_restart_and_corrupt_files_are_moved_aside() {
        let tmp = TmpDir::new("persist");
        {
            let store = tmp.store();
            store.run("markNotInterested", &[video("v1")]).unwrap();
            store.run("blockChannel", &[video("v2")]).unwrap();
            store
                .run("addDownload", &[json!({ "video": video("v1"), "file": "a.mp4" })])
                .unwrap();
        }
        let reloaded = tmp.store();
        let state = reloaded.state();
        assert_eq!(ids(&state, &["library", "notInterestedIds"]), vec!["v1"]);
        assert_eq!(ids(&state, &["library", "blockedChannelIds"]), vec!["UCchan"]);
        assert_eq!(state["downloads"].as_array().unwrap().len(), 1);

        std::fs::write(tmp.file(), "{ not json").unwrap();
        let recovered = tmp.store();
        assert!(recovered.state()["library"]["history"]
            .as_array()
            .unwrap()
            .is_empty());
        assert!(tmp.0.join("indiotube-library_corrupt.json").exists());
    }

    #[test]
    fn sync_ops_round_trip_through_the_store() {
        let tmp = TmpDir::new("syncops");
        let store = tmp.store();
        store.run("toggleLike", &[video("seed-like")]).unwrap();
        store
            .run("noteOpen", &[video("seed-hist")])
            .unwrap();
        let snapshot = store.run("snapshot", &[]).unwrap();
        assert_eq!(snapshot["indio"], "sync");
        assert_eq!(snapshot["library"]["liked"].as_array().unwrap().len(), 1);

        let stats = store
            .run(
                "mergeIncoming",
                &[json!({
                    "indio": "sync",
                    "name": "Pixel",
                    "library": { "watchLater": [video("phone-wl")], "liked": [video("phone-like")] },
                    "subscriptions": [{ "channelId": "UCphone", "name": "Phone" }]
                })],
            )
            .unwrap();
        assert_eq!(stats["watchLaterAdded"], 1);
        assert_eq!(stats["likedAdded"], 1);
        assert_eq!(stats["subsAdded"], 1);
        let state = store.state();
        let channel_ids: Vec<String> = state["subscriptions"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|s| s["channelId"].as_str().map(str::to_string))
            .collect();
        assert_eq!(channel_ids, vec!["UCphone"]);
    }

    #[test]
    fn unknown_operations_are_rejected_like_the_electron_allowlist() {
        let tmp = TmpDir::new("unknown");
        let store = tmp.store();
        let err = store.run("dropEverything", &[]).unwrap_err();
        assert!(err.contains("unknown library operation"), "{err}");
    }

    #[test]
    fn default_settings_match_the_electron_defaults() {
        let settings = default_settings("C:\\Downloads\\IndioTube");
        assert_eq!(settings["theme"], "dark");
        assert_eq!(settings["autoplay"], true);
        assert!(settings["defaultQuality"].is_null());
        assert_eq!(settings["downloadHeight"], 720);
        assert_eq!(settings["sponsorBlockCategories"].as_array().unwrap().len(), 8);

        let mut current = settings.as_object().unwrap().clone();
        merge_settings(
            &mut current,
            &json!({ "theme": "light", "volume": 0.5 })
                .as_object()
                .unwrap()
                .clone(),
        );
        assert_eq!(current["theme"], "light");
        assert_eq!(current["volume"], 0.5);
        assert_eq!(current["autoplay"], true);
    }

    #[test]
    fn uuid_like_looks_like_a_v4_uuid() {
        let id = uuid_like();
        assert_eq!(id.len(), 36);
        assert_eq!(&id[14..15], "4");
        assert_ne!(id, uuid_like());
    }
}
