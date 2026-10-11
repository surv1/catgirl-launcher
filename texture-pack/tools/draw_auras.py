"""Catgirl Pack: a soft glowing aura with drifting sparkles around weapons while they are held
(in your hand, in first and third person). In the hotbar, item frames and on the ground they
look normal. Usage: python3 draw_auras.py <pack root (the folder with assets/)>"""
import json
import math
import os
import sys
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import draw_weapons as dw  # noqa: E402

N = dw.N
FRAMES = 8
PINK = (255, 126, 182)


def head_mask(kind, img):
    """Pixels of the blade / axe head / mace head (not the handle) in the resting frame."""
    px = img.load()
    m = set()
    for y in range(N):
        for x in range(N):
            if px[x, y][3] == 0:
                continue
            if kind == 'sword':
                t, side = dw.seg_param(x + 0.5, y + 0.5, 10.5, 20.5, 28.5, 2.5)
                ok = 0.08 <= t <= 1.05 and abs(side) <= 3.5
            elif kind == 'axe':
                ok = math.hypot(x + 0.5 - 17.0, y + 0.5 - 6.0) <= 9.0 and not (x > 19 and y > 6)
            else:  # mace
                ok = math.hypot(x + 0.5 - 21.5, y + 0.5 - 10.5) <= 9.5
            if ok:
                m.add((x, y))
    return m


def aura_frames(kind, rest, glow):
    mask = head_mask(kind, rest)
    rpx = rest.load()
    pts = list(mask)
    col = dw.mix(glow, PINK, 0.5)
    frames = []
    # sparkles: a few points that rise along the blade over the loop
    sparks = [(0.1, 0.3), (0.45, 0.75), (0.8, 0.15)]
    xs = sorted(pts, key=lambda p: p[0] - p[1])  # from the hilt end towards the tip
    for f in range(FRAMES):
        pulse = 0.55 + 0.45 * (0.5 + 0.5 * math.sin(f / FRAMES * 2 * math.pi))
        img = Image.new('RGBA', (N, N), (0, 0, 0, 0))
        px = img.load()
        for y in range(N):
            for x in range(N):
                if rpx[x, y][3]:
                    continue  # never on top of the weapon itself
                d = min((math.hypot(x - a, y - b) for a, b in pts), default=99)
                if d <= 3.2:
                    a = (1 - (d - 0.6) / 2.8) ** 1.3 if d > 0.6 else 1.0
                    px[x, y] = (*col, max(0, min(255, round(200 * a * pulse))))
        for start, side in sparks:
            k = (start + f / FRAMES) % 1.0
            sx, sy = xs[min(len(xs) - 1, int(k * len(xs)))]
            # sparkle sits just outside the blade, on alternating sides
            ox, oy = (2, 2) if side < 0.5 else (-2, -2)
            for dx, dy, a in ((0, 0, 255), (1, 0, 150), (-1, 0, 150), (0, 1, 150), (0, -1, 150)):
                x, y = sx + ox + dx, sy + oy + dy
                if 0 <= x < N and 0 <= y < N and not rpx[x, y][3]:
                    fade = math.sin(k * math.pi)
                    px[x, y] = (255, 240, 250, round(a * fade))
        frames.append(img)
    return frames


def main(root):
    tex = os.path.join(root, 'assets', 'catgirl', 'textures', 'item', 'aura')
    mdl = os.path.join(root, 'assets', 'catgirl', 'models', 'item', 'aura')
    items = os.path.join(root, 'assets', 'minecraft', 'items')
    for d in (tex, mdl, items):
        os.makedirs(d, exist_ok=True)
    jobs = [(f'{t}_{k}', k, fn, t) for t in dw.TIERS for k, fn in (('sword', dw.sword), ('axe', dw.axe))]
    jobs.append(('mace', 'mace', dw.mace, 'iron'))
    for name, kind, fn, tier in jobs:
        rest = fn(tier, 8)
        glow = dw.PINK_HI if kind == 'mace' else dw.TIERS[tier]['glow']
        fr = aura_frames(kind, rest, glow)
        strip = Image.new('RGBA', (N, N * FRAMES))
        for i, im in enumerate(fr):
            strip.paste(im, (0, N * i))
        strip.save(os.path.join(tex, f'{name}.png'))
        with open(os.path.join(tex, f'{name}.png.mcmeta'), 'w') as f:
            json.dump({'animation': {'frametime': 2}}, f)
        with open(os.path.join(mdl, f'{name}.json'), 'w') as f:
            json.dump({'parent': f'minecraft:item/{name}', 'textures': {'layer0': f'catgirl:item/aura/{name}'}}, f, indent=1)
        base = {'type': 'minecraft:model', 'model': f'minecraft:item/{name}'}
        with open(os.path.join(items, f'{name}.json'), 'w') as f:
            json.dump({'model': {
                'type': 'minecraft:select', 'property': 'minecraft:display_context',
                'cases': [{'when': ['gui', 'fixed', 'ground', 'head'], 'model': base}],
                'fallback': {'type': 'minecraft:composite', 'models': [
                    base, {'type': 'minecraft:model', 'model': f'catgirl:item/aura/{name}'}]},
            }}, f, indent=1)


if __name__ == '__main__':
    main(sys.argv[1])
