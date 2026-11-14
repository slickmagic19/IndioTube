//! Download queue: the Rust port of electron/downloads.js.
//!
//! Same state machine: concurrency 2, pause / resume / cancel per task and all
//! at once, bounded retries (`MAX_ATTEMPTS`, shared with indio-core), progress
//! with speed and ETA, and a queue file so tasks survive a restart. yt-dlp does
//! the transfer; this module owns the bookkeeping and pushes `download:event`
//! for the renderer.

use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use indio_core::downloads::{after_failure, parse_download_line, AfterFailure};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

pub const MAX_ACTIVE: usize = 2;
const EMIT_INTERVAL: Duration = Duration::from_millis(200);
const RETRY_DELAY_MS: u64 = 1500;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub video_id: String,
    pub video: Value,
    pub height: Option<i64>,
    pub status: String,
    pub percent: f64,
    #[serde(default)]
    pub received_bytes: u64,
    #[serde(default)]
    pub total_bytes: u64,
    #[serde(default)]
    pub speed: f64,
    pub eta: Option<u64>,
    #[serde(default)]
    pub file: String,
    #[serde(default)]
    pub bytes: u64,
    pub error: Option<String>,
    #[serde(default)]
    pub attempts: u32,
    pub at: i64,
}

impl Task {
    fn new(video: Value, height: Option<i64>) -> Self {
        let video_id = video
            .get("videoId")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        Self {
            video_id,
            video,
            height,
            status: "queued".to_string(),
            percent: 0.0,
            received_bytes: 0,
            total_bytes: 0,
            speed: 0.0,
            eta: None,
            file: String::new(),
            bytes: 0,
            error: None,
            attempts: 0,
            at: now_ms(),
        }
    }

    fn public(&self) -> Value {
        json!({
            "videoId": self.video_id,
            "video": self.video,
            "height": self.height,
            "status": self.status,
            "percent": (self.percent * 10.0).round() / 10.0,
            "receivedBytes": self.received_bytes,
            "totalBytes": self.total_bytes,
            "speed": self.speed.round() as i64,
            "eta": self.eta,
            "file": self.file,
            "bytes": self.bytes,
            "error": self.error,
            "attempts": self.attempts,
            "at": self.at,
        })
    }

    /// The queue file only keeps what a restart needs.
    fn persisted(&self) -> Value {
        json!({
            "videoId": self.video_id,
            "video": self.video,
            "height": self.height,
            "status": self.status,
            "percent": self.percent,
            "receivedBytes": self.received_bytes,
            "totalBytes": self.total_bytes,
            "file": self.file,
            "error": self.error,
            "attempts": self.attempts,
            "at": self.at,
        })
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

type SharedTask = Arc<Mutex<Task>>;
/// Where queue updates go; the shell forwards them as `download:event`.
pub type EventSink = Arc<dyn Fn(Value) + Send + Sync + 'static>;

pub struct Downloads {
    dir: Arc<Mutex<PathBuf>>,
    ytdlp: PathBuf,
    ffmpeg: Option<PathBuf>,
    queue_file: PathBuf,
    emit: EventSink,
    tasks: Mutex<HashMap<String, SharedTask>>,
    procs: Mutex<HashMap<String, Arc<Mutex<Child>>>>,
    loaded: Mutex<bool>,
}

impl Downloads {
    pub fn new(
        dir: Arc<Mutex<PathBuf>>,
        ytdlp: PathBuf,
        ffmpeg: Option<PathBuf>,
        queue_file: PathBuf,
        emit: EventSink,
    ) -> Self {
        Self {
            dir,
            ytdlp,
            ffmpeg,
            queue_file,
            emit,
            tasks: Mutex::new(HashMap::new()),
            procs: Mutex::new(HashMap::new()),
            loaded: Mutex::new(false),
        }
    }

    fn dir(&self) -> PathBuf {
        self.dir
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    pub fn set_dir(&self, dir: PathBuf) {
        let _ = std::fs::create_dir_all(&dir);
        *self
            .dir
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = dir;
    }

    fn ensure_loaded(&self) {
        let mut loaded = self
            .loaded
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if *loaded {
            return;
        }
        *loaded = true;
        let Ok(text) = std::fs::read_to_string(&self.queue_file) else {
            return;
        };
        let Ok(doc) = serde_json::from_str::<Value>(&text) else {
            return;
        };
        let Some(list) = doc.get("tasks").and_then(Value::as_array) else {
            return;
        };
        let mut tasks = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        for raw in list {
            let Some(video_id) = raw.get("videoId").and_then(Value::as_str) else {
                continue;
            };
            let video = raw
                .get("video")
                .cloned()
                .unwrap_or_else(|| json!({ "videoId": video_id, "title": video_id }));
            let status = raw
                .get("status")
                .and_then(Value::as_str)
                .unwrap_or("paused");
            tasks.insert(
                video_id.to_string(),
                Arc::new(Mutex::new(Task {
                    video_id: video_id.to_string(),
                    video,
                    height: raw.get("height").and_then(Value::as_i64),
                    // Interrupted mid-transfer: keep it, but let the viewer decide.
                    status: if status == "downloading" {
                        "paused".to_string()
                    } else {
                        status.to_string()
                    },
                    percent: raw.get("percent").and_then(Value::as_f64).unwrap_or(0.0),
                    received_bytes: raw
                        .get("receivedBytes")
                        .and_then(Value::as_u64)
                        .unwrap_or(0),
                    total_bytes: raw.get("totalBytes").and_then(Value::as_u64).unwrap_or(0),
                    speed: 0.0,
                    eta: None,
                    file: raw
                        .get("file")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_string(),
                    bytes: 0,
                    error: raw
                        .get("error")
                        .and_then(Value::as_str)
                        .map(str::to_string),
                    attempts: raw.get("attempts").and_then(Value::as_u64).unwrap_or(0) as u32,
                    at: raw.get("at").and_then(Value::as_i64).unwrap_or_else(now_ms),
                })),
            );
        }
    }

    fn save(&self) {
        let tasks = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let list: Vec<Value> = tasks
            .values()
            .filter_map(|t| t.lock().ok().map(|t| t.persisted()))
            .collect();
        let doc = json!({ "v": 1, "tasks": list });
        if let Ok(text) = serde_json::to_string(&doc) {
            let tmp = self.queue_file.with_extension("json.tmp");
            if std::fs::write(&tmp, text).is_ok() {
                let _ = std::fs::rename(&tmp, &self.queue_file);
            }
        }
    }

    fn send(&self, video_id: &str, status: &str, extra: Value) {
        let mut payload = json!({ "videoId": video_id, "status": status });
        if let (Some(base), Some(extra)) = (payload.as_object_mut(), extra.as_object()) {
            for (key, value) in extra {
                base.insert(key.clone(), value.clone());
            }
        }
        let _ = (self.emit)(payload);
    }

    fn active_count(&self) -> usize {
        self.tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .values()
            .filter(|t| {
                t.lock()
                    .map(|t| t.status == "downloading")
                    .unwrap_or(false)
            })
            .count()
    }

    pub fn pump(self: &Arc<Self>) {
        self.ensure_loaded();
        loop {
            if self.active_count() >= MAX_ACTIVE {
                return;
            }
            let next = {
                let tasks = self
                    .tasks
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                tasks
                    .values()
                    .find(|t| {
                        t.lock()
                            .map(|t| t.status == "queued")
                            .unwrap_or(false)
                    })
                    .cloned()
            };
            let Some(task) = next else { return };
            self.launch(task);
        }
    }

    fn launch(self: &Arc<Self>, task: SharedTask) {
        let video_id = match task.lock() {
            Ok(mut t) => {
                t.status = "downloading".to_string();
                t.error = None;
                t.speed = 0.0;
                t.eta = None;
                t.video_id.clone()
            }
            Err(_) => return,
        };
        self.save();
        let (percent, received, total, height) = {
            let t = match task.lock() {
                Ok(t) => t,
                Err(_) => return,
            };
            (t.percent, t.received_bytes, t.total_bytes, t.height)
        };
        self.send(
            &video_id,
            "started",
            json!({
                "height": height,
                "percent": percent,
                "receivedBytes": received,
                "totalBytes": total,
            }),
        );

        let ffmpeg = self
            .ffmpeg
            .as_ref()
            .map(|p| p.is_file())
            .unwrap_or(false);
        let h = height.filter(|h| *h > 0).unwrap_or(1080);
        let format = if ffmpeg {
            format!("bv*[height<={h}]+ba/b[height<={h}]/bv*+ba/b")
        } else {
            format!("b[height<={h}]/b")
        };
        let dir = self.dir();
        let _ = std::fs::create_dir_all(&dir);
        let output = dir.join("%(title)s [%(id)s].%(ext)s");

        let mut command = Command::new(&self.ytdlp);
        command.args([
            "--no-warnings",
            "--no-playlist",
            "--newline",
            "--progress",
            "--retries",
            "10",
            "--fragment-retries",
            "10",
            "--continue",
            "-f",
            &format,
        ]);
        if ffmpeg {
            command.args(["--merge-output-format", "mp4"]);
            if let Some(ffmpeg_path) = &self.ffmpeg {
                if let Some(parent) = ffmpeg_path.parent() {
                    command.arg("--ffmpeg-location").arg(parent);
                }
            }
        }
        command
            .arg("-o")
            .arg(&output)
            .arg(format!("https://www.youtube.com/watch?v={video_id}"))
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                self.finish_failure(&task, format!("yt-dlp failed to start: {e}"));
                return;
            }
        };
        let stdout = child.stdout.take();
        let child = Arc::new(Mutex::new(child));
        self.procs
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(video_id.clone(), Arc::clone(&child));

        let manager = Arc::clone(self);
        let task_for_thread = Arc::clone(&task);
        std::thread::spawn(move || {
            let mut last_emit = Instant::now() - EMIT_INTERVAL;
            if let Some(stdout) = stdout {
                let reader = BufReader::new(stdout);
                for line in reader.lines().map_while(Result::ok) {
                    let line = line.trim().to_string();
                    if line.is_empty() {
                        continue;
                    }
                    let mut t = match task_for_thread.lock() {
                        Ok(t) => t,
                        Err(_) => break,
                    };
                    if t.status != "downloading" {
                        break;
                    }
                    if let Some(rest) = line.split("[download] Destination: ").nth(1) {
                        t.file = rest.trim().to_string();
                    }
                    if let Some(rest) = line.split("[Merger] Merging files into ").nth(1) {
                        t.file = rest.trim().to_string();
                    }
                    if line.contains("has already been downloaded") {
                        if let Some(rest) = line.split("Destination: ").nth(1) {
                            t.file = rest.trim().to_string();
                        }
                    }
                    let Some(progress) = parse_download_line(&line) else {
                        continue;
                    };
                    t.percent = progress.percent;
                    if let Some(total) = progress.total_bytes.filter(|b| *b > 0) {
                        t.total_bytes = total;
                    }
                    if t.total_bytes > 0 {
                        t.received_bytes = (progress.percent / 100.0 * t.total_bytes as f64).round() as u64;
                    }
                    if let Some(speed) = progress.speed.filter(|s| *s > 0) {
                        t.speed = speed as f64;
                    }
                    if let Some(eta) = progress.eta {
                        t.eta = Some(eta as u64);
                    }
                    let emit = last_emit.elapsed() >= EMIT_INTERVAL;
                    let payload = json!({
                        "percent": (t.percent * 10.0).round() / 10.0,
                        "receivedBytes": t.received_bytes,
                        "totalBytes": t.total_bytes,
                        "speed": t.speed.round() as i64,
                        "eta": t.eta,
                    });
                    drop(t);
                    if emit {
                        last_emit = Instant::now();
                        manager.send(&video_id, "progress", payload);
                    }
                }
            }
            let code = child
                .lock()
                .map(|mut c| c.wait().ok().and_then(|s| s.code()))
                .ok()
                .flatten();
            manager.procs
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .remove(&video_id);
            manager.finish(&task_for_thread, code);
        });
    }

    fn finish(self: &Arc<Self>, task: &SharedTask, code: Option<i32>) {
        let (video_id, status) = match task.lock() {
            Ok(t) => (t.video_id.clone(), t.status.clone()),
            Err(_) => return,
        };
        if status == "paused" || status == "cancelled" {
            return;
        }
        if code == Some(0) {
            let resolved = self.resolve_output(&video_id);
            // A split stream left on disk is not a download: without the merge
            // step it is half a video.
            if let Some(path) = &resolved {
                let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
                if is_split_part(name) {
                    self.finish_failure(task, "This quality needs ffmpeg to merge video and audio".to_string());
                    return;
                }
            }
            if let Some(path) = resolved.filter(|p| p.is_file()) {
                let bytes = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
                let file = path.to_string_lossy().to_string();
                {
                    let mut t = match task.lock() {
                        Ok(t) => t,
                        Err(_) => return,
                    };
                    t.status = "done".to_string();
                    t.file = file.clone();
                    t.bytes = bytes;
                    t.percent = 100.0;
                    t.eta = None;
                    t.speed = 0.0;
                }
                self.send(
                    &video_id,
                    "done",
                    json!({ "file": file, "bytes": bytes, "height": task.lock().ok().and_then(|t| t.height) }),
                );
                self.tasks
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner())
                    .remove(&video_id);
                self.save();
                self.pump();
                return;
            }
            self.finish_failure(task, "Download finished but the file was not found".to_string());
            return;
        }
        let message = match code {
            Some(code) => format!("Download failed (yt-dlp exit {code})"),
            None => "Download failed".to_string(),
        };
        self.finish_failure(task, message);
    }

    fn finish_failure(self: &Arc<Self>, task: &SharedTask, message: String) {
        let (video_id, attempts) = match task.lock() {
            Ok(mut t) => {
                t.attempts += 1;
                if after_failure(t.attempts) == AfterFailure::Fail {
                    t.status = "error".to_string();
                    t.error = Some(message.clone());
                    t.speed = 0.0;
                    t.eta = None;
                    (t.video_id.clone(), t.attempts)
                } else {
                    t.status = "queued".to_string();
                    t.error = None;
                    t.speed = 0.0;
                    (t.video_id.clone(), t.attempts)
                }
            }
            Err(_) => return,
        };
        self.save();
        if after_failure(attempts) == AfterFailure::Fail {
            self.send(&video_id, "error", json!({ "error": message }));
            self.pump();
            return;
        }
        self.send(&video_id, "queued", json!({ "attempts": attempts }));
        let manager = Arc::clone(self);
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(RETRY_DELAY_MS * attempts as u64));
            manager.pump();
        });
    }

    /// The file a finished task produced: yt-dlp's last "Destination:" is the
    /// split stream, so the merged file is found on disk instead.
    fn resolve_output(&self, video_id: &str) -> Option<PathBuf> {
        let dir = self.dir();
        let needle = format!("[{video_id}]");
        let mut merged: Vec<PathBuf> = std::fs::read_dir(&dir)
            .ok()?
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| {
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
                name.contains(&needle) && !name.ends_with(".part") && !is_split_part(name)
            })
            .collect();
        merged.sort();
        merged.pop()
    }

    pub fn start(self: &Arc<Self>, video: Value, height: Option<i64>) -> Value {
        self.ensure_loaded();
        let Some(video_id) = video
            .get("videoId")
            .and_then(Value::as_str)
            .map(str::to_string)
        else {
            return json!({ "started": false, "reason": "no video" });
        };
        let existing = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(&video_id)
            .cloned();
        if let Some(existing) = &existing {
            let status = existing
                .lock()
                .map(|t| t.status.clone())
                .unwrap_or_default();
            if status == "queued" || status == "downloading" {
                return json!({ "started": false, "reason": "already downloading" });
            }
            if status == "paused" {
                if let Ok(mut t) = existing.lock() {
                    t.status = "queued".to_string();
                }
                self.save();
                self.send(&video_id, "resumed", json!({}));
                self.pump();
                return json!({ "started": true });
            }
        }
        let task = Arc::new(Mutex::new(Task::new(video, height)));
        self.tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(video_id, Arc::clone(&task));
        self.save();
        self.pump();
        json!({ "started": true })
    }

    pub fn pause(self: &Arc<Self>, video_id: &str) -> bool {
        self.ensure_loaded();
        let task = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(video_id)
            .cloned();
        let Some(task) = task else { return false };
        {
            let mut t = match task.lock() {
                Ok(t) => t,
                Err(_) => return false,
            };
            if t.status != "downloading" {
                return false;
            }
            t.status = "paused".to_string();
            t.speed = 0.0;
            t.eta = None;
        }
        if let Some(proc) = self
            .procs
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(video_id)
        {
            if let Ok(mut child) = proc.lock() {
                let _ = child.kill();
            }
        }
        self.save();
        if let Ok(t) = task.lock() {
            self.send(
                video_id,
                "paused",
                json!({
                    "percent": t.percent,
                    "receivedBytes": t.received_bytes,
                    "totalBytes": t.total_bytes,
                }),
            );
        }
        true
    }

    pub fn resume(self: &Arc<Self>, video_id: &str) -> bool {
        self.ensure_loaded();
        let task = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(video_id)
            .cloned();
        let Some(task) = task else { return false };
        let mut t = match task.lock() {
            Ok(t) => t,
            Err(_) => return false,
        };
        if t.status == "downloading" || t.status == "queued" {
            return true;
        }
        t.status = "queued".to_string();
        t.error = None;
        t.attempts = 0;
        let percent = t.percent;
        drop(t);
        self.save();
        self.send(video_id, "resumed", json!({ "percent": percent }));
        true
    }

    pub fn cancel(self: &Arc<Self>, video_id: &str) -> bool {
        self.ensure_loaded();
        let task = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(video_id)
            .cloned();
        let Some(task) = task else { return false };
        if let Ok(mut t) = task.lock() {
            t.status = "cancelled".to_string();
        }
        if let Some(proc) = self
            .procs
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(video_id)
        {
            if let Ok(mut child) = proc.lock() {
                let _ = child.kill();
            }
        }
        self.tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(video_id);
        self.save();
        self.send(video_id, "cancelled", json!({}));
        if let Ok(entries) = std::fs::read_dir(self.dir()) {
            let needle = format!("[{video_id}]");
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if name.contains(&needle) {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        true
    }

    pub fn pause_all(self: &Arc<Self>) {
        let ids: Vec<String> = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .keys()
            .cloned()
            .collect();
        for id in ids {
            self.pause(&id);
        }
    }

    pub fn resume_all(self: &Arc<Self>) {
        let ids: Vec<String> = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .iter()
            .filter_map(|(id, task)| {
                task.lock()
                    .ok()
                    .filter(|t| t.status == "paused" || t.status == "error")
                    .map(|_| id.clone())
            })
            .collect();
        for id in ids {
            self.resume(&id);
        }
    }

    pub fn list(&self) -> Vec<Value> {
        self.ensure_loaded();
        let mut tasks: Vec<Value> = self
            .tasks
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .values()
            .filter_map(|t| t.lock().ok().map(|t| t.public()))
            .collect();
        tasks.sort_by_key(|t| t.get("at").and_then(Value::as_i64).unwrap_or(0));
        tasks
    }
}

fn is_split_part(name: &str) -> bool {
    let lower = name.to_lowercase();
    let Some(dot_f) = lower.rfind(".f") else {
        return false;
    };
    let rest = &lower[dot_f + 2..];
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    !digits.is_empty() && rest[digits.len()..].starts_with('.')
}

pub fn download_dir_from_settings(settings: &Value) -> Option<PathBuf> {
    settings
        .get("downloadDir")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use indio_core::downloads::MAX_ATTEMPTS;

    struct Tmp {
        root: PathBuf,
    }

    impl Tmp {
        fn new(tag: &str) -> Self {
            let root = std::env::temp_dir().join(format!(
                "indio-downloads-{}-{}",
                tag,
                now_ms()
            ));
            let _ = std::fs::create_dir_all(&root);
            Self { root }
        }

        fn manager(self: &Self, queue: Value) -> (Arc<Downloads>, Arc<Mutex<Vec<Value>>>) {
            let queue_file = self.root.join("downloads.json");
            if !queue.is_null() {
                std::fs::write(&queue_file, queue.to_string()).unwrap();
            }
            let events = Arc::new(Mutex::new(Vec::new()));
            let sink_events = Arc::clone(&events);
            let downloads = Arc::new(Downloads::new(
                Arc::new(Mutex::new(self.root.join("files"))),
                self.root.join("yt-dlp-not-installed.exe"),
                None,
                queue_file,
                Arc::new(move |event: Value| sink_events.lock().unwrap().push(event)),
            ));
            (downloads, events)
        }
    }

    impl Drop for Tmp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    fn video(id: &str) -> Value {
        json!({ "videoId": id, "title": format!("T-{id}"), "durationSec": 60 })
    }

    fn task(video_id: &str, status: &str, at: i64) -> Value {
        json!({
            "videoId": video_id,
            "video": video(video_id),
            "height": 720,
            "status": status,
            "percent": 42.5,
            "receivedBytes": 4_000_000,
            "totalBytes": 10_000_000,
            "file": format!("C:\\Downloads\\IndioTube\\X [{video_id}].f399.mp4"),
            "error": null,
            "attempts": 1,
            "at": at,
        })
    }

    #[test]
    fn a_requeued_task_is_written_back_to_the_queue_file() {
        let tmp = Tmp::new("restart");
        let queue = json!({ "v": 1, "tasks": [task("v1", "paused", 1)] });
        {
            let (downloads, _events) = tmp.manager(queue);
            // `resume` re-arms a task without pumping, so this stays exact.
            assert!(downloads.resume("v1"));
        }
        let doc: Value =
            serde_json::from_str(&std::fs::read_to_string(tmp.root.join("downloads.json")).unwrap())
                .unwrap();
        assert_eq!(doc["v"], 1);
        let tasks = doc["tasks"].as_array().unwrap();
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0]["videoId"], "v1");
        assert_eq!(tasks[0]["status"], "queued");
    }

    #[test]
    fn the_queue_file_is_loaded_once_and_sorted_oldest_first() {
        let tmp = Tmp::new("load");
        let queue = json!({
            "v": 1,
            "tasks": [task("newer", "paused", 200), task("older", "error", 100)],
        });
        let (downloads, _events) = tmp.manager(queue);
        let list = downloads.list();
        assert_eq!(
            list.iter()
                .map(|t| t["videoId"].as_str().unwrap())
                .collect::<Vec<_>>(),
            vec!["older", "newer"]
        );
        assert_eq!(list[0]["percent"], 42.5);
        assert_eq!(list[0]["receivedBytes"], 4_000_000);
        assert_eq!(list[0]["totalBytes"], 10_000_000);
    }

    #[test]
    fn a_transfer_interrupted_by_a_shutdown_resumes_as_paused() {
        let tmp = Tmp::new("interrupted");
        let (downloads, _events) =
            tmp.manager(json!({ "v": 1, "tasks": [task("x", "downloading", 1)] }));
        assert_eq!(downloads.list()[0]["status"], "paused");
    }

    #[test]
    fn a_corrupt_queue_file_just_starts_empty() {
        let tmp = Tmp::new("corrupt");
        std::fs::write(tmp.root.join("downloads.json"), "{ not json").unwrap();
        let (downloads, _events) = tmp.manager(Value::Null);
        assert!(downloads.list().is_empty());
    }

    #[test]
    fn cancelling_clears_the_task_and_its_partial_files() {
        let tmp = Tmp::new("cancel");
        let files = tmp.root.join("files");
        std::fs::create_dir_all(&files).unwrap();
        let partial = files.join("Video [v1].f399.mp4.part");
        let done = files.join("Other [v2].mp4");
        std::fs::write(&partial, b"x").unwrap();
        std::fs::write(&done, b"y").unwrap();

        let queue = json!({ "v": 1, "tasks": [task("v1", "paused", 1)] });
        let (downloads, events) = tmp.manager(queue);
        assert!(downloads.cancel("v1"));
        assert!(downloads.list().is_empty());
        assert!(!partial.exists(), "the partial file must be cleaned up");
        assert!(done.exists(), "other downloads are left alone");
        assert_eq!(events.lock().unwrap()[0]["status"], "cancelled");
        assert_eq!(events.lock().unwrap()[0]["videoId"], "v1");
        assert!(!downloads.cancel("missing"));
    }

    #[test]
    fn pause_only_takes_a_running_task_and_resume_requeues_it() {
        let tmp = Tmp::new("pause");
        let queue = json!({
            "v": 1,
            "tasks": [task("paused1", "paused", 1), task("broken", "error", 2)],
        });
        let (downloads, events) = tmp.manager(queue);
        // Nothing is transferring, so pausing is a no-op the UI can ignore.
        assert!(!downloads.pause("paused1"));
        assert!(!downloads.pause("nope"));

        assert!(downloads.resume("broken"));
        let list = downloads.list();
        assert_eq!(list[1]["status"], "queued");
        assert_eq!(list[1]["attempts"], 0);
        assert!(list[1]["error"].is_null());
        drop(list);
        assert_eq!(events.lock().unwrap().last().unwrap()["status"], "resumed");

        downloads.pause_all();
        downloads.resume_all();
        let list = downloads.list();
        let statuses: Vec<&str> = list
            .iter()
            .map(|t| t["status"].as_str().unwrap())
            .collect();
        assert_eq!(statuses, vec!["queued", "queued"]);
    }

    #[test]
    fn starting_the_same_video_twice_does_not_double_up() {
        let tmp = Tmp::new("twice");
        let queue = json!({ "v": 1, "tasks": [task("v1", "queued", 1)] });
        let (downloads, _events) = tmp.manager(queue);
        let again = downloads.start(video("v1"), Some(720));
        assert_eq!(again["started"], false);
        assert_eq!(again["reason"], "already downloading");
        assert_eq!(downloads.list().len(), 1);

        let missing = downloads.start(json!({}), None);
        assert_eq!(missing["started"], false);
        assert_eq!(missing["reason"], "no video");
    }

    #[test]
    fn split_stream_files_are_recognised() {
        assert!(is_split_part("Video [abc].f399.mp4"));
        assert!(is_split_part("Video [abc].f251.webm"));
        assert!(!is_split_part("Video [abc].mp4"));
        assert!(!is_split_part("f1.mp4"));
    }

    #[test]
    fn a_task_retries_up_to_the_limit_then_fails() {
        // The policy lives in indio-core; this pins the shell's constant to it
        // so a queue cannot retry forever (Electron used the same bound).
        assert_eq!(MAX_ACTIVE, 2);
        assert_eq!(after_failure(1), AfterFailure::Retry);
        assert_eq!(after_failure(MAX_ATTEMPTS - 1), AfterFailure::Retry);
        assert_eq!(after_failure(MAX_ATTEMPTS), AfterFailure::Fail);
        assert_eq!(after_failure(MAX_ATTEMPTS + 4), AfterFailure::Fail);
    }

    #[test]
    fn the_download_folder_follows_the_settings() {
        let dir = download_dir_from_settings(&json!({ "downloadDir": "D:\\Media" })).unwrap();
        assert_eq!(dir, PathBuf::from("D:\\Media"));
        assert!(download_dir_from_settings(&json!({ "downloadDir": "" })).is_none());
        assert!(download_dir_from_settings(&json!({})).is_none());
    }

    /// A real download, end to end: yt-dlp, progress events, the merged file.
    /// Left out of the normal run because it talks to YouTube and takes about a
    /// minute; run it by hand with
    /// `cargo test -- --ignored downloads_a_real_video`.
    #[test]
    #[ignore = "hits YouTube and yt-dlp; run explicitly"]
    fn downloads_a_real_video_end_to_end() {
        let ytdlp = crate::ytdlp::locate("yt-dlp", "INDIOTUBE_YTDLP")
            .expect("no yt-dlp — run `npm run fetch:ytdlp` first");
        let ffmpeg = crate::ytdlp::locate("ffmpeg", "INDIOTUBE_FFMPEG");
        let root = std::env::temp_dir().join(format!("indio-download-live-{}", now_ms()));
        let files = root.join("files");
        std::fs::create_dir_all(&files).unwrap();

        let events: Arc<Mutex<Vec<Value>>> = Arc::new(Mutex::new(Vec::new()));
        let sink_events = Arc::clone(&events);
        let downloads = Arc::new(Downloads::new(
            Arc::new(Mutex::new(files.clone())),
            ytdlp,
            ffmpeg,
            root.join("downloads.json"),
            Arc::new(move |event: Value| sink_events.lock().unwrap().push(event)),
        ));

        let video_id = "dQw4w9WgXcQ";
        let started = downloads.start(
            json!({ "videoId": video_id, "title": "Never Gonna Give You Up", "durationSec": 213 }),
            Some(360),
        );
        assert_eq!(started["started"], true, "{started}");

        // Up to three minutes; a 360p rip of a 3½-minute video is far under it.
        let mut finished = None;
        for _ in 0..360 {
            std::thread::sleep(Duration::from_millis(500));
            let seen = events.lock().unwrap().clone();
            if let Some(done) = seen.iter().find(|e| e["status"] == "done") {
                finished = Some(done.clone());
                break;
            }
            if let Some(error) = seen.iter().find(|e| e["status"] == "error") {
                finished = Some(error.clone());
                break;
            }
        }
        let report = finished.expect("the download reported an end state");
        let statuses: Vec<Value> = events.lock().unwrap().clone();
        let seen: Vec<&str> = statuses
            .iter()
            .filter_map(|e| e["status"].as_str())
            .collect();
        assert!(seen.contains(&"started"), "{seen:?}");
        assert!(seen.contains(&"progress"), "no progress events: {seen:?}");
        assert_eq!(report["status"], "done", "{report}");

        let file = PathBuf::from(report["file"].as_str().unwrap());
        assert!(file.is_file(), "{} never appeared", file.display());
        assert!(report["bytes"].as_u64().unwrap() > 100_000, "{report}");
        // A finished task leaves the queue behind and lands in the library
        // through the `done` event instead.
        assert!(downloads.list().is_empty());

        let _ = std::fs::remove_dir_all(&root);
    }
}
