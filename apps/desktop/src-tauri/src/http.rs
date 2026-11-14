//! HTTP transport for the InnerTube client and the media proxy, both backed by
//! `ureq` (rustls, no OpenSSL). One agent per role so connections are reused.

use std::io::Read;
use std::time::Duration;

use indio_core::innertube::{Transport, UA_DESKTOP};
use indio_core::proxy::{Upstream, UpstreamResponse};

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .user_agent(UA_DESKTOP)
        // Three attempts at 8 s each: a dead link is reported in ~25 s instead
        // of a minute, while a normal connect (well under a second) never sees
        // the timeout at all.
        .timeout_connect(std::time::Duration::from_secs(8))
        .timeout_read(std::time::Duration::from_secs(30))
        // The picture and the sound are two files on the same CDN host, and a
        // seek back re-asks for ranges that were just fetched. ureq keeps one
        // idle socket per host by default, so every second request paid for a
        // new TCP+TLS handshake - on a four-core Atom that is both latency and
        // real CPU. A small pool keeps both tracks' ranges on warm sockets.
        .max_idle_connections(16)
        .max_idle_connections_per_host(6)
        .build()
}

pub struct UreqTransport {
    agent: ureq::Agent,
}

impl UreqTransport {
    pub fn new() -> Self {
        Self { agent: agent() }
    }
}

impl Default for UreqTransport {
    fn default() -> Self {
        Self::new()
    }
}

/// Three attempts for a connection-level failure. A flaky link, a stale pooled
/// socket or a slow DNS answer should not end up on screen as an error page:
/// the request is idempotent, so trying again is always safe.
const TRANSPORT_ATTEMPTS: u32 = 3;
const TRANSPORT_BACKOFF: Duration = Duration::from_millis(200);

/// True for failures that happen before any HTTP status exists. Errors with a
/// status are answers (a 403 will not become a 200 by asking again).
fn is_transient_transport_error(message: &str) -> bool {
    let text = message.to_ascii_lowercase();
    const CODES: [&str; 7] = [
        "os error 10060", // connection attempt timed out
        "os error 10054", // connection reset by peer
        "os error 10053", // software caused connection abort
        "os error 10051", // network unreachable
        "os error 10061", // connection refused
        "os error 11001", // host not found
        "os error 11002", // transient DNS failure
    ];
    CODES.iter().any(|code| text.contains(code))
        || text.contains("timed out")
        || text.contains("timeout")
        || text.contains("network error")
        || text.contains("connection reset")
        || text.contains("connection closed")
        || text.contains("connection refused")
        || text.contains("connection attempt")
        || text.contains("unexpected eof")
        || text.contains("temporarily unavailable")
        || text.contains("could not resolve")
        || text.contains("dns")
}

/// Absolute URLs make an error longer than the screen that shows it and say
/// nothing the viewer can act on. Keep the reason, drop the URL.
fn without_urls(message: &str) -> String {
    let kept: Vec<&str> = message
        .split_whitespace()
        .filter(|word| !word.starts_with("http://") && !word.starts_with("https://"))
        .collect();
    kept.join(" ")
}

impl UreqTransport {
    fn post_once(&self, url: &str, headers: &[(String, String)], body: &str) -> Result<String, String> {
        // Debug-only fault injection for the smoke harness: reproduce the exact
        // field failure (a Windows connect timeout) without unplugging the host,
        // so the error screen and its retry can be exercised end to end.
        #[cfg(debug_assertions)]
        if std::env::var_os("INDIOTUBE_FORCE_NETWORK_ERROR").is_some() {
            return Err("Network Error: A connection attempt failed because the connected party did not properly respond after a period of time (os error 10060)".to_string());
        }
        let mut req = self.agent.post(url);
        for (key, value) in headers {
            req = req.set(key, value);
        }
        match req.send_string(body) {
            Ok(resp) => resp
                .into_string()
                .map_err(|e| format!("could not read YouTube response: {}", without_urls(&e.to_string()))),
            Err(ureq::Error::Status(code, _)) => Err(format!("YouTube returned HTTP {code}")),
            Err(e) => Err(without_urls(&format!("Could not reach YouTube: {e}"))),
        }
    }
}

/// Retry only failures that happened before an HTTP answer existed. Split out
/// from `post` so the policy itself is unit-tested without a socket.
fn retry_transport<T>(
    backoff: Duration,
    mut call: impl FnMut(u32) -> Result<T, String>,
) -> Result<T, String> {
    let mut last = String::new();
    for attempt in 0..TRANSPORT_ATTEMPTS {
        match call(attempt) {
            Ok(value) => return Ok(value),
            Err(message) => {
                let retry = is_transient_transport_error(&message)
                    && attempt + 1 < TRANSPORT_ATTEMPTS;
                if !retry {
                    return Err(message);
                }
                eprintln!(
                    "[indio] request failed, retrying ({}/{}): {}",
                    attempt + 1,
                    TRANSPORT_ATTEMPTS,
                    message
                );
                std::thread::sleep(backoff * 2u32.pow(attempt));
                last = message;
            }
        }
    }
    Err(last)
}

impl Transport for UreqTransport {
    fn post(&self, url: &str, headers: &[(String, String)], body: &str) -> Result<String, String> {
        retry_transport(TRANSPORT_BACKOFF, |_| self.post_once(url, headers, body))
    }
}

/// A defensive ceiling on one upstream body. The proxy only asks for 8 MB
/// chunks and 2 MB stream headers, so anything near this means the CDN ignored
/// the Range; reading it into memory is exactly the spike this proxy exists to
/// avoid. The one-byte slack lets the caller tell "at the limit" from "over".
const MAX_UPSTREAM_BODY: u64 = 16 * 1024 * 1024;

/// The player asks for byte windows; the CDN answers 206 with a Content-Range.
pub struct UreqUpstream {
    agent: ureq::Agent,
}

impl UreqUpstream {
    pub fn new() -> Self {
        Self { agent: agent() }
    }
}

impl Default for UreqUpstream {
    fn default() -> Self {
        Self::new()
    }
}

impl Upstream for UreqUpstream {
    fn get(&self, url: &str, range: Option<&str>) -> Result<UpstreamResponse, String> {
        let mut req = self.agent.get(url);
        if let Some(range) = range {
            req = req.set("range", range);
        }
        let resp = match req.call() {
            Ok(resp) => resp,
            // A non-2xx is still an answer: the proxy retries 5xx and passes
            // 4xx through, so keep the status instead of turning it into an
            // opaque transport error.
            Err(ureq::Error::Status(code, resp)) => {
                return Ok(UpstreamResponse {
                    status: code,
                    content_type: resp
                        .header("content-type")
                        .unwrap_or("text/plain")
                        .to_string(),
                    content_range: resp.header("content-range").map(str::to_string),
                    body: Vec::new(),
                });
            }
            Err(e) => return Err(format!("upstream request failed: {e}")),
        };
        let status = resp.status();
        let content_type = resp
            .header("content-type")
            .unwrap_or("application/octet-stream")
            .to_string();
        let content_range = resp.header("content-range").map(str::to_string);
        let mut body = Vec::new();
        resp.into_reader()
            .take(MAX_UPSTREAM_BODY + 1)
            .read_to_end(&mut body)
            .map_err(|e| format!("could not read upstream body: {e}"))?;
        if body.len() as u64 > MAX_UPSTREAM_BODY {
            return Err(format!(
                "upstream sent more than {} bytes for one range",
                MAX_UPSTREAM_BODY
            ));
        }
        Ok(UpstreamResponse {
            status,
            content_type,
            content_range,
            body,
        })
    }
}

/// Plain GET for the endpoints that are not InnerTube (suggestions,
/// SponsorBlock).
pub fn get_text(url: &str, timeout_secs: u64) -> Result<(u16, String), String> {
    let agent = ureq::AgentBuilder::new()
        .user_agent(UA_DESKTOP)
        .timeout_connect(std::time::Duration::from_secs(8))
        .timeout_read(std::time::Duration::from_secs(timeout_secs))
        .build();
    // Suggestions and SponsorBlock are best-effort, but one dropped connection
    // should not empty the suggestion list: retry connection failures once.
    let mut last = String::new();
    for attempt in 0..2 {
        match agent.get(url).call() {
            Ok(resp) => {
                let status = resp.status();
                let text = resp
                    .into_string()
                    .map_err(|e| format!("could not read response: {}", without_urls(&e.to_string())))?;
                return Ok((status, text));
            }
            Err(ureq::Error::Status(code, resp)) => {
                let text = resp.into_string().unwrap_or_default();
                return Ok((code, text));
            }
            Err(e) => {
                let message = without_urls(&format!("request failed: {e}"));
                if attempt > 0 || !is_transient_transport_error(&message) {
                    return Err(message);
                }
                std::thread::sleep(TRANSPORT_BACKOFF);
                last = message;
            }
        }
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_failures_are_transient() {
        // The exact Windows error from the field.
        assert!(is_transient_transport_error(
            "Could not reach YouTube: Network Error: Error encountered in the status line: A connection attempt failed because the connected party did not properly respond after a period of time (os error 10060)"
        ));
        assert!(is_transient_transport_error("timed out"));
        assert!(is_transient_transport_error("connection reset by peer"));
        assert!(is_transient_transport_error("Network Error"));
        assert!(is_transient_transport_error("unexpected eof"));
    }

    #[test]
    fn http_answers_are_not_retried_as_connection_failures() {
        assert!(!is_transient_transport_error("YouTube returned HTTP 403"));
        assert!(!is_transient_transport_error("YouTube returned HTTP 500"));
        assert!(!is_transient_transport_error("could not parse YouTube response"));
    }

    #[test]
    fn a_transient_failure_is_retried_and_can_succeed() {
        let mut calls = 0;
        let result = retry_transport(Duration::ZERO, |_| {
            calls += 1;
            if calls < 3 {
                Err("timed out (os error 10060)".to_string())
            } else {
                Ok("body")
            }
        });
        assert_eq!(result.unwrap(), "body");
        assert_eq!(calls, 3);
    }

    #[test]
    fn a_persistent_connection_failure_stops_after_three_attempts() {
        let mut calls = 0;
        let result: Result<(), String> = retry_transport(Duration::ZERO, |_| {
            calls += 1;
            Err("Network Error: (os error 10060)".to_string())
        });
        assert!(result.is_err());
        assert_eq!(calls, TRANSPORT_ATTEMPTS);
    }

    #[test]
    fn an_http_answer_is_never_retried_as_a_connection_failure() {
        let mut calls = 0;
        let result: Result<(), String> = retry_transport(Duration::ZERO, |_| {
            calls += 1;
            Err("YouTube returned HTTP 403".to_string())
        });
        assert!(result.is_err());
        assert_eq!(calls, 1);
    }

    #[test]
    fn error_messages_never_carry_the_request_url() {
        let message = "Could not reach YouTube: https://www.youtube.com/youtubei/v1/next?prettyPrint=false&key=secret Network Error: timed out";
        let cleaned = without_urls(message);
        assert!(!cleaned.contains("http"));
        assert!(!cleaned.contains("key=secret"));
        assert!(cleaned.contains("Network Error"));
    }
}
