// Channel page: header + Subscribe, Videos / Live / Playlists / About tabs,
// infinite continuation per tab.
import React, { useEffect, useState } from 'react';
import type { ChannelAbout, ChannelPage, PlaylistItem, VideoItem } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { VideoCard } from '../components/VideoCard';
import { Avatar, Spinner } from '../components/ui';
import { ChannelSkeleton } from '../components/Skeletons';

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

  const subscribed = Boolean(lib?.subscriptions.some((s) => s.channelId === channelId));

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setPage(null);
    setLivePage(null);
    setPlaylists(null);
    setAbout(undefined);
    window.indio.channel(channelId)
      .then((p) => { if (!cancelled) setPage(p); })
      .catch((e) => { if (!cancelled) setError(String(e?.message || e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [channelId]);

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

  if (loading) return <ChannelSkeleton />;
  if (error || !page) {
    return <div className="state"><div>Channel unavailable: {error}</div><button onClick={() => navigate('/home')}>Home</button></div>;
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
        page.videos.length
          ? <div className="grid">{page.videos.map((v, i) => <VideoCard key={`${v.videoId}-${i}`} video={v} showChannel={false} />)}</div>
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
