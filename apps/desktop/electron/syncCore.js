// Phone-sync core: payload shape + add-only merge.
//
// This is the shared contract with the IndioTube Android app (SyncMerge.kt
// mirrors these exact semantics, and both sides run the same test vectors).
// Rule #1: sync only ever ADDS — nothing local is removed or overwritten by
// an incoming payload. Matching rows are left untouched or resolved by newest
// timestamp (history), and remote-only rows are appended/prepended. Merging
// the same payload twice is a no-op.
//
// Data shapes mirror the Android app's Models.kt:
//   VideoItem      { videoId, title, channelName, channelId, channelAvatar,
//                    thumbnail, durationSec, viewsText, publishedText,
//                    isLive, membersOnly }
//   HistoryEntry   { video: VideoItem, ts, positionMs, durationMs }
//   LocalPlaylist  { id, name, createdAt, videos: VideoItem[] }
//   Subscription   { channelId, name, avatar, at }
//   Snapshot       { indio:'sync', v, name, library: { watchLater, history,
//                    playlists, liked }, subscriptions }
const PROTOCOL_VERSION = 1;
const HISTORY_CAP = 500; // same cap as LibraryStore.HISTORY_CAP

function normName(s) {
  return String(s || '').trim().toLowerCase().replace(/\s+/g, ' ');
}

function buildSnapshot(name, lib) {
  return {
    indio: 'sync',
    v: PROTOCOL_VERSION,
    name: String(name || '').slice(0, 48),
    library: {
      watchLater: lib.library.watchLater || [],
      history: (lib.library.history || []).slice(0, HISTORY_CAP),
      playlists: (lib.library.playlists || []).map((p) => ({
        id: '',
        name: p.name,
        createdAt: p.createdAt || 0,
        videos: (p.videos || []).filter((v) => v && v.videoId),
      })),
      liked: lib.library.liked || [],
    },
    subscriptions: lib.subscriptions || [],
  };
}

function isVideo(v) {
  return v && typeof v === 'object' && typeof v.videoId === 'string' && v.videoId;
}

// Add-only union for a newest-first video list (watch later / liked). Remote
// entries the local side does not have are prepended in their own order, so
// fresh arrivals sit on top exactly like a local save would.
function unionVideos(local, incoming) {
  const have = new Set(local.map((v) => v.videoId));
  const additions = [];
  let added = 0;
  for (const v of Array.isArray(incoming) ? incoming : []) {
    if (!isVideo(v) || have.has(v.videoId)) continue;
    have.add(v.videoId);
    additions.push(v);
    added += 1;
  }
  return { next: [...additions, ...local], added };
}

// History is keyed by videoId and the entry with the newest ts wins, so the
// resume position travels with the most recent watch. Ties break toward the
// larger position. Result is ts-desc and capped like the local store.
function mergeHistory(local, incoming) {
  const byId = new Map();
  for (const e of Array.isArray(local) ? local : []) {
    if (e && isVideo(e.video)) byId.set(e.video.videoId, e);
  }
  let added = 0;
  for (const e of Array.isArray(incoming) ? incoming : []) {
    if (!e || !isVideo(e.video)) continue;
    const hit = byId.get(e.video.videoId);
    if (!hit) { byId.set(e.video.videoId, e); added += 1; continue; }
    const ts = Number(e.ts) || 0;
    const hitTs = Number(hit.ts) || 0;
    if (ts > hitTs || (ts === hitTs && (Number(e.positionMs) || 0) > (Number(hit.positionMs) || 0))) {
      byId.set(e.video.videoId, e);
    }
  }
  const next = [...byId.values()].sort((a, b) => (Number(b.ts) || 0) - (Number(a.ts) || 0)).slice(0, HISTORY_CAP);
  return { next, added };
}

// Playlists match by normalized name; a name that exists unions its videos
// (appended by videoId), a name that doesn't is added with a fresh local id.
function mergePlaylists(local, incoming, newId) {
  const playlists = (Array.isArray(local) ? local : []).map((p) => ({ ...p, videos: [...(p.videos || [])] }));
  const byName = new Map(playlists.map((p) => [normName(p.name), p]));
  let playlistsAdded = 0;
  let playlistsMerged = 0;
  let videosAdded = 0;
  for (const inc of Array.isArray(incoming) ? incoming : []) {
    const name = String(inc && inc.name || '').trim();
    if (!name) continue;
    const incVideos = (Array.isArray(inc.videos) ? inc.videos : []).filter(isVideo);
    const hit = byName.get(normName(name));
    if (!hit) {
      const np = { id: newId(), name, createdAt: Number(inc.createdAt) || 0, videos: [] };
      const seen = new Set();
      for (const v of incVideos) {
        if (seen.has(v.videoId)) continue;
        seen.add(v.videoId);
        np.videos.push(v);
        videosAdded += 1;
      }
      playlists.push(np);
      byName.set(normName(name), np);
      playlistsAdded += 1;
    } else {
      const have = new Set(hit.videos.map((v) => v.videoId));
      let added = 0;
      for (const v of incVideos) {
        if (have.has(v.videoId)) continue;
        have.add(v.videoId);
        hit.videos.push(v);
        added += 1;
      }
      if (added) { playlistsMerged += 1; videosAdded += added; }
    }
  }
  return { next: playlists, playlistsAdded, playlistsMerged, videosAdded };
}

// Subscriptions union by channelId; remote-only rows are prepended (the phone
// keeps newest subscriptions on top).
function mergeSubscriptions(local, incoming) {
  const subs = Array.isArray(local) ? [...local] : [];
  const have = new Set(subs.map((s) => s && s.channelId));
  const additions = [];
  let added = 0;
  for (const s of Array.isArray(incoming) ? incoming : []) {
    if (!s || typeof s.channelId !== 'string' || !s.channelId || have.has(s.channelId)) continue;
    have.add(s.channelId);
    additions.push(s);
    added += 1;
  }
  return { next: [...additions, ...subs], added };
}

// lib:  { library: { watchLater, history, playlists, liked }, subscriptions }
// incoming: the `snapshot` object from buildSnapshot (or a phone payload —
// unknown extra fields are ignored, missing arrays are treated as empty).
// newId: generator for playlist ids (crypto.randomUUID on desktop, UUID on
// Android) — never taken from the wire.
function mergeIncoming(lib, incoming, newId) {
  const inLib = (incoming && incoming.library) || {};
  const watch = unionVideos(lib.library.watchLater || [], inLib.watchLater);
  const liked = unionVideos(lib.library.liked || [], inLib.liked);
  const history = mergeHistory(lib.library.history || [], inLib.history);
  const playlists = mergePlaylists(lib.library.playlists || [], inLib.playlists, newId);
  const subs = mergeSubscriptions(lib.subscriptions || [], incoming && incoming.subscriptions);

  const stats = {
    historyAdded: history.added,
    watchLaterAdded: watch.added,
    likedAdded: liked.added,
    playlistsAdded: playlists.playlistsAdded,
    playlistsMerged: playlists.playlistsMerged,
    videosAdded: playlists.videosAdded,
    subsAdded: subs.added,
  };
  const next = {
    ...lib,
    library: {
      ...lib.library,
      watchLater: watch.next,
      liked: liked.next,
      history: history.next,
      playlists: playlists.next,
    },
    subscriptions: subs.next,
  };
  return { next, stats };
}

function syncSummary(s) {
  const parts = [];
  if (s.historyAdded) parts.push(`+${s.historyAdded} history`);
  if (s.watchLaterAdded) parts.push(`+${s.watchLaterAdded} watch later`);
  if (s.likedAdded) parts.push(`+${s.likedAdded} liked`);
  if (s.playlistsAdded) parts.push(`+${s.playlistsAdded} playlists`);
  if (s.playlistsMerged) parts.push(`${s.playlistsMerged} playlists updated`);
  else if (s.videosAdded) parts.push(`+${s.videosAdded} playlist videos`);
  if (s.subsAdded) parts.push(`+${s.subsAdded} subscriptions`);
  return parts.length ? parts.join(' · ') : 'already in sync';
}

module.exports = {
  PROTOCOL_VERSION,
  HISTORY_CAP,
  normName,
  buildSnapshot,
  mergeIncoming,
  syncSummary,
};
