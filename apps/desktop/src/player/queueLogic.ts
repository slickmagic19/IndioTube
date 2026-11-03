// Queue maths for "Play next" / "Add to queue", kept pure so the ordering
// rules are unit-tested instead of exercised by hand in the UI.
//
// Rules:
//  - the playing video keeps its position in the list;
//  - adding a video that is already queued moves it instead of duplicating;
//  - 'next' inserts right after the current item, 'end' appends.
import type { VideoItem } from '../types';

export interface QueueState { items: VideoItem[]; index: number }

export function enqueue(
  items: VideoItem[],
  index: number,
  video: VideoItem,
  mode: 'next' | 'end',
): QueueState {
  if (!video || !video.videoId) return { items, index };
  if (!items.length) return { items: [video], index: 0 };
  const current = items[index] || items[0];
  const rest = items.filter((v) => v.videoId !== video.videoId);
  const insertAt = mode === 'next'
    ? Math.max(0, rest.findIndex((v) => v.videoId === current.videoId)) + 1
    : rest.length;
  rest.splice(insertAt, 0, video);
  // The playing video keeps being the current one, wherever it ended up
  // (re-queuing the playing video moves it without changing what plays).
  const currentIdx = rest.findIndex((v) => v.videoId === current.videoId);
  return { items: rest, index: Math.max(0, currentIdx) };
}
