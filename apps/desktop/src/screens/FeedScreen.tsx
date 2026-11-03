// Home + Trending grids: chips row, infinite continuation, retry states.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { Feed } from '../types';
import { VideoCard } from '../components/VideoCard';
import { GridSkeleton } from '../components/Skeletons';
import { useApp } from '../store';

// Last rendered feed per mode: returning to Home/Trending paints instantly
// from here while a fresh request revalidates in the background.
const feedCache: Record<string, { at: number; feed: Feed; popular: boolean }> = {};
const CACHE_TTL = 10 * 60_000;

export function FeedScreen({ mode }: { mode: 'home' | 'trending' }) {
  const { lib } = useApp();
  const [feed, setFeed] = useState<Feed | null>(null);
  const [chipIdx, setChipIdx] = useState(-1);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
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
    }
    setLoading(true);
    setError(null);
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
    const params = feed?.chips[idx]?.params || null;
    window.indio.home({ params, force: false })
      .then(setFeed)
      .catch((e) => setError(String(e?.message || e)))
      .finally(() => setLoading(false));
  };

  const loadMore = useCallback(() => {
    if (!feed?.continuation || loadingMore) return;
    setLoadingMore(true);
    const next = mode === 'home'
      ? window.indio.homeCont(feed.continuation)
      : window.indio.trendingCont(feed.continuation);
    next.then((f) => setFeed((cur) => cur && ({
      chips: cur.chips,
      videos: [...cur.videos, ...f.videos],
      continuation: f.continuation === cur.continuation ? null : f.continuation,
    }))).catch(() => setFeed((cur) => cur && { ...cur, continuation: null }))
      .finally(() => setLoadingMore(false));
  }, [feed, mode, loadingMore]);

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
            window.indio.home({ params: null, force: false })
              .then(setFeed)
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
      {feed?.continuation ? (
        <div className="show-more">
          <button className="btn ghost" onClick={loadMore} disabled={loadingMore}>
            {loadingMore ? 'Loading…' : 'Show more videos'}
          </button>
        </div>
      ) : visible.length > 0 ? (
        <div className="show-more" style={{ color: 'var(--text-dim)' }}>You're all caught up</div>
      ) : null}
      {loadingMore && <div style={{ marginTop: 22 }}><GridSkeleton count={4} /></div>}
    </div>
  );
}
