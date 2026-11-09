// Auto-loading sentinel for grids. Attach the returned ref to a 1px element
// at the end of the list: whenever it comes within `rootMargin` of the
// scroll container's edge, `onVisible` runs. Re-observing on `deps` keeps the
// chain going when a page appends but the sentinel is still on screen.
import { useEffect, useRef } from 'react';

export function useInfiniteScroll(onVisible: () => void, deps: unknown[], rootMargin = '900px 0px') {
  const callback = useRef(onVisible);
  callback.current = onVisible;
  const node = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = node.current;
    if (!el || typeof IntersectionObserver === 'undefined') return undefined;
    const root = (el.closest('.content') as Element | null) || null;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) callback.current();
    }, { root, rootMargin });
    observer.observe(el);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return node;
}
