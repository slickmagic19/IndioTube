// Tiny hash router: #/watch/VIDEO, #/channel/ID, #/playlist/local/ID, ...
// Hash-based so an app reload lands back on the same page.
import { useEffect, useState } from 'react';

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
  return {
    page,
    id: segs[1] || '',
    mode: segs[2] || '',
    query: queryPart ? decodeURIComponent((queryPart.match(/q=([^&]*)/) || [, ''])[1]) : '',
  };
}

export function navigate(path: string) {
  if (window.location.hash === `#${path}`) {
    // Same target: force an update for callers that rely on re-navigation.
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  } else {
    window.location.hash = path;
  }
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
