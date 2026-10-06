// Skins: wear a new skin on your Minecraft account, copy a player's skin, and keep a history
// of every skin you've worn so nothing is ever lost.
// Uses the official Minecraft services API (the same one minecraft.net uses).
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const paths = require('./paths');
const { UA } = require('./net');

const MC = 'https://api.minecraftservices.com';
const MAX_HISTORY = 80;
const MAX_BYTES = 2 * 1024 * 1024;

function dir() {
  const d = path.join(paths.dirs().base, 'skins');
  fs.mkdirSync(d, { recursive: true });
  return d;
}
const indexFile = () => path.join(dir(), 'history.json');
const readIndex = () => paths.readJson(indexFile(), []);
const writeIndex = (list) => paths.writeJson(indexFile(), list);

// ---------- checking a picture really is a Minecraft skin ----------
function pngSize(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 24) return null;
  if (buf.readUInt32BE(0) !== 0x89504e47 || buf.readUInt32BE(4) !== 0x0d0a1a0a) return null;
  if (buf.toString('ascii', 12, 16) !== 'IHDR') return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

function checkSkin(buf) {
  const size = pngSize(buf);
  if (!size) throw new Error("That isn't a skin picture (Minecraft skins are PNG images).");
  if (buf.length > MAX_BYTES) throw new Error('That picture is too big to be a skin.');
  const ok = size.width === 64 && (size.height === 64 || size.height === 32);
  if (!ok) {
    throw new Error(`That picture is ${size.width}×${size.height}. A skin file is 64×64. On skin sites, use the Download button rather than the preview picture.`);
  }
  return size;
}

const sha1 = (buf) => crypto.createHash('sha1').update(buf).digest('hex');
const toDataUrl = (buf) => `data:image/png;base64,${buf.toString('base64')}`;
const fromDataUrl = (url) => {
  const m = /^data:image\/png;base64,(.+)$/.exec(url || '');
  if (!m) throw new Error('Not a PNG picture.');
  return Buffer.from(m[1], 'base64');
};

async function fetchBuffer(url, opts = {}) {
  const res = await fetch(url, { ...opts, headers: { 'User-Agent': UA, ...(opts.headers || {}) } });
  if (!res.ok) throw new Error(`Couldn't download that (${res.status}).`);
  return Buffer.from(await res.arrayBuffer());
}

// ---------- history ("Old skins used") ----------
function addToHistory(buf, { variant = 'classic', name = '', source = '', account = '' } = {}) {
  checkSkin(buf);
  const id = sha1(buf);
  const file = path.join(dir(), `${id}.png`);
  if (!fs.existsSync(file)) fs.writeFileSync(file, buf);
  const list = readIndex();
  const existing = list.find((e) => e.id === id && e.account === account);
  // always newer than everything else, so the skin you just used is at the top
  const now = Math.max(Date.now(), ...list.map((e) => (e.lastUsed || 0) + 1));
  if (existing) {
    existing.variant = variant || existing.variant;
    existing.lastUsed = now;
    if (name && !existing.name) existing.name = name;
  } else {
    list.unshift({ id, variant, name, source, account, addedAt: now, lastUsed: now });
  }
  list.sort((a, b) => b.lastUsed - a.lastUsed);
  // keep the newest; delete pictures nothing points to any more
  const kept = list.slice(0, MAX_HISTORY);
  const keepIds = new Set(kept.map((e) => e.id));
  for (const e of list.slice(MAX_HISTORY)) {
    if (!keepIds.has(e.id)) fs.rmSync(path.join(dir(), `${e.id}.png`), { force: true });
  }
  writeIndex(kept);
  return id;
}

function history(account = '') {
  return readIndex()
    .filter((e) => !account || !e.account || e.account === account)
    .map((e) => {
      let dataUrl = null;
      try { dataUrl = toDataUrl(fs.readFileSync(path.join(dir(), `${e.id}.png`))); } catch {}
      return { ...e, dataUrl };
    })
    .filter((e) => e.dataUrl);
}

function removeFromHistory(id, account = '') {
  const list = readIndex().filter((e) => !(e.id === id && (!account || e.account === account)));
  writeIndex(list);
  if (!list.some((e) => e.id === id)) fs.rmSync(path.join(dir(), `${id}.png`), { force: true });
}

function historyBuffer(id) {
  if (!/^[0-9a-f]{40}$/.test(id)) throw new Error('Unknown skin.');
  return fs.readFileSync(path.join(dir(), `${id}.png`));
}

// ---------- your Minecraft account ----------
function explain(status, body) {
  if (status === 401) return 'Your login has expired. Remove and re-add your account in the launcher.';
  if (status === 429) return "Minecraft only allows a few skin changes per minute. Wait a moment and try again.";
  if (status === 400) return `Minecraft didn't accept that skin${body?.errorMessage ? `: ${body.errorMessage}` : '.'}`;
  return `Minecraft's skin service said no (${status}).`;
}

async function currentSkin(token) {
  const res = await fetch(`${MC}/minecraft/profile`, { headers: { Authorization: `Bearer ${token}`, 'User-Agent': UA } });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error(explain(res.status, body));
  const active = (body.skins || []).find((s) => s.state === 'ACTIVE');
  if (!active?.url) return null;
  const buf = await fetchBuffer(active.url.replace(/^http:/, 'https:'));
  return { buf, variant: String(active.variant || 'classic').toLowerCase() === 'slim' ? 'slim' : 'classic' };
}

// Save whatever you're wearing now, so it's never lost.
async function rememberCurrent(account) {
  try {
    const cur = await currentSkin(account.mcToken);
    if (cur) addToHistory(cur.buf, { variant: cur.variant, name: 'Skin you were wearing', source: 'account', account: account.uuid });
  } catch { /* not fatal */ }
}

async function wear(account, buf, { variant = 'classic', name = '', source = '' } = {}) {
  checkSkin(buf);
  if (!['classic', 'slim'].includes(variant)) variant = 'classic';
  await rememberCurrent(account);
  const form = new FormData();
  form.append('variant', variant);
  form.append('file', new Blob([buf], { type: 'image/png' }), 'skin.png');
  const res = await fetch(`${MC}/minecraft/profile/skins`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${account.mcToken}`, 'User-Agent': UA },
    body: form,
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error(explain(res.status, body));
  return addToHistory(buf, { variant, name, source, account: account.uuid });
}

// ---------- getting skins from elsewhere ----------
async function fromUrl(url) {
  if (!/^https:\/\//i.test(url || '')) throw new Error('Paste a link that starts with https://');
  const buf = await fetchBuffer(url);
  checkSkin(buf);
  return { dataUrl: toDataUrl(buf), source: new URL(url).hostname };
}

function fromFile(file) {
  const buf = fs.readFileSync(file);
  checkSkin(buf);
  return { dataUrl: toDataUrl(buf), name: path.basename(file, path.extname(file)), source: 'file' };
}

async function fromPlayer(username) {
  const name = String(username || '').trim();
  if (!/^[A-Za-z0-9_]{2,16}$/.test(name)) throw new Error('Type a Minecraft username (letters, numbers and _ only).');
  const res = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(name)}`, { headers: { 'User-Agent': UA } });
  if (res.status === 404 || res.status === 204) throw new Error(`No player called ${name}.`);
  if (!res.ok) throw new Error(`Couldn't look up ${name} (${res.status}).`);
  const { id, name: realName } = await res.json();
  const prof = await fetch(`https://sessionserver.mojang.com/session/minecraft/profile/${id}`, { headers: { 'User-Agent': UA } }).then((r) => r.json());
  const prop = (prof.properties || []).find((p) => p.name === 'textures');
  if (!prop) throw new Error(`${realName} is using a default skin.`);
  const tex = JSON.parse(Buffer.from(prop.value, 'base64').toString('utf8')).textures?.SKIN;
  if (!tex?.url) throw new Error(`${realName} is using a default skin.`);
  const buf = await fetchBuffer(tex.url.replace(/^http:/, 'https:'));
  checkSkin(buf);
  return { dataUrl: toDataUrl(buf), name: `${realName}'s skin`, source: 'player', variant: tex.metadata?.model === 'slim' ? 'slim' : 'classic' };
}

module.exports = { pngSize, checkSkin, addToHistory, history, removeFromHistory, historyBuffer, currentSkin, rememberCurrent, wear, fromUrl, fromFile, fromPlayer, toDataUrl, fromDataUrl };
