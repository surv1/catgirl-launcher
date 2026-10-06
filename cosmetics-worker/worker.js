// Catgirl Client cosmetics service (Cloudflare Worker + KV).
// Stores which free cosmetics each player wears, so every Catgirl Client player can see them.
//
// GET  /v1/cape/<sha256>.png                → a custom cape picture (public, cached forever)
// POST /v1/admin/remove-cape { uuid }       → takes down someone's cape picture (needs ADMIN_TOKEN)
// GET  /v1/cosmetics?uuids=<uuid>,<uuid>…  → { "<uuid>": { ears, tail, bow, wings, halo, horns, pet, cape } }   (public, cached)
// POST /v1/challenge                        → { nonce }   (one-time code, valid 2 minutes)
// PUT  /v1/me  { username, nonce, cosmetics } → saves your cosmetics
//
// Proving you own an account without ever sending us your login: the launcher tells Mojang
// "I'm joining server <sha1(nonce)>" with the player's own token (like joining a real server),
// then this service asks Mojang "did <username> join <sha1(nonce)>?". Only the real account
// owner can make that true.
//
// Setup: a KV namespace bound as COSMETICS, and a secret NONCE_SECRET (any long random text).

const DEFAULT_COLOR = { ears: '#3b2a2a', tail: '#3b2a2a', bow: '#ff7eb6', wings: '#ffffff', halo: '#ffd34d', horns: '#5a1a1a', pet: '#ffb3d9', cape: '#ff7eb6', trim: '#7ec8ff' };
const ITEMS = Object.keys(DEFAULT_COLOR);
const WING_STYLES = ['angel', 'demon'];
const TRIM_STYLES = ['paws', 'stars', 'hearts', 'circuit'];
const CAPE_STYLES = ['plain', 'paw', 'heart', 'meow', 'catmeow'];
const CAPE_LINES = [...["meow!", "nya~", "nyaa~!", "mrrp?", "purr~", "mew!", ":3", "uwu"], 'cycle', 'none'];
const MAX_UUIDS = 60;
const NONCE_TTL_MS = 2 * 60 * 1000;
// Custom cape pictures: a PNG "film strip" of frames stacked top to bottom, each 60×96 (cape shape).
export const CAPE_W = 60, CAPE_H = 96, MAX_FRAMES = 32, MAX_PNG = 1536 * 1024;

const json = (body, status = 200, extra = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...extra } });

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
async function hmac(secret, text) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(text)));
}
export async function serverIdFor(nonce) {
  return hex(await crypto.subtle.digest('SHA-1', new TextEncoder().encode(nonce)));
}

export async function makeNonce(secret, now = Date.now()) {
  const rand = hex(crypto.getRandomValues(new Uint8Array(12)));
  const body = `${now}.${rand}`;
  return `${body}.${(await hmac(secret, body)).slice(0, 32)}`;
}
export async function checkNonce(secret, nonce, now = Date.now()) {
  const m = /^(\d{10,16})\.([0-9a-f]{24})\.([0-9a-f]{32})$/.exec(String(nonce || ''));
  if (!m) return false;
  if (now - Number(m[1]) > NONCE_TTL_MS || Number(m[1]) > now + 5000) return false;
  return (await hmac(secret, `${m[1]}.${m[2]}`)).slice(0, 32) === m[3];
}

// Only known items, booleans and #rrggbb colours are kept.
export function clean(input) {
  const out = {};
  const color = (c, d) => (/^#[0-9a-fA-F]{6}$/.test(c || '') ? c.toLowerCase() : d);
  for (const k of ITEMS) {
    const v = input && typeof input === 'object' ? input[k] : null;
    if (!v || typeof v !== 'object') continue;
    out[k] = { on: !!v.on, color: color(v.color, DEFAULT_COLOR[k]) };
    if (k === 'ears') out[k].inner = color(v.inner, '#ffb3d9');
    if (k === 'wings') out[k].style = WING_STYLES.includes(v.style) ? v.style : 'angel';
    if (k === 'trim') { out[k].accent = color(v.accent, '#ff7eb6'); out[k].style = TRIM_STYLES.includes(v.style) ? v.style : 'paws'; }
    if (k === 'cape') { out[k].trim = color(v.trim, '#ffffff'); out[k].style = CAPE_STYLES.includes(v.style) ? v.style : 'paw'; out[k].line = CAPE_LINES.includes(v.line) ? v.line : 'cycle'; }
  }
  return out;
}

const b64ToBytes = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

// Checks an uploaded cape picture. Returns { bytes, frames, delay } or throws a readable error.
export function checkCapeImage(img) {
  if (!img || typeof img !== 'object' || typeof img.png !== 'string') throw new Error('Bad cape picture.');
  if (img.png.length > Math.ceil(MAX_PNG / 3) * 4 + 8) throw new Error('That cape picture is too big.');
  const bytes = b64ToBytes(img.png);
  const sig = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 33 || sig.some((b, i) => bytes[i] !== b)) throw new Error('Cape pictures must be PNG.');
  const dv = new DataView(bytes.buffer);
  const w = dv.getUint32(16), h = dv.getUint32(20);
  const frames = Number(img.frames);
  if (!Number.isInteger(frames) || frames < 1 || frames > MAX_FRAMES) throw new Error('Too many frames in that GIF.');
  if (w !== CAPE_W || h !== CAPE_H * frames) throw new Error('That cape picture is the wrong size.');
  const delay = Math.max(20, Math.min(2000, Math.round(Number(img.delay) || 100)));
  return { bytes, frames, delay };
}

const normUuid = (u) => String(u || '').toLowerCase().replace(/-/g, '');

async function getMany(env, uuids) {
  const result = {};
  await Promise.all(uuids.map(async (u) => {
    const v = await env.COSMETICS.get(`c:${u}`, 'json');
    if (v) result[u] = v;
  }));
  return result;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === 'GET' && url.pathname === '/v1/cosmetics') {
        const uuids = [...new Set((url.searchParams.get('uuids') || '').split(',').map(normUuid).filter((u) => /^[0-9a-f]{32}$/.test(u)))].slice(0, MAX_UUIDS);
        if (!uuids.length) return json({});
        const cache = caches.default;
        const key = new Request(`${url.origin}/v1/cosmetics?uuids=${uuids.sort().join(',')}`);
        const hit = await cache.match(key);
        if (hit) return hit;
        const res = json(await getMany(env, uuids), 200, { 'cache-control': 'public, max-age=30' });
        ctx.waitUntil(cache.put(key, res.clone()));
        return res;
      }

      if (request.method === 'POST' && url.pathname === '/v1/challenge') {
        return json({ nonce: await makeNonce(env.NONCE_SECRET) });
      }

      if (request.method === 'PUT' && url.pathname === '/v1/me') {
        const body = await request.json().catch(() => null);
        if (!body || !/^[A-Za-z0-9_]{2,16}$/.test(body.username || '')) return json({ error: 'Bad request' }, 400);
        if (!(await checkNonce(env.NONCE_SECRET, body.nonce))) return json({ error: 'That code expired. Try again.' }, 400);
        const used = `n:${body.nonce}`;
        if (await env.COSMETICS.get(used)) return json({ error: 'That code was already used. Try again.' }, 400);
        // Ask Mojang if this player really "joined" our one-time code. Try a few times: Mojang can
        // take a moment to see the join.
        const joinedUrl = `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(body.username)}&serverId=${await serverIdFor(body.nonce)}`;
        let check = null;
        for (let attempt = 0; attempt < 4; attempt++) {
          if (attempt) await new Promise((r) => setTimeout(r, 600 * attempt));
          check = await fetch(joinedUrl, { headers: { 'User-Agent': 'CatgirlClient-Cosmetics/1.0 (+https://catgirlclient.lol)', Accept: 'application/json' } });
          if (check.status === 200) break;
        }
        if (check.status !== 200) return json({ error: `Couldn't confirm you own that Minecraft account (Mojang said ${check.status}).` }, 403);
        const profile = await check.json();
        const uuid = normUuid(profile.id);
        await env.COSMETICS.put(used, '1', { expirationTtl: 300 });
        const cosmetics = clean(body.cosmetics);
        if (body.capeImage && cosmetics.cape) {
          let img;
          try { img = checkCapeImage(body.capeImage); } catch (e) { return json({ error: e.message }, 400); }
          const sha = hex(await crypto.subtle.digest('SHA-256', img.bytes));
          if (await env.COSMETICS.get(`blockimg:${sha}`)) return json({ error: "That cape picture isn't allowed." }, 403);
          await env.COSMETICS.put(`img:${sha}`, img.bytes);
          Object.assign(cosmetics.cape, { image: sha, frames: img.frames, delay: img.delay });
        }
        await env.COSMETICS.put(`c:${uuid}`, JSON.stringify(cosmetics));
        return json({ ok: true, uuid, cosmetics });
      }

      const capeMatch = /^\/v1\/cape\/([0-9a-f]{64})\.png$/.exec(url.pathname);
      if (request.method === 'GET' && capeMatch) {
        const sha = capeMatch[1];
        if (await env.COSMETICS.get(`blockimg:${sha}`)) return json({ error: 'Not found' }, 404);
        const bytes = await env.COSMETICS.get(`img:${sha}`, 'arrayBuffer');
        if (!bytes) return json({ error: 'Not found' }, 404);
        return new Response(bytes, { headers: { 'content-type': 'image/png', 'cache-control': 'public, max-age=31536000, immutable' } });
      }

      // Moderation: remove a player's cape picture and stop it ever being shown again.
      if (request.method === 'POST' && url.pathname === '/v1/admin/remove-cape') {
        if (!env.ADMIN_TOKEN || request.headers.get('authorization') !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: 'Not allowed' }, 403);
        const body = await request.json().catch(() => null);
        const uuid = normUuid(body?.uuid);
        const rec = await env.COSMETICS.get(`c:${uuid}`, 'json');
        const sha = rec?.cape?.image;
        if (!sha) return json({ ok: true, removed: false });
        await env.COSMETICS.put(`blockimg:${sha}`, '1');
        await env.COSMETICS.delete(`img:${sha}`);
        delete rec.cape.image; delete rec.cape.frames; delete rec.cape.delay;
        await env.COSMETICS.put(`c:${uuid}`, JSON.stringify(rec));
        return json({ ok: true, removed: true });
      }

      if (url.pathname === '/' || url.pathname === '/v1') return json({ service: 'Catgirl Client cosmetics', ok: true });
      return json({ error: 'Not found' }, 404);
    } catch (e) {
      return json({ error: 'Something went wrong' }, 500);
    }
  },
};
