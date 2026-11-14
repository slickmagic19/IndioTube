//! The `window.indio` command surface, backed by indio-core.
//!
//! Every command returns the same wrapper object the Electron preload used
//! (`{ feed }`, `{ results }`, `{ page }`, …) so the renderer's calls map one to
//! one and only the bridge has to know about the transport.

use std::time::{Duration, Instant};

use indio_core::innertube::{
    build_search_params, default_config, recommendable_videos, Feed, InnerTube, SearchEntry,
    SearchFilters, CHANNEL_TAB_LIVE, CHANNEL_TAB_VIDEOS, TRENDING_PARAMS,
};
use indio_core::proxy::TokenEntry;
use indio_core::quality::QualityLadder;
use serde_json::{json, Value};
use tauri::{Manager, State};

use crate::http::UreqTransport;
use crate::ytdlp;
use crate::AppState;

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

fn with_tube<T>(
    state: &AppState,
    f: impl FnOnce(&mut InnerTube<UreqTransport>) -> Result<T, String>,
) -> Result<T, String> {
    let mut guard = state
        .tube
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let tube = guard.as_mut().ok_or("the shell is not initialised yet")?;
    f(tube)
}

#[tauri::command]
pub async fn indio_init(state: State<'_, AppState>, lang: Option<String>, country: Option<String>) -> Result<Value, String> {
    let mut guard = state
        .tube
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if guard.is_none() {
        let today = utc_ymd();
        let lang = lang.unwrap_or_else(|| "en".to_string());
        let country = country.unwrap_or_else(|| "US".to_string());
        eprintln!("[indio] init lang={lang:?} country={country:?}");
        *guard = Some(InnerTube::new(
            UreqTransport::new(),
            default_config(today),
            &lang,
            &country,
        ));
        *state
            .locale
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = (lang, country);
    }
    Ok(json!({ "ok": true }))
}

pub fn utc_ymd() -> (i32, u32, u32) {
    // Days since the Unix epoch -> civil date (Howard Hinnant's algorithm), so
    // the client version string matches the JavaScript's `2.YYYYMMDD.00.00`.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = (secs / 86_400) as i64;
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    (year as i32, m as u32, d as u32)
}

// ---- feeds ------------------------------------------------------------------

/// Home falls back to the local recommendation mix when YouTube's signed-out
/// feed is empty and no category chip is selected — the same rule as the old
/// electron/main.js. On top of that, the mix from the previous run is painted
/// immediately (`instant_page`) and re-gathered behind the page, because waiting
/// for the gather — or even for one InnerTube round trip — is what made a
/// relaunch feel slow.
#[tauri::command]
pub async fn home(
    state: State<'_, AppState>,
    library: State<'_, crate::LibraryState>,
    params: Option<String>,
    force: Option<bool>,
) -> Result<Value, String> {
    let force = force.unwrap_or(false);
    let factory = crate::tube_factory(&state);
    let lib = library.0.state();
    if params.is_none() && !force {
        if let Some(page) = state.local_feed.instant_page(&lib, &factory) {
            eprintln!("[indio] home from the saved mix");
            return Ok(page);
        }
    }
    let remote = with_tube(&state, |tube| tube.home(params.as_deref(), force).map_err(err));
    let (chips, videos, continuation) = match remote {
        Ok(feed) => (feed.chips, feed.videos, feed.continuation),
        Err(e) => {
            eprintln!("[indio] home failed: {e}");
            (Vec::new(), Vec::new(), None)
        }
    };
    eprintln!("[indio] home -> {} videos", videos.len());
    if !videos.is_empty() {
        // YouTube is recommending on its own again: stop repainting the local
        // mix over its answer, in this run and from the saved page onwards.
        state.local_feed.mark_remote();
    }
    if !videos.is_empty() || params.is_some() {
        return Ok(json!({ "feed": {
            "chips": chips,
            "videos": videos,
            "continuation": continuation,
        }}));
    }
    // A category chip is a YouTube-side filter; local recommendations cannot
    // honour it, so only the plain Home tab falls back to the local mix.
    // The gather runs on its own clients, so the shared one stays free.
    let local = state.local_feed.build(&lib, force, &factory);
    if local.is_empty() {
        return Ok(json!({ "feed": {
            "chips": chips,
            "videos": videos,
            "continuation": continuation,
        }}));
    }
    // Keep the chips with the page for the next launch's instant repaint.
    state.local_feed.remember_chips(chips.clone());
    eprintln!("[indio] home local mix -> {} videos", local.len());
    Ok(json!({ "feed": {
        "chips": chips,
        "videos": local,
        "continuation": Value::Null,
        "local": true,
    }}))
}

#[tauri::command]
pub async fn home_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let feed = with_tube(&state, |tube| tube.home_continuation(&token).map_err(err))?;
    Ok(json!({ "feed": feed }))
}

/// Infinite Home paging, drawn from the same local pool as the first page.
#[tauri::command]
pub async fn home_more(
    state: State<'_, AppState>,
    library: State<'_, crate::LibraryState>,
    exclude_ids: Option<Vec<String>>,
    limit: Option<usize>,
) -> Result<Value, String> {
    let exclude = exclude_ids.unwrap_or_default();
    let limit = limit
        .unwrap_or(crate::local_feed::PAGE_SIZE)
        .clamp(1, crate::local_feed::RANK_LIMIT);
    let lib = library.0.state();
    let factory = crate::tube_factory(&state);
    let videos = state.local_feed.more(&lib, &exclude, limit, &factory);
    Ok(json!({ "videos": videos }))
}

#[tauri::command]
pub async fn trending(state: State<'_, AppState>, force: Option<bool>) -> Result<Value, String> {
    let _ = force;
    // Signed-out FEtrending is dead, so Trending is the "popular" search tab;
    // a failure answers with an empty feed rather than an error, as before.
    let results = with_tube(&state, |tube| {
        tube.raw_search("", Some(TRENDING_PARAMS)).map_err(err)
    })
    .unwrap_or_else(|e| {
        eprintln!("[indio] trending failed: {e}");
        indio_core::innertube::SearchResults {
            entries: Vec::new(),
            continuation: None,
            restricted: false,
        }
    });
    let feed = Feed {
        chips: Vec::new(),
        videos: recommendable_videos(video_entries(results.entries)),
        continuation: results.continuation,
    };
    Ok(json!({ "feed": feed }))
}

fn video_entries(entries: Vec<SearchEntry>) -> Vec<indio_core::innertube::VideoItem> {
    entries
        .into_iter()
        .filter_map(|entry| match entry {
            SearchEntry::Video { video } => Some(video),
            _ => None,
        })
        .collect()
}

#[tauri::command]
pub async fn trending_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let results = with_tube(&state, |tube| tube.search_continuation(&token).map_err(err))?;
    let feed = Feed {
        chips: Vec::new(),
        videos: recommendable_videos(video_entries(results.entries)),
        continuation: results.continuation,
    };
    Ok(json!({ "feed": feed }))
}

#[tauri::command]
pub async fn search(
    state: State<'_, AppState>,
    query: String,
    filters: Option<Value>,
) -> Result<Value, String> {
    let filters = filters.unwrap_or(Value::Null);
    let number = |key: &str| filters.get(key).and_then(Value::as_u64).map(|n| n as u32);
    let params = build_search_params(SearchFilters {
        kind: number("type"),
        sort: number("sort"),
        upload_date: number("uploadDate"),
        duration: number("duration"),
    });
    let results = with_tube(&state, |tube| {
        tube.raw_search(&query, params.as_deref()).map_err(err)
    })?;
    Ok(json!({ "results": results }))
}

#[tauri::command]
pub async fn search_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let results = with_tube(&state, |tube| tube.search_continuation(&token).map_err(err))?;
    Ok(json!({ "results": results }))
}

/// The completion endpoint is a JSONP-ish array, not InnerTube; the JavaScript
/// slice-and-parse is ported here because the core has no suggestion model.
fn parse_suggestions(text: &str) -> Vec<String> {
    let start = match text.find('[') {
        Some(i) => i,
        None => return Vec::new(),
    };
    let end = match text.rfind(']') {
        Some(i) if i > start => i,
        _ => return Vec::new(),
    };
    let parsed: Value = match serde_json::from_str(&text[start..=end]) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let rows = match parsed.get(1).and_then(Value::as_array) {
        Some(rows) => rows,
        None => return Vec::new(),
    };
    let mut out: Vec<String> = Vec::new();
    for row in rows {
        let Some(row) = row.as_array() else { continue };
        let label = row
            .first()
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim()
            .to_string();
        if label.is_empty() || out.contains(&label) {
            continue;
        }
        out.push(label);
    }
    out
}

#[tauri::command]
pub async fn suggest(
    state: State<'_, AppState>,
    query: String,
    force: Option<bool>,
) -> Result<Value, String> {
    let _ = force;
    let q = query.trim().to_string();
    if q.is_empty() {
        return Ok(json!({ "suggestions": [] }));
    }
    let (lang, country) = {
        let guard = state
            .locale
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.clone()
    };
    let url = format!(
        "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&hl={lang}&gl={country}&q={}",
        url_encode(&q)
    );
    match crate::http::get_text(&url, 8) {
        Ok((status, text)) if (200..300).contains(&status) => {
            Ok(json!({ "suggestions": parse_suggestions(&text) }))
        }
        _ => Ok(json!({ "suggestions": [] })),
    }
}

// ---- channels ----------------------------------------------------------------

#[tauri::command]
pub async fn channel(
    state: State<'_, AppState>,
    channel_id: String,
    params: Option<String>,
) -> Result<Value, String> {
    // No params means the Videos tab (the JavaScript default); asking with no
    // params at all gets the channel's Featured page instead.
    let params = match params.filter(|p| !p.is_empty()) {
        Some(params) => Some(params),
        None => Some(CHANNEL_TAB_VIDEOS.to_string()),
    };
    let page = with_tube(&state, |tube| {
        tube.channel_videos(&channel_id, params.as_deref(), false)
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn channel_cont(
    state: State<'_, AppState>,
    token: String,
    channel_id: Option<String>,
) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_continuation(&token, channel_id.as_deref().unwrap_or(""))
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn channel_live(state: State<'_, AppState>, channel_id: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_videos(&channel_id, Some(CHANNEL_TAB_LIVE), false)
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn channel_playlists(state: State<'_, AppState>, channel_id: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_playlists_page(&channel_id, None, false)
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn channel_playlists_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_playlists_continuation(&token).map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn channel_about(
    state: State<'_, AppState>,
    channel_id: String,
    token: Option<String>,
) -> Result<Value, String> {
    let about = with_tube(&state, |tube| {
        tube.channel_about(&channel_id, token.as_deref(), false)
            .map_err(err)
    })?;
    Ok(json!({ "about": about }))
}

// ---- watch / comments / playlists --------------------------------------------

#[tauri::command]
pub async fn watch(state: State<'_, AppState>, video_id: String) -> Result<Value, String> {
    let data = with_tube(&state, |tube| tube.watch(&video_id).map_err(err))?;
    Ok(json!({ "data": data }))
}

#[tauri::command]
pub async fn watch_more(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.watch_continuation(&token).map_err(err))?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn comments(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.comments(&token).map_err(err))?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn playlist(state: State<'_, AppState>, playlist_id: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.playlist(&playlist_id).map_err(err))?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub async fn playlist_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.playlist_continuation(&token).map_err(err))?;
    Ok(json!({ "page": page }))
}

// ---- streams -----------------------------------------------------------------

const STREAM_TTL: Duration = Duration::from_secs(5 * 3600);
/// How long a failed lookup stays "unavailable" before yt-dlp is asked again
/// (the JavaScript `MISS_TTL_MS`).
const MISS_TTL_MS: u64 = 60_000;

fn now_ms() -> u64 {
    crate::stream_cache::now_ms()
}

/// A ladder from the cache, in memory or on disk, if it is still fresh.
fn cached_ladder(state: &AppState, video_id: &str) -> Option<QualityLadder> {
    let cache = state
        .streams
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let (at, ladder) = cache.get(video_id)?;
    (now_ms().saturating_sub(*at) < STREAM_TTL.as_millis() as u64).then(|| ladder.clone())
}

/// Keep the resolved ladder in memory for this run and on disk for the next
/// one. Extraction is the slow part of pressing play (measured ~5 s, with
/// occasional 15-25 s stalls), and signed URLs stay valid for about six hours.
fn store_ladder(state: &AppState, video_id: &str, ladder: &QualityLadder) {
    let snapshot = {
        let mut cache = state
            .streams
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        cache.insert(video_id.to_string(), (now_ms(), ladder.clone()));
        cache.clone()
    };
    let path = state.streams_cache_path.clone();
    std::thread::spawn(move || crate::stream_cache::save(&path, &snapshot, now_ms()));
}

/// The resolved ladder for a video: the in-memory cache, then the disk cache it
/// came from, then one shared yt-dlp run. `force` (a quality change on a broken
/// stream) skips the caches.
fn ladder_for(state: &AppState, video_id: &str, force: bool) -> Result<QualityLadder, String> {
    state.ensure_streams_loaded();
    if !force {
        if let Some(ladder) = cached_ladder(state, video_id) {
            eprintln!("[indio] ladder {video_id} from the saved cache");
            return Ok(ladder);
        }
        if state.stream_misses.held(video_id, now_ms(), MISS_TTL_MS) {
            return Err("Video stream unavailable right now".to_string());
        }
    }
    let ytdlp = state
        .ytdlp
        .as_ref()
        .cloned()
        .ok_or_else(|| "yt-dlp is missing from this build".to_string())?;
    let id = video_id.to_string();
    let key = id.clone();
    // "someone else is already resolving this one" is the difference between a
    // two-second start and a twenty-second one, so the log says which happened.
    let sharing = state.ladder_flight.is_running(&key);
    let started = Instant::now();
    let outcome = state.ladder_flight.run(&key, move || {
        // A caller that started before us may have finished while we queued.
        if let Some(ladder) = cached_ladder(state, &id) {
            return Ok(ladder);
        }
        let info = ytdlp::extract(&ytdlp, &id)?;
        let ladder = ytdlp::ladder(&info);
        if ladder.options.is_empty() && !ladder.is_live {
            return Err("This video has no downloadable stream without ads".to_string());
        }
        store_ladder(state, &id, &ladder);
        state.stream_misses.forget(&id);
        Ok(ladder)
    });
    eprintln!(
        "[indio] ladder {video_id} {} in {:.1}s{}",
        if outcome.is_ok() { "ok" } else { "failed" },
        started.elapsed().as_secs_f64(),
        if sharing { " (shared run)" } else { "" }
    );
    if outcome.is_err() {
        state.stream_misses.remember(video_id, now_ms());
    }
    outcome
}

#[tauri::command]
pub async fn qualities(
    state: State<'_, AppState>,
    video_id: String,
    force: Option<bool>,
) -> Result<Value, String> {
    let ladder = ladder_for(&state, &video_id, force.unwrap_or(false))?;
    Ok(json!({ "qualities": ladder }))
}

/// "These videos are on screen." The page says which, and the shell decides
/// whether now is a good moment to look at them. Answering a click instantly is
/// worth a background yt-dlp run; making that click *slower* is not, so the
/// work is queued, one at a time, and always yields to anything the viewer is
/// actually waiting for.
#[tauri::command]
pub async fn warm_streams(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    video_ids: Vec<String>,
) -> Result<Value, String> {
    let ids: Vec<String> = video_ids.into_iter().filter(|id| !id.is_empty()).collect();
    if ids.is_empty() {
        return Ok(json!({ "queued": 0 }));
    }
    state.warm.push(&ids);
    let queued = state.warm.len();
    if state.warm.claim() {
        let _ = tauri::async_runtime::spawn_blocking(move || drain_warm(app));
    }
    Ok(json!({ "queued": queued }))
}

/// Rest between background lookups. Each yt-dlp run already takes seconds, so a
/// short gap is enough to keep the CPU free for the lookup the viewer is
/// actually waiting on; the old 1.2 s pause just delayed the stream they were
/// about to click. The page only warms two videos now (see warmStreams.ts).
const WARM_GAP: Duration = Duration::from_millis(500);

fn drain_warm(app: tauri::AppHandle) {
    let state = app.state::<AppState>();
    let mut settled = false;
    loop {
        let Some(video_id) = state.warm.next() else {
            state.warm.release();
            // A page can arrive while we are deciding to stop.
            if state.warm.is_empty() || !state.warm.claim() {
                return;
            }
            continue;
        };
        if !settled {
            // Let the page finish loading its thumbnails and the launch finish
            // whatever it was doing first.
            std::thread::sleep(WARM_GAP);
            settled = true;
        }
        if state.ladder_flight.active() > 0 {
            // The viewer is waiting on a resolution of their own; go round
            // again later instead of competing with it.
            state.warm.requeue(&video_id);
            std::thread::sleep(WARM_GAP);
            continue;
        }
        match ladder_for(&state, &video_id, false) {
            Ok(_) => eprintln!("[indio] warmed {video_id} in the background"),
            // A video that cannot be resolved is not worth trying again right
            // now; the miss cache says so for a minute, so drop it from the
            // queue rather than spinning on it.
            Err(e) => eprintln!("[indio] warm {video_id} skipped: {e}"),
        }
        std::thread::sleep(WARM_GAP);
    }
}

#[derive(Debug)]
struct Picked {
    url: String,
    audio: Option<String>,
    height: i64,
    label: String,
    video_codec: Option<String>,
    audio_codec: Option<String>,
}

/// The same choice electron/streams.js makes: a muxed rung when there is one,
/// otherwise video-only plus the shared best audio track.
fn pick_tracks(ladder: &indio_core::quality::QualityLadder, height: Option<i64>) -> Result<Picked, String> {
    if ladder.options.is_empty() && ladder.auto.is_none() {
        return Err("This video has no ad-free stream".to_string());
    }
    let target = height.unwrap_or(ladder.auto_height);
    if let Some(rung) = indio_core::quality::nearest(&ladder.options, target) {
        let label = if height.is_none() {
            "Auto".to_string()
        } else {
            rung.label.clone()
        };
        if let Some(muxed) = &rung.muxed {
            return Ok(Picked {
                url: muxed.url.clone(),
                audio: None,
                height: rung.height,
                label,
                video_codec: muxed.codec.clone(),
                audio_codec: None,
            });
        }
        if let Some(video) = &rung.video_only {
            return Ok(Picked {
                url: video.url.clone(),
                audio: ladder.audio.as_ref().map(|a| a.url.clone()),
                height: rung.height,
                label,
                video_codec: video.codec.clone(),
                audio_codec: ladder.audio.as_ref().and_then(|a| a.codec.clone()),
            });
        }
    }
    if let Some(auto) = &ladder.auto {
        return Ok(Picked {
            url: auto.url.clone(),
            audio: None,
            height: auto.height,
            label: "Auto".to_string(),
            video_codec: auto.codec.clone(),
            audio_codec: None,
        });
    }
    Err("This quality isn't available for this video".to_string())
}

fn proxy_token(state: &AppState, url: &str, video_id: &str, role: &str, height: Option<i64>) -> String {
    let token = state.proxy.add_token(
        url,
        TokenEntry {
            url: url.to_string(),
            video_id: Some(video_id.to_string()),
            role: Some(role.to_string()),
            height,
            at: Instant::now(),
        },
    );
    format!("http://127.0.0.1:{}/v?t={token}", state.proxy_port)
}

#[tauri::command]
pub async fn play_url(
    state: State<'_, AppState>,
    video_id: String,
    height: Option<i64>,
) -> Result<Value, String> {
    let ladder = ladder_for(&state, &video_id, false)?;
    if ladder.is_live {
        return Err("Live videos are not playable on desktop yet".to_string());
    }
    let picked = pick_tracks(&ladder, height)?;
    let video = proxy_token(&state, &picked.url, &video_id, "video", Some(picked.height));
    let audio = picked
        .audio
        .as_deref()
        .map(|url| proxy_token(&state, url, &video_id, "audio", Some(picked.height)));
    Ok(json!({
        "video": video,
        "audio": audio,
        "height": picked.height,
        "label": picked.label,
        "videoCodec": picked.video_codec,
        "audioCodec": picked.audio_codec,
    }))
}

#[tauri::command]
pub async fn subtitle_url(
    state: State<'_, AppState>,
    video_id: String,
    lang: Option<String>,
) -> Result<Value, String> {
    let ladder = ladder_for(&state, &video_id, false)?;
    let wanted = lang.unwrap_or_default().to_lowercase();
    let mut track = ladder
        .subtitles
        .iter()
        .find(|s| s.language_tag.to_lowercase() == wanted);
    if track.is_none() && !wanted.is_empty() {
        let base = wanted.split('-').next().unwrap_or(&wanted);
        track = ladder.subtitles.iter().find(|s| {
            s.language_tag
                .to_lowercase()
                .split('-')
                .next()
                .unwrap_or("")
                == base
        });
    }
    let Some(track) = track else {
        return Ok(json!({ "url": Value::Null }));
    };
    let vtt = indio_core::quality::subtitle_vtt_url(&track.url);
    let token = state.proxy.add_token(
        &vtt,
        TokenEntry {
            url: vtt.clone(),
            video_id: Some(video_id),
            role: Some("subtitle".to_string()),
            height: None,
            at: Instant::now(),
        },
    );
    Ok(json!({
        "url": format!("http://127.0.0.1:{}/subs?t={token}&ext=1", state.proxy_port)
    }))
}

#[tauri::command]
pub async fn local_url(state: State<'_, AppState>, file: String) -> Result<Value, String> {
    Ok(json!({
        "url": format!(
            "http://127.0.0.1:{}/file?p={}",
            state.proxy_port,
            url_encode(&file)
        )
    }))
}

#[tauri::command]
pub async fn ffmpeg(state: State<'_, AppState>) -> Result<Value, String> {
    Ok(json!({ "available": ytdlp::ffmpeg_available(state.ffmpeg.as_ref()) }))
}

// ---- misc --------------------------------------------------------------------

#[tauri::command]
pub async fn app_version() -> Result<Value, String> {
    Ok(json!({ "version": env!("CARGO_PKG_VERSION") }))
}

#[tauri::command]
pub async fn sponsor_segments(
    state: State<'_, AppState>,
    video_id: String,
    categories: Option<Vec<String>>,
) -> Result<Value, String> {
    let valid = !video_id.is_empty()
        && video_id.len() >= 6
        && video_id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
    if !valid {
        return Err("invalid video id".to_string());
    }
    let categories = categories.unwrap_or_default();
    let mut sorted = categories.clone();
    sorted.sort();
    let key = format!("{video_id}|{}", sorted.join(","));
    {
        let cache = state
            .sb_cache
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some((at, data)) = cache.get(&key) {
            if at.elapsed() < Duration::from_secs(600) {
                return Ok(json!({ "data": data }));
            }
        }
    }
    let url = format!(
        "https://sponsor.ajay.app/api/skipSegments?videoID={}&categories={}",
        url_encode(&video_id),
        url_encode(&serde_json::to_string(&categories).map_err(err)?)
    );
    let (status, text) = crate::http::get_text(&url, 8)?;
    // 404 is the normal "nobody submitted segments" answer.
    let data: Value = if status == 404 {
        json!([])
    } else if (200..300).contains(&status) {
        let parsed: Value = serde_json::from_str(&text).unwrap_or_else(|_| json!([]));
        if parsed.is_array() {
            parsed
        } else {
            json!([])
        }
    } else {
        return Err(format!("SponsorBlock returned HTTP {status}"));
    };
    let mut cache = state
        .sb_cache
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    cache.insert(key, (Instant::now(), data.clone()));
    Ok(json!({ "data": data }))
}

fn url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use indio_core::quality::build_qualities;
    use serde_json::json;

    /// A trimmed `yt-dlp --dump-single-json` document: one muxed 360p rung, a
    /// split 720p rung, a 1080p60 video-only rung and one audio track.
    fn fixture() -> Value {
        json!({
            "id": "abc123",
            "title": "Fixture",
            "duration": 60,
            "live_status": "not_live",
            "formats": [
                { "itag": 18, "url": "https://cdn/360muxed", "ext": "mp4", "vcodec": "avc1.4d401e",
                  "acodec": "mp4a.40.2", "width": 640, "height": 360, "fps": 30, "tbr": 700, "protocol": "https" },
                { "itag": 137, "url": "https://cdn/1080video", "ext": "mp4", "vcodec": "avc1.640028",
                  "acodec": "none", "width": 1920, "height": 1080, "fps": 60, "tbr": 4500, "protocol": "https" },
                { "itag": 248, "url": "https://cdn/720video", "ext": "webm", "vcodec": "vp09.00.40.08",
                  "acodec": "none", "width": 1280, "height": 720, "fps": 30, "tbr": 2500, "protocol": "https" },
                { "itag": 140, "url": "https://cdn/audio", "ext": "m4a", "vcodec": "none",
                  "acodec": "mp4a.40.2", "tbr": 129, "protocol": "https", "format_note": "English (US) original (default)" }
            ],
            "subtitles": {},
            "automatic_captions": {}
        })
    }

    #[test]
    fn a_muxed_rung_is_one_file_and_needs_no_audio_track() {
        let ladder = build_qualities(&fixture());
        let picked = pick_tracks(&ladder, Some(360)).unwrap();
        assert_eq!(picked.url, "https://cdn/360muxed");
        assert!(picked.audio.is_none(), "a single file carries its own sound");
        assert_eq!(picked.height, 360);
        assert_eq!(picked.label, "360p");
        assert_eq!(picked.video_codec.as_deref(), Some("avc1.4d401e"));
    }

    #[test]
    fn a_split_rung_pairs_with_the_shared_audio_track() {
        let ladder = build_qualities(&fixture());
        let picked = pick_tracks(&ladder, Some(720)).unwrap();
        assert_eq!(picked.url, "https://cdn/720video");
        assert_eq!(picked.audio.as_deref(), Some("https://cdn/audio"));
        assert_eq!(picked.height, 720);
        assert_eq!(picked.audio_codec.as_deref(), Some("mp4a.40.2"));
    }

    #[test]
    fn plain_auto_means_the_auto_height_and_keeps_the_label() {
        let ladder = build_qualities(&fixture());
        let picked = pick_tracks(&ladder, None).unwrap();
        assert_eq!(picked.label, "Auto");
        // The ladder's auto height is capped at 720p, and 720 is only offered
        // split — so Auto still gets its audio partner.
        assert_eq!(picked.height, ladder.auto_height);
        assert!(picked.height <= 720);
    }

    #[test]
    fn a_missing_rung_falls_to_the_nearest_one_below() {
        let ladder = build_qualities(&fixture());
        let picked = pick_tracks(&ladder, Some(800)).unwrap();
        assert_eq!(picked.height, 720);
        let tiny = pick_tracks(&ladder, Some(120)).unwrap();
        assert_eq!(tiny.height, 360, "nothing below: take the smallest rung");
    }

    #[test]
    fn an_unplayable_video_says_so() {
        let mut info = fixture();
        info["formats"] = json!([]);
        let ladder = build_qualities(&info);
        let error = pick_tracks(&ladder, Some(720)).unwrap_err();
        assert!(error.contains("no ad-free stream"), "{error}");
    }

    #[test]
    fn suggestions_are_deduped_trimmed_and_skipped_when_empty() {
        let body = r#"window.google.ac.h(["rust",[["rust tutorial",null],["rust async",null,1],["rust tutorial",null],["",null],["  spaced  "],[ ] ,"more"],{"a":"b"}])"#;
        assert_eq!(
            parse_suggestions(body),
            vec!["rust tutorial", "rust async", "spaced"]
        );
        assert!(parse_suggestions("garbage").is_empty());
        assert!(parse_suggestions("").is_empty());
        // A payload whose second slot is not a list yields nothing.
        assert!(parse_suggestions(r#"["q", "nope"]"#).is_empty());
    }

    #[test]
    fn url_encoding_leaves_unreserved_characters_alone() {
        assert_eq!(url_encode("a-b_c.d~e"), "a-b_c.d~e");
        assert_eq!(url_encode("C:\\Users\\a b\\v.mp4"), "C%3A%5CUsers%5Ca%20b%5Cv.mp4");
        assert_eq!(url_encode("é"), "%C3%A9");
    }

    #[test]
    fn the_client_version_date_is_today_in_utc() {
        let (year, month, day) = utc_ymd();
        let secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        assert!(year >= 2024 && year < 2100, "{year}");
        assert!((1..=12).contains(&month), "{month}");
        assert!((1..=31).contains(&day), "{day}");
        // The same days-since-epoch arithmetic the civil-date algorithm runs on.
        assert!(secs > 1_700_000_000);
    }

    #[test]
    fn sponsor_block_ids_are_validated_before_a_request() {
        let valid = |id: &str| {
            !id.is_empty()
                && id.len() >= 6
                && id
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        };
        assert!(valid("dQw4w9WgXcQ"));
        assert!(valid("abc_-123"));
        assert!(!valid("short"));
        assert!(!valid("bad id!"));
        assert!(!valid(""));
    }
}
