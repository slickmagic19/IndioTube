// End-to-end sync loop over 127.0.0.1: start the pairing server the desktop
// would expose, run the phone's pull-merge-push against it with a real
// library store, and assert both sides converge. This is the same shape
// electron/sync.js documents for the Android SyncClient, minus the LAN.
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import sync from '../electron/sync';
import lib from '../electron/library';

const video = (id: string) => ({
  videoId: id, title: `T-${id}`, durationSec: 60, isLive: false, membersOnly: false,
});

describe('sync server round trip', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'indio-sync-'));
  const store = lib.createStore(dir);
  let server: any;

  beforeAll(async () => {
    store.mergeIncoming({
      name: 'seed',
      library: { liked: [video('seed-like')], history: [{ video: video('seed-hist'), ts: 1, positionMs: 5000, durationMs: 60000 }] },
      subscriptions: [{ channelId: 'UCseed', name: 'Seed', at: 1 }],
    });
    server = await sync.startSyncServer({
      name: 'Test PC',
      onRequest: async ({ kind, payload }) => {
        if (kind === 'snapshot') return store.snapshot();
        const stats = store.mergeIncoming(payload);
        return { stats };
      },
    });
  });

  afterAll(async () => {
    await server.stop();
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* windows lock */ }
  });

  const base = () => `http://127.0.0.1:${server.port}/indio/v1`;

  it('ping answers without a token', async () => {
    const res = await fetch(`${base()}/ping`);
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ app: 'indiotube', name: 'Test PC' });
  });

  it('rejects a wrong token', async () => {
    const res = await fetch(`${base()}/snapshot?token=00000000000000000000000000000000`);
    expect(res.status).toBe(401);
  });

  it('phone pulls the desktop snapshot', async () => {
    const res = await fetch(`${base()}/snapshot?token=${server.token}`);
    const body: any = await res.json();
    expect(body.ok).toBe(true);
    expect(body.snapshot.indio).toBe('sync');
    expect(body.snapshot.library.liked.map((v: any) => v.videoId)).toContain('seed-like');
  });

  it('phone pushes a merge; both sides keep everything', async () => {
    // What the phone sends: its own merged snapshot (add-only contract).
    const phonePayload = {
      indio: 'sync', v: 1, name: 'Pixel',
      library: {
        watchLater: [video('phone-wl')],
        liked: [video('seed-like'), video('phone-like')],
        history: [{ video: video('phone-hist'), ts: Date.now(), positionMs: 1000, durationMs: 4000 }],
        playlists: [{ id: '', name: 'Road Trip', videos: [video('phone-pl')] }],
      },
      subscriptions: [{ channelId: 'UCphone', name: 'Phone Chan', at: 5 }],
    };
    const res = await fetch(`${base()}/merge?token=${server.token}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(phonePayload),
    });
    const body: any = await res.json();
    expect(body.ok).toBe(true);
    expect(body.stats.watchLaterAdded).toBe(1);
    expect(body.stats.likedAdded).toBe(1);
    expect(body.stats.playlistsAdded).toBe(1);
    expect(body.stats.subsAdded).toBe(1);

    const state = store.getState();
    expect(state.library.liked.map((v: any) => v.videoId)).toContain('phone-like');
    expect(state.library.playlists[0].name).toBe('Road Trip');
    expect(state.subscriptions.map((s: any) => s.channelId)).toContain('UCphone');
  });

  it('merging the same payload again reports zero additions (idempotent)', async () => {
    const phonePayload = {
      indio: 'sync', v: 1, name: 'Pixel',
      library: { watchLater: [video('phone-wl')], liked: [video('phone-like')], history: [], playlists: [{ name: 'Road Trip', videos: [video('phone-pl')] }] },
      subscriptions: [{ channelId: 'UCphone' }],
    };
    const res = await fetch(`${base()}/merge?token=${server.token}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(phonePayload),
    });
    const body: any = await res.json();
    const sum = Object.values(body.stats).reduce((a: any, b) => a + b, 0);
    expect(sum).toBe(0);
  });

  it('rejects an oversized body and unknown routes', async () => {
    const big = JSON.stringify({ blob: 'x'.repeat(13 * 1024 * 1024) });
    // The server destroys the socket once the cap is crossed, so the phone
    // sees either a 413 or a dropped connection - both mean "too large".
    let rejected = false;
    try {
      const res = await fetch(`${base()}/merge?token=${server.token}`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: big,
      });
      rejected = res.status === 413 || res.status >= 400;
    } catch {
      rejected = true;
    }
    expect(rejected).toBe(true);
    const nf = await fetch(`${base()}/nope?token=${server.token}`);
    expect(nf.status).toBe(404);
  });

  it('pairing payload matches the Android contract', () => {
    const p = server.payload;
    expect(p.indio).toBe('sync');
    expect(p.v).toBe(1);
    expect(p.host).toBeTruthy();
    expect(typeof p.port).toBe('number');
    expect(String(p.token).length).toBe(32);
  });
});
