/* Catgirl Launcher – renderer */
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const ICONS = { cat: '🐱', star: '⭐', sword: '⚔️', pick: '⛏️', heart: '💗', moon: '🌙', fish: '🐟', yarn: '🧶' };
const state = {
  info: null,
  instances: [],
  accounts: { selected: null, accounts: [] },
  running: new Set(),
  launching: new Set(),
  progress: {},
  logs: {},
  consoleInst: null,
  modsInst: null,
  modsTab: 'installed',
  modOffset: 0,
};

/* ---------- helpers ---------- */
function toast(msg, kind = '') {
  const t = document.createElement('div');
  t.className = `toast ${kind}`;
  t.textContent = msg;
  $('#toasts').append(t);
  setTimeout(() => t.remove(), kind === 'err' ? 7000 : 3500);
}
async function safe(fn, okMsg) {
  try { const r = await fn(); if (okMsg) toast(okMsg, 'ok'); return r; } catch (e) {
    toast(e.message, 'err');
    if (/Instance not found/i.test(e.message)) setTimeout(() => refreshInstances(), 0); // a stale card: redraw
    return undefined;
  }
}
function head(uuid, size = 64) { return uuid ? `https://mc-heads.net/avatar/${uuid}/${size}` : 'assets/logo.png'; }
function fmtPlay(ms) {
  if (!ms) return 'Never played';
  const h = Math.floor(ms / 3600000), m = Math.round((ms % 3600000) / 60000);
  return h ? `${h}h ${m}m played` : `${m}m played`;
}
function fmtNum(n) { return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(0) + 'k' : String(n); }

/* ---------- navigation ---------- */
function go(page) {
  $$('.page').forEach((p) => p.classList.toggle('active', p.id === `page-${page}`));
  $$('.nav').forEach((n) => n.classList.toggle('active', n.dataset.page === page));
  if (page === 'mods') renderMods();
  if (page === 'console') renderConsole();
  if (page === 'accounts') renderAccounts();
  if (page === 'settings') loadSettings();
  if (page === 'cosmetics') renderCosmetics();
}
document.addEventListener('click', (e) => {
  const nav = e.target.closest('[data-page]');
  if (nav) go(nav.dataset.page);
  const w = e.target.closest('[data-win]');
  if (w) window.cat.win[w.dataset.win]();
  const play = e.target.closest('[data-play]');
  if (play) playOrStop(play.dataset.play);
});

$('#navWardrobe').addEventListener('click', () => safe(() => window.cat.wardrobe.open()));

/* ---------- data ---------- */
async function refreshInstances() {
  state.instances = (await safe(() => window.cat.instances.list())) || [];
  renderHome();
  renderInstances();
  fillInstanceSelects();
}
async function refreshAccounts() {
  state.accounts = (await safe(() => window.cat.auth.list())) || { selected: null, accounts: [] };
  const sel = state.accounts.accounts.find((a) => a.uuid === state.accounts.selected);
  $('#accountName').textContent = sel ? sel.name : 'Not signed in';
  $('#accountHead').src = sel ? head(sel.uuid) : 'assets/logo.png';
  renderHome();
  if ($('#page-accounts').classList.contains('active')) renderAccounts();
}

/* ---------- play buttons + progress ---------- */
function playLabel(id) {
  if (state.running.has(id)) return 'Running';
  if (state.launching.has(id)) return 'Starting…';
  return 'Play';
}
function progressHtml(id) {
  const p = state.progress[id];
  if (!state.launching.has(id) || !p) return '<div class="progress hidden"><div class="bar"></div><span></span></div>';
  const pct = p.total ? Math.round((p.done / p.total) * 100) : null;
  return `<div class="progress ${pct == null ? 'indeterminate' : ''}" style="--p:${pct ?? 0}%"><div class="bar"></div><span>${esc(p.stage)}${pct != null ? ` · ${pct}%` : ''}</span></div>`;
}
function updatePlayUI(id) {
  $$(`[data-play="${CSS.escape(id)}"]`).forEach((b) => {
    b.textContent = playLabel(id);
    b.classList.toggle('running', state.running.has(id));
    b.disabled = state.launching.has(id);
    b.title = state.running.has(id) ? 'Click to force stop' : '';
  });
  $$(`[data-progress="${CSS.escape(id)}"]`).forEach((el) => { el.innerHTML = progressHtml(id); });
  if (id === state.heroId) {
    const p = state.progress[id];
    const el = $('#heroProgress');
    if (state.launching.has(id) && p) {
      const pct = p.total ? Math.round((p.done / p.total) * 100) : null;
      el.classList.remove('hidden');
      el.classList.toggle('indeterminate', pct == null);
      el.style.setProperty('--p', `${pct ?? 0}%`);
      $('span', el).textContent = p.stage + (pct != null ? ` · ${pct}%` : '');
    } else el.classList.add('hidden');
  }
}

async function playOrStop(id) {
  if (state.running.has(id)) {
    if (confirm('Force stop Minecraft? Unsaved progress may be lost.')) window.cat.launch.kill(id);
    return;
  }
  if (!state.accounts.selected) { toast('Sign in with your Microsoft account first.', 'err'); go('accounts'); return; }
  state.launching.add(id);
  state.progress[id] = { stage: 'Getting ready', done: 0, total: 0 };
  state.logs[id] = [];
  state.consoleInst = id;
  updatePlayUI(id);
  try {
    await window.cat.launch.start(id);
  } catch (e) {
    state.launching.delete(id);
    toast(e.message, 'err');
    pushLog(id, `[Catgirl] Launch failed: ${e.message}`);
    if (/Instance not found/i.test(e.message)) refreshInstances();
  }
  updatePlayUI(id);
}

window.cat.launch.onProgress((p) => { state.progress[p.instId] = p; updatePlayUI(p.instId); });
window.cat.launch.onStarted(({ instId }) => {
  state.launching.delete(instId);
  state.running.add(instId);
  updatePlayUI(instId);
  toast('Minecraft is starting. Have fun! 🐾', 'ok');
  refreshInstances();
});
window.cat.launch.onExit(({ instId, code, error }) => {
  state.launching.delete(instId);
  state.running.delete(instId);
  updatePlayUI(instId);
  if (error) toast(`Couldn't start Java: ${error}`, 'err');
  else if (code !== 0 && code !== null) { toast(`Minecraft closed with an error (code ${code}). Check the Console.`, 'err'); }
  refreshInstances();
});
window.cat.launch.onLog(({ instId, line }) => pushLog(instId, line));

function pushLog(id, line) {
  const log = (state.logs[id] ||= []);
  log.push(line);
  if (log.length > 5000) log.splice(0, log.length - 5000);
  if (state.consoleInst === id && $('#page-console').classList.contains('active')) appendConsoleLine(line);
}

/* ---------- home ---------- */
function instanceCard(inst) {
  const icon = inst.iconUrl ? `<img src="${esc(inst.iconUrl)}" alt="" />` : ICONS[inst.icon] || '🐱';
  const loader = inst.loader === 'fabric' ? 'Fabric' : 'Vanilla';
  return `<div class="card" data-inst="${esc(inst.id)}">
    <div class="card-top">
      <div class="inst-icon ${inst.featured ? 'featured' : ''}">${icon}</div>
      <div style="min-width:0">
        <h3 title="${esc(inst.name)}">${esc(inst.name)}</h3>
        <div class="meta"><span class="tag">${esc(inst.mcVersion)}</span><span class="tag">${loader}</span></div>
      </div>
    </div>
    <div class="meta">${fmtPlay(inst.playTimeMs)}${inst.joinServer ? ` · joins ${esc(inst.joinServer)}` : ''}</div>
    <div data-progress="${esc(inst.id)}">${progressHtml(inst.id)}</div>
    <div class="card-actions">
      <button class="primary" data-play="${esc(inst.id)}">${playLabel(inst.id)}</button>
      <button class="ghost" data-edit="${esc(inst.id)}" title="Edit">✎</button>
    </div>
  </div>`;
}

function renderHome() {
  const acc = state.accounts.accounts.find((a) => a.uuid === state.accounts.selected);
  const hero = [...state.instances].sort((a, b) => (b.lastPlayed || 0) - (a.lastPlayed || 0))[0];
  state.heroId = hero?.id || null;
  const play = $('#heroPlay');
  $('#heroName').textContent = acc ? `Hi, ${acc.name}!` : 'Welcome!';
  if (hero) {
    $('#heroPill').textContent = hero.lastPlayed ? 'Continue playing' : 'Ready to play';
    $('#heroTagline').textContent = hero.name;
    $('#heroMeta').innerHTML = `<span class="tag">${esc(hero.mcVersion)}</span><span class="tag">${hero.loader === 'fabric' ? 'Fabric' : 'Vanilla'}</span>${hero.joinServer ? `<span class="muted">joins ${esc(hero.joinServer)}</span>` : ''}<span class="muted">${fmtPlay(hero.playTimeMs)}</span>`;
    play.dataset.play = hero.id;
    updatePlayUI(hero.id);
    renderHeroServer(hero);
  } else {
    renderHeroServer(null);
    $('#heroPill').textContent = 'Catgirl Launcher';
    $('#heroTagline').textContent = 'Create an instance to start playing any Minecraft version.';
    $('#heroMeta').innerHTML = '';
    delete play.dataset.play;
    play.textContent = 'New instance';
    play.disabled = false;
    play.classList.remove('running');
    $('#heroProgress').classList.add('hidden');
  }

  const rest = state.instances.filter((i) => i.id !== state.heroId).slice(0, 4);
  $('#recentInstances').innerHTML = rest.length
    ? rest.map(instanceCard).join('')
    : `<div class="empty">${hero ? 'Make more instances for other versions or modpacks.' : 'No instances yet.'} <button class="link" id="emptyNew">Create one</button></div>`;
}
$('#heroPlay').addEventListener('click', () => { if (!state.heroId) instanceModal(); });

// The big picture on the home screen shows the last server you played on (its real icon,
// MOTD and player count). Without a server it shows the Catgirl mascot.
const pingCache = new Map();
async function renderHeroServer(inst) {
  const art = $('.hero-art');
  const motd = $('#heroMotd');
  const address = inst && (inst.lastServer || inst.joinServer);
  if (!address) {
    art.className = 'hero-art';
    art.innerHTML = '';
    motd.classList.add('hidden');
    return;
  }
  const key = address.toLowerCase();
  let entry = pingCache.get(key);
  if (!entry || Date.now() - entry.at > 60000) {
    entry = { at: Date.now(), promise: window.cat.ping(address).catch(() => null) };
    pingCache.set(key, entry);
  }
  const info = await entry.promise;
  if (state.heroId !== inst.id) return; // hero changed while waiting
  art.className = 'hero-art server';
  const online = info && info.online != null;
  art.innerHTML = `<img src="${info?.favicon || 'assets/logo.png'}" alt="" />
    <span class="online ${online ? '' : 'off'}">${online ? `● ${info.online}/${info.max} online` : 'Offline'}</span>`;
  motd.textContent = `${address}${info?.motd ? `\n${info.motd}` : ''}`;
  motd.classList.remove('hidden');
}
window.cat.launch.onServer(({ instId, server }) => {
  const inst = state.instances.find((i) => i.id === instId);
  if (inst) { inst.lastServer = server; if (state.heroId === instId) renderHeroServer(inst); }
});
document.addEventListener('click', (e) => {
  if (e.target.id === 'emptyNew') instanceModal();
  const ed = e.target.closest('[data-edit]');
  if (ed) instanceModal(state.instances.find((i) => i.id === ed.dataset.edit));
});

/* ---------- instances ---------- */
function renderInstances() {
  $('#instanceGrid').innerHTML = state.instances.length ? state.instances.map(instanceCard).join('') : '<div class="empty">No instances yet.</div>';
}
$('#newInstanceBtn').addEventListener('click', () => instanceModal());

function openModal(html) {
  $('#modal .modal').classList.remove('wide');
  $('#modalBody').innerHTML = html;
  $('#modal').classList.remove('hidden');
}
function closeModal() {
  $('#modal').classList.add('hidden');
  $('#modalBody').innerHTML = '';
  if (state.loginPending) { window.cat.auth.cancel(); state.loginPending = false; }
}
$('#modalClose').addEventListener('click', closeModal);
$('#modal').addEventListener('mousedown', (e) => { if (e.target.id === 'modal') closeModal(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#modal').classList.contains('hidden')) closeModal(); });

async function instanceModal(existing = null) {
  const editing = !!existing;
  const inst = existing || { name: '', mcVersion: '', loader: 'fabric', loaderVersion: null, memoryMB: 4096, joinServer: '', icon: 'cat', javaArgs: '' };
  openModal(`
    <h3>${editing ? 'Edit instance' : 'New instance'}</h3>
    ${editing ? '' : '<button class="ghost" id="iPack" style="width:100%;margin:-4px 0 16px">📦 Install a modpack instead</button>'}
    <div class="field"><label>Name</label><input id="iName" value="${esc(inst.name)}" placeholder="My survival world" maxlength="40" /></div>
    <div class="field"><label>Icon</label><div class="seg" id="iIcon">${Object.entries(ICONS).map(([k, v]) => `<button data-v="${k}" class="${inst.icon === k ? 'on' : ''}">${v}</button>`).join('')}</div></div>
    <div class="field"><label>Minecraft version</label><select id="iVersion"><option>Loading…</option></select></div>
    <div class="field"><label>Mod loader</label><div class="seg" id="iLoader">
      <button data-v="vanilla" class="${inst.loader === 'vanilla' ? 'on' : ''}">Vanilla</button>
      <button data-v="fabric" class="${inst.loader === 'fabric' ? 'on' : ''}">Fabric</button></div></div>
    <div class="field ${inst.loader === 'fabric' ? '' : 'hidden'}" id="iLoaderVerWrap"><label>Fabric loader <small>Latest stable is picked automatically</small></label><select id="iLoaderVer"><option value="">Latest stable</option></select></div>
    <div class="field"><label>Memory (RAM) <small id="iMemHint"></small></label>
      <div class="mem-presets" id="iMemPresets"></div>
      <div class="range-row"><input type="range" id="iMem" min="1024" step="512" /><span class="mem-box"><input type="number" id="iMemGb" min="1" step="0.5" /> GB</span></div>
      <small class="mem-warn hidden" id="iMemWarn"></small>
    </div>
    <div class="field"><label>Join a server on launch <small>Optional, e.g. play.example.net</small></label><input id="iServer" value="${esc(inst.joinServer)}" /></div>
    <div class="field"><label class="check"><input type="checkbox" id="iShare" ${inst.shareSettings === false ? '' : 'checked'} /> Use my shared Minecraft settings</label></div>
    <div class="field"><label>Java arguments <small>Optional</small></label><input id="iArgs" value="${esc(inst.javaArgs || '')}" placeholder="-XX:+UseG1GC" /></div>
    <div class="actions">
      ${editing ? '<button class="ghost" id="iFolder">Open folder</button><button class="danger" id="iDelete">Delete</button>' : ''}
      <span class="spacer" style="flex:1"></span>
      <button class="primary" id="iSave">${editing ? 'Save' : 'Create'}</button>
    </div>`);

  $('#iPack')?.addEventListener('click', modpackModal);
  let loader = inst.loader, icon = inst.icon;
  // Memory: the slider goes up to what this PC has (leaving 2 GB for Windows), with quick picks.
  const totalMB = state.info?.totalMemMB || 16384;
  const maxMB = Math.max(4096, Math.floor((totalMB - 2048) / 512) * 512);
  const totalGB = Math.round(totalMB / 1024);
  $('#iMem').max = String(maxMB);
  $('#iMemHint').textContent = `This PC has ${totalGB} GB. Most modpacks run well with 6–8 GB; huge modpacks and shaders like 10–16 GB.`;
  $('#iMemPresets').innerHTML = [2, 4, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64]
    .filter((g) => g * 1024 <= maxMB)
    .map((g) => `<button type="button" data-gb="${g}">${g} GB</button>`).join('');
  const setMem = (mb) => {
    mb = Math.max(1024, Math.min(maxMB, Math.round(mb / 512) * 512));
    $('#iMem').value = String(mb);
    $('#iMemGb').value = String(+(mb / 1024).toFixed(1));
    $$('#iMemPresets button').forEach((b) => b.classList.toggle('on', +b.dataset.gb * 1024 === mb));
    const warn = $('#iMemWarn');
    const share = mb / totalMB;
    warn.classList.toggle('hidden', share <= 0.6);
    warn.textContent = share > 0.75
      ? `⚠️ That's most of your PC's memory. Windows and Discord may slow down. Try ${Math.max(4, Math.floor(totalGB * 0.5))} GB or less.`
      : `Heads up: that's over half your PC's memory. Fine if Minecraft is the only big thing running.`;
  };
  const memLabel = () => setMem(+$('#iMem').value);
  $('#iMemPresets').addEventListener('click', (e) => { const b = e.target.closest('[data-gb]'); if (b) setMem(+b.dataset.gb * 1024); });
  $('#iMemGb').addEventListener('change', (e) => setMem((parseFloat(e.target.value) || 4) * 1024));
  setMem(inst.memoryMB || 4096);
  memLabel();
  $('#iMem').addEventListener('input', memLabel);
  $$('#iIcon button').forEach((b) => b.addEventListener('click', () => { icon = b.dataset.v; $$('#iIcon button').forEach((x) => x.classList.toggle('on', x === b)); }));
  $$('#iLoader button').forEach((b) => b.addEventListener('click', () => {
    loader = b.dataset.v;
    $$('#iLoader button').forEach((x) => x.classList.toggle('on', x === b));
    $('#iLoaderVerWrap').classList.toggle('hidden', loader !== 'fabric');
    loadLoaders();
  }));

  const loadLoaders = async () => {
    const sel = $('#iLoaderVer');
    if (!sel || loader !== 'fabric') return;
    sel.innerHTML = '<option value="">Latest stable</option>';
    const list = await safe(() => window.cat.versions.fabric($('#iVersion').value));
    if (!list) return;
    if (!list.length) { sel.innerHTML = '<option value="">Fabric doesn\'t support this version</option>'; return; }
    sel.innerHTML += list.slice(0, 40).map((l) => `<option value="${esc(l.version)}" ${l.version === inst.loaderVersion ? 'selected' : ''}>${esc(l.version)}${l.stable ? '' : ' (beta)'}</option>`).join('');
  };

  const versions = await safe(() => window.cat.versions.list());
  if (!$('#iVersion')) return;
  if (versions) {
    const ids = versions.versions.map((v) => v.id);
    if (inst.mcVersion && !ids.includes(inst.mcVersion)) ids.unshift(inst.mcVersion);
    $('#iVersion').innerHTML = ids.map((id) => `<option ${id === (inst.mcVersion || versions.latest.release) ? 'selected' : ''}>${esc(id)}</option>`).join('');
  } else {
    $('#iVersion').innerHTML = inst.mcVersion ? `<option>${esc(inst.mcVersion)}</option>` : '<option value="">Offline: can\'t load versions</option>';
  }
  $('#iVersion').addEventListener('change', loadLoaders);
  loadLoaders();

  if (editing) {
    $('#iFolder').addEventListener('click', () => window.cat.instances.openFolder(inst.id));
    $('#iDelete')?.addEventListener('click', async () => {
      if (!confirm(`Delete "${inst.name}" and all its worlds, mods and settings? This can't be undone.`)) return;
      try {
        await window.cat.instances.remove(inst.id);
        toast(`${inst.name} deleted`, 'ok');
        closeModal();
      } catch (e) {
        toast(e.message, 'err');
        if (/not found/i.test(e.message)) closeModal(); // it was already gone
      }
      refreshInstances(); // always redraw so no stale card is left behind
    });
  }
  $('#iSave').addEventListener('click', async () => {
    const data = {
      name: $('#iName').value.trim(),
      mcVersion: $('#iVersion').value,
      loader,
      loaderVersion: loader === 'fabric' ? $('#iLoaderVer').value || null : null,
      memoryMB: +$('#iMem').value,
      icon,
      javaArgs: $('#iArgs').value.trim(),
      shareSettings: $('#iShare').checked,
    };
    data.joinServer = $('#iServer').value.trim();
    if (!data.name) return toast('Give your instance a name.', 'err');
    if (!data.mcVersion) return toast('Pick a Minecraft version.', 'err');
    const versionChanged = editing && (data.mcVersion !== inst.mcVersion || data.loader !== inst.loader);
    const r = editing ? await safe(() => window.cat.instances.update(inst.id, data), 'Saved') : await safe(() => window.cat.instances.create(data), 'Instance created');
    if (!r) return;
    closeModal();
    refreshInstances();
    if (versionChanged && data.loader === 'fabric') migrateMods(r);
  });
}

// After a version change, swap every Modrinth mod for the build made for the new version.
async function migrateMods(inst) {
  const mods = (await safe(() => window.cat.mods.list(inst.id))) || [];
  if (!mods.some((m) => m.projectId)) return;
  toast(`Updating ${inst.name}'s mods for Minecraft ${inst.mcVersion}…`);
  const r = await safe(() => window.cat.mods.migrate(inst.id));
  if (!r) return;
  const parts = [];
  if (r.updated.length) parts.push(`${r.updated.length} updated`);
  if (r.unchanged.length) parts.push(`${r.unchanged.length} already fine`);
  if (r.disabled.length) parts.push(`${r.disabled.length} disabled (no ${inst.mcVersion} version yet: ${r.disabled.join(', ')})`);
  toast(`Mods for ${inst.mcVersion}: ${parts.join(', ')}`, r.disabled.length ? '' : 'ok');
  if ($('#page-mods').classList.contains('active')) renderMods();
}

/* ---------- modpacks ---------- */
let packSearchId = 0;
async function modpackModal() {
  openModal(`
    <h3>Install a modpack</h3>
    <p class="muted" style="margin:-8px 0 12px">Fabric modpacks from Modrinth. Every mod comes in the exact version the pack was made for.</p>
    <input id="packSearch" class="search" placeholder="Search modpacks… (Fabulously Optimized, Cobblemon, Simply Optimized)" />
    <div class="pack-list" id="packResults"><div class="empty"><span class="spinner"></span>Loading…</div></div>`);
  $('#modal .modal').classList.add('wide');
  let timer = null;
  const run = async () => {
    const q = $('#packSearch')?.value.trim() || '';
    const myId = ++packSearchId;
    const r = await safe(() => window.cat.packs.search(q, 0));
    if (myId !== packSearchId || !$('#packResults')) return;
    if (!r) { $('#packResults').innerHTML = '<div class="empty">Couldn\'t reach Modrinth.</div>'; return; }
    $('#packResults').innerHTML = r.hits.map((h) => `
      <div class="mod">
        ${h.icon ? `<img src="${esc(h.icon)}" alt="" loading="lazy" />` : '<div class="ph"></div>'}
        <div class="info"><b>${esc(h.title)}</b><small>${esc(h.description)}</small><small>by ${esc(h.author)} · ${fmtNum(h.downloads)} downloads${h.versions.length ? ` · MC ${esc(h.versions[h.versions.length - 1])}` : ''}</small></div>
        <button class="primary small" data-pack="${esc(h.id)}">Install</button>
      </div>`).join('') || '<div class="empty">No modpacks found.</div>';
  };
  $('#packSearch').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 300); });
  $('#packResults').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-pack]');
    if (!b) return;
    $$('[data-pack]').forEach((x) => { x.disabled = true; });
    b.innerHTML = '<span class="spinner"></span>Starting';
    state.packBtn = b;
    const inst = await safe(() => window.cat.packs.install(b.dataset.pack));
    state.packBtn = null;
    if (inst) {
      closeModal();
      toast(`${inst.name} is installed. Press Play!`, 'ok');
      refreshInstances();
    } else {
      $$('[data-pack]').forEach((x) => { x.disabled = false; });
      b.textContent = 'Install';
    }
  });
  run();
}
window.cat.packs.onProgress(({ stage, done, total }) => {
  if (!state.packBtn) return;
  state.packBtn.innerHTML = `<span class="spinner"></span>${esc(stage)}${total ? ` ${Math.round((done / total) * 100)}%` : ''}`;
});

/* ---------- mods ---------- */
function fillInstanceSelects() {
  const opts = state.instances.map((i) => `<option value="${esc(i.id)}">${esc(i.name)} (${esc(i.mcVersion)}${i.loader === 'fabric' ? ' Fabric' : ''})</option>`).join('');
  for (const [sel, key] of [['#modsInstance', 'modsInst'], ['#consoleInstance', 'consoleInst']]) {
    $(sel).innerHTML = opts;
    if (!state[key] || !state.instances.some((i) => i.id === state[key])) state[key] = state.instances[0]?.id || null;
    $(sel).value = state[key] || '';
  }
}
$('#modsInstance').addEventListener('change', (e) => { state.modsInst = e.target.value; renderMods(); });
$$('.tab').forEach((t) => t.addEventListener('click', () => {
  state.modsTab = t.dataset.tab;
  $$('.tab').forEach((x) => x.classList.toggle('active', x === t));
  renderMods();
}));

async function renderMods() {
  const inst = state.instances.find((i) => i.id === state.modsInst);
  const browse = state.modsTab === 'browse';
  $('#modsInstalled').classList.toggle('hidden', browse);
  $('#modsBrowse').classList.toggle('hidden', !browse);
  if (!inst) { $('#modsInstalled').innerHTML = '<div class="empty">Create an instance first.</div>'; return; }
  if (inst.loader === 'vanilla') {
    const msg = '<div class="empty">This instance is Vanilla. Switch it to Fabric (✎ on the instance) to use mods.</div>';
    $('#modsInstalled').innerHTML = msg; $('#modResults').innerHTML = msg; return;
  }
  if (browse) return searchMods(true);
  const list = (await safe(() => window.cat.mods.list(inst.id))) || [];
  $('#modsInstalled').innerHTML = list.length ? list.map((m) => `
    <div class="mod ${m.enabled ? '' : 'disabled'}">
      <div class="ph"></div>
      <div class="info"><b>${esc(m.title)}</b><small>${esc(m.version || m.file)}</small></div>
      <button class="ghost small" data-toggle="${esc(m.file)}">${m.enabled ? 'Disable' : 'Enable'}</button>
      <button class="danger small" data-rmmod="${esc(m.file)}">Remove</button>
    </div>`).join('') : '<div class="empty">No mods installed. Find some under Browse Modrinth.</div>';
}
document.addEventListener('click', async (e) => {
  const t = e.target.closest('[data-toggle]');
  if (t) { await safe(() => window.cat.mods.toggle(state.modsInst, t.dataset.toggle)); renderMods(); }
  const r = e.target.closest('[data-rmmod]');
  if (r) { await safe(() => window.cat.mods.remove(state.modsInst, r.dataset.rmmod), 'Mod removed'); renderMods(); }
  const ins = e.target.closest('[data-install]');
  if (ins) {
    ins.disabled = true; ins.innerHTML = '<span class="spinner"></span>Installing';
    const got = await safe(() => window.cat.mods.install(state.modsInst, ins.dataset.install));
    if (got) { ins.textContent = 'Installed'; toast(got.length > 1 ? `Installed ${got.join(', ')}` : 'Installed', 'ok'); }
    else { ins.disabled = false; ins.textContent = 'Install'; }
  }
});

let searchTimer = null;
$('#modSearch').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => searchMods(true), 300); });
$('#modSearch').addEventListener('keydown', (e) => { if (e.key === 'Enter') { clearTimeout(searchTimer); searchMods(true); } });
$('#modMore').addEventListener('click', () => searchMods(false));

let modSearchId = 0;
async function searchMods(reset) {
  const myId = ++modSearchId; // only the newest search may show its results
  if (reset) { state.modOffset = 0; $('#modResults').innerHTML = '<div class="empty"><span class="spinner"></span>Searching Modrinth…</div>'; }
  const q = $('#modSearch').value.trim();
  const r = await safe(() => window.cat.mods.search(state.modsInst, q, state.modOffset));
  if (myId !== modSearchId) return; // a newer search started while this one was loading
  if (!r) { $('#modResults').innerHTML = '<div class="empty">Couldn\'t reach Modrinth.</div>'; return; }
  const html = r.hits.map((h) => `
    <div class="mod">
      ${h.icon ? `<img src="${esc(h.icon)}" alt="" loading="lazy" />` : '<div class="ph"></div>'}
      <div class="info"><b>${esc(h.title)}</b><small>${esc(h.description)}</small><small>by ${esc(h.author)} · ${fmtNum(h.downloads)} downloads</small></div>
      <button class="${h.installed ? 'ghost' : 'primary'} small" data-install="${esc(h.id)}" ${h.installed ? 'disabled' : ''}>${h.installed ? 'Installed' : 'Install'}</button>
    </div>`).join('');
  if (reset) $('#modResults').innerHTML = html || '<div class="empty">No mods found for this version.</div>';
  else $('#modResults').insertAdjacentHTML('beforeend', html);
  state.modOffset += r.hits.length;
  $('#modMore').classList.toggle('hidden', state.modOffset >= r.total);
}

/* ---------- console ---------- */
function lineClass(l) { return l.startsWith('[Catgirl]') ? 'cat' : /\b(ERROR|FATAL|Exception)\b/.test(l) ? 'err' : /\bWARN\b/.test(l) ? 'warn' : ''; }
function appendConsoleLine(line) {
  const out = $('#consoleOut');
  const atBottom = out.scrollHeight - out.scrollTop - out.clientHeight < 40;
  const span = document.createElement('span');
  span.className = lineClass(line);
  span.textContent = line + '\n';
  out.append(span);
  if (atBottom) out.scrollTop = out.scrollHeight;
}
function renderConsole() {
  $('#consoleInstance').value = state.consoleInst || '';
  const out = $('#consoleOut');
  out.innerHTML = '';
  const lines = state.logs[state.consoleInst] || [];
  if (!lines.length) out.textContent = 'Nothing here yet. Launch the instance and its log appears here.';
  else for (const l of lines) appendConsoleLine(l);
  out.scrollTop = out.scrollHeight;
}
$('#consoleInstance').addEventListener('change', (e) => { state.consoleInst = e.target.value; renderConsole(); });
$('#consoleCopy').addEventListener('click', () => safe(() => window.cat.copy((state.logs[state.consoleInst] || []).join('\n')), 'Log copied'));
$('#consoleKill').addEventListener('click', () => { if (state.running.has(state.consoleInst)) window.cat.launch.kill(state.consoleInst); else toast('That instance isn\'t running.'); });

/* ---------- accounts ---------- */
function renderAccounts() {
  const { accounts, selected } = state.accounts;
  $('#accountList').innerHTML = accounts.length ? accounts.map((a) => `
    <div class="account ${a.uuid === selected ? 'selected' : ''}">
      <img src="${head(a.uuid)}" alt="" />
      <div class="info"><b>${esc(a.name)}</b><br /><small>${esc(a.uuid)}</small></div>
      ${a.uuid === selected ? '<span class="tag">Active</span>' : `<button class="ghost small" data-useacc="${esc(a.uuid)}">Use</button>`}
      <button class="danger small" data-rmacc="${esc(a.uuid)}">Remove</button>
    </div>`).join('') : '<div class="empty">No accounts yet. Add your Microsoft account to play.</div>';
}
document.addEventListener('click', async (e) => {
  const u = e.target.closest('[data-useacc]');
  if (u) { await safe(() => window.cat.auth.select(u.dataset.useacc)); refreshAccounts(); }
  const r = e.target.closest('[data-rmacc]');
  if (r && confirm('Remove this account from the launcher?')) { await safe(() => window.cat.auth.remove(r.dataset.rmacc)); refreshAccounts(); }
});

$('#addAccountBtn').addEventListener('click', addAccount);
async function addAccount() {
  openModal('<h3>Sign in with Microsoft</h3><p class="muted"><span class="spinner"></span>Getting a login code…</p>');
  const d = await safe(() => window.cat.auth.start());
  if (!d) { closeModal(); return; }
  openModal(`
    <h3>Sign in with Microsoft</h3>
    <p class="muted">Open <b>${esc(d.verificationUri)}</b> and enter this code:</p>
    <div class="code-box">${esc(d.userCode)}</div>
    <div class="actions" style="justify-content:center">
      <button class="ghost" id="copyCode">Copy code</button>
      <button class="primary" id="openLink">Open login page</button>
    </div>
    <p class="muted" style="text-align:center;margin-top:18px"><span class="spinner"></span>Waiting for you to finish signing in…</p>`);
  $('#copyCode').addEventListener('click', () => safe(() => window.cat.copy(d.userCode), 'Code copied'));
  $('#openLink').addEventListener('click', () => { window.cat.copy(d.userCode); window.cat.openExternal(d.verificationUri); });
  state.loginPending = true;
  try {
    const acc = await window.cat.auth.poll({ deviceCode: d.deviceCode, interval: d.interval });
    state.loginPending = false;
    closeModal();
    toast(`Welcome, ${acc.name}! 🐾`, 'ok');
    refreshAccounts();
  } catch (e) {
    if (state.loginPending) { state.loginPending = false; closeModal(); toast(e.message, 'err'); }
  }
}

/* ---------- look & feel: themes, colours, fonts, backgrounds, menu ---------- */
const THEMES = [
  { id: 'sakura', name: 'Sakura', dots: ['#170d1c', '#ff7eb6', '#c77dff'] },
  { id: 'lavender', name: 'Lavender', dots: ['#13111d', '#b69cff', '#7f9cff'] },
  { id: 'midnight', name: 'Midnight', dots: ['#0b0e1a', '#7aa2ff', '#c77dff'] },
  { id: 'strawberry', name: 'Strawberry', dots: ['#1a0c10', '#ff5c7a', '#ffb36b'] },
  { id: 'mint', name: 'Mint', dots: ['#0c1613', '#6fe3b5', '#7ec8ff'] },
  { id: 'peach', name: 'Peach', dots: ['#1c120e', '#ff9f7a', '#ff7eb6'] },
  { id: 'ocean', name: 'Ocean', dots: ['#08151c', '#4fd1e8', '#7f9cff'] },
  { id: 'sunset', name: 'Sunset', dots: ['#1a0f1c', '#ff8a5c', '#c86bff'] },
  { id: 'neon', name: 'Neon', dots: ['#07060f', '#ff3fd1', '#2ee6ff'] },
  { id: 'mocha', name: 'Mocha', dots: ['#17110f', '#e8a87c', '#d98fb0'] },
  { id: 'cottoncandy', name: 'Cotton Candy', dots: ['#fff4f9', '#ff5fa2', '#9b6bff'] },
  { id: 'snow', name: 'Snow', dots: ['#f6f4ff', '#8b6bff', '#ff7eb6'] },
  { id: 'custom', name: 'Custom…', dots: ['#000', '#ff7eb6', '#c77dff'] },
];
const ACCENT_PRESETS = ['#ff7eb6', '#ff5c7a', '#ff9f7a', '#ffd166', '#6fe3b5', '#4fd1e8', '#7aa2ff', '#b69cff', '#c77dff', '#ff3fd1', '#ffffff'];
const FONTS = [
  { id: 'default', name: 'Default (Segoe UI)', css: '"Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif' },
  { id: 'fredoka', name: 'Fredoka (round & cute)', css: '"Fredoka", "Segoe UI", sans-serif' },
  { id: 'nunito', name: 'Nunito (soft)', css: '"Nunito", "Segoe UI", sans-serif' },
  { id: 'quicksand', name: 'Quicksand (light)', css: '"Quicksand", "Segoe UI", sans-serif' },
  { id: 'comfortaa', name: 'Comfortaa (bubbly)', css: '"Comfortaa", "Segoe UI", sans-serif' },
  { id: 'baloo', name: 'Baloo 2 (chunky)', css: '"Baloo 2", "Segoe UI", sans-serif' },
  { id: 'pixel', name: 'Pixelify Sans (pixel)', css: '"Pixelify Sans", "Segoe UI", sans-serif' },
  { id: 'comic', name: 'Comic Sans MS', css: '"Comic Sans MS", "Comic Sans", "Segoe UI", sans-serif' },
  { id: 'installed', name: 'A font on my PC…', css: null },
  { id: 'file', name: 'A font file…', css: '"CatgirlCustomFont", "Segoe UI", sans-serif' },
];
const BACKGROUNDS = [
  { id: 'none', name: 'None' },
  { id: 'sakura-night', name: 'Sakura night', url: 'assets/bg/sakura-night.svg' },
  { id: 'catgirl', name: 'Catgirl', url: 'assets/bg/catgirl.svg' },
  { id: 'starry', name: 'Cat stars', url: 'assets/bg/starry.svg' },
  { id: 'paws', name: 'Paws', url: 'assets/bg/paws.svg' },
  { id: 'custom', name: 'My picture', url: null },
];
state.look = {};
state.customBgUrl = null;

/* colour maths for the custom theme */
const hexToRgb = (h) => { const n = parseInt(h.replace('#', ''), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const rgbToHex = (r) => '#' + r.map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
const mix = (a, b, t) => rgbToHex(hexToRgb(a).map((v, i) => v + (hexToRgb(b)[i] - v) * t));
const lum = (h) => { const [r, g, b] = hexToRgb(h).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };

function customThemeVars(bg, accent) {
  const dark = lum(bg) < 0.4;
  const toward = dark ? '#ffffff' : '#000000';
  const text = dark ? mix('#ffffff', accent, 0.06) : mix('#1a1020', accent, 0.1);
  return {
    '--bg': bg, '--bg-2': mix(bg, toward, 0.035), '--panel': mix(bg, toward, 0.08), '--panel-2': mix(bg, toward, 0.13), '--line': mix(bg, toward, 0.2),
    '--text': text, '--muted': mix(text, bg, 0.4),
    '--pink': accent, '--pink-2': dark ? mix(accent, '#ffffff', 0.35) : mix(accent, '#000000', 0.2),
    '--lilac': mix(accent, '#7f9cff', 0.55), '--on-accent': lum(accent) > 0.45 ? '#1a1020' : '#ffffff',
    '--hero-a': mix(bg, accent, dark ? 0.35 : 0.3), '--hero-b': mix(bg, accent, 0.1), '--hero-c': bg,
    '--console': dark ? mix(bg, '#000000', 0.4) : '#1d1630', '--console-text': dark ? mix(text, bg, 0.15) : '#e2dbfa',
    '--backdrop': dark ? 'rgba(0,0,0,.6)' : 'rgba(40,30,60,.3)',
  };
}

function currentAccent(look) {
  if (look.theme === 'custom') return look.customAccent || '#ff7eb6';
  return (THEMES.find((t) => t.id === look.theme) || THEMES[0]).dots[1];
}

function applyLook(look) {
  const root = document.documentElement;
  const body = document.body;
  // theme
  const theme = THEMES.some((t) => t.id === look.theme) ? look.theme : 'sakura';
  body.dataset.theme = theme;
  const custom = customThemeVars(look.customBg || '#1a1020', look.customAccent || '#ff7eb6');
  for (const k of Object.keys(custom)) body.style.removeProperty(k);
  if (theme === 'custom') for (const [k, v] of Object.entries(custom)) body.style.setProperty(k, v);
  $$('.theme-swatch').forEach((b) => b.classList.toggle('on', b.dataset.theme === theme));
  $('#customColorsField').classList.toggle('hidden', theme !== 'custom');
  // font
  const f = FONTS.find((x) => x.id === look.font) || FONTS[0];
  let css = f.css;
  if (f.id === 'installed') css = look.fontName ? `"${look.fontName.replace(/["\\]/g, '')}", "Segoe UI", sans-serif` : FONTS[0].css;
  root.style.setProperty('--font', css);
  root.style.setProperty('--display', css);
  // background
  const bg = BACKGROUNDS.find((x) => x.id === look.background) || BACKGROUNDS[0];
  const url = bg.id === 'custom' ? state.customBgUrl : bg.url;
  const on = bg.id !== 'none' && !!url;
  body.classList.toggle('has-bg', on);
  $('#appBg').style.backgroundImage = on ? `url("${url}")` : 'none';
  $('#appBg').style.filter = on && look.bgBlur ? `blur(${look.bgBlur}px)` : 'none';
  $('#appBg').style.inset = on && look.bgBlur ? `-${look.bgBlur * 2}px` : '0';
  $('#appBgDim').style.opacity = on ? String((look.bgDim ?? 55) / 100) : '0';
  $$('.bg-tile').forEach((t) => t.classList.toggle('on', t.dataset.bg === bg.id));
  // launcher menu layout
  const pos = ['left', 'right', 'top', 'bottom'].includes(look.navPosition) ? look.navPosition : 'left';
  body.classList.remove('nav-left', 'nav-right', 'nav-top', 'nav-bottom');
  body.classList.add(`nav-${pos}`);
  body.classList.toggle('nav-icons', !!look.navIcons);
}

async function setLook(change) {
  Object.assign(state.look, change);
  applyLook(state.look);
  syncLookControls();
  await safe(() => window.cat.settings.set({ ...change, accentHex: currentAccent(state.look) }));
}

function syncLookControls() {
  const l = state.look;
  $('#customBg').value = l.customBg || '#1a1020';
  $('#customAccent').value = l.customAccent || '#ff7eb6';
  $('#setFont').value = l.font || 'default';
  $('#fontNameRow').classList.toggle('hidden', l.font !== 'installed');
  $('#fontFileRow').classList.toggle('hidden', l.font !== 'file');
  $('#setFontName').value = l.fontName || '';
  $('#bgDim').value = l.bgDim ?? 55; $('#bgDimVal').textContent = `${l.bgDim ?? 55}%`;
  $('#bgBlur').value = l.bgBlur ?? 0; $('#bgBlurVal').textContent = `${l.bgBlur ?? 0}px`;
  $$('#navPos button').forEach((b) => b.classList.toggle('on', b.dataset.v === (l.navPosition || 'left')));
  $('#navIcons').checked = !!l.navIcons;
  $$('#menuPos button').forEach((b) => b.classList.toggle('on', b.dataset.v === (l.menuPosition || 'right')));
  $('#menuIcons').checked = !!l.menuIconsOnly;
  $('#setSplashes').checked = l.splashes !== false;
  $('#setCapePictures').checked = l.showCapePictures !== false;
  $('#setShareOptions').checked = l.shareOptions !== false;
  $('#setShareServers').checked = l.shareServers !== false;
  $('#setShareServers').disabled = l.shareOptions === false;
  $('#setDiscord').checked = l.discordPresence !== false;
  $('#setDiscordServer').checked = l.discordShowServer !== false;
  $('#setDiscordServer').disabled = l.discordPresence === false;
}

// build the pickers
$('#themePicker').innerHTML = THEMES.map((t) => `<button class="theme-swatch ${t.id === 'custom' ? 'custom' : ''}" data-theme="${t.id}"><div class="dots">${t.dots.map((c) => `<i style="background:${c}"></i>`).join('')}</div><span>${t.name}</span></button>`).join('');
$('#themePicker').addEventListener('click', (e) => { const b = e.target.closest('.theme-swatch'); if (b) setLook({ theme: b.dataset.theme }); });
$('#accentDots').innerHTML = ACCENT_PRESETS.map((c) => `<button style="background:${c}" data-accent="${c}" title="${c}"></button>`).join('');
$('#accentDots').addEventListener('click', (e) => { const b = e.target.closest('[data-accent]'); if (b) setLook({ customAccent: b.dataset.accent }); });
let colorTimer = null;
for (const id of ['customBg', 'customAccent']) {
  $(`#${id}`).addEventListener('input', (e) => {
    state.look[id] = e.target.value;
    applyLook(state.look); // live preview while dragging
    clearTimeout(colorTimer);
    colorTimer = setTimeout(() => setLook({ [id]: e.target.value }), 250);
  });
}
$('#setFont').innerHTML = FONTS.map((f) => `<option value="${f.id}">${esc(f.name)}</option>`).join('');
$('#setFont').addEventListener('change', (e) => setLook({ font: e.target.value }));
$('#applyFontName').addEventListener('click', () => setLook({ fontName: $('#setFontName').value.trim() }));
$('#setFontName').addEventListener('keydown', (e) => { if (e.key === 'Enter') setLook({ fontName: e.target.value.trim() }); });
$('#pickFontFile').addEventListener('click', async () => {
  const r = await safe(() => window.cat.font.pickFile());
  if (!r) return;
  await loadFontFile(r.dataUrl);
  $('#fontFileName').textContent = r.name;
  setLook({ font: 'file' });
});
async function loadFontFile(dataUrl) {
  if (!dataUrl) return;
  try {
    const face = new FontFace('CatgirlCustomFont', `url(${dataUrl})`);
    await face.load();
    document.fonts.add(face);
  } catch { toast("That font file couldn't be loaded.", 'err'); }
}

$('#bgPicker').innerHTML = BACKGROUNDS.map((b) => `<button class="bg-tile ${b.id === 'none' ? 'none' : ''}" data-bg="${b.id}" ${b.url ? `style="background-image:url('${b.url}')"` : ''}><span>${b.name}</span></button>`).join('');
$('#bgPicker').addEventListener('click', async (e) => {
  const t = e.target.closest('.bg-tile');
  if (!t) return;
  if (t.dataset.bg === 'custom' && !state.customBgUrl) return pickBgFile();
  setLook({ background: t.dataset.bg });
});
function showCustomBgTile() {
  const tile = $('.bg-tile[data-bg="custom"]');
  if (tile && state.customBgUrl) tile.style.backgroundImage = `url("${state.customBgUrl}")`;
}
async function pickBgFile() {
  const url = await safe(() => window.cat.bg.pickFile());
  if (!url) return;
  state.customBgUrl = url;
  showCustomBgTile();
  setLook({ background: 'custom' });
}
$('#bgPickFile').addEventListener('click', pickBgFile);
$('#bgUseUrl').addEventListener('click', async () => {
  const link = $('#bgUrl').value.trim();
  if (!link) return toast('Paste an image link first. Right-click a picture → "Copy image address".', 'err');
  $('#bgUseUrl').disabled = true;
  const url = await safe(() => window.cat.bg.fromUrl(link), 'Background set 🐾');
  $('#bgUseUrl').disabled = false;
  if (!url) return;
  state.customBgUrl = url;
  $('#bgUrl').value = '';
  showCustomBgTile();
  setLook({ background: 'custom' });
});
let sliderTimer = null;
for (const [id, key] of [['bgDim', 'bgDim'], ['bgBlur', 'bgBlur']]) {
  $(`#${id}`).addEventListener('input', (e) => {
    state.look[key] = +e.target.value;
    applyLook(state.look);
    syncLookControls();
    clearTimeout(sliderTimer);
    sliderTimer = setTimeout(() => setLook({ [key]: +e.target.value }), 250);
  });
}
$$('#navPos button').forEach((b) => b.addEventListener('click', () => setLook({ navPosition: b.dataset.v })));
$('#navIcons').addEventListener('change', (e) => setLook({ navIcons: e.target.checked }));
$$('#menuPos button').forEach((b) => b.addEventListener('click', () => setLook({ menuPosition: b.dataset.v })));
$('#menuIcons').addEventListener('change', (e) => setLook({ menuIconsOnly: e.target.checked }));
$('#setCatgirlMenu').addEventListener('change', (e) => setLook({ catgirlMenu: e.target.checked }));
$('#setShareOptions').addEventListener('change', (e) => setLook({ shareOptions: e.target.checked }));
$('#setShareServers').addEventListener('change', (e) => setLook({ shareServers: e.target.checked }));
$('#setSplashes').addEventListener('change', (e) => setLook({ splashes: e.target.checked }));
$('#setCapePictures').addEventListener('change', (e) => setLook({ showCapePictures: e.target.checked }));
$('#setDiscord').addEventListener('change', (e) => setLook({ discordPresence: e.target.checked }));
$('#setDiscordServer').addEventListener('change', (e) => setLook({ discordShowServer: e.target.checked }));

/* ---------- updates ---------- */
function showUpdate(u) {
  if (!u) return;
  const text = {
    checking: 'Checking for updates…',
    latest: "You're on the latest version.",
    downloading: `Downloading v${u.version || ''}${u.percent ? ` · ${u.percent}%` : ''}…`,
    ready: `v${u.version} is ready to install.`,
    error: `Update check failed: ${u.message || ''}`,
  }[u.state] || '';
  $('#updateText').textContent = text;
  $('#updateBanner').classList.toggle('hidden', u.state !== 'ready');
  if (u.state === 'ready') $('#updateBannerText').textContent = `Catgirl Launcher v${u.version} is ready 🐾`;
}
window.cat.update.onStatus(showUpdate);
$('#updateRestart').addEventListener('click', () => window.cat.update.install());
$('#checkUpdates').addEventListener('click', () => safe(() => window.cat.update.check()));

/* ---------- settings ---------- */
const LOOK_KEYS = ['theme', 'customBg', 'customAccent', 'font', 'fontName', 'background', 'bgDim', 'bgBlur', 'navPosition', 'navIcons', 'menuPosition', 'menuIconsOnly', 'catgirlMenu', 'discordPresence', 'discordShowServer', 'splashes', 'shareOptions', 'shareServers'];
async function loadSettings() {
  const s = await safe(() => window.cat.settings.get());
  if (!s) return;
  for (const k of LOOK_KEYS) state.look[k] = s[k];
  applyLook(state.look);
  syncLookControls();
  $('#setCatgirlMenu').checked = s.catgirlMenu !== false;
  $('#setAfterLaunch').value = s.afterLaunch;
  $('#setJavaPath').value = s.javaPath || '';
  $('#setJavaArgs').value = s.javaArgs || '';
  $('#setCustomRes').checked = !!s.customResolution;
  $('#setWidth').value = s.width;
  $('#setHeight').value = s.height;
  $('#setSnapshots').checked = !!s.showSnapshots;
  $('#dataDir').textContent = state.info?.dataDir || '';
  $('#appVersion').textContent = `Catgirl Launcher v${state.info?.version || ''}`;
  if (!state.info?.canUpdate) $('#updateText').textContent = 'Auto-updates work in the installed app.';
}
$('#pickJava').addEventListener('click', async () => { const p = await safe(() => window.cat.settings.pickJava()); if (p) $('#setJavaPath').value = p; });
$('#saveSettings').addEventListener('click', () => safe(() => window.cat.settings.set({
  afterLaunch: $('#setAfterLaunch').value,
  javaPath: $('#setJavaPath').value.trim(),
  javaArgs: $('#setJavaArgs').value.trim(),
  customResolution: $('#setCustomRes').checked,
  width: +$('#setWidth').value || 1280,
  height: +$('#setHeight').value || 720,
  showSnapshots: $('#setSnapshots').checked,
}), 'Settings saved'));

/* ---------- boot ---------- */
$('#accountHead').addEventListener('error', (e) => { e.target.src = 'assets/logo.png'; });
(async function boot() {
  state.info = await safe(() => window.cat.info());
  const [bgUrl, font] = await Promise.all([safe(() => window.cat.bg.get()), safe(() => window.cat.font.get())]);
  state.customBgUrl = bgUrl || null;
  showCustomBgTile();
  if (font?.dataUrl) loadFontFile(font.dataUrl);
  await loadSettings();
  showUpdate(state.info?.update);
  for (const id of state.info?.running || []) state.running.add(id);
  await Promise.all([refreshInstances(), refreshAccounts()]);
  if (!state.accounts.accounts.length) setTimeout(() => toast('Add your Microsoft account to start playing.'), 600);
})();
