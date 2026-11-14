// Search must survive a single transient failure without showing the viewer a
// broken page. The retry helper is pure, so the policy is tested here rather
// than by waiting on the network.
import { describe, expect, it, vi } from 'vitest';
import {
  SEARCH_ATTEMPTS,
  SEARCH_RETRY_DELAY_MS,
  withSearchRetry,
} from '../src/searchLogic';

describe('withSearchRetry', () => {
  it('returns the first successful result without sleeping', async () => {
    const sleep = vi.fn(async () => undefined);
    const task = vi.fn(async () => 'results');
    await expect(withSearchRetry(task, { sleep })).resolves.toBe('results');
    expect(task).toHaveBeenCalledTimes(1);
    expect(sleep).not.toHaveBeenCalled();
  });

  it('retries a transient rejection and returns the later result', async () => {
    const sleep = vi.fn(async () => undefined);
    const task = vi.fn()
      .mockRejectedValueOnce(new Error('HTTP 502'))
      .mockResolvedValueOnce('results');
    await expect(withSearchRetry(task, { sleep })).resolves.toBe('results');
    expect(task).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledWith(SEARCH_RETRY_DELAY_MS);
  });

  it('backs off further on each attempt', async () => {
    const delays: number[] = [];
    const task = vi.fn()
      .mockRejectedValueOnce(new Error('one'))
      .mockRejectedValueOnce(new Error('two'))
      .mockResolvedValueOnce('results');
    await withSearchRetry(task, {
      attempts: 3,
      delayMs: 100,
      sleep: async (ms) => { delays.push(ms); },
    });
    expect(delays).toEqual([100, 200]);
  });

  it('gives up after the configured attempts and rethrows the last error', async () => {
    const sleep = vi.fn(async () => undefined);
    const task = vi.fn()
      .mockRejectedValueOnce(new Error('first'))
      .mockRejectedValueOnce(new Error('last'));
    await expect(withSearchRetry(task, { sleep })).rejects.toThrow('last');
    expect(task).toHaveBeenCalledTimes(SEARCH_ATTEMPTS);
    expect(sleep).toHaveBeenCalledTimes(SEARCH_ATTEMPTS - 1);
  });

  it('always makes at least one attempt', async () => {
    const task = vi.fn(async () => 'results');
    await expect(withSearchRetry(task, { attempts: 0 })).resolves.toBe('results');
    expect(task).toHaveBeenCalledTimes(1);
  });

  it('does not retry a valid empty answer', async () => {
    const task = vi.fn(async () => ({ entries: [], continuation: null }));
    await expect(withSearchRetry(task)).resolves.toEqual({ entries: [], continuation: null });
    expect(task).toHaveBeenCalledTimes(1);
  });
});
