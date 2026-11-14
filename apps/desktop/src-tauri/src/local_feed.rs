//! Local Home recommendations — the Rust port of `electron/localFeed.js`,
//! itself the port of Android's `LocalFeed.kt`.
//!
//! YouTube serves nothing to a signed-out session, so Home is computed on the
//! device from what the viewer actually watches: subscription uploads, the last
//! couple of searches, the videos related to recent watches, plus everything the
//! library already knows about channel affinity. Every rebuild draws a new
//! weighted sample, so a refresh genuinely changes the mix, and the per-channel
//! cap keeps one uploader from taking over the page.
//!
//! The ranking is the testable heart of this module: `rank_and_sample` is pure
//! (a seeded RNG makes a rotation reproducible), and `LocalFeed` only adds the
//! network gather, the 10-minute pool cache and the rotation counter.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use indio_core::innertube::{
    Chip, InnerTube, SearchEntry, VideoItem, CHANNEL_TAB_VIDEOS,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::http::UreqTransport;

/// A client factory that can move into a background thread: each recommendation
/// worker needs its own `InnerTube`, and a refresh behind a stale page has to
/// outlive the command that started it.
pub type TubeFactory =
    Arc<dyn Fn() -> InnerTube<UreqTransport> + Send + Sync + 'static>;

const COMPLETED_FRACTION: f64 = 0.92;
const CACHE_TTL: Duration = Duration::from_secs(10 * 60);
/// How many videos one draw produces, and the cap on a local "load more" page
/// (a renderer bug must not ask for the whole candidate pool at once).
pub const RANK_LIMIT: usize = 80;
pub const MAX_PER_CHANNEL: usize = 3;
pub const PAGE_SIZE: usize = 40;
const REBUILD_THRESHOLD: usize = 12;
const MAX_CHANNELS: usize = 32;
const CHANNEL_WORKERS: usize = 8;

const STOP_WORDS: &[&str] = &[
    "the", "and", "for", "with", "from", "that", "this", "you", "your", "how", "what", "why",
    "when", "who", "are", "was", "were", "will", "can", "not", "official", "video", "music",
    "song", "full", "new", "best", "top",
];

/// "2 days ago" → a rough age in minutes (port of `publishAgeMinutes`).
pub fn publish_age_minutes(published_text: Option<&str>) -> f64 {
    const UNKNOWN: f64 = f64::MAX / 4.0;
    let text = published_text.unwrap_or("").to_lowercase();
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let start = i;
        while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b'.') {
            i += 1;
        }
        if i > start {
            let number = &text[start..i];
            let rest = text[i..].trim_start();
            let unit = ["second", "minute", "hour", "day", "week", "month", "year"]
                .into_iter()
                .find(|unit| rest.starts_with(unit));
            if let Some(unit) = unit {
                if let Ok(value) = number.parse::<f64>() {
                    return match unit {
                        "second" => value / 60.0,
                        "minute" => value,
                        "hour" => value * 60.0,
                        "day" => value * 60.0 * 24.0,
                        "week" => value * 60.0 * 24.0 * 7.0,
                        "month" => value * 60.0 * 24.0 * 30.0,
                        _ => value * 60.0 * 24.0 * 365.0,
                    };
                }
            }
        }
        i += 1;
    }
    UNKNOWN
}

/// Lowercase words of a search query, long enough to mean something and not a
/// stop word — the same list as the JavaScript.
pub fn tokenize_query(query: &str) -> Vec<String> {
    query
        .to_lowercase()
        .split(|c: char| !c.is_ascii_alphanumeric())
        .filter(|token| token.len() >= 3 && !STOP_WORDS.contains(token))
        .map(str::to_string)
        .collect()
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
                .get("video")?
                .get("durationSec")
                .and_then(Value::as_f64)
                .filter(|d| *d > 0.0)
                .map(|seconds| seconds * 1000.0)
        })?;
    Some((position / duration).clamp(0.0, 1.0))
}

/// Video ids whose history row says the viewer finished them.
pub fn completed_ids(history: &[Value]) -> HashSet<String> {
    history
        .iter()
        .filter(|entry| watched_fraction(entry).unwrap_or(0.0) >= COMPLETED_FRACTION)
        .filter_map(|entry| {
            entry
                .get("video")?
                .get("videoId")?
                .as_str()
                .map(str::to_string)
        })
        .collect()
}

fn string_set(library: &Value, key: &str) -> HashSet<String> {
    library
        .get(key)
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|v| v.as_str().map(str::to_string))
        .collect()
}

fn array_of(value: Option<&Value>) -> Vec<Value> {
    value.and_then(Value::as_array).cloned().unwrap_or_default()
}

/// Rebuilds a [`VideoItem`] from the library JSON the renderer round-trips, so
/// watch-later and liked rows can seed a Home mix without another request.
pub fn video_from_json(video: &Value) -> Option<VideoItem> {
    let video_id = video.get("videoId")?.as_str()?.to_string();
    if video_id.is_empty() {
        return None;
    }
    Some(VideoItem {
        video_id,
        title: video
            .get("title")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        channel_name: video
            .get("channelName")
            .and_then(Value::as_str)
            .map(str::to_string),
        channel_id: video
            .get("channelId")
            .and_then(Value::as_str)
            .map(str::to_string),
        channel_avatar: video
            .get("channelAvatar")
            .and_then(Value::as_str)
            .map(str::to_string),
        channel_verified: None,
        thumbnail: video.get("thumbnail").and_then(Value::as_str).map(str::to_string),
        duration_sec: video.get("durationSec").and_then(Value::as_i64).unwrap_or(0),
        views_text: video.get("viewsText").and_then(Value::as_str).map(str::to_string),
        published_text: video
            .get("publishedText")
            .and_then(Value::as_str)
            .map(str::to_string),
        is_live: video.get("isLive").and_then(Value::as_bool).unwrap_or(false),
        members_only: video
            .get("membersOnly")
            .and_then(Value::as_bool)
            .unwrap_or(false),
    })
}

/// Everything the ranker knows about this device, as sets and weights.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Signals {
    pub affinity: HashMap<String, f64>,
    pub sub_ids: HashSet<String>,
    pub liked_channel_ids: HashSet<String>,
    pub watched_ids: HashSet<String>,
    pub seed_ids: HashSet<String>,
    pub search_terms: HashSet<String>,
    pub completed_ids: HashSet<String>,
    pub interested_channel_ids: HashSet<String>,
    pub hidden_ids: HashSet<String>,
    pub blocked_channel_ids: HashSet<String>,
}

/// The signals behind YouTube's recommender, read off the local library:
/// recency-weighted channel affinity, subs, likes, hidden and blocked channels.
pub fn signals_from(lib: &Value) -> Signals {
    let library = lib.get("library").cloned().unwrap_or(Value::Null);
    let mut history = array_of(library.get("history"));
    history.sort_by(|a, b| {
        b.get("ts")
            .and_then(Value::as_f64)
            .unwrap_or(0.0)
            .partial_cmp(&a.get("ts").and_then(Value::as_f64).unwrap_or(0.0))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let liked = array_of(library.get("liked"));
    let subs = array_of(lib.get("subscriptions"));

    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0);

    let mut affinity: HashMap<String, f64> = HashMap::new();
    for entry in &history {
        if let Some(channel) = entry
            .get("video")
            .and_then(|v| v.get("channelId"))
            .and_then(Value::as_str)
        {
            let ts = entry.get("ts").and_then(Value::as_f64).unwrap_or(0.0);
            let days_old = ((now_ms - ts) / 86_400_000.0).max(0.0);
            *affinity.entry(channel.to_string()).or_insert(0.0) += 1.0 / (1.0 + days_old * 0.7);
        }
    }
    for video in &liked {
        if let Some(channel) = video.get("channelId").and_then(Value::as_str) {
            *affinity.entry(channel.to_string()).or_insert(0.0) += 0.8;
        }
    }

    Signals {
        affinity,
        sub_ids: channel_ids(&subs),
        liked_channel_ids: liked
            .iter()
            .filter_map(|v| v.get("channelId").and_then(Value::as_str))
            .map(str::to_string)
            .collect(),
        watched_ids: history
            .iter()
            .filter_map(|e| {
                e.get("video")
                    .and_then(|v| v.get("videoId"))
                    .and_then(Value::as_str)
            })
            .map(str::to_string)
            .collect(),
        seed_ids: HashSet::new(),
        search_terms: HashSet::new(),
        completed_ids: completed_ids(&history),
        interested_channel_ids: string_set(&library, "interestedChannelIds"),
        hidden_ids: string_set(&library, "notInterestedIds"),
        blocked_channel_ids: string_set(&library, "blockedChannelIds"),
    }
}

fn channel_ids(subs: &[Value]) -> HashSet<String> {
    subs.iter()
        .filter_map(|s| s.get("channelId").and_then(Value::as_str))
        .filter(|id| !id.is_empty())
        .map(str::to_string)
        .collect()
}

/// Channels worth fetching uploads from: subscriptions first, then the most
/// watched, then liked and explicitly interested channels — never a blocked one.
pub fn candidate_channels(lib: &Value, signals: &Signals) -> Vec<String> {
    let mut ids: Vec<String> = Vec::new();
    let mut push = |id: &str| {
        if !id.is_empty() && !ids.iter().any(|existing| existing == id) {
            ids.push(id.to_string());
        }
    };
    for sub in array_of(lib.get("subscriptions"))
        .iter()
        .filter_map(|s| s.get("channelId").and_then(Value::as_str))
    {
        push(sub);
    }
    let mut watched: Vec<(&String, &f64)> = signals.affinity.iter().collect();
    watched.sort_by(|a, b| {
        b.1.partial_cmp(a.1)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.0.cmp(b.0))
    });
    for (id, _) in watched.into_iter().take(10) {
        push(id);
    }
    for id in signals
        .liked_channel_ids
        .iter()
        .chain(signals.interested_channel_ids.iter())
    {
        push(id);
    }
    let blocked = &signals.blocked_channel_ids;
    ids.retain(|id| !blocked.contains(id));
    ids.truncate(MAX_CHANNELS);
    ids
}

/// mulberry32 — the same seeded RNG shape as the JavaScript, so a rotation is
/// reproducible while still differing between refreshes.
struct Rng(u32);

impl Rng {
    fn new(seed: u32) -> Self {
        Self(seed)
    }

    fn next_f64(&mut self) -> f64 {
        self.0 = self.0.wrapping_add(0x6D2B_79F5);
        let mut t = self.0;
        t = t.wrapping_mul(t ^ (t >> 15)).wrapping_mul(1 | t);
        t ^= t.wrapping_add(t.wrapping_mul(t ^ (t >> 7)).wrapping_mul(61 | t));
        (t ^ (t >> 14)) as f64 / 4_294_967_296.0
    }
}

fn rotation_seed(rotation: u64) -> u32 {
    ((rotation as i32).wrapping_mul(7919)).wrapping_add(104_729) as u32
}

/// Score every candidate, then draw `limit` videos with probability
/// proportional to their score. `rotation` reseeds the draw, so the same pool
/// and the same rotation always produce the same page.
pub fn rank_and_sample(
    pool: &[VideoItem],
    signals: &Signals,
    rotation: u64,
    limit: usize,
    max_per_channel: usize,
) -> Vec<VideoItem> {
    let candidates: Vec<&VideoItem> = pool
        .iter()
        .filter(|video| {
            !video.members_only
                && !signals.hidden_ids.contains(&video.video_id)
                && !video
                    .channel_id
                    .as_deref()
                    .map(|channel| signals.blocked_channel_ids.contains(channel))
                    .unwrap_or(false)
        })
        .collect();
    if candidates.is_empty() {
        return Vec::new();
    }
    let mut rng = Rng::new(rotation_seed(rotation));

    let mut seen: HashSet<String> = HashSet::new();
    let mut scored: Vec<(VideoItem, f64)> = Vec::new();
    for video in candidates {
        if !seen.insert(video.video_id.clone()) {
            continue;
        }
        let age = publish_age_minutes(video.published_text.as_deref());
        let mut value = 60_000.0 / (age + 480.0);
        let aff = video
            .channel_id
            .as_deref()
            .and_then(|id| signals.affinity.get(id))
            .copied()
            .unwrap_or(0.0);
        if aff > 0.0 {
            value *= 1.0 + 1.6 * (aff / (aff + 2.5)).min(1.0);
        }
        let channel = video.channel_id.as_deref().unwrap_or_default();
        if !channel.is_empty() && signals.sub_ids.contains(channel) {
            value *= 2.2;
        }
        if !channel.is_empty() && signals.liked_channel_ids.contains(channel) {
            value *= 1.15;
        }
        if !channel.is_empty() && signals.interested_channel_ids.contains(channel) {
            value *= 2.0;
        }
        if signals.seed_ids.contains(&video.video_id) {
            value *= 2.3;
        }
        if signals.watched_ids.contains(&video.video_id) {
            value *= 0.12;
        }
        if signals.completed_ids.contains(&video.video_id) {
            value *= 0.22;
        }
        if !signals.search_terms.is_empty() {
            let title = video.title.to_lowercase();
            let matches = signals
                .search_terms
                .iter()
                .filter(|term| title.contains(term.as_str()))
                .count();
            if matches > 0 {
                value *= 1.0 + (0.45 * matches as f64).min(1.2);
            }
            let channel_name = video.channel_name.as_deref().unwrap_or("").to_lowercase();
            if !channel_name.is_empty()
                && signals
                    .search_terms
                    .iter()
                    .any(|term| channel_name.contains(term.as_str()))
            {
                value *= 1.25;
            }
        }
        // Per-refresh perturbation: reshuffles the page without letting noise
        // override the signals above.
        value *= 0.72 + rng.next_f64() * 0.56;
        scored.push((video.clone(), value.max(1e-6)));
    }

    let mut out: Vec<VideoItem> = Vec::new();
    let mut overflow: Vec<VideoItem> = Vec::new();
    let mut per_channel: HashMap<String, usize> = HashMap::new();
    while !scored.is_empty() && out.len() < limit {
        let total: f64 = scored.iter().map(|(_, weight)| *weight).sum();
        if total <= 0.0 {
            break;
        }
        let mut target = rng.next_f64() * total;
        let mut pick = scored.len() - 1;
        for (index, (_, weight)) in scored.iter().enumerate() {
            target -= *weight;
            if target <= 0.0 {
                pick = index;
                break;
            }
        }
        let (video, _) = scored.remove(pick);
        let key = video
            .channel_id
            .clone()
            .filter(|id| !id.is_empty())
            .unwrap_or_else(|| video.video_id.clone());
        let count = per_channel.entry(key).or_insert(0);
        if *count >= max_per_channel {
            overflow.push(video);
            continue;
        }
        *count += 1;
        out.push(video);
    }
    // Diversity first, then fill from what the cap pushed out, so a small
    // candidate pool still fills the page.
    for video in overflow {
        if out.len() >= limit {
            break;
        }
        out.push(video);
    }
    out
}

/// Instant placeholder mix from what is already on the device, used when the
/// network-backed pool comes back empty (offline, or nothing to gather from).
pub fn seed_recommendations(lib: &Value, rotation: u64, limit: usize) -> Vec<VideoItem> {
    let library = lib.get("library").cloned().unwrap_or(Value::Null);
    let mut history = array_of(library.get("history"));
    history.sort_by(|a, b| {
        b.get("ts")
            .and_then(Value::as_f64)
            .unwrap_or(0.0)
            .partial_cmp(&a.get("ts").and_then(Value::as_f64).unwrap_or(0.0))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut saved: Vec<VideoItem> = array_of(library.get("liked"))
        .iter()
        .chain(array_of(library.get("watchLater")).iter())
        .filter_map(video_from_json)
        .collect();
    if saved.is_empty() {
        saved = history
            .iter()
            .filter_map(|entry| entry.get("video"))
            .filter_map(video_from_json)
            .collect();
    }
    saved.retain(|video| !video.members_only);
    if saved.is_empty() {
        return Vec::new();
    }
    rank_and_sample(&saved, &signals_from(lib), rotation, limit, MAX_PER_CHANNEL)
}

/// Recent searches, newest first, without repeats — the queries worth running
/// again to refresh the pool.
pub fn recent_queries(lib: &Value, limit: usize) -> Vec<String> {
    let library = lib.get("library").cloned().unwrap_or(Value::Null);
    let mut searches = array_of(library.get("searches"));
    searches.sort_by(|a, b| {
        b.get("ts")
            .and_then(Value::as_f64)
            .unwrap_or(0.0)
            .partial_cmp(&a.get("ts").and_then(Value::as_f64).unwrap_or(0.0))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut out: Vec<String> = Vec::new();
    for search in &searches {
        let query = search
            .get("query")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .to_string();
        if query.is_empty() || out.iter().any(|existing: &String| existing == &query) {
            continue;
        }
        out.push(query);
        if out.len() >= limit {
            break;
        }
    }
    out
}

/// Search terms for the ranking, taken from the eight most recent queries.
pub fn search_terms(lib: &Value) -> HashSet<String> {
    let library = lib.get("library").cloned().unwrap_or(Value::Null);
    array_of(library.get("searches"))
        .iter()
        .take(8)
        .flat_map(|search| {
            tokenize_query(search.get("query").and_then(Value::as_str).unwrap_or(""))
        })
        .collect()
}

/// The most recent watches, by id, newest first — their related rails seed the
/// mix.
pub fn recent_watch_ids(lib: &Value, limit: usize) -> Vec<String> {
    let library = lib.get("library").cloned().unwrap_or(Value::Null);
    let mut history = array_of(library.get("history"));
    history.sort_by(|a, b| {
        b.get("ts")
            .and_then(Value::as_f64)
            .unwrap_or(0.0)
            .partial_cmp(&a.get("ts").and_then(Value::as_f64).unwrap_or(0.0))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut out: Vec<String> = Vec::new();
    for entry in &history {
        let id = match entry
            .get("video")
            .and_then(|v| v.get("videoId"))
            .and_then(Value::as_str)
        {
            Some(id) => id.to_string(),
            None => continue,
        };
        if id.is_empty() || out.iter().any(|existing| existing == &id) {
            continue;
        }
        out.push(id);
        if out.len() >= limit {
            break;
        }
    }
    out
}

#[derive(Clone)]
struct Snapshot {
    at: Instant,
    /// False for a mix restored from disk: it is on screen already, but a fresh
    /// one is being gathered behind it. (The age cannot be encoded in `at`
    /// because a machine booted five minutes ago has no instant twelve hours
    /// back to subtract from.)
    fresh: bool,
    pool: Vec<VideoItem>,
    signals: Signals,
    videos: Vec<VideoItem>,
    /// The category chips that came with this page, so an instant repaint does
    /// not lose the row above the grid.
    chips: Vec<Chip>,
    /// False when the page is YouTube's own signed-out Home answer.
    local: bool,
}

/// What is written to disk, so the next launch has a Home page before it has
/// made a single request.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SavedMix {
    pub saved_at_ms: u64,
    pub rotation: u64,
    pub videos: Vec<VideoItem>,
    pub pool: Vec<VideoItem>,
    pub signals: Signals,
    pub chips: Vec<Chip>,
    #[serde(default = "default_true")]
    pub local: bool,
}

fn default_true() -> bool {
    true
}

fn epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Read the saved mix. A missing, half-written or too-old file is simply "no
/// cache": the caller gathers the slow way, exactly as a first run does.
pub fn read_saved_mix(path: &PathBuf, now: u64) -> Option<SavedMix> {
    let text = std::fs::read_to_string(path).ok()?;
    let doc: Value = serde_json::from_str(&text).ok()?;
    let mut saved: SavedMix = serde_json::from_value(doc).ok()?;
    if saved.videos.is_empty() || now.saturating_sub(saved.saved_at_ms) > MAX_STALE_MS {
        return None;
    }
    // One page, whatever an older build happened to write: handing back the
    // whole candidate pool means rendering a thousand cards on launch.
    saved.videos.truncate(RANK_LIMIT);
    saved.pool.truncate(POOL_SAVE_CAP);
    Some(saved)
}

/// Write the mix (pool capped, so the file stays a few hundred KB at most).
pub fn write_saved_mix(path: &PathBuf, saved: &SavedMix) {
    let mut trimmed = saved.clone();
    trimmed.pool.truncate(POOL_SAVE_CAP);
    let text = match serde_json::to_string(&trimmed) {
        Ok(text) => text,
        Err(_) => return,
    };
    let tmp = path.with_extension("json.tmp");
    if std::fs::write(&tmp, text).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

/// Older than this and the mix is not worth showing at all.
const MAX_STALE_MS: u64 = 12 * 60 * 60 * 1000;
/// How much of the candidate pool travels to disk (for "load more").
const POOL_SAVE_CAP: usize = 240;

/// What to do when someone asks for the Home mix. Pulled out of `build` so the
/// stale-while-revalidate rules are testable without a network.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MixPlan {
    /// The cached mix is inside the window: hand it over, ask nothing.
    ServeFresh,
    /// A usable but older mix (from this run or the last one): show it now and
    /// re-gather behind it.
    ServeStaleAndRefresh,
    /// Nothing usable, or an explicit refresh: gather and wait for it.
    Gather,
}

pub fn plan(force: bool, has_fresh: bool, has_usable_cache: bool) -> MixPlan {
    if !force {
        if has_fresh {
            return MixPlan::ServeFresh;
        }
        if has_usable_cache {
            return MixPlan::ServeStaleAndRefresh;
        }
    }
    MixPlan::Gather
}

/// What can be painted without a request, if anything can. A page this run
/// gathered itself is not in the running: the normal path is fast for those and
/// must stay in charge of anything YouTube might have real recommendations for.
fn instant_page_for(snapshot: Option<&Snapshot>) -> Option<(Vec<VideoItem>, Vec<Chip>)> {
    let snapshot = snapshot?;
    if snapshot.videos.is_empty() || !snapshot.local || snapshot.fresh {
        return None;
    }
    Some((snapshot.videos.clone(), snapshot.chips.clone()))
}

/// One local-feed session: a cached pool plus a rotation counter, so an
/// explicit refresh genuinely redraws and paging does not.
pub struct LocalFeed {
    snapshot: Mutex<Option<Snapshot>>,
    rotation: AtomicU64,
    /// Where the mix is kept between launches.
    save_path: PathBuf,
    /// A background re-gather is already running.
    refreshing: AtomicBool,
    /// A gather in flight. `build` is called twice for one mount (React's dev
    /// StrictMode does this), and a second full gather would double every
    /// YouTube request the first one makes — so gathers are serialised and the
    /// late caller finds the mix already built, like the JavaScript's
    /// `building` promise.
    gather_lock: std::sync::Mutex<()>,
}

impl LocalFeed {
    /// Restores the previous run's mix when it is still usable.
    pub fn open(save_path: PathBuf) -> Self {
        let now = epoch_ms();
        let saved = read_saved_mix(&save_path, now);
        // Paging continues where the last run left off.
        let rotation = saved.as_ref().map(|saved| saved.rotation).unwrap_or(0);
        let snapshot = saved.map(|saved| Snapshot {
            at: Instant::now(),
            fresh: now.saturating_sub(saved.saved_at_ms) <= CACHE_TTL.as_millis() as u64,
            pool: saved.pool,
            signals: saved.signals,
            videos: saved.videos,
            chips: saved.chips,
            local: saved.local,
        });
        Self {
            snapshot: Mutex::new(snapshot),
            rotation: AtomicU64::new(rotation),
            save_path,
            refreshing: AtomicBool::new(false),
            gather_lock: std::sync::Mutex::new(()),
        }
    }

    fn rotation(&self) -> u64 {
        self.rotation.fetch_add(1, Ordering::SeqCst) + 1
    }

    fn snapshot(&self) -> std::sync::MutexGuard<'_, Option<Snapshot>> {
        self.snapshot
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// The network-backed gather: channel uploads, recent searches and the
    /// related rails of the last few watches, all in parallel. Each worker owns
    /// its own client because `InnerTube` keeps a request context and is not
    /// shared across threads.
    fn gather(
        &self,
        lib: &Value,
        force: bool,
        new_tube: &TubeFactory,
    ) -> (Vec<VideoItem>, Signals) {
        let mut signals = signals_from(lib);
        signals.search_terms = search_terms(lib);
        let queries = recent_queries(lib, 2);
        let channels = candidate_channels(lib, &signals);
        let watch_ids = recent_watch_ids(lib, 3);

        let mut uploads: Vec<VideoItem> = Vec::new();
        let mut related: Vec<VideoItem> = Vec::new();
        let mut searched: Vec<VideoItem> = Vec::new();
        std::thread::scope(|scope| {
            // Channel uploads, worked off a shared queue like the eight
            // JavaScript workers did.
            let queue = Arc::new(Mutex::new(channels.clone()));
            let mut upload_handles = Vec::new();
            for _ in 0..CHANNEL_WORKERS.min(channels.len()) {
                let queue = Arc::clone(&queue);
                upload_handles.push(scope.spawn(move || {
                    let mut tube = new_tube();
                    let mut mine = Vec::new();
                    loop {
                        let next = queue
                            .lock()
                            .unwrap_or_else(|poisoned| poisoned.into_inner())
                            .pop();
                        let Some(channel) = next else { break };
                        // The Videos tab, like the JavaScript's default: with no
                        // params YouTube answers the channel's Featured page.
                        if let Ok(page) =
                            tube.channel_videos(&channel, Some(CHANNEL_TAB_VIDEOS), force)
                        {
                            mine.extend(page.videos.into_iter().take(20));
                        }
                    }
                    mine
                }));
            }

            // Related rails of the most recent watches.
            let related_handles: Vec<_> = watch_ids
                .into_iter()
                .map(|video_id| {
                    scope.spawn(move || {
                        let tube = new_tube();
                        match tube.watch(&video_id) {
                            Ok(data) => data.related,
                            Err(_) => Vec::new(),
                        }
                    })
                })
                .collect();

            // The last couple of searches.
            let search_handles: Vec<_> = queries
                .into_iter()
                .map(|query| {
                    scope.spawn(move || {
                        let tube = new_tube();
                        match tube.raw_search(&query, None) {
                            Ok(results) => results
                                .entries
                                .into_iter()
                                .filter_map(|entry| match entry {
                                    SearchEntry::Video { video } => Some(video),
                                    _ => None,
                                })
                                .take(12)
                                .collect(),
                            Err(_) => Vec::new(),
                        }
                    })
                })
                .collect();

            for handle in upload_handles {
                if let Ok(videos) = handle.join() {
                    uploads.extend(videos);
                }
            }
            for handle in related_handles {
                if let Ok(videos) = handle.join() {
                    related.extend(videos);
                }
            }
            for handle in search_handles {
                if let Ok(videos) = handle.join() {
                    searched.extend(videos);
                }
            }
        });

        // Related rails both join the pool and weight it: they are the closest
        // thing to "more like what I just watched".
        signals.seed_ids = related
            .iter()
            .map(|video| video.video_id.clone())
            .collect();

        let uploads = dedupe_videos(uploads);
        let searched = dedupe_videos(searched);
        let related = dedupe_videos(related);
        let mut pool = uploads;
        for video in searched.iter().chain(related.iter()) {
            if !pool.iter().any(|v| v.video_id == video.video_id) {
                pool.push(video.clone());
            }
        }
        stamp_channels(&mut pool, lib, &searched);
        (pool, signals)
    }

    /// The cached pool minus the videos the renderer already shows.
    fn pool_minus(&self, excluded: &HashSet<String>) -> Option<(Vec<VideoItem>, Signals)> {
        let snapshot = self.snapshot();
        let snapshot = snapshot.as_ref()?;
        let pool = snapshot
            .pool
            .iter()
            .filter(|video| !excluded.contains(&video.video_id))
            .cloned()
            .collect();
        Some((pool, snapshot.signals.clone()))
    }

    /// The cached mix, when it is still inside the window.
    fn fresh_videos(&self) -> Option<Vec<VideoItem>> {
        let snapshot = self.snapshot();
        let snapshot = snapshot.as_ref()?;
        (snapshot.fresh && snapshot.at.elapsed() <= CACHE_TTL).then(|| snapshot.videos.clone())
    }

    /// The cached mix even when it is out of date — good enough to put on
    /// screen while a fresh one is gathered behind it.
    fn any_videos(&self) -> Option<Vec<VideoItem>> {
        let snapshot = self.snapshot();
        let snapshot = snapshot.as_ref()?;
        Some(snapshot.videos.clone())
    }

    /// How up to date the restored mix is, for the tests and for logging.
    pub fn cached_state(&self) -> &'static str {
        let snapshot = self.snapshot();
        match snapshot.as_ref() {
            None => "empty",
            Some(snapshot) if snapshot.fresh && snapshot.at.elapsed() <= CACHE_TTL => "fresh",
            Some(_) => "stale",
        }
    }

    /// Re-gather without making anyone wait for it. One at a time: a viewer
    /// tabbing back and forth over Home must not start five gathers.
    fn refresh_in_background(self: &Arc<Self>, lib: &Value, new_tube: &TubeFactory) {
        if self.refreshing.swap(true, Ordering::SeqCst) {
            return;
        }
        let feed = Arc::clone(self);
        let lib = lib.clone();
        let new_tube = Arc::clone(new_tube);
        std::thread::spawn(move || {
            feed.gather_and_store(&lib, false, &new_tube);
            feed.refreshing.store(false, Ordering::SeqCst);
        });
    }

    /// Remember the chips that came with this page, so an instant repaint of a
    /// locally built mix still has the category row above it.
    pub fn remember_chips(&self, chips: Vec<Chip>) {
        let saved = {
            let mut snapshot = self.snapshot();
            match snapshot.as_mut() {
                None => return,
                Some(snapshot) => {
                    snapshot.chips = chips.clone();
                    SavedMix {
                        saved_at_ms: epoch_ms(),
                        rotation: self.rotation.load(Ordering::SeqCst),
                        videos: snapshot.videos.clone(),
                        pool: snapshot.pool.clone(),
                        signals: snapshot.signals.clone(),
                        chips,
                        local: snapshot.local,
                    }
                }
            }
        };
        let path = self.save_path.clone();
        std::thread::spawn(move || write_saved_mix(&path, &saved));
    }

    /// YouTube answered a real Home feed. Stop painting from the local mix until
    /// the next gather: the viewer should see YouTube's own recommendations, and
    /// ask for them every time, whenever they exist.
    pub fn mark_remote(&self) {
        let mut snapshot = self.snapshot();
        if let Some(snapshot) = snapshot.as_mut() {
            snapshot.local = false;
        }
    }

    /// The Home page to show *now*, without asking YouTube: the mix that came
    /// off disk at launch, with its chips. It stops being instant as soon as
    /// this run has gathered a fresh page (or YouTube turned out to have its own
    /// recommendations), so a stale mix never hides a real Home feed for long.
    pub fn instant_page(self: &Arc<Self>, lib: &Value, new_tube: &TubeFactory) -> Option<Value> {
        let page = instant_page_for(self.snapshot().as_ref())?;
        self.refresh_in_background(lib, new_tube);
        Some(json!({
            "feed": {
                "chips": page.1,
                "videos": page.0,
                "continuation": Value::Null,
                "local": true,
            }
        }))
    }

    /// One gather at a time. `build` is called twice for a single mount in
    /// development (React's StrictMode), and a second gather would double every
    /// request to YouTube — so the late caller waits its turn, finds the mix
    /// already built and does no network work at all. That is the JavaScript
    /// `building` promise, in lock form.
    fn gather_slot(&self) -> std::sync::MutexGuard<'_, ()> {
        self.gather_lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn gather_and_store(&self, lib: &Value, force: bool, new_tube: &TubeFactory) -> Vec<VideoItem> {
        let (pool, signals) = self.gather(lib, force, new_tube);
        let rotation = self.rotation();
        let candidates = if pool.is_empty() {
            seed_recommendations(lib, rotation, RANK_LIMIT)
        } else {
            pool
        };
        let videos = rank_and_sample(&candidates, &signals, rotation, RANK_LIMIT, MAX_PER_CHANNEL);
        if videos.is_empty() {
            // Offline, or nothing to go on: the previous mix beats an empty
            // Home page, so keep it (and keep it marked stale, so the next
            // visit tries again).
            return self.any_videos().unwrap_or_default();
        }
        {
            let mut snapshot = self.snapshot();
            // Keep the chips that came with the page we are replacing: they do
            // not change between gathers, and an instant repaint needs them.
            let chips = snapshot
                .as_ref()
                .map(|previous| previous.chips.clone())
                .unwrap_or_default();
            *snapshot = Some(Snapshot {
                at: Instant::now(),
                fresh: true,
                pool: candidates.clone(),
                signals: signals.clone(),
                videos: videos.clone(),
                chips: chips.clone(),
                local: true,
            });
            let saved = SavedMix {
                saved_at_ms: epoch_ms(),
                rotation,
                videos: videos.clone(),
                pool: candidates,
                signals,
                chips,
                local: true,
            };
            let path = self.save_path.clone();
            std::thread::spawn(move || write_saved_mix(&path, &saved));
        }
        videos
    }

    /// Build (or reuse) the Home mix.
    ///
    /// * a mix younger than the cache window is returned straight away;
    /// * an older one (from this run or the last one) is shown immediately and
    ///   re-gathered in the background;
    /// * with nothing cached at all, or on an explicit refresh, the caller waits
    ///   so the page they see is the new one.
    pub fn build(self: &Arc<Self>, lib: &Value, force: bool, new_tube: &TubeFactory) -> Vec<VideoItem> {
        match plan(force, self.fresh_videos().is_some(), self.any_videos().is_some()) {
            MixPlan::ServeFresh => return self.fresh_videos().unwrap_or_default(),
            MixPlan::ServeStaleAndRefresh => {
                let stale = self.any_videos().unwrap_or_default();
                self.refresh_in_background(lib, new_tube);
                return stale;
            }
            MixPlan::Gather => {}
        }
        // One gather at a time; whoever arrives second finds the mix ready and
        // does no network work at all.
        let _slot = self.gather_slot();
        if let Some(videos) = self.fresh_videos() {
            // Somebody refreshed while we queued; that answer is the new mix.
            return videos;
        }
        self.gather_and_store(lib, force, new_tube)
    }

    /// The local analogue of a continuation page: the same pool minus what is
    /// already on screen, drawn with a fresh rotation. When the pool runs dry,
    /// one fresh gather keeps "more" producing videos.
    pub fn more(
        self: &Arc<Self>,
        lib: &Value,
        exclude: &[String],
        limit: usize,
        new_tube: &TubeFactory,
    ) -> Vec<VideoItem> {
        let limit = limit.clamp(1, PAGE_SIZE);
        let excluded: HashSet<String> = exclude.iter().cloned().collect();
        let cached = |feed: &Arc<Self>| match feed.pool_minus(&excluded) {
            Some((pool, signals)) if !pool.is_empty() => Some((pool, signals)),
            _ => None,
        };
        // Any pool at all is good enough to page from; freshness is handled by
        // `build`, which keeps the disk mix warm.
        let mut current = cached(self);
        if current.is_none() {
            self.build(lib, true, new_tube);
            current = cached(self);
        }
        let (mut pool, mut signals) = match current {
            Some(pair) => pair,
            None => return Vec::new(),
        };
        if pool.len() < REBUILD_THRESHOLD.min(limit) {
            self.build(lib, true, new_tube);
            if let Some((rebuilt, rebuilt_signals)) = cached(self) {
                pool = rebuilt;
                signals = rebuilt_signals;
            }
        }
        if pool.is_empty() {
            return Vec::new();
        }
        let rotation = self.rotation();
        rank_and_sample(&pool, &signals, rotation, limit, MAX_PER_CHANNEL)
    }
}

fn dedupe_videos(videos: Vec<VideoItem>) -> Vec<VideoItem> {
    let mut seen: HashSet<String> = HashSet::new();
    videos
        .into_iter()
        .filter(|video| !video.video_id.is_empty() && seen.insert(video.video_id.clone()))
        .filter(|video| !video.members_only)
        .collect()
}

/// Fill in the channel name and avatar from the library, so a card from a
/// channel page or a search result still shows who uploaded it.
fn stamp_channels(pool: &mut [VideoItem], lib: &Value, extra: &[VideoItem]) {
    let library = lib.get("library").cloned().unwrap_or(Value::Null);
    let mut known: HashMap<String, (Option<String>, Option<String>)> = HashMap::new();
    for sub in array_of(lib.get("subscriptions")) {
        if let Some(id) = sub.get("channelId").and_then(Value::as_str) {
            known.insert(
                id.to_string(),
                (
                    sub.get("name").and_then(Value::as_str).map(str::to_string),
                    sub.get("avatar").and_then(Value::as_str).map(str::to_string),
                ),
            );
        }
    }
    let mut record = |video: &VideoItem| {
        if let Some(id) = video.channel_id.as_deref() {
            if id.is_empty() {
                return;
            }
            let entry = known.entry(id.to_string()).or_insert((None, None));
            if entry.0.is_none() {
                entry.0 = video.channel_name.clone();
            }
            if entry.1.is_none() {
                entry.1 = video.channel_avatar.clone();
            }
        }
    };
    for video in array_of(library.get("liked")).iter().filter_map(video_from_json) {
        record(&video);
    }
    for entry in array_of(library.get("history")) {
        if let Some(video) = entry.get("video").and_then(video_from_json) {
            record(&video);
        }
    }
    for video in extra {
        record(video);
    }
    for video in pool.iter() {
        record(video);
    }
    for video in pool.iter_mut() {
        let entry = video
            .channel_id
            .as_deref()
            .and_then(|id| known.get(id).cloned());
        let Some((name, avatar)) = entry else { continue };
        if video.channel_name.as_deref().unwrap_or("").is_empty() {
            video.channel_name = name;
        }
        if video.channel_avatar.as_deref().unwrap_or("").is_empty() {
            video.channel_avatar = avatar;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn video(id: &str, channel: &str, extra: Option<&Value>) -> VideoItem {
        let mut item = VideoItem {
            video_id: id.to_string(),
            title: format!("Video {id}"),
            channel_name: Some(format!("Chan {channel}")),
            channel_id: Some(channel.to_string()),
            channel_avatar: None,
            channel_verified: None,
            thumbnail: None,
            duration_sec: 300,
            views_text: Some("1K views".to_string()),
            published_text: Some("1 day ago".to_string()),
            is_live: false,
            members_only: false,
        };
        if let Some(extra) = extra {
            if extra.get("membersOnly").and_then(Value::as_bool) == Some(true) {
                item.members_only = true;
            }
            if let Some(title) = extra.get("title").and_then(Value::as_str) {
                item.title = title.to_string();
            }
        }
        item
    }

    fn ids(videos: &[VideoItem]) -> Vec<String> {
        videos.iter().map(|v| v.video_id.clone()).collect()
    }

    #[test]
    fn tokenizes_queries_like_the_javascript() {
        assert_eq!(
            tokenize_query("How to build the Best Kotlin app"),
            vec!["build", "kotlin", "app"]
        );
        assert!(tokenize_query("   ").is_empty());
    }

    #[test]
    fn reads_publish_ages_like_the_renderer() {
        assert_eq!(publish_age_minutes(Some("2 days ago")), 2880.0);
        assert_eq!(publish_age_minutes(Some("3 hours ago")), 180.0);
        assert_eq!(publish_age_minutes(Some("30 seconds ago")), 0.5);
        assert!(publish_age_minutes(Some("nonsense")) > 1_000_000.0);
        assert!(publish_age_minutes(None) > 1_000_000.0);
    }

    #[test]
    fn finds_completed_history_entries_the_way_android_does() {
        let history = vec![
            json_entry("done", 299_000, 300_000),
            json_entry("half", 100_000, 300_000),
            json_entry("empty", 0, 300_000),
        ];
        assert_eq!(completed_ids(&history), HashSet::from(["done".to_string()]));
    }

    fn json_entry(id: &str, position_ms: i64, duration_ms: i64) -> Value {
        serde_json::json!({
            "video": { "videoId": id, "title": id, "channelId": "UCa", "durationSec": 300 },
            "ts": 1,
            "positionMs": position_ms,
            "durationMs": duration_ms,
        })
    }

    #[test]
    fn filters_members_only_hidden_videos_and_blocked_channels() {
        let pool = vec![
            video("keep", "UCa", None),
            video("member", "UCb", Some(&serde_json::json!({ "membersOnly": true }))),
            video("hidden", "UCc", None),
            video("blocked", "UCblocked", None),
        ];
        let signals = Signals {
            hidden_ids: HashSet::from(["hidden".to_string()]),
            blocked_channel_ids: HashSet::from(["UCblocked".to_string()]),
            ..Default::default()
        };
        assert_eq!(
            ids(&rank_and_sample(&pool, &signals, 1, 10, MAX_PER_CHANNEL)),
            vec!["keep"]
        );
    }

    #[test]
    fn caps_one_channel_while_diversity_is_available() {
        let mut pool: Vec<VideoItem> = Vec::new();
        for i in 0..10 {
            pool.push(video(&format!("a{i}"), "UCa", None));
        }
        for i in 0..10 {
            pool.push(video(&format!("b{i}"), "UCb", None));
        }
        let out = rank_and_sample(&pool, &Signals::default(), 3, 6, 3);
        assert_eq!(out.len(), 6);
        let per = |channel: &str| {
            out.iter()
                .filter(|v| v.channel_id.as_deref() == Some(channel))
                .count()
        };
        assert_eq!(per("UCa"), 3);
        assert_eq!(per("UCb"), 3);
    }

    #[test]
    fn a_rotation_is_reproducible_and_the_next_rotation_differs() {
        let pool: Vec<VideoItem> = (0..24)
            .map(|i| video(&format!("v{i}"), &format!("UC{}", i % 6), None))
            .collect();
        let first = ids(&rank_and_sample(&pool, &Signals::default(), 1, 12, MAX_PER_CHANNEL)).join(",");
        let again = ids(&rank_and_sample(&pool, &Signals::default(), 1, 12, MAX_PER_CHANNEL)).join(",");
        let next = ids(&rank_and_sample(&pool, &Signals::default(), 2, 12, MAX_PER_CHANNEL)).join(",");
        assert_eq!(again, first);
        assert_ne!(next, first);
    }

    #[test]
    fn an_all_filtered_pool_returns_nothing() {
        let pool = vec![video("m", "UCa", Some(&serde_json::json!({ "membersOnly": true })))];
        assert!(rank_and_sample(&pool, &Signals::default(), 1, 10, MAX_PER_CHANNEL).is_empty());
    }

    #[test]
    fn subscriptions_and_affinity_lift_a_channel() {
        let signals = Signals {
            sub_ids: HashSet::from(["UCsub".to_string()]),
            ..Default::default()
        };
        // A subscription multiplies the weight, so with the same age the
        // subscribed channel dominates a 200-draw sample.
        let pool = vec![video("sub", "UCsub", None), video("other", "UCother", None)];
        let mut sub_hits = 0;
        for rotation in 1..200 {
            let out = rank_and_sample(&pool, &signals, rotation, 1, MAX_PER_CHANNEL);
            if out.first().map(|v| v.video_id.as_str()) == Some("sub") {
                sub_hits += 1;
            }
        }
        assert!(sub_hits > 120, "subscribed channel won {sub_hits}/199 draws");
    }

    #[test]
    fn candidate_channels_prefers_subs_and_skips_blocked() {
        let lib = serde_json::json!({
            "library": {
                "history": [json_entry("h1", 1000, 60_000), json_entry("h2", 1000, 60_000)],
                "liked": [{ "videoId": "l1", "channelId": "UCliked" }],
                "interestedChannelIds": ["UCpicked"],
                "blockedChannelIds": ["UCblocked"],
                "notInterestedIds": [],
                "searches": [],
            },
            "subscriptions": [{ "channelId": "UCsub", "name": "Sub" }],
        });
        let mut signals = signals_from(&lib);
        signals.interested_channel_ids = HashSet::from(["UCpicked".to_string()]);
        let channels = candidate_channels(&lib, &signals);
        assert_eq!(channels[0], "UCsub");
        assert!(!channels.contains(&"UCblocked".to_string()));
        assert!(channels.contains(&"UCpicked".to_string()));
    }

    #[test]
    fn a_blocked_channel_is_never_a_candidate() {
        let lib = serde_json::json!({
            "library": {
                "history": [{ "video": { "videoId": "h", "channelId": "UCblocked", "durationSec": 60 }, "ts": 1, "positionMs": 0, "durationMs": 0 }],
                "blockedChannelIds": ["UCblocked"],
            },
            "subscriptions": [],
        });
        let signals = signals_from(&lib);
        assert!(candidate_channels(&lib, &signals).is_empty());
    }

    #[test]
    fn search_history_drives_queries_and_terms() {
        let lib = serde_json::json!({
            "library": {
                "searches": [
                    { "query": "rust async tutorial", "ts": 5 },
                    { "query": "rust async tutorial", "ts": 4 },
                    { "query": "  ", "ts": 3 },
                    { "query": "kotlin coroutines", "ts": 2 },
                ]
            }
        });
        // Newest first, repeats and blanks dropped — the JavaScript rule.
        assert_eq!(
            recent_queries(&lib, 2),
            vec!["rust async tutorial".to_string(), "kotlin coroutines".to_string()]
        );
        let terms = search_terms(&lib);
        assert!(terms.contains("rust") && terms.contains("async") && terms.contains("kotlin"));
        assert!(!terms.contains("the"));
    }

    #[test]
    fn seeds_come_from_the_library_when_the_pool_is_empty() {
        let lib = serde_json::json!({
            "library": {
                "liked": [{ "videoId": "liked1", "title": "Liked", "channelId": "UCliked", "durationSec": 100, "publishedText": "1 day ago" }],
                "watchLater": [
                    { "videoId": "saved1", "title": "Saved", "channelId": "UCsaved", "durationSec": 100, "publishedText": "1 day ago" },
                    { "videoId": "saved2", "title": "Saved", "channelId": "UCsaved2", "durationSec": 100, "publishedText": "1 day ago" },
                    { "videoId": "member", "title": "Members", "channelId": "UCm", "membersOnly": true }
                ],
                "history": [],
            },
            "subscriptions": []
        });
        let seeded = seed_recommendations(&lib, 1, 30);
        let mut got = ids(&seeded);
        got.sort();
        assert_eq!(got, vec!["liked1", "saved1", "saved2"]);
    }

    #[test]
    fn history_entries_are_stamped_with_their_channel_from_the_library() {
        let lib = serde_json::json!({
            "library": { "liked": [], "history": [], "searches": [] },
            "subscriptions": [{ "channelId": "UCsub", "name": "Subby", "avatar": "a.png" }],
        });
        let mut bare = video("v1", "UCsub", None);
        bare.channel_name = None;
        bare.channel_avatar = None;
        let mut pool = vec![bare];
        stamp_channels(&mut pool, &lib, &[]);
        assert_eq!(pool[0].channel_name.as_deref(), Some("Subby"));
        assert_eq!(pool[0].channel_avatar.as_deref(), Some("a.png"));
    }

    // ---- the saved mix: why a relaunch is instant --------------------------

    #[test]
    fn the_plan_follows_the_cache_and_the_refresh_flag() {
        // Fresh cache: no request at all.
        assert_eq!(plan(false, true, true), MixPlan::ServeFresh);
        // Only an old cache (the usual second launch of the day): show it and
        // re-gather behind it.
        assert_eq!(plan(false, false, true), MixPlan::ServeStaleAndRefresh);
        // Nothing cached: the first page has to wait for the gather.
        assert_eq!(plan(false, false, false), MixPlan::Gather);
        // An explicit refresh always re-gathers, cache or not.
        assert_eq!(plan(true, true, true), MixPlan::Gather);
        assert_eq!(plan(true, false, false), MixPlan::Gather);
    }

    fn saved_mix(rotation: u64, at_ms: u64) -> SavedMix {
        SavedMix {
            saved_at_ms: at_ms,
            rotation,
            videos: vec![video("a", "UCa", None), video("b", "UCb", None)],
            pool: (0..400).map(|i| video(&format!("p{i}"), "UCp", None)).collect(),
            signals: Signals {
                affinity: HashMap::from([("UCp".to_string(), 3.5)]),
                sub_ids: HashSet::from(["UCsub".to_string()]),
                hidden_ids: HashSet::from(["hidden".to_string()]),
                ..Default::default()
            },
            chips: vec![Chip {
                title: "Music".to_string(),
                params: Some("EgXEBgA%3D".to_string()),
                token: None,
            }],
            local: true,
        }
    }

    #[test]
    fn a_saved_mix_comes_back_from_disk() {
        let path = std::env::temp_dir().join(format!(
            "indio-home-mix-{}-{}.json",
            std::process::id(),
            epoch_ms()
        ));
        let now = epoch_ms();
        write_saved_mix(&path, &saved_mix(7, now));
        let loaded = read_saved_mix(&path, now + 1_000).expect("the mix is restored");
        assert_eq!(loaded.rotation, 7);
        assert_eq!(ids(&loaded.videos), vec!["a", "b"]);
        assert_eq!(loaded.signals.affinity.get("UCp"), Some(&3.5));
        assert!(loaded.signals.sub_ids.contains("UCsub"));
        assert!(loaded.signals.hidden_ids.contains("hidden"));
        assert_eq!(loaded.chips.len(), 1, "the chips travel with the page");
        assert!(loaded.local);
        // The pool is capped so the file cannot grow without bound.
        assert_eq!(loaded.pool.len(), POOL_SAVE_CAP);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn the_saved_page_is_the_ranked_mix_not_the_whole_pool() {
        // A previous version wrote the candidate pool into `videos`, which made
        // a restored Home page render every candidate it had ever seen.
        let path = std::env::temp_dir().join(format!(
            "indio-home-mix-cap-{}-{}.json",
            std::process::id(),
            epoch_ms()
        ));
        let mut big = saved_mix(1, epoch_ms());
        big.videos = (0..600).map(|i| video(&format!("v{i}"), "UCv", None)).collect();
        big.pool = big.videos.clone();
        write_saved_mix(&path, &big);
        let loaded = read_saved_mix(&path, epoch_ms()).unwrap();
        assert_eq!(
            loaded.videos.len(),
            RANK_LIMIT,
            "a restored page is one page, however it was written"
        );
        assert_eq!(loaded.pool.len(), POOL_SAVE_CAP);
        let _ = std::fs::remove_file(&path);

        // The gather itself caps the page at RANK_LIMIT.
        let pool: Vec<VideoItem> = (0..600)
            .map(|i| video(&format!("v{i}"), &format!("UC{}", i % 40), None))
            .collect();
        let ranked = rank_and_sample(&pool, &Signals::default(), 1, RANK_LIMIT, MAX_PER_CHANNEL);
        assert_eq!(ranked.len(), RANK_LIMIT);
    }

    #[test]
    fn a_stale_broken_or_empty_mix_is_ignored() {
        let path = std::env::temp_dir().join(format!(
            "indio-home-mix-bad-{}-{}.json",
            std::process::id(),
            epoch_ms()
        ));
        let now = epoch_ms();

        // Older than the usable window (a laptop asleep for a day).
        write_saved_mix(&path, &saved_mix(1, now - MAX_STALE_MS - 1));
        assert!(read_saved_mix(&path, now).is_none());

        // An empty mix is not worth showing.
        let mut empty = saved_mix(1, now);
        empty.videos.clear();
        write_saved_mix(&path, &empty);
        assert!(read_saved_mix(&path, now).is_none());

        // Half-written or from an older build shape.
        std::fs::write(&path, "{ not json").unwrap();
        assert!(read_saved_mix(&path, now).is_none());
        std::fs::write(&path, r#"{"what":1}"#).unwrap();
        assert!(read_saved_mix(&path, now).is_none());

        // A missing file is the normal first run.
        assert!(read_saved_mix(&path.with_extension("missing"), now).is_none());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn opening_a_feed_restores_the_previous_mix_as_stale() {
        let path = std::env::temp_dir().join(format!(
            "indio-home-mix-open-{}-{}.json",
            std::process::id(),
            epoch_ms()
        ));
        // Written 30 minutes ago: outside the 10-minute window, inside the
        // usable one, which is the state that makes a relaunch paint at once.
        let age = 30 * 60 * 1000;
        write_saved_mix(&path, &saved_mix(3, epoch_ms() - age));
        let feed = LocalFeed::open(path.clone());
        assert_eq!(feed.cached_state(), "stale");
        assert_eq!(ids(&feed.any_videos().unwrap()), vec!["a", "b"]);
        assert!(feed.fresh_videos().is_none(), "it still gets re-gathered");

        // A brand-new feed has nothing to show.
        let fresh = LocalFeed::open(path.with_extension("absent"));
        assert_eq!(fresh.cached_state(), "empty");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn only_a_restored_local_page_is_painted_without_asking() {
        let restored = Snapshot {
            at: Instant::now(),
            fresh: false,
            pool: vec![video("a", "UCa", None)],
            signals: Signals::default(),
            videos: vec![video("a", "UCa", None)],
            chips: vec![Chip { title: "Music".into(), params: None, token: None }],
            local: true,
        };
        assert!(instant_page_for(Some(&restored)).is_some());

        // Gathered in this run: the normal path answers from the client cache.
        let mut just_gathered = restored.clone();
        just_gathered.fresh = true;
        assert!(instant_page_for(Some(&just_gathered)).is_none());

        // YouTube's own feed is never papered over by the local mix.
        let mut remote = restored.clone();
        remote.local = false;
        assert!(instant_page_for(Some(&remote)).is_none());

        // Nothing cached, and a cached page with no videos, are both "ask".
        assert!(instant_page_for(None).is_none());
        let mut empty = restored;
        empty.videos.clear();
        assert!(instant_page_for(Some(&empty)).is_none());
    }
}
