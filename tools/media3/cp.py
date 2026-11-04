import os, struct, sys, json
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
if __name__ == "__main__":
    root = sys.argv[1]; mode = sys.argv[2]
    files = []
    for r,_,fs in os.walk(root):
        for f in fs:
            if f.endswith('.class'): files.append(os.path.join(r,f))
    ifaces = set(); own = set()
    for p in files:
        b = open(p,'rb').read(); cp, pos, end = parse(b)
        acc, this = struct.unpack('>HH', b[end:end+4]); name = cp[cp[this][1]][1]; own.add(name)
        if acc & 0x200: ifaces.add(name)
    if mode == 'ext':
        ext = set()
        for p in files:
            b = open(p,'rb').read(); cp, pos, end = parse(b)
            for e in cp:
                if e and e[0] == 10:
                    cn = cp[cp[e[1]][1]][1]
                    if cn not in own: ext.add(cn)
        print('\n'.join(sorted(ext)))
    else:
        ext_ifaces = set(open(mode).read().split())
        allif = ifaces | ext_ifaces; changed = 0
        for p in files:
            b = bytearray(open(p,'rb').read()); cp, pos, end = parse(b); dirty = False
            for k,e in enumerate(cp):
                if e and e[0] == 10 and cp[cp[e[1]][1]][1] in allif:
                    b[pos[k]] = 11; dirty = True; changed += 1
            if dirty: open(p,'wb').write(b)
        print('changed', changed)
