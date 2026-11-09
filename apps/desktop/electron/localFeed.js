// Local Home recommender — the desktop port of the Android app's LocalFeed.kt.
//
// YouTube serves no recommendations to signed-out sessions, so the Home feed
// is computed locally from this device's own data, mirroring the signals
// YouTube's recommender uses:
//  - watch history (recency-weighted channel affinity)
//  - subscriptions and liked videos
//  - search history (the topics asked for and the videos those searches found)
//  - the "related" videos of the last few watches
//  - freshness and a strong demotion of already-watched videos
//
// Every rebuild draws a new weighted sample from the candidate pool, so a
// refresh genuinely changes the mix instead of showing the same list. The
// per-channel cap keeps one uploader from taking over the page. `more()` uses
// the same pool with a new rotation and the already-shown ids removed, which
// is the local analogue of YouTube's continuation pages.
const COMPLETED_FRACTION = 0.92; // same as Android's Models.kt
const CACHE_TTL_MS = 10 * 60_000;
const RANK_LIMIT = 80;
const MAX_PER_CHANNEL = 3;
const PAGE_SIZE = 40;
const REBUILD_THRESHOLD = 12;

const STOP_WORDS = new Set([
  'the', 'and', 'for', 'with', 'from', 'that', 'this', 'you', 'your', 'how',
  'what', 'why', 'when', 'who', 'are', 'was', 'were', 'will', 'can', 'not',
  'official', 'video', 'music', 'song', 'full', 'new', 'best', 'top',
]);

function tokenizeQuery(query) {
  return String(query || '')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((t) => t.length >= 3 && !STOP_WORDS.has(t));
}

// "2 days ago" → a rough age in minutes (port of publishAgeMinutes).
function publishAgeMinutes(publishedText) {
  const t = String(publishedText || '').toLowerCase();
  const m = /(\d+(?:[.,]\d+)?)\s*(second|minute|hour|day|week|month|year)/.exec(t);
  if (!m) return Number.MAX_SAFE_INTEGER / 4;
  const value = Number(m[1].replace(',', '.'));
  if (!Number.isFinite(value)) return Number.MAX_SAFE_INTEGER / 4;
  switch (m[2]) {
    case 'second': return value / 60;
    case 'minute': return value;
    case 'hour': return value * 60;
    case 'day': return value * 60 * 24;
    case 'week': return value * 60 * 24 * 7;
    case 'month': return value * 60 * 24 * 30;
    case 'year': return value * 60 * 24 * 365;
    default: return Number.MAX_SAFE_INTEGER / 4;
  }
}

function watchedFraction(entry) {
  if (!entry || !(entry.positionMs > 0)) return null;
  const duration = entry.durationMs > 0
    ? entry.durationMs
    : (entry.video && entry.video.durationSec ? entry.video.durationSec * 1000 : 0);
  if (!(duration > 0)) return null;
  return Math.min(1, Math.max(0, entry.positionMs / duration));
}

function completedIdsFromHistory(history) {
  return new Set((history || [])
    .filter((e) => (watchedFraction(e) || 0) >= COMPLETED_FRACTION)
    .map((e) => e.video && e.video.videoId)
    .filter(Boolean));
}

function recommendable(videos) {
  return (videos || []).filter((v) => v && !v.membersOnly);
}

function dedupeVideos(videos) {
  const seen = new Set();
  return (videos || []).filter((v) => {
    if (!v || !v.videoId || seen.has(v.videoId)) return false;
    seen.add(v.videoId);
    return true;
  });
}

// Seeded RNG so a rotation is reproducible (tests) while still differing
// between refreshes, like Kotlin's Random(rotation * 7919 + 104729).
function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function emptySignals() {
  return {
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
  };
}

// Score every candidate, then draw `limit` videos with probability
// proportional to their score. `rotation` reseeds the draw.
function rankAndSample(pool, signals, rotation, limit = RANK_LIMIT, maxPerChannel = MAX_PER_CHANNEL) {
  const s = { ...emptySignals(), ...(signals || {}) };
  const candidates = (pool || []).filter((v) => v
    && !v.membersOnly
    && !s.hiddenIds.has(v.videoId)
    && !(v.channelId && s.blockedChannelIds.has(v.channelId)));
  if (!candidates.length) return [];
  const rng = mulberry32((Math.imul(rotation, 7919) + 104729) >>> 0);

  function score(v) {
    const age = publishAgeMinutes(v.publishedText);
    let value = 60_000 / (age + 480);
    const aff = s.affinity.get(v.channelId) || 0;
    if (aff > 0) value *= 1 + 1.6 * Math.min(1, aff / (aff + 2.5));
    if (v.channelId && s.subIds.has(v.channelId)) value *= 2.2;
    if (v.channelId && s.likedChannelIds.has(v.channelId)) value *= 1.15;
    if (v.channelId && s.interestedChannelIds.has(v.channelId)) value *= 2.0;
    if (s.seedIds.has(v.videoId)) value *= 2.3;
    if (s.watchedIds.has(v.videoId)) value *= 0.12;
    if (s.completedIds.has(v.videoId)) value *= 0.22;
    if (s.searchTerms.size) {
      const title = String(v.title || '').toLowerCase();
      let matches = 0;
      for (const term of s.searchTerms) if (title.includes(term)) matches += 1;
      if (matches > 0) value *= 1 + Math.min(1.2, 0.45 * matches);
      const channel = String(v.channelName || '').toLowerCase();
      if (channel) {
        for (const term of s.searchTerms) {
          if (channel.includes(term)) { value *= 1.25; break; }
        }
      }
    }
    // Per-refresh perturbation: reshuffles the page without letting noise
    // override the signals above.
    value *= 0.72 + rng() * 0.56;
    return Math.max(value, 1e-6);
  }

  const scored = dedupeVideos(candidates).map((v) => [v, score(v)]);
  const out = [];
  const overflow = [];
  const perChannel = new Map();

  while (scored.length) {
    if (out.length >= limit) break;
    let total = 0;
    for (const [, w] of scored) total += w;
    if (total <= 0) break;
    let target = rng() * total;
    let pick = scored.length - 1;
    for (let i = 0; i < scored.length; i += 1) {
      target -= scored[i][1];
      if (target <= 0) { pick = i; break; }
    }
    const [video] = scored.splice(pick, 1)[0];
    const key = video.channelId || video.videoId;
    const n = perChannel.get(key) || 0;
    if (n >= maxPerChannel) { overflow.push(video); continue; }
    perChannel.set(key, n + 1);
    out.push(video);
  }
  // Diversity first, then fill from what the per-channel cap pushed out so a
  // small candidate pool still fills the page.
  for (const video of overflow) {
    if (out.length >= limit) break;
    out.push(video);
  }
  return out;
}

function withTimeout(promise, ms, fallback) {
  return Promise.race([
    Promise.resolve(promise).catch(() => fallback),
    new Promise((resolve) => {
      const timer = setTimeout(() => resolve(fallback), ms);
      timer.unref?.();
    }),
  ]);
}

async function relatedFromHistory(tube, history) {
  const groups = await Promise.all(history.slice(0, 3).map(async (entry) => {
    try {
      const data = await withTimeout(tube.watch(entry.video.videoId), 8_000, null);
      return (data && data.related) || [];
    } catch {
      return [];
    }
  }));
  return recommendable(groups.flat());
}

async function videosForQueries(tube, queries) {
  const groups = await Promise.all(queries.map(async (query) => {
    try {
      const result = await withTimeout(tube.search(query), 10_000, null);
      return ((result && result.entries) || [])
        .filter((e) => e && e.kind === 'video')
        .map((e) => e.video)
        .slice(0, 12);
    } catch {
      return [];
    }
  }));
  return recommendable(groups.flat());
}

async function latestFromChannels(tube, channelIds, perChannel, force) {
  if (!channelIds.length) return [];
  const results = new Array(channelIds.length);
  let next = 0;
  const worker = async () => {
    while (next < channelIds.length) {
      const index = next;
      next += 1;
      const id = channelIds[index];
      try {
        const page = await withTimeout(tube.channelVideos(id, undefined, force), 8_000, null);
        results[index] = ((page && page.videos) || []).slice(0, perChannel);
      } catch {
        results[index] = [];
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(8, channelIds.length) }, worker));
  return dedupeVideos(results.flat()).filter((v) => !v.membersOnly);
}

function signalsFrom(lib, extra = {}) {
  const library = (lib && lib.library) || {};
  const history = [...(library.history || [])].sort((a, b) => b.ts - a.ts);
  const liked = library.liked || [];
  const subs = (lib && lib.subscriptions) || [];

  const affinity = new Map();
  const now = Date.now();
  for (const entry of history) {
    const cid = entry.video && entry.video.channelId;
    if (!cid) continue;
    const daysOld = Math.max(0, (now - entry.ts) / 86_400_000);
    affinity.set(cid, (affinity.get(cid) || 0) + 1 / (1 + daysOld * 0.7));
  }
  for (const video of liked) {
    const cid = video.channelId;
    if (!cid) continue;
    affinity.set(cid, (affinity.get(cid) || 0) + 0.8);
  }

  return {
    affinity,
    subIds: new Set(subs.map((s) => s.channelId).filter(Boolean)),
    likedChannelIds: new Set(liked.map((v) => v.channelId).filter(Boolean)),
    watchedIds: new Set(history.map((e) => e.video && e.video.videoId).filter(Boolean)),
    seedIds: new Set(),
    searchTerms: new Set(),
    completedIds: completedIdsFromHistory(history),
    interestedChannelIds: new Set(library.interestedChannelIds || []),
    hiddenIds: new Set(library.notInterestedIds || []),
    blockedChannelIds: new Set(library.blockedChannelIds || []),
    ...extra,
  };
}

// Instant placeholder mix from what is already on the device. Used when the
// network-backed pool comes back empty (offline, or nothing to gather from).
function seedRecommendations(lib, rotation, limit = 30) {
  const library = (lib && lib.library) || {};
  const history = [...(library.history || [])].sort((a, b) => b.ts - a.ts);
  const liked = library.liked || [];
  const saved = dedupeVideos([...liked, ...(library.watchLater || [])]);
  const candidates = recommendable(saved.length ? saved : history.map((e) => e.video));
  if (!candidates.length) return [];
  return rankAndSample(candidates, signalsFrom(lib), rotation, limit);
}

function knownChannelsFor(lib, history, searchVideos) {
  const library = (lib && lib.library) || {};
  const known = new Map();
  for (const sub of (lib && lib.subscriptions) || []) {
    known.set(sub.channelId, [sub.name, sub.avatar]);
  }
  const liked = library.liked || [];
  for (const video of [...liked, ...history.map((e) => e.video), ...searchVideos]) {
    if (!video || !video.channelId) continue;
    const current = known.get(video.channelId) || [null, null];
    known.set(video.channelId, [
      current[0] || video.channelName,
      current[1] || video.channelAvatar,
    ]);
  }
  return known;
}

function stampChannels(pool, known) {
  for (let i = 0; i < pool.length; i += 1) {
    const video = pool[i];
    const channel = video.channelId ? known.get(video.channelId) : null;
    if (channel && (!video.channelName || !video.channelAvatar)) {
      pool[i] = {
        ...video,
        channelName: video.channelName || channel[0],
        channelAvatar: video.channelAvatar || channel[1],
      };
    }
  }
}

// A local feed session: one cached pool + signals, and a rotation counter so
// every explicit refresh draws a genuinely new mix. `build()` mirrors
// Android's localRecommendations(); `more()` mirrors a continuation page.
function createLocalFeed(tube) {
  let rotation = 0;
  let snapshot = null; // { at, pool, signals, videos }
  let building = null;

  async function gather(lib, force) {
    const library = (lib && lib.library) || {};
    const history = [...(library.history || [])].sort((a, b) => b.ts - a.ts);
    const subs = (lib && lib.subscriptions) || [];
    const liked = library.liked || [];
    const searches = [...(library.searches || [])].sort((a, b) => b.ts - a.ts);
    const signals = signalsFrom(lib);

    const topWatched = [...signals.affinity.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([id]) => id)
      .filter((id) => !signals.subIds.has(id))
      .slice(0, 10);

    const recentQueries = [...new Set(searches.map((s) => s.query).filter(Boolean))].slice(0, 2);
    signals.searchTerms = new Set(searches.slice(0, 8).flatMap((s) => tokenizeQuery(s.query)));

    const channelIds = [...new Set([
      ...subs.map((s) => s.channelId),
      ...topWatched,
      ...signals.likedChannelIds,
      ...signals.interestedChannelIds,
    ].filter(Boolean))].slice(0, 32);

    // Related videos, search results and channel uploads are independent, so
    // all three groups run at once. Every request is bounded so one hung
    // endpoint cannot hold the first paint hostage.
    const [related, searched, fromChannels] = await Promise.all([
      relatedFromHistory(tube, history),
      videosForQueries(tube, recentQueries),
      latestFromChannels(tube, channelIds, 20, force),
    ]);

    signals.seedIds = new Set(related.map((v) => v.videoId));

    const pool = new Map();
    for (const video of fromChannels) pool.set(video.videoId, video);
    for (const video of searched) if (!pool.has(video.videoId)) pool.set(video.videoId, video);
    for (const video of related) if (!pool.has(video.videoId)) pool.set(video.videoId, video);

    const known = knownChannelsFor(lib, history, searched);
    const poolVideos = [...pool.values()];
    stampChannels(poolVideos, known);

    return { pool: poolVideos, signals };
  }

  async function build(lib, { force = false } = {}) {
    if (!force && snapshot && Date.now() - snapshot.at <= CACHE_TTL_MS) return snapshot.videos;
    if (building) {
      await building.catch(() => undefined);
      if (!force && snapshot) return snapshot.videos;
    }
    building = (async () => {
      const { pool, signals } = await gather(lib, force);
      rotation += 1;
      const candidates = pool.length ? pool : seedRecommendations(lib, rotation, RANK_LIMIT);
      const videos = rankAndSample(candidates, signals, rotation);
      snapshot = { at: Date.now(), pool: candidates, signals, videos };
      return videos;
    })();
    try {
      return await building;
    } finally {
      building = null;
    }
  }

  async function more(lib, { excludeIds = [], limit = PAGE_SIZE } = {}) {
    if (!snapshot) {
      await build(lib, { force: true });
    } else if (Date.now() - snapshot.at > CACHE_TTL_MS) {
      await build(lib, { force: true });
    }
    const excluded = new Set(excludeIds.filter((id) => typeof id === 'string'));
    let pool = snapshot ? snapshot.pool.filter((v) => !excluded.has(v.videoId)) : [];
    // The pool is running out: gather fresh channel data once so "more"
    // keeps producing videos instead of stopping early.
    if (pool.length < Math.min(REBUILD_THRESHOLD, limit)) {
      await build(lib, { force: true });
      pool = snapshot ? snapshot.pool.filter((v) => !excluded.has(v.videoId)) : [];
    }
    if (!pool.length) return [];
    rotation += 1;
    return rankAndSample(pool, snapshot.signals, rotation, limit);
  }

  return {
    build,
    more,
    get rotation() { return rotation; },
  };
}

module.exports = {
  createLocalFeed,
  rankAndSample,
  tokenizeQuery,
  publishAgeMinutes,
  completedIdsFromHistory,
  watchedFraction,
  seedRecommendations,
  recommendable,
  COMPLETED_FRACTION,
  CACHE_TTL_MS,
  RANK_LIMIT,
  MAX_PER_CHANNEL,
};
