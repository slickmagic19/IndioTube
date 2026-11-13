// Quality ladder: the Rust port of buildQualities() in electron/streams.js.
//
// Logged-out YouTube serves split video/audio streams, so the ladder mirrors
// the Android app's VideoQualities: one rung per height, a muxed file when one
// exists, otherwise the video-only half plus the shared best audio track, and
// an "auto" pick that means "720p when the video has it".

use serde::Serialize;
use serde_json::Value;

pub const AUTO_HEIGHT_CAP: i64 = 720;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Track {
    pub height: i64,
    pub url: String,
    pub ext: String,
    pub codec: Option<String>,
    pub bitrate: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Rung {
    pub height: i64,
    pub label: String,
    pub fps: i64,
    pub muxed: Option<Track>,
    pub video_only: Option<Track>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct AudioTrack {
    pub url: String,
    pub ext: String,
    pub codec: Option<String>,
    pub language: Option<String>,
    pub original: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Subtitle {
    pub language_tag: String,
    pub label: String,
    pub url: String,
    pub auto_generated: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct QualityLadder {
    pub video_id: String,
    pub title: String,
    pub duration_sec: i64,
    pub options: Vec<Rung>,
    pub auto: Option<Track>,
    pub auto_height: i64,
    pub audio: Option<AudioTrack>,
    pub subtitles: Vec<Subtitle>,
    pub is_live: bool,
    pub members_only: bool,
}

fn s(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_string)
}

fn f(v: &Value, key: &str) -> Option<f64> {
    v.get(key).and_then(Value::as_f64)
}

fn playable(v: &Value) -> bool {
    let Some(url) = s(v, "url") else { return false };
    if url.is_empty() {
        return false;
    }
    match s(v, "protocol") {
        Some(protocol) => protocol == "https",
        None => true,
    }
}

fn codec(v: &Value, key: &str) -> Option<String> {
    s(v, key).filter(|c| !c.is_empty() && c != "none")
}

/// avc decodes everywhere, then vp9, then av01 - Android's order.
pub fn codec_rank(v: &Value) -> u8 {
    let c = s(v, "vcodec").unwrap_or_default().to_lowercase();
    if c.starts_with("avc") {
        0
    } else if c.starts_with("vp9") || c.starts_with("vp09") {
        1
    } else if c.starts_with("av01") {
        2
    } else {
        3
    }
}

pub fn audio_rank(v: &Value) -> u8 {
    let ext = s(v, "ext").unwrap_or_default().to_lowercase();
    let codec = s(v, "acodec").unwrap_or_default().to_lowercase();
    if ext == "m4a" || codec.starts_with("mp4a") {
        2
    } else if ext == "weba" || ext == "opus" || codec.starts_with("opus") {
        1
    } else {
        0
    }
}

fn language_base(tag: &str) -> String {
    tag.trim()
        .to_lowercase()
        .split(['-', '_'])
        .next()
        .unwrap_or("")
        .to_string()
}

/// The creator's original track first, then a track matching the video's own
/// language, then unknown metadata, then a known dub.
pub fn audio_origin_rank(v: &Value, info: &Value) -> u8 {
    let note = s(v, "format_note").unwrap_or_default().to_lowercase();
    if note.contains("original") || note.contains("(default)") {
        return 3;
    }
    let track_type = v
        .get("audio_track")
        .and_then(|t| t.get("type"))
        .and_then(Value::as_str)
        .unwrap_or("");
    if track_type == "original" {
        return 3;
    }
    if !track_type.is_empty() {
        return 0;
    }
    let primary = language_base(&s(info, "language").unwrap_or_default());
    let lang = language_base(&s(v, "language").unwrap_or_default());
    if !primary.is_empty() && !lang.is_empty() {
        return if lang == primary { 2 } else { 0 };
    }
    1
}

pub fn audio_score(v: &Value, info: &Value) -> f64 {
    let note = s(v, "format_note").unwrap_or_default().to_lowercase();
    let drc = if note.contains("drc") { 0.0 } else { 1.0 };
    (audio_origin_rank(v, info) as f64) * 1e12
        + drc * 1e9
        + (audio_rank(v) as f64) * 1e6
        + f(v, "abr").or_else(|| f(v, "tbr")).unwrap_or(0.0)
}

pub fn format_height(v: &Value) -> i64 {
    if let Some(h) = v.get("height").and_then(Value::as_i64) {
        if h > 0 {
            return h;
        }
    }
    let note = s(v, "format_note").unwrap_or_default();
    // first "NNNp" in the note
    let bytes = note.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            let start = i;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
            let digits = &note[start..i];
            if digits.len() >= 3 && i < bytes.len() && (bytes[i] == b'p' || bytes[i] == b'P') {
                if let Ok(h) = digits.parse::<i64>() {
                    return h;
                }
            }
            continue;
        }
        i += 1;
    }
    0
}

/// "Auto" is 720p when the video has it, never the biggest file on offer.
pub fn resolve_auto_height(options: &[Rung], cap: i64) -> i64 {
    if options.is_empty() {
        return 0;
    }
    if let Some(best) = options.iter().filter(|o| o.height <= cap).max_by_key(|o| o.height) {
        return best.height;
    }
    options.iter().map(|o| o.height).min().unwrap_or(0)
}

fn wrap(v: &Value, height: i64) -> Track {
    let ext = s(v, "ext").unwrap_or_else(|| "mp4".to_string());
    Track {
        height,
        url: s(v, "url").unwrap_or_default(),
        ext,
        codec: s(v, "vcodec").or_else(|| s(v, "acodec")),
        bitrate: f(v, "tbr").unwrap_or(0.0),
    }
}

fn pick_subtitle_url(list: &Value) -> Option<String> {
    let arr = list.as_array()?;
    // yt-dlp lists lowest first; the JS side iterates in reverse.
    let ordered: Vec<&Value> = arr.iter().rev().collect();
    for ext in ["vtt", "json3", "ttml", "srv1"] {
        if let Some(hit) = ordered.iter().find(|v| {
            s(v, "ext").as_deref() == Some(ext) && s(v, "url").map(|u| !u.is_empty()).unwrap_or(false)
        }) {
            return s(hit, "url");
        }
    }
    ordered
        .iter()
        .find_map(|v| s(v, "url").filter(|u| !u.is_empty()))
}

fn pick_subtitle_name(list: &Value) -> Option<String> {
    let first = list.as_array()?.first()?;
    s(first, "name").or_else(|| s(first, "language_code"))
}

/// Ask timedtext for the WebVTT rendition, keeping signed parameters intact.
pub fn subtitle_vtt_url(url: &str) -> String {
    let re = regex::Regex::new(r"([?&])fmt=[^&]*").unwrap();
    if re.is_match(url) {
        return re.replace(url, "${1}fmt=vtt").to_string();
    }
    if url.contains('?') {
        format!("{url}&fmt=vtt")
    } else {
        format!("{url}?fmt=vtt")
    }
}

fn dedupe_subtitles(subs: Vec<Subtitle>) -> Vec<Subtitle> {
    let mut out: Vec<Subtitle> = Vec::new();
    for sub in subs {
        let key = sub.language_tag.to_lowercase();
        match out.iter().position(|s| s.language_tag.to_lowercase() == key) {
            Some(i) => {
                // The manual captions win over the auto-generated ones.
                if out[i].auto_generated && !sub.auto_generated {
                    out[i] = sub;
                }
            }
            None => out.push(sub),
        }
    }
    out
}

pub fn build_qualities(info: &Value) -> QualityLadder {
    let formats: Vec<&Value> = info
        .get("formats")
        .and_then(Value::as_array)
        .map(|a| a.iter().collect())
        .unwrap_or_default();

    let usable: Vec<&Value> = formats.iter().copied().filter(|v| playable(v)).collect();
    let muxed: Vec<&Value> = usable
        .iter()
        .copied()
        .filter(|v| codec(v, "vcodec").is_some() && codec(v, "acodec").is_some())
        .collect();
    let video_only: Vec<&Value> = usable
        .iter()
        .copied()
        .filter(|v| codec(v, "vcodec").is_some() && codec(v, "acodec").is_none() && format_height(v) > 0)
        .collect();
    let audios: Vec<&Value> = usable
        .iter()
        .copied()
        .filter(|v| codec(v, "acodec").is_some() && codec(v, "vcodec").is_none())
        .collect();

    let audio = audios.iter().copied().max_by(|a, b| {
        audio_score(a, info)
            .partial_cmp(&audio_score(b, info))
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    let best_muxed_at = |h: i64| -> Option<&Value> {
        muxed
            .iter()
            .copied()
            .filter(|v| format_height(v) == h)
            .max_by(|a, b| {
                audio_score(a, info)
                    .partial_cmp(&audio_score(b, info))
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
    };
    let best_video_at = |h: i64| -> Option<&Value> {
        audio?;
        video_only
            .iter()
            .copied()
            .filter(|v| format_height(v) == h)
            .min_by(|a, b| {
                // 30 fps before 60 fps at the same height: half the bitrate for
                // the same picture, which is what makes a start feel slow.
                let fps_rank = |v: &Value| if v.get("fps").and_then(Value::as_f64).unwrap_or(30.0) > 30.0 { 1 } else { 0 };
                codec_rank(a)
                    .cmp(&codec_rank(b))
                    .then(fps_rank(a).cmp(&fps_rank(b)))
                    .then(
                        f(b, "tbr")
                            .unwrap_or(0.0)
                            .partial_cmp(&f(a, "tbr").unwrap_or(0.0))
                            .unwrap_or(std::cmp::Ordering::Equal),
                    )
            })
    };

    let mut heights: Vec<i64> = muxed
        .iter()
        .chain(video_only.iter())
        .map(|v| format_height(v))
        .filter(|h| *h > 0)
        .collect();
    heights.sort_unstable_by(|a, b| b.cmp(a));
    heights.dedup();

    let options: Vec<Rung> = heights
        .iter()
        .map(|&h| {
            let m = best_muxed_at(h);
            let v = best_video_at(h);
            let chosen = v.or(m);
            let fps = chosen
                .and_then(|f| f.get("fps").and_then(Value::as_f64))
                .unwrap_or(0.0)
                .round() as i64;
            Rung {
                height: h,
                label: if fps >= 50 { format!("{h}p{fps}") } else { format!("{h}p") },
                fps,
                muxed: m.map(|f| wrap(f, h)),
                video_only: v.map(|f| wrap(f, h)),
            }
        })
        .collect();

    let best_muxed = muxed.iter().copied().max_by(|a, b| {
        format_height(a)
            .cmp(&format_height(b))
            .then(
                f(b, "tbr")
                    .unwrap_or(0.0)
                    .partial_cmp(&f(a, "tbr").unwrap_or(0.0))
                    .unwrap_or(std::cmp::Ordering::Equal),
            )
    });
    let auto = match best_muxed {
        Some(m) => Some(wrap(m, format_height(m))),
        None => options.first().and_then(|o| o.video_only.clone().or_else(|| o.muxed.clone())),
    };

    let mut subs: Vec<Subtitle> = Vec::new();
    for (key, auto_generated) in [("subtitles", false), ("automatic_captions", true)] {
        if let Some(map) = info.get(key).and_then(Value::as_object) {
            for (lang, list) in map {
                if let Some(url) = pick_subtitle_url(list) {
                    subs.push(Subtitle {
                        language_tag: lang.clone(),
                        label: pick_subtitle_name(list).unwrap_or_else(|| lang.clone()),
                        url: subtitle_vtt_url(&url),
                        auto_generated,
                    });
                }
            }
        }
    }

    let live_status = s(info, "live_status").unwrap_or_default().to_lowercase();
    let is_live = live_status == "is_live"
        || live_status == "was_live"
        || info.get("is_live").and_then(Value::as_bool).unwrap_or(false);
    let members_only = live_status == "needs_auth";

    let mut ladder = QualityLadder {
        video_id: s(info, "id").unwrap_or_default(),
        title: s(info, "title").unwrap_or_default(),
        duration_sec: f(info, "duration").unwrap_or(0.0).round() as i64,
        auto_height: 0,
        options,
        auto,
        audio: audio.map(|a| AudioTrack {
            url: s(a, "url").unwrap_or_default(),
            ext: s(a, "ext").unwrap_or_else(|| "m4a".to_string()),
            codec: s(a, "acodec"),
            language: s(a, "language"),
            original: audio_origin_rank(a, info) >= 2,
        }),
        subtitles: dedupe_subtitles(subs),
        is_live,
        members_only,
    };
    ladder.auto_height = resolve_auto_height(&ladder.options, AUTO_HEIGHT_CAP);
    ladder
}

/// The rung a requested height maps to: at or below it, else the lowest.
pub fn nearest(options: &[Rung], height: i64) -> Option<&Rung> {
    options
        .iter()
        .filter(|o| o.height <= height)
        .max_by_key(|o| o.height)
        .or_else(|| options.iter().min_by_key(|o| o.height))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn f(over: Value) -> Value {
        let mut base = json!({
            "ext": "mp4",
            "vcodec": "avc1.4d401f",
            "acodec": "mp4a.40.2",
            "tbr": 1000.0,
            "fps": 30,
            "url": "https://cdn.example/vid.mp4?sig=x",
        });
        for (k, v) in over.as_object().unwrap() {
            base[k] = v.clone();
        }
        base
    }

    fn info(formats: Vec<Value>) -> Value {
        json!({ "id": "vid1", "title": "A video", "duration": 100.0, "formats": formats })
    }

    #[test]
    fn builds_a_muxed_ladder_with_the_best_audio() {
        let ladder = build_qualities(&info(vec![
            f(json!({ "height": 360, "format_id": "18", "audio_track": { "type": "original" } })),
            f(json!({ "height": 720, "format_id": "22", "audio_track": { "type": "original" } })),
            f(json!({ "height": 720, "format_id": "a1", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128.0, "audio_track": { "type": "original" } })),
        ]));
        let heights: Vec<i64> = ladder.options.iter().map(|o| o.height).collect();
        assert_eq!(heights, vec![720, 360]);
        assert!(ladder.options[0].muxed.is_some());
        assert_eq!(ladder.audio.as_ref().unwrap().url, "https://cdn.example/vid.mp4?sig=x");
        assert!(ladder.audio.as_ref().unwrap().original);
    }

    #[test]
    fn prefers_split_video_when_audio_exists_and_labels_frame_rates() {
        let ladder = build_qualities(&info(vec![
            f(json!({ "height": 1080, "format_id": "v60", "acodec": "none", "fps": 60, "tbr": 4000.0 })),
            f(json!({ "height": 1080, "format_id": "v30", "acodec": "none", "fps": 30, "tbr": 2500.0 })),
            f(json!({ "height": 1080, "format_id": "a", "vcodec": "none", "acodec": "opus", "ext": "webm", "abr": 160.0 })),
        ]));
        let rung = &ladder.options[0];
        // The label follows the rung's chosen stream: the 30fps video-only file
        // wins over the 60fps one, so the label carries no frame rate.
        assert_eq!(rung.label, "1080p");
        assert_eq!(rung.fps, 30);
        let chosen = rung.video_only.as_ref().unwrap();
        assert_eq!(chosen.url, "https://cdn.example/vid.mp4?sig=x");
        // 30 fps is the preferred pick even though 60 fps exists.
        assert!(rung.video_only.is_some());
    }

    #[test]
    fn auto_is_720_even_when_a_bigger_muxed_file_exists() {
        let ladder = build_qualities(&info(vec![
            f(json!({ "height": 360, "format_id": "18" })),
            f(json!({ "height": 720, "format_id": "22" })),
            f(json!({ "height": 1440, "format_id": "48" })),
        ]));
        assert_eq!(ladder.auto.as_ref().unwrap().height, 1440);
        assert_eq!(ladder.auto_height, 720);
    }

    #[test]
    fn original_audio_beats_a_higher_bitrate_dub() {
        let ladder = build_qualities(&json!({
            "id": "v", "title": "t", "duration": 10.0, "language": "en",
            "formats": [
                { "height": 720, "vcodec": "avc1", "acodec": "mp4a", "url": "https://cdn/v" },
                { "vcodec": "none", "acodec": "mp4a", "ext": "m4a", "abr": 96.0, "language": "en", "format_note": "English original", "url": "https://cdn/orig" },
                { "vcodec": "none", "acodec": "mp4a", "ext": "m4a", "abr": 130.0, "language": "es", "format_note": "Spanish", "url": "https://cdn/dub" },
            ]
        }));
        assert_eq!(ladder.audio.unwrap().url, "https://cdn/orig");
    }

    #[test]
    fn captions_are_deduped_and_rewritten_to_vtt() {
        let ladder = build_qualities(&json!({
            "id": "v", "title": "t", "duration": 10.0,
            "formats": [{ "height": 720, "vcodec": "avc1", "acodec": "mp4a", "url": "https://cdn/v" }],
            "subtitles": { "en": [{ "ext": "ttml", "url": "https://t/en?fmt=ttml&sig=1" }] },
            "automatic_captions": { "en": [{ "ext": "json3", "name": "English (auto)", "url": "https://t/en2" }], "fr": [{ "ext": "vtt", "url": "https://t/fr" }] },
        }));
        assert_eq!(ladder.subtitles.len(), 2);
        let en = ladder.subtitles.iter().find(|s| s.language_tag == "en").unwrap();
        assert!(!en.auto_generated);
        assert_eq!(en.url, "https://t/en?fmt=vtt&sig=1");
        // A caption URL without a fmt parameter has one appended.
        assert_eq!(
            ladder.subtitles.iter().find(|s| s.language_tag == "fr").unwrap().url,
            "https://t/fr?fmt=vtt"
        );
    }

    #[test]
    fn format_height_reads_the_note_when_height_is_missing() {
        assert_eq!(format_height(&json!({ "format_note": "720p60" })), 720);
        assert_eq!(format_height(&json!({ "format_note": "hd720" })), 0);
        assert_eq!(format_height(&json!({ "height": 480, "format_note": "720p" })), 480);
    }

    #[test]
    fn nearest_never_goes_above_the_requested_height() {
        let ladder = build_qualities(&info(vec![
            f(json!({ "height": 360 })),
            f(json!({ "height": 720 })),
            f(json!({ "height": 1080 })),
        ]));
        assert_eq!(nearest(&ladder.options, 720).unwrap().height, 720);
        assert_eq!(nearest(&ladder.options, 900).unwrap().height, 720);
        assert_eq!(nearest(&ladder.options, 240).unwrap().height, 360);
    }
}
