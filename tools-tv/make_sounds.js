// The launcher's focus sounds, synthesised (no samples, no licences): a soft tick for focus moves and a
// rounder click for OK. 16-bit mono WAV at 44.1 kHz. Usage: node make_sounds.js <res/raw folder>
'use strict';
const fs = require('fs');
const RATE = 44100;
function wav(samples) {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((v, i) => data.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(v * 32767))), i * 2));
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + data.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(RATE, 24);
  h.writeUInt32LE(RATE * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}
// A short decaying tone with a quick attack, like a light tap on glass.
function tone(ms, freq, decayMs, gain, overtone) {
  const n = Math.round(RATE * ms / 1000), out = new Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / RATE, env = Math.min(1, i / (RATE * 0.0008)) * Math.exp(-t * 1000 / decayMs);
    out[i] = gain * env * (Math.sin(2 * Math.PI * freq * t) + overtone * Math.sin(2 * Math.PI * freq * 2.01 * t));
  }
  return out;
}
const dir = process.argv[2];
fs.writeFileSync(dir + '/focus_tick.wav', wav(tone(28, 2300, 6, 0.32, 0.25)));
fs.writeFileSync(dir + '/focus_select.wav', wav(tone(70, 880, 18, 0.38, 0.35)));
console.log('sounds written');
