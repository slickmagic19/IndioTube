// Downloads the pinned yt-dlp release binary into apps/desktop/vendor/ytdlp/.
// Same pattern as Awitize's fetch-python.js: the desktop app ships/uses this
// sidecar for ad-free stream extraction, and keeping the download in one
// script means the exact build is reproducible.
//
//   node scripts/fetch-ytdlp.js            — download if missing/stale
//   node scripts/fetch-ytdlp.js --force    — re-download even if present
const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');

const PINNED_TAG = '2026.10.26';
const EXE_NAME = 'yt-dlp.exe';
const vendorDir = path.join(__dirname, '..', 'apps', 'desktop', 'vendor', 'ytdlp');
const target = path.join(vendorDir, EXE_NAME);
const marker = path.join(vendorDir, '.yt-dlp-version');
const downloadUrl =
    `https://github.com/yt-dlp/yt-dlp/releases/download/${PINNED_TAG}/${EXE_NAME}`;

function get(url, redirects = 5) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'user-agent': 'indiotube-desktop' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
        res.resume();
        return resolve(get(new URL(res.headers.location, url).toString(), redirects - 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
      }
      resolve(res);
    }).on('error', reject);
  });
}

async function main() {
  const force = process.argv.includes('--force');
  if (!force && fs.existsSync(target) && fs.existsSync(marker)) {
    const have = fs.readFileSync(marker, 'utf8').trim();
    if (have === PINNED_TAG) {
      console.log(`yt-dlp ${PINNED_TAG} already vendored at ${target}`);
      return;
    }
  }
  fs.mkdirSync(vendorDir, { recursive: true });
  console.log(`Downloading ${downloadUrl} ...`);
  const res = await get(downloadUrl);
  const tmp = `${target}.download`;
  await new Promise((resolve, reject) => {
    const out = fs.createWriteStream(tmp);
    res.pipe(out);
    out.on('finish', () => out.close(resolve));
    out.on('error', reject);
    res.on('error', (err) => { out.destroy(); reject(err); });
  });
  // Sanity check before replacing the working binary: the exe starts with the
  // MZ header and must be a plausible size (the release is ~12 MB).
  const buf = fs.readFileSync(tmp);
  if (buf.length < 4 * 1024 * 1024 || buf.readUInt16LE(0) !== 0x5a4d) {
    fs.unlinkSync(tmp);
    throw new Error('downloaded file does not look like a Windows executable');
  }
  try { if (fs.existsSync(target)) fs.unlinkSync(target); } catch { /* in use? rename below still works */ }
  fs.renameSync(tmp, target);
  fs.writeFileSync(marker, PINNED_TAG);
  console.log(`yt-dlp ${PINNED_TAG} ready: ${target}`);
}

main().catch((err) => {
  console.error('fetch-ytdlp failed:', err.message);
  process.exit(1);
});
