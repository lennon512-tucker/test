#!/usr/bin/env python3
"""v3 audit: for each in-scope test, list the stored checksum bytes of every frame (split by N
only; dump bytes) and the hex pairs written as '(XX YY)' / 'checksum XX YY' in the test comment.
Reports pairs in the comment that are not a stored checksum of that test, and frames whose stored
checksum is not mentioned. Text/byte comparison only."""
import re
d = {}
for ln in open('inputs.txt'):
    p = ln.split()
    if p[1] != 'NULL': d[p[0]] = [int(x, 16) for x in p[2:]]
def frames(b):
    out, p = [], 4
    while p + 3 <= len(b):
        n = b[p+1]*256 + b[p+2]; e = p + 5 + n
        if e <= len(b): out.append((p, '%02X %02X' % (b[e-2], b[e-1])))
        p = e
    return out
src = open('/root/Documents/test/wal-recovery/tests/hidden/HiddenTests.java').read() + open('/root/Documents/test/wal-recovery/tests/public/PublicTests.java').read()
blocks = re.split(r'\n\s*// (?=(?:H\d\d|P0\d) )', src)
com = {}
for bl in blocks:
    m = re.match(r'(H\d\d|P0\d) ', bl)
    if m:
        c = '\n'.join(l for l in bl.splitlines() if l.strip().startswith('//') or l is bl.splitlines()[0])
        com[m.group(1)] = c
scope = ['P04', 'H11', 'H20'] + ['H%d' % i for i in range(70, 100)]
for t in scope:
    fr = frames(d[t]); stored = [c for _, c in fr]
    pairs = re.findall(r'\b([0-9A-F]{2} [0-9A-F]{2})\b', com[t])
    extra = [x for x in pairs if x not in stored]
    missing = [(o, c) for o, c in fr if c not in pairs]
    print(t, 'frames', ' '.join('%d:%s' % f for f in fr))
    print('   not-stored pairs in comment:', extra, '| stored not mentioned:', missing)
