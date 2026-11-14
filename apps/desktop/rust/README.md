# indio-core (Rust)

The Rust core of the IndioTube desktop app: the rules, the edge cases and the
tests that used to live in the Electron main process, ported module by module.
The shell in `../src-tauri` is the only caller, and the desktop app ships no
JavaScript main process at all.

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

`rustup` (stable) plus the GNU host toolchain when MSVC build tools are missing;
`../../scripts/with-rust-env.js` (used by the npm scripts) picks between them and
points cargo at a portable MinGW-w64 — `~/tools/mingw2/mingw64/bin`, or whatever
`INDIOTUBE_MINGW_BIN` says:

```bash
cd apps/desktop
npm run test:rust          # indio-core, then the shell
node ../../scripts/with-rust-env.js cargo test --manifest-path rust/indio-core/Cargo.toml
```

## Build and test

The tests mirror the JavaScript suites that used to sit in `apps/desktop/test`:
the same ladder shapes, the same caption rules, the same progress lines and the
same retry limits. The proxy tests drive real `TcpStream`s through
`serve_connection`, so request parsing and response headers are exercised over
a socket, not just in memory.

## Parity against the JavaScript

The JavaScript reference implementation is gone from the tree — it is in git
history up to the last Electron release. What remains here is the fixture
checker, which parses a saved InnerTube or yt-dlp response with the Rust core:

```bash
cd apps/desktop/rust/indio-core
cargo run --example parse_fixture -- ../fixture-search.json search
cargo run --example parse_fixture -- ../fixture-watch.json watch <videoId>
```

A live run on 2026-11-13 matched on all six modes against YouTube responses: a
search page, a watch page with its Up next rail and chapters, the comment
threads behind that video, a channel page, that channel's playlists tab and a
playlist header. That run caught one real bug — the lockup thumbnail took the
last source of *any* `sources` array, which was an animated preview webp instead
of the still image — which is fixed here. Playlist *items* were not exercised
live: the continuation response came back empty, so only the header was
compared; the item renderers are covered by unit tests.
