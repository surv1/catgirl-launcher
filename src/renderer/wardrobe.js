/* Catgirl Wardrobe – left panel */
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const state = { site: 'skindex', found: null, variant: 'classic', items: [], busy: false };

function status(msg, kind = '') {
  const el = $('#status');
  el.textContent = msg;
  el.className = `status ${kind}`;
}

/* ---------- theme (same as the launcher) ---------- */
const hexToRgb = (h) => { const n = parseInt(h.replace('#', ''), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const rgbToHex = (r) => '#' + r.map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
const mix = (a, b, t) => rgbToHex(hexToRgb(a).map((v, i) => v + (hexToRgb(b)[i] - v) * t));
const lum = (h) => { const [r, g, b] = hexToRgb(h).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
function applyTheme(s) {
  document.body.dataset.theme = s.theme || 'sakura';
  if (s.theme !== 'custom') return;
  const bg = s.customBg || '#1a1020';
  const accent = s.customAccent || '#ff7eb6';
  const dark = lum(bg) < 0.4;
  const toward = dark ? '#ffffff' : '#000000';
  const text = dark ? mix('#ffffff', accent, 0.06) : mix('#1a1020', accent, 0.1);
  const vars = {
    '--bg': bg, '--bg-2': mix(bg, toward, 0.035), '--panel': mix(bg, toward, 0.08), '--panel-2': mix(bg, toward, 0.13), '--line': mix(bg, toward, 0.2),
    '--text': text, '--muted': mix(text, bg, 0.4), '--pink': accent, '--pink-2': dark ? mix(accent, '#ffffff', 0.35) : mix(accent, '#000000', 0.2),
    '--on-accent': lum(accent) > 0.45 ? '#1a1020' : '#ffffff',
  };
  for (const [k, v] of Object.entries(vars)) document.body.style.setProperty(k, v);
}

/* ---------- drawing skins ---------- */
function loadImage(src) {
  return new Promise((resolve, reject) => { const i = new Image(); i.onload = () => resolve(i); i.onerror = reject; i.src = src; });
}

// Slim skins leave the outer column of the arm empty.
function detectSlim(img) {
  if (img.height < 64) return false;
  const c = document.createElement('canvas');
  c.width = 64; c.height = 64;
  const ctx = c.getContext('2d');
  ctx.drawImage(img, 0, 0);
  const a = (x, y) => ctx.getImageData(x, y, 1, 1).data[3];
  return a(54, 20) === 0 && a(55, 20) === 0 && a(54, 31) === 0 && a(55, 31) === 0;
}

// Front view of the whole player, 16×32 "pixels".
function drawBody(canvas, img, variant) {
  const ctx = canvas.getContext('2d');
  canvas.width = 16; canvas.height = 32;
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, 16, 32);
  const modern = img.height >= 64;
  const aw = variant === 'slim' ? 3 : 4;
  const part = (sx, sy, w, h, dx, dy, mirror = false) => {
    if (!mirror) { ctx.drawImage(img, sx, sy, w, h, dx, dy, w, h); return; }
    ctx.save(); ctx.translate(dx + w, dy); ctx.scale(-1, 1); ctx.drawImage(img, sx, sy, w, h, 0, 0, w, h); ctx.restore();
  };
  // legs
  part(4, 20, 4, 12, 4, 20);
  if (modern) part(20, 52, 4, 12, 8, 20); else part(4, 20, 4, 12, 8, 20, true);
  // body
  part(20, 20, 8, 12, 4, 8);
  // arms (the player's right arm is on our left)
  part(44, 20, aw, 12, 4 - aw, 8);
  if (modern) part(36, 52, aw, 12, 12, 8); else part(44, 20, aw, 12, 12, 8, true);
  // head
  part(8, 8, 8, 8, 4, 0);
  // second layer (jacket, sleeves, trousers, hat)
  if (modern) {
    part(4, 36, 4, 12, 4, 20); part(4, 52, 4, 12, 8, 20);
    part(20, 36, 8, 12, 4, 8);
    part(44, 36, aw, 12, 4 - aw, 8); part(52, 52, aw, 12, 12, 8);
  }
  part(40, 8, 8, 8, 4, 0);
}

function drawFace(canvas, img) {
  const ctx = canvas.getContext('2d');
  canvas.width = 8; canvas.height = 8;
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(img, 8, 8, 8, 8, 0, 0, 8, 8);
  ctx.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8);
}

/* ---------- found skin ---------- */
async function showFound(found) {
  try {
    const img = await loadImage(found.dataUrl);
    state.found = found;
    state.variant = found.variant || (detectSlim(img) ? 'slim' : 'classic');
    $('#foundName').textContent = found.name || 'New skin';
    $('#foundSource').textContent = found.source ? `from ${found.source}` : '';
    $('#foundBlock').classList.remove('hidden');
    drawBody($('#foundCanvas'), img, state.variant);
    $$('#variant button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.variant));
    $('#foundBlock').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    status('Found a skin! Check it looks right, then click "Use this skin".');
  } catch {
    status("Couldn't show that skin.", 'err');
  }
}
$$('#variant button').forEach((b) => b.addEventListener('click', async () => {
  state.variant = b.dataset.v;
  $$('#variant button').forEach((x) => x.classList.toggle('on', x === b));
  if (state.found) drawBody($('#foundCanvas'), await loadImage(state.found.dataUrl), state.variant);
}));
$('#skip').addEventListener('click', () => { state.found = null; $('#foundBlock').classList.add('hidden'); status('Keep looking. Click Download on another skin.'); });

async function wear(payload, label) {
  if (state.busy) return;
  state.busy = true;
  $('#wear').disabled = true;
  status(`Putting on ${label}…`);
  try {
    const items = await window.cat.skins.wear(payload);
    renderHistory(items);
    status(`Done! You're wearing ${label}. Rejoin your server (or world) to see it in-game. 🐾`, 'ok');
    state.found = null;
    $('#foundBlock').classList.add('hidden');
  } catch (e) {
    status(e.message, 'err');
  } finally {
    state.busy = false;
    $('#wear').disabled = false;
  }
}
$('#wear').addEventListener('click', () => {
  if (!state.found) return;
  wear({ dataUrl: state.found.dataUrl, variant: state.variant, name: state.found.name, source: state.found.source }, state.found.name || 'your new skin');
});

/* ---------- history ---------- */
let historyRender = 0;
function renderHistory(items) {
  const mine = ++historyRender; // only the newest render may draw
  state.items = items || state.items;
  const grid = $('#history');
  grid.innerHTML = state.items.length ? '' : '<p class="empty-note">No skins yet. The skin you wear now is saved here as soon as you sign in.</p>';
  state.items.forEach((it, i) => {
    const tile = document.createElement('div');
    tile.className = `tile${i === 0 ? ' current' : ''}`;
    tile.title = `${it.name || 'Skin'}${i === 0 ? ' (wearing now)' : ''}. Click to wear.`;
    tile.innerHTML = '<canvas></canvas><button class="x" title="Remove from history">×</button>';
    loadImage(it.dataUrl).then((img) => { if (mine === historyRender) drawFace(tile.querySelector('canvas'), img); }).catch(() => {});
    tile.addEventListener('click', (e) => {
      if (e.target.closest('.x')) return;
      if (i === 0) { status("That's the skin you're wearing now."); return; }
      wear({ historyId: it.id, variant: it.variant, name: it.name }, it.name || 'that skin');
    });
    tile.querySelector('.x').addEventListener('click', async () => {
      await window.cat.skins.remove(it.id).catch(() => {});
      renderHistory(state.items.filter((x) => x.id !== it.id));
    });
    grid.append(tile);
  });
}

async function loadHistory() {
  try {
    const r = await window.cat.skins.history();
    if (!r.account) { status('Sign in to your Microsoft account in the launcher to change skins.', 'err'); return; }
    renderHistory(r.items);
    // Save the skin you're wearing right now, then refresh.
    await window.cat.skins.remember().catch(() => {});
    renderHistory((await window.cat.skins.history()).items);
  } catch (e) {
    status(e.message, 'err');
  }
}

/* ---------- search + site view ---------- */
async function setupSites() {
  const sites = { skindex: 'Skindex', namemc: 'NameMC', planet: 'Planet MC' };
  $('#sites').innerHTML = Object.entries(sites).map(([k, v]) => `<button data-site="${k}" class="${k === state.site ? 'on' : ''}">${esc(v)}</button>`).join('');
  $('#sites').addEventListener('click', (e) => {
    const b = e.target.closest('[data-site]');
    if (!b) return;
    state.site = b.dataset.site;
    $$('#sites button').forEach((x) => x.classList.toggle('on', x === b));
    window.cat.wardrobe.search(state.site, $('#q').value);
  });
}
const doSearch = () => window.cat.wardrobe.search(state.site, $('#q').value);
$('#go').addEventListener('click', doSearch);
$('#q').addEventListener('keydown', (e) => { if (e.key === 'Enter') doSearch(); });
$('#back').addEventListener('click', () => window.cat.wardrobe.nav('back'));
$('#fwd').addEventListener('click', () => window.cat.wardrobe.nav('forward'));
$('#reload').addEventListener('click', () => window.cat.wardrobe.nav('reload'));
$('#ext').addEventListener('click', () => window.cat.wardrobe.nav('external'));
window.cat.wardrobe.onNav((n) => {
  $('#where').textContent = n.loading ? `Loading ${n.host}…` : n.host;
  $('#back').disabled = !n.canBack;
  $('#fwd').disabled = !n.canForward;
});
window.cat.wardrobe.onFound(showFound);
window.cat.wardrobe.onNotice((n) => status(n.error || n.message, n.error ? 'err' : ''));

/* ---------- more ways ---------- */
async function getAndShow(fn, busyMsg) {
  status(busyMsg);
  try { const r = await fn(); if (r) showFound(r); else status(''); } catch (e) { status(e.message, 'err'); }
}
$('#useLink').addEventListener('click', () => getAndShow(() => window.cat.skins.fromUrl($('#link').value.trim()), 'Getting that skin…'));
$('#link').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#useLink').click(); });
$('#usePlayer').addEventListener('click', () => getAndShow(() => window.cat.skins.fromPlayer($('#player').value.trim()), 'Looking up that player…'));
$('#player').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#usePlayer').click(); });
$('#useFile').addEventListener('click', () => getAndShow(() => window.cat.skins.pickFile(), 'Choose a skin file…'));

/* ---------- window buttons ---------- */
$$('[data-wcmd]').forEach((b) => b.addEventListener('click', () => window.cat.wardrobe.window(b.dataset.wcmd)));
$('#pin').addEventListener('click', async () => { const on = await window.cat.wardrobe.window('pin'); $('#pin').classList.toggle('on', !!on); });

(async function boot() {
  const s = await window.cat.settings.get().catch(() => ({}));
  applyTheme(s);
  $('#pin').classList.toggle('on', !!(await window.cat.wardrobe.window('state').catch(() => false)));
  await setupSites();
  loadHistory();
  $('#q').focus();
})();
