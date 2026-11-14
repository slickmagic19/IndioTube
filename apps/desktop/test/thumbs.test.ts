import { describe, expect, it } from 'vitest';
import { isYtThumb, thumbUrlFor, thumbVariant } from '../src/thumbs';

describe('thumbVariant', () => {
  it('swaps a large YouTube poster for the card-sized one', () => {
    expect(thumbVariant('https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg', true))
      .toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg');
    expect(thumbVariant('https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg', true))
      .toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg');
  });

  it('keeps the signed query string on the URL', () => {
    expect(thumbVariant('https://i.ytimg.com/vi/abc123XYZ_-/hq720.jpg?sqp=abc&rs=def', true))
      .toBe('https://i.ytimg.com/vi/abc123XYZ_-/mqdefault.jpg?sqp=abc&rs=def');
  });

  it('leaves full-size posters alone when small mode is off', () => {
    const big = 'https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg';
    expect(thumbVariant(big, false)).toBe(big);
  });

  it('passes through non-YouTube and missing images untouched', () => {
    expect(thumbVariant('https://example.com/a.jpg', true)).toBe('https://example.com/a.jpg');
    expect(thumbVariant(null, true)).toBeNull();
    expect(thumbVariant(undefined, true)).toBeNull();
  });

  it('builds the canonical fallback for a video id', () => {
    expect(thumbUrlFor('dQw4w9WgXcQ', true)).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg');
    expect(thumbUrlFor('dQw4w9WgXcQ', false)).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg');
  });

  it('recognises YouTube poster URLs only', () => {
    expect(isYtThumb('https://i.ytimg.com/vi/x/hqdefault.jpg')).toBe(true);
    expect(isYtThumb('https://example.com/vi/x.jpg')).toBe(false);
    expect(isYtThumb(null)).toBe(false);
  });
});
