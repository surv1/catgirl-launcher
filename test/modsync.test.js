// Before-launch mod check: out-of-date mods get updated, impossible ones get switched off.
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ms-'));
require('../src/main/paths').init(tmp);
const instances = require('../src/main/instances');
const mods = require('../src/main/mods');

// A tiny zip writer (stored entries) for fake mod jars.
function zip(files) {
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = ~0; for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return ~c >>> 0; };
  const locals = [], centrals = [];
  let off = 0;
  for (const [name, text] of Object.entries(files)) {
    const data = Buffer.from(text), nm = Buffer.from(name);
    const h = Buffer.alloc(30); h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt32LE(crc(data), 14); h.writeUInt32LE(data.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(nm.length, 26);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt32LE(crc(data), 16); c.writeUInt32LE(data.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(nm.length, 28); c.writeUInt32LE(off, 42);
    locals.push(h, nm, data); centrals.push(c, nm); off += 30 + nm.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(Object.keys(files).length, 8); end.writeUInt16LE(Object.keys(files).length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...locals, cd, end]);
}
const fmj = (id, mc, extra = '') => zip({ 'fabric.mod.json': JSON.stringify({ schemaVersion: 1, id, name: id, depends: mc === undefined ? {} : { minecraft: mc } }) + extra });
const sha1 = (b) => crypto.createHash('sha1').update(b).digest('hex');

(async () => {
  const inst = instances.create({ name: 'Friend', mcVersion: '1.21.11', loader: 'fabric' });
  const dir = path.join(instances.gameDir(inst.id), 'mods');
  fs.mkdirSync(dir, { recursive: true });
  const put = (name, buf) => { fs.writeFileSync(path.join(dir, name), buf); return sha1(buf); };

  // On Modrinth: sodium (old build for 1.21.4, newer one exists), lithium (already right),
  // oldmod (only exists for 1.20), and a new build of sodium needs a library we don't have.
  const sodiumOld = fmj('sodium', '~1.21.4'), sodiumNew = fmj('sodium', '>=1.21.11', ' ');
  const hSodium = put('sodium-0.6-mc1.21.4.jar', sodiumOld);
  const hLith = put('lithium-mc1.21.11.jar', fmj('lithium', '1.21.11'));
  const hOld = put('oldmod-1.20.jar', fmj('oldmod', '1.20.x'));
  // Not on Modrinth: one that says it fits, one that doesn't, a Forge mod, and our own jar.
  put('handmade-ok.jar', fmj('okmod', '>=1.21'));
  put('handmade-old.jar', fmj('oldhand', '~1.20.1'));
  put('forge-thing.jar', zip({ 'META-INF/mods.toml': 'modLoader="javafml"' }));
  put('catgirl-client-v0.6.0-mc1.21.11.jar', fmj('catgirl', '1.20.x')); // never touched
  const libJar = fmj('sodium-lib', '*');

  const V = (pid, ver, games, file, buf, deps = []) => ({ project_id: pid, version_number: ver, game_versions: games, loaders: ['fabric'], dependencies: deps, files: [{ primary: true, filename: file, url: `https://cdn.test/${file}`, size: buf.length, hashes: { sha1: sha1(buf) } }] });
  const known = {
    [hSodium]: V('AANobbMI', '0.6', ['1.21.4'], 'sodium-0.6-mc1.21.4.jar', sodiumOld),
    [hLith]: V('gvQqBUqZ', '0.15', ['1.21.11'], 'lithium-mc1.21.11.jar', Buffer.alloc(1)),
    [hOld]: V('OLDMOD', '1.0', ['1.20.1'], 'oldmod-1.20.jar', Buffer.alloc(1)),
  };
  const newSodium = V('AANobbMI', '0.7', ['1.21.11'], 'sodium-0.7-mc1.21.11.jar', sodiumNew, [{ dependency_type: 'required', project_id: 'SODLIB' }]);
  const cdn = { 'sodium-0.7-mc1.21.11.jar': sodiumNew, 'sodium-lib.jar': libJar };
  const calls = [];
  global.fetch = async (url, opts = {}) => {
    const u = String(url);
    calls.push(u);
    if (u.endsWith('/v2/version_files')) {
      const { hashes } = JSON.parse(opts.body);
      return Response.json(Object.fromEntries(hashes.filter((h) => known[h]).map((h) => [h, known[h]])));
    }
    if (u.endsWith('/v2/version_files/update')) {
      const b = JSON.parse(opts.body);
      assert.deepStrictEqual(b.game_versions, ['1.21.11']);
      assert.deepStrictEqual(b.loaders, ['fabric']);
      return Response.json(b.hashes.includes(hSodium) ? { [hSodium]: newSodium } : {});
    }
    if (u.includes('/v2/project/SODLIB/version')) return Response.json([V('SODLIB', '1.0', ['1.21.11'], 'sodium-lib.jar', libJar)]);
    if (u.endsWith('/v2/project/SODLIB')) return Response.json({ title: 'Sodium Lib' });
    if (u.startsWith('https://cdn.test/')) return new Response(cdn[u.split('/').pop()]);
    throw new Error(`unexpected fetch ${u}`);
  };

  const log = [];
  const r = await mods.syncToVersion(inst.id, (l) => log.push(l));
  const files = fs.readdirSync(dir).sort();
  assert.ok(files.includes('sodium-0.7-mc1.21.11.jar'), 'sodium updated');
  assert.ok(!files.includes('sodium-0.6-mc1.21.4.jar'), 'old sodium removed');
  assert.ok(files.includes('sodium-lib.jar'), "sodium's new required library installed");
  assert.ok(files.includes('lithium-mc1.21.11.jar'), 'lithium left alone');
  assert.ok(files.includes('oldmod-1.20.jar.disabled'), 'no build for this version: turned off');
  assert.ok(files.includes('handmade-ok.jar'), 'fitting hand-added mod left alone');
  assert.ok(files.includes('handmade-old.jar.disabled'), 'hand-added mod for another version: turned off');
  assert.ok(files.includes('forge-thing.jar.disabled'), 'Forge mod: turned off');
  assert.ok(files.includes('catgirl-client-v0.6.0-mc1.21.11.jar'), 'our own jar is never touched');
  assert.strictEqual(r.updated.length, 1);
  assert.strictEqual(r.disabled.length, 3);
  assert.ok(log.some((l) => /Updated sodium-0\.6/.test(l)));
  assert.ok(log.some((l) => /Forge mod/.test(l)));
  assert.ok(log.some((l) => /made for Minecraft ~1\.20\.1/.test(l)));

  // Running again changes nothing.
  known[sha1(sodiumNew)] = newSodium;
  known[sha1(libJar)] = V('SODLIB', '1.0', ['1.21.11'], 'sodium-lib.jar', libJar);
  const r2 = await mods.syncToVersion(inst.id, () => {});
  assert.deepStrictEqual([r2.updated.length, r2.disabled.length], [0, 0]);

  // Modrinth down: nothing breaks, unknown-but-fine mods stay on.
  global.fetch = async () => { throw new Error('offline'); };
  const r3 = await mods.syncToVersion(inst.id, () => {});
  assert.strictEqual(r3.disabled.length, 0);

  // Vanilla instances are skipped.
  const van = instances.create({ name: 'Plain', mcVersion: '1.21.11', loader: 'vanilla' });
  assert.deepStrictEqual(await mods.syncToVersion(van.id), { updated: [], disabled: [], ok: 0 });

  // Version rules from fabric.mod.json
  const m = mods.matchesMc;
  assert.strictEqual(m('1.21.x', '1.21.11'), true);
  assert.strictEqual(m('~1.21.4', '1.21.11'), true);
  assert.strictEqual(m('~1.20.4', '1.21.11'), false);
  assert.strictEqual(m(['1.21', '1.21.1'], '1.21.11'), false);
  assert.strictEqual(m('>=1.21 <1.22', '1.21.11'), true);
  assert.strictEqual(m(undefined, '1.21.11'), true);
  console.log('mod sync tests passed');
})().catch((e) => { console.error(e); process.exit(1); });
