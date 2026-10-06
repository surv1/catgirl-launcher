// Tests for v0.3 pieces that run without Electron: server ping, log following, server detection,
// picture checks, mod migration and modpack install (network is faked).
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const zlib = require('zlib');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cg-'));
require('../src/main/paths').init(tmp);
const { ping, varint, readVarint, plainText } = require('../src/main/ping');
const { parseServer, followLog } = require('../src/main/launch');
const { sniffImage } = require('../src/main/userAssets');
const instances = require('../src/main/instances');
const mods = require('../src/main/mods');

let n = 0;
const t = async (name, fn) => { await fn(); n++; console.log('ok -', name); };

// tiny zip writer (stored entries) for the fake modpack
function makeZip(files) {
  const locals = []; const cens = []; let off = 0;
  for (const [name, content] of Object.entries(files)) {
    const data = Buffer.from(content); const nb = Buffer.from(name);
    const crc = zlib.crc32 ? zlib.crc32(data) : 0;
    const loc = Buffer.alloc(30); loc.writeUInt32LE(0x04034b50, 0); loc.writeUInt16LE(20, 4); loc.writeUInt32LE(crc >>> 0, 14);
    loc.writeUInt32LE(data.length, 18); loc.writeUInt32LE(data.length, 22); loc.writeUInt16LE(nb.length, 26);
    const cen = Buffer.alloc(46); cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 6); cen.writeUInt32LE(crc >>> 0, 16);
    cen.writeUInt32LE(data.length, 20); cen.writeUInt32LE(data.length, 24); cen.writeUInt16LE(nb.length, 28); cen.writeUInt32LE(off, 42);
    locals.push(loc, nb, data); cens.push(cen, nb); off += 30 + nb.length + data.length;
  }
  const cenBuf = Buffer.concat(cens);
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(Object.keys(files).length, 8);
  end.writeUInt16LE(Object.keys(files).length, 10); end.writeUInt32LE(cenBuf.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...locals, cenBuf, end]);
}

// fake fetch: routes -> body
let routes = {};
global.fetch = async (url) => {
  const key = Object.keys(routes).find((k) => url.startsWith(k));
  if (!key) return { ok: false, status: 404, text: async () => 'nope', body: null };
  const v = routes[key];
  const buf = Buffer.isBuffer(v) ? v : Buffer.from(typeof v === 'string' ? v : JSON.stringify(v));
  return { ok: true, status: 200, text: async () => buf.toString(), arrayBuffer: async () => buf, body: new Blob([buf]).stream() };
};

(async () => {
  await t('varint round trip', () => {
    for (const v of [0, 1, 127, 128, 255, 25565, 2097151, 767]) assert.strictEqual(readVarint(varint(v), 0).value, v);
  });

  await t('plainText of chat components', () => {
    assert.strictEqual(plainText({ text: '§dCat', extra: [{ text: 'land ' }, { text: 'SMP' }] }), 'Catland SMP');
    assert.strictEqual(plainText('§aHello'), 'Hello');
  });

  await t('server list ping against a fake server', async () => {
    const reply = JSON.stringify({ description: { text: 'Welcome to ', extra: [{ text: 'Catland' }] }, players: { online: 12, max: 100 }, version: { name: '1.21.11' }, favicon: 'data:image/png;base64,AAAA' });
    const srv = net.createServer((sock) => {
      let got = Buffer.alloc(0);
      sock.on('data', (d) => {
        got = Buffer.concat([got, d]);
        const body = Buffer.concat([varint(0), varint(Buffer.byteLength(reply)), Buffer.from(reply)]);
        sock.write(Buffer.concat([varint(body.length), body.subarray(0, 5)]));
        setTimeout(() => sock.write(body.subarray(5)), 30); // split packet on purpose
      });
    });
    await new Promise((r) => srv.listen(0, '127.0.0.1', r));
    const info = await ping(`127.0.0.1:${srv.address().port}`);
    srv.close();
    assert.strictEqual(info.motd, 'Welcome to Catland');
    assert.strictEqual(info.online, 12);
    assert.strictEqual(info.max, 100);
    assert.strictEqual(info.favicon, 'data:image/png;base64,AAAA');
  });

  await t('ping to a closed port fails nicely', async () => {
    const srv = net.createServer(); await new Promise((r) => srv.listen(0, '127.0.0.1', r)); const port = srv.address().port; srv.close();
    await assert.rejects(ping(`127.0.0.1:${port}`, 2000), /Can't reach server/);
  });

  await t('detect the server from the game log', () => {
    assert.strictEqual(parseServer('[02:10:01] [Render thread/INFO]: Connecting to play.catland.net, 25565'), 'play.catland.net');
    assert.strictEqual(parseServer('[x] Connecting to 1.2.3.4, 25570'), '1.2.3.4:25570');
    assert.strictEqual(parseServer('[x] Loading world'), null);
  });

  await t('follow a growing log file', async () => {
    const f = path.join(tmp, 'g.log'); fs.writeFileSync(f, '');
    const lines = []; const tail = followLog(f, (l) => lines.push(l));
    fs.appendFileSync(f, 'one\ntw'); await new Promise((r) => setTimeout(r, 400));
    fs.appendFileSync(f, 'o\nthree'); await new Promise((r) => setTimeout(r, 400));
    tail.stop();
    assert.deepStrictEqual(lines, ['one', 'two', 'three']);
  });

  await t('only real pictures are accepted as backgrounds', () => {
    assert.strictEqual(sniffImage(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])), 'png');
    assert.strictEqual(sniffImage(Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0])), 'jpg');
    assert.strictEqual(sniffImage(Buffer.from('<html>not an image</html>')), null);
  });

  await t('mods follow the instance to a new Minecraft version', async () => {
    const inst = instances.create({ name: 'Mig', mcVersion: '1.21.4', loader: 'fabric' });
    const dir = path.join(instances.gameDir(inst.id), 'mods'); fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'sodium-1.21.4.jar'), 'old'); fs.writeFileSync(path.join(dir, 'oldmod-1.21.4.jar'), 'old');
    fs.writeFileSync(path.join(instances.instDir(inst.id), 'mods.json'), JSON.stringify({
      AANobbMI: { file: 'sodium-1.21.4.jar', title: 'Sodium', version: '0.6' },
      OLD: { file: 'oldmod-1.21.4.jar', title: 'Old Mod', version: '1' },
    }));
    instances.update(inst.id, { mcVersion: '1.21.11' });
    routes = {
      'https://api.modrinth.com/v2/project/AANobbMI/version': [{ project_id: 'AANobbMI', version_number: '0.7', version_type: 'release', files: [{ primary: true, url: 'https://cdn/sodium-new.jar', filename: 'sodium-1.21.11.jar' }] }],
      'https://api.modrinth.com/v2/project/OLD/version': [],
      'https://cdn/sodium-new.jar': 'newjar',
    };
    const r = await mods.migrate(inst.id);
    assert.deepStrictEqual(r.updated, ['Sodium']);
    assert.deepStrictEqual(r.disabled, ['Old Mod']);
    const files = fs.readdirSync(dir).sort();
    assert.deepStrictEqual(files, ['oldmod-1.21.4.jar.disabled', 'sodium-1.21.11.jar']);
  });

  await t('install a Modrinth modpack', async () => {
    const index = {
      formatVersion: 1, game: 'minecraft', name: 'Cozy Pack',
      dependencies: { minecraft: '1.21.1', 'fabric-loader': '0.16.9' },
      files: [
        { path: 'mods/a.jar', downloads: ['https://cdn/a.jar'], env: { client: 'required' }, fileSize: 1 },
        { path: 'mods/server-only.jar', downloads: ['https://cdn/s.jar'], env: { client: 'unsupported' } },
        { path: '../../evil.jar', downloads: ['https://cdn/a.jar'] },
      ],
    };
    const pack = makeZip({ 'modrinth.index.json': JSON.stringify(index), 'overrides/config/cozy.txt': 'cfg', 'overrides/options.txt': 'opts' });
    routes = {
      'https://api.modrinth.com/v2/project/PACK/version': [{ id: 'V1', version_type: 'release', version_number: '1.0', files: [{ primary: true, url: 'https://cdn/pack.mrpack' }] }],
      'https://api.modrinth.com/v2/project/PACK': { title: 'Cozy Pack', icon_url: 'https://cdn/icon.png' },
      'https://cdn/pack.mrpack': pack,
      'https://cdn/a.jar': 'A',
    };
    const inst = await mods.installPack('PACK');
    assert.strictEqual(inst.mcVersion, '1.21.1');
    assert.strictEqual(inst.loader, 'fabric');
    assert.strictEqual(inst.loaderVersion, '0.16.9');
    const game = instances.gameDir(inst.id);
    assert.ok(fs.existsSync(path.join(game, 'mods/a.jar')));
    assert.ok(!fs.existsSync(path.join(game, 'mods/server-only.jar')));
    assert.strictEqual(fs.readFileSync(path.join(game, 'config/cozy.txt'), 'utf8'), 'cfg');
    assert.ok(!fs.existsSync(path.join(path.dirname(path.dirname(game)), 'evil.jar')));
  });

  console.log(`\n${n} tests passed`);
  fs.rmSync(tmp, { recursive: true, force: true });
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
