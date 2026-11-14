// Channel page: header + Subscribe, Videos / Live / Playlists / About tabs,
// with the Videos grid appending the next page automatically when scrolled
// to the end.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { ChannelAbout, ChannelPage, PlaylistItem, VideoItem } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { VideoCard } from '../components/VideoCard';
import { Avatar, Spinner } from '../components/ui';
import { ChannelSkeleton } from '../components/Skeletons';
import { useInfiniteScroll } from '../useInfiniteScroll';

const TABS = ['Videos', 'Live', 'Playlists', 'About'] as const;

export function ChannelScreen({ channelId }: { channelId: string }) {
  const { lib, op, toast } = useApp();
  const [tab, setTab] = useState(0);
  const [page, setPage] = useState<ChannelPage | null>(null);
  const [livePage, setLivePage] = useState<ChannelPage | null | 'loading'>(null);
  const [playlists, setPlaylists] = useState<PlaylistItem[] | null>(null);
  const [about, setAbout] = useState<ChannelAbout | null | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [videos, setVideos] = useState<VideoItem[]>([]);
  const [continuation, setContinuation] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [done, setDone] = useState(false);
  // A paging failure keeps the grid and offers a retry instead of pretending
  // the channel ended there.
  const [moreError, setMoreError] = useState<string | null>(null);
  const moreBlocked = useRef(false);
  const [attempt, setAttempt] = useState(0);

  const subscribed = Boolean(lib?.subscriptions.some((s) => s.channelId === channelId));

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setPage(null);
    setLivePage(null);
    setPlaylists(null);
    setAbout(undefined);
    setVideos([]);
    setContinuation(null);
    setDone(false);
    setMoreError(null);
    moreBlocked.current = false;
    window.indio.channel(channelId)
      .then((p) => {
        if (cancelled) return;
        setPage(p);
        setVideos(p.videos);
        setContinuation(p.continuation);
        setDone(!p.continuation);
      })
      .catch((e) => { if (!cancelled) setError(String(e?.message || e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [channelId, attempt]);

  useEffect(() => {
    if (tab === 1 && livePage === null) {
      setLivePage('loading');
      window.indio.channelLive(channelId).then(setLivePage).catch(() => setLivePage(null));
    }
  }, [tab, livePage, channelId]);

  useEffect(() => {
    if (tab === 2 && playlists === null) {
      window.indio.channelPlaylists(channelId).then((p) => setPlaylists(p.playlists)).catch(() => setPlaylists([]));
    }
    if (tab === 3 && about === undefined) {
      window.indio.channelAbout(channelId, page?.aboutToken || null).then(setAbout).catch(() => setAbout(null));
    }
  }, [tab, playlists, about, channelId, page?.aboutToken]);

  const loadMore = useCallback(() => {
    if (tab !== 0 || loadingMore || done || !continuation || moreBlocked.current) return;
    setLoadingMore(true);
    setMoreError(null);
    window.indio.channelCont(continuation, channelId).then((p) => {
      const known = new Set(videos.map((v) => v.videoId));
      const fresh = p.videos.filter((v) => !known.has(v.videoId));
      if (fresh.length) setVideos((cur) => [...cur, ...fresh]);
      const next = p.continuation && p.continuation !== continuation ? p.continuation : null;
      setContinuation(next);
      if (!next) setDone(true);
    }).catch((e) => {
      moreBlocked.current = true;
      setMoreError(String(e?.message || e));
    })
      .finally(() => setLoadingMore(false));
  }, [tab, loadingMore, done, continuation, videos, channelId]);

  const retryMore = () => {
    moreBlocked.current = false;
    setMoreError(null);
    loadMore();
  };

  const sentinelRef = useInfiniteScroll(loadMore, [
    tab, videos.length, continuation, done, loadingMore,
  ]);

  if (loading) return <ChannelSkeleton />;
  if (error || !page) {
    return (
      <div className="state">
        <div>Channel unavailable.</div>
        {error && <div style={{ color: 'var(--text-dim)', maxWidth: 620 }}>{error}</div>}
        <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
          <button onClick={() => setAttempt((n) => n + 1)}>Try again</button>
          <button className="btn ghost" onClick={() => navigate('/home')}>Home</button>
        </div>
      </div>
    );
  }

  const c = page.channel;
  return (
    <div>
      <div className="channel-head">
        <div className="avatar"><Avatar name={c.name} src={c.avatar} size={110} /></div>
        <div style={{ minWidth: 0 }}>
          <h1>{c.name}</h1>
          <div className="sub">
            {[c.subscribersText, c.videoCountText, c.description?.slice(0, 80)].filter(Boolean).join(' • ')}
          </div>
          <div style={{ marginTop: 12, display: 'flex', gap: 10 }}>
            <button className={`pill${subscribed ? ' subbed' : ' red'}`} onClick={() => {
              if (subscribed) { op('unsubscribe', channelId); toast('Unsubscribed'); }
              else { op('subscribe', { channelId, name: c.name, avatar: c.avatar || null }); toast(`Subscribed to ${c.name}`); }
            }}>
              {subscribed ? 'Subscribed' : 'Subscribe'}
            </button>
          </div>
        </div>
      </div>

      <div className="tabs">
        {TABS.map((t, i) => (
          <button key={t} className={tab === i ? 'active' : ''} onClick={() => setTab(i)}>{t}</button>
        ))}
      </div>

      {tab === 0 && (
        videos.length
          ? <>
            <div className="grid">{videos.map((v, i) => <VideoCard key={`${v.videoId}-${i}`} video={v} showChannel={false} />)}</div>
            {!done && <div ref={sentinelRef} className="feed-sentinel" aria-hidden="true" />}
            {loadingMore && <div className="state"><Spinner /></div>}
            {moreError && !loadingMore && (
              <div className="state" style={{ paddingTop: 12 }}>
                <div>{moreError}</div>
                <button onClick={retryMore}>Try again</button>
              </div>
            )}
          </>
          : <div className="state">No videos yet.</div>
      )}
      {tab === 1 && (
        livePage === 'loading' || livePage === null
          ? <div className="state"><Spinner /></div>
          : livePage.videos.length
            ? <div className="grid">{livePage.videos.map((v, i) => <VideoCard key={`l${i}`} video={v} showChannel={false} />)}</div>
            : <div className="state">No live or past streams.</div>
      )}
      {tab === 2 && (
        playlists === null
          ? <div className="state"><Spinner /></div>
          : playlists.length
            ? <div className="grid compact">
              {playlists.map((p) => (
                <div className="card" key={p.playlistId} onClick={() => navigate(`/playlist/yt/${p.playlistId}`)}>
                  <div className="thumb-wrap"><img src={p.thumbnail || ''} alt="" loading="lazy" /></div>
                  <div className="title" style={{ marginTop: 8 }}>{p.title}</div>
                  <div className="sub">{p.videoCountText || ''}</div>
                </div>
              ))}
            </div>
            : <div className="state">This channel has no public playlists.</div>
      )}
      {tab === 3 && (
        about === undefined
          ? <div className="state"><Spinner /></div>
          : <div style={{ maxWidth: 620 }}>
            {about?.description && <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>{about.description}</p>}
            <div className="sub" style={{ marginTop: 10 }}>
              {[about?.subscribersText, about?.videoCountText, about?.viewCountText, about?.joinedText, about?.country]
                .filter(Boolean).join(' • ')}
            </div>
            {about?.links?.length ? (
              <div style={{ marginTop: 14 }}>
                <b>Links</b>
                {about.links.map((l) => (
                  <div key={l.url} style={{ marginTop: 6 }}>
                    <a href={l.url} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)' }}>{l.title}</a>
                    <span className="sub"> {l.url}</span>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
      )}
    </div>
  );
}
