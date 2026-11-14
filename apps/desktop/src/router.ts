// Tiny hash router: #/watch/VIDEO, #/channel/ID, #/playlist/local/ID, ...
// Hash-based so an app reload lands back on the same page.
//
// It also tracks back/forward availability: every in-app navigation is one
// history entry, and the little index/top model in `navLogic` is mirrored into
// `history.state` and sessionStorage so the top-bar buttons know when they can
// do anything.
import { useEffect, useState } from 'react';
import {
  initialNav, navFlags, pushNav, syncNav,
  type NavFlags, type NavState,
} from './navLogic';

export interface Route {
  page: string;
  id: string;
  mode: string;
  query: string;
}

export function parseHash(hash: string): Route {
  const raw = hash.replace(/^#\/?/, '');
  const [pathPart, queryPart] = raw.split('?');
  const segs = pathPart.split('/').filter(Boolean);
  const page = segs[0] || 'home';
  let query = '';
  if (queryPart) {
    const match = queryPart.match(/(?:^|&)q=([^&]*)/);
    try {
      query = match ? decodeURIComponent(match[1]) : '';
    } catch {
      // A malformed escape in the URL must not take the whole screen down.
      query = match ? match[1] : '';
    }
  }
  return {
    page,
    id: segs[1] || '',
    mode: segs[2] || '',
    query,
  };
}

const NAV_STORAGE_KEY = 'indio.nav';

let nav: NavState | null = null;

interface HistoryEnvelope {
  indio?: Partial<NavState>;
}

function readStoredNav(): Partial<NavState> | null {
  try {
    const raw = window.sessionStorage.getItem(NAV_STORAGE_KEY);
    return raw ? JSON.parse(raw) as Partial<NavState> : null;
  } catch {
    return null;
  }
}

function readEntryNav(): Partial<NavState> | null {
  const state = window.history.state as HistoryEnvelope | null;
  return state && typeof state === 'object' ? state.indio ?? null : null;
}

function currentNav(): NavState {
  if (!nav) {
    const entry = readEntryNav();
    // A fresh window has a single history entry. Ignore a stale session model
    // then, so Back does not look available before anything has been opened.
    const stored = !entry && window.history.length <= 1 ? null : readStoredNav();
    nav = initialNav(stored, entry);
  }
  return nav;
}

function rememberNav(next: NavState) {
  nav = next;
  try {
    window.sessionStorage.setItem(NAV_STORAGE_KEY, JSON.stringify(next));
  } catch {
    // Private mode / quota: the buttons still work for this page view.
  }
}

/**
 * Reconcile the model with the entry the browser just landed on. Entries the
 * router did not create (the very first load, or a bare `location.hash` write
 * such as the smoke harness) are adopted in place so the next navigation has
 * an index to move from.
 */
function syncFromLocation() {
  const entry = readEntryNav();
  if (entry) {
    rememberNav(syncNav(currentNav(), entry));
    return;
  }
  try {
    const state = (window.history.state || {}) as HistoryEnvelope;
    window.history.replaceState({ ...state, indio: currentNav() }, '', window.location.href);
  } catch {
    // replaceState can be denied in odd embeddings; routing still works.
  }
}

export function navigate(path: string) {
  const target = `#${path}`;
  if (window.location.hash === target) {
    // Same target: force an update for callers that rely on re-navigation.
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }
  const current = currentNav();
  const next = pushNav(current);
  rememberNav(next);
  try {
    const state = (window.history.state || {}) as HistoryEnvelope;
    // The entry being left must remember that a forward page now exists;
    // otherwise coming back to it would wrongly disable Forward. It also has
    // to shrink its `top` when a new branch replaces the old forward pages.
    window.history.replaceState(
      { ...state, indio: { index: current.index, top: next.top } },
      '',
      window.location.href,
    );
    // pushState both updates the hash and carries the model on the new entry.
    window.history.pushState({ ...state, indio: next }, '', target);
  } catch {
    // Odd embeddings can deny pushState; the hash alone still routes.
    window.location.hash = path;
  }
  // pushState does not fire hashchange, so tell the route hook the page moved.
  window.dispatchEvent(new HashChangeEvent('hashchange'));
}

export function historyBack() {
  if (navFlags(currentNav()).canBack) window.history.back();
}

export function historyForward() {
  if (navFlags(currentNav()).canForward) window.history.forward();
}

function onRouteChange(handler: () => void) {
  const onChange = () => {
    syncFromLocation();
    handler();
  };
  window.addEventListener('hashchange', onChange);
  window.addEventListener('popstate', onChange);
  return () => {
    window.removeEventListener('hashchange', onChange);
    window.removeEventListener('popstate', onChange);
  };
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    const detach = onRouteChange(onChange);
    syncFromLocation();
    return detach;
  }, []);
  return route;
}

export function useNavHistory(): NavFlags {
  const [flags, setFlags] = useState<NavFlags>(() => navFlags(currentNav()));
  useEffect(() => {
    const update = () => setFlags(navFlags(currentNav()));
    const detach = onRouteChange(update);
    syncFromLocation();
    update();
    return detach;
  }, []);
  return flags;
}
