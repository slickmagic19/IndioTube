import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
// Builds `window.indio` on top of the Rust shell's IPC, and arms the dev smoke
// harness when the shell asks for it.
import './indio-tauri';
import './styles.css';
import { applyPerfMode } from './perf';

// Low-end machines get the cheap rendering mode before the first paint, so no
// blurred layer or transition is ever composited on them.
applyPerfMode();

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
