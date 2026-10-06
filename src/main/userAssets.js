// The player's own background picture and font, kept in the launcher's data folder.
const fs = require('fs');
const path = require('path');
const paths = require('./paths');
const { UA } = require('./net');

const IMAGE_TYPES = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif' };
const FONT_TYPES = { ttf: 'font/ttf', otf: 'font/otf', woff: 'font/woff', woff2: 'font/woff2' };
const MAX_IMAGE = 15 * 1024 * 1024;
const MAX_FONT = 10 * 1024 * 1024;

function dir(name) {
  const d = path.join(paths.dirs().base, name);
  fs.mkdirSync(d, { recursive: true });
  return d;
}

function ext(file) { return path.extname(file).slice(1).toLowerCase(); }

function clear(d, prefix) {
  for (const f of fs.readdirSync(d)) if (f.startsWith(prefix)) fs.rmSync(path.join(d, f), { force: true });
}

function findOne(d, prefix) {
  return fs.readdirSync(d).find((f) => f.startsWith(prefix)) || null;
}

function toDataUrl(file, types) {
  const type = types[ext(file)];
  if (!type) return null;
  return `data:${type};base64,${fs.readFileSync(file).toString('base64')}`;
}

// Images are checked by their first bytes, not just the file name.
function sniffImage(buf) {
  if (buf.length < 12) return null;
  if (buf[0] === 0x89 && buf.toString('ascii', 1, 4) === 'PNG') return 'png';
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'jpg';
  if (buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'webp';
  if (buf.toString('ascii', 0, 3) === 'GIF') return 'gif';
  return null;
}

function saveBackground(buf) {
  const kind = sniffImage(buf);
  if (!kind) throw new Error("That file isn't a PNG, JPG, WEBP or GIF picture.");
  if (buf.length > MAX_IMAGE) throw new Error('That picture is too big (max 15 MB).');
  const d = dir('backgrounds');
  clear(d, 'custom.');
  const file = path.join(d, `custom.${kind}`);
  fs.writeFileSync(file, buf);
  return toDataUrl(file, IMAGE_TYPES);
}

function setBackgroundFromFile(file) {
  return saveBackground(fs.readFileSync(file));
}

async function setBackgroundFromUrl(url) {
  if (!/^https:\/\//i.test(url || '')) throw new Error('Paste a link that starts with https://');
  const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'image/*' } });
  if (!res.ok) throw new Error(`Couldn't download that picture (${res.status}). Try "Copy image address" on the picture itself.`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (!sniffImage(buf)) throw new Error("That link isn't a picture. Right-click the image and choose \"Copy image address\".");
  return saveBackground(buf);
}

function getBackground() {
  const d = dir('backgrounds');
  const f = findOne(d, 'custom.');
  return f ? toDataUrl(path.join(d, f), IMAGE_TYPES) : null;
}

function setFontFromFile(file) {
  const e = ext(file);
  if (!FONT_TYPES[e]) throw new Error('Pick a .ttf, .otf, .woff or .woff2 font file.');
  if (fs.statSync(file).size > MAX_FONT) throw new Error('That font file is too big.');
  const d = dir('fonts');
  clear(d, 'custom.');
  const dest = path.join(d, `custom.${e}`);
  fs.copyFileSync(file, dest);
  return { name: path.basename(file, path.extname(file)), dataUrl: toDataUrl(dest, FONT_TYPES) };
}

function getFont() {
  const d = dir('fonts');
  const f = findOne(d, 'custom.');
  return f ? { dataUrl: toDataUrl(path.join(d, f), FONT_TYPES) } : null;
}

module.exports = { setBackgroundFromFile, setBackgroundFromUrl, getBackground, setFontFromFile, getFont, sniffImage };
