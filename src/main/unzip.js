// Small built-in zip extractor (no external programs), used for Java runtimes on
// Windows and for native library jars. Supports stored and deflated entries.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const EOCD_SIG = 0x06054b50;
const CEN_SIG = 0x02014b50;
const LOC_SIG = 0x04034b50;

function findEocd(buf) {
  const min = Math.max(0, buf.length - 0xffff - 22);
  for (let i = buf.length - 22; i >= min; i--) {
    if (buf.readUInt32LE(i) === EOCD_SIG) return i;
  }
  throw new Error('Not a zip file (end of central directory not found)');
}

function entries(buf) {
  const eocd = findEocd(buf);
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  if (count === 0xffff || off === 0xffffffff) throw new Error('ZIP64 archives are not supported');
  const list = [];
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== CEN_SIG) throw new Error('Corrupt zip central directory');
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const madeBy = buf.readUInt16LE(off + 4) >> 8;
    const extAttr = buf.readUInt32LE(off + 38);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    // Unix-made zips keep file permissions in the high 16 bits.
    const mode = madeBy === 3 ? (extAttr >>> 16) & 0o777 : 0;
    list.push({ name, method, compSize, localOff, mode });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return list;
}

function readEntry(buf, e) {
  if (buf.readUInt32LE(e.localOff) !== LOC_SIG) throw new Error(`Corrupt zip entry: ${e.name}`);
  const nameLen = buf.readUInt16LE(e.localOff + 26);
  const extraLen = buf.readUInt16LE(e.localOff + 28);
  const start = e.localOff + 30 + nameLen + extraLen;
  const data = buf.subarray(start, start + e.compSize);
  if (e.method === 0) return data;
  if (e.method === 8) return zlib.inflateRawSync(data);
  throw new Error(`Unsupported zip compression method ${e.method} in ${e.name}`);
}

// Extract a zip into destDir. Entries that try to escape destDir are skipped.
// prefix: only extract entries under this folder, with the folder removed (e.g. "overrides/").
function extractZip(zipPath, destDir, { exclude = [], prefix = '' } = {}) {
  const buf = fs.readFileSync(zipPath);
  const root = path.resolve(destDir);
  fs.mkdirSync(root, { recursive: true });
  for (const e of entries(buf)) {
    let name = e.name.replace(/\\/g, '/');
    if (exclude.some((x) => name.startsWith(x))) continue;
    if (prefix) {
      if (!name.startsWith(prefix) || name === prefix) continue;
      name = name.slice(prefix.length);
    }
    const target = path.resolve(root, name);
    if (target !== root && !target.startsWith(root + path.sep)) continue;
    if (name.endsWith('/')) { fs.mkdirSync(target, { recursive: true }); continue; }
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, readEntry(buf, e));
    if (e.mode && process.platform !== 'win32') fs.chmodSync(target, e.mode);
  }
}

// Read one file from a zip as a Buffer (or null if missing).
function readFile(zipPath, name) {
  const buf = fs.readFileSync(zipPath);
  const e = entries(buf).find((x) => x.name === name);
  return e ? readEntry(buf, e) : null;
}

// All files in a zip held in memory: [{ name, read() }].
function filesInZip(buf) {
  return entries(buf).filter((e) => !e.name.endsWith('/')).map((e) => ({ name: e.name, read: () => readEntry(buf, e) }));
}

module.exports = { extractZip, readFile, filesInZip };
