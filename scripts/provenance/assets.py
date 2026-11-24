"""Byte-identical asset scan: which nuvx images, fonts, icons and media also exist in an upstream tree.

Usage: python -I assets.py <nuvx_tree> <out_csv> <name>=<upstream_tree> [...]
"""
import csv, hashlib, os, sys
from collections import Counter

EXT = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg', '.ico', '.icns', '.ttf', '.otf', '.woff', '.woff2',
       '.mp3', '.ogg', '.wav', '.json', '.xml', '.onnx', '.tflite', '.mlmodel'}
ASSET_DIRS = ('res/', 'assets/', 'public/', 'resources/', 'brand/', 'fonts/', 'icons/', 'Assets.xcassets', 'drawable', 'mipmap')


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def assets(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ('.git', 'node_modules', 'build', 'target')]
        for f in files:
            p = os.path.join(d, f)
            rel = os.path.relpath(p, root).replace('\\', '/')
            ext = os.path.splitext(f)[1].lower()
            if ext in EXT and (ext not in ('.json', '.xml') or any(a in rel for a in ASSET_DIRS)):
                yield rel, p


def main():
    nuvx, out_csv, ups = sys.argv[1], sys.argv[2], sys.argv[3:]
    index = {}
    for spec in ups:
        name, root = spec.split('=', 1)
        for rel, p in assets(root):
            index.setdefault(digest(p), []).append(f'{name}:{rel}')
    rows, kinds = [], Counter()
    for rel, p in assets(nuvx):
        hit = index.get(digest(p))
        ext = os.path.splitext(rel)[1].lower()
        rows.append({'file': rel, 'type': ext, 'identical_in': ';'.join(hit[:3]) if hit else ''})
        kinds[(ext, bool(hit))] += 1
    with open(out_csv, 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=['file', 'type', 'identical_in'])
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: r['file']))
    exts = sorted({e for e, _ in kinds})
    print(f"{'type':8} {'total':>6} {'identical upstream':>19}")
    for e in exts:
        print(f'{e:8} {kinds[(e, True)] + kinds[(e, False)]:6} {kinds[(e, True)]:19}')
    print('total', len(rows), 'identical upstream:', sum(1 for r in rows if r['identical_in']))


if __name__ == '__main__':
    main()
