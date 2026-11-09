// Desktop local Home recommender — the port of Android's LocalFeed.kt.
// These tests pin the ranking contract (filters, diversity, rotation) and the
// async pool builder used by the main process when signed-out Home is empty.
import { describe, expect, it } from 'vitest';
import localFeed from '../electron/localFeed';

const {
  createLocalFeed, rankAndSample, tokenizeQuery, publishAgeMinutes,
  completedIdsFromHistory, seedRecommendations,
} = localFeed as any;

const video = (id: string, channelId: string, extra: Record<string, unknown> = {}) => ({
  videoId: id,
  title: `Video ${id}`,
  channelId,
  channelName: `Chan ${channelId}`,
  channelAvatar: null,
  thumbnail: null,
  durationSec: 300,
  viewsText: '1K views',
  publishedText: '1 day ago',
  isLive: false,
  membersOnly: false,
  ...extra,
});

const signals = (extra: Record<string, unknown> = {}) => ({
  affinity: new Map(),
  subIds: new Set(),
  likedChannelIds: new Set(),
  watchedIds: new Set(),
  seedIds: new Set(),
  searchTerms: new Set(),
  completedIds: new Set(),
  interestedChannelIds: new Set(),
  hiddenIds: new Set(),
  blockedChannelIds: new Set(),
  ...extra,
});

const historyEntry = (id: string, channelId: string, extra: Record<string, unknown> = {}) => ({
  video: video(id, channelId),
  ts: Date.now() - 3_600_000,
  positionMs: 0,
  durationMs: 300_000,
  ...extra,
});

function makeLib(overrides: Record<string, any> = {}) {
  return {
    profileName: 'Test',
    library: {
      watchLater: [],
      historyPaused: false,
      history: [],
      playlists: [],
      liked: [],
      searches: [],
      notInterestedIds: [],
      interestedChannelIds: [],
      blockedChannelIds: [],
      ...(overrides.library || {}),
    },
    subscriptions: overrides.subscriptions || [],
    downloads: [],
  };
}

describe('local feed helpers', () => {
  it('tokenizes search queries and drops stop words', () => {
    expect(tokenizeQuery('How to build the Best Kotlin app')).toEqual(['build', 'kotlin', 'app']);
    expect(tokenizeQuery('  ')).toEqual([]);
  });

  it('reads publish ages like the renderer', () => {
    expect(publishAgeMinutes('2 days ago')).toBe(2880);
    expect(publishAgeMinutes('3 hours ago')).toBe(180);
    expect(publishAgeMinutes('nonsense')).toBeGreaterThan(1_000_000);
  });

  it('finds completed history entries the way Android does', () => {
    const ids = completedIdsFromHistory([
      historyEntry('done', 'UCa', { positionMs: 299_000, durationMs: 300_000 }),
      historyEntry('half', 'UCa', { positionMs: 100_000, durationMs: 300_000 }),
      historyEntry('empty', 'UCa', { positionMs: 0, durationMs: 300_000 }),
    ]);
    expect([...ids]).toEqual(['done']);
  });
});

describe('rankAndSample', () => {
  it('filters members-only, hidden videos and blocked channels', () => {
    const pool = [
      video('keep', 'UCa'),
      video('member', 'UCb', { membersOnly: true }),
      video('hidden', 'UCc'),
      video('blocked', 'UCblocked'),
    ];
    const out = rankAndSample(pool, signals({
      hiddenIds: new Set(['hidden']),
      blockedChannelIds: new Set(['UCblocked']),
    }), 1, 10);
    expect(out.map((v: any) => v.videoId)).toEqual(['keep']);
  });

  it('caps one channel while diversity is available', () => {
    const pool = [
      ...Array.from({ length: 10 }, (_, i) => video(`a${i}`, 'UCa')),
      ...Array.from({ length: 10 }, (_, i) => video(`b${i}`, 'UCb')),
    ];
    const out = rankAndSample(pool, signals(), 3, 6, 3);
    const perChannel = new Map<string, number>();
    for (const v of out) perChannel.set(v.channelId, (perChannel.get(v.channelId) || 0) + 1);
    expect(out).toHaveLength(6);
    expect(perChannel.get('UCa')).toBe(3);
    expect(perChannel.get('UCb')).toBe(3);
  });

  it('draws a different mix for a different rotation', () => {
    const pool = Array.from({ length: 24 }, (_, i) => video(`v${i}`, `UC${i % 6}`));
    const first = rankAndSample(pool, signals(), 1, 12).map((v: any) => v.videoId).join(',');
    const again = rankAndSample(pool, signals(), 1, 12).map((v: any) => v.videoId).join(',');
    const next = rankAndSample(pool, signals(), 2, 12).map((v: any) => v.videoId).join(',');
    expect(again).toBe(first); // deterministic per rotation, like Kotlin's seeded Random
    expect(next).not.toBe(first);
  });

  it('returns nothing when the whole pool is filtered out', () => {
    expect(rankAndSample([video('m', 'UCa', { membersOnly: true })], signals(), 1)).toEqual([]);
  });
});

// ---- async pool builder ------------------------------------------------------

function fakeTube(perChannel = 20) {
  const calls = { watch: 0, search: 0, channel: 0 };
  const channels = new Map<string, any[]>();
  const channelVideos = (id: string) => {
    if (!channels.has(id)) {
      channels.set(id, Array.from({ length: perChannel }, (_, i) => video(`${id}-${i}`, id)));
    }
    return channels.get(id)!;
  };
  return {
    calls,
    channels,
    async watch(id: string) {
      calls.watch += 1;
      return { related: [video(`rel-${id}`, 'UCrelated')] };
    },
    async search(query: string) {
      calls.search += 1;
      return {
        entries: [{ kind: 'video', video: video(`search-${query.replace(/\s+/g, '-')}`, 'UCsearch') }],
        continuation: null,
        restricted: false,
      };
    },
    async channelVideos(id: string) {
      calls.channel += 1;
      return { channel: { channelId: id, name: `Chan ${id}` }, videos: channelVideos(id), continuation: null };
    },
  };
}

describe('createLocalFeed', () => {
  const tube = fakeTube();
  const subs = Array.from({ length: 6 }, (_, i) => ({
    channelId: `UCsub${i}`, name: `Sub ${i}`, avatar: null, at: Date.now(),
  }));
  const lib = makeLib({
    subscriptions: subs,
    library: {
      history: [historyEntry('watched1', 'UCwatched')],
      liked: [video('liked1', 'UCliked')],
      searches: [{ query: 'kotlin tutorial', ts: Date.now() }],
      notInterestedIds: ['rel-watched1', 'UCsub0-0'],
      blockedChannelIds: ['UCblocked'],
    },
  });

  it('builds a mix from subscriptions, history, likes and searches', async () => {
    const feed = createLocalFeed(tube as any);
    const videos = await feed.build(lib, { force: true });
    expect(videos.length).toBeGreaterThan(0);
    const ids = videos.map((v: any) => v.videoId);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).not.toContain('rel-watched1');
    expect(ids).not.toContain('UCsub0-0');
    expect(videos.every((v: any) => !v.membersOnly)).toBe(true);
    expect(videos.every((v: any) => v.channelId !== 'UCblocked')).toBe(true);
    expect(tube.calls.channel).toBeGreaterThan(0);
    expect(tube.calls.watch).toBeGreaterThan(0);
  });

  it('caches an unforced build and rotates on force', async () => {
    const feed = createLocalFeed(fakeTube() as any);
    const first = await feed.build(lib);
    const second = await feed.build(lib);
    expect(second.map((v: any) => v.videoId)).toEqual(first.map((v: any) => v.videoId));
    const rotation = feed.rotation;
    const fresh = await feed.build(lib, { force: true });
    expect(feed.rotation).toBe(rotation + 1);
    expect(fresh.map((v: any) => v.videoId)).not.toEqual(first.map((v: any) => v.videoId));
  });

  it('serves more videos that do not repeat the ones already shown', async () => {
    const feed = createLocalFeed(fakeTube() as any);
    const first = await feed.build(lib, { force: true });
    const more = await feed.more(lib, {
      excludeIds: first.map((v: any) => v.videoId),
      limit: 40,
    });
    expect(more.length).toBeGreaterThan(0);
    const shown = new Set(first.map((v: any) => v.videoId));
    expect(more.every((v: any) => !shown.has(v.videoId))).toBe(true);
  });

  it('falls back to the library when the network pool is empty', async () => {
    const empty = {
      async watch() { return { related: [] }; },
      async search() { return { entries: [], continuation: null, restricted: false }; },
      async channelVideos() { return { channel: {}, videos: [], continuation: null }; },
    };
    const localLib = makeLib({
      library: { watchLater: [video('saved1', 'UCsaved'), video('saved2', 'UCsaved2')] },
    });
    const feed = createLocalFeed(empty as any);
    const built = await feed.build(localLib, { force: true });
    expect(built.map((v: any) => v.videoId).sort()).toEqual(['saved1', 'saved2']);
    const seeded = seedRecommendations(localLib, 1);
    expect(seeded.map((v: any) => v.videoId).sort()).toEqual(['saved1', 'saved2']);
  });
});
