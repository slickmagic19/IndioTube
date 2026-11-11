// Small shared UI primitives: menu popup, image with fallback, spinner.
import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

export function Spinner() {
  return <div className="spin" aria-label="loading" />;
}

export function Menu({
  anchor, onClose, children, width, placement = 'below', label = 'Menu', className,
}: {
  anchor: DOMRect | null;
  onClose(): void;
  children: React.ReactNode;
  width?: number;
  placement?: 'below' | 'above';
  label?: string;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  const closeRef = useRef(onClose);
  const [pos, setPos] = useState<{ top?: number; bottom?: number; left: number } | null>(null);

  // Callers pass a fresh onClose each render (the player re-renders on every
  // tick); keeping it in a ref stops the focus/keyboard effects from
  // re-running and stealing focus back to the first item.
  useEffect(() => { closeRef.current = onClose; }, [onClose]);

  useEffect(() => {
    if (!anchor) return;
    const w = width || 220;
    const left = Math.max(8, Math.min(anchor.right - w, window.innerWidth - w - 8));
    if (placement === 'above') {
      setPos({ bottom: Math.max(8, window.innerHeight - anchor.top + 8), left });
    } else {
      setPos({ top: Math.min(anchor.bottom + 6, window.innerHeight - 280), left });
    }
  }, [anchor, width, placement]);

  // Remember where focus came from; Escape hands it back (clicking elsewhere
  // must keep the user's new focus instead of yanking it back).
  useEffect(() => {
    restoreRef.current = document.activeElement as HTMLElement;
  }, []);

  // First item focused once the panel is actually positioned (a hidden or
  // not-yet-mounted element cannot take focus); arrows cycle, Escape closes.
  useEffect(() => {
    if (!pos) return;
    const items = () => Array.from(
      (ref.current || document).querySelectorAll<HTMLElement>('button:not(:disabled), input, [tabindex="0"]'),
    );
    items()[0]?.focus();
    const onKey = (e: KeyboardEvent) => {
      const list = items();
      if (!list.length) return;
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return; // typing, not navigating
      const idx = list.indexOf(document.activeElement as HTMLElement);
      if (e.key === 'ArrowDown') { e.preventDefault(); list[(idx + 1) % list.length].focus(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); list[(idx - 1 + list.length) % list.length].focus(); }
      else if (e.key === 'Home') { e.preventDefault(); list[0].focus(); }
      else if (e.key === 'End') { e.preventDefault(); list[list.length - 1].focus(); }
      else if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        closeRef.current();
        restoreRef.current?.focus?.({ preventScroll: true });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [pos]);

  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      // Synthetic events (and some native drags) carry a non-Node target;
      // `contains` throws on those, so treat them as an outside click.
      const target = e.target instanceof Node ? e.target : null;
      if (ref.current && !(target && ref.current.contains(target))) closeRef.current();
    };
    window.addEventListener('mousedown', onDown);
    return () => window.removeEventListener('mousedown', onDown);
  }, []);

  if (!anchor || !pos) return null;
  // 'above' menus are capped to the space between the anchor and the top
  // edge so a long list scrolls inside the panel instead of leaving the
  // screen, exactly like YouTube's player menu. The cap also guarantees
  // the panel can never reach past the top edge (158 caption languages
  // used to push the header off-screen and clip the close button).
  const maxHeight = placement === 'above'
    ? Math.min(Math.max(180, anchor.top - 12), Math.max(180, window.innerHeight - anchor.top - 12))
    : Math.max(180, window.innerHeight - anchor.bottom - 12);
  // Portalled to <body>: a menu inside an animated/transformed ancestor (the
  // feed grids animate in) would otherwise resolve position:fixed against
  // that ancestor and land in the wrong place. React events still bubble
  // through the component tree, so clicks must not reach the card underneath.
  return createPortal(
    <div ref={ref} className={`menu${className ? ` ${className}` : ''}`} role="menu" aria-label={label}
      style={{ ...pos, width: width || 220, maxHeight }}
      onClick={(e) => e.stopPropagation()}
      onMouseDown={(e) => e.stopPropagation()}>
      {children}
    </div>,
    document.body,
  );
}

export function MenuHead({ children }: { children: React.ReactNode }) {
  return <div className="head">{children}</div>;
}

export function MenuSep() {
  return <div className="sep" role="separator" />;
}

export function Thumb({ src, alt, live, duration, members, fraction, fallbackSrc }: {
  src?: string | null;
  alt: string;
  live?: boolean;
  duration?: string;
  members?: boolean;
  fraction?: number | null;
  fallbackSrc?: string;
}) {
  const [current, setCurrent] = useState<string | null>(src || null);
  const [failed, setFailed] = useState(false);
  // A different video reusing this slot must not inherit the previous state.
  useEffect(() => { setCurrent(src || null); setFailed(false); }, [src]);
  const onError = () => {
    if (fallbackSrc && current !== fallbackSrc) { setCurrent(fallbackSrc); return; }
    setFailed(true);
  };
  return (
    <div className="thumb-wrap">
      {current && !failed ? (
        <img src={current} alt={alt} loading="lazy" onError={onError} />
      ) : (
        <div style={{ width: '100%', height: '100%', background: 'var(--chip)' }} />
      )}
      {members && <span className="members-tag">Members-only</span>}
      {live ? <span className="duration live">LIVE</span>
        : duration ? <span className="duration">{duration}</span> : null}
      {fraction != null && fraction > 0.02 && fraction < 0.98 && (
        <div className="progress-bar"><i style={{ width: `${Math.round(fraction * 100)}%` }} /></div>
      )}
    </div>
  );
}

export function Avatar({ name, src, size = 36 }: { name?: string | null; src?: string | null; size?: number }) {
  const [failed, setFailed] = useState(false);
  const letter = (name || '?').trim().charAt(0).toUpperCase() || '?';
  if (src && !failed) {
    return <img src={src} alt="" style={{ width: size, height: size, borderRadius: '50%', objectFit: 'cover' }}
      onError={() => setFailed(true)} />;
  }
  return (
    <div style={{
      width: size, height: size, minWidth: size, borderRadius: '50%', background: 'var(--chip)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 600,
      fontSize: size * 0.42, color: 'var(--text-dim)',
    }}>{letter}</div>
  );
}
