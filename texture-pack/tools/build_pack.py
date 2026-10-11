"""Builds the whole Catgirl Pack from the drawing/sound scripts next to this file.
Usage: python3 build_pack.py <skin.png> <out_dir>
Writes <out_dir>/CatgirlPack/ (the folder) and <out_dir>/CatgirlPack.zip."""
import json
import os
import shutil
import subprocess
import sys
import zipfile
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PACK_FORMAT = 75  # Minecraft 1.21.11
VERSION = '1.0.0'


def run(script, *args):
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True)


def sounds_json():
    def ev(sub, files, volume=1.0):
        return {'replace': True, 'subtitle': sub,
                'sounds': [{'name': f'catgirl:{f}', 'volume': volume} for f in files]}
    j = {
        'item.totem.use': ev('subtitles.item.totem.use', ['item/totem_use']),
        'entity.player.attack.sweep': ev('subtitles.entity.player.attack.sweep', [f'weapon/sweep{i}' for i in (1, 2, 3)], 0.8),
        'entity.player.attack.strong': ev('subtitles.entity.player.attack.strong', [f'weapon/strong{i}' for i in (1, 2, 3)], 0.8),
        'entity.player.attack.crit': ev('subtitles.entity.player.attack.crit', [f'weapon/crit{i}' for i in (1, 2, 3)], 0.8),
    }
    for kind in ('generic', 'leather', 'chain', 'iron', 'gold', 'diamond', 'netherite'):
        sub = 'subtitles.item.armor.equip' if kind == 'generic' else f'subtitles.item.armor.equip_{kind}'
        j[f'item.armor.equip_{kind}'] = ev(sub, [f'armor/equip_{kind}{i}' for i in (1, 2)], 0.9)
    return j


def pack_png(catgirl_assets, out):
    """128x128 icon: the 3D totem on a soft pink-to-lilac background."""
    bg = Image.new('RGBA', (128, 128))
    d = ImageDraw.Draw(bg)
    for y in range(128):
        k = y / 127
        d.line([(0, y), (127, y)], fill=(round(255 - 60 * k), round(170 - 60 * k), round(220 + 20 * k), 255))
    sys.path.insert(0, HERE)
    from render_model import render
    model = json.load(open(os.path.join(catgirl_assets, 'models', 'item', 'totem_doll.json')))
    model['display']['icon'] = {'rotation': [12, -25, 0], 'scale': [0.95, 0.95, 0.95]}
    tex = Image.open(os.path.join(catgirl_assets, 'textures', 'item', 'totem_doll.png')).convert('RGBA')
    doll = render(model, tex, 'icon', px=24, bg=(0, 0, 0, 0)).resize((128, 128), Image.LANCZOS)
    bg.alpha_composite(doll, (0, 2))
    bg.save(out)


def main(skin, out):
    pack = os.path.join(out, 'CatgirlPack')
    shutil.rmtree(pack, ignore_errors=True)
    mc = os.path.join(pack, 'assets', 'minecraft')
    tex = os.path.join(mc, 'textures')
    item = os.path.join(tex, 'item')
    os.makedirs(item, exist_ok=True)
    # the totem is a 3D "mini you" figure made from the skin
    run('draw_totem3d.py', skin, os.path.join(pack, 'assets', 'catgirl'))
    os.makedirs(os.path.join(mc, 'items'), exist_ok=True)
    with open(os.path.join(mc, 'items', 'totem_of_undying.json'), 'w') as f:
        json.dump({'model': {'type': 'minecraft:model', 'model': 'catgirl:item/totem_doll'}}, f, indent=2)
    run('draw_weapons.py', item)
    run('draw_armor.py', os.path.join(tex, 'entity', 'equipment'))
    run('draw_effects.py', tex)
    run('make_sounds.py', os.path.join(pack, 'assets', 'catgirl', 'sounds'))
    with open(os.path.join(mc, 'sounds.json'), 'w') as f:
        json.dump(sounds_json(), f, indent=2)
    with open(os.path.join(pack, 'pack.mcmeta'), 'w') as f:
        json.dump({'pack': {
            'pack_format': PACK_FORMAT, 'min_format': PACK_FORMAT, 'max_format': PACK_FORMAT,
            'description': [{'text': 'Catgirl Pack ', 'color': '#ff7eb6'}, {'text': f'v{VERSION} ', 'color': 'gray'},
                            {'text': 'nya~ ', 'color': '#c89bff'}, {'text': 'catgirlclient.lol', 'color': 'gray'}],
        }}, f, indent=2)
    pack_png(os.path.join(pack, 'assets', 'catgirl'), os.path.join(pack, 'pack.png'))
    shutil.copy(os.path.join(HERE, '..', 'LICENSE.txt'), os.path.join(pack, 'LICENSE.txt'))
    # zip it (files sorted, fixed timestamps, so the same input gives the same zip)
    zpath = os.path.join(out, 'CatgirlPack.zip')
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, _, files in sorted(os.walk(pack)):
            for name in sorted(files):
                full = os.path.join(root, name)
                info = zipfile.ZipInfo(os.path.relpath(full, pack).replace(os.sep, '/'), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(full, 'rb') as fh:
                    z.writestr(info, fh.read())
    print(zpath, os.path.getsize(zpath), 'bytes')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
