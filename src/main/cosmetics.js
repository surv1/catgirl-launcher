// Free Catgirl cosmetics (cat ears, tail, bow, wings, halo, horns, angel buddy, cape). Saved on this PC per account, and sent to the
// Catgirl cosmetics service so every Catgirl Client player can see them in game.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const paths = require('./paths');

const DEFAULTS = {
  ears: { on: false, color: '#3b2a2a', inner: '#ffb3d9' },
  tail: { on: false, color: '#3b2a2a' },
  bow: { on: false, color: '#ff7eb6' },
  wings: { on: false, color: '#ffffff', style: 'angel' },
  halo: { on: false, color: '#ffd34d' },
  horns: { on: false, color: '#5a1a1a' },
  pet: { on: false, color: '#ffb3d9' },
  cape: { on: false, color: '#ff7eb6', trim: '#ffffff', style: 'paw', line: 'cycle' },
};
const ITEMS = Object.keys(DEFAULTS);
const UA = 'CatgirlLauncher (+https://catgirlclient.lol)';
const hexColor = (c, d) => (/^#[0-9a-fA-F]{6}$/.test(c || '') ? c.toLowerCase() : d);

// Only known items, booleans and #rrggbb colours. Same rules as the online service.
function normalize(input) {
  const out = {};
  for (const k of ITEMS) {
    const v = input && typeof input === 'object' && input[k] && typeof input[k] === 'object' ? input[k] : {};
    out[k] = { on: !!v.on, color: hexColor(v.color, DEFAULTS[k].color) };
    if (k === 'ears') out[k].inner = hexColor(v.inner, DEFAULTS.ears.inner);
    if (k === 'wings') out[k].style = ['angel', 'demon'].includes(v.style) ? v.style : 'angel';
    if (k === 'cape') {
      out[k].trim = hexColor(v.trim, '#ffffff');
      out[k].style = ['plain', 'paw', 'heart', 'meow', 'catmeow'].includes(v.style) ? v.style : 'paw';
      out[k].line = [...["meow!", "nya~", "nyaa~!", "mrrp?", "purr~", "mew!", ":3", "uwu"], 'cycle', 'none'].includes(v.line) ? v.line : 'cycle';
      const c = v.custom;
      if (c && /^[0-9a-f]{64}$/.test(c.sha || '')) out[k].custom = { sha: c.sha, frames: clampInt(c.frames, 1, MAX_FRAMES, 1), delay: clampInt(c.delay, 20, 2000, 100) };
    }
  }
  return out;
}

// ---------- your own cape picture ----------
// Stored as a PNG "film strip": frames of 60×96 stacked top to bottom (the launcher page builds it).
const CAPE_W = 60, CAPE_H = 96, MAX_FRAMES = 32, MAX_PNG = 1536 * 1024;
function clampInt(n, lo, hi, d) { n = Math.round(Number(n)); return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : d; }
const capeDir = () => path.join(paths.dirs().base, 'capes');
const capeFile = (sha) => path.join(capeDir(), `${sha}.png`);

function checkStrip(buf, frames) {
  if (buf.length > MAX_PNG) throw new Error('That cape picture is too big. Try a shorter GIF.');
  if (buf.length < 33 || buf.readUInt32BE(0) !== 0x89504e47) throw new Error("That isn't a picture we can use.");
  if (buf.readUInt32BE(16) !== CAPE_W || buf.readUInt32BE(20) !== CAPE_H * frames) throw new Error('That cape picture is the wrong size.');
}

function saveCapePicture(pngBase64, frames, delay) {
  frames = clampInt(frames, 1, MAX_FRAMES, 1);
  const buf = Buffer.from(String(pngBase64 || ''), 'base64');
  checkStrip(buf, frames);
  const sha = crypto.createHash('sha256').update(buf).digest('hex');
  fs.mkdirSync(capeDir(), { recursive: true });
  fs.writeFileSync(capeFile(sha), buf);
  return { sha, frames, delay: clampInt(delay, 20, 2000, 100) };
}

function capePictureDataUrl(custom) {
  try { return custom ? `data:image/png;base64,${fs.readFileSync(capeFile(custom.sha)).toString('base64')}` : null; } catch { return null; }
}

const file = () => path.join(paths.dirs().base, 'cosmetics.json');
const store = () => paths.readJson(file(), {});

function get(uuid) {
  const e = store()[uuid];
  return { items: normalize(e?.items), synced: !!e?.synced };
}

function save(uuid, items, synced = false) {
  const all = store();
  all[uuid] = { items: normalize(items), synced };
  paths.writeJson(file(), all);
  return all[uuid];
}

const serverIdFor = (nonce) => crypto.createHash('sha1').update(nonce).digest('hex');

async function asJson(res) { return res.json().catch(() => null); }

// Prove we own the account (like joining a server), then save the cosmetics online.
async function upload(api, account, items) {
  if (!api) throw new Error('No cosmetics service is set up yet.');
  const base = api.replace(/\/+$/, '');
  let res;
  try {
    res = await fetch(`${base}/v1/challenge`, { method: 'POST', headers: { 'User-Agent': UA } });
  } catch {
    throw new Error("Couldn't reach the Catgirl cosmetics service.");
  }
  const ch = await asJson(res);
  if (!res.ok || !ch?.nonce) throw new Error(`The cosmetics service isn't answering right now (${res.status}).`);

  const join = await fetch('https://sessionserver.mojang.com/session/minecraft/join', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': UA },
    body: JSON.stringify({ accessToken: account.mcToken, selectedProfile: String(account.uuid).replace(/-/g, ''), serverId: serverIdFor(ch.nonce) }),
  });
  if (join.status === 401 || join.status === 403) throw new Error('Your login has expired. Remove and re-add your account.');
  if (!join.ok) throw new Error(`Minecraft's login service said no (${join.status}).`);

  const put = await fetch(`${base}/v1/me`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'User-Agent': UA },
    body: JSON.stringify({ username: account.name, nonce: ch.nonce, cosmetics: normalize(items), capeImage: capeUpload(normalize(items).cape.custom) }),
  });
  const body = await asJson(put);
  if (!put.ok) throw new Error(body?.error || `The cosmetics service said no (${put.status}).`);
  return body;
}

function capeUpload(custom) {
  if (!custom) return null;
  try { return { png: fs.readFileSync(capeFile(custom.sha)).toString('base64'), frames: custom.frames, delay: custom.delay }; } catch { return null; }
}

// Save locally first (so you always see them yourself), then try to share them online.
async function saveAndSync(api, account, items) {
  save(account.uuid, items, false);
  try {
    await upload(api, account, items);
    save(account.uuid, items, true);
    return { items: normalize(items), synced: true };
  } catch (e) {
    return { items: normalize(items), synced: false, error: e.message };
  }
}

// Quietly retry an earlier save that couldn't reach the service.
async function retryIfNeeded(api, account) {
  const cur = get(account.uuid);
  if (cur.synced || !ITEMS.some((k) => cur.items[k].on)) return;
  try { await upload(api, account, cur.items); save(account.uuid, cur.items, true); } catch { /* next time */ }
}

// What the in-game mod reads: <game>/config/catgirl-cosmetics.json. The mod watches this file,
// so changes show up while you're playing.
function writeForGame(gameDir, api, account, { showCapePictures = true } = {}) {
  const f = path.join(gameDir, 'config', 'catgirl-cosmetics.json');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  const uuid = account ? String(account.uuid).replace(/-/g, '').toLowerCase() : '';
  const items = uuid ? get(account.uuid).items : normalize({});
  // The game reads your own cape picture straight from this PC, so it shows even before it's online.
  const c = items.cape.custom;
  if (c) { Object.assign(items.cape, { image: c.sha, frames: c.frames, delay: c.delay, file: capeFile(c.sha) }); delete items.cape.custom; }
  fs.writeFileSync(f, JSON.stringify({ api: api || '', uuid, showCapePictures: showCapePictures !== false, items }, null, 2));
}

module.exports = { ITEMS, DEFAULTS, normalize, get, save, upload, saveAndSync, retryIfNeeded, writeForGame, serverIdFor, saveCapePicture, capePictureDataUrl, CAPE_W, CAPE_H, MAX_FRAMES };
