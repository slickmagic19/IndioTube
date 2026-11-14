// What the app already knows about a video before the watch page asks for it.
//
// Every card on screen carries the title, channel, thumbnail, duration, view
// count and publish date, and clicking one threw all of that away: the watch
// page started from `Loading…` and waited for its own metadata round trip
// before it painted anything. The cards register what they show here, and the
// watch page paints with it straight away and refines when the reply lands.

import type { VideoItem } from './types';

const MAX_ENTRIES = 200;
const hints = new Map<string, VideoItem>();

export function rememberVideo(video: VideoItem): void {
  if (!video || !video.videoId) return;
  hints.delete(video.videoId);
  hints.set(video.videoId, video);
  // Oldest first: the videos a viewer clicked an hour ago are not going to be
  // the ones they open next.
  while (hints.size > MAX_ENTRIES) {
    const oldest = hints.keys().next().value as string | undefined;
    if (oldest === undefined) break;
    hints.delete(oldest);
  }
}

export function peekVideo(videoId: string): VideoItem | null {
  return hints.get(videoId) || null;
}
