import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
// Builds `window.indio` on top of the Rust shell's IPC, and arms the dev smoke
// harness when the shell asks for it.
import './indio-tauri';
import './styles.css';
import { applyPerfMode } from './perf';
import { ErrorBoundary } from './ErrorBoundary';

// Low-end machines get the cheap rendering mode before the first paint, so no
// blurred layer or transition is ever composited on them.
applyPerfMode();

// A render error must never leave the window as a black rectangle: the
// boundary shows what happened and how to get back. index.html covers the case
// where the bundle itself never runs.
createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>,
);
