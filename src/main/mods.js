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

// ---------- before every launch: make every mod fit this Minecraft version ----------
// Works for ALL jars in the mods folder, including ones dropped in by hand:
//  1. Mods Modrinth knows (matched by file hash) that aren't made for this version are swapped
//     for the newest build that is (and any new required mods are installed).
//  2. Mods Modrinth has no build of, and unknown mods whose fabric.mod.json says they need a
//     different Minecraft version (or Forge mods), are switched off so the game doesn't crash.
const { sha1File } = require('./net');
const { readFile } = require('./unzip');

// Minecraft version maths for fabric.mod.json "depends.minecraft" (e.g. "1.21.x", ">=1.21 <1.22",
// "~1.20.4", ["1.21", "1.21.1"]). Returns true/false, or null when we can't tell.
function vparts(v) {
  const m = /^(\d+)\.(\d+)(?:\.(\d+))?/.exec(String(v).trim());
  return m ? [+m[1], +m[2], +(m[3] || 0)] : null;
}
function vcmp(a, b) { for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1; return 0; }
function matchTerm(term, mc) {
  const t = term.trim();
  if (!t || t === '*') return true;
  const v = vparts(mc);
  if (!v) return null;
  const wild = /^(\d+)\.(\d+|x|\*)(?:\.(\d+|x|\*))?$/.exec(t);
  if (wild && /[x*]/.test(t)) {
    if (+wild[1] !== v[0]) return false;
    if (wild[2] === 'x' || wild[2] === '*') return true;
    if (+wild[2] !== v[1]) return false;
    return wild[3] === undefined || wild[3] === 'x' || wild[3] === '*' || +wild[3] === v[2];
  }
  const m = /^(>=|<=|>|<|=|~|\^)?\s*(.+)$/.exec(t);
  const want = vparts(m[2]); // pre-release tags (-pre1, -alpha) are ignored: close enough
  if (!want) return null;
  const c = vcmp(v, want);
  switch (m[1]) {
    case '>=': return c >= 0;
    case '<=': return c <= 0;
    case '>': return c > 0;
    case '<': return c < 0;
    case '~': return c >= 0 && v[0] === want[0] && v[1] === want[1];
    case '^': return c >= 0 && v[0] === want[0];
    default: return c === 0;
  }
}
function matchesMc(spec, mc) {
  if (spec === undefined || spec === null) return true;
  if (Array.isArray(spec)) {
    let unknown = false;
    for (const s of spec) { const r = matchesMc(s, mc); if (r) return true; if (r === null) unknown = true; }
    return unknown ? null : false;
  }
  let result = true;
  for (const term of String(spec).split(/\s+/).filter(Boolean)) {
    const r = matchTerm(term, mc);
    if (r === false) return false;
    if (r === null) result = null;
  }
  return result;
}

// What a jar says about itself: { fabric: true, minecraft: <spec>, name } or { forge: true } or null.
function jarInfo(file) {
  try {
    const fmj = readFile(file, 'fabric.mod.json');
    if (fmj) {
      const j = JSON.parse(fmj.toString('utf8').replace(/^﻿/, '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, ' '));
      return { fabric: true, minecraft: j.depends?.minecraft, name: j.name || j.id };
    }
    if (readFile(file, 'quilt.mod.json')) return { fabric: true };
    if (readFile(file, 'META-INF/mods.toml') || readFile(file, 'META-INF/neoforge.mods.toml') || readFile(file, 'mcmod.info')) return { forge: true };
  } catch { /* unreadable jar: leave it alone */ }
  return null;
}

async function syncToVersion(instId, log = () => {}) {
  const inst = instances.get(instId);
  const result = { updated: [], disabled: [], ok: 0 };
  if (inst.loader !== 'fabric') return result;
  const dir = modsDir(instId);
  const jars = fs.readdirSync(dir).filter((f) => f.endsWith('.jar') && !f.startsWith('catgirl-client-'));
  if (!jars.length) return result;

  const byHash = {};
  for (const f of jars) { try { byHash[await sha1File(path.join(dir, f))] = f; } catch {} }
  const hashes = Object.keys(byHash);
  let known = {}, latest = {};
  try {
    known = await fetchJson(`${API}/version_files`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hashes, algorithm: 'sha1' }) });
  } catch (e) { log(`[Catgirl] Couldn't check mods for updates (${e.message}). Starting anyway.`); }
  const fits = (v) => v && (v.game_versions || []).includes(inst.mcVersion) && (v.loaders || []).some((l) => l === 'fabric' || l === 'quilt');
  const outdated = hashes.filter((h) => known[h] && !fits(known[h]));
  if (outdated.length) {
    try {
      latest = await fetchJson(`${API}/version_files/update`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hashes: outdated, algorithm: 'sha1', loaders: ['fabric'], game_versions: [inst.mcVersion] }),
      });
    } catch (e) { log(`[Catgirl] Couldn't look up mod updates (${e.message}).`); }
  }

  const idx = readIndex(instId);
  const present = new Set(Object.values(known).map((v) => v.project_id));
  const disable = (file, why) => {
    try { fs.renameSync(path.join(dir, file), path.join(dir, file + '.disabled')); } catch { return; }
    result.disabled.push(file);
    log(`[Catgirl] Turned off ${file}: ${why}`);
  };

  for (const h of hashes) {
    const file = byHash[h];
    const v = known[h];
    if (v) {
      if (fits(v)) { result.ok++; continue; }
      const nv = latest[h];
      if (!fits(nv)) { disable(file, `there's no version of it for Minecraft ${inst.mcVersion} yet.`); continue; }
      const nf = nv.files.find((x) => x.primary) || nv.files[0];
      try {
        await download(nf.url, path.join(dir, nf.filename), { sha1: nf.hashes?.sha1, size: nf.size });
        if (nf.filename !== file) fs.rmSync(path.join(dir, file), { force: true });
        let title = idx[nv.project_id]?.title || nf.filename.replace(/\.jar$/, '');
        idx[nv.project_id] = { ...(idx[nv.project_id] || {}), file: nf.filename, title, version: nv.version_number };
        result.updated.push(title);
        log(`[Catgirl] Updated ${file} → ${nf.filename} for Minecraft ${inst.mcVersion}`);
        for (const dep of nv.dependencies || []) {
          if (dep.dependency_type !== 'required' || !dep.project_id || present.has(dep.project_id) || idx[dep.project_id]) continue;
          present.add(dep.project_id);
          try { writeIndex(instId, idx); const added = await install(instId, dep.project_id); Object.assign(idx, readIndex(instId)); if (added.length) log(`[Catgirl] Added ${added.join(', ')} (needed by ${title})`); } catch {}
        }
      } catch (e) {
        disable(file, `couldn't download the ${inst.mcVersion} version (${e.message}).`);
      }
      continue;
    }
    // Not on Modrinth: trust what the mod itself says.
    const info = jarInfo(path.join(dir, file));
    if (info?.forge) { disable(file, "it's a Forge mod, and this instance uses Fabric."); continue; }
    if (info?.fabric && matchesMc(info.minecraft, inst.mcVersion) === false) {
      disable(file, `it's made for Minecraft ${Array.isArray(info.minecraft) ? info.minecraft.join(' / ') : info.minecraft}, not ${inst.mcVersion}.`);
      continue;
    }
    result.ok++;
  }
  writeIndex(instId, idx);
  return result;
}

module.exports.syncToVersion = syncToVersion;
module.exports.matchesMc = matchesMc;
