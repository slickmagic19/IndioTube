// The watch page paints with what the clicked card already showed. That only
// works if the registry holds the newest copy of a video and stays small, so
// these two rules are pinned here.
import { describe, expect, it } from 'vitest';
import { peekVideo, rememberVideo } from '../src/videoHints';
import type { VideoItem } from '../src/types';

function video(videoId: string, title = `Video ${videoId}`): VideoItem {
  return { videoId, title, durationSec: 60, isLive: false, membersOnly: false };
}

describe('what the app already knows about a video', () => {
  it('remembers the copy the card was showing', () => {
    rememberVideo(video('warm1'));
    expect(peekVideo('warm1')?.title).toBe('Video warm1');
    expect(peekVideo('never-seen')).toBeNull();
  });

  it('keeps the last thing said about a video', () => {
    rememberVideo(video('same', 'First title'));
    rememberVideo(video('same', 'Second title'));
    expect(peekVideo('same')?.title).toBe('Second title');
  });

  it('forgets the oldest videos instead of growing forever', () => {
    for (let i = 0; i < 260; i += 1) rememberVideo(video(`v${i}`));
    expect(peekVideo('v0')).toBeNull();
    expect(peekVideo('v259')?.title).toBe('Video v259');
  });

  it('ignores an empty video', () => {
    rememberVideo(video(''));
    expect(peekVideo('')).toBeNull();
  });
});
