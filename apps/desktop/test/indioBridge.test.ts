// The renderer ↔ Rust-shell contract.
//
// The React app only ever calls `window.indio.*`; `src/indio-tauri.ts` turns
// each of those calls into a Tauri `invoke` and unwraps the reply envelope the
// Rust commands return. This test pins both halves of that seam — the command
// names as spelled in `src-tauri/src/main.rs`'s handler list, and which key of
// the reply the renderer gets back — because a mismatch here is invisible to
// the type checker and shows up as a screen that silently renders nothing.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const tauri = vi.hoisted(() => {
  type Handler = (args: Record<string, unknown> | undefined) => unknown;
  const handlers = new Map<string, Handler>();
  const calls: { cmd: string; args?: Record<string, unknown> }[] = [];
  const listeners = new Map<string, (data: unknown) => void>();
  return {
    handlers,
    calls,
    listeners,
    on(cmd: string, handler: Handler) {
      handlers.set(cmd, handler);
    },
    invoke(cmd: string, args?: Record<string, unknown>) {
      calls.push({ cmd, args });
      const handler = handlers.get(cmd);
      if (!handler) return Promise.reject(new Error(`no Rust command wired for ${cmd}`));
      return Promise.resolve(handler(args));
    },
    listen(event: string, cb: (payload: { payload: unknown }) => void) {
      listeners.set(event, (data) => cb({ payload: data }));
      return Promise.resolve(() => listeners.delete(event));
    },
    emit(event: string, data: unknown) {
      listeners.get(event)?.(data);
    },
  };
});

vi.mock('@tauri-apps/api/core', () => ({ invoke: tauri.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: tauri.listen }));

async function loadBridge() {
  const win: Record<string, unknown> = { __TAURI_INTERNALS__: {} };
  (globalThis as unknown as { window: unknown }).window = win;
  Object.defineProperty(globalThis, 'navigator', { value: { language: 'en-GB' }, configurable: true });
  vi.resetModules();
  await import('../src/indio-tauri');
  return (win as unknown as { indio: import('../src/types').IndioBridge }).indio;
}

beforeEach(() => {
  tauri.calls.length = 0;
  tauri.handlers.clear();
  tauri.listeners.clear();
});

describe('window.indio over Tauri invoke', () => {
  it('initialises the core with the renderer locale before the first call', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('app_version', () => ({ version: '0.1.38' }));
    const indio = await loadBridge();
    await indio.app.version();
    const init = tauri.calls.find((call) => call.cmd === 'indio_init');
    expect(init).toBeTruthy();
    // The language comes from the page; the country is the two-letter region
    // the shell will send as InnerTube's `gl`.
    expect(init!.args).toMatchObject({ lang: 'en' });
    expect(String(init!.args!.country)).toMatch(/^[A-Z]{2}$/);
    // …and it is the first thing the shell is asked to do.
    expect(tauri.calls[0].cmd).toBe('indio_init');
  }, 10_000);

  it('unwraps every browsing envelope the Rust commands return', async () => {
    const feed = { chips: [], videos: [{ videoId: 'v1' }], continuation: null };
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('home', () => ({ feed }));
    tauri.on('home_cont', () => ({ feed }));
    tauri.on('homeMore', () => ({ videos: feed.videos }));
    tauri.on('trending', () => ({ feed }));
    tauri.on('trending_cont', () => ({ feed }));
    tauri.on('search', () => ({ results: { entries: [], continuation: null, restricted: false } }));
    tauri.on('search_cont', () => ({ results: { entries: [], continuation: null, restricted: false } }));
    tauri.on('suggest', () => ({ suggestions: ['one', 'two'] }));
    tauri.on('channel', () => ({ page: { channel: { channelId: 'UC1' } } }));
    tauri.on('channel_cont', () => ({ page: { videos: [] } }));
    tauri.on('channel_live', () => ({ page: { videos: [] } }));
    tauri.on('channel_playlists', () => ({ page: { playlists: [] } }));
    tauri.on('channel_playlists_cont', () => ({ page: { playlists: [] } }));
    tauri.on('channel_about', () => ({ about: { country: 'US' } }));
    tauri.on('watch', () => ({ data: { videoId: 'v1', title: 'T' } }));
    tauri.on('watch_more', () => ({ page: { related: [] } }));
    tauri.on('comments', () => ({ page: { comments: [] } }));
    tauri.on('playlist', () => ({ page: { title: 'P' } }));
    tauri.on('playlist_cont', () => ({ page: { title: 'P' } }));
    tauri.on('app_version', () => ({ version: '0.1.38' }));
    const indio = await loadBridge();

    expect(await indio.home({ force: true })).toEqual(feed);
    expect(tauri.calls.at(-1)).toEqual({ cmd: 'home', args: { force: true } });
    expect(await indio.homeCont('tok')).toEqual(feed);
    expect(tauri.calls.at(-1)!.args).toEqual({ token: 'tok' });
    expect(await indio.homeMore({ excludeIds: ['v1'] })).toEqual(feed.videos);
    expect(await indio.trending({})).toEqual(feed);
    expect(await indio.trendingCont('tok')).toEqual(feed);
    expect(await indio.search('cats', { type: 1 })).toEqual({
      entries: [],
      continuation: null,
      restricted: false,
    });
    expect(tauri.calls.at(-1)!.args).toEqual({ query: 'cats', filters: { type: 1 } });
    expect(await indio.searchCont('tok')).toEqual({ entries: [], continuation: null, restricted: false });
    expect(await indio.suggest('ru')).toEqual(['one', 'two']);
    expect((await indio.channel('UC1', 'params')).channel.channelId).toBe('UC1');
    expect(tauri.calls.at(-1)!.args).toEqual({ channelId: 'UC1', params: 'params' });
    expect((await indio.channelCont('tok', 'UC1')).videos).toEqual([]);
    expect((await indio.channelLive('UC1')).videos).toEqual([]);
    expect((await indio.channelPlaylists('UC1')).playlists).toEqual([]);
    expect((await indio.channelPlaylistsCont('tok')).playlists).toEqual([]);
    expect(await indio.channelAbout('UC1')).toEqual({ country: 'US' });
    expect((await indio.watch('v1')).videoId).toBe('v1');
    expect((await indio.watchMore('tok')).related).toEqual([]);
    expect((await indio.comments('tok')).comments).toEqual([]);
    expect((await indio.playlist('PL1')).title).toBe('P');
    expect((await indio.playlistCont('tok')).title).toBe('P');
    expect(await indio.app.version()).toBe('0.1.38');
  });

  it('hands the player proxy URLs and the codecs the MSE track needs', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('qualities', () => ({ qualities: { options: [], autoHeight: 720 } }));
    tauri.on('play_url', () => ({
      video: 'http://127.0.0.1:1/v?t=1',
      audio: null,
      height: 720,
      label: '720p',
      videoCodec: 'avc1.4d401f',
      audioCodec: null,
    }));
    tauri.on('subtitle_url', () => ({ url: 'http://127.0.0.1:1/subs?t=2&ext=1' }));
    tauri.on('local_url', () => ({ url: 'http://127.0.0.1:1/file?p=x' }));
    tauri.on('ffmpeg', () => ({ available: true }));
    const indio = await loadBridge();

    expect(await indio.streams.qualities('v1', true)).toEqual({ options: [], autoHeight: 720 });
    expect(tauri.calls.at(-1)!.args).toEqual({ videoId: 'v1', force: true });
    expect(await indio.streams.playUrl('v1', null)).toEqual({
      video: 'http://127.0.0.1:1/v?t=1',
      audio: null,
      height: 720,
      label: '720p',
      videoCodec: 'avc1.4d401f',
      audioCodec: null,
    });
    expect(tauri.calls.at(-1)!.args).toEqual({ videoId: 'v1', height: null });
    expect(await indio.streams.subtitleUrl('v1')).toBe('http://127.0.0.1:1/subs?t=2&ext=1');
    expect(await indio.streams.localUrl('C:\\d.mp4')).toBe('http://127.0.0.1:1/file?p=x');
    expect(tauri.calls.at(-1)!.args).toEqual({ file: 'C:\\d.mp4' });
    expect(await indio.streams.ffmpeg()).toBe(true);
  });

  it('tells the shell which videos are on screen without waiting for them', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('warm_streams', () => ({ queued: 2 }));
    const indio = await loadBridge();

    // Fire and forget: the page must not wait on a background yt-dlp run, and
    // the shell owns the queue, the pacing and the "is now a bad moment" call.
    expect(indio.streams.warm(['a', 'b', '']))
      .toBe(2);
    indio.streams.warm(['c']);
    await new Promise((resolve) => setTimeout(resolve, 0));
    const asked = tauri.calls
      .filter((call) => call.cmd === 'warm_streams')
      .map((call) => call.args!.videoIds);
    expect(asked).toEqual([['a', 'b'], ['c']]);
    // Nothing else goes to the shell for a warm-up: no player-visible call is
    // made, so a prefetch cannot race the video the viewer actually picked.
    expect(tauri.calls.filter((call) => call.cmd === 'qualities')).toEqual([]);
  });

  it('drives the library, settings and downloads through the same shapes', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('library_get', () => ({ state: { profileName: 'Desk', library: {} } }));
    tauri.on('library_op', (args) => ({ result: { echo: args } }));
    tauri.on('settings_get', () => ({ settings: { theme: 'dark' } }));
    tauri.on('settings_set', (args) => ({ settings: { ...(args!.patch as object), saved: true } }));
    tauri.on('downloads_list', () => ({ tasks: [{ videoId: 'v1', status: 'queued' }] }));
    tauri.on('downloads_start', () => ({ started: true }));
    tauri.on('downloads_cancel', () => ({ cancelled: true }));
    tauri.on('downloads_pause', () => ({ paused: true }));
    tauri.on('downloads_resume', () => ({ resumed: true }));
    tauri.on('downloads_pick_dir', () => ({ dir: 'D:\\Media' }));
    tauri.on('downloads_reveal', () => ({}));
    tauri.on('sponsor_segments', () => ({ data: [{ category: 'sponsor', segment: [1, 2] }] }));
    const indio = await loadBridge();

    expect((await indio.library.get()).profileName).toBe('Desk');
    const echoed = await indio.library.op('noteOpen', { videoId: 'v1' });
    expect(tauri.calls.at(-1)!.args).toEqual({ op: 'noteOpen', args: [{ videoId: 'v1' }] });
    expect((echoed as { result: unknown }).result).toBeTruthy();

    expect(await indio.settings.get()).toEqual({ theme: 'dark' });
    expect(await indio.settings.set({ theme: 'light' })).toEqual({ theme: 'light', saved: true });

    expect(await indio.downloads.list()).toEqual([{ videoId: 'v1', status: 'queued' }]);
    expect(await indio.downloads.start({ videoId: 'v1' } as never, 720)).toEqual({ started: true });
    expect(tauri.calls.at(-1)!.args).toEqual({ video: { videoId: 'v1' }, height: 720 });
    expect(await indio.downloads.cancel('v1')).toEqual({ cancelled: true });
    expect(await indio.downloads.pause('v1')).toEqual({ paused: true });
    expect(await indio.downloads.resume('v1')).toEqual({ resumed: true });
    expect(await indio.downloads.pickDir()).toEqual({ dir: 'D:\\Media' });
    expect(await indio.downloads.reveal('C:\\a.mp4')).toEqual({});
    expect(await indio.sponsorBlock.segments('v1')).toEqual([
      { category: 'sponsor', segment: [1, 2] },
    ]);
  });

  it('gives the sync modal the pairing payload the Android app scans', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('sync_start', () => ({
      payload: { indio: 'sync', v: 1, host: '192.168.1.5', port: 47711, token: 't', name: 'Desk' },
      ips: ['192.168.1.5'],
      port: 47711,
      // extras the shell reports must not leak into the modal's contract
      active: true,
      url: 'http://192.168.1.5:47711/indio/v1',
    }));
    tauri.on('sync_stop', () => ({ ok: true }));
    const indio = await loadBridge();

    const started = await indio.sync.start();
    expect(Object.keys(started).sort()).toEqual(['ips', 'payload', 'port']);
    expect(started.payload.host).toBe('192.168.1.5');
    expect(started.ips).toEqual(['192.168.1.5']);
    expect(started.port).toBe(47711);
    expect(await indio.sync.stop()).toEqual({ ok: true });
  });

  it('delivers shell events in the payload shapes the renderer subscribes to', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    const indio = await loadBridge();

    // `onFullscreen(setIsFullscreen)` takes a plain boolean, not a wrapper.
    let fullscreen: unknown = 'unset';
    indio.window.onFullscreen((on) => {
      fullscreen = on;
    });
    tauri.emit('win:fullscreen-changed', true);
    expect(fullscreen).toBe(true);

    let download: unknown = null;
    indio.downloads.onEvent((event) => {
      download = event;
    });
    tauri.emit('download:event', { videoId: 'v1', status: 'done', file: 'a.mp4' });
    expect(download).toEqual({ videoId: 'v1', status: 'done', file: 'a.mp4' });

    let library: unknown = null;
    indio.library.onChanged((state) => {
      library = state;
    });
    tauri.emit('lib:changed', { profileName: 'Desk' });
    expect(library).toEqual({ profileName: 'Desk' });

    let syncEvent: unknown = null;
    indio.sync.onEvent((event) => {
      syncEvent = event;
    });
    tauri.emit('sync:event', { type: 'merged', stats: { historyAdded: 2 }, sender: 'Pixel' });
    expect(syncEvent).toMatchObject({ type: 'merged', sender: 'Pixel' });

    tauri.on('win_mini', () => ({ mini: true }));
    tauri.on('win_fullscreen', () => ({ fullscreen: false }));
    tauri.on('win_is_fullscreen', () => ({ fullscreen: true }));
    expect(await indio.window.setMini(true)).toBe(true);
    expect(await indio.window.setFullscreen(false)).toBe(false);
    expect(await indio.window.isFullscreen()).toBe(true);
  });

  it('surfaces a failed command as a rejected promise the screens can catch', async () => {
    tauri.on('indio_init', () => ({ ok: true }));
    tauri.on('watch', () => {
      throw new Error('Video unavailable');
    });
    const indio = await loadBridge();
    // The Rust command answers Err(String); invoke rejects, so the screen's
    // try/catch still works exactly as it did over IPC.
    await expect(indio.watch('gone')).rejects.toBeTruthy();
  });
});
