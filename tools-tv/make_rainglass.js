// Rainy-window wallpapers: blurred blue light, soft bokeh and glass droplets. No dependencies.
'use strict';
const zlib = require('zlib'), fs = require('fs');
const W = 1920, H = 1080;
function crc32(b) { let c, r = ~0; for (const x of b) { c = (r ^ x) & 255; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; r = (r >>> 8) ^ c; } return ~r >>> 0; }
function chunk(t, d) { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); }
function png(px) { const s = W * 3 + 1, raw = Buffer.alloc(s * H); for (let y = 0; y < H; y++) { raw[y * s] = 0; px.copy(raw, y * s + 1, y * W * 3, (y + 1) * W * 3); } const ih = Buffer.alloc(13); ih.writeUInt32BE(W, 0); ih.writeUInt32BE(H, 4); ih[8] = 8; ih[9] = 2; return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]); }
let seed = 11; const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
function make(top, bot, glow, light, drops) {
  const f = new Float32Array(W * H * 3);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const v = y / H, o = (y * W + x) * 3; for (let i = 0; i < 3; i++) f[o + i] = top[i] + (bot[i] - top[i]) * v; }
  const add = (cx, cy, r, col, a, hard) => { const r2 = r * r, x0 = Math.max(0, cx - r | 0), x1 = Math.min(W - 1, cx + r | 0), y0 = Math.max(0, cy - r | 0), y1 = Math.min(H - 1, cy + r | 0); for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) { const d = ((x - cx) ** 2 + (y - cy) ** 2) / r2; if (d > 1) continue; const w = (hard ? (d > .8 ? (1 - d) / .2 : 1) : Math.exp(-d * 3)) * a, o = (y * W + x) * 3; for (let i = 0; i < 3; i++) f[o + i] += (col[i] - f[o + i]) * w; } };
  for (let i = 0; i < 9; i++) add(rnd() * W, rnd() * H, 260 + rnd() * 260, glow, .22 + rnd() * .2, false);   // out-of-focus light
  for (let i = 0; i < 70; i++) add(rnd() * W, rnd() * H, 18 + rnd() * 46, light, .05 + rnd() * .09, true);   // bokeh discs
  for (let i = 0; i < drops; i++) { const r = 5 + rnd() * rnd() * 30, cx = rnd() * W, cy = rnd() * H; add(cx, cy, r * 1.25, [10, 20, 40], .20, false); add(cx, cy, r, glow, .22, true); add(cx - r * .3, cy - r * .35, r * .32, [255, 255, 255], .55, false); }
  const px = Buffer.alloc(W * H * 3);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const vig = 1 - .30 * Math.pow(Math.hypot(x / W - .5, (y / H - .5) * .9) * 1.2, 2), o = (y * W + x) * 3; for (let i = 0; i < 3; i++) px[o + i] = Math.max(0, Math.min(255, Math.round(f[o + i] * vig + (rnd() + rnd() - 1) * 3))); }
  return png(px);
}
const out = process.argv[2]; fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(out + '/morning.png', make([92, 140, 170], [38, 78, 112], [120, 175, 205], [215, 235, 250], 260));
fs.writeFileSync(out + '/evening.png', make([84, 82, 140], [34, 38, 82], [150, 130, 200], [255, 205, 190], 260));
fs.writeFileSync(out + '/night.png', make([30, 52, 86], [10, 20, 40], [70, 120, 170], [170, 205, 235], 260));
console.log('done');
