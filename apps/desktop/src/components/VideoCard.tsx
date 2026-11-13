// Video card + related-item row. The ⋮ menu carries the same actions as the
// Android app: mark watched, play next / add to queue, save + download,
// and the recommendation feedback (interested / not interested / block).
import React, { useEffect, useRef, useState } from 'react';
import type { VideoItem } from '../types';
import { formatDuration, metaLine, watchedFraction } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { usePlayback } from '../player/PlaybackHost';
import { Avatar, Menu, MenuHead, MenuSep, Thumb, Verified } from './ui';
import {
  BlockIcon, CheckCircleIcon, CheckIcon, ClockIcon, DownloadIcon, EyeOffIcon, MoreIcon,
  PlaylistPlayIcon, PlusIcon, QueueIcon, ThumbUpIcon,
} from './icons';
import { videoCache } from '../screens/DownloadsScreen';

export function openVideo(video: VideoItem) {
  navigate(`/watch/${video.videoId}`);
}

export function downloadVideo(video: VideoItem, height: number | null, toast: (m: string) => void) {
  videoCache[video.videoId] = video;
  window.indio.downloads.start(video, height).then((res) => {
    if (res.started) toast(`Downloading ${video.title.slice(0, 40)}…`);
    else if (res.reason !== 'already downloading') toast('Already downloading');
  }).catch((e) => toast(String(e.message || e)));
}

// Hovering a card warms the stream ladder (yt-dlp is the slow part of
// starting a video); one extraction per video, cached in the main process.
const prefetched = new Set<string>();

export function VideoCard({ video, showChannel = true, fraction }: {
  video: VideoItem;
  showChannel?: boolean;
  fraction?: number | null;
}) {
  const { lib, op, toast, settings } = useApp();
  const playback = usePlayback();
  const [anchor, setAnchor] = useState<DOMRect | null>(null);
  const prefetchTimer = useRef<number>(0);

  const startPrefetch = () => {
    if (video.isLive || video.membersOnly || prefetched.has(video.videoId)) return;
    window.clearTimeout(prefetchTimer.current);
    prefetchTimer.current = window.setTimeout(() => {
      prefetched.add(video.videoId);
      window.indio.streams.qualities(video.videoId).catch(() => undefined);
    }, 350);
  };
  const cancelPrefetch = () => window.clearTimeout(prefetchTimer.current);

  const liked = Boolean(lib?.library.liked.some((v) => v.videoId === video.videoId));
  const later = Boolean(lib?.library.watchLater.some((v) => v.videoId === video.videoId));
  const frac = fraction !== undefined ? fraction
    : watchedFraction(lib?.library.history.find((h) => h.video.videoId === video.videoId));
  const watched = (frac ?? 0) >= 0.92;

  const run = (label: string, fn: Promise<unknown>, actionLabel?: string, action?: () => void) => {
    fn.then(() => { if (label) toast(label, actionLabel, action); }).catch((e) => toast(String(e?.message || e)));
    setAnchor(null);
  };

  return (
    <div className="card" data-vid={video.videoId} onClick={() => openVideo(video)}
      onMouseEnter={startPrefetch} onMouseLeave={cancelPrefetch}>
      <Thumb
        src={video.thumbnail}
        fallbackSrc={`https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg`}
        alt={video.title}
        live={video.isLive}
        duration={video.isLive ? undefined : formatDuration(video.durationSec)}
        members={video.membersOnly}
        fraction={frac}
      />
      <div className="meta">
        {showChannel && (
          <div className="avatar" onClick={(e) => {
            e.stopPropagation();
            if (video.channelId) navigate(`/channel/${video.channelId}`);
          }}>
            <Avatar name={video.channelName} src={video.channelAvatar} size={36} />
          </div>
        )}
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="title">{video.title}</div>
          <div className="sub">
            {video.channelVerified ? <Verified size={12} /> : null}
            {metaLine(video)}
          </div>
        </div>
        <button
          className="icon-btn" style={{ width: 30, height: 30 }}
          onClick={(e) => { e.stopPropagation(); setAnchor(e.currentTarget.getBoundingClientRect()); }}
          title="More"
        >
          <MoreIcon size={16} />
        </button>
      </div>

      {anchor && (
        <Menu anchor={anchor} onClose={() => setAnchor(null)} width={250} label="Video options">
          <button onClick={() => run(watched ? 'Marked as unwatched' : 'Marked as watched',
            watched ? op('markUnwatched', video.videoId) : op('markWatched', video))}>
            {watched ? <CheckCircleIcon size={18} /> : <CheckIcon size={18} />}
            {watched ? 'Mark as unwatched' : 'Mark as watched'}
          </button>
          <button onClick={() => { playback.enqueue(video, 'next'); toast('Playing next'); setAnchor(null); }}>
            <PlaylistPlayIcon size={18} /> Play next
          </button>
          <button onClick={() => { playback.enqueue(video, 'end'); toast('Added to queue'); setAnchor(null); }}>
            <QueueIcon size={18} /> Add to queue
          </button>
          <MenuSep />
          <button onClick={() => run(liked ? 'Removed from Liked' : 'Liked', op('toggleLike', video))}>
            {liked ? <CheckIcon size={18} /> : <ThumbUpIcon size={18} />} {liked ? 'Liked' : 'Like'}
          </button>
          <button onClick={() => run(later ? 'Removed from Watch later' : 'Saved to Watch later', op('toggleWatchLater', video))}>
            <ClockIcon size={18} /> {later ? 'Remove from Watch later' : 'Save to Watch later'}
          </button>
          <PlaylistPicker video={video} onClose={() => setAnchor(null)} />
          {!video.membersOnly && !video.isLive && (
            <button onClick={() => {
              downloadVideo(video, settings?.downloadHeight ?? 720, toast);
              setAnchor(null);
            }}>
              <DownloadIcon size={18} /> Download
            </button>
          )}
          <button onClick={() => {
            navigator.clipboard.writeText(`https://www.youtube.com/watch?v=${video.videoId}`);
            toast('Link copied'); setAnchor(null);
          }}>
            Copy link
          </button>
          <MenuSep />
          <button onClick={() => run('Interested — more videos like this', op('markInterested', video))}>
            <ThumbUpIcon size={18} /> Interested
          </button>
          <button onClick={() => {
            op('markNotInterested', video).then(() => {
              toast('Not interested — hidden from Home', 'Undo', () => { op('undoNotInterested', video.videoId); });
            }).catch(() => undefined);
            setAnchor(null);
          }}>
            <BlockIcon size={18} /> Not interested
          </button>
          {video.channelId && (
            <button onClick={() => run(`Hiding ${video.channelName || 'this channel'} from recommendations`,
              op('blockChannel', video))}>
              <EyeOffIcon size={18} /> Don't recommend channel
            </button>
          )}
        </Menu>
      )}
    </div>
  );
}

export function PlaylistPicker({ video, onClose }: { video?: VideoItem; onClose(): void }) {
  const { lib, op, toast } = useApp();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const playlists = lib?.library.playlists || [];

  const create = async () => {
    const clean = name.trim();
    if (!clean) return;
    const pl = await op<any>('createPlaylist', clean);
    if (video) await op('addToPlaylist', pl.id, video);
    toast(video ? `Created "${clean}" and saved` : `Created "${clean}"`);
    setName('');
    setCreating(false);
    onClose();
  };

  if (creating) {
    return (
      <div style={{ padding: 8 }} onClick={(e) => e.stopPropagation()}>
        <input className="text-input" style={{ width: '100%' }} autoFocus placeholder="Playlist name"
          value={name} onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') create(); }} />
        <div style={{ display: 'flex', gap: 8, marginTop: 8, justifyContent: 'flex-end' }}>
          <button className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn" onClick={create}>Create</button>
        </div>
      </div>
    );
  }

  return (
    <>
      <MenuHead>Save to playlist</MenuHead>
      {playlists.map((pl) => (
        <button key={pl.id} onClick={() => {
          if (!video) { navigate(`/playlist/local/${pl.id}`); onClose(); return; }
          op('addToPlaylist', pl.id, video).then(() => toast(`Saved to ${pl.name}`));
          onClose();
        }}>
          <PlaylistMiniIcon /> {pl.name}
        </button>
      ))}
      <button onClick={() => setCreating(true)}><PlusIcon size={18} /> New playlist</button>
    </>
  );
}

function PlaylistMiniIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round">
      <path d="M4 7h12M4 12h12M4 17h8" />
      <path d="M17 13.5v5.5l4-2.75z" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function RelatedCard({ video, active }: { video: VideoItem; active?: boolean }) {
  // Some related-rail lockups carry no thumbnail URL; fall back to the
  // canonical i.ytimg poster so the rail never shows a gray hole.
  const fallback = `https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg`;
  const [thumb, setThumb] = useState(video.thumbnail || fallback);
  useEffect(() => { setThumb(video.thumbnail || fallback); }, [video.videoId, video.thumbnail]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="related-item" onClick={() => openVideo(video)} style={active ? { background: 'var(--chip)' } : undefined}>
      <div className="thumb">
        <img src={thumb} alt="" loading="lazy"
          onError={() => { if (thumb !== fallback) setThumb(fallback); }} />
        {video.isLive
          ? <span className="duration live">LIVE</span>
          : video.durationSec > 0 && <span className="duration">{formatDuration(video.durationSec)}</span>}
      </div>
      <div style={{ minWidth: 0 }}>
        <div className="t">{video.title}</div>
        <div className="s">{video.channelName || ''}</div>
        <div className="s">{[video.viewsText, video.publishedText].filter(Boolean).join(' • ')}</div>
      </div>
    </div>
  );
}
