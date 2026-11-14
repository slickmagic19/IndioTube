//! The dev-only smoke harness, ported from the Electron main process.
//!
//! `INDIOTUBE_SMOKE=1` boots the real UI, lets the renderer report what it
//! actually painted (plus any window errors it caught), and exits. The report
//! arrives through `smoke_report` because only the page can see its own DOM;
//! the shell's job is to hand the page its instructions (`smoke_config`), print
//! the report on stdout, and quit with a usable exit code.
//!
//! ```text
//! INDIOTUBE_SMOKE=1 INDIOTUBE_SMOKE_ROUTE=watch/dQw4w9WgXcQ IndioTube.exe
//! ```
//!
//! Optional: `INDIOTUBE_SMOKE_MEDIA=1` (probe the video/audio clocks),
//! `INDIOTUBE_SMOKE_WINDOW=1` (prove the mini-player and fullscreen commands),
//! `INDIOTUBE_SMOKE_CLICK=1` (click the first card once the feed appears, which
//! is how the load timings are read), `INDIOTUBE_SMOKE_WAIT=<ms>` (how long to
//! wait before reporting).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use serde_json::{json, Value};
use tauri::{AppHandle};

static SMOKE: OnceLock<Arc<Smoke>> = OnceLock::new();

struct Smoke {
    reported: AtomicBool,
}

/// The harness is a debug-build tool: a packaged app never arms it, exactly
/// like the Electron version's `!app.isPackaged` gate.
pub fn enabled() -> bool {
    cfg!(debug_assertions) && std::env::var("INDIOTUBE_SMOKE").as_deref() == Ok("1")
}

fn env_number(name: &str, default: u64) -> u64 {
    std::env::var(name)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

/// Called from `setup`, so the watchdog only starts once a window exists.
pub fn install(app: AppHandle) {
    if !enabled() {
        return;
    }
    let smoke = Arc::new(Smoke {
        reported: AtomicBool::new(false),
    });
    let _ = SMOKE.set(Arc::clone(&smoke));
    println!("[smoke] armed; waiting for the renderer's report");
    let watch_app = app.clone();
    let budget_ms = env_number("INDIOTUBE_SMOKE_WAIT", 8_000) + 45_000;
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(budget_ms));
        if !smoke.reported.load(Ordering::SeqCst) {
            println!("[smoke-fail] the renderer never reported back");
            let _ = watch_app;
            std::process::exit(1);
        }
    });
}

#[tauri::command]
pub fn smoke_config() -> Value {
    let config = json!({
        "enabled": enabled(),
        "route": std::env::var("INDIOTUBE_SMOKE_ROUTE").unwrap_or_default(),
        "media": std::env::var("INDIOTUBE_SMOKE_MEDIA").as_deref() == Ok("1"),
        "window": std::env::var("INDIOTUBE_SMOKE_WINDOW").as_deref() == Ok("1"),
        "click": std::env::var("INDIOTUBE_SMOKE_CLICK").as_deref() == Ok("1"),
        "waitMs": env_number("INDIOTUBE_SMOKE_WAIT", 8_000),
    });
    println!("[smoke] config -> {config}");
    config
}

/// The exit code the harness should leave behind: a report with renderer
/// errors is a failed run, whatever the window did.
pub fn report_exit_code(report: &Value) -> i32 {
    let errors = report
        .get("errors")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    if errors.is_empty() {
        0
    } else {
        1
    }
}

#[tauri::command]
pub fn smoke_report(app: AppHandle, report: Value) -> Value {
    if let Some(smoke) = SMOKE.get() {
        smoke.reported.store(true, Ordering::SeqCst);
    }
    println!("[smoke] {report}");
    let code = report_exit_code(&report);
    // The library, settings and download queue are written synchronously on
    // every change, so nothing is lost by leaving straight away. `App::exit`
    // is not used here because the harness must always take the process with
    // it, including any other instance the dev server kept open.
    let _ = app;
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(400));
        std::process::exit(code);
    });
    json!({ "ok": true })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_clean_report_exits_zero_and_an_error_report_does_not() {
        assert_eq!(report_exit_code(&json!({ "errors": [] })), 0);
        assert_eq!(report_exit_code(&json!({})), 0);
        assert_eq!(
            report_exit_code(&json!({ "errors": ["Uncaught TypeError"] })),
            1
        );
    }

    #[test]
    fn the_wait_budget_falls_back_to_the_default() {
        let name = "INDIOTUBE_SMOKE_TEST_BUDGET";
        std::env::remove_var(name);
        assert_eq!(env_number(name, 8_000), 8_000);
        std::env::set_var(name, "not-a-number");
        assert_eq!(env_number(name, 8_000), 8_000);
        std::env::set_var(name, "1500");
        assert_eq!(env_number(name, 8_000), 1500);
        std::env::remove_var(name);
    }
}
