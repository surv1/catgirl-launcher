// Shared Minecraft settings: options.txt (controls, FOV, sensitivity, video, sound, chat…)
// and servers.dat (multiplayer server list) are kept in sync across instances.
//
// How: before an instance starts, we first collect the newest settings from whichever
// instance was played last (this also works if the launcher was closed while playing),
// then copy them into the instance that is starting. When a game exits we collect again.
const fs = require('fs');
const path = require('path');
const paths = require('./paths');
const instances = require('./instances');

// Things that belong to one instance only.
const EXCLUDE = new Set([
  'version',                    // data version of that Minecraft release
  'resourcePacks', 'incompatibleResourcePacks', // packs differ per instance
  'lastServer', 'tutorialStep', 'joinedFirstServer', 'skipMultiplayerWarning',
]);

function dir() {
  const d = path.join(paths.dirs().base, 'shared');
  fs.mkdirSync(d, { recursive: true });
  return d;
}
const sharedOptions = () => path.join(dir(), 'options.txt');
const sharedServers = () => path.join(dir(), 'servers.dat');
const metaFile = () => path.join(dir(), 'sync.json');

function parse(text) {
  const map = new Map();
  for (const line of String(text || '').split(/\r?\n/)) {
    const i = line.indexOf(':');
    if (i <= 0) continue;
    map.set(line.slice(0, i), line.slice(i + 1));
  }
  return map;
}
function serialize(map) {
  return [...map].map(([k, v]) => `${k}:${v}`).join('\n') + '\n';
}
function read(file) { try { return fs.readFileSync(file, 'utf8'); } catch { return null; } }
function mtime(file) { try { return fs.statSync(file).mtimeMs; } catch { return 0; } }

const shares = (inst) => inst.shareSettings !== false;

// Pull the newest options.txt / servers.dat from any sharing instance into the shared copy.
function collect() {
  const meta = paths.readJson(metaFile(), { optionsFrom: 0, serversFrom: 0 });
  let bestOpt = null;
  let bestSrv = null;
  for (const inst of instances.list().filter(shares)) {
    const g = instances.gameDir(inst.id);
    const o = path.join(g, 'options.txt');
    const s = path.join(g, 'servers.dat');
    const om = mtime(o);
    const sm = mtime(s);
    if (om > meta.optionsFrom && (!bestOpt || om > bestOpt.m)) bestOpt = { file: o, m: om };
    if (sm > meta.serversFrom && (!bestSrv || sm > bestSrv.m)) bestSrv = { file: s, m: sm };
  }
  if (bestOpt) {
    const shared = parse(read(sharedOptions()));
    for (const [k, v] of parse(read(bestOpt.file))) shared.set(k, v); // keeps 'version' as a fallback for new instances
    fs.writeFileSync(sharedOptions(), serialize(shared));
    meta.optionsFrom = bestOpt.m;
  }
  if (bestSrv) {
    fs.copyFileSync(bestSrv.file, sharedServers());
    meta.serversFrom = bestSrv.m;
  }
  paths.writeJson(metaFile(), meta);
}

// Copy the shared settings into one instance (only the shared keys; its own extras stay).
function apply(inst, { servers = true } = {}) {
  if (!shares(inst)) return false;
  const g = instances.gameDir(inst.id);
  fs.mkdirSync(g, { recursive: true });
  const shared = read(sharedOptions());
  if (shared) {
    const target = path.join(g, 'options.txt');
    const existing = read(target);
    const mine = parse(existing);
    for (const [k, v] of parse(shared)) {
      if (EXCLUDE.has(k)) { if (k === 'version' && !mine.has('version')) mine.set(k, v); continue; }
      mine.set(k, v);
    }
    // Put "version" first like Minecraft does.
    const out = new Map();
    if (mine.has('version')) out.set('version', mine.get('version'));
    for (const [k, v] of mine) if (k !== 'version') out.set(k, v);
    fs.writeFileSync(target, serialize(out));
  }
  if (servers && fs.existsSync(sharedServers())) fs.copyFileSync(sharedServers(), path.join(g, 'servers.dat'));
  // Our own writes must not count as "newer settings" next time.
  const meta = paths.readJson(metaFile(), { optionsFrom: 0, serversFrom: 0 });
  meta.optionsFrom = Math.max(meta.optionsFrom, mtime(path.join(g, 'options.txt')));
  if (servers) meta.serversFrom = Math.max(meta.serversFrom, mtime(path.join(g, 'servers.dat')));
  paths.writeJson(metaFile(), meta);
  return true;
}

module.exports = { collect, apply, parse, serialize, EXCLUDE };
