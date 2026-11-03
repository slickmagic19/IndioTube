// Sync-core contract tests. These vectors are the Android-side
// SyncMergeTest.kt's twins: both platforms must merge identical payloads to
// identical results (add-only, idempotent).
import { describe, expect, it } from 'vitest';
import syncCore from '../electron/syncCore';

const { buildSnapshot, mergeIncoming, normName, syncSummary } = syncCore as any;

const newId = (() => { let n = 0; return () => `id-${++n}`; })();

const video = (id: string, extra = {}) => ({
  videoId: id, title: `T-${id}`, durationSec: 60, isLive: false, membersOnly: false, ...extra,
});
const history = (id: string, ts: number, positionMs = 0, durationMs = 60_000) => ({
  video: video(id), ts, positionMs, durationMs,
});
const envelope = (over: any = {}) => ({
  indio: 'sync', v: 1, name: 'phone',
  library: { watchLater: [], history: [], playlists: [], liked: [] },
  subscriptions: [],
  ...over,
  ...(over.library ? { library: { watchLater: [], history: [], playlists: [], liked: [], ...over.library } } : {}),
});
const localLib = (over: any = {}) => ({
  library: { watchLater: [], history: [], playlists: [], liked: [] },
  subscriptions: [],
  ...over,
  ...(over.library ? { library: { watchLater: [], history: [], playlists: [], liked: [], ...over.library } } : {}),
});

describe('mergeIncoming', () => {
  it('unions liked / watch later by videoId and prepends remote-only rows', () => {
    const local = localLib({ library: { liked: [video('a')], watchLater: [video('w')] } });
    const incoming = envelope({ library: { liked: [video('a'), video('b')], watchLater: [video('x'), video('w')] } });
    const { next, stats } = mergeIncoming(local, incoming, newId);
    expect(next.library.liked.map((v: any) => v.videoId)).toEqual(['b', 'a']);
    expect(next.library.watchLater.map((v: any) => v.videoId)).toEqual(['x', 'w']);
    expect(stats.likedAdded).toBe(1);
    expect(stats.watchLaterAdded).toBe(1);
  });

  it('keeps the newest-ts history entry (progress travels with it)', () => {
    const local = localLib({ library: { history: [history('v1', 100, 9_000)] } });
    const older = envelope({ library: { history: [history('v1', 50, 40_000)] } });
    const r1 = mergeIncoming(local, older, newId);
    expect(r1.next.library.history[0].positionMs).toBe(9_000);
    expect(r1.stats.historyAdded).toBe(0);

    const newer = envelope({ library: { history: [history('v1', 200, 30_000)] } });
    const r2 = mergeIncoming(local, newer, newId);
    expect(r2.next.library.history[0].positionMs).toBe(30_000);
    expect(r2.next.library.history[0].ts).toBe(200);
  });

  it('ties ts toward the larger position', () => {
    const local = localLib({ library: { history: [history('v', 100, 5_000)] } });
    const incoming = envelope({ library: { history: [history('v', 100, 40_000)] } });
    const { next } = mergeIncoming(local, incoming, newId);
    expect(next.library.history[0].positionMs).toBe(40_000);
  });

  it('is idempotent — merging the same payload twice changes nothing', () => {
    const local = localLib({ library: { liked: [video('a')], history: [history('h', 1)] } });
    const incoming = envelope({
      library: { liked: [video('b')], history: [history('h2', 2)], playlists: [{ name: 'Road', videos: [video('r')] }] },
      subscriptions: [{ channelId: 'UC1', name: 'Chan' }],
    });
    const first = mergeIncoming(local, incoming, newId);
    const second = mergeIncoming(first.next, incoming, newId);
    expect(second.next).toEqual(first.next);
    expect(second.stats.historyAdded + second.stats.likedAdded + second.stats.playlistsAdded + second.stats.subsAdded).toBe(0);
  });

  it('never touches unsynced local fields', () => {
    const local: any = localLib();
    local.library.historyPaused = true;
    local.library.searches = [{ query: 'abc', ts: 1 }];
    const { next } = mergeIncoming(local, envelope(), newId);
    expect(next.library.historyPaused).toBe(true);
    expect(next.library.searches.length).toBe(1);
  });

  it('matches playlists by normalized name and unions their videos', () => {
    const local = localLib({
      library: { playlists: [{ id: 'p1', name: '  Gym  HITS ', videos: [video('a')] }] },
    });
    const incoming = envelope({
      library: { playlists: [{ id: 'ignored', name: 'gym hits', videos: [video('a'), video('b')] }] },
    });
    const { next, stats } = mergeIncoming(local, incoming, newId);
    expect(next.library.playlists.length).toBe(1);
    expect(next.library.playlists[0].id).toBe('p1'); // never taken from the wire
    expect(next.library.playlists[0].videos.map((v: any) => v.videoId)).toEqual(['a', 'b']);
    expect(stats.playlistsMerged).toBe(1);
    expect(stats.videosAdded).toBe(1);
  });

  it('appends a playlist whose name does not exist locally', () => {
    const local = localLib({ library: { playlists: [{ id: 'p1', name: 'Old', videos: [] }] } });
    const incoming = envelope({ library: { playlists: [{ name: 'Focus', videos: [video('f1'), video('f1')] }] } });
    const { next, stats } = mergeIncoming(local, incoming, newId);
    expect(next.library.playlists.length).toBe(2);
    expect(stats.playlistsAdded).toBe(1);
    expect(stats.videosAdded).toBe(1); // remote-internal duplicate counted once
  });

  it('unions subscriptions and prepends remote-only rows in order', () => {
    const local = localLib({ subscriptions: [{ channelId: 'UCb', name: 'B' }] });
    const incoming = envelope({ subscriptions: [{ channelId: 'UCb' }, { channelId: 'UCa', name: 'A' }, { channelId: 'UCc', name: 'C' }] });
    const { next, stats } = mergeIncoming(local, incoming, newId);
    expect(next.subscriptions.map((s: any) => s.channelId)).toEqual(['UCa', 'UCc', 'UCb']);
    expect(stats.subsAdded).toBe(2);
  });

  it('caps merged history at 500, oldest first out', () => {
    const local = localLib({ library: { history: Array.from({ length: 500 }, (_, i) => history(`l${i}`, 1000 + i)) } });
    const incoming = envelope({ library: { history: [history('new', 9999)] } });
    const { next } = mergeIncoming(local, incoming, newId);
    expect(next.library.history.length).toBe(500);
    expect(next.library.history[0].video.videoId).toBe('new');
    expect(next.library.history.some((e: any) => e.video.videoId === 'l0')).toBe(false);
  });

  it('tolerates a malformed payload', () => {
    const { next, stats } = mergeIncoming(localLib(), { library: { history: 'nope', liked: null } } as any, newId);
    expect(stats.total ?? Object.values(stats).reduce((a: any, b) => a + b, 0)).toBe(0);
    expect(next.library.history).toEqual([]);
  });
});

describe('buildSnapshot', () => {
  it('ships only the synced subset with blank playlist ids', () => {
    const lib = localLib({
      library: { liked: [video('a')], history: [history('h', 5)], playlists: [{ id: 'p1', name: 'X', videos: [video('v')] }] },
      subscriptions: [{ channelId: 'UC1' }],
    });
    (lib.library as any).searches = [{ query: 'keep off the wire', ts: 1 }];
    const snap = buildSnapshot('Desk', lib);
    expect(snap.indio).toBe('sync');
    expect(snap.name).toBe('Desk');
    expect(snap.library.playlists[0].id).toBe('');
    expect(snap.library.searches).toBeUndefined();
    expect(snap.subscriptions.length).toBe(1);
  });
});

describe('summary + normName', () => {
  it('reads like the phone side', () => {
    expect(syncSummary({
      historyAdded: 2, watchLaterAdded: 0, likedAdded: 1, playlistsAdded: 0,
      playlistsMerged: 1, videosAdded: 3, subsAdded: 0,
    })).toBe('+2 history · +1 liked · 1 playlists updated');
    expect(syncSummary({
      historyAdded: 0, watchLaterAdded: 0, likedAdded: 0, playlistsAdded: 0,
      playlistsMerged: 0, videosAdded: 0, subsAdded: 0,
    })).toBe('already in sync');
  });
  it('normalizes whitespace and case', () => {
    expect(normName('  Chill   Beats ')).toBe('chill beats');
  });
});
