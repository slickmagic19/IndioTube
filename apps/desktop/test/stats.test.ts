// The numbers and labels behind "Stats for nerds".
import { describe, expect, it } from 'vitest';
import {
  bufferAheadSeconds,
  buildStatsRows,
  estimateBitrate,
  formatBitrate,
  formatBytes,
  formatResolution,
  frameDropPercent,
  type VideoStatsSnapshot,
} from '../src/player/stats';

describe('buffer health', () => {
  it('measures only the range the playhead is inside', () => {
    const ranges = [{ start: 0, end: 12.5 }, { start: 40, end: 60 }];
    expect(bufferAheadSeconds(ranges, 2)).toBeCloseTo(10.5, 6);
    expect(bufferAheadSeconds(ranges, 45)).toBeCloseTo(15, 6);
  });

  it('is zero in a gap, before the first byte or with no buffer at all', () => {
    const ranges = [{ start: 10, end: 20 }];
    expect(bufferAheadSeconds(ranges, 15.5)).toBeCloseTo(4.5, 6);
    expect(bufferAheadSeconds([{ start: 0, end: 5 }, { start: 10, end: 20 }], 7)).toBe(0);
    expect(bufferAheadSeconds([{ start: 10, end: 20 }], -3)).toBe(0);
    expect(bufferAheadSeconds([], 3)).toBe(0);
    expect(bufferAheadSeconds([], Number.NaN)).toBe(0);
  });

  it('clamps a playhead just past the end instead of going negative', () => {
    expect(bufferAheadSeconds([{ start: 0, end: 10 }], 10.04)).toBe(0);
  });
});

describe('network numbers', () => {
  it('turns bytes over time into a rate', () => {
    expect(estimateBitrate(1_000_000, 2)).toBe(500_000);
    expect(estimateBitrate(0, 2)).toBeNull();
    expect(estimateBitrate(100, 0)).toBeNull();
    expect(estimateBitrate(Number.NaN, 2)).toBeNull();
  });

  it('formats a bitrate at the right magnitude', () => {
    expect(formatBitrate(500_000)).toBe('4.00 Mbps');
    expect(formatBitrate(100_000)).toBe('800.0 Kbps');
    expect(formatBitrate(10)).toBe('80 bps');
    expect(formatBitrate(null)).toBe('—');
    expect(formatBitrate(0)).toBe('—');
  });

  it('formats byte counts compactly', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(1.5 * 1024 * 1024)).toBe('1.5 MB');
    expect(formatBytes(2 * 1024 * 1024 * 1024)).toBe('2.00 GB');
    expect(formatBytes(-1)).toBe('—');
    expect(formatBytes(undefined)).toBe('—');
  });
});

describe('video and frame labels', () => {
  it('formats the resolution with an optional frame rate', () => {
    expect(formatResolution(1280, 720, 30)).toBe('1280x720@30');
    expect(formatResolution(1920, 1080)).toBe('1920x1080');
    expect(formatResolution(0, 0, 30)).toBe('—');
  });

  it('reports dropped frames as a bounded percentage', () => {
    expect(frameDropPercent(5, 1000)).toBeCloseTo(0.5, 6);
    expect(frameDropPercent(0, 0)).toBe(0);
    expect(frameDropPercent(2000, 1000)).toBe(100);
  });
});

describe('stats rows', () => {
  const base: VideoStatsSnapshot = {
    videoId: 'dQw4w9WgXcQ',
    playerWidth: 1280,
    playerHeight: 720,
    frameWidth: 1920,
    frameHeight: 1080,
    fps: 30,
    sourceHeight: 1080,
    videoCodec: 'avc1.640028',
    audioCodec: 'mp4a.40.2',
    volume: 0.5,
    muted: false,
    playbackRate: 1.25,
    bufferedAhead: 8.24,
    networkBytes: 2_000_000,
    networkSeconds: 2,
    droppedFrames: 5,
    totalFrames: 1000,
  };

  it('paints the rows in YouTube order with the live values', () => {
    const rows = buildStatsRows(base);
    expect(rows.map((row) => row.label)).toEqual([
      'Video ID',
      'Viewport',
      'Current / Optimal Res',
      'Codecs',
      'Volume',
      'Playback rate',
      'Buffer health',
      'Network activity',
      'Connection speed',
      'Frames',
    ]);
    const value = (label: string) => rows.find((row) => row.label === label)?.value;
    expect(value('Video ID')).toBe('dQw4w9WgXcQ');
    expect(value('Viewport')).toBe('1280x720');
    expect(value('Current / Optimal Res')).toBe('1920x1080@30 / 1080p');
    expect(value('Codecs')).toBe('avc1.640028 / mp4a.40.2');
    expect(value('Volume')).toBe('50%');
    expect(value('Playback rate')).toBe('1.25×');
    expect(value('Buffer health')).toBe('8.2 s');
    expect(value('Network activity')).toBe('1.9 MB / 2.0 s');
    expect(value('Connection speed')).toBe('8.00 Mbps');
    expect(value('Frames')).toBe('5 dropped / 1000 (0.5%)');
    // No heap sample in the node test environment: the row stays off.
    expect(rows.some((row) => row.label === 'JS heap')).toBe(false);
  });

  it('shows muted instead of a percentage and dashes for missing samples', () => {
    const rows = buildStatsRows({
      ...base,
      muted: true,
      networkBytes: null,
      networkSeconds: null,
      videoCodec: null,
      audioCodec: '  ',
      sourceHeight: null,
      frameWidth: 0,
      frameHeight: 0,
      fps: null,
    });
    const value = (label: string) => rows.find((row) => row.label === label)?.value;
    expect(value('Volume')).toBe('Muted');
    expect(value('Network activity')).toBe('—');
    expect(value('Connection speed')).toBe('—');
    expect(value('Codecs')).toBe('— / —');
    expect(value('Current / Optimal Res')).toBe('— / —');
  });

  it('adds the JS heap row only when a heap size was sampled', () => {
    const rows = buildStatsRows({ ...base, memoryBytes: 42 * 1024 * 1024 });
    expect(rows[rows.length - 1]).toEqual({ label: 'JS heap', value: '42.0 MB' });
  });
});
