// SponsorBlock decisions, kept pure so the player's 120ms tick can be tested
// without a DOM. Same categories and labels as the Android app
// (SponsorBlockPrefs.kt / SponsorBlock.kt), so both front ends offer the same
// controls.
export interface SbSegment {
  category: string;
  segment: [number, number];
  UUID?: string;
}

export interface SponsorCategory {
  id: string;
  label: string;
  description: string;
}

export const SPONSOR_CATEGORIES: SponsorCategory[] = [
  { id: 'sponsor', label: 'Sponsor', description: 'Paid promotion, paid referrals and direct advertisements' },
  { id: 'selfpromo', label: 'Self-promotion', description: 'Unpaid promotion of the creator’s own merch, products or channels' },
  { id: 'interaction', label: 'Interaction', description: 'Like, subscribe and comment reminders' },
  { id: 'intro', label: 'Intro', description: 'Opening animation or title sequence' },
  { id: 'outro', label: 'Outro', description: 'End cards and credits' },
  { id: 'preview', label: 'Preview', description: 'Recap or preview of the video’s contents' },
  { id: 'music_offtopic', label: 'Non-music section', description: 'Non-music portions of a music video' },
  { id: 'filler', label: 'Filler', description: 'Tangential scenes added only for filler or humour' },
];

export const DEFAULT_SPONSOR_CATEGORIES = SPONSOR_CATEGORIES.map((c) => c.id);

export function sponsorLabel(category: string): string {
  const hit = SPONSOR_CATEGORIES.find((c) => c.id === category);
  if (hit) return hit.label;
  return category ? category.replace(/^\w/, (m) => m.toUpperCase()) : 'Sponsor';
}

/** Stable key for a segment; UUID when the API sent one (it always does). */
export function segmentKey(s: SbSegment): string {
  return s.UUID || `${s.category}:${s.segment[0]}-${s.segment[1]}`;
}

/**
 * The segment worth showing a manual "Skip" button for: the one covering the
 * playhead, or the next one starting within `leadSec`. Mirrors the Android
 * pill, which is visible slightly before the segment so there is time to tap.
 */
export function sponsorSegmentNear(
  segments: SbSegment[],
  positionSec: number,
  leadSec = 4,
): SbSegment | null {
  let soonest: SbSegment | null = null;
  for (const s of segments) {
    if (!s || !Array.isArray(s.segment)) continue;
    const [start, end] = s.segment;
    if (!(end > start)) continue;
    if (positionSec >= start && positionSec < end) return s;
    if (positionSec < start && start - positionSec <= leadSec) {
      if (!soonest || start < soonest.segment[0]) soonest = s;
    }
  }
  return soonest;
}

/**
 * The segment the playhead sits inside, unless it was already skipped or the
 * user undid that skip. With auto-skip off this is always null, so a seek back
 * into a segment never fights the viewer.
 */
export function autoSkipTarget(
  segments: SbSegment[],
  positionSec: number,
  skipped: Set<string>,
  autoSkip: boolean,
): SbSegment | null {
  if (!autoSkip) return null;
  return segments.find((s) => (
    s && Array.isArray(s.segment) && s.segment[1] > s.segment[0]
    && !skipped.has(segmentKey(s))
    && positionSec >= s.segment[0]
    && positionSec < s.segment[1]
  )) || null;
}
