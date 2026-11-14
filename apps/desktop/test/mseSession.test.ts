// The MSE session owns the pump loop, the SourceBuffers and the element
// listeners. The node test runner has no media stack, so these drive the real
// class with fakes and pin the two failure modes that used to bite:
//  - a rejected append (full buffer) used to advance past the segment and leave
//    a permanent hole;
//  - a failed session used to leave the pump interval and the element
//    listeners running for the rest of the app session.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FETCH_ATTEMPTS, MseSession, type FetchRange, type TrackIndex } from '../src/player/mse';

const SOURCE = {
  video: 'https://example.test/v',
  audio: 'https://example.test/a',
  videoCodec: 'avc1.4D4020',
  audioCodec: 'mp4a.40.2',
};

function indexFrom(times: number[]): TrackIndex {
  return {
    initEnd: 0,
    timescale: 1000,
    duration: times.length,
    segments: times.map((time, i) => ({ start: i, size: 1, time, duration: 1 })),
  };
}

/** Just enough of a SourceBuffer for the append/reclaim path. */
class FakeSourceBuffer {
  updating = false;
  appended: ArrayBuffer[] = [];
  removed: Array<[number, number]> = [];
  /** How many coming appends fail the way a full buffer does. */
  failAppends = 0;
  buffered = { length: 0, start: (_i: number) => 0, end: (_i: number) => 0 };
  private listeners = new Map<string, Set<() => void>>();

  addEventListener(type: string, fn: () => void): void {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(fn);
  }

  removeEventListener(type: string, fn: () => void): void {
    this.listeners.get(type)?.delete(fn);
  }

  appendBuffer(data: ArrayBuffer): void {
    if (this.failAppends > 0) {
      this.failAppends -= 1;
      throw new DOMException('buffer is full', 'QuotaExceededError');
    }
    this.appended.push(data);
    // Real SourceBuffer updates finish asynchronously; so does this one.
    queueMicrotask(() => this.emit('updateend'));
  }

  remove(start: number, end: number): void {
    this.removed.push([start, end]);
    queueMicrotask(() => this.emit('updateend'));
  }

  bufferedRange(start: number, end: number): void {
    this.buffered = { length: 1, start: () => start, end: () => end };
  }

  private emit(type: string): void {
    for (const fn of [...(this.listeners.get(type) ?? [])]) fn();
  }
}

class FakeMediaSource {
  readyState = 'open';
  duration = Number.NaN;
  addEventListener(): void { /* sourceopen is never needed here */ }
  removeEventListener(): void { /* nothing to remove */ }
}

function fakeVideo() {
  const listeners = new Map<string, Set<() => void>>();
  const video = {
    currentTime: 0,
    paused: true,
    readyState: 4,
    src: '',
    error: null as { code: number } | null,
    buffered: { length: 0, start: (_i: number) => 0, end: (_i: number) => 0 },
    addEventListener(type: string, fn: () => void): void {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type)!.add(fn);
    },
    removeEventListener(type: string, fn: () => void): void {
      listeners.get(type)?.delete(fn);
    },
    removeAttribute(attr: string): void {
      if (attr === 'src') video.src = '';
    },
    load(): void { /* the fallback sets a new src */ },
    pause(): void { video.paused = true; },
    listenerCount: () => [...listeners.values()].reduce((n, set) => n + set.size, 0),
  };
  return video;
}

interface Rig {
  session: MseSession;
  video: ReturnType<typeof fakeVideo>;
  errors: string[];
  fetchRange: ReturnType<typeof vi.fn>;
  start: (tracks: FakeSourceBuffer[], at?: number) => void;
}

function rig(): Rig {
  const video = fakeVideo();
  const errors: string[] = [];
  const fetchRange = vi.fn(async () => new Uint8Array([1, 2, 3]).buffer);
  const session = new MseSession(
    video as unknown as HTMLVideoElement,
    SOURCE,
    fetchRange as unknown as FetchRange,
    (reason) => errors.push(reason),
  );
  // `start()` needs real MediaSource events; these tests drive the pump that
  // owns the append/evict behaviour directly.
  return {
    session,
    video,
    errors,
    fetchRange,
    start: (tracks, at = 0) => {
      const internals = session as unknown as { started: boolean; tracks: unknown[] };
      internals.started = true;
      video.currentTime = at;
      internals.tracks = [
        { url: SOURCE.video, mime: 'video/mp4', index: indexFrom([35, 36]), buffer: tracks[0], next: 0, gen: 0 },
        { url: SOURCE.audio, mime: 'audio/mp4', index: indexFrom([]), buffer: tracks[1], next: 0, gen: 0 },
      ];
    },
  };
}

const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 20));

beforeEach(() => {
  vi.stubGlobal('MediaSource', FakeMediaSource);
  vi.stubGlobal('URL', { createObjectURL: () => 'blob:fake', revokeObjectURL: () => undefined });
  // The player reads timers off `window`; the node runner has no window.
  vi.stubGlobal('window', {
    setTimeout: (fn: () => void, ms?: number) => setTimeout(fn, ms),
    clearTimeout: (id: number) => clearTimeout(id),
    setInterval: (fn: () => void, ms?: number) => setInterval(fn, ms),
    clearInterval: (id: number) => clearInterval(id),
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('a segment the SourceBuffer rejects', () => {
  it('is offered again after the played history is dropped', async () => {
    const videoSb = new FakeSourceBuffer();
    const audioSb = new FakeSourceBuffer();
    // 39 s buffered around a playhead at 40 s: the first segment (35 s) is not
    // buffered, and there is history behind the playhead to reclaim.
    videoSb.bufferedRange(0, 34);
    videoSb.failAppends = 1;
    const { session, start, fetchRange, errors } = rig();
    start([videoSb, audioSb], 40);

    (session as unknown as { pump(): void }).pump();
    await settle();

    // The rejected segment was retried instead of being skipped, so the buffer
    // holds a contiguous 35 s onward.
    expect(errors).toEqual([]);
    expect(videoSb.appended).toHaveLength(2);
    expect(videoSb.removed[0]).toEqual([0, 35]);
    expect(fetchRange).toHaveBeenCalledTimes(2);
  });

  it('ends the session once instead of spinning when it is still rejected', async () => {
    const videoSb = new FakeSourceBuffer();
    const audioSb = new FakeSourceBuffer();
    videoSb.bufferedRange(0, 34);
    videoSb.failAppends = 10;
    const { session, video, start, fetchRange, errors } = rig();
    start([videoSb, audioSb], 40);

    (session as unknown as { pump(): void }).pump();
    await settle();

    expect(errors).toEqual(['stream append failed']);
    // Nothing is left running for a session that already told the player.
    expect(video.listenerCount()).toBe(0);
    const calls = fetchRange.mock.calls.length;
    (session as unknown as { pump(): void }).pump();
    await settle();
    expect(fetchRange).toHaveBeenCalledTimes(calls);
  });
});

describe('keeping the pipe full', () => {
  function trackInternals(session: MseSession): { tracks: any[]; primed: boolean } {
    return session as unknown as { tracks: any[]; primed: boolean };
  }

  it('asks for a whole window of segments in one range request', async () => {
    const videoSb = new FakeSourceBuffer();
    const audioSb = new FakeSourceBuffer();
    const { session, start, fetchRange, errors } = rig();
    start([videoSb, audioSb], 0);
    const internals = trackInternals(session);
    // Four contiguous one-second segments, one byte each.
    internals.tracks[0].index = indexFrom([0, 1, 2, 3]);
    internals.tracks[0].next = 0;
    internals.primed = true;

    (session as unknown as { pump(): void }).pump();
    await settle();

    expect(errors).toEqual([]);
    // One round trip, one SourceBuffer update, and the index jumps the run.
    expect(fetchRange).toHaveBeenCalledTimes(1);
    expect(fetchRange.mock.calls[0].slice(1, 3)).toEqual([0, 3]);
    expect(videoSb.appended).toHaveLength(1);
    expect(internals.tracks[0].next).toBe(4);
  });

  it('keeps the first fetch after a start small and batches afterwards', async () => {
    const videoSb = new FakeSourceBuffer();
    const audioSb = new FakeSourceBuffer();
    const { session, start, fetchRange } = rig();
    start([videoSb, audioSb], 0);
    const internals = trackInternals(session);
    internals.tracks[0].index = indexFrom([0, 1, 2, 3]);
    internals.tracks[0].next = 0;

    (session as unknown as { pump(): void }).pump();
    await settle();

    // The segment under the playhead comes first, on its own...
    expect(fetchRange.mock.calls[0].slice(1, 3)).toEqual([0, 0]);
    // ...and the rest of the window follows in a single batched request.
    expect(fetchRange.mock.calls[1].slice(1, 3)).toEqual([1, 3]);
    expect(videoSb.appended).toHaveLength(2);
    expect(internals.primed).toBe(true);
  });

  it('does not re-fetch what the element already holds after a seek back', async () => {
    const videoSb = new FakeSourceBuffer();
    const audioSb = new FakeSourceBuffer();
    const { session, start, fetchRange } = rig();
    start([videoSb, audioSb], 0);
    const internals = trackInternals(session);
    internals.tracks[0].index = indexFrom([0, 1, 2, 3]);
    internals.tracks[0].next = 0;
    // Everything up to 4 s is already in the buffer.
    videoSb.bufferedRange(0, 4);

    (session as unknown as { pump(): void }).pump();
    await settle();

    expect(fetchRange).not.toHaveBeenCalled();
    expect(videoSb.appended).toHaveLength(0);
  });
});

describe('disposing a session', () => {
  it('removes every element listener and stops the pump', () => {
    const { session, video } = rig();
    expect(video.listenerCount()).toBe(4);
    const internals = session as unknown as { pumpTimer: number };
    internals.pumpTimer = setInterval(() => undefined, 1000);

    session.dispose();

    expect(video.listenerCount()).toBe(0);
    expect(video.src).toBe('');
    // A second dispose is a no-op, not a second teardown.
    expect(() => session.dispose()).not.toThrow();
  });
});

describe('a byte range that fails on its own', () => {
  it('is re-fetched before the session gives up', async () => {
    const { session, fetchRange, errors } = rig();
    fetchRange
      .mockRejectedValueOnce(new Error('stream range failed (502)'))
      .mockResolvedValueOnce(new Uint8Array([9]).buffer);

    const data = await (session as unknown as {
      fetchBytes(url: string, start: number, end: number): Promise<ArrayBuffer>;
    }).fetchBytes(SOURCE.video, 0, 10);

    expect(new Uint8Array(data)).toEqual(new Uint8Array([9]));
    expect(fetchRange).toHaveBeenCalledTimes(2);
    expect(errors).toEqual([]);
  });

  it('surfaces the original error once the attempts are spent', async () => {
    const { session, fetchRange } = rig();
    fetchRange.mockRejectedValue(new Error('stream range failed (502)'));

    await expect((session as unknown as {
      fetchBytes(url: string, start: number, end: number): Promise<ArrayBuffer>;
    }).fetchBytes(SOURCE.video, 0, 10)).rejects.toThrow('stream range failed (502)');
    expect(fetchRange).toHaveBeenCalledTimes(FETCH_ATTEMPTS);
  });

  it('never retries a request the viewer cancelled', async () => {
    const { session, fetchRange } = rig();
    const abort = Object.assign(new Error('The operation was aborted'), { name: 'AbortError' });
    fetchRange.mockRejectedValue(abort);

    await expect((session as unknown as {
      fetchBytes(url: string, start: number, end: number): Promise<ArrayBuffer>;
    }).fetchBytes(SOURCE.video, 0, 10)).rejects.toThrow('aborted');
    expect(fetchRange).toHaveBeenCalledTimes(1);
  });
});
