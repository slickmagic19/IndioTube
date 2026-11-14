// When the player chrome is on screen.
//
// YouTube keeps the controls up while a video is paused, while one of the
// player menus is open, and for a few seconds after the pointer last moved;
// during playback they fade away even if the pointer is still resting on the
// video. The rule lives here as a pure function so it can be unit-tested,
// rather than being implied by a `:hover` selector (which never hides what the
// viewer is not moving the mouse over).

/** How long the controls stay up after the last input while playing. */
export const CONTROLS_HIDE_DELAY_MS = 3_000;

/** Leaving the player hides them a little sooner, like YouTube. */
export const CONTROLS_HIDE_DELAY_AFTER_LEAVE_MS = 450;

export interface ControlsVisibilityInput {
  playing: boolean;
  /** The settings/captions menu is open. */
  menuOpen?: boolean;
  /** The viewer is scrubbing the seek bar. */
  dragging?: boolean;
  /** Milliseconds since the last pointer/key input on the player. */
  idleMs: number;
  hideDelayMs?: number;
}

/**
 * True when the control strip should be visible.
 *
 * Note that `idleMs` only matters while playing: a paused video always shows
 * its controls, and so does one whose menu is open.
 */
export function controlsVisible(input: ControlsVisibilityInput): boolean {
  if (!input.playing) return true;
  if (input.menuOpen) return true;
  if (input.dragging) return true;
  return Math.max(0, input.idleMs) < (input.hideDelayMs ?? CONTROLS_HIDE_DELAY_MS);
}

/** True when an auto-hide timer should be running for this state. */
export function shouldScheduleControlsHide(input: {
  playing: boolean;
  menuOpen?: boolean;
  dragging?: boolean;
}): boolean {
  return Boolean(input.playing) && !input.menuOpen && !input.dragging;
}

/** YouTube hides the mouse cursor along with the controls. */
export function cursorHidden(playing: boolean, visible: boolean): boolean {
  return Boolean(playing) && !visible;
}
