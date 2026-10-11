"""Catgirl Pack: a 3D "mini you" totem. A chibi figure built from a player skin (big head with the
hat layer, body, arms, legs and their overlay layers), cat ears and little golden totem wings.
Writes <out>/models/item/totem_doll.json and <out>/textures/item/totem_doll.png (namespace catgirl).
Usage: python3 draw_totem3d.py <skin.png> <assets/catgirl dir>"""
import json
import os
import sys
from PIL import Image

PINK, PINK_HI = (255, 126, 182, 255), (255, 200, 226, 255)
GOLD = [(255, 236, 140, 255), (250, 206, 70, 255), (222, 160, 34, 255), (160, 100, 20, 255)]


def skin_parts(slim):
    a = 3 if slim else 4
    # name: (u, v, w, h, d) of the base layer, (u, v) of the overlay layer
    return {
        'head': ((0, 0, 8, 8, 8), (32, 0)),
        'body': ((16, 16, 8, 12, 4), (16, 32)),
        'rarm': ((40, 16, a, 12, 4), (40, 32)),
        'larm': ((32, 48, a, 12, 4), (48, 48)),
        'rleg': ((0, 16, 4, 12, 4), (0, 32)),
        'lleg': ((16, 48, 4, 12, 4), (0, 48)),
    }


def net(u, v, w, h, d):
    """Where each side of a box lives on a skin (pixel rects), named by model faces for a figure
    looking south (+z): front=south, back=north, its right=west, its left=east."""
    return {'up': (u + d, v, u + d + w, v + d), 'down': (u + d + w, v + d, u + d + 2 * w, v),  # down is stored upside down
            'west': (u, v + d, u + d, v + d + h), 'south': (u + d, v + d, u + d + w, v + d + h),
            'east': (u + d + w, v + d, u + 2 * d + w, v + d + h), 'north': (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h)}


def faces(rects, tex='#skin'):
    return {f: {'uv': [r[0] / 4, r[1] / 4, r[2] / 4, r[3] / 4], 'texture': tex} for f, r in rects.items()}


def solid(rect_px):
    """All six faces painted from one small patch of the texture."""
    r = [x / 4 for x in rect_px]
    return {f: {'uv': r, 'texture': '#skin'} for f in ('north', 'south', 'east', 'west', 'up', 'down')}


def box(name, frm, to, uvfaces, rot=None):
    e = {'name': name, 'from': [round(x, 3) for x in frm], 'to': [round(x, 3) for x in to], 'faces': uvfaces}
    if rot:
        e['rotation'] = rot
    return e


def grow(frm, to, k):
    return [x - k for x in frm], [x + k for x in to]


def has_pixels(img, rect):
    x0, y0, x1, y1 = rect
    return any(img.getpixel((x, y))[3] > 0 for x in range(min(x0, x1), max(x0, x1)) for y in range(min(y0, y1), max(y0, y1)))


def main(skin_path, out):
    skin = Image.open(skin_path).convert('RGBA')
    if skin.size != (64, 64):
        raise SystemExit('need a 64x64 skin')
    slim = skin.getpixel((50, 16))[3] == 0 and skin.getpixel((54, 20))[3] == 0
    tex = skin.copy()
    hair = skin.getpixel((12, 1))[:3] + (255,)
    # paint the extra colours into a corner skins never use (0,0)-(8,8)
    for y in range(8):
        for x in range(8):
            tex.putpixel((x, y), (0, 0, 0, 0))
    for y in range(4):
        for x in range(4):
            tex.putpixel((x, y), hair)                       # ear fur
            tex.putpixel((4 + x, y), PINK if (x + y) % 3 else PINK_HI)  # inner ear
    for y in range(4):                                        # gold, light at the top
        for x in range(8):
            tex.putpixel((x, 4 + y), GOLD[y])
    EAR, INNER, GOLDP = (0, 0, 4, 4), (4, 0, 8, 4), (0, 4, 8, 8)

    P = skin_parts(slim)
    aw = 1.5
    geo = {  # model units; the figure stands 0..16 tall, looking south
        'head': ([4, 8, 4], [12, 16, 12]),
        'body': ([5, 3.5, 6.5], [11, 8, 9.5]),
        'rarm': ([5 - aw, 3.5, 6.75], [5, 8, 9.25]),
        'larm': ([11, 3.5, 6.75], [11 + aw, 8, 9.25]),
        'rleg': ([5, 0, 6.5], [8, 3.5, 9.5]),
        'lleg': ([8, 0, 6.5], [11, 3.5, 9.5]),
    }
    els = []
    # golden totem wings behind the shoulders: three feathers a side, longest at the top
    for sgn in (-1, 1):
        root = 5.5 if sgn < 0 else 10.5
        for k, (length, y) in enumerate(((5.2, 7.4), (4.3, 6.2), (3.3, 5.0))):
            x0, x1 = (root - length, root) if sgn < 0 else (root, root + length)
            row = 4 + k  # lighter gold for the top feather
            els.append(box(f'wing{sgn}_{k}', [x0, y, 5.7 + 0.15 * k], [x1, y + 1.1, 6.3 + 0.15 * k],
                           solid((0, row, 8, row + 1)),
                           {'angle': 22.5 * -sgn, 'axis': 'z', 'origin': [root, 8, 6]}))
    for name, ((u, v, w, h, d), (ou, ov)) in P.items():
        frm, to = geo[name]
        els.append(box(name, frm, to, faces(net(u, v, w, h, d))))
        over = net(ou, ov, w, h, d)
        if any(has_pixels(skin, r) for r in over.values()):
            k = 0.5 if name == 'head' else 0.25
            f2, t2 = grow(frm, to, k)
            els.append(box(name + '_layer', f2, t2, faces({f: r for f, r in over.items() if has_pixels(skin, r)})))
    # cat ears: squares turned 45 degrees, half sunk into the head so a triangle pokes out
    for side, cx in (('rear', 5.6), ('lear', 10.4)):
        rot = {'angle': 45, 'axis': 'z', 'origin': [cx, 16.3, 8]}
        els.append(box(side, [cx - 1.6, 14.7, 7.2], [cx + 1.6, 17.9, 8.8], solid(EAR), rot))
        els.append(box(side + '_inner', [cx - 0.9, 15.4, 8.8], [cx + 0.9, 17.2, 9.0], solid(INNER), rot))
    model = {
        'credit': 'Catgirl Pack (catgirlclient.lol)',
        'textures': {'skin': 'catgirl:item/totem_doll', 'particle': 'catgirl:item/totem_doll'},
        'elements': els,
        'gui_light': 'front',
        'display': {
            'gui': {'rotation': [12, -25, 0], 'translation': [0, -0.5, 0], 'scale': [0.82, 0.82, 0.82]},
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.4, 0.4, 0.4]},
            'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.9, 0.9, 0.9]},
            'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
            'thirdperson_righthand': {'rotation': [0, 0, 90], 'translation': [0, 2, 1.5], 'scale': [0.34, 0.34, 0.34]},
            'thirdperson_lefthand': {'rotation': [0, 0, 90], 'translation': [0, 2, 1.5], 'scale': [0.34, 0.34, 0.34]},
            'firstperson_righthand': {'rotation': [0, 20, 0], 'translation': [0, 1.5, -2], 'scale': [0.28, 0.28, 0.28]},
            'firstperson_lefthand': {'rotation': [0, -20, 0], 'translation': [0, 1.5, -2], 'scale': [0.28, 0.28, 0.28]},
        },
    }
    os.makedirs(os.path.join(out, 'models', 'item'), exist_ok=True)
    os.makedirs(os.path.join(out, 'textures', 'item'), exist_ok=True)
    with open(os.path.join(out, 'models', 'item', 'totem_doll.json'), 'w') as f:
        json.dump(model, f, indent=1)
    tex.save(os.path.join(out, 'textures', 'item', 'totem_doll.png'))
    return model


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
