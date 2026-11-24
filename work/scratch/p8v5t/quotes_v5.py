#!/usr/bin/env python3
"""List quoted phrases (>= 12 chars) in the comment blocks of in-scope tests and check that
each piece (split at '...') occurs verbatim in PROMPT.md (whitespace-normalised). Text only."""
import re, sys
scope = {"H09", "H11", "H20"} | {"H%d" % i for i in range(124, 163)}
prompt = ' '.join(open(sys.argv[1], encoding='utf-8').read().split())
lines = open(sys.argv[2], encoding='utf-8').read().splitlines()
blocks, cur, tid = {}, [], None
for l in lines:
    s = l.strip()
    if s.startswith('//'):
        m = re.match(r'// (H\d+) ', s)
        if m: tid, cur = m.group(1), []
        cur.append(s[2:].strip())
    elif tid:
        blocks.setdefault(tid, ' '.join(cur)); tid = None
for t in sorted(blocks, key=lambda x: int(x[1:])):
    if t not in scope: continue
    for q in re.findall(r'"([^"]{12,})"', blocks[t]):
        parts = [p.strip() for p in q.split('...') if p.strip()]
        ok = all(' '.join(p.split()) in prompt for p in parts)
        print(('OK  ' if ok else 'BAD ') + t + ': "' + q + '"')
