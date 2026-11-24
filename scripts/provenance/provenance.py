"""Line-overlap provenance scan: what share of each nuvx source file's meaningful lines also occur in an upstream tree.

Usage: python -I provenance.py <nuvx_tree> <out_csv> <name>=<upstream_tree> [...]
Reads files only; never executes anything from the trees.
"""
import csv, os, re, sys
from collections import defaultdict

EXT = {'.kt', '.kts', '.java', '.swift', '.rs', '.ts', '.tsx', '.js', '.cpp', '.cc', '.h', '.c', '.go', '.py', '.css'}
BRAND = re.compile(r'bitchord|convx|nuvx|metrolist|licht|kushagrasinghx|cosmictaserdev', re.I)
SKIP = re.compile(r'^(import |package |using |#include|//|/\*|\*|\*/|@file:|use |mod |pub mod )')


def lines_of(path):
    try:
        text = open(path, encoding='utf-8', errors='ignore').read()
    except OSError:
        return []
    out = []
    for raw in text.splitlines():
        s = raw.strip()
        if len(s) < 25 or SKIP.match(s):
            continue
        s = BRAND.sub('BRAND', s)
        s = re.sub(r'\s+', ' ', s)
        out.append(s)
    return out


def walk(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ('.git', 'node_modules', 'build', 'target', 'vendor', 'third_party', 'dist')]
        for f in files:
            if os.path.splitext(f)[1] in EXT:
                yield os.path.join(d, f)


def main():
    nuvx, out_csv, ups = sys.argv[1], sys.argv[2], sys.argv[3:]
    upstream = {}
    for spec in ups:
        name, root = spec.split('=', 1)
        seen = set()
        for p in walk(root):
            seen.update(lines_of(p))
        upstream[name] = seen
    rows = []
    for p in walk(nuvx):
        ls = lines_of(p)
        if len(ls) < 5:
            continue
        rel = os.path.relpath(p, nuvx).replace('\\', '/')
        row = {'file': rel, 'lines': len(ls)}
        for name, seen in upstream.items():
            row[name] = round(sum(1 for l in ls if l in seen) / len(ls), 3)
        rows.append(row)
    names = list(upstream)
    with open(out_csv, 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=['file', 'lines'] + names)
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: r['file']))
    # summary: per top-level area, lines-weighted share above thresholds
    area = defaultdict(lambda: defaultdict(float))
    for r in rows:
        a = '/'.join(r['file'].split('/')[:2])
        area[a]['lines'] += r['lines']
        best = max(r[n] for n in names)
        area[a]['derived'] += r['lines'] if best >= 0.5 else 0
        area[a]['partial'] += r['lines'] if 0.15 <= best < 0.5 else 0
    print(f"{'area':32} {'lines':>8} {'>=50%':>7} {'15-50%':>7}")
    for a, v in sorted(area.items(), key=lambda kv: -kv[1]['lines']):
        print(f"{a:32} {int(v['lines']):8} {v['derived']/v['lines']:7.0%} {v['partial']/v['lines']:7.0%}")
    tot = sum(v['lines'] for v in area.values())
    print('total', int(tot), 'derived>=50%:', f"{sum(v['derived'] for v in area.values())/tot:.0%}",
          'partial:', f"{sum(v['partial'] for v in area.values())/tot:.0%}")
    for n in names:
        hits = sum(1 for r in rows if r[n] >= 0.5)
        print(f'files >=50% shared with {n}: {hits} of {len(rows)}')


if __name__ == '__main__':
    main()
