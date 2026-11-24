#!/usr/bin/env python3
"""v3 audit step 5 (regression check for H01-H69): from the byte dump only, split each input into
frames by N (type u8 at p, N u16 at p+1, next p = p + 5 + N, for every p with at least 3 bytes
left). Flags (a) any frame start whose type byte is 6 or 7, (b) logs with more than 8 frames of
type byte 2 or 3; for those, the frames are grouped by their first 4 payload bytes (raw hex, not
interpreted). No checksum check, no rule logic, no outcome."""
from collections import Counter
for ln in open('inputs.txt'):
    p = ln.split(); tid = p[0]
    if not (tid.startswith('H') and 1 <= int(tid[1:]) <= 69): continue
    if p[1] == 'NULL':
        print(tid, 'NULL input (no frames)'); continue
    b = [int(x, 16) for x in p[2:]]; L = len(b)
    q, starts = 4, []
    while q + 3 <= L:
        t = b[q]; n = b[q+1]*256 + b[q+2]
        starts.append((q, t, n)); q += 5 + n
    t67 = [(q, t) for q, t, n in starts if t in (6, 7)]
    w = [(q, ' '.join('%02X' % x for x in b[q+3:q+7])) for q, t, n in starts if t in (2, 3)]
    c = Counter(x for _, x in w)
    flag = ('TYPE6/7 at %s' % t67 if t67 else '') + (' >8 writes total; by txn bytes %s' % dict(c) if len(w) > 8 else '')
    print('%s L=%d frames=%d types=%s writes=%d %s' % (tid, L, len(starts), ''.join(str(t) if t < 10 else 'x' for _, t, _ in starts), len(w), flag or 'ok'))
