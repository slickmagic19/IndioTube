// The shell's side of the update conversation.
//
// The Tauri updater plugin owns the manifest, the signature check and the
// installer; this file is the thin layer the app talks to. The update handle
// it returns holds a resource on the shell side, so it is kept until it is
// installed (or replaced by a newer check).
import { check, type Update } from '@tauri-apps/plugin-updater';
import { updater, updateCheckError, type UpdateInfo, type UpdaterBackend } from './updater';
import { isTauriShell } from './indio-tauri';

let pending: Update | null = null;

export const tauriUpdaterBackend: UpdaterBackend = {
  async check(): Promise<UpdateInfo | null> {
    let update: Update | null = null;
    try {
      update = await check();
    } catch (e) {
      // The transport's own words are not for the screen.
      throw new Error(updateCheckError(e));
    }
    if (!update) {
      pending = null;
      return null;
    }
    // A check that is not installed must not hold on to the shell's resource.
    if (pending && pending !== update) await pending.close().catch(() => undefined);
    pending = update;
    return {
      version: update.version,
      currentVersion: update.currentVersion,
      notes: update.body || '',
    };
  },

  async install(): Promise<void> {
    const update = pending;
    if (!update) throw new Error('The update has to be checked before it can be installed');
    pending = null;
    // On Windows the shell hands over to the installer and the app closes;
    // the installer restarts it when it is done.
    await update.downloadAndInstall();
  },
};

/**
 * Wire the shell into the shared update state and ask whether there is
 * something newer. A check that fails is not an error the viewer has to see:
 * the sidebar stays quiet and the settings page explains it.
 */
export async function startUpdateCheck(): Promise<void> {
  if (!isTauriShell()) return;
  updater.setBackend(tauriUpdaterBackend);
  await updater.check();
}
