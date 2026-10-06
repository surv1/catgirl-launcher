// Catgirl Client cosmetics service (Cloudflare Worker + KV).
// Stores which free cosmetics each player wears, so every Catgirl Client player can see them.
//
// GET  /v1/cosmetics?uuids=<uuid>,<uuid>…  → { "<uuid>": { ears, tail, bow } }   (public, cached)
// POST /v1/challenge                        → { nonce }   (one-time code, valid 2 minutes)
// PUT  /v1/me  { username, nonce, cosmetics } → saves your cosmetics
//
// Proving you own an account without ever sending us your login: the launcher tells Mojang
// "I'm joining server <sha1(nonce)>" with the player's own token (like joining a real server),
// then this service asks Mojang "did <username> join <sha1(nonce)>?". Only the real account
// owner can make that true.
//
// Setup: a KV namespace bound as COSMETICS, and a secret NONCE_SECRET (any long random text).

const ITEMS = ['ears', 'tail', 'bow'];
const MAX_UUIDS = 60;
const NONCE_TTL_MS = 2 * 60 * 1000;

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
    out[k] = { on: !!v.on, color: color(v.color, '#3b2a2a') };
    if (k === 'ears') out[k].inner = color(v.inner, '#ffb3d9');
  }
  return out;
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
        const check = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(body.username)}&serverId=${await serverIdFor(body.nonce)}`);
        if (check.status !== 200) return json({ error: "Couldn't confirm you own that Minecraft account." }, 403);
        const profile = await check.json();
        const uuid = normUuid(profile.id);
        await env.COSMETICS.put(used, '1', { expirationTtl: 300 });
        const cosmetics = clean(body.cosmetics);
        await env.COSMETICS.put(`c:${uuid}`, JSON.stringify(cosmetics));
        return json({ ok: true, uuid, cosmetics });
      }

      if (url.pathname === '/' || url.pathname === '/v1') return json({ service: 'Catgirl Client cosmetics', ok: true });
      return json({ error: 'Not found' }, 404);
    } catch (e) {
      return json({ error: 'Something went wrong' }, 500);
    }
  },
};
