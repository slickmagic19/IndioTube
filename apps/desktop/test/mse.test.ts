// The MSE player is driven entirely by the fragmented-MP4 index, so these pin
// the byte-offset maths against a hand-built box layout.
import { describe, expect, it } from 'vitest';
import { canUseMse, mimeForCodec, parseTrackIndex, readBoxes, segmentIndexAt } from '../src/player/mse';

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
