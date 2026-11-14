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
