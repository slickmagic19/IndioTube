// Live smoke probe for the InnerTube TS port: home, suggestions, search,
// watch, channel, playlist, comments — everything through the same code the
// Electron main process runs. Requires network. `node probe-indio.js`.
const { InnerTube } = require('./electron/inTube');

(async () => {
  const tube = new InnerTube();
  const home = await tube.home();
  console.log('home     :', home.videos.length, 'videos,', home.chips.length, 'chips, cont:', Boolean(home.continuation));

  const sug = await tube.suggestions('lofi');
  console.log('suggest  :', sug.slice(0, 3).join(' | '));

  const search = await tube.search('quicklybaby');
  console.log('search   :', search.entries.length, 'entries, restricted:', search.restricted,
    '| first:', search.entries[0] && (search.entries[0].video?.title || search.entries[0].channel?.name));

  const watch = await tube.watch('dQw4w9WgXcQ');
  console.log('watch    :', watch.title, '| channel:', watch.channelName, '| related:', watch.related.length,
    '| chapters:', watch.chapters.length, '| comments token:', Boolean(watch.commentsToken), '| live:', watch.isLive);

  if (watch.commentsToken) {
    const page = await tube.comments(watch.commentsToken);
    console.log('comments :', page.comments.length, 'threads | first:', (page.comments[0]?.text || '').slice(0, 60));
  }

  if (watch.channelId) {
    const chan = await tube.channelVideos(watch.channelId);
    console.log('channel  :', chan.channel.name, '| videos:', chan.videos.length, '| about token:', Boolean(chan.aboutToken));
  }

  const pl = await tube.playlist('RDCLAK5uy_');
  console.log('playlist :', pl.title, '| videos:', pl.videos.length);

  const trend = await tube.trending();
  console.log('trending :', trend.videos.length, 'videos');
  console.log('OK');
})().catch((e) => { console.error('PROBE FAILED:', e); process.exit(1); });
