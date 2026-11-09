// Desktop SponsorBlock controls: same categories/labels as Android and the
// same add-only skip rules (a skipped or undone segment never re-triggers).
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SPONSOR_CATEGORIES, SPONSOR_CATEGORIES, autoSkipTarget, segmentKey,
  sponsorLabel, sponsorSegmentNear,
} from '../src/player/sponsorLogic';

const seg = (category: string, start: number, end: number, UUID = `${category}-1`) => ({
  category, segment: [start, end] as [number, number], UUID,
});

describe('SponsorBlock categories', () => {
  it('offers the phone categories by default', () => {
    expect(SPONSOR_CATEGORIES.map((c) => c.id)).toEqual([
      'sponsor', 'selfpromo', 'interaction', 'intro', 'outro', 'preview', 'music_offtopic', 'filler',
    ]);
    expect(DEFAULT_SPONSOR_CATEGORIES).toEqual(SPONSOR_CATEGORIES.map((c) => c.id));
  });

  it('labels categories like the Android app', () => {
    expect(sponsorLabel('sponsor')).toBe('Sponsor');
    expect(sponsorLabel('music_offtopic')).toBe('Non-music section');
    expect(sponsorLabel('something_new')).toBe('Something_new');
  });

  it('keys a segment by UUID, falling back to its time range', () => {
    expect(segmentKey(seg('sponsor', 1, 2))).toBe('sponsor-1');
    expect(segmentKey({ category: 'intro', segment: [1, 2] })).toBe('intro:1-2');
  });
});

describe('manual skip pill', () => {
  const segments = [seg('intro', 5, 10), seg('sponsor', 30, 40)];

  it('appears inside a segment', () => {
    expect(sponsorSegmentNear(segments, 32)?.category).toBe('sponsor');
  });

  it('appears just before the next segment', () => {
    expect(sponsorSegmentNear(segments, 27)?.category).toBe('sponsor');
    expect(sponsorSegmentNear(segments, 25)).toBe(null);
  });

  it('picks the active segment over a later one', () => {
    expect(sponsorSegmentNear(segments, 9)?.category).toBe('intro');
  });
});

describe('autoSkipTarget', () => {
  const segments = [seg('sponsor', 10, 20), seg('outro', 50, 60)];

  it('does nothing when auto-skip is off', () => {
    expect(autoSkipTarget(segments, 15, new Set(), false)).toBe(null);
  });

  it('skips only inside a segment', () => {
    expect(autoSkipTarget(segments, 9.9, new Set(), true)).toBe(null);
    expect(autoSkipTarget(segments, 10, new Set(), true)?.category).toBe('sponsor');
    expect(autoSkipTarget(segments, 20, new Set(), true)).toBe(null);
  });

  it('never repeats a skipped segment, so seeking back is left alone', () => {
    const skipped = new Set([segmentKey(segments[0])]);
    expect(autoSkipTarget(segments, 15, skipped, true)).toBe(null);
    expect(autoSkipTarget(segments, 55, skipped, true)?.category).toBe('outro');
  });

  it('ignores malformed segments', () => {
    const bad = [
      { category: 'x', segment: [5, 5] as [number, number] },
      { category: 'y', segment: [7, 3] as [number, number] },
      seg('sponsor', 10, 20),
    ];
    expect(autoSkipTarget(bad, 11, new Set(), true)?.category).toBe('sponsor');
  });
});
