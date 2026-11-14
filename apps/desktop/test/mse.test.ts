// The MSE player is driven entirely by the fragmented-MP4 index, so these pin
// the byte-offset maths against a hand-built box layout.
import { describe, expect, it } from 'vitest';
import {
  BUFFER_AHEAD_SECONDS,
  BUFFER_BEHIND_SECONDS,
  CHUNK_MAX_BYTES,
  CHUNK_MAX_SEGMENTS,
  EMERGENCY_BEHIND_SECONDS,
  EVICT_MARGIN_SECONDS,
  FETCH_ATTEMPTS,
  canUseMse,
  chunkAt,
  emergencyCutoff,
  evictionCutoff,
  mimeForCodec,
  parseTrackIndex,
  readBoxes,
  segmentIndexAt,
  shouldEvict,
} from '../src/player/mse';
import type { TrackIndex } from '../src/player/mse';

function box(type: string, payload: number): Uint8Array {
  const b = new Uint8Array(8 + payload);
  const dv = new DataView(b.buffer);
  dv.setUint32(0, b.length);
  b.set([...type].map((c) => c.charCodeAt(0)), 4);
  return b;
}

function sidx(timescale: number, refs: Array<{ size: number; dur: number }>): Uint8Array {
  const b = box('sidx', 4 + 4 + 4 + 4 + 4 + 2 + 2 + refs.length * 12);
  const dv = new DataView(b.buffer);
  let o = 8;             // box header
  dv.setUint32(o, 0); o += 4; // version + flags
  dv.setUint32(o, 1); o += 4; // reference_ID
  dv.setUint32(o, timescale); o += 4;
  dv.setUint32(o, 0); o += 4; // earliest presentation time
  dv.setUint32(o, 0); o += 4; // first_offset
  dv.setUint16(o, 0); o += 2; // reserved
  dv.setUint16(o, refs.length); o += 2;
  for (const r of refs) {
    dv.setUint32(o, r.size); o += 4;
    dv.setUint32(o, r.dur); o += 4;
    dv.setUint32(o, 0); o += 4; // SAP
  }
  return b;
}

function sampleFile(): Uint8Array {
  const parts = [
    box('ftyp', 8),
    box('moov', 16),
    sidx(1000, [{ size: 1000, dur: 1000 }, { size: 2000, dur: 2000 }, { size: 500, dur: 500 }]),
  ];
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

describe('fragmented MP4 index', () => {
  it('lists the top-level boxes in order', () => {
    const types = readBoxes(sampleFile()).map((b) => b.type);
    expect(types).toEqual(['ftyp', 'moov', 'sidx']);
  });

  it('parses the init boundary, timescale, segments and duration', () => {
    const idx = parseTrackIndex(sampleFile());
    expect(idx).not.toBeNull();
    expect(idx!.initEnd).toBe(40);          // ftyp (16) + moov (24)
    expect(idx!.timescale).toBe(1000);
    expect(idx!.segments).toHaveLength(3);
    expect(idx!.duration).toBeCloseTo(3.5, 6);
    // Offsets start right after the sidx box and accumulate by byte size.
    const sidxEnd = 40 + 68;
    expect(idx!.segments[0]).toMatchObject({ start: sidxEnd, size: 1000, time: 0, duration: 1 });
    expect(idx!.segments[1]).toMatchObject({ start: sidxEnd + 1000, size: 2000, time: 1, duration: 2 });
    expect(idx!.segments[2]).toMatchObject({ start: sidxEnd + 3000, size: 500, time: 3, duration: 0.5 });
  });

  it('finds the segment for any position, including the end', () => {
    const idx = parseTrackIndex(sampleFile())!;
    expect(segmentIndexAt(idx, 0)).toBe(0);
    expect(segmentIndexAt(idx, 0.9)).toBe(0);
    expect(segmentIndexAt(idx, 1.5)).toBe(1);
    expect(segmentIndexAt(idx, 3.2)).toBe(2);
    expect(segmentIndexAt(idx, 99)).toBe(2);
  });

  it('rejects a file without an index', () => {
    const parts = [box('ftyp', 8), box('moov', 16)];
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    expect(parseTrackIndex(out)).toBeNull();
  });
});

describe('SourceBuffer memory policy', () => {
  it('keeps half a minute on each side of the playhead, like YouTube', () => {
    // The old 40 s ahead / 90 s behind held several times more decoded media.
    expect(BUFFER_AHEAD_SECONDS).toBe(30);
    expect(BUFFER_BEHIND_SECONDS).toBe(30);
  });

  it('only evicts once there is more than the retained window behind', () => {
    expect(evictionCutoff(10)).toBeNull();
    expect(evictionCutoff(BUFFER_BEHIND_SECONDS)).toBe(0);
    expect(evictionCutoff(BUFFER_BEHIND_SECONDS + 12.5)).toBeCloseTo(12.5, 6);
    expect(evictionCutoff(600)).toBeCloseTo(570, 6);
  });

  it('does not trim a sliver on every pump round', () => {
    // A remove() is an async update that wakes the decoder, so steady playback
    // must not ask for one until a meaningful window has really fallen behind.
    expect(EVICT_MARGIN_SECONDS).toBe(5);
    expect(shouldEvict(null, 100)).toBe(false);
    expect(shouldEvict(80, null)).toBe(false);
    // 80 s buffered start vs a 100 s cutoff: 20 s behind is worth a remove.
    expect(shouldEvict(80, 100)).toBe(true);
    // 3 s behind is inside the margin: nothing worth reclaiming yet.
    expect(shouldEvict(97, 100)).toBe(false);
    // Exactly on the margin is not "more than" the margin.
    expect(shouldEvict(95, 100)).toBe(false);
    expect(shouldEvict(94.9, 100)).toBe(true);
    expect(shouldEvict(Number.NaN, 100)).toBe(false);
  });

  it('empties the buffer only when it can really reclaim the history', () => {
    expect(EMERGENCY_BEHIND_SECONDS).toBe(5);
    expect(emergencyCutoff(40, null)).toBeNull();
    // Nothing buffered before the playhead yet (the first seconds of a video).
    expect(emergencyCutoff(3, 0)).toBeNull();
    // A sliver is not what a rejected append needs.
    expect(emergencyCutoff(5.1, 0)).toBeNull();
    expect(emergencyCutoff(40, 0)).toBeCloseTo(35, 6);
    expect(emergencyCutoff(600, 12)).toBeCloseTo(595, 6);
    expect(emergencyCutoff(Number.NaN, 0)).toBeNull();
  });

  it('re-asks for a byte range that failed on its own', () => {
    // One CDN hiccup used to end the whole MSE session.
    expect(FETCH_ATTEMPTS).toBeGreaterThan(1);
  });
});

describe('batching segments into one range request', () => {
  function track(times: number[], sizes?: number[]): TrackIndex {
    let offset = 100;
    const segments = times.map((time, i) => {
      const size = sizes?.[i] ?? 10;
      const seg = { start: offset, size, time, duration: 1 };
      offset += size;
      return seg;
    });
    return { initEnd: 0, timescale: 1000, duration: times.length, segments };
  }

  it('covers every segment inside the buffer window in one chunk', () => {
    const chunk = chunkAt(track([0, 1, 2, 3]), 0, 30);
    expect(chunk).not.toBeNull();
    expect(chunk!).toMatchObject({ first: 0, count: 4, time: 0, duration: 4 });
    expect(chunk!.start).toBe(100);
    // Inclusive end of the last segment.
    expect(chunk!.end).toBe(100 + 10 * 4 - 1);
  });

  it('never reaches past the buffer-ahead horizon', () => {
    // Playhead at 0 with a 5 s segment: only the segment under it is worth
    // fetching now, so the first picture does not wait for the whole window.
    const chunk = chunkAt(track([0, 5, 10]), 0, 5);
    expect(chunk!.count).toBe(1);
  });

  it('stops at the byte and segment caps', () => {
    const big = chunkAt(track([0, 1, 2], [4, 4, 4]), 0, 30, 8);
    expect(big!.count).toBe(2);
    const many = chunkAt(track([0, 1, 2, 3, 4, 5, 6, 7, 8]), 0, 30);
    expect(many!.count).toBe(CHUNK_MAX_SEGMENTS);
    expect(CHUNK_MAX_BYTES).toBeGreaterThan(1024 * 1024);
  });

  it('stops where the byte offsets are no longer contiguous', () => {
    const index = track([0, 1, 2]);
    index.segments[2] = { ...index.segments[2], start: 9_999 };
    const chunk = chunkAt(index, 0, 30);
    expect(chunk!.count).toBe(2);
  });

  it('returns nothing past the last segment', () => {
    expect(chunkAt(track([0]), 1)).toBeNull();
    expect(chunkAt(track([]), 0)).toBeNull();
  });
});

describe('codec mapping', () => {
  it('maps avc/mp4a to mp4 mime types', () => {
    expect(mimeForCodec('avc1.4D4020', 'video')).toBe('video/mp4; codecs="avc1.4D4020"');
    expect(mimeForCodec('mp4a.40.2', 'audio')).toBe('audio/mp4; codecs="mp4a.40.2"');
  });

  it('maps vp9/opus and rejects unknown codecs', () => {
    expect(mimeForCodec('vp09.00.10.08', 'video')).toBe('video/mp4; codecs="vp09.00.10.08"');
    expect(mimeForCodec('opus', 'audio')).toBe('audio/webm; codecs="opus"');
    expect(mimeForCodec('theora', 'video')).toBeNull();
    expect(mimeForCodec('', 'audio')).toBeNull();
  });

  it('only enables MSE when both tracks and codecs are known', () => {
    expect(canUseMse({ audio: null, videoCodec: 'avc1.4D4020', audioCodec: 'mp4a.40.2' })).toBe(false);
    expect(canUseMse({ audio: 'x', videoCodec: 'avc1.4D4020' })).toBe(false);
  });
});
