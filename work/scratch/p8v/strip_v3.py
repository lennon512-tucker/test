#!/usr/bin/env python3
"""v3 audit helper: print id + input-building code only, for the tests in scope.

Strips comments, test names, rule lists and expected strings. Only reformats
source text; it never runs, encodes or decodes anything.
Usage: strip_v3.py FILE... [--all-ids]   (default scope: P04, H11, H20, H70-H99)
"""
import sys

SCOPE = {"P04", "H11", "H20"} | {"H%02d" % i for i in range(70, 100)}

def strip_comments(src):
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; out.append(' ')
        elif src[i] in '"\'':
            q = src[i]; j = i + 1
            while src[j] != q:
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        else:
            out.append(src[i]); i += 1
    return ''.join(out)

def split_args(s, i):
    args, depth, cur = [], 0, []
    while True:
        c = s[i]
        if c in '"\'':
            j = i + 1
            while s[j] != c:
                j += 2 if s[j] == '\\' else 1
            cur.append(s[i:j + 1]); i = j + 1; continue
        if c in '([{': depth += 1
        elif c in ')]}':
            if depth == 0:
                args.append(''.join(cur).strip()); return args, i + 1
            depth -= 1
        elif c == ',' and depth == 0:
            args.append(''.join(cur).strip()); cur = []; i += 1; continue
        cur.append(c); i += 1

def main():
    files = [a for a in sys.argv[1:] if not a.startswith('--')]
    allids = '--all-ids' in sys.argv
    for path in files:
        src = strip_comments(open(path, encoding='utf-8').read())
        rest, i = [], 0
        tests = []
        while True:
            a = src.find('TestCase.of', i)
            if a < 0:
                rest.append(src[i:]); break
            rest.append(src[i:a])
            p = src.find('(', a)
            kind = src[a:p].strip()
            args, end = split_args(src, p + 1)
            tid = args[0].strip('"')
            code = 'NULL INPUT' if kind == 'TestCase.ofNull' else ' '.join(args[3].split())
            tests.append((tid, code))
            rest.append('<TEST %s>' % tid)
            i = end
        print('==== %s : non-test code ====' % path)
        print('\n'.join(l for l in ''.join(rest).splitlines() if l.strip()))
        print('==== %s : tests ====' % path)
        for tid, code in tests:
            if allids or tid in SCOPE:
                print('%s: %s' % (tid, code))

main()
