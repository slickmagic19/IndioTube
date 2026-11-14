// The message layer for transport failures: what a screen may show, and what
// must never reach one (request URLs, API keys, socket-level noise).
import { describe, expect, it } from 'vitest';
import { friendlyError, isNetworkError } from '../src/errors';

const NETWORK_SENTENCE = 'Couldn’t reach YouTube. Check your connection and try again.';

// The exact shape of the error in the field, including the duplicated
// "Network Error:" prefix and the full InnerTube URL ureq puts in Display.
const FIELD_ERROR =
  'YouTube request failed: https://www.youtube.com/youtubei/v1/next?prettyPrint=false&key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8 '
  + 'Network Error: Network Error: Error encountered in the status line: A connection attempt failed because the connected party '
  + 'did not properly respond after a period of time, or established connection failed because connected host has failed to respond. (os error 10060)';

describe('friendlyError', () => {
  it('turns the Windows connection timeout into one actionable sentence', () => {
    const message = friendlyError(FIELD_ERROR);
    expect(message).toBe(NETWORK_SENTENCE);
    expect(message).not.toContain('http');
    expect(message).not.toContain('AIzaSy');
    expect(message).not.toContain('os error');
  });

  it('covers the other connection-level failures', () => {
    for (const raw of [
      'Could not reach YouTube: Network Error',
      'request timed out',
      'connection reset by peer',
      'unexpected eof',
      'upstream request failed: socket closed',
      'DNS lookup failed',
      'os error 10054',
    ]) {
      expect(friendlyError(raw)).toBe(NETWORK_SENTENCE);
    }
  });

  it('covers yt-dlp extractor failures, which carry their own wording', () => {
    expect(friendlyError(
      'ERROR: Unable to download webpage: <urlopen error [Errno 11001] getaddrinfo failed>',
    )).toBe(NETWORK_SENTENCE);
    expect(friendlyError('ERROR: Unable to download webpage: timed out')).toBe(NETWORK_SENTENCE);
  });

  it('separates server failures and rate limiting', () => {
    expect(friendlyError('YouTube returned HTTP 503'))
      .toBe('YouTube is having trouble right now. Try again in a moment.');
    expect(friendlyError('YouTube returned HTTP 500'))
      .toBe('YouTube is having trouble right now. Try again in a moment.');
    expect(friendlyError('YouTube returned HTTP 429'))
      .toBe('YouTube is limiting requests from this device. Wait a minute and try again.');
  });

  it('keeps specific errors instead of flattening them', () => {
    expect(friendlyError('yt-dlp failed to start: spawn yt-dlp.exe ENOENT'))
      .toBe('yt-dlp failed to start: spawn yt-dlp.exe ENOENT');
    expect(friendlyError('This video has no downloadable stream without ads'))
      .toBe('This video has no downloadable stream without ads');
    expect(friendlyError('YouTube returned HTTP 403')).toBe('YouTube returned HTTP 403');
  });

  it('strips URLs from unknown errors and clamps long tails', () => {
    const message = friendlyError(`Unexpected view model at https://www.youtube.com/watch?v=abc ${'x'.repeat(400)}`);
    expect(message).not.toContain('http');
    expect(message.length).toBeLessThanOrEqual(160);
    expect(message.endsWith('…')).toBe(true);
  });

  it('accepts Error objects, strings and nothing at all', () => {
    expect(friendlyError(new Error('connection refused'))).toBe(NETWORK_SENTENCE);
    expect(friendlyError({ message: 'network error' })).toBe(NETWORK_SENTENCE);
    expect(friendlyError('')).toBe('Something went wrong. Please try again.');
    expect(friendlyError(null)).toBe('Something went wrong. Please try again.');
    expect(friendlyError(undefined)).toBe('Something went wrong. Please try again.');
  });

  it('reports whether the failure was connectivity', () => {
    expect(isNetworkError(FIELD_ERROR)).toBe(true);
    expect(isNetworkError('YouTube returned HTTP 403')).toBe(false);
    expect(isNetworkError('yt-dlp failed to start')).toBe(false);
  });
});
