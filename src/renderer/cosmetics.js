/* Catgirl Launcher – Cosmetics page with a live front/side preview.
   The shapes and animations match the in-game mod (CosmeticsLayer.java). */

const COS_ITEMS = [
  { id: 'ears', name: 'Cat ears', emoji: '🐱', colors: [['color', 'Fur'], ['inner', 'Inside']] },
  { id: 'tail', name: 'Cat tail', emoji: '🐈', colors: [['color', 'Fur']] },
  { id: 'bow', name: 'Hair bow', emoji: '🎀', colors: [['color', 'Colour']] },
  { id: 'wings', name: 'Wings', emoji: '🪽', colors: [['color', 'Colour']], styles: [['angel', 'Angel'], ['demon', 'Demon']] },
  { id: 'halo', name: 'Halo', emoji: '😇', colors: [['color', 'Colour']] },
  { id: 'horns', name: 'Devil horns', emoji: '😈', colors: [['color', 'Colour']] },
  { id: 'pet', name: 'Angel buddy', emoji: '👼', colors: [['color', 'Colour']] },
  { id: 'trim', name: 'Glow trim', emoji: '✨', hint: 'Shows on the armour you wear, like a real armour trim.', colors: [['color', 'Glow'], ['accent', 'Accent']], styles: [['paws', 'Paws'], ['stars', 'Starlight'], ['hearts', 'Heartbeat'], ['circuit', 'Circuit']] },
  { id: 'cape', name: 'Cape', emoji: '🧣', colors: [['color', 'Cape'], ['trim', 'Trim']], styles: [['plain', 'Plain'], ['paw', 'Paw'], ['heart', 'Heart'], ['meow', 'Meow'], ['catmeow', 'Catgirl meow ✨']] },
];
const COS_DEFAULTS = {
  ears: { on: false, color: '#3b2a2a', inner: '#ffb3d9' },
  tail: { on: false, color: '#3b2a2a' },
  bow: { on: false, color: '#ff7eb6' },
  wings: { on: false, color: '#ffffff', style: 'angel' },
  halo: { on: false, color: '#ffd34d' },
  horns: { on: false, color: '#5a1a1a' },
  pet: { on: false, color: '#ffb3d9' },
  cape: { on: false, color: '#ff7eb6', trim: '#ffffff', style: 'paw', line: 'cycle' },
  trim: { on: false, color: '#7ec8ff', accent: '#ff7eb6', style: 'paws' },
};
const WING_DEFAULT = { angel: '#ffffff', demon: '#8b1a1a' };
const FUR_PRESETS = ['#3b2a2a', '#1b1b1f', '#f5f0e6', '#c98a4b', '#f2c879', '#9a9aa3', '#ff7eb6', '#b48cff'];
const INNER_PRESETS = ['#ffb3d9', '#ffd6e8', '#ff8fc7', '#f5f0e6', '#b48cff'];
const BOW_PRESETS = ['#ff7eb6', '#e5383b', '#ffffff', '#1b1b1f', '#b48cff', '#7ec8ff', '#ffd166', '#6fe3b5'];
const PRESETS = {
  wings: ['#ffffff', '#fff3c4', '#ffd6e8', '#b48cff', '#8b1a1a', '#1b1b1f', '#3a1f5c', '#7ec8ff'],
  halo: ['#ffd34d', '#ffffff', '#ff7eb6', '#7ec8ff', '#b48cff', '#e5383b'],
  horns: ['#5a1a1a', '#e5383b', '#1b1b1f', '#3a1f5c', '#f5f0e6', '#ff7eb6'],
  pet: ['#ffb3d9', '#ffffff', '#ffd34d', '#7ec8ff', '#b48cff', '#6fe3b5'],
  cape: ['#ff7eb6', '#b48cff', '#1b1b1f', '#ffffff', '#8b1a1a', '#3a1f5c', '#7ec8ff', '#6fe3b5'],
  trim: ['#7ec8ff', '#ff7eb6', '#b48cff', '#6fe3b5', '#ffd34d', '#ff5c5c', '#ffffff', '#ff9a3c'],
  'trim.accent': ['#ff7eb6', '#ffffff', '#ffd34d', '#7ec8ff', '#b48cff', '#6fe3b5'],
  'cape.trim': ['#ffffff', '#ffd34d', '#1b1b1f', '#ff7eb6', '#b48cff', '#e5383b'],
};

const cos = { view2: 'back', items: structuredClone(COS_DEFAULTS), skin: null, variant: 'classic', loaded: false, dirty: false, anim: 0, saving: false };

/* ---------- shared shape maths (model pixels, y points down, -x is the player's right, -z the front) ---------- */
const TAIL = { N: 14, LEN: 0.9, A0: -0.7, CURL: 0.13, BASE: [0, 10.5, 2] };
const EAR = (s) => ({ base: [[s * 1.0, -8], [s * 4.4, -8]], tip: [s * 3.7, -12.6], inner: [[s * 1.8, -8.1], [s * 3.8, -8.1], [s * 3.45, -11.3]], z: [-1.6, -0.2] });
const BOW = { c: [3.0, -7.3], tilt: 0.35, wing: (s) => [[3.0 + s * 0.4, -7.3], [3.0 + s * 2.1, -8.5], [3.0 + s * 2.1, -6.1]], knot: [2.45, -7.9, 3.55, -6.7] };

function earFlick(t) { const ph = t % 80; return ph < 6 ? Math.sin((ph / 6) * Math.PI) * 0.25 : 0; }
function tailSway(t, i) { return i < 0 ? Math.sin(t * 0.08) * 0.22 : Math.sin(t * 0.08 - (i + 1) * 0.32) * 0.05; }
function tailWidth(i) { const f = i / (TAIL.N - 1); return 1.5 + 0.5 * Math.sin(Math.PI * f * 0.8) - 0.7 * f ** 3; }

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
  return '#' + c.map((v) => Math.max(0, Math.min(255, v)).toString(16).padStart(2, '0')).join('');
}
function rot2(pts, [cx, cy], a) {
  const c = Math.cos(a), s = Math.sin(a);
  return pts.map(([x, y]) => [cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c]);
}

/* ---------- drawing: a front view and a side view (the player's right side) ---------- */
const S = 14;                      // canvas pixels per skin pixel
const GRID = [30, 40];             // canvas size in skin pixels
const NECK = 15;                   // y of the neck on the grid
const VIEW = {
  front: { cx: 15, at: ([x, y]) => [15 + x, NECK + y] },
  side: { cx: 19, at: ([, y, z]) => [19 - z, NECK + y] },
  back: { cx: 15, at: ([x, y]) => [15 - x, NECK + y] },
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
  if (view === 'back') {
    const rBack = 48 + aw, lBack = 40 + aw; // x of the back of each arm on the skin (rows 20 and 52)
    return [
      [12, 20, 4, 12, 0, 12], modern ? [28, 52, 4, 12, -4, 12] : [12, 20, 4, 12, -4, 12, 1],
      [32, 20, 8, 12, -4, 0],
      [rBack, 20, aw, 12, 4, 0], modern ? [lBack, 52, aw, 12, -4 - aw, 0] : [rBack, 20, aw, 12, -4 - aw, 0, 1],
      [24, 8, 8, 8, -4, -8],
      ...(modern ? [[12, 36, 4, 12, 0, 12], [12, 52, 4, 12, -4, 12], [32, 36, 8, 12, -4, 0], [rBack, 36, aw, 12, 4, 0], [lBack + 16, 52, aw, 12, -4 - aw, 0]] : []),
      [56, 8, 8, 8, -4, -8],
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
    if (view === 'front') {
      poly(ctx, view, at3(rot2(e.inner, pivot, a), 0), it.inner);
      poly(ctx, view, at3(rot2([[s * 2.2, -8.15], [s * 2.9, -9.7], [s * 3.4, -8.15]], pivot, a), 0), shade(it.inner, 1.35));
    }
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
    ctx.strokeStyle = i >= TAIL.N - 3 ? shade(it.color, 1.25) : it.color;
    ctx.lineWidth = tailWidth(i) * S;
    ctx.beginPath();
    ctx.moveTo(pts[i][0] * S, pts[i][1] * S);
    ctx.lineTo(pts[i + 1][0] * S, pts[i + 1][1] * S);
    ctx.stroke();
  }
}

/* ---------- 3D shapes (wings, halo, horns, angel buddy) ---------- */
// A tiny copy of Minecraft's PoseStack.Pose: every call works in the current local space,
// exactly like the mod, so the preview and the game share the same numbers.
class Pose {
  constructor(m = [[1, 0, 0], [0, 1, 0], [0, 0, 1]], o = [0, 0, 0]) { this.m = m; this.o = o; }
  copy() { return new Pose(this.m.map((r) => [...r]), [...this.o]); }
  apply([x, y, z]) { const m = this.m; return [this.o[0] + m[0][0] * x + m[0][1] * y + m[0][2] * z, this.o[1] + m[1][0] * x + m[1][1] * y + m[1][2] * z, this.o[2] + m[2][0] * x + m[2][1] * y + m[2][2] * z]; }
  translate(x, y, z) { this.o = this.apply([x, y, z]); return this; }
  rotate(r) { this.m = mmul(this.m, r); return this; }
  rotateAround(r, x, y, z) { return this.translate(x, y, z).rotate(r).translate(-x, -y, -z); }
  scale(k) { this.m = this.m.map((r) => r.map((v) => v * k)); return this; }
}
const rz = (a) => [[Math.cos(a), -Math.sin(a), 0], [Math.sin(a), Math.cos(a), 0], [0, 0, 1]];

// Collects coloured faces in model space.
class Faces {
  constructor() { this.list = []; this.p = new Pose(); this.bend = null; }
  face(color, pts, glow = false) { this.list.push({ color, layer: this.layer || 0, pts: pts.map((q) => this.p.apply(this.bend ? this.bend(q) : q)), glow }); }
  boxY(color, x0, y0, z0, x1, y1, z1, n) { for (let i = 0; i < n; i++) this.box(color, x0, y0 + ((y1 - y0) * i) / n, z0, x1, y0 + ((y1 - y0) * (i + 1)) / n, z1); }
  box(color, x0, y0, z0, x1, y1, z1, glow) {
    const c = (x, y, z) => [x, y, z];
    const [a, b, cc, d, e, f, g, h] = [c(x0, y0, z0), c(x1, y0, z0), c(x1, y1, z0), c(x0, y1, z0), c(x0, y0, z1), c(x1, y0, z1), c(x1, y1, z1), c(x0, y1, z1)];
    for (const q of [[a, b, cc, d], [e, f, g, h], [a, b, f, e], [d, cc, g, h], [a, d, h, e], [b, cc, g, f]]) this.face(color, q, glow);
  }
  slab(color, poly, z0, z1) { // a flat shape (x/y) with a little thickness
    this.face(color, poly.map(([x, y]) => [x, y, z0]));
    this.face(color, poly.map(([x, y]) => [x, y, z1]));
  }
}

const WING = {
  angelArm: [[0, -0.6], [7.2, -5.8], [7.6, -4.6], [0, 0.8]],
  feathers: [3.5, 4.5, 5.5, 6.2, 6.5, 6.0],
  demonBone: [[0, -0.4], [7.5, -6.4], [8.0, -5.6], [0, 0.6]],
  claw: [[7.3, -6.0], [8.3, -6.2], [8.4, -8.0]],
  wrist: [7.7, -5.9],
  fingers: [[9.6, 1.6], [6.5, 3.5], [3.5, 4.5]],
};
function featherPoly(k) {
  const t = k / 5, a = [0.6 + 6.6 * t, 0.3 - 5.3 * t], L = WING.feathers[k];
  const d = [0.287, 0.958]; // (0.3, 1) normalised: feathers hang down and a little out
  return [[a[0] - 0.75, a[1]], [a[0] + 0.75, a[1]], [a[0] + 0.75 + d[0] * L * 0.85, a[1] + d[1] * L * 0.85], [a[0] + d[0] * L, a[1] + d[1] * L], [a[0] - 0.75 + d[0] * L * 0.85, a[1] + d[1] * L * 0.85]];
}
function boneQuad([ax, ay], [bx, by], w0, w1) {
  const l = Math.hypot(bx - ax, by - ay), px = -(by - ay) / l, py = (bx - ax) / l;
  return [[ax + px * w0, ay + py * w0], [bx + px * w1, by + py * w1], [bx - px * w1, by - py * w1], [ax - px * w0, ay - py * w0]];
}
const flap = (t, style) => (style === 'demon' ? Math.sin(t * 0.09) * 0.12 : Math.sin(t * 0.12) * 0.15);

function wingFaces(F, it, t) {
  for (const s of [-1, 1]) {
    const saved = F.p.copy();
    F.p.translate(s * 1.5, 2.5, 2.1).rotate(ry(-s * (0.4 + flap(t, it.style)))).scale(1.5);
    const m = (poly) => poly.map(([u, v]) => [s * u, v]);
    if (it.style === 'demon') {
      const mem = shade(it.color, 0.7);
      for (const f of [[[0, 0.6], WING.wrist, WING.fingers[2]], [WING.wrist, WING.fingers[1], WING.fingers[2]], [WING.wrist, WING.fingers[0], WING.fingers[1]]]) F.slab(mem, m(f), -0.1, 0.1);
      for (const f of WING.fingers) F.slab(it.color, m(boneQuad(WING.wrist, f, 0.3, 0.15)), -0.2, 0.2);
      F.slab(it.color, m(WING.demonBone), -0.25, 0.25);
      F.slab(shade(it.color, 1.3), m(WING.claw), -0.2, 0.2);
    } else {
      for (let k = 5; k >= 0; k--) F.slab(k % 2 ? shade(it.color, 0.92) : it.color, m(featherPoly(k)), -0.2 + k * 0.03, 0.2 + k * 0.03);
      F.slab(it.color, m(WING.angelArm), -0.3, 0.3);
    }
    F.p = saved;
  }
}

function ring(F, color, r, n, w, glow) {
  for (let i = 0; i < n; i++) {
    const saved = F.p.copy();
    F.p.rotate(ry((i / n) * Math.PI * 2)).translate(0, 0, r);
    F.box(color, -w, -0.3, -0.3, w, 0.3, 0.3, glow);
    F.p = saved;
  }
}
function haloFaces(F, it, t) {
  const saved = F.p.copy();
  F.p.translate(0, -11 + Math.sin(t * 0.1) * 0.3, 0).rotate(rx(0.3));
  ring(F, it.color, 3.4, 14, 0.8, true);
  F.p = saved;
}
function hornFaces(F, it) {
  for (const s of [-1, 1]) {
    const saved = F.p.copy();
    F.p.translate(s * 2.3, -7.6, -1.8).rotate(rz(s * 0.35)).rotate(rx(Math.PI / 2));
    for (let i = 0; i < 4; i++) {
      const w = (1.4 - 0.25 * i) / 2;
      F.box(i === 3 ? shade(it.color, 1.3) : it.color, -w, -w, -0.1, w, w, 1.1);
      F.p.translate(0, 0, 1).rotate(rx(-0.3));
    }
    F.p = saved;
  }
}
function petFaces(F, it, t) {
  const saved = F.p.copy();
  F.p.translate(-11, -3 + Math.sin(t * 0.1) * 0.6, 1).rotate(ry(Math.sin(t * 0.03) * 0.4));
  F.box(it.color, -2, -2, -2, 2, 2, 2);
  F.box('#1b1b1f', -1.3, -0.6, -2.1, -0.5, 0.3, -2.0);
  F.box('#1b1b1f', 0.5, -0.6, -2.1, 1.3, 0.3, -2.0);
  for (const s of [-1, 1]) {
    const w = F.p.copy();
    F.p.translate(s * 1.9, 0, 0.8).rotate(ry(-s * (0.6 + Math.sin(t * 0.6) * 0.35)));
    F.slab('#ffffff', [[0, -0.5], [s * 2.2, -1.6], [s * 2.0, 0.3], [0, 0.6]], -0.1, 0.1);
    F.p = w;
  }
  F.p.translate(0, -3.3, 0).rotate(rx(0.3));
  ring(F, '#ffd34d', 1.3, 8, 0.5, true);
  F.p = saved;
}

// Pixel-art emblems for the back of the cape (X = filled), shared with the mod.
const EMBLEMS = {
  paw: ['..XX.XX..', '..XX.XX..', 'XX.....XX', 'XX.XXX.XX', '..XXXXX..', '.XXXXXXX.', '.XXXXXXX.', '..XX.XX..'],
  heart: ['.XX.XX.', 'XXXXXXX', 'XXXXXXX', '.XXXXX.', '..XXX..', '...X...'],
  meow: ['X.........X', 'XX.......XX', 'XXX.....XXX', 'XXXXXXXXXXX', 'XX..XXX..XX', 'XX..XXX..XX', 'XXXXX.XXXXX', 'XXXX.X.XXXX', '.XXXXXXXXX.'],
};
function emblemBoxes(F, color, rows, cell, top, z) {
  rows.forEach((row, r) => {
    for (let i = 0; i < row.length;) {
      if (row[i] !== 'X') { i++; continue; }
      let j = i; while (j < row.length && row[j] === 'X') j++;
      const x0 = (i - row.length / 2) * cell, x1 = (j - row.length / 2) * cell;
      F.box(color, x0, top + r * cell, z, x1, top + (r + 1) * cell, z + 0.12);
      i = j;
    }
  });
}
// Cloth bend for the cape: curls back more towards the bottom, with a soft ripple (same as the mod).
function makeBend(amount, t) {
  const STEP = 0.5, n = 34, th = [], cy = [0], cz = [0];
  for (let k = 0; k < n; k++) {
    const y = k * STEP, f = Math.min(1, y / 16);
    th[k] = amount * f * f + 0.06 * f * Math.sin(t * 0.15 - y * 0.45);
    if (k > 0) { const mid = (th[k] + th[k - 1]) / 2; cy[k] = cy[k - 1] + STEP * Math.cos(mid); cz[k] = cz[k - 1] + STEP * Math.sin(mid); }
  }
  return ([x, y, z]) => {
    const k = Math.max(0, Math.min(n - 1.001, y / STEP)), i = Math.floor(k), f = k - i;
    const a = th[i] + (th[i + 1] - th[i]) * f, by = cy[i] + (cy[i + 1] - cy[i]) * f, bz = cz[i] + (cz[i + 1] - cz[i]) * f;
    return [x, by - z * Math.sin(a), bz + z * Math.cos(a)];
  };
}
const CAPE_SLICES = 16;
const capeSwing = (t) => (6 + Math.sin(t * 0.05) * 3) * Math.PI / 180;
function capeFaces(F, it, t) {
  const saved = F.p.copy();
  F.p.translate(0, 0, 2.1).rotate(rx(capeSwing(t)));
  F.bend = makeBend(0.25, t);
  const meow = !(it.custom && cos.capeSheet) && it.style === 'catmeow';
  F.boxY(meow ? MEOW_EDGE : it.color, -5, 0, 0, 5, 16, 1, CAPE_SLICES);
  const sheet = it.custom && cos.capeSheet ? { img: cos.capeSheet, fw: CAPE_W, fh: CAPE_H, frames: it.custom.frames, delay: it.custom.delay }
    : it.style === 'catmeow' && MEOW_SHEET.img ? MEOW_SHEET : null;
  if (sheet) {
    for (let i = 0; i < CAPE_SLICES; i++) {
      const ya = (16 * i) / CAPE_SLICES, yb = (16 * (i + 1)) / CAPE_SLICES;
      F.list.push({ pic: true, layer: 1, sheet, v0: i / CAPE_SLICES, v1: (i + 1) / CAPE_SLICES, color: it.color, pts: [[5, ya, 1.02], [-5, ya, 1.02], [-5, yb, 1.02], [5, yb, 1.02]].map((q) => F.p.apply(F.bend(q))) });
    }
    if (meow) { const line = capeLine(it.line); F.layer = 2; if (line) capeTextFaces(F, line, t); F.layer = 0; }
    F.bend = null;
    F.p = saved;
    return;
  }
  F.layer = 2;
  F.boxY(it.trim, -5, 0, 1, -4.2, 16, 1.1, CAPE_SLICES);
  F.boxY(it.trim, 4.2, 0, 1, 5, 16, 1.1, CAPE_SLICES);
  F.box(it.trim, -5, 15.2, 1, 5, 16, 1.1);
  if (EMBLEMS[it.style]) emblemBoxes(F, it.trim, EMBLEMS[it.style], Math.min(0.75, 7.6 / EMBLEMS[it.style][0].length), 4.5, 1);
  F.layer = 0;
  F.bend = null;
  F.p = saved;
}

// Glowing trim patterns (# = glow colour, + = accent). Same as the mod.
const TRIM_PATTERNS = {"paws": {"body": ["########", "........", "..#..#..", ".#.##.#.", "...##...", "..####..", "..####..", "........", "........", "+......+", ".+....+.", "..++++.."], "arm": ["####", "....", "....", "....", ".#..", "..#.", ".#..", "....", "....", "....", "++++", "...."], "leg": ["....", "....", "....", "....", ".##.", "#..#", ".##.", "....", "....", "....", "####", "+..+"]}, "stars": {"body": ["+......+", "...#....", "..###...", "...#....", "......+.", ".+......", ".....#..", "....###.", ".....#..", "..+.....", "........", "+..++..+"], "arm": ["+..+", "....", ".#..", "###.", ".#..", "....", "..+.", "....", ".+..", "....", "....", "++++"], "leg": ["....", ".+..", "....", "..#.", ".###", "..#.", "....", "+...", "....", "..+.", "####", "...."]}, "hearts": {"body": ["++++++++", "........", ".##..##.", "########", "########", ".######.", "..####..", "...##...", "........", "#..#....", ".##.#..#", "....#.##"], "arm": ["++++", "....", "....", "....", "....", "#...", ".#.#", "..#.", "....", "....", "....", "++++"], "leg": ["....", "....", "....", "....", ".##.", "####", "####", ".##.", "....", "....", "++++", "...."]}, "circuit": {"body": ["########", "#......#", "#.####.#", "#.#..#.#", "#.####.#", "#..##..#", "#..##..#", "#......#", "#.+..+.#", "#......#", "#......#", "########"], "arm": ["####", "#..#", "#..#", "#++#", "#..#", "#..#", "#..#", "#..#", "#++#", "#..#", "#..#", "####"], "leg": ["####", "#..#", "#..#", "#..#", "#++#", "#..#", "#..#", "#..#", "#++#", "#..#", "#..#", "####"]}};

// Standing-pose pivots of each body part, as in the game.
const PARTS = { head: [0, 0], body: [0, 0], rightArm: [-5, 2], leftArm: [5, 2], rightLeg: [-1.9, 12], leftLeg: [1.9, 12] };
function trimGrid(F, grid, x0, y0, cellW, d, glow, accent, rowFrom = 0, rowTo = 12) {
  const cols = grid[0].length;
  for (const side of [0, 1]) {
    const z = side === 0 ? -(2 + d) : 2 + d;
    grid.forEach((row, r) => {
      if (r < rowFrom || r >= rowTo) return;
      for (let c = 0; c < cols;) {
        const ch = row[c];
        if (ch !== '#' && ch !== '+') { c++; continue; }
        let e = c; while (e < cols && row[e] === ch) e++;
        const [xa, xb] = side === 0 ? [x0 + c * cellW, x0 + e * cellW] : [x0 + (cols - e) * cellW, x0 + (cols - c) * cellW];
        F.box(ch === '#' ? glow : accent, xa, y0 + r, z - 0.06, xb, y0 + r + 1, z + 0.06, true);
        c = e;
      }
    });
  }
}
function trimFaces(F, it, t) {
  const pat = TRIM_PATTERNS[it.style] || TRIM_PATTERNS.paws;
  const pulse = 1 + 0.15 * Math.sin(t * 0.12); // brightens and dims, so it glows
  const glow = shade(it.color, pulse), accent = shade(it.accent, pulse), d = 1.05;
  // Trims only show on armour, so the preview puts you in a dark armour set (like the game, 1 pixel out).
  const ARMOR = '#3a3640', ARMOR2 = '#2c2931';
  const shell = (part, x0, y0, z0, x1, y1, z1, col = ARMOR) => { const saved = F.p.copy(); F.p.translate(PARTS[part][0], PARTS[part][1], 0); F.box(col, x0, y0, z0, x1, y1, z1); F.p = saved; };
  shell('head', -5, -9, -5, 5, -5.5, 5);
  shell('body', -5, -1, -3, 5, 13, 3);
  shell('rightArm', -4, -3, -3, 2, 11, 3, ARMOR2);
  shell('leftArm', -2, -3, -3, 4, 11, 3, ARMOR2);
  shell('rightLeg', -3, -1, -3, 3, 13, 3);
  shell('leftLeg', -3, -1, -3, 3, 13, 3);
  const at = (part, fn) => { const saved = F.p.copy(); F.p.translate(PARTS[part][0], PARTS[part][1], 0); fn(); F.p = saved; };
  at('head', () => {
    const o = 4 + d + 0.02;
    F.box(glow, -o, -6, -o - 0.06, o, -5.4, -o + 0.06, true);
    F.box(glow, -o, -6, o - 0.06, o, -5.4, o + 0.06, true);
    F.box(accent, -0.9, -6.6, -o - 0.14, 0.9, -4.8, -o + 0.02, true);
  });
  at('body', () => trimGrid(F, pat.body, -4, 0, 1, d, glow, accent));
  at('rightArm', () => trimGrid(F, pat.arm, -2.25, -2, 0.75, d, glow, accent));
  at('leftArm', () => trimGrid(F, pat.arm, -0.75, -2, 0.75, d, glow, accent));
  at('rightLeg', () => trimGrid(F, pat.leg, -2, 0, 1, d, glow, accent));
  at('leftLeg', () => trimGrid(F, pat.leg, -2, 0, 1, d, glow, accent));
}

function cosmeticFaces(t) {
  const F = new Faces(), it = cos.items;
  if (it.trim.on) trimFaces(F, it.trim, t);
  if (it.cape.on) capeFaces(F, it.cape, t);
  if (it.wings.on) wingFaces(F, it.wings, t);
  if (it.halo.on) haloFaces(F, it.halo, t);
  if (it.horns.on) hornFaces(F, it.horns, t);
  if (it.pet.on) petFaces(F, it.pet, t);
  return F.list;
}

// Paint faces back to front. "behind" picks the ones hidden behind the player's body.
function drawFaces(ctx, faces, view, behind) {
  const depth = (f) => f.pts.reduce((a, p) => a + (view === 'side' ? p[0] : view === 'back' ? -p[2] : p[2]), 0) / f.pts.length; // bigger = further away
  // Sort by each face's farthest corner so small details (trim, emblems) land on top of the big face they sit on.
  const far = (f) => Math.max(...f.pts.map((p) => (view === 'side' ? p[0] : view === 'back' ? -p[2] : p[2])));
  const list = faces.filter((f) => (depth(f) > (view === 'side' ? 0.5 : 1.5)) === behind).sort((a, b) => (far(b) - (b.layer || 0) * 4) - (far(a) - (a.layer || 0) * 4)); // things stuck on the cape go on top
  for (const f of list) {
    if (f.pic) { drawCapePicture(ctx, view, f); continue; }
    const [a, b, c] = f.pts;
    const n = [(b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]), (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]), (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])];
    const len = Math.hypot(...n) || 1;
    const facing = Math.abs((view === 'side' ? n[0] : n[2]) / len);
    poly(ctx, view, f.pts, f.glow ? f.color : shade(f.color, 0.72 + 0.28 * facing));
  }
}

// Your cape picture, stretched onto the back of the cape (only visible from behind).
const CAPE_LINES = ["meow!", "nya~", "nyaa~!", "mrrp?", "purr~", "mew!", ":3", "uwu"];

const MEOW_EDGE = '#5b6fd6'; // the art's blue, so the whole cape matches
function capeLine(chosen) {
  if (chosen === 'none') return '';
  return CAPE_LINES.includes(chosen) ? chosen : CAPE_LINES[Math.floor(Date.now() / 2500) % CAPE_LINES.length];
}
// The catgirl's line near the bottom of the cape (from cape-lines.png), bouncing and wiggling. Same as the mod.
const LINES_SHEET = { img: null, fw: 256, fh: 80 * 8, frames: 1, delay: 1000 };
(() => { const i = new Image(); i.onload = () => { LINES_SHEET.img = i; }; i.src = 'assets/cape-lines.png'; })();
function capeTextFaces(F, line, t) {
  const row = Math.max(0, CAPE_LINES.indexOf(line));
  const w = 9.4, h = (w * 80) / 256, cy = 13.6 - Math.abs(Math.sin(t * 0.35)) * 0.5;
  const a = Math.sin(t * 0.18) * 0.12, c = Math.cos(a), sn = Math.sin(a);
  const pts = [[w / 2, -h / 2], [-w / 2, -h / 2], [-w / 2, h / 2], [w / 2, h / 2]].map(([x, y]) => [x * c - y * sn, cy + x * sn + y * c, 1.12]);
  F.list.push({ pic: true, layer: 2, sheet: LINES_SHEET, v0: row / 8, v1: (row + 1) / 8, pts: pts.map((q) => F.p.apply(F.bend ? F.bend(q) : q)) });
}

// The built-in animated "Catgirl meow" cape (same picture the game uses).
const MEOW_SHEET = { img: null, fw: 120, fh: 192, frames: 16, delay: 90 };
(() => { const i = new Image(); i.onload = () => { MEOW_SHEET.img = i; }; i.src = 'assets/cape-meow.png'; })();

function drawCapePicture(ctx, view, f) {
  const sh0 = f.sheet;
  if (view !== 'back' || !sh0?.img) return;
  const [p0, p1, , p3] = f.pts.map((p) => VIEW[view].at(p).map((v) => v * S));
  const frame = Math.floor(performance.now() / sh0.delay) % sh0.frames;
  const sy = frame * sh0.fh + f.v0 * sh0.fh, sh = (f.v1 - f.v0) * sh0.fh;
  ctx.save();
  ctx.setTransform((p1[0] - p0[0]) / sh0.fw, (p1[1] - p0[1]) / sh0.fw, (p3[0] - p0[0]) / sh, (p3[1] - p0[1]) / sh, p0[0], p0[1]);
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(sh0.img, 0, sy, sh0.fw, sh, 0, 0, sh0.fw, sh + 1); // a little overlap hides seams
  ctx.restore();
}

/* ---------- your own cape picture or GIF ---------- */
const CAPE_W = 60, CAPE_H = 96, CAPE_MAX_FRAMES = 32, CAPE_MAX_PNG = 1536 * 1024;

// Decodes every frame of a picture or (animated) GIF/WebP/PNG. Returns [{ img, ms }], closing nothing.
async function decodeFrames(bytes, type, base64) {
  if (type === 'image/gif') {
    const g = decodeGif(bytes);
    return g.frames.map((f) => {
      const c = document.createElement('canvas');
      c.width = g.width; c.height = g.height;
      c.getContext('2d').putImageData(new ImageData(f.rgba, g.width, g.height), 0, 0);
      return { img: c, ms: f.ms };
    });
  }
  if ('ImageDecoder' in window && (await ImageDecoder.isTypeSupported(type))) {
    const dec = new ImageDecoder({ data: bytes, type });
    await dec.completed;
    const count = dec.tracks.selectedTrack?.frameCount || 1;
    const out = [];
    for (let i = 0; i < count; i++) {
      const { image } = await dec.decode({ frameIndex: i });
      out.push({ img: image, ms: (image.duration || 100000) / 1000 });
    }
    dec.close();
    return out;
  }
  const img = await loadImg(`data:${type};base64,${base64}`);
  return [{ img, ms: 100 }];
}

// Crops each frame to the cape's shape (like "cover") and stacks them into one PNG strip.
function buildStrip(frames, maxFrames) {
  const step = Math.max(1, frames.length / maxFrames);
  const picked = [];
  for (let i = 0; i < frames.length && picked.length < maxFrames; i += step) picked.push(Math.floor(i));
  const totalMs = frames.reduce((a, f) => a + f.ms, 0);
  const delay = Math.max(20, Math.min(2000, Math.round(totalMs / picked.length)));
  const cv = document.createElement('canvas');
  cv.width = CAPE_W; cv.height = CAPE_H * picked.length;
  const ctx = cv.getContext('2d');
  ctx.imageSmoothingQuality = 'high';
  picked.forEach((idx, n) => {
    const im = frames[idx].img;
    const w = im.displayWidth || im.naturalWidth || im.width, h = im.displayHeight || im.naturalHeight || im.height;
    const scale = Math.max(CAPE_W / w, CAPE_H / h);
    const sw = CAPE_W / scale, sh = CAPE_H / scale;
    ctx.drawImage(im, (w - sw) / 2, (h - sh) / 2, sw, sh, 0, n * CAPE_H, CAPE_W, CAPE_H);
  });
  return { dataUrl: cv.toDataURL('image/png'), frames: picked.length, delay };
}

async function pickCapePicture() {
  const file = await safe(() => window.cat.cosmetics.pickCapeFile());
  if (!file) return;
  $('#cosStatus').textContent = 'Making your cape…';
  try {
    const bytes = Uint8Array.from(atob(file.base64), (c) => c.charCodeAt(0));
    const frames = await decodeFrames(bytes, file.type, file.base64);
    let strip, max = CAPE_MAX_FRAMES;
    for (;;) {
      strip = buildStrip(frames, max);
      if ((strip.dataUrl.length * 3) / 4 <= CAPE_MAX_PNG || max <= 1) break;
      max = Math.max(1, Math.floor(max / 2)); // too big: use fewer frames
    }
    frames.forEach((f) => f.img.close?.());
    const meta = await window.cat.cosmetics.saveCapePicture({ png: strip.dataUrl.split(',')[1], frames: strip.frames, delay: strip.delay });
    cos.capeSheet = await loadImg(strip.dataUrl);
    cos.items.cape.custom = meta;
    setCos('cape', 'on', true);
    renderCosItems();
    if (cos.view2 !== 'back') $('#cosView2 [data-v="back"]').click();
    $('#cosStatus').textContent = 'Not saved yet';
    toast(meta.frames > 1 ? `Cape GIF ready (${meta.frames} frames)! Click Save to wear it.` : 'Cape picture ready! Click Save to wear it.', 'ok');
  } catch (e) {
    $('#cosStatus').textContent = '';
    toast(`Couldn't use that picture: ${e.message}`, 'err');
  }
}

function drawPreview(t) {
  for (const view of ['front', cos.view2]) {
    const cv = $(view === 'front' ? '#cosFront' : '#cosSide');
    if (!cv) continue;
    if (cv.width !== GRID[0] * S) { cv.width = GRID[0] * S; cv.height = GRID[1] * S; }
    const ctx = cv.getContext('2d');
    ctx.clearRect(0, 0, cv.width, cv.height);
    const it = cos.items;
    const faces = cosmeticFaces(t);
    drawFaces(ctx, faces, view, true);
    if (it.tail.on && view !== 'back') drawTail(ctx, it.tail, view, t); // behind the body
    drawSkin(ctx, cos.skin, cos.variant, view);
    if (it.tail.on && view === 'back') drawTail(ctx, it.tail, view, t);
    if (it.ears.on) drawEars(ctx, it.ears, view, t);
    if (it.bow.on) drawBow(ctx, it.bow, view);
    drawFaces(ctx, faces, view, false);
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
function presetsFor(item, key) { return PRESETS[`${item}.${key}`] || PRESETS[item] || (item === 'bow' ? BOW_PRESETS : key === 'inner' ? INNER_PRESETS : FUR_PRESETS); }

function renderCosItems() {
  $('#cosItems').innerHTML = COS_ITEMS.map((d) => {
    const v = cos.items[d.id];
    return `<div class="cos-item ${v.on ? 'on' : ''}" data-item="${d.id}">
      <header><span class="emoji">${d.emoji}</span><b>${d.name}</b>
        <label class="switch" title="Wear ${d.name.toLowerCase()}"><input type="checkbox" data-on="${d.id}" ${v.on ? 'checked' : ''} /><i></i></label></header>
      ${d.hint ? `<small class="muted cos-hint">${d.hint}</small>` : ''}
      <div class="colors">${d.styles ? `<div class="seg cos-style">${d.styles.map(([k, l]) => `<button data-style="${d.id}" data-v="${k}" class="${v.style === k ? 'on' : ''}">${l}</button>`).join('')}</div>` : ''}${d.colors.map(([key, label]) => `
        <div class="cos-color"><span>${label}</span><input type="color" data-color="${d.id}.${key}" value="${esc(v[key])}" />
          <div class="presets-dots">${presetsFor(d.id, key).map((c) => `<button style="background:${c}" data-preset="${d.id}.${key}" data-c="${c}" title="${c}"></button>`).join('')}</div></div>`).join('')}

      </div>
        ${d.id === 'cape' && v.style === 'catmeow' && !v.custom ? `<div class="cos-lines"><span>Catgirl says</span><div class="seg wrap">${[['cycle', 'All of them'], ...CAPE_LINES.map((l) => [l, l]), ['none', 'Nothing']].map(([k, l]) => `<button data-line="${esc(k)}" class="${(v.line || 'cycle') === k ? 'on' : ''}">${esc(l)}</button>`).join('')}</div></div>` : ''}
        ${d.id === 'cape' ? `<div class="cos-pic">
          <button class="ghost small" data-act="cape-pick">🖼️ ${v.custom ? 'Change picture' : 'Use my own picture or GIF…'}</button>
          ${v.custom ? `<button class="ghost small" data-act="cape-clear">Remove picture</button>` : ''}
          <small class="muted">${v.custom ? `Your picture is on the back${v.custom.frames > 1 ? ` (${v.custom.frames}-frame GIF)` : ''}. Colours above are for the edges.` : 'PNG, JPG, GIF or WebP. Everyone sees it, so keep it friendly.'}</small>
        </div>` : ''}</div>`;
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
$('#cosItems').addEventListener('click', (e) => {
  const p = e.target.closest('[data-preset]');
  if (p) { const [i, k] = p.dataset.preset.split('.'); setCos(i, k, p.dataset.c); }
  const act = e.target.closest('[data-act]');
  if (act?.dataset.act === 'cape-pick') pickCapePicture();
  if (act?.dataset.act === 'cape-clear') { delete cos.items.cape.custom; cos.capeSheet = null; setCos('cape', 'on', cos.items.cape.on); renderCosItems(); }
  const ln = e.target.closest('[data-line]');
  if (ln) { setCos('cape', 'line', ln.dataset.line); $$('[data-line]').forEach((b) => b.classList.toggle('on', b === ln)); }
  const st = e.target.closest('[data-style]');
  if (st) {
    const item = st.dataset.style, old = cos.items[item].style;
    setCos(item, 'style', st.dataset.v);
    // Swap to the new style's usual colour if you hadn't picked your own.
    if (item === 'wings' && cos.items.wings.color === WING_DEFAULT[old]) setCos('wings', 'color', WING_DEFAULT[st.dataset.v]);
    $$(`[data-style="${item}"]`).forEach((b) => b.classList.toggle('on', b === st));
    if (item === 'cape') renderCosItems(); // show or hide the catgirl's lines
  }
});

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
  cos.items = structuredClone(COS_DEFAULTS);
  for (const k of Object.keys(COS_DEFAULTS)) Object.assign(cos.items[k], data.items?.[k] || {});
  cos.variant = data.skin?.variant || 'classic';
  cos.capeSheet = data.capePicture ? await loadImg(data.capePicture).catch(() => null) : null;
  cos.skin = data.skin ? await loadImg(data.skin.dataUrl).catch(() => null) : null;
  cos.loaded = true;
  renderCosItems();
  $('#cosStatus').textContent = data.synced ? 'Saved ✓' : COS_ITEMS.some((d) => data.items[d.id]?.on) ? "Saved on this PC (not shared online yet)" : '';
}

$$('#cosView2 button').forEach((b) => b.addEventListener('click', () => {
  cos.view2 = b.dataset.v;
  $$('#cosView2 button').forEach((x) => x.classList.toggle('on', x === b));
}));
