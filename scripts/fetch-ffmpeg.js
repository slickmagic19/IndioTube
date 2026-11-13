// Downloads a static ffmpeg build into apps/desktop/vendor/ffmpeg/.
// yt-dlp needs it to merge YouTube's separate video and audio streams into one
// playable file; the app looks for it next to the vendored yt-dlp, then in
// userData, then on PATH.
//
//   node scripts/fetch-ffmpeg.js           — download if missing
//   node scripts/fetch-ffmpeg.js --force   — re-download even if present
const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');
const { execFileSync } = require('node:child_process');

const URL_ZIP = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip';
const vendorDir = path.join(__dirname, '..', 'apps', 'desktop', 'vendor', 'ffmpeg');
const exe = path.join(vendorDir, 'ffmpeg.exe');

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
  if (!force && fs.existsSync(exe)) {
    console.log(`ffmpeg already vendored at ${exe}`);
    return;
  }
  fs.mkdirSync(vendorDir, { recursive: true });
  const zip = path.join(vendorDir, 'ffmpeg.zip');
  console.log(`Downloading ${URL_ZIP} ...`);
  const res = await get(URL_ZIP);
  await new Promise((resolve, reject) => {
    const out = fs.createWriteStream(zip);
    res.pipe(out);
    out.on('finish', () => out.close(resolve));
    out.on('error', reject);
    res.on('error', reject);
  });

  const outDir = path.join(vendorDir, 'extract');
  fs.rmSync(outDir, { recursive: true, force: true });
  // Windows ships Expand-Archive; no JS zip dependency needed.
  execFileSync('powershell', ['-NoProfile', '-Command',
    `Expand-Archive -Path '${zip}' -DestinationPath '${outDir}' -Force`], { stdio: 'inherit' });

  const findExe = (dir) => {
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name);
      const stat = fs.statSync(full);
      if (stat.isDirectory()) {
        const hit = findExe(full);
        if (hit) return hit;
      } else if (name.toLowerCase() === 'ffmpeg.exe') {
        return full;
      }
    }
    return null;
  };
  const found = findExe(outDir);
  if (!found) throw new Error('ffmpeg.exe not found in the downloaded archive');
  fs.copyFileSync(found, exe);
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.rmSync(zip, { force: true });
  const size = fs.statSync(exe).size;
  if (size < 10 * 1024 * 1024) throw new Error(`ffmpeg.exe looks too small (${size} bytes)`);
  console.log(`ffmpeg vendored at ${exe} (${Math.round(size / 1024 / 1024)} MB)`);
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
