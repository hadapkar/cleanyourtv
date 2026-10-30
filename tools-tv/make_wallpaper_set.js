// Wallpaper styles for the card home, drawn from code (no photos, no licences).
//   node make_wallpaper_set.js preview <out.png>        four styles side by side, 960x540 each
//   node make_wallpaper_set.js <style> <out-folder>     morning.png, evening.png, night.png at 1920x1080
// Styles: mesh (soft flowing colour, like Apple's abstract wallpapers), nebula, dunes, midnight.
'use strict';
const zlib = require('zlib'), fs = require('fs');

let seed = 5;
const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
const mix = (a, b, t) => [0, 1, 2].map(i => a[i] + (b[i] - a[i]) * t);
const clamp = v => Math.max(0, Math.min(255, Math.round(v)));

// Each style paints a W x H float RGB buffer for one palette.
const STYLES = {
  // Big blurred colour fields, like macOS / iPadOS abstract wallpapers.
  mesh(W, H, p) {
    const f = new Float32Array(W * H * 3);
    const blobs = p.blobs.map(([x, y, r, c]) => ({x: x * W, y: y * H, r: r * W, c}));
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      let c = mix(p.top, p.bottom, y / H), wsum = 1;
      const acc = c.map(v => v);
      for (const b of blobs) {
        const d2 = ((x - b.x) ** 2 + (y - b.y) ** 2) / (b.r * b.r);
        const w = Math.exp(-d2 * 1.6) * 2.2;
        for (let i = 0; i < 3; i++) acc[i] += b.c[i] * w;
        wsum += w;
      }
      const o = (y * W + x) * 3;
      for (let i = 0; i < 3; i++) f[o + i] = acc[i] / wsum;
    }
    return f;
  },
  // Dark space: a soft nebula band and stars.
  nebula(W, H, p) {
    const f = STYLES.mesh(W, H, p);
    for (let i = 0; i < W * H / 900; i++) {
      const x = rnd() * W | 0, y = rnd() * H | 0, a = 0.3 + rnd() * 0.7, o = (y * W + x) * 3;
      for (let k = 0; k < 3; k++) f[o + k] += (255 - f[o + k]) * a;
    }
    return f;
  },
  // Layered sand dunes under a gradient sky.
  dunes(W, H, p) {
    const f = new Float32Array(W * H * 3);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const c = mix(p.top, p.bottom, Math.pow(y / H, 1.3)), o = (y * W + x) * 3;
      f[o] = c[0]; f[o + 1] = c[1]; f[o + 2] = c[2];
    }
    const sx = p.sun[0] * W, sy = p.sun[1] * H, sr = H * 0.07;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const d = Math.hypot(x - sx, y - sy), o = (y * W + x) * 3;
      const a = d < sr ? 0.95 : 0.35 * Math.exp(-(d - sr) / (sr * 2.5));
      for (let k = 0; k < 3; k++) f[o + k] += (p.sunColor[k] - f[o + k]) * a;
    }
    p.layers.forEach(([base, amp, freq, top, bot], li) => {
      const ph = li * 1.7 + 0.4;
      for (let x = 0; x < W; x++) {
        const u = x / W;
        const y0 = H * base + H * amp * (Math.sin(u * freq + ph) * 0.6 + Math.sin(u * freq * 2.3 + ph * 2) * 0.4);
        for (let y = Math.max(0, y0 | 0); y < H; y++) {
          const c = mix(top, bot, Math.min(1, (y - y0) / (H * 0.25))), o = (y * W + x) * 3;
          f[o] = c[0]; f[o + 1] = c[1]; f[o + 2] = c[2];
        }
      }
    });
    return f;
  },
  // Almost flat: deep colour with one soft light, the calmest behind glass.
  midnight(W, H, p) {
    return STYLES.mesh(W, H, p);
  },
};

const PALETTES = {
  mesh: {
    morning: {top: [196, 214, 250], bottom: [250, 214, 196], blobs: [[0.15, 0.2, 0.45, [120, 170, 255]], [0.85, 0.3, 0.4, [255, 170, 200]], [0.55, 0.95, 0.5, [255, 210, 150]]]},
    evening: {top: [60, 40, 120], bottom: [200, 90, 110], blobs: [[0.2, 0.25, 0.45, [110, 70, 220]], [0.8, 0.2, 0.4, [240, 110, 150]], [0.5, 0.95, 0.55, [255, 150, 90]]]},
    night: {top: [12, 14, 40], bottom: [24, 18, 60], blobs: [[0.2, 0.3, 0.5, [40, 60, 170]], [0.85, 0.25, 0.4, [120, 50, 170]], [0.55, 0.95, 0.5, [20, 110, 150]]]},
  },
  nebula: {
    morning: {top: [10, 14, 34], bottom: [30, 20, 60], blobs: [[0.3, 0.4, 0.35, [90, 60, 180]], [0.7, 0.55, 0.3, [40, 120, 190]]]},
    evening: {top: [12, 10, 30], bottom: [40, 16, 50], blobs: [[0.35, 0.45, 0.35, [160, 50, 140]], [0.7, 0.5, 0.3, [80, 60, 190]]]},
    night: {top: [4, 6, 16], bottom: [14, 10, 30], blobs: [[0.35, 0.4, 0.35, [50, 40, 130]], [0.7, 0.6, 0.3, [20, 80, 130]]]},
  },
  dunes: {
    morning: {top: [150, 196, 240], bottom: [250, 220, 190], sun: [0.75, 0.25], sunColor: [255, 245, 220],
      layers: [[0.62, 0.05, 5, [235, 190, 150], [215, 165, 125]], [0.72, 0.06, 4, [220, 160, 115], [195, 135, 95]], [0.84, 0.05, 6, [195, 130, 90], [165, 105, 72]]]},
    evening: {top: [70, 60, 140], bottom: [250, 150, 110], sun: [0.3, 0.55], sunColor: [255, 200, 140],
      layers: [[0.62, 0.05, 5, [170, 90, 110], [140, 70, 100]], [0.72, 0.06, 4, [125, 60, 95], [100, 45, 85]], [0.84, 0.05, 6, [85, 38, 75], [62, 28, 60]]]},
    night: {top: [10, 16, 44], bottom: [40, 50, 100], sun: [0.78, 0.2], sunColor: [235, 238, 250],
      layers: [[0.62, 0.05, 5, [44, 52, 100], [34, 40, 84]], [0.72, 0.06, 4, [32, 38, 78], [24, 28, 62]], [0.84, 0.05, 6, [22, 26, 56], [15, 18, 42]]]},
  },
  midnight: {
    morning: {top: [28, 36, 56], bottom: [16, 20, 34], blobs: [[0.75, 0.1, 0.55, [60, 90, 140]]]},
    evening: {top: [30, 24, 44], bottom: [14, 12, 24], blobs: [[0.25, 0.1, 0.55, [90, 60, 110]]]},
    night: {top: [10, 12, 20], bottom: [4, 5, 10], blobs: [[0.5, 0.05, 0.6, [30, 40, 70]]]},
  },
};

function toPng(f, W, H) {
  const px = Buffer.alloc(W * H * 3);
  for (let i = 0; i < W * H * 3; i++) px[i] = clamp(f[i] + (rnd() + rnd() - 1) * 2.5);   // dither against banding
  function crc32(b) { let c, r = ~0; for (const x of b) { c = (r ^ x) & 255; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; r = (r >>> 8) ^ c; } return ~r >>> 0; }
  function chunk(t, d) { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); }
  const st = W * 3 + 1, raw = Buffer.alloc(st * H);
  for (let y = 0; y < H; y++) px.copy(raw, y * st + 1, y * W * 3, (y + 1) * W * 3);
  const ih = Buffer.alloc(13); ih.writeUInt32BE(W, 0); ih.writeUInt32BE(H, 4); ih[8] = 8; ih[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih),
    chunk('IDAT', zlib.deflateSync(raw, {level: 9})), chunk('IEND', Buffer.alloc(0))]);
}

const [what, out] = process.argv.slice(2);
if (what === 'preview') {
  // 2x2 grid, the evening palette of each style.
  const W = 960, H = 540, f = new Float32Array(W * 2 * H * 2 * 3);
  ['mesh', 'nebula', 'dunes', 'midnight'].forEach((name, n) => {
    const tile = STYLES[name](W, H, PALETTES[name].evening);
    const ox = (n % 2) * W, oy = (n >> 1) * H;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) for (let k = 0; k < 3; k++)
      f[((oy + y) * W * 2 + ox + x) * 3 + k] = tile[(y * W + x) * 3 + k];
  });
  fs.writeFileSync(out, toPng(f, W * 2, H * 2));
} else if (STYLES[what]) {
  fs.mkdirSync(out, {recursive: true});
  for (const mood of ['morning', 'evening', 'night']) {
    fs.writeFileSync(`${out}/${mood}.png`, toPng(STYLES[what](1920, 1080, PALETTES[what][mood]), 1920, 1080));
  }
} else {
  console.error('usage: make_wallpaper_set.js preview <out.png> | <mesh|nebula|dunes|midnight> <folder>');
  process.exit(64);
}
console.log('done');
