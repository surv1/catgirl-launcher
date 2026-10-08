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
// Settings the in-game mod reads from <game>/config/catgirl-client.json
function writeConfig(inst, settings = {}, discord = {}, launcherCommand = []) {
  const file = path.join(instances.gameDir(inst.id), 'config', 'catgirl-client.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const accent = /^#[0-9a-f]{6}$/i.test(settings.accentHex || '') ? settings.accentHex : '#ff7eb6';
  fs.writeFileSync(file, JSON.stringify({
    menuPosition: ['right', 'left', 'top', 'bottom', 'hidden'].includes(settings.menuPosition) ? settings.menuPosition : 'right',
    iconsOnly: !!settings.menuIconsOnly,
    accent,
    windowTitle: 'CatGirl Client',
    launcherCommand: Array.isArray(launcherCommand) ? launcherCommand.map(String) : [],
    splashes: settings.splashes !== false,
    nowPlaying: {
      enabled: settings.nowPlaying !== false,
      position: ['bottom-right', 'bottom-left', 'top-right', 'top-left', 'hotbar', 'video'].includes(settings.nowPlayingPos) ? settings.nowPlayingPos : 'bottom-right',
    },
    discord: {
      enabled: !!discord.enabled,
      clientId: String(discord.clientId || ''),
      showServer: discord.showServer !== false,
      downloadUrl: discord.downloadUrl || null,
    },
  }, null, 2));
}

// Returns true when the in-game mod is installed and will run (it then also handles Discord).
async function sync(inst, { enabled, github, settings, discord, launcherCommand }, log) {
  const dir = path.join(instances.gameDir(inst.id), 'mods');
  if (!enabled || inst.loader !== 'fabric') { removeMenuJars(dir); return false; }
  try { writeConfig(inst, settings, discord, launcherCommand); } catch (e) { log(`[Catgirl] Couldn't write menu settings: ${e.message}`); }
  const installed = () => fs.existsSync(dir) && fs.readdirSync(dir).some((f) => f.startsWith(PREFIX) && f.endsWith('.jar'));
  if (!github?.owner || !github?.repo) { log('[Catgirl] In-game menu: no GitHub repo set in config.json yet, skipping.'); return installed(); }
  try {
    const rel = await latestRelease(github);
    const want = `${PREFIX}mc${inst.mcVersion}.jar`;
    const asset = rel.assets?.find((a) => a.name === want);
    if (!asset) { log(`[Catgirl] In-game menu isn't built for Minecraft ${inst.mcVersion} yet, skipping.`); removeMenuJars(dir); return false; }
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
  return installed();
}

module.exports = { sync, writeConfig };
