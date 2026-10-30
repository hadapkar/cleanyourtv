// Night landscape for the clock screensaver: starry sky, moon, a mountain, rolling hills and a pine
// forest, in blues. Drawn from code (no photos, no licences). Usage: node make_night_scene.js <out.png>
'use strict';
const zlib = require('zlib'), fs = require('fs');
const W = 1920, H = 1080, S = 2; // drawn at 2x and averaged down, for smooth edges
const w = W * S, h = H * S;
const buf = new Float32Array(w * h * 3);

let seed = 23;
const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
const mix = (a, b, t) => [0, 1, 2].map(i => a[i] + (b[i] - a[i]) * t);
function put(x, y, c, a = 1) {
  if (x < 0 || y < 0 || x >= w || y >= h) return;
  const o = (y * w + x) * 3;
  for (let i = 0; i < 3; i++) buf[o + i] += (c[i] - buf[o + i]) * a;
}

// Sky: deep navy at the top to a lighter blue at the horizon.
for (let y = 0; y < h; y++) {
  const c = mix([14, 22, 70], [58, 86, 170], Math.pow(y / (h * 0.75), 1.4));
  for (let x = 0; x < w; x++) put(x, y, c);
}
// Stars, denser and brighter higher up.
for (let i = 0; i < 520; i++) {
  const x = rnd() * w | 0, y = Math.pow(rnd(), 1.6) * h * 0.6 | 0, r = rnd() < 0.08 ? 3 : rnd() < 0.4 ? 2 : 1;
  const a = 0.35 + rnd() * 0.6;
  for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) if (dx * dx + dy * dy <= r * r) put(x + dx, y + dy, [255, 255, 255], a);
}
// Moon with a soft halo, upper right.
const mx = w * 0.74, my = h * 0.2, mr = h * 0.065;
for (let y = my - mr * 4 | 0; y < my + mr * 4; y++) for (let x = mx - mr * 4 | 0; x < mx + mr * 4; x++) {
  const d = Math.hypot(x - mx, y - my);
  if (d < mr) put(x, y, mix([250, 248, 235], [222, 226, 236], d / mr));
  else put(x, y, [150, 175, 240], 0.18 * Math.exp(-(d - mr) / (mr * 0.9)));
}

// A ridge as a height function, filled with a vertical gradient below it.
function ridge(heightAt, top, bottom) {
  for (let x = 0; x < w; x++) {
    const y0 = Math.max(0, heightAt(x) | 0);
    for (let y = y0; y < h; y++) put(x, y, mix(top, bottom, Math.min(1, (y - y0) / (h * 0.35))));
  }
}
// Distant mountain: one soft peak in the middle.
ridge(x => h * 0.52 - h * 0.30 * Math.exp(-Math.pow((x - w * 0.5) / (w * 0.13), 2)) + 8 * S * Math.sin(x / (37 * S)),
  [62, 88, 165], [40, 58, 125]);
// Rolling hills, nearer and darker.
const hills = [[0.62, 30, 0.0035, [48, 70, 145], [34, 50, 112]], [0.70, 26, 0.0050, [36, 54, 120], [26, 40, 95]],
  [0.78, 22, 0.0068, [27, 41, 98], [20, 31, 78]]];
for (const [base, amp, f, a, b] of hills) {
  const p = rnd() * 6;
  ridge(x => h * base + amp * S * Math.sin(x / S * f + p) + amp * 0.5 * S * Math.sin(x / S * f * 2.7 + p * 2), a, b);
}

// Pine trees: stacked triangles on a trunk.
function pine(cx, base, height, c) {
  const tiers = 4, width = height * 0.42;
  for (let t = 0; t < tiers; t++) {
    const top = base - height + t * height * 0.2, bot = top + height * 0.42, half = width * (0.45 + t * 0.18);
    for (let y = Math.max(0, top | 0); y < Math.min(h, bot); y++) {
      const hw = half * (y - top) / (bot - top);
      for (let x = Math.max(0, cx - hw | 0); x < Math.min(w, cx + hw); x++) put(x, y, c);
    }
  }
  for (let y = base - height * 0.1 | 0; y < Math.min(h, base); y++) for (let x = cx - width * 0.05 | 0; x < cx + width * 0.05; x++) put(x, y, c);
}
const rows = [[0.80, 70, 120, [24, 36, 86]], [0.88, 110, 190, [17, 27, 66]], [0.98, 170, 300, [10, 17, 44]]];
for (const [baseF, minH, maxH, c] of rows) {
  for (let x = -40 * S; x < w + 40 * S; x += (18 + rnd() * 34) * S) {
    const hh = (minH + rnd() * (maxH - minH)) * S;
    pine(x, h * baseF + rnd() * 20 * S, hh, c);
  }
}
// Ground band under the front row.
for (let y = h * 0.97 | 0; y < h; y++) for (let x = 0; x < w; x++) put(x, y, [8, 13, 34]);

// Average 2x2 down, dither, write PNG.
const px = Buffer.alloc(W * H * 3);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) for (let i = 0; i < 3; i++) {
  let s = 0;
  for (let dy = 0; dy < S; dy++) for (let dx = 0; dx < S; dx++) s += buf[((y * S + dy) * w + x * S + dx) * 3 + i];
  px[(y * W + x) * 3 + i] = Math.max(0, Math.min(255, Math.round(s / (S * S) + (rnd() + rnd() - 1) * 2.5)));
}
function crc32(b) { let c, r = ~0; for (const x of b) { c = (r ^ x) & 255; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; r = (r >>> 8) ^ c; } return ~r >>> 0; }
function chunk(t, d) { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); }
const st = W * 3 + 1, raw = Buffer.alloc(st * H);
for (let y = 0; y < H; y++) px.copy(raw, y * st + 1, y * W * 3, (y + 1) * W * 3);
const ih = Buffer.alloc(13); ih.writeUInt32BE(W, 0); ih.writeUInt32BE(H, 4); ih[8] = 8; ih[9] = 2;
fs.writeFileSync(process.argv[2], Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
console.log('wrote ' + process.argv[2]);
