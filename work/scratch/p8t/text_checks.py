"""Text checks only (compares hand-written strings; computes nothing):
1. each '// Expected:' comment equals the expected literal of its TestCase;
2. each double-quoted phrase of 25+ chars in a comment occurs in PROMPT.md (whitespace-normalised)."""
import re
src = open('wal-recovery/tests/hidden/HiddenTests.java').read()
prompt = re.sub(r'\s+', ' ', open('wal-recovery/task/PROMPT.md').read().replace('`', ''))
blocks = re.split(r'\n        // (?=H\d\d )', src)
bad = 0; n = 0
for b in blocks[1:]:
    tid = b[:3]
    m = re.search(r'// Expected: (.*)\n', b)
    lit = re.search(r'TestCase\.of(?:Null)?\("' + tid + r'".*?,\s*"((?:[^"\\]|\\.)*)"\)\);', b, re.S)
    if not m or not lit: print("parse?", tid); bad += 1; continue
    n += 1
    if m.group(1) != lit.group(1).replace('\\"', '"'): print("MISMATCH", tid); bad += 1
print("expected-comment checks:", n, "mismatches:", bad)
# quotes
comments = "\n".join(l.split('//', 1)[1] for l in src.splitlines() if l.strip().startswith('//'))
comments = re.sub(r'\n\s*', ' ', comments)
qbad = 0; qn = 0
for q in re.findall(r'"([^"]{25,})"', comments):
    qn += 1
    parts = [p.strip() for p in q.split('...')]
    if not all(re.sub(r'\s+', ' ', p) in prompt for p in parts if p):
        print("QUOTE NOT IN PROMPT:", q); qbad += 1
print("quotes checked:", qn, "not found:", qbad)
