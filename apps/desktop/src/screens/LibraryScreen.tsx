// History / Watch later / Liked lists and the Continue-watching row. Rows
// support remove, "mark watched", and resume.
import React, { useMemo, useState } from 'react';
import type { HistoryEntry, VideoItem } from '../types';
import { formatDuration, watchedFraction } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { openVideo } from '../components/VideoCard';
import { Menu, Thumb } from '../components/ui';
import { ClockIcon, MoreIcon, PlayIcon, PlusIcon, TrashIcon } from '../components/icons';

const COMPLETED = 0.92;

function continueWatching(history: HistoryEntry[]): HistoryEntry[] {
  const seen = new Set<string>();
  const out: HistoryEntry[] = [];
  for (const entry of history) {
    if (out.length >= 12) break;
    const v = entry.video;
    if (v.membersOnly || v.isLive) continue;
    if (seen.has(v.videoId)) continue;
    const frac = watchedFraction(entry);
    if (frac == null || frac < 0.02 || frac >= COMPLETED) { continue; }
    const duration = entry.durationMs > 0 ? entry.durationMs : v.durationSec * 1000;
    if (duration < 20_000) continue;
    seen.add(v.videoId);
    out.push(entry);
  }
  return out;
}

export function LibraryScreen({ kind }: { kind: 'history' | 'watchlater' | 'liked' }) {
  const { lib, op, toast } = useApp();
  const [anchor, setAnchor] = useState<{ rect: DOMRect; video: VideoItem } | null>(null);

  const history = lib?.library.history || [];
  const cont = useMemo(() => continueWatching(history), [history]);

  const title = kind === 'history' ? 'History' : kind === 'watchlater' ? 'Watch later' : 'Liked videos';
  const rows: VideoItem[] = kind === 'history'
    ? history.map((h) => h.video)
    : kind === 'watchlater' ? (lib?.library.watchLater || []) : (lib?.library.liked || []);

  return (
    <div>
      <div className="section-head" style={{ marginTop: 2 }}>
        <h2>{title}</h2>
        {kind === 'history' && (
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 10 }}>
            <button className="btn ghost" onClick={async () => {
              const paused = Boolean(lib?.library.historyPaused);
              await op('setHistoryPaused', !paused);
              toast(!paused ? 'Watch history paused' : 'Watch history resumed');
            }}>
              {lib?.library.historyPaused ? 'Resume history' : 'Pause history'}
            </button>
            <button className="btn ghost" onClick={() => { op('clearHistory'); toast('History cleared'); }}>
              Clear all
            </button>
          </div>
        )}
      </div>

      {kind === 'history' && cont.length > 0 && (
        <>
          <h3 style={{ fontSize: 15 }}>Continue watching</h3>
          <div className="grid compact">
            {cont.map((e) => {
              const frac = watchedFraction(e) || 0;
              const remaining = Math.max(0, Math.round(((e.durationMs || e.video.durationSec * 1000) - e.positionMs) / 1000));
              return (
                <div className="card" key={`c-${e.video.videoId}`} onClick={() => openVideo(e.video)}>
                  <Thumb src={e.video.thumbnail} alt={e.video.title}
                    duration={formatDuration(e.video.durationSec)} fraction={frac} />
                  <div className="title" style={{ marginTop: 8 }}>{e.video.title}</div>
                  <div className="sub">{remaining > 0 ? `${formatDuration(remaining)} left` : 'Almost done'}</div>
                </div>
              );
            })}
          </div>
        </>
      )}

      {!rows.length ? (
        <div className="state">
          <div>Nothing here yet.</div>
          <button onClick={() => navigate('/home')}>Browse Home</button>
        </div>
      ) : (
        rows.map((v, i) => {
          const entry = kind === 'history' ? history.find((h) => h.video.videoId === v.videoId) : undefined;
          const frac = watchedFraction(entry);
          return (
            <div className="list-row" key={`${v.videoId}-${i}`} onClick={() => openVideo(v)}>
              <div className="thumb">
                <Thumb src={v.thumbnail} alt={v.title} live={v.isLive}
                  duration={v.isLive ? undefined : formatDuration(v.durationSec)} fraction={frac} />
              </div>
              <div className="grow">
                <div className="t">{v.title}</div>
                <div className="s">{v.channelName || ''}</div>
                <div className="s">{[v.viewsText, v.publishedText].filter(Boolean).join(' • ')}</div>
              </div>
              <div className="acts">
                {kind === 'history' && frac != null && frac < COMPLETED && (
                  <button className="icon-btn" title="Mark watched" onClick={async (e) => {
                    e.stopPropagation();
                    await op('markWatched', v);
                    toast('Marked as watched');
                  }}><PlayIcon size={16} /></button>
                )}
                <button className="icon-btn" onClick={(e) => {
                  e.stopPropagation();
                  setAnchor({ rect: e.currentTarget.getBoundingClientRect(), video: v });
                }}><MoreIcon size={16} /></button>
              </div>
            </div>
          );
        })
      )}

      {anchor && (
        <Menu anchor={anchor.rect} onClose={() => setAnchor(null)} width={230}>
          <button onClick={async () => {
            if (kind === 'history') await op('dismissContinueWatching', anchor.video.videoId);
            else if (kind === 'watchlater') await op('toggleWatchLater', anchor.video);
            else await op('toggleLike', anchor.video);
            toast('Removed');
            setAnchor(null);
          }}><TrashIcon size={16} /> Remove from {title}</button>
          {kind === 'history' && (
            <button onClick={async () => {
              await op('markWatched', anchor.video);
              toast('Marked as watched');
              setAnchor(null);
            }}><ClockIcon size={16} /> Mark as watched</button>
          )}
          {kind !== 'watchlater' && (
            <button onClick={async () => {
              await op('toggleWatchLater', anchor.video);
              toast('Saved to Watch later');
              setAnchor(null);
            }}><PlusIcon size={16} /> Add to Watch later</button>
          )}
        </Menu>
      )}
    </div>
  );
}
