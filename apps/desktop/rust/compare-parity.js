// Compares the JavaScript parser output (`js-*.json`, written by parity-check.js)
// with the Rust output (`rust-*.json`, written by examples/parse_fixture.rs).
// `null`/`undefined` and missing keys are treated as equal, and numbers are
// compared after rounding, because JSON has no undefined.
const fs = require('node:fs');

function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === 'object') {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      const v = value[key];
      if (v === null || v === undefined) continue;
      out[key] = normalize(v);
    }
    return out;
  }
  if (typeof value === 'number') return Math.round(value * 1000) / 1000;
  return value;
}

function diff(a, b, path, out) {
  if (out.length >= 12) return out;
  const an = normalize(a);
  const bn = normalize(b);
  if (JSON.stringify(an) === JSON.stringify(bn)) return out;
  if (an && bn && typeof an === 'object' && typeof bn === 'object') {
    const keys = new Set([...Object.keys(an), ...Object.keys(bn)]);
    for (const key of keys) diff(an[key], bn[key], `${path}.${key}`, out);
    return out;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    const n = Math.max(a.length, b.length);
    for (let i = 0; i < n; i += 1) diff(a[i], b[i], `${path}[${i}]`, out);
    return out;
  }
  out.push({ path, js: an, rust: bn });
  return out;
}

const modes = process.argv.slice(2);
let failures = 0;
for (const mode of modes) {
  const jsFile = `js-${mode}.json`;
  const rustFile = `rust-${mode}.json`;
  if (!fs.existsSync(jsFile) || !fs.existsSync(rustFile)) {
    console.log(`SKIP ${mode}: missing ${!fs.existsSync(jsFile) ? jsFile : rustFile}`);
    continue;
  }
  const js = JSON.parse(fs.readFileSync(jsFile, 'utf8'));
  const rust = JSON.parse(fs.readFileSync(rustFile, 'utf8'));
  const differences = diff(js, rust, '$', []);
  if (differences.length) {
    failures += 1;
    console.log(`MISMATCH ${mode}`);
    for (const d of differences.slice(0, 6)) {
      console.log(`  ${d.path}: js=${JSON.stringify(d.js)} rust=${JSON.stringify(d.rust)}`);
    }
  } else {
    console.log(`MATCH ${mode}`);
  }
}
process.exit(failures ? 1 : 0);
