// Persistent playback host: ONE <Player> instance lives here for the whole
// session, so leaving the watch page keeps the video playing (YouTube's mini
// player behaviour) instead of restarting it.
//
// Layout model: the player is rendered in a fixed layer that either tracks a
// slot element on the watch page (measured every frame while scrolling) or
// docks to the bottom-right corner as the mini player. The media elements are
// never unmounted for a layout change, which is what keeps audio/video alive.
import React, {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import type { VideoItem, WatchData } from '../types';
import { navigate } from '../router';
import { Player } from './Player';
import { enqueue as enqueueQueue } from './queueLogic';
import { CloseIcon, ExitFullscreenIcon } from '../components/icons';

interface QueueState { items: VideoItem[]; index: number }

interface PlaybackApi {
  video: VideoItem | null;
  watch: WatchData | null;
  queue: QueueState;
  mini: boolean;
  /** Start (or switch to) a video immediately, before its metadata arrives. */
  play(video: VideoItem): void;
  /** Attach the freshly loaded watch metadata + queue for the current video. */
  setWatchData(watch: WatchData, video: VideoItem): void;
  next(): void;
  prev(): void;
  /** "Play next" / "Add to queue" from any card menu. */
  enqueue(video: VideoItem, mode: 'next' | 'end'): void;
  setMini(on: boolean): void;
  /** Stop playback and hide the player. */
  close(): void;
  /** The watch page registers the element the player should cover. */
  registerSlot(el: HTMLDivElement | null): void;
}

const Ctx = createContext<PlaybackApi>(null as any);

const MINI_WIDTH = 384;
const MINI_HEIGHT = 216;

export function PlaybackHost({ children }: { children: React.ReactNode }) {
  const [video, setVideo] = useState<VideoItem | null>(null);
  const [watch, setWatch] = useState<WatchData | null>(null);
  const [queue, setQueue] = useState<QueueState>({ items: [], index: -1 });
  const [mini, setMini] = useState(false);
  const [slot, setSlot] = useState<HTMLDivElement | null>(null);
  const [geom, setGeom] = useState<{ top: number; left: number; width: number; height: number } | null>(null);

  const queueRef = useRef(queue);
  useEffect(() => { queueRef.current = queue; }, [queue]);
  const videoRef = useRef(video);
  useEffect(() => { videoRef.current = video; }, [video]);

  const onWatchRoute = () => window.location.hash.startsWith('#/watch/');

  const play = useCallback((v: VideoItem) => {
    setVideo(v);
    setWatch(null);
    setQueue((q) => {
      const idx = q.items.findIndex((x) => x.videoId === v.videoId);
      return idx >= 0 ? { ...q, index: idx } : { items: [v], index: 0 };
    });
  }, []);

  const setWatchData = useCallback((w: WatchData, v: VideoItem) => {
    setWatch(w);
    setVideo((cur) => (cur && cur.videoId === v.videoId ? { ...v, ...cur, ...v } : v));
    setQueue((q) => {
      const idx = q.items.findIndex((x) => x.videoId === v.videoId);
      if (idx >= 0) {
        const items = q.items.map((x) => (x.videoId === v.videoId ? { ...x, ...v } : x));
        return { items, index: idx };
      }
      // Brand new video: seed the up-next chain from its related rail.
      const merged = [v, ...(w.related || [])]
        .filter((x, i, list) => x.videoId && list.findIndex((y) => y.videoId === x.videoId) === i);
      return { items: merged, index: 0 };
    });
  }, []);

  const next = useCallback(() => {
    const q = queueRef.current;
    const nxt = q.items[q.index + 1];
    if (!nxt) return;
    // On the watch page the page follows along; in mini mode only the player
    // switches, so the user keeps browsing where they are.
    if (onWatchRoute()) navigate(`/watch/${nxt.videoId}`);
    else play(nxt);
  }, [play]);

  const prev = useCallback(() => {
    const q = queueRef.current;
    const before = q.items[q.index - 1];
    if (!before) return;
    if (onWatchRoute()) navigate(`/watch/${before.videoId}`);
    else play(before);
  }, [play]);

  const close = useCallback(() => {
    setVideo(null);
    setWatch(null);
    setQueue({ items: [], index: -1 });
    setMini(false);
  }, []);

  const enqueue = useCallback((v: VideoItem, mode: 'next' | 'end') => {
    if (!videoRef.current) { play(v); return; }
    setQueue((q) => enqueueQueue(q.items, q.index, v, mode));
  }, [play]);

  const registerSlot = useCallback((el: HTMLDivElement | null) => setSlot(el), []);

  // YouTube behaviour: leaving the watch page docks the player to the corner,
  // coming back expands it. Entering mini from the watch page also pops the
  // page back to Home so the floating player sits over content.
  useEffect(() => {
    const applyRoute = () => {
      if (!videoRef.current) return;
      setMini(!window.location.hash.startsWith('#/watch/'));
    };
    window.addEventListener('hashchange', applyRoute);
    return () => window.removeEventListener('hashchange', applyRoute);
  }, []);

  const toggleMini = useCallback((on: boolean) => {
    setMini(on);
    if (on && window.location.hash.startsWith('#/watch/')) navigate('/home');
  }, []);

  const expand = useCallback(() => {
    const id = videoRef.current?.videoId;
    setMini(false);
    if (id) navigate(`/watch/${id}`);
  }, []);

  const api = useMemo<PlaybackApi>(() => ({
    video, watch, queue, mini,
    play, setWatchData, next, prev, enqueue, setMini: toggleMini, close, registerSlot,
  }), [video, watch, queue, mini, play, setWatchData, next, prev, enqueue, toggleMini, close, registerSlot]);

  // Track the watch page slot: scroll (captured, so inner scrollers count),
  // resize, slot size changes and a slow poll for layout shifts like theater.
  useEffect(() => {
    if (!video || mini) { setGeom(null); return undefined; }
    const measure = () => {
      if (!slot) { setGeom(null); return; }
      const r = slot.getBoundingClientRect();
      if (!r.width || !r.height) { setGeom(null); return; }
      setGeom((prev) => (
        prev && Math.abs(prev.top - r.top) < 0.5 && Math.abs(prev.left - r.left) < 0.5
          && Math.abs(prev.width - r.width) < 0.5 && Math.abs(prev.height - r.height) < 0.5
          ? prev
          : { top: r.top, left: r.left, width: r.width, height: r.height }
      ));
    };
    measure();
    let raf = 0;
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(measure);
    };
    document.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onScroll);
    const ro = slot ? new ResizeObserver(onScroll) : null;
    if (ro && slot) ro.observe(slot);
    const poll = window.setInterval(measure, 400);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onScroll);
      ro?.disconnect();
      window.clearInterval(poll);
    };
  }, [video, mini, slot]);

  // Esc in the mini player returns to the watch page (the player owns the
  // key handler); the X closes playback entirely.
  const style: React.CSSProperties = mini
    ? { width: MINI_WIDTH, height: MINI_HEIGHT, right: 20, bottom: 20 }
    : geom
      ? { top: geom.top, left: geom.left, width: geom.width, height: geom.height }
      : { display: 'none' };

  return (
    <Ctx.Provider value={api}>
      {children}
      {video && (
        <div className={`playback-layer${mini ? ' mini' : ''}`} style={style}
          data-testid="playback-layer">
          <Player
            video={video}
            watch={watch}
            queue={queue.items}
            queueIndex={queue.index}
            onNext={next}
            onPrev={prev}
            miniMode={mini}
            onExitMini={() => { if (mini) expand(); else toggleMini(true); }}
          />
          {mini && (
            <div className="mini-chrome">
              <button className="mini-open" title="Back to watch page" aria-label="Back to watch page"
                onClick={expand}>
                <ExitFullscreenIcon size={16} />
              </button>
              <button className="mini-close" title="Close mini player" aria-label="Close mini player"
                onClick={close}>
                <CloseIcon size={16} />
              </button>
            </div>
          )}
        </div>
      )}
    </Ctx.Provider>
  );
}

export function usePlayback() { return useContext(Ctx); }
