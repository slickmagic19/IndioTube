//! Media proxy: the Rust port of the 127.0.0.1 server in electron/streams.js.
//!
//! The renderer never talks to a CDN directly. It asks this proxy for opaque
//! `/v?t=…` tokens, `/r?t=…&s=…&e=…` byte windows (the range lives in the query
//! because a Range header is not CORS-safelisted), `/subs?t=…` captions and
//! `/file?p=…` for downloaded files. Responses carry the CORS headers the media
//! element needs, and repeated range requests are served from a bounded cache.
//!
//! Upstream HTTP is behind the [`Upstream`] trait so routing, caching, range
//! handling and caption conversion are all testable without a network.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::range::ByteRange;

pub const CACHE_MAX_BYTES: usize = 96 * 1024 * 1024;
pub const CACHE_MAX_ENTRY: usize = 24 * 1024 * 1024;
pub const TOKENS_MAX: usize = 400;

#[derive(Debug, Clone, PartialEq)]
pub struct TokenEntry {
    pub url: String,
    pub video_id: Option<String>,
    /// "video" or "audio", used to re-extract after a CDN URL expires.
    pub role: Option<String>,
    pub height: Option<i64>,
    pub at: Instant,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CachedResponse {
    pub status: u16,
    pub content_type: String,
    pub content_range: Option<String>,
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct UpstreamResponse {
    pub status: u16,
    pub content_type: String,
    pub content_range: Option<String>,
    pub body: Vec<u8>,
}

impl UpstreamResponse {
    pub fn ok(body: Vec<u8>, content_type: &str) -> Self {
        Self {
            status: 200,
            content_type: content_type.to_string(),
            content_range: None,
            body,
        }
    }

    pub fn partial(range: &ByteRange, total: u64, body: Vec<u8>, content_type: &str) -> Self {
        Self {
            status: 206,
            content_type: content_type.to_string(),
            content_range: Some(format!("bytes {}-{}/{}", range.start, range.end, total)),
            body,
        }
    }
}

pub trait Upstream {
    fn get(&self, url: &str, range: Option<&str>) -> Result<UpstreamResponse, String>;
}

#[derive(Debug, Clone, PartialEq)]
pub struct Request {
    pub method: String,
    pub path: String,
    /// Raw query string, e.g. `t=abc&s=0&e=1023`.
    pub query: String,
    pub range_header: Option<String>,
}

impl Request {
    pub fn get(target: &str) -> Self {
        let (path, query) = match target.split_once('?') {
            Some((p, q)) => (p.to_string(), q.to_string()),
            None => (target.to_string(), String::new()),
        };
        Self {
            method: "GET".to_string(),
            path,
            query,
            range_header: None,
        }
    }

    fn param(&self, key: &str) -> Option<String> {
        self.query.split('&').find_map(|pair| {
            let (k, v) = pair.split_once('=')?;
            if k == key {
                Some(percent_decode(v))
            } else {
                None
            }
        })
    }
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                match u8::from_str_radix(hex, 16) {
                    Ok(b) => {
                        out.push(b);
                        i += 3;
                    }
                    Err(_) => {
                        out.push(bytes[i]);
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).to_string()
}

/// A body that lives on disk: served straight from the file so a 2 GB download
/// never has to fit in memory to be played back.
#[derive(Debug, Clone, PartialEq)]
pub struct FileBody {
    pub path: PathBuf,
    pub start: u64,
    pub len: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    pub file: Option<FileBody>,
}

impl Response {
    fn new(status: u16, body: &str) -> Self {
        Self {
            status,
            headers: cors(),
            body: body.as_bytes().to_vec(),
            file: None,
        }
    }

    fn with(mut self, key: &str, value: &str) -> Self {
        self.headers.push((key.to_string(), value.to_string()));
        self
    }

    pub fn header(&self, key: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    fn status_text(&self) -> &'static str {
        match self.status {
            200 => "OK",
            204 => "No Content",
            206 => "Partial Content",
            403 => "Forbidden",
            404 => "Not Found",
            416 => "Range Not Satisfiable",
            500 => "Internal Server Error",
            502 => "Bad Gateway",
            _ => "OK",
        }
    }
}

fn cors() -> Vec<(String, String)> {
    vec![
        ("access-control-allow-origin".to_string(), "*".to_string()),
        ("access-control-allow-headers".to_string(), "range".to_string()),
    ]
}

fn content_type_for(file: &str) -> &'static str {
    let ext = file.rsplit('.').next().unwrap_or("").to_lowercase();
    match ext.as_str() {
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        "mkv" => "video/x-matroska",
        "m4a" => "audio/mp4",
        "vtt" => "text/vtt; charset=utf-8",
        _ => "application/octet-stream",
    }
}

// ---- captions ----------------------------------------------------------------

/// YouTube's json3 caption format -> WebVTT.
pub fn json3_to_vtt(text: &str) -> Option<String> {
    let doc: serde_json::Value = serde_json::from_str(text).ok()?;
    let events = doc.get("events")?.as_array()?;
    let mut lines = vec!["WEBVTT".to_string(), String::new()];
    let mut index = 0;
    for ev in events {
        let segs = ev
            .get("segs")
            .or_else(|| ev.get("seg"))
            .and_then(|s| s.as_array());
        let Some(segs) = segs else { continue };
        let joined: String = segs
            .iter()
            .map(|s| s.get("utf8").and_then(|v| v.as_str()).unwrap_or("").replace('\n', " "))
            .collect::<String>();
        let txt = joined.trim();
        if txt.is_empty() {
            continue;
        }
        let start = ev.get("tStartMs").and_then(|v| v.as_f64()).unwrap_or(0.0);
        let dur = ev.get("dDurationMs").and_then(|v| v.as_f64()).unwrap_or(0.0);
        index += 1;
        lines.push(index.to_string());
        lines.push(format!("{} --> {}", clock(start), clock(start + dur)));
        lines.push(txt.to_string());
        lines.push(String::new());
    }
    if lines.len() > 2 {
        Some(lines.join("\n"))
    } else {
        None
    }
}

/// Only the common `<p begin="…" end="…">` TTML form, best effort.
pub fn ttml_to_vtt(text: &str) -> Option<String> {
    let re = regex::Regex::new(
        r#"(?s)<p\b[^>]*\bbegin="([\d:.]+)"[^>]*\bend="([\d:.]+)"[^>]*>(.*?)</p>"#,
    )
    .unwrap();
    let tag = regex::Regex::new(r"<[^>]+>").unwrap();
    let br = regex::Regex::new(r"(?i)<br\s*/?>").unwrap();
    let mut lines = vec!["WEBVTT".to_string(), String::new()];
    let mut index = 0;
    for caps in re.captures_iter(text) {
        let raw = br.replace_all(&caps[3], "\n").to_string();
        let txt = tag.replace_all(&raw, "").trim().to_string();
        if txt.is_empty() {
            continue;
        }
        index += 1;
        lines.push(index.to_string());
        lines.push(format!("{} --> {}", ttml_clock(&caps[1]), ttml_clock(&caps[2])));
        lines.push(txt);
        lines.push(String::new());
    }
    if index > 0 {
        Some(lines.join("\n"))
    } else {
        None
    }
}

fn clock(ms: f64) -> String {
    let clamped = ms.max(0.0).round() as u64;
    format!(
        "{:02}:{:02}:{:02}.{:03}",
        clamped / 3_600_000,
        (clamped % 3_600_000) / 60_000,
        (clamped % 60_000) / 1000,
        clamped % 1000
    )
}

fn ttml_clock(value: &str) -> String {
    let total: f64 = value
        .split(':')
        .map(|p| p.parse::<f64>().unwrap_or(0.0))
        .fold(0.0, |acc, n| acc * 60.0 + n);
    clock(total * 1000.0)
}

// ---- proxy -------------------------------------------------------------------

struct CacheInner {
    map: HashMap<String, CachedResponse>,
    bytes: usize,
}

/// Shared through an `Arc` by the accept loop: each connection gets its own
/// thread, so tokens and the range cache need interior mutability rather than
/// `&mut self` (a single mutex around the whole proxy would serialise the
/// video and audio streams and make playback stutter).
pub struct Proxy<U: Upstream> {
    upstream: U,
    /// The download folder, shared with the shell so changing it in Settings
    /// takes effect for files that are already on disk.
    download_dir: Arc<Mutex<PathBuf>>,
    tokens: Mutex<HashMap<String, TokenEntry>>,
    cache: Mutex<CacheInner>,
    cache_max_bytes: usize,
    cache_max_entry: usize,
    tokens_max: usize,
    next_token: AtomicU64,
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

impl<U: Upstream> Proxy<U> {
    pub fn new(upstream: U, download_dir: impl Into<PathBuf>) -> Self {
        Self::with_shared_dir(upstream, Arc::new(Mutex::new(download_dir.into())))
    }

    pub fn with_shared_dir(upstream: U, download_dir: Arc<Mutex<PathBuf>>) -> Self {
        Self {
            upstream,
            download_dir,
            tokens: Mutex::new(HashMap::new()),
            cache: Mutex::new(CacheInner {
                map: HashMap::new(),
                bytes: 0,
            }),
            cache_max_bytes: CACHE_MAX_BYTES,
            cache_max_entry: CACHE_MAX_ENTRY,
            tokens_max: TOKENS_MAX,
            next_token: AtomicU64::new(1),
        }
    }

    /// The folder `/file` is allowed to serve from.
    pub fn download_dir(&self) -> PathBuf {
        lock(&self.download_dir).clone()
    }

    pub fn set_download_dir(&self, dir: PathBuf) {
        *lock(&self.download_dir) = dir;
    }

    pub fn with_limits(mut self, max_bytes: usize, max_entry: usize, tokens_max: usize) -> Self {
        self.cache_max_bytes = max_bytes;
        self.cache_max_entry = max_entry;
        self.tokens_max = tokens_max;
        self
    }

    /// Register a signed CDN URL and hand back an opaque token.
    pub fn add_token(&self, url: &str, entry: TokenEntry) -> String {
        let token = format!("{:016x}", self.next_token.fetch_add(1, Ordering::SeqCst));
        let mut tokens = lock(&self.tokens);
        tokens.insert(
            token.clone(),
            TokenEntry {
                url: url.to_string(),
                ..entry
            },
        );
        while tokens.len() > self.tokens_max {
            let oldest = tokens
                .iter()
                .min_by_key(|(_, e)| e.at)
                .map(|(k, _)| k.clone());
            if let Some(k) = oldest {
                tokens.remove(&k);
            } else {
                break;
            }
        }
        token
    }

    pub fn token(&self, token: &str) -> Option<TokenEntry> {
        lock(&self.tokens).get(token).cloned()
    }

    pub fn token_count(&self) -> usize {
        lock(&self.tokens).len()
    }

    pub fn cache_len(&self) -> usize {
        lock(&self.cache).map.len()
    }

    fn cache_get(&self, key: &str) -> Option<CachedResponse> {
        let mut cache = lock(&self.cache);
        let hit = cache.map.remove(key)?;
        cache.map.insert(key.to_string(), hit.clone());
        Some(hit)
    }

    fn cache_set(&self, key: String, entry: CachedResponse) {
        if entry.body.len() > self.cache_max_entry {
            return;
        }
        let mut cache = lock(&self.cache);
        if let Some(prev) = cache.map.remove(&key) {
            cache.bytes = cache.bytes.saturating_sub(prev.body.len());
        }
        cache.bytes += entry.body.len();
        cache.map.insert(key, entry);
        while cache.bytes > self.cache_max_bytes && cache.map.len() > 1 {
            let oldest = cache.map.keys().next().cloned();
            match oldest {
                Some(k) => {
                    if let Some(gone) = cache.map.remove(&k) {
                        cache.bytes = cache.bytes.saturating_sub(gone.body.len());
                    }
                }
                None => break,
            }
        }
    }

    pub fn handle(&self, req: &Request) -> Response {
        if req.method == "OPTIONS" {
            return Response::new(204, "")
                .with("access-control-allow-methods", "GET, HEAD, OPTIONS")
                .with("access-control-allow-headers", "range")
                .with("access-control-max-age", "600");
        }

        match req.path.as_str() {
            "/r" => {
                let Some(token) = req.param("t") else { return Response::new(404, "stale token") };
                let (Some(s), Some(e)) = (req.param("s"), req.param("e")) else {
                    return Response::new(404, "stale token");
                };
                let range = match crate::range::range_from_query(Some(&s), Some(&e)) {
                    Some(r) => r,
                    None => return Response::new(404, "stale token"),
                };
                self.proxy_stream(&token, Some(range.header()))
            }
            // `/v` forwards the player's own Range header untouched, the same
            // way the JavaScript proxy does for a direct <video> source.
            "/v" => match req.param("t") {
                Some(token) => self.proxy_stream(&token, req.range_header.clone()),
                None => Response::new(404, "stale token"),
            },
            "/subs" => match req.param("t") {
                Some(token) => self.serve_subtitle(&token),
                None => Response::new(404, "stale token"),
            },
            "/file" => match req.param("p") {
                Some(path) => self.serve_file(&path, req.range_header.as_deref()),
                None => Response::new(404, "missing"),
            },
            _ => Response::new(404, ""),
        }
    }

    fn proxy_stream(&self, token: &str, range_header: Option<String>) -> Response {
        let Some(entry) = self.token(token) else {
            return Response::new(404, "stale token");
        };
        let url = entry.url;
        let key = format!("{}|{}", url, range_header.clone().unwrap_or_default());
        if let Some(hit) = self.cache_get(&key) {
            let mut res = Response {
                status: hit.status,
                headers: cors(),
                body: hit.body,
                file: None,
            };
            res = res
                .with("content-type", &hit.content_type)
                .with("accept-ranges", "bytes");
            if let Some(cr) = &hit.content_range {
                res = res.with("content-range", cr);
            }
            let len = res.body.len().to_string();
            return res.with("content-length", &len);
        }

        // Three attempts: a transient CDN hiccup should not fail a seek.
        let mut attempt = 0;
        let mut last_error = None;
        while attempt < 3 {
            match self.upstream.get(&url, range_header.as_deref()) {
                Ok(up) if up.status < 500 => {
                    let mut res = Response {
                        status: up.status,
                        headers: cors(),
                        body: up.body.clone(),
                        file: None,
                    };
                    res = res
                        .with("content-type", &up.content_type)
                        .with("accept-ranges", "bytes");
                    if let Some(cr) = &up.content_range {
                        res = res.with("content-range", cr);
                    }
                    let len = res.body.len().to_string();
                    res = res.with("content-length", &len);
                    if up.status == 200 || up.status == 206 {
                        self.cache_set(
                            key,
                            CachedResponse {
                                status: up.status,
                                content_type: up.content_type,
                                content_range: up.content_range,
                                body: up.body,
                            },
                        );
                    }
                    return res;
                }
                Ok(up) => {
                    last_error = Some(format!("upstream HTTP {}", up.status));
                }
                Err(e) => last_error = Some(e),
            }
            attempt += 1;
            if attempt < 3 {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        // 502s carry the CORS headers too: without them Chromium reports a media
        // failure as a confusing CORS error instead of a network one.
        Response::new(502, &format!("proxy error: {}", last_error.unwrap_or_default()))
            .with("content-type", "text/plain")
    }

    fn serve_subtitle(&self, token: &str) -> Response {
        let Some(entry) = self.token(token) else {
            return Response::new(404, "stale token");
        };
        let url = entry.url;
        let mut attempt = 0;
        while attempt < 3 {
            match self.upstream.get(&url, None) {
                Ok(up) if up.status < 400 => {
                    let text = String::from_utf8_lossy(&up.body).to_string();
                    let vtt = if text.starts_with("WEBVTT") {
                        Some(text)
                    } else if text.trim_start().starts_with('{') {
                        json3_to_vtt(&text)
                    } else if text.contains("</p>") || text.contains("</ttd:p>") {
                        ttml_to_vtt(&text)
                    } else {
                        None
                    };
                    return match vtt {
                        Some(vtt) => Response {
                            status: 200,
                            headers: cors(),
                            body: vtt.into_bytes(),
                            file: None,
                        }
                        .with("content-type", &up.content_type)
                        .with("cache-control", "no-store"),
                        None => Response::new(502, "subtitle unavailable"),
                    };
                }
                Ok(up) => {
                    if up.status < 500 {
                        break; // client errors are not worth retrying
                    }
                }
                Err(_) => {}
            }
            attempt += 1;
            if attempt < 3 {
                std::thread::sleep(Duration::from_millis(100));
            }
        }
        Response::new(502, "subtitle unavailable")
    }

    fn serve_file(&self, path: &str, range_header: Option<&str>) -> Response {
        let resolved = match Path::new(path).canonicalize() {
            Ok(p) => p,
            Err(_) => return Response::new(404, "missing"),
        };
        let root = match self.download_dir().canonicalize() {
            Ok(p) => p,
            Err(_) => return Response::new(404, "missing"),
        };
        // Downloads only: refuse anything outside the folder.
        if !resolved.starts_with(&root) {
            return Response::new(403, "forbidden");
        }
        let total = match std::fs::metadata(&resolved) {
            Ok(m) if m.is_file() => m.len(),
            _ => return Response::new(404, "missing"),
        };
        let content_type = content_type_for(&resolved.to_string_lossy());
        let range = match parse_request_range(&resolved, range_header, total) {
            Ok(r) => r,
            Err(_) => {
                return Response::new(416, "").with("content-range", &format!("bytes */{total}"))
            }
        };
        let (start, len, status) = match range {
            None => (0u64, total, 200),
            Some(range) => (range.start, range.len(), 206),
        };
        if len > 0 && start.saturating_add(len) > total {
            return Response::new(416, "").with("content-range", &format!("bytes */{total}"));
        }
        let mut res = Response {
            status,
            headers: cors(),
            body: Vec::new(),
            file: Some(FileBody {
                path: resolved.clone(),
                start,
                len,
            }),
        };
        res = res
            .with("content-type", content_type)
            .with("content-length", &len.to_string())
            .with("accept-ranges", "bytes");
        if status == 206 {
            res = res.with(
                "content-range",
                &format!("bytes {}-{}/{}", start, start + len.saturating_sub(1), total),
            );
        }
        res
    }
}

/// `bytes=start-end` against a file of `total` bytes. `Ok(None)` means "the
/// whole file". Out-of-range or malformed asks are a 416.
pub fn parse_request_range(_file: &Path, header: Option<&str>, total: u64) -> Result<Option<ByteRange>, ()> {
    let Some(header) = header else { return Ok(None) };
    let re = regex::Regex::new(r"bytes=(\d*)-(\d*)").unwrap();
    let Some(caps) = re.captures(header) else { return Ok(None) };
    let start: u64 = caps.get(1).map(|m| m.as_str()).filter(|s| !s.is_empty()).unwrap_or("0").parse().map_err(|_| ())?;
    let end: u64 = match caps.get(2).map(|m| m.as_str()).filter(|s| !s.is_empty()) {
        Some(v) => v.parse().map_err(|_| ())?,
        None => total.saturating_sub(1),
    };
    if total == 0 || start >= total {
        return Err(());
    }
    let end = end.min(total.saturating_sub(1));
    ByteRange::new(start, end).map(Some).ok_or(())
}

// ---- HTTP/1.1 -----------------------------------------------------------------

fn read_request(stream: &TcpStream) -> std::io::Result<Request> {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader.read_line(&mut line)?;
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_string();
    let target = parts.next().unwrap_or("/").to_string();
    let mut range_header = None;
    loop {
        let mut header = String::new();
        if reader.read_line(&mut header)? == 0 {
            break;
        }
        let trimmed = header.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        if let Some((k, v)) = trimmed.split_once(':') {
            if k.eq_ignore_ascii_case("range") {
                range_header = Some(v.trim().to_string());
            }
        }
    }
    let mut req = Request::get(&target);
    req.method = method;
    req.range_header = range_header;
    Ok(req)
}

/// Serve one connection, then close it. Kept separate from the accept loop so
/// tests can drive a single request over a real socket.
pub fn serve_connection<U: Upstream>(proxy: &Proxy<U>, mut stream: TcpStream) -> std::io::Result<()> {
    let req = match read_request(&stream) {
        Ok(r) => r,
        Err(_) => return Ok(()),
    };
    let res = proxy.handle(&req);
    write_response(&mut stream, &res)?;
    if let Some(file) = &res.file {
        copy_file_range(&mut stream, file)?;
    } else {
        stream.write_all(&res.body)?;
    }
    stream.flush()?;
    Ok(())
}

fn write_response(stream: &mut TcpStream, res: &Response) -> std::io::Result<()> {
    let mut head = format!("HTTP/1.1 {} {}\r\n", res.status, res.status_text());
    let mut has_len = false;
    for (k, v) in &res.headers {
        if k == "content-length" {
            has_len = true;
        }
        head.push_str(&format!("{k}: {v}\r\n"));
    }
    if !has_len {
        let len = match &res.file {
            Some(file) => file.len,
            None => res.body.len() as u64,
        };
        head.push_str(&format!("content-length: {len}\r\n"));
    }
    head.push_str("connection: close\r\n\r\n");
    stream.write_all(head.as_bytes())
}

/// Stream a byte window of a local file to the player without buffering it.
fn copy_file_range<S: Write>(stream: &mut S, file: &FileBody) -> std::io::Result<()> {
    use std::io::{Seek, SeekFrom};
    let mut handle = File::open(&file.path)?;
    handle.seek(SeekFrom::Start(file.start))?;
    let mut remaining = file.len;
    let mut buffer = vec![0u8; 64 * 1024];
    while remaining > 0 {
        let want = remaining.min(buffer.len() as u64) as usize;
        let read = match handle.read(&mut buffer[..want]) {
            Ok(0) => break,
            Ok(read) => read,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        stream.write_all(&buffer[..read])?;
        remaining -= read as u64;
    }
    Ok(())
}

/// Blocking accept loop, one thread per connection so a long video range does
/// not hold up the audio stream. `stop` lets the shell shut it down.
pub fn serve<U: Upstream + Send + Sync + 'static>(
    proxy: Arc<Proxy<U>>,
    listener: TcpListener,
    stop: Arc<AtomicBool>,
) {
    for incoming in listener.incoming() {
        if stop.load(Ordering::SeqCst) {
            break;
        }
        let Ok(stream) = incoming else { continue };
        let proxy = Arc::clone(&proxy);
        std::thread::spawn(move || {
            let _ = serve_connection(&proxy, stream);
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::net::SocketAddr;


    #[derive(Default)]
    struct FakeUpstream {
        calls: RefCell<Vec<(String, Option<String>)>>,
        /// status to answer with, per call index; falls back to 200.
        statuses: Vec<u16>,
        body: Vec<u8>,
        content_type: String,
    }

    impl FakeUpstream {
        fn new(body: &[u8], content_type: &str) -> Self {
            Self {
                calls: RefCell::new(Vec::new()),
                statuses: Vec::new(),
                body: body.to_vec(),
                content_type: content_type.to_string(),
            }
        }
    }

    impl Upstream for FakeUpstream {
        fn get(&self, url: &str, range: Option<&str>) -> Result<UpstreamResponse, String> {
            self.calls.borrow_mut().push((url.to_string(), range.map(str::to_string)));
            let idx = self.calls.borrow().len();
            if let Some(status) = self.statuses.get(idx - 1) {
                if *status >= 500 {
                    return Ok(UpstreamResponse {
                        status: *status,
                        content_type: "text/plain".to_string(),
                        content_range: None,
                        body: Vec::new(),
                    });
                }
            }
            if let Some(r) = range {
                let re = regex::Regex::new(r"bytes=(\d+)-(\d+)").unwrap();
                let (start, end) = re
                    .captures(r)
                    .map(|c| {
                        (
                            c[1].parse::<u64>().unwrap_or(0),
                            c[2].parse::<u64>().unwrap_or(9),
                        )
                    })
                    .unwrap_or((0, 9));
                return Ok(UpstreamResponse {
                    status: 206,
                    content_type: self.content_type.clone(),
                    content_range: Some(format!("bytes {start}-{end}/999")),
                    body: self.body.clone(),
                });
            }
            Ok(UpstreamResponse::ok(self.body.clone(), &self.content_type))
        }
    }

    fn entry() -> TokenEntry {
        TokenEntry {
            url: String::new(),
            video_id: None,
            role: None,
            height: None,
            at: Instant::now(),
        }
    }

    #[test]
    fn options_preflight_answers_without_touching_upstream() {
        let upstream = FakeUpstream::new(b"", "video/mp4");
        let proxy = Proxy::new(upstream, ".");
        let mut req = Request::get("/v?t=x");
        req.method = "OPTIONS".to_string();
        let res = proxy.handle(&req);
        assert_eq!(res.status, 204);
        assert_eq!(res.header("access-control-allow-origin"), Some("*"));
        assert_eq!(res.header("access-control-allow-headers"), Some("range"));
        assert_eq!(proxy.upstream.calls.borrow().len(), 0);
    }

    #[test]
    fn stale_tokens_are_404() {
        let upstream = FakeUpstream::new(b"", "video/mp4");
        let proxy = Proxy::new(upstream, ".");
        assert_eq!(proxy.handle(&Request::get("/v?t=missing")).status, 404);
        assert_eq!(proxy.handle(&Request::get("/r?t=missing&s=0&e=9")).status, 404);
        assert_eq!(proxy.handle(&Request::get("/subs?t=missing")).status, 404);
    }

    #[test]
    fn range_requests_go_through_the_path_query_and_are_cached() {
        let upstream = FakeUpstream::new(b"0123456789", "video/mp4");
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/media", entry());
        let req = Request::get(&format!("/r?t={token}&s=0&e=9"));

        let first = proxy.handle(&req);
        assert_eq!(first.status, 206);
        assert_eq!(first.header("access-control-allow-origin"), Some("*"));
        assert_eq!(first.header("accept-ranges"), Some("bytes"));
        assert_eq!(first.body, b"0123456789");
        assert_eq!(
            proxy.upstream.calls.borrow()[0].1.as_deref(),
            Some("bytes=0-9")
        );

        // The same window again is served from memory: no second upstream call.
        let second = proxy.handle(&req);
        assert_eq!(second.body, b"0123456789");
        assert_eq!(proxy.upstream.calls.borrow().len(), 1);
        assert_eq!(proxy.cache_len(), 1);
    }

    #[test]
    fn the_v_route_forwards_the_players_range_header() {
        let upstream = FakeUpstream::new(b"0123456789", "video/mp4");
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/media", entry());
        let mut req = Request::get(&format!("/v?t={token}"));
        req.range_header = Some("bytes=2-5".to_string());
        let res = proxy.handle(&req);
        assert_eq!(res.status, 206);
        assert_eq!(res.header("content-range"), Some("bytes 2-5/999"));
        assert_eq!(
            proxy.upstream.calls.borrow()[0].1.as_deref(),
            Some("bytes=2-5")
        );
    }

    #[test]
    fn retries_a_5xx_three_times_then_reports_502_with_cors() {
        let mut upstream = FakeUpstream::new(b"", "video/mp4");
        upstream.statuses = vec![503, 503, 503];
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/media", entry());
        let res = proxy.handle(&Request::get(&format!("/v?t={token}")));
        assert_eq!(res.status, 502);
        assert_eq!(res.header("access-control-allow-origin"), Some("*"));
        assert_eq!(proxy.upstream.calls.borrow().len(), 3);
    }

    #[test]
    fn a_failing_attempt_is_retried_and_then_succeeds() {
        let mut upstream = FakeUpstream::new(b"payload", "video/mp4");
        upstream.statuses = vec![500];
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/media", entry());
        let res = proxy.handle(&Request::get(&format!("/v?t={token}")));
        assert_eq!(res.status, 200);
        assert_eq!(res.body, b"payload");
        assert_eq!(proxy.upstream.calls.borrow().len(), 2);
    }

    #[test]
    fn captions_are_converted_before_reaching_the_player() {
        let json3 = r#"{"events":[
            {"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"hello "},{"utf8":"world"}]},
            {"tStartMs":1500,"dDurationMs":1000,"segs":[{"utf8":"\n"}]},
            {"tStartMs":2000,"dDurationMs":1000,"segs":[{"utf8":"second line"}]}
        ]}"#;
        let upstream = FakeUpstream::new(json3.as_bytes(), "application/json");
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/cap", entry());
        let res = proxy.handle(&Request::get(&format!("/subs?t={token}")));
        assert_eq!(res.status, 200);
        let body = String::from_utf8(res.body.clone()).unwrap();
        assert!(body.starts_with("WEBVTT\n\n1\n00:00:00.000 --> 00:00:01.500\nhello world"));
        assert!(body.contains("00:00:02.000 --> 00:00:03.000\nsecond line"));
        assert_eq!(res.header("cache-control"), Some("no-store"));
    }

    #[test]
    fn webvtt_passes_through_and_ttml_is_converted() {
        let upstream = FakeUpstream::new(b"WEBVTT\n\n1\n00:00:00.000 --> 00:00:01.000\nhi", "text/vtt");
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/cap", entry());
        let res = proxy.handle(&Request::get(&format!("/subs?t={token}")));
        assert!(String::from_utf8(res.body).unwrap().starts_with("WEBVTT"));

        let ttml = r#"<tt><body><div><p begin="0:00:01.500" end="0:00:03.000">Line<br/>two</p></div></body></tt>"#;
        let out = ttml_to_vtt(ttml).unwrap();
        assert!(out.contains("00:00:01.500 --> 00:00:03.000"));
        assert!(out.contains("Line\ntwo"));
    }

    #[test]
    fn local_files_are_limited_to_the_downloads_folder() {
        let dir = std::env::temp_dir().join("indio-core-proxy-test");
        let _ = std::fs::create_dir_all(&dir);
        let inside = dir.join("video.mp4");
        std::fs::write(&inside, b"0123456789").unwrap();
        let outside = std::env::temp_dir().join("indio-core-outside.mp4");
        std::fs::write(&outside, b"nope").unwrap();

        let upstream = FakeUpstream::new(b"", "video/mp4");
        let proxy = Proxy::new(upstream, &dir);

        let ok = proxy.handle(&Request::get(&format!(
            "/file?p={}",
            urlencode(&inside.to_string_lossy())
        )));
        assert_eq!(ok.status, 200);
        assert_eq!(ok.header("content-type"), Some("video/mp4"));
        // The bytes stream from disk, so they are described rather than held.
        assert!(ok.body.is_empty());
        let file = ok.file.expect("a file body");
        assert_eq!((file.start, file.len), (0, 10));

        let denied = proxy.handle(&Request::get(&format!(
            "/file?p={}",
            urlencode(&outside.to_string_lossy())
        )));
        assert_eq!(denied.status, 403);

        let missing = proxy.handle(&Request::get(&format!(
            "/file?p={}",
            urlencode(&dir.join("nope.mp4").to_string_lossy())
        )));
        assert_eq!(missing.status, 404);
    }

    #[test]
    fn a_downloaded_file_streams_by_byte_range() {
        let dir = std::env::temp_dir().join("indio-core-proxy-file-stream");
        let _ = std::fs::create_dir_all(&dir);
        let file = dir.join("big.mp4");
        let payload: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(&file, &payload).unwrap();

        let proxy = Proxy::new(FakeUpstream::new(b"", "video/mp4"), &dir);
        let mut request = Request::get(&format!("/file?p={}", urlencode(&file.to_string_lossy())));
        request.range_header = Some("bytes=1000-1999".to_string());
        let res = proxy.handle(&request);
        assert_eq!(res.status, 206);
        assert_eq!(
            res.header("content-range"),
            Some(format!("bytes 1000-1999/{}", payload.len()).as_str())
        );
        assert_eq!(res.header("content-length"), Some("1000"));

        // Over the socket the window really is the requested slice, and only
        // that slice: a 2 GB download must never be buffered to be played.
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let mut socket = TcpStream::connect(("127.0.0.1", port)).unwrap();
        socket
            .write_all(
                format!(
                    "GET /file?p={} HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=1000-1999\r\n\r\n",
                    urlencode(&file.to_string_lossy())
                )
                .as_bytes(),
            )
            .unwrap();
        let (server, _) = listener.accept().unwrap();
        serve_connection(&proxy, server).unwrap();
        let mut raw = Vec::new();
        socket.read_to_end(&mut raw).unwrap();
        let head_end = raw
            .windows(4)
            .position(|w| w == b"\r\n\r\n")
            .expect("a header terminator");
        let head = String::from_utf8_lossy(&raw[..head_end]).to_lowercase();
        assert!(head.contains("http/1.1 206"), "{head}");
        assert_eq!(&raw[head_end + 4..], &payload[1000..2000]);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn moving_the_download_folder_moves_what_file_may_serve() {
        let from = std::env::temp_dir().join("indio-core-proxy-dir-a");
        let to = std::env::temp_dir().join("indio-core-proxy-dir-b");
        let _ = std::fs::create_dir_all(&from);
        let _ = std::fs::create_dir_all(&to);
        let in_from = from.join("a.mp4");
        let in_to = to.join("b.mp4");
        std::fs::write(&in_from, b"aaaa").unwrap();
        std::fs::write(&in_to, b"bbbb").unwrap();

        let shared = Arc::new(Mutex::new(from.clone()));
        let proxy = Proxy::with_shared_dir(FakeUpstream::new(b"", "video/mp4"), Arc::clone(&shared));
        let ask = |path: &PathBuf| {
            proxy.handle(&Request::get(&format!(
                "/file?p={}",
                urlencode(&path.to_string_lossy())
            )))
        };
        assert_eq!(ask(&in_from).status, 200);
        assert_eq!(ask(&in_to).status, 403);

        // Settings changed at runtime: the new folder is served, the old one is
        // not, and nothing needed a restart.
        proxy.set_download_dir(to.clone());
        assert_eq!(shared.lock().unwrap().as_path(), to.as_path());
        assert_eq!(ask(&in_to).status, 200);
        assert_eq!(ask(&in_from).status, 403);

        let _ = std::fs::remove_dir_all(&from);
        let _ = std::fs::remove_dir_all(&to);
    }

    fn urlencode(value: &str) -> String {
        value
            .replace('%', "%25")
            .replace('\\', "%5C")
            .replace('/', "%2F")
            .replace(':', "%3A")
            .replace(' ', "%20")
    }

    #[test]
    fn range_parsing_covers_the_edge_cases() {
        assert_eq!(parse_request_range(Path::new("f"), None, 10).unwrap(), None);
        assert_eq!(
            parse_request_range(Path::new("f"), Some("bytes=2-5"), 10).unwrap(),
            ByteRange::new(2, 5)
        );
        assert_eq!(
            parse_request_range(Path::new("f"), Some("bytes=7-"), 10).unwrap(),
            ByteRange::new(7, 9)
        );
        assert_eq!(
            parse_request_range(Path::new("f"), Some("bytes=5-99"), 10).unwrap(),
            ByteRange::new(5, 9)
        );
        assert!(parse_request_range(Path::new("f"), Some("bytes=20-30"), 10).is_err());
    }

    #[test]
    fn tokens_are_bounded() {
        let upstream = FakeUpstream::new(b"", "video/mp4");
        let proxy = Proxy::new(upstream, ".").with_limits(CACHE_MAX_BYTES, CACHE_MAX_ENTRY, 2);
        let a = proxy.add_token("u1", entry());
        let _b = proxy.add_token("u2", entry());
        let _c = proxy.add_token("u3", entry());
        assert!(proxy.token(&a).is_none(), "the oldest token is evicted");
        assert_eq!(proxy.token_count(), 2);
    }

    #[test]
    fn serves_a_real_http_request_over_a_socket() {
        let upstream = FakeUpstream::new(b"0123456789", "video/mp4");
        let proxy = Proxy::new(upstream, ".");
        let token = proxy.add_token("https://cdn.example/media", entry());

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr: SocketAddr = listener.local_addr().unwrap();
        let handle = std::thread::spawn(move || {
            for incoming in listener.incoming().take(1) {
                let stream = incoming.unwrap();
                serve_connection(&proxy, stream).unwrap();
            }
        });

        let mut client = TcpStream::connect(addr).unwrap();
        client
            .write_all(format!("GET /r?t={token}&s=0&e=9 HTTP/1.1\r\nHost: x\r\n\r\n").as_bytes())
            .unwrap();
        let mut reader = BufReader::new(client);
        let mut status = String::new();
        reader.read_line(&mut status).unwrap();
        assert!(status.contains("206"));
        let mut seen_cors = false;
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).unwrap();
            if line.trim().is_empty() {
                break;
            }
            if line.to_lowercase().starts_with("access-control-allow-origin") {
                seen_cors = true;
            }
        }
        let mut body = Vec::new();
        reader.read_to_end(&mut body).unwrap();
        assert!(seen_cors);
        assert_eq!(body, b"0123456789");
        handle.join().unwrap();
    }

    #[test]
    fn percent_decoding_handles_encoded_paths() {
        assert_eq!(percent_decode("a%20b"), "a b");
        assert_eq!(percent_decode("100%25"), "100%");
        assert_eq!(percent_decode("plain"), "plain");
    }
}
