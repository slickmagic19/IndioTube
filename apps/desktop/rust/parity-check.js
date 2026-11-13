// Live parity check for the Rust InnerTube port.
//
// Fetches a real search page and the real Home feed through the app's own
// JavaScript client, saves the raw responses, and prints what the JavaScript
// parser saw. The Rust side (examples/parse_fixture.rs) is then run on the same
// files and the two summaries are compared.
const fs = require('node:fs');
const path = require('node:path');
const inTube = require('../electron/inTube');

const outDir = __dirname;

function summarizeSearch(results) {
  return {
    items: results.entries.map((e) => (e.kind === 'video'
      ? {
        kind: 'video',
        videoId: e.video.videoId,
        title: e.video.title,
        channelId: e.video.channelId,
        durationSec: e.video.durationSec,
        verified: Boolean(e.video.channelVerified),
        membersOnly: Boolean(e.video.membersOnly),
      }
      : e.kind === 'channel'
        ? { kind: 'channel', channelId: e.channel.channelId, name: e.channel.name }
        : { kind: 'playlist', playlistId: e.playlist.playlistId, title: e.playlist.title })),
    continuation: Boolean(results.continuation),
    restricted: Boolean(results.restricted),
  };
}

(async () => {
  const client = new inTube.InnerTube();
  const cfg = await client.config();

  const searchRoot = await client.post('search', { context: client.contextOf(cfg), query: 'cats' });
  fs.writeFileSync(path.join(outDir, 'fixture-search.json'), JSON.stringify(searchRoot));
  console.log('JS_SEARCH ' + JSON.stringify(summarizeSearch(inTube.parseSearch(searchRoot))));

  const feedRoot = await client.browse('FEwhat_to_watch', null);
  fs.writeFileSync(path.join(outDir, 'fixture-feed.json'), JSON.stringify(feedRoot));
  const feed = inTube.parseFeed(feedRoot);
  console.log('JS_FEED ' + JSON.stringify({
    items: feed.videos.map((v) => ({
      videoId: v.videoId,
      title: v.title,
      channelId: v.channelId,
      durationSec: v.durationSec,
      verified: Boolean(v.channelVerified),
      membersOnly: Boolean(v.membersOnly),
    })),
    chips: feed.chips.length,
    continuation: Boolean(feed.continuation),
  }));
})().catch((e) => {
  console.error('FETCH_FAILED', e.message);
  process.exit(1);
});
