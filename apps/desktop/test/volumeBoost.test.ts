// The volume booster: sound above 100%.
//
// A media element cannot play louder than 100%, so the boost has to come from
// a Web Audio gain node. Two rules keep it from breaking the sound, and both
// are checked here:
//
//  * the element itself stays at 100% while the graph is attached, and the
//    gain node carries the viewer's volume - otherwise the volume would be
//    applied twice, or not at all;
//  * a muted element (the sync logic mutes the track while it is seeking)
//    has to be silent, whatever the gain says.
import { describe, expect, it } from 'vitest';
import {
  VOLUME_BOOST_GAIN,
  VolumeBooster,
  boostGain,
  boostedPercent,
  clampVolume,
  effectiveGain,
  type BoostAudioContext,
  type BoostElement,
} from '../src/player/volumeBoost';

/** A media element that only remembers what it was told. */
class FakeElement implements BoostElement {
  volume = 1;
  muted = false;
  listeners = new Map<string, Set<() => void>>();

  addEventListener(type: string, listener: () => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(listener);
  }

  removeEventListener(type: string, listener: () => void) {
    this.listeners.get(type)?.delete(listener);
  }

  /** What the browser does when the app changes the volume or the mute. */
  fire(type: string) {
    for (const listener of this.listeners.get(type) || []) listener();
  }
}

/** An audio context that records the graph it was asked for. */
class FakeContext implements BoostAudioContext {
  state = 'running';
  destination = { name: 'destination' };
  sources: unknown[] = [];
  gains: { gain: { value: number }; connected: unknown[] }[] = [];

  async resume() {
    this.state = 'running';
  }

  createMediaElementSource(element: unknown) {
    this.sources.push(element);
    return {
      connect: (node: unknown) => {
        this.gains[this.gains.length - 1]?.connected.push(node);
        return node;
      },
    };
  }

  createGain() {
    const gain = { gain: { value: 1 }, connected: [] as unknown[] };
    this.gains.push(gain);
    return {
      gain: gain.gain,
      connect: (node: unknown) => {
        gain.connected.push(node);
        return node;
      },
    };
  }
}

describe('the boost factor', () => {
  it('doubles the sound', () => {
    expect(VOLUME_BOOST_GAIN).toBe(2);
    expect(boostGain(true)).toBe(2);
    expect(boostGain(false)).toBe(1);
  });

  it('keeps the volume inside what an element accepts', () => {
    expect(clampVolume(-1)).toBe(0);
    expect(clampVolume(0.5)).toBe(0.5);
    expect(clampVolume(4)).toBe(1);
    expect(clampVolume(Number.NaN)).toBe(1);
  });

  it('multiplies the volume with the boost', () => {
    expect(effectiveGain(1, true, false)).toBe(2);
    expect(effectiveGain(0.5, true, false)).toBe(1);
    expect(effectiveGain(0.5, false, false)).toBe(0.5);
  });

  it('is silent while anything is muted', () => {
    expect(effectiveGain(1, true, true)).toBe(0);
  });

  it('reports how loud the viewer hears it', () => {
    expect(boostedPercent(1, false)).toBe(100);
    expect(boostedPercent(1, true)).toBe(200);
    expect(boostedPercent(0.5, true)).toBe(100);
  });
});

describe('the booster', () => {
  it('routes an element through a gain node', () => {
    const ctx = new FakeContext();
    const element = new FakeElement();
    const booster = new VolumeBooster();

    expect(booster.attach(ctx, element)).toBe(true);
    expect(booster.attached).toBe(true);
    expect(booster.owns(element)).toBe(true);
    expect(ctx.sources).toEqual([element]);
    // The element is not touched: the gain carries the loudness.
    expect(element.volume).toBe(1);
  });

  it('puts the loudness in the gain, not in the element', () => {
    const ctx = new FakeContext();
    const element = new FakeElement();
    const booster = new VolumeBooster();
    booster.attach(ctx, element);

    booster.setVolume(0.5);
    booster.setBoosted(true);
    expect(ctx.gains[0].gain.value).toBe(1);

    booster.setVolume(1);
    expect(ctx.gains[0].gain.value).toBe(2);
  });

  it('goes back to the plain volume when the booster is turned off', () => {
    const ctx = new FakeContext();
    const booster = new VolumeBooster();
    booster.attach(ctx, new FakeElement());

    booster.setVolume(0.4);
    booster.setBoosted(true);
    expect(ctx.gains[0].gain.value).toBe(0.8);

    booster.setBoosted(false);
    expect(ctx.gains[0].gain.value).toBe(0.4);
  });

  it('is silent while the viewer mutes', () => {
    const ctx = new FakeContext();
    const booster = new VolumeBooster();
    booster.attach(ctx, new FakeElement());

    booster.setVolume(1);
    booster.setBoosted(true);
    booster.setMuted(true);
    expect(ctx.gains[0].gain.value).toBe(0);

    booster.setMuted(false);
    expect(ctx.gains[0].gain.value).toBe(2);
  });

  it('follows the mute the sync logic puts on the element', () => {
    // The sync logic mutes the audio track while it is seeking. That mute is
    // the element's own, so the graph has to follow it - otherwise the track
    // would be heard out of sync.
    const ctx = new FakeContext();
    const element = new FakeElement();
    const booster = new VolumeBooster();
    booster.attach(ctx, element);
    booster.setVolume(1);
    booster.setBoosted(true);

    element.muted = true;
    element.fire('volumechange');
    expect(ctx.gains[0].gain.value).toBe(0);

    element.muted = false;
    element.fire('volumechange');
    expect(ctx.gains[0].gain.value).toBe(2);
  });

  it('refuses an element the browser will not route', () => {
    const ctx = new FakeContext();
    ctx.createMediaElementSource = () => { throw new Error('not allowed'); };
    const booster = new VolumeBooster();
    expect(booster.attach(ctx, new FakeElement())).toBe(false);
    expect(booster.attached).toBe(false);
  });

  it('does not route the same element twice', () => {
    const ctx = new FakeContext();
    const element = new FakeElement();
    const booster = new VolumeBooster();
    booster.attach(ctx, element);
    booster.attach(ctx, element);
    expect(ctx.sources.length).toBe(1);
  });

  it('serves the picture element and the audio element alike', () => {
    const ctx = new FakeContext();
    const video = new FakeElement();
    const audio = new FakeElement();
    const booster = new VolumeBooster();
    booster.attach(ctx, video);
    booster.attach(ctx, audio);
    expect(booster.owns(video)).toBe(true);
    expect(booster.owns(audio)).toBe(true);
    booster.setVolume(1);
    booster.setBoosted(true);
    expect(ctx.gains.every((g) => g.gain.value === 2)).toBe(true);
  });

  it('stops following an element it let go of', () => {
    const ctx = new FakeContext();
    const element = new FakeElement();
    const booster = new VolumeBooster();
    booster.attach(ctx, element);
    booster.dispose();
    expect(booster.attached).toBe(false);
    // The element is no longer watched, so a mute of its own does not change
    // anything any more.
    element.muted = true;
    element.fire('volumechange');
    expect(ctx.gains[0].gain.value).toBe(1);
  });
});
