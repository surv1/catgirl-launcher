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
  assert.strictEqual(cosmetics.isoToMillis('2026-10-08T01:02:03.123456Z'), Date.UTC(2026, 9, 8, 1, 2, 3, 123));
  assert.strictEqual(cosmetics.isoToMillis('2026-10-08T01:02:03Z'), Date.UTC(2026, 9, 8, 1, 2, 3));

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
  // Fake Mojang: a "Mojang" signing key, and per-account player certificates signed by it,
  // exactly like https://api.minecraftservices.com/player/certificates.
  const mojang = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const mojangPub = mojang.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const PROFILES = { TOKEN: '0123456789abcdef0123456789abcdef', OTHER: 'ffffffffffffffffffffffffffffffff' };
  const pem = (label, der) => `-----BEGIN ${label}-----\n${der.toString('base64').match(/.{1,64}/g).join('\n')}\n-----END ${label}-----\n`;
  function certFor(uuid, expiresAt = Date.now() + 48 * 3600e3) {
    const kp = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
    const spki = kp.publicKey.export({ type: 'spki', format: 'der' });
    const payload = Buffer.alloc(24 + spki.length);
    Buffer.from(uuid, 'hex').copy(payload, 0);
    payload.writeBigUInt64BE(BigInt(expiresAt), 16);
    spki.copy(payload, 24);
    const iso = new Date(expiresAt).toISOString().replace('Z', '456Z'); // Mojang sends microseconds
    return {
      keyPair: { privateKey: pem('RSA PRIVATE KEY', kp.privateKey.export({ type: 'pkcs8', format: 'der' })), publicKey: pem('RSA PUBLIC KEY', spki) },
      publicKeySignatureV2: crypto.sign('sha1', payload, mojang.privateKey).toString('base64'),
      expiresAt: iso, refreshedAfter: iso,
    };
  }
  let certCalls = 0;
  global.fetch = async (url, opts = {}) => {
    const u = String(url);
    if (u.startsWith('https://api.example.test/')) return worker.default.fetch(new Request(u, opts), env, ctx);
    if (u === 'https://api.minecraftservices.com/publickeys') return Response.json({ playerCertificateKeys: [{ publicKey: mojangPub }] });
    if (u === 'https://api.minecraftservices.com/player/certificates') {
      certCalls++;
      const uuid = PROFILES[String(opts.headers?.Authorization || '').replace('Bearer ', '')];
      return uuid ? Response.json(certFor(uuid)) : new Response('', { status: 401 });
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
  const signNonce = (c, nonce) => {
    const key = crypto.createPrivateKey({ key: Buffer.from(c.keyPair.privateKey.replace(/-----[^-]+-----|\s/g, ''), 'base64'), format: 'der', type: 'pkcs8' });
    return crypto.sign('sha256', Buffer.from(nonce), key).toString('base64');
  };
  const certBody = (c) => ({ publicKey: c.keyPair.publicKey.replace(/-----[^-]+-----|\s/g, ''), expiresAt: cosmetics.isoToMillis(c.expiresAt), signature: c.publicKeySignatureV2 });
  let ch = await (await fetch(`${API}/v1/challenge`, { method: 'POST' })).json();
  const other = certFor(PROFILES.OTHER);
  // their own valid key, but claiming to be you
  let bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ uuid: acc.uuid, nonce: ch.nonce, cert: certBody(other), sig: signNonce(other, ch.nonce), cosmetics: { bow: { on: true } } }) });
  assert.strictEqual(bad.status, 403, "another player's key can't save as you");
  // a key Mojang never signed
  const forged = certFor(acc.uuid); forged.publicKeySignatureV2 = other.publicKeySignatureV2;
  bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ uuid: acc.uuid, nonce: ch.nonce, cert: certBody(forged), sig: signNonce(forged, ch.nonce), cosmetics: {} }) });
  assert.strictEqual(bad.status, 403, 'forged key');
  // the right key but signing a different code
  const mine = certFor(acc.uuid);
  bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ uuid: acc.uuid, nonce: ch.nonce, cert: certBody(mine), sig: signNonce(mine, 'something else'), cosmetics: {} }) });
  assert.strictEqual(bad.status, 403, 'signature must cover the code');
  // an expired key
  const old = certFor(acc.uuid, Date.now() - 1000);
  bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ uuid: acc.uuid, nonce: ch.nonce, cert: certBody(old), sig: signNonce(old, ch.nonce), cosmetics: {} }) });
  assert.strictEqual(bad.status, 403, 'expired key');
  // ...and a code can't be used twice
  const ok = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ uuid: acc.uuid, nonce: ch.nonce, cert: certBody(mine), sig: signNonce(mine, ch.nonce), cosmetics: items }) });
  assert.strictEqual(ok.status, 200, await ok.clone().text());
  bad = await fetch(`${API}/v1/me`, { method: 'PUT', body: JSON.stringify({ uuid: acc.uuid, nonce: ch.nonce, cert: certBody(mine), sig: signNonce(mine, ch.nonce), cosmetics: items }) });
  assert.strictEqual(bad.status, 400, 'nonce reuse');
  // the built-in Mojang keys parse, and the live list gets cached
  assert.ok(JSON.parse(kv.get('mojangkeys')).includes(mojangPub));

  // ---- wrong token: saved locally, not online, with a readable reason
  PROFILES.TOKEN3 = 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee';
  const acc3 = { uuid: PROFILES.TOKEN3, name: 'Friend', mcToken: 'EXPIRED' };
  const r2 = await cosmetics.saveAndSync(API, acc3, { ...items, bow: { on: true, color: '#ffffff' } });
  assert.strictEqual(r2.synced, false);
  assert.match(r2.error, /login has expired/);
  assert.strictEqual(cosmetics.get(acc3.uuid).items.bow.on, true);
  assert.strictEqual(cosmetics.get(acc3.uuid).synced, false);
  // the launch-time retry fixes it once the login works
  await cosmetics.retryIfNeeded(API, { ...acc3, mcToken: 'TOKEN3' });
  assert.strictEqual(cosmetics.get(acc3.uuid).synced, true);
  const after = await (await fetch(`${API}/v1/cosmetics?uuids=${acc3.uuid}`)).json();
  assert.strictEqual(after[acc3.uuid].bow.color, '#ffffff');
  // the signed key is reused while it's valid, even if the login token has expired since
  const calls = certCalls;
  assert.strictEqual((await cosmetics.saveAndSync(API, { ...acc, mcToken: 'EXPIRED' }, items)).synced, true);
  assert.strictEqual(certCalls, calls);

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
  assert.ok(certCalls >= 2);
  console.log('cosmetics tests passed');
})().catch((e) => { console.error(e); process.exit(1); });
