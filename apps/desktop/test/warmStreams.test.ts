// Which videos a page hands to the shell for background stream resolution.
// The queue and its pacing live in Rust; what belongs here is the policy about
// the list itself: the top of the page, and nothing that cannot play here.
import { beforeEach, describe, expect, it } from 'vitest';
import { warmPage } from '../src/player/warmStreams';

function setWindow(indio: unknown) {
  (globalThis as unknown as { window: unknown }).window = indio === undefined
    ? {}
    : { indio };
}

describe('warming the videos a page shows', () => {
  let seen: string[][] = [];
  beforeEach(() => {
    seen = [];
    setWindow({ streams: { warm: (ids: string[]) => { seen.push(ids); return ids.length; } } });
  });

  it('asks for the top of the page in order', () => {
    const count = warmPage([{ videoId: 'a' }, { videoId: 'b' }, { videoId: 'c' }], 2);
    expect(count).toBe(2);
    expect(seen).toEqual([['a', 'b']]);
  });

  it('warms only the first two videos by default', () => {
    // Every warm-up is a yt-dlp run holding a resolved stream in memory; two
    // are enough to make the likely click instant without competing with it.
    const count = warmPage([{ videoId: 'a' }, { videoId: 'b' }, { videoId: 'c' }, { videoId: 'd' }]);
    expect(count).toBe(2);
    expect(seen).toEqual([['a', 'b']]);
  });

  it('skips what the player cannot open', () => {
    const count = warmPage([
      { videoId: 'live', isLive: true },
      { videoId: 'members', membersOnly: true },
      { videoId: 'no-id', isLive: false },
      { videoId: 'a' },
    ]);
    expect(count).toBe(2);
    expect(seen).toEqual([['no-id', 'a']]);
  });

  it('says nothing when there is nothing to say', () => {
    expect(warmPage([])).toBe(0);
    expect(warmPage([{ videoId: 'live', isLive: true }])).toBe(0);
    expect(seen).toEqual([]);
  });

  it('is quiet without the Tauri bridge', () => {
    setWindow(undefined);
    expect(warmPage([{ videoId: 'a' }])).toBe(0);
    setWindow({});
    expect(warmPage([{ videoId: 'a' }])).toBe(0);
  });
});
