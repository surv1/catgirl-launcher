// The Wardrobe: a small Catgirl window with a skin site on the right and our panel on the left.
// When you click Download on any skin (or open a skin picture, or right-click it), the window
// catches it and offers to put it on. Nothing is copied to our own site: players browse the
// real skin sites themselves, inside this window.
const { BrowserWindow, WebContentsView, Menu, session, shell } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const skins = require('./skins');

const PANEL = 360;
const enc = encodeURIComponent;
const SITES = {
  skindex: { name: 'The Skindex', home: 'https://www.minecraftskins.com/', search: (q) => `https://www.minecraftskins.com/search/skin/${enc(q)}/1/` },
  namemc: { name: 'NameMC', home: 'https://namemc.com/minecraft-skins', search: (q) => `https://namemc.com/minecraft-skins/tag/${enc(q.trim().toLowerCase().replace(/\s+/g, '-'))}` },
  planet: { name: 'Planet Minecraft', home: 'https://www.planetminecraft.com/skins/', search: (q) => `https://www.planetminecraft.com/skins/?keywords=${enc(q)}` },
};

let win = null;
let view = null;
let iconPath = null;
let preloadPath = null;
let rendererDir = null;
const sessionsHooked = new WeakSet();

const isPng = (url) => /^https:\/\/[^?#]+\.png([?#].*)?$/i.test(url || '');
const host = (url) => { try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return ''; } };
const send = (ch, payload) => { if (win && !win.isDestroyed()) win.webContents.send(ch, payload); };

function nameFrom(fileOrUrl) {
  const base = decodeURIComponent(String(fileOrUrl).split(/[?#]/)[0].split('/').pop() || '');
  const clean = base.replace(/\.png$/i, '').replace(/[-_]+/g, ' ').trim();
  return /^[0-9a-f]{12,}$/i.test(clean) ? '' : clean.slice(0, 40);
}

function offer(buf, { name = '', source = '' } = {}) {
  try {
    skins.checkSkin(buf);
    send('wardrobe:found', { dataUrl: skins.toDataUrl(buf), name, source });
    if (win && !win.isDestroyed()) { win.show(); win.focus(); }
  } catch (e) {
    send('wardrobe:notice', { error: e.message });
  }
}

async function catchUrl(url, wc) {
  try {
    if (!wc || wc.isDestroyed()) return;
    const pageHost = host(wc.getURL());
    const res = await wc.session.fetch(url);
    if (!res.ok) throw new Error(`Couldn't download that picture (${res.status}).`);
    offer(Buffer.from(await res.arrayBuffer()), { name: nameFrom(url), source: pageHost || host(url) });
  } catch (e) {
    send('wardrobe:notice', { error: e.message });
  }
}

function hookSession(ses) {
  if (sessionsHooked.has(ses)) return;
  sessionsHooked.add(ses);
  ses.on('will-download', (_e, item) => {
    const tmp = path.join(os.tmpdir(), `catgirl-skin-${Date.now()}-${Math.random().toString(36).slice(2)}.png`);
    item.setSavePath(tmp);
    const filename = item.getFilename();
    item.once('done', (_ev, state) => {
      if (state === 'completed') {
        let source = '';
        try { if (view && !view.webContents.isDestroyed()) source = host(view.webContents.getURL()); } catch {}
        try { offer(fs.readFileSync(tmp), { name: nameFrom(filename), source }); } catch {}
      }
      fs.rm(tmp, { force: true }, () => {});
    });
  });
}

function layout() {
  if (!win || win.isDestroyed() || !view || view.webContents.isDestroyed()) return;
  const [w, h] = win.getContentSize();
  view.setBounds({ x: PANEL, y: 0, width: Math.max(0, w - PANEL), height: h });
}

function navState(wc) {
  if (!wc || wc.isDestroyed() || !win || win.isDestroyed()) return;
  send('wardrobe:nav', { url: wc.getURL(), host: host(wc.getURL()), canBack: wc.navigationHistory.canGoBack(), canForward: wc.navigationHistory.canGoForward(), loading: wc.isLoading() });
}

function init({ icon, preload, renderer }) {
  iconPath = icon;
  preloadPath = preload;
  rendererDir = renderer;
}

function open({ fromGame = false } = {}) {
  if (win && !win.isDestroyed()) {
    if (win.isMinimized()) win.restore();
    if (fromGame) win.setAlwaysOnTop(true, 'floating');
    win.show();
    win.focus();
    return;
  }
  win = new BrowserWindow({
    width: 1240, height: 800, minWidth: 900, minHeight: 560,
    frame: false, backgroundColor: '#170d1c', title: 'Catgirl Wardrobe', icon: iconPath,
    alwaysOnTop: fromGame,
    webPreferences: { preload: preloadPath, contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.loadFile(path.join(rendererDir, 'wardrobe.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e) => e.preventDefault());

  // The skin site, in its own sandboxed view that keeps its logins between visits.
  const ses = session.fromPartition('persist:catgirl-wardrobe');
  hookSession(ses);
  view = new WebContentsView({ webPreferences: { session: ses, sandbox: true, contextIsolation: true, nodeIntegration: false } });
  win.contentView.addChildView(view);
  const wc = view.webContents;
  wc.setWindowOpenHandler(({ url }) => {
    if (isPng(url)) catchUrl(url, wc);
    else if (/^https?:\/\//.test(url)) wc.loadURL(url);
    return { action: 'deny' };
  });
  wc.on('will-navigate', (e, url) => { if (isPng(url)) { e.preventDefault(); catchUrl(url, wc); } });
  for (const ev of ['did-navigate', 'did-navigate-in-page', 'did-start-loading', 'did-stop-loading']) wc.on(ev, () => navState(wc));
  wc.on('context-menu', (_e, params) => {
    const items = [];
    if (params.mediaType === 'image' && params.srcURL) items.push({ label: 'Use this picture as my skin', click: () => catchUrl(params.srcURL, wc) }, { type: 'separator' });
    items.push(
      { label: 'Back', enabled: wc.navigationHistory.canGoBack(), click: () => wc.navigationHistory.goBack() },
      { label: 'Reload', click: () => wc.reload() },
      { label: 'Open in my browser', click: () => shell.openExternal(wc.getURL()) },
    );
    Menu.buildFromTemplate(items).popup({ window: win });
  });
  wc.loadURL(SITES.skindex.home);

  win.on('resize', layout);
  win.once('ready-to-show', () => { layout(); win.show(); win.focus(); });
  win.on('closed', () => {
    const v = view;
    win = null;
    view = null;
    try { if (v && !v.webContents.isDestroyed()) v.webContents.close(); } catch {}
  });
  layout();
}

function search(site, query) {
  if (!view || view.webContents.isDestroyed()) return;
  const s = SITES[site] || SITES.skindex;
  const q = String(query || '').trim().slice(0, 80);
  view.webContents.loadURL(q ? s.search(q) : s.home);
}

function nav(cmd) {
  if (!view || view.webContents.isDestroyed()) return;
  const wc = view.webContents;
  if (cmd === 'back' && wc.navigationHistory.canGoBack()) wc.navigationHistory.goBack();
  if (cmd === 'forward' && wc.navigationHistory.canGoForward()) wc.navigationHistory.goForward();
  if (cmd === 'reload') wc.reload();
  if (cmd === 'external') shell.openExternal(wc.getURL());
}

function windowCmd(cmd) {
  if (!win || win.isDestroyed()) return false;
  if (cmd === 'minimize') { win.minimize(); return false; }
  if (cmd === 'close') { win.close(); return false; }
  if (cmd === 'pin') { const on = !win.isAlwaysOnTop(); win.setAlwaysOnTop(on, 'floating'); return on; }
  return win.isAlwaysOnTop();
}

module.exports = { init, open, search, nav, windowCmd, SITES, isPng, nameFrom };
