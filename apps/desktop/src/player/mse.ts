// A small DASH/MSE player for YouTube's fragmented MP4 streams.
//
// Why this exists: playing a video-only file in <video> and an audio-only file
// in a second <audio> element means two independent clocks and two independent
// play() states, which is what caused the skipped/delayed/missing audio. Here
// both tracks are appended to one <video> element, so the browser's own media
// pipeline keeps them sample-locked - the way YouTube itself plays.
//
// Every YouTube adaptive format is a fragmented MP4: ftyp + moov (init), a sidx
// index, then moof/mdat segments. The index gives the byte offset and duration
// of each segment, so any position can be fetched directly with a Range
// request; no manifest, no re-muxing, no ffmpeg.
import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';

export interface Segment {
  /** Absolute byte offset in the file. */
  start: number;
  size: number;
  /** Presentation time of this segment, in seconds. */
  time: number;
  duration: number;
}

export interface TrackIndex {
  /** Byte offset just past the moov box: the init segment is [0, initEnd). */
  initEnd: number;
  timescale: number;
  /** Total presentation duration in seconds. */
  duration: number;
  segments: Segment[];
}

export interface MseSource {
  video: string;
  audio: string;
  videoCodec: string;
  audioCodec: string;
}

export interface FetchRange {
  (url: string, start: number, endInclusive: number): Promise<ArrayBuffer>;
}

const u32 = (b: Uint8Array, o: number) => ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0;
const i32 = (b: Uint8Array, o: number) => (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3];
const u64 = (b: Uint8Array, o: number) => u32(b, o) * 2 ** 32 + u32(b, o + 4);
const fourcc = (b: Uint8Array, o: number) => String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);

export interface Box { type: string; start: number; end: number; header: number }

/** Walks the top-level boxes of a byte range. Truncated boxes end at the buffer. */
export function readBoxes(buf: Uint8Array, from = 0, to = buf.length): Box[] {
  const out: Box[] = [];
  let o = from;
  while (o + 8 <= to) {
    let size = u32(buf, o);
    const type = fourcc(buf, o + 4);
    let header = 8;
    if (size === 1) {
      if (o + 16 > to) break;
      size = u64(buf, o + 8);
      header = 16;
    } else if (size === 0) {
      size = to - o;
    }
    if (size < header) break;
    const end = Math.min(o + size, to);
    out.push({ type, start: o, end, header });
    if (o + size > to) break; // truncated tail
    o += size;
  }
  return out;
}

/** Parses the init segment (moov) and the sidx index out of the file head. */
export function parseTrackIndex(buf: Uint8Array): TrackIndex | null {
  const boxes = readBoxes(buf);
  const moov = boxes.find((b) => b.type === 'moov');
  const sidx = boxes.find((b) => b.type === 'sidx');
  if (!moov || !sidx || sidx.end > buf.length) return null;

  const b = buf;
  const version = b[sidx.start + 8];
  let o = sidx.start + 12; // version/flags already read
  o += 4; // reference_ID
  const timescale = u32(b, o); o += 4;
  let earliest: number;
  let firstOffset: number;
  if (version === 0) {
    earliest = u32(b, o); o += 4;
    firstOffset = u32(b, o); o += 4;
  } else {
    earliest = u64(b, o); o += 8;
    firstOffset = u64(b, o); o += 8;
  }
  o += 2; // reserved
  const count = (b[o] << 8) | b[o + 1];
  o += 2;
  if (!timescale) return null;

  const segments: Segment[] = [];
  let offset = sidx.end + firstOffset;
  let time = earliest / timescale;
  for (let i = 0; i < count; i += 1) {
    if (o + 12 > sidx.end) break;
    const ref = u32(b, o); o += 4;
    const dur = u32(b, o); o += 4;
    o += 4; // SAP flags
    const size = ref & 0x7fffffff;
    const duration = dur / timescale;
    if (size > 0) segments.push({ start: offset, size, time, duration });
    offset += size;
    time += duration;
  }
  if (!segments.length) return null;
  const end = segments[segments.length - 1];
  return {
    initEnd: moov.end,
    timescale,
    duration: end.time + end.duration,
    segments,
  };
}

/** Index of the segment that contains `t` (or the closest earlier one). */
export function segmentIndexAt(index: TrackIndex, t: number): number {
  const s = index.segments;
  let lo = 0;
  let hi = s.length - 1;
  let best = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (s[mid].time <= t + 1e-3) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return best;
}

/** Byte ranges that can be played directly from the start of the file. */
export function trackDuration(index: TrackIndex): number {
  return index.duration;
}

export function mimeForCodec(codec: string, kind: 'video' | 'audio'): string | null {
  const c = (codec || '').trim();
  if (!c) return null;
  const family = c.split('.')[0].toLowerCase();
  if (kind === 'video') {
    if (family === 'avc1' || family === 'avc3') return `video/mp4; codecs="${c}"`;
    if (family === 'vp09' || family === 'vp9') return `video/mp4; codecs="${c}"`;
    if (family === 'av01') return `video/mp4; codecs="${c}"`;
    return null;
  }
  if (family === 'mp4a') return `audio/mp4; codecs="${c}"`;
  if (family === 'opus') return `audio/webm; codecs="opus"`;
  return null;
}

export function canUseMse(source: { audio?: string | null; videoCodec?: string | null; audioCodec?: string | null }): boolean {
  if (!source.audio || !source.videoCodec || !source.audioCodec) return false;
  if (typeof MediaSource === 'undefined') return false;
  const v = mimeForCodec(source.videoCodec, 'video');
  const a = mimeForCodec(source.audioCodec, 'audio');
  if (!v || !a) return false;
  try {
    return MediaSource.isTypeSupported(v) && MediaSource.isTypeSupported(a);
  } catch {
    return false;
  }
}

interface Track {
  url: string;
  mime: string;
  index: TrackIndex;
  buffer: SourceBuffer | null;
  next: number;
  /** Bumped by every seek: a fetch or append from an older position must not
   *  advance the index the new position depends on. */
  gen: number;
}

const BUFFER_AHEAD = 40;     // seconds kept in front of the playhead
const BUFFER_BEHIND = 90;    // seconds kept behind it
const MAX_INIT_BYTES = 4 * 1024 * 1024;

/**
 * Owns one MediaSource with a SourceBuffer per track and keeps them fed.
 * The video element's own clock drives everything, so audio can never drift or
 * go missing: it is part of the same presentation.
 */
export class MseSession {
  private ms: MediaSource;
  private objectUrl: string;
  private tracks: Track[] = [];
  private disposed = false;
  private pumping = false;
  private pumpsAgain = false;
  private started = false;

  private pendingSeek: number | null = null;
  private pumpTimer = 0;

  private onSeeking = () => {
    if (this.disposed) return;
    // A seek can arrive before the first segments are in; remember it so the
    // session opens at the viewer's position instead of the resume point.
    if (!this.started) { this.pendingSeek = this.video.currentTime; return; }
    this.seek(this.video.currentTime);
  };

  /**
   * A seek lands on an unbuffered time, and the append starts a few tens of
   * milliseconds AFTER the requested position. The element then sits in a gap
   * and never advances (MSE does not skip gaps by itself), which looks like a
   * frozen player. Nudge the playhead onto the buffered start when it is a
   * sub-second hop away.
   */
  private snapForward(): void {
    const v = this.video;
    if (this.disposed || v.paused) return;
    const t = v.currentTime;
    const b = v.buffered;
    for (let i = 0; i < b.length; i += 1) {
      const start = b.start(i);
      if (start > t + 0.001 && start - t < 1.5) {
        try { v.currentTime = start; } catch { /* retry on the next tick */ }
        return;
      }
    }
  }

  private onWaiting = () => this.snapForward();

  private stallMs = 0;

  /**
   * If the element has data buffered ahead but stopped advancing (the playhead
   * is in a gap MediaSource will not cross on its own), hop onto the buffered
   * edge instead of sitting frozen. Never jumps when nothing is buffered - that
   * is an ordinary network stall the pump is already fixing.
   */
  private recoverStall(): void {
    const v = this.video;
    if (this.disposed || v.paused) { this.stallMs = 0; return; }
    if (v.readyState >= 3) { this.stallMs = 0; return; }
    this.stallMs += 600;
    if (this.stallMs < 1800) return;
    this.stallMs = 0;
    const t = v.currentTime;
    const b = v.buffered;
    for (let i = 0; i < b.length; i += 1) {
      const start = b.start(i);
      if (start > t + 0.001 && start - t < 30) {
        try { v.currentTime = start; } catch { /* next tick */ }
        return;
      }
    }
  }

  constructor(
    private video: HTMLVideoElement,
    private source: MseSource,
    private fetchRange: FetchRange,
    private onError: (reason: string) => void,
  ) {
    this.ms = new MediaSource();
    this.objectUrl = URL.createObjectURL(this.ms);
    this.video.addEventListener('seeking', this.onSeeking);
    this.video.addEventListener('waiting', this.onWaiting);
    this.video.addEventListener('stalled', this.onWaiting);
  }

  async start(atSeconds: number): Promise<void> {
    const [videoBuf, audioBuf] = await Promise.all([
      this.fetchHead(this.source.video),
      this.fetchHead(this.source.audio),
    ]);
    if (this.disposed) return;
    const videoIndex = parseTrackIndex(videoBuf);
    const audioIndex = parseTrackIndex(audioBuf);
    if (!videoIndex || !audioIndex) throw new Error('stream index unavailable');
    const videoMime = mimeForCodec(this.source.videoCodec, 'video');
    const audioMime = mimeForCodec(this.source.audioCodec, 'audio');
    if (!videoMime || !audioMime) throw new Error('unsupported codecs');

    this.tracks = [
      { url: this.source.video, mime: videoMime, index: videoIndex, buffer: null, next: 0, gen: 0 },
      { url: this.source.audio, mime: audioMime, index: audioIndex, buffer: null, next: 0, gen: 0 },
    ];

    await this.open();
    if (this.disposed) return;

    const total = Math.max(videoIndex.duration, audioIndex.duration);
    // A saved position in the last seconds means the video was finished:
    // starting there leaves a frozen frame with no audio at all.
    const wanted = this.pendingSeek == null ? atSeconds : this.pendingSeek;
    if (wanted > total - 10) {
      try { this.video.currentTime = 0; } catch { /* first segment */ }
    }
    const at = wanted > total - 10 ? 0 : wanted;
    this.pendingSeek = null;
    // Init segments first, one per track, then the segment containing `at`.
    for (const [i, track] of this.tracks.entries()) {
      const head = i === 0 ? videoBuf : audioBuf;
      await this.append(track, toArrayBuffer(head, 0, track.index.initEnd));
      track.next = segmentIndexAt(track.index, at);
    }
    // The media element attaches asynchronously; duration is only settable
    // while the MediaSource is open.
    if (this.ms.readyState === 'open') {
      try { this.ms.duration = Math.max(videoIndex.duration, audioIndex.duration); } catch { /* streams carry it */ }
    }
    this.started = true;
    if (at > 0.5) {
      try { this.video.currentTime = at; } catch { /* first segment covers 0 */ }
    }
    this.pump();
    // Keep feeding even if one pump round gives up (network hiccup, a seek that
    // arrived mid-round): without this the player could sit on a gap forever.
    this.pumpTimer = window.setInterval(() => {
      if (this.disposed) return;
      this.pump();
      this.snapForward();
      this.recoverStall();
    }, 600);
    // An element fed by MediaSource has no autoplay of its own: it must be
    // asked to play, otherwise it sits on a frozen first frame.
    try { await this.video.play(); } catch { /* blocked: a click starts it */ }
  }

  seek(t: number): void {
    if (!this.started || this.disposed) return;
    for (const track of this.tracks) {
      track.gen += 1;
      track.next = segmentIndexAt(track.index, t);
    }
    this.pump();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    try { this.video.removeEventListener('seeking', this.onSeeking); } catch { /* gone */ }
    try { this.video.removeEventListener('waiting', this.onWaiting); } catch { /* gone */ }
    try { this.video.removeEventListener('stalled', this.onWaiting); } catch { /* gone */ }
    window.clearInterval(this.pumpTimer);
    try { this.video.removeAttribute('src'); this.video.load(); } catch { /* gone */ }
    try { URL.revokeObjectURL(this.objectUrl); } catch { /* gone */ }
  }

  private async fetchHead(url: string): Promise<Uint8Array> {
    const buf = new Uint8Array(await this.fetchRange(url, 0, 256 * 1024 - 1));
    // The index must be complete before we can trust the offsets.
    const idx = parseTrackIndex(buf);
    if (idx) return buf;
    const big = new Uint8Array(await this.fetchRange(url, 0, MAX_INIT_BYTES - 1));
    if (!parseTrackIndex(big)) throw new Error('stream index unavailable');
    return big;
  }

  private open(): Promise<void> {
    return new Promise((resolve, reject) => {
      const fail = () => reject(new Error('media source failed to open'));
      this.ms.addEventListener('sourceopen', () => {
        try {
          for (const track of this.tracks) {
            if (!MediaSource.isTypeSupported(track.mime)) {
              fail();
              return;
            }
            const sb = this.ms.addSourceBuffer(track.mime);
            sb.mode = 'segments';
            sb.addEventListener('error', () => this.fail('source buffer error'));
            track.buffer = sb;
          }
          resolve();
        } catch (e) {
          reject(e as Error);
        }
      }, { once: true });
      this.ms.addEventListener('sourceclose', () => { /* teardown */ }, { once: true });
      window.      setTimeout(() => reject(new Error('media source timed out')), 8000);
      // Point the element at the MediaSource exactly once: assigning src twice
      // reloads the resource and closes the MediaSource that just opened.
      if (this.video.src !== this.objectUrl) this.video.src = this.objectUrl;
    });
  }

  private fail(reason: string): void {
    if (this.disposed) return;
    this.disposed = true;
    try { URL.revokeObjectURL(this.objectUrl); } catch { /* gone */ }
    this.onError(reason);
  }

  private async append(track: Track, data: ArrayBuffer): Promise<void> {
    const sb = track.buffer;
    if (!sb || this.disposed) return;
    // One append at a time per SourceBuffer; wait for the current update to
    // finish instead of throwing InvalidStateError.
    while (sb.updating && !this.disposed) {
      await new Promise<void>((resolve) => {
        const done = () => { sb.removeEventListener('updateend', done); resolve(); };
        sb.addEventListener('updateend', done);
        window.setTimeout(done, 2000);
      });
    }
    if (this.disposed) return;
    await new Promise<void>((resolve) => {
      const done = () => {
        sb.removeEventListener('updateend', done);
        sb.removeEventListener('error', done);
        resolve();
      };
      sb.addEventListener('updateend', done);
      sb.addEventListener('error', done);
      try { sb.appendBuffer(new Uint8Array(data)); } catch { done(); }
    });
  }

  private pump(): void {
    if (this.disposed || !this.started || this.tracks.length < 2) return;
    if (this.pumping) { this.pumpsAgain = true; return; }
    this.pumping = true;
    this.pumpsAgain = false;

    const run = async () => {
      try {
        for (let guard = 0; guard < 64; guard += 1) {
          if (this.disposed) return;
          const now = this.video.currentTime || 0;
          // Append whichever track is furthest behind the playhead first, so
          // audio never lags the picture into a gap.
          const pending = this.tracks.filter((tr) => tr.next < tr.index.segments.length);
          if (!pending.length) return;
          pending.sort((a, b) => a.index.segments[a.next].time - b.index.segments[b.next].time);
          const track = pending[0];
          const seg = track.index.segments[track.next];
          if (seg.time > now + BUFFER_AHEAD) {
            // Everything needed ahead is buffered; trim what is far behind.
            this.evict(now);
            return;
          }
          if (this.isBuffered(track, seg.time, seg.duration)) {
            track.next += 1;
            continue;
          }
          const gen = track.gen;
          const data = await this.fetchRange(track.url, seg.start, seg.start + seg.size - 1);
          if (this.disposed) return;
          // The viewer seeked again while this segment was downloading: append
          // it (still useful) but never move the new position's index past the
          // segment it needs - that used to leave the playhead in a gap with no
          // data and no sound until the next skip.
          if (track.gen !== gen) continue;
          await this.append(track, data);
          if (track.gen !== gen) continue;
          track.next += 1;
          this.snapForward();
        }
      } catch (e) {
        this.fail((e as Error).message || 'stream append failed');
      } finally {
        this.pumping = false;
        if (this.pumpsAgain && !this.disposed) this.pump();
      }
    };
    void run();
  }

  private isBuffered(track: Track, time: number, duration: number): boolean {
    const sb = track.buffer;
    if (!sb) return false;
    const b = sb.buffered;
    for (let i = 0; i < b.length; i += 1) {
      if (b.start(i) <= time + 0.05 && b.end(i) >= time + duration - 0.05) return true;
    }
    return false;
  }

  private evict(now: number): void {
    if (now < BUFFER_BEHIND) return;
    for (const track of this.tracks) {
      const sb = track.buffer;
      if (!sb || sb.updating || this.disposed) continue;
      try { sb.remove(0, now - BUFFER_BEHIND); } catch { /* retry next pump */ }
    }
  }
}

function toArrayBuffer(buf: Uint8Array, start: number, end: number): ArrayBuffer {
  return buf.buffer.slice(buf.byteOffset + start, buf.byteOffset + Math.min(end, buf.byteLength)) as ArrayBuffer;
}

/** React glue: keeps one MseSession alive for the current source. */
export function useMseSource(
  videoRef: RefObject<HTMLVideoElement | null>,
  source: MseSource | null,
  fetchRange: FetchRange,
  onError: (reason: string) => void,
  startAtRef: RefObject<number>,
): { active: boolean } {
  const [active, setActive] = useState(false);
  const sessionRef = useRef<MseSession | null>(null);
  const errorRef = useRef(onError);
  errorRef.current = onError;

  useEffect(() => {
    const v = videoRef.current;
    if (!v || !source) { setActive(false); return undefined; }
    let cancelled = false;
    const session = new MseSession(v, source, fetchRange, (reason) => {
      if (!cancelled) { setActive(false); errorRef.current(reason); }
    });
    sessionRef.current = session;
    session.start(Math.max(0, startAtRef.current || 0))
      .then(() => { if (!cancelled) setActive(true); })
      .catch((e: Error) => { if (!cancelled) { setActive(false); errorRef.current(e.message || 'mse failed'); } });
    return () => {
      cancelled = true;
      sessionRef.current = null;
      session.dispose();
      setActive(false);
    };
  }, [source, fetchRange, videoRef, startAtRef]);

  return { active };
}
