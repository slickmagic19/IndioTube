// "Is there a newer IndioTube, and can I install it?"
//
// The shell owns the update: it knows the published manifest, verifies the
// signature of the installer and runs it. This module is the renderer's side
// of that conversation - it asks the shell whether a newer build exists, keeps
// the answer around for the sidebar and the settings page, and starts the
// installation when the viewer asks for it.
//
// The state machine is separated from the shell on purpose: the rules (when
// is the Update button shown, what happens when the check fails) are tested
// without a shell and without a network.

export type UpdateStatus =
  | 'idle'
  | 'checking'
  | 'up-to-date'
  | 'available'
  | 'installing'
  | 'error';

export interface UpdateInfo {
  /** The version that is available, e.g. "0.1.46". */
  version: string;
  /** The version that is running. */
  currentVersion: string;
  /** The release notes, if the manifest carries any. */
  notes: string;
}

export interface UpdateState {
  status: UpdateStatus;
  info: UpdateInfo | null;
  error: string | null;
}

/** The shell calls this to answer "is there a newer build?". */
export interface UpdaterBackend {
  check(): Promise<UpdateInfo | null>;
  install(info: UpdateInfo): Promise<void>;
}

/**
 * The state the whole app shares: one check at startup, and the answer every
 * screen reads. The sidebar only offers the Update button when a newer build
 * really is there.
 */
export class UpdateController {
  private state: UpdateState = { status: 'idle', info: null, error: null };
  private listeners = new Set<(state: UpdateState) => void>();
  private backend: UpdaterBackend | null = null;
  private running: Promise<UpdateState> | null = null;

  getState(): UpdateState {
    return this.state;
  }

  subscribe(listener: (state: UpdateState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => { this.listeners.delete(listener); };
  }

  private publish(next: UpdateState) {
    this.state = next;
    for (const listener of this.listeners) listener(next);
  }

  /** Use this in the app; the tests hand in a fake. */
  setBackend(backend: UpdaterBackend | null) {
    this.backend = backend;
  }

  /**
   * Ask the shell whether a newer build exists. A check that fails is not a
   * broken app: the sidebar simply keeps quiet and the settings page says
   * what went wrong.
   */
  async check(): Promise<UpdateState> {
    if (!this.backend) return this.state;
    this.publish({ status: 'checking', info: this.state.info, error: null });
    try {
      const info = await this.backend.check();
      if (info) {
        this.publish({ status: 'available', info, error: null });
      } else {
        this.publish({ status: 'up-to-date', info: null, error: null });
      }
    } catch (e) {
      this.publish({
        status: 'error',
        info: null,
        error: e instanceof Error ? e.message : String(e),
      });
    }
    return this.state;
  }

  /**
   * Install the update. The shell downloads the installer, verifies it and
   * hands over to it - on Windows the app closes while the installer runs.
   */
  async install(): Promise<UpdateState> {
    const info = this.state.info;
    if (!info || !this.backend) return this.state;
    if (this.running) return this.running;
    this.publish({ status: 'installing', info, error: null });
    this.running = (async (): Promise<UpdateState> => {
      try {
        await this.backend!.install(info);
        this.publish({ status: 'installing', info, error: null });
      } catch (e) {
        this.publish({
          status: 'error',
          info,
          error: e instanceof Error ? e.message : String(e),
        });
      } finally {
        this.running = null;
      }
      return this.state;
    })();
    return this.running;
  }
}

/** The controller the app shares. The tests make their own. */
export const updater = new UpdateController();

/** True when the sidebar has to offer the Update button. */
export function showsUpdateButton(state: UpdateState): boolean {
  if (!state.info) return false;
  // A failed install keeps the button: the viewer can try again without
  // going to the settings first.
  return state.status === 'available' || state.status === 'error';
}

/** The label of the Update button: the version that is waiting. */
export function updateLabel(state: UpdateState): string {
  if (!state.info) return 'Update';
  return `Update to ${state.info.version}`;
}

/**
 * A sentence a viewer can act on, instead of the transport's own words.
 *
 * The raw failure of an update check is a URL and a socket error; what the
 * viewer needs to know is whether to try again later or to leave it alone.
 */
export function updateCheckError(input: unknown): string {
  const raw = (input instanceof Error ? input.message : String(input ?? '')).trim();
  if (!raw) return 'The update check failed.';
  if (/404|not found|no such/i.test(raw)) {
    return 'No update information has been published yet.';
  }
  if (/network|connect|timeout|timed out|dns|resolve|offline|unreachable/i.test(raw)) {
    return 'Could not reach the update server. Check your connection and try again.';
  }
  return raw.length > 160 ? `${raw.slice(0, 159).trimEnd()}…` : raw;
}
