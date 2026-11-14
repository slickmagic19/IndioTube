// Back/forward availability for the hash router.
//
// A browser does not expose "is there a page behind this one?", and the
// renderer only changes the hash, so the router keeps a small model of where
// the viewer is. Every in-app navigation pushes one entry; the model is
// mirrored into `history.state` (one value per entry, which survives a reload)
// and sessionStorage (the fallback when an entry was not created by us).
//
// The rules are pure so the buttons can be tested without a browser.

export interface NavState {
  /** The history entry the viewer is on, 0 for the first page of the session. */
  index: number;
  /** The furthest entry the viewer has visited; forward stops here. */
  top: number;
}

export interface NavFlags {
  canBack: boolean;
  canForward: boolean;
}

export const INITIAL_NAV: NavState = { index: 0, top: 0 };

function asIndex(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null;
}

function asNav(value: Partial<NavState> | null | undefined): NavState | null {
  if (!value || typeof value !== 'object') return null;
  const index = asIndex(value.index);
  if (index === null) return null;
  const top = asIndex(value.top);
  return { index, top: top !== null && top >= index ? top : index };
}

/**
 * The position to start from on load: the current history entry wins because
 * it is the one the browser will return to, then sessionStorage fills in the
 * forward end of a reloaded session.
 */
export function initialNav(
  stored?: Partial<NavState> | null,
  entry?: Partial<NavState> | null,
): NavState {
  return asNav(entry) ?? asNav(stored) ?? { ...INITIAL_NAV };
}

/**
 * Record a new page. Anything that was ahead of the current entry is dropped,
 * exactly like a browser does when a link is followed after going back.
 */
export function pushNav(state: NavState): NavState {
  const index = state.index + 1;
  return { index, top: index };
}

/**
 * Adopt the browser's own entry, used after `popstate`/`hashchange`. An entry
 * written by an older build may lack `top`, in which case the known forward
 * end is kept instead of shrinking it.
 */
export function syncNav(
  state: NavState,
  entry?: Partial<NavState> | null,
): NavState {
  if (!entry || typeof entry !== 'object') return state;
  const index = asIndex(entry.index);
  if (index === null) return state;
  const top = asIndex(entry.top);
  return {
    index,
    top: top !== null && top >= index ? top : Math.max(state.top, index),
  };
}

/** Which buttons should be enabled for this position. */
export function navFlags(state: NavState): NavFlags {
  return {
    canBack: state.index > 0,
    canForward: state.index < state.top,
  };
}
