// The volume booster: sound that is louder than the player's own slider.
//
// A media element can only play between 0% and 100%, so "louder than 100%" has
// to come from somewhere else. The Web Audio API can do it: routing the
// element through a gain node multiplies the signal, so a slider at 100% can
// come out at 200%.
//
// Three rules keep this from breaking the sound:
//
//  * the element itself stays at 100% while the graph is attached, and the
//    gain node carries the viewer's volume instead - otherwise the volume
//    would be applied twice (or not at all, depending on the browser);
//  * each routed element has a gain of its own. Above 360p the sound arrives
//    as a second file and the picture element is muted on purpose, so one
//    muted element must never silence the one that is carrying the sound;
//  * the graph is only built when the audio context can actually run. A
//    suspended context would silence the element it routes, so the sound
//    never depends on it.
//
// The rules live here, away from the player, so they can be tested without a
// browser and without an audio device.

/** How much louder the booster makes the sound (100% slider -> 200% output). */
export const VOLUME_BOOST_GAIN = 2;

/** The gain the graph runs at: 2 with the booster on, 1 with it off. */
export function boostGain(enabled: boolean): number {
  return enabled ? VOLUME_BOOST_GAIN : 1;
}

/** The volume an element accepts (the browser clamps anything outside 0..1). */
export function clampVolume(volume: number): number {
  if (!Number.isFinite(volume)) return 1;
  return Math.min(1, Math.max(0, volume));
}

/**
 * The gain the graph runs at: the viewer's volume, times the boost, and zero
 * while anything is muted.
 */
export function effectiveGain(volume: number, boosted: boolean, muted: boolean): number {
  if (muted) return 0;
  return clampVolume(volume) * boostGain(boosted);
}

/** How loud the viewer hears it, as a percentage of the ordinary maximum. */
export function boostedPercent(volume: number, boosted: boolean): number {
  return Math.round(clampVolume(volume) * boostGain(boosted) * 100);
}

export interface BoostGainNode {
  gain: { value: number };
  connect(node: unknown): unknown;
}

export interface BoostSourceNode {
  connect(node: unknown): unknown;
}

export interface BoostAudioContext {
  state: string;
  destination: unknown;
  resume(): Promise<void>;
  createMediaElementSource(element: unknown): BoostSourceNode;
  createGain(): BoostGainNode;
}

export interface BoostElement {
  volume: number;
  muted: boolean;
  addEventListener?: (type: string, listener: () => void) => void;
  removeEventListener?: (type: string, listener: () => void) => void;
}

/**
 * Routes media elements through a gain node.
 *
 * One booster serves the whole player: the picture element and the separate
 * audio element both end up in the graph, and whichever one is carrying the
 * sound is the one that is heard. Elements that are silent anyway (a picture
 * without a sound track) do not care about their gain.
 */
export class VolumeBooster {
  private ctx: BoostAudioContext | null = null;
  private gains = new Map<unknown, BoostGainNode>();
  private listeners = new Map<unknown, () => void>();
  private volume = 1;
  private boosted = false;
  private userMuted = false;

  /** True when at least one element is routed through the graph. */
  get attached(): boolean {
    return this.gains.size > 0;
  }

  /** True when this element is routed through the graph. */
  owns(element: unknown): boolean {
    return this.gains.has(element);
  }

  /** The gain the graph is running at right now, ignoring mute. */
  get gain(): number {
    return effectiveGain(this.volume, this.boosted, this.userMuted);
  }

  /**
   * The gain of one routed element.
   *
   * A muted element is silent on its own; its gain is zeroed as well so the
   * intent is explicit. The other elements keep their gain either way - the
   * picture element of a split stream is muted for the whole video and still
   * must not take the sound with it.
   */
  private elementGain(element: unknown): number {
    return effectiveGain(this.volume, this.boosted, this.userMuted || Boolean((element as BoostElement)?.muted));
  }

  /**
   * Route `element` through the graph. Returns false when the browser refuses
   * (no Web Audio, or the element is already routed somewhere else) - the
   * player then simply keeps the ordinary volume.
   */
  attach(ctx: BoostAudioContext, element: unknown): boolean {
    if (!ctx || !element) return false;
    if (this.gains.has(element)) {
      this.apply();
      return true;
    }
    if (!this.ctx) this.ctx = ctx;
    try {
      const source = ctx.createMediaElementSource(element);
      const gain = ctx.createGain();
      gain.gain.value = this.elementGain(element);
      source.connect(gain);
      gain.connect(ctx.destination);
      this.gains.set(element, gain);
      // The element's own mute is the sync logic's business (it mutes the
      // track while it is seeking), so the graph has to follow it.
      const listener = () => this.apply();
      (element as BoostElement).addEventListener?.('volumechange', listener);
      this.listeners.set(element, listener);
      this.apply();
      return true;
    } catch {
      return false;
    }
  }

  setVolume(volume: number): void {
    this.volume = clampVolume(volume);
    this.apply();
  }

  setBoosted(on: boolean): void {
    this.boosted = on;
    this.apply();
  }

  setMuted(on: boolean): void {
    this.userMuted = on;
    this.apply();
  }

  /** Push the current levels into the graph. */
  apply(): void {
    for (const [element, node] of this.gains) node.gain.value = this.elementGain(element);
  }

  /** Detach from the elements. The graph itself cannot be undone, so the
   *  gain is left at the viewer's volume: the sound stays the same. */
  dispose(): void {
    for (const [element, listener] of this.listeners) {
      (element as BoostElement).removeEventListener?.('volumechange', listener);
    }
    this.listeners.clear();
    this.gains.clear();
  }
}
