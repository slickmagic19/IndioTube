// Telling the shell what is on screen, so it can have those streams ready.
//
// Starting a video costs one `yt-dlp` run (measured ~5 s, with occasional
// 15-25 s stalls), which is what "it takes ages after I click" is. Hovering a
// card already warms that one video; this hands the whole visible page to the
// shell, which queues the work, does one at a time and gets out of the way of
// anything the viewer is actually waiting for. The policy lives in
// `src-tauri/src/commands.rs::warm_streams` because the shell is the only place
// that can see how busy it is.

/**
 * Warm the first `count` videos of a freshly painted page. Live and
 * members-only videos are skipped: they cannot play here, so resolving their
 * streams would only cost time. Best effort — without the Tauri bridge there
 * is nothing to warm and nothing to say about it.
 */
export function warmPage(
  videos: { videoId: string; isLive?: boolean; membersOnly?: boolean }[],
  count = 6,
): number {
  const ids = videos
    .filter((video) => video && video.videoId && !video.isLive && !video.membersOnly)
    .slice(0, count)
    .map((video) => video.videoId);
  if (!ids.length) return 0;
  const indio = typeof window === 'undefined'
    ? undefined
    : (window as unknown as { indio?: { streams?: { warm?(ids: string[]): number } } }).indio;
  return indio?.streams?.warm?.(ids) ?? 0;
}
