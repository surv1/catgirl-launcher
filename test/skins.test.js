// Skin service: validation, history, wearing a skin, copying a player's skin (network faked).
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sk-'));
require('../src/main/paths').init(tmp);
const skins = require('../src/main/skins');
const fx = (n) => fs.readFileSync(path.join(__dirname, 'fixtures', n));

let current = { buf: fx('skin-pink.png'), variant: 'CLASSIC' };
const calls = [];
global.fetch = async (url, opts = {}) => {
  calls.push({ url, method: opts.method || 'GET' });
  const json = (status, body) => ({ ok: status < 300, status, json: async () => body, arrayBuffer: async () => Buffer.from(JSON.stringify(body)) });
  const bin = (buf) => ({ ok: true, status: 200, arrayBuffer: async () => buf, json: async () => ({}) });
  if (url === 'https://api.minecraftservices.com/minecraft/profile') {
    assert.strictEqual(opts.headers.Authorization, 'Bearer TOKEN');
    return json(200, { id: 'u', name: 'Jerrix3', skins: [{ state: 'ACTIVE', url: 'http://textures.minecraft.net/texture/current', variant: current.variant }] });
  }
  if (url === 'https://textures.minecraft.net/texture/current') return bin(current.buf);
  if (url === 'https://api.minecraftservices.com/minecraft/profile/skins') {
    if (opts.headers.Authorization !== 'Bearer TOKEN') return json(401, {});
    const variant = opts.body.get('variant');
    const file = opts.body.get('file');
    const buf = Buffer.from(await file.arrayBuffer());
    current = { buf, variant: variant.toUpperCase() };
    return json(200, { skins: [{ state: 'ACTIVE', variant }] });
  }
  if (url === 'https://api.mojang.com/users/profiles/minecraft/Notch') return json(200, { id: 'abc', name: 'Notch' });
  if (url === 'https://api.mojang.com/users/profiles/minecraft/Nobody123') return json(404, {});
  if (url === 'https://sessionserver.mojang.com/session/minecraft/profile/abc') {
    const tex = Buffer.from(JSON.stringify({ textures: { SKIN: { url: 'http://textures.minecraft.net/texture/notch', metadata: { model: 'slim' } } } })).toString('base64');
    return json(200, { properties: [{ name: 'textures', value: tex }] });
  }
  if (url === 'https://textures.minecraft.net/texture/notch') return bin(fx('skin-old.png'));
  if (url === 'https://cdn.example/skin.png') return bin(fx('skin-purple.png'));
  if (url === 'https://cdn.example/big.png') return bin(fx('not-a-skin.png'));
  return json(404, {});
};

(async () => {
  const account = { uuid: 'u', name: 'Jerrix3', mcToken: 'TOKEN' };

  assert.deepStrictEqual(skins.pngSize(fx('skin-purple.png')), { width: 64, height: 64 });
  assert.throws(() => skins.checkSkin(fx('not-a-skin.png')), /300×300/);
  assert.throws(() => skins.checkSkin(Buffer.from('<html>')), /isn't a skin/);
  skins.checkSkin(fx('skin-old.png')); // old 64x32 skins are fine
  console.log('ok - only real skin pictures are accepted');

  await skins.wear(account, fx('skin-purple.png'), { variant: 'slim', name: 'Purple girl', source: 'www.minecraftskins.com' });
  assert.ok(current.buf.equals(fx('skin-purple.png')));
  assert.strictEqual(current.variant, 'SLIM');
  let h = skins.history('u');
  assert.strictEqual(h.length, 2);
  assert.strictEqual(h[0].name, 'Purple girl');                 // newest first
  assert.strictEqual(h[1].name, 'Skin you were wearing');       // the old skin was saved first
  assert.ok(h[1].dataUrl.startsWith('data:image/png;base64,'));
  console.log('ok - wearing a skin saves the old one to "Old skins used" first');

  // switch back to the old one from history
  const old = h[1];
  await skins.wear(account, skins.historyBuffer(old.id), { variant: old.variant });
  assert.ok(current.buf.equals(fx('skin-pink.png')));
  h = skins.history('u');
  assert.strictEqual(h.length, 2);                              // no duplicates
  assert.strictEqual(h[0].id, old.id);
  console.log('ok - switching back works and never duplicates history');

  const p = await skins.fromPlayer('Notch');
  assert.strictEqual(p.variant, 'slim');
  assert.strictEqual(p.name, "Notch's skin");
  await assert.rejects(skins.fromPlayer('Nobody123'), /No player called Nobody123/);
  await assert.rejects(skins.fromPlayer('bad name!'), /username/);
  console.log("ok - copy a player's skin by username");

  assert.ok((await skins.fromUrl('https://cdn.example/skin.png')).dataUrl);
  await assert.rejects(skins.fromUrl('https://cdn.example/big.png'), /Download button/);
  await assert.rejects(skins.fromUrl('http://insecure/skin.png'), /https/);
  console.log('ok - skin links are checked');

  await assert.rejects(skins.wear({ ...account, mcToken: 'EXPIRED' }, fx('skin-purple.png')), /login has expired/);
  skins.removeFromHistory(old.id, 'u');
  assert.ok(!skins.history('u').some((e) => e.id === old.id));
  console.log('ok - expired logins explained; history items can be removed\n\n6 tests passed');
  fs.rmSync(tmp, { recursive: true, force: true });
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
