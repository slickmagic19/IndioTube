// The Update button: only when there really is a newer build.
//
// The controller is the whole rule book of the feature - when the sidebar
// offers the button, what happens when the check fails, and what happens when
// the install starts - so it is tested with a fake shell instead of a network.
import { describe, expect, it, vi } from 'vitest';
import {
  UpdateController,
  showsUpdateButton,
  updateCheckError,
  updateLabel,
  type UpdateInfo,
  type UpdaterBackend,
} from '../src/updater';

const newer: UpdateInfo = {
  version: '0.1.46',
  currentVersion: '0.1.45',
  notes: 'Fixes the thing',
};

function backend(overrides: Partial<UpdaterBackend> = {}): UpdaterBackend {
  return {
    check: vi.fn(async () => newer),
    install: vi.fn(async () => undefined),
    ...overrides,
  };
}

describe('the update state', () => {
  it('starts out with nothing to say', () => {
    const controller = new UpdateController();
    expect(controller.getState().status).toBe('idle');
    expect(showsUpdateButton(controller.getState())).toBe(false);
  });

  it('offers the Update button when a newer build is there', async () => {
    const controller = new UpdateController();
    controller.setBackend(backend());
    await controller.check();
    expect(controller.getState().status).toBe('available');
    expect(showsUpdateButton(controller.getState())).toBe(true);
    expect(updateLabel(controller.getState())).toBe('Update to 0.1.46');
  });

  it('keeps quiet when there is nothing newer', async () => {
    const controller = new UpdateController();
    controller.setBackend(backend({ check: vi.fn(async () => null) }));
    await controller.check();
    expect(controller.getState().status).toBe('up-to-date');
    expect(showsUpdateButton(controller.getState())).toBe(false);
  });

  it('does not break the app when the check fails', async () => {
    const controller = new UpdateController();
    controller.setBackend(backend({ check: vi.fn(async () => { throw new Error('no network'); }) }));
    await controller.check();
    expect(controller.getState().status).toBe('error');
    expect(controller.getState().error).toBe('no network');
    // No button: there is nothing to install.
    expect(showsUpdateButton(controller.getState())).toBe(false);
  });

  it('tells every screen that watches it', async () => {
    const controller = new UpdateController();
    const seen: string[] = [];
    const off = controller.subscribe((state) => seen.push(state.status));
    controller.setBackend(backend());
    await controller.check();
    off();
    expect(seen).toContain('available');
    // Nothing arrives after unsubscribing.
    const before = seen.length;
    await controller.check();
    expect(seen.length).toBe(before);
  });

  it('installs the update it found', async () => {
    const shell = backend();
    const controller = new UpdateController();
    controller.setBackend(shell);
    await controller.check();
    await controller.install();
    expect(shell.install).toHaveBeenCalledWith(newer);
  });

  it('installs only once, however often the button is pressed', async () => {
    let release: () => void = () => undefined;
    const shell = backend({
      install: vi.fn(() => new Promise<void>((resolve) => { release = resolve; })),
    });
    const controller = new UpdateController();
    controller.setBackend(shell);
    await controller.check();
    const first = controller.install();
    const second = controller.install();
    expect(shell.install).toHaveBeenCalledTimes(1);
    release();
    await first;
    await second;
  });

  it('says what went wrong when the install fails', async () => {
    const controller = new UpdateController();
    controller.setBackend(backend({
      install: vi.fn(async () => { throw new Error('the installer refused to start'); }),
    }));
    await controller.check();
    await controller.install();
    expect(controller.getState().status).toBe('error');
    expect(controller.getState().error).toBe('the installer refused to start');
    // The update is still known, so the viewer can try again - and the button
    // is still there to press.
    expect(controller.getState().info).toEqual(newer);
    expect(showsUpdateButton(controller.getState())).toBe(true);
  });

  it('does nothing when there is no update to install', async () => {
    const shell = backend();
    const controller = new UpdateController();
    controller.setBackend(shell);
    await controller.install();
    expect(shell.install).not.toHaveBeenCalled();
  });
});

describe('what a failed check says', () => {
  it('explains that nothing has been published yet', () => {
    expect(updateCheckError(new Error('HTTP 404 Not Found')))
      .toBe('No update information has been published yet.');
  });

  it('explains a connection problem', () => {
    expect(updateCheckError(new Error('error sending request: dns error')))
      .toBe('Could not reach the update server. Check your connection and try again.');
    expect(updateCheckError('timed out')).toContain('Could not reach the update server');
  });

  it('keeps a message it does not recognise, without the noise', () => {
    expect(updateCheckError(new Error('the signature does not match'))).toBe('the signature does not match');
  });

  it('always says something', () => {
    expect(updateCheckError(new Error(''))).toBe('The update check failed.');
    expect(updateCheckError(null)).toBe('The update check failed.');
  });
});
