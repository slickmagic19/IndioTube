// The "reload the feed" intent, kept outside the screen that serves it.
//
// Clicking Home from a watch page navigates before the feed screen exists, so
// the request has to be parked somewhere the next mount can read - and it must
// not leak into the navigation that follows it, or every return to Home would
// go back to the network.
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  FEED_REFRESH_EVENT,
  clearFeedRefresh,
  doneFeedRefresh,
  requestFeedRefresh,
  wantsFeedRefresh,
} from '../src/feedRefresh';

// The module talks to the DOM the way the app does; the node runner has none.
const dispatched: string[] = [];
vi.stubGlobal('window', {
  dispatchEvent: (event: { type: string }) => { dispatched.push(event.type); return true; },
  Event: class { constructor(public type: string) {} },
});

afterEach(() => clearFeedRefresh());

describe('a feed reload request', () => {
  it('outlives the navigation that asked for it', () => {
    requestFeedRefresh('home');
    expect(dispatched).toEqual([FEED_REFRESH_EVENT]);
    // The screen that mounts for Home finds the request waiting for it.
    expect(wantsFeedRefresh('home')).toBe(true);
  });

  it('is only owed to the feed that was asked for', () => {
    requestFeedRefresh('home');
    expect(wantsFeedRefresh('trending')).toBe(false);
  });

  it('is spent once that feed answered', () => {
    requestFeedRefresh('home');
    doneFeedRefresh('home');
    expect(wantsFeedRefresh('home')).toBe(false);
    // A later return to Home is a plain return: the cache serves it.
    expect(wantsFeedRefresh('home')).toBe(false);
  });

  it('is not spent by a different feed answering', () => {
    requestFeedRefresh('home');
    doneFeedRefresh('trending');
    expect(wantsFeedRefresh('home')).toBe(true);
  });

  it('is not owed twice for a feed nobody asked about', () => {
    expect(wantsFeedRefresh('home')).toBe(false);
    doneFeedRefresh('home');
    expect(wantsFeedRefresh('home')).toBe(false);
  });

  it('keeps the event name the mounted screen already listens for', () => {
    expect(FEED_REFRESH_EVENT).toBe('indio-feed-refresh');
  });
});
