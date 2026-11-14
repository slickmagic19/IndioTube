// Low-end detection and the "lite" rendering mode.
//
// The target is a tablet-class chip (Intel Atom x5-z8350: four slow cores and a
// weak iGPU that must also decode 720p60). On those machines Chromium's
// compositor is the bottleneck for everything that is not the video itself, so
// the shell drops blurred layers, transitions, animations and shadow blurs when
// it detects one. Nothing here changes layout, colours or content.
//
// Mode is `auto` (follow the hardware), `lite` or `full`. The choice is stored
// in localStorage because it must be applied before the first paint, long
// before the settings IPC can answer:
//   auto  -> the hardware probe decides
//   lite  -> always lite, even on a fast desktop
//   full  -> never lite, even on a slow tablet
export const LITE_CLASS = 'perf-lite';
export const PERF_KEY = 'indio-perf';

export type PerfMode = 'auto' | 'lite' | 'full';

export interface PerfProbe {
  /** navigator.deviceMemory, in GiB (Chromium caps the reported value at 8). */
  memory?: number | null;
  /** navigator.hardwareConcurrency. */
  cores?: number | null;
  /** Stored override: 'lite' | 'full' | anything else. */
  override?: string | null;
}

/** Pure hardware policy, pinned by a unit test. */
export function decideLite(probe: PerfProbe): boolean {
  if (probe.override === 'lite') return true;
  if (probe.override === 'full') return false;
  if (typeof probe.memory === 'number' && probe.memory > 0 && probe.memory <= 4) return true;
  const cores = typeof probe.cores === 'number' && probe.cores > 0 ? probe.cores : 8;
  return cores <= 4;
}

/** Map an explicit mode plus the hardware probe to the rendered mode. */
export function resolveLite(mode: PerfMode, probe: PerfProbe): boolean {
  if (mode === 'lite') return true;
  if (mode === 'full') return false;
  return decideLite({ memory: probe.memory, cores: probe.cores, override: null });
}

function readOverride(): string | null {
  try {
    return localStorage.getItem(PERF_KEY);
  } catch {
    return null;
  }
}

export function readPerfMode(): PerfMode {
  const raw = readOverride();
  return raw === 'lite' || raw === 'full' ? raw : 'auto';
}

function hardware(): PerfProbe {
  if (typeof navigator === 'undefined') return {};
  const nav = navigator as Navigator & { deviceMemory?: number };
  return { memory: nav.deviceMemory ?? null, cores: nav.hardwareConcurrency ?? null };
}

// Resolved once at startup so the first paint is already in the right mode.
let lite = resolveLite(readPerfMode(), hardware());

/** Current effective mode. Read at render time so a toggle applies at once. */
export function perfLite(): boolean {
  return lite;
}

export function applyPerfMode(
  root: Element | null = typeof document !== 'undefined' ? document.documentElement : null,
): void {
  if (!root) return;
  root.classList.toggle(LITE_CLASS, lite);
}

/**
 * Persist and apply a mode immediately. Components read `perfLite()` while
 * rendering, so the event is what makes the open screens re-render with the new
 * choice (thumbnails, speculative warm-ups, the player poster).
 */
export function setPerfMode(mode: PerfMode): void {
  try {
    if (mode === 'auto') localStorage.removeItem(PERF_KEY);
    else localStorage.setItem(PERF_KEY, mode);
  } catch {
    /* storage unavailable: still applies for this session */
  }
  lite = resolveLite(mode, hardware());
  applyPerfMode();
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event('indio-perf-changed'));
  }
}
