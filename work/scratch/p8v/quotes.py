#!/usr/bin/env python3
"""v3 audit step 4: every "..." quote in the in-scope test comments must occur verbatim in
PROMPT.md (whitespace normalised; '...' inside a quote = elision, each part checked in order)."""
import re
prompt = ' '.join(open('/root/Documents/test/wal-recovery/task/PROMPT.md', encoding='utf-8').read().split())
prompt_plain = prompt.replace('**', '').replace('`', '')
srcs = open('/root/Documents/test/wal-recovery/tests/hidden/HiddenTests.java').read() + open('/root/Documents/test/wal-recovery/tests/public/PublicTests.java').read()
blocks = re.split(r'\n\s*// (?=(?:H\d\d|P0\d) )', srcs)
scope = {'P04', 'H11', 'H20'} | {'H%d' % i for i in range(70, 100)}
n = bad = 0
for bl in blocks:
    m = re.match(r'(H\d\d|P0\d) ', bl)
    if not m or m.group(1) not in scope: continue
    lines = [bl.splitlines()[0]] + [l.strip()[2:].strip() for l in bl.splitlines()[1:] if l.strip().startswith('//')]
    text = ' '.join(' '.join(lines).split())
    text = text.split('Expected:')[0]
    for q in re.findall(r'"([^"]{12,})"', text):
        n += 1
        parts = [p.strip() for p in q.split('...')]
        pos, ok = 0, True
        for p in parts:
            if not p: continue
            i = prompt_plain.find(p, pos)
            if i < 0: ok = False; break
            pos = i + len(p)
        if not ok: bad += 1
        print(('OK  ' if ok else 'BAD ') + m.group(1) + ': ' + q)
print(n, 'quotes,', bad, 'not found')
