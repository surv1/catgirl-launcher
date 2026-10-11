"""Catgirl Pack: effects. Pink sparkly enchant glint (items + armour), pink sweep slashes,
heart/paw totem sparkles and star crits. Usage: python3 draw_effects.py <textures_dir>
(writes misc/ and particle/ under it)."""
import math
import os
import random
import sys
from PIL import Image

PINK = (255, 110, 190)


def put_add(px, size, x, y, col, k):
    """Additive paint with wrap-around (the glint texture tiles)."""
    x %= size; y %= size
    o = px[x, y]
    px[x, y] = tuple(min(255, round(o[i] + col[i] * k)) for i in range(3)) + (255,)


def glint(size, seed, streak_k):
    """Tileable. The game adds this on top of the item, so black means 'no glint'."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 255))
    px = img.load()
    # soft diagonal pink streaks (period divides the size so it tiles)
    for y in range(size):
        for x in range(size):
            u = (x + y) / size * 2 * math.pi
            s = (0.5 + 0.5 * math.sin(u * 2)) ** 3 * 0.55 + (0.5 + 0.5 * math.sin(u * 5 + 1.3)) ** 6 * 0.35
            put_add(px, size, x, y, PINK, s * streak_k)
            put_add(px, size, x, y, (120, 60, 255), (0.5 + 0.5 * math.sin(u * 3 + 2.0)) ** 4 * 0.25 * streak_k)
    # sparkles: 4-point stars
    rng = random.Random(seed)
    for _ in range(size // 6):
        cx, cy, r = rng.randrange(size), rng.randrange(size), rng.choice((1, 2, 2, 3))
        for i in range(-r, r + 1):
            k = (1 - abs(i) / (r + 1)) ** 1.5
            put_add(px, size, cx + i, cy, (255, 220, 240), k)
            put_add(px, size, cx, cy + i, (255, 220, 240), k)
    return img


def soft_shape(size, mask_fn, col, glow=True):
    """Draw a shape from mask_fn(x, y) -> bool with a soft glow around it."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = img.load()
    inside = [[mask_fn(x + 0.5, y + 0.5) for x in range(size)] for y in range(size)]
    for y in range(size):
        for x in range(size):
            if inside[y][x]:
                px[x, y] = (*col, 255)
            elif glow:
                near = any(0 <= x + dx < size and 0 <= y + dy < size and inside[y + dy][x + dx]
                           for dx in (-1, 0, 1) for dy in (-1, 0, 1))
                if near:
                    px[x, y] = (*col, 110)
    return img


def heart_mask(cx, cy, s):
    def f(x, y):
        X, Y = (x - cx) / s, -(y - cy) / s + 0.25
        return (X * X + Y * Y - 1) ** 3 - X * X * Y ** 3 <= 0
    return f


def paw_mask(cx, cy, s):
    def f(x, y):
        X, Y = (x - cx) / s, (y - cy) / s
        if (X / 1.0) ** 2 + ((Y - 0.55) / 0.72) ** 2 <= 1: return True
        for tx, ty in ((-1.25, -0.45), (-0.5, -1.15), (0.5, -1.15), (1.25, -0.45)):
            if (X - tx) ** 2 + (Y - ty) ** 2 <= 0.36 ** 2: return True
        return False
    return f


def star_mask(cx, cy, r, thick):
    def f(x, y):
        dx, dy = abs(x - cx), abs(y - cy)
        return (dx <= thick * (1 - dy / r) and dy <= r) or (dy <= thick * (1 - dx / r) and dx <= r)
    return f


def sweep(frame, size=32):
    """A pink crescent slash that opens up and fades over 8 frames."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = img.load()
    t = frame / 7
    span = 0.35 + 0.65 * min(1, t * 1.6)          # how much of the arc is drawn
    fade = 1 - 0.7 * max(0, t - 0.45) / 0.55      # fades in the second half
    R, W = 12.5, 3.2 * (1 - 0.5 * t) + 0.8
    cx, cy = 16, 22
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            ang = math.atan2(-dy, dx)  # 0 = right, pi/2 = up
            a = (ang - math.radians(15)) / math.radians(150)  # 0..1 across the arc
            if not (0 <= a <= 1) or a > span:
                continue
            taper = math.sin(math.pi * a / span) ** 0.6 if span > 0 else 0
            w = W * taper
            d = abs(r - R)
            if d <= w + 1:
                core = d <= w * 0.35
                col = (255, 240, 248) if core else PINK if d <= w else (200, 80, 160)
                alpha = (255 if d <= w else 90) * fade
                px[x, y] = (*col, max(0, min(255, round(alpha))))
    return img


def main(root):
    misc, part = os.path.join(root, 'misc'), os.path.join(root, 'particle')
    os.makedirs(misc, exist_ok=True); os.makedirs(part, exist_ok=True)
    glint(128, 1, 0.75).save(os.path.join(misc, 'enchanted_glint_item.png'))
    glint(128, 2, 0.55).save(os.path.join(misc, 'enchanted_glint_armor.png'))
    for name in ('enchanted_glint_item', 'enchanted_glint_armor'):
        with open(os.path.join(misc, name + '.png.mcmeta'), 'w') as f:
            f.write('{\n  "texture": {\n    "blur": true\n  }\n}\n')
    for i in range(8):
        sweep(i).save(os.path.join(part, f'sweep_{i}.png'))
    # Totem sparkles (glitter_7 shows first, glitter_0 last). The game tints these yellow/green,
    # so they are drawn white to keep their colours clean: hearts and paws that shrink to a dot.
    W = (255, 255, 255)
    shapes = {7: heart_mask(8, 8, 6.2), 6: paw_mask(8, 8.5, 4.6), 5: heart_mask(8, 8, 5.0), 4: paw_mask(8, 8.5, 3.8),
              3: heart_mask(8, 8, 3.8), 2: star_mask(8, 8, 5, 1.6), 1: star_mask(8, 8, 3.5, 1.3), 0: star_mask(8, 8, 2, 1.0)}
    for i, m in shapes.items():
        soft_shape(16, m, W, glow=i not in (6, 4)).save(os.path.join(part, f'glitter_{i}.png'))  # paws: no glow, keeps the toes apart
    soft_shape(16, star_mask(8, 8, 7, 2.2), (255, 150, 210)).save(os.path.join(part, 'critical_hit.png'))
    soft_shape(16, star_mask(8, 8, 7, 2.2), (255, 220, 255)).save(os.path.join(part, 'enchanted_hit.png'))


if __name__ == '__main__':
    main(sys.argv[1])
