// Shared Minecraft settings across instances.
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'so-'));
require('../src/main/paths').init(tmp);
const instances = require('../src/main/instances');
const shared = require('../src/main/sharedOptions');

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const opts = (id) => path.join(instances.gameDir(id), 'options.txt');
const readOpts = (id) => shared.parse(fs.readFileSync(opts(id), 'utf8'));

(async () => {
  const a = instances.create({ name: 'A', mcVersion: '1.21.11', loader: 'fabric' });
  const b = instances.create({ name: 'B', mcVersion: '1.21.4', loader: 'fabric' });
  const c = instances.create({ name: 'C', mcVersion: '1.21.11', loader: 'vanilla', shareSettings: false });
  fs.mkdirSync(instances.gameDir(a.id), { recursive: true });
  fs.mkdirSync(instances.gameDir(b.id), { recursive: true });

  // Player plays A and sets FOV, sensitivity and a keybind; A also has its own resource pack.
  fs.writeFileSync(opts(a.id), 'version:4440\nfov:0.5\nmouseSensitivity:0.8\nkey_key.sprint:key.keyboard.left.control\nresourcePacks:["vanilla","file/Pink.zip"]\n');
  fs.writeFileSync(path.join(instances.gameDir(a.id), 'servers.dat'), 'SERVERS-FROM-A');
  // B already has its own (older) options with a different version and pack.
  fs.writeFileSync(opts(b.id), 'version:4189\nfov:0.0\nrenderDistance:8\nresourcePacks:["vanilla"]\n');
  const past = new Date(Date.now() - 60000); fs.utimesSync(opts(b.id), past, past);

  // Launch B: collect from A (newest), apply to B.
  shared.collect();
  assert.ok(shared.apply(instances.get(b.id)));
  let bo = readOpts(b.id);
  assert.strictEqual(bo.get('fov'), '0.5');
  assert.strictEqual(bo.get('mouseSensitivity'), '0.8');
  assert.strictEqual(bo.get('key_key.sprint'), 'key.keyboard.left.control');
  assert.strictEqual(bo.get('renderDistance'), '8');            // B's own extra setting kept
  assert.strictEqual(bo.get('version'), '4189');                // B keeps its own data version
  assert.strictEqual(bo.get('resourcePacks'), '["vanilla"]');   // packs stay per instance
  assert.strictEqual(fs.readFileSync(path.join(instances.gameDir(b.id), 'servers.dat'), 'utf8'), 'SERVERS-FROM-A');
  assert.ok(fs.readFileSync(opts(b.id), 'utf8').startsWith('version:'));
  console.log('ok - settings from A applied to B (own version, packs and extras kept)');

  // Player changes FOV in B (game writes options later), then launches A again.
  await wait(30);
  fs.writeFileSync(opts(b.id), fs.readFileSync(opts(b.id), 'utf8').replace('fov:0.5', 'fov:0.9'));
  shared.collect();
  shared.apply(instances.get(a.id));
  assert.strictEqual(readOpts(a.id).get('fov'), '0.9');
  assert.strictEqual(readOpts(a.id).get('resourcePacks'), '["vanilla","file/Pink.zip"]');
  console.log('ok - change made in B follows back to A');

  // Opted-out instance C is never touched, and a brand new instance gets everything.
  assert.strictEqual(shared.apply(instances.get(c.id)), false);
  assert.ok(!fs.existsSync(opts(c.id)));
  const d = instances.create({ name: 'D', mcVersion: '1.21.11', loader: 'fabric' });
  shared.apply(instances.get(d.id));
  const dd = readOpts(d.id);
  assert.strictEqual(dd.get('fov'), '0.9');
  assert.ok(dd.has('version'));
  assert.ok(!dd.has('resourcePacks'));
  console.log('ok - opted-out instance untouched; new instance gets shared settings');

  // Applying must not make the target look like the "newest" source of different settings.
  shared.collect();
  shared.apply(instances.get(b.id));
  assert.strictEqual(readOpts(b.id).get('fov'), '0.9');
  console.log('ok - no ping-pong after applying\n\n4 tests passed');
  fs.rmSync(tmp, { recursive: true, force: true });
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
