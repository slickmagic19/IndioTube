// Live parity check for the Rust InnerTube port.
//
// Fetches real responses through the app's own JavaScript client, saves the raw
// JSON, and writes what the JavaScript parsers produced. The Rust side
// (examples/parse_fixture.rs) is then run on the same files and the two are
// compared by compare-parity.js.
const fs = require('node:fs');
const path = require('node:path');
const inTube = require('../electron/inTube');

const outDir = __dirname;
const write = (name, value) => fs.writeFileSync(path.join(outDir, name), JSON.stringify(value));

(async () => {
  const client = new inTube.InnerTube();
  const cfg = await client.config();
  const context = client.contextOf(cfg);
  const videoId = 'dQw4w9WgXcQ';
  const channelId = 'UCPIvT-zcQl2H0vabdXJGcpg';

  const searchRoot = await client.post('search', { context, query: 'cats' });
  write('fixture-search.json', searchRoot);
  write('js-search.json', inTube.parseSearch(searchRoot));

  const watchRoot = await client.post('next', { context, videoId });
  const watch = inTube.parseWatch(watchRoot, videoId);
  write('fixture-watch.json', watchRoot);
  write('js-watch.json', watch);

  if (watch.commentsToken) {
    const commentsRoot = await client.post('next', { context, continuation: watch.commentsToken });
    write('fixture-comments.json', commentsRoot);
    write('js-comments.json', inTube.parseComments(commentsRoot));
  }

  const channelRoot = await client.browse(channelId, inTube.CHANNEL_TAB_VIDEOS);
  write('fixture-channel.json', channelRoot);
  write('js-channel.json', inTube.parseChannelPage(channelRoot, channelId));
  write('js-tabs.json', inTube.parseChannelTabs(channelRoot));

  const tabs = inTube.parseChannelTabs(channelRoot);
  const playlistsTab = tabs.find((t) => t.title === 'Playlists');
  if (playlistsTab) {
    const pageRoot = await client.browse(channelId, playlistsTab.params || inTube.CHANNEL_TAB_PLAYLISTS);
    const page = inTube.parseChannelPlaylistsPage(pageRoot);
    write('fixture-playlists.json', pageRoot);
    write('js-playlists.json', page);
    if (page.playlists.length) {
      const playlistRoot = await client.browse(`VL${page.playlists[0].playlistId}`);
      const playlist = inTube.parsePlaylist(playlistRoot);
      write('fixture-playlist.json', playlistRoot);
      write('js-playlist.json', playlist);
      // The items of a playlist arrive with its continuation, not the header.
      if (playlist.continuation) {
        const itemsRoot = await client.browse('', null, playlist.continuation);
        write('fixture-playlist-items.json', itemsRoot);
        write('js-playlist-items.json', inTube.parsePlaylist(itemsRoot));
      }
    }
  }

  for (const mode of ['search', 'watch', 'comments', 'channel', 'playlists', 'playlist']) {
    const file = path.join(outDir, `js-${mode}.json`);
    if (!fs.existsSync(file)) continue;
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    console.log(`JS_${mode.toUpperCase()} ${JSON.stringify(parsed).length} bytes`);
  }
})().catch((e) => {
  console.error('FETCH_FAILED', e.message);
  process.exit(1);
});
