"""Draws the Catgirl menu's mod icons (one 64x64 PNG per mod) in a flat, outlined style with the
Catgirl palette. Each is drawn at 4x (256px) and scaled down for smooth edges.
Usage: python3 draw_mod_icons.py <out_dir> [preview.png]"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFont

S = 256                      # drawing size
INK = (42, 24, 48, 255)
PINK, DEEP, LIGHT = (255, 126, 182, 255), (214, 79, 143, 255), (255, 208, 230, 255)
LILAC, CREAM, BUTTER = (200, 155, 255, 255), (255, 244, 234, 255), (255, 211, 77, 255)
MINT, SKY, RED, GREY = (140, 233, 154, 255), (126, 200, 255, 255), (255, 107, 122, 255), (185, 169, 198, 255)
WHITE, DARK = (255, 255, 255, 255), (74, 46, 86, 255)
W = 12                       # outline width at drawing size


def canvas():
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def ell(d, box, fill, outline=INK, w=W):
    d.ellipse(box, fill=fill, outline=outline, width=w)


def rect(d, box, fill, r=16, outline=INK, w=W):
    d.rounded_rectangle(box, r, fill=fill, outline=outline, width=w)


def poly(d, pts, fill, outline=INK, w=W):
    d.polygon(pts, fill=fill)
    if outline: d.line(pts + [pts[0]], fill=outline, width=w, joint='curve')


def line(d, pts, fill=INK, w=W):
    d.line(pts, fill=fill, width=w, joint='curve')


def star(d, cx, cy, r, fill, w=W):
    pts = []
    for i in range(8):
        a = math.pi / 4 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * 0.38
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
    poly(d, pts, fill, w=w)


def text(d, xy, s, size, fill=INK):
    try:
        f = ImageFont.truetype('DejaVuSans-Bold.ttf', size)
    except OSError:
        f = ImageFont.load_default()
    d.text(xy, s, font=f, fill=fill, anchor='mm')


ICONS = {}


def icon(name):
    def deco(fn):
        ICONS[name] = fn
        return fn
    return deco


@icon('fps')
def _(d):
    d.pieslice((28, 40, 228, 240), 180, 360, fill=CREAM, outline=INK, width=W)
    for i, c in enumerate((MINT, BUTTER, PINK)):
        d.arc((52, 64, 204, 216), 180 + i * 60, 240 + i * 60, fill=c, width=22)
    a = math.radians(-35)
    line(d, [(128, 140), (128 + math.cos(a) * 82, 140 + math.sin(a) * 82)], DEEP, 14)
    ell(d, (110, 122, 146, 158), DEEP)
    text(d, (128, 196), 'FPS', 52, CREAM)


@icon('cps')
def _(d):
    rect(d, (70, 40, 186, 220), CREAM, r=58)
    line(d, [(128, 46), (128, 118)])
    line(d, [(74, 118), (182, 118)])
    d.rounded_rectangle((76, 46, 122, 112), 30, fill=PINK)
    for a in (-150, -120, -90):
        r = math.radians(a)
        line(d, [(98 + math.cos(r) * 52, 40 + math.sin(r) * 40 + 30), (98 + math.cos(r) * 76, 40 + math.sin(r) * 58 + 30)], DEEP, 10)


@icon('keys')
def _(d):
    for (x, y, label, on) in ((96, 34, 'W', True), (34, 112, 'A', False), (96, 112, 'S', False), (158, 112, 'D', False)):
        rect(d, (x, y, x + 64, y + 64), PINK if on else CREAM, r=14)
        text(d, (x + 32, y + 30), label, 40, INK)
    rect(d, (34, 190, 222, 222), LILAC, r=12)


@icon('coords')
def _(d):
    poly(d, [(128, 236), (60, 120), (196, 120)], PINK)
    ell(d, (52, 30, 204, 182), PINK)
    d.polygon([(128, 228), (70, 130), (186, 130)], fill=PINK)
    ell(d, (98, 76, 158, 136), CREAM)


@icon('armour')
def _(d):
    pts = [(56, 44), (100, 44), (128, 70), (156, 44), (200, 44), (236, 92), (200, 116), (196, 220), (60, 220), (56, 116), (20, 92)]
    poly(d, pts, SKY)
    line(d, [(128, 70), (128, 210)], (90, 150, 210, 255), 10)
    star(d, 128, 140, 30, PINK, w=8)


@icon('ping')
def _(d):
    for i, h in enumerate((50, 90, 130, 170)):
        x = 40 + i * 46
        rect(d, (x, 220 - h, x + 34, 220), MINT if i < 3 else CREAM, r=8)


@icon('clock')
def _(d):
    ell(d, (28, 28, 228, 228), CREAM)
    for i in range(12):
        a = math.radians(i * 30)
        line(d, [(128 + math.cos(a) * 84, 128 + math.sin(a) * 84), (128 + math.cos(a) * 94, 128 + math.sin(a) * 94)], DARK, 8)
    line(d, [(128, 128), (128, 66)], INK, 14)
    line(d, [(128, 128), (176, 150)], PINK, 14)
    ell(d, (116, 116, 140, 140), PINK)


@icon('nowplaying')
def _(d):
    poly(d, [(98, 60), (210, 32), (210, 70), (98, 98)], PINK)
    line(d, [(98, 60), (98, 186)], INK, 16)
    line(d, [(210, 32), (210, 158)], INK, 16)
    ell(d, (40, 160, 108, 216), PINK)
    ell(d, (152, 132, 220, 188), PINK)


@icon('effects')
def _(d):
    rect(d, (104, 26, 152, 70), CREAM, r=8)
    ell(d, (48, 70, 208, 230), CREAM)
    d.chord((60, 82, 196, 218), 10, 170, fill=PINK)
    ell(d, (48, 70, 208, 230), None)
    for (x, y, r) in ((100, 150, 10), (140, 172, 14), (124, 124, 7)):
        d.ellipse((x - r, y - r, x + r, y + r), fill=LIGHT)


@icon('direction')
def _(d):
    ell(d, (24, 24, 232, 232), CREAM)
    poly(d, [(128, 40), (154, 128), (102, 128)], RED, w=8)
    poly(d, [(128, 216), (154, 128), (102, 128)], GREY, w=8)
    ell(d, (114, 114, 142, 142), INK)
    text(d, (128, 22 + 4), '', 10)


@icon('day')
def _(d):
    for i in range(8):
        a = math.radians(i * 45)
        line(d, [(128 + math.cos(a) * 82, 128 + math.sin(a) * 82), (128 + math.cos(a) * 112, 128 + math.sin(a) * 112)], BUTTER, 18)
    ell(d, (58, 58, 198, 198), BUTTER)
    text(d, (128, 132), '7', 72)


@icon('server')
def _(d):
    for i in range(3):
        y = 36 + i * 64
        rect(d, (40, y, 216, y + 52), LILAC if i != 1 else PINK, r=10)
        ell(d, (60, y + 16, 80, y + 36), MINT, w=6)
        line(d, [(108, y + 26), (196, y + 26)], DARK, 8)


@icon('speed')
def _(d):
    for i, y in enumerate((80, 128, 176)):
        line(d, [(24 + i * 10, y), (110 + i * 10, y)], SKY, 16)
    poly(d, [(120, 40), (232, 128), (120, 216), (140, 128)], PINK)


@icon('items')
def _(d):
    rect(d, (36, 64, 220, 222), (196, 140, 96, 255), r=22)
    rect(d, (78, 30, 178, 80), (176, 120, 80, 255), r=20)
    rect(d, (60, 120, 196, 170), (160, 104, 66, 255), r=10)
    ell(d, (110, 128, 146, 162), BUTTER, w=6)
    text(d, (190, 200), '3', 56, WHITE)


@icon('reach')
def _(d):
    line(d, [(40, 210), (150, 100)], INK, 30)
    line(d, [(40, 210), (150, 100)], SKY, 14)
    poly(d, [(150, 70), (200, 56), (186, 106)], SKY, w=8)
    line(d, [(36, 236), (220, 236)], PINK, 10)
    for x in (36, 220):
        line(d, [(x, 222), (x, 248)], PINK, 10)


@icon('combo')
def _(d):
    poly(d, [(128, 20), (172, 92), (214, 70), (196, 170), (128, 234), (60, 170), (42, 70), (84, 92)], PINK)
    poly(d, [(128, 92), (156, 140), (128, 196), (100, 140)], BUTTER, w=8)
    text(d, (128, 150), '', 10)


@icon('memory')
def _(d):
    rect(d, (24, 76, 232, 168), MINT, r=10)
    for i in range(4):
        rect(d, (44 + i * 46, 96, 78 + i * 46, 136), DARK, r=6, w=6)
    for i in range(9):
        line(d, [(40 + i * 22, 168), (40 + i * 22, 196)], BUTTER, 10)


@icon('stopwatch')
def _(d):
    rect(d, (108, 18, 148, 46), PINK, r=6)
    ell(d, (34, 44, 222, 232), CREAM)
    d.pieslice((60, 70, 196, 206), -90, 40, fill=LIGHT)
    line(d, [(128, 138), (128, 82)], INK, 14)
    line(d, [(128, 138), (166, 166)], PINK, 12)


@icon('packs')
def _(d):
    rect(d, (76, 30, 220, 174), LILAC, r=14)
    rect(d, (54, 56, 198, 200), SKY, r=14)
    rect(d, (32, 82, 176, 226), CREAM, r=14)
    poly(d, [(50, 210), (96, 150), (124, 186), (146, 162), (162, 210)], MINT, w=8)
    ell(d, (124, 100, 154, 130), BUTTER, w=6)


@icon('saturation')
def _(d):
    ell(d, (34, 34, 170, 170), (196, 120, 80, 255))
    line(d, [(140, 140), (204, 204)], CREAM, 26)
    ell(d, (192, 186, 230, 224), CREAM)
    ell(d, (196, 210, 236, 248), CREAM)
    star(d, 190, 70, 44, BUTTER)


@icon('crosshair')
def _(d):
    ell(d, (48, 48, 208, 208), None, PINK, 14)
    for (a, b) in (((128, 20), (128, 92)), ((128, 164), (128, 236)), ((20, 128), (92, 128)), ((164, 128), (236, 128))):
        line(d, [a, b], INK, 22)
        line(d, [a, b], PINK, 10)
    ell(d, (116, 116, 140, 140), PINK)


@icon('scoreboard')
def _(d):
    rect(d, (40, 26, 216, 230), DARK, r=12)
    line(d, [(64, 64), (192, 64)], PINK, 14)
    for i, w in enumerate((100, 80, 110)):
        y = 108 + i * 38
        line(d, [(64, y), (64 + w, y)], CREAM, 12)
        line(d, [(184, y), (196, y)], RED, 12)


@icon('bossbar')
def _(d):
    ell(d, (60, 30, 196, 160), CREAM)
    poly(d, [(64, 70), (34, 20), (96, 46)], CREAM, w=8)
    poly(d, [(192, 70), (222, 20), (160, 46)], CREAM, w=8)
    ell(d, (88, 78, 120, 110), INK)
    ell(d, (136, 78, 168, 110), INK)
    rect(d, (24, 186, 232, 222), DARK, r=8)
    d.rounded_rectangle((32, 194, 160, 214), 6, fill=PINK)


@icon('fullbright')
def _(d):
    ell(d, (58, 24, 198, 164), BUTTER)
    rect(d, (92, 154, 164, 214), GREY, r=8)
    line(d, [(96, 176), (160, 176)], DARK, 8)
    line(d, [(96, 196), (160, 196)], DARK, 8)
    for a in (-150, -120, -60, -30):
        r = math.radians(a)
        line(d, [(128 + math.cos(r) * 92, 94 + math.sin(r) * 92), (128 + math.cos(r) * 116, 94 + math.sin(r) * 116)], BUTTER, 12)


@icon('time')
def _(d):
    ell(d, (28, 28, 228, 228), SKY)
    d.pieslice((28, 28, 228, 228), 90, 270, fill=DARK)
    ell(d, (28, 28, 228, 228), None)
    ell(d, (148, 84, 204, 140), BUTTER, w=8)
    ell(d, (56, 96, 104, 144), CREAM, w=8)
    d.ellipse((74, 92, 118, 136), fill=DARK)


@icon('weather')
def _(d):
    for i in range(8):
        a = math.radians(i * 45)
        line(d, [(150 + math.cos(a) * 54, 90 + math.sin(a) * 54), (150 + math.cos(a) * 76, 90 + math.sin(a) * 76)], BUTTER, 12)
    ell(d, (106, 46, 194, 134), BUTTER)
    d.rounded_rectangle((30, 120, 200, 210), 44, fill=CREAM, outline=INK, width=W)
    ell(d, (60, 92, 140, 172), CREAM)
    d.rectangle((44, 150, 186, 196), fill=CREAM)


@icon('nohurtcam')
def _(d):
    rect(d, (28, 74, 228, 214), LILAC, r=20)
    rect(d, (88, 44, 168, 80), LILAC, r=10)
    ell(d, (84, 98, 172, 186), CREAM)
    poly(d, [(128, 172), (102, 144), (110, 122), (128, 132), (146, 122), (154, 144)], PINK, w=6)


@icon('particles')
def _(d):
    star(d, 100, 110, 76, PINK)
    star(d, 190, 62, 40, BUTTER)
    star(d, 186, 186, 34, LILAC)
    star(d, 56, 210, 24, SKY)


@icon('snaplook')
def _(d):
    d.ellipse((24, 70, 232, 186), fill=CREAM, outline=INK, width=W)
    ell(d, (88, 78, 168, 178), PINK)
    ell(d, (114, 106, 142, 150), INK)
    d.arc((40, 20, 216, 236), 200, 340, fill=DEEP, width=14)
    poly(d, [(196, 52), (232, 64), (204, 92)], DEEP, w=6)


@icon('sneak')
def _(d):
    poly(d, [(60, 96), (130, 96), (140, 150), (220, 176), (220, 214), (52, 214)], (150, 100, 70, 255))
    rect(d, (52, 196, 222, 222), CREAM, r=8)
    for i in range(3):
        line(d, [(40 + i * 18, 40 + i * 6), (64 + i * 18, 60 + i * 6)], LILAC, 10)


@icon('sprint')
def _(d):
    poly(d, [(70, 80), (140, 80), (148, 140), (226, 166), (226, 206), (62, 206)], PINK)
    rect(d, (60, 190, 228, 218), CREAM, r=8)
    for y in (100, 136, 172):
        line(d, [(18, y), (54, y)], SKY, 12)


@icon('zoom')
def _(d):
    line(d, [(150, 150), (224, 224)], INK, 40)
    line(d, [(150, 150), (220, 220)], PINK, 24)
    ell(d, (28, 28, 172, 172), SKY)
    d.arc((52, 52, 148, 148), 200, 260, fill=WHITE, width=12)


# ---- batch 2
@icon('freelook')
def _(d):
    d.arc((28, 28, 228, 228), 30, 330, fill=PINK, width=18)
    poly(d, [(206, 50), (236, 92), (190, 94)], PINK, w=6)
    d.ellipse((70, 96, 186, 160), fill=CREAM, outline=INK, width=W)
    ell(d, (110, 104, 146, 152), INK)


@icon('minimap')
def _(d):
    rect(d, (30, 30, 226, 226), (96, 170, 96, 255), r=18)
    d.polygon([(40, 150), (100, 120), (150, 170), (216, 140), (216, 216), (40, 216)], fill=SKY)
    d.rectangle((60, 54, 100, 94), fill=(150, 110, 70, 255))
    rect(d, (30, 30, 226, 226), None, r=18)
    poly(d, [(128, 92), (152, 156), (128, 142), (104, 156)], PINK, w=8)


@icon('waypoints')
def _(d):
    line(d, [(72, 30), (72, 230)], INK, 18)
    poly(d, [(80, 36), (210, 64), (80, 120)], PINK)
    ell(d, (40, 210, 104, 240), LILAC, w=8)


@icon('outline')
def _(d):
    poly(d, [(128, 30), (220, 76), (128, 122), (36, 76)], None, PINK, 14)
    line(d, [(36, 76), (36, 178), (128, 226), (220, 178), (220, 76)], PINK, 14)
    line(d, [(128, 122), (128, 226)], PINK, 14)


@icon('hitcolor')
def _(d):
    poly(d, [(128, 220), (40, 128), (44, 74), (88, 46), (128, 80), (168, 46), (212, 74), (216, 128)], RED)
    line(d, [(30, 226), (226, 30)], INK, 26)
    line(d, [(36, 220), (220, 36)], CREAM, 12)


def main(out, preview=None):
    os.makedirs(out, exist_ok=True)
    tiles = []
    for name, fn in ICONS.items():
        img, d = canvas()
        fn(d)
        small = img.resize((64, 64), Image.LANCZOS)
        small.save(os.path.join(out, name + '.png'), optimize=True)
        with open(os.path.join(out, name + '.png.mcmeta'), 'w') as f:
            f.write('{"texture": {"blur": true}}\n')
        tiles.append((name, small))
    if preview:
        cols = 8
        rows = (len(tiles) + cols - 1) // cols
        sheet = Image.new('RGBA', (cols * 80, rows * 80), (44, 22, 56, 255))
        for i, (name, t) in enumerate(tiles):
            sheet.alpha_composite(t, ((i % cols) * 80 + 8, (i // cols) * 80 + 8))
        sheet.save(preview)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
