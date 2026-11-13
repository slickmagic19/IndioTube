// The desktop player. HTML5 <video> fed by the main-process stream proxy,
// with the Premium-style control set: quality ladder + auto, playback speed,
// chapters, WebVTT captions, SponsorBlock auto-skip with a manual button,
// PiP, theater and mini modes, keyboard shortcuts, resume-from-position and
// throttled progress writes back into the library.
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Qualities, VideoItem, WatchData, Settings } from '../types';
import { formatDuration } from '../types';
import { useApp } from '../store';
import { audioSyncAction } from './syncLogic';
import { canUseMse, useMseSource, type MseSource } from './mse';

// What streams.playUrl resolves to: the two files plus their codecs.
type StreamSrc = {
  video: string;
  audio: string | null;
  height: number;
  label?: string;
  videoCodec?: string | null;
  audioCodec?: string | null;
};
import {
  DEFAULT_SPONSOR_CATEGORIES, autoSkipTarget, segmentKey, sponsorLabel, sponsorSegmentNear,
  type SbSegment,
} from './sponsorLogic';
import {
  BackIcon, CaptionsIcon, CheckIcon, ChevronRightIcon, CloseIcon, GearSmallIcon,
  MiniIcon, MuteIcon, NextIcon,
  PauseIcon, PipIcon, PlayIcon, PrevIcon, TheaterIcon, VolumeIcon,
  FullscreenIcon, ExitFullscreenIcon,
} from '../components/icons';
import { Menu, Spinner } from '../components/ui';

const SPEEDS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

export interface PlayerProps {
  video: VideoItem;
  watch: WatchData | null;
  queue: VideoItem[];       // up-next rail (may be empty)
  queueIndex: number;
  onNext(): void;
  onPrev(): void;
  miniMode: boolean;
  onExitMini(): void;
}

export function Player(props: PlayerProps) {
  const { video, watch, queue, queueIndex, onNext, onPrev, miniMode } = props;
  const { settings, saveSettings, op, toast } = useApp();
  const videoRef = useRef<HTMLVideoElement>(null);
  const frameRef = useRef<HTMLDivElement>(null);
  const hideTimer = useRef<number>(0);

  const [qualities, setQualities] = useState<Qualities | null>(null);
  const [height, setHeight] = useState<number | null>(settings?.defaultQuality ?? null);
  const [srcs, setSrcs] = useState<{ video: string; audio: string | null } | null>(null);
  const audioRef = useRef<HTMLAudioElement>(null);
  const [loading, setLoading] = useState(true);
  // True whenever the decoder is waiting for data (start, seek or rebuffer).
  const [buffering, setBuffering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);
  const [stickyControls, setStickyControls] = useState(false);
  const [now, setNow] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [volume, setVolume] = useState(settings?.volume ?? 1);
  const [muted, setMuted] = useState(false);
  const [rate, setRate] = useState(settings?.playbackRate ?? 1);
  const [theater, setTheater] = useState(Boolean(settings?.theater));
  const [isFullscreen, setIsFullscreen] = useState(false);
  // Picture-in-picture is tracked from the element's own events (the user can
  // close the PiP window from the OS), so the button and layout always agree.
  const [pipActive, setPipActive] = useState(false);
  const wasPlayingBeforePip = useRef(true);
  const [gearAnchor, setGearAnchor] = useState<DOMRect | null>(null);
  const [gearView, setGearView] = useState<'main' | 'quality' | 'speed' | 'captions'>('main');
  const [captionsOn, setCaptionsOn] = useState(Boolean(settings?.captionsEnabled));
  const [captionUrl, setCaptionUrl] = useState<string | null>(null);
  // Cue text for the overlay below. Rendering the active cues ourselves is what
  // lets captions sit where the phone app puts them; the native cue renderer
  // always parks them at the very bottom of the video.
  const [cueLines, setCueLines] = useState<string[]>([]);
  const [segments, setSegments] = useState<SbSegment[]>([]);
  const [skipHint, setSkipHint] = useState<SbSegment | null>(null);
  // Segments auto-skipped (or explicitly undone) stay that way for the rest of
  // the video, so seeking back into one does not fight the viewer.
  const skippedRef = useRef<Set<string>>(new Set());
  const resumeRef = useRef<number>(0);
  const lastSaveRef = useRef<number>(0);
  // A seek issued while the audio element hasn't loaded yet is parked here
  // and applied once its metadata arrives - setting currentTime on a media
  // element that is still loading is what made the audio glitch.
  const pendingAudioSeek = useRef<number | null>(null);
  const lastSeekRef = useRef<number>(0);
  // Signed CDN URLs can fail transiently. Real YouTube reloads without asking;
  // retry once per video with freshly extracted URLs before showing an error.
  const recoveryRef = useRef<{ videoId: string; tries: number }>({ videoId: '', tries: 0 });
  // One recovery at a time: an error storm must not spawn parallel retries.
  const recoveringRef = useRef(false);
  // Bumped on every seek so a stale "audio is ready, resume the picture"
  // callback can never fire after the viewer has already skipped again.
  const seekTokenRef = useRef(0);
  // Watchdog state: how long the audio clock has failed to advance while the
  // picture runs, and the last value it had. Any stuck state (paused, muted,
  // lost seek) is repaired from this instead of being trusted.
  const audioStallRef = useRef(0);
  const lastAudioClockRef = useRef(-1);
  // The user's mute switch, readable from callbacks that must not capture
  // a stale render value when they unmute the audio.
  const mutedRef = useRef(muted);
  useEffect(() => { mutedRef.current = muted; }, [muted]);

  // ---- source resolution ---------------------------------------------------

  // Above 360p YouTube hands out the picture and the sound as two files.
  // Instead of running two elements and chasing their clocks, both tracks are
  // appended to one <video> element through Media Source Extensions (how
  // YouTube itself plays), so the browser keeps them sample-locked and a seek
  // moves a single clock. If MSE cannot take the pair, the old two-element
  // path is used instead.
  const mseBlockedRef = useRef(false);
  const lastSrcRef = useRef<StreamSrc | null>(null);
  const [mseSrc, setMseSrc] = useState<MseSource | null>(null);
  const mseStartRef = useRef(0);

  const applySource = useCallback((src: StreamSrc, startAt?: number) => {
    lastSrcRef.current = src;
    if (!mseBlockedRef.current && canUseMse(src)) {
      // Quality switches keep the position; a fresh video starts where it was
      // last left off.
      mseStartRef.current = startAt ?? (resumeRef.current || 0);
      setSrcs(null);
      setMseSrc({
        video: src.video,
        audio: src.audio as string,
        videoCodec: src.videoCodec as string,
        audioCodec: src.audioCodec as string,
      });
    } else {
      setMseSrc(null);
      setSrcs(src);
    }
  }, []);

  const fetchRange = useCallback(async (url: string, start: number, endInclusive: number) => {
    // The range travels in the path, not in a Range header: a custom header is
    // not CORS-safelisted, so every segment would cost a preflight, and a
    // failed preflight silently knocked the player back to the two-element
    // fallback. A plain GET has no preflight at all.
    const u = new URL(url);
    u.pathname = '/r';
    u.searchParams.set('s', String(start));
    u.searchParams.set('e', String(endInclusive));
    const res = await fetch(u.toString(), { cache: 'no-store' });
    if (!res.ok) throw new Error(`stream range failed (${res.status})`);
    return res.arrayBuffer();
  }, []);

  const onMseError = useCallback(() => {
    // Unsupported codec, a corrupt index or a buffer error: play the same two
    // files with the fallback rather than showing no video at all.
    mseBlockedRef.current = true;
    setMseSrc(null);
    const src = lastSrcRef.current;
    if (src) setSrcs(src);
    setLoading(false);
  }, []);

  useMseSource(videoRef, mseSrc, fetchRange, onMseError, mseStartRef);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setQualities(null);
    setSrcs(null);
    setMseSrc(null);
    setCaptionUrl(null);
    setSegments([]);
    // A different video must never be preceded by the old one's sound: the
    // audio track is its own element and keeps playing until it is stopped.
    videoRef.current?.pause();
    const oldAudio = audioRef.current;
    if (oldAudio) {
      oldAudio.pause();
      oldAudio.muted = true;
    }
    if (video.membersOnly) {
      setError('Members-only video — sign-in is not supported, so it cannot play here.');
      setLoading(false);
      return;
    }
    if (video.isLive) {
      setError('Live videos are not playable on desktop yet.');
      setLoading(false);
      return;
    }
    (async () => {
      try {
        const q = await window.indio.streams.qualities(video.videoId);
        if (cancelled) return;
        setQualities(q);
        const target = height ?? settings?.defaultQuality ?? null;
        const src = await window.indio.streams.playUrl(video.videoId, target);
        if (cancelled) return;
        applySource(src);
        // Remembered caption language, then the remembered on/off switch.
        if (settings?.captionsEnabled) {
          const lang = settings.captionLang
            || q.subtitles.find((s) => s.autoGenerated)?.languageTag
            || q.subtitles[0]?.languageTag || '';
          if (lang) {
            const u = await window.indio.streams.subtitleUrl(video.videoId, lang).catch(() => null);
            if (cancelled) return;
            if (u) setCaptionUrl(u);
            // YouTube occasionally refuses the caption file (rate limiting).
            // Leaving the switch on with no track makes the button look broken,
            // so fall back to off; the viewer can retry from the menu.
            else setCaptionsOn(false);
          }
        }
      } catch (e: any) {
        if (!cancelled) {
          setError(String(e?.message || e) === 'yt-dlp failed to start: spawn yt-dlp.exe ENOENT'
            ? 'yt-dlp sidecar missing — run `npm run fetch:ytdlp`.'
            : String(e?.message || e));
        }
      } finally {
        // The overlay stays up until the first frame plays (see onPlaying):
        // priming the audio track must never look like a frozen player.
      }
    })();
    return () => { cancelled = true; };
    // height intentionally omitted: the src effect below handles quality swaps
  }, [video.videoId, video.membersOnly, video.isLive]); // eslint-disable-line react-hooks/exhaustive-deps

  // Navigation starts before the new video's data arrives: silence the current
  // one the moment the route points at a different video, instead of hearing it
  // over the new page while the streams resolve.
  useEffect(() => {
    const onHash = () => {
      const m = /^#\/watch\/([^?/]+)/.exec(window.location.hash);
      if (!m || m[1] === video.videoId) return;
      videoRef.current?.pause();
      audioRef.current?.pause();
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, [video.videoId]);

  // SponsorBlock fetch (best-effort; offline just means no skips). Only the
  // categories chosen in Settings are requested, exactly like the phone.
  const sponsorCategories = useMemo(() => (
    settings?.sponsorBlockCategories?.length
      ? settings.sponsorBlockCategories
      : DEFAULT_SPONSOR_CATEGORIES
  ), [settings?.sponsorBlockCategories]);
  const sponsorCategoryKey = sponsorCategories.join(',');

  useEffect(() => {
    skippedRef.current = new Set();
    setSkipHint(null);
    if (!settings?.sponsorBlock) { setSegments([]); return; }
    let cancelled = false;
    window.indio.sponsorBlock.segments(video.videoId, sponsorCategories)
      .then((segs) => {
        if (cancelled) return;
        setSegments(segs.filter((s) => (
          s && Array.isArray(s.segment) && s.segment.length >= 2 && s.segment[1] > s.segment[0]
        )));
      })
      .catch(() => { if (!cancelled) setSegments([]); });
    return () => { cancelled = true; };
  }, [video.videoId, settings?.sponsorBlock, sponsorCategoryKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // ---- resume + history bookkeeping -------------------------------------------

  useEffect(() => {
    let cancelled = false;
    resumeRef.current = 0;
    window.indio.library.op<number>('noteOpen', video).catch(() => undefined);
    window.indio.library.op<number>('resumePosition', video.videoId)
      .then((pos) => { if (!cancelled) resumeRef.current = Number(pos) || 0; });
    return () => { cancelled = true; };
  }, [video.videoId]); // eslint-disable-line react-hooks/exhaustive-deps

  // The watch page plays a "Loading…" stub first; once the real title arrives,
  // enrich the stored entry so history never keeps the placeholder.
  const hasRealTitle = Boolean(video.title) && !/^loading/i.test(String(video.title).trim());
  useEffect(() => {
    if (!hasRealTitle) return;
    window.indio.library.op<number>('enrichVideo', video).catch(() => undefined);
  }, [video.videoId, hasRealTitle]); // eslint-disable-line react-hooks/exhaustive-deps

  const saveProgress = useCallback(() => {
    const v = videoRef.current;
    if (!v || !v.duration || Number.isFinite(v.duration) === false) return;
    op('notePosition', video.videoId, Math.round(v.currentTime * 1000), Math.round(v.duration * 1000))
      .catch(() => undefined);
  }, [op, video.videoId]);

  // ---- playback plumbing -------------------------------------------------------

  // Every stream failure retries itself. The ladder is: the same quality with a
  // fresh extraction, then each lower rung, then Auto; each step waits a little
  // longer. Only when all of that fails is an error shown, and even then the
  // Retry button starts a fresh ladder instead of a dead end.
  const recoverStream = useCallback(async () => {
    if (recoveryRef.current.videoId !== video.videoId) {
      recoveryRef.current = { videoId: video.videoId, tries: 0 };
    }
    if (recoveringRef.current) return;
    recoveringRef.current = true;
    try {
      while (recoveryRef.current.tries < 4) {
        const attempt = recoveryRef.current.tries;
        recoveryRef.current.tries += 1;
        if (attempt === 0) setLoading(true);
        setError(null);
        try {
          await new Promise((r) => setTimeout(r, 300 * 2 ** attempt));
          const q = await window.indio.streams.qualities(video.videoId, true);
          setQualities(q);
          const rungs = [...q.options].sort((a, b) => b.height - a.height);
          // Attempt 0 keeps the viewer's quality; later attempts walk down the
          // ladder (Auto last) so one bad CDN edge cannot keep the video down.
          const target = attempt === 0
            ? height
            : (rungs[Math.min(attempt - 1, rungs.length - 1)]?.height ?? null);
          const src = await window.indio.streams.playUrl(video.videoId, target);
          if (attempt > 0) setHeight(target);
          applySource(src, videoRef.current?.currentTime || 0);
          return;
        } catch {
          // Next rung.
        }
      }
      setError('The stream failed to load. Check your connection and press Retry.');
      setLoading(false);
    } finally {
      recoveringRef.current = false;
    }
  }, [video.videoId, height]);

  const togglePlay = useCallback(() => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (!v) return;
    if (v.paused) {
      if (a && srcs?.audio) {
        // After a paused seek the audio may still be muted; unmute before the
        // picture starts so the first word is not clipped.
        if (a.readyState >= 2) {
          a.muted = mutedRef.current;
          lastSeekRef.current = 0;
        }
        playAudio(a, true);
      }
      v.play().catch(() => setError('Playback was blocked — click to start.'));
    } else {
      v.pause();
      if (a) a.pause();
    }
  }, [srcs]);

  // A skip moves both clocks and only resumes the picture once the audio can
  // play from the new position. When the target is already buffered the sound
  // is instant and the picture never stalls.
  const seekAudioInStep = useCallback((pos: number, wasPlaying: boolean) => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (!v || !a || !srcs?.audio) return;
    const token = (seekTokenRef.current += 1);
    a.muted = true;
    if (a.readyState >= 1) {
      try { a.currentTime = pos; pendingAudioSeek.current = null; }
      catch { pendingAudioSeek.current = pos; }
    } else {
      pendingAudioSeek.current = pos;
    }
    // Start fetching at the new offset immediately, in parallel with the video.
    playAudio(a, false);
    // Buffered target: nothing to wait for, unmute straight away. Clearing
    // lastSeekAt tells the tick this seek is settled, so it cannot re-mute
    // during its settle window right after the sound came back.
    if (!a.seeking && a.readyState >= 2) {
      if (wasPlaying) {
        a.muted = mutedRef.current;
        lastSeekRef.current = 0;
        playAudio(a, true);
      }
      return;
    }
    if (!wasPlaying) return;
    v.pause();
    let done = false;
    const finish = () => {
      if (done || token !== seekTokenRef.current) return;
      done = true;
      window.clearTimeout(timer);
      a.removeEventListener('seeked', check);
      a.removeEventListener('canplay', check);
      a.removeEventListener('loadeddata', check);
      // A seek issued before the element had data can land nowhere; re-target
      // the video's real position once so silence can never become permanent.
      if (a.readyState < 2) {
        try { a.currentTime = v.currentTime; } catch { /* the tick retries */ }
      }
      const ready = a.readyState >= 2 && !a.seeking;
      if (!ready) {
        a.muted = true;
        if (v.paused) v.play().catch(() => undefined);
        return;
      }
      // The audio is confirmed at the new position: settle the tick so it
      // neither re-mutes nor re-seeks, unmute before the first audio frame so
      // no syllable is clipped, and only move the picture once the sound is
      // actually running.
      lastSeekRef.current = 0;
      a.muted = mutedRef.current;
      const resumeVideo = () => { if (v.paused) v.play().catch(() => undefined); };
      playAudio(a, true).then(resumeVideo);
      window.setTimeout(resumeVideo, 500);
    };
    const check = () => { if (a.readyState >= 2 && !a.seeking) finish(); };
    const timer = window.setTimeout(finish, 1200);
    a.addEventListener('seeked', check);
    a.addEventListener('canplay', check);
    a.addEventListener('loadeddata', check);
  }, [srcs]);

  const seekTo = useCallback((seconds: number) => {
    const v = videoRef.current;
    if (!v || !isFinite(v.duration)) return;
    const pos = Math.max(0, Math.min(v.duration - 0.5, seconds));
    const wasPlaying = !v.paused;
    v.currentTime = pos;
    lastSeekRef.current = Date.now();
    seekAudioInStep(pos, wasPlaying);
    setNow(pos);
  }, [srcs]);

  const switchQuality = useCallback(async (h: number | null) => {
    setHeight(h);
    saveSettings({ defaultQuality: h });
    try {
      const src = await window.indio.streams.playUrl(video.videoId, h);
      const v = videoRef.current;
      const wasPlaying = !!v && !v.paused;
      const t = v?.currentTime || 0;
      lastSeekRef.current = Date.now();
      pendingAudioSeek.current = t;
      if (audioRef.current) audioRef.current.muted = true;
      applySource(src, t);
      requestAnimationFrame(() => {
        const vv = videoRef.current;
        const aa = audioRef.current;
        if (!vv) return;
        // A blob URL means Media Source: MseSession already resumed it.
        if (vv.src.startsWith('blob:')) return;
        vv.currentTime = t;
        if (aa && src.audio) aa.currentTime = t;
        if (wasPlaying) { vv.play().catch(() => undefined); aa?.play().catch(() => undefined); }
      });
    } catch (e: any) {
      toast(String(e?.message || e));
    }
  }, [saveSettings, toast, video.videoId]);

  // Chromium may reject an unmuted play() on the audio element (it is a second
  // element the viewer never clicked). Muted playback is always allowed, so
  // fall back to starting muted and unmuting in the same task: the element is
  // never left paused while the picture runs, and no audio is skipped.
  const playAudio = useCallback(async (a: HTMLAudioElement, unmute: boolean) => {
    const target = unmute ? mutedRef.current : true;
    if (!a.paused) {
      a.muted = target;
      return;
    }
    try {
      await a.play();
      a.muted = target;
    } catch {
      a.muted = true;
      try {
        await a.play();
        a.muted = target;
      } catch { /* the 120ms tick retries */ }
    }
  }, []);

  // Split streams are two files: starting the video first is exactly why the
  // first seconds had no sound and it faded in later. Wait (briefly) for the
  // audio to be able to start, then start both in the same tick.
  const startTogether = useCallback((hasAudio: boolean) => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (!v) return;
    if (!hasAudio || !a) {
      v.play().catch(() => undefined);
      return;
    }
    let started = false;
    const go = () => {
      if (started) return;
      started = true;
      window.clearTimeout(timer);
      a.removeEventListener('canplay', go);
      a.removeEventListener('loadeddata', go);
      try { a.currentTime = v.currentTime; } catch { /* the tick realigns */ }
      // Unmute before the first audio frame: starting muted and unmuting a
      // tick later is what clipped the first fraction of a second of sound.
      a.muted = mutedRef.current;
      lastSeekRef.current = 0;
      const resumeVideo = () => { if (v.paused) v.play().catch(() => undefined); };
      playAudio(a, true).then(resumeVideo);
      // Never leave the picture waiting when the audio is slow to start.
      window.setTimeout(resumeVideo, 500);
    };
    const timer = window.setTimeout(go, 600);
    if (a.readyState >= 3) go();
    else {
      a.addEventListener('canplay', go);
      a.addEventListener('loadeddata', go);
    }
  }, []);

  // Keep the media elements in sync when the source pair changes.
  useEffect(() => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (!v || !srcs) return;
    const t = v.currentTime;
    // No settle window at startup: nothing was seeked, so the audio may
    // become audible as soon as it is ready.
    lastSeekRef.current = 0;
    pendingAudioSeek.current = resumeRef.current > 0 ? resumeRef.current : t;
    v.src = srcs.video;
    if (a) {
      if (srcs.audio) a.src = srcs.audio; else a.removeAttribute('src');
      // Start silent; the sync tick unmutes once both clocks agree.
      a.muted = true;
      a.load();
    }
      // Buffer the audio track immediately (muted, so nothing is heard before
      // the clocks agree) instead of waiting for the first sync tick.
      if (srcs.audio) playAudio(a, false);
    v.load();
    if (resumeRef.current > 0 && t < 1) {
      const onMeta = () => {
        const pos = Math.min(resumeRef.current, (v.duration || 0) - 5);
        v.currentTime = pos;
        pendingAudioSeek.current = pos;
        v.removeEventListener('loadedmetadata', onMeta);
      };
      v.addEventListener('loadedmetadata', onMeta);
    }
    startTogether(Boolean(srcs.audio));
    // From here the sync tick owns the audio clock (play/pause/drift).
  }, [srcs]);

  useEffect(() => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (v) { v.volume = volume; v.muted = muted || !!srcs?.audio; v.playbackRate = rate; }
    // a.muted is owned by the sync tick (mute while loading/seeking/drifting).
    // preservesPitch keeps the ±5% drift nudge from making voices chipmunk.
    if (a) { a.volume = volume; a.playbackRate = rate; a.preservesPitch = true; }
  }, [volume, muted, rate, srcs]);

  // ---- time tick: progress bar, sponsor skip, throttled saves ------------------

  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    const onTick = () => {
      const t = v.currentTime;
      setNow(t);
      // Dual-track sync: the audio element shadows the video clock. The
      // decision table lives in syncLogic.ts (muted while loading/seeking,
      // settle window after a user seek, drift correction, play/pause mirror).
      const a = audioRef.current;
      if (a && srcs?.audio) {
        const action = audioSyncAction({
          audioReadyState: a.readyState,
          audioSeeking: a.seeking,
          videoReadyState: v.readyState,
          videoPaused: v.paused,
          videoSeeking: v.seeking,
          audioPaused: a.paused,
          audioTime: a.currentTime,
          videoTime: v.currentTime,
          muted,
          now: Date.now(),
          playbackRate: rate,
        });
        if (action.pause && !a.paused) a.pause();
        if (action.play && a.paused) playAudio(a, !action.muted);
        // Soft drift correction: a hair faster/slower until the clocks agree,
        // otherwise back to the user's chosen speed.
        const wantedRate = action.rate ?? rate;
        if (Math.abs(a.playbackRate - wantedRate) > 0.001) a.playbackRate = wantedRate;
        if (action.seekTo !== undefined) {
          try { a.currentTime = action.seekTo; } catch { /* retry next tick */ }
        }
        if (a.muted !== action.muted) a.muted = action.muted;

        // Watchdog: whenever the picture runs, the audible track must advance
        // too. If it has not moved for ~700ms (or is muted when it should be
        // heard), repair it from the picture's clock. This makes every missed
        // event or rejected play() self-heal instead of going silent.
        if (!v.paused) {
          const clock = a.currentTime;
          const progressed = Math.abs(clock - lastAudioClockRef.current) > 0.001;
          lastAudioClockRef.current = clock;
          if (progressed) {
            audioStallRef.current = 0;
          } else if (a.readyState >= 2 && !a.seeking) {
            audioStallRef.current += 120;
          } else {
            audioStallRef.current = 0;
          }
          if (audioStallRef.current > 700) {
            audioStallRef.current = 0;
            lastSeekRef.current = 0;
            a.muted = mutedRef.current;
            try { a.currentTime = v.currentTime; } catch { /* next tick */ }
            playAudio(a, true);
          } else if (a.muted && !mutedRef.current && !a.seeking && a.readyState >= 2
            && audioStallRef.current > 300) {
            // Stuck silent while the picture moves: unmute and let it play.
            a.muted = false;
            playAudio(a, true);
          }
        }
      }
      if (v.buffered.length) setBuffered(v.buffered.end(v.buffered.length - 1));
      if (settings?.sponsorBlock && settings.sponsorBlockAutoSkip) {
        const target = autoSkipTarget(segments, t, skippedRef.current, true);
        if (target) {
          // Mark before seeking: the next tick must not chase the same segment,
          // and seeking back by hand later is left alone too.
          skippedRef.current.add(segmentKey(target));
          // seekTo moves both clocks (and mutes the audio until they agree),
          // so the skipped segment is never heard trailing behind the video.
          seekTo(target.segment[1] + 0.01);
          setSkipHint(target);
          window.setTimeout(() => setSkipHint((cur) => (cur === target ? null : cur)), 2000);
        }
      }
      if (Date.now() - lastSaveRef.current > 5000 && !v.paused) {
        lastSaveRef.current = Date.now();
        saveProgress();
      }
    };
    // 120ms keeps the two clocks within a couple of frames; with a single
    // element this tick is a no-op.
    const id = window.setInterval(onTick, 120);
    return () => window.clearInterval(id);
  }, [segments, settings?.sponsorBlock, settings?.sponsorBlockAutoSkip, saveProgress, srcs, muted, rate, seekTo]);

  // ---- fullscreen (± the shell hides its chrome through App) -----------------

  useEffect(() => {
    window.indio.window.isFullscreen().then(setIsFullscreen).catch(() => undefined);
    return window.indio.window.onFullscreen(setIsFullscreen);
  }, []);

  const toggleFullscreen = useCallback(async () => {
    const next = !isFullscreen;
    const actual = await window.indio.window.setFullscreen(next).catch(() => next);
    setIsFullscreen(Boolean(actual));
  }, [isFullscreen]);

  // PiP: never enter it while the app window is full-screen (the small PiP
  // window would sit on top of a chrome-less fullscreen window and there is
  // no way back), and restore playback when the PiP window is closed.
  const togglePip = useCallback(async () => {
    const v = videoRef.current as HTMLVideoElement & {
      requestPictureInPicture?: () => Promise<unknown>;
      disablePictureInPicture?: boolean;
    };
    if (!v) return;
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
        return;
      }
      if (isFullscreen) {
        const actual = await window.indio.window.setFullscreen(false).catch(() => false);
        setIsFullscreen(Boolean(actual));
        await new Promise((r) => setTimeout(r, 350));
      }
      if (!v.requestPictureInPicture) throw new Error('unsupported');
      wasPlayingBeforePip.current = !v.paused;
      await v.requestPictureInPicture();
    } catch {
      toast('Picture in picture is not available');
    }
  }, [isFullscreen, toast]);

  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    const onEnter = () => setPipActive(true);
    const onLeave = () => {
      setPipActive(false);
      // Chromium can leave the element paused after the PiP window closes;
      // pick playback back up where it was instead of a frozen frame.
      if (wasPlayingBeforePip.current && v.paused) v.play().catch(() => undefined);
    };
    v.addEventListener('enterpictureinpicture', onEnter);
    v.addEventListener('leavepictureinpicture', onLeave);
    return () => {
      v.removeEventListener('enterpictureinpicture', onEnter);
      v.removeEventListener('leavepictureinpicture', onLeave);
    };
  }, [srcs]);

  // ---- keyboard shortcuts --------------------------------------------------------

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
      if (document.querySelector('.menu')) return; // a menu owns the keyboard
      const v = videoRef.current;
      if (!v) return;
      switch (e.key) {
        case ' ': case 'k': e.preventDefault(); togglePlay(); break;
        case 'ArrowRight': seekTo(v.currentTime + 5); break;
        case 'ArrowLeft': seekTo(v.currentTime - 5); break;
        case 'l': seekTo(v.currentTime + 10); break;
        case 'j': seekTo(v.currentTime - 10); break;
        case 'ArrowUp': setVolume((x) => Math.min(1, x + 0.05)); break;
        case 'ArrowDown': setVolume((x) => Math.max(0, x - 0.05)); break;
        case 'm': setMuted((x) => !x); break;
        case 'f': if (!miniMode) toggleFullscreen(); break;
        case 'Escape':
          // Escape steps back out of the most enclosing mode first.
          if (miniMode) props.onExitMini();
          else if (isFullscreen) toggleFullscreen();
          break;
        case 't': setTheater((x) => { saveSettings({ theater: !x }); return !x; }); break;
        case ',': setRate(Math.max(0.25, SPEEDS[SPEEDS.indexOf(rate) - 1] ?? 0.25)); break;
        case '.': setRate(Math.min(2, SPEEDS[SPEEDS.indexOf(rate) + 1] ?? 2)); break;
        case 'n': if (queue.length > queueIndex + 1) onNext(); break;
        case 'p': if (queueIndex > 0) onPrev(); break;
        case 'c': captionsToggleRef.current(); break;
        default: break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [togglePlay, seekTo, rate, queue.length, queueIndex, onNext, onPrev, saveSettings, toggleFullscreen, miniMode, isFullscreen]);

  // ---- chapters ------------------------------------------------------------------

  const chapters = watch?.chapters || [];
  const currentChapter = useMemo(() => {
    for (let i = chapters.length - 1; i >= 0; i -= 1) {
      if (now * 1000 >= chapters[i].startMs) return chapters[i];
    }
    return null;
  }, [chapters, now]);

  // ---- captions toggle -------------------------------------------------------------

  const captionFailure = useCallback(() => {
    setCaptionsOn(false);
    setCaptionUrl(null);
    saveSettings({ captionsEnabled: false });
    toast('Captions failed to load for this video');
  }, [saveSettings, toast]);

  const toggleCaptions = useCallback(async () => {
    const next = !captionsOn;
    if (!next) {
      setCaptionsOn(false);
      saveSettings({ captionsEnabled: false });
      return;
    }
    // Already resolved: just show it.
    if (captionUrl) {
      setCaptionsOn(true);
      saveSettings({ captionsEnabled: true });
      return;
    }
    const preferred = settings?.captionLang
      || qualities?.subtitles.find((s) => s.autoGenerated)?.languageTag
      || qualities?.subtitles?.[0]?.languageTag || '';
    if (!preferred) {
      toast('No captions available for this video');
      return;
    }
    const u = await window.indio.streams.subtitleUrl(video.videoId, preferred).catch(() => null);
    if (!u) { captionFailure(); return; }
    setCaptionUrl(u);
    setCaptionsOn(true);
    saveSettings({ captionsEnabled: true });
  }, [captionsOn, captionUrl, qualities, settings?.captionLang, saveSettings, video.videoId, captionFailure, toast]);

  // The keyboard effect is installed before this callback exists; the ref
  // lets the 'c' shortcut call the latest toggle without a forward reference.
  const captionsToggleRef = useRef<() => void>(() => undefined);
  captionsToggleRef.current = toggleCaptions;

  // ---- player settings panel ------------------------------------------------
  // One list, like YouTube's: the main menu shows the current value of each
  // setting and a submenu opens with the options (and a tick on the chosen one).
  const closeGear = useCallback(() => {
    setGearAnchor(null);
    setGearView('main');
  }, []);
  const gearTitle = gearView === 'main' ? 'Settings'
    : gearView === 'quality' ? 'Quality'
      : gearView === 'speed' ? 'Playback speed' : 'Subtitles/CC';
  const qualityLabel = height === null
    ? (qualities?.autoHeight ? `Auto (${qualities.autoHeight}p)` : 'Auto')
    : `${height}p`;
  const captionLabel = !captionsOn ? 'Off'
    : (qualities?.subtitles.find((s) => s.languageTag === settings?.captionLang)?.label || 'On');

  // Captions are painted by the overlay below, never by the native cue
  // renderer: every track stays "hidden", which still parses the cues (so
  // seeking and toggling stay instant) while leaving placement to us. YouTube
  // hugs the bottom edge and lifts the line above the controls when they show;
  // that is exactly what .caption-layer does.
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    let tracks: TextTrack[] = [];

    const read = () => {
      if (!captionsOn) {
        setCueLines((prev) => (prev.length ? [] : prev));
        return;
      }
      const lines: string[] = [];
      for (const track of tracks) {
        const cues = track.activeCues;
        if (!cues) continue;
        for (let i = 0; i < cues.length; i += 1) {
          const text = String((cues[i] as VTTCue).text || '')
            .replace(/<[^>]*>/g, '')
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&nbsp;/g, ' ')
            .trim();
          if (text) lines.push(text);
        }
      }
      setCueLines((prev) => (
        prev.length === lines.length && prev.every((line, i) => line === lines[i]) ? prev : lines
      ));
    };

    const sync = () => {
      for (const track of tracks) track.removeEventListener('cuechange', read);
      tracks = [];
      for (let i = 0; i < v.textTracks.length; i += 1) {
        const track = v.textTracks[i];
        if (track.kind !== 'subtitles' && track.kind !== 'captions') continue;
        track.mode = 'hidden';
        track.addEventListener('cuechange', read);
        tracks.push(track);
      }
      read();
    };

    sync();
    v.textTracks.addEventListener('addtrack', sync);
    // Seeking into a cue, or a track finishing parsing after the last cuechange,
    // would otherwise leave a stale line on screen.
    v.addEventListener('timeupdate', read);
    v.addEventListener('seeking', read);
    // Belt and braces: some track implementations miss a cuechange right after a
    // quality swap, which would leave the overlay empty.
    const ticker = captionsOn ? window.setInterval(read, 250) : 0;
    return () => {
      v.textTracks.removeEventListener('addtrack', sync);
      v.removeEventListener('timeupdate', read);
      v.removeEventListener('seeking', read);
      for (const track of tracks) track.removeEventListener('cuechange', read);
      if (ticker) window.clearInterval(ticker);
    };
  }, [captionsOn, captionUrl, srcs]);

  // A buffering stretch that never ends is treated like a load failure and
  // retried; a normal rebuffer just shows the spinner and clears itself.
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return undefined;
    let stallTimer = 0;
    const onWaiting = () => {
      setBuffering(true);
      window.clearTimeout(stallTimer);
      stallTimer = window.setTimeout(() => {
        if (!v.paused && !v.ended) recoverStream();
      }, 12_000);
    };
    const onPlaying = () => {
      setBuffering(false);
      setLoading(false);
      window.clearTimeout(stallTimer);
    };
    const onCanPlay = () => setBuffering(false);
    v.addEventListener('waiting', onWaiting);
    v.addEventListener('stalled', onWaiting);
    v.addEventListener('playing', onPlaying);
    v.addEventListener('canplay', onCanPlay);
    return () => {
      window.clearTimeout(stallTimer);
      v.removeEventListener('waiting', onWaiting);
      v.removeEventListener('stalled', onWaiting);
      v.removeEventListener('playing', onPlaying);
      v.removeEventListener('canplay', onCanPlay);
    };
  }, [recoverStream, srcs]);

  const sponsorNear = settings?.sponsorBlock ? sponsorSegmentNear(segments, now) : null;
  const showControls = !miniMode ? 'controls' : 'controls stick';

  return (
    <div ref={frameRef} className={`player-frame${(!playing && !loading && !error) ? ' paused' : ''}`}>
      <video
        ref={videoRef}
        onClick={togglePlay}
        onSeeked={() => {
          // The seek is over: clear the settle marker so the tick does not
          // mute the audio again for another 350ms after it came back.
          lastSeekRef.current = 0;
          const a = audioRef.current;
          const v = videoRef.current;
          if (a && v && srcs?.audio) {
            // Re-align right after the video lands, then let the audio keep
            // fetching from the new offset (the tick unmutes when it is ready).
            if (a.readyState >= 1) {
              try { a.currentTime = v.currentTime; } catch { /* tick retries */ }
            }
            if (!v.paused) playAudio(a, false);
          }
        }}
        onLoadedMetadata={() => {
          const v = videoRef.current;
          if (v) setDuration(Number.isFinite(v.duration) ? v.duration : 0);
        }}
        onDurationChange={() => {
          const v = videoRef.current;
          if (v && Number.isFinite(v.duration) && v.duration !== duration) setDuration(v.duration);
        }}
        onTimeUpdate={() => {
          const v = videoRef.current;
          if (v && duration === 0 && Number.isFinite(v.duration)) setDuration(v.duration);
        }}
        onPlay={() => {
          setPlaying(true);
          // A frame actually reached the screen: the retry budget starts over.
          recoveryRef.current = { videoId: video.videoId, tries: 0 };
        }}
        onPlaying={() => { setBuffering(false); setPlaying(true); setLoading(false); }}
        onWaiting={() => setBuffering(true)}
        onCanPlay={() => setBuffering(false)}
        onStalled={() => setBuffering(true)}
        onPause={() => { setPlaying(false); saveProgress(); }}
        onEnded={() => { saveProgress(); if (settings?.autoplay && queue.length > queueIndex + 1) onNext(); }}
        onError={(e) => {
          // Code 1 is "aborted", which is normal while switching sources.
          if (e.currentTarget.error?.code === 1) return;
          if (srcs) recoverStream();
        }}
        crossOrigin="anonymous"
        // Show the thumbnail while the first bytes arrive instead of a black
        // box, and let Chromium fill the buffer as soon as a source is set.
        poster={video.thumbnail}
        preload="auto"
        playsInline
      >
        {captionUrl && captionsOn && (
          <track
            kind="subtitles"
            src={captionUrl}
            srcLang={settings?.captionLang || 'en'}
            label="Captions"
            onError={captionFailure}
          />
        )}
      </video>
      <audio
        ref={audioRef}
        playsInline
        preload="auto"
        // Off-screen rather than display:none: Chromium can suspend hidden
        // media, and a suspended audio element is exactly the silence bug.
        style={{ position: 'absolute', width: 1, height: 1, opacity: 0, pointerEvents: 'none' }}
        onError={(e) => {
          if (e.currentTarget.error?.code === 1) return;
          if (srcs?.audio) recoverStream();
        }}
        onLoadedMetadata={() => {
          // Apply a seek that was requested while the track was still loading.
          const a = audioRef.current;
          const pending = pendingAudioSeek.current;
          if (a && pending != null) {
            try { a.currentTime = Math.min(pending, a.duration || pending); } catch { /* next tick retries */ }
          }
          pendingAudioSeek.current = null;
          // Loading metadata is not a user seek; do not mute the track for it.
          lastSeekRef.current = 0;
        }}
      />

      {captionsOn && cueLines.length > 0 && (
        <div className="caption-layer" aria-live="polite" aria-atomic="true">
          {cueLines.map((line, i) => <span key={i}>{line}</span>)}
        </div>
      )}
      {loading && !error && (
        <div className="load-overlay">
          {video.thumbnail && <img src={video.thumbnail} alt="" aria-hidden="true" />}
          <div className="load-overlay-scrim" />
          <Spinner />
        </div>
      )}
      {!loading && buffering && !error && !pipActive && (
        <div className="buffer-overlay" role="status" aria-live="polite">
          <Spinner />
        </div>
      )}
      {error && (
        <div style={{
          position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', gap: 12,
          alignItems: 'center', justifyContent: 'center', color: '#fff', background: 'rgba(0,0,0,.6)', padding: 24,
          textAlign: 'center',
        }}>
          <div>{error}</div>
          {!video.membersOnly && !video.isLive && (
            <button className="btn" onClick={async () => {
              setError(null); setLoading(true);
              try {
                const q = await window.indio.streams.qualities(video.videoId, true);
                setQualities(q);
                const src = await window.indio.streams.playUrl(video.videoId, height);
                applySource(src);
              } catch (e: any) { setError(String(e?.message || e)); setLoading(false); }
            }}>Retry</button>
          )}
        </div>
      )}
      {pipActive && (
        <div className="pip-placeholder">
          <div style={{ fontSize: 15 }}>Playing in picture-in-picture</div>
          <button className="btn" onClick={() => { document.exitPictureInPicture().catch(() => undefined); }}>
            Back to tab
          </button>
        </div>
      )}
      {!playing && !loading && !error && !pipActive && (
        <button className="center-play" onClick={togglePlay} aria-label="Play video" title="Play">
          <PlayIcon size={64} />
        </button>
      )}
      {skipHint && !miniMode && (
        <button className="skip-bn" onClick={() => {
          seekTo(Math.max(0, skipHint.segment[0] + 0.1));
          skippedRef.current.add(segmentKey(skipHint));
          setSkipHint(null);
        }}>
          Skipped {sponsorLabel(skipHint.category)} · Undo
        </button>
      )}

      <div className={showControls} onMouseEnter={() => { setStickyControls(true); clearTimeout(hideTimer.current); }}
        onMouseLeave={() => { setStickyControls(false); }}>
        <div className="seek" onClick={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          const frac = (e.clientX - rect.left) / rect.width;
          seekTo(frac * (duration || 0));
        }}>
          <div className="track">
            <div className="buffered" style={{ width: `${duration ? (buffered / duration) * 100 : 0}%` }} />
            {segments.map((s, i) => duration > 0 && (
              <div key={i} className="sb-seg" style={{
                left: `${(s.segment[0] / duration) * 100}%`, width: `${((s.segment[1] - s.segment[0]) / duration) * 100}%`,
              }} />
            ))}
            {chapters.map((c, i) => i > 0 && duration > 0 && (
              <div key={i} className="chapter-tick" style={{ left: `${(c.startMs / 1000 / duration) * 100}%` }} />
            ))}
            <div className="played" style={{ width: `${duration ? (now / duration) * 100 : 0}%` }} />
            <div className="knob" style={{ left: `${duration ? (now / duration) * 100 : 0}%` }} />
          </div>
        </div>
        <div className="row">
          <button className="tbtn" onClick={togglePlay}>{playing ? <PauseIcon size={20} /> : <PlayIcon size={20} />}</button>
          {queueIndex > 0 && <button className="tbtn" onClick={onPrev}><PrevIcon size={18} /></button>}
          {queue.length > queueIndex + 1 && <button className="tbtn" onClick={onNext}><NextIcon size={18} /></button>}
          <span className="vol">
            <button className="tbtn" onClick={() => setMuted((x) => !x)}>
              {muted || volume === 0 ? <MuteIcon size={18} /> : <VolumeIcon size={18} />}
            </button>
            <input type="range" min={0} max={1} step={0.01} value={muted ? 0 : volume}
              onMouseDown={() => setMuted(false)}
              onChange={(e) => { setVolume(Number(e.target.value)); saveSettings({ volume: Number(e.target.value) }); }} />
          </span>
          <span className="time">
            {formatDuration(now)} / {formatDuration(duration)}
            {currentChapter && (
              <span className="chapter-name" title={currentChapter.title}>
                {' · '}{currentChapter.title}
              </span>
            )}
          </span>
          <span className="spacer" />
          {sponsorNear && (
            <button className="tbtn" onClick={() => seekTo(sponsorNear.segment[1] + 0.1)}
              title={`SponsorBlock: ${sponsorNear.category}`}>
              Skip {sponsorLabel(sponsorNear.category)}
            </button>
          )}
          {!miniMode && (
            <>
            <button
              className={`tbtn${captionsOn ? ' on' : ''}`}
              onClick={(e) => {
                const rect = e.currentTarget.getBoundingClientRect();
                setGearView('captions');
                setGearAnchor(gearAnchor && gearView === 'captions' ? null : rect);
              }}
              title="Captions (c)"
              aria-label="Captions"
              aria-haspopup="menu"
              aria-expanded={Boolean(gearAnchor) && gearView === 'captions'}
            >
              <CaptionsIcon size={18} />
            </button>
            <button
              className={`tbtn${gearAnchor && gearView === 'main' ? ' on' : ''}`}
              onClick={(e) => {
                const rect = e.currentTarget.getBoundingClientRect();
                setGearView('main');
                setGearAnchor(gearAnchor && gearView === 'main' ? null : rect);
              }}
              title="Settings"
              aria-label="Player settings"
              aria-haspopup="menu"
              aria-expanded={Boolean(gearAnchor) && gearView === 'main'}
            >
              <GearSmallIcon size={18} />
            </button>
            </>
          )}
          {gearAnchor && (
            <Menu anchor={gearAnchor} onClose={closeGear} width={320}
              placement="above" label={gearTitle} className="player-menu">
              <div className="ps-head">
                {gearView === 'main' ? (
                  <span className="ps-title ps-title-main">Settings</span>
                ) : (
                  <button type="button" className="ps-icon ps-back" onClick={() => setGearView('main')}
                    title="Back" aria-label="Back to settings">
                    <BackIcon size={20} />
                  </button>
                )}
                {gearView !== 'main' && <span className="ps-title">{gearTitle}</span>}
                <span className="ps-head-gap" />
                {gearView === 'main' && (
                  <button type="button" className="ps-icon" onClick={closeGear}
                    title="Close" aria-label="Close settings">
                    <CloseIcon size={18} />
                  </button>
                )}
              </div>

              <div className="ps-body">
                {gearView === 'main' && (
                  <div className="ps-list">
                    <button type="button" className="ps-row" onClick={() => setGearView('quality')}>
                      <span className="ps-row-label">Quality</span>
                      <span className="ps-row-end">
                        <span className="ps-row-value">{qualityLabel}</span>
                        <ChevronRightIcon size={18} />
                      </span>
                    </button>
                    <button type="button" className="ps-row" onClick={() => setGearView('speed')}>
                      <span className="ps-row-label">Playback speed</span>
                      <span className="ps-row-end">
                        <span className="ps-row-value">{rate === 1 ? 'Normal' : `${rate}×`}</span>
                        <ChevronRightIcon size={18} />
                      </span>
                    </button>
                    <button type="button" className="ps-row" onClick={() => setGearView('captions')}>
                      <span className="ps-row-label">Subtitles/CC</span>
                      <span className="ps-row-end">
                        <span className="ps-row-value">{captionLabel}</span>
                        <ChevronRightIcon size={18} />
                      </span>
                    </button>
                  </div>
                )}

                {gearView === 'quality' && (
                  <div className="ps-list">
                    <button type="button" className="ps-row" aria-pressed={height === null}
                      onClick={() => { switchQuality(null); closeGear(); }}>
                      <span className="ps-row-label">
                        Auto{qualities?.autoHeight ? ` (${qualities.autoHeight}p)` : ''}
                      </span>
                      {height === null && <CheckIcon size={18} />}
                    </button>
                    {(qualities?.options || []).map((o) => (
                      <button type="button" key={o.height} className="ps-row" aria-pressed={height === o.height}
                        onClick={() => { switchQuality(o.height); closeGear(); }}>
                        <span className="ps-row-label">{o.label}</span>
                        {height === o.height && <CheckIcon size={18} />}
                      </button>
                    ))}
                    {!qualities?.options.length && (
                      <div className="ps-note">Resolution options load with the video.</div>
                    )}
                  </div>
                )}

                {gearView === 'speed' && (
                  <div className="ps-list">
                    {SPEEDS.map((sp) => (
                      <button type="button" key={sp} className="ps-row" aria-pressed={rate === sp}
                        onClick={() => { setRate(sp); saveSettings({ playbackRate: sp }); closeGear(); }}>
                        <span className="ps-row-label">{sp === 1 ? 'Normal' : `${sp}×`}</span>
                        {rate === sp && <CheckIcon size={18} />}
                      </button>
                    ))}
                  </div>
                )}

                {gearView === 'captions' && (
                  <div className="ps-list">
                    <button type="button" className="ps-row" aria-pressed={!captionsOn}
                      onClick={() => { if (captionsOn) toggleCaptions(); closeGear(); }}>
                      <span className="ps-row-label">Off</span>
                      {!captionsOn && <CheckIcon size={18} />}
                    </button>
                    {(qualities?.subtitles?.length || 0) > 0 && <div className="ps-divider" />}
                    {(qualities?.subtitles || []).map((sub) => {
                      const active = captionsOn && settings?.captionLang === sub.languageTag;
                      return (
                        <button type="button" key={sub.languageTag} className="ps-row" aria-pressed={active}
                          onClick={async () => {
                            const u = await window.indio.streams.subtitleUrl(video.videoId, sub.languageTag)
                              .catch(() => null);
                            if (!u) { captionFailure(); return; }
                            setCaptionUrl(u);
                            setCaptionsOn(true);
                            saveSettings({ captionsEnabled: true, captionLang: sub.languageTag });
                            closeGear();
                          }}>
                          <span className="ps-row-label">
                            {sub.label}
                            {sub.autoGenerated && <span className="ps-row-tag">Auto-generated</span>}
                          </span>
                          {active && <CheckIcon size={18} />}
                        </button>
                      );
                    })}
                    {!qualities && <div className="ps-note">Checking available captions…</div>}
                    {qualities && !qualities.subtitles.length && (
                      <div className="ps-note">No captions available for this video.</div>
                    )}
                    {(qualities?.subtitles || []).some((sub) => sub.autoGenerated) && (
                      <div className="ps-note">Auto-generated captions may be inaccurate.</div>
                    )}
                  </div>
                )}
              </div>
            </Menu>
          )}
          <button className="tbtn" onClick={togglePip} title={pipActive ? 'Exit picture in picture' : 'Picture in picture'} aria-pressed={pipActive}>
            <PipIcon size={18} />
          </button>
          {!miniMode && (
            <button className="tbtn" onClick={() => {
              setTheater((x) => { saveSettings({ theater: !x }); return !x; });
            }} title="Theater (t)"><TheaterIcon size={18} /></button>
          )}
          <button className="tbtn" onClick={props.onExitMini} title={miniMode ? 'Exit mini player' : 'Mini player'}>
            <MiniIcon size={18} />
          </button>
          {!miniMode && (
            <button className="tbtn" onClick={toggleFullscreen} title="Full screen (f)">
              {isFullscreen ? <ExitFullscreenIcon size={18} /> : <FullscreenIcon size={18} />}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
