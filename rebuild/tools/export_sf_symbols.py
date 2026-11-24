"""Export SF Symbols from the owner's SFStudio library (github.com/lylicht/SFStudio) as SVG files.

Usage: python -I export_sf_symbols.py <SFStudio checkout> <out dir> <symbol.name> [...]
Each symbol is written as <symbol.name>.svg with fill="currentColor" so the app can colour it with CSS.
Only reads SFStudio's monochrome glyph files (path data + viewport); runs nothing from the checkout.
"""
import os, re, sys

VIEW = re.compile(r'viewportWidth = ([0-9.]+)f,\s*viewportHeight = ([0-9.]+)f')
PATH = re.compile(r'addSfPath\("([^"]+)"([^)]*)\)')


def kotlin_name(symbol):
    parts = re.split(r'[.]', symbol)
    return 'SF' + ''.join(p[:1].upper() + p[1:] for p in parts)


def export(root, out, symbol):
    path = os.path.join(root, 'sfsymbols', 'src', 'commonMain', 'kotlin', 'com', 'composables', 'sfsymbols',
                        'monochrome', kotlin_name(symbol) + '.kt')
    text = open(path, encoding='utf-8').read()
    w, h = VIEW.search(text).groups()
    paths = []
    for d, rest in PATH.findall(text):
        rule = ' fill-rule="evenodd"' if 'EvenOdd' in rest else ''
        alpha = re.search(r'fillAlpha = ([0-9.]+)f', rest)
        opacity = f' fill-opacity="{alpha.group(1)}"' if alpha and float(alpha.group(1)) != 1.0 else ''
        paths.append(f'<path d="{d}"{rule}{opacity}/>')
    if not paths:
        raise ValueError(f'{symbol}: no path data in {path}')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" fill="currentColor" '
           f'aria-hidden="true">{"".join(paths)}</svg>\n')
    with open(os.path.join(out, symbol + '.svg'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(svg)


def main():
    root, out, symbols = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    missing = []
    for s in symbols:
        try:
            export(root, out, s)
        except (OSError, AttributeError, ValueError) as e:
            missing.append(f'{s}: {e}')
    print(f'exported {len(symbols) - len(missing)} of {len(symbols)}')
    for m in missing:
        print('MISSING', m)
    if missing:
        sys.exit(1)


if __name__ == '__main__':
    main()
