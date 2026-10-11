"""Catgirl Pack: the Totem of Undying, golden, with the player's face in the head and cat ears.
Usage: python3 draw_totem.py <skin.png> <out.png>"""
import sys
from PIL import Image

OUT = (0, 0, 0, 0)
G = {  # golden palette, light to dark, plus outline
    'hi': (255, 246, 176, 255), 'lt': (250, 214, 84, 255), 'md': (226, 168, 46, 255),
    'dk': (170, 104, 30, 255), 'ol': (92, 56, 18, 255),
}
EAR, EAR_IN = (24, 22, 28, 255), (255, 150, 200, 255)
GEM, GEM_HI, GEM_DK = (255, 126, 182, 255), (255, 214, 236, 255), (176, 52, 112, 255)


def draw(skin_path, out_path):
    skin = Image.open(skin_path).convert('RGBA')
    face = Image.new('RGBA', (8, 8))
    face.alpha_composite(skin.crop((8, 8, 16, 16)))
    face.alpha_composite(skin.crop((40, 8, 48, 16)))  # hat layer on top
    img = Image.new('RGBA', (32, 32), OUT)
    px = img.load()

    def rect(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px[x, y] = c

    # wings: flared, feathered, rising towards the tips
    for x in range(1, 31):
        d = abs(x - 15.5)
        if d < 6: continue
        lift = int((d - 6) * 0.45)
        top, bot = 21 - lift, 25 - lift + (1 if d < 9 else 0)
        for y in range(top, bot + 1):
            if y == top: px[x, y] = G['lt']
            elif y == bot: px[x, y] = G['dk']
            else: px[x, y] = G['md'] if int(d) % 3 else G['dk']  # feather lines
    # body: tapering robe
    for y in range(20, 31):
        half = 6 if y < 26 else 6 - (y - 25)
        for x in range(16 - half, 16 + half):
            px[x, y] = G['lt'] if x < 16 - half + 2 else G['dk'] if x >= 16 + half - 2 else G['md']
    rect(13, 30, 18, 30, G['dk'])
    # pink gem on the chest
    rect(14, 23, 17, 26, GEM); rect(14, 23, 15, 24, GEM_HI); rect(16, 26, 17, 26, GEM_DK)
    # head: rounded golden hood, then the face (each skin pixel 2x2)
    rect(7, 3, 24, 20, G['md'])
    rect(7, 3, 24, 3, G['lt']); rect(7, 3, 7, 20, G['lt']); rect(24, 3, 24, 20, G['dk']); rect(7, 20, 24, 20, G['dk'])
    img.alpha_composite(face.resize((16, 16), Image.NEAREST), (8, 4))
    for (x, y) in ((7, 3), (24, 3), (7, 20), (24, 20)):  # round the corners
        px[x, y] = OUT
    # cat ears on top
    for (x0, step) in ((8, 1), (23, -1)):
        for row in range(4):
            for k in range(4 - row):
                px[x0 + step * k, 3 - row] = EAR
        px[x0 + step, 2] = EAR_IN; px[x0 + step, 3] = EAR_IN; px[x0 + 2 * step, 3] = EAR_IN
    # outline everything
    solid = [[px[x, y][3] > 0 for x in range(32)] for y in range(32)]
    for y in range(32):
        for x in range(32):
            if solid[y][x]: continue
            if any(0 <= x + dx < 32 and 0 <= y + dy < 32 and solid[y + dy][x + dx] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = G['ol']
    img.save(out_path)


if __name__ == '__main__':
    draw(sys.argv[1], sys.argv[2])
