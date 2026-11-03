// The dual-track audio must never stutter or blurt the wrong position while
// scrubbing through a still-loading video; these pin the decisions the
// 250ms tick makes.
import { describe, expect, it } from 'vitest';
import { audioSyncAction } from '../src/player/syncLogic';

const base = {
  audioReadyState: 4,
  audioSeeking: false,
  videoReadyState: 4,
  videoPaused: false,
  audioPaused: false,
  audioTime: 10,
  videoTime: 10,
  muted: false,
  lastSeekAt: 0,
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

  it('does not fight the user right after a seek (settle window)', () => {
    const action = audioSyncAction({
      ...base,
      audioTime: 0,
      videoTime: 120,
      lastSeekAt: 10_000 - 300,
      now: 10_000,
    });
    expect(action).toEqual({ muted: true });
  });

  it('re-seeks the audio once the settle window expires', () => {
    const action = audioSyncAction({
      ...base,
      audioTime: 0,
      videoTime: 120,
      lastSeekAt: 10_000 - 900,
      now: 10_000,
    });
    expect(action.seekTo).toBe(120);
    expect(action.muted).toBe(true);
  });

  it('mutes on large drift, keeps playing small drift', () => {
    expect(audioSyncAction({ ...base, audioTime: 10.2, videoTime: 10 }).muted).toBe(false);
    const big = audioSyncAction({ ...base, audioTime: 9, videoTime: 10 });
    expect(big.seekTo).toBe(10);
    expect(big.muted).toBe(true);
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
    // yank it back every 250ms - the audible stutter while the video stalls.
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
