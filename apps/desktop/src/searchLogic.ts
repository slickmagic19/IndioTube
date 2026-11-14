// Search retry policy.
//
// YouTube's search endpoint occasionally answers with a transient HTTP error
// or a dropped connection. A one-shot request turns that into an empty search
// page, which reads as "search is broken" even though a second attempt a
// moment later succeeds. The shell already retries three times; this is the
// renderer's shorter safety net for an IPC call that failed on the way in/out.
//
// The helper is pure (the sleep is injectable) so the policy is unit-tested.

export const SEARCH_ATTEMPTS = 2;
export const SEARCH_RETRY_DELAY_MS = 250;

export interface SearchRetryOptions {
  /** Total attempts, including the first one. Minimum 1. */
  attempts?: number;
  /** Base delay; each retry waits `delayMs * attempt`. */
  delayMs?: number;
  /** Injected in tests so a retry does not actually wait. */
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => {
  setTimeout(resolve, ms);
});

/**
 * Run `task` until it resolves, or throw the last error after `attempts`.
 * A retry only happens for a rejected promise: an empty result is a valid
 * answer from YouTube and must not be retried.
 */
export async function withSearchRetry<T>(
  task: () => Promise<T>,
  options: SearchRetryOptions = {},
): Promise<T> {
  const attempts = Math.max(1, Math.floor(options.attempts ?? SEARCH_ATTEMPTS));
  const delayMs = Math.max(0, options.delayMs ?? SEARCH_RETRY_DELAY_MS);
  const sleep = options.sleep ?? defaultSleep;
  let lastError: unknown;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await task();
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(delayMs * attempt);
    }
  }
  throw lastError;
}
