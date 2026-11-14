// `window.indio`, built out of Tauri `invoke` calls.
//
// The renderer only ever talks to this object, so the whole native surface —
// feeds, playback, downloads, library, sync, window modes — lives here and in
// `src-tauri`. It is installed as soon as `__TAURI_INTERNALS__` is present, and
// the app never learns which shell it is running in because there is only one.
import { invoke } from '@tauri-apps/api/core';
import { friendlyError } from './errors';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';

export function isTauriShell(): boolean {
  return (
    typeof window !== 'undefined' &&
    ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
  );
}

const isTauri = isTauriShell();

type Args = Record<string, unknown>;

async function call<T>(cmd: string, args?: Args): Promise<T> {
  try {
    return await invoke<T>(cmd, args);
  } catch (e) {
    // Every screen and toast shows this message, so it must be a sentence, not
    // a raw transport dump. The shell already retried connection failures.
    throw new Error(friendlyError(e));
  }
}

function detectLocale(): { lang: string; country: string } {
  const locale = (typeof navigator !== 'undefined' && navigator.language) || 'en-US';
  const lang = locale.split('-')[0] || 'en';
  let country = 'US';
  try {
    const options = Intl.DateTimeFormat().resolvedOptions();
    const timeZone = options.timeZone || '';
    if (timeZone.split('/')[0] !== 'UTC') {
      const region = (options as unknown as { region?: string }).region;
      if (typeof region === 'string' && region.length === 2) country = region.toUpperCase();
    }
  } catch {
    /* keep US */
  }
  return { lang, country };
}

if (isTauri && !(window as unknown as { indio?: unknown }).indio) {
  const { lang, country } = detectLocale();
  // Every call waits for this: the client version and the request context
  // depend on the locale the renderer reports.
  const ready = call('indio_init', { lang, country }).catch(() => undefined);
  const go = async <T,>(cmd: string, args?: Args): Promise<T> => {
    await ready;
    return call<T>(cmd, args);
  };

  // Electron's preload returned a plain unsubscribe from every `onX(cb)`;
  // Tauri events are async, so the subscription is started in the background
  // and cancelling before it resolves still detaches. Screens register
  // listeners once on mount.
  const on = (event: string, cb: (data: unknown) => void) => {
    let cancelled = false;
    let unlisten: UnlistenFn | null = null;
    listen(event, (payload) => cb(payload.payload))
      .then((fn) => {
        if (cancelled) fn();
        else unlisten = fn;
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (unlisten) unlisten();
    };
  };

  const api = {
    home: async (p: Args = {}) => (await go<{ feed: unknown }>('home', p)).feed,
    homeMore: async (p: Args = {}) => (await go<{ videos: unknown[] }>('homeMore', p)).videos,
    homeCont: async (token: string) => (await go<{ feed: unknown }>('home_cont', { token })).feed,
    trending: async (p: Args = {}) => (await go<{ feed: unknown }>('trending', p)).feed,
    trendingCont: async (token: string) => (await go<{ feed: unknown }>('trending_cont', { token })).feed,
    search: async (query: string, filters?: unknown) =>
      (await go<{ results: unknown }>('search', { query, filters })).results,
    searchCont: async (token: string) => (await go<{ results: unknown }>('search_cont', { token })).results,
    suggest: async (query: string) => (await go<{ suggestions: string[] }>('suggest', { query })).suggestions,
    channel: async (channelId: string, params?: string | null) =>
      (await go<{ page: unknown }>('channel', { channelId, params })).page,
    channelCont: async (token: string, channelId: string) =>
      (await go<{ page: unknown }>('channel_cont', { token, channelId })).page,
    channelLive: async (channelId: string) => (await go<{ page: unknown }>('channel_live', { channelId })).page,
    channelPlaylists: async (channelId: string) =>
      (await go<{ page: unknown }>('channel_playlists', { channelId })).page,
    channelPlaylistsCont: async (token: string) =>
      (await go<{ page: unknown }>('channel_playlists_cont', { token })).page,
    channelAbout: async (channelId: string, token?: string | null) =>
      (await go<{ about: unknown }>('channel_about', { channelId, token })).about,
    watch: async (videoId: string) => (await go<{ data: unknown }>('watch', { videoId })).data,
    watchMore: async (token: string) => (await go<{ page: unknown }>('watch_more', { token })).page,
    comments: async (token: string) => (await go<{ page: unknown }>('comments', { token })).page,
    playlist: async (playlistId: string) => (await go<{ page: unknown }>('playlist', { playlistId })).page,
    playlistCont: async (token: string) => (await go<{ page: unknown }>('playlist_cont', { token })).page,

    streams: {
      qualities: async (videoId: string, force?: boolean) =>
        (await go<{ qualities: unknown }>('qualities', { videoId, force: Boolean(force) })).qualities,
      playUrl: async (videoId: string, height?: number | null, fps?: number | null) =>
        go<{ video: string; audio: string | null; height: number; label: string; videoCodec: string | null; audioCodec: string | null }>(
          'play_url',
          { videoId, height: height ?? null, fps: fps ?? null },
        ),
      subtitleUrl: async (videoId: string, lang?: string) =>
        (await go<{ url: string | null }>('subtitle_url', { videoId, lang: lang ?? '' })).url,
      /** Tell the shell which videos are on screen, so it can have their
       *  streams ready if it gets a quiet moment. Never waits, never fails. */
      warm: (videoIds: string[]) => {
        const ids = videoIds.filter(Boolean);
        if (ids.length) void go('warm_streams', { videoIds: ids }).catch(() => undefined);
        return ids.length;
      },
      localUrl: async (file: string) => (await go<{ url: string }>('local_url', { file })).url,
      ffmpeg: async () => (await go<{ available: boolean }>('ffmpeg')).available,
    },

    downloads: {
      start: async (video: unknown, height?: number | null) =>
        go('downloads_start', { video, height: height ?? null }),
      cancel: async (videoId: string) => go('downloads_cancel', { videoId }),
      list: async () => (await go<{ tasks: unknown[] }>('downloads_list')).tasks,
      pause: async (videoId: string) => go('downloads_pause', { videoId }),
      resume: async (videoId: string) => go('downloads_resume', { videoId }),
      pickDir: async () => go<{ dir: string }>('downloads_pick_dir'),
      reveal: async (file: string) => go('downloads_reveal', { file }),
      onEvent: (cb: (data: unknown) => void) => on('download:event', cb),
    },

    sponsorBlock: {
      segments: async (videoId: string, categories?: string[]) =>
        (await go<{ data: unknown }>('sponsor_segments', { videoId, categories: categories ?? [] })).data,
    },

    library: {
      get: async () => (await go<{ state: unknown }>('library_get')).state,
      op: async (name: string, ...args: unknown[]) => go('library_op', { op: name, args }),
      onChanged: (cb: (data: unknown) => void) => on('lib:changed', cb),
    },

    settings: {
      get: async () => (await go<{ settings: unknown }>('settings_get')).settings,
      set: async (patch: unknown) => (await go<{ settings: unknown }>('settings_set', { patch })).settings,
    },

    sync: {
      start: async () => {
        const res = await go<{ payload: Record<string, unknown>; ips: string[]; port: number }>('sync_start');
        return { payload: res.payload, ips: res.ips, port: res.port };
      },
      stop: async () => go('sync_stop'),
      onEvent: (cb: (data: unknown) => void) => on('sync:event', cb),
    },

    window: {
      setMini: async (on: boolean) => (await go<{ mini: boolean }>('win_mini', { on })).mini,
      setFullscreen: async (on: boolean) =>
        (await go<{ fullscreen: boolean }>('win_fullscreen', { on })).fullscreen,
      isFullscreen: async () => (await go<{ fullscreen: boolean }>('win_is_fullscreen')).fullscreen,
      onFullscreen: (cb: (data: unknown) => void) => on('win:fullscreen-changed', cb),
    },

    app: {
      version: async () => (await go<{ version: string }>('app_version')).version,
    },
  };

  (window as unknown as { indio: typeof api }).indio = api;
  void runSmoke();
}

type SmokeConfig = {
  enabled: boolean;
  route: string;
  media: boolean;
  window: boolean;
  /** Fade/wake the controls and open Stats for nerds in the real window. */
  ui?: boolean;
  click?: boolean;
  /** Drive the top bar's Back/Forward buttons through real clicks. */
  nav?: boolean;
  /** Click a search row's empty side and its channel line. */
  searchClick?: boolean;
  waitMs: number;
};

/** Sample both media clocks, the way the release smoke run used to. */
async function probeMedia(): Promise<unknown> {
  const snap = () => {
    const v = document.querySelector('video');
    const a = document.querySelector('audio');
    return {
      t: v ? Math.round(v.currentTime * 10) / 10 : null,
      ready: v ? v.readyState : null,
      paused: v ? v.paused : null,
      at: a ? Math.round(a.currentTime * 10) / 10 : null,
      muted: a ? a.muted : null,
      seeking: a ? a.seeking : null,
      aready: a ? a.readyState : null,
    };
  };
  const samples = [snap()];
  for (let i = 0; i < 4; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 800));
    samples.push(snap());
  }
  return { samples };
}

/**
 * The mini player and windowed fullscreen, driven through the bridge: both are
 * shell-side window modes, so a stale payload shape shows up here rather than
 * in a manual click-through.
 */
async function probeWindow(): Promise<unknown> {
  const indio = (window as unknown as { indio?: any }).indio;
  if (!indio) return { skipped: 'no bridge' };
  // The shell-side fullscreen event carries a plain boolean, so anything else
  // arriving here is a payload shape the renderer would silently misread.
  let fullscreenEvent: unknown = 'never fired';
  const off = indio.window.onFullscreen((on: boolean) => {
    fullscreenEvent = typeof on === 'boolean' ? on : `not a boolean: ${JSON.stringify(on)}`;
  });
  const before = { fullscreen: await indio.window.isFullscreen().catch(() => null) };

  await indio.window.setFullscreen(true).catch((e: unknown) => String(e));
  await new Promise((resolve) => setTimeout(resolve, 900));
  const inFullscreen = await indio.window.isFullscreen().catch(() => null);
  await indio.window.setFullscreen(false).catch(() => undefined);
  await new Promise((resolve) => setTimeout(resolve, 900));

  // Mini player: the shell remembers the pre-mini geometry, shrinks the window
  // and always-on-tops it, then puts it back exactly where it was.
  const setMini = await indio.window.setMini(true).catch((e: unknown) => String(e));
  await new Promise((resolve) => setTimeout(resolve, 900));
  const inMini = { size: [window.innerWidth, window.innerHeight] };
  await indio.window.setMini(false).catch(() => undefined);
  await new Promise((resolve) => setTimeout(resolve, 900));
  off();
  return {
    before,
    fullscreenEvent,
    inFullscreen,
    afterFullscreen: await indio.window.isFullscreen().catch(() => null),
    setMini,
    inMini,
    after: { size: [window.innerWidth, window.innerHeight] },
  };
}

/**
 * The player chrome: it must fade after a few idle seconds of playback, wake
 * on pointer movement, and the settings menu must actually open the Stats for
 * nerds panel. Everything here goes through the same DOM a viewer uses, so a
 * broken CSS class or menu row shows up in the report instead of in a screenshot
 * someone forgets to take.
 */
async function probePlayerUi(): Promise<unknown> {
  const frame = document.querySelector('.player-frame') as HTMLElement | null;
  const controls = document.querySelector('.controls') as HTMLElement | null;
  if (!frame || !controls) return { skipped: 'no player' };
  const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

  await wait(3_600);
  const hiddenAfterIdle = !controls.classList.contains('visible');
  frame.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }));
  await wait(250);
  const shownAfterMove = controls.classList.contains('visible');

  let stats: string | null = null;
  let menuOpened = false;
  const gear = controls.querySelector('button[title="Settings"]') as HTMLElement | null;
  if (gear) {
    gear.click();
    await wait(350);
    menuOpened = Boolean(document.querySelector('.player-menu'));
    const row = Array.from(document.querySelectorAll('.ps-row'))
      .find((el) => (el.textContent || '').includes('Stats for nerds')) as HTMLElement | null;
    row?.click();
    await wait(800);
    const panel = document.querySelector('[data-testid="stats-for-nerds"]');
    stats = panel ? (panel.textContent || '').replace(/\s+/g, ' ').slice(0, 360) : null;
  }
  return { hiddenAfterIdle, shownAfterMove, menuOpened, stats };
}

/**
 * A search result is one big video row with a small channel line inside it.
 * This drives the real DOM: the far side of the channel line must open the
 * video, and only the avatar/name may open the channel. A regression in the
 * hit areas shows up here instead of as a misclick in a screenshot.
 */
function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function until<T>(find: () => T | null, timeoutMs = 10_000): Promise<T | null> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const found = find();
    if (found) return found;
    if (Date.now() >= deadline) return null;
    await wait(100);
  }
}

type SearchClickProbe = {
  skipped?: string;
  failures: string[];
  rowWidth?: number;
  linkWidth?: number;
  emptySideIsChannel?: boolean;
  reloadMs?: number;
  start?: string;
  emptySideHash?: string;
  channelHash?: string;
  restoredHash?: string;
};

async function probeSearchClick(): Promise<SearchClickProbe> {
  const videoRow = await until<HTMLElement>(() =>
    (Array.from(document.querySelectorAll('.list-row'))
      .find((row) => row.querySelector('.channel-link')) as HTMLElement | undefined) ?? null);
  if (!videoRow) {
    return { skipped: 'no video result appeared', failures: ['no video result appeared on the search page'] };
  }
  const failures: string[] = [];
  const link = videoRow.querySelector('.channel-link') as HTMLElement;
  videoRow.scrollIntoView({ block: 'center' });
  await wait(150);

  const rowRect = videoRow.getBoundingClientRect();
  const linkRect = link.getBoundingClientRect();
  const y = linkRect.top + linkRect.height / 2;
  // Well to the right of the name but still on the channel line: the space
  // that used to be part of the channel link.
  const x = Math.max(linkRect.right + 24, (linkRect.right + rowRect.right) / 2);
  const hit = document.elementFromPoint(x, y) as HTMLElement | null;
  const emptySideIsChannel = Boolean(hit && (hit === link || link.contains(hit)));

  const start = location.hash;
  (hit ?? videoRow).click();
  await wait(400);
  const emptySideHash = location.hash;
  if (!emptySideHash.startsWith('#/watch/')) {
    failures.push(`the empty side of the channel line opened ${emptySideHash || 'nothing'} instead of the video`);
  }

  history.back();
  const backAt = Date.now();
  const linkAfterBack = await until<HTMLElement>(() =>
    document.querySelector('.list-row .channel-link') as HTMLElement | null, 25_000);
  const reloadMs = Date.now() - backAt;
  if (!linkAfterBack) {
    failures.push('the search page did not come back after going back from the video');
  } else {
    linkAfterBack.click();
    await wait(400);
  }
  const channelHash = location.hash;
  if (!channelHash.startsWith('#/channel/')) {
    failures.push(`clicking the channel name opened ${channelHash || 'nothing'} instead of the channel`);
  }

  history.back();
  const restored = await until<boolean>(() => location.hash === start, 8_000);
  if (!restored) failures.push(`the search page did not return to ${start}`);
  return {
    failures,
    rowWidth: Math.round(rowRect.width),
    linkWidth: Math.round(linkRect.width),
    emptySideIsChannel,
    reloadMs,
    start,
    emptySideHash,
    channelHash,
    restoredHash: location.hash,
  };
}

type NavigationProbe = {
  skipped?: string;
  failures: string[];
  start?: string;
  backInitiallyDisabled?: boolean;
  afterNav?: { hash: string; backEnabled: boolean; forwardEnabled: boolean };
  afterBack?: { hash: string; backEnabled: boolean; forwardEnabled: boolean };
  afterForward?: { hash: string; backEnabled: boolean; forwardEnabled: boolean };
};

/**
 * The top bar's Back/Forward buttons, clicked exactly as a viewer would. The
 * browser does not expose history availability, so this is the only place the
 * router's index/top model meets real traversal.
 */
async function probeNavigation(): Promise<NavigationProbe> {
  const back = await until<HTMLButtonElement>(() =>
    document.querySelector('button[title="Back"]') as HTMLButtonElement | null, 3_000);
  const forward = await until<HTMLButtonElement>(() =>
    document.querySelector('button[title="Forward"]') as HTMLButtonElement | null, 3_000);
  if (!back || !forward) return { skipped: 'the nav buttons are not in the top bar', failures: [] };

  const failures: string[] = [];
  const start = location.hash;
  const backInitiallyDisabled = back.disabled;
  if (!backInitiallyDisabled) failures.push('Back was enabled before anything had been opened');

  const trending = Array.from(document.querySelectorAll('.sidenav .item'))
    .find((el) => (el.textContent || '').trim().toLowerCase().startsWith('trending')) as HTMLElement | undefined;
  if (!trending) {
    // Opening a video collapses the guide, and it stays collapsed after going
    // back; the hamburger is how a viewer brings it back.
    const menu = document.querySelector('.tb-left .icon-btn') as HTMLElement | null;
    menu?.click();
    await wait(300);
  }
  const item = Array.from(document.querySelectorAll('.sidenav .item'))
    .find((el) => (el.textContent || '').trim().toLowerCase().startsWith('trending')) as HTMLElement | undefined;
  if (!item) return { skipped: 'the side navigation is not open', failures };

  item.click();
  await wait(500);
  const afterNav = { hash: location.hash, backEnabled: !back.disabled, forwardEnabled: !forward.disabled };
  if (afterNav.hash !== '#/trending') failures.push(`Trending opened ${afterNav.hash || 'nothing'}`);
  if (!afterNav.backEnabled) failures.push('Back stayed disabled after opening Trending');
  if (afterNav.forwardEnabled) failures.push('Forward was enabled on the newest page');

  back.click();
  await wait(600);
  const afterBack = { hash: location.hash, backEnabled: !back.disabled, forwardEnabled: !forward.disabled };
  if (afterBack.hash !== start) failures.push(`Back opened ${afterBack.hash || 'nothing'} instead of ${start}`);
  if (!afterBack.forwardEnabled) failures.push('Forward stayed disabled after going back');

  forward.click();
  await wait(600);
  const afterForward = { hash: location.hash, backEnabled: !back.disabled, forwardEnabled: !forward.disabled };
  if (afterForward.hash !== '#/trending') failures.push(`Forward opened ${afterForward.hash || 'nothing'} instead of Trending`);

  // Leave the app on the page the probe started from.
  back.click();
  await wait(400);
  return {
    failures,
    start,
    backInitiallyDisabled,
    afterNav,
    afterBack,
    afterForward,
  };
}

/**
 * When the parts the viewer waits for actually appear. `cardsAt` and `clickedAt`
 * count from the page loading; `titleAt`, `playerAt` and `frameAt` count from
 * the click, because "how long after I picked a video did it start?" is the
 * number that decides whether the app feels fast. Measured instead of eyeballed
 * so a release can be compared against the last one.
 */
const firsts: {
  cardsAt: number | null;
  clickedAt: number | null;
  titleAt: number | null;
  playerAt: number | null;
  frameAt: number | null;
} = { cardsAt: null, clickedAt: null, titleAt: null, playerAt: null, frameAt: null };

// What the numbers above count from: 0 until a click resets it.
let clickMark = 0;

function watchFirsts(): number {
  return window.setInterval(() => {
    const now = performance.now();
    if (firsts.cardsAt === null && document.querySelector('.card')) firsts.cardsAt = Math.round(now);
    const layer = document.querySelector('.playback-layer');
    if (
      firsts.playerAt === null
      && layer
      && getComputedStyle(layer).display !== 'none'
    ) {
      firsts.playerAt = Math.round(now - clickMark);
    }
    if (firsts.titleAt === null && document.querySelector('.watch-title')) {
      firsts.titleAt = Math.round(now - clickMark);
    }
    const v = document.querySelector('video') as HTMLVideoElement | null;
    if (firsts.frameAt === null && v && (v.readyState >= 2 || v.currentTime > 0)) {
      firsts.frameAt = Math.round(now - clickMark);
    }
  }, 100);
}

/** Click the first card on screen, the way a viewer does. */
async function clickFirstCard(): Promise<boolean> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const card = document.querySelector('.card') as HTMLElement | null;
    if (card) {
      clickMark = performance.now();
      firsts.clickedAt = Math.round(clickMark);
      card.click();
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return false;
}

/**
 * The dev smoke harness (`INDIOTUBE_SMOKE=1`): open a route, let the UI settle,
 * and send the shell a report of what was actually painted. The shell prints it
 * and exits, so a release can be checked without clicking.
 */
async function runSmoke(): Promise<void> {
  if (typeof document === 'undefined') return;
  let config: SmokeConfig;
  try {
    config = await invoke<SmokeConfig>('smoke_config');
  } catch (e) {
    console.warn('[smoke] the shell has no harness: ' + String(e));
    return; // the shell is not running with the harness armed
  }
  if (!config || !config.enabled) return;
  console.log('[smoke] armed in the renderer: ' + JSON.stringify(config));
  // index.html collects anything thrown before the module scripts ran.
  const early = (window as unknown as { __smokeErrors?: string[] }).__smokeErrors || [];
  const errors: string[] = [...early];
  window.addEventListener('error', (event) => errors.push(String(event.message)));
  window.addEventListener('unhandledrejection', (event) =>
    errors.push(String((event as PromiseRejectionEvent).reason)));
  if (config.route) {
    location.hash = `#/${config.route.replace(/^#?\/?/, '')}`;
  }
  const watch = watchFirsts();
  // `INDIOTUBE_SMOKE_CLICK=1` turns the run into the thing the viewer actually
  // complains about: open the app, click a video, time what happens.
  const clicked = config.click ? await clickFirstCard() : false;
  await new Promise((resolve) => setTimeout(resolve, config.waitMs || 8000));
  window.clearInterval(watch);
  const content = document.querySelector('.content');
  const media = config.media ? await probeMedia() : null;
  const modes = config.window ? await probeWindow() : null;
  const playerUi = config.ui ? await probePlayerUi() : null;
  const searchClick = config.searchClick ? await probeSearchClick() : null;
  const navigation = config.nav ? await probeNavigation() : null;
  if (searchClick) errors.push(...searchClick.failures.map((f) => `search click: ${f}`));
  if (navigation) errors.push(...navigation.failures.map((f) => `navigation: ${f}`));
  const report = {
    title: document.title,
    hash: location.hash,
    nodes: document.querySelectorAll('*').length,
    skeletons: document.querySelectorAll('.sk').length,
    cards: document.querySelectorAll('.card').length,
    contentScrollable: content ? content.scrollHeight > content.clientHeight + 4 : null,
    text: (document.body && document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 220),
    // The startup screen in index.html must be gone once React painted, or the
    // window is showing a placeholder instead of the app.
    boot: {
      present: Boolean(document.getElementById('boot')),
      visible: (() => {
        const el = document.getElementById('boot');
        return el ? getComputedStyle(el).display !== 'none' : null;
      })(),
      rootChildren: document.getElementById('root')?.childElementCount ?? 0,
    },
    errors,
    firsts,
    clicked,
    ...(media ? { media } : {}),
    ...(modes ? { modes } : {}),
    ...(playerUi ? { playerUi } : {}),
    ...(searchClick ? { searchClick } : {}),
    ...(navigation ? { navigation } : {}),
  };
  try {
    await invoke('smoke_report', { report });
  } catch (e) {
    console.log('[smoke] ' + String(e));
    console.log('[smoke]', JSON.stringify(report));
  }
}
