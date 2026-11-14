// The recovery pane is what stands between a render failure and the window
// showing nothing but black. It is rendered to static markup here because the
// test runner has no DOM.
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { RECOVERY_MAX_MESSAGE, RecoveryPane, recoveryMessage } from '../src/ErrorBoundary';

describe('a render failure', () => {
  it('says something a viewer can act on and offers both ways out', () => {
    const html = renderToStaticMarkup(
      <RecoveryPane message="Cannot read properties of null" onReload={() => undefined} onRetry={() => undefined} />,
    );
    expect(html).toContain('Something went wrong');
    expect(html).toContain('Cannot read properties of null');
    expect(html).toContain('Reload');
    expect(html).toContain('Try again');
  });

  it('keeps a long error on one line that fits the pane', () => {
    const message = recoveryMessage(new Error(`line one\nline two ${'x'.repeat(500)}`));
    expect(message).not.toContain('\n');
    expect(message.length).toBe(RECOVERY_MAX_MESSAGE);
    expect(message.endsWith('…')).toBe(true);
  });

  it('always has something to show', () => {
    expect(recoveryMessage(new Error(''))).toBe('Error');
    expect(recoveryMessage(null)).toBe('Unknown error');
    expect(recoveryMessage(undefined)).toBe('Unknown error');
    expect(recoveryMessage('plain string')).toBe('plain string');
  });

  it('offers no retry when the caller has nothing to reset', () => {
    const dom = renderToStaticMarkup(
      <RecoveryPane message="boom" onReload={() => undefined} />,
    );
    expect(dom).toContain('Reload');
    expect(dom).not.toContain('Try again');
  });
});
