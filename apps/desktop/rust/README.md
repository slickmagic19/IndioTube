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
retry limits.
