"""Checks the Catgirl menu's add-on list against Modrinth: each mod must exist for this Minecraft
version on Fabric, and its jar's mod id must match ours (that's how the menu knows it's installed).
Usage: python3 check_addons.py <addons.json> <minecraft version>"""
import io
import json
import sys
import urllib.parse
import urllib.request
import zipfile

UA = {'User-Agent': 'catgirl-launcher-ci (catgirlclient.lol)'}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


def main(path, mc):
    problems, rows = [], []
    for a in json.load(open(path)):
        q = urllib.parse.urlencode({'loaders': json.dumps(['fabric']), 'game_versions': json.dumps([mc])})
        vs = json.loads(get(f"https://api.modrinth.com/v2/project/{a['slug']}/version?{q}"))
        if not vs:
            rows.append(f"{a['name']}: not out for {mc} yet (shown as such in the menu)")
            continue
        v = vs[0]
        f = next((x for x in v['files'] if x.get('primary')), v['files'][0])
        jar = zipfile.ZipFile(io.BytesIO(get(f['url'])))
        mid = json.loads(jar.read('fabric.mod.json'))['id']
        deps = [d['project_id'] for d in v.get('dependencies', []) if d.get('dependency_type') == 'required' and d.get('project_id')]
        rows.append(f"{a['name']}: {v['version_number']} id={mid} deps={deps}")
        if mid != a['id']:
            problems.append(f"{a['name']}: jar id is {mid}, addons.json says {a['id']}")
    print('\n'.join(rows))
    for p in problems:
        print('PROBLEM:', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1], sys.argv[2]))
