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
import type { MutableRefObject, RefObject } from 'react';
import { releaseMediaElement } from './mediaLifecycle';

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
  (url: string, start: number, endInclusive: number, signal?: AbortSignal): Promise<ArrayBuffer>;
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

// Chromium's decoded media plus these SourceBuffer windows is the player's
// biggest memory cost. YouTube itself keeps roughly half a minute on each side
// of the playhead; holding 40 s ahead and 90 s behind was four times that and
// is what made memory climb the longer a video stayed open. The windows are
// exported so the policy is pinned by a unit test.
export const BUFFER_AHEAD_SECONDS = 30;
export const BUFFER_BEHIND_SECONDS = 30;
const MAX_INIT_BYTES = 2 * 1024 * 1024;

/**
 * A byte range can fail on its own (a CDN 5xx, a socket reset, a proxy hiccup)
 * long before the stream as a whole is unhealthy. Re-asking for that one range
 * keeps the session alive where a single failure used to end it and drop the
 * viewer into the two-element fallback. Pinned by a unit test.
 */
export const FETCH_ATTEMPTS = 2;
export const FETCH_RETRY_MS = 250;

/**
 * Don't start a SourceBuffer `remove` for a sliver: every trim is an async
 * update that wakes the media pipeline and its decoder. Waiting until at least
 * this much has fallen out of the retained window keeps steady playback from
 * running a remove every single pump round. Pinned by a unit test.
 */
export const EVICT_MARGIN_SECONDS = 5;

/** Media kept behind the playhead when a full buffer rejects an append. */
export const EMERGENCY_BEHIND_SECONDS = 5;

/**
 * A segment is one sidx entry - usually a few seconds, and on a high-latency
 * link each one costs a full round trip. Fetching several adjacent segments in
 * one range request keeps the pipe full instead of idling a whole RTT per
 * segment, and appending them together means fewer SourceBuffer updates (each
 * one wakes the decoder). Bounded so one chunk still fits the proxy cache and
 * never holds much more than the buffer window in memory.
 */
export const CHUNK_MAX_BYTES = 8 * 1024 * 1024;
export const CHUNK_MAX_SEGMENTS = 8;

/** A contiguous run of segments that one range request can deliver. */
export interface Chunk {
  /** Index of the first segment in the run. */
  first: number;
  count: number;
  /** Inclusive byte range of the whole run. */
  start: number;
  end: number;
  /** Presentation time of the first segment and the run's total duration. */
  time: number;
  duration: number;
}

/**
 * The largest run of contiguous segments worth one request, starting at
 * `from`. `untilTime` is the buffer-ahead horizon: a chunk never reaches past
 * it, so the retained window stays bounded. Returns null when `from` is past
 * the end.
 */
export function chunkAt(
  index: TrackIndex,
  from: number,
  untilTime = Number.POSITIVE_INFINITY,
  maxBytes = CHUNK_MAX_BYTES,
  maxSegments = CHUNK_MAX_SEGMENTS,
): Chunk | null {
  const segments = index.segments;
  if (from < 0 || from >= segments.length) return null;
  const first = segments[from];
  let count = 1;
  let bytes = first.size;
  while (from + count < segments.length && count < maxSegments) {
    const prev = segments[from + count - 1];
    const next = segments[from + count];
    // The index accumulates offsets, so this holds; a gap would mean the range
    // no longer maps onto the fragment that was asked for.
    if (next.start !== prev.start + prev.size) break;
    if (bytes + next.size > maxBytes) break;
    if (next.time + next.duration > untilTime) break;
    bytes += next.size;
    count += 1;
  }
  const last = segments[from + count - 1];
  return {
    first: from,
    count,
    start: first.start,
    end: last.start + last.size - 1,
    time: first.time,
    duration: last.time + last.duration - first.time,
  };
}

/** Byte offset below which buffered data can be dropped, or null to keep it. */
export function evictionCutoff(now: number, behind = BUFFER_BEHIND_SECONDS): number | null {
  return now >= behind ? Math.max(0, now - behind) : null;
}

/**
 * True only when a trim would really reclaim a useful chunk of media. `remove`
 * on an already-empty range still costs an update cycle, so a null/unknown
 * buffered start is a no-op.
 */
export function shouldEvict(
  bufferedStart: number | null,
  cutoff: number | null,
  margin = EVICT_MARGIN_SECONDS,
): boolean {
  if (bufferedStart == null || cutoff == null) return false;
  if (!Number.isFinite(bufferedStart) || !Number.isFinite(cutoff)) return false;
  return bufferedStart < cutoff - margin;
}

/**
 * Aggressive cut used after a failed append (usually a full SourceBuffer).
 * Returns null when there is nothing behind the playhead left to reclaim.
 */
export function emergencyCutoff(
  now: number,
  bufferedStart: number | null,
  behind = EMERGENCY_BEHIND_SECONDS,
): number | null {
  if (bufferedStart == null || !Number.isFinite(bufferedStart) || !Number.isFinite(now)) return null;
  const cutoff = Math.max(0, now - behind);
  return cutoff > bufferedStart + 0.25 ? cutoff : null;
}

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
  /**
   * False until the fetch the viewer is waiting for has landed. The first
   * fetch after a start or a seek asks for one segment only, so the picture
   * appears after the least possible number of bytes; everything after it
   * batches several segments per request.
   */
  private primed = false;
  /** Cancels every in-flight byte request when this session is replaced. */
  private abort = new AbortController();
  private fetchedBytes = 0;
  private fetchedRequests = 0;

  /**
   * The element itself can fail (decoder error, MediaSource closed by the
   * shell). Without this the player would sit on a frozen frame forever in MSE
   * mode because `srcs` is null, so the JSX `onError` fallback never fires.
   */
  private onMediaError = () => {
    if (this.disposed) return;
    // Code 1 is "aborted", which is normal while the element is re-pointed at
    // a new resource during a source swap.
    if (this.video.error?.code === 1) return;
    this.fail('media element error');
  };

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
    this.video.addEventListener('error', this.onMediaError);
  }

  async start(atSeconds: number): Promise<void> {
    // Opening the MediaSource is independent of downloading the two stream
    // headers, so start both now: the element is ready by the time the index
    // arrives instead of adding the source-open delay on top of the network.
    const opening = this.open();
    // If a header fetch fails first, `opening` is never awaited; keep its
    // eventual failure from surfacing as an unhandled rejection.
    opening.catch(() => undefined);
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

    await opening;
    if (this.disposed) return;

    // SourceBuffers can only be added once the MediaSource is open, and the
    // tracks only exist once both indexes have parsed. Adding them here keeps
    // the source-open and header-download overlap above without the race where
    // `sourceopen` fired while `this.tracks` was still empty and the session
    // sat with no buffers at all (a forever-black player).
    for (const track of this.tracks) {
      if (!MediaSource.isTypeSupported(track.mime)) throw new Error('unsupported codecs');
      const sb = this.ms.addSourceBuffer(track.mime);
      sb.mode = 'segments';
      sb.addEventListener('error', () => this.fail('source buffer error'));
      track.buffer = sb;
    }

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
    // The two tracks have their own SourceBuffers, so the appends can overlap.
    const inits = await Promise.all(this.tracks.map((track, i) => {
      const head = i === 0 ? videoBuf : audioBuf;
      return this.append(track, toArrayBuffer(head, 0, track.index.initEnd));
    }));
    if (this.disposed) return;
    // Without its init segment a SourceBuffer rejects every media segment, so
    // the session would sit on a black frame instead of letting the player fall
    // back to the two-element path.
    if (inits.some((ok) => !ok)) throw new Error('stream init failed');
    for (const track of this.tracks) {
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
    }, 500);
    // An element fed by MediaSource has no autoplay of its own: it must be
    // asked to play, otherwise it sits on a frozen first frame.
    try { await this.video.play(); } catch { /* blocked: a click starts it */ }
  }

  /** Bytes fetched by this session, for the Stats for nerds overlay. */
  stats(): { fetchedBytes: number; fetchedRequests: number } {
    return { fetchedBytes: this.fetchedBytes, fetchedRequests: this.fetchedRequests };
  }

  seek(t: number): void {
    if (!this.started || this.disposed) return;
    for (const track of this.tracks) {
      track.gen += 1;
      track.next = segmentIndexAt(track.index, t);
    }
    // The segment under the new playhead is what the viewer is waiting for;
    // batch again once it is on screen.
    this.primed = false;
    this.pump();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.teardown();
    // Pause, detach and reload: the revoked MediaSource must not leave the
    // element holding a decoder and its buffers for the next video's sake.
    releaseMediaElement(this.video);
    try { URL.revokeObjectURL(this.objectUrl); } catch { /* gone */ }
  }

  /**
   * Stops everything that can outlive the session. A SourceBuffer failure used
   * to leave the 500 ms pump interval and the element listeners running for the
   * rest of the app session, because `dispose()` returned early once `fail()`
   * had marked the session disposed.
   */
  private teardown(): void {
    // Stop downloading segments for a video that is no longer on screen; a
    // fast skip used to leave several megabytes arriving into a dead session.
    try { this.abort.abort(); } catch { /* already gone */ }
    try { this.video.removeEventListener('seeking', this.onSeeking); } catch { /* gone */ }
    try { this.video.removeEventListener('waiting', this.onWaiting); } catch { /* gone */ }
    try { this.video.removeEventListener('stalled', this.onWaiting); } catch { /* gone */ }
    try { this.video.removeEventListener('error', this.onMediaError); } catch { /* gone */ }
    window.clearInterval(this.pumpTimer);
    this.pumpTimer = 0;
  }

  private async fetchBytes(url: string, start: number, endInclusive: number): Promise<ArrayBuffer> {
    let last: unknown = null;
    for (let attempt = 0; attempt < FETCH_ATTEMPTS; attempt += 1) {
      if (this.disposed) throw new Error('session disposed');
      try {
        const data = await this.fetchRange(url, start, endInclusive, this.abort.signal);
        this.fetchedBytes += data.byteLength;
        this.fetchedRequests += 1;
        return data;
      } catch (e) {
        last = e;
        // A cancelled request means the viewer moved on; asking again would
        // only queue work nobody is waiting for.
        if (this.abort.signal.aborted || (e as Error)?.name === 'AbortError') throw e;
        if (attempt + 1 < FETCH_ATTEMPTS) await sleep(FETCH_RETRY_MS);
      }
    }
    throw last instanceof Error ? last : new Error('stream range failed');
  }

  private async fetchHead(url: string): Promise<Uint8Array> {
    const buf = new Uint8Array(await this.fetchBytes(url, 0, 256 * 1024 - 1));
    // The index must be complete before we can trust the offsets.
    const idx = parseTrackIndex(buf);
    if (idx) return buf;
    const big = new Uint8Array(await this.fetchBytes(url, 0, MAX_INIT_BYTES - 1));
    if (!parseTrackIndex(big)) throw new Error('stream index unavailable');
    return big;
  }

  private open(): Promise<void> {
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error('media source timed out')), 8000);
      this.ms.addEventListener('sourceopen', () => {
        window.clearTimeout(timer);
        resolve();
      }, { once: true });
      this.ms.addEventListener('sourceclose', () => {
        window.clearTimeout(timer);
        reject(new Error('media source closed'));
      }, { once: true });
      // Point the element at the MediaSource exactly once: assigning src twice
      // reloads the resource and closes the MediaSource that just opened.
      if (this.video.src !== this.objectUrl) this.video.src = this.objectUrl;
    });
  }

  private fail(reason: string): void {
    if (this.disposed) return;
    this.disposed = true;
    this.teardown();
    try { URL.revokeObjectURL(this.objectUrl); } catch { /* gone */ }
    this.onError(reason);
  }

  /** Appends one segment and reports whether the SourceBuffer accepted it. */
  private async append(track: Track, data: ArrayBuffer): Promise<boolean> {
    const sb = track.buffer;
    if (!sb || this.disposed) return false;
    // One append at a time per SourceBuffer; wait for the current update to
    // finish instead of throwing InvalidStateError.
    while (sb.updating && !this.disposed) {
      await new Promise<void>((resolve) => {
        const done = () => { sb.removeEventListener('updateend', done); resolve(); };
        sb.addEventListener('updateend', done);
        window.setTimeout(done, 2000);
      });
    }
    if (this.disposed) return false;
    return await new Promise<boolean>((resolve) => {
      let settled = false;
      const finish = (ok: boolean) => {
        if (settled) return;
        settled = true;
        sb.removeEventListener('updateend', onEnd);
        sb.removeEventListener('error', onError);
        resolve(ok);
      };
      const onEnd = () => finish(true);
      const onError = () => finish(false);
      sb.addEventListener('updateend', onEnd);
      sb.addEventListener('error', onError);
      // appendBuffer accepts the ArrayBuffer directly; wrapping it in another
      // Uint8Array copied every segment before it was handed to the decoder.
      // A throw (usually QuotaExceededError on a full buffer) must not be
      // treated as success: that used to skip the segment and leave a gap.
      try { sb.appendBuffer(data); } catch { finish(false); }
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
          // Retained data needs no second copy: jump the index past anything
          // the element already holds (a seek back into the window).
          let skipped = false;
          for (const track of this.tracks) {
            while (track.next < track.index.segments.length) {
              const s = track.index.segments[track.next];
              if (!this.isBuffered(track, s.time, s.duration)) break;
              track.next += 1;
              skipped = true;
            }
          }
          // Append whichever track is furthest behind the playhead first, so
          // audio never lags the picture into a gap.
          const pending = this.tracks.filter((tr) => tr.next < tr.index.segments.length);
          if (!pending.length) return;
          if (skipped) continue; // the order may have changed
          pending.sort((a, b) => a.index.segments[a.next].time - b.index.segments[b.next].time);
          const track = pending[0];
          const seg = track.index.segments[track.next];
          if (seg.time > now + BUFFER_AHEAD_SECONDS) {
            // Everything needed ahead is buffered; trim what is far behind.
            this.evict(now);
            return;
          }
          // One range request for the whole run of segments the window needs:
          // a round trip per segment leaves the pipe idle, and every extra
          // SourceBuffer update wakes the decoder. The first fetch stays small
          // so the first picture does not wait for the whole window.
          const until = this.primed
            ? now + BUFFER_AHEAD_SECONDS
            : seg.time + Math.max(seg.duration, 1);
          const chunk = chunkAt(track.index, track.next, until);
          if (!chunk) return;
          const gen = track.gen;
          const data = await this.fetchBytes(track.url, chunk.start, chunk.end);
          if (this.disposed) return;
          // The viewer seeked again while this chunk was downloading: append
          // it (still useful) but never move the new position's index past the
          // segment it needs - that used to leave the playhead in a gap with no
          // data and no sound until the next skip.
          if (track.gen !== gen) continue;
          let appended = await this.append(track, data);
          if (!appended && !this.disposed) {
            // A full SourceBuffer rejects the append. Drop what has already
            // played and offer the same bytes again; advancing past a
            // rejected segment leaves a permanent hole in the timeline.
            this.reclaim(track, this.video.currentTime || 0);
            appended = await this.append(track, data);
          }
          if (this.disposed) return;
          if (!appended) throw new Error('stream append failed');
          if (track.gen !== gen) continue;
          track.next = chunk.first + chunk.count;
          this.primed = true;
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

  private bufferedStart(sb: SourceBuffer): number | null {
    const b = sb.buffered;
    return b.length ? b.start(0) : null;
  }

  /** Drops everything but a few seconds of history after a rejected append. */
  private reclaim(track: Track, now: number): void {
    const sb = track.buffer;
    if (!sb || sb.updating || this.disposed) return;
    const cutoff = emergencyCutoff(now, this.bufferedStart(sb));
    if (cutoff == null) return;
    try { sb.remove(0, cutoff); } catch { /* the retry reports the failure */ }
  }

  private evict(now: number): void {
    const cutoff = evictionCutoff(now, BUFFER_BEHIND_SECONDS);
    if (cutoff == null) return;
    for (const track of this.tracks) {
      const sb = track.buffer;
      if (!sb || sb.updating || this.disposed) continue;
      // Only trim when a meaningful window has really fallen behind. Without
      // this, steady playback asked every SourceBuffer for a remove on each
      // 500 ms pump, even when there was nothing to reclaim.
      if (!shouldEvict(this.bufferedStart(sb), cutoff)) continue;
      try { sb.remove(0, cutoff); } catch { /* retry next pump */ }
    }
  }
}

function toArrayBuffer(buf: Uint8Array, start: number, end: number): ArrayBuffer {
  return buf.buffer.slice(buf.byteOffset + start, buf.byteOffset + Math.min(end, buf.byteLength)) as ArrayBuffer;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/** React glue: keeps one MseSession alive for the current source. */
export function useMseSource(
  videoRef: RefObject<HTMLVideoElement | null>,
  source: MseSource | null,
  fetchRange: FetchRange,
  onError: (reason: string) => void,
  startAtRef: RefObject<number>,
): { active: boolean; sessionRef: MutableRefObject<MseSession | null> } {
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

  return { active, sessionRef };
}
