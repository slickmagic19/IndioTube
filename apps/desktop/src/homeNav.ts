// What the logo and the Home entry mean.
//
// Both are the same control: "take me to the feed". The feed is only asked for a
// fresh mix when it is already on screen - that is, a click on the logo or on
// Home while you are looking at the feed. From anywhere else the control only
// navigates, so a detour through a watch page does not silently re-gather (and
// re-rank) the whole Home mix. Keeping the decision here means it can be tested
// without a browser, which is the only way to be sure the logo still works after
// a refactor.

/** The hashes that mean "the Home feed is on screen". */
export const HOME_HASHES = ['', '#/', '#/home'];

export function isHomeHash(hash: string): boolean {
  return HOME_HASHES.includes(hash);
}

export interface HomeClickPlan {
  /** Ask the feed for a fresh mix - only when it is already on screen. */
  refresh: boolean;
  /** Navigate to the Home feed - only when it is not on screen already. */
  navigate: boolean;
}

/**
 * The plan for one click on the logo or on Home.
 *
 * On Home: refresh the feed in place (a fresh weighted mix) without navigating.
 * From anywhere else: navigate to Home and leave the feed's own cache rules in
 * charge, so returning to Home is not forced through a re-gather.
 */
export function planHomeClick(hash: string): HomeClickPlan {
  const home = isHomeHash(hash);
  return { refresh: home, navigate: !home };
}