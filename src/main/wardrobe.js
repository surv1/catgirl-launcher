// The Wardrobe: a small Catgirl window with a skin site on the right and our panel on the left.
// When you click Download on any skin (or open a skin picture, or right-click it), the window
// catches it and offers to put it on. Nothing is copied to our own site: players browse the
// real skin sites themselves, inside this window.
const { BrowserWindow, WebContentsView, Menu, session, shell } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const skins = require('./skins');
const { filesInZip } = require('./unzip');

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

// Is this a skin we can use? Normal 64×64 skins as they are; "HD" skins (128×128, 256×256…, which
// Bedrock allows) are shrunk to 64×64 by the Wardrobe page. Returns { buf, hd } or null.
function asSkin(buf) {
  const size = skins.pngSize(buf);
  if (!size) return null;
  if (size.width === 64 && (size.height === 64 || size.height === 32)) return { buf, hd: false };
  const square = size.width === size.height || size.width === size.height * 2;
  if (square && size.width > 64 && size.width <= 1024 && size.width % 64 === 0) return { buf, hd: true };
  return null;
}

// Skin downloads can also be zipped skin packs (.zip / .mcpack, mostly from Bedrock): look inside.
function findSkin(buf) {
  const direct = asSkin(buf);
  if (direct) return direct;
  if (buf.length > 4 && buf.readUInt32LE(0) === 0x04034b50) {
    let files = [];
    try { files = filesInZip(buf).filter((f) => /\.png$/i.test(f.name) && !/pack_icon|preview|thumbnail/i.test(f.name)); } catch { return null; }
    const score = (n) => (/skin/i.test(n) ? 0 : 1) + (/icon|preview|cape/i.test(n) ? 5 : 0);
    files.sort((a, b) => score(a.name) - score(b.name));
    for (const f of files.slice(0, 40)) {
      try { const s = asSkin(f.read()); if (s) return s; } catch { /* next */ }
    }
  }
  return null;
}

function offer(buf, { name = '', source = '' } = {}) {
  const skin = findSkin(buf);
  if (!skin) {
    let why = "That download isn't a skin we can use.";
    try { skins.checkSkin(buf); } catch (e) { if (skins.pngSize(buf)) why = e.message; }
    send('wardrobe:notice', { error: why });
    return false;
  }
  send('wardrobe:found', { dataUrl: skins.toDataUrl(skin.buf), name, source, hd: skin.hd });
  if (win && !win.isDestroyed()) { win.show(); win.focus(); }
  return true;
}

async function fetchBytes(url, wc) {
  const res = await wc.session.fetch(url);
  if (!res.ok) throw new Error(`Couldn't download that (${res.status}).`);
  return Buffer.from(await res.arrayBuffer());
}

// The "Use the skin on this page" button: find the skin's download link on the open page.
async function grabFromPage() {
  if (!view || view.webContents.isDestroyed()) throw new Error('Open a skin site first.');
  const wc = view.webContents;
  const links = await wc.executeJavaScript(`(() => {
    const out = [];
    const add = (h) => { if (h && !out.includes(h)) out.push(h); };
    const all = [...document.querySelectorAll('a[href]')].map((a) => a.href);
    all.filter((h) => /planetminecraft\.com\/skin\/.+\/download\/file\//.test(h)).forEach(add);
    all.filter((h) => /minecraftskins\.com\/skin\/download\//.test(h)).forEach(add);
    all.filter((h) => /namemc\.com\/texture\/[0-9a-f]+\.png/i.test(h)).forEach(add);
    [...document.querySelectorAll('[src],[data-src]')].map((e) => e.getAttribute('data-src') || e.src)
      .filter((h) => h && /texture\/[0-9a-f]+\.png|\/skins?\/.*\.png/i.test(h)).forEach((h) => add(new URL(h, location.href).href));
    all.filter((h) => /download/i.test(h) && /skin/i.test(h) && !/remote|apply|bedrock-app/i.test(h)).forEach(add);
    return out.slice(0, 8);
  })()`, true);
  if (!links.length) throw new Error("Couldn't find a skin on this page. Open one skin's own page first.");
  let lastErr = null;
  for (const url of links) {
    try {
      const buf = await fetchBytes(url, wc);
      if (findSkin(buf)) return offer(buf, { name: nameFrom(url) || nameFrom(wc.getTitle() || ''), source: host(wc.getURL()) });
    } catch (e) { lastErr = e; }
  }
  throw new Error(lastErr ? lastErr.message : "Found a download on this page, but it isn't a skin we can use.");
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

module.exports = { init, open, search, nav, windowCmd, grabFromPage, findSkin, SITES, isPng, nameFrom };
