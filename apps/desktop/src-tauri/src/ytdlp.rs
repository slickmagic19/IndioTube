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
            roots.extend(search_roots(dir));
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        roots.push(cwd.join("vendor"));
        roots.push(cwd.join("../vendor"));
    }
    let sub = if name == "yt-dlp" { "ytdlp" } else { "ffmpeg" };
    for root in roots {
        for candidate in [
            root.join(sub).join(&exe_name),
            root.join("ytdlp").join(&exe_name),
            root.join("ffmpeg").join(&exe_name),
            root.join(&exe_name),
        ] {
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    which(&exe_name)
}

/// Where the sidecars can be, given the directory the executable lives in.
///
/// The NSIS bundle puts `bundle.resources` under `_up_\vendor\<tool>\` next to
/// the installed exe, so a packaged app has to be looked at differently from a
/// `cargo run` out of `src-tauri/target/debug` — which is what found nothing
/// and made playback claim yt-dlp was missing on a machine that had it.
pub fn search_roots(exe_dir: &std::path::Path) -> Vec<PathBuf> {
    let mut roots = vec![
        exe_dir.to_path_buf(),
        exe_dir.join("vendor"),
        exe_dir.join("resources"),
        // Tauri's NSIS layout: `$INSTDIR\IndioTube.exe` and
        // `$INSTDIR\_up_\vendor\ytdlp\yt-dlp.exe`.
        exe_dir.join("_up_"),
        exe_dir.join("_up_").join("vendor"),
        exe_dir.join("_up_").join("resources"),
        exe_dir.join("resources").join("vendor"),
    ];
    // src-tauri/target/debug/IndioTube.exe -> apps/desktop/vendor
    if let Some(app_dir) = exe_dir.ancestors().nth(3) {
        roots.push(app_dir.join("vendor"));
    }
    roots
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_env_override_wins_when_the_file_exists() {
        let dir = std::env::temp_dir().join(format!("indio-locate-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let fake = dir.join("yt-dlp-here.exe");
        std::fs::write(&fake, b"").unwrap();
        std::env::set_var("INDIOTUBE_LOCATE_TEST", &fake);
        assert_eq!(
            locate("yt-dlp", "INDIOTUBE_LOCATE_TEST").as_deref(),
            Some(fake.as_path())
        );
        // A variable pointing at nothing falls back to the normal search.
        std::env::set_var("INDIOTUBE_LOCATE_TEST", dir.join("missing.exe"));
        assert!(locate("yt-dlp-definitely-not-installed", "INDIOTUBE_LOCATE_TEST").is_none());
        std::env::remove_var("INDIOTUBE_LOCATE_TEST");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_sidecar_is_reported_not_guessed() {
        assert!(locate("indiotube-no-such-tool", "INDIOTUBE_NO_SUCH_TOOL").is_none());
    }

    #[test]
    fn the_installed_bundle_is_searched_where_it_actually_puts_them() {
        // `%LOCALAPPDATA%\IndioTube\IndioTube.exe` with the resources under
        // `_up_\vendor\…` — the NSIS layout. Missing this is what made a
        // packaged build say yt-dlp was not installed.
        let installed = std::path::Path::new("C:/Users/me/AppData/Local/IndioTube");
        let roots = search_roots(installed);
        assert!(roots.contains(&installed.join("_up_").join("vendor")));
        assert!(roots.contains(&installed.join("resources")));
        // The yt-dlp candidate the bundle produces:
        let ytdlp = installed.join("_up_").join("vendor").join("ytdlp").join("yt-dlp.exe");
        assert!(roots.iter().any(|root| root.join("ytdlp").join("yt-dlp.exe") == ytdlp));
    }

    #[test]
    fn a_development_build_finds_the_repo_vendor_folder() {
        // src-tauri/target/debug/IndioTube.exe -> apps/desktop/vendor
        let debug = std::path::Path::new("C:/repo/apps/desktop/src-tauri/target/debug");
        let roots = search_roots(debug);
        assert!(roots.contains(
            &std::path::Path::new("C:/repo/apps/desktop")
                .join("vendor")
        ));
    }
}
