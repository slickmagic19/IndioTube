//! The `window.indio` command surface, backed by indio-core.
//!
//! Every command returns the same wrapper object the Electron preload used
//! (`{ feed }`, `{ results }`, `{ page }`, …) so the renderer's calls map one to
//! one and only the bridge has to know about the transport.

use std::time::{Duration, Instant};

use indio_core::innertube::{
    build_search_params, default_config, recommendable_videos, Feed, InnerTube, SearchFilters,
    CHANNEL_TAB_LIVE, TRENDING_PARAMS,
};
use indio_core::proxy::TokenEntry;
use serde_json::{json, Value};
use tauri::State;

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
pub fn indio_init(state: State<'_, AppState>, lang: Option<String>, country: Option<String>) -> Result<Value, String> {
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

fn utc_ymd() -> (i32, u32, u32) {
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

#[tauri::command]
pub fn home(state: State<'_, AppState>, params: Option<String>, force: Option<bool>) -> Result<Value, String> {
    let feed = with_tube(&state, |tube| {
        tube.home(params.as_deref(), force.unwrap_or(false))
            .map_err(err)
    })?;
    eprintln!("[indio] home -> {} videos", feed.videos.len());
    Ok(json!({ "feed": feed }))
}

#[tauri::command]
pub fn home_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let feed = with_tube(&state, |tube| tube.home_continuation(&token).map_err(err))?;
    Ok(json!({ "feed": feed }))
}

/// The Electron build pages Home from a local recommendation pool; the shell
/// filters the current feed instead, which is the same list without the ids the
/// renderer already shows.
#[tauri::command]
pub fn home_more(
    state: State<'_, AppState>,
    exclude_ids: Option<Vec<String>>,
    limit: Option<usize>,
) -> Result<Value, String> {
    let exclude = exclude_ids.unwrap_or_default();
    let limit = limit.unwrap_or(20);
    let feed = with_tube(&state, |tube| tube.home(None, false).map_err(err))?;
    let videos: Vec<_> = feed
        .videos
        .into_iter()
        .filter(|v| !exclude.contains(&v.video_id))
        .take(limit)
        .collect();
    Ok(json!({ "videos": videos }))
}

#[tauri::command]
pub fn trending(state: State<'_, AppState>, force: Option<bool>) -> Result<Value, String> {
    let _ = force;
    let results = with_tube(&state, |tube| {
        tube.raw_search("", Some(TRENDING_PARAMS)).map_err(err)
    })?;
    let videos: Vec<_> = recommendable_videos(
        results
            .entries
            .into_iter()
            .filter_map(|e| match e {
                indio_core::innertube::SearchEntry::Video { video } => Some(video),
                _ => None,
            })
            .collect(),
    );
    let feed = Feed {
        chips: Vec::new(),
        videos,
        continuation: results.continuation,
    };
    Ok(json!({ "feed": feed }))
}

#[tauri::command]
pub fn trending_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let results = with_tube(&state, |tube| tube.search_continuation(&token).map_err(err))?;
    let videos: Vec<_> = recommendable_videos(
        results
            .entries
            .into_iter()
            .filter_map(|e| match e {
                indio_core::innertube::SearchEntry::Video { video } => Some(video),
                _ => None,
            })
            .collect(),
    );
    let feed = Feed {
        chips: Vec::new(),
        videos,
        continuation: results.continuation,
    };
    Ok(json!({ "feed": feed }))
}

#[tauri::command]
pub fn search(
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
pub fn search_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
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
pub fn suggest(
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
pub fn channel(
    state: State<'_, AppState>,
    channel_id: String,
    params: Option<String>,
) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_videos(&channel_id, params.as_deref(), false)
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn channel_cont(
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
pub fn channel_live(state: State<'_, AppState>, channel_id: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_videos(&channel_id, Some(CHANNEL_TAB_LIVE), false)
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn channel_playlists(state: State<'_, AppState>, channel_id: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_playlists_page(&channel_id, None, false)
            .map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn channel_playlists_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| {
        tube.channel_playlists_continuation(&token).map_err(err)
    })?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn channel_about(
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
pub fn watch(state: State<'_, AppState>, video_id: String) -> Result<Value, String> {
    let data = with_tube(&state, |tube| tube.watch(&video_id).map_err(err))?;
    Ok(json!({ "data": data }))
}

#[tauri::command]
pub fn watch_more(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.watch_continuation(&token).map_err(err))?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn comments(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.comments(&token).map_err(err))?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn playlist(state: State<'_, AppState>, playlist_id: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.playlist(&playlist_id).map_err(err))?;
    Ok(json!({ "page": page }))
}

#[tauri::command]
pub fn playlist_cont(state: State<'_, AppState>, token: String) -> Result<Value, String> {
    let page = with_tube(&state, |tube| tube.playlist_continuation(&token).map_err(err))?;
    Ok(json!({ "page": page }))
}

// ---- streams -----------------------------------------------------------------

const STREAM_TTL: Duration = Duration::from_secs(5 * 3600);

fn ladder_for(state: &AppState, video_id: &str, force: bool) -> Result<indio_core::quality::QualityLadder, String> {
    {
        let cache = state
            .streams
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !force {
            if let Some((at, ladder)) = cache.get(video_id) {
                if at.elapsed() < STREAM_TTL {
                    return Ok(ladder.clone());
                }
            }
        }
    }
    let ytdlp = state
        .ytdlp
        .as_ref()
        .ok_or("yt-dlp is missing from this build")?;
    let info = ytdlp::extract(ytdlp, video_id)?;
    let ladder = ytdlp::ladder(&info);
    if ladder.options.is_empty() && !ladder.is_live {
        return Err("This video has no downloadable stream without ads".to_string());
    }
    let mut cache = state
        .streams
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    cache.insert(video_id.to_string(), (Instant::now(), ladder.clone()));
    Ok(ladder)
}

#[tauri::command]
pub fn qualities(
    state: State<'_, AppState>,
    video_id: String,
    force: Option<bool>,
) -> Result<Value, String> {
    let ladder = ladder_for(&state, &video_id, force.unwrap_or(false))?;
    Ok(json!({ "qualities": ladder }))
}

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
pub fn play_url(
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
pub fn subtitle_url(
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
pub fn local_url(state: State<'_, AppState>, file: String) -> Result<Value, String> {
    Ok(json!({
        "url": format!(
            "http://127.0.0.1:{}/file?p={}",
            state.proxy_port,
            url_encode(&file)
        )
    }))
}

#[tauri::command]
pub fn ffmpeg(state: State<'_, AppState>) -> Result<Value, String> {
    Ok(json!({ "available": ytdlp::ffmpeg_available(state.ffmpeg.as_ref()) }))
}

// ---- misc --------------------------------------------------------------------

#[tauri::command]
pub fn app_version() -> Result<Value, String> {
    Ok(json!({ "version": env!("CARGO_PKG_VERSION") }))
}

#[tauri::command]
pub fn sponsor_segments(
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

/// Unported groups answer with a clear reason instead of hanging or pretending.
pub fn not_portable(feature: &str) -> String {
    format!("{feature} is not available in the Rust shell yet")
}
