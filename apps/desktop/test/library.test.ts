// Desktop library side of the Android-parity actions: recommendation
// feedback, queue-irrelevant history flags and persistence.
import { afterAll, describe, expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import library from '../electron/library';

const dirs: string[] = [];
function freshStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'indio-lib-'));
  dirs.push(dir);
  return { store: library.createStore(dir), dir };
}

const video = (id: string, channelId = 'UCchan') => ({
  videoId: id, title: `T-${id}`, channelId, channelName: 'Chan', durationSec: 100,
  isLive: false, membersOnly: false,
});

afterAll(() => {
  for (const d of dirs) {
    try { fs.rmSync(d, { recursive: true, force: true }); } catch { /* windows lock */ }
  }
});

describe('recommendation feedback (Android parity)', () => {
  it('not interested pins the video and clears an interested channel', () => {
    const { store } = freshStore();
    const v = video('v1');
    store.markInterested(v);
    expect(store.getState().library.interestedChannelIds).toContain('UCchan');
    store.markNotInterested(v);
    const state = store.getState().library;
    expect(state.notInterestedIds).toContain('v1');
    expect(state.interestedChannelIds).not.toContain('UCchan');
  });

  it('undo brings the video back', () => {
    const { store } = freshStore();
    store.markNotInterested(video('v1'));
    store.undoNotInterested('v1');
    expect(store.getState().library.notInterestedIds).toEqual([]);
  });

  it('interested removes a not-interested pin and remembers the channel', () => {
    const { store } = freshStore();
    store.markNotInterested(video('v1'));
    store.markInterested(video('v1'));
    const state = store.getState().library;
    expect(state.notInterestedIds).toEqual([]);
    expect(state.interestedChannelIds).toEqual(['UCchan']);
  });

  it("don't recommend channel blocks it once and drops interest", () => {
    const { store } = freshStore();
    store.markInterested(video('v1'));
    store.blockChannel(video('v1'));
    store.blockChannel(video('v2'));
    const state = store.getState().library;
    expect(state.blockedChannelIds).toEqual(['UCchan']);
    expect(state.interestedChannelIds).toEqual([]);
    store.unblockChannel('UCchan');
    expect(store.getState().library.blockedChannelIds).toEqual([]);
  });
});

describe('watch flags', () => {
  it('markWatched writes a completed history entry', () => {
    const { store } = freshStore();
    expect(store.markWatched(video('v1'))).toBe(true);
    const entry = store.getState().library.history[0];
    expect(entry.video.videoId).toBe('v1');
    expect(entry.positionMs).toBe(100_000);
    expect(store.isWatched('v1')).toBe(true);
    expect(store.resumePosition('v1')).toBe(0); // finished videos start over
  });

  it('markUnwatched keeps the row but drops the progress', () => {
    const { store } = freshStore();
    store.markWatched(video('v1'));
    store.markUnwatched('v1');
    const entry = store.getState().library.history[0];
    expect(entry.positionMs).toBe(0);
    expect(store.isWatched('v1')).toBe(false);
  });
});

describe('change notifications', () => {
  // Regression: the listener registration and the channel "subscribe" action
  // once shared the name `subscribe`, so the channel method shadowed the
  // listener one and no mutation ever reached the renderer.
  it('notifies listeners on every mutation', () => {
    const { store } = freshStore();
    const seen: string[][] = [];
    const off = store.onChange((s) => seen.push(s.library.notInterestedIds));
    store.markNotInterested(video('v1'));
    store.markInterested(video('v1'));
    expect(seen).toEqual([['v1'], []]);
    off();
    store.markNotInterested(video('v2'));
    expect(seen).toHaveLength(2);
  });

  it('keeps channel subscriptions separate from change listeners', () => {
    const { store } = freshStore();
    const events: number[] = [];
    store.onChange(() => events.push(1));
    store.subscribe({ channelId: 'UCchan', name: 'Chan' } as any);
    expect(store.isSubscribed('UCchan')).toBe(true);
    expect(events).toHaveLength(1);
  });
});

describe('persistence', () => {
  it('feedback survives a restart', async () => {
    const { store, dir } = freshStore();
    store.markNotInterested(video('v1'));
    store.blockChannel(video('v2'));
    await store.flush();
    const reloaded = library.createStore(dir);
    const state = reloaded.getState().library;
    expect(state.notInterestedIds).toContain('v1');
    expect(state.blockedChannelIds).toContain('UCchan');
  });
});
