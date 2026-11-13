// Download manager: the desktop counterpart of Android's Downloads.kt.
//
// Same behaviour the Android app has: a queue with a concurrency limit, tasks
// that survive navigation and app restarts, pause / resume / cancel per task
// (and all at once), retries with a bounded attempt count, and progress with
// transfer speed and a time estimate. yt-dlp does the transfer; this module
// owns the state machines around it, so the renderer only ever sees events and
// a snapshot it can re-read at any time.
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const MAX_ACTIVE = 2;
const MAX_ATTEMPTS = 3;
const EMIT_INTERVAL_MS = 200;
const RETRY_DELAY_MS = 1500;

// yt-dlp prints sizes like "15.30MiB", "1.2GiB", "900.00KiB", "12B".
const SIZE_RE = /([\d.]+)\s*([KMGTP]?)i?B/i;

function parseSize(text) {
  if (!text) return 0;
  const m = SIZE_RE.exec(String(text));
  if (!m) return 0;
  const power = { '': 0, K: 1, M: 2, G: 3, T: 4, P: 5 }[m[2].toUpperCase()] ?? 0;
  const value = parseFloat(m[1]);
  if (!Number.isFinite(value)) return 0;
  return Math.round(value * 1024 ** power);
}

/**
 * One yt-dlp progress line -> the numbers the UI shows. Returns null for lines
 * that carry no progress (Destination, Merger, warnings ...).
 *
 * Examples:
 *   [download]  45.2% of ~ 15.30MiB at  1.20MiB/s ETA 00:05
 *   [download] 100% of 12.34MiB in 00:03
 */
function parseDownloadLine(line) {
  const text = String(line || '');
  if (!text.includes('[download]')) return null;
  const pct = /\[download\]\s+([\d.]+)%/.exec(text);
  if (!pct) return null;
  const percent = Math.min(100, Math.max(0, parseFloat(pct[1])));
  const out = { percent };

  const total = /of\s+~?\s*([\d.]+\s*[KMGTP]?i?B)/i.exec(text);
  if (total) out.totalBytes = parseSize(total[1]);

  // "at 1.20MiB/s" - yt-dlp omits it for the last line and for stalled runs.
  const speed = /\bat\s+~?\s*([\d.]+\s*[KMGTP]?i?B)\/s/i.exec(text);
  if (speed) out.speed = parseSize(speed[1]);

  const eta = /ETA\s+(?:(\d+):)?(\d+):(\d+)/.exec(text);
  if (eta) {
    const [, h, m, s] = eta;
    out.eta = (h ? Number(h) * 3600 : 0) + Number(m) * 60 + Number(s);
  }
  return out;
}

function publicTask(t) {
  return {
    videoId: t.videoId,
    video: t.video,
    height: t.height,
    status: t.status,
    percent: Math.round((t.percent || 0) * 10) / 10,
    receivedBytes: t.receivedBytes || 0,
    totalBytes: t.totalBytes || 0,
    speed: Math.round(t.speed || 0),
    eta: t.eta == null ? null : t.eta,
    file: t.file || '',
    bytes: t.bytes || 0,
    error: t.error || null,
    attempts: t.attempts || 0,
    at: t.at,
  };
}

function createDownloadManager({ ytdlpPath, downloadDir, queueFile, emit, ffmpegAvailable, ffmpegPath, spawnFn = spawn }) {
  const tasks = new Map(); // videoId -> task (queued / downloading / paused / error)
  let loaded = false;
  let saveTimer = null;
  const retryTimers = new Set();

  function dir() {
    return typeof downloadDir === 'function' ? downloadDir() : downloadDir;
  }

  function save() {
    if (!queueFile) return;
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      saveTimer = null;
      try {
        const doc = {
          v: 1,
          tasks: [...tasks.values()].map((t) => ({
            videoId: t.videoId,
            video: t.video,
            height: t.height,
            status: t.status,
            percent: t.percent,
            receivedBytes: t.receivedBytes,
            totalBytes: t.totalBytes,
            file: t.file,
            error: t.error,
            attempts: t.attempts,
            at: t.at,
          })),
        };
        const tmp = `${queueFile}.tmp`;
        fs.writeFileSync(tmp, JSON.stringify(doc));
        fs.renameSync(tmp, queueFile);
      } catch {
        // The queue file is a convenience; a failure must never break a download.
      }
    }, 400);
    if (typeof saveTimer.unref === 'function') saveTimer.unref();
  }

  function load() {
    if (loaded) return;
    loaded = true;
    if (!queueFile) return;
    try {
      const doc = JSON.parse(fs.readFileSync(queueFile, 'utf8'));
      const list = doc && Array.isArray(doc.tasks) ? doc.tasks : [];
      for (const raw of list) {
        if (!raw || !raw.videoId) continue;
        tasks.set(raw.videoId, {
          videoId: raw.videoId,
          video: raw.video || { videoId: raw.videoId, title: raw.videoId },
          height: raw.height ?? null,
          // Interrupted mid-transfer: keep it, but let the viewer decide.
          status: raw.status === 'downloading' ? 'paused' : (raw.status || 'paused'),
          percent: raw.percent || 0,
          receivedBytes: raw.receivedBytes || 0,
          totalBytes: raw.totalBytes || 0,
          speed: 0,
          eta: null,
          file: raw.file || '',
          error: raw.error || null,
          attempts: raw.attempts || 0,
          at: raw.at || Date.now(),
          proc: null,
          lastBytes: raw.receivedBytes || 0,
          lastTick: 0,
          lastEmit: 0,
        });
      }
    } catch {
      // First run or unreadable file: start empty.
    }
  }

  function send(task, status, extra) {
    if (!emit) return;
    emit({ videoId: task.videoId, status, ...extra });
  }

  function activeCount() {
    let n = 0;
    for (const t of tasks.values()) if (t.status === 'downloading') n += 1;
    return n;
  }

  function pump() {
    load();
    for (const task of tasks.values()) {
      if (activeCount() >= MAX_ACTIVE) return;
      if (task.status === 'queued') launch(task);
    }
  }

  async function launch(task) {
    task.status = 'downloading';
    task.error = null;
    task.speed = 0;
    task.eta = null;
    task.lastBytes = task.receivedBytes || 0;
    task.lastTick = Date.now();
    save();
    send(task, 'started', {
      height: task.height,
      percent: task.percent || 0,
      receivedBytes: task.receivedBytes || 0,
      totalBytes: task.totalBytes || 0,
    });

    let ffmpeg = false;
    try { ffmpeg = ffmpegAvailable ? await ffmpegAvailable() : false; } catch { ffmpeg = false; }
    if (task.status !== 'downloading') return; // paused while we probed

    const h = task.height && task.height > 0 ? task.height : 1080;
    // Fall back all the way: a rung the extractor does not offer must not
    // fail the whole download (some short videos have no muxed 144p file).
    // Without ffmpeg a split video+audio pair cannot be merged into one
    // playable file, so only single-file formats are allowed there.
    const fmt = ffmpeg ? `bv*[height<=${h}]+ba/b[height<=${h}]/bv*+ba/b` : `b[height<=${h}]/b`;
    const outTpl = path.join(dir(), '%(title)s [%(id)s].%(ext)s');
    const args = [
      '--no-warnings', '--no-playlist', '--newline', '--progress',
      // Same spirit as Android's retries: transient CDN hiccups must not turn
      // a long download into a failed one.
      '--retries', '10', '--fragment-retries', '10', '--continue',
      '-f', fmt,
    ];
    if (ffmpeg) args.push('--merge-output-format', 'mp4');
    // yt-dlp looks for ffmpeg on PATH by default; the vendored copy lives
    // next to yt-dlp instead.
    if (ffmpeg && ffmpegPath && ffmpegPath !== 'ffmpeg') {
      args.push('--ffmpeg-location', path.dirname(ffmpegPath));
    }
    args.push('-o', outTpl, `https://www.youtube.com/watch?v=${task.videoId}`);

    let proc;
    try {
      proc = spawnFn(ytdlpPath, args, { windowsHide: true });
    } catch (e) {
      task.status = 'error';
      task.error = `yt-dlp failed to start: ${e.message}`;
      save();
      send(task, 'error', { error: task.error });
      pump();
      return;
    }
    task.proc = proc;

    let buf = '';
    proc.stdout.on('data', (d) => {
      buf += d.toString();
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        const dest = /\[download\] Destination: (.+)$/.exec(line);
        if (dest) task.file = dest[1].trim();
        const merged = /\[Merger\] Merging files into (.+)$/.exec(line);
        if (merged) task.file = merged[1].trim();
        if (line.includes('has already been downloaded')) {
          const m = /Destination: (.+)$/.exec(line);
          if (m) task.file = m[1].trim();
        }
        const p = parseDownloadLine(line);
        if (!p) continue;
        task.percent = p.percent;
        if (p.totalBytes) task.totalBytes = p.totalBytes;
        if (task.totalBytes) task.receivedBytes = Math.round((p.percent / 100) * task.totalBytes);
        const now = Date.now();
        if (p.speed) {
          task.speed = p.speed;
        } else if (now - task.lastTick >= 700) {
          task.speed = Math.max(0, ((task.receivedBytes - task.lastBytes) * 1000) / (now - task.lastTick));
        }
        if (p.eta != null) task.eta = p.eta;
        if (now - task.lastTick >= 700) {
          task.lastTick = now;
          task.lastBytes = task.receivedBytes;
        }
        if (now - task.lastEmit >= EMIT_INTERVAL_MS) {
          task.lastEmit = now;
          send(task, 'progress', {
            percent: task.percent,
            receivedBytes: task.receivedBytes,
            totalBytes: task.totalBytes,
            speed: task.speed,
            eta: task.eta,
          });
        }
      }
    });
    proc.stderr.on('data', () => { /* progress noise; real failures land in close */ });
    proc.on('error', (e) => {
      task.proc = null;
      if (task.status !== 'downloading') return;
      finishFailure(task, `yt-dlp failed to start: ${e.message}`);
    });
    proc.on('close', (code) => {
      task.proc = null;
      if (task.status === 'paused' || task.status === 'cancelled') return;
      const output = code === 0 ? resolveOutput(task) : '';
      // A split stream left on disk is not a download: without the merge step
      // it is half a video, which used to be recorded as finished.
      if (code === 0 && /\.f\d+\.[a-z0-9]+$/i.test(output)) {
        finishFailure(task, 'This quality needs ffmpeg to merge video and audio');
        return;
      }
      if (code === 0 && output && fs.existsSync(output)) {
        let bytes = 0;
        try { bytes = fs.statSync(output).size; } catch { /* fine */ }
        task.status = 'done';
        task.file = output;
        task.bytes = bytes;
        task.percent = 100;
        send(task, 'done', { file: output, bytes, height: h });
        tasks.delete(task.videoId);
        save();
        pump();
        return;
      }
      finishFailure(task, code === 0
        ? 'Download finished but the file was not found'
        : `Download failed (yt-dlp exit ${code})`);
    });
  }

  /**
   * The file a finished task actually produced. yt-dlp's last "Destination:"
   * line is the *split* stream when it downloads video and audio separately and
   * merges them afterwards, so the merged file has to be found on disk.
   */
  function resolveOutput(task) {
    let names = [];
    try { names = fs.readdirSync(dir()); } catch { return task.file || ''; }
    const mine = names.filter((n) => n.includes(`[${task.videoId}]`));
    const merged = mine.filter((n) => !/\.f\d+\./i.test(n) && !/\.part$/i.test(n));
    if (merged.length) return path.join(dir(), merged[merged.length - 1]);
    return task.file || '';
  }

  function finishFailure(task, message) {
    task.attempts = (task.attempts || 0) + 1;
    if (task.attempts < MAX_ATTEMPTS) {
      // Retry quietly: the row stays put and the viewer sees the bar resume.
      task.status = 'queued';
      task.error = null;
      task.speed = 0;
      save();
      send(task, 'queued', { attempts: task.attempts });
      const timer = setTimeout(() => {
        retryTimers.delete(timer);
        pump();
      }, RETRY_DELAY_MS * task.attempts);
      if (typeof timer.unref === 'function') timer.unref();
      retryTimers.add(timer);
      return;
    }
    task.status = 'error';
    task.error = message;
    task.speed = 0;
    task.eta = null;
    save();
    send(task, 'error', { error: message });
    pump();
  }

  function start(video, height) {
    load();
    const videoId = video && video.videoId;
    if (!videoId) return { started: false, reason: 'no video' };
    const existing = tasks.get(videoId);
    if (existing && (existing.status === 'queued' || existing.status === 'downloading')) {
      return { started: false, reason: 'already downloading' };
    }
    if (existing && existing.status === 'paused') {
      existing.status = 'queued';
      save();
      send(existing, 'resumed', {});
      pump();
      return { started: true };
    }
    const task = {
      videoId,
      video: { ...video },
      height: height ?? null,
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
      proc: null,
      lastBytes: 0,
      lastTick: 0,
      lastEmit: 0,
    };
    tasks.set(videoId, task);
    save();
    pump();
    return { started: true };
  }

  function pause(videoId) {
    load();
    const task = tasks.get(videoId);
    if (!task || task.status !== 'downloading') return false;
    task.status = 'paused';
    task.speed = 0;
    task.eta = null;
    try { task.proc && task.proc.kill(); } catch { /* gone */ }
    task.proc = null;
    save();
    send(task, 'paused', { percent: task.percent, receivedBytes: task.receivedBytes, totalBytes: task.totalBytes });
    pump();
    return true;
  }

  function resume(videoId) {
    load();
    const task = tasks.get(videoId);
    if (!task) return false;
    if (task.status === 'downloading' || task.status === 'queued') return true;
    task.status = 'queued';
    task.error = null;
    task.attempts = 0;
    save();
    send(task, 'resumed', { percent: task.percent });
    pump();
    return true;
  }

  function cancel(videoId) {
    load();
    const task = tasks.get(videoId);
    if (!task) return false;
    task.status = 'cancelled';
    try { task.proc && task.proc.kill(); } catch { /* gone */ }
    task.proc = null;
    tasks.delete(videoId);
    save();
    send(task, 'cancelled', {});
    // Cancel means "stop and clean up", exactly like Android's finishCancel:
    // the partial files for this id go away.
    try {
      const root = dir();
      for (const name of fs.readdirSync(root)) {
        if (name.includes(`[${videoId}]`)) {
          try { fs.unlinkSync(path.join(root, name)); } catch { /* in use */ }
        }
      }
    } catch { /* folder missing */ }
    pump();
    return true;
  }

  function pauseAll() {
    load();
    for (const id of [...tasks.keys()]) pause(id);
    return true;
  }

  function resumeAll() {
    load();
    for (const task of [...tasks.values()]) {
      if (task.status === 'paused' || task.status === 'error') resume(task.videoId);
    }
    return true;
  }

  function list() {
    load();
    return [...tasks.values()].map(publicTask).sort((a, b) => a.at - b.at);
  }

  return { start, pause, resume, cancel, pauseAll, resumeAll, list, pump, _tasks: tasks };
}

module.exports = { createDownloadManager, parseDownloadLine, parseSize, MAX_ACTIVE, MAX_ATTEMPTS };
