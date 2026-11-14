#!/usr/bin/env node
// Run a Rust command (cargo or the Tauri CLI) with the toolchain this machine
// actually has.
//
// The desktop shell is Tauri, so building it needs a working Rust linker. A
// dev machine with Visual Studio Build Tools uses the default MSVC toolchain
// and nothing special is needed here. A machine without it falls back to the
// GNU toolchain plus a portable MinGW-w64 (a ~400 MB unzip instead of a
// multi-gigabyte Visual Studio install), which is how this repo is normally
// built. Override the MinGW location with INDIOTUBE_MINGW_BIN.
//
//   node scripts/with-rust-env.js cargo test --manifest-path src-tauri/Cargo.toml
//   node scripts/with-rust-env.js tauri dev
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const HOME = os.homedir();

function mingwBin() {
  const candidates = [
    process.env.INDIOTUBE_MINGW_BIN,
    path.join(HOME, 'tools', 'mingw2', 'mingw64', 'bin'),
    path.join(HOME, 'tools', 'mingw', 'mingw64', 'bin'),
    'C:\\msys64\\mingw64\\bin',
    'C:\\Tools\\mingw64\\bin',
  ].filter(Boolean);
  return candidates.find((dir) => fs.existsSync(path.join(dir, 'gcc.exe'))) || null;
}

function hasMsvcLinker() {
  const paths = (process.env.PATH || '').split(path.delimiter);
  return paths.some((dir) => {
    try {
      return fs.existsSync(path.join(dir, 'link.exe'));
    } catch {
      return false;
    }
  });
}

function hasGnuToolchain() {
  return [
    path.join(HOME, '.rustup', 'toolchains', 'stable-x86_64-pc-windows-gnu'),
    path.join(process.env.RUSTUP_HOME || '', 'toolchains', 'stable-x86_64-pc-windows-gnu'),
  ].some((dir) => dir && fs.existsSync(dir));
}

// `{ ...process.env }` is a plain object, and on Windows the real key is
// usually `Path`. Reading `env.PATH` off the copy would then be undefined and
// the child would inherit a PATH of "mingw64\bin;" — no node, no npm, no cargo.
// Always go through the key the parent actually has.
function lookUp(env, want) {
  return Object.keys(env).find((key) => key.toUpperCase() === want) || want;
}

function prependPath(env, dirs) {
  const key = lookUp(env, 'PATH');
  const current = env[key] || '';
  env[key] = [...dirs, current].filter(Boolean).join(path.delimiter);
  return env;
}

function env() {
  const env = { ...process.env };
  const cargoBin = path.join(HOME, '.cargo', 'bin');
  const extra = fs.existsSync(cargoBin) ? [cargoBin] : [];
  if (hasMsvcLinker()) return prependPath(env, extra);
  const mingw = mingwBin();
  if (!mingw || !hasGnuToolchain()) return prependPath(env, extra);
  const gcc = path.join(mingw, 'gcc.exe').replace(/\\/g, '/');
  env.RUSTUP_TOOLCHAIN = 'stable-x86_64-pc-windows-gnu';
  prependPath(env, [mingw, ...extra]);
  // An existing RUSTFLAGS (a release profile, say) is kept and appended to.
  const flagsKey = lookUp(env, 'RUSTFLAGS');
  env[flagsKey] = [env[flagsKey], `-C linker=${gcc}`].filter(Boolean).join(' ');
  return env;
}

function resolve(cmd) {
  if (cmd !== 'tauri') return cmd;
  const local = path.join(
    __dirname,
    '..',
    'apps',
    'desktop',
    'node_modules',
    '.bin',
    process.platform === 'win32' ? 'tauri.cmd' : 'tauri',
  );
  if (fs.existsSync(local)) return local;
  const root = path.join(
    __dirname,
    '..',
    'node_modules',
    '.bin',
    process.platform === 'win32' ? 'tauri.cmd' : 'tauri',
  );
  return fs.existsSync(root) ? root : 'tauri';
}

const [cmd, ...args] = process.argv.slice(2);
if (!cmd) {
  console.error('usage: node scripts/with-rust-env.js <cargo|tauri> [args…]');
  process.exit(2);
}
if (!hasMsvcLinker() && mingwBin()) {
  console.log('[rust-env] no MSVC linker found — building with the GNU toolchain');
}
const result = spawnSync(resolve(cmd), args, { stdio: 'inherit', env: env(), shell: process.platform === 'win32' });
process.exit(result.status === null ? 1 : result.status);
