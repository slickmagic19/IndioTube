// What the logo and the Home entry mean.
//
// Both are the same control: "take me to the feed and give me a fresh mix".
// Whether the feed is already on screen decides one thing only - a navigation
// or no navigation - and the reload itself is the same either way. Keeping the
// decision here means it can be tested without a browser, which is the only
// way to be sure the logo still works after a refactor.

/** The hashes that mean "the Home feed is on screen". */
export const HOME_HASHES = ['', '#/', '#/home'];

export function isHomeHash(hash: string): boolean {
  return HOME_HASHES.includes(hash);
}

export interface HomeClickPlan {
  /** Ask the feed for a fresh mix (always: the logo is a refresh button). */
  refresh: boolean;
  /** Navigate to the Home feed - only when it is not on screen already. */
  navigate: boolean;
}

/**
 * The plan for one click on the logo or on Home.
 *
 * Clicking Home while already on Home refreshes the feed in place; clicking
 * it from anywhere else asks for the same fresh mix and then navigates, so
 * the feed that mounts finds the request waiting for it.
 */
export function planHomeClick(hash: string): HomeClickPlan {
  return { refresh: true, navigate: !isHomeHash(hash) };
}
