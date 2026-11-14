// A black picture with a running clock is a decoder failure, not a stall. The
// player only nudges the pipeline while the clock actually moves, and only
// rebuilds the stream when even that produced nothing.
import { describe, expect, it } from 'vitest';
import {
  BLACK_FRAME_RECOVER_MS,
  BLACK_FRAME_WAIT_MS,
  FRAME_PROGRESS_SECONDS,
  frameVerdict,
  type FrameSample,
} from '../src/player/frameWatchdog';

const base: FrameSample = {
  now: 10_000,
  watchingSince: 0,
  lastFrameAt: 9_900,
  time: 10,
  lastTime: 9,
  paused: false,
  readyState: 4,
};

describe('black picture watch', () => {
  it('stays out of the way while paused, loading, or the clock is still', () => {
    expect(frameVerdict({ ...base, paused: true })).toBe('ok');
    expect(frameVerdict({ ...base, readyState: 1 })).toBe('ok');
    // A rebuffer freezes the clock: the pump owns that, not this.
    expect(frameVerdict({ ...base, time: 9, lastTime: 9 })).toBe('ok');
    expect(frameVerdict({ ...base, time: 9 + FRAME_PROGRESS_SECONDS / 2 })).toBe('ok');
  });

  it('waits while frames are still arriving', () => {
    expect(frameVerdict(base)).toBe('wait');
    expect(frameVerdict({ ...base, now: 9_900 + BLACK_FRAME_WAIT_MS - 1 })).toBe('wait');
  });

  it('nudges the pipeline once the picture falls behind the clock', () => {
    expect(frameVerdict({ ...base, now: 9_900 + BLACK_FRAME_WAIT_MS })).toBe('kick');
    expect(frameVerdict({ ...base, now: 9_900 + BLACK_FRAME_RECOVER_MS - 1 })).toBe('kick');
  });

  it('rebuilds the stream when nothing shows up even after the nudge', () => {
    expect(frameVerdict({ ...base, now: 9_900 + BLACK_FRAME_RECOVER_MS })).toBe('recover');
  });

  it('counts from the start of the watch when no frame ever arrived', () => {
    const silent = { ...base, lastFrameAt: null, watchingSince: 1_000 };
    expect(frameVerdict({ ...silent, now: 1_000 + BLACK_FRAME_WAIT_MS - 1 })).toBe('wait');
    expect(frameVerdict({ ...silent, now: 1_000 + BLACK_FRAME_WAIT_MS })).toBe('kick');
    expect(frameVerdict({ ...silent, now: 1_000 + BLACK_FRAME_RECOVER_MS })).toBe('recover');
  });

  it('recovers faster than a viewer would reach for the close button', () => {
    expect(BLACK_FRAME_WAIT_MS).toBeLessThanOrEqual(3000);
    expect(BLACK_FRAME_RECOVER_MS).toBeLessThan(10_000);
  });
});
