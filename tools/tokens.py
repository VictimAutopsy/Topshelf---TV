#!/usr/bin/env python3
"""Swaps a Java file's literal colours, small text sizes and timings for the shared steps in Ui.java.

    python3 tools/tokens.py app/src/main/java/com/topshelf/launcher/NativeHome.java [more files]

Only whites and blacks with some transparency, text() sizes up to 1.55 rem and setDuration() values from
150 to 600 ms are touched; each moves to the nearest step (at most 5% strength, .05 rem, 50 ms).
Fully clear and fully solid colours are left alone (they're also used as bit masks)."""
import re, sys

WHITE = {'F2': 95, 'D9': 85, 'BF': 75, '99': 60, '80': 50, '66': 40, '40': 25, '33': 20, '1F': 12, '12': 7}
BLACK = {'E6': 90, 'D9': 85, 'B3': 70, '99': 60, '8C': 55, '73': 45, '40': 25}
# which step a strength snaps to (alpha, in hex) — listed, not computed, so nothing moves by surprise
W_SNAP = {'F2': 'F2', 'F0': 'F2', 'E6': 'F2', 'E0': 'D9', 'DD': 'D9', 'D9': 'D9', 'CC': 'D9',
          'C7': 'BF', 'BF': 'BF', 'B8': 'BF', 'B3': 'BF', 'A6': '99', '9E': '99', '99': '99', '8C': '99', '80': '80',
          '66': '66', '61': '66', '59': '66', '47': '40', '42': '40', '40': '40', '3D': '40',
          '38': '33', '33': '33', '2E': '33', '26': '1F', '24': '1F', '1F': '1F', '1A': '1F', '14': '12', '12': '12', '0D': '12'}
B_SNAP = {'F0': 'E6', 'E6': 'E6', 'D9': 'D9', 'CC': 'D9', 'B8': 'B3', 'B3': 'B3', 'A6': '99', '99': '99',
          '8C': '8C', '80': '8C', '73': '73', '6B': '73', '66': '73', '40': '40'}
SIZES = [(.85, .95, 'TEXT_XS'), (1.0, 1.1, 'TEXT_S'), (1.15, 1.25, 'TEXT_M'), (1.3, 1.4, 'TEXT_L'), (1.45, 1.55, 'TEXT_XL')]
TIMES = {150: 'QUICK', 160: 'QUICK', 180: 'FAST', 190: 'FAST', 200: 'FAST', 220: 'FAST', 240: 'BASE', 250: 'BASE', 260: 'BASE',
         280: 'MOVE', 300: 'MOVE', 320: 'MOVE', 350: 'SLIDE', 360: 'SLIDE', 380: 'SLIDE', 400: 'SLIDE', 420: 'SLIDE',
         450: 'SLOW', 500: 'SLOW', 550: 'SLOW', 600: 'SLOWER'}

def colour(m):
    a, rest = m.group(1).upper(), m.group(2).upper()
    snap, names, word = (W_SNAP, WHITE, 'WHITE_') if rest == 'FFFFFF' else (B_SNAP, BLACK, 'BLACK_')
    return 'Ui.' + word + str(names[snap[a]]) if a in snap else m.group(0)

def size(m):
    v = float(m.group(2))
    for lo, hi, name in SIZES:
        if lo - 1e-6 <= v <= hi + 1e-6: return m.group(1) + 'Ui.' + name
    return m.group(0)

def time(m):
    inner = re.sub(r'(?<![\w.])(\d{3})(?![\w.])', lambda n: 'Ui.' + TIMES[int(n.group(1))] if int(n.group(1)) in TIMES else n.group(0), m.group(1))
    return 'setDuration(' + inner + ')'

for path in sys.argv[1:]:
    s = old = open(path, encoding='utf-8').read()
    s = re.sub(r'0x([0-9A-Fa-f]{2})(FFFFFF|ffffff|000000)\b', colour, s)
    s = re.sub(r'(\btext\((?:h\.)?f(?:Reg|Med|Semi), *)(\d*\.?\d+)f\b', size, s)
    s = re.sub(r'setDuration\(([^()]*)\)', time, s)
    if s != old: open(path, 'w', encoding='utf-8').write(s)
    print(path.split('/')[-1], 'changed' if s != old else 'unchanged')
