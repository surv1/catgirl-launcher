"""A small preview renderer for Minecraft block/item model JSON (elements, face UVs, element
rotation, display transforms). Used to check models before trying them in the game.
Usage: python3 render_model.py <model.json> <texture.png> <out.png> [display ...]"""
import json
import math
import sys
from PIL import Image, ImageDraw

CORNERS = {  # TL, TR, BR, BL as (x, y, z) picks: 0 = from, 1 = to
    'south': ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)),
    'north': ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)),
    'west': ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)),
    'east': ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)),
    'up': ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)),
    'down': ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)),
}
LIGHT = {'up': 1.0, 'south': 0.92, 'north': 0.92, 'east': 0.8, 'west': 0.8, 'down': 0.6}


def rot(p, axis, deg, o):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    if axis == 'x': y, z = y * c - z * s, y * s + z * c
    elif axis == 'y': x, z = x * c + z * s, -x * s + z * c
    else: x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def render(model, tex, display=None, px=24, size=None, bg=(54, 40, 66, 255)):
    W = size or px * 26
    img = Image.new('RGBA', (W, W), bg)
    d = ImageDraw.Draw(img)
    tw, th = tex.size
    polys = []
    dt = (model.get('display', {}) or {}).get(display) if display else None
    for e in model['elements']:
        f, t = e['from'], e['to']
        for face, fd in e['faces'].items():
            u0, v0, u1, v1 = fd['uv']
            corners = []
            for cx, cy, cz in CORNERS[face]:
                p = (t[0] if cx else f[0], t[1] if cy else f[1], t[2] if cz else f[2])
                if 'rotation' in e:
                    r = e['rotation']
                    p = rot(p, r['axis'], r['angle'], r['origin'])
                if dt:
                    q = [p[i] - 8 for i in range(3)]
                    sc = dt.get('scale', [1, 1, 1])
                    q = [q[i] * sc[i] for i in range(3)]
                    rx, ry, rz = dt.get('rotation', [0, 0, 0])
                    q = rot(q, 'x', rx, (0, 0, 0)); q = rot(q, 'y', ry, (0, 0, 0)); q = rot(q, 'z', rz, (0, 0, 0))
                    tr = dt.get('translation', [0, 0, 0])
                    p = tuple(q[i] + 8 + tr[i] for i in range(3))
                corners.append(p)
            # back-face cull (viewer looks towards -z)
            a, b, c = corners[0], corners[1], corners[3]
            nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            if nz > 0:
                continue
            nx_ = int(round(abs(u1 - u0) * tw / 16)) or 1
            ny_ = int(round(abs(v1 - v0) * th / 16)) or 1
            for j in range(ny_):
                for i in range(nx_):
                    su = u0 + (u1 - u0) * (i + 0.5) / nx_
                    sv = v0 + (v1 - v0) * (j + 0.5) / ny_
                    col = tex.getpixel((min(tw - 1, int(su * tw / 16)), min(th - 1, int(sv * th / 16))))
                    if col[3] < 128:
                        continue
                    def at(fx, fy):
                        top = [corners[0][k] + (corners[1][k] - corners[0][k]) * fx for k in range(3)]
                        bot = [corners[3][k] + (corners[2][k] - corners[3][k]) * fx for k in range(3)]
                        return [top[k] + (bot[k] - top[k]) * fy for k in range(3)]
                    q = [at(i / nx_, j / ny_), at((i + 1) / nx_, j / ny_), at((i + 1) / nx_, (j + 1) / ny_), at(i / nx_, (j + 1) / ny_)]
                    z = sum(p[2] for p in q) / 4
                    k = LIGHT[face]
                    polys.append((z, [(W / 2 + (p[0] - 8) * px, W / 2 - (p[1] - 8) * px) for p in q],
                                  tuple(min(255, int(col[m] * k)) for m in range(3)) + (255,)))
    polys.sort(key=lambda p: p[0])
    for _, pts, col in polys:
        d.polygon(pts, fill=col)
    return img


if __name__ == '__main__':
    m = json.load(open(sys.argv[1]))
    tx = Image.open(sys.argv[2]).convert('RGBA')
    views = sys.argv[4:] or [None]
    out = Image.new('RGBA', (len(views) * 26 * 16, 26 * 16))
    for i, v in enumerate(views):
        out.paste(render(m, tx, v, px=16), (i * 26 * 16, 0))
    out.save(sys.argv[3])
