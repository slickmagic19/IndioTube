# IndioTube — ad-free YouTube client for Android

Unofficial YouTube client. No ads, no Shorts, no sign-in required. Dark theme only.

**Stack:** Kotlin + Jetpack Compose + Media3 (ExoPlayer) + NewPipeExtractor + InnerTube (WEB) for metadata.

**Features**
- Ad-free video playback (NewPipeExtractor resolves clean progressive streams)
- Shorts are filtered out everywhere (home, search, channel pages, related)
- No sign-in: local accounts you can switch between — each with own subscriptions, history and playlists
- Subscribe to channels and get a merged "Latest from your subscriptions" feed
- Download videos for offline playback (resumable)
- YouTube-style dark UI (home feed with chips, watch page with action row, comments, mini player)
- Thumbnails carry the video length in the corner (LIVE badge for live streams), like the real app
- Search with YouTube's own type-ahead suggestions as you type
- The bottom navigation never disappears while you browse: it stays on search, channel, playlist and watch screens (it only steps aside for fullscreen playback), with the mini player docked above it
- Player controls match YouTube mobile: slim seek bar with drag preview, ±10s double-tap, centre play/pause, skip-next and fullscreen — fullscreen rotates straight into landscape in one clean turn (plain lock, works even with the system rotation lock on) and letterboxes on pure black instead of a thumbnail backdrop
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

## Build

```powershell
cd apps/android
.\gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Legal note
Streams resolve publicly-accessible YouTube media for personal local playback only (same as browser + ad-blocker). No DRM bypass. Uses unofficial APIs — Google can break it.
