// "Play next" / "Add to queue" ordering — the same rules the Android player
// uses, kept pure so they can be pinned with tests.
import { describe, expect, it } from 'vitest';
import { enqueue } from '../src/player/queueLogic';

const v = (id: string) => ({ videoId: id, title: id, durationSec: 0, isLive: false, membersOnly: false });

describe('queue enqueue', () => {
  it('starts a queue when nothing is playing', () => {
    const q = enqueue([], -1, v('a'), 'end');
    expect(q.items.map((x) => x.videoId)).toEqual(['a']);
    expect(q.index).toBe(0);
  });

  it('play next inserts directly after the playing video', () => {
    const q = enqueue([v('a'), v('b'), v('c')], 0, v('x'), 'next');
    expect(q.items.map((x) => x.videoId)).toEqual(['a', 'x', 'b', 'c']);
    expect(q.index).toBe(0); // the playing video did not move
  });

  it('add to queue appends at the end', () => {
    const q = enqueue([v('a'), v('b')], 1, v('x'), 'end');
    expect(q.items.map((x) => x.videoId)).toEqual(['a', 'b', 'x']);
    expect(q.index).toBe(1);
  });

  it('keeps the current index when inserting mid-queue', () => {
    const q = enqueue([v('a'), v('b'), v('c')], 1, v('x'), 'next');
    expect(q.items.map((x) => x.videoId)).toEqual(['a', 'b', 'x', 'c']);
    expect(q.items[q.index].videoId).toBe('b');
  });

  it('moves an already-queued video instead of duplicating it', () => {
    const q = enqueue([v('a'), v('b'), v('c')], 0, v('c'), 'next');
    expect(q.items.map((x) => x.videoId)).toEqual(['a', 'c', 'b']);
    expect(q.index).toBe(0);
    expect(new Set(q.items.map((x) => x.videoId)).size).toBe(q.items.length);
  });

  it('never reorders when the playing video is the one re-queued', () => {
    const q = enqueue([v('a'), v('b')], 0, v('a'), 'end');
    expect(q.items.map((x) => x.videoId)).toEqual(['b', 'a']);
    expect(q.items[q.index].videoId).toBe('a');
  });

  it('ignores an invalid video', () => {
    const q = enqueue([v('a')], 0, { videoId: '' } as any, 'next');
    expect(q.items.map((x) => x.videoId)).toEqual(['a']);
  });
});
