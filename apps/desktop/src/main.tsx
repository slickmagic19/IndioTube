import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
// Builds `window.indio` on top of the Rust shell's IPC, and arms the dev smoke
// harness when the shell asks for it.
import './indio-tauri';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
