// The last line of defence against a black window.
//
// A React error anywhere in the tree unmounts the whole app, and the player's
// dark backdrop then looks exactly like "the app only shows a black screen,
// nothing works". This catches it, says what happened, and offers the two ways
// out (retry the render, or reload the webview) instead of leaving the viewer
// with an unresponsive rectangle.
import React from 'react';

export const RECOVERY_MAX_MESSAGE = 240;

/** Keep a stack-free one-liner that fits the pane. */
export function recoveryMessage(error: unknown): string {
  const text = error instanceof Error
    ? error.message || error.name
    : String(error ?? 'Unknown error');
  const oneLine = text.replace(/\s+/g, ' ').trim();
  if (!oneLine) return 'Unknown error';
  return oneLine.length > RECOVERY_MAX_MESSAGE
    ? `${oneLine.slice(0, RECOVERY_MAX_MESSAGE - 1)}…`
    : oneLine;
}

export function RecoveryPane({
  message,
  onReload,
  onRetry,
}: {
  message: string;
  onReload: () => void;
  onRetry?: () => void;
}) {
  return (
    <div
      role="alert"
      style={{
        position: 'fixed', inset: 0, zIndex: 50,
        display: 'flex', flexDirection: 'column', gap: 12,
        alignItems: 'center', justifyContent: 'center',
        padding: 24, textAlign: 'center',
        background: '#0f0f0f', color: '#e5e5e5',
        font: '500 15px/1.5 system-ui, "Segoe UI", sans-serif',
      }}
    >
      <div style={{ fontSize: 20, fontWeight: 600 }}>Something went wrong</div>
      <div style={{ opacity: 0.65, maxWidth: 560, wordBreak: 'break-word' }}>{message}</div>
      <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            style={{
              font: 'inherit', padding: '8px 18px', borderRadius: 8,
              border: '1px solid #3f3f3f', background: 'transparent', color: '#e5e5e5',
              cursor: 'pointer',
            }}
          >
            Try again
          </button>
        )}
        <button
          type="button"
          onClick={onReload}
          style={{
            font: 'inherit', padding: '8px 18px', borderRadius: 8, border: 0,
            background: '#f1f1f1', color: '#0f0f0f', cursor: 'pointer',
          }}
        >
          Reload
        </button>
      </div>
    </div>
  );
}

interface State {
  error: unknown | null;
}

export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: unknown): State {
    return { error };
  }

  componentDidCatch(error: unknown): void {
    // The smoke harness reads window errors too; this keeps the cause visible
    // in a dev console even when the app has already replaced the tree.
    console.error('[indio] the UI failed to render:', error);
  }

  render(): React.ReactNode {
    if (this.state.error !== null) {
      return (
        <RecoveryPane
          message={recoveryMessage(this.state.error)}
          onReload={() => { window.location.reload(); }}
          onRetry={() => this.setState({ error: null })}
        />
      );
    }
    return this.props.children;
  }
}
