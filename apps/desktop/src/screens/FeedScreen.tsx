// Home + Trending grids: chips row, auto-loading continuation, retry states.
//
// Home is built by the main process: when signed-out YouTube answers with an
// empty feed, it returns a local mix computed from this device's history,
// subscriptions, likes and searches (the port of Android's LocalFeed.kt).
// A reload (logo / Home click) rotates that mix, so the next pull is genuinely
// different. Scrolling to the end keeps appending: YouTube continuation pages
// for the network feed, a fresh draw from the local pool for the local feed.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { Feed, VideoItem } from '../types';
import { VideoCard } from '../components/VideoCard';
import { GridSkeleton } from '../components/Skeletons';
import { useApp } from '../store';
import { useInfiniteScroll } from '../useInfiniteScroll';

// Last rendered feed per mode. Coming back Home paints this immediately and
// does NOT re-request it: the feed is only reloaded when the viewer asks for
// it (logo / Home while already on Home) or when it is older than 30 minutes.
const feedCache: Record<string, { at: number; feed: Feed; popular: boolean }> = {};
const CACHE_TTL = 30 * 60_000;
const LOCAL_PAGE_SIZE = 40;

export function FeedScreen({ mode }: { mode: 'home' | 'trending' }) {
  const { lib } = useApp();
  const [feed, setFeed] = useState<Feed | null>(null);
  const [chipIdx, setChipIdx] = useState(-1);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [force, setForce] = useState(0);
  const [popular, setPopular] = useState(false);
  const [feedVersion, setFeedVersion] = useState(0);
  const modeRef = useRef<string>('');

  useEffect(() => {
    let cancelled = false;
    // Switching Home <-> Trending swaps in that mode's cache immediately.
    if (modeRef.current !== mode) {
      modeRef.current = mode;
      const c = feedCache[mode];
      setFeed(c && Date.now() - c.at < CACHE_TTL ? c.feed : null);
      setPopular(c ? c.popular : mode === 'trending');
      setChipIdx(-1);
      setExhausted(false);
    }
    setError(null);
    setExhausted(false);
    // Returning to a feed we loaded recently: show it as it was, untouched.
    // `force` is bumped by a logo/Home click, which is the only other reason
    // to go back to the network.
    const cached = feedCache[mode];
    if (force === 0 && cached && Date.now() - cached.at < CACHE_TTL) {
      setFeed(cached.feed);
      setPopular(cached.popular);
      setLoading(false);
      return () => { cancelled = true; };
    }
    setLoading(true);
    const params = chipIdx >= 0 ? feed?.chips[chipIdx]?.params || null : null;
    // Home and Trending go out together: a signed-out Home is usually empty
    // and the popular mix is the fallback, so racing them removes a whole
    // round trip from the first paint.
    const job = mode === 'home'
      ? Promise.all([
        window.indio.home({ params, force: force > 0 }),
        window.indio.trending({ force: force > 0 }).catch(() => null),
      ]).then(([home, trend]) => {
        if (home.videos.length || home.chips.length) return { feed: home, popular: false };
        if (trend && trend.videos.length) return { feed: trend, popular: true };
        return { feed: home, popular: false };
      })
      : window.indio.trending({ force: force > 0 }).then((f) => ({ feed: f, popular: true }));
    job.then(({ feed: f, popular: p }) => {
      if (cancelled) return;
      setFeed(f);
      setPopular(p);
      setFeedVersion((v) => v + 1);
      feedCache[mode] = { at: Date.now(), feed: f, popular: p };
    })
      .catch((e) => { if (!cancelled) setError(String(e?.message || e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [mode, force]); // chips are applied by an explicit reload below

  // Logo / Home clicks ask the current feed to revalidate (YouTube does the
  // same: the feed flips to a fresh mix without leaving the page).
  useEffect(() => {
    const onRefresh = () => setForce((f) => f + 1);
    window.addEventListener('indio-feed-refresh', onRefresh);
    return () => window.removeEventListener('indio-feed-refresh', onRefresh);
  }, []);

  // Chip changes re-request with its params (home) — trending has none.
  const pickChip = (idx: number) => {
    setChipIdx(idx);
    setLoading(true);
    setExhausted(false);
    const params = feed?.chips[idx]?.params || null;
    window.indio.home({ params, force: false })
      .then((f) => {
        setFeed(f);
        setFeedVersion((v) => v + 1);
        // A category feed is not what Home shows on return, so it must not
        // replace the cached unfiltered feed.
        if (chipIdx === -1) feedCache[mode] = { at: Date.now(), feed: f, popular };
      })
      .catch((e) => setError(String(e?.message || e)))
      .finally(() => setLoading(false));
  };

  const loadMore = useCallback(() => {
    if (!feed || loadingMore || exhausted) return;
    const isLocal = Boolean(feed.local);
    const token = feed.continuation;
    if (!isLocal && !token) {
      setExhausted(true);
      return;
    }
    setLoadingMore(true);
    const request: Promise<Feed | VideoItem[]> = isLocal
      ? window.indio.homeMore({ excludeIds: feed.videos.map((v) => v.videoId), limit: LOCAL_PAGE_SIZE })
      : (mode === 'home' ? window.indio.homeCont(token!) : window.indio.trendingCont(token!));
    request.then((result) => {
      const known = new Set(feed.videos.map((v) => v.videoId));
      if (isLocal) {
        const fresh = (result as VideoItem[]).filter((v) => !known.has(v.videoId));
        if (!fresh.length) {
          setExhausted(true);
          return;
        }
        const next = { ...feed, videos: [...feed.videos, ...fresh] };
        setFeed(next);
        if (chipIdx === -1) feedCache[mode] = { at: Date.now(), feed: next, popular };
        return;
      }
      const page = result as Feed;
      const fresh = page.videos.filter((v) => !known.has(v.videoId));
      const nextToken = page.continuation && page.continuation !== feed.continuation
        ? page.continuation
        : null;
      if (!fresh.length && !nextToken) {
        setExhausted(true);
        return;
      }
      const next: Feed = {
        chips: feed.chips,
        videos: [...feed.videos, ...fresh],
        continuation: nextToken,
        local: false,
      };
      setFeed(next);
      if (chipIdx === -1) feedCache[mode] = { at: Date.now(), feed: next, popular };
      if (!nextToken) setExhausted(true);
    }).catch(() => setExhausted(true))
      .finally(() => setLoadingMore(false));
  }, [feed, mode, loadingMore, exhausted, popular, chipIdx]);

  const sentinelRef = useInfiniteScroll(loadMore, [
    feed?.videos.length || 0,
    feed?.local || false,
    exhausted,
    loadingMore,
    mode,
  ]);

  if (loading && !feed) {
    return <div style={{ paddingTop: 6 }}><GridSkeleton count={12} /></div>;
  }
  if (error && !feed) {
    return (
      <div className="state">
        <div>Could not load {mode === 'home' ? 'your Home feed' : 'Trending'}: {error}</div>
        <button onClick={() => setForce((f) => f + 1)}>Retry</button>
      </div>
    );
  }

  const videos = feed?.videos || [];
  // Recommendation feedback (same effect as the Android local feed):
  // "Not interested" pins a video out and "Don't recommend channel" hides
  // the whole channel from the recommendable grids.
  const hiddenIds = new Set(lib?.library.notInterestedIds || []);
  const blockedChannels = new Set(lib?.library.blockedChannelIds || []);
  const visible = videos.filter((v) => !hiddenIds.has(v.videoId)
    && !(v.channelId && blockedChannels.has(v.channelId)));
  // Revalidating with content already on screen keeps the grid and shows a
  // slim activity bar (plus a soft re-entry animation) instead of a skeleton.
  const refreshing = loading && videos.length > 0;
  return (
    <div>
      <div className={`refresh-bar${refreshing ? ' on' : ''}`} aria-hidden="true" />
      {mode === 'home' && feed && feed.chips.length > 0 && (
        <div className="chips">
          <button className={`chip${chipIdx === -1 ? ' active' : ''}`} onClick={() => {
            setChipIdx(-1);
            setLoading(true);
            setExhausted(false);
            window.indio.home({ params: null, force: false })
              .then((f) => {
                setFeed(f);
                setFeedVersion((v) => v + 1);
                feedCache[mode] = { at: Date.now(), feed: f, popular };
              })
              .catch((e) => setError(String(e?.message || e)))
              .finally(() => setLoading(false));
          }}>
            All
          </button>
          {feed.chips.map((c, i) => (
            <button key={c.title + i} className={`chip${chipIdx === i ? ' active' : ''}`} onClick={() => pickChip(i)}>
              {c.title}
            </button>
          ))}
        </div>
      )}
      {popular && mode === 'home' && (
        <div style={{ color: 'var(--text-dim)', padding: '2px 2px 12px' }}>
          Popular right now — signed-out Home is empty on YouTube, so this is the trending mix.
        </div>
      )}
      {!visible.length ? (
        <div className="state">
          <div>Nothing here right away. {mode === 'home' ? 'Signed-out Home can be empty — try Trending or Search.' : ''}</div>
          <button onClick={() => setForce((f) => f + 1)}>Refresh</button>
        </div>
      ) : (
        <div className="grid feed-in" key={feedVersion}>
          {visible.map((v, i) => (
            <VideoCard key={`${v.videoId}-${i}`} video={v} />
          ))}
        </div>
      )}
      {visible.length > 0 && !exhausted && (
        <div ref={sentinelRef} className="feed-sentinel" aria-hidden="true" />
      )}
      {loadingMore && <div style={{ marginTop: 22 }}><GridSkeleton count={4} /></div>}
      {exhausted && visible.length > 0 && (
        <div className="show-more" style={{ color: 'var(--text-dim)' }}>You're all caught up</div>
      )}
    </div>
  );
}
