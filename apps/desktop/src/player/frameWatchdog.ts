// "Audio is playing but the picture is black."
//
// That is a decoder/GPU failure, not a buffering stall: the media clock keeps
// running, `readyState` is high, and yet no frame ever reaches the compositor.
// Nothing in the DOM reports it, so without this the only fix the viewer has is
// to close and reopen the video. The decision table lives here (and is pinned
// by a unit test); the hook applies it to the real element.
import { useEffect } from 'react';
import type { RefObject } from 'react';

/** Give the decoder this long to put a first/next frame on screen. */
export const BLACK_FRAME_WAIT_MS = 2000;
/** After a nudge, this long without a frame means the stream needs rebuilding. */
export const BLACK_FRAME_RECOVER_MS = 5000;
/** The clock has to really move: below this it is just a paused/stalled element. */
export const FRAME_PROGRESS_SECONDS = 0.05;

export interface FrameSample {
  /** performance.now() at this sample. */
  now: number;
  /** performance.now() when the watch started. */
  watchingSince: number;
  /** performance.now() of the last decoded frame, or null if none arrived. */
  lastFrameAt: number | null;
  /** Media clock now and at the previous sample. */
  time: number;
  lastTime: number;
  paused: boolean;
  /** video.readyState */
  readyState: number;
}

export type FrameVerdict = 'ok' | 'wait' | 'kick' | 'recover';

/**
 * - `ok`: nothing to do (paused, no data yet, or the clock is not moving - an
 *   ordinary buffering stall the pump already handles).
 * - `wait`: the clock runs but the last frame is recent enough.
 * - `kick`: no frame for a while; ask the pipeline for one.
 * - `recover`: still nothing after the nudge; rebuild the stream.
 */
export function frameVerdict(sample: FrameSample): FrameVerdict {
  if (sample.paused || sample.readyState < 2) return 'ok';
  // A still clock is a stall, not a black picture: pulling the stream apart
  // here would turn a normal rebuffer into a restart.
  if (!(sample.time > sample.lastTime + FRAME_PROGRESS_SECONDS)) return 'ok';
  const since = sample.lastFrameAt == null
    ? sample.now - sample.watchingSince
    : sample.now - sample.lastFrameAt;
  if (!Number.isFinite(since) || since < BLACK_FRAME_WAIT_MS) return 'wait';
  if (since < BLACK_FRAME_RECOVER_MS) return 'kick';
  return 'recover';
}

/**
 * Watch a playing element for a picture that never arrives. One frame callback
 * is armed at a time, so this costs a couple of callbacks per second even at
 * 60 fps.
 */
export function useBlackFrameWatchdog(
  videoRef: RefObject<HTMLVideoElement | null>,
  playing: boolean,
  onKick: () => void,
  onRecover: () => void,
): void {
  useEffect(() => {
    const v = videoRef.current;
    if (!v || !playing) return undefined;
    const request = (v as HTMLVideoElement & {
      requestVideoFrameCallback?: (cb: () => void) => number;
      cancelVideoFrameCallback?: (handle: number) => void;
    }).requestVideoFrameCallback;
    // No frame callback support: there is nothing that could tell a black
    // picture from a slow one, so leave the element alone.
    if (typeof request !== 'function') return undefined;

    let cancelled = false;
    let handle = 0;
    let lastFrameAt: number | null = null;
    let frames = 0;
    let seen = 0;
    let kicked = 0;
    let done = false;

    const arm = () => {
      if (cancelled) return;
      try {
        handle = request.call(v, () => {
          frames += 1;
          lastFrameAt = performance.now();
        });
      } catch {
        /* the element was detached mid-flight */
      }
    };
    arm();

    const watchingSince = performance.now();
    let since = watchingSince;
    let lastTime = v.currentTime;
    const id = window.setInterval(() => {
      const time = v.currentTime;
      // A hidden or occluded window is not asked to draw frames, and a seek in
      // flight legitimately has none: neither is a broken picture.
      if (document.visibilityState === 'hidden' || v.seeking) {
        since = performance.now();
        lastFrameAt = null;
        lastTime = time;
        return;
      }
      const verdict = frameVerdict({
        now: performance.now(),
        watchingSince: since,
        lastFrameAt,
        time,
        lastTime,
        paused: v.paused,
        readyState: v.readyState,
      });
      lastTime = time;
      // The callback only fires once; queue the next one after it was used.
      if (frames > seen) {
        seen = frames;
        arm();
      }
      if (verdict === 'kick' && kicked === 0) {
        kicked += 1;
        onKick();
      } else if (verdict === 'recover' && !done) {
        done = true;
        onRecover();
      }
    }, 1000);

    return () => {
      cancelled = true;
      window.clearInterval(id);
      const cancel = (v as HTMLVideoElement & {
        cancelVideoFrameCallback?: (handle: number) => void;
      }).cancelVideoFrameCallback;
      try { if (cancel) cancel.call(v, handle); } catch { /* already gone */ }
    };
  }, [videoRef, playing, onKick, onRecover]);
}
