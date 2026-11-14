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
  bufferAheadSeconds, buildStatsRows, type BufferedRange, type StatsRow,
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
  onClose(): void;
}

const SAMPLE_MS = 500;

export function StatsForNerds(props: StatsForNerdsProps) {
  const { videoId, videoRef, sourceHeight, videoCodec, audioCodec, getNetwork, onClose } = props;
  const [rows, setRows] = useState<StatsRow[]>([]);
  const lastSample = useRef<{ bytes: number; at: number } | null>(null);

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
      volume: video.volume,
      muted: video.muted,
      playbackRate: video.playbackRate,
      bufferedAhead: bufferAheadSeconds(ranges, video.currentTime),
      networkBytes,
      networkSeconds,
      droppedFrames: quality?.droppedVideoFrames ?? 0,
      totalFrames: quality?.totalVideoFrames ?? 0,
      memoryBytes: typeof memory === 'number' ? memory : null,
    }));
  }, [audioCodec, getNetwork, sourceHeight, videoCodec, videoId, videoRef]);

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
