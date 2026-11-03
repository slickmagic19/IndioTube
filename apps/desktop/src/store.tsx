// App-wide React state: the desktop library (mirrored from the main process
// on every mutation), settings, and a small toast channel. Everything else
// fetches through window.indio directly.
import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { LibraryState, Settings } from './types';

interface Toast { msg: string; actionLabel?: string; action?: () => void; key: number }

interface AppCtx {
  lib: LibraryState | null;
  settings: Settings | null;
  saveSettings(patch: Partial<Settings>): void;
  toast(msg: string, actionLabel?: string, action?: () => void): void;
  op<T>(name: string, ...args: unknown[]): Promise<T>;
}

const Ctx = createContext<AppCtx>(null as any);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [lib, setLib] = useState<LibraryState | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [toast, setToast] = useState<Toast | null>(null);
  const toastTimer = useRef<number>(0);

  useEffect(() => {
    window.indio.library.get().then(setLib).catch(() => undefined);
    const off = window.indio.library.onChanged(setLib);
    return off;
  }, []);

  useEffect(() => {
    window.indio.settings.get().then((s) => {
      setSettings(s);
      document.documentElement.dataset.theme = s.theme === 'light' ? 'light' : 'dark';
    }).catch(() => undefined);
  }, []);

  const saveSettings = useCallback((patch: Partial<Settings>) => {
    window.indio.settings.set(patch).then((s) => {
      setSettings(s);
      if (patch.theme) document.documentElement.dataset.theme = patch.theme === 'light' ? 'light' : 'dark';
      try { localStorage.setItem('indio-theme', s.theme); } catch { /* private mode */ }
    }).catch(() => undefined);
  }, []);

  const toastFn = useCallback((msg: string, actionLabel?: string, action?: () => void) => {
    setToast({ msg, actionLabel, action, key: Date.now() });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 4200);
  }, []);

  // Mutations go through the main process; it broadcasts the new state back,
  // so op() only surfaces errors here rather than returning fresh data.
  const op = useCallback(async <T,>(name: string, ...args: unknown[]): Promise<T> => {
    return await (window.indio.library.op as any)(name, ...args) as T;
  }, []);

  const value = useMemo(() => ({ lib, settings, saveSettings, toast: toastFn, op }),
    [lib, settings, saveSettings, toastFn, op]);

  return (
    <Ctx.Provider value={value}>
      {children}
      {toast && (
        <div className="toast">
          <span>{toast.msg}</span>
          {toast.actionLabel && toast.action && (
            <button onClick={() => { toast.action!(); setToast(null); }}>{toast.actionLabel}</button>
          )}
        </div>
      )}
    </Ctx.Provider>
  );
}

export function useApp() { return useContext(Ctx); }
