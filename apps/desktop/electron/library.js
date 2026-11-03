// Desktop library store — the main-process counterpart of Android's
// LibraryStore + AccountsStore.subscriptions. One JSON file in userData,
// shaped exactly like the Android wire format so a sync snapshot transfers
// without translation. All mutations live here; the renderer talks to it
// through IPC and re-renders from broadcast state.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const syncCore = require('./syncCore');

const HISTORY_CAP = syncCore.HISTORY_CAP; // 500, same as Android
const SEARCH_CAP = 200;
const COMPLETED_FRACTION = 0.92; // same as Android's Models.kt

function watchedFraction(entry) {
  if (!entry || !(entry.positionMs > 0)) return null;
  const duration = entry.durationMs > 0
    ? entry.durationMs
    : (entry.video && entry.video.durationSec ? entry.video.durationSec * 1000 : 0);
  if (!(duration > 0)) return null;
  return Math.min(1, Math.max(0, entry.positionMs / duration));
}

function emptyData() {
  return {
    profileName: 'Desktop',
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
    },
    subscriptions: [],
    downloads: [],
  };
}

// dataDir: app.getPath('userData'). The store never throws into IPC — a
// corrupt file is renamed aside and replaced with a fresh one, like Android.
function createStore(dataDir) {
  const file = path.join(dataDir, 'indiotube-library.json');
  let data = emptyData();
  const listeners = new Set();

  try {
    if (fs.existsSync(file)) {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      data = {
        ...emptyData(),
        ...parsed,
        library: { ...emptyData().library, ...(parsed.library || {}) },
      };
      if (!Array.isArray(data.subscriptions)) data.subscriptions = [];
      if (!Array.isArray(data.downloads)) data.downloads = [];
    }
  } catch {
    try { fs.renameSync(file, path.join(dataDir, 'indiotube-library_corrupt.json')); } catch { /* ignore */ }
  }

  let writeTimer = null;
  function persist() {
    // Coalesce bursts (progress ticks + card actions) into one small write.
    if (writeTimer) clearTimeout(writeTimer);
    writeTimer = setTimeout(() => {
      writeTimer = null;
      try {
        const tmp = `${file}.tmp`;
        fs.writeFileSync(tmp, JSON.stringify(data));
        fs.renameSync(tmp, file);
      } catch { /* next mutation retries */ }
    }, 250);
    writeTimer.unref?.();
  }

  function broadcast() {
    const snapshot = getState();
    for (const cb of listeners) { try { cb(snapshot); } catch { /* dead listener */ } }
  }

  function commit() { persist(); broadcast(); }

  function getState() {
    return JSON.parse(JSON.stringify(data));
  }

  const byId = (list, id) => (list || []).find((v) => v.videoId === id);
  // History rows wrap the video, so they need their own lookup (a plain
  // byId() over history silently found nothing and broke resume/watched).
  const historyEntry = (videoId) => data.library.history.find((e) => e.video.videoId === videoId);

  const store = {
    getState,
    // Change listeners. NB: kept separate from subscribe(channel) below — both
    // used to share the name "subscribe", and the channel method (defined
    // later in this object) silently won, so nothing was ever notified and
    // the renderer only saw the state it fetched at startup.
    onChange(cb) { listeners.add(cb); return () => listeners.delete(cb); },
    async flush() { if (writeTimer) { clearTimeout(writeTimer); writeTimer = null; } fs.writeFileSync(file, JSON.stringify(data)); },

    // ---- profile / settings surface ---------------------------------------
    setProfileName(name) {
      const clean = String(name || '').trim().slice(0, 48);
      if (!clean || clean === data.profileName) return;
      data.profileName = clean;
      commit();
    },

    // ---- history -----------------------------------------------------------
    noteOpen(video) {
      if (data.library.historyPaused) return;
      const existing = historyEntry(video.videoId);
      const entry = {
        video,
        ts: Date.now(),
        positionMs: existing ? existing.positionMs : 0,
        durationMs: existing ? existing.durationMs : 0,
      };
      data.library.history = [entry, ...data.library.history.filter((e) => e.video.videoId !== video.videoId)]
        .slice(0, HISTORY_CAP);
      commit();
    },

    notePosition(videoId, positionMs, durationMs) {
      if (data.library.historyPaused) return;
      const idx = data.library.history.findIndex((e) => e.video.videoId === videoId);
      if (idx < 0) return;
      const entry = data.library.history[idx];
      const samePosition = Math.abs((entry.positionMs || 0) - positionMs) < 5000;
      const sameDuration = !(durationMs > 0) || entry.durationMs === durationMs;
      if (samePosition && sameDuration) return;
      const updated = {
        ...entry,
        positionMs,
        durationMs: durationMs > 0 ? durationMs : entry.durationMs,
        ts: Date.now(),
      };
      data.library.history = [updated, ...data.library.history.filter((e) => e.video.videoId !== videoId)];
      commit();
    },

    resumePosition(videoId) {
      const e = historyEntry(videoId);
      const frac = watchedFraction(e);
      if (!e || !frac || frac >= COMPLETED_FRACTION) return 0;
      const pos = e.positionMs || 0;
      if (pos <= 20_000) return 0;
      const duration = e.durationMs > 0 ? e.durationMs : (e.video.durationSec || 0) * 1000;
      if (duration > 0 && pos >= duration - 10_000) return 0;
      return pos;
    },

    isWatched(videoId) {
      return (watchedFraction(historyEntry(videoId)) || 0) >= COMPLETED_FRACTION;
    },

    setHistoryPaused(paused) {
      if (data.library.historyPaused === Boolean(paused)) return;
      data.library.historyPaused = Boolean(paused);
      commit();
    },

    removeFromHistory(videoIds) {
      const ids = new Set(Array.isArray(videoIds) ? videoIds : [videoIds]);
      const before = data.library.history.length;
      data.library.history = data.library.history.filter((e) => !ids.has(e.video.videoId));
      if (data.library.history.length !== before) commit();
    },

    // Explicit "Mark as watched" from a card or the watch page - false when
    // history is paused, so the caller can explain why nothing changed.
    markWatched(video) {
      if (data.library.historyPaused) return false;
      const existing = historyEntry(video.videoId);
      const knownDuration = (existing && existing.durationMs > 0 ? existing.durationMs : 0)
        || (video.durationSec > 0 ? video.durationSec * 1000 : 0)
        || 1000;
      const entry = { video, ts: Date.now(), positionMs: knownDuration, durationMs: knownDuration };
      data.library.history = [entry, ...data.library.history.filter((e) => e.video.videoId !== video.videoId)]
        .slice(0, HISTORY_CAP);
      commit();
      return true;
    },

    // "Remove from Continue watching" only drops the progress; the history
    // row stays, exactly like Android's dismissContinueWatching().
    dismissContinueWatching(videoId) {
      const idx = data.library.history.findIndex((e) => e.video.videoId === videoId);
      if (idx < 0) return;
      const updated = { ...data.library.history[idx], positionMs: 0, ts: Date.now() };
      data.library.history = [updated, ...data.library.history.filter((e) => e.video.videoId !== videoId)]
        .slice(0, HISTORY_CAP);
      commit();
    },

    // Android's "Mark as unwatched": drop the progress, keep the row.
    markUnwatched(videoId) {
      const idx = data.library.history.findIndex((e) => e.video.videoId === videoId);
      if (idx < 0) return;
      const updated = { ...data.library.history[idx], positionMs: 0, ts: Date.now() };
      data.library.history = [updated, ...data.library.history.filter((e) => e.video.videoId !== videoId)]
        .slice(0, HISTORY_CAP);
      commit();
    },

    // ---- recommendation feedback (Android parity) ---------------------------
    // Same semantics as Android's LibraryStore: "not interested" pins a video
    // out of the recommendable feed, "don't recommend channel" blocks the
    // channel, "interested" is the positive counterpart.
    markNotInterested(video) {
      const id = video && video.videoId;
      if (!id) return;
      if (!data.library.notInterestedIds.includes(id)) data.library.notInterestedIds.push(id);
      const channel = video.channelId;
      if (channel) data.library.interestedChannelIds = data.library.interestedChannelIds.filter((c) => c !== channel);
      commit();
    },

    undoNotInterested(videoId) {
      if (!data.library.notInterestedIds.includes(videoId)) return;
      data.library.notInterestedIds = data.library.notInterestedIds.filter((id) => id !== videoId);
      commit();
    },

    markInterested(video) {
      const id = video && video.videoId;
      if (!id) return;
      data.library.notInterestedIds = data.library.notInterestedIds.filter((x) => x !== id);
      const channel = video.channelId;
      if (channel && !data.library.blockedChannelIds.includes(channel)
        && !data.library.interestedChannelIds.includes(channel)) {
        data.library.interestedChannelIds.push(channel);
      }
      commit();
    },

    blockChannel(video) {
      const channel = video && video.channelId;
      if (!channel) return;
      if (!data.library.blockedChannelIds.includes(channel)) data.library.blockedChannelIds.push(channel);
      data.library.interestedChannelIds = data.library.interestedChannelIds.filter((c) => c !== channel);
      commit();
    },

    unblockChannel(channelId) {
      data.library.blockedChannelIds = data.library.blockedChannelIds.filter((c) => c !== channelId);
      commit();
    },

    clearHistory() {
      data.library.history = [];
      commit();
    },

    // Fills gaps on stored copies of a video once the watch page has full
    // metadata — same enrichVideo semantics as Android.
    enrichVideo(video) {
      let changed = false;
      const merge = (old) => {
        const m = {
          ...old,
          title: old.title || video.title,
          channelId: old.channelId || video.channelId,
          channelName: old.channelName || video.channelName,
          channelAvatar: old.channelAvatar || video.channelAvatar,
          thumbnail: old.thumbnail || video.thumbnail,
          durationSec: old.durationSec > 0 ? old.durationSec : video.durationSec,
          viewsText: old.viewsText || video.viewsText,
          publishedText: old.publishedText || video.publishedText,
        };
        if (JSON.stringify(m) !== JSON.stringify(old)) changed = true;
        return m;
      };
      data.library.history = data.library.history.map((e) =>
        e.video.videoId === video.videoId ? { ...e, video: merge(e.video) } : e);
      data.library.watchLater = data.library.watchLater.map((v) => (v.videoId === video.videoId ? merge(v) : v));
      data.library.liked = data.library.liked.map((v) => (v.videoId === video.videoId ? merge(v) : v));
      data.library.playlists = data.library.playlists.map((pl) =>
        pl.videos.some((v) => v.videoId === video.videoId)
          ? { ...pl, videos: pl.videos.map((v) => (v.videoId === video.videoId ? merge(v) : v)) }
          : pl);
      if (changed) commit();
      return changed;
    },

    // ---- watch later / liked -----------------------------------------------
    isInWatchLater: (videoId) => Boolean(byId(data.library.watchLater, videoId)),
    isLiked: (videoId) => Boolean(byId(data.library.liked, videoId)),

    toggleWatchLater(video) {
      const present = store.isInWatchLater(video.videoId);
      data.library.watchLater = present
        ? data.library.watchLater.filter((v) => v.videoId !== video.videoId)
        : [video, ...data.library.watchLater];
      commit();
      return !present;
    },

    toggleLike(video) {
      const present = store.isLiked(video.videoId);
      data.library.liked = present
        ? data.library.liked.filter((v) => v.videoId !== video.videoId)
        : [video, ...data.library.liked];
      commit();
      return !present;
    },

    // ---- playlists ----------------------------------------------------------
    createPlaylist(name) {
      const clean = String(name || '').trim() || 'New playlist';
      const pl = { id: crypto.randomUUID(), name: clean, createdAt: Date.now(), videos: [] };
      data.library.playlists = [pl, ...data.library.playlists];
      commit();
      return pl;
    },

    renamePlaylist(id, name) {
      const clean = String(name || '').trim();
      if (!clean) return;
      data.library.playlists = data.library.playlists.map((p) => (p.id === id ? { ...p, name: clean } : p));
      commit();
    },

    deletePlaylist(id) {
      data.library.playlists = data.library.playlists.filter((p) => p.id !== id);
      commit();
    },

    addToPlaylist(playlistId, video) {
      data.library.playlists = data.library.playlists.map((p) => {
        if (p.id !== playlistId) return p;
        if (p.videos.some((v) => v.videoId === video.videoId)) return p;
        return { ...p, videos: [...p.videos, video] };
      });
      commit();
    },

    removeFromPlaylist(playlistId, videoId) {
      data.library.playlists = data.library.playlists.map((p) =>
        p.id === playlistId ? { ...p, videos: p.videos.filter((v) => v.videoId !== videoId) } : p);
      commit();
    },

    // ---- search history ------------------------------------------------------
    noteSearch(query) {
      const q = String(query || '').trim();
      if (!q) return;
      data.library.searches = [
        { query: q, ts: Date.now() },
        ...data.library.searches.filter((s) => s.query.toLowerCase() !== q.toLowerCase()),
      ].slice(0, SEARCH_CAP);
      commit();
    },

    clearSearches() {
      data.library.searches = [];
      commit();
    },

    // ---- subscriptions -------------------------------------------------------
    isSubscribed(channelId) {
      return Boolean(channelId) && data.subscriptions.some((s) => s.channelId === channelId);
    },

    subscribe(sub) {
      if (!sub || !sub.channelId || store.isSubscribed(sub.channelId)) return;
      data.subscriptions = [{ ...sub, at: Date.now() }, ...data.subscriptions];
      commit();
    },

    unsubscribe(channelId) {
      data.subscriptions = data.subscriptions.filter((s) => s.channelId !== channelId);
      commit();
    },

    // ---- downloads (desktop-saved files) --------------------------------------
    addDownload(item) {
      data.downloads = [item, ...data.downloads.filter((d) => d.video.videoId !== item.video.videoId)];
      commit();
    },

    removeDownload(videoId) {
      data.downloads = data.downloads.filter((d) => d.video.videoId !== videoId);
      commit();
    },

    // ---- sync ------------------------------------------------------------------
    snapshot() {
      return syncCore.buildSnapshot(data.profileName, { library: data.library, subscriptions: data.subscriptions });
    },

    mergeIncoming(incoming) {
      const result = syncCore.mergeIncoming(
        { library: data.library, subscriptions: data.subscriptions },
        incoming,
        () => crypto.randomUUID(),
      );
      // Add-only merge returns fresh arrays; keep unsynced local fields.
      data.library = { ...data.library, ...result.next.library };
      data.subscriptions = result.next.subscriptions;
      commit();
      return result.stats;
    },
  };

  return store;
}

module.exports = { createStore, HISTORY_CAP, COMPLETED_FRACTION, watchedFraction };
