// The 127.0.0.1 media proxy must never throw or leave sockets dangling:
// a seek aborts in-flight range requests, and an unguarded rejection used
// to surface Electron's "JavaScript error occurred in the main process"
// dialog. These cases pin the soft-fail behaviour.
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import streamsModule from '../electron/streams';

const { createStreams } = streamsModule;

describe('media proxy', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'indio-proxy-'));
  const file = path.join(dir, 'sample.mp4');
  fs.writeFileSync(file, Buffer.alloc(64 * 1024, 7));
  let streams: any;
  let base = '';

  beforeAll(async () => {
    streams = createStreams({ ytdlpPath: 'no-such-yt-dlp.exe', downloadDir: dir, onDownloadEvent() {} });
    await streams.startProxy();
    base = `http://127.0.0.1:${streams.port}`;
  });

  afterAll(() => {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* locked */ }
  });

  it('serves a local downloaded file with range support', async () => {
    const full = await fetch(`${base}/file?p=${encodeURIComponent(file)}`);
    expect(full.status).toBe(200);
    expect(full.headers.get('accept-ranges')).toBe('bytes');
    expect(full.headers.get('access-control-allow-origin')).toBe('*');
    await full.arrayBuffer();

    const partial = await fetch(`${base}/file?p=${encodeURIComponent(file)}`, { headers: { range: 'bytes=0-1023' } });
    expect(partial.status).toBe(206);
    expect(partial.headers.get('content-range')).toBe(`bytes 0-1023/${64 * 1024}`);
    expect((await partial.arrayBuffer()).byteLength).toBe(1024);
  });

  it('refuses files outside the downloads folder', async () => {
    const res = await fetch(`${base}/file?p=${encodeURIComponent('C:\\Windows\\win.ini')}`);
    expect(res.status).toBe(403);
  });

  it('404s a missing file without throwing', async () => {
    const res = await fetch(`${base}/file?p=${encodeURIComponent(path.join(dir, 'nope.mp4'))}`);
    expect(res.status).toBe(404);
  });

  it('404s unknown and stale tokens instead of crashing', async () => {
    expect((await fetch(`${base}/v?t=deadbeef`)).status).toBe(404);
    expect((await fetch(`${base}/subs?t=deadbeef`)).status).toBe(404);
    expect((await fetch(`${base}/nope`)).status).toBe(404);
  });

  it('survives a client that aborts mid-response', async () => {
    const ac = new AbortController();
    const pending = fetch(`${base}/file?p=${encodeURIComponent(file)}`, { signal: ac.signal }).catch(() => null);
    ac.abort();
    await pending;
    // The server is still healthy afterwards.
    const res = await fetch(`${base}/file?p=${encodeURIComponent(file)}`, { headers: { range: 'bytes=1024-2047' } });
    expect(res.status).toBe(206);
    await res.arrayBuffer();
  });

  it('reports a helpful error when yt-dlp is missing', async () => {
    await expect(streams.qualities('abc12345678')).rejects.toThrow(/yt-dlp failed to start|no metadata/i);
  });
});
