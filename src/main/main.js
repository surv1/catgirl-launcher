const { app, BrowserWindow, ipcMain, shell, dialog, clipboard } = require('electron');
const path = require('path');
const fs = require('fs');

const paths = require('./paths');
const auth = require('./auth');
const versions = require('./versions');
const instances = require('./instances');
const mods = require('./mods');
const catmod = require('./catmod');
const launcher = require('./launch');

let autoUpdater = null;
try { ({ autoUpdater } = require('electron-updater')); } catch { /* not installed: updates disabled */ }

const config = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'config.json'), 'utf8'));
const DEFAULT_SETTINGS = {
  javaPath: '', javaArgs: '', afterLaunch: 'minimize', customResolution: false, width: 1280, height: 720,
  showSnapshots: false, theme: 'sakura', catgirlMenu: true,
};

let win = null;
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
  }));
  handle('app:openExternal', (url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
  handle('app:copy', (text) => clipboard.writeText(String(text)));
  handle('settings:get', () => settings());
  handle('settings:set', (s) => {
    const current = settings();
    delete current.msClientId;
    for (const k of Object.keys(DEFAULT_SETTINGS)) if (k in s) current[k] = s[k];
    paths.writeJson(paths.dirs().settings, current);
    return settings();
  });
  handle('settings:pickJava', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a java executable', properties: ['openFile'] });
    return r.canceled ? null : r.filePaths[0];
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
  handle('inst:remove', (id) => instances.remove(id));
  handle('inst:openFolder', (id) => shell.openPath(instances.gameDir(id)));

  handle('mods:search', (id, q, offset) => mods.search(id, q, offset));
  handle('mods:install', (id, projectId) => mods.install(id, projectId));
  handle('mods:list', (id) => mods.listInstalled(id));
  handle('mods:toggle', (id, file) => mods.toggle(id, file));
  handle('mods:remove', (id, file) => mods.remove(id, file));

  handle('launch:start', async (id) => {
    const account = await auth.getLaunchAccount();
    const inst = instances.get(id);
    const s = settings();
    await installStarterMods(inst);
    send('launch:progress', { instId: id, stage: 'Checking Catgirl menu', done: 0, total: 0 });
    await catmod.sync(inst, { enabled: s.catgirlMenu, github: config.github }, (line) => send('launch:log', { instId: id, line }));
    await launcher.launch(id, account, s, {
      progress: (p) => send('launch:progress', p),
      log: (l) => send('launch:log', l),
      started: (x) => {
        send('launch:started', x);
        if (s.afterLaunch === 'minimize') win?.minimize();
      },
      exit: (x) => {
        send('launch:exit', x);
        if (s.afterLaunch === 'minimize' && win?.isMinimized()) win.restore();
      },
    });
  });
  handle('launch:kill', (id) => launcher.kill(id));
}

app.whenReady().then(() => {
  paths.init(app.getPath('appData'));
  auth.setClientId(config.msClientId);
  ensureFeatured();
  registerIpc();
  createWindow();
  setupUpdates();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
