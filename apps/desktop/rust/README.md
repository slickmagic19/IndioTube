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

A run on 2026-11-13 produced 19 search entries from both sides with identical
ids, titles, channel ids, durations and verified flags (0 mismatches).
