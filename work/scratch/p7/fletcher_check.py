#!/usr/bin/env python3
"""P7: Fletcher-16 arithmetic only. Recomputes the s1/s2 traces written in the HiddenTests.java
derivation comments and checks that each traced record (covered bytes + stored s2 s1) occurs in
the dumped inputs. Does not split, walk or interpret logs."""
import re
src = open('/root/Documents/test/wal-recovery/tests/hidden/HiddenTests.java').read()
# comment text only, joined, with the // markers removed
com = ' '.join(l.split('//', 1)[1] for l in src.splitlines() if l.strip().startswith('//'))
com = ' '.join(com.split())
dump = open('/root/Documents/test/work/scratch/p7/inputs.txt').read()
HX = r'(?:[0-9A-F]{2} )+'
pat = re.compile(r'(' + HX + r')[;(]?\s*s1 ([\d, ]+?)\s*(?:\([^)]*\))?;?\s*s2 ([\d, ]+?)\s*->\s*(?:correct )?([0-9A-F]{2}) ([0-9A-F]{2})')
n = bad = 0
for m in pat.finditer(com):
    cov = m.group(1).split()
    # keep only the trailing run that has as many bytes as the trace
    s1t = [int(x) for x in m.group(2).replace(' ', '').split(',') if x]
    s2t = [int(x) for x in m.group(3).replace(' ', '').split(',') if x]
    cov = cov[-len(s1t):]
    s1 = s2 = 0; t1 = []; t2 = []
    for h in cov:
        s1 = (s1 + int(h, 16)) % 255; s2 = (s2 + s1) % 255; t1.append(s1); t2.append(s2)
    res = '%02X %02X' % (s2, s1)
    ok = t1 == s1t and t2 == s2t and res == m.group(4) + ' ' + m.group(5)
    present = (' '.join(cov) + ' ' + res) in dump
    n += 1
    if not ok:
        bad += 1; print('TRACE MISMATCH:', ' '.join(cov), 'stated', s1t, s2t, m.group(4), m.group(5), 'computed', t1, t2, res)
    print(('ok  ' if ok else 'BAD ') + ('in-input ' if present else 'not-in-input ') + ' '.join(cov) + ' -> ' + res)
print('traces checked:', n, 'mismatches:', bad)
