const path = require('path');
const paths = require('./paths');
const { fetchJson, download } = require('./net');
const { mergeVersions } = require('./rules');

const MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const FABRIC_META = 'https://meta.fabricmc.net/v2';

let manifestCache = null;

async function getManifest() {
  if (manifestCache) return manifestCache;
  const file = path.join(paths.dirs().cache, 'version_manifest_v2.json');
  try {
    manifestCache = await fetchJson(MANIFEST_URL);
    paths.writeJson(file, manifestCache);
  } catch (e) {
    manifestCache = paths.readJson(file, null); // offline fallback
    if (!manifestCache) throw e;
  }
  return manifestCache;
}

async function listGameVersions(includeSnapshots = false) {
  const m = await getManifest();
  return {
    latest: m.latest,
    versions: m.versions
      .filter((v) => v.type === 'release' || (includeSnapshots && v.type === 'snapshot'))
      .map((v) => ({ id: v.id, type: v.type, releaseTime: v.releaseTime })),
  };
}

async function listFabricLoaders(gameVersion) {
  const list = await fetchJson(`${FABRIC_META}/versions/loader/${encodeURIComponent(gameVersion)}`);
  return list.map((x) => ({ version: x.loader.version, stable: x.loader.stable }));
}

async function latestFabricLoader(gameVersion) {
  const loaders = await listFabricLoaders(gameVersion);
  if (!loaders.length) throw new Error(`Fabric doesn't support Minecraft ${gameVersion} yet.`);
  return (loaders.find((l) => l.stable) || loaders[0]).version;
}

// Vanilla version JSON, downloaded once into versions/<id>/<id>.json
async function getVanillaJson(id) {
  const file = path.join(paths.dirs().versions, id, `${id}.json`);
  const cached = paths.readJson(file, null);
  if (cached) return cached;
  const m = await getManifest();
  const entry = m.versions.find((v) => v.id === id);
  if (!entry) throw new Error(`Unknown Minecraft version ${id}`);
  await download(entry.url, file, { sha1: entry.sha1 });
  return paths.readJson(file, null);
}

async function getFabricJson(gameVersion, loaderVersion) {
  const id = `fabric-loader-${loaderVersion}-${gameVersion}`;
  const file = path.join(paths.dirs().versions, id, `${id}.json`);
  let json = paths.readJson(file, null);
  if (!json) {
    json = await fetchJson(`${FABRIC_META}/versions/loader/${encodeURIComponent(gameVersion)}/${encodeURIComponent(loaderVersion)}/profile/json`);
    paths.writeJson(file, json);
  }
  return json;
}

// Full, merged version JSON for an instance.
async function resolveVersion(instance) {
  const vanilla = await getVanillaJson(instance.mcVersion);
  if (instance.loader === 'fabric') {
    if (!instance.loaderVersion) instance.loaderVersion = await latestFabricLoader(instance.mcVersion);
    const fabric = await getFabricJson(instance.mcVersion, instance.loaderVersion);
    const merged = mergeVersions(vanilla, fabric);
    merged.jarId = vanilla.id;
    return merged;
  }
  return { ...vanilla, jarId: vanilla.id };
}

function clearMemoryCache() { manifestCache = null; }

module.exports = { getManifest, listGameVersions, listFabricLoaders, latestFabricLoader, resolveVersion, clearMemoryCache };
