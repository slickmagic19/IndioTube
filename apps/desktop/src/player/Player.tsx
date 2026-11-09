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
import {
  DEFAULT_SPONSOR_CATEGORIES, autoSkipTarget, segmentKey, sponsorLabel, sponsorSegmentNear,
  type SbSegment,
} from './sponsorLogic';
import {
  CaptionsIcon, CheckIcon, CloseIcon, GearSmallIcon, HdIcon, MiniIcon, MuteIcon, NextIcon,
  PauseIcon, PipIcon, PlayIcon, PrevIcon, SpeedIcon, TheaterIcon, VolumeIcon,
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
  const [gearTab, setGearTab] = useState<'quality' | 'speed' | 'subs'>('quality');
  const [captionsOn, setCaptionsOn] = useState(Boolean(settings?.captionsEnabled));
  const [captionUrl, setCaptionUrl] = useState<string | null>(null);
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

  // ---- source resolution ---------------------------------------------------

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setQualities(null);
    setSrcs(null);
    setCaptionUrl(null);
    setSegments([]);
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
        setSrcs(src);
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
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // height intentionally omitted: the src effect below handles quality swaps
  }, [video.videoId, video.membersOnly, video.isLive]); // eslint-disable-line react-hooks/exhaustive-deps

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
    window.indio.library.op<number>('enrichVideo', video).catch(() => undefined);
    window.indio.library.op<number>('noteOpen', video).catch(() => undefined);
    window.indio.library.op<number>('resumePosition', video.videoId)
      .then((pos) => { if (!cancelled) resumeRef.current = Number(pos) || 0; });
    return () => { cancelled = true; };
  }, [video.videoId]); // eslint-disable-line react-hooks/exhaustive-deps

  const saveProgress = useCallback(() => {
    const v = videoRef.current;
    if (!v || !v.duration || Number.isFinite(v.duration) === false) return;
    op('notePosition', video.videoId, Math.round(v.currentTime * 1000), Math.round(v.duration * 1000))
      .catch(() => undefined);
  }, [op, video.videoId]);

  // ---- playback plumbing -------------------------------------------------------

  const recoverStream = useCallback(async () => {
    if (recoveryRef.current.videoId !== video.videoId) {
      recoveryRef.current = { videoId: video.videoId, tries: 0 };
    }
    if (recoveryRef.current.tries >= 1) {
      setError('The stream failed to load. Try a lower quality.');
      return;
    }
    recoveryRef.current.tries += 1;
    setLoading(true);
    setError(null);
    try {
      const q = await window.indio.streams.qualities(video.videoId, true);
      setQualities(q);
      const src = await window.indio.streams.playUrl(video.videoId, height);
      setSrcs(src);
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setLoading(false);
    }
  }, [video.videoId, height]);

  const togglePlay = useCallback(() => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (!v) return;
    if (v.paused) {
      v.play().catch(() => setError('Playback was blocked — click to start.'));
      if (a && srcs?.audio) a.play().catch(() => undefined);
    } else {
      v.pause();
      if (a) a.pause();
    }
  }, [srcs]);

  const seekTo = useCallback((seconds: number) => {
    const v = videoRef.current;
    if (!v || !isFinite(v.duration)) return;
    const pos = Math.max(0, Math.min(v.duration - 0.5, seconds));
    v.currentTime = pos;
    lastSeekRef.current = Date.now();
    const a = audioRef.current;
    if (a && srcs?.audio) {
      // Muted until the sync tick proves the clock matches: the audio element
      // must never be heard at last video's position after a seek/switch.
      a.muted = true;
      lastSeekRef.current = Date.now();
      if (a.readyState >= 1) {
        try { a.currentTime = pos; pendingAudioSeek.current = null; }
        catch { pendingAudioSeek.current = pos; }
      } else {
        pendingAudioSeek.current = pos;
      }
    }
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
      setSrcs(src);
      requestAnimationFrame(() => {
        const vv = videoRef.current;
        const aa = audioRef.current;
        if (!vv) return;
        vv.currentTime = t;
        if (aa && src.audio) aa.currentTime = t;
        if (wasPlaying) { vv.play().catch(() => undefined); aa?.play().catch(() => undefined); }
      });
    } catch (e: any) {
      toast(String(e?.message || e));
    }
  }, [saveSettings, toast, video.videoId]);

  // Keep the media elements in sync when the source pair changes.
  useEffect(() => {
    const v = videoRef.current;
    const a = audioRef.current;
    if (!v || !srcs) return;
    const t = v.currentTime;
    lastSeekRef.current = Date.now();
    pendingAudioSeek.current = resumeRef.current > 0 ? resumeRef.current : t;
    v.src = srcs.video;
    if (a) {
      if (srcs.audio) a.src = srcs.audio; else a.removeAttribute('src');
      // Start silent; the sync tick unmutes once both clocks agree.
      a.muted = true;
      a.load();
    }
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
    v.play().catch(() => undefined);
    // No audio autoplay here: the tick starts it when it is in sync.
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
          audioPaused: a.paused,
          audioTime: a.currentTime,
          videoTime: v.currentTime,
          muted,
          lastSeekAt: lastSeekRef.current,
          now: Date.now(),
          playbackRate: rate,
        });
        if (action.pause && !a.paused) a.pause();
        if (action.play && a.paused) a.play().catch(() => undefined);
        // Soft drift correction: a hair faster/slower until the clocks agree,
        // otherwise back to the user's chosen speed.
        const wantedRate = action.rate ?? rate;
        if (Math.abs(a.playbackRate - wantedRate) > 0.001) a.playbackRate = wantedRate;
        if (action.seekTo !== undefined) {
          try { a.currentTime = action.seekTo; } catch { /* retry next tick */ }
        }
        if (a.muted !== action.muted) a.muted = action.muted;
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
        case 'c': setCaptionsOn((x) => {
          saveSettings({ captionsEnabled: !x });
          return !x;
        }); break;
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

  // A <track> is inert until its TextTrack mode is "showing". The element only
  // mounts once the VTT URL is known, and swapping sources re-creates the track,
  // so re-assert the mode whenever either changes. Hidden (not "disabled") keeps
  // the cues parsed so toggling back on is instant.
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    const sync = () => {
      for (let i = 0; i < v.textTracks.length; i += 1) {
        const track = v.textTracks[i];
        if (track.kind !== 'subtitles' && track.kind !== 'captions') continue;
        track.mode = captionsOn ? 'showing' : 'hidden';
      }
    };
    sync();
    v.textTracks.addEventListener('addtrack', sync);
    return () => v.textTracks.removeEventListener('addtrack', sync);
  }, [captionsOn, captionUrl, srcs]);

  const sponsorNear = settings?.sponsorBlock ? sponsorSegmentNear(segments, now) : null;
  const showControls = !miniMode ? 'controls' : 'controls stick';

  return (
    <div ref={frameRef} className={`player-frame${(!playing && !loading && !error) ? ' paused' : ''}`}>
      <video
        ref={videoRef}
        onClick={togglePlay}
        onSeeking={() => { lastSeekRef.current = Date.now(); }}
        onSeeked={() => {
          lastSeekRef.current = Date.now();
          const a = audioRef.current;
          const v = videoRef.current;
          if (a && v && srcs?.audio && a.readyState >= 1) {
            try { a.currentTime = v.currentTime; } catch { /* settle window covers it */ }
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
        onPlay={() => setPlaying(true)}
        onPause={() => { setPlaying(false); saveProgress(); }}
        onEnded={() => { saveProgress(); if (settings?.autoplay && queue.length > queueIndex + 1) onNext(); }}
        onError={(e) => {
          // Code 1 is "aborted", which is normal while switching sources.
          if (e.currentTarget.error?.code === 1) return;
          if (srcs) recoverStream();
        }}
        crossOrigin="anonymous"
        playsInline
      >
        {captionUrl && captionsOn && (
          <track
            kind="subtitles"
            src={captionUrl}
            srcLang={settings?.captionLang || 'en'}
            label="Captions"
            default
            onError={captionFailure}
          />
        )}
      </video>
      <audio
        ref={audioRef}
        playsInline
        preload="auto"
        style={{ display: 'none' }}
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
          lastSeekRef.current = Date.now();
        }}
      />

      {loading && (
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
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
                setSrcs(src);
                setLoading(false);
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
          <button className={`tbtn${captionsOn ? ' on' : ''}`} onClick={toggleCaptions} title="Captions (c)">
            <CaptionsIcon size={18} />
          </button>
          <button
            className={`tbtn${gearAnchor ? ' on' : ''}`}
            onClick={(e) => setGearAnchor(e.currentTarget.getBoundingClientRect())}
            title="Settings"
            aria-label="Player settings"
            aria-haspopup="menu"
            aria-expanded={Boolean(gearAnchor)}
          >
            <GearSmallIcon size={18} />
          </button>
          {gearAnchor && (
            <Menu anchor={gearAnchor} onClose={() => setGearAnchor(null)} width={320}
              placement="above" label="Player settings" className="player-menu">
              <div className="ps-head">
                <span className="ps-title">Settings</span>
                <button type="button" className="ps-x" onClick={() => setGearAnchor(null)}
                  title="Close" aria-label="Close settings">
                  <CloseIcon size={16} />
                </button>
              </div>
              <div role="tablist" aria-label="Settings sections" className="ps-tabs">
                {([
                  ['quality', 'Quality', HdIcon],
                  ['speed', 'Speed', SpeedIcon],
                  ['subs', 'Captions', CaptionsIcon],
                ] as const).map(([id, label, Icon]) => (
                  <button key={id} type="button" role="tab" aria-selected={gearTab === id}
                    className={`ps-tab${gearTab === id ? ' sel' : ''}`} onClick={() => setGearTab(id)}>
                    <Icon size={18} />
                    <span>{label}</span>
                  </button>
                ))}
              </div>
              <div className="ps-body" role="tabpanel">
                {gearTab === 'quality' && (
                  <div className="ps-section">
                    <div className="ps-section-title">Resolution</div>
                    <button type="button" aria-pressed={height === null}
                      className={`ps-row${height === null ? ' sel' : ''}`} onClick={() => switchQuality(null)}>
                      <span className="ps-row-main">
                        <span className="ps-row-label">Auto</span>
                        <span className="ps-row-sub">
                          {qualities?.autoHeight ? `Up to ${qualities.autoHeight}p` : 'Best for your connection'}
                        </span>
                      </span>
                      {height === null && <CheckIcon size={18} />}
                    </button>
                    {(qualities?.options || []).map((o) => (
                      <button type="button" key={o.height} aria-pressed={height === o.height}
                        className={`ps-row${height === o.height ? ' sel' : ''}`}
                        onClick={() => switchQuality(o.height)}>
                        <span className="ps-row-main">
                          <span className="ps-row-label">{o.label}</span>
                        </span>
                        {height === o.height && <CheckIcon size={18} />}
                      </button>
                    ))}
                    {!qualities?.options.length && (
                      <div className="ps-empty">Resolution options load with the video.</div>
                    )}
                  </div>
                )}
                {gearTab === 'speed' && (
                  <div className="ps-section">
                    <div className="ps-section-title">Playback speed</div>
                    {SPEEDS.map((s) => (
                      <button type="button" key={s} aria-pressed={rate === s}
                        className={`ps-row${rate === s ? ' sel' : ''}`}
                        onClick={() => { setRate(s); saveSettings({ playbackRate: s }); }}>
                        <span className="ps-row-main">
                          <span className="ps-row-label">{s === 1 ? 'Normal' : `${s}×`}</span>
                        </span>
                        {rate === s && <CheckIcon size={18} />}
                      </button>
                    ))}
                  </div>
                )}
                {gearTab === 'subs' && (
                  <div className="ps-section">
                    <div className="ps-section-title">Captions</div>
                    <button type="button" aria-pressed={captionsOn}
                      className={`ps-row${captionsOn ? ' sel' : ''}`} onClick={toggleCaptions}>
                      <span className="ps-row-main">
                        <span className="ps-row-label">Show captions</span>
                        <span className="ps-row-sub">{captionsOn ? 'On' : 'Off'}</span>
                      </span>
                      <span className={`ps-switch${captionsOn ? ' on' : ''}`} aria-hidden="true"><i /></span>
                    </button>
                    {(qualities?.subtitles || []).map((s) => (
                      <button type="button" key={s.languageTag}
                        className={`ps-row${settings?.captionLang === s.languageTag && captionsOn ? ' sel' : ''}`}
                        onClick={async () => {
                          const u = await window.indio.streams.subtitleUrl(video.videoId, s.languageTag)
                            .catch(() => null);
                          if (!u) { captionFailure(); return; }
                          setCaptionUrl(u);
                          setCaptionsOn(true);
                          saveSettings({ captionsEnabled: true, captionLang: s.languageTag });
                        }}>
                        <span className="ps-row-main">
                          <span className="ps-row-label">{s.label}</span>
                          {s.autoGenerated && <span className="ps-row-sub">Auto-generated</span>}
                        </span>
                        {settings?.captionLang === s.languageTag && captionsOn && <CheckIcon size={18} />}
                      </button>
                    ))}
                    {!qualities?.subtitles.length && (
                      <div className="ps-empty">No captions are available for this video.</div>
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
