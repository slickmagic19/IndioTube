//! yt-dlp sidecar: locating the binaries and turning `--dump-single-json` into
//! the quality ladder the player uses. Same flags as electron/streams.js.

use std::io::Read;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use indio_core::quality::{build_qualities, QualityLadder};
use serde_json::Value;

/// Looks next to the executable first (packaged app), then in the repo's
/// `vendor/` directory (development), then on PATH.
pub fn locate(name: &str, env_var: &str) -> Option<PathBuf> {
    if let Ok(path) = std::env::var(env_var) {
        let candidate = PathBuf::from(path);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    let exe_name = if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    };
    let mut roots: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            roots.push(dir.to_path_buf());
            roots.push(dir.join("vendor"));
            // src-tauri/target/debug/IndioTube.exe -> apps/desktop/vendor
            if let Some(app_dir) = dir.ancestors().nth(3) {
                roots.push(app_dir.join("vendor"));
            }
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        roots.push(cwd.join("vendor"));
        roots.push(cwd.join("../vendor"));
    }
    let sub = if name == "yt-dlp" { "ytdlp" } else { "ffmpeg" };
    for root in roots {
        for candidate in [root.join(sub).join(&exe_name), root.join(&exe_name)] {
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    which(&exe_name)
}

fn which(exe: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join(exe))
        .find(|candidate| candidate.is_file())
}

pub fn ffmpeg_available(ffmpeg: Option<&PathBuf>) -> bool {
    ffmpeg.map(|p| p.is_file()).unwrap_or(false)
}

/// `--dump-single-json` with bounded retries, mirroring the JavaScript flags.
pub fn extract(ytdlp: &PathBuf, video_id: &str) -> Result<Value, String> {
    let mut command = Command::new(ytdlp);
    command
        .args([
            "--no-warnings",
            "--no-playlist",
            "--dump-single-json",
            "--skip-download",
            "--socket-timeout",
            "8",
            "--retries",
            "1",
            "--extractor-retries",
            "1",
            "--fragment-retries",
            "1",
            &format!("https://www.youtube.com/watch?v={video_id}"),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }

    let mut child = command
        .spawn()
        .map_err(|e| format!("could not start yt-dlp: {e}"))?;
    let stdout = child.stdout.take().ok_or("yt-dlp produced no output")?;
    // Read on a thread: the JSON is far larger than a pipe buffer, so waiting
    // for exit before reading would deadlock.
    let reader = std::thread::spawn(move || {
        let mut text = String::new();
        let _ = std::io::BufReader::new(stdout).read_to_string(&mut text);
        text
    });

    let deadline = Instant::now() + Duration::from_secs(45);
    let mut timed_out = false;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if Instant::now() > deadline {
                    timed_out = true;
                    let _ = child.kill();
                    break;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(e) => return Err(format!("yt-dlp failed: {e}")),
        }
    }
    let _ = child.wait();
    let text = reader.join().unwrap_or_default();
    if timed_out {
        return Err("yt-dlp timed out".to_string());
    }

    // Some builds print warnings before the JSON document.
    let brace = text.find('{').ok_or("yt-dlp returned no metadata")?;
    let info: Value = serde_json::from_str(&text[brace..])
        .map_err(|e| format!("could not read stream metadata ({e})"))?;
    let unavailable = info
        .get("description")
        .and_then(Value::as_str)
        .map(|d| d.contains("not available"))
        .unwrap_or(false);
    if info.get("errors").is_some() || unavailable {
        return Err("Video unavailable (private, deleted or region-blocked)".to_string());
    }
    Ok(info)
}

pub fn ladder(info: &Value) -> QualityLadder {
    build_qualities(info)
}
