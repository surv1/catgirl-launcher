/* Catgirl Launcher – Cosmetics page (cat ears, tail, bow) with a live front/back preview.
   The shapes and the tail animation match the in-game mod (CosmeticsLayer.java). */

const COS_ITEMS = [
  { id: 'ears', name: 'Cat ears', emoji: '🐱', colors: [['color', 'Fur'], ['inner', 'Inside']] },
  { id: 'tail', name: 'Cat tail', emoji: '🐈', colors: [['color', 'Fur']] },
  { id: 'bow', name: 'Hair bow', emoji: '🎀', colors: [['color', 'Colour']] },
];
const COS_DEFAULTS = {
  ears: { on: false, color: '#3b2a2a', inner: '#ffb3d9' },
  tail: { on: false, color: '#3b2a2a' },
  bow: { on: false, color: '#ff7eb6' },
};
const FUR_PRESETS = ['#3b2a2a', '#1b1b1f', '#f5f0e6', '#c98a4b', '#f2c879', '#9a9aa3', '#ff7eb6', '#b48cff'];
const INNER_PRESETS = ['#ffb3d9', '#ffd6e8', '#ff8fc7', '#f5f0e6', '#b48cff'];
const BOW_PRESETS = ['#ff7eb6', '#e5383b', '#ffffff', '#1b1b1f', '#b48cff', '#7ec8ff', '#ffd166', '#6fe3b5'];

const cos = { items: structuredClone(COS_DEFAULTS), skin: null, variant: 'classic', loaded: false, dirty: false, anim: 0, saving: false };

/* ---------- shared shape maths (model pixels, y points down, -x is the player's right, -z the front) ---------- */
const TAIL = { N: 9, LEN: 1.35, A0: -0.7, CURL: 0.2, BASE: [0, 10.5, 2] };
const EAR = (s) => ({ base: [[s * 1.0, -8], [s * 4.4, -8]], tip: [s * 3.7, -12.6], inner: [[s * 1.8, -8.1], [s * 3.8, -8.1], [s * 3.45, -11.3]], z: [-1.6, -0.2] });
const BOW = { c: [3.0, -7.3], tilt: 0.35, wing: (s) => [[3.0 + s * 0.4, -7.3], [3.0 + s * 2.1, -8.5], [3.0 + s * 2.1, -6.1]], knot: [2.45, -7.9, 3.55, -6.7] };

function earFlick(t) { const ph = t % 80; return ph < 6 ? Math.sin((ph / 6) * Math.PI) * 0.25 : 0; }
function tailSway(t, i) { return i < 0 ? Math.sin(t * 0.08) * 0.22 : Math.sin(t * 0.08 - (i + 1) * 0.5) * 0.08; }
function tailWidth(i) { return 1.8 - (0.75 * i) / (TAIL.N - 1); }

const mmul = (a, b) => a.map((r) => [0, 1, 2].map((j) => r[0] * b[0][j] + r[1] * b[1][j] + r[2] * b[2][j]));
const rx = (a) => [[1, 0, 0], [0, Math.cos(a), -Math.sin(a)], [0, Math.sin(a), Math.cos(a)]];
const ry = (a) => [[Math.cos(a), 0, Math.sin(a)], [0, 1, 0], [-Math.sin(a), 0, Math.cos(a)]];
function tailJoints(t) {
  let m = mmul(ry(tailSway(t, -1)), rx(TAIL.A0));
  let p = [...TAIL.BASE];
  const pts = [p];
  for (let i = 0; i < TAIL.N; i++) {
    p = [p[0] + m[0][2] * TAIL.LEN, p[1] + m[1][2] * TAIL.LEN, p[2] + m[2][2] * TAIL.LEN];
    pts.push(p);
    m = mmul(mmul(m, rx(TAIL.CURL)), ry(tailSway(t, i)));
  }
  return pts;
}

function shade(hex, f) {
  const n = parseInt(hex.slice(1), 16);
  const c = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => Math.round(f >= 1 ? v + (255 - v) * (f - 1) : v * f));
  return `rgb(${c.join(',')})`;
}
function rot2(pts, [cx, cy], a) {
  const c = Math.cos(a), s = Math.sin(a);
  return pts.map(([x, y]) => [cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c]);
}

/* ---------- drawing: a front view and a side view (the player's right side) ---------- */
const S = 14;                      // canvas pixels per skin pixel
const GRID = [24, 40];             // canvas size in skin pixels
const NECK = 15;                   // y of the neck on the grid
const VIEW = {
  front: { cx: 12, at: ([x, y]) => [12 + x, NECK + y] },
  side: { cx: 16, at: ([, y, z]) => [16 - z, NECK + y] },
};

// [sx, sy, w, h, dx, dy, mirror] with dx/dy in grid pixels from the view's centre line / the neck
function skinParts(img, variant, view) {
  const modern = img.height >= 64;
  const aw = variant === 'slim' ? 3 : 4;
  if (view === 'side') {
    return [
      [0, 20, 4, 12, -2, 12], [16, 20, 4, 12, -2, 0],
      ...(modern ? [[0, 36, 4, 12, -2, 12], [16, 36, 4, 12, -2, 0]] : []),
      [40, 20, 4, 12, -2, 0], ...(modern ? [[40, 36, 4, 12, -2, 0]] : []),
      [0, 8, 8, 8, -4, -8], [32, 8, 8, 8, -4, -8],
    ];
  }
  return [
    [4, 20, 4, 12, -4, 12], modern ? [20, 52, 4, 12, 0, 12] : [4, 20, 4, 12, 0, 12, 1],
    [20, 20, 8, 12, -4, 0],
    [44, 20, aw, 12, -4 - aw, 0], modern ? [36, 52, aw, 12, 4, 0] : [44, 20, aw, 12, 4, 0, 1],
    [8, 8, 8, 8, -4, -8],
    ...(modern ? [[4, 36, 4, 12, -4, 12], [4, 52, 4, 12, 0, 12], [20, 36, 8, 12, -4, 0], [44, 36, aw, 12, -4 - aw, 0], [52, 52, aw, 12, 4, 0]] : []),
    [40, 8, 8, 8, -4, -8],
  ];
}

function drawSkin(ctx, img, variant, view) {
  const cx = VIEW[view].cx;
  if (!img) {
    ctx.fillStyle = 'rgba(255,255,255,.18)';
    const boxes = view === 'side' ? [[-4, -8, 8, 8], [-2, 0, 4, 24]] : [[-4, -8, 8, 8], [-8, 0, 16, 12], [-4, 12, 8, 12]];
    for (const [x, y, w, h] of boxes) ctx.fillRect((cx + x) * S, (NECK + y) * S, w * S, h * S);
    return;
  }
  ctx.imageSmoothingEnabled = false;
  for (const [sx, sy, w, h, dx, dy, mirror] of skinParts(img, variant, view)) {
    const x = (cx + dx) * S, y = (NECK + dy) * S;
    if (!mirror) { ctx.drawImage(img, sx, sy, w, h, x, y, w * S, h * S); continue; }
    ctx.save(); ctx.translate(x + w * S, y); ctx.scale(-1, 1); ctx.drawImage(img, sx, sy, w, h, 0, 0, w * S, h * S); ctx.restore();
  }
}

function poly(ctx, view, pts3, fill) {
  ctx.beginPath();
  pts3.forEach((p, i) => { const [x, y] = VIEW[view].at(p); i ? ctx.lineTo(x * S, y * S) : ctx.moveTo(x * S, y * S); });
  ctx.closePath();
  ctx.fillStyle = fill;
  ctx.fill();
}
const at3 = (pts2, z) => pts2.map(([x, y]) => [x, y, z]);

function drawEars(ctx, it, view, t) {
  for (const s of [-1, 1]) {
    const e = EAR(s);
    if (view === 'side') {
      if (s > 0) continue; // only the near ear shows from the side
      const [z0, z1] = e.z;
      poly(ctx, view, [[0, -8, z0], [0, -8, z1], [0, e.tip[1], z1 - 0.3], [0, e.tip[1], z0 + 0.3]], it.color);
      continue;
    }
    const pivot = [s * 2.75, -8];
    const a = s * earFlick(t + (s > 0 ? 0 : 40));
    poly(ctx, view, at3(rot2([e.base[0], e.tip, e.base[1]], pivot, a), 0), it.color);
    poly(ctx, view, at3(rot2(e.inner, pivot, a), 0), it.inner);
  }
}

function drawBow(ctx, it, view) {
  if (view !== 'front') return; // it sits on the far side of the head
  for (const s of [-1, 1]) poly(ctx, view, at3(rot2(BOW.wing(s), BOW.c, BOW.tilt), 0), it.color);
  const [x0, y0, x1, y1] = BOW.knot;
  poly(ctx, view, at3(rot2([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], BOW.c, BOW.tilt), 0), shade(it.color, 0.8));
}

function drawTail(ctx, it, view, t) {
  const pts = tailJoints(t).map((p) => VIEW[view].at(p));
  ctx.lineCap = 'round';
  for (let i = 0; i < TAIL.N; i++) {
    ctx.strokeStyle = i >= TAIL.N - 2 ? shade(it.color, 1.25) : it.color;
    ctx.lineWidth = tailWidth(i) * S;
    ctx.beginPath();
    ctx.moveTo(pts[i][0] * S, pts[i][1] * S);
    ctx.lineTo(pts[i + 1][0] * S, pts[i + 1][1] * S);
    ctx.stroke();
  }
}

function drawPreview(t) {
  for (const view of ['front', 'side']) {
    const cv = $(view === 'side' ? '#cosSide' : '#cosFront');
    if (!cv) continue;
    if (cv.width !== GRID[0] * S) { cv.width = GRID[0] * S; cv.height = GRID[1] * S; }
    const ctx = cv.getContext('2d');
    ctx.clearRect(0, 0, cv.width, cv.height);
    const it = cos.items;
    if (it.tail.on) drawTail(ctx, it.tail, view, t); // behind the body
    drawSkin(ctx, cos.skin, cos.variant, view);
    if (it.ears.on) drawEars(ctx, it.ears, view, t);
    if (it.bow.on) drawBow(ctx, it.bow, view);
  }
}

function animate() {
  cancelAnimationFrame(cos.anim);
  const step = () => {
    if (!$('#page-cosmetics').classList.contains('active')) return;
    drawPreview(performance.now() / 50);
    cos.anim = requestAnimationFrame(step);
  };
  step();
}

/* ---------- controls ---------- */
function presetsFor(item, key) { return item === 'bow' ? BOW_PRESETS : key === 'inner' ? INNER_PRESETS : FUR_PRESETS; }

function renderCosItems() {
  $('#cosItems').innerHTML = COS_ITEMS.map((d) => {
    const v = cos.items[d.id];
    return `<div class="cos-item ${v.on ? 'on' : ''}" data-item="${d.id}">
      <header><span class="emoji">${d.emoji}</span><b>${d.name}</b>
        <label class="switch" title="Wear ${d.name.toLowerCase()}"><input type="checkbox" data-on="${d.id}" ${v.on ? 'checked' : ''} /><i></i></label></header>
      <div class="colors">${d.colors.map(([key, label]) => `
        <div class="cos-color"><span>${label}</span><input type="color" data-color="${d.id}.${key}" value="${esc(v[key])}" />
          <div class="presets-dots">${presetsFor(d.id, key).map((c) => `<button style="background:${c}" data-preset="${d.id}.${key}" data-c="${c}" title="${c}"></button>`).join('')}</div></div>`).join('')}
      </div></div>`;
  }).join('');
}

function setCos(item, key, value) {
  cos.items[item][key] = value;
  cos.dirty = true;
  $('#cosStatus').textContent = 'Not saved yet';
  if (key === 'on') $(`.cos-item[data-item="${item}"]`)?.classList.toggle('on', !!value);
  if (key !== 'on') { const inp = $(`[data-color="${item}.${key}"]`); if (inp && inp.value !== value) inp.value = value; }
}

$('#cosItems').addEventListener('change', (e) => { const on = e.target.closest('[data-on]'); if (on) setCos(on.dataset.on, 'on', on.checked); });
$('#cosItems').addEventListener('input', (e) => { const c = e.target.closest('[data-color]'); if (c) { const [i, k] = c.dataset.color.split('.'); setCos(i, k, c.value); } });
$('#cosItems').addEventListener('click', (e) => { const p = e.target.closest('[data-preset]'); if (p) { const [i, k] = p.dataset.preset.split('.'); setCos(i, k, p.dataset.c); } });

// The top of the head (or the hat layer, if the skin has one there) is usually hair.
function hairColor(img) {
  const c = document.createElement('canvas');
  c.width = 64; c.height = 16;
  const ctx = c.getContext('2d');
  ctx.drawImage(img, 0, 0, 64, 16, 0, 0, 64, 16);
  const avg = (x0) => {
    const d = ctx.getImageData(x0, 0, 8, 8).data;
    let r = 0, g = 0, b = 0, n = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i + 3] > 200) { r += d[i]; g += d[i + 1]; b += d[i + 2]; n++; }
    return n >= 8 ? '#' + [r, g, b].map((v) => Math.round(v / n).toString(16).padStart(2, '0')).join('') : null;
  };
  return avg(40) || avg(8);
}
$('#cosHair').addEventListener('click', () => {
  if (!cos.skin) { toast("Couldn't load your skin to check its hair colour.", 'err'); return; }
  const hex = hairColor(cos.skin);
  if (!hex) { toast("Couldn't find any hair on your skin.", 'err'); return; }
  setCos('ears', 'color', hex);
  setCos('tail', 'color', hex);
  toast('Ears and tail now match your hair 🎨', 'ok');
});

$('#cosSave').addEventListener('click', async () => {
  if (cos.saving) return;
  cos.saving = true;
  $('#cosSave').disabled = true;
  $('#cosStatus').textContent = 'Saving…';
  const r = await safe(() => window.cat.cosmetics.save(cos.items));
  cos.saving = false;
  $('#cosSave').disabled = false;
  if (!r) { $('#cosStatus').textContent = ''; return; }
  cos.dirty = false;
  if (r.synced) {
    $('#cosStatus').textContent = 'Saved ✓ Everyone can see them';
    toast('Saved! Everyone playing with Catgirl Client can see them, nya~', 'ok');
  } else {
    $('#cosStatus').textContent = "Saved on this PC ✓ (you'll see them in game)";
    toast(`Saved! You'll see them in game. Others will see them once they can be shared online: ${r.error}`, 'err');
  }
});

const loadImg = (src) => new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = src; });

async function renderCosmetics() {
  if (!cos.loaded) renderCosItems();
  animate();
  if (cos.dirty) return; // keep unsaved changes while you look around the launcher
  let data;
  try { data = await window.cat.cosmetics.get(); } catch (e) {
    $('#cosSignin').classList.remove('hidden');
    $('#cosWrap').classList.add('hidden');
    return;
  }
  $('#cosSignin').classList.add('hidden');
  $('#cosWrap').classList.remove('hidden');
  cos.items = { ...structuredClone(COS_DEFAULTS), ...data.items };
  cos.variant = data.skin?.variant || 'classic';
  cos.skin = data.skin ? await loadImg(data.skin.dataUrl).catch(() => null) : null;
  cos.loaded = true;
  renderCosItems();
  $('#cosStatus').textContent = data.synced ? 'Saved ✓' : COS_ITEMS.some((d) => data.items[d.id]?.on) ? "Saved on this PC (not shared online yet)" : '';
}
