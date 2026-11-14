#!/usr/bin/env node
// `npm run tauri:dev` — one command for the desktop app in development.
//
// The Tauri shell loads vite's dev server (src-tauri/tauri.conf.json's
// `build.devUrl`), so the UI has to be up before the window opens. Running that
// as Tauri's `beforeDevCommand` turned out to be fragile on Windows — the CLI's
// child shell does not always inherit a usable PATH, and the app comes up on a
// blank page — so this script starts vite itself, waits for the port, and then
// hands over to the Tauri CLI with the right Rust toolchain (see
// with-rust-env.js). Killing this process takes the dev server with it.
const { spawn } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');

const DEV_URL = process.env.INDIOTUBE_DEV_URL || 'http://127.0.0.1:5174';
const [, hostRaw, portRaw] = DEV_URL.match(/^https?:\/\/([^:/]+):?(\d+)?/) || [];
const HOST = hostRaw || '127.0.0.1';
const PORT = Number(portRaw || 5174);
const APP_DIR = path.join(__dirname, '..', 'apps', 'desktop');

function npm(args) {
  return spawn('npm', args, { cwd: APP_DIR, stdio: 'inherit', shell: process.platform === 'win32' });
}

function listening(retries) {
  return new Promise((resolve) => {
    const socket = net.connect(PORT, HOST);
    socket.once('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.once('error', () => {
      socket.destroy();
      if (retries <= 0) return resolve(false);
      setTimeout(() => resolve(listening(retries - 1)), 500);
    });
  });
}

async function main() {
  const vite = npm(['run', 'dev']);
  vite.on('exit', (code) => {
    if (code) console.error(`[dev] the vite server exited with ${code}`);
  });
  if (!(await listening(60))) {
    console.error(`[dev] nothing is listening on ${HOST}:${PORT} — is another dev server in the way?`);
    vite.kill();
    process.exit(1);
  }
  const tauri = await new Promise((resolve) => {
    const child = npm(['run', 'tauri:shell']);
    child.on('exit', (code) => resolve(code === null ? 1 : code));
    const stop = () => {
      try {
        vite.kill();
      } catch {
        /* already gone */
      }
    };
    process.on('SIGINT', () => {
      stop();
      child.kill();
    });
    process.on('SIGTERM', () => {
      stop();
      child.kill();
    });
  });
  vite.kill();
  process.exit(tauri);
}

main().catch((e) => {
  console.error('[dev]', e);
  process.exit(1);
});
