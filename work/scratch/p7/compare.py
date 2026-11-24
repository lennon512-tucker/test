#!/usr/bin/env python3
"""Compare the auditor's own derived outcomes (test-audit.md Part 1) with each test's expected literal.
Text comparison only."""
import re, sys
sys.path.insert(0, '/root/Documents/test/work/scratch/p7')
from strip_tests import strip_comments, split_args

def expected(path):
    src = strip_comments(open(path, encoding='utf-8').read())
    out, i = {}, 0
    while True:
        a = src.find('TestCase.of', i)
        if a < 0: return out
        p = src.find('(', a)
        kind = src[a:p].strip()
        args, i = split_args(src, p + 1)
        lit = args[4] if kind == 'TestCase.of' else args[3]
        out[eval(args[0])] = (args[1], args[2], eval(lit.replace('\\"', '\\"')))

base = '/root/Documents/test/wal-recovery/tests/'
exp = {}
exp.update(expected(base + 'public/PublicTests.java'))
exp.update(expected(base + 'hidden/HiddenTests.java'))
mine = {}
part1 = open('/root/Documents/test/work/notes/test-audit.md').read().split('## Part 1')[1].split('## Part 2')[0]
for line in part1.splitlines():
    m = re.match(r'\| ([PH]\d\d) \|.*\| `([^`]*)` \|$', line)
    if m: mine[m.group(1)] = m.group(2)
print('mine:', len(mine), 'tests:', len(exp))
for tid in exp:
    name, rules, e = exp[tid]
    flag = 'SAME' if mine.get(tid) == e else 'DIFF'
    print(f'{tid}\t{flag}\t{rules}\t{name}')
    if flag == 'DIFF':
        print('   mine:', mine.get(tid)); print('   test:', e)
