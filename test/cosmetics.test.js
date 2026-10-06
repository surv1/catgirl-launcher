// Cosmetics: launcher saving + the real worker code, end to end, with Mojang and Cloudflare faked.
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cos-'));
require('../src/main/paths').init(tmp);
const cosmetics = require('../src/main/cosmetics');

(async () => {
  const worker = await import('../cosmetics-worker/worker.js');
  const SECRET = 'test-secret-that-is-long-enough';

  // ---- pure bits
  const n = cosmetics.normalize({ ears: { on: 1, color: '#ABCDEF', inner: 'red' }, tail: { on: true, color: 'nope' }, hat: { on: true } });
  assert.deepStrictEqual(n, {
    ears: { on: true, color: '#abcdef', inner: '#ffb3d9' }, tail: { on: true, color: '#3b2a2a' }, bow: { on: false, color: '#ff7eb6' },
    wings: { on: false, color: '#ffffff', style: 'angel' }, halo: { on: false, color: '#ffd34d' }, horns: { on: false, color: '#5a1a1a' }, pet: { on: false, color: '#ffb3d9' },
    cape: { on: false, color: '#ff7eb6', trim: '#ffffff', style: 'paw', line: 'cycle' },
    trim: { on: false, color: '#7ec8ff', accent: '#ff7eb6', style: 'paws' },
  });
  assert.deepStrictEqual(worker.clean({ trim: { on: true, style: 'circuit', accent: '#FFFFFF' } }), { trim: { on: true, color: '#7ec8ff', accent: '#ffffff', style: 'circuit' } });
  assert.strictEqual(worker.clean({ cape: { on: true, style: 'meow' } }).cape.style, 'meow');
  assert.strictEqual(cosmetics.normalize({ cape: { style: 'catmeow' } }).cape.style, 'catmeow');
  assert.strictEqual(cosmetics.normalize({ cape: { line: 'nya~' } }).cape.line, 'nya~');
  assert.strictEqual(worker.clean({ cape: { on: true, line: 'something rude' } }).cape.line, 'cycle', 'only preset lines');
  assert.deepStrictEqual(worker.clean({ cape: { on: true, style: 'skull', trim: '#FFD34D' } }), { cape: { on: true, color: '#ff7eb6', trim: '#ffd34d', style: 'paw', line: 'cycle' } });
  assert.strictEqual(cosmetics.normalize({ wings: { on: true, style: 'demon', color: '#8B1A1A' } }).wings.style, 'demon');
  assert.strictEqual(cosmetics.normalize({ wings: { on: true, style: 'dragon' } }).wings.style, 'angel');
  assert.deepStrictEqual(worker.clean({ wings: { on: true, style: 'demon' }, pet: { on: true } }), { wings: { on: true, color: '#ffffff', style: 'demon' }, pet: { on: true, color: '#ffb3d9' } });
  assert.deepStrictEqual(worker.clean({ ears: { on: true, color: '#00FF00' }, evil: { on: true }, bow: 'x' }), { ears: { on: true, color: '#00ff00', inner: '#ffb3d9' } });
  const nonce = await worker.makeNonce(SECRET);
  assert.ok(await worker.checkNonce(SECRET, nonce));
  assert.ok(!(await worker.checkNonce('other-secret', nonce)), 'nonce signed with another secret');
  assert.ok(!(await worker.checkNonce(SECRET, nonce, Date.now() + 3 * 60 * 1000)), 'nonce expires');
  assert.ok(!(await worker.checkNonce(SECRET, nonce.replace(/.$/, (c) => (c === '0' ? '1' : '0')))), 'tampered nonce');
  assert.strictEqual(await worker.serverIdFor('abc'), cosmetics.serverIdFor('abc'), 'launcher and worker agree on serverId');

  // ---- fake Cloudflare (KV + cache) and Mojang
  const kv = new Map();
  const env = {
    NONCE_SECRET: SECRET,
    COSMETICS: {
      get: async (k, type) => (kv.has(k) ? (type === 'json' ? JSON.parse(kv.get(k)) : type === 'arrayBuffer' ? kv.get(k).buffer.slice(kv.get(k).byteOffset, kv.get(k).byteOffset + kv.get(k).byteLength) : kv.get(k)) : null),
      put: async (k, v) => { kv.set(k, v); },
      delete: async (k) => { kv.delete(k); },
    },
  };
  global.caches = { default: { match: async () => undefined, put: async () => {} } };
  const ctx = { waitUntil: () => {} };
  const realFetch = global.fetch;
  const joined = new Map(); // serverId -> profile
  const PROFILES = { TOKEN: { id: '0123456789abcdef0123456789abcdef', name: 'Jerrix' } };
  let joinCalls = 0;
  global.fetch = async (url, opts = {}) => {
    const u = String(url);
    if (u.startsWith('https://api.example.test/')) return worker.default.fetch(new Request(u, opts), env, ctx);
    if (u === 'https://sessionserver.mojang.com/session/minecraft/join') {
      joinCalls++;
      const b = JSON.parse(opts.body);
      const prof = PROFILES[b.accessToken];
      if (!prof || prof.id !== b.selectedProfile) return new Response('', { status: 403 });
      joined.set(b.serverId, prof);
      return new Response(null, { status: 204 });
    }
    if (u.startsWith('https://sessionserver.mojang.com/session/minecraft/hasJoined')) {
      const q = new URL(u).searchParams;
      const prof = joined.get(q.get('serverId'));
      return prof && prof.name === q.get('username') ? Response.json(prof) : new Response(null, { status: 204 });
    }
    throw new Error(`unexpected fetch ${u}`);
  };

  const API = 'https://api.example.test';
  const acc = { uuid: '0123456789abcdef0123456789abcdef', name: 'Jerrix', mcToken: 'TOKEN' };
  const items = cosmetics.normalize({ ears: { on: true }, tail: { on: true }, wings: { on: true, style: 'demon', color: '#8b1a1a' }, halo: { on: true } });

  // ---- saving works end to end
  const r = await cosmetics.saveAndSync(API, acc, items);
  assert.strictEqual(r.synced, true, r.error);
  assert.deepStrictEqual(cosmetics.get(acc.uuid), { items, synced: true });
  const res = await fetch(`${API}/v1/cosmetics?uuids=${acc.uuid},ffffffffffffffffffffffffffffffff,not-a-uuid`);
  const all = await res.json();
  assert.deepStrictEqual(Object.keys(all), [acc.uuid]);
  assert.strictEqual(all[acc.uuid].ears.on, true);
  assert.strictEqual(all[acc.uuid].bow.on, false);
  assert.strictEqual(all[acc.uuid].wings.style, 'demon');
  assert.strictEqual(all[acc.uuid].halo.on, true);

  // ---- someone else can't save as you
  const ch = await (await fetch(`${API}/v1/challenge`, { method: 'POST' })).json();
  let bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ username: 'Jerrix', nonce: ch.nonce, cosmetics: { bow: { on: true } } }) });
  assert.strictEqual(bad.status, 403, 'no Mojang join = not allowed');
  // ...and a code can't be used twice
  const sid = cosmetics.serverIdFor(ch.nonce);
  joined.set(sid, PROFILES.TOKEN);
  const ok = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ username: 'Jerrix', nonce: ch.nonce, cosmetics: items }) });
  assert.strictEqual(ok.status, 200);
  bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ username: 'Jerrix', nonce: ch.nonce, cosmetics: items }) });
  assert.strictEqual(bad.status, 400, 'nonce reuse');

  // ---- wrong token: saved locally, not online, with a readable reason
  const r2 = await cosmetics.saveAndSync(API, { ...acc, mcToken: 'EXPIRED' }, { ...items, bow: { on: true, color: '#ffffff' } });
  assert.strictEqual(r2.synced, false);
  assert.match(r2.error, /login has expired/);
  assert.strictEqual(cosmetics.get(acc.uuid).items.bow.on, true);
  assert.strictEqual(cosmetics.get(acc.uuid).synced, false);
  // the launch-time retry fixes it once the login works
  await cosmetics.retryIfNeeded(API, acc);
  assert.strictEqual(cosmetics.get(acc.uuid).synced, true);
  const after = await (await fetch(`${API}/v1/cosmetics?uuids=${acc.uuid}`)).json();
  assert.strictEqual(after[acc.uuid].bow.color, '#ffffff');

  // ---- service offline: still saved for you
  const r3 = await cosmetics.saveAndSync('https://offline.example.test', acc, items);
  assert.strictEqual(r3.synced, false);
  assert.match(r3.error, /Couldn't reach/);

  // ---- the file the in-game mod reads
  const game = path.join(tmp, 'game');
  cosmetics.writeForGame(game, API, acc);
  const forGame = JSON.parse(fs.readFileSync(path.join(game, 'config', 'catgirl-cosmetics.json'), 'utf8'));
  assert.strictEqual(forGame.api, API);
  assert.strictEqual(forGame.uuid, acc.uuid);
  assert.strictEqual(forGame.items.ears.on, true);

  // ---- cape pictures: saved on this PC, uploaded with the cosmetics, served to everyone
  const zlib = require('zlib');
  const png = (w, h) => {
    const crc = (buf) => { let c = ~0; for (const b of buf) { c ^= b; for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1)); } return ~c >>> 0; };
    const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
    const raw = Buffer.alloc((w * 4 + 1) * h, 0x7f);
    for (let y = 0; y < h; y++) raw[y * (w * 4 + 1)] = 0;
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
  };
  assert.throws(() => cosmetics.saveCapePicture(png(64, 64).toString('base64'), 1, 100), /wrong size/);
  const strip = png(60, 96 * 3);
  const custom = cosmetics.saveCapePicture(strip.toString('base64'), 3, 70);
  assert.strictEqual(custom.frames, 3);
  assert.strictEqual(custom.sha, crypto.createHash('sha256').update(strip).digest('hex'));
  const withCape = cosmetics.normalize({ ...items, cape: { on: true, style: 'meow', custom } });
  assert.deepStrictEqual(withCape.cape.custom, custom);
  const r4 = await cosmetics.saveAndSync(API, acc, withCape);
  assert.strictEqual(r4.synced, true, r4.error);
  const online = (await (await fetch(`${API}/v1/cosmetics?uuids=${acc.uuid}`)).json())[acc.uuid].cape;
  assert.strictEqual(online.image, custom.sha);
  assert.strictEqual(online.frames, 3);
  assert.strictEqual(online.delay, 70);
  assert.strictEqual(online.custom, undefined, 'local-only fields never go online');
  const served = await fetch(`${API}/v1/cape/${custom.sha}.png`);
  assert.strictEqual(served.status, 200);
  assert.ok(Buffer.from(await served.arrayBuffer()).equals(strip));
  // the game gets your own picture straight from this PC
  cosmetics.writeForGame(path.join(tmp, 'game2'), API, acc, { showCapePictures: false });
  const g2 = JSON.parse(fs.readFileSync(path.join(tmp, 'game2', 'config', 'catgirl-cosmetics.json'), 'utf8'));
  assert.strictEqual(g2.showCapePictures, false);
  assert.strictEqual(g2.items.cape.image, custom.sha);
  assert.ok(fs.existsSync(g2.items.cape.file));
  // moderation: an admin can take a picture down, and it can't come back
  env.ADMIN_TOKEN = 'admin-secret';
  let down = await fetch(`${API}/v1/admin/remove-cape`, { method: 'POST', headers: { authorization: 'Bearer nope' }, body: JSON.stringify({ uuid: acc.uuid }) });
  assert.strictEqual(down.status, 403);
  down = await fetch(`${API}/v1/admin/remove-cape`, { method: 'POST', headers: { authorization: 'Bearer admin-secret' }, body: JSON.stringify({ uuid: acc.uuid }) });
  assert.strictEqual((await down.json()).removed, true);
  assert.strictEqual((await fetch(`${API}/v1/cape/${custom.sha}.png`)).status, 404);
  const again = await cosmetics.saveAndSync(API, acc, withCape);
  assert.strictEqual(again.synced, false);
  assert.match(again.error, /isn't allowed/);
  // a wrong-sized upload straight to the service is refused
  assert.throws(() => worker.checkCapeImage({ png: png(60, 100).toString('base64'), frames: 1 }), /wrong size/);

  global.fetch = realFetch;
  assert.ok(joinCalls >= 2);
  console.log('cosmetics tests passed');
})().catch((e) => { console.error(e); process.exit(1); });
