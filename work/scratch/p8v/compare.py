#!/usr/bin/env python3
"""v3 audit step 3: compare the expected literal of each in-scope test with the auditor's own
outcome (the 'Own outcome' column of the v3 table in test-audit.md). Text comparison only."""
import re
exec(open('/root/Documents/test/work/scratch/p8v/strip_v3.py').read().split('def main')[0])
exp = {}
for path in ['tests/public/PublicTests.java', 'tests/hidden/HiddenTests.java']:
    src = strip_comments(open(path, encoding='utf-8').read())
    i = 0
    while True:
        a = src.find('TestCase.of', i)
        if a < 0: break
        p = src.find('(', a)
        args, end = split_args(src, p + 1)
        exp[args[0].strip('"')] = eval(args[-1].replace('\\"', '\x00')).replace('\x00', '"') if False else args[-1]
        i = end
own = {}
txt = open('/root/Documents/test/work/notes/test-audit.md', encoding='utf-8').read()
part = txt.split('## v3 derivations')[1]
for line in part.splitlines():
    m = re.match(r'\| (P04|H\d\d) \|', line)
    if m:
        cells = line.split(' | ')
        o = re.search(r'`([^`]*)`', cells[3]).group(1)
        own[m.group(1)] = o
for tid in own:
    e = exp[tid]
    assert e.startswith('"') and e.endswith('"')
    e = e[1:-1].replace('\\"', '"').replace('\\\\', '\\')
    print(('SAME ' if e == own[tid] else 'DIFF ') + tid + ('' if e == own[tid] else '\n   exp: ' + e + '\n   own: ' + own[tid]))
print(len(own), 'compared')
