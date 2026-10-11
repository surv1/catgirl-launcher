"""Draws the website picture of the pack. Usage: python3 make_showcase.py <built CatgirlPack dir> <skin.png> <out.png>"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

pack, skin_path, out = sys.argv[1:4]
P = pack + '/assets/minecraft/textures'
img = Image.new('RGBA', (1180, 740))
d = ImageDraw.Draw(img)
for y in range(740):
    k = y / 739
    d.line([(0, y), (1179, y)], fill=(int(44 - 10 * k), int(22 - 6 * k), int(56 - 10 * k), 255))


def frame(path, f=8, s=32):
    im = Image.open(path).convert('RGBA')
    im = im.crop((0, s * f, s, s * f + s)) if im.height > im.width else im
    name = os.path.basename(path)
    aura = f'{pack}/assets/catgirl/textures/item/aura/{name}'
    if os.path.exists(aura):  # show the held-weapon glow behind it
        a = Image.open(aura).convert('RGBA').crop((0, 0, s, s))
        a.alpha_composite(im)
        im = a
    return im


def glow(im, scale, pos, pad=40):
    big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    canvas = Image.new('RGBA', (big.width + 2 * pad, big.height + 2 * pad))
    canvas.alpha_composite(big, (pad, pad))
    a = canvas.split()[3].filter(ImageFilter.GaussianBlur(16)).point(lambda v: int(min(255, v * 0.7)))
    g = Image.new('RGBA', canvas.size, (255, 126, 182, 0)); g.putalpha(a)
    img.alpha_composite(g, (pos[0] - pad, pos[1] - pad)); img.alpha_composite(big, pos)


import json, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from render_model import render
C = pack + '/assets/catgirl'
doll_model = json.load(open(C + '/models/item/totem_doll.json'))
doll_model['display']['show'] = {'rotation': [10, -25, 0]}
doll = render(doll_model, Image.open(C + '/textures/item/totem_doll.png').convert('RGBA'), 'show', px=22, bg=(0, 0, 0, 0))
doll = doll.crop(doll.getbbox())
glow(doll, 1, (590 - doll.width // 2, 30))
for i, (n, f) in enumerate([('diamond_sword', 6), ('netherite_sword', 6), ('golden_sword', 8)]):
    glow(frame(f'{P}/item/{n}.png', f), 6, (30 + i * 125, 50 + i * 25))
for i, (n, f) in enumerate([('mace', 6), ('diamond_axe', 8), ('iron_axe', 8)]):
    glow(frame(f'{P}/item/{n}.png', f), 6, (770 + i * 120, 100 - i * 25))
skin = Image.open(skin_path).convert('RGBA')


def front(t):
    v = Image.new('RGBA', (16, 32))
    for box, pos in [((8, 8, 16, 16), (4, 0)), ((20, 20, 28, 32), (4, 8)), ((44, 20, 48, 32), (0, 8)),
                     ((44, 20, 48, 32), (12, 8)), ((4, 20, 8, 32), (4, 20)), ((4, 20, 8, 32), (8, 20))]:
        v.alpha_composite(t.crop(box), pos)
    return v


for i, m in enumerate(['diamond', 'gold', 'netherite', 'iron']):
    v = front(skin)
    for layer in (f'{P}/entity/equipment/humanoid_leggings/{m}.png', f'{P}/entity/equipment/humanoid/{m}.png'):
        v.alpha_composite(front(Image.open(layer).convert('RGBA')))
    img.alpha_composite(v.resize((16 * 7, 32 * 7), Image.NEAREST), ([70, 250, 830, 1010][i], 470))
for i, n in enumerate(['glitter_7', 'glitter_6', 'glitter_5', 'glitter_4']):
    s = Image.open(f'{P}/particle/{n}.png').convert('RGBA')
    r, g, b, a = s.split()
    tint = (0.75, 0.8, 0.1) if i % 2 == 0 else (0.25, 0.65, 0.1)  # the game colours totem sparkles gold or green
    s = Image.merge('RGBA', (r.point(lambda v: int(v * tint[0])), g.point(lambda v: int(v * tint[1])), b.point(lambda v: int(v * tint[2])), a))
    img.alpha_composite(s.resize((64, 64), Image.NEAREST), (440 + i * 80, 580))
img.alpha_composite(Image.open(f'{P}/particle/sweep_3.png').convert('RGBA').resize((192, 192), Image.NEAREST), (494, 440))
img.convert('RGB').save(out, optimize=True)
