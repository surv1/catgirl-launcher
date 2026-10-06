// The launcher's own GIF decoder (used for cape GIFs).
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { decodeGif } = require('../src/renderer/gif.js');

// 5 frames, 40×30, a red bar moving right on a transparent background, 50 ms each, disposal 2.
const g = decodeGif(fs.readFileSync(path.join(__dirname, 'fixtures', 'cape-anim.gif')));
assert.strictEqual(g.width, 40);
assert.strictEqual(g.height, 30);
assert.strictEqual(g.frames.length, 5);
assert.strictEqual(g.frames[0].ms, 50);
const px = (f, x, y) => Array.from(f.rgba.subarray((y * 40 + x) * 4, (y * 40 + x) * 4 + 4));
assert.deepStrictEqual(px(g.frames[0], 2, 10), [255, 0, 0, 255], 'bar drawn');
assert.strictEqual(px(g.frames[0], 20, 10)[3], 0, 'transparent elsewhere');
assert.strictEqual(px(g.frames[4], 2, 10)[3], 0, 'old bar cleared by disposal');
assert.deepStrictEqual(px(g.frames[4], 26, 10), [255, 0, 0, 255], 'bar moved');
assert.throws(() => decodeGif(Buffer.from('not a gif')), /isn't a GIF/);
console.log('gif tests passed');
