// Installs the Catgirl in-game menu mod (catgirl-client) into Fabric instances.
// The jar for each Minecraft version is published on the launcher's GitHub releases
// as catgirl-client-mc<version>.jar by the release workflow.
const fs = require('fs');
const path = require('path');
const { fetchJson, download } = require('./net');
const instances = require('./instances');
const mods = require('./mods');

const PREFIX = 'catgirl-client-';
let releaseCache = { at: 0, data: null };

async function latestRelease(github) {
  if (Date.now() - releaseCache.at < 10 * 60 * 1000 && releaseCache.data) return releaseCache.data;
  const data = await fetchJson(`https://api.github.com/repos/${encodeURIComponent(github.owner)}/${encodeURIComponent(github.repo)}/releases/latest`, {
    headers: { Accept: 'application/vnd.github+json' },
  });
  releaseCache = { at: Date.now(), data };
  return data;
}

function removeMenuJars(dir, keep) {
  if (!fs.existsSync(dir)) return;
  for (const f of fs.readdirSync(dir)) {
    if (f.startsWith(PREFIX) && f !== keep) fs.rmSync(path.join(dir, f), { force: true });
  }
}

// enabled=false removes the mod; otherwise installs/updates it. Never throws: the game
// should still launch even if GitHub can't be reached.
async function sync(inst, { enabled, github }, log) {
  const dir = path.join(instances.gameDir(inst.id), 'mods');
  if (!enabled || inst.loader !== 'fabric') { removeMenuJars(dir); return; }
  if (!github?.owner || !github?.repo) { log('[Catgirl] In-game menu: no GitHub repo set in config.json yet, skipping.'); return; }
  try {
    const rel = await latestRelease(github);
    const want = `${PREFIX}mc${inst.mcVersion}.jar`;
    const asset = rel.assets?.find((a) => a.name === want);
    if (!asset) { log(`[Catgirl] In-game menu isn't built for Minecraft ${inst.mcVersion} yet, skipping.`); return; }
    const fileName = `${PREFIX}${rel.tag_name}-mc${inst.mcVersion}.jar`;
    fs.mkdirSync(dir, { recursive: true });
    removeMenuJars(dir, fileName);
    if (!fs.existsSync(path.join(dir, fileName))) {
      log(`[Catgirl] Installing in-game menu ${rel.tag_name}`);
      await download(asset.browser_download_url, path.join(dir, fileName), { size: asset.size });
    }
    // The menu needs Fabric API.
    const hasFapi = fs.readdirSync(dir).some((f) => /^fabric-api.*\.jar$/i.test(f));
    if (!hasFapi) await mods.install(inst.id, 'fabric-api');
  } catch (e) {
    log(`[Catgirl] Couldn't update the in-game menu: ${e.message}`);
  }
}

module.exports = { sync };
