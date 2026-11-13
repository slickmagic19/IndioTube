//! HTTP transport for the InnerTube client and the media proxy, both backed by
//! `ureq` (rustls, no OpenSSL). One agent per role so connections are reused.

use std::io::Read;

use indio_core::innertube::{Transport, UA_DESKTOP};
use indio_core::proxy::{Upstream, UpstreamResponse};

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .user_agent(UA_DESKTOP)
        .timeout_connect(std::time::Duration::from_secs(10))
        .timeout_read(std::time::Duration::from_secs(30))
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

impl Transport for UreqTransport {
    fn post(&self, url: &str, headers: &[(String, String)], body: &str) -> Result<String, String> {
        let mut req = self.agent.post(url);
        for (key, value) in headers {
            req = req.set(key, value);
        }
        match req.send_string(body) {
            Ok(resp) => resp
                .into_string()
                .map_err(|e| format!("could not read YouTube response: {e}")),
            Err(ureq::Error::Status(code, _)) => Err(format!("YouTube returned HTTP {code}")),
            Err(e) => Err(format!("YouTube request failed: {e}")),
        }
    }
}

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
            .read_to_end(&mut body)
            .map_err(|e| format!("could not read upstream body: {e}"))?;
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
    match agent.get(url).call() {
        Ok(resp) => {
            let status = resp.status();
            let text = resp
                .into_string()
                .map_err(|e| format!("could not read response: {e}"))?;
            Ok((status, text))
        }
        Err(ureq::Error::Status(code, resp)) => {
            let text = resp.into_string().unwrap_or_default();
            Ok((code, text))
        }
        Err(e) => Err(format!("request failed: {e}")),
    }
}
