// Prepares files and starts Minecraft for an instance.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const paths = require('./paths');
const versions = require('./versions');
const instances = require('./instances');
const { ensureJava } = require('./java');
const { download, downloadAll } = require('./net');
const { resolveLibrary, buildArgs } = require('./rules');
const { extractZip } = require('./unzip');

const running = new Map(); // instId -> child process

async function prepareAssets(version, game, report) {
  const assetsDir = paths.dirs().assets;
  const idx = version.assetIndex;
  const idxFile = path.join(assetsDir, 'indexes', `${idx.id}.json`);
  await download(idx.url, idxFile, { sha1: idx.sha1 });
  const index = JSON.parse(fs.readFileSync(idxFile, 'utf8'));

  const jobs = Object.values(index.objects).map(({ hash, size }) => ({
    url: `https://resources.download.minecraft.net/${hash.slice(0, 2)}/${hash}`,
    path: path.join(assetsDir, 'objects', hash.slice(0, 2), hash),
    size,
  }));
  await downloadAll(jobs, (d, t) => report('Downloading assets', d, t));

  // Very old versions read assets from a flat folder instead of hashed objects.
  let legacyDir = null;
  if (index.map_to_resources || index.virtual) {
    legacyDir = index.map_to_resources ? path.join(game, 'resources') : path.join(assetsDir, 'virtual', idx.id);
    for (const [name, { hash }] of Object.entries(index.objects)) {
      const dst = path.join(legacyDir, name);
      if (fs.existsSync(dst)) continue;
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(path.join(assetsDir, 'objects', hash.slice(0, 2), hash), dst);
    }
  }
  return { assetsDir, legacyDir };
}

async function launch(instId, account, settings, events) {
  if (running.has(instId)) throw new Error('This instance is already running.');
  const inst = instances.get(instId);
  const game = instances.gameDir(instId);
  fs.mkdirSync(game, { recursive: true });
  const report = (stage, done = 0, total = 0) => events.progress({ instId, stage, done, total });

  report('Reading version info');
  const version = await versions.resolveVersion(inst);
  instances.save(inst); // keeps the chosen Fabric loader version

  // Java
  const major = version.javaVersion?.majorVersion || 8;
  const javaPath = settings.javaPath?.trim() || (await ensureJava(major, (s) => report(s)));

  // Client jar
  report('Downloading Minecraft');
  const jarId = version.jarId;
  const clientJar = path.join(paths.dirs().versions, jarId, `${jarId}.jar`);
  const client = version.downloads.client;
  await download(client.url, clientJar, { sha1: client.sha1, size: client.size });

  // Libraries
  const libDir = paths.dirs().libraries;
  const resolved = version.libraries.map((l) => resolveLibrary(l, libDir)).filter(Boolean);
  const libJobs = [];
  for (const r of resolved) {
    if (r.artifact) libJobs.push(r.artifact);
    if (r.natives) libJobs.push(r.natives);
  }
  const unique = [...new Map(libJobs.map((j) => [j.path, j])).values()];
  await downloadAll(unique, (d, t) => report('Downloading libraries', d, t));

  // Natives: old versions ship them in classifier jars; 1.19+ ships "natives-<os>" jars on the
  // classpath. Unzip both into one folder like the official launcher does.
  const nativesDir = path.join(instances.instDir(instId), 'natives');
  fs.rmSync(nativesDir, { recursive: true, force: true });
  fs.mkdirSync(nativesDir, { recursive: true });
  for (const r of resolved) {
    const jar = r.natives?.path || (r.artifact && /:natives-/.test(r.name) ? r.artifact.path : null);
    if (!jar) continue;
    report('Unpacking natives');
    await extractZip(jar, nativesDir);
  }
  fs.rmSync(path.join(nativesDir, 'META-INF'), { recursive: true, force: true });

  // Assets
  const { assetsDir, legacyDir } = await prepareAssets(version, game, report);

  // Arguments
  const cpEntries = [...new Set(resolved.filter((r) => r.artifact).map((r) => r.artifact.path))];
  cpEntries.push(clientJar);
  const classpath = cpEntries.join(path.delimiter);

  const server = (inst.joinServer || '').trim();
  const supportsQuickPlay = JSON.stringify(version.arguments?.game || []).includes('quickPlayMultiplayer');
  const vars = {
    auth_player_name: account.name,
    version_name: version.id,
    game_directory: game,
    assets_root: assetsDir,
    game_assets: legacyDir || assetsDir,
    assets_index_name: version.assetIndex.id,
    auth_uuid: account.uuid,
    auth_access_token: account.mcToken,
    auth_session: `token:${account.mcToken}:${account.uuid}`,
    clientid: settings.msClientId || '',
    auth_xuid: account.xuid || '0',
    user_type: 'msa',
    user_properties: '{}',
    version_type: version.type || 'release',
    natives_directory: nativesDir,
    launcher_name: 'catgirl-launcher',
    launcher_version: '0.1.0',
    classpath,
    classpath_separator: path.delimiter,
    library_directory: libDir,
    resolution_width: settings.width || 1280,
    resolution_height: settings.height || 720,
    quickPlayMultiplayer: server,
  };
  const features = {
    is_demo_user: false,
    has_custom_resolution: !!settings.customResolution,
    is_quick_play_multiplayer: !!server && supportsQuickPlay,
  };
  const { jvm, game: gameArgs } = buildArgs(version, vars, features);

  if (server && !supportsQuickPlay) {
    const [host, port = '25565'] = server.split(':');
    gameArgs.push('--server', host, '--port', port);
  }

  const memory = Math.max(1024, inst.memoryMB || 4096);
  const extra = (inst.javaArgs || settings.javaArgs || '').split(' ').filter(Boolean);
  const args = [`-Xmx${memory}M`, `-Xms${Math.min(memory, 1024)}M`, ...extra, ...jvm, version.mainClass, ...gameArgs];

  report('Starting Minecraft');
  const safeLog = args.map((a) => (a === account.mcToken ? '********' : a));
  events.log({ instId, line: `[Catgirl] ${javaPath} ${safeLog.join(' ')}` });

  const child = spawn(javaPath, args, { cwd: game, windowsHide: false });
  running.set(instId, child);
  const started = Date.now();

  const pipe = (stream) => {
    let buf = '';
    stream.on('data', (d) => {
      buf += d.toString();
      const lines = buf.split(/\r?\n/);
      buf = lines.pop();
      for (const line of lines) events.log({ instId, line: line.split(account.mcToken).join('********') });
    });
  };
  pipe(child.stdout);
  pipe(child.stderr);

  child.on('error', (e) => {
    running.delete(instId);
    events.exit({ instId, code: -1, error: e.message });
  });
  child.on('close', (code) => {
    running.delete(instId);
    try { instances.recordPlay(instId, Date.now() - started); } catch {}
    events.exit({ instId, code });
  });

  events.started({ instId });
}

function kill(instId) {
  const c = running.get(instId);
  if (c) c.kill();
}

function runningIds() { return [...running.keys()]; }

module.exports = { launch, kill, runningIds };
