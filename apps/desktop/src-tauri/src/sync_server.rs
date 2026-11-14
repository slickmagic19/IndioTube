//! LAN device sync, the Rust port of electron/sync.js.
//!
//! A tiny HTTP server on port 47711 (any free port when that one is taken) with
//! the same three routes the Android app already talks to, answering with the
//! same envelopes:
//!
//! ```text
//! GET  /indio/v1/ping                — discovery, no auth, { app, v, name }
//! GET  /indio/v1/snapshot?token=…    — phone pulls the desktop, { ok, snapshot }
//! POST /indio/v1/merge?token=…       — phone pushes its additions, { ok, stats }
//! ```
//!
//! The token is the one-time capability carried by the QR and is required by
//! every route but ping. The pairing window closes itself after 15 quiet
//! minutes. The router is a plain function over closures so the wire contract is
//! unit-testable without a webview or a socket; `SyncServer` only adds the
//! listener, the thread and the `sync:event` emissions.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::library::Library;

pub const PORT: u16 = 47_711;
pub const PROTOCOL_VERSION: i64 = 1;
const IDLE_TTL: Duration = Duration::from_secs(15 * 60);
const BODY_CAP: usize = 12 * 1024 * 1024;
const MAX_HEADER: usize = 32 * 1024;

/// What the UI needs to hear about, in `electron/sync.js` event terms: the
/// renderer's `sync:event` handler only understands `type`.
#[derive(Debug, Clone, PartialEq)]
pub enum RouterEvent {
    Linked,
    Pulled,
    Merged { stats: Value, sender: Option<String> },
}

/// The answer to one request plus the events it produced.
pub struct Routed {
    pub status: u16,
    pub body: Value,
    pub events: Vec<RouterEvent>,
}

/// Private ranges first (typical home Wi-Fi), then everything else — the QR
/// needs exactly one address, so the most likely reachable one wins. Mirrors
/// `lanAddresses()` in `electron/sync.js`, including the skip of loopback and
/// of the carrier-grade NAT range a VPN adapter likes to hand out.
pub fn lan_addresses() -> Vec<String> {
    let mut private_a = Vec::new();
    let mut private_b = Vec::new();
    let mut private_c = Vec::new();
    let mut other = Vec::new();
    if let Ok(addrs) = if_addrs::get_if_addrs() {
        for addr in addrs {
            if addr.is_loopback() {
                continue;
            }
            let ip = addr.ip().to_string();
            let octets: Vec<u8> = ip
                .split('.')
                .filter_map(|part| part.parse::<u8>().ok())
                .collect();
            if octets.len() != 4 {
                continue; // an IPv6 address; the phone pairs over v4
            }
            if octets[0] == 192 && octets[1] == 168 {
                private_a.push(ip);
            } else if octets[0] == 10 {
                private_b.push(ip);
            } else if octets[0] == 172 && (16..=31).contains(&octets[1]) {
                private_c.push(ip);
            } else if octets[0] == 127 || (octets[0] == 100 && (64..=127).contains(&octets[1])) {
                // Loopback or CGNAT (Tailscale/Cloudflare WARP): never the LAN
                // address a phone on the Wi-Fi can reach.
                continue;
            } else {
                other.push(ip);
            }
        }
    }
    let mut all = private_a;
    all.extend(private_b);
    all.extend(private_c);
    all.extend(other);
    if all.is_empty() {
        // Nothing usable was enumerated; fall back to the routing-table trick so
        // a single-homed machine still reports something.
        if let Some(ip) = route_to_public() {
            all.push(ip);
        }
    }
    all
}

/// No interface enumeration available: dialling a public address makes the
/// routing table pick the outbound interface, and the socket's local address is
/// the one this machine would actually use.
fn route_to_public() -> Option<String> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    let addr = socket.local_addr().ok()?;
    if addr.ip().is_unspecified() || addr.ip().is_loopback() {
        return None;
    }
    Some(addr.ip().to_string())
}

/// Constant-time-ish token comparison: never branch on the first mismatch.
pub fn token_ok(expected: &str, got: Option<&str>) -> bool {
    let got = match got {
        Some(got) => got,
        None => return false,
    };
    if got.len() != expected.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in expected.bytes().zip(got.bytes()) {
        diff |= a ^ b;
    }
    diff == 0
}

/// The QR payload the Android app scans: `{ indio, v, host, port, token, name }`.
pub fn pairing_payload(host: &str, port: u16, token: &str, name: &str) -> Value {
    json!({
        "indio": "sync",
        "v": PROTOCOL_VERSION,
        "host": host,
        "port": port,
        "token": token,
        "name": name.chars().take(48).collect::<String>(),
    })
}

/// 32 hex characters, like `crypto.randomBytes(16).toString('hex')`. Seeded from
/// `RandomState`, whose keys come from the OS entropy source, so no extra crate
/// is needed for a per-session capability.
pub fn session_token() -> String {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hash, Hasher};
    let mut out = String::with_capacity(32);
    for round in 0..2u64 {
        let mut value: u64 = 0;
        for lane in 0..4u64 {
            let state = RandomState::new();
            let mut hasher = state.build_hasher();
            round.hash(&mut hasher);
            lane.hash(&mut hasher);
            std::process::id().hash(&mut hasher);
            value = value.rotate_left(17) ^ hasher.finish();
        }
        out.push_str(&format!("{value:016x}"));
    }
    out
}

/// The whole wire contract, with the library behind two closures.
pub fn route(
    token: &str,
    method: &str,
    path: &str,
    body: &[u8],
    device_name: &dyn Fn() -> String,
    snapshot: &dyn Fn() -> Value,
    merge: &dyn Fn(&Value) -> Result<Value, String>,
) -> Routed {
    let path_only = path.split('?').next().unwrap_or("");
    if method == "OPTIONS" {
        return Routed {
            status: 200,
            body: json!({ "ok": true }),
            events: Vec::new(),
        };
    }
    if method == "GET" && path_only == "/indio/v1/ping" {
        return Routed {
            status: 200,
            body: json!({ "app": "indiotube", "v": PROTOCOL_VERSION, "name": device_name() }),
            events: Vec::new(),
        };
    }
    if !token_ok(token, query_param(path, "token").as_deref()) {
        return Routed {
            status: 401,
            body: json!({ "error": "pairing token missing or wrong" }),
            events: Vec::new(),
        };
    }
    if method == "GET" && path_only == "/indio/v1/snapshot" {
        let payload = snapshot();
        return Routed {
            status: 200,
            body: json!({ "ok": true, "snapshot": payload }),
            events: vec![RouterEvent::Linked, RouterEvent::Pulled],
        };
    }
    if method == "POST" && path_only == "/indio/v1/merge" {
        let parsed: Value = match serde_json::from_slice(body) {
            Ok(parsed) => parsed,
            Err(_) => {
                return Routed {
                    status: 400,
                    body: json!({ "error": "body must be JSON" }),
                    events: Vec::new(),
                }
            }
        };
        if !parsed.is_object() {
            return Routed {
                status: 400,
                body: json!({ "error": "body must be a JSON object" }),
                events: Vec::new(),
            };
        }
        let sender = parsed
            .get("name")
            .and_then(Value::as_str)
            .map(str::to_string);
        return match merge(&parsed) {
            Ok(stats) => Routed {
                status: 200,
                body: json!({ "ok": true, "stats": stats }),
                events: vec![
                    RouterEvent::Linked,
                    RouterEvent::Merged { stats, sender },
                ],
            },
            Err(e) => Routed {
                status: 400,
                body: json!({ "error": e }),
                events: Vec::new(),
            },
        };
    }
    Routed {
        status: 404,
        body: json!({ "error": "unknown route" }),
        events: Vec::new(),
    }
}

fn query_param(path: &str, key: &str) -> Option<String> {
    let query = path.split_once('?')?.1;
    for pair in query.split('&') {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        if k == key {
            return Some(percent_decode(v));
        }
    }
    None
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 3 <= bytes.len() {
            if let Ok(hex) = u8::from_str_radix(&value[i + 1..i + 3], 16) {
                out.push(hex);
                i += 3;
                continue;
            }
        }
        out.push(if bytes[i] == b'+' { b' ' } else { bytes[i] });
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

enum Received {
    Request {
        method: String,
        path: String,
        body: Vec<u8>,
    },
    /// The client announced more bytes than the pairing payload may ever hold.
    TooLarge,
    Closed,
}

fn read_request(stream: &mut TcpStream) -> Received {
    stream
        .set_read_timeout(Some(Duration::from_secs(10)))
        .ok();
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 8192];
    let header_end = loop {
        let n = match stream.read(&mut chunk) {
            Ok(0) | Err(_) => return Received::Closed,
            Ok(n) => n,
        };
        buffer.extend_from_slice(&chunk[..n]);
        if let Some(pos) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos;
        }
        if buffer.len() > MAX_HEADER {
            return Received::Closed;
        }
    };
    let head = String::from_utf8_lossy(&buffer[..header_end]).to_string();
    let mut lines = head.split("\r\n");
    let request_line = match lines.next() {
        Some(line) => line,
        None => return Received::Closed,
    };
    let mut parts = request_line.split(' ');
    let (method, path) = match (parts.next(), parts.next()) {
        (Some(m), Some(p)) => (m.to_string(), p.to_string()),
        _ => return Received::Closed,
    };
    let mut content_length = 0usize;
    for line in lines {
        if let Some((key, value)) = line.split_once(':') {
            if key.trim().eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0);
            }
        }
    }
    if content_length > BODY_CAP {
        return Received::TooLarge;
    }
    let mut body = buffer[header_end + 4..].to_vec();
    while body.len() < content_length {
        match stream.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => body.extend_from_slice(&chunk[..n]),
        }
    }
    body.truncate(content_length);
    Received::Request { method, path, body }
}

fn write_response(stream: &mut TcpStream, status: u16, body: &Value) {
    let text = body.to_string();
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        413 => "Payload Too Large",
        _ => "Internal Server Error",
    };
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: content-type\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: close\r\n\r\n",
        text.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(text.as_bytes());
    let _ = stream.flush();
}

struct Handle {
    token: String,
    port: u16,
    host: String,
    ips: Vec<String>,
}

pub struct SyncServer {
    handle: Mutex<Option<Handle>>,
    stop: Mutex<Option<Arc<AtomicBool>>>,
    library: Arc<Library>,
    emit: Arc<dyn Fn(Value) + Send + Sync + 'static>,
}

impl SyncServer {
    pub fn new(
        library: Arc<Library>,
        emit: Arc<dyn Fn(Value) + Send + Sync + 'static>,
    ) -> Self {
        Self {
            handle: Mutex::new(None),
            stop: Mutex::new(None),
            library,
            emit,
        }
    }

    pub fn status(&self) -> Value {
        let handle = self.lock_handle();
        match handle.as_ref() {
            Some(handle) => json!({
                "active": true,
                "port": handle.port,
                "host": handle.host,
                "ips": handle.ips,
                "token": handle.token,
                "url": format!("http://{}:{}/indio/v1", handle.host, handle.port),
            }),
            None => json!({ "active": false }),
        }
    }

    fn lock_handle(&self) -> std::sync::MutexGuard<'_, Option<Handle>> {
        self.handle
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn stop(&self) -> Value {
        if let Some(stop) = self
            .stop
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
        {
            stop.store(true, Ordering::SeqCst);
        }
        if let Some(previous) = self.lock_handle().take() {
            // Wake the accept loop so it notices the flag immediately.
            let _ = TcpStream::connect(("127.0.0.1", previous.port));
        }
        json!({ "ok": true })
    }

    pub fn start(&self) -> Result<Value, String> {
        self.stop();
        let ips = lan_addresses();
        if ips.is_empty() {
            return Err("no LAN address — connect this PC to Wi-Fi or Ethernet first".to_string());
        }
        let listener = TcpListener::bind(("0.0.0.0", PORT))
            .or_else(|_| TcpListener::bind(("0.0.0.0", 0)))
            .map_err(|e| format!("could not open the sync port: {e}"))?;
        let host = ips[0].clone();
        self.serve(listener, &host, ips)
    }

    /// Run the pairing server on an already-bound listener. The tests use this
    /// to drive the real socket path against a temporary library, request for
    /// request, the way the phone does.
    fn serve(
        &self,
        listener: TcpListener,
        host: &str,
        ips: Vec<String>,
    ) -> Result<Value, String> {
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        listener
            .set_nonblocking(true)
            .map_err(|e| e.to_string())?;
        let token = session_token();
        let host = host.to_string();
        let stop = Arc::new(AtomicBool::new(false));

        *self.lock_handle() = Some(Handle {
            token: token.clone(),
            port,
            host: host.clone(),
            ips: ips.clone(),
        });
        *self
            .stop
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(Arc::clone(&stop));

        let library = Arc::clone(&self.library);
        let emit = Arc::clone(&self.emit);
        let thread_stop = Arc::clone(&stop);
        let thread_token = token.clone();
        let thread_ips = ips.clone();
        std::thread::spawn(move || {
            let token = thread_token;
            let ips = thread_ips;
            let mut last_touch = Instant::now();
            let mut linked = false;
            loop {
                if thread_stop.load(Ordering::SeqCst) {
                    break;
                }
                if last_touch.elapsed() > IDLE_TTL {
                    // The pairing window closed itself: the modal shuts with it.
                    emit(json!({ "type": "expired" }));
                    break;
                }
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        last_touch = Instant::now();
                        let _ = stream.set_nonblocking(false);
                        let name_library = Arc::clone(&library);
                        let snapshot_library = Arc::clone(&library);
                        let merge_library = Arc::clone(&library);
                        let routed = match read_request(&mut stream) {
                            Received::TooLarge => Routed {
                                status: 413,
                                body: json!({ "error": "payload too large" }),
                                events: Vec::new(),
                            },
                            Received::Closed => continue,
                            Received::Request { method, path, body } => route(
                                &token,
                                &method,
                                &path,
                                &body,
                                &move || {
                                    name_library
                                        .state()
                                        .get("profileName")
                                        .and_then(Value::as_str)
                                        .unwrap_or("")
                                        .to_string()
                                },
                                &move || snapshot_library.snapshot_public(),
                                &move |payload| merge_library.merge_public(payload),
                            ),
                        };
                        write_response(&mut stream, routed.status, &routed.body);
                        for event in routed.events {
                            match event {
                                RouterEvent::Linked if !linked => {
                                    linked = true;
                                    emit(json!({ "type": "linked", "ips": ips }));
                                }
                                RouterEvent::Linked => {}
                                RouterEvent::Pulled => emit(json!({ "type": "pulled" })),
                                RouterEvent::Merged { stats, sender } => emit(json!({
                                    "type": "merged",
                                    "stats": stats,
                                    "sender": sender,
                                })),
                            }
                        }
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(40));
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(json!({
            "payload": pairing_payload(&host, port, &token, &self.device_name()),
            "ips": ips,
            "port": port,
            "host": host,
            "token": token,
            "url": format!("http://{host}:{port}/indio/v1"),
        }))
    }

    fn device_name(&self) -> String {
        self.library
            .state()
            .get("profileName")
            .and_then(Value::as_str)
            .unwrap_or("Desktop")
            .to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    fn call(method: &str, path: &str, body: &[u8]) -> Routed {
        route(
            TOKEN,
            method,
            path,
            body,
            &|| "Test PC".to_string(),
            &|| json!({ "indio": "sync", "v": 1, "library": { "liked": [] } }),
            &|payload| Ok(json!({ "historyAdded": 1, "subsAdded": payload["name"].as_str().map(|_| 1).unwrap_or(0) })),
        )
    }

    #[test]
    fn ping_answers_without_a_token() {
        let routed = call("GET", "/indio/v1/ping", b"");
        assert_eq!(routed.status, 200);
        assert_eq!(routed.body["app"], "indiotube");
        assert_eq!(routed.body["v"], json!(PROTOCOL_VERSION));
        assert_eq!(routed.body["name"], "Test PC");
        assert!(routed.events.is_empty());
    }

    #[test]
    fn every_other_route_needs_the_pairing_token() {
        for (method, path) in [
            ("GET", "/indio/v1/snapshot"),
            ("GET", "/indio/v1/snapshot?token=nope"),
            ("GET", "/indio/v1/snapshot?token=0123456789abcdef0123456789abcde"),
            ("POST", "/indio/v1/merge?token="),
        ] {
            let routed = call(method, path, b"{}");
            assert_eq!(routed.status, 401, "{method} {path}");
            assert_eq!(routed.body["error"], "pairing token missing or wrong");
        }
    }

    #[test]
    fn a_phone_pulls_the_desktop_snapshot() {
        let routed = call("GET", &format!("/indio/v1/snapshot?token={TOKEN}"), b"");
        assert_eq!(routed.status, 200);
        assert_eq!(routed.body["ok"], true);
        assert_eq!(routed.body["snapshot"]["indio"], "sync");
        assert_eq!(routed.events, vec![RouterEvent::Linked, RouterEvent::Pulled]);
    }

    #[test]
    fn a_phone_pushes_a_merge_and_gets_the_stats_back() {
        let routed = call(
            "POST",
            &format!("/indio/v1/merge?token={TOKEN}"),
            br#"{"indio":"sync","v":1,"name":"Pixel","library":{}}"#,
        );
        assert_eq!(routed.status, 200);
        assert_eq!(routed.body["ok"], true);
        assert_eq!(routed.body["stats"]["historyAdded"], 1);
        assert_eq!(routed.body["stats"]["subsAdded"], 1);
        match &routed.events[1] {
            RouterEvent::Merged { sender, .. } => assert_eq!(sender.as_deref(), Some("Pixel")),
            other => panic!("expected a merged event, got {other:?}"),
        }
    }

    #[test]
    fn bad_bodies_and_unknown_routes_are_refused() {
        let not_json = call(
            "POST",
            &format!("/indio/v1/merge?token={TOKEN}"),
            b"definitely not json",
        );
        assert_eq!(not_json.status, 400);
        let array_body = call(
            "POST",
            &format!("/indio/v1/merge?token={TOKEN}"),
            b"[1,2,3]",
        );
        assert_eq!(array_body.status, 400);
        assert_eq!(array_body.body["error"], "body must be a JSON object");
        assert_eq!(
            call("GET", &format!("/indio/v1/nope?token={TOKEN}"), b"").status,
            404
        );
        assert_eq!(call("OPTIONS", "/indio/v1/anything", b"").status, 200);
    }

    #[test]
    fn query_tokens_are_percent_decoded() {
        // %30 is "0": the decoded token is the real one, so it must be let in.
        let encoded = format!("%30{}", &TOKEN[1..]);
        let routed = call("GET", &format!("/indio/v1/snapshot?token={encoded}"), b"");
        assert_eq!(routed.status, 200);
        // A decoded token that still differs is refused.
        let wrong = format!("%30{}", &TOKEN[1..].replace('1', "9"));
        assert_eq!(
            call("GET", &format!("/indio/v1/snapshot?token={wrong}"), b"").status,
            401
        );
        // A trailing escape is complete, not dropped: `%00` decodes to NUL.
        assert_eq!(percent_decode("ab%00"), "ab\0");
        assert_eq!(percent_decode("a%20b+c"), "a b c");
    }

    #[test]
    fn token_comparison_is_length_and_content_checked() {
        assert!(token_ok(TOKEN, Some(TOKEN)));
        assert!(!token_ok(TOKEN, Some(&TOKEN.to_uppercase())));
        assert!(!token_ok(TOKEN, None));
        assert!(!token_ok(TOKEN, Some("")));
    }

    #[test]
    fn session_tokens_look_like_the_javascript_ones() {
        let token = session_token();
        assert_eq!(token.len(), 32);
        assert!(token.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(token, session_token());
    }

    #[test]
    fn pairing_payload_matches_the_android_contract() {
        let payload = pairing_payload("192.168.1.10", 47711, TOKEN, "Desk");
        assert_eq!(payload["indio"], "sync");
        assert_eq!(payload["v"], json!(1));
        assert_eq!(payload["host"], "192.168.1.10");
        assert_eq!(payload["port"], 47711);
        assert_eq!(payload["token"], TOKEN);
        assert_eq!(payload["name"], "Desk");
    }

    #[test]
    fn lan_address_ordering_prefers_the_home_ranges() {
        // No machine state is required for the rule itself: loopback and CGNAT
        // addresses must never be offered to a phone, and the list is
        // deduplicated IPv4 only.
        let ips = lan_addresses();
        for ip in &ips {
            let octets: Vec<u8> = ip.split('.').filter_map(|p| p.parse().ok()).collect();
            assert_eq!(octets.len(), 4, "{ip} is not IPv4");
            assert_ne!(octets[0], 127);
            if octets[0] == 100 {
                assert!(!(64..=127).contains(&octets[1]), "CGNAT address {ip} offered");
            }
        }
    }

    // ---- the whole pairing flow over a real socket -------------------------
    //
    // This is the ported `test/syncServer.test.ts`: start the server the
    // desktop would expose, run the phone's pull-and-push against it with a
    // real library store, and check that both sides converge.

    fn send(
        method: &str,
        url: &str,
        body: Option<&str>,
    ) -> (u16, Value) {
        let request = ureq::request(method, url);
        let response = match body {
            Some(body) => request.set("content-type", "application/json").send_string(body),
            None => request.call(),
        };
        match response {
            Ok(response) => {
                let status = response.status();
                let text = response.into_string().unwrap_or_default();
                (status, serde_json::from_str(&text).unwrap_or(Value::Null))
            }
            Err(ureq::Error::Status(status, response)) => {
                let text = response.into_string().unwrap_or_default();
                (status, serde_json::from_str(&text).unwrap_or(Value::Null))
            }
            Err(e) => panic!("{method} {url} failed: {e}"),
        }
    }

    #[test]
    fn a_phone_pairs_pulls_and_merges_over_the_socket() {
        use crate::library::Library;

        let dir = std::env::temp_dir().join(format!(
            "indio-sync-live-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let _ = std::fs::create_dir_all(&dir);
        let library = Arc::new(Library::open(
            dir.join("indiotube-library.json"),
            Arc::new(|_| {}),
        ));
        let phone = json!({ "videoId": "phone-like", "title": "From phone", "durationSec": 60 });
        library
            .run(
                "toggleLike",
                &[json!({ "videoId": "seed-like", "title": "Seed", "durationSec": 60 })],
            )
            .unwrap();

        let events: Arc<Mutex<Vec<Value>>> = Arc::new(Mutex::new(Vec::new()));
        let sink = Arc::clone(&events);
        let server = SyncServer::new(
            Arc::clone(&library),
            Arc::new(move |event: Value| sink.lock().unwrap().push(event)),
        );

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let started = server
            .serve(listener, "127.0.0.1", vec!["127.0.0.1".to_string()])
            .expect("the pairing server starts");
        let token = started["payload"]["token"].as_str().unwrap().to_string();
        let base = format!("http://127.0.0.1:{port}/indio/v1");

        // Discovery needs no token, and answers with the desktop's name.
        let (status, ping) = send("GET", &format!("{base}/ping"), None);
        assert_eq!(status, 200);
        assert_eq!(ping["app"], "indiotube");
        assert_eq!(ping["v"], json!(1));
        assert_eq!(ping["name"], "Desktop");

        // Everything else is guarded by the QR token.
        let (status, _) = send("GET", &format!("{base}/snapshot?token=nope"), None);
        assert_eq!(status, 401);

        // Pull: the desktop snapshot arrives wrapped exactly as the Android
        // client expects.
        let (status, pull) = send("GET", &format!("{base}/snapshot?token={token}"), None);
        assert_eq!(status, 200);
        assert_eq!(pull["ok"], true);
        assert_eq!(pull["snapshot"]["indio"], "sync");
        assert_eq!(pull["snapshot"]["library"]["liked"].as_array().unwrap().len(), 1);

        // Push: the phone's own additions merge in, add-only.
        let payload = json!({
            "indio": "sync",
            "v": 1,
            "name": "Pixel",
            "library": { "liked": [phone.clone()], "watchLater": [phone.clone()] },
            "subscriptions": [{ "channelId": "UCphone", "name": "Phone Chan" }]
        });
        let (status, merged) = send(
            "POST",
            &format!("{base}/merge?token={token}"),
            Some(&payload.to_string()),
        );
        assert_eq!(status, 200);
        assert_eq!(merged["ok"], true);
        assert_eq!(merged["stats"]["likedAdded"], 1);
        assert_eq!(merged["stats"]["watchLaterAdded"], 1);
        assert_eq!(merged["stats"]["subsAdded"], 1);

        // Both sides keep everything: the desktop now likes the phone's video
        // and still has its own.
        let liked: Vec<String> = library.state()["library"]["liked"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v["videoId"].as_str().unwrap_or_default().to_string())
            .collect();
        assert!(liked.contains(&"seed-like".to_string()) && liked.contains(&"phone-like".to_string()));

        // Merging the same payload again is a no-op.
        let (status, again) = send(
            "POST",
            &format!("{base}/merge?token={token}"),
            Some(&payload.to_string()),
        );
        assert_eq!(status, 200);
        let sum: u64 = again["stats"]
            .as_object()
            .unwrap()
            .values()
            .map(|v| v.as_u64().unwrap_or(0))
            .sum();
        assert_eq!(sum, 0);

        // Unknown routes are refused, and the modal is told what happened.
        let (status, _) = send("GET", &format!("{base}/nope?token={token}"), None);
        assert_eq!(status, 404);
        server.stop();

        // The accept loop is single-threaded, so give it a moment to publish
        // the last event before reading them back.
        std::thread::sleep(Duration::from_millis(200));
        let seen: Vec<String> = events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| e["type"].as_str().map(str::to_string))
            .collect();
        assert!(seen.contains(&"linked".to_string()), "{seen:?}");
        assert!(seen.contains(&"pulled".to_string()), "{seen:?}");
        assert!(seen.contains(&"merged".to_string()), "{seen:?}");
        let merged_event = events
            .lock()
            .unwrap()
            .iter()
            .find(|e| e["type"] == "merged")
            .cloned()
            .unwrap();
        assert_eq!(merged_event["sender"], "Pixel");
        assert_eq!(merged_event["stats"]["likedAdded"], 1);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn stopping_the_server_frees_it_for_the_next_pairing() {
        // Two pairing windows in a row must work: `start` replaces whatever is
        // running rather than failing with a taken port.
        let dir = std::env::temp_dir().join(format!("indio-sync-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let library = Arc::new(Library::open(
            dir.join("indiotube-library.json"),
            Arc::new(|_| {}),
        ));
        let server = SyncServer::new(Arc::clone(&library), Arc::new(|_| {}));

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let first = server
            .serve(listener, "127.0.0.1", vec!["127.0.0.1".to_string()])
            .unwrap();
        assert_eq!(first["port"].as_u64().unwrap() as u16, port);
        assert_eq!(server.status()["active"], true);

        // The QR payload is what the Android app scans.
        let payload = &first["payload"];
        assert_eq!(payload["indio"], "sync");
        assert_eq!(payload["host"], "127.0.0.1");
        assert_eq!(payload["port"], json!(port));

        server.stop();
        assert_eq!(server.status()["active"], false);
        let second = server
            .serve(
                TcpListener::bind("127.0.0.1:0").unwrap(),
                "127.0.0.1",
                vec!["127.0.0.1".to_string()],
            )
            .unwrap();
        assert_ne!(
            second["payload"]["token"].as_str(),
            first["payload"]["token"].as_str(),
            "each pairing gets a fresh capability"
        );
        server.stop();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_oversized_push_is_refused_at_the_http_layer() {
        // The socket reader refuses a body past the cap before the router sees
        // it, so a phone cannot make the desktop allocate gigabytes.
        let dir = std::env::temp_dir().join(format!("indio-sync-big-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let library = Arc::new(Library::open(dir.join("lib.json"), Arc::new(|_| {})));
        let server = SyncServer::new(Arc::clone(&library), Arc::new(|_| {}));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let started = server
            .serve(listener, "127.0.0.1", vec!["127.0.0.1".to_string()])
            .unwrap();
        let token = started["payload"]["token"].as_str().unwrap();

        let mut socket = TcpStream::connect(("127.0.0.1", port)).unwrap();
        socket
            .write_all(
                format!(
                    "POST /indio/v1/merge?token={token} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: {}\r\n\r\n",
                    BODY_CAP + 1
                )
                .as_bytes(),
            )
            .unwrap();
        let mut head = Vec::new();
        socket
            .set_read_timeout(Some(Duration::from_secs(5)))
            .ok();
        let mut buffer = [0u8; 128];
        if let Ok(read) = socket.read(&mut buffer) {
            head.extend_from_slice(&buffer[..read]);
        }
        let text = String::from_utf8_lossy(&head).to_lowercase();
        assert!(text.contains("413"), "expected a 413, got: {text}");
        server.stop();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_phone_pulls_the_desktop_library_through_the_library_store() {
        // The snapshot route serves the same add-only payload the merge accepts,
        // straight out of the store: this is the wire half of the phone sync.
        let dir = std::env::temp_dir().join(format!("indio-sync-snap-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let library = Arc::new(Library::open(dir.join("lib.json"), Arc::new(|_| {})));
        library
            .run(
                "subscribe",
                &[json!({ "channelId": "UCseed", "name": "Seed Chan" })],
            )
            .unwrap();
        let server = SyncServer::new(Arc::clone(&library), Arc::new(|_| {}));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let started = server
            .serve(listener, "127.0.0.1", vec!["127.0.0.1".to_string()])
            .unwrap();
        let token = started["payload"]["token"].as_str().unwrap();

        let (status, pull) = send(
            "GET",
            &format!("http://127.0.0.1:{port}/indio/v1/snapshot?token={token}"),
            None,
        );
        assert_eq!(status, 200);
        assert_eq!(pull["snapshot"]["subscriptions"].as_array().unwrap().len(), 1);
        assert_eq!(pull["snapshot"]["name"], "Desktop");
        server.stop();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
