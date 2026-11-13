// Writes icons/icon.ico (a single 256x256, 32-bit BMP entry): a dark tile with
// a red play triangle. Run with `node make-icon.js`; tauri-build needs the file
// to generate the Windows resource.
const fs = require('node:fs');
const path = require('node:path');

const SIZE = 256;
const BG = [15, 15, 15, 255];
const ACCENT = [255, 0, 51, 255];

function pixel(x, y) {
  // Play triangle in the middle: for each row, the triangle spans from the
  // left edge to a diagonal that narrows towards the vertical centre.
  const cx = SIZE / 2;
  const cy = SIZE / 2;
  const half = SIZE * 0.26;
  const left = cx - SIZE * 0.16;
  const t = (y - (cy - half)) / (2 * half);
  if (t >= 0 && t <= 1) {
    const reach = left + SIZE * 0.42 * (1 - Math.abs(2 * t - 1));
    if (x >= left && x <= reach) return ACCENT;
  }
  return BG;
}

const header = Buffer.alloc(40);
header.writeUInt32LE(40, 0); // biSize
header.writeInt32LE(SIZE, 4); // biWidth
header.writeInt32LE(SIZE * 2, 8); // biHeight (XOR + AND)
header.writeUInt16LE(1, 12); // planes
header.writeUInt16LE(32, 14); // bit count
header.writeUInt32LE(0, 16); // BI_RGB

const xor = Buffer.alloc(SIZE * SIZE * 4);
for (let y = 0; y < SIZE; y += 1) {
  for (let x = 0; x < SIZE; x += 1) {
    const [r, g, b, a] = pixel(x, y);
    // BMP rows are bottom-up and stored BGRA.
    const row = SIZE - 1 - y;
    const offset = (row * SIZE + x) * 4;
    xor[offset] = b;
    xor[offset + 1] = g;
    xor[offset + 2] = r;
    xor[offset + 3] = a;
  }
}
const andMask = Buffer.alloc((SIZE / 8) * SIZE, 0);
const image = Buffer.concat([header, xor, andMask]);

const dir = Buffer.alloc(6);
dir.writeUInt16LE(0, 0);
dir.writeUInt16LE(1, 2); // icon
dir.writeUInt16LE(1, 4); // one image

const entry = Buffer.alloc(16);
entry[0] = 0; // width 256
entry[1] = 0; // height 256
entry[2] = 0;
entry[3] = 0;
entry.writeUInt16LE(1, 4);
entry.writeUInt16LE(32, 6);
entry.writeUInt32LE(image.length, 8);
entry.writeUInt32LE(6 + 16, 12);

const outDir = path.join(__dirname, 'icons');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'icon.ico'), Buffer.concat([dir, entry, image]));
console.log('wrote', path.join(outDir, 'icon.ico'));
