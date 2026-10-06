// Pure helpers for reading Mojang/Fabric version files. No Electron, no network,
// so they can be unit tested with plain node (see test/rules.test.js).
const path = require('path');

function currentOs() {
  if (process.platform === 'win32') return 'windows';
  if (process.platform === 'darwin') return 'osx';
  return 'linux';
}

// Evaluate a Mojang "rules" array. features = { is_quick_play_multiplayer: true, ... }
function rulesAllow(rules, features = {}, os = currentOs(), arch = process.arch) {
  if (!rules || rules.length === 0) return true;
  let allowed = false;
  for (const rule of rules) {
    let matches = true;
    if (rule.os) {
      if (rule.os.name && rule.os.name !== os) matches = false;
      if (rule.os.arch) {
        const want = rule.os.arch;
        const is32 = arch === 'ia32';
        if (want === 'x86' && !is32) matches = false;
        if (want === 'arm64' && arch !== 'arm64') matches = false;
      }
    }
    if (rule.features) {
      for (const [k, v] of Object.entries(rule.features)) {
        if (!!features[k] !== v) matches = false;
      }
    }
    if (matches) allowed = rule.action === 'allow';
  }
  return allowed;
}

// "net.fabricmc:fabric-loader:0.16.9" -> "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar"
function mavenPath(name) {
  let [coords, ext = 'jar'] = name.split('@');
  const parts = coords.split(':');
  const [group, artifact, version, classifier] = parts;
  const file = `${artifact}-${version}${classifier ? '-' + classifier : ''}.${ext}`;
  return [...group.split('.'), artifact, version, file].join('/');
}

// "group:artifact[:version][:classifier]" -> key used to de-duplicate libraries
function libKey(name) {
  const parts = name.split('@')[0].split(':');
  return `${parts[0]}:${parts[1]}${parts[3] ? ':' + parts[3] : ''}`;
}

// Merge a child version (e.g. Fabric) on top of its parent (vanilla).
function mergeVersions(parent, child) {
  const merged = { ...parent, ...child };
  merged.id = child.id;
  merged.mainClass = child.mainClass || parent.mainClass;
  // Child libraries win when the same artifact appears in both (e.g. newer ASM from Fabric).
  const childKeys = new Set((child.libraries || []).map((l) => libKey(l.name)));
  merged.libraries = [...(child.libraries || []), ...(parent.libraries || []).filter((l) => !childKeys.has(libKey(l.name)))];
  if (parent.arguments || child.arguments) {
    merged.arguments = {
      game: [...(parent.arguments?.game || []), ...(child.arguments?.game || [])],
      jvm: [...(parent.arguments?.jvm || []), ...(child.arguments?.jvm || [])],
    };
  }
  if (child.minecraftArguments) merged.minecraftArguments = child.minecraftArguments;
  for (const k of ['downloads', 'assetIndex', 'assets', 'javaVersion', 'logging']) {
    if (!child[k] && parent[k]) merged[k] = parent[k];
  }
  delete merged.inheritsFrom;
  return merged;
}

// Turn one library entry into { artifact, natives } download jobs for this OS.
function resolveLibrary(lib, libDir, os = currentOs(), arch = process.arch) {
  if (!rulesAllow(lib.rules, {}, os, arch)) return null;
  const out = { name: lib.name, artifact: null, natives: null };
  const dl = lib.downloads;
  if (dl?.artifact) {
    const p = dl.artifact.path || mavenPath(lib.name);
    out.artifact = { url: dl.artifact.url, path: path.join(libDir, p), sha1: dl.artifact.sha1, size: dl.artifact.size };
    if (!dl.artifact.url) out.artifact = null;
  } else if (!dl) {
    // Fabric style: { name, url: "https://maven.fabricmc.net/" }
    const p = mavenPath(lib.name);
    const baseUrl = (lib.url || 'https://libraries.minecraft.net/').replace(/\/?$/, '/');
    out.artifact = { url: baseUrl + p, path: path.join(libDir, p), sha1: lib.sha1, size: lib.size };
  }
  // Old-style natives (pre 1.19): separate classifier jar that must be unzipped.
  if (lib.natives && lib.natives[os]) {
    const classifier = lib.natives[os].replace('${arch}', arch === 'ia32' ? '32' : '64');
    const c = dl?.classifiers?.[classifier];
    if (c) out.natives = { url: c.url, path: path.join(libDir, c.path), sha1: c.sha1, size: c.size, exclude: lib.extract?.exclude || [] };
  }
  return out;
}

function substitute(str, vars) {
  return str.replace(/\$\{([a-zA-Z_]+)\}/g, (m, k) => (vars[k] !== undefined ? String(vars[k]) : m));
}

// Expand a modern "arguments" list (strings + rule objects) into plain strings.
function expandArgs(list, vars, features) {
  const out = [];
  for (const entry of list || []) {
    if (typeof entry === 'string') { out.push(substitute(entry, vars)); continue; }
    if (!rulesAllow(entry.rules, features)) continue;
    const vals = Array.isArray(entry.value) ? entry.value : [entry.value];
    for (const v of vals) out.push(substitute(v, vars));
  }
  return out;
}

// Build full JVM + game argument lists for a resolved version.
function buildArgs(version, vars, features) {
  let jvm, game;
  if (version.arguments) {
    jvm = expandArgs(version.arguments.jvm, vars, features);
    game = expandArgs(version.arguments.game, vars, features);
  } else {
    // Legacy versions: no jvm list, game args are one string.
    jvm = [
      `-Djava.library.path=${vars.natives_directory}`,
      '-cp', vars.classpath,
    ];
    if (currentOs() === 'osx') jvm.unshift('-XstartOnFirstThread');
    game = (version.minecraftArguments || '').split(' ').filter(Boolean).map((a) => substitute(a, vars));
  }
  return { jvm, game };
}

// Compare release ids like "1.20.4" >= "1.20"
function versionAtLeast(id, min) {
  const a = String(id).split(/[.-]/).map((x) => parseInt(x, 10) || 0);
  const b = String(min).split('.').map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) > (b[i] || 0);
  }
  return true;
}

module.exports = { currentOs, rulesAllow, mavenPath, libKey, mergeVersions, resolveLibrary, substitute, expandArgs, buildArgs, versionAtLeast };
