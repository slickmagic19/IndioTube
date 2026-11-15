#!/usr/bin/env node
// Build the manifest the app checks for updates.
//
// The shell asks a fixed URL for a JSON manifest, compares the version in it
// with the running one, and downloads the installer from it. The manifest has
// to carry the minisign signature of that installer, so it is built here from
// the `.sig` file the Tauri signer writes next to it.
//
//   node scripts/build-update-manifest.js \
//     --version 0.1.45 \
//     --installer release/IndioTube-0.1.45-win.exe \
//     --url https://github.com/slickmagic19/IndioTube/releases/download/v0.1.45/IndioTube-0.1.45-win.exe \
//     --out release/latest.json
const fs = require('node:fs');
const path = require('node:path');

/** The key the shell looks the download up under. */
const TARGET = 'windows-x86_64';

/**
 * The manifest the updater plugin reads.
 *
 * The platform key has to be `<target>-<arch>`: "windows-x86_64" for the
 * Windows build this repository ships.
 */
function buildManifest(options) {
  const { version, url, signature, notes, date } = options;
  if (!version) throw new Error('the manifest needs a version');
  if (!url) throw new Error('the manifest needs a download url');
  if (!signature) throw new Error('the manifest needs the signature of the installer');
  const manifest = {
    version: String(version),
    notes: notes ? String(notes) : '',
    pub_date: date || new Date().toISOString(),
    platforms: {
      [TARGET]: { signature: String(signature).trim(), url: String(url) },
    },
  };
  return manifest;
}

/** The signature is the base64 text the signer wrote into `<installer>.sig`. */
function readSignature(installer) {
  const sigPath = `${installer}.sig`;
  if (!fs.existsSync(sigPath)) {
    throw new Error(`no signature next to the installer: ${sigPath} (run "tauri signer sign" first)`);
  }
  return fs.readFileSync(sigPath, 'utf8');
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) {
      args[key] = true;
    } else {
      args[key] = next;
      i += 1;
    }
  }
  return args;
}

function main(argv) {
  const args = parseArgs(argv);
  if (!args.version || !args.installer) {
    console.error('usage: build-update-manifest.js --version <v> --installer <exe> [--url <url>] [--out <file>]');
    process.exit(2);
  }
  const installer = path.resolve(String(args.installer));
  const url = args.url
    || `https://github.com/slickmagic19/IndioTube/releases/download/v${args.version}/${path.basename(installer)}`;
  const manifest = buildManifest({
    version: args.version,
    url,
    signature: readSignature(installer),
    notes: args.notes || '',
    date: args.date,
  });
  const out = path.resolve(String(args.out || path.join('release', 'latest.json')));
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`[updater] wrote ${out} (version ${manifest.version})`);
}

module.exports = { buildManifest, readSignature, parseArgs, TARGET };

if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch (e) {
    console.error(`[updater] ${e && e.message ? e.message : e}`);
    process.exit(1);
  }
}
