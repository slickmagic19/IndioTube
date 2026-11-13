// The IndioTube desktop shell: a Tauri window around the same React UI, with
// the Rust core (indio-core) behind the window.indio bridge.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod http;
mod ytdlp;

use std::collections::HashMap;
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use indio_core::innertube::InnerTube;
use indio_core::proxy::Proxy;
use indio_core::quality::QualityLadder;
use serde_json::{json, Value};
use tauri::{Manager, State};

use crate::http::{UreqTransport, UreqUpstream};

pub struct AppState {
    /// Created by `indio_init` once the renderer reports its locale, so the
    /// request context carries the same `hl`/`gl` the JavaScript client used.
    pub tube: Mutex<Option<InnerTube<UreqTransport>>>,
    pub locale: Mutex<(String, String)>,
    pub proxy: Arc<Proxy<UreqUpstream>>,
    pub proxy_port: u16,
    pub download_dir: PathBuf,
    pub streams: Mutex<HashMap<String, (Instant, QualityLadder)>>,
    pub sb_cache: Mutex<HashMap<String, (Instant, Value)>>,
    pub ytdlp: Option<PathBuf>,
    pub ffmpeg: Option<PathBuf>,
    pub settings_path: PathBuf,
    pub settings: Mutex<Value>,
}

fn data_dir() -> PathBuf {
    if let Ok(appdata) = std::env::var("APPDATA") {
        return PathBuf::from(appdata).join("indiotube-desktop");
    }
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

fn download_dir() -> PathBuf {
    let home = std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .unwrap_or_else(|_| ".".to_string());
    let dir = PathBuf::from(home).join("Downloads").join("IndioTube");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

fn load_settings(path: &PathBuf) -> Value {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str::<Value>(&text).ok())
        .filter(Value::is_object)
        .unwrap_or_else(|| json!({}))
}

#[tauri::command]
fn settings_get(state: State<'_, AppState>) -> Result<Value, String> {
    let settings = state
        .settings
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    Ok(json!({ "settings": settings }))
}

#[tauri::command]
fn settings_set(state: State<'_, AppState>, patch: Value) -> Result<Value, String> {
    let merged = {
        let mut guard = state
            .settings
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let (Some(current), Some(patch)) = (guard.as_object_mut(), patch.as_object()) {
            for (key, value) in patch {
                current.insert(key.clone(), value.clone());
            }
        }
        guard.clone()
    };
    if let Ok(text) = serde_json::to_string_pretty(&merged) {
        let _ = std::fs::write(&state.settings_path, text);
    }
    Ok(json!({ "settings": merged }))
}

#[tauri::command]
fn win_fullscreen(window: tauri::WebviewWindow, on: bool) -> Result<Value, String> {
    window.set_fullscreen(on).map_err(|e| e.to_string())?;
    Ok(json!({ "fullscreen": on }))
}

#[tauri::command]
fn win_is_fullscreen(window: tauri::WebviewWindow) -> Result<Value, String> {
    let fullscreen = window.is_fullscreen().unwrap_or(false);
    Ok(json!({ "fullscreen": fullscreen }))
}

#[tauri::command]
fn win_mini(window: tauri::WebviewWindow, on: bool) -> Result<Value, String> {
    let _ = window.set_always_on_top(on);
    if on {
        let _ = window.set_size(tauri::PhysicalSize::new(440, 260));
    } else {
        let _ = window.set_size(tauri::PhysicalSize::new(1280, 820));
    }
    Ok(json!({ "mini": on }))
}

// ---- groups the shell does not own yet ---------------------------------------

#[tauri::command]
fn library_get() -> Result<Value, String> {
    Err(commands::not_portable("the library"))
}

#[tauri::command]
fn library_op(_op: String, _args: Option<Vec<Value>>) -> Result<Value, String> {
    Err(commands::not_portable("the library"))
}

#[tauri::command]
fn downloads_start() -> Result<Value, String> {
    Err(commands::not_portable("the download queue"))
}

#[tauri::command]
fn downloads_cancel() -> Result<Value, String> {
    Err(commands::not_portable("the download queue"))
}

#[tauri::command]
fn downloads_list() -> Result<Value, String> {
    Ok(json!({ "tasks": [] }))
}

#[tauri::command]
fn downloads_pause() -> Result<Value, String> {
    Err(commands::not_portable("the download queue"))
}

#[tauri::command]
fn downloads_resume() -> Result<Value, String> {
    Err(commands::not_portable("the download queue"))
}

#[tauri::command]
fn downloads_pick_dir() -> Result<Value, String> {
    Err(commands::not_portable("the folder picker"))
}

#[tauri::command]
fn downloads_reveal(file: String) -> Result<Value, String> {
    #[cfg(windows)]
    {
        let _ = std::process::Command::new("explorer").arg("/select,").arg(&file).spawn();
    }
    let _ = file;
    Ok(json!({}))
}

#[tauri::command]
fn sync_start() -> Result<Value, String> {
    Err(commands::not_portable("device sync"))
}

#[tauri::command]
fn sync_stop() -> Result<Value, String> {
    Err(commands::not_portable("device sync"))
}

/// Headless check of the parts that need the network and the sidecars:
///
/// ```text
/// INDIOTUBE_SELFTEST=dQw4w9WgXcQ target/debug/IndioTube.exe
/// ```
///
/// Resolves the ladder with yt-dlp, registers a proxy token for the chosen
/// rung, then fetches a byte range back through the proxy like the player does.
fn selftest(state: &AppState, video_id: &str) -> i32 {
    use indio_core::innertube::{default_config, InnerTube};
    let tube = InnerTube::new(
        UreqTransport::new(),
        default_config(commands_utc_ymd()),
        "en",
        "US",
    );

    match tube.raw_search("cats", None) {
        Ok(results) => println!("[selftest] search entries={}", results.entries.len()),
        Err(e) => {
            println!("[selftest] search FAILED: {e}");
            return 1;
        }
    }

    let ytdlp = match state.ytdlp.as_ref() {
        Some(path) => path,
        None => {
            println!("[selftest] yt-dlp not found");
            return 1;
        }
    };
    let info = match ytdlp::extract(ytdlp, video_id) {
        Ok(info) => info,
        Err(e) => {
            println!("[selftest] extract FAILED: {e}");
            return 1;
        }
    };
    let ladder = ytdlp::ladder(&info);
    println!(
        "[selftest] ladder options={} audio={} subtitles={} live={} auto={}",
        ladder.options.len(),
        ladder.audio.is_some(),
        ladder.subtitles.len(),
        ladder.is_live,
        ladder.auto_height
    );
    let Some(rung) = indio_core::quality::nearest(&ladder.options, ladder.auto_height) else {
        println!("[selftest] no rung for the auto height");
        return 1;
    };
    let url = rung
        .muxed
        .as_ref()
        .map(|t| t.url.clone())
        .or_else(|| rung.video_only.as_ref().map(|t| t.url.clone()))
        .or_else(|| ladder.auto.as_ref().map(|t| t.url.clone()));
    let Some(url) = url else {
        println!("[selftest] no playable url on the chosen rung");
        return 1;
    };
    let token = state.proxy.add_token(
        &url,
        indio_core::proxy::TokenEntry {
            url: url.clone(),
            video_id: Some(video_id.to_string()),
            role: Some("video".to_string()),
            height: Some(rung.height),
            at: Instant::now(),
        },
    );
    let proxy_url = format!(
        "http://127.0.0.1:{}/v?t={token}",
        state.proxy_port
    );
    let agent = ureq::AgentBuilder::new().build();
    match agent
        .get(&proxy_url)
        .set("range", "bytes=0-1023")
        .call()
    {
        Ok(resp) => {
            let status = resp.status();
            let content_range = resp.header("content-range").unwrap_or("-").to_string();
            let mut body = Vec::new();
            let _ = std::io::Read::read_to_end(&mut resp.into_reader(), &mut body);
            println!(
                "[selftest] proxy status={status} bytes={} content-range={content_range} height={}",
                body.len(),
                rung.height
            );
            if body.is_empty() || (status != 200 && status != 206) {
                return 1;
            }
        }
        Err(e) => {
            println!("[selftest] proxy request FAILED: {e}");
            return 1;
        }
    }
    println!("[selftest] OK");
    0
}

fn commands_utc_ymd() -> (i32, u32, u32) {
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

fn main() {
    let settings_path = data_dir().join("settings.json");
    let _ = std::fs::create_dir_all(settings_path.parent().unwrap_or(&settings_path));
    let settings = load_settings(&settings_path);

    let listener = TcpListener::bind("127.0.0.1:0").expect("bind media proxy");
    let proxy_port = listener.local_addr().expect("proxy address").port();
    let proxy = Arc::new(Proxy::new(UreqUpstream::new(), download_dir()));
    let stop = Arc::new(AtomicBool::new(false));
    {
        let proxy = Arc::clone(&proxy);
        let stop = Arc::clone(&stop);
        std::thread::spawn(move || indio_core::proxy::serve(proxy, listener, stop));
    }

    let ytdlp = ytdlp::locate("yt-dlp", "INDIOTUBE_YTDLP");
    let ffmpeg = ytdlp::locate("ffmpeg", "INDIOTUBE_FFMPEG");

    let state = AppState {
        tube: Mutex::new(None),
        locale: Mutex::new(("en".to_string(), "US".to_string())),
        proxy,
        proxy_port,
        download_dir: download_dir(),
        streams: Mutex::new(HashMap::new()),
        sb_cache: Mutex::new(HashMap::new()),
        ytdlp,
        ffmpeg,
        settings_path,
        settings: Mutex::new(settings),
    };

    if let Ok(video) = std::env::var("INDIOTUBE_SELFTEST") {
        let code = selftest(&state, &video);
        std::process::exit(code);
    }

    tauri::Builder::default()
        .manage(state)
        .setup(|app| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::indio_init,
            commands::home,
            commands::home_cont,
            commands::home_more,
            commands::trending,
            commands::trending_cont,
            commands::search,
            commands::search_cont,
            commands::suggest,
            commands::channel,
            commands::channel_cont,
            commands::channel_live,
            commands::channel_playlists,
            commands::channel_playlists_cont,
            commands::channel_about,
            commands::watch,
            commands::watch_more,
            commands::comments,
            commands::playlist,
            commands::playlist_cont,
            commands::qualities,
            commands::play_url,
            commands::subtitle_url,
            commands::local_url,
            commands::ffmpeg,
            commands::app_version,
            commands::sponsor_segments,
            settings_get,
            settings_set,
            win_fullscreen,
            win_is_fullscreen,
            win_mini,
            library_get,
            library_op,
            downloads_start,
            downloads_cancel,
            downloads_list,
            downloads_pause,
            downloads_resume,
            downloads_pick_dir,
            downloads_reveal,
            sync_start,
            sync_stop,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the IndioTube shell");
}
