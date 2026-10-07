const { app, BrowserWindow, ipcMain, shell, dialog, clipboard } = require('electron');
const path = require('path');
const fs = require('fs');

const paths = require('./paths');
const auth = require('./auth');
const versions = require('./versions');
const instances = require('./instances');
const mods = require('./mods');
const catmod = require('./catmod');
const { ping } = require('./ping');
const assets = require('./userAssets');
const { DiscordPresence, buildActivity } = require('./discord');
const sharedOptions = require('./sharedOptions');
const skins = require('./skins');
const wardrobe = require('./wardrobe');
const cosmetics = require('./cosmetics');
const launcher = require('./launch');

let autoUpdater = null;
try { ({ autoUpdater } = require('electron-updater')); } catch { /* not installed: updates disabled */ }

const config = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'config.json'), 'utf8'));
const DEFAULT_SETTINGS = {
  javaPath: '', javaArgs: '', afterLaunch: 'minimize', customResolution: false, width: 1280, height: 720,
  showSnapshots: false, theme: 'sakura', catgirlMenu: true,
  customBg: '#1a1020', customAccent: '#ff7eb6',
  font: 'default', fontName: '',
  background: 'none', bgDim: 55, bgBlur: 0, navPosition: 'left', navIcons: false,
  menuPosition: 'right', menuIconsOnly: false, splashes: true, accentHex: '#ff7eb6',
  discordPresence: true, discordShowServer: true, shareOptions: true, shareServers: true, showCapePictures: true, autoFixMods: true,
};

let win = null;

// ---------- Discord Rich Presence ----------
const presence = new DiscordPresence(config.discordClientId);
const playing = { inst: null, server: null, startedAt: 0, gameHandlesDiscord: false };
// How the in-game mod starts this launcher (or pokes the running one) to open the Wardrobe.
function launcherCommand() {
  return app.isPackaged ? [process.execPath] : [process.execPath, app.getAppPath()];
}
function downloadUrl() {
  const gh = config.github;
  return gh?.owner && gh?.repo ? `https://github.com/${gh.owner}/${gh.repo}/releases/latest` : null;
}
function updatePresence() {
  const s = settings();
  // While a game with the Catgirl mod runs, the game itself shows the Discord status
  // (so it stays even if the launcher is closed). The launcher steps aside.
  if (!s.discordPresence || !presence.configured || playing.gameHandlesDiscord) { presence.stop(); return; }
  presence.start();
  presence.set(buildActivity({
    playing: !!playing.inst, inst: playing.inst, server: playing.server, startedAt: playing.startedAt,
    showServer: s.discordShowServer,
    downloadUrl: downloadUrl(),
  }));
}
let updateState = { state: 'idle' };

function settings() { return { ...DEFAULT_SETTINGS, ...paths.readJson(paths.dirs().settings, {}), msClientId: config.msClientId }; }
function send(channel, payload) { if (win && !win.isDestroyed()) win.webContents.send(channel, payload); }

// Optional: a server instance the launcher creates for you (set "featured" in config.json).
function ensureFeatured() {
  const f = config.featured;
  if (!f || !f.address) return;
  try { instances.get('featured'); } catch {
    instances.create({ id: 'featured', name: f.name, mcVersion: f.mcVersion, loader: f.loader || 'fabric', joinServer: f.address, icon: 'star', featured: true });
  }
}

async function installStarterMods(inst) {
  const f = config.featured;
  if (!inst.featured || !f || inst.starterModsInstalled || inst.loader === 'vanilla') return;
  for (const slug of f.mods || []) {
    send('launch:progress', { instId: inst.id, stage: `Installing ${slug}`, done: 0, total: 0 });
    try { await mods.install(inst.id, slug); } catch (e) { send('launch:log', { instId: inst.id, line: `[Catgirl] Skipped ${slug}: ${e.message}` }); }
  }
  instances.save({ ...instances.get(inst.id), starterModsInstalled: true });
}

// ---------- auto updates (only in the installed app, from GitHub Releases) ----------
function setUpdate(s) { updateState = s; send('update:status', s); }

function setupUpdates() {
  if (!autoUpdater || !app.isPackaged) return;
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('checking-for-update', () => setUpdate({ state: 'checking' }));
  autoUpdater.on('update-not-available', () => setUpdate({ state: 'latest' }));
  autoUpdater.on('update-available', (i) => setUpdate({ state: 'downloading', version: i.version, percent: 0 }));
  autoUpdater.on('download-progress', (p) => setUpdate({ ...updateState, state: 'downloading', percent: Math.round(p.percent) }));
  autoUpdater.on('update-downloaded', (i) => setUpdate({ state: 'ready', version: i.version }));
  autoUpdater.on('error', (e) => setUpdate({ state: 'error', message: e?.message || String(e) }));
  const check = () => autoUpdater.checkForUpdates().catch(() => {});
  setTimeout(check, 3000);
  setInterval(check, 4 * 60 * 60 * 1000);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1180,
    height: 740,
    minWidth: 940,
    minHeight: 600,
    frame: false,
    backgroundColor: '#170d1c',
    title: 'Catgirl Launcher',
    icon: path.join(__dirname, '..', 'renderer', 'assets', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

function handle(channel, fn) {
  ipcMain.handle(channel, async (_e, ...args) => {
    try { return { ok: true, data: await fn(...args) }; } catch (err) { return { ok: false, error: err.message || String(err) }; }
  });
}

function registerIpc() {
  ipcMain.on('win:minimize', () => win?.minimize());
  ipcMain.on('win:maximize', () => (win?.isMaximized() ? win.unmaximize() : win?.maximize()));
  ipcMain.on('win:close', () => win?.close());

  handle('app:info', () => ({
    version: app.getVersion(), config, dataDir: paths.dirs().base, running: launcher.runningIds(),
    canUpdate: !!autoUpdater && app.isPackaged, update: updateState,
    totalMemMB: Math.round(require('os').totalmem() / 1048576),
  }));
  handle('app:openExternal', (url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
  handle('app:copy', (text) => clipboard.writeText(String(text)));
  handle('settings:get', () => settings());
  handle('settings:set', (s) => {
    const current = settings();
    delete current.msClientId;
    for (const k of Object.keys(DEFAULT_SETTINGS)) if (k in s) current[k] = s[k];
    paths.writeJson(paths.dirs().settings, current);
    if ('discordPresence' in s || 'discordShowServer' in s) updatePresence();
    return settings();
  });
  handle('settings:pickJava', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a java executable', properties: ['openFile'] });
    return r.canceled ? null : r.filePaths[0];
  });

  handle('server:ping', (address) => ping(address));

  handle('bg:get', () => assets.getBackground());
  handle('bg:pickFile', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a background picture', properties: ['openFile'], filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif'] }] });
    return r.canceled ? null : assets.setBackgroundFromFile(r.filePaths[0]);
  });
  handle('bg:fromUrl', (url) => assets.setBackgroundFromUrl(url));
  handle('font:get', () => assets.getFont());
  handle('font:pickFile', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a font file', properties: ['openFile'], filters: [{ name: 'Fonts', extensions: ['ttf', 'otf', 'woff', 'woff2'] }] });
    return r.canceled ? null : assets.setFontFromFile(r.filePaths[0]);
  });

  handle('update:check', async () => {
    if (!autoUpdater || !app.isPackaged) throw new Error('Updates work in the installed app, not when running with npm start.');
    await autoUpdater.checkForUpdates();
  });
  handle('update:install', () => { if (updateState.state === 'ready') autoUpdater.quitAndInstall(); });

  handle('auth:list', () => auth.listAccounts());
  handle('auth:start', () => auth.startDeviceLogin());
  handle('auth:poll', ({ deviceCode, interval }) => auth.pollDeviceLogin(deviceCode, interval).then(({ uuid, name, skinUrl }) => ({ uuid, name, skinUrl })));
  handle('auth:cancel', () => auth.cancelLogin());
  handle('auth:select', (uuid) => auth.selectAccount(uuid));
  handle('auth:remove', (uuid) => auth.removeAccount(uuid));

  handle('versions:list', () => versions.listGameVersions(settings().showSnapshots));
  handle('versions:fabric', (mc) => versions.listFabricLoaders(mc));

  handle('inst:list', () => instances.list());
  handle('inst:create', (data) => instances.create(data));
  handle('inst:update', (id, data) => instances.update(id, data));
  handle('inst:remove', (id) => {
    if (launcher.runningIds().includes(id)) throw new Error('Close Minecraft for this instance before deleting it.');
    instances.remove(id);
    return true;
  });
  handle('inst:openFolder', (id) => shell.openPath(instances.gameDir(id)));

  handle('mods:search', (id, q, offset) => mods.search(id, q, offset));
  handle('mods:install', (id, projectId) => mods.install(id, projectId));
  handle('mods:list', (id) => mods.listInstalled(id));
  handle('mods:toggle', (id, file) => mods.toggle(id, file));
  handle('mods:remove', (id, file) => mods.remove(id, file));
  handle('mods:migrate', (id) => mods.migrate(id, (stage, done, total) => send('mods:progress', { instId: id, stage, done, total })));
  handle('packs:search', (q, offset) => mods.searchPacks(q, offset));
  handle('packs:install', (projectId) => mods.installPack(projectId, (stage, done, total) => send('pack:progress', { projectId, stage, done, total })));

  handle('launch:start', async (id) => {
    const account = await auth.getLaunchAccount();
    const inst = instances.get(id);
    const s = settings();
    await installStarterMods(inst);
    if (s.autoFixMods && inst.loader === 'fabric') {
      send('launch:progress', { instId: id, stage: 'Checking your mods', done: 0, total: 0 });
      try {
        const r = await mods.syncToVersion(id, (line) => send('launch:log', { instId: id, line }));
        if (r.updated.length || r.disabled.length) send('mods:fixed', { instId: id, ...r });
      } catch (e) { send('launch:log', { instId: id, line: `[Catgirl] Couldn't check mods: ${e.message}` }); }
    }
    send('launch:progress', { instId: id, stage: 'Checking Catgirl menu', done: 0, total: 0 });
    if (s.shareOptions) {
      try {
        sharedOptions.collect();
        if (sharedOptions.apply(inst, { servers: s.shareServers })) send('launch:log', { instId: id, line: '[Catgirl] Applied your shared Minecraft settings' });
      } catch (e) { send('launch:log', { instId: id, line: `[Catgirl] Couldn't share settings: ${e.message}` }); }
    }
    const modActive = await catmod.sync(inst, {
      enabled: s.catgirlMenu, github: config.github, settings: s,
      discord: { enabled: s.discordPresence && presence.configured, clientId: config.discordClientId, showServer: s.discordShowServer, downloadUrl: downloadUrl() },
      launcherCommand: launcherCommand(),
    }, (line) => send('launch:log', { instId: id, line }));
    if (inst.loader === 'fabric' && s.catgirlMenu) {
      try { cosmetics.writeForGame(instances.gameDir(id), config.cosmeticsApi, account, { showCapePictures: s.showCapePictures }); } catch (e) { send('launch:log', { instId: id, line: `[Catgirl] Couldn't write cosmetics: ${e.message}` }); }
      cosmetics.retryIfNeeded(config.cosmeticsApi, account);
    }
    await launcher.launch(id, account, s, {
      progress: (p) => send('launch:progress', p),
      log: (l) => send('launch:log', l),
      server: (x) => { send('launch:server', x); playing.server = x.server; updatePresence(); },
      started: (x) => {
        send('launch:started', x);
        Object.assign(playing, { inst: instances.get(id), server: inst.joinServer || null, startedAt: Date.now(), gameHandlesDiscord: modActive });
        updatePresence();
        if (s.afterLaunch === 'minimize') win?.minimize();
        // Minecraft runs on its own, so the launcher can close without stopping the game.
        if (s.afterLaunch === 'close') setTimeout(() => app.quit(), 1500);
      },
      exit: (x) => {
        send('launch:exit', x);
        if (settings().shareOptions) { try { sharedOptions.collect(); } catch {} }
        if (playing.inst?.id === id) { Object.assign(playing, { inst: null, server: null, startedAt: 0, gameHandlesDiscord: false }); updatePresence(); }
        if (s.afterLaunch === 'minimize' && win?.isMinimized()) win.restore();
      },
    });
  });
  handle('launch:kill', (id) => launcher.kill(id));

  // ---------- wardrobe ----------
  handle('wardrobe:open', () => openWardrobe({ fromGame: false }));
  handle('wardrobe:search', (site, q) => wardrobe.search(site, q));
  handle('wardrobe:nav', (cmd) => wardrobe.nav(cmd));
  handle('wardrobe:window', (cmd) => wardrobe.windowCmd(cmd));
  handle('wardrobe:sites', () => Object.fromEntries(Object.entries(wardrobe.SITES).map(([k, v]) => [k, v.name])));
  handle('skins:history', async () => {
    let acc = null;
    try { acc = await auth.getLaunchAccount(); } catch {}
    return { account: acc ? { uuid: acc.uuid, name: acc.name } : null, items: skins.history(acc?.uuid || '') };
  });
  handle('skins:remember', async () => { const acc = await auth.getLaunchAccount(); await skins.rememberCurrent(acc); return true; });
  handle('skins:wear', async ({ dataUrl, historyId, variant, name, source }) => {
    const acc = await auth.getLaunchAccount();
    const buf = historyId ? skins.historyBuffer(historyId) : skins.fromDataUrl(dataUrl);
    await skins.wear(acc, buf, { variant, name, source });
    return skins.history(acc.uuid);
  });
  handle('skins:remove', async (id) => { let acc = null; try { acc = await auth.getLaunchAccount(); } catch {} skins.removeFromHistory(id, acc?.uuid || ''); return true; });
  // ---------- cosmetics ----------
  handle('cosmetics:get', async () => {
    const acc = await auth.getLaunchAccount();
    let skin = null;
    try { const cur = await skins.currentSkin(acc.mcToken); if (cur) skin = { dataUrl: skins.toDataUrl(cur.buf), variant: cur.variant }; } catch {}
    const cur = cosmetics.get(acc.uuid);
    return { account: { uuid: acc.uuid, name: acc.name }, ...cur, capePicture: cosmetics.capePictureDataUrl(cur.items.cape.custom), skin, online: !!config.cosmeticsApi };
  });
  handle('cosmetics:save', async (items) => {
    const acc = await auth.getLaunchAccount();
    const r = await cosmetics.saveAndSync(config.cosmeticsApi, acc, items);
    // Show them straight away in any game that's already running.
    for (const id of launcher.runningIds()) {
      try { cosmetics.writeForGame(instances.gameDir(id), config.cosmeticsApi, acc, { showCapePictures: settings().showCapePictures }); } catch {}
    }
    return r;
  });
  handle('cosmetics:pickCapeFile', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a picture or GIF for your cape', properties: ['openFile'], filters: [{ name: 'Pictures and GIFs', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp'] }] });
    if (r.canceled) return null;
    const f = r.filePaths[0];
    if (fs.statSync(f).size > 25 * 1024 * 1024) throw new Error('That file is too big (25 MB max).');
    const ext = path.extname(f).slice(1).toLowerCase();
    const type = ext === 'jpg' ? 'image/jpeg' : `image/${ext}`;
    return { name: path.basename(f), type, base64: fs.readFileSync(f).toString('base64') };
  });
  handle('cosmetics:saveCapePicture', ({ png, frames, delay }) => cosmetics.saveCapePicture(png, frames, delay));
  handle('skins:fromUrl', (url) => skins.fromUrl(url));
  handle('skins:fromPlayer', (name) => skins.fromPlayer(name));
  handle('skins:pickFile', async () => {
    const parent = BrowserWindow.getFocusedWindow() || win;
    const r = await dialog.showOpenDialog(parent, { title: 'Choose a skin file', properties: ['openFile'], filters: [{ name: 'Skin (PNG)', extensions: ['png'] }] });
    return r.canceled ? null : skins.fromFile(r.filePaths[0]);
  });
}

function openWardrobe({ fromGame }) {
  wardrobe.open({ fromGame });
}

// Only one launcher at a time. Starting it again (e.g. from the in-game Wardrobe button)
// just pokes the running one.
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) app.quit();
app.on('second-instance', (_e, argv) => {
  if (argv.includes('--wardrobe')) { openWardrobe({ fromGame: true }); return; }
  if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
});

app.whenReady().then(() => {
  if (!gotLock) return;
  paths.init(app.getPath('appData'));
  wardrobe.init({ icon: path.join(__dirname, '..', 'renderer', 'assets', 'icon.png'), preload: path.join(__dirname, 'preload.js'), renderer: path.join(__dirname, '..', 'renderer') });
  auth.setClientId(config.msClientId);
  ensureFeatured();
  try { if (settings().shareOptions) sharedOptions.collect(); } catch {}
  registerIpc();
  createWindow();
  if (process.argv.includes('--wardrobe')) { win.minimize(); openWardrobe({ fromGame: true }); }
  setupUpdates();
  updatePresence();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('before-quit', () => presence.stop());
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
