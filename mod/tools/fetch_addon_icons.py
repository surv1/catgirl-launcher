"""Downloads each add-on's logo from Modrinth (the mod's own icon, as shown on its Modrinth page)
and saves it as a 64x64 PNG for the Catgirl menu. Usage: python3 fetch_addon_icons.py <addons.json> <out_dir>"""
import io
import json
import os
import sys
import urllib.request
from PIL import Image

UA = {'User-Agent': 'catgirl-launcher-ci (catgirlclient.lol)'}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


def main(path, out):
    os.makedirs(out, exist_ok=True)
    for a in json.load(open(path)):
        project = json.loads(get(f"https://api.modrinth.com/v2/project/{a['slug']}"))
        url = project.get('icon_url')
        if not url:
            print(a['name'], 'has no icon')
            continue
        img = Image.open(io.BytesIO(get(url))).convert('RGBA')
        side = max(img.size)
        sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
        sq.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
        sq.resize((64, 64), Image.LANCZOS).save(os.path.join(out, a['slug'] + '.png'), optimize=True)
        print(a['name'], 'ok', url)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
