// "Reload the feed" has to outlive the navigation that asked for it.
//
// Clicking Home while already on Home can just fire an event, because the feed
// screen is mounted and listening. Clicking Home from a watch page navigates
// first: the screen that should honour the request does not exist yet when the
// click happens, so the intent is parked here and the screen that mounts for
// that feed picks it up. Without it, arriving from another page showed the
// cached mix and never asked YouTube for a new one.
export const FEED_REFRESH_EVENT = 'indio-feed-refresh';

let pending: string | null = null;

/** Ask `mode` to revalidate, and wake the copy that is already on screen. */
export function requestFeedRefresh(mode = 'home'): void {
  pending = mode;
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(FEED_REFRESH_EVENT));
}

/** True while a request for `mode` is still owed to it. */
export function wantsFeedRefresh(mode: string): boolean {
  return pending === mode;
}

/** The request has been served (loaded or failed); a plain return keeps the cache. */
export function doneFeedRefresh(mode: string): void {
  if (pending === mode) pending = null;
}

/** Test seam: the module outlives a screen, so its state has to be resettable. */
export function clearFeedRefresh(): void {
  pending = null;
}

export interface FeedLoadPlan {
  /** Fetch the feed again instead of serving the cached one. */
  reload: boolean;
  /** The category to ask for; null is the plain (unfiltered) feed. */
  params: string | null;
  /** Ask the shell for a brand new mix instead of the cached one. */
  force: boolean;
  /** Go back to the "All" chip: a reload is the plain feed, not a category. */
  resetChips: boolean;
}

/**
 * What to ask the shell for.
 *
 * A reload is always the plain feed: the chip row goes back to "All" and the
 * category the viewer happened to look at last is dropped. Otherwise the feed
 * that is on screen keeps its category and comes from the cache.
 */
export function planFeedLoad(opts: { wanted: boolean; chipParams: string | null }): FeedLoadPlan {
  if (opts.wanted) {
    return { reload: true, params: null, force: true, resetChips: true };
  }
  return { reload: false, params: opts.chipParams, force: false, resetChips: false };
}
