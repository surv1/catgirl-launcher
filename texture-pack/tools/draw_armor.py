"""Catgirl Pack: armour (64x32 player-armour layout). Writes <material>.png (helmet, chestplate,
boots) into <out>/humanoid and <material>.png (leggings) into <out>/humanoid_leggings.
Every piece is our own drawing: bevelled plates, rivets, and pink catgirl accents (ear plates on the
helmet, a heart on the chest, a bow on the belt, paw pads on the boots).
Usage: python3 draw_armor.py <out_dir>"""
import os
import random
import sys
from PIL import Image

MATS = {  # highlight, light, mid, dark, outline
    'iron':      [(255, 255, 255), (222, 222, 228), (186, 186, 196), (130, 130, 142), (78, 78, 90)],
    'gold':      [(255, 252, 196), (252, 222, 92), (232, 178, 40), (180, 116, 22), (112, 62, 14)],
    'diamond':   [(226, 255, 252), (130, 240, 230), (58, 204, 196), (24, 140, 140), (12, 78, 84)],
    'netherite': [(132, 118, 134), (98, 86, 102), (72, 62, 76), (50, 42, 54), (28, 22, 30)],
    'copper':    [(255, 206, 170), (236, 150, 104), (204, 110, 70), (150, 74, 46), (88, 42, 28)],
    'chainmail': [(236, 236, 244), (196, 196, 206), (150, 150, 162), (104, 104, 116), (64, 64, 76)],
}
PINK, PINK_HI, PINK_DK = (255, 126, 182), (255, 214, 236), (176, 52, 112)


def box(u, v, w, h, d):
    """Faces of a Minecraft model box in its texture (x, y, width, height)."""
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
            'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + d + w + d, v + d, w, h)}


HEAD = box(0, 0, 8, 8, 8)
BODY = box(16, 16, 8, 12, 4)
ARM = box(40, 16, 4, 12, 4)
LEG = box(0, 16, 4, 12, 4)


class Painter:
    def __init__(self, mat):
        self.img = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
        self.px = self.img.load()
        self.c = MATS[mat]
        self.mesh = mat == 'chainmail'
        self.rng = random.Random(mat)

    def put(self, x, y, col):
        self.px[x, y] = (*col, 255)

    def plate(self, face, rows=None, cols=None, rivets=True):
        """Fill part of a face as a bevelled metal plate. rows/cols limit it (inclusive ranges)."""
        fx, fy, fw, fh = face
        r0, r1 = rows or (0, fh - 1)
        c0, c1 = cols or (0, fw - 1)
        hi, lt, md, dk, ol = self.c
        for y in range(r0, r1 + 1):
            for x in range(c0, c1 + 1):
                if self.mesh and (x + y) % 2 == 1 and r0 < y < r1 and c0 < x < c1:
                    continue  # chain links: see-through
                if y == r1 or x == c1: col = dk
                elif y == r0 or x == c0: col = lt
                else: col = md if self.rng.random() > 0.1 else (lt if self.rng.random() > 0.5 else dk)
                self.put(fx + x, fy + y, col)
        if (r1 - r0) >= 3 and (c1 - c0) >= 3:
            self.put(fx + c0, fy + r0, hi)  # glint on the top-left corner
            if rivets and not self.mesh:
                for (x, y) in ((c0 + 1, r0 + 1), (c1 - 1, r0 + 1)):
                    self.put(fx + x, fy + y, hi)

    def heart(self, fx, fy):
        for dy, row in enumerate(('.x.x.', 'xxxxx', '.xxx.', '..x..')):
            for dx, ch in enumerate(row):
                if ch == 'x':
                    self.put(fx + dx, fy + dy, PINK)
        self.put(fx + 1, fy + 1, PINK_HI)
        self.put(fx + 2, fy + 3, PINK_DK)
        self.put(fx + 3, fy + 2, PINK_DK)


def helmet_chest_boots(mat):
    p = Painter(mat)
    _, lt, md, dk, ol = p.c
    # Helmet: every side but the bottom; the front leaves the face open under a brow.
    for f in ('top', 'right', 'left', 'back'):
        p.plate(HEAD[f])
    fx, fy, fw, fh = HEAD['front']
    p.plate(HEAD['front'], rows=(0, 2))
    for y in range(3, 8):  # cheek guards
        for x in (0, 7):
            p.put(fx + x, fy + y, dk if y > 5 else md)
    p.put(fx + 3, fy + 1, PINK); p.put(fx + 4, fy + 1, PINK_HI)  # little gem on the brow
    # Cat-ear plates on top: pink inner triangles near the front corners
    tx, ty, _, _ = HEAD['top']
    for (x0, step) in ((1, 1), (6, -1)):
        for i, (dx, dy) in enumerate(((0, 6), (step, 6), (0, 5), (step, 5), (0, 4))):
            p.put(tx + x0 + dx, ty + dy, PINK if i else PINK_DK)
        p.put(tx + x0, ty + 7, ol); p.put(tx + x0 + step, ty + 7, ol)
    # ...and ear tips on the sides of the helmet
    for f in ('right', 'left'):
        sx, sy, _, _ = HEAD[f]
        p.put(sx + 6, sy + 0, PINK); p.put(sx + 5, sy + 0, PINK_HI); p.put(sx + 6, sy + 1, PINK_DK)
    # Chestplate: body all round, heart on the front.
    for f in ('top', 'bottom', 'right', 'left', 'back', 'front'):
        p.plate(BODY[f])
    bx, by, _, _ = BODY['front']
    p.heart(bx + 1, by + 3)
    for y in (9, 10):  # waist band
        for x in range(8):
            p.put(bx + x, by + y, dk if y == 10 else md)
    p.put(bx + 3, by + 9, PINK); p.put(bx + 4, by + 9, PINK)
    # back: a little heart too
    kx, ky, _, _ = BODY['back']
    p.put(kx + 3, ky + 4, PINK); p.put(kx + 4, ky + 4, PINK); p.put(kx + 3, ky + 5, PINK_DK); p.put(kx + 4, ky + 5, PINK_DK)
    # Shoulders: top of the arms
    p.plate(ARM['top'])
    for f in ('right', 'front', 'left', 'back'):
        p.plate(ARM[f], rows=(0, 5), rivets=False)
        ax, ay, aw, _ = ARM[f]
        for x in range(aw):
            p.put(ax + x, ay + 6, ol)  # rim of the pauldron
    # Boots: bottom of the legs, with pink toe pads and a paw sole
    for f in ('right', 'front', 'left', 'back'):
        p.plate(LEG[f], rows=(8, 11), rivets=False)
        lx, ly, lw, _ = LEG[f]
        for x in range(lw):
            p.put(lx + x, ly + 8, lt)  # cuff
    p.plate(LEG['bottom'], rivets=False)
    sx, sy, _, _ = LEG['bottom']
    for (x, y) in ((1, 2), (2, 2), (1, 3), (2, 3)):
        p.put(sx + x, sy + y, PINK)
    for (x, y) in ((0, 0), (2, 0), (3, 1)):
        p.put(sx + x, sy + y, PINK_HI)
    fx, fy, _, _ = LEG['front']
    p.put(fx + 1, fy + 11, PINK); p.put(fx + 2, fy + 11, PINK)
    return p.img


def leggings(mat):
    p = Painter(mat)
    _, lt, md, dk, ol = p.c
    # Hips: lower part of the body box, plus a pink bow on the belt.
    for f in ('right', 'front', 'left', 'back'):
        p.plate(BODY[f], rows=(7, 11), rivets=False)
    p.plate(BODY['bottom'], rivets=False)
    bx, by, _, _ = BODY['front']
    for x in range(8):
        p.put(bx + x, by + 7, dk)  # belt
    for (x, y, c) in ((2, 7, PINK), (3, 7, PINK_HI), (4, 7, PINK_DK), (5, 7, PINK), (3, 8, PINK), (4, 8, PINK), (2, 6, PINK), (5, 6, PINK)):
        p.put(bx + x, by + y, c)
    # Legs: thighs and knees (the boots cover the bottom)
    p.plate(LEG['top'], rivets=False)
    for f in ('right', 'front', 'left', 'back'):
        p.plate(LEG[f], rows=(0, 9), rivets=False)
    lx, ly, _, _ = LEG['front']
    for (x, y, c) in ((1, 5, PINK), (2, 5, PINK_HI), (1, 6, PINK_DK), (2, 6, PINK)):  # knee gem
        p.put(lx + x, ly + y, c)
    return p.img


if __name__ == '__main__':
    out = sys.argv[1]
    for sub in ('humanoid', 'humanoid_leggings'):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    for mat in MATS:
        helmet_chest_boots(mat).save(os.path.join(out, 'humanoid', f'{mat}.png'))
        leggings(mat).save(os.path.join(out, 'humanoid_leggings', f'{mat}.png'))
