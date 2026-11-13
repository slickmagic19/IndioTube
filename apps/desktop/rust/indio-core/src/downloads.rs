// Download queue primitives: the Rust port of electron/downloads.js.
//
// yt-dlp does the transfer; this module owns the parts worth testing on their
// own - the progress-line parser, the size parser and the retry policy that
// decides whether a failed transfer is retried or reported.

pub const MAX_ACTIVE: usize = 2;
pub const MAX_ATTEMPTS: u32 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Queued,
    Downloading,
    Paused,
    Error,
    Done,
    Cancelled,
}

impl Status {
    pub fn as_str(self) -> &'static str {
        match self {
            Status::Queued => "queued",
            Status::Downloading => "downloading",
            Status::Paused => "paused",
            Status::Error => "error",
            Status::Done => "done",
            Status::Cancelled => "cancelled",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AfterFailure {
    Retry,
    Fail,
}

/// Bounded retries: transient CDN hiccups must not fail a long download, but a
/// format the extractor does not offer must not be retried forever.
pub fn after_failure(attempts: u32) -> AfterFailure {
    if attempts < MAX_ATTEMPTS {
        AfterFailure::Retry
    } else {
        AfterFailure::Fail
    }
}

/// "15.30MiB" -> bytes. yt-dlp mixes KiB/MiB/GiB and bare "B".
pub fn parse_size(text: &str) -> u64 {
    let re = regex::Regex::new(r"([\d.]+)\s*([KMGTP]?)i?B").unwrap();
    let Some(caps) = re.captures(text) else { return 0 };
    let value: f64 = caps[1].parse().unwrap_or(0.0);
    if !value.is_finite() {
        return 0;
    }
    let power = match caps[2].to_uppercase().as_str() {
        "K" => 1,
        "M" => 2,
        "G" => 3,
        "T" => 4,
        "P" => 5,
        _ => 0,
    };
    (value * 1024f64.powi(power)).round() as u64
}

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Progress {
    pub percent: f64,
    pub total_bytes: Option<u64>,
    pub speed: Option<u64>,
    pub eta: Option<u32>,
}

/// One yt-dlp progress line -> the numbers the UI shows. Lines without a
/// percentage (Destination, Merger, warnings) return None.
pub fn parse_download_line(line: &str) -> Option<Progress> {
    if !line.contains("[download]") {
        return None;
    }
    let pct = regex::Regex::new(r"\[download\]\s+([\d.]+)%").unwrap();
    let caps = pct.captures(line)?;
    let percent: f64 = caps[1].parse().ok()?;
    let mut out = Progress {
        percent: percent.clamp(0.0, 100.0),
        ..Default::default()
    };

    if let Some(c) = regex::Regex::new(r"of\s+~?\s*([\d.]+\s*[KMGTP]?i?B)")
        .unwrap()
        .captures(line)
    {
        let bytes = parse_size(&c[1]);
        if bytes > 0 {
            out.total_bytes = Some(bytes);
        }
    }
    if let Some(c) = regex::Regex::new(r"\bat\s+~?\s*([\d.]+\s*[KMGTP]?i?B)/s")
        .unwrap()
        .captures(line)
    {
        let bytes = parse_size(&c[1]);
        if bytes > 0 {
            out.speed = Some(bytes);
        }
    }
    if let Some(c) = regex::Regex::new(r"ETA\s+(?:(\d+):)?(\d+):(\d+)")
        .unwrap()
        .captures(line)
    {
        let h: u32 = c.get(1).and_then(|m| m.as_str().parse().ok()).unwrap_or(0);
        let m: u32 = c[2].parse().unwrap_or(0);
        let s: u32 = c[3].parse().unwrap_or(0);
        out.eta = Some(h * 3600 + m * 60 + s);
    }
    Some(out)
}

/// yt-dlp's last "Destination:" line is the split stream when it downloads
/// video and audio separately, so a finished task has to look for the merged
/// file instead of trusting it.
pub fn is_split_part(file_name: &str) -> bool {
    regex::Regex::new(r"\.f\d+\.[a-z0-9]+$")
        .unwrap()
        .is_match(file_name)
}

/// Pick the merged output among the files belonging to one video id.
pub fn merged_output<'a>(files: &'a [String], video_id: &str) -> Option<&'a String> {
    let needle = format!("[{video_id}]");
    files
        .iter()
        .filter(|n| n.contains(&needle))
        .filter(|n| !is_split_part(n) && !n.ends_with(".part"))
        .next_back()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_live_progress_line() {
        let p = parse_download_line("[download]  45.2% of ~ 15.30MiB at  1.20MiB/s ETA 00:05").unwrap();
        assert_eq!(p.percent, 45.2);
        assert_eq!(p.total_bytes, Some(16043213));
        assert_eq!(p.speed, Some(1258291));
        assert_eq!(p.eta, Some(5));
    }

    #[test]
    fn parses_the_finishing_line_without_speed_or_eta() {
        let p = parse_download_line("[download] 100% of 12.34MiB in 00:03").unwrap();
        assert_eq!(p.percent, 100.0);
        assert_eq!(p.total_bytes, Some(12939428));
        assert_eq!(p.speed, None);
        assert_eq!(p.eta, None);
    }

    #[test]
    fn parses_hour_long_etas() {
        let p = parse_download_line("[download]  10.0% of 1.20GiB at 500.00KiB/s ETA 01:02:03").unwrap();
        assert_eq!(p.eta, Some(3723));
    }

    #[test]
    fn ignores_lines_without_progress() {
        assert!(parse_download_line("[download] Destination: video.mp4").is_none());
        assert!(parse_download_line("[Merger] Merging files into out.mp4").is_none());
        assert!(parse_download_line("").is_none());
    }

    #[test]
    fn parses_yt_dlp_size_units() {
        assert_eq!(parse_size("900.00KiB"), 921600);
        assert_eq!(parse_size("15.30MiB"), 16043213);
        assert_eq!(parse_size("12B"), 12);
        assert_eq!(parse_size("nonsense"), 0);
    }

    #[test]
    fn retries_are_bounded() {
        // attempts is the count *after* the failure was recorded, exactly like
        // task.attempts in the JavaScript manager: three tries, then report.
        assert_eq!(after_failure(0), AfterFailure::Retry);
        assert_eq!(after_failure(1), AfterFailure::Retry);
        assert_eq!(after_failure(2), AfterFailure::Retry);
        assert_eq!(after_failure(3), AfterFailure::Fail);
        assert_eq!(after_failure(9), AfterFailure::Fail);
    }

    #[test]
    fn finds_the_merged_file_after_a_split_download() {
        let files = vec![
            "Me at the zoo [jNQXAC9IVRw].f251.webm".to_string(),
            "Me at the zoo [jNQXAC9IVRw].mp4".to_string(),
            "unrelated [other].mp4".to_string(),
        ];
        assert!(is_split_part(&files[0]));
        assert!(!is_split_part(&files[1]));
        assert_eq!(merged_output(&files, "jNQXAC9IVRw").unwrap(), &files[1]);
        assert!(merged_output(&files, "missing").is_none());
    }

    #[test]
    fn status_strings_match_the_renderer_contract() {
        assert_eq!(Status::Downloading.as_str(), "downloading");
        assert_eq!(Status::Paused.as_str(), "paused");
    }
}
