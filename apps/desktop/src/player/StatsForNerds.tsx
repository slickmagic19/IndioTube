// "Stats for nerds", sampled from the live media element.
//
// The panel is intentionally light: one timer while it is open, no React state
// changes anywhere else in the player, and the timer disappears the moment the
// overlay is closed. Buffer health comes from the element's Buffered ranges;
// network activity comes from the MSE session's own fetch counter, which is
// more reliable than cross-origin Resource Timing.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { CloseIcon } from '../components/icons';
import {
  bufferAheadSeconds, buildStatsRows, realDroppedFrames, vsyncDropAllowance,
  type BufferedRange, type StatsRow,
} from './stats';

export interface NetworkSnapshot {
  fetchedBytes: number;
  fetchedRequests: number;
}

export interface StatsForNerdsProps {
  videoId: string;
  videoRef: RefObject<HTMLVideoElement | null>;
  sourceHeight: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  /** Bytes fetched by the MSE session so far; null when a direct source plays. */
  getNetwork?(): NetworkSnapshot | null;
  /** The loudness the viewer hears, when it is not the element's own volume. */
  volumeOverride?: number | null;
  onClose(): void;
}

const SAMPLE_MS = 500;

/**
 * The display's refresh rate, measured from `requestAnimationFrame` (one call per
 * refresh). Used to discount the frames a 60 fps stream on a 50 Hz panel can
 * never show. Zero when it cannot be measured.
 */
function useDisplayRefreshRate(): number {
  const [rate, setRate] = useState(0);
  useEffect(() => {
    let cancelled = false;
    let handle = 0;
    const times: number[] = [];
    const tick = (t: number) => {
      if (cancelled) return;
      times.push(t);
      if (times.length < 10) { handle = requestAnimationFrame(tick); return; }
      const deltas = times.slice(1).map((v, i) => v - times[i]).filter((d) => d > 0).sort((a, b) => a - b);
      const median = deltas.length ? deltas[Math.floor(deltas.length / 2)] : 0;
      if (median > 0) setRate(1000 / median);
    };
    handle = requestAnimationFrame(tick);
    const timer = window.setTimeout(() => cancelAnimationFrame(handle), 1000);
    return () => { cancelled = true; cancelAnimationFrame(handle); window.clearTimeout(timer); };
  }, []);
  return rate;
}

export function StatsForNerds(props: StatsForNerdsProps) {
  const { videoId, videoRef, sourceHeight, videoCodec, audioCodec, getNetwork, volumeOverride, onClose } = props;
  const [rows, setRows] = useState<StatsRow[]>([]);
  const lastSample = useRef<{ bytes: number; at: number } | null>(null);
  const displayRate = useDisplayRefreshRate();
  // The element's counters are cumulative for this video; baseline them (and the
  // time the picture actually ran) so the drop count reflects this video only.
  const frameBase = useRef<{ videoId: string; total: number; dropped: number; playedMs: number; started: boolean }>({
    videoId, total: 0, dropped: 0, playedMs: 0, started: false,
  });
  const lastTickAt = useRef<number | null>(null);

  const sample = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;

    const ranges: BufferedRange[] = [];
    for (let i = 0; i < video.buffered.length; i += 1) {
      ranges.push({ start: video.buffered.start(i), end: video.buffered.end(i) });
    }

    const quality = typeof video.getVideoPlaybackQuality === 'function'
      ? video.getVideoPlaybackQuality()
      : null;
    const nowMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
    const total = Math.max(0, Math.round(quality?.totalVideoFrames ?? 0));
    const dropped = Math.max(0, Math.round(quality?.droppedVideoFrames ?? 0));
    // Count only the time the picture actually ran toward the display's capacity.
    const playing = !video.paused && !video.seeking && video.readyState >= 3 && document.visibilityState === 'visible';
    const dt = lastTickAt.current == null ? 0 : Math.max(0, (nowMs - lastTickAt.current) / 1000);
    lastTickAt.current = nowMs;
    let base = frameBase.current;
    if (!base.started || base.videoId !== videoId) base = { videoId, total, dropped, playedMs: 0, started: true };
    if (playing) base.playedMs += dt * 1000;
    frameBase.current = base;
    const totalDelta = Math.max(0, total - base.total);
    const droppedDelta = Math.max(0, dropped - base.dropped);
    // Drop the frames the panel physically cannot show (60 fps on 50 Hz), so a
    // smooth high-frame-rate video reads 0 like the older 30 fps builds.
    const allowance = vsyncDropAllowance(totalDelta, base.playedMs / 1000, displayRate);
    const droppedFrames = realDroppedFrames(droppedDelta, allowance);
    const totalFrames = total;
    const network = getNetwork ? getNetwork() : null;
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    let networkBytes: number | null = null;
    let networkSeconds: number | null = null;
    if (network) {
      const previous = lastSample.current;
      networkBytes = previous ? Math.max(0, network.fetchedBytes - previous.bytes) : 0;
      networkSeconds = previous ? Math.max(0.001, (now - previous.at) / 1000) : 0;
      lastSample.current = { bytes: network.fetchedBytes, at: now };
    } else {
      lastSample.current = null;
    }

    const memory = (performance as unknown as {
      memory?: { usedJSHeapSize?: number };
    } | undefined)?.memory?.usedJSHeapSize;
    const rect = video.getBoundingClientRect();

    setRows(buildStatsRows({
      videoId,
      playerWidth: rect.width,
      playerHeight: rect.height,
      frameWidth: video.videoWidth,
      frameHeight: video.videoHeight,
      fps: null,
      sourceHeight,
      videoCodec,
      audioCodec,
      volume: typeof volumeOverride === 'number' ? volumeOverride : video.volume,
      muted: video.muted,
      playbackRate: video.playbackRate,
      bufferedAhead: bufferAheadSeconds(ranges, video.currentTime),
      networkBytes,
      networkSeconds,
      droppedFrames,
      totalFrames,
      memoryBytes: typeof memory === 'number' ? memory : null,
    }));
  }, [audioCodec, displayRate, getNetwork, sourceHeight, videoCodec, videoId, videoRef, volumeOverride]);

  useEffect(() => {
    lastSample.current = null;
    sample();
    const id = window.setInterval(sample, SAMPLE_MS);
    return () => window.clearInterval(id);
  }, [sample, videoId]);

  const copy = () => {
    const text = rows.map((row) => `${row.label}: ${row.value}`).join('\n');
    navigator.clipboard?.writeText(text).catch(() => undefined);
  };

  return (
    <div className="stats-nerds" role="dialog" aria-label="Stats for nerds" data-testid="stats-for-nerds">
      <div className="stats-nerds-head">
        <span className="stats-nerds-title">Stats for nerds</span>
        <button type="button" className="stats-nerds-copy" onClick={copy} title="Copy stats">Copy</button>
        <button type="button" className="stats-nerds-close" onClick={onClose} title="Close stats" aria-label="Close stats">
          <CloseIcon size={14} />
        </button>
      </div>
      <div className="stats-nerds-body">
        {rows.map((row) => (
          <div className="stats-nerds-row" key={row.label}>
            <span className="stats-nerds-label">{row.label}</span>
            <span className="stats-nerds-value">{row.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
