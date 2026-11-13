// IndioTube desktop — Electron main process. Owns the window, the library
// store, the InnerTube client, the yt-dlp sidecar (streams + proxy) and the
// LAN sync server. The renderer only ever sees the small IPC surface exposed
// through preload.js.
const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { InnerTube } = require('./inTube');
const library = require('./library');
const { createLocalFeed } = require('./localFeed');
const { createStreams } = require('./streams');
const sync = require('./sync');

// The picture and the sound are separate media elements; Chromium's default
// policy can refuse an unmuted play() on the one the viewer has not clicked.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const DEV_URL = process.env.INDIOTUBE_DEV_URL || 'http://127.0.0.1:5174';
// Cap on a local "load more" page, so a renderer bug cannot ask for the whole
// candidate pool at once.
const RANK_PAGE_MAX = 80;

let win = null;
let store = null;
let streams = null;
let syncState = null; // { server, stop }
const tube = new InnerTube();
// Signed-out YouTube serves an empty Home, so the local recommender builds a
// personal mix from this device's history, subscriptions, likes and searches
// (the desktop counterpart of Android's LocalFeed.kt).
const localFeed = createLocalFeed(tube);

// Stream cancellation (seeks) and network drops must never surface
// Electron's default "JavaScript error occurred in the main process"
// dialog. Everything here is already handled where it happens; these are
// the last-resort nets so one stray rejection can't kill the session.
process.on('unhandledRejection', (reason) => {
  console.warn('[indiotube] unhandled rejection:', reason && reason.message ? reason.message : reason);
});
process.on('uncaughtException', (err) => {
  console.warn('[indiotube] uncaught exception:', err && err.message ? err.message : err);
});

// ---- settings ------------------------------------------------------------------

const DEFAULT_SETTINGS = {
  theme: 'dark',
  autoplay: true,
  defaultQuality: null,   // null = Auto
  captionsEnabled: false,
  captionLang: '',
  sponsorBlock: true,
  // Full SponsorBlock controls, mirroring the phone: a master switch, an
  // auto-skip switch, and the categories to skip. Auto-skip stays on by
  // default because the desktop toggle always behaved as "auto-skip".
  sponsorBlockAutoSkip: true,
  sponsorBlockCategories: [
    'sponsor', 'selfpromo', 'interaction', 'intro', 'outro', 'preview', 'music_offtopic', 'filler',
  ],
  downloadDir: '',        // filled on first use
  downloadHeight: 720,
  volume: 1,
  playbackRate: 1,
  theater: false,
};
let settings = { ...DEFAULT_SETTINGS };
let settingsFile = null;

function loadSettings() {
  settingsFile = path.join(app.getPath('userData'), 'indiotube-settings.json');
  try {
    if (fs.existsSync(settingsFile)) {
      settings = { ...DEFAULT_SETTINGS, ...JSON.parse(fs.readFileSync(settingsFile, 'utf8')) };
    }
  } catch { /* defaults */ }
  if (!settings.downloadDir) {
    settings.downloadDir = path.join(app.getPath('downloads'), 'IndioTube');
    try { fs.mkdirSync(settings.downloadDir, { recursive: true }); } catch { /* ok */ }
  }
}

function saveSettings() {
  try { fs.writeFileSync(settingsFile, JSON.stringify(settings, null, 2)); } catch { /* ignore */ }
}

// ---- yt-dlp sidecar location ------------------------------------------------------

// ffmpeg merges the split video/audio streams YouTube serves. Vendored like
// yt-dlp (scripts/fetch-ffmpeg.js): without it a download can only keep a
// single-file format, and logged-out YouTube offers very few of those.
function resolveFfmpeg() {
  const candidates = app.isPackaged
    ? [path.join(process.resourcesPath, 'ffmpeg', 'ffmpeg.exe')]
    : [
      path.join(__dirname, '..', 'vendor', 'ffmpeg', 'ffmpeg.exe'),
      path.join(app.getPath('userData'), 'ffmpeg', 'ffmpeg.exe'),
    ];
  for (const c of candidates) {
    try { if (fs.existsSync(c)) return c; } catch { /* keep looking */ }
  }
  return null;
}

function resolveYtdlp() {
  const candidates = app.isPackaged
    ? [path.join(process.resourcesPath, 'ytdlp', 'yt-dlp.exe')]
    : [
      path.join(__dirname, '..', 'vendor', 'ytdlp', 'yt-dlp.exe'),
      path.join(app.getPath('userData'), 'ytdlp', 'yt-dlp.exe'),
    ];
  for (const c of candidates) {
    try { if (fs.existsSync(c)) return c; } catch { /* keep looking */ }
  }
  return null;
}

// ---- helpers ---------------------------------------------------------------------

const ok = (data) => ({ ok: true, ...data });
const fail = (err) => ({ ok: false, error: String(err && err.message || err) });

async function wrap(fn) {
  try { return ok(await fn()); } catch (e) { return fail(e); }
}

function broadcast(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

// ---- IPC --------------------------------------------------------------------------

function registerIpc() {
  // InnerTube browsing. Handlers return { ok, ... } shape with named keys;
  // preload throws on { ok: false } so the renderer can try/catch everywhere.
  //
  // Home is special: a signed-out FEwhat_to_watch is empty, so an empty
  // network answer is replaced by the local recommender (Android parity).
  // `force` (a reload / logo click) rotates the mix, so every refresh shows
  // different videos from the same signals.
  ipcMain.handle('indio:home', (_e, p = {}) => wrap(async () => {
    const force = Boolean(p.force);
    const params = p.params || null;
    let remote = null;
    try {
      remote = await tube.home(params, force);
    } catch {
      remote = { chips: [], videos: [], continuation: null };
    }
    // A category chip is a YouTube-side filter; local recommendations cannot
    // honour it, so only substitute the local mix for the plain Home tab.
    if (remote.videos.length || params) return { feed: remote };
    const videos = await localFeed.build(store.getState(), { force });
    if (!videos.length) return { feed: remote };
    return {
      feed: {
        chips: remote.chips || [],
        videos,
        continuation: null,
        local: true,
      },
    };
  }));
  ipcMain.handle('indio:homeMore', (_e, p = {}) => wrap(async () => ({
    videos: await localFeed.more(store.getState(), {
      excludeIds: Array.isArray(p.excludeIds) ? p.excludeIds : [],
      limit: Number(p.limit) > 0 ? Math.min(RANK_PAGE_MAX, Number(p.limit)) : undefined,
    }),
  })));
  ipcMain.handle('indio:homeCont', (_e, p) => wrap(async () => ({ feed: await tube.homeContinuation(p.token) })));
  ipcMain.handle('indio:trending', (_e, p = {}) => wrap(async () => ({ feed: await tube.trending(Boolean(p.force)) })));
  ipcMain.handle('indio:trendingCont', (_e, p) => wrap(async () => ({ feed: await tube.trendingContinuation(p.token) })));
  ipcMain.handle('indio:search', (_e, p) => wrap(async () => ({ results: await tube.search(String(p.query || ''), p.filters || {}) })));
  ipcMain.handle('indio:searchCont', (_e, p) => wrap(async () => ({ results: await tube.searchContinuation(p.token) })));
  ipcMain.handle('indio:suggest', (_e, p) => wrap(async () => ({ suggestions: await tube.suggestions(String(p.query || '')) })));
  ipcMain.handle('indio:channel', (_e, p) => wrap(async () => ({ page: await tube.channelVideos(p.channelId, p.params || undefined) })));
  ipcMain.handle('indio:channelCont', (_e, p) => wrap(async () => ({ page: await tube.channelContinuation(p.token, p.channelId || '') })));
  ipcMain.handle('indio:channelLive', (_e, p) => wrap(async () => ({ page: await tube.channelLive(p.channelId) })));
  ipcMain.handle('indio:channelPlaylists', (_e, p) => wrap(async () => ({ page: await tube.channelPlaylistsPage(p.channelId) })));
  ipcMain.handle('indio:channelPlaylistsCont', (_e, p) => wrap(async () => ({ page: await tube.channelPlaylistsContinuation(p.token) })));
  ipcMain.handle('indio:channelAbout', (_e, p) => wrap(async () => ({ about: await tube.channelAbout(p.channelId, p.token || null) })));
  ipcMain.handle('indio:watch', (_e, p) => wrap(async () => ({ data: await tube.watch(p.videoId) })));
  ipcMain.handle('indio:watchMore', (_e, p) => wrap(async () => ({ page: await tube.watchContinuation(p.token) })));
  ipcMain.handle('indio:comments', (_e, p) => wrap(async () => ({ page: await tube.comments(p.token) })));
  ipcMain.handle('indio:playlist', (_e, p) => wrap(async () => ({ page: await tube.playlist(p.playlistId) })));
  ipcMain.handle('indio:playlistCont', (_e, p) => wrap(async () => ({ page: await tube.playlistContinuation(p.token) })));

  // Streams + downloads.
  ipcMain.handle('streams:qualities', (_e, p) => wrap(async () => ({ qualities: await streams.qualities(p.videoId, Boolean(p.force)) })));
  ipcMain.handle('streams:playUrl', (_e, p) => wrap(() => streams.playUrl(p.videoId, p.height ?? null)));
  ipcMain.handle('streams:subtitleUrl', (_e, p) => wrap(async () => ({ url: await streams.subtitleUrl(p.videoId, p.lang || '') })));
  ipcMain.handle('streams:localUrl', (_e, p) => ok({ url: streams.localFileUrl(p.file) }));
  ipcMain.handle('streams:ffmpeg', () => wrap(async () => ({ available: await streams.ffmpegAvailable() })));
  ipcMain.handle('downloads:start', (_e, p) => wrap(() => streams.startDownload(p.video, p.height)));
  ipcMain.handle('downloads:cancel', (_e, p) => ok({ cancelled: streams.cancelDownload(p.videoId) }));
  ipcMain.handle('downloads:list', () => ok({ tasks: streams.listDownloads() }));
  ipcMain.handle('downloads:pause', (_e, p) => ok({ paused: streams.pauseDownload(p.videoId) }));
  ipcMain.handle('downloads:resume', (_e, p) => ok({ resumed: streams.resumeDownload(p.videoId) }));
  ipcMain.handle('downloads:pickDir', async () => {
    const res = await dialog.showOpenDialog(win, {
      title: 'Choose a downloads folder',
      defaultPath: settings.downloadDir,
      properties: ['openDirectory', 'createDirectory'],
    });
    if (res.canceled || !res.filePaths.length) return ok({ dir: settings.downloadDir });
    settings.downloadDir = res.filePaths[0];
    saveSettings();
    return ok({ dir: settings.downloadDir });
  });
  ipcMain.handle('downloads:reveal', (_e, p) => { shell.showItemInFolder(p.file); return ok({}); });

  // SponsorBlock — plain HTTPS, proxied through main to avoid CORS. The host
  // is the official API (sponsor.ajay.app); the old sponsor.ajay.news name
  // never resolved, so every lookup failed silently. The categories selected
  // in Settings are requested instead of filtering a full list afterwards.
  const sbCache = new Map();
  ipcMain.handle('sb:segments', (_e, p) => wrap(async () => {
    const videoId = String(p.videoId || '');
    if (!/^[A-Za-z0-9_-]{6,}$/.test(videoId)) throw new Error('invalid video id');
    const categories = Array.isArray(p.categories) && p.categories.length
      ? p.categories.map(String)
      : settings.sponsorBlockCategories;
    const key = `${videoId}|${[...categories].sort().join(',')}`;
    const hit = sbCache.get(key);
    if (hit && Date.now() - hit.at < 10 * 60_000) return { data: hit.data };
    const url = new URL('https://sponsor.ajay.app/api/skipSegments');
    url.searchParams.set('videoID', videoId);
    url.searchParams.set('categories', JSON.stringify(categories));
    const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
    // 404 is the normal "nobody submitted segments" answer, not a failure.
    if (res.status === 404) {
      sbCache.set(key, { at: Date.now(), data: [] });
      return { data: [] };
    }
    if (!res.ok) throw new Error(`SponsorBlock returned HTTP ${res.status}`);
    const data = await res.json();
    if (!Array.isArray(data)) return { data: [] };
    sbCache.set(key, { at: Date.now(), data });
    return { data };
  }));

  // Library: get state once, then re-render on every mutation broadcast.
  ipcMain.handle('lib:get', () => ok({ state: store.getState() }));
  const LIB_OPS = new Set([
    'noteOpen', 'notePosition', 'resumePosition', 'isWatched', 'setHistoryPaused',
    'markWatched', 'markUnwatched', 'dismissContinueWatching',
    'markNotInterested', 'undoNotInterested', 'markInterested', 'blockChannel', 'unblockChannel',
    'removeFromHistory', 'clearHistory', 'enrichVideo', 'isInWatchLater', 'isLiked',
    'toggleWatchLater', 'toggleLike', 'createPlaylist', 'renamePlaylist', 'deletePlaylist',
    'addToPlaylist', 'removeFromPlaylist', 'noteSearch', 'clearSearches',
    'isSubscribed', 'subscribe', 'unsubscribe', 'addDownload', 'removeDownload',
    'setProfileName',
  ]);
  ipcMain.handle('lib:op', (_e, p) => wrap(() => {
    if (!LIB_OPS.has(p.op)) throw new Error(`unknown library operation ${p.op}`);
    const fn = store[p.op];
    const result = fn(...(p.args || []));
    return { result: result === undefined ? null : result };
  }));

  // Settings.
  ipcMain.handle('settings:get', () => ok({ settings: { ...settings } }));
  ipcMain.handle('settings:set', (_e, p) => {
    settings = { ...settings, ...(p.patch || {}) };
    saveSettings();
    return ok({ settings: { ...settings } });
  });

  // Phone sync.
  ipcMain.handle('sync:start', () => wrap(async () => {
    if (syncState) await syncState.stop();
    const server = await sync.startSyncServer({
      name: store.getState().profileName || app.getName(),
      onEvent: (evt) => broadcast('sync:event', evt),
      onRequest: async ({ kind, payload }) => {
        if (kind === 'snapshot') return store.snapshot();
        const stats = store.mergeIncoming(payload);
        return { stats };
      },
    });
    syncState = server;
    return { payload: server.payload, ips: server.ips, port: server.port };
  }));
  ipcMain.handle('sync:stop', async () => {
    if (syncState) { await syncState.stop(); syncState = null; }
    return ok({});
  });

  // Mini player window mode (always-on-top small window). The pre-mini
  // geometry is remembered so restoring returns to exactly where you were
  // (including maximized/fullscreen), instead of a generic 1280x820.
  let miniPrev = null;
  ipcMain.handle('win:mini', (_e, p) => {
    if (!win) return ok({});
    if (p.on) {
      miniPrev = {
        bounds: win.getBounds(),
        maximized: win.isMaximized(),
        fullscreen: win.isFullScreen(),
      };
      if (win.isFullScreen()) win.setFullScreen(false);
      if (win.isMaximized()) win.unmaximize();
      win.setAlwaysOnTop(true, 'screen-saver');
      // A mini player must be able to shrink past the normal minimum size.
      win.setMinimumSize(380, 240);
      win.setResizable(true);
      win.setBounds({ width: 460, height: 300, x: win.getPosition()[0], y: win.getPosition()[1] }, true);
    } else {
      win.setAlwaysOnTop(false);
      if (win.isFullScreen()) win.setFullScreen(false);
      if (miniPrev) {
        win.setBounds(miniPrev.bounds, true);
        if (miniPrev.maximized) win.maximize();
      } else {
        win.setBounds({ width: 1280, height: 820 }, true);
      }
      win.setMinimumSize(940, 600);
      miniPrev = null;
    }
    return ok({ mini: Boolean(p.on) });
  });

  // Window fullscreen — the renderer hides its chrome while this is on.
  ipcMain.handle('win:fullscreen', (_e, p) => {
    if (!win) return ok({ fullscreen: false });
    win.setFullScreen(Boolean(p.on));
    return ok({ fullscreen: win.isFullScreen() });
  });
  ipcMain.handle('win:isFullscreen', () => ok({ fullscreen: Boolean(win && win.isFullScreen()) }));

  ipcMain.handle('app:version', () => ok({ version: app.getVersion() }));
}

// ---- app lifecycle ------------------------------------------------------------------

app.requestSingleInstanceLock();

async function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 940,
    minHeight: 600,
    backgroundColor: '#0f0f0f',
    autoHideMenuBar: true,
    title: 'IndioTube',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // YouTube thumbnails and i.ytimg.com load directly in the renderer.
      webSecurity: true,
      // The player's A/V sync tick must keep running at full rate even when
      // the window is in the background, or audio can drift while minimized.
      backgroundThrottling: false,
    },
  });
  if (!app.isPackaged) {
    const smokeRoute = process.env.INDIOTUBE_SMOKE_ROUTE || '';
    win.loadURL(DEV_URL + (smokeRoute ? `#/${smokeRoute.replace(/^#?\/?/, '')}` : ''));
  } else {
    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }
  const throttleKbps = Number(process.env.INDIOTUBE_SMOKE_THROTTLE || 0);
  if (throttleKbps > 0) {
    try {
      win.webContents.session.enableNetworkEmulation({
        downloadThroughput: throttleKbps * 1024,
        uploadThroughput: 64 * 1024,
      });
    } catch (e) { console.log('[throttle-fail]', e.message); }
  }
  win.on('closed', () => { win = null; });
  win.on('enter-full-screen', () => { if (win) win.webContents.send('win:fullscreen-changed', true); });
  win.on('leave-full-screen', () => { if (win) win.webContents.send('win:fullscreen-changed', false); });

  // INDIOTUBE_SMOKE=1: the dev harness boots the UI, reports what the
  // renderer actually painted (plus any console errors), and exits. Used by
  // the release smoke run; never active in normal use.
  if (process.env.INDIOTUBE_SMOKE === '1' && !app.isPackaged) {
    win.webContents.on('console-message', (_e, level, message) => {
      if (level >= 2) console.log('[renderer-error]', String(message).slice(0, 300));
    });
    win.webContents.once('did-finish-load', () => {
      const earlyMs = Number(process.env.INDIOTUBE_SMOKE_SHOT_EARLY || 0);
      if (earlyMs > 0 && process.env.INDIOTUBE_SMOKE_SHOT) {
        // Capture while the first paint is still on skeletons.
        setTimeout(async () => {
          try {
            const image = await win.webContents.capturePage();
            const p = process.env.INDIOTUBE_SMOKE_SHOT.replace(/\.png$/, '-early.png');
            fs.writeFileSync(p, image.toPNG());
            console.log('[shot-early]', p);
          } catch (e) { console.log('[shot-early-fail]', e.message); }
        }, earlyMs);
      }
      setTimeout(async () => {
        try {
          const report = await win.webContents.executeJavaScript(`({
            title: document.title,
            hash: location.hash,
            nodes: document.querySelectorAll('*').length,
            skeletons: document.querySelectorAll('.sk').length,
            contentScrollable: (() => { const el = document.querySelector('.content'); return el ? el.scrollHeight > el.clientHeight + 4 : null; })(),
            text: (document.body && document.body.innerText || '').replace(/\\s+/g,' ').slice(0, 220),
            errors: (window.__smokeErrors || []),
          })`);
          console.log('[smoke]', JSON.stringify(report));
          // Generic probe for release checks that need DOM detail the report
          // above does not carry (modal contents, switches, QR canvas).
          if (process.env.INDIOTUBE_SMOKE_PROBE) {
            try {
              const probed = await win.webContents.executeJavaScript(process.env.INDIOTUBE_SMOKE_PROBE);
              console.log('[probe]', JSON.stringify(probed));
            } catch (e) { console.log('[probe-fail]', e.message); }
          }
          if (process.env.INDIOTUBE_SMOKE_MEDIA === '1') {
            // Timeline probe: seek once, then sample both clocks every 800ms.
            // With INDIOTUBE_SMOKE_THROTTLE this reproduces "seeking while
            // the audio is still loading" over a slow link.
            const media = await win.webContents.executeJavaScript(`(async () => {
              const v = document.querySelector('video');
              if (!v) return { none: true };
              const a = document.querySelector('audio');
              const snap = () => ({
                t: Math.round(v.currentTime * 10) / 10,
                vr: v.readyState,
                vp: v.paused,
                at: a ? Math.round(a.currentTime * 10) / 10 : null,
                am: a ? a.muted : null,
                as: a ? a.seeking : null,
                ar: a ? a.readyState : null,
                ap: a ? a.paused : null,
              });
              const samples = [snap()];
              v.currentTime = 60;
              for (let i = 0; i < 8; i += 1) {
                await new Promise((r) => setTimeout(r, 800));
                samples.push(snap());
              }
              return { samples };
            })()`);
            console.log('[media]', JSON.stringify(media));
            // Open the player settings menu the same way a click does and
            // report its role/placement/focus plus the audio element state.
            const menu = await win.webContents.executeJavaScript(`(async () => {
              const sample = () => {
                const v = document.querySelector('video');
                const a = document.querySelector('audio');
                const ae = document.activeElement;
                return {
                  focus: ae ? (ae.tagName + ':' + String(ae.textContent || '').trim().slice(0, 18)) : null,
                  menuOpen: Boolean(document.querySelector('[role="menu"]')),
                  v: v ? { t: Math.round(v.currentTime * 10) / 10, paused: v.paused } : null,
                  a: a ? { t: Math.round(a.currentTime * 10) / 10, muted: a.muted, seeking: a.seeking, ready: a.readyState, paused: a.paused } : null,
                };
              };
              const btn = document.querySelector('[aria-label="Player settings"]');
              if (!btn) return { none: true };
              btn.click();
              await new Promise((r) => setTimeout(r, 150));
              const t0 = sample();
              await new Promise((r) => setTimeout(r, 1200));
              const t1 = sample();
              await new Promise((r) => setTimeout(r, 1500));
              const t2 = sample();
              const m = document.querySelector('[role="menu"]');
              return {
                menu: m ? {
                  label: m.getAttribute('aria-label'),
                  items: m.querySelectorAll('button').length,
                  height: Math.round(m.getBoundingClientRect().height),
                  top: Math.round(m.getBoundingClientRect().top),
                } : null,
                timeline: [t0, t1, t2],
              };
            })()`);
            console.log('[menu]', JSON.stringify(menu));
          }
          if (process.env.INDIOTUBE_SMOKE_MINI === '1') {
            // YouTube-style mini player: floating layer over the page, chrome
            // still usable, playback uninterrupted, expand back to watch, and
            // auto-dock when navigating away from a playing video.
            const click = (sel) => win.webContents.executeJavaScript(
              `(() => { const el = document.querySelector('${sel}'); if (!el) return false; el.click(); return true; })()`);
            const state = () => win.webContents.executeJavaScript(`({
              route: location.hash,
              mini: Boolean(document.querySelector('.playback-layer.mini')),
              chrome: getComputedStyle(document.querySelector('.topbar')).display !== 'none',
              t: Math.round(((document.querySelector('video') || {}).currentTime || 0) * 10) / 10,
              paused: Boolean(document.querySelector('video') && document.querySelector('video').paused),
            })`);
            win.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'Escape' });
            await new Promise((r) => setTimeout(r, 150));
            const before = await state();
            await click('[title="Mini player"]');
            await new Promise((r) => setTimeout(r, 1000));
            const inMini = await state();
            if (process.env.INDIOTUBE_SMOKE_SHOT) {
              try {
                const img = await win.webContents.capturePage();
                const p = process.env.INDIOTUBE_SMOKE_SHOT.replace(/\.png$/, '-mini.png');
                fs.writeFileSync(p, img.toPNG());
                console.log('[shot-mini]', p);
              } catch (e) { console.log('[shot-mini-fail]', e.message); }
            }
            await click('.mini-open');
            await new Promise((r) => setTimeout(r, 1000));
            const expanded = await state();
            await win.webContents.executeJavaScript("location.hash = '#/trending'");
            await new Promise((r) => setTimeout(r, 1000));
            const autoMini = await state();
            console.log('[mini]', JSON.stringify({ before, inMini, expanded, autoMini }));
          }
          if (process.env.INDIOTUBE_SMOKE_FEED === '1') {
            // Home refresh affordance + the card menu (placement, Android-
            // parity actions, and a working "Not interested").
            const feedState = () => win.webContents.executeJavaScript(`({
              refreshBar: Boolean(document.querySelector('.refresh-bar.on')),
              gridAnimated: Boolean(document.querySelector('.grid.feed-in')),
              sentinel: Boolean(document.querySelector('.feed-sentinel')),
              cards: document.querySelectorAll('.card').length,
              skeletons: document.querySelectorAll('.sk').length,
            })`);
            const cardVids = () => win.webContents.executeJavaScript(
              `[...document.querySelectorAll('.card')].map((c) => c.dataset.vid)`);
            // Diagnostic: count the library broadcasts the renderer actually
            // receives, so a stale UI can be told apart from a dropped event.
            await win.webContents.executeJavaScript(`(() => {
              window.__libEvents = 0;
              window.__libIds = null;
              window.indio.library.onChanged((s) => {
                window.__libEvents += 1;
                window.__libIds = (s && s.library && s.library.notInterestedIds) || null;
              });
            })()`);
            const vidsBefore = await cardVids();
            const initial = await feedState();
            await win.webContents.executeJavaScript("document.querySelector('.brand').click()");
            await new Promise((r) => setTimeout(r, 120));
            const during = await feedState();
            await new Promise((r) => setTimeout(r, 2600));
            const after = await feedState();
            const menu = await win.webContents.executeJavaScript(`(async () => {
              const card = document.querySelector('.card');
              const btn = card.querySelector('.icon-btn[title="More"]');
              const r = btn.getBoundingClientRect();
              btn.click();
              await new Promise((x) => setTimeout(x, 300));
              const m = document.querySelector('[role="menu"]');
              if (!m) return { open: false };
              const mr = m.getBoundingClientRect();
              return {
                open: true,
                vid: card.dataset.vid,
                inBody: m.parentElement === document.body,
                rightAligned: Math.abs(mr.right - r.right) < 20,
                gapBelow: Math.round(mr.top - r.bottom),
                items: [...m.querySelectorAll('button')].map((b) => b.textContent.trim()),
              };
            })()`);
            const menuVid = menu.open ? menu.vid : null;
            // Visual proof of the placement fix: the panel must sit under its
            // ⋮ trigger, not float over the card because of the grid animation.
            if (process.env.INDIOTUBE_SMOKE_SHOT) {
              try {
                const image = await win.webContents.capturePage();
                const p = process.env.INDIOTUBE_SMOKE_SHOT.replace(/\.png$/, '-menu.png');
                fs.writeFileSync(p, image.toPNG());
                console.log('[shot-menu]', p);
              } catch (e) { console.log('[shot-menu-fail]', e.message); }
            }
            const notInterested = await win.webContents.executeJavaScript(`(async () => {
              const m = document.querySelector('[role="menu"]');
              const target = m && [...m.querySelectorAll('button')].find((b) => b.textContent.includes('Not interested'));
              if (!target) return { clicked: false };
              const vid = ${JSON.stringify(menuVid)};
              target.click();
              await new Promise((x) => setTimeout(x, 600));
              const toastEl = document.querySelector('.toast');
              const vids = [...document.querySelectorAll('.card')].map((c) => c.dataset.vid);
              return {
                clicked: true,
                vid,
                cards: vids.length,
                present: vid ? vids.includes(vid) : null,
                ids: (await window.indio.library.get()).library.notInterestedIds,
                events: window.__libEvents,
                libIds: window.__libIds,
                toast: toastEl ? toastEl.textContent.trim().slice(0, 60) : null,
                hasUndo: Boolean(document.querySelector('.toast button')),
              };
            })()`);
            const undone = await win.webContents.executeJavaScript(`(async () => {
              const btn = document.querySelector('.toast button');
              if (!btn) return { undone: false };
              btn.click();
              await new Promise((x) => setTimeout(x, 600));
              const vids = [...document.querySelectorAll('.card')].map((c) => c.dataset.vid);
              const vid = ${JSON.stringify(menuVid)};
              return { undone: true, cards: vids.length, present: vid ? vids.includes(vid) : null };
            })()`);
            console.log('[feed]', JSON.stringify({ vidsBefore: vidsBefore.length, initial, during, after, menu, notInterested, undone }));

            // The remaining Android-parity actions must also reach the store
            // *and* repaint live (they all shared the dropped-broadcast bug).
            const watchedToggle = await win.webContents.executeJavaScript(`(async () => {
              const wait = (ms) => new Promise((r) => setTimeout(r, ms));
              const card = document.querySelector('.card');
              const vid = card.dataset.vid;
              const openMenu = async () => {
                card.querySelector('.icon-btn[title="More"]').click();
                await wait(250);
                return document.querySelector('[role="menu"]');
              };
              const closeMenu = async () => {
                window.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                await wait(200);
              };
              const label = (m) => ((m && m.querySelector('button')) || {}).textContent || '';
              let m = await openMenu();
              if (!m) return { error: 'menu did not open' };
              const before = label(m).trim();
              m.querySelector('button').click();
              await wait(500);
              const toastEl = document.querySelector('.toast');
              m = await openMenu();
              const after = label(m).trim();
              await closeMenu();
              // Leave the library as we found it.
              await window.indio.library.op('removeFromHistory', vid);
              const state = await window.indio.library.get();
              return {
                vid,
                before,
                after,
                toast: toastEl ? toastEl.textContent.trim().slice(0, 40) : null,
                historyHas: state.library.history.some((e) => e.video.videoId === vid),
              };
            })()`);
            const blockedBefore = await win.webContents.executeJavaScript(
              `(async () => (await window.indio.library.get()).library.blockedChannelIds)()`);
            const blocked = await win.webContents.executeJavaScript(`(async () => {
              const wait = (ms) => new Promise((r) => setTimeout(r, ms));
              const card = document.querySelector('.card');
              const vid = card.dataset.vid;
              card.querySelector('.icon-btn[title="More"]').click();
              await wait(250);
              const m = document.querySelector('[role="menu"]');
              const target = m && [...m.querySelectorAll('button')]
                .find((b) => b.textContent.includes("Don't recommend channel"));
              if (!target) return { clicked: false };
              target.click();
              await wait(600);
              window.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
              const vids = [...document.querySelectorAll('.card')].map((c) => c.dataset.vid);
              const state = await window.indio.library.get();
              return {
                clicked: true,
                cards: vids.length,
                present: vids.includes(vid),
                blocked: state.library.blockedChannelIds,
              };
            })()`);
            // Undo only the channel this probe blocked, so the real profile
            // is left exactly as it was.
            const unblocked = await win.webContents.executeJavaScript(`(async () => {
              const before = ${JSON.stringify(blockedBefore)};
              const now = (await window.indio.library.get()).library.blockedChannelIds;
              const added = now.filter((c) => !before.includes(c));
              for (const c of added) await window.indio.library.op('unblockChannel', c);
              await new Promise((r) => setTimeout(r, 400));
              return { added, cards: document.querySelectorAll('.card').length };
            })()`);
            console.log('[feed-menu]', JSON.stringify({ watchedToggle, blockedBefore: blockedBefore.length, blocked, unblocked }));
          }
          const shot = process.env.INDIOTUBE_SMOKE_SHOT;
          if (shot) {
            if (process.env.INDIOTUBE_SMOKE_FS === '1') {
              win.setFullScreen(true);
              await new Promise((r) => setTimeout(r, 900));
            }
            const image = await win.webContents.capturePage();
            fs.writeFileSync(shot, image.toPNG());
            console.log('[shot]', shot);
          }
        } catch (e) {
          console.log('[smoke-fail]', e.message);
        }
        app.quit();
      }, process.env.INDIOTUBE_SMOKE_FEED === '1' ? 18000
        : process.env.INDIOTUBE_SMOKE_ROUTE ? 14000 : 6500);
    });
  }
}

app.whenReady().then(async () => {
  loadSettings();
  store = library.createStore(app.getPath('userData'));
  store.onChange((state) => broadcast('lib:changed', state));

  const ytdlpPath = resolveYtdlp();
  streams = createStreams({
    ytdlpPath: ytdlpPath || 'yt-dlp.exe',
    ffmpegPath: resolveFfmpeg(),
    downloadDir: settings.downloadDir,
    cacheFile: path.join(app.getPath('userData'), 'streams-cache.json'),
    queueFile: path.join(app.getPath('userData'), 'downloads.json'),
    onDownloadEvent: (evt) => broadcast('download:event', evt),
  });
  await streams.startProxy();
  // Pick up downloads that were queued when the app last closed.
  streams.pumpDownloads();
  if (!ytdlpPath) {
    console.warn('[indiotube] yt-dlp.exe not found - run `npm run fetch:ytdlp`. Playback will report a helpful error.');
  }

  registerIpc();
  await createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (syncState) syncState.stop();
  app.quit();
});

process.on('exit', () => {
  try { store && store.flush(); } catch { /* best effort */ }
});
