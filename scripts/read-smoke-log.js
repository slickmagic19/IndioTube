// Dev helper: pretty-print the smoke harness report out of a redirected log.
const fs = require('fs');
const file = process.argv[2];
let text = fs.readFileSync(file);
let s = text.toString('utf8');
if (!s.includes('[smoke] {')) s = text.toString('utf16le');
const i = s.indexOf('[smoke] {');
if (i < 0) { console.log('no report in', file); process.exit(1); }
const end = s.indexOf('\n[', i + 9);
const line = s.slice(i + 8, end < 0 ? undefined : end).trim();
const r = JSON.parse(line);
console.log('firsts   ', JSON.stringify(r.firsts));
console.log('routes   ', r.hash, 'cards=' + r.cards, 'nodes=' + r.nodes, 'skeletons=' + r.skeletons);
console.log('errors   ', JSON.stringify(r.errors));
for (const [k, v] of Object.entries(r)) {
  if (['firsts', 'hash', 'cards', 'nodes', 'skeletons', 'errors', 'text', 'media', 'contentScrollable'].includes(k)) continue;
  console.log(k.padEnd(9), JSON.stringify(v).slice(0, 220));
}
const samples = (r.media && r.media.samples) || [];
console.log('samples  ', samples.length, JSON.stringify(samples[0]), '->', JSON.stringify(samples[samples.length - 1]));
for (const [k, v] of Object.entries((r.media && r.media) || {})) {
  if (k === 'samples') continue;
  console.log('media.' + k.padEnd(6), JSON.stringify(v).slice(0, 220));
}
