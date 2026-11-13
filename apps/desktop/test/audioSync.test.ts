// The dual-track audio must never stutter, go silent, or blurt the wrong
// position while scrubbing through a still-loading video; these pin the
// decisions the 120ms tick makes.
import { describe, expect, it } from 'vitest';
import { audioSyncAction } from '../src/player/syncLogic';

const base = {
  audioReadyState: 4,
  audioSeeking: false,
  videoReadyState: 4,
  videoPaused: false,
  videoSeeking: false,
  audioPaused: false,
  audioTime: 10,
  videoTime: 10,
  muted: false,
  now: 10_000,
};

describe('audioSyncAction', () => {
  it('does nothing when already in sync and playing', () => {
    expect(audioSyncAction(base)).toEqual({ muted: false });
  });

  it('stays silent and leaves the clock alone while the audio is loading', () => {
    expect(audioSyncAction({ ...base, audioReadyState: 1, audioTime: 0 })).toEqual({ muted: true });
    expect(audioSyncAction({ ...base, audioReadyState: 0 })).toEqual({ muted: true });
  });

  it('stays silent while the audio element is still seeking', () => {
    expect(audioSyncAction({ ...base, audioSeeking: true })).toEqual({ muted: true });
  });

  it('mirrors play/pause even while the audio is loading', () => {
    expect(audioSyncAction({ ...base, audioReadyState: 1, audioPaused: true }))
      .toEqual({ muted: true, play: true });
    expect(audioSyncAction({ ...base, audioSeeking: true, videoPaused: true }))
      .toEqual({ muted: true, pause: true });
  });

  it('never chases the clock while the video itself is still seeking', () => {
    const action = audioSyncAction({ ...base, videoSeeking: true, audioTime: 0, videoTime: 120 });
    expect(action).toEqual({ muted: false });
    expect(action.seekTo).toBeUndefined();
  });

  it('keeps the play mirror while the video is seeking', () => {
    expect(audioSyncAction({ ...base, videoSeeking: true, audioPaused: true }))
      .toEqual({ muted: false, play: true });
  });

  it('re-seeks large drift without muting the sound', () => {
    const big = audioSyncAction({ ...base, audioTime: 9, videoTime: 10 });
    expect(big.seekTo).toBe(10);
    // Muting here would swallow the words until the next tick.
    expect(big.muted).toBe(false);
  });

  it('keeps playing small drift', () => {
    expect(audioSyncAction({ ...base, audioTime: 10.2, videoTime: 10 }).muted).toBe(false);
  });

  it('leaves frame-level drift alone', () => {
    const action = audioSyncAction({ ...base, audioTime: 10.02, videoTime: 10 });
    expect(action.rate).toBeUndefined();
    expect(action).toEqual({ muted: false });
  });

  it('nudges the rate instead of seeking for small drift', () => {
    // Audio 100ms behind: speed up a hair so it catches up in under a second.
    const behind = audioSyncAction({ ...base, audioTime: 9.9, videoTime: 10, playbackRate: 1 });
    expect(behind.seekTo).toBeUndefined();
    expect(behind.muted).toBe(false);
    expect(behind.rate).toBeGreaterThan(1);
    expect(behind.rate).toBeLessThan(1.05);

    const ahead = audioSyncAction({ ...base, audioTime: 10.15, videoTime: 10, playbackRate: 1.5 });
    expect(ahead.rate).toBeLessThan(1.5);
    expect(ahead.rate).toBeGreaterThan(1.4);
  });

  it('never leaves a nudge running while the video is paused', () => {
    const action = audioSyncAction({ ...base, videoPaused: true, audioPaused: true, audioTime: 9.9, videoTime: 10 });
    expect(action.rate).toBeUndefined();
    expect(action.pause).toBeUndefined(); // already paused on both sides
  });

  it('mirrors play/pause state', () => {
    expect(audioSyncAction({ ...base, videoPaused: true, audioPaused: false })).toEqual({ muted: false, pause: true });
    expect(audioSyncAction({ ...base, videoPaused: false, audioPaused: true })).toEqual({ muted: false, play: true });
  });

  it('respects the user mute switch', () => {
    expect(audioSyncAction({ ...base, muted: true })).toEqual({ muted: true });
  });

  it('tolerates unmeasured clocks', () => {
    const action = audioSyncAction({ ...base, audioTime: NaN, videoTime: NaN });
    expect(action.muted).toBe(false);
    expect(action.seekTo).toBeUndefined();
  });

  it('holds (pauses) the audio while the video is rebuffering instead of re-seeking it', () => {
    // Audio has run ahead in its own buffer; without this rule the tick would
    // yank it back every tick - the audible stutter while the video stalls.
    const action = audioSyncAction({
      ...base,
      videoReadyState: 2,
      audioTime: 14,
      videoTime: 10,
    });
    expect(action).toEqual({ pause: true, muted: false });
    expect(action.seekTo).toBeUndefined();
  });

  it('resumes the audio once the video has data again', () => {
    expect(audioSyncAction({ ...base, videoReadyState: 4, audioPaused: true })).toEqual({ muted: false, play: true });
  });
});
