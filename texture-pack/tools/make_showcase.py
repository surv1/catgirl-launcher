"""Draws the website picture of the pack. Usage: python3 make_showcase.py <built CatgirlPack dir> <skin.png> <out.png>"""
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
    return im.crop((0, s * f, s, s * f + s)) if im.height > im.width else im


def glow(im, scale, pos, pad=40):
    big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    canvas = Image.new('RGBA', (big.width + 2 * pad, big.height + 2 * pad))
    canvas.alpha_composite(big, (pad, pad))
    a = canvas.split()[3].filter(ImageFilter.GaussianBlur(16)).point(lambda v: int(min(255, v * 0.7)))
    g = Image.new('RGBA', canvas.size, (255, 126, 182, 0)); g.putalpha(a)
    img.alpha_composite(g, (pos[0] - pad, pos[1] - pad)); img.alpha_composite(big, pos)


glow(Image.open(P + '/item/totem_of_undying.png').convert('RGBA'), 11, (414, 40))
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
    s = Image.merge('RGBA', (r.point(lambda v: 255), g.point(lambda v: int(v * 0.55)), b.point(lambda v: int(v * 0.8)), a))
    img.alpha_composite(s.resize((64, 64), Image.NEAREST), (440 + i * 80, 580))
img.alpha_composite(Image.open(f'{P}/particle/sweep_3.png').convert('RGBA').resize((192, 192), Image.NEAREST), (494, 440))
img.convert('RGB').save(out, optimize=True)
