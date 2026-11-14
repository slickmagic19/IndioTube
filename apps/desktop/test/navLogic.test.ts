// Back/forward buttons: the browser only has a hash, so the availability rules
// are a small pure model. These pin the parts that are easy to get wrong: a
// new navigation must drop the redo branch, and a reload must keep it.
import { describe, expect, it } from 'vitest';
import {
  INITIAL_NAV,
  initialNav,
  navFlags,
  pushNav,
  syncNav,
} from '../src/navLogic';

describe('starting position', () => {
  it('starts at the first page with both buttons disabled', () => {
    expect(initialNav(null, null)).toEqual(INITIAL_NAV);
    expect(navFlags(INITIAL_NAV)).toEqual({ canBack: false, canForward: false });
  });

  it('prefers the current entry over the stored session', () => {
    expect(initialNav({ index: 1, top: 6 }, { index: 3, top: 4 })).toEqual({ index: 3, top: 4 });
  });

  it('uses the stored session when the entry has no model (first load)', () => {
    expect(initialNav({ index: 2, top: 4 }, null)).toEqual({ index: 2, top: 4 });
  });

  it('repairs junk instead of producing broken flags', () => {
    expect(initialNav({ index: -4, top: 9 }, { index: 1.5, top: 2 })).toEqual(INITIAL_NAV);
    expect(initialNav({ index: 2, top: 1 }, null)).toEqual({ index: 2, top: 2 });
  });
});

describe('following a link', () => {
  it('moves one step and enables Back', () => {
    const one = pushNav(INITIAL_NAV);
    expect(one).toEqual({ index: 1, top: 1 });
    expect(navFlags(one)).toEqual({ canBack: true, canForward: false });
  });

  it('truncates the forward branch when navigating after going back', () => {
    // Visit 0 -> 1 -> 2 -> 3, then go back twice to 1.
    let state = INITIAL_NAV;
    for (let i = 0; i < 3; i += 1) state = pushNav(state);
    state = syncNav(state, { index: 1, top: 3 });
    expect(navFlags(state)).toEqual({ canBack: true, canForward: true });

    // Following a new link from there must forget pages 2 and 3.
    const next = pushNav(state);
    expect(next).toEqual({ index: 2, top: 2 });
    expect(navFlags(next)).toEqual({ canBack: true, canForward: false });
  });
});

describe('browser traversal', () => {
  it('arriving on an older entry enables Forward', () => {
    const state = syncNav({ index: 3, top: 3 }, { index: 1, top: 3 });
    expect(state).toEqual({ index: 1, top: 3 });
    expect(navFlags(state)).toEqual({ canBack: true, canForward: true });
  });

  it('is a no-op when the entry carries no model', () => {
    const state = { index: 2, top: 5 };
    expect(syncNav(state, null)).toEqual(state);
    expect(syncNav(state, {})).toEqual(state);
  });

  it('keeps the known forward end when an older entry has no top', () => {
    expect(syncNav({ index: 3, top: 5 }, { index: 1 })).toEqual({ index: 1, top: 5 });
  });
});
