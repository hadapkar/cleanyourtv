#!/usr/bin/env node
// Minimal ADB-over-Wi-Fi client with no dependencies (Node 18+). It exists because the work laptop
// cannot install adb or Python. Speaks the ADB wire protocol on TCP 5555.
//
//   node adbtv.js <host> shell "<command>" ["<command>" ...]
//   node adbtv.js <host> push <local-file> <remote-path>
//   node adbtv.js <host> install <local-apk>        push, `pm install -r`, then delete the temp copy
//   node adbtv.js <host> theme <theme-folder>        push config.json and wallpapers/ to the launcher, restart Home
//   node adbtv.js <host> tmdb-token <file>          give TV Discover its TMDB read token (from a local file)
//   node adbtv.js <host> gemini-key <file> [model]   give Jarvis its Gemini API key (from a local file)
//   node adbtv.js <host> key-service on|off          the launcher's remote-button helper (mic button -> Jarvis)
//   node adbtv.js <host> grant-addons               runtime permissions for the add-ons (TV listings, mic)
//   node adbtv.js <host> backup <folder>             the owner's setup off the TV into <folder> (no keys or tokens)
//   node adbtv.js <host> restore <folder>            put a backup from <folder> back on the TV
//   node adbtv.js <host> pull <remote-file> <local-file>
//   node adbtv.js <host> screenshot <out.png>       capture the TV screen (binary-safe exec: service)
//   node adbtv.js <host> pull-state <out-file>      save enabled and disabled package lists to a file
//
// The signing key lives in ~/.adbtv/adbkey.pem (never in the repo). The first connection shows
// "Allow USB debugging?" on the TV; accept it once.
'use strict';
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f;
const A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43;
const MAX_DATA = 64 * 1024; // sync DATA chunks may not exceed 64 KiB
const SHA1_PREFIX = Buffer.from('3021300906052b0e03021a05000414', 'hex');

function loadKey() {
  const dir = path.join(os.homedir(), '.adbtv');
  const file = path.join(dir, 'adbkey.pem');
  if (fs.existsSync(file)) return crypto.createPrivateKey(fs.readFileSync(file));
  fs.mkdirSync(dir, { recursive: true });
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicExponent: 65537 });
  fs.writeFileSync(file, privateKey.export({ type: 'pkcs1', format: 'pem' }), { mode: 0o600 });
  return privateKey;
}

function words(v, count) {
  const b = Buffer.alloc(count * 4);
  for (let i = 0; i < count; i++) { b.writeUInt32LE(Number(v & 0xffffffffn), i * 4); v >>= 32n; }
  return b;
}

function adbPublicKey(privKey) {
  const jwk = privKey.export({ format: 'jwk' });
  const n = BigInt('0x' + Buffer.from(jwk.n, 'base64url').toString('hex'));
  const m = 1n << 32n;
  let x = 1n;
  const a = n % m;
  for (let i = 0; i < 6; i++) x = (x * (2n - a * x)) % m;
  const n0inv = (m - ((x % m + m) % m)) % m;
  const rr = (1n << 4096n) % n;
  const head = Buffer.alloc(8);
  head.writeUInt32LE(64, 0);
  head.writeUInt32LE(Number(n0inv), 4);
  const exp = Buffer.alloc(4); exp.writeUInt32LE(65537, 0);
  const blob = Buffer.concat([head, words(n, 64), words(rr, 64), exp]);
  return Buffer.concat([Buffer.from(blob.toString('base64') + ' adbtv@pc'), Buffer.from([0])]);
}

function pack(cmd, a0, a1, data = Buffer.alloc(0)) {
  const h = Buffer.alloc(24);
  h.writeUInt32LE(cmd, 0); h.writeUInt32LE(a0, 4); h.writeUInt32LE(a1, 8);
  h.writeUInt32LE(data.length, 12);
  let sum = 0; for (const x of data) sum += x;
  h.writeUInt32LE(sum >>> 0, 16);
  h.writeUInt32LE((cmd ^ 0xffffffff) >>> 0, 20);
  return Buffer.concat([h, data]);
}

class Adb {
  constructor(host) {
    this.host = host;
    this.key = loadKey();
    this.buf = Buffer.alloc(0);
    this.streams = new Map(); // local id -> { remote, onData, onClose, ready }
    this.nextId = 1;
  }

  connect() {
    return new Promise((resolve, reject) => {
      let sentSig = false;
      this.sock = net.connect(5555, this.host);
      this.sock.setTimeout(90000, () => reject(new Error('timeout; did you accept the prompt on the TV?')));
      this.sock.on('error', reject);
      this.sock.on('connect', () => this.sock.write(pack(A_CNXN, 0x01000001, 256 * 1024, Buffer.from('host::\0'))));
      this.sock.on('data', (d) => {
        this.buf = Buffer.concat([this.buf, d]);
        while (this.buf.length >= 24) {
          const len = this.buf.readUInt32LE(12);
          if (this.buf.length < 24 + len) break;
          const cmd = this.buf.readUInt32LE(0), a0 = this.buf.readUInt32LE(4), a1 = this.buf.readUInt32LE(8);
          const data = this.buf.subarray(24, 24 + len);
          this.buf = this.buf.subarray(24 + len);
          if (cmd === A_AUTH && a0 === 1) {
            if (!sentSig) {
              sentSig = true;
              const sig = crypto.privateEncrypt({ key: this.key, padding: crypto.constants.RSA_PKCS1_PADDING },
                Buffer.concat([SHA1_PREFIX, data]));
              this.sock.write(pack(A_AUTH, 2, 0, sig));
            } else {
              console.error('Accept the "Allow USB debugging?" prompt on the TV now...');
              this.sock.write(pack(A_AUTH, 3, 0, adbPublicKey(this.key)));
            }
          } else if (cmd === A_CNXN) {
            this.banner = data.toString().replace(/\0/g, '');
            resolve(this.banner);
          } else {
            this.dispatch(cmd, a0, a1, data);
          }
        }
      });
    });
  }

  dispatch(cmd, remote, local, data) {
    const s = this.streams.get(local);
    if (!s) return;
    if (cmd === A_OKAY) { s.remote = remote; s.ready = true; if (s.onOkay) s.onOkay(); }
    else if (cmd === A_WRTE) { this.sock.write(pack(A_OKAY, local, remote)); s.onData(data); }
    else if (cmd === A_CLSE) { this.sock.write(pack(A_CLSE, local, remote)); this.streams.delete(local); s.onClose(); }
  }

  open(service, onData) {
    const id = this.nextId++;
    return new Promise((resolve) => {
      const s = { remote: 0, ready: false, onData, onClose: () => {}, onOkay: null };
      this.streams.set(id, s);
      s.onOkay = () => { s.onOkay = null; resolve({ id, s }); };
      this.sock.write(pack(A_OPEN, id, 0, Buffer.from(service + '\0')));
    });
  }

  // Send one WRTE and wait for the peer's OKAY before the next (ADB flow control).
  write(stream, data) {
    return new Promise((resolve) => {
      stream.s.onOkay = () => { stream.s.onOkay = null; resolve(); };
      this.sock.write(pack(A_WRTE, stream.id, stream.s.remote, data));
    });
  }

  async shell(cmd) {
    let out = '';
    const done = new Promise((resolve) => { this._shellDone = resolve; });
    const st = await this.open('shell:' + cmd, (d) => { out += d.toString(); });
    st.s.onClose = this._shellDone;
    await done;
    return out;
  }

  async exec(cmd) {
    const chunks = [];
    const done = new Promise((resolve) => { this._execDone = resolve; });
    const st = await this.open('exec:' + cmd, (d) => chunks.push(d));
    st.s.onClose = this._execDone;
    await done;
    return Buffer.concat(chunks);
  }

  async push(local, remote, mode = 0o644) {
    const file = fs.readFileSync(local);
    let reply = Buffer.alloc(0);
    let gotReply;
    const replied = new Promise((r) => { gotReply = r; });
    const st = await this.open('sync:', (d) => { reply = Buffer.concat([reply, d]); if (reply.length >= 8) gotReply(); });
    const spec = Buffer.from(`${remote},${mode}`);
    const hdr = (id, n) => { const b = Buffer.alloc(8); b.write(id, 0, 'ascii'); b.writeUInt32LE(n, 4); return b; };
    await this.write(st, Buffer.concat([hdr('SEND', spec.length), spec]));
    for (let off = 0; off < file.length; off += MAX_DATA) {
      const chunk = file.subarray(off, off + MAX_DATA);
      await this.write(st, Buffer.concat([hdr('DATA', chunk.length), chunk]));
    }
    await this.write(st, hdr('DONE', Math.floor(Date.now() / 1000)));
    await replied;
    const tag = reply.subarray(0, 4).toString('ascii');
    if (tag !== 'OKAY') throw new Error('push failed: ' + reply.subarray(8).toString());
    this.sock.write(pack(A_CLSE, st.id, st.s.remote));
    return file.length;
  }

  close() { this.sock.end(); }
}

// ---- backup and restore --------------------------------------------------------------------------------

const HOME_FILES = '/sdcard/Android/data/com.gathin.tvhome/files';
const DISCOVER_FILES = '/sdcard/Android/data/com.gathin.tvdiscover/files';
/** TV settings worth keeping: the screensaver and sleep timers set for this TV. */
const TV_SETTINGS = [['system', 'screen_off_timeout'], ['secure', 'sleep_timeout'], ['secure', 'screensaver_components'],
  ['secure', 'screensaver_enabled'], ['secure', 'screensaver_activate_on_sleep'], ['secure', 'screensaver_activate_on_dock']];

/** The keys in ~/.adbtv, so a backup can be checked never to contain one. */
function secrets() {
  const dir = path.join(require('os').homedir(), '.adbtv');
  const out = [];
  for (const f of ['tmdb.token', 'gemini.key']) {
    try { const s = fs.readFileSync(path.join(dir, f), 'utf8').replace(/^﻿/, '').trim(); if (s.length > 8) out.push(s); } catch { /* not set up */ }
  }
  return out;
}

async function backup(adb, dir) {
  if (!dir) throw new Error('backup needs a folder');
  fs.mkdirSync(dir, { recursive: true });
  const files = {};
  const json = async (remote) => {
    const text = (await adb.exec(`cat ${remote} 2>/dev/null`)).toString('utf8');
    try { JSON.parse(text); return text; } catch { return null; }
  };
  files['config.json'] = await json(`${HOME_FILES}/config.json`);
  files['home-settings.json'] = await json(`${HOME_FILES}/backup/home.json`);
  await adb.shell('content call --uri content://com.gathin.tvdiscover --method export');
  files['discover.json'] = await json(`${DISCOVER_FILES}/backup.json`);
  // Shared storage is readable by apps with storage access: the copy is not left there once it is here.
  await adb.shell(`rm -f ${DISCOVER_FILES}/backup.json`);
  const weather = await adb.shell('content query --uri content://com.gathin.tvweather/current');
  const city = (weather.match(/\bcity=([^,\n]+)/) || [])[1] || '';
  const settings = {};
  for (const [ns, key] of TV_SETTINGS) {
    const v = (await adb.shell(`settings get ${ns} ${key}`)).trim();
    if (v && v !== 'null') settings[`${ns}/${key}`] = v;
  }
  const list = async (flags) => (await adb.shell(`pm list packages ${flags}`)).split('\n')
    .map((l) => l.replace(/^package:/, '').trim()).filter(Boolean).sort();
  files['tv.json'] = JSON.stringify({ city, settings, disabled: await list('-d'), apps: await list('-3') }, null, 2) + '\n';
  // Never let a key or token into the backup, whatever a future version of an app writes.
  const keys = secrets();
  for (const [name, text] of Object.entries(files)) {
    if (text && keys.some((k) => text.includes(k))) throw new Error(`${name} contains a key or token; backup stopped`);
  }
  for (const [name, text] of Object.entries(files)) {
    if (text == null) { console.log(`  ${name}: not on the TV, skipped`); continue; }
    fs.writeFileSync(path.join(dir, name), text);
    console.log(`  ${name}: ${Buffer.byteLength(text)} bytes`);
  }
}

async function restore(adb, dir) {
  if (!dir) throw new Error('restore needs a folder');
  const read = (name) => { try { return fs.readFileSync(path.join(dir, name), 'utf8'); } catch { return null; } };
  const put = async (text, remote) => {
    const stage = '/data/local/tmp/restore-' + path.basename(remote);
    const tmp = path.join(require('os').tmpdir(), 'tv-restore-' + path.basename(remote));
    fs.writeFileSync(tmp, text);
    await adb.push(tmp, stage);
    fs.unlinkSync(tmp);
    await adb.shell(`mkdir -p ${path.posix.dirname(remote)}; cp ${stage} ${remote}; rm ${stage}`);
  };
  const tv = JSON.parse(read('tv.json') || '{}');
  const config = read('config.json');
  if (config) { await put(config, `${HOME_FILES}/config.json`); console.log('  config.json restored'); }
  const home = JSON.parse(read('home-settings.json') || '{}');
  if (tv.city) home.city = tv.city;
  if (Object.keys(home).length) { await put(JSON.stringify(home), `${HOME_FILES}/restore/home.json`); console.log('  Home settings and city queued'); }
  const discover = read('discover.json');
  if (discover) {
    await put(discover, `${DISCOVER_FILES}/restore.json`);
    console.log('  Discover: ' + (await adb.shell('content call --uri content://com.gathin.tvdiscover --method restore')).trim());
  }
  for (const [k, v] of Object.entries(tv.settings || {})) {
    const [ns, key] = k.split('/');
    if (!/^(system|secure|global)$/.test(ns) || !/^[\w.]+$/.test(key) || !/^[\w./:-]*$/.test(v)) continue;
    await adb.shell(`settings put ${ns} ${key} ${v}`);
    console.log(`  ${k} = ${v}`);
  }
  // Apps and disabled packages are only reported: installing or disabling is left to the owner.
  const installed = (await adb.shell('pm list packages -3')).split('\n').map((l) => l.replace(/^package:/, '').trim());
  const missing = (tv.apps || []).filter((p) => !installed.includes(p));
  if (missing.length) console.log('  apps in the backup but not on this TV: ' + missing.join(', '));
  if ((tv.disabled || []).length) console.log('  disabled in the backup (not changed): ' + tv.disabled.join(', '));
  // Home applies its settings when it starts.
  process.stdout.write(await adb.shell('am force-stop com.gathin.tvhome; am start -W -n com.gathin.tvhome/.MainActivity | grep -E "Status|Error"'));
  // A force stop of Home drops its remote-button helper on Xiaomi firmware: put it back, last.
  const svc = 'com.gathin.tvhome/com.gathin.tvhome.HomeKeyService';
  const cur = (await adb.shell('settings get secure enabled_accessibility_services')).trim();
  const list = cur && cur !== 'null' ? cur.split(':').filter((e) => e && e !== svc) : [];
  list.push(svc);
  await adb.shell(`settings put secure enabled_accessibility_services ${list.join(':')}; settings put secure accessibility_enabled 1`);
  console.log('  button helper: ' + (await adb.shell('settings get secure enabled_accessibility_services')).trim());
}

async function main() {
  const [host, verb, ...args] = process.argv.slice(2);
  if (!host || !verb) { console.error(fs.readFileSync(__filename, 'utf8').split('\n').slice(1, 9).join('\n')); process.exit(64); }
  const adb = new Adb(host);
  console.error('Connected: ' + await adb.connect());
  try {
    if (verb === 'shell') {
      for (const c of args) { console.log('\n$ ' + c); process.stdout.write(await adb.shell(c)); }
    } else if (verb === 'push') {
      const n = await adb.push(args[0], args[1]);
      console.log(`pushed ${n} bytes to ${args[1]}`);
      process.stdout.write(await adb.shell(`ls -l ${args[1]}`));
    } else if (verb === 'install') {
      const remote = '/data/local/tmp/tvhome-install.apk';
      await adb.push(args[0], remote);
      process.stdout.write(await adb.shell(`pm install -r ${remote}`));
      await adb.shell(`rm ${remote}`);
    } else if (verb === 'theme') {
      const dir = '/sdcard/Android/data/com.gathin.tvhome/files';
      const files = ['config.json'];
      for (const extra of ['clock.png', 'clock.jpg']) if (fs.existsSync(path.join(args[0], extra))) files.push(extra);
      const wp = path.join(args[0], 'wallpapers');
      if (fs.existsSync(wp)) for (const n of fs.readdirSync(wp)) files.push('wallpapers/' + n);
      await adb.shell(`mkdir -p ${dir}/wallpapers; rm -f ${dir}/wallpapers/*`);
      for (const rel of files) {
        const stage = '/data/local/tmp/theme-' + rel.replace('/', '-');
        const n = await adb.push(path.join(args[0], rel), stage);
        process.stdout.write(await adb.shell(`cp ${stage} ${dir}/${rel}; rm ${stage}; ls -l ${dir}/${rel}`));
        console.log(`  ${rel}: ${n} bytes`);
      }
      process.stdout.write(await adb.shell('am force-stop com.gathin.tvhome; am start -W -n com.gathin.tvhome/.MainActivity'));
    } else if (verb === 'tmdb-token') {
      const token = fs.readFileSync(args[0], 'utf8').replace(/^\uFEFF/, '').trim();
      if (!/^[A-Za-z0-9._-]+$/.test(token)) throw new Error('that file does not look like a TMDB read token');
      const out = await adb.shell('am broadcast -f 0x20 -a com.gathin.tvdiscover.SET_TOKEN '
        + '-n com.gathin.tvdiscover/.TokenReceiver --es token ' + token);
      console.log(out.replace(token, '<token>').trim());
    } else if (verb === 'gemini-key') {
      const key = fs.readFileSync(args[0], 'utf8').replace(/^\uFEFF/, '').trim();
      if (!/^[A-Za-z0-9._-]+$/.test(key)) throw new Error('that file does not look like a Gemini API key');
      const model = args[1] && /^[a-z0-9.-]+$/.test(args[1]) ? ' --es model ' + args[1] : '';
      const out = await adb.shell('am broadcast -f 0x20 -a com.gathin.tvjarvis.SET_KEY '
        + '-n com.gathin.tvjarvis/.KeyReceiver --es key ' + key + model);
      console.log(out.replace(key, '<key>').trim());
      process.stdout.write(await adb.shell('pm grant com.gathin.tvjarvis android.permission.RECORD_AUDIO && echo mic granted'));
    } else if (verb === 'key-service') {
      const svc = 'com.gathin.tvhome/com.gathin.tvhome.HomeKeyService';
      const cur = (await adb.shell('settings get secure enabled_accessibility_services')).trim();
      const list = cur && cur !== 'null' ? cur.split(':').filter((e) => e && e !== svc) : [];
      if (args[0] === 'on') {
        list.push(svc);
        process.stdout.write(await adb.shell('settings put secure enabled_accessibility_services ' + list.join(':')
          + '; settings put secure accessibility_enabled 1'
          // Xiaomi stops a third-party launcher at boot, which drops its accessibility service; the
          // notification listener is bound again afterwards and puts the helper back (GuardRevival).
          + '; cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.GuardRevival'
          + '; settings get secure enabled_accessibility_services'));
      } else {
        process.stdout.write(await adb.shell('settings put secure enabled_accessibility_services "' + list.join(':') + '"'
          + (list.length ? '' : '; settings put secure accessibility_enabled 0')
          + '; cmd notification disallow_listener com.gathin.tvhome/com.gathin.tvhome.GuardRevival'
          + '; settings get secure enabled_accessibility_services'));
      }
    } else if (verb === 'grant-addons') {
      process.stdout.write(await adb.shell('pm grant com.gathin.tvdiscover android.permission.READ_TV_LISTINGS && echo tv-listings granted; '
        + 'pm grant com.gathin.tvjarvis android.permission.RECORD_AUDIO && echo mic granted'));
    } else if (verb === 'pull') {
      const data = await adb.exec('cat ' + args[0]);
      fs.writeFileSync(args[1], data);
      console.log(`pulled ${data.length} bytes to ${args[1]}`);
    } else if (verb === 'screenshot') {
      const png = await adb.exec('screencap -p');
      if (png.subarray(1, 4).toString() !== 'PNG') throw new Error('not a PNG: ' + png.subarray(0, 80).toString());
      fs.writeFileSync(args[0], png);
      console.log(`saved ${args[0]} (${png.length} bytes)`);
    } else if (verb === 'pull-state') {
      const enabled = await adb.shell('pm list packages -e');
      const disabled = await adb.shell('pm list packages -d');
      const home = await adb.shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME');
      fs.writeFileSync(args[0], `# enabled\n${enabled}\n# disabled\n${disabled}\n# home\n${home}\n`);
      console.log(`saved ${args[0]}`);
    } else if (verb === 'backup') {
      await backup(adb, args[0]);
    } else if (verb === 'restore') {
      await restore(adb, args[0]);
    } else {
      throw new Error('unknown command ' + verb);
    }
  } finally {
    adb.close();
  }
  process.exit(0);
}

main().catch((e) => { console.error('ERROR: ' + e.message); process.exit(1); });
