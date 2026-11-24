#!/usr/bin/env python3
"""P7 audit helper: print each test's id and its input-building code only.

Comments, test names, rule lists and expected strings are stripped out.
Non-test code (helper methods/constants) is printed too, also comment-free.
This script only reformats source text; it never runs or decodes anything.
"""
import sys

def strip_comments(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        else:
            out.append(c); i += 1
    return ''.join(out)

def split_args(s, start):
    """s[start] is just after '('. Returns (list of arg strings, index after ')')."""
    args, depth, cur, i = [], 0, [], start
    while i < len(s):
        c = s[i]
        if c in '"\'':
            j = i + 1
            while s[j] != c:
                j += 2 if s[j] == '\\' else 1
            cur.append(s[i:j + 1]); i = j + 1; continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            if depth == 0:
                args.append(''.join(cur).strip()); return args, i + 1
            depth -= 1
        elif c == ',' and depth == 0:
            args.append(''.join(cur).strip()); cur = []; i += 1; continue
        cur.append(c); i += 1
    raise ValueError('unbalanced')

def norm(code):
    return ' '.join(code.split())

def process(path):
    src = strip_comments(open(path, encoding='utf-8').read())
    rest, tests, i = [], [], 0
    while True:
        a = src.find('TestCase.of', i)
        if a < 0:
            rest.append(src[i:]); break
        rest.append(src[i:a])
        p = src.find('(', a)
        kind = src[a:p].strip()
        args, end = split_args(src, p + 1)
        if kind == 'TestCase.of':
            tests.append((args[0], norm(args[3])))
        elif kind == 'TestCase.ofNull':
            tests.append((args[0], 'NULL INPUT (TestCase.ofNull)'))
        else:
            raise ValueError(kind)
        rest.append('<TEST %s>' % args[0])
        i = end
    print('==== %s ====' % path)
    other = '\n'.join(l for l in ''.join(rest).splitlines() if l.strip())
    print('---- non-test code (comments stripped) ----')
    print(other)
    print('---- tests ----')
    for tid, code in tests:
        print('%s: %s' % (tid, code))
    print()

for p in sys.argv[1:]:
    process(p)
