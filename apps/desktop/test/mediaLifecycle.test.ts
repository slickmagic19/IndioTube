// Releasing the outgoing media element is what stops the old decoder, packets
// and buffered bytes from living next to the next video's.
import { describe, expect, it } from 'vitest';
import {
  releaseMediaElement,
  releasePlayback,
  type ReleasableMedia,
} from '../src/player/mediaLifecycle';

function fakeElement(log: string[], name: string): ReleasableMedia {
  return {
    pause: () => log.push(`${name}:pause`),
    removeAttribute: (attr) => log.push(`${name}:remove:${attr}`),
    load: () => log.push(`${name}:load`),
  };
}

describe('releasing a media element', () => {
  it('pauses, detaches the source and reloads', () => {
    const log: string[] = [];
    releaseMediaElement(fakeElement(log, 'video'));
    expect(log).toEqual(['video:pause', 'video:remove:src', 'video:load']);
  });

  it('releases the picture and the shadow audio track', () => {
    const log: string[] = [];
    releasePlayback(fakeElement(log, 'video'), fakeElement(log, 'audio'));
    expect(log).toEqual([
      'video:pause',
      'video:remove:src',
      'video:load',
      'audio:pause',
      'audio:remove:src',
      'audio:load',
    ]);
  });

  it('does nothing when there is no element', () => {
    expect(() => releaseMediaElement(null)).not.toThrow();
    expect(() => releaseMediaElement(undefined)).not.toThrow();
    expect(() => releasePlayback(null, undefined)).not.toThrow();
  });

  it('never throws when the element is already torn down', () => {
    const log: string[] = [];
    const element: ReleasableMedia = {
      pause: () => { throw new Error('detached'); },
      removeAttribute: () => { throw new Error('detached'); },
      load: () => { log.push('load'); },
    };
    expect(() => releaseMediaElement(element)).not.toThrow();
    // One failing step must not skip the ones after it.
    expect(log).toEqual(['load']);
  });
});
