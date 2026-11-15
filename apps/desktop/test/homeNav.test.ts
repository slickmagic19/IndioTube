// The logo and the Home entry are one control: "the feed, and a fresh mix".
//
// The decision lives in its own module so it can be tested without a browser
// - the bug it guards against (a Home click that navigated but never
// refreshed, or refreshed but never navigated) is invisible in the markup.
import { describe, expect, it } from 'vitest';
import { HOME_HASHES, isHomeHash, planHomeClick } from '../src/homeNav';

describe('the Home button and the logo', () => {
  it('knows the hashes that mean "the feed is on screen"', () => {
    expect(HOME_HASHES).toEqual(['', '#/', '#/home']);
    expect(isHomeHash('')).toBe(true);
    expect(isHomeHash('#/')).toBe(true);
    expect(isHomeHash('#/home')).toBe(true);
    expect(isHomeHash('#/trending')).toBe(false);
    expect(isHomeHash('#/watch/abc')).toBe(false);
  });

  it('refreshes the feed in place when it is already on screen', () => {
    const plan = planHomeClick('#/home');
    expect(plan.refresh).toBe(true);
    // No navigation: the screen is already there, and navigating would only
    // throw away what the viewer is looking at.
    expect(plan.navigate).toBe(false);
  });

  it('refreshes and navigates when the feed is somewhere else', () => {
    for (const hash of ['#/watch/abc', '#/search?q=cats', '#/settings']) {
      const plan = planHomeClick(hash);
      expect(plan.refresh, `hash ${hash}`).toBe(true);
      expect(plan.navigate, `hash ${hash}`).toBe(true);
    }
  });

  it('treats the empty hash as the feed, because that is where the app opens', () => {
    const plan = planHomeClick('');
    expect(plan.navigate).toBe(false);
    expect(plan.refresh).toBe(true);
  });
});
