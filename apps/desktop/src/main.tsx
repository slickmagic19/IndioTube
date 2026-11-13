import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
// Defines window.indio when running under the Tauri shell; a no-op under
// Electron, where the preload script already provides it.
import './indio-tauri';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
