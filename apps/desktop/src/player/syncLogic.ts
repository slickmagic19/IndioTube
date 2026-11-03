// Dual-track sync decisions, kept pure so they can be unit-tested without a
// DOM. The player has a muted <video> (video-only stream) and an <audio>
// element (audio-only stream); this decides each 250ms tick what to do with
// the audio so scrubbing while everything is still loading never produces
// the "audio stutters / plays from the wrong position" effect.
//
// Rules:
//  - while the audio element has no usable metadata (readyState < 2) or is
//    already seeking, stay muted and never touch its clock;
//  - for a moment after a user seek (700ms) let both elements settle: muted,
//    no further seeking;
//  - if the VIDEO is stalling (readyState < 3, i.e. it has no future data),
//    pause the audio instead of chasing its clock: that is how a single
//    muxed stream behaves, and it avoids the audible "seek back" stutter
//    every time the video rebuffers;
//  - otherwise mirror play/pause, correct drift above 350ms, and stay muted
//    while the drift is still large (so the wrong part is never heard).
export interface AudioSyncInput {
  audioReadyState: number;   // HTMLMediaElement.readyState (0..4)
  audioSeeking: boolean;
  videoReadyState: number;   // < 3 means the video is rebuffering
  videoPaused: boolean;
  audioPaused: boolean;
  audioTime: number;
  videoTime: number;
  muted: boolean;            // the user's mute switch
  lastSeekAt: number;        // ms timestamp of the last user seek
  now: number;               // current ms timestamp
}

export interface AudioSyncAction {
  play?: boolean;
  pause?: boolean;
  seekTo?: number;
  muted: boolean;
}

const SEEK_SETTLE_MS = 700;
const DRIFT_SEEK = 0.35;
const DRIFT_MUTE = 0.9;

export function audioSyncAction(i: AudioSyncInput): AudioSyncAction {
  // NaN/inf clocks mean "not measured yet" — leave the audio alone.
  const audioTime = Number.isFinite(i.audioTime) ? i.audioTime : 0;
  const videoTime = Number.isFinite(i.videoTime) ? i.videoTime : 0;

  if (i.audioReadyState < 2 || i.audioSeeking) return { muted: true };
  if (i.now - i.lastSeekAt < SEEK_SETTLE_MS) return { muted: true };

  // The video is rebuffering: hold the audio where it is (a muxed stream
  // would simply stall) instead of re-seeking it every tick.
  if (!i.videoPaused && i.videoReadyState < 3) {
    return { pause: true, muted: false };
  }

  const drift = Math.abs(audioTime - videoTime);
  const action: AudioSyncAction = { muted: i.muted || drift > DRIFT_MUTE };

  if (i.videoPaused && !i.audioPaused) action.pause = true;
  if (!i.videoPaused && i.audioPaused) action.play = true;

  if (drift > DRIFT_SEEK) {
    action.seekTo = videoTime;
    action.muted = true;
  }
  return action;
}
