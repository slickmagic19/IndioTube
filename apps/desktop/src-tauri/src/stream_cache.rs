//! The on-disk quality cache: the Rust counterpart of the `streams-cache.json`
//! block in `electron/streams.js`.
//!
//! Extraction is the slow part of pressing play (measured ~5s, with occasional
//! 15-25s stalls when YouTube is slow). Signed format URLs stay valid for about
//! six hours, so a resolved ladder is kept between runs: reopening a video — or
//! starting the app on the one you were watching — skips yt-dlp completely.
//!
//! Everything here works in epoch milliseconds and takes `now` as an argument,
//! so the expiry rules are testable without waiting five hours.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use indio_core::quality::QualityLadder;
use serde_json::{json, Value};

/// Signed URLs live ~6h; refresh a little before that.
pub const TTL: Duration = Duration::from_secs(5 * 3600);
/// The file is a convenience, not an archive: keep the most recent handful.
pub const MAX_ENTRIES: usize = 60;
/// Shape of a stored ladder. Bumped when the ladder gained its 60 fps rungs (2):
/// a file written by an older build has no high-frame-rate options in it, and
/// keeping it would hide 720p60 for up to six hours.
pub const FORMAT: u64 = 2;

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Drop the oldest entries of an in-memory map until at most `max` remain.
///
/// The SponsorBlock answer cache used to be one entry per video ever opened
/// and nothing ever removed them, so a long session kept growing on a machine
/// where memory is the scarce resource. Returns how many entries were dropped.
pub fn trim_to_newest<T>(map: &mut HashMap<String, (Instant, T)>, max: usize) -> usize {
    let mut removed = 0;
    while map.len() > max {
        let oldest = map
            .iter()
            .min_by_key(|(_, (at, _))| *at)
            .map(|(key, _)| key.clone());
        let Some(key) = oldest else { break };
        map.remove(&key);
        removed += 1;
    }
    removed
}

/// Read the cache. A missing, unreadable, half-written or stale file is simply
/// an empty cache — the worst case is the extraction the viewer would have
/// skipped.
pub fn load(path: &Path, now: u64) -> HashMap<String, (u64, QualityLadder)> {
    let mut out = HashMap::new();
    let Ok(text) = std::fs::read_to_string(path) else {
        return out;
    };
    let Ok(doc) = serde_json::from_str::<Value>(&text) else {
        return out;
    };
    // A file written before the ladder changed shape is worse than no cache:
    // it would hide the renditions the player now offers.
    if doc.get("v").and_then(Value::as_u64) != Some(FORMAT) {
        return out;
    }
    let Some(entries) = doc.get("entries").and_then(Value::as_object) else {
        return out;
    };
    for (video_id, entry) in entries {
        let Some(at) = entry.get("at").and_then(Value::as_u64) else {
            continue;
        };
        if now.saturating_sub(at) >= TTL.as_millis() as u64 {
            continue;
        }
        let Some(qualities) = entry.get("qualities") else {
            continue;
        };
        match serde_json::from_value::<QualityLadder>(qualities.clone()) {
            Ok(ladder) => {
                out.insert(video_id.clone(), (at, ladder));
            }
            // A cache written by an older build: ignore the whole file rather
            // than half-trusting it.
            Err(_) => return HashMap::new(),
        }
    }
    out
}

/// Write the cache, newest first and capped. Written to a temporary file and
/// renamed, so a crash mid-save cannot leave a truncated JSON behind.
pub fn save(path: &Path, entries: &HashMap<String, (u64, QualityLadder)>, now: u64) {
    let mut fresh: Vec<(&String, &u64, &QualityLadder)> = entries
        .iter()
        .filter(|(_, (at, _))| now.saturating_sub(*at) < TTL.as_millis() as u64)
        .map(|(id, (at, ladder))| (id, at, ladder))
        .collect();
    fresh.sort_by(|a, b| b.1.cmp(a.1));
    fresh.truncate(MAX_ENTRIES);

    let mut doc = serde_json::Map::new();
    for (id, at, ladder) in fresh {
        if let Ok(value) = serde_json::to_value(ladder) {
            doc.insert(id.clone(), json!({ "at": at, "qualities": value }));
        }
    }
    let text = match serde_json::to_string(&json!({ "v": FORMAT, "entries": doc })) {
        Ok(text) => text,
        Err(_) => return,
    };
    let tmp: PathBuf = path.with_extension("json.tmp");
    if std::fs::write(&tmp, text).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ladder(id: &str) -> QualityLadder {
        let info = json!({
            "id": id,
            "title": format!("T-{id}"),
            "duration": 60,
            "formats": [
                { "itag": 18, "url": format!("https://cdn/{id}"), "ext": "mp4",
                  "vcodec": "avc1.4d401e", "acodec": "mp4a.40.2", "height": 360,
                  "fps": 30, "tbr": 700, "protocol": "https" }
            ]
        });
        indio_core::quality::build_qualities(&info)
    }

    fn temp(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "indio-stream-cache-{}-{}-{}.json",
            name,
            std::process::id(),
            now_ms()
        ))
    }

    #[test]
    fn a_missing_or_broken_file_is_just_an_empty_cache() {
        let path = temp("missing");
        assert!(load(&path, now_ms()).is_empty());
        std::fs::write(&path, "not json at all").unwrap();
        assert!(load(&path, now_ms()).is_empty());
        std::fs::write(&path, r#"{"v":1,"entries":{"abc":{"at":"nope"}}}"#).unwrap();
        assert!(load(&path, now_ms()).is_empty());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_saved_ladder_comes_back_identical() {
        let path = temp("roundtrip");
        let mut entries = HashMap::new();
        entries.insert("abc123".to_string(), (1_000u64, ladder("abc123")));
        entries.insert("def456".to_string(), (2_000u64, ladder("def456")));
        save(&path, &entries, 3_000);

        let loaded = load(&path, 3_000);
        assert_eq!(loaded.len(), 2);
        assert_eq!(loaded["abc123"].0, 1_000);
        assert_eq!(loaded["abc123"].1, ladder("abc123"));
        assert_eq!(loaded["def456"].1.options.len(), 1);
        assert_eq!(loaded["def456"].1.auto_height, 360);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn expired_entries_are_dropped_on_both_ends() {
        let path = temp("expiry");
        let age = TTL.as_millis() as u64 + 1;
        let mut entries = HashMap::new();
        entries.insert("old".to_string(), (0u64, ladder("old")));
        entries.insert("new".to_string(), (age, ladder("new")));
        // Written a second after "new" was resolved, long after "old" expired.
        let now = age + 1_000;
        save(&path, &entries, now);

        // `save` keeps only what is still inside the TTL …
        let text = std::fs::read_to_string(&path).unwrap();
        let doc: Value = serde_json::from_str(&text).unwrap();
        let keys: Vec<&String> = doc["entries"].as_object().unwrap().keys().collect();
        assert_eq!(keys, vec!["new"]);
        // … and `load` ignores anything that aged out since it was written.
        let loaded = load(&path, now + TTL.as_millis() as u64 + 1);
        assert!(loaded.is_empty());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_cache_from_the_old_ladder_shape_is_ignored() {
        let path = temp("shape-version");
        let old = json!({
            "v": FORMAT - 1,
            "entries": { "abc123": { "at": now_ms(), "qualities": ladder("abc123") } }
        });
        std::fs::write(&path, old.to_string()).unwrap();
        assert!(load(&path, now_ms()).is_empty(), "the old shape hides 60 fps");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_in_memory_cache_drops_its_oldest_entries() {
        let now = Instant::now();
        let mut map: HashMap<String, (Instant, &str)> = HashMap::new();
        map.insert("oldest".to_string(), (now - Duration::from_secs(30), "a"));
        map.insert("middle".to_string(), (now - Duration::from_secs(10), "b"));
        map.insert("newest".to_string(), (now - Duration::from_secs(1), "c"));
        assert_eq!(trim_to_newest(&mut map, 3), 0);
        assert_eq!(trim_to_newest(&mut map, 2), 1);
        assert!(map.get("oldest").is_none());
        assert!(map.contains_key("newest"));
        assert_eq!(trim_to_newest(&mut map, 0), 2);
        assert!(map.is_empty());
    }

    #[test]
    fn the_file_stays_bounded_to_the_newest_entries() {
        let path = temp("cap");
        let mut entries = HashMap::new();
        for i in 0..(MAX_ENTRIES as u64 + 40) {
            entries.insert(format!("v{i}"), (1_000 + i, ladder("v")));
        }
        save(&path, &entries, 10_000);
        let doc: Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(doc["entries"].as_object().unwrap().len(), MAX_ENTRIES);
        // The newest 60 survive: ids v79…v39 in descending age.
        assert!(doc["entries"].get("v99").is_some());
        assert!(doc["entries"].get("v0").is_none());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn an_unknown_ladder_shape_clears_the_cache_instead_of_guessing() {
        let path = temp("shape");
        // A payload from a build whose ladder fields no longer line up.
        std::fs::write(
            &path,
            json!({ "v": 1, "entries": { "abc": { "at": now_ms(), "qualities": { "nope": 1 } } } })
                .to_string(),
        )
        .unwrap();
        assert!(load(&path, now_ms()).is_empty());
        let _ = std::fs::remove_file(&path);
    }
}
