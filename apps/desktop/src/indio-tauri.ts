// The Tauri counterpart of electron/preload.js.
//
// Under the Electron shell `window.indio` is injected by the preload script and
// this module does nothing. Under the Tauri shell there is no preload, so the
// same object is built here out of `invoke` calls; the renderer keeps calling
// `window.indio.*` and never learns which shell it is running in.
import { invoke } from '@tauri-apps/api/core';

const isTauri =
  typeof window !== 'undefined' &&
  ('__TAURI_INTERNALS__' in window || '__TAURI__' in window);

type Args = Record<string, unknown>;

async function call<T>(cmd: string, args?: Args): Promise<T> {
  return invoke<T>(cmd, args);
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

  const noEvents = (_cb: (data: unknown) => void) => () => undefined;

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
      playUrl: async (videoId: string, height?: number | null) =>
        go<{ video: string; audio: string | null; height: number; label: string; videoCodec: string | null; audioCodec: string | null }>(
          'play_url',
          { videoId, height: height ?? null },
        ),
      subtitleUrl: async (videoId: string, lang?: string) =>
        (await go<{ url: string | null }>('subtitle_url', { videoId, lang: lang ?? '' })).url,
      localUrl: async (file: string) => (await go<{ url: string }>('local_url', { file })).url,
      ffmpeg: async () => (await go<{ available: boolean }>('ffmpeg')).available,
    },

    // Not ported yet: the commands answer with a plain reason, which is what the
    // screens surface instead of a silent no-op.
    downloads: {
      start: async (video: unknown, height?: number | null) =>
        go('downloads_start', { video, height: height ?? null }),
      cancel: async (videoId: string) => go('downloads_cancel', { videoId }),
      list: async () => (await go<{ tasks: unknown[] }>('downloads_list')).tasks,
      pause: async (videoId: string) => go('downloads_pause', { videoId }),
      resume: async (videoId: string) => go('downloads_resume', { videoId }),
      pickDir: async () => go<{ dir: string }>('downloads_pick_dir'),
      reveal: async (file: string) => go('downloads_reveal', { file }),
      onEvent: noEvents,
    },

    sponsorBlock: {
      segments: async (videoId: string, categories?: string[]) =>
        (await go<{ data: unknown }>('sponsor_segments', { videoId, categories: categories ?? [] })).data,
    },

    library: {
      get: async () => (await go<{ state: unknown }>('library_get')).state,
      op: async (name: string, ...args: unknown[]) => go('library_op', { op: name, args }),
      onChanged: noEvents,
    },

    settings: {
      get: async () => (await go<{ settings: unknown }>('settings_get')).settings,
      set: async (patch: unknown) => (await go<{ settings: unknown }>('settings_set', { patch })).settings,
    },

    sync: {
      start: async () => go('sync_start'),
      stop: async () => go('sync_stop'),
      onEvent: noEvents,
    },

    window: {
      setMini: async (on: boolean) => (await go<{ mini: boolean }>('win_mini', { on })).mini,
      setFullscreen: async (on: boolean) =>
        (await go<{ fullscreen: boolean }>('win_fullscreen', { on })).fullscreen,
      isFullscreen: async () => (await go<{ fullscreen: boolean }>('win_is_fullscreen')).fullscreen,
      onFullscreen: noEvents,
    },

    app: {
      version: async () => (await go<{ version: string }>('app_version')).version,
    },
  };

  (window as unknown as { indio: typeof api }).indio = api;
}
