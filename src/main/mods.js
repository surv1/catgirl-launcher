// Mod browser + installer backed by Modrinth (https://docs.modrinth.com).
const fs = require('fs');
const path = require('path');
const { fetchJson, download } = require('./net');
const instances = require('./instances');

const API = 'https://api.modrinth.com/v2';

function modsDir(instId) {
  const d = path.join(instances.gameDir(instId), 'mods');
  fs.mkdirSync(d, { recursive: true });
  return d;
}

// Track which Modrinth project each jar came from, so we can show "installed".
function indexFile(instId) { return path.join(instances.instDir(instId), 'mods.json'); }
function readIndex(instId) { try { return JSON.parse(fs.readFileSync(indexFile(instId), 'utf8')); } catch { return {}; } }
function writeIndex(instId, idx) { fs.writeFileSync(indexFile(instId), JSON.stringify(idx, null, 2)); }

async function search(instId, query, offset = 0) {
  const inst = instances.get(instId);
  const facets = [['project_type:mod'], [`versions:${inst.mcVersion}`], [`categories:${inst.loader}`]];
  const url = `${API}/search?limit=20&offset=${offset}&index=${query ? 'relevance' : 'downloads'}&query=${encodeURIComponent(query || '')}&facets=${encodeURIComponent(JSON.stringify(facets))}`;
  const r = await fetchJson(url);
  const idx = readIndex(instId);
  return {
    total: r.total_hits,
    hits: r.hits.map((h) => ({
      id: h.project_id, slug: h.slug, title: h.title, author: h.author, description: h.description,
      icon: h.icon_url, downloads: h.downloads, installed: !!idx[h.project_id],
    })),
  };
}

async function bestVersion(projectId, inst) {
  const q = `loaders=${encodeURIComponent(JSON.stringify([inst.loader]))}&game_versions=${encodeURIComponent(JSON.stringify([inst.mcVersion]))}`;
  const versions = await fetchJson(`${API}/project/${encodeURIComponent(projectId)}/version?${q}`);
  return versions.find((v) => v.version_type === 'release') || versions[0] || null;
}

// Install a mod and its required dependencies.
async function install(instId, projectId, seen = new Set()) {
  if (seen.has(projectId)) return [];
  seen.add(projectId);
  const inst = instances.get(instId);
  if (inst.loader === 'vanilla') throw new Error('Mods need Fabric. Switch this instance to Fabric in its settings.');
  const idx = readIndex(instId);
  if (idx[projectId]) return [];

  const v = await bestVersion(projectId, inst);
  if (!v) throw new Error(`No version of this mod for ${inst.loader} ${inst.mcVersion}.`);
  const file = v.files.find((f) => f.primary) || v.files[0];
  await download(file.url, path.join(modsDir(instId), file.filename), { sha1: file.hashes?.sha1, size: file.size });

  let title = projectId;
  try { title = (await fetchJson(`${API}/project/${encodeURIComponent(projectId)}`)).title; } catch {}
  const fresh = readIndex(instId);
  fresh[v.project_id] = { file: file.filename, title, version: v.version_number };
  writeIndex(instId, fresh);

  const installed = [title];
  for (const dep of v.dependencies || []) {
    if (dep.dependency_type === 'required' && dep.project_id) {
      try { installed.push(...(await install(instId, dep.project_id, seen))); } catch {}
    }
  }
  return installed;
}

// List jars in the mods folder (including ones the player dropped in by hand).
function listInstalled(instId) {
  const dir = modsDir(instId);
  const idx = readIndex(instId);
  const byFile = Object.fromEntries(Object.entries(idx).map(([pid, m]) => [m.file, { pid, ...m }]));
  return fs.readdirSync(dir)
    .filter((f) => f.endsWith('.jar') || f.endsWith('.jar.disabled'))
    .map((f) => {
      const enabled = f.endsWith('.jar');
      const base = enabled ? f : f.slice(0, -'.disabled'.length);
      const meta = byFile[base];
      return { file: f, enabled, title: meta?.title || base.replace(/\.jar$/, ''), version: meta?.version || '', projectId: meta?.pid || null };
    })
    .sort((a, b) => a.title.localeCompare(b.title));
}

function toggle(instId, file) {
  const dir = modsDir(instId);
  const src = path.join(dir, path.basename(file));
  const dst = file.endsWith('.disabled') ? src.slice(0, -'.disabled'.length) : src + '.disabled';
  fs.renameSync(src, dst);
}

function remove(instId, file) {
  const dir = modsDir(instId);
  fs.rmSync(path.join(dir, path.basename(file)), { force: true });
  const base = file.replace(/\.disabled$/, '');
  const idx = readIndex(instId);
  for (const [pid, m] of Object.entries(idx)) if (m.file === base) delete idx[pid];
  writeIndex(instId, idx);
}

module.exports = { search, install, listInstalled, toggle, remove };

// ---------- keep mods matching the instance's Minecraft version ----------
// Called after an instance's version or loader changes: every mod installed from Modrinth
// is swapped for the build made for the new version. Mods with no build are disabled.
async function migrate(instId, onProgress = () => {}) {
  const inst = instances.get(instId);
  const result = { updated: [], disabled: [], unchanged: [] };
  if (inst.loader === 'vanilla') return result;
  const dir = modsDir(instId);
  const idx = readIndex(instId);
  const ids = Object.keys(idx);
  let n = 0;
  for (const pid of ids) {
    const m = idx[pid];
    onProgress(`Updating ${m.title}`, n++, ids.length);
    let v = null;
    try { v = await bestVersion(pid, inst); } catch {}
    const oldPath = path.join(dir, m.file);
    if (!v) {
      if (fs.existsSync(oldPath)) fs.renameSync(oldPath, oldPath + '.disabled');
      result.disabled.push(m.title);
      continue;
    }
    const file = v.files.find((f) => f.primary) || v.files[0];
    if (file.filename === m.file && fs.existsSync(oldPath)) { result.unchanged.push(m.title); continue; }
    await download(file.url, path.join(dir, file.filename), { sha1: file.hashes?.sha1, size: file.size });
    if (file.filename !== m.file) {
      fs.rmSync(oldPath, { force: true });
      fs.rmSync(oldPath + '.disabled', { force: true });
    }
    idx[pid] = { ...m, file: file.filename, version: v.version_number };
    result.updated.push(m.title);
  }
  writeIndex(instId, idx);
  return result;
}

// ---------- modpacks (.mrpack from Modrinth) ----------
async function searchPacks(query, offset = 0) {
  const facets = [['project_type:modpack'], ['categories:fabric']];
  const url = `${API}/search?limit=20&offset=${offset}&index=${query ? 'relevance' : 'downloads'}&query=${encodeURIComponent(query || '')}&facets=${encodeURIComponent(JSON.stringify(facets))}`;
  const r = await fetchJson(url);
  return {
    total: r.total_hits,
    hits: r.hits.map((h) => ({
      id: h.project_id, title: h.title, author: h.author, description: h.description,
      icon: h.icon_url, downloads: h.downloads, versions: h.versions || [],
    })),
  };
}

async function installPack(projectId, onProgress = () => {}) {
  const { extractZip, readFile } = require('./unzip');
  const paths = require('./paths');
  onProgress('Finding modpack version', 0, 0);
  const project = await fetchJson(`${API}/project/${encodeURIComponent(projectId)}`);
  const versions = await fetchJson(`${API}/project/${encodeURIComponent(projectId)}/version?loaders=${encodeURIComponent('["fabric"]')}`);
  const v = versions.find((x) => x.version_type === 'release') || versions[0];
  if (!v) throw new Error('This modpack has no Fabric version.');
  const packFile = v.files.find((f) => f.primary) || v.files[0];
  const packPath = path.join(paths.dirs().cache, `pack-${v.id}.mrpack`);
  onProgress('Downloading modpack', 0, 0);
  await download(packFile.url, packPath, { sha1: packFile.hashes?.sha1 });

  const raw = readFile(packPath, 'modrinth.index.json');
  if (!raw) throw new Error('Not a valid Modrinth modpack.');
  const index = JSON.parse(raw.toString('utf8'));
  const deps = index.dependencies || {};
  if (!deps.minecraft) throw new Error('Modpack does not say which Minecraft version it needs.');
  if (deps.forge || deps.neoforge || deps.quilt_loader) throw new Error('Only Fabric modpacks are supported.');

  const inst = instances.create({
    name: project.title.slice(0, 40),
    mcVersion: deps.minecraft,
    loader: deps['fabric-loader'] ? 'fabric' : 'vanilla',
    loaderVersion: deps['fabric-loader'] || null,
    icon: 'yarn',
    memoryMB: 6144,
  });
  instances.save({ ...instances.get(inst.id), iconUrl: project.icon_url || null, modpack: { id: projectId, version: v.version_number } });

  const game = path.resolve(instances.gameDir(inst.id));
  const jobs = [];
  for (const f of index.files || []) {
    if (f.env?.client === 'unsupported') continue;
    const dest = path.resolve(game, f.path);
    if (!dest.startsWith(game + path.sep)) continue; // ignore unsafe paths
    const url = (f.downloads || [])[0];
    if (!url) continue;
    jobs.push({ url, path: dest, sha1: f.hashes?.sha1, size: f.fileSize });
  }
  const { downloadAll } = require('./net');
  await downloadAll(jobs, (d, t) => onProgress('Downloading mods', d, t), 8);

  onProgress('Copying configs', 0, 0);
  extractZip(packPath, game, { prefix: 'overrides/' });
  extractZip(packPath, game, { prefix: 'client-overrides/' });
  fs.rmSync(packPath, { force: true });
  return instances.get(inst.id);
}

module.exports.migrate = migrate;
module.exports.searchPacks = searchPacks;
module.exports.installPack = installPack;
