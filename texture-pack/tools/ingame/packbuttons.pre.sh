# A resource pack with its own (green, striped) buttons, like the stone buttons in many PvP packs.
mkdir -p resourcepacks
python3 - <<'PY'
import struct, zlib, zipfile, io
def png(w, h, px):
    raw = b''.join(b'\x00' + bytes(c for x in range(w) for c in px(x, y)) for y in range(h))
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')
def button(base):
    def px(x, y):
        if x in (0, 199) or y in (0, 19): return (0, 0, 0, 255)
        k = 30 if (x // 4 + y // 4) % 2 else 0
        return (base[0] + k, base[1] + k, base[2] + k, 255)
    return png(200, 20, px)
meta = b'{"gui":{"scaling":{"type":"nine_slice","width":200,"height":20,"border":3}}}'
z = zipfile.ZipFile('resourcepacks/ButtonsPack.zip', 'w')
z.writestr('pack.mcmeta', '{"pack":{"pack_format":75,"min_format":75,"max_format":75,"description":"Test pack with its own buttons"}}')
for name, base in [('button', (40, 120, 40)), ('button_highlighted', (80, 170, 80)), ('button_disabled', (10, 10, 10))]:
    z.writestr(f'assets/minecraft/textures/gui/sprites/widget/{name}.png', button(base))
    z.writestr(f'assets/minecraft/textures/gui/sprites/widget/{name}.png.mcmeta', meta)
z.close()
PY
ls -la resourcepacks
