// Dual-track sync decisions, kept pure so they can be unit-tested without a
// DOM. The player has a muted <video> (video-only stream) and an <audio>
// element (audio-only stream); this decides each tick what to do with the
// audio so the two clocks stay together without audible stutter.
//
// Rules:
//  - while the audio element has no usable metadata (readyState < 2) or is
//    already seeking, stay muted and never touch its clock;
//  - for a moment after a seek let both elements settle: muted, no seeking;
//  - if the VIDEO is stalling (readyState < 3, i.e. it has no future data),
//    pause the audio instead of chasing its clock: that is how a single
//    muxed stream behaves, and it avoids the audible "seek back" stutter
//    every time the video rebuffers;
//  - otherwise mirror play/pause, gently nudge the audio rate while the drift
//    is still small (Chromium keeps the pitch), re-seek above 250ms, and stay
//    muted only while the drift is large enough that the wrong part could be
//    heard. A 40ms window is about one frame at 24fps - the same tolerance a
//    single muxed stream gets from the browser - so ordinary playback is no
//    longer left audibly ahead or behind.
export interface AudioSyncInput {
  audioReadyState: number;   // HTMLMediaElement.readyState (0..4)
  audioSeeking: boolean;
  videoReadyState: number;   // < 3 means the video is rebuffering
  videoPaused: boolean;
  videoSeeking: boolean;     // the video's own seek has not landed yet
  audioPaused: boolean;
  audioTime: number;
  videoTime: number;
  muted: boolean;            // the user's mute switch
  now: number;               // current ms timestamp
  playbackRate?: number;     // the user's chosen speed (default 1)
}

export interface AudioSyncAction {
  play?: boolean;
  pause?: boolean;
  seekTo?: number;
  /** Absolute playbackRate for the audio element this tick (nudge only). */
  rate?: number;
  muted: boolean;
}

const DRIFT_SEEK = 0.25;
const SOFT_DRIFT = 0.04;
const MAX_NUDGE = 0.05;

export function audioSyncAction(i: AudioSyncInput): AudioSyncAction {
  // NaN/inf clocks mean "not measured yet" — leave the audio alone.
  const audioTime = Number.isFinite(i.audioTime) ? i.audioTime : 0;
  const videoTime = Number.isFinite(i.videoTime) ? i.videoTime : 0;

  if (i.audioReadyState < 2 || i.audioSeeking) {
    // Muted, but still mirror play/pause: a seek that takes a moment must not
    // leave the audio element paused after the picture resumes, which is what
    // made a skip occasionally stay silent until the next skip.
    const hold: AudioSyncAction = { muted: true };
    if (i.videoPaused && !i.audioPaused) hold.pause = true;
    if (!i.videoPaused && i.audioPaused) hold.play = true;
    return hold;
  }
  // The video is still seeking: its currentTime is the target, not the frame
  // on screen, so correcting the audio to it now would rewind the sound.
  if (i.videoSeeking) {
    const hold: AudioSyncAction = { muted: i.muted };
    if (i.videoPaused && !i.audioPaused) hold.pause = true;
    if (!i.videoPaused && i.audioPaused) hold.play = true;
    return hold;
  }

  // The video is rebuffering: hold the audio where it is (a muxed stream
  // would simply stall) instead of re-seeking it every tick.
  if (!i.videoPaused && i.videoReadyState < 3) {
    return { pause: true, muted: i.muted };
  }

  const drift = Math.abs(audioTime - videoTime);
  // Sound is never muted to hide a drift: muting an element that is already
  // playing loses the words until it is unmuted, which is the skipped-audio
  // bug. Correct the clock instead.
  const action: AudioSyncAction = { muted: i.muted };

  if (i.videoPaused && !i.audioPaused) action.pause = true;
  if (!i.videoPaused && i.audioPaused) action.play = true;

  if (drift > DRIFT_SEEK) {
    action.seekTo = videoTime;
  } else if (!i.videoPaused && drift > SOFT_DRIFT) {
    // Behind (audioTime < videoTime) speeds up a hair, ahead slows down a
    // hair; the correction is small enough to be inaudible but converges in
    // under a second instead of leaving a permanent offset.
    const base = Number.isFinite(i.playbackRate) && i.playbackRate > 0 ? i.playbackRate : 1;
    const nudge = Math.max(-MAX_NUDGE, Math.min(MAX_NUDGE, (videoTime - audioTime) * 0.35));
    action.rate = Math.max(0.25, Math.min(4, base + nudge));
  }
  return action;
}
