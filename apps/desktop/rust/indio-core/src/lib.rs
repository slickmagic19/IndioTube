//! Rust core for the IndioTube desktop app.
//!
//! The pieces that the Electron main process currently holds in JavaScript,
//! ported with the same rules and covered by the same kind of tests:
//!
//! * [`quality`] - yt-dlp's format list turned into the Android-shaped ladder
//!   (muxed rungs where they exist, split video + best audio otherwise, 720p
//!   "auto", deduped captions).
//! * [`downloads`] - progress-line parsing, size units and the bounded retry
//!   policy behind the download queue.
//! * [`range`] - byte ranges for the media proxy and the segmented downloader.

pub mod downloads;
pub mod innertube;
pub mod quality;
pub mod proxy;
pub mod range;

pub use innertube::{
    build_search_params, default_config, parse_feed, parse_search, parse_video_renderer, ClientError, Config,
    Feed, InnerTube, SearchEntry, SearchFilters, SearchResults, Transport, VideoItem,
};
pub use proxy::{json3_to_vtt, ttml_to_vtt, Proxy, Request, Response, Upstream, UpstreamResponse};
pub use downloads::{after_failure, parse_download_line, parse_size, AfterFailure, Progress, Status};
pub use quality::{
    build_qualities, nearest, resolve_auto_height, subtitle_vtt_url, AudioTrack, QualityLadder,
    Rung, Subtitle, Track, AUTO_HEIGHT_CAP,
};
pub use range::{
    parse_content_range, plan_byte_ranges, plan_seeded_byte_ranges, range_from_query, ByteRange,
    ContentRange,
};
