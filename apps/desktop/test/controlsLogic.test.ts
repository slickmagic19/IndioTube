// YouTube-style auto-hiding chrome. The rules are pure so the player never has
// to guess whether a paused video, an open menu or a resting mouse keeps them up.
import { describe, expect, it } from 'vitest';
import {
  CONTROLS_HIDE_DELAY_AFTER_LEAVE_MS,
  CONTROLS_HIDE_DELAY_MS,
  controlsVisible,
  cursorHidden,
  shouldScheduleControlsHide,
} from '../src/player/controlsLogic';

describe('when the player controls are visible', () => {
  it('keeps them up for the whole time a video is paused', () => {
    expect(controlsVisible({ playing: false, idleMs: 0 })).toBe(true);
    expect(controlsVisible({ playing: false, idleMs: 10 * 60_000 })).toBe(true);
  });

  it('shows them for a few seconds after input while playing', () => {
    expect(controlsVisible({ playing: true, idleMs: 0 })).toBe(true);
    expect(controlsVisible({ playing: true, idleMs: CONTROLS_HIDE_DELAY_MS - 1 })).toBe(true);
    expect(controlsVisible({ playing: true, idleMs: CONTROLS_HIDE_DELAY_MS })).toBe(false);
  });

  it('honours a shorter delay when the pointer leaves the player', () => {
    expect(controlsVisible({
      playing: true,
      idleMs: 0,
      hideDelayMs: CONTROLS_HIDE_DELAY_AFTER_LEAVE_MS,
    })).toBe(true);
    expect(controlsVisible({
      playing: true,
      idleMs: CONTROLS_HIDE_DELAY_AFTER_LEAVE_MS,
      hideDelayMs: CONTROLS_HIDE_DELAY_AFTER_LEAVE_MS,
    })).toBe(false);
  });

  it('stays up while a menu is open or the seek bar is being dragged', () => {
    const idleForever = 60 * 60_000;
    expect(controlsVisible({ playing: true, menuOpen: true, idleMs: idleForever })).toBe(true);
    expect(controlsVisible({ playing: true, dragging: true, idleMs: idleForever })).toBe(true);
  });

  it('treats a clock that went backwards as fresh input', () => {
    expect(controlsVisible({ playing: true, idleMs: -50 })).toBe(true);
  });
});

describe('when an auto-hide timer should run', () => {
  it('only while playing with nothing holding the controls open', () => {
    expect(shouldScheduleControlsHide({ playing: true })).toBe(true);
    expect(shouldScheduleControlsHide({ playing: false })).toBe(false);
    expect(shouldScheduleControlsHide({ playing: true, menuOpen: true })).toBe(false);
    expect(shouldScheduleControlsHide({ playing: true, dragging: true })).toBe(false);
  });
});

describe('hiding the mouse cursor with the controls', () => {
  it('hides it only during playback with the chrome gone', () => {
    expect(cursorHidden(true, false)).toBe(true);
    expect(cursorHidden(true, true)).toBe(false);
    expect(cursorHidden(false, false)).toBe(false);
  });
});
