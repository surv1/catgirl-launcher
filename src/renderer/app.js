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
  try { const r = await fn(); if (okMsg) toast(okMsg, 'ok'); return r; } catch (e) { toast(e.message, 'err'); return undefined; }
}
function head(uuid, size = 64) { return uuid ? `https://mc-heads.net/avatar/${uuid}/${size}` : 'assets/logo.svg'; }
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
}
document.addEventListener('click', (e) => {
  const nav = e.target.closest('[data-page]');
  if (nav) go(nav.dataset.page);
  const w = e.target.closest('[data-win]');
  if (w) window.cat.win[w.dataset.win]();
  const play = e.target.closest('[data-play]');
  if (play) playOrStop(play.dataset.play);
});

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
  $('#accountHead').src = sel ? head(sel.uuid) : 'assets/logo.svg';
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
  }
  updatePlayUI(id);
}

window.cat.launch.onProgress((p) => { state.progress[p.instId] = p; updatePlayUI(p.instId); });
window.cat.launch.onStarted(({ instId }) => {
  state.launching.delete(instId);
  state.running.add(instId);
  updatePlayUI(instId);
  toast('Minecraft is starting. Have fun! 🐾', 'ok');
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
  const icon = ICONS[inst.icon] || '🐱';
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
  } else {
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
    <div class="field"><label>Name</label><input id="iName" value="${esc(inst.name)}" placeholder="My survival world" maxlength="40" /></div>
    <div class="field"><label>Icon</label><div class="seg" id="iIcon">${Object.entries(ICONS).map(([k, v]) => `<button data-v="${k}" class="${inst.icon === k ? 'on' : ''}">${v}</button>`).join('')}</div></div>
    <div class="field"><label>Minecraft version</label><select id="iVersion"><option>Loading…</option></select></div>
    <div class="field"><label>Mod loader</label><div class="seg" id="iLoader">
      <button data-v="vanilla" class="${inst.loader === 'vanilla' ? 'on' : ''}">Vanilla</button>
      <button data-v="fabric" class="${inst.loader === 'fabric' ? 'on' : ''}">Fabric</button></div></div>
    <div class="field ${inst.loader === 'fabric' ? '' : 'hidden'}" id="iLoaderVerWrap"><label>Fabric loader <small>Latest stable is picked automatically</small></label><select id="iLoaderVer"><option value="">Latest stable</option></select></div>
    <div class="field"><label>Memory</label><div class="range-row"><input type="range" id="iMem" min="1024" max="16384" step="512" value="${inst.memoryMB}" /><b id="iMemVal"></b></div></div>
    <div class="field"><label>Join a server on launch <small>Optional, e.g. play.example.net</small></label><input id="iServer" value="${esc(inst.joinServer)}" /></div>
    <div class="field"><label>Java arguments <small>Optional</small></label><input id="iArgs" value="${esc(inst.javaArgs || '')}" placeholder="-XX:+UseG1GC" /></div>
    <div class="actions">
      ${editing ? '<button class="ghost" id="iFolder">Open folder</button><button class="danger" id="iDelete">Delete</button>' : ''}
      <span class="spacer" style="flex:1"></span>
      <button class="primary" id="iSave">${editing ? 'Save' : 'Create'}</button>
    </div>`);

  let loader = inst.loader, icon = inst.icon;
  const memLabel = () => { $('#iMemVal').textContent = `${(+$('#iMem').value / 1024).toFixed(1)} GB`; };
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
      if (await safe(() => window.cat.instances.remove(inst.id), 'Instance deleted') !== undefined) { closeModal(); refreshInstances(); }
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
    };
    data.joinServer = $('#iServer').value.trim();
    if (!data.name) return toast('Give your instance a name.', 'err');
    if (!data.mcVersion) return toast('Pick a Minecraft version.', 'err');
    const r = editing ? await safe(() => window.cat.instances.update(inst.id, data), 'Saved') : await safe(() => window.cat.instances.create(data), 'Instance created');
    if (r) { closeModal(); refreshInstances(); }
  });
}

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
$('#modMore').addEventListener('click', () => searchMods(false));

async function searchMods(reset) {
  if (reset) { state.modOffset = 0; $('#modResults').innerHTML = '<div class="empty"><span class="spinner"></span>Searching Modrinth…</div>'; }
  const q = $('#modSearch').value.trim();
  const r = await safe(() => window.cat.mods.search(state.modsInst, q, state.modOffset));
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

/* ---------- themes ---------- */
const THEMES = [
  { id: 'sakura', name: 'Sakura', dots: ['#170d1c', '#ff7eb6', '#c77dff'] },
  { id: 'lavender', name: 'Lavender', dots: ['#13111d', '#b69cff', '#7f9cff'] },
  { id: 'midnight', name: 'Midnight', dots: ['#0b0e1a', '#7aa2ff', '#c77dff'] },
  { id: 'strawberry', name: 'Strawberry', dots: ['#1a0c10', '#ff5c7a', '#ffb36b'] },
  { id: 'mint', name: 'Mint', dots: ['#0c1613', '#6fe3b5', '#7ec8ff'] },
  { id: 'cottoncandy', name: 'Cotton Candy', dots: ['#fff4f9', '#ff5fa2', '#9b6bff'] },
];
function applyTheme(id) {
  const theme = THEMES.some((t) => t.id === id) ? id : 'sakura';
  document.body.dataset.theme = theme;
  $$('.theme-swatch').forEach((b) => b.classList.toggle('on', b.dataset.theme === theme));
}
$('#themePicker').innerHTML = THEMES.map((t) => `<button class="theme-swatch" data-theme="${t.id}"><div class="dots">${t.dots.map((c) => `<i style="background:${c}"></i>`).join('')}</div><span>${t.name}</span></button>`).join('');
$('#themePicker').addEventListener('click', (e) => {
  const b = e.target.closest('.theme-swatch');
  if (!b) return;
  applyTheme(b.dataset.theme);
  safe(() => window.cat.settings.set({ theme: b.dataset.theme }));
});

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
async function loadSettings() {
  const s = await safe(() => window.cat.settings.get());
  if (!s) return;
  applyTheme(s.theme);
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
  catgirlMenu: $('#setCatgirlMenu').checked,
  afterLaunch: $('#setAfterLaunch').value,
  javaPath: $('#setJavaPath').value.trim(),
  javaArgs: $('#setJavaArgs').value.trim(),
  customResolution: $('#setCustomRes').checked,
  width: +$('#setWidth').value || 1280,
  height: +$('#setHeight').value || 720,
  showSnapshots: $('#setSnapshots').checked,
}), 'Settings saved'));

/* ---------- boot ---------- */
$('#accountHead').addEventListener('error', (e) => { e.target.src = 'assets/logo.svg'; });
(async function boot() {
  state.info = await safe(() => window.cat.info());
  const s0 = await safe(() => window.cat.settings.get());
  applyTheme(s0?.theme);
  showUpdate(state.info?.update);
  for (const id of state.info?.running || []) state.running.add(id);
  await Promise.all([refreshInstances(), refreshAccounts()]);
  if (!state.accounts.accounts.length) setTimeout(() => toast('Add your Microsoft account to start playing.'), 600);
})();
