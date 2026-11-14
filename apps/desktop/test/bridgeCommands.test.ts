// Every command the renderer invokes must be one the shell registered.
//
// A name that exists on only one side does not fail to build: it fails at
// runtime as "Command homeMore not found", which looks like a broken feature
// rather than a typo. That is exactly how Home's "load more" shipped dead for
// several releases (`homeMore` in the bridge, `home_more` in the handler list),
// so the two sides are now compared here instead of by clicking.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const DESKTOP = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BRIDGE = path.join(DESKTOP, 'src', 'indio-tauri.ts');
const SHELL = path.join(DESKTOP, 'src-tauri', 'src', 'main.rs');

/** `go('name', …)`, `call<{…}>('name', …)` and `invoke('name', …)`. */
function invokedCommands(source: string): string[] {
  return [...source.matchAll(/\b(?:go|call|invoke)(?:<[^>]*>)?\(\s*'([a-z][a-z0-9_]*)'/g)]
    .map((m) => m[1]);
}

/** The `generate_handler![ … ]` list, with each entry's command name. */
function registeredCommands(main: string): string[] {
  const start = main.indexOf('generate_handler![');
  expect(start, 'main.rs has no generate_handler! list').toBeGreaterThan(-1);
  const end = main.indexOf(']', start);
  const block = main.slice(start + 'generate_handler!['.length, end);
  return [...block.matchAll(/^\s*(?:[a-z_][\w]*::)?([a-z_][\w]*)\s*,?\s*$/gm)].map((m) => m[1]);
}

describe('the window.indio surface matches the shell', () => {
  const invoked = invokedCommands(fs.readFileSync(BRIDGE, 'utf8'));
  const registered = registeredCommands(fs.readFileSync(SHELL, 'utf8'));

  it('found both lists to compare', () => {
    // A regex that stopped matching would make this test vacuously green.
    expect(invoked.length).toBeGreaterThan(25);
    expect(registered.length).toBeGreaterThan(25);
    expect(invoked).toContain('indio_init');
    expect(registered).toContain('home_more');
  });

  it('invokes no command the shell never registered', () => {
    const known = new Set(registered);
    expect(invoked.filter((name) => !known.has(name))).toEqual([]);
  });

  it('has no camelCase command names, which the shell never registers', () => {
    // Rust commands are snake_case; a camelCase string here is the migration
    // bug class, not a name that can exist.
    expect(invoked.filter((name) => /[A-Z]/.test(name))).toEqual([]);
  });

  it('pages the local Home mix through home_more', () => {
    expect(invoked).toContain('home_more');
    expect(invoked).not.toContain('homeMore');
  });
});
