// The IndioTube desktop shell: a Tauri window around the same React UI, with
// the Rust core (indio-core) behind the window.indio bridge. Everything the
// Electron main process used to do — feeds, playback proxy, library, downloads,
// LAN sync, settings and window modes — now lives in this process.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod downloads;
mod extract;
mod http;
mod library;
mod local_feed;
mod smoke;
mod stream_cache;
mod sync_core;
mod sync_server;
mod ytdlp;

use std::collections::HashMap;
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use indio_core::innertube::{default_config, InnerTube};
use indio_core::proxy::Proxy;
use indio_core::quality::QualityLadder;
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::downloads::Downloads;
use crate::extract::{MissCache, Prefetch, SingleFlight};
use crate::http::{UreqTransport, UreqUpstream};
use crate::library::Library;
use crate::local_feed::{LocalFeed, TubeFactory};
use crate::sync_server::SyncServer;

pub struct AppState {
    /// Created by `indio_init` once the renderer reports its locale, so the
    /// request context carries the same `hl`/`gl` the JavaScript client used.
    pub tube: Mutex<Option<InnerTube<UreqTransport>>>,
    pub locale: Mutex<(String, String)>,
    pub proxy: Arc<Proxy<UreqUpstream>>,
    pub proxy_port: u16,
    /// video id -> (resolved at epoch ms, ladder). Filled from
    /// `streams_cache_path` on first use, not before the window is up.
    pub streams: Mutex<HashMap<String, (u64, QualityLadder)>>,
    pub streams_cache_path: PathBuf,
    pub streams_loaded: AtomicBool,
    /// Hovering a card, opening the watch page and the player itself all ask for
    /// the same ladder within a second of each other; one yt-dlp run answers all
    /// three.
    pub ladder_flight: SingleFlight<QualityLadder>,
    /// Videos yt-dlp just refused, so a second ask does not wait all over again.
    pub stream_misses: MissCache,
    /// The videos the UI has just shown, waiting for a quiet moment to have
    /// their streams resolved.
    pub warm: Prefetch,
    pub sb_cache: Mutex<HashMap<String, (Instant, Value)>>,
    pub ytdlp: Option<PathBuf>,
    pub ffmpeg: Option<PathBuf>,
    pub settings_path: PathBuf,
    pub settings: Mutex<Value>,
    /// Shared with the media proxy and the download queue, so a folder change
    /// in Settings takes effect without a restart.
    pub download_dir: Arc<Mutex<PathBuf>>,
    /// The local Home recommender: its pool is cached between refreshes.
    pub local_feed: Arc<LocalFeed>,
    pub mini_prev: Mutex<Option<MiniPrev>>,
}

pub struct LibraryState(pub Arc<Library>);
pub struct DownloadsState(pub Arc<Downloads>);
pub struct SyncState(pub Arc<SyncServer>);

impl AppState {
    /// Read the saved ladder cache on first use. Parsing up to 60 of them is
    /// worth a few milliseconds, and doing it here keeps them off the path
    /// between launching the app and the window appearing.
    pub fn ensure_streams_loaded(&self) {
        let mut streams = self
            .streams
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if self.streams_loaded.load(Ordering::SeqCst) {
            return;
        }
        for (id, entry) in stream_cache::load(&self.streams_cache_path, stream_cache::now_ms()) {
            streams.entry(id).or_insert(entry);
        }
        self.streams_loaded.store(true, Ordering::SeqCst);
    }
}

#[derive(Clone, Copy)]
pub struct MiniPrev {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub maximized: bool,
    pub fullscreen: bool,
}

/// Same folder Electron used (`app.getPath('userData')`), so a machine that
/// switches shells keeps its library, settings and download queue.
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

/// The settings file is the one Electron wrote. A `settings.json` from an early
/// Rust-only run is folded in by renaming it into place before anything reads.
fn settings_file(dir: &PathBuf) -> PathBuf {
    let current = dir.join("indiotube-settings.json");
    let legacy = dir.join("settings.json");
    if !current.exists() && legacy.exists() {
        let _ = std::fs::rename(&legacy, &current);
    }
    current
}

/// The download queue file, likewise: Electron called it `downloads.json`.
fn queue_file(dir: &PathBuf) -> PathBuf {
    let current = dir.join("downloads.json");
    let legacy = dir.join("indiotube-downloads.json");
    if !current.exists() && legacy.exists() {
        let _ = std::fs::rename(&legacy, &current);
    }
    current
}

fn load_settings(path: &PathBuf, download_dir: &str) -> Value {
    let mut merged = library::default_settings(download_dir);
    if let Ok(text) = std::fs::read_to_string(path) {
        if let Ok(parsed) = serde_json::from_str::<Value>(&text) {
            if let (Some(base), Some(stored)) = (merged.as_object_mut(), parsed.as_object()) {
                library::merge_settings(base, stored);
            }
        }
    }
    if merged
        .get("downloadDir")
        .and_then(Value::as_str)
        .unwrap_or("")
        .is_empty()
    {
        merged["downloadDir"] = json!(download_dir);
    }
    merged
}

/// A fresh client for one gather: `InnerTube` owns its request context, so each
/// recommendation worker gets its own.
pub fn new_tube(state: &AppState) -> InnerTube<UreqTransport> {
    let (lang, country) = state
        .locale
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    InnerTube::new(
        UreqTransport::new(),
        default_config(commands::utc_ymd()),
        &lang,
        &country,
    )
}

/// The same thing as a factory that can move into a background thread, which is
/// what refreshing Home behind a cached page needs.
pub fn tube_factory(state: &AppState) -> TubeFactory {
    let (lang, country) = state
        .locale
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    Arc::new(move || {
        InnerTube::new(
            UreqTransport::new(),
            default_config(commands::utc_ymd()),
            &lang,
            &country,
        )
    })
}

fn emit_to(app: &AppHandle, event: &str) -> impl Fn(Value) + Send + Sync + 'static {
    let app = app.clone();
    let event = event.to_string();
    move |payload: Value| {
        let _ = app.emit(&event, payload);
    }
}

#[tauri::command]
async fn settings_get(state: State<'_, AppState>) -> Result<Value, String> {
    let settings = state
        .settings
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    Ok(json!({ "settings": settings }))
}

#[tauri::command]
async fn settings_set(
    state: State<'_, AppState>,
    downloads: State<'_, DownloadsState>,
    patch: Value,
) -> Result<Value, String> {
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
    if let Some(dir) = downloads::download_dir_from_settings(&merged) {
        apply_download_dir(&state, &downloads.0, dir);
    }
    if let Ok(text) = serde_json::to_string_pretty(&merged) {
        let _ = std::fs::write(&state.settings_path, text);
    }
    Ok(json!({ "settings": merged }))
}

/// Point the queue, the proxy's `/file` root and the settings at a folder.
pub fn apply_download_dir(state: &AppState, downloads: &Downloads, dir: PathBuf) {
    let _ = std::fs::create_dir_all(&dir);
    {
        let mut shared = state
            .download_dir
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *shared = dir.clone();
    }
    state.proxy.set_download_dir(dir.clone());
    downloads.set_dir(dir.clone());
    let mut settings = state
        .settings
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    settings["downloadDir"] = json!(dir.to_string_lossy().to_string());
}

// ---- library -----------------------------------------------------------------

#[tauri::command]
async fn library_get(library: State<'_, LibraryState>) -> Result<Value, String> {
    Ok(json!({ "state": library.0.state() }))
}

#[tauri::command]
async fn library_op(
    library: State<'_, LibraryState>,
    op: String,
    args: Option<Vec<Value>>,
) -> Result<Value, String> {
    library.0.run(&op, &args.unwrap_or_default())
}

// ---- downloads ---------------------------------------------------------------

#[tauri::command]
async fn downloads_start(
    downloads: State<'_, DownloadsState>,
    video: Value,
    height: Option<i64>,
) -> Result<Value, String> {
    Ok(downloads.0.start(video, height))
}

#[tauri::command]
async fn downloads_cancel(
    downloads: State<'_, DownloadsState>,
    video_id: String,
) -> Result<Value, String> {
    let cancelled = downloads.0.cancel(&video_id);
    downloads.0.pump();
    Ok(json!({ "cancelled": cancelled }))
}

#[tauri::command]
async fn downloads_pause(
    downloads: State<'_, DownloadsState>,
    video_id: String,
) -> Result<Value, String> {
    let paused = downloads.0.pause(&video_id);
    // A freed slot is filled straight away, like the JavaScript manager.
    downloads.0.pump();
    Ok(json!({ "paused": paused }))
}

#[tauri::command]
async fn downloads_resume(
    downloads: State<'_, DownloadsState>,
    video_id: String,
) -> Result<Value, String> {
    let resumed = downloads.0.resume(&video_id);
    downloads.0.pump();
    Ok(json!({ "resumed": resumed }))
}

#[tauri::command]
async fn downloads_list(downloads: State<'_, DownloadsState>) -> Result<Value, String> {
    Ok(json!({ "tasks": downloads.0.list() }))
}

#[tauri::command]
async fn downloads_pause_all(downloads: State<'_, DownloadsState>) -> Result<Value, String> {
    downloads.0.pause_all();
    Ok(json!({ "tasks": downloads.0.list() }))
}

#[tauri::command]
async fn downloads_resume_all(downloads: State<'_, DownloadsState>) -> Result<Value, String> {
    downloads.0.resume_all();
    downloads.0.pump();
    Ok(json!({ "tasks": downloads.0.list() }))
}

/// Electron's `dialog.showOpenDialog`: the picked folder becomes the download
/// folder at once, and cancelling just reports the folder that is already set.
#[tauri::command]
async fn downloads_pick_dir(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
    downloads: tauri::State<'_, DownloadsState>,
) -> Result<Value, String> {
    use tauri_plugin_dialog::DialogExt;
    let current = state
        .settings
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .get("downloadDir")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let dialog_dir = current.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title("Choose a downloads folder")
            .set_directory(&dialog_dir)
            .blocking_pick_folder()
    })
    .await
    .map_err(|e| e.to_string())?;
    let Some(picked) = picked else {
        return Ok(json!({ "dir": current }));
    };
    let dir = PathBuf::from(picked.to_string());
    apply_download_dir(&state, &downloads.0, dir.clone());
    if let Ok(text) = serde_json::to_string_pretty(
        &state
            .settings
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone(),
    ) {
        let _ = std::fs::write(&state.settings_path, text);
    }
    Ok(json!({ "dir": dir.to_string_lossy().to_string() }))
}

#[tauri::command]
async fn downloads_reveal(file: String) -> Result<Value, String> {
    #[cfg(windows)]
    {
        let _ = std::process::Command::new("explorer")
            .arg("/select,")
            .arg(&file)
            .spawn();
    }
    #[cfg(not(windows))]
    {
        let _ = std::process::Command::new("xdg-open")
            .arg(
                std::path::Path::new(&file)
                    .parent()
                    .map(|p| p.to_path_buf())
                    .unwrap_or_else(|| std::path::PathBuf::from(&file)),
            )
            .spawn();
    }
    let _ = file;
    Ok(json!({}))
}

// ---- sync --------------------------------------------------------------------

#[tauri::command]
async fn sync_start(sync: State<'_, SyncState>) -> Result<Value, String> {
    sync.0.start()
}

#[tauri::command]
async fn sync_stop(sync: State<'_, SyncState>) -> Result<Value, String> {
    Ok(sync.0.stop())
}

#[tauri::command]
async fn sync_status(sync: State<'_, SyncState>) -> Result<Value, String> {
    Ok(sync.0.status())
}

// ---- window ------------------------------------------------------------------

#[tauri::command]
async fn win_fullscreen(
    window: tauri::WebviewWindow,
    app: tauri::AppHandle,
    on: bool,
) -> Result<Value, String> {
    window.set_fullscreen(on).map_err(|e| e.to_string())?;
    // The renderer's `onFullscreen(cb)` took a plain boolean under Electron.
    let _ = app.emit("win:fullscreen-changed", on);
    Ok(json!({ "fullscreen": on }))
}

#[tauri::command]
async fn win_is_fullscreen(window: tauri::WebviewWindow) -> Result<Value, String> {
    let fullscreen = window.is_fullscreen().unwrap_or(false);
    Ok(json!({ "fullscreen": fullscreen }))
}

/// Same mini-player geometry rules as the old electron/main.js: remember the
/// pre-mini bounds, drop fullscreen/maximize, always-on-top, shrink, and restore
/// exactly on the way back.
#[tauri::command]
async fn win_mini(
    window: tauri::WebviewWindow,
    state: State<'_, AppState>,
    on: bool,
) -> Result<Value, String> {
    let mut prev = state
        .mini_prev
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if on {
        let position = window.outer_position().unwrap_or(tauri::PhysicalPosition::new(0, 0));
        let size = window.outer_size().unwrap_or(tauri::PhysicalSize::new(1280, 820));
        *prev = Some(MiniPrev {
            x: position.x,
            y: position.y,
            width: size.width,
            height: size.height,
            maximized: window.is_maximized().unwrap_or(false),
            fullscreen: window.is_fullscreen().unwrap_or(false),
        });
        let _ = window.set_fullscreen(false);
        let _ = window.unmaximize();
        let _ = window.set_always_on_top(true);
        // A mini player must be able to shrink past the normal minimum size.
        let _ = window.set_min_size(Some(tauri::PhysicalSize::new(380, 240)));
        let _ = window.set_size(tauri::PhysicalSize::new(460, 300));
    } else {
        let _ = window.set_always_on_top(false);
        let _ = window.set_fullscreen(false);
        match prev.take() {
            Some(saved) => {
                let _ = window.set_size(tauri::PhysicalSize::new(saved.width, saved.height));
                let _ = window.set_position(tauri::PhysicalPosition::new(saved.x, saved.y));
                if saved.maximized {
                    let _ = window.maximize();
                }
            }
            None => {
                let _ = window.set_size(tauri::PhysicalSize::new(1280, 820));
            }
        }
        let _ = window.set_min_size(Some(tauri::PhysicalSize::new(940, 600)));
    }
    Ok(json!({ "mini": on }))
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
    let tube = new_tube(state);

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
    let proxy_url = format!("http://127.0.0.1:{}/v?t={token}", state.proxy_port);
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

fn main() {
    let dir = data_dir();
    let settings_path = settings_file(&dir);
    let library_path = dir.join("indiotube-library.json");
    let queue_path = queue_file(&dir);
    let streams_cache_path = dir.join("streams-cache.json");
    let home_mix_path = dir.join("home-mix.json");
    let _ = std::fs::create_dir_all(&dir);
    let default_downloads = download_dir();
    let settings = load_settings(&settings_path, &default_downloads.to_string_lossy());
    let settings_dir = downloads::download_dir_from_settings(&settings).unwrap_or(default_downloads);
    let download_dir = Arc::new(Mutex::new(settings_dir.clone()));

    let listener = TcpListener::bind("127.0.0.1:0").expect("bind media proxy");
    let proxy_port = listener.local_addr().expect("proxy address").port();
    let proxy = Arc::new(Proxy::with_shared_dir(
        UreqUpstream::new(),
        Arc::clone(&download_dir),
    ));
    let stop = Arc::new(AtomicBool::new(false));
    {
        let proxy = Arc::clone(&proxy);
        let stop = Arc::clone(&stop);
        std::thread::spawn(move || indio_core::proxy::serve(proxy, listener, stop));
    }

    let ytdlp = ytdlp::locate("yt-dlp", "INDIOTUBE_YTDLP");
    let ffmpeg = ytdlp::locate("ffmpeg", "INDIOTUBE_FFMPEG");
    if ytdlp.is_none() {
        eprintln!("[indio] yt-dlp.exe not found - run `npm run fetch:ytdlp`. Playback will report a helpful error.");
    }

    let state = AppState {
        tube: Mutex::new(None),
        locale: Mutex::new(("en".to_string(), "US".to_string())),
        proxy,
        proxy_port,
        streams: Mutex::new(HashMap::new()),
        streams_cache_path,
        streams_loaded: AtomicBool::new(false),
        ladder_flight: SingleFlight::new(),
        stream_misses: MissCache::new(),
        warm: Prefetch::default(),
        sb_cache: Mutex::new(HashMap::new()),
        ytdlp: ytdlp.clone(),
        ffmpeg: ffmpeg.clone(),
        settings_path,
        settings: Mutex::new(settings),
        download_dir,
        // Restores the last run's Home mix, so relaunching paints the feed
        // before YouTube has been asked anything.
        local_feed: Arc::new(LocalFeed::open(home_mix_path)),
        mini_prev: Mutex::new(None),
    };

    if let Ok(video) = std::env::var("INDIOTUBE_SELFTEST") {
        let code = selftest(&state, &video);
        std::process::exit(code);
    }

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            // A second launch just focuses the first window: two shells writing
            // the same library file would fight over it.
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_focus();
            }
        }))
        .manage(state)
        .setup(move |app| {
            let handle = app.handle().clone();
            let library = Arc::new(Library::open(
                library_path.clone(),
                Arc::new(emit_to(&handle, "lib:changed")),
            ));
            let downloads = Arc::new(Downloads::new(
                app.state::<AppState>().download_dir.clone(),
                ytdlp.unwrap_or_else(|| PathBuf::from("yt-dlp")),
                ffmpeg,
                queue_path.clone(),
                Arc::new(emit_to(&handle, "download:event")),
            ));
            let sync = Arc::new(SyncServer::new(
                Arc::clone(&library),
                Arc::new(emit_to(&handle, "sync:event")),
            ));
            app.manage(LibraryState(Arc::clone(&library)));
            app.manage(DownloadsState(Arc::clone(&downloads)));
            app.manage(SyncState(sync));
            // Pick up downloads that were queued when the app last closed.
            downloads.pump();
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
            }
            smoke::install(handle);
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
            commands::warm_streams,
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
            downloads_pause_all,
            downloads_resume_all,
            downloads_pick_dir,
            downloads_reveal,
            sync_start,
            sync_stop,
            sync_status,
            smoke::smoke_config,
            smoke::smoke_report,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the IndioTube shell");
}
