#!/usr/bin/env python3
"""v3 audit: split each dumped input into frames by N only (type u8, N u16, skip 5+N).
Prints L and, per frame: offset, type byte, N, end offset. No checksum check, no payload decoding,
no rule logic. Usage: frames.py inputs.txt [ID...]"""
import sys
lines = open(sys.argv[1]).read().splitlines()
want = set(sys.argv[2:])
for ln in lines:
    parts = ln.split()
    tid = parts[0]
    if want and tid not in want: continue
    if parts[1] == 'NULL':
        print(tid, 'NULL'); continue
    L = int(parts[1][2:])
    b = [int(x, 16) for x in parts[2:]]
    assert len(b) == L
    out = []
    p = 4
    while p + 3 <= L:
        t = b[p]; n = b[p + 1] * 256 + b[p + 2]
        out.append('%d:t%d,N%d,end%d' % (p, t, n, p + 5 + n))
        p = p + 5 + n
    if p < L: out.append('%d:rest%d' % (p, L - p))
    print(tid, 'L=%d' % L, ' '.join(out))
