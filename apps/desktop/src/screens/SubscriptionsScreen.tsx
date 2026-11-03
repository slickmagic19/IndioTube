// Subscriptions: channel rail + the "Latest" merged feed. There is no
// sign-in, so the feed is built locally from each subscribed channel's
// first page - the same trick Android's LocalFeed uses, sorted by publish
// age.
import React, { useEffect, useMemo, useState } from 'react';
import type { VideoItem } from '../types';
import { publishAgeMinutes } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { VideoCard } from '../components/VideoCard';
import { Avatar, Spinner } from '../components/ui';
import { GridSkeleton } from '../components/Skeletons';

export function SubscriptionsScreen() {
  const { lib } = useApp();
  const subs = lib?.subscriptions || [];
  const [videos, setVideos] = useState<VideoItem[] | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    if (!subs.length) { setVideos([]); return; }
    let cancelled = false;
    setVideos(null);
    const cacheKey = 'indio-subs-feed';
    const cached = sessionStorage.getItem(cacheKey);
    const cachedAt = Number(sessionStorage.getItem(`${cacheKey}:at`) || 0);
    if (cached && Date.now() - cachedAt < 10 * 60_000 && !refresh) {
      try { setVideos(JSON.parse(cached)); } catch { /* refetch */ }
    }
    Promise.all(subs.slice(0, 18).map(async (s) => {
      try {
        const page = await window.indio.channel(s.channelId);
        return page.videos.map((v) => ({ ...v, channelName: v.channelName || s.name, channelAvatar: v.channelAvatar || s.avatar }));
      } catch { return []; }
    })).then((lists) => {
      if (cancelled) return;
      const merged = lists.flat()
        .filter((v, i, list) => v.videoId && list.findIndex((x) => x.videoId === v.videoId) === i)
        .sort((a, b) => publishAgeMinutes(a.publishedText) - publishAgeMinutes(b.publishedText));
      setVideos(merged);
      try {
        sessionStorage.setItem(cacheKey, JSON.stringify(merged));
        sessionStorage.setItem(`${cacheKey}:at`, String(Date.now()));
      } catch { /* full */ }
    });
    return () => { cancelled = true; };
  }, [subs.map((s) => s.channelId).join(','), refresh]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div>
      <div className="section-head">
        <h2>Subscriptions</h2>
        <button className="btn ghost" onClick={() => setRefresh((r) => r + 1)}>Refresh</button>
      </div>
      {subs.length === 0 ? (
        <div className="state">
          <div>You haven't subscribed to any channels yet. Subscribe from a channel page or the watch page — and sync the list from your phone in Settings.</div>
          <button onClick={() => navigate('/home')}>Browse Home</button>
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', gap: 14, overflowX: 'auto', paddingBottom: 14 }}>
            {subs.map((s) => (
              <div key={s.channelId} style={{ textAlign: 'center', cursor: 'pointer', minWidth: 74 }}
                onClick={() => navigate(`/channel/${s.channelId}`)}>
                <Avatar name={s.name} src={s.avatar} size={64} />
                <div style={{ fontSize: 12, marginTop: 6, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {s.name}
                </div>
              </div>
            ))}
          </div>
          {videos === null ? (
            <GridSkeleton count={8} />
          ) : videos.length === 0 ? (
            <div className="state">No recent uploads from your subscriptions.</div>
          ) : (
            <div className="grid">
              {videos.map((v, i) => <VideoCard key={`${v.videoId}-${i}`} video={v} />)}
            </div>
          )}
        </>
      )}
    </div>
  );
}
