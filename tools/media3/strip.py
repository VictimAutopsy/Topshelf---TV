import os, struct, sys
def parse(b):
    n = struct.unpack('>H', b[8:10])[0]; i = 10; cp = [None]*n; pos = [None]*n; k = 1
    while k < n:
        t = b[i]; pos[k] = i
        if t == 1: l = struct.unpack('>H', b[i+1:i+3])[0]; cp[k] = (1, b[i+3:i+3+l].decode('utf8', 'replace')); i += 3+l
        elif t in (3,4): cp[k]=(t,); i += 5
        elif t in (5,6): cp[k]=(t,); i += 9; k += 1
        elif t == 7: cp[k] = (7, struct.unpack('>H', b[i+1:i+3])[0]); i += 3
        elif t in (8,16,19,20): cp[k]=(t, struct.unpack('>H', b[i+1:i+3])[0]); i += 3
        elif t in (9,10,11,12,18,17): cp[k] = (t,)+struct.unpack('>HH', b[i+1:i+5]); i += 5
        elif t == 15: cp[k]=(t,); i += 4
        else: raise Exception('tag %d' % t)
        k += 1
    return cp, pos, i
DROP = {b'MethodParameters'}
def strip(b):
    cp, pos, i = parse(b)
    name = lambda k: cp[k][1].encode() if cp[k] and cp[k][0] == 1 else b''
    out = bytearray(b[:i]); out += b[i:i+6]; i += 6
    ni = struct.unpack('>H', b[i:i+2])[0]; out += b[i:i+2+2*ni]; i += 2 + 2*ni
    removed = 0
    def members(i):
        nonlocal removed
        n = struct.unpack('>H', b[i:i+2])[0]; o = bytearray(b[i:i+2]); i += 2
        for _ in range(n):
            o += b[i:i+6]; i += 6
            na = struct.unpack('>H', b[i:i+2])[0]; i += 2; keep = []
            for _ in range(na):
                nm, ln = struct.unpack('>HI', b[i:i+6]); seg = b[i:i+6+ln]; i += 6 + ln
                if name(nm) in DROP: removed += 1
                else: keep.append(seg)
            o += struct.pack('>H', len(keep)); [o.__iadd__(s) for s in keep]
        return o, i
    o, i = members(i); out += o
    o, i = members(i); out += o
    out += b[i:]
    return bytes(out), removed
tot = 0
for r,_,fs in os.walk(sys.argv[1]):
    for f in fs:
        if f.endswith('.class'):
            p = os.path.join(r, f); b = open(p,'rb').read(); nb, n = strip(b)
            if n: open(p,'wb').write(nb); tot += n
print('removed', tot)
