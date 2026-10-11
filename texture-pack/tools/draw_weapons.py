"""Catgirl Pack: animated swords and axes (32x32, 9 frames: a shine runs up the blade, a sparkle
twinkles at the tip, then it rests). Writes <tier>_sword.png / <tier>_axe.png (+ .mcmeta) into out_dir.
Usage: python3 draw_weapons.py <out_dir>"""
import json
import math
import os
import sys
from PIL import Image

N = 32
TIERS = {  # blade: light, mid, dark, deep ; handle colours ; glow colour for the shine
    'wooden':    dict(blade=[(214, 170, 112), (176, 130, 76), (132, 94, 52), (86, 60, 32)], grip=(96, 62, 34), glow=(255, 226, 170)),
    'stone':     dict(blade=[(200, 200, 204), (156, 156, 162), (112, 112, 118), (70, 70, 76)], grip=(96, 62, 34), glow=(240, 240, 255)),
    'copper':    dict(blade=[(255, 190, 150), (226, 128, 82), (166, 82, 52), (84, 150, 128)], grip=(96, 62, 34), glow=(255, 230, 200)),
    'iron':      dict(blade=[(255, 255, 255), (216, 216, 222), (168, 168, 178), (104, 104, 116)], grip=(80, 54, 34), glow=(255, 255, 255)),
    'golden':    dict(blade=[(255, 250, 190), (250, 214, 84), (214, 150, 38), (150, 92, 22)], grip=(80, 54, 34), glow=(255, 255, 220)),
    'diamond':   dict(blade=[(220, 255, 250), (120, 236, 224), (42, 186, 178), (18, 110, 112)], grip=(70, 48, 32), glow=(255, 200, 236)),
    'netherite': dict(blade=[(120, 108, 122), (76, 66, 80), (52, 44, 56), (30, 26, 34)], grip=(40, 32, 40), glow=(255, 126, 182)),
}
PINK, PINK_HI, PINK_DK = (255, 126, 182), (255, 214, 236), (176, 52, 112)
OUTLINE = (34, 22, 30)
FRAMES = 9


def seg_param(px, py, ax, ay, bx, by):
    """Position along segment a→b (0..1) and signed distance from it."""
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy
    t = ((px - ax) * dx + (py - ay) * dy) / L2
    L = math.sqrt(L2)
    side = ((px - ax) * dy - (py - ay) * dx) / L
    return t, side


def mix(c, d, k):
    return tuple(round(c[i] + (d[i] - c[i]) * k) for i in range(3))


def star(px, cx, cy, r, col, a=255):
    for i in range(-r, r + 1):
        for (x, y) in ((cx + i, cy), (cx, cy + i)):
            if 0 <= x < N and 0 <= y < N:
                k = 1 - abs(i) / (r + 1)
                o = px[x, y]
                px[x, y] = (*mix(o[:3] if o[3] else col, col, k), max(o[3], round(a * (0.5 + k / 2))))


def outline(img):
    px = img.load()
    solid = [[px[x, y][3] > 0 for x in range(N)] for y in range(N)]
    for y in range(N):
        for x in range(N):
            if not solid[y][x] and any(0 <= x + dx < N and 0 <= y + dy < N and solid[y + dy][x + dx]
                                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = (*OUTLINE, 255)


def sword(tier, frame):
    pal = TIERS[tier]
    lt, md, dk, dp = pal['blade']
    img = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    px = img.load()
    A, B = (10.5, 20.5), (28.5, 2.5)  # blade from guard to tip
    shine = frame / 7 if frame < 8 else None
    for y in range(N):
        for x in range(N):
            t, side = seg_param(x + 0.5, y + 0.5, *A, *B)
            if 0 <= t <= 1:
                w = 2.2 * (1 - t) ** 0.35 + 0.4
                if abs(side) <= w:
                    c = lt if side < -w * 0.35 else md if side < w * 0.35 else dk
                    if abs(side) < 0.5: c = mix(c, lt, 0.4)  # centre ridge
                    if shine is not None and abs(t - shine) < 0.09:
                        c = mix(c, pal['glow'], 0.85)
                    px[x, y] = (*c, 255)
    # guard (across the blade) with a pink gem
    for y in range(N):
        for x in range(N):
            t, side = seg_param(x + 0.5, y + 0.5, 6.5, 16.5, 14.5, 24.5)
            if 0 <= t <= 1 and abs(side) <= 1.2:
                px[x, y] = (*(dp if side > 0.3 else dk), 255)
    for (x, y) in ((10, 20), (11, 20), (10, 21), (11, 21)):
        px[x, y] = (*PINK, 255)
    px[10, 20] = (*PINK_HI, 255)
    # grip and a pink paw pommel
    for y in range(N):
        for x in range(N):
            t, side = seg_param(x + 0.5, y + 0.5, 9.5, 22.5, 4.5, 27.5)
            if 0 <= t <= 1 and abs(side) <= 1.0:
                px[x, y] = (*(mix(pal['grip'], (0, 0, 0), 0.3) if (x + y) % 3 == 0 else pal['grip']), 255)
    for (x, y) in ((3, 27), (4, 27), (5, 27), (3, 28), (4, 28), (5, 28), (3, 29), (4, 29), (5, 29)):
        px[x, y] = (*PINK, 255)
    for (x, y) in ((3, 27), (5, 27), (3, 29), (5, 29)):
        px[x, y] = (0, 0, 0, 0)  # round it off
    px[3, 28] = (*PINK_HI, 255); px[4, 27] = (*PINK_HI, 255)
    px[5, 28] = (*PINK_DK, 255); px[4, 29] = (*PINK_DK, 255)
    outline(img)
    if frame in (5, 6, 7):  # sparkle at the tip
        star(px, 27, 4, (1, 2, 1)[frame - 5], pal['glow'])
    return img


def axe(tier, frame):
    pal = TIERS[tier]
    lt, md, dk, dp = pal['blade']
    img = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    px = img.load()
    # handle from bottom-left to top-right
    for y in range(N):
        for x in range(N):
            t, side = seg_param(x + 0.5, y + 0.5, 5.5, 27.5, 24.5, 6.5)
            if 0 <= t <= 1 and abs(side) <= 1.0:
                px[x, y] = (*(mix(pal['grip'], (0, 0, 0), 0.3) if (x + y) % 3 == 0 else pal['grip']), 255)
    # head: narrow where it grips the handle, flaring out to a curved cutting edge (up-left)
    shine = frame / 7 if frame < 8 else None
    hx, hy = 21.0, 9.5  # where the head meets the handle
    ux, uy = (24.5 - 5.5), (6.5 - 27.5)
    L = math.hypot(ux, uy); ux, uy = ux / L, uy / L  # along the handle (towards its top)
    nx, ny = uy, -ux  # out to the blade side
    for y in range(N):
        for x in range(N):
            rx, ry = x + 0.5 - hx, y + 0.5 - hy
            a = rx * ux + ry * uy        # along handle
            b = rx * nx + ry * ny        # outwards
            if -2.6 <= b < 0:            # small back (poll) behind the handle
                if abs(a) <= 1.9:
                    px[x, y] = (*dk, 255)
                continue
            if b < 0: continue
            half = 1.5 + b * b * 0.06 + b * 0.12
            bmax = 11.0 - a * a / 45
            if abs(a) <= half and b <= bmax:
                if b > bmax - 1.7: c = lt                    # bright sharpened edge
                elif b > bmax - 2.7: c = dk                   # bevel line
                elif a > half - 1.3: c = dk                   # shaded underside
                elif a < -half + 1.3: c = mix(md, lt, 0.5)    # lit top
                else: c = md
                if b < 1.2: c = dp                            # where it wraps the handle
                if shine is not None and b > bmax - 1.7 and abs((a + half) / (2 * half) - shine) < 0.16:
                    c = mix(c, pal['glow'], 0.9)
                px[x, y] = (*c, 255)
    # pink gem where head meets handle
    gx, gy = round(hx + nx * 4.5 - 0.5), round(hy + ny * 4.5 - 0.5)
    for (x, y) in ((gx, gy), (gx + 1, gy), (gx, gy + 1), (gx + 1, gy + 1)):
        if 0 <= x < N and 0 <= y < N: px[x, y] = (*PINK, 255)
    px[gx, gy] = (*PINK_HI, 255)
    outline(img)
    if frame in (5, 6, 7):
        star(px, round(hx + nx * 10 - ux * 4), round(hy + ny * 10 - uy * 4), (1, 2, 1)[frame - 5], pal['glow'])
    return img


def mace(tier, frame):
    """The mace: a pale breeze-rod handle and a heavy studded head with a pink heart in the middle."""
    lt, md, dk, dp = TIERS['iron']['blade']
    glow = PINK_HI
    img = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    px = img.load()
    for y in range(N):
        for x in range(N):
            t, side = seg_param(x + 0.5, y + 0.5, 5.5, 27.5, 19.5, 13.5)
            if 0 <= t <= 1 and abs(side) <= 1.1:
                c = (196, 204, 232) if side < 0 else (140, 146, 186)
                if (round(t * 14)) % 4 == 0: c = (110, 114, 160)  # rod bands
                px[x, y] = (*c, 255)
    cx, cy, R = 21.5, 10.5, 6.2
    shine = frame / 7 if frame < 8 else None
    for y in range(N):
        for x in range(N):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            spike = any(abs(dx * math.sin(a) - dy * math.cos(a)) < 0.9 and 0 < dx * math.cos(a) + dy * math.sin(a) < R + 2.2
                        for a in (math.radians(d) for d in (0, 90, 180, 270)))
            if r <= R or spike:
                lit = -(dx + dy) / (R * 1.42)  # light from the top-left
                c = lt if lit > 0.6 else md if lit > -0.1 else dk
                if r > R: c = dk if lit < 0 else md
                if r > R - 1 and r <= R: c = dp if lit < 0.2 else c
                if shine is not None and abs((dx - dy) / (2 * R) + 0.5 - shine) < 0.12 and r <= R:
                    c = mix(c, glow, 0.8)
                px[x, y] = (*c, 255)
    for dy, row in enumerate(('.x.x.', 'xxxxx', '.xxx.', '..x..')):  # heart in the middle
        for dx, ch in enumerate(row):
            if ch == 'x':
                px[round(cx) - 3 + dx, round(cy) - 2 + dy] = (*(PINK_DK if dy == 3 or (dy == 2 and dx == 3) else PINK), 255)
    px[round(cx) - 2, round(cy) - 1] = (*PINK_HI, 255)
    outline(img)
    if frame in (5, 6, 7):
        star(px, 27, 4, (1, 2, 1)[frame - 5], PINK_HI)
    return img


def strip(fn, tier):
    img = Image.new('RGBA', (N, N * FRAMES))
    for f in range(FRAMES):
        img.paste(fn(tier, f), (0, N * f))
    return img


MCMETA = {'animation': {'frames': [{'index': i, 'time': 2} for i in range(8)] + [{'index': 8, 'time': 50}]}}

if __name__ == '__main__':
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for tier in TIERS:
        for kind, fn in (('sword', sword), ('axe', axe)):
            name = f'{tier}_{kind}.png'
            strip(fn, tier).save(os.path.join(out, name))
            with open(os.path.join(out, name + '.mcmeta'), 'w') as f:
                json.dump(MCMETA, f, indent=1)
    strip(mace, 'iron').save(os.path.join(out, 'mace.png'))
    with open(os.path.join(out, 'mace.png.mcmeta'), 'w') as f:
        json.dump(MCMETA, f, indent=1)
