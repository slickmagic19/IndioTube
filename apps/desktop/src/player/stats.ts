// The numbers behind "Stats for nerds".
//
// The overlay itself only samples the media element; every calculation and
// every label lives here so the reporting format is unit-tested instead of
// being buried in JSX. The fields mirror YouTube's panel: viewport, current and
// optimal resolution, codecs, buffer health, network activity, connection
// speed and dropped frames.

export interface BufferedRange {
  start: number;
  end: number;
}

/** Seconds of *contiguous* media buffered ahead of the playhead. */
export function bufferAheadSeconds(ranges: ArrayLike<BufferedRange>, currentTime: number): number {
  if (!ranges || !Number.isFinite(currentTime)) return 0;
  for (let i = 0; i < ranges.length; i += 1) {
    const range = ranges[i];
    if (!range) continue;
    // A playhead sitting in a gap has no playable buffer, even when a later
    // range is buffered: that is what buffer health means.
    if (currentTime >= range.start - 0.05 && currentTime <= range.end + 0.05) {
      return Math.max(0, range.end - currentTime);
    }
  }
  return 0;
}

/** Bytes per second from a byte count and the interval it arrived in. */
export function estimateBitrate(bytes: number, seconds: number): number | null {
  if (!Number.isFinite(bytes) || !Number.isFinite(seconds)) return null;
  if (bytes <= 0 || seconds <= 0) return null;
  return bytes / seconds;
}

/** "12.34 Mbps", "842.1 Kbps" or an em dash when nothing was sampled. */
export function formatBitrate(bytesPerSecond: number | null | undefined): string {
  if (bytesPerSecond == null || !Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) return '—';
  const bits = bytesPerSecond * 8;
  if (bits >= 1_000_000) return `${(bits / 1_000_000).toFixed(2)} Mbps`;
  if (bits >= 1_000) return `${(bits / 1_000).toFixed(1)} Kbps`;
  return `${Math.round(bits)} bps`;
}

/** Compact byte sizes for the network and memory rows. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

/** "1280x720@30", tolerating a missing frame size or frame rate. */
export function formatResolution(width: number, height: number, fps?: number | null): string {
  const w = Number.isFinite(width) && width > 0 ? Math.round(width) : 0;
  const h = Number.isFinite(height) && height > 0 ? Math.round(height) : 0;
  if (!w && !h) return '—';
  const size = `${w || '?'}x${h || '?'}`;
  return Number.isFinite(fps) && fps && fps > 0 ? `${size}@${Math.round(fps)}` : size;
}

export function frameDropPercent(dropped: number, total: number): number {
  if (!Number.isFinite(dropped) || !Number.isFinite(total) || total <= 0) return 0;
  return Math.min(100, Math.max(0, (dropped / total) * 100));
}

export interface VideoStatsSnapshot {
  videoId: string;
  playerWidth: number;
  playerHeight: number;
  frameWidth: number;
  frameHeight: number;
  fps?: number | null;
  sourceHeight: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  volume: number;
  muted: boolean;
  playbackRate: number;
  bufferedAhead: number;
  networkBytes?: number | null;
  networkSeconds?: number | null;
  droppedFrames: number;
  totalFrames: number;
  memoryBytes?: number | null;
}

export interface StatsRow {
  label: string;
  value: string;
}

const dash = (value: string | null | undefined) => (value && value.trim() ? value : '—');

/** The rows the overlay paints, in the order YouTube uses them. */
export function buildStatsRows(snapshot: VideoStatsSnapshot): StatsRow[] {
  const bitrate = snapshot.networkBytes != null && snapshot.networkSeconds != null
    ? estimateBitrate(snapshot.networkBytes, snapshot.networkSeconds)
    : null;
  const network = snapshot.networkBytes == null
    ? '—'
    : `${formatBytes(snapshot.networkBytes)} / ${(snapshot.networkSeconds ?? 0).toFixed(1)} s`;
  const volume = snapshot.muted
    ? 'Muted'
    : `${Math.round(Math.min(1, Math.max(0, snapshot.volume)) * 100)}%`;

  const rows: StatsRow[] = [
    { label: 'Video ID', value: snapshot.videoId },
    {
      label: 'Viewport',
      value: `${Math.round(snapshot.playerWidth) || 0}x${Math.round(snapshot.playerHeight) || 0}`,
    },
    {
      label: 'Current / Optimal Res',
      value: `${formatResolution(snapshot.frameWidth, snapshot.frameHeight, snapshot.fps)} / ${
        snapshot.sourceHeight ? `${snapshot.sourceHeight}p` : '—'
      }`,
    },
    { label: 'Codecs', value: `${dash(snapshot.videoCodec)} / ${dash(snapshot.audioCodec)}` },
    { label: 'Volume', value: volume },
    { label: 'Playback rate', value: `${snapshot.playbackRate}×` },
    { label: 'Buffer health', value: `${Math.max(0, snapshot.bufferedAhead).toFixed(1)} s` },
    { label: 'Network activity', value: network },
    { label: 'Connection speed', value: formatBitrate(bitrate) },
    {
      label: 'Frames',
      value: `${Math.max(0, Math.round(snapshot.droppedFrames))} dropped / ${
        Math.max(0, Math.round(snapshot.totalFrames))
      } (${frameDropPercent(snapshot.droppedFrames, snapshot.totalFrames).toFixed(1)}%)`,
    },
  ];
  if (snapshot.memoryBytes != null) {
    rows.push({ label: 'JS heap', value: formatBytes(snapshot.memoryBytes) });
  }
  return rows;
}
