# indio-core (Rust)

The Rust core for the IndioTube desktop app. This is the first slice of moving
the Electron main process's JavaScript into Rust ahead of a Tauri shell; the
rules, the edge cases and the tests are ported one module at a time so the
running app keeps working while the core grows.

| module | JavaScript it replaces | what it owns |
|---|---|---|
| `quality` | `buildQualities()` in `electron/streams.js` | yt-dlp's format list -> the Android-shaped ladder: muxed rungs where YouTube offers them, split video + best audio otherwise, 30fps preferred at the same height, 720p `auto`, deduped captions with the `fmt=vtt` rewrite |
| `downloads` | `electron/downloads.js` | progress-line parsing (percent, size, speed, ETA), yt-dlp size units, bounded retry policy, finding the merged output after a split download |
| `range` | `/r` in `electron/streams.js`, `DownloadTransfer.kt` | byte ranges for the media proxy and the segmented downloader, `Content-Range` validation, seeded resume plans |
| `innertube` | `electron/inTube.js` | the InnerTube client: request contexts, protobuf search filters, visitor/API config handling, a TTL response cache, and the parsers for feed, search, `videoRenderer`, `lockupViewModel`, channel and playlist results |
| `proxy` | `electron/streams.js` | the 127.0.0.1 media server: opaque CDN tokens, `/v` `/r` `/subs` `/file` routing, CORS preflight, three-attempt retry, a 96 MB / 24 MB-entry LRU of range responses, json3 and TTML captions converted to WebVTT, `Range` handling for local files |
| `watch` | `parseWatch`, `parseWatchContinuation`, `parseChapters`, `parseComments` | the watch header (title, channel, views, likes, description), members-only detection from the playability reason, the Up next rail plus its token, chapters (deduped and sorted), and comments assembled from `frameworkUpdates` entities with reply tokens and the old `commentRenderer` fallback |
| `channel` | `parseChannelHeader`, `parseChannelPage`, `parseChannelPlaylistsPage`, `parseChannelTabs`, `parseChannelAbout`, `parseAboutToken`, `parsePlaylistLockup` | channel header from `pageHeaderRenderer` or the legacy `c4TabbedHeaderRenderer`, videos with the header channel stamped onto them, the About panel and its token, tabs and the playlists grid |
| `playlist` | `parsePlaylist` | playlist title/author/count from `playlistHeaderRenderer` or `pageHeaderViewModel`, items from `playlistVideoRenderer` or lockups, and both continuation shapes |

## Toolchain on this machine

`rustup` (stable) plus a **GNU** host toolchain, because the MSVC build tools
are not installed here and a MinGW gcc is a ~400 MB portable unzip instead of a
multi-gigabyte Visual Studio install:

```bash
rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal
# MinGW-w64 from winlibs, unpacked outside the repo:
#   C:/Users/<you>/tools/mingw2/mingw64/bin/gcc.exe
export PATH="$HOME/.cargo/bin:$HOME/tools/mingw2/mingw64/bin:$PATH"
export RUSTFLAGS="-C linker=C:/Users/<you>/tools/mingw2/mingw64/bin/gcc.exe"
```

## Build and test

```bash
cd apps/desktop/rust/indio-core
cargo +stable-x86_64-pc-windows-gnu test
```

The tests mirror `test/streams.test.ts` and `test/downloads.test.ts`: the same
ladder shapes, the same caption rules, the same progress lines and the same
retry limits. The proxy tests drive real `TcpStream`s through
`serve_connection`, so request parsing and response headers are exercised over
a socket, not just in memory.

## Parity against the JavaScript

`parity-check.js` fetches a real search page and Home feed with the app's own
JavaScript client, saves the raw responses and prints what `electron/inTube.js`
parsed. `examples/parse_fixture.rs` parses the same bytes with the Rust core:

```bash
node parity-check.js                      # writes fixture-search.json
cargo run --example parse_fixture -- \
  ../fixture-search.json search           # same items, Rust side
```

`compare-parity.js` then diffs the two outputs (ignoring `null` vs missing keys
and rounding numbers), for every mode `parity-check.js` captured:

```bash
node parity-check.js                      # writes fixture-*.json and js-*.json
# for each mode: search, watch, comments, channel, playlists, playlist
cargo run --example parse_fixture -- ../fixture-watch.json watch <videoId> \
  > ../rust-watch.json
node compare-parity.js search watch comments channel playlists playlist
```

A run on 2026-11-13 matched on all six modes against live YouTube responses: a
search page, a watch page with its Up next rail and chapters, the comment
threads behind that video, a channel page, that channel's playlists tab and a
playlist header. That run caught one real bug — the lockup thumbnail took the
last source of *any* `sources` array, which was an animated preview webp instead
of the still image — which is fixed here. Playlist *items* were not exercised
live: the continuation response came back empty, so only the header was
compared; the item renderers are covered by unit tests.
