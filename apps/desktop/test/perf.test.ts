import { afterEach, describe, expect, it } from 'vitest';
import { decideLite, perfLite, readPerfMode, resolveLite, setPerfMode } from '../src/perf';

describe('decideLite', () => {
  it('turns lite mode on for a tablet-class chip', () => {
    // Atom x5-z8350 class: four cores, 2-4 GiB of memory.
    expect(decideLite({ cores: 4, memory: 4 })).toBe(true);
    expect(decideLite({ cores: 4, memory: 2 })).toBe(true);
  });

  it('turns it on from either signal alone', () => {
    expect(decideLite({ cores: 8, memory: 4 })).toBe(true);
    expect(decideLite({ cores: 4, memory: 16 })).toBe(true);
  });

  it('leaves a normal desktop in full mode', () => {
    expect(decideLite({ cores: 8, memory: 16 })).toBe(false);
    expect(decideLite({ cores: 16, memory: 8 })).toBe(false);
  });

  it('treats unreported hardware as capable', () => {
    expect(decideLite({})).toBe(false);
    expect(decideLite({ cores: null, memory: null })).toBe(false);
  });

  it('honours an explicit override in both directions', () => {
    expect(decideLite({ cores: 16, memory: 16, override: 'lite' })).toBe(true);
    expect(decideLite({ cores: 2, memory: 2, override: 'full' })).toBe(false);
    expect(decideLite({ cores: 16, memory: 16, override: 'nonsense' })).toBe(false);
  });
});

describe('resolveLite', () => {
  it('auto follows the hardware probe', () => {
    expect(resolveLite('auto', { cores: 4, memory: 4 })).toBe(true);
    expect(resolveLite('auto', { cores: 16, memory: 32 })).toBe(false);
  });

  it('an explicit mode wins over the hardware', () => {
    expect(resolveLite('lite', { cores: 16, memory: 32 })).toBe(true);
    expect(resolveLite('full', { cores: 2, memory: 2 })).toBe(false);
  });

  it('auto ignores a stale probe override', () => {
    expect(resolveLite('auto', { cores: 16, memory: 32, override: 'lite' })).toBe(false);
  });
});

// The Settings toggle: the mode must apply immediately, survive a relaunch and
// be readable again without the settings IPC.
describe('switching the performance mode', () => {
  const store = new Map<string, string>();
  const classes = new Set<string>();
  const events: string[] = [];
  const globals = globalThis as unknown as Record<string, unknown>;
  const saved = {
    localStorage: globals.localStorage,
    document: globals.document,
    window: globals.window,
  };

  globals.localStorage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { store.set(key, value); },
    removeItem: (key: string) => { store.delete(key); },
  };
  globals.document = {
    documentElement: {
      classList: {
        toggle: (name: string, on: boolean) => { if (on) classes.add(name); else classes.delete(name); },
      },
    },
  };
  globals.window = { dispatchEvent: (event: { type: string }) => { events.push(event.type); } };

  afterEach(() => {
    store.clear();
    classes.clear();
    events.length = 0;
    globals.localStorage = saved.localStorage;
    globals.document = saved.document;
    globals.window = saved.window;
  });

  it('applies lite/full at once and persists the choice', () => {
    setPerfMode('full');
    expect(perfLite()).toBe(false);
    expect(classes.has('perf-lite')).toBe(false);
    expect(readPerfMode()).toBe('full');

    setPerfMode('lite');
    expect(perfLite()).toBe(true);
    expect(classes.has('perf-lite')).toBe(true);
    expect(store.get('indio-perf')).toBe('lite');
    expect(events).toEqual(['indio-perf-changed', 'indio-perf-changed']);
  });

  it('auto clears the stored override', () => {
    setPerfMode('lite');
    setPerfMode('auto');
    expect(store.has('indio-perf')).toBe(false);
    expect(readPerfMode()).toBe('auto');
  });
});
