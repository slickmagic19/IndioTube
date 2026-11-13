// Byte ranges: what the media proxy and the downloader need to talk to the
// CDN in pieces. Ported from the /r endpoint in electron/streams.js and from
// Android's DownloadTransfer.kt.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteRange {
    pub start: u64,
    /// Inclusive, like the HTTP Range header.
    pub end: u64,
}

impl ByteRange {
    pub fn new(start: u64, end: u64) -> Option<Self> {
        if end < start {
            return None;
        }
        Some(ByteRange { start, end })
    }

    pub fn len(&self) -> u64 {
        self.end - self.start + 1
    }

    pub fn is_empty(&self) -> bool {
        false
    }

    pub fn header(&self) -> String {
        format!("bytes={}-{}", self.start, self.end)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ContentRange {
    pub start: u64,
    pub end: u64,
    pub total: Option<u64>,
}

/// `bytes 0-1023/4096` -> the parsed range. `*` total means unknown.
pub fn parse_content_range(value: &str) -> Option<ContentRange> {
    let re = regex::Regex::new(r"(?i)bytes\s+(\d+)-(\d+)/(\d+|\*)").unwrap();
    let caps = re.captures(value)?;
    let start: u64 = caps[1].parse().ok()?;
    let end: u64 = caps[2].parse().ok()?;
    if end < start {
        return None;
    }
    let total = caps[3].parse::<u64>().ok();
    Some(ContentRange { start, end, total })
}

/// The `/r?s=…&e=…` query of the proxy. Segment fetches carry their range in
/// the path because a Range header is not CORS-safelisted.
pub fn range_from_query(s: Option<&str>, e: Option<&str>) -> Option<ByteRange> {
    let start: u64 = s?.trim().parse().ok()?;
    let end: u64 = e?.trim().parse().ok()?;
    ByteRange::new(start, end)
}

/// Split a file into `max_segments` contiguous ranges of roughly equal size.
/// Android does the same so one stalled connection cannot hold a whole file.
pub fn plan_byte_ranges(total: u64, max_segments: usize, target_segment_bytes: u64) -> Vec<ByteRange> {
    if total == 0 || max_segments == 0 || target_segment_bytes == 0 {
        return Vec::new();
    }
    let wanted = ((total + target_segment_bytes - 1) / target_segment_bytes).max(1);
    let count = (max_segments as u64).min(wanted) as usize;
    if count <= 1 {
        return vec![ByteRange { start: 0, end: total - 1 }];
    }
    let base = total / count as u64;
    let extra = (total % count as u64) as usize;
    let mut out = Vec::with_capacity(count);
    let mut start = 0;
    for index in 0..count {
        let length = base + if index < extra { 1 } else { 0 };
        out.push(ByteRange { start, end: start + length - 1 });
        start += length;
    }
    out
}

/// Re-plan around the bytes already on disk (resume after pause/restart).
pub fn plan_seeded_byte_ranges(
    total: u64,
    completed: u64,
    max_segments: usize,
    target_segment_bytes: u64,
) -> Vec<ByteRange> {
    if completed == 0 || completed >= total || max_segments <= 1 {
        return Vec::new();
    }
    let mut out = vec![ByteRange { start: 0, end: completed - 1 }];
    for r in plan_byte_ranges(total - completed, max_segments - 1, target_segment_bytes) {
        out.push(ByteRange {
            start: completed + r.start,
            end: completed + r.end,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_a_range_header() {
        let r = ByteRange::new(100, 199).unwrap();
        assert_eq!(r.header(), "bytes=100-199");
        assert_eq!(r.len(), 100);
    }

    #[test]
    fn rejects_inverted_ranges() {
        assert!(ByteRange::new(200, 100).is_none());
    }

    #[test]
    fn parses_content_range_with_and_without_total() {
        let c = parse_content_range("bytes 0-1023/4096").unwrap();
        assert_eq!((c.start, c.end, c.total), (0, 1023, Some(4096)));
        let c = parse_content_range("Bytes 5-9/*").unwrap();
        assert_eq!((c.start, c.end, c.total), (5, 9, None));
        assert!(parse_content_range("items 0-1/2").is_none());
        assert!(parse_content_range("bytes 9-5/10").is_none());
    }

    #[test]
    fn reads_the_proxy_query() {
        assert_eq!(range_from_query(Some("0"), Some("1023")), ByteRange::new(0, 1023));
        assert_eq!(range_from_query(Some("10"), Some("5")), None);
        assert_eq!(range_from_query(None, Some("5")), None);
        assert_eq!(range_from_query(Some("abc"), Some("5")), None);
    }

    #[test]
    fn plans_segments_that_cover_the_whole_file() {
        let plan = plan_byte_ranges(1000, 4, 300);
        assert_eq!(plan.len(), 4);
        assert_eq!(plan.first().unwrap().start, 0);
        assert_eq!(plan.last().unwrap().end, 999);
        for pair in plan.windows(2) {
            assert_eq!(pair[0].end + 1, pair[1].start);
        }
    }

    #[test]
    fn one_segment_when_the_file_is_small() {
        let plan = plan_byte_ranges(100, 8, 8 * 1024 * 1024);
        assert_eq!(plan, vec![ByteRange { start: 0, end: 99 }]);
    }

    #[test]
    fn seeds_a_plan_from_bytes_already_downloaded() {
        let plan = plan_seeded_byte_ranges(1000, 400, 3, 200);
        assert_eq!(plan[0], ByteRange { start: 0, end: 399 });
        assert_eq!(plan.last().unwrap().end, 999);
        assert!(plan_seeded_byte_ranges(1000, 0, 3, 200).is_empty());
        assert!(plan_seeded_byte_ranges(1000, 1000, 3, 200).is_empty());
    }

    #[test]
    fn empty_inputs_plan_nothing() {
        assert!(plan_byte_ranges(0, 4, 100).is_empty());
        assert!(plan_byte_ranges(100, 0, 100).is_empty());
        assert!(plan_byte_ranges(100, 4, 0).is_empty());
    }
}
