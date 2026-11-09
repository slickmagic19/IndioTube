# IndioTube — ad-free YouTube client for Android and Windows

Unofficial YouTube client. No ads, no Shorts, no sign-in required. Dark theme only.

Two front ends, one library each: the Android app (Kotlin + Compose) and a Windows desktop app
(Electron + React). They keep their history, likes, playlists and subscriptions on the device, and
can be merged with each other over your own network — no account, no server in the middle.

**Stack:** Kotlin + Jetpack Compose + Media3 (ExoPlayer) + NewPipeExtractor + InnerTube (WEB) for
metadata; Electron + React + Vite + InnerTube + a bundled yt-dlp for the desktop build.

**Features**
- Ad-free video playback (NewPipeExtractor resolves clean progressive streams)
- Shorts are filtered out everywhere (home, search, channel pages, related)
- No sign-in: local accounts you can switch between — each with own subscriptions, history and playlists
- Subscribe to channels and get a merged "Latest from your subscriptions" feed
- Download videos for offline playback (resumable)
- YouTube-style dark UI (home feed with chips, watch page with action row, comments, mini player)
- Tablet layouts like YouTube's: a left navigation rail that sizes itself to its widest label (so nothing clips at large system font scales), two to four columns of feed cards, continue-watching cards that grow with the window, and a two-pane watch page on landscape tablets — video and its details on the left, "Up next" in a separate rail on the right — while the single-column pages (you, settings, statistics, search, downloads, playlists) are capped and centred rather than stretched edge to edge, and dialogs and the mini player stay card-sized. Rotating a phone still goes straight into fullscreen
- Thumbnails carry the video length in the corner (LIVE badge for live streams), like the real app
- Search with YouTube's own type-ahead suggestions as you type
- The bottom navigation never disappears while you browse: it stays on search, channel, playlist and watch screens (it only steps aside for fullscreen playback), with the mini player docked above it
- Player controls match YouTube mobile: slim seek bar with drag preview, ±10s double-tap, centre play/pause, skip-next and fullscreen — fullscreen rotates straight into landscape in one clean turn (plain lock, works even with the system rotation lock on) and letterboxes on pure black instead of a thumbnail backdrop
- Real quality selector like YouTube: Auto or any resolution the video actually offers, from 144p up to 4K — high resolutions that YouTube only serves as separate video and audio files are paired transparently, switching keeps your position and resumes instantly, and a resolution the device can't play steps down the ladder instead of interrupting the video (Auto stays on the single muxed file, so playback starts fast even on slow connections)
- Closed captions like YouTube: a CC button in the player lists every caption track the video offers (including auto-generated ones), captions render in the familiar bold-white-with-outline style, and the language you pick is remembered and re-applied to the next videos — a caption file that fails to load is dropped instead of stopping playback
- Playback speed like YouTube: pick 0.25× up to 2× from the player's speed chip or from Settings, the choice is remembered for every video, and press-and-hold on the video plays it at 2× for as long as your finger stays down (releasing goes straight back to your own speed, never to 1×)
- Members-only videos are tagged, not hidden: a lock badge on the thumbnail and a "Members only" tag in the card's meta line, wherever they are listed (search, channel pages, the library) — they never appear in the home recommendations, the subscriptions feed, the related list or Up next, so a video that needs a channel membership never becomes a card that can only fail
- Watch statistics beyond YouTube's own card, under Settings → Watch statistics: total watch time, today and this week, day streak and active days, average per active day, longest session, videos watched and finished, the last seven days as a bar chart, your most-watched channels, and the wall-clock time playback speed saved you — all measured locally on this device (only while a video is actually playing), per account, and clearable at any time
- Mini player like the real app: keeps playing while you browse, swipe down on the player to minimise, tap to expand, play/pause + next + close inline
- Channel photos load from the real avatar data in YouTube's lockups (not just channel pages), with a lettered placeholder as the fallback

**Note on the home feed:** since 2024 YouTube serves no recommendations to signed-out
sessions (the "Try searching to get started" nudge). When that happens, IndioTube builds
a local "Recommended for you" feed instead, mimicking YouTube's ranking signals:
candidates come from subscribed channels, the channels you watch most (recency-weighted
from local history), the related videos of your last few watches and results for your
recent searches; they're scored by freshness, channel affinity, subscribe/like boosts,
search-topic matches and a demotion for already-watched videos, then drawn by weighted
random sampling so every pull-to-refresh produces a genuinely different mix (with a max
of a few videos per channel and the page scrolled back to the top). Returning to Home
rebuilds the ranking, and it force-refreshes channel data when the last build is more
than 30 minutes old (or on pull-to-refresh). The nudge is only shown when there is
nothing local to recommend either.

## Desktop app (Windows)

The same app as a Windows build (`apps/desktop`), laid out like the web player: a top bar with the
same type-ahead search, a left navigation column, and the phone's card grid for Home and Trending.

- Ad-free playback through a bundled `yt-dlp` sidecar. YouTube only serves single-file streams up to
  360p now, so anything above that comes as separate video and audio tracks: the player runs a
  muted video element and an audio element, keeps them within a frame of each other, mutes while
  they are out of sync and never lets the audio speak from the wrong position — including while a
  seek is still loading. A single-file stream is used whenever the rung has one, and multi-audio
  videos always play the creator's original track rather than an auto-dub
- Auto quality picks 720p when the video offers it and steps down from there; hovering a card warms
  the stream ladder so playback starts sooner, and a byte cache serves the second seek instantly
- YouTube-style mini player: it floats over the page and keeps playing while you browse, expands back
  to the watch page on click, and docks itself automatically when you leave a playing video
- The watch page keeps scrolling: video, actions, description, comments, an Up next rail, chapters,
  SponsorBlock segments, captions and playback speed
- SponsorBlock has the phone's full controls: a master switch, an auto-skip switch, and the eight
  segment categories to skip; with auto-skip off the player offers a Skip button at each segment
  and an Undo after every automatic skip
- Every card has the phone's full â‹® menu: mark watched/unwatched, play next, add to queue, like, save
  to Watch later, save to a playlist, download, copy link, interested, not interested (with Undo) and
  don't recommend this channel â€” the last three filter the Home and Trending grids immediately
- Downloads land as real files in a folder you choose; History, Watch later, Liked videos, Playlists
  and Downloads all mirror the phone's library screens

## Phone â†” desktop sync

The desktop app opens a small LAN server (`/indio/v1/*`, port 47711, token protected) and shows a QR
code; scanning it on the phone pairs the two, and the same screen accepts a typed host/port/token
when there is no camera. Sync is add-only and merges rather than overwrites: history keeps the newest
position per video, likes and Watch later are unioned, playlists are matched by name, and
subscriptions are unioned. On the phone it lives under **You â†’ Sync**; on the desktop under
**Settings â†’ Sync**.

## Build

```powershell
cd apps/android
.\gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

```powershell
# desktop, from the repository root
npm install
npm run fetch:ytdlp      # pins yt-dlp.exe into apps/desktop/vendor
npm run dist:portable    # -> release/IndioTube-<version>-win.exe

npm run dev              # vite dev server on 127.0.0.1:5174
npm run electron:dev     # the desktop app against it
npm test                 # desktop unit tests (vitest)
```

## Legal note
Streams resolve publicly-accessible YouTube media for personal local playback only (same as browser + ad-blocker). No DRM bypass. Uses unofficial APIs — Google can break it.
