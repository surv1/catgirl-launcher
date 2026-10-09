// Wardrobe: which downloads count as a skin (normal, HD/Bedrock, zipped skin packs).
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const Module = require('module');
const origLoad = Module._load;
Module._load = function (req, ...rest) { return req === 'electron' ? {} : origLoad.call(this, req, ...rest); };
const { findSkin } = require('../src/main/wardrobe');

const skin64 = fs.readFileSync(path.join(__dirname, 'fixtures', 'skin-pink.png'));
const resized = (w, h) => { const b = Buffer.from(skin64); b.writeUInt32BE(w, 16); b.writeUInt32BE(h, 20); return b; };
function zip(files) { // stored + deflated entries, enough for our reader
  const locals = [], centrals = []; let off = 0;
  for (const [name, data] of Object.entries(files)) {
    const comp = zlib.deflateRawSync(data), nm = Buffer.from(name);
    const h = Buffer.alloc(30); h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(8, 8); h.writeUInt32LE(comp.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(nm.length, 26);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(8, 10); c.writeUInt32LE(comp.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(nm.length, 28); c.writeUInt32LE(off, 42);
    locals.push(h, nm, comp); centrals.push(c, nm); off += 30 + nm.length + comp.length;
  }
  const cd = Buffer.concat(centrals), n = Object.keys(files).length;
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(n, 8); end.writeUInt16LE(n, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...locals, cd, end]);
}

assert.deepStrictEqual(findSkin(skin64), { buf: skin64, hd: false }, 'normal skin');
assert.strictEqual(findSkin(resized(64, 32)).hd, false, 'old 64×32 skin');
assert.strictEqual(findSkin(resized(128, 128)).hd, true, 'HD Bedrock skin gets shrunk');
assert.strictEqual(findSkin(resized(256, 128)).hd, true, 'HD old-style skin');
assert.strictEqual(findSkin(resized(300, 300)), null, 'random picture');
assert.strictEqual(findSkin(Buffer.from('<html>login</html>')), null, 'a web page');
const pack = zip({ 'pack_icon.png': resized(256, 256), 'manifest.json': Buffer.from('{}'), 'skins/my_skin.png': skin64 });
assert.ok(findSkin(pack)?.buf.equals(skin64), 'skin pack: picks the skin, not the icon');
assert.strictEqual(findSkin(zip({ 'readme.txt': Buffer.from('hi') })), null, 'zip without a skin');
console.log('skin finding tests passed');
