// The update feature has to be wired all the way through, or it fails at
// runtime in a way nobody can see: a missing plugin, a missing permission or
// a manifest the shell cannot read all look the same to a viewer - the Update
// button simply never appears.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
// eslint-disable-next-line @typescript-eslint/no-var-requires
import { buildManifest, TARGET } from '../../../scripts/build-update-manifest.js';

const DESKTOP = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CONFIG = path.join(DESKTOP, 'src-tauri', 'tauri.conf.json');
const CAPABILITIES = path.join(DESKTOP, 'src-tauri', 'capabilities', 'default.json');
const MAIN = path.join(DESKTOP, 'src-tauri', 'src', 'main.rs');

describe('the updater is wired into the shell', () => {
  const config = JSON.parse(fs.readFileSync(CONFIG, 'utf8'));
  const capabilities = JSON.parse(fs.readFileSync(CAPABILITIES, 'utf8'));
  const main = fs.readFileSync(MAIN, 'utf8');

  it('registers the updater plugin', () => {
    expect(main).toContain('tauri_plugin_updater');
  });

  it('lets the window use it', () => {
    expect(capabilities.permissions).toContain('updater:default');
  });

  it('knows where the manifest is and how to trust it', () => {
    const updater = config.plugins?.updater;
    expect(updater).toBeTruthy();
    expect(Array.isArray(updater.endpoints)).toBe(true);
    expect(updater.endpoints.length).toBeGreaterThan(0);
    for (const endpoint of updater.endpoints) {
      expect(endpoint).toMatch(/^https:\/\//);
    }
    // The signature key is what makes an update verifiable; without it the
    // shell refuses to build the updater.
    expect(typeof updater.pubkey).toBe('string');
    expect(updater.pubkey.length).toBeGreaterThan(32);
  });

  it('does not ask for update artifacts on every build', () => {
    // The signing key is only needed for a release, so a normal build must
    // not depend on it.
    expect(config.bundle?.createUpdaterArtifacts ?? false).toBe(false);
  });
});

describe('the published manifest', () => {
  it('carries the version, the download and the signature', () => {
    const manifest = buildManifest({
      version: '0.1.46',
      url: 'https://github.com/slickmagic19/IndioTube/releases/download/v0.1.46/IndioTube-0.1.46-win.exe',
      signature: '  RWSaF1kabc+signature=\n',
      notes: 'Fixes the thing',
      date: '2026-11-15T00:00:00Z',
    });
    expect(manifest.version).toBe('0.1.46');
    expect(manifest.notes).toBe('Fixes the thing');
    expect(manifest.pub_date).toBe('2026-11-15T00:00:00Z');
    // The platform key has to be the one the shell looks up.
    expect(Object.keys(manifest.platforms)).toEqual([TARGET]);
    expect(TARGET).toBe('windows-x86_64');
    expect(manifest.platforms[TARGET].url).toContain('IndioTube-0.1.46-win.exe');
    // The signature is base64 text: the line breaks around it must not end up
    // in the manifest.
    expect(manifest.platforms[TARGET].signature).toBe('RWSaF1kabc+signature=');
  });

  it('refuses a manifest that is missing half of it', () => {
    expect(() => buildManifest({ url: 'https://example.com/a.exe', signature: 'x' })).toThrow(/version/);
    expect(() => buildManifest({ version: '1.0.0', signature: 'x' })).toThrow(/url/);
    expect(() => buildManifest({ version: '1.0.0', url: 'https://example.com/a.exe' })).toThrow(/signature/);
  });
});
