// Turning transport failures into something a viewer can act on.
//
// The shell already retries connection-level failures, so a message that gets
// this far means every attempt failed (or the server answered with an error).
// What must never reach the screen is a raw ureq string: the request URL, the
// API key and the Windows socket text are noise to a viewer and can overflow a
// whole page. Every IPC failure is normalised here before a screen sees it.
const NETWORK =
  /could not reach youtube|network error|timed out|timeout|connection (?:attempt|reset|closed|refused)|conn(?:ection)? abort|unexpected eof|upstream request failed|socket|dns|network is unreachable|temporarily unavailable|unable to download webpage|urlopen error|getaddrinfo|failed to resolve|name or service not known|no route to host|os error (?:10051|10053|10054|10060|10061|11001|11002)|errno (?:10051|10053|10054|10060|10061|11001|11002)/i;

const SERVER = /\bHTTP 5\d\d\b|bad gateway|service unavailable|internal server error/i;
const RATE_LIMIT = /\bHTTP 429\b|too many requests/i;

const MAX_LENGTH = 160;

function messageOf(input: unknown): string {
  if (typeof input === 'string') return input;
  if (input && typeof input === 'object') {
    const message = (input as { message?: unknown }).message;
    if (typeof message === 'string') return message;
    const error = (input as { error?: unknown }).error;
    if (typeof error === 'string') return error;
  }
  return '';
}

/** Drop API URLs and collapse whitespace: neither is useful on screen. */
function stripNoise(text: string): string {
  return text
    .replace(/https?:\/\/\S+/g, '')
    .replace(/\s+/g, ' ')
    .replace(/^[\s:–-]+/, '')
    .trim();
}

/**
 * A short, human sentence for any error. Unknown messages keep their own text
 * (trimmed and clamped) so specific failures — a missing yt-dlp, a members-only
 * video — are not swallowed by a generic line.
 */
export function friendlyError(input: unknown): string {
  const raw = messageOf(input).trim();
  if (!raw) return 'Something went wrong. Please try again.';
  if (NETWORK.test(raw)) {
    return 'Couldn’t reach YouTube. Check your connection and try again.';
  }
  if (RATE_LIMIT.test(raw)) {
    return 'YouTube is limiting requests from this device. Wait a minute and try again.';
  }
  if (SERVER.test(raw)) {
    return 'YouTube is having trouble right now. Try again in a moment.';
  }
  const cleaned = stripNoise(raw);
  if (!cleaned) return 'Something went wrong. Please try again.';
  return cleaned.length > MAX_LENGTH ? `${cleaned.slice(0, MAX_LENGTH - 1).trimEnd()}…` : cleaned;
}

/** True when the message describes a connectivity problem rather than a refusal. */
export function isNetworkError(input: unknown): boolean {
  return NETWORK.test(messageOf(input));
}
