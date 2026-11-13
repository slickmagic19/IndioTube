// Downloads: the queue (live progress, speed, ETA, pause/resume/cancel) plus
// finished files playable offline through the local proxy.
//
// The queue lives in the main process, so it keeps running while you browse
// other pages and survives an app restart; this screen re-reads it on mount and
// then follows the events.
import React, { useEffect, useState } from 'react';
import type { DownloadEvent, DownloadTask } from '../types';
import { formatDuration } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { Menu, Thumb } from '../components/ui';
import { FolderIcon, MoreIcon, PauseIcon, PlayIcon, TrashIcon } from '../components/icons';

function fmtBytes(n?: number) {
  if (!n || n < 1) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

function fmtSpeed(n?: number) {
  return n && n > 0 ? `${fmtBytes(n)}/s` : null;
}

function fmtEta(sec?: number | null) {
  if (sec == null || sec < 0) return null;
  if (sec < 60) return `${sec}s left`;
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m < 60) return `${m}m ${String(s).padStart(2, '0')}s left`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m left`;
}

function statusLine(t: DownloadTask) {
  const parts: string[] = [];
  if (t.status === 'error') return t.error || 'Download failed';
  if (t.status === 'paused') parts.push('Paused');
  else if (t.status === 'queued') parts.push(t.attempts > 0 ? `Retrying (${t.attempts}/3)…` : 'Queued…');
  else parts.push(`${Math.round(t.percent)}%`);
  if (t.totalBytes > 0) parts.push(`${fmtBytes(t.receivedBytes)} of ${fmtBytes(t.totalBytes)}`);
  else if (t.receivedBytes > 0) parts.push(fmtBytes(t.receivedBytes));
  const speed = t.status === 'downloading' ? fmtSpeed(t.speed) : null;
  if (speed) parts.push(speed);
  const eta = t.status === 'downloading' ? fmtEta(t.eta) : null;
  if (eta) parts.push(eta);
  return parts.join(' • ');
}

export function DownloadsScreen() {
  const { lib, op, toast } = useApp();
  const [tasks, setTasks] = useState<Record<string, DownloadTask>>({});
  const [anchor, setAnchor] = useState<{ rect: DOMRect; file: string; videoId: string } | null>(null);
  const saved = lib?.downloads || [];

  useEffect(() => {
    let alive = true;
    // Whatever the main process already has queued (it may have been running
    // while another page was open).
    window.indio.downloads.list()
      .then((rows) => {
        if (!alive) return;
        const map: Record<string, DownloadTask> = {};
        for (const row of rows) map[row.videoId] = row;
        setTasks(map);
      })
      .catch(() => undefined);

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
        return;
      }
      if (e.status === 'cancelled') {
        setTasks((t) => { const n = { ...t }; delete n[e.videoId]; return n; });
        return;
      }
      setTasks((t) => {
        const prev = t[e.videoId];
        const base: DownloadTask = prev || {
          videoId: e.videoId,
          video: videoCache[e.videoId] || { videoId: e.videoId, title: e.videoId, durationSec: 0, isLive: false, membersOnly: false },
          height: e.height ?? null,
          status: 'queued',
          percent: 0,
          receivedBytes: 0,
          totalBytes: 0,
          speed: 0,
          eta: null,
          file: '',
          bytes: 0,
          error: null,
          attempts: 0,
          at: Date.now(),
        };
        const status: DownloadTask['status'] = e.status === 'paused' ? 'paused'
          : e.status === 'error' ? 'error'
            : e.status === 'queued' ? 'queued' : 'downloading';
        return {
          ...t,
          [e.videoId]: {
            ...base,
            video: base.video,
            status,
            height: e.height ?? base.height,
            percent: e.percent ?? base.percent,
            receivedBytes: e.receivedBytes ?? base.receivedBytes,
            totalBytes: e.totalBytes ?? base.totalBytes,
            speed: e.speed ?? (status === 'downloading' ? base.speed : 0),
            eta: e.eta === undefined ? base.eta : e.eta,
            error: status === 'error' ? (e.error || 'Download failed') : null,
            attempts: e.attempts ?? base.attempts,
          },
        };
      });
      if (e.status === 'error') toast(e.error || 'Download failed');
    });
    return () => { alive = false; off(); };
  }, [toast]);

  const play = async (file: string) => {
    const url = await window.indio.streams.localUrl(file);
    navigate(`/local/${encodeURIComponent(url)}`);
  };

  const taskList = Object.values(tasks).sort((a, b) => a.at - b.at);
  const active = taskList.filter((t) => t.status === 'downloading' || t.status === 'queued').length;
  return (
    <div>
      <div className="section-head">
        <h2>Downloads</h2>
        {active > 0 && <span className="dl-count">{active} active</span>}
      </div>

      {taskList.length > 0 && (
        <>
          <h3 style={{ fontSize: 14 }}>In progress</h3>
          {taskList.map((t) => {
            const video = t.video || videoCache[t.videoId];
            const busy = t.status === 'downloading' || t.status === 'queued';
            return (
              <div className="list-row" key={t.videoId}>
                <div className="thumb">
                  <Thumb src={video?.thumbnail} alt={video?.title || ''} />
                </div>
                <div className="grow">
                  <div className="t">{video?.title || t.videoId}</div>
                  <div className={`s dl-status${t.status === 'error' ? ' bad' : ''}`}>{statusLine(t)}</div>
                  <div className={`dl-progress${t.status === 'paused' ? ' paused' : ''}`}>
                    <i style={{ width: `${Math.min(100, Math.max(0, t.percent))}%` }} />
                  </div>
                </div>
                <div className="acts">
                  {busy && (
                    <button className="icon-btn" title="Pause" onClick={() => window.indio.downloads.pause(t.videoId)}>
                      <PauseIcon size={16} />
                    </button>
                  )}
                  {!busy && (
                    <button className="icon-btn" title={t.status === 'error' ? 'Retry' : 'Resume'}
                      onClick={() => window.indio.downloads.resume(t.videoId)}>
                      <PlayIcon size={16} />
                    </button>
                  )}
                  <button className="icon-btn" title="Cancel" onClick={() => window.indio.downloads.cancel(t.videoId)}>
                    <TrashIcon size={16} />
                  </button>
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
