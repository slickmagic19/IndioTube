// Downloads: finished files (playable offline through the local proxy) plus
// live task rows fed by yt-dlp progress events.
import React, { useEffect, useState } from 'react';
import type { DownloadEvent } from '../types';
import { formatDuration } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { Menu, Thumb } from '../components/ui';
import { FolderIcon, MoreIcon, TrashIcon } from '../components/icons';

interface Task { videoId: string; percent: number; receivedBytes: number; totalBytes: number; error?: string }

export function DownloadsScreen() {
  const { lib, op, toast } = useApp();
  const [tasks, setTasks] = useState<Record<string, Task>>({});
  const [anchor, setAnchor] = useState<{ rect: DOMRect; file: string; videoId: string } | null>(null);
  const saved = lib?.downloads || [];

  useEffect(() => {
    const off = window.indio.downloads.onEvent((e: DownloadEvent) => {
      if (e.status === 'done' && e.file) {
        // Record the finished file in the library; it survives restarts.
        window.indio.library.op('addDownload', {
          video: videoCache[e.videoId] || { videoId: e.videoId, title: 'Downloaded video', durationSec: 0, isLive: false, membersOnly: false },
          file: e.file,
          bytes: e.bytes || 0,
          at: Date.now(),
          height: e.height || null,
        }).catch(() => undefined);
        setTasks((t) => { const n = { ...t }; delete n[e.videoId]; return n; });
      } else if (e.status === 'error') {
        setTasks((t) => ({ ...t, [e.videoId]: { ...(t[e.videoId] as any), error: e.error || 'Download failed' } }));
        toast(e.error || 'Download failed');
      } else if (e.status === 'cancelled') {
        setTasks((t) => { const n = { ...t }; delete n[e.videoId]; return n; });
      } else if (e.status === 'progress') {
        setTasks((t) => ({
          ...t,
          [e.videoId]: {
            videoId: e.videoId,
            percent: e.percent ?? t[e.videoId]?.percent ?? 0,
            receivedBytes: e.receivedBytes ?? t[e.videoId]?.receivedBytes ?? 0,
            totalBytes: e.totalBytes ?? t[e.videoId]?.totalBytes ?? 0,
          },
        }));
      } else if (e.status === 'started') {
        setTasks((t) => ({ ...t, [e.videoId]: { videoId: e.videoId, percent: 0, receivedBytes: 0, totalBytes: 0 } }));
      }
    });
    return off;
  }, [toast]);

  const play = async (file: string) => {
    const url = await window.indio.streams.localUrl(file);
    navigate(`/local/${encodeURIComponent(url)}`);
  };

  const taskList = Object.values(tasks);
  return (
    <div>
      <div className="section-head"><h2>Downloads</h2></div>

      {taskList.length > 0 && (
        <>
          <h3 style={{ fontSize: 14 }}>In progress</h3>
          {taskList.map((t) => {
            const video = videoCache[t.videoId];
            return (
              <div className="list-row" key={t.videoId}>
                <div className="thumb">
                  <Thumb src={video?.thumbnail} alt={video?.title || ''} />
                </div>
                <div className="grow">
                  <div className="t">{video?.title || t.videoId}</div>
                  <div className="s">{t.error ? t.error : `${Math.round(t.percent)}%`}</div>
                  <div className="dl-progress"><i style={{ width: `${Math.min(100, t.percent)}%` }} /></div>
                </div>
                <div className="acts">
                  {!t.error && (
                    <button className="icon-btn" title="Cancel" onClick={() => window.indio.downloads.cancel(t.videoId)}>
                      <TrashIcon size={16} />
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </>
      )}

      {!saved.length && !taskList.length ? (
        <div className="state">
          <div>No downloads yet. Use the Download action on any video card or watch page.</div>
          <button onClick={() => navigate('/home')}>Browse Home</button>
        </div>
      ) : (
        saved.map((d, i) => (
          <div className="list-row" key={`${d.video.videoId}-${i}`} onClick={() => play(d.file)}>
            <div className="thumb">
              <Thumb src={d.video.thumbnail} alt={d.video.title}
                duration={d.video.durationSec ? formatDuration(d.video.durationSec) : undefined} />
            </div>
            <div className="grow">
              <div className="t">{d.video.title}</div>
              <div className="s">
                {[d.video.channelName, d.height ? `${d.height}p` : null,
                  d.bytes ? `${Math.round(d.bytes / 1024 / 1024)} MB` : null].filter(Boolean).join(' • ')}
              </div>
            </div>
            <div className="acts">
              <button className="icon-btn" onClick={(e) => {
                e.stopPropagation();
                setAnchor({ rect: e.currentTarget.getBoundingClientRect(), file: d.file, videoId: d.video.videoId });
              }}><MoreIcon size={16} /></button>
            </div>
          </div>
        ))
      )}

      {anchor && (
        <Menu anchor={anchor.rect} onClose={() => setAnchor(null)} width={230}>
          <button onClick={() => { window.indio.downloads.reveal(anchor.file); setAnchor(null); }}>
            <FolderIcon size={16} /> Show in folder
          </button>
          <button onClick={async () => {
            await op('removeDownload', anchor.videoId);
            toast('Removed from list (file stays on disk)');
            setAnchor(null);
          }}>
            <TrashIcon size={16} /> Remove from list
          </button>
        </Menu>
      )}
    </div>
  );
}

// A tiny module-level cache of the videos passed to downloads:start so the
// progress rows can show title/thumbnail; the watch/cards set it before the
// download begins.
export const videoCache: Record<string, any> = {};

// Offline playback of a saved file: plain HTML5 controls on the local proxy
// URL (the streaming Player needs yt-dlp, which is pointless for a file).
export function LocalPlayerScreen({ url, title }: { url: string; title: string }) {
  return (
    <div style={{ maxWidth: 1000, margin: '0 auto' }}>
      <div className="player-frame">
        <video src={url} controls autoPlay style={{ width: '100%', display: 'block', background: '#000' }} />
      </div>
      <h3 style={{ fontWeight: 500 }}>{title}</h3>
      <button className="btn ghost" onClick={() => navigate('/downloads')}>Back to Downloads</button>
    </div>
  );
}
