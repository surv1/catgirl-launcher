// Instances: each is a separate Minecraft setup (version, loader, mods, worlds),
// stored in instances/<id>/instance.json with the game folder in instances/<id>/minecraft.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const paths = require('./paths');

function instDir(id) { return path.join(paths.dirs().instances, id); }
function gameDir(id) { return path.join(instDir(id), 'minecraft'); }

function slug(name) {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 32) || 'instance';
}

function list() {
  const root = paths.dirs().instances;
  return fs.readdirSync(root)
    .map((id) => paths.readJson(path.join(root, id, 'instance.json'), null))
    .filter(Boolean)
    .sort((a, b) => (b.lastPlayed || 0) - (a.lastPlayed || 0) || a.name.localeCompare(b.name));
}

function get(id) {
  const inst = paths.readJson(path.join(instDir(id), 'instance.json'), null);
  if (!inst) throw new Error('Instance not found');
  return inst;
}

function save(inst) {
  fs.mkdirSync(gameDir(inst.id), { recursive: true });
  paths.writeJson(path.join(instDir(inst.id), 'instance.json'), inst);
  return inst;
}

function create({ name, mcVersion, loader = 'vanilla', loaderVersion = null, memoryMB = 4096, joinServer = '', icon = 'cat', featured = false, id = null }) {
  if (!name || !mcVersion) throw new Error('Name and Minecraft version are required');
  let newId = id || slug(name);
  if (!id) while (fs.existsSync(instDir(newId))) newId = `${slug(name)}-${crypto.randomBytes(2).toString('hex')}`;
  return save({ id: newId, name, mcVersion, loader, loaderVersion, memoryMB, joinServer, icon, featured, created: Date.now(), lastPlayed: 0, playTimeMs: 0, javaArgs: '' });
}

function update(id, changes) {
  const inst = get(id);
  const allowed = ['name', 'mcVersion', 'loader', 'loaderVersion', 'memoryMB', 'joinServer', 'icon', 'javaArgs'];
  for (const k of allowed) if (k in changes) inst[k] = changes[k];
  // Changing game version or loader means the old loader version no longer applies.
  if ('mcVersion' in changes || 'loader' in changes) {
    if (!('loaderVersion' in changes)) inst.loaderVersion = null;
  }
  return save(inst);
}

function remove(id) {
  get(id);
  fs.rmSync(instDir(id), { recursive: true, force: true });
}

function recordPlay(id, ms) {
  const inst = get(id);
  inst.lastPlayed = Date.now();
  inst.playTimeMs = (inst.playTimeMs || 0) + ms;
  save(inst);
}

module.exports = { list, get, create, update, remove, save, gameDir, instDir, recordPlay };
