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
