"""Checks a built Catgirl Pack zip against the real game files.
Usage: python3 validate_pack.py <pack.zip> <unzipped client jar dir> <vanilla sounds.json> <pack_version>
Prints every problem and exits 1 if there are any."""
import io
import json
import sys
import zipfile
from PIL import Image

problems, checked = [], 0


def bad(msg):
    problems.append(msg)
    print('PROBLEM:', msg)


def main(zpath, jar, vanilla_sounds_path, pack_version):
    global checked
    z = zipfile.ZipFile(zpath)
    names = set(z.namelist())
    if z.testzip() is not None:
        bad('zip is corrupt')
    # pack.mcmeta / pack.png
    meta = json.loads(z.read('pack.mcmeta'))['pack']
    for k in ('pack_format', 'min_format', 'max_format'):
        if meta.get(k) != pack_version:
            bad(f'pack.mcmeta {k} is {meta.get(k)}, the game is {pack_version}')
    icon = Image.open(io.BytesIO(z.read('pack.png')))
    if icon.width != icon.height:
        bad('pack.png is not square')
    # textures: must replace a real game texture, with a matching shape
    for n in sorted(names):
        if n.startswith('assets/catgirl/textures/') and n.endswith('.png'):
            continue  # our own textures, used by our models (checked below)
        if not n.startswith('assets/minecraft/textures/'):
            continue
        rel = n[len('assets/minecraft/'):]
        if n.endswith('.png.mcmeta'):
            try:
                m = json.loads(z.read(n))
            except Exception as e:
                bad(f'{n}: bad JSON ({e})'); continue
            if n[:-len('.mcmeta')] not in names:
                bad(f'{n}: no texture next to it')
            continue
        if not n.endswith('.png'):
            bad(f'{n}: unexpected file'); continue
        van = f'{jar}/assets/minecraft/{rel}'
        try:
            v = Image.open(van)
        except FileNotFoundError:
            bad(f'{n}: the game has no texture at {rel} (wrong name?)'); continue
        o = Image.open(io.BytesIO(z.read(n)))
        if o.mode not in ('RGBA', 'RGB', 'P', 'LA', 'L'):
            bad(f'{n}: odd image mode {o.mode}')
        checked += 1
        mm = n + '.mcmeta'
        if mm in names and 'animation' in json.loads(z.read(mm)):
            anim = json.loads(z.read(mm))['animation']
            fw = o.width
            if o.height % fw:
                bad(f'{n}: animated strip height {o.height} is not a multiple of its width {fw}'); continue
            nframes = o.height // fw
            idx = [f['index'] if isinstance(f, dict) else f for f in anim.get('frames', range(nframes))]
            if max(idx) >= nframes:
                bad(f'{n}: animation uses frame {max(idx)} but has only {nframes}')
            shape = (fw, fw)
        else:
            shape = o.size
        vw, vh = v.size
        if shape[0] * vh != shape[1] * vw:
            bad(f'{n}: shape {shape} does not match the game\'s {v.size}')
        if '/entity/equipment/' in n and shape != v.size:
            bad(f'{n}: armour must be exactly {v.size}, is {shape}')
    # item definitions and models: everything they point at must exist (in the pack or the game)
    import os
    def exists(ns_path, kind, ext):
        ns, path = ns_path.split(':') if ':' in ns_path else ('minecraft', ns_path)
        rel = f'assets/{ns}/{kind}/{path}{ext}'
        return rel in names or os.path.exists(f'{jar}/{rel}')
    for n in sorted(names):
        if n.startswith('assets/minecraft/items/') and n.endswith('.json'):
            if not os.path.exists(f'{jar}/{n}'):
                bad(f'{n}: the game has no item called that')
            d = json.loads(z.read(n))
            m = d.get('model', {})
            if m.get('type') == 'minecraft:model' and not exists(m['model'], 'models', '.json'):
                bad(f'{n}: model {m["model"]} does not exist')
            checked += 1
        if '/models/' in n and n.endswith('.json'):
            d = json.loads(z.read(n))
            for k, v in d.get('textures', {}).items():
                if not v.startswith('#') and not exists(v, 'textures', '.png'):
                    bad(f'{n}: texture {k}={v} does not exist')
            for e in d.get('elements', []):
                if 'rotation' in e and e['rotation']['angle'] not in (-45, -22.5, 0, 22.5, 45):
                    bad(f'{n}: rotation {e["rotation"]["angle"]} is not allowed')
                for p in e['from'] + e['to']:
                    if not -16 <= p <= 32:
                        bad(f'{n}: element {e.get("name")} goes outside -16..32')
                for fn, fd in e['faces'].items():
                    if fn not in ('north', 'south', 'east', 'west', 'up', 'down'):
                        bad(f'{n}: bad face {fn}')
                    if fd['texture'].startswith('#') and fd['texture'][1:] not in d.get('textures', {}):
                        bad(f'{n}: face uses {fd["texture"]} which is not defined')
            checked += 1
    # sounds
    vs = json.load(open(vanilla_sounds_path))
    ours = json.loads(z.read('assets/minecraft/sounds.json'))
    used = set()
    for ev, d in ours.items():
        if ev not in vs:
            bad(f'sounds.json: the game has no sound event "{ev}"'); continue
        if d.get('subtitle') != vs[ev].get('subtitle'):
            bad(f'sounds.json {ev}: subtitle {d.get("subtitle")} but the game uses {vs[ev].get("subtitle")}')
        if not d.get('replace'):
            bad(f'sounds.json {ev}: should replace the old sounds')
        for s in d['sounds']:
            name = s['name'] if isinstance(s, dict) else s
            ns, path = name.split(':')
            f = f'assets/{ns}/sounds/{path}.ogg'
            used.add(f)
            if f not in names:
                bad(f'sounds.json {ev}: missing file {f}'); continue
            data = z.read(f)
            i = data.find(b'\x01vorbis')
            if not data.startswith(b'OggS') or i < 0:
                bad(f'{f}: not an Ogg Vorbis file'); continue
            channels = data[i + 11]
            if channels != 1:
                bad(f'{f}: has {channels} channels; it must be mono or it will not fade with distance')
            checked += 1
    for n in names:
        if n.endswith('.ogg') and n not in used:
            bad(f'{n}: not used by sounds.json')
    print(f'checked {checked} files, {len(problems)} problem(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])))
