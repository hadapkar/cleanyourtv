// Generates smooth, dithered 1920x1080 PNG wallpapers (no dependencies).
const zlib = require('zlib'), fs = require('fs');
const W = 1920, H = 1080;
function crc32(buf) { let c, crc = ~0; for (const b of buf) { c = (crc ^ b) & 255; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crc = (crc >>> 8) ^ c; } return ~crc >>> 0; }
function chunk(t, d) { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); }
function png(px) { const raw = Buffer.alloc((W * 3 + 1) * H); for (let y = 0; y < H; y++) { raw[y * (W * 3 + 1)] = 0; px.copy(raw, y * (W * 3 + 1) + 1, y * W * 3, (y + 1) * W * 3); } const ih = Buffer.alloc(13); ih.writeUInt32BE(W, 0); ih.writeUInt32BE(H, 4); ih[8] = 8; ih[9] = 2; return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]); }
let seed = 7; const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
function make(base, top, blobs) { // base colour at bottom, top colour at top, soft radial blobs added
  const px = Buffer.alloc(W * H * 3);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const v = y / H; let c = [0, 1, 2].map(i => top[i] + (base[i] - top[i]) * v);
    for (const b of blobs) { const dx = (x / W - b.x) * (W / H), dy = y / H - b.y; const f = Math.exp(-(dx * dx + dy * dy) / (2 * b.r * b.r)) * b.a; for (let i = 0; i < 3; i++) c[i] += (b.c[i] - c[i]) * f; }
    const vig = 1 - 0.35 * Math.pow(Math.hypot(x / W - 0.5, (y / H - 0.5) * 0.9) * 1.25, 2);
    const o = (y * W + x) * 3;
    for (let i = 0; i < 3; i++) px[o + i] = Math.max(0, Math.min(255, Math.round(c[i] * vig + (rnd() + rnd() - 1) * 3.2)));
  }
  return png(px);
}
const out = process.argv[2]; fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(out + '/night.png', make([8, 10, 22], [14, 20, 44], [{ x: .22, y: .3, r: .28, a: .55, c: [22, 140, 150] }, { x: .78, y: .22, r: .30, a: .50, c: [96, 62, 190] }, { x: .55, y: .85, r: .35, a: .30, c: [20, 60, 120] }]));
fs.writeFileSync(out + '/evening.png', make([22, 12, 34], [20, 18, 58], [{ x: .3, y: .92, r: .34, a: .70, c: [230, 110, 70] }, { x: .72, y: .8, r: .32, a: .55, c: [200, 60, 130] }, { x: .5, y: .2, r: .3, a: .35, c: [70, 60, 170] }]));
fs.writeFileSync(out + '/morning.png', make([20, 38, 52], [28, 52, 84], [{ x: .75, y: .85, r: .34, a: .65, c: [235, 175, 90] }, { x: .25, y: .3, r: .30, a: .45, c: [70, 170, 170] }, { x: .5, y: .6, r: .4, a: .25, c: [90, 120, 190] }]));
console.log('done');
