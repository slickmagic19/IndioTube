// Preload: the only surface the renderer gets. Every invoke-wrapped handler
// returns { ok, ... }; { ok: false, error } becomes a thrown Error so screens
// can try/catch like any other async call.
const { contextBridge, ipcRenderer } = require('electron');

async function invoke(channel, ...args) {
  const res = await ipcRenderer.invoke(channel, ...args);
  if (res && res.ok === false) throw new Error(res.error || 'request failed');
  return res;
}

function on(channel, cb) {
  const listener = (_ev, data) => cb(data);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}

contextBridge.exposeInMainWorld('indio', {
  home: async (p = {}) => (await invoke('indio:home', p)).feed,
  homeMore: async (p = {}) => (await invoke('indio:homeMore', p)).videos,
  homeCont: async (token) => (await invoke('indio:homeCont', { token })).feed,
  trending: async (p = {}) => (await invoke('indio:trending', p)).feed,
  trendingCont: async (token) => (await invoke('indio:trendingCont', { token })).feed,
  search: async (query, filters) => (await invoke('indio:search', { query, filters })).results,
  searchCont: async (token) => (await invoke('indio:searchCont', { token })).results,
  suggest: async (query) => (await invoke('indio:suggest', { query })).suggestions,
  channel: async (channelId, params) => (await invoke('indio:channel', { channelId, params })).page,
  channelCont: async (token, channelId) => (await invoke('indio:channelCont', { token, channelId })).page,
  channelLive: async (channelId) => (await invoke('indio:channelLive', { channelId })).page,
  channelPlaylists: async (channelId) => (await invoke('indio:channelPlaylists', { channelId })).page,
  channelPlaylistsCont: async (token) => (await invoke('indio:channelPlaylistsCont', { token })).page,
  channelAbout: async (channelId, token) => (await invoke('indio:channelAbout', { channelId, token })).about,
  watch: async (videoId) => (await invoke('indio:watch', { videoId })).data,
  watchMore: async (token) => (await invoke('indio:watchMore', { token })).page,
  comments: async (token) => (await invoke('indio:comments', { token })).page,
  playlist: async (playlistId) => (await invoke('indio:playlist', { playlistId })).page,
  playlistCont: async (token) => (await invoke('indio:playlistCont', { token })).page,

  streams: {
    qualities: async (videoId, force) => (await invoke('streams:qualities', { videoId, force })).qualities,
    playUrl: async (videoId, height) => {
      const res = await invoke('streams:playUrl', { videoId, height });
      // The codecs are what the MSE player needs to build its mime types; the
      // IPC rebuild used to drop them, which silently disabled MSE entirely.
      return { video: res.video, audio: res.audio || null, height: res.height, label: res.label,
        videoCodec: res.videoCodec || null, audioCodec: res.audioCodec || null };
    },
    subtitleUrl: async (videoId, lang) => (await invoke('streams:subtitleUrl', { videoId, lang })).url,
    localUrl: async (file) => (await invoke('streams:localUrl', { file })).url,
    ffmpeg: async () => (await invoke('streams:ffmpeg')).available,
  },

  downloads: {
    start: async (video, height) => (await invoke('downloads:start', { video, height })),
    cancel: async (videoId) => invoke('downloads:cancel', { videoId }),
    list: async () => (await invoke('downloads:list', {})).tasks || [],
    pause: async (videoId) => invoke('downloads:pause', { videoId }),
    resume: async (videoId) => invoke('downloads:resume', { videoId }),
    pickDir: () => invoke('downloads:pickDir'),
    reveal: (file) => invoke('downloads:reveal', { file }),
    onEvent: (cb) => on('download:event', cb),
  },

  sponsorBlock: {
    segments: async (videoId, categories) => (await invoke('sb:segments', { videoId, categories })).data,
  },

  library: {
    get: async () => (await invoke('lib:get')).state,
    op: async (name, ...args) => (await invoke('lib:op', { op: name, args })).result,
    onChanged: (cb) => on('lib:changed', cb),
  },

  settings: {
    get: async () => (await invoke('settings:get')).settings,
    set: async (patch) => (await invoke('settings:set', { patch })).settings,
  },

  sync: {
    start: async () => {
      const res = await invoke('sync:start');
      return { payload: res.payload, ips: res.ips, port: res.port };
    },
    stop: () => invoke('sync:stop'),
    onEvent: (cb) => on('sync:event', cb),
  },

  window: {
    setMini: async (on) => (await invoke('win:mini', { on })).mini,
    setFullscreen: async (on) => (await invoke('win:fullscreen', { on })).fullscreen,
    isFullscreen: async () => (await invoke('win:isFullscreen')).fullscreen,
    onFullscreen: (cb) => on('win:fullscreen-changed', cb),
  },

  app: {
    version: async () => (await invoke('app:version')).version,
  },
});
