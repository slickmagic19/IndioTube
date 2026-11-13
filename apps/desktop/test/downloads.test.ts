// Download manager parity tests: the queue, pause/resume/cancel and retry
// rules that Android's DownloadsTest covers, plus the yt-dlp progress parser.
import { EventEmitter } from 'node:events';
import { describe, expect, it, vi } from 'vitest';
import downloads from '../electron/downloads';

const { createDownloadManager, parseDownloadLine, parseSize, MAX_ACTIVE } = downloads as any;

function fakeSpawn() {
  const proc: any = new EventEmitter();
  proc.stdout = new EventEmitter();
  proc.stderr = new EventEmitter();
  proc.killed = false;
  proc.kill = () => { proc.killed = true; };
  return proc;
}

function setup() {
  const procs: any[] = [];
  const events: any[] = [];
  const spawnFn = vi.fn(() => {
    const p = fakeSpawn();
    procs.push(p);
    return p;
  });
  const manager = createDownloadManager({
    ytdlpPath: 'yt-dlp.exe',
    downloadDir: () => 'C:/tmp/downloads',
    queueFile: null,
    emit: (e: any) => events.push(e),
    ffmpegAvailable: async () => false,
    spawnFn,
  });
  return { manager, procs, events, spawnFn };
}

const tick = () => new Promise((r) => setTimeout(r, 0));
const video = (id: string) => ({ videoId: id, title: id, durationSec: 60, isLive: false, membersOnly: false });

describe('parseDownloadLine', () => {
  it('reads percent, size, speed and ETA from a yt-dlp progress line', () => {
    const p = parseDownloadLine('[download]  45.2% of ~ 15.30MiB at  1.20MiB/s ETA 00:05');
    expect(p).toEqual({ percent: 45.2, totalBytes: 16043213, speed: 1258291, eta: 5 });
  });

  it('handles the finishing line without speed or ETA', () => {
    const p = parseDownloadLine('[download] 100% of 12.34MiB in 00:03');
    expect(p).toEqual({ percent: 100, totalBytes: 12939428 });
  });

  it('handles hour-long ETAs', () => {
    const p = parseDownloadLine('[download]  10.0% of 1.20GiB at 500.00KiB/s ETA 01:02:03');
    expect(p?.eta).toBe(3723);
  });

  it('ignores lines that carry no progress', () => {
    expect(parseDownloadLine('[download] Destination: video.mp4')).toBe(null);
    expect(parseDownloadLine('[Merger] Merging files into out.mp4')).toBe(null);
    expect(parseDownloadLine('')).toBe(null);
  });

  it('parses yt-dlp size units', () => {
    expect(parseSize('900.00KiB')).toBe(921600);
    expect(parseSize('15.30MiB')).toBe(16043213);
    expect(parseSize('1.20GiB')).toBe(1288490189);
    expect(parseSize('12B')).toBe(12);
    expect(parseSize('nonsense')).toBe(0);
  });
});

describe('download queue', () => {
  it('runs at most MAX_ACTIVE transfers and starts the next when one is paused', async () => {
    const { manager, procs, spawnFn } = setup();
    manager.start(video('a'), 720);
    manager.start(video('b'), 720);
    manager.start(video('c'), 720);
    await tick();

    expect(spawnFn).toHaveBeenCalledTimes(MAX_ACTIVE);
    expect(manager.list().map((t: any) => t.status).sort()).toEqual(['downloading', 'downloading', 'queued']);

    manager.pause('a');
    await tick();
    expect(spawnFn).toHaveBeenCalledTimes(MAX_ACTIVE + 1);
    const byId = Object.fromEntries(manager.list().map((t: any) => [t.videoId, t]));
    expect(byId.a.status).toBe('paused');
    expect(byId.c.status).toBe('downloading');
    expect(procs[0].killed).toBe(true);
  });

  it('refuses to start the same video twice and resumes a paused one', async () => {
    const { manager } = setup();
    manager.start(video('a'), 720);
    await tick();
    expect(manager.start(video('a'), 720)).toEqual({ started: false, reason: 'already downloading' });
    manager.pause('a');
    expect(manager.resume('a')).toBe(true);
    await tick();
    expect(manager.list()[0].status).toBe('downloading');
  });

  it('reports progress with speed and ETA', async () => {
    const { manager, procs, events } = setup();
    manager.start(video('a'), 720);
    await tick();
    procs[0].stdout.emit('data', Buffer.from('[download]  50.0% of 10.00MiB at 2.00MiB/s ETA 00:03\n'));
    const progress = events.filter((e) => e.status === 'progress').pop();
    expect(progress.percent).toBe(50);
    expect(progress.totalBytes).toBe(10485760);
    expect(progress.speed).toBe(2097152);
    expect(progress.eta).toBe(3);
  });

  it('retries a failed transfer before giving up', async () => {
    const { manager, procs, events } = setup();
    manager.start(video('a'), 720);
    await tick();
    procs[0].emit('close', 1);
    const [task] = manager.list();
    expect(task.status).toBe('queued');
    expect(task.attempts).toBe(1);
    expect(events.some((e) => e.status === 'queued' && e.attempts === 1)).toBe(true);
  });

  it('drops a cancelled task from the queue', async () => {
    const { manager, events } = setup();
    manager.start(video('a'), 720);
    await tick();
    manager.cancel('a');
    expect(manager.list()).toEqual([]);
    expect(events.some((e) => e.status === 'cancelled')).toBe(true);
  });
});
