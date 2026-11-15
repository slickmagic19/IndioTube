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
  planFeedLoad,
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

describe('what a feed load asks for', () => {
  it('serves the cached feed when nobody asked for a reload', () => {
    const plan = planFeedLoad({ wanted: false, chipParams: null });
    expect(plan.reload).toBe(false);
    expect(plan.force).toBe(false);
    expect(plan.params).toBe(null);
    expect(plan.resetChips).toBe(false);
  });

  it('keeps the category the viewer picked', () => {
    const plan = planFeedLoad({ wanted: false, chipParams: 'EgZ2aWRlb3PyBgQKAjoA' });
    expect(plan.reload).toBe(false);
    expect(plan.params).toBe('EgZ2aWRlb3PyBgQKAjoA');
    expect(plan.resetChips).toBe(false);
  });

  it('asks for a brand new mix when a reload was requested', () => {
    const plan = planFeedLoad({ wanted: true, chipParams: null });
    expect(plan.reload).toBe(true);
    expect(plan.force).toBe(true);
  });

  it('reloads the plain feed, not the category that was on screen', () => {
    // A reload that kept the chip filter would refresh the category over
    // and over - which is what "clicking Home gives me a different mix"
    // must not do.
    const plan = planFeedLoad({ wanted: true, chipParams: 'EgZ2aWRlb3PyBgQKAjoA' });
    expect(plan.params).toBe(null);
    expect(plan.resetChips).toBe(true);
    expect(plan.force).toBe(true);
  });

  it('is spent only by the feed that was asked for', () => {
    requestFeedRefresh('home');
    expect(planFeedLoad({ wanted: wantsFeedRefresh('home'), chipParams: null }).reload).toBe(true);
    expect(planFeedLoad({ wanted: wantsFeedRefresh('trending'), chipParams: null }).reload).toBe(false);
    doneFeedRefresh('home');
    // A plain return to the feed afterwards is served from the cache.
    expect(planFeedLoad({ wanted: wantsFeedRefresh('home'), chipParams: null }).reload).toBe(false);
  });
});
