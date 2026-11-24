#!/usr/bin/env python3
"""P8 L3 helper: mechanical scan for frame starts with type 8 (CHECKPOINT in v4) or 9.
Walks frame starts from offset 4 using only N (next = p + 5 + N). No PROMPT.md checks, no outcome."""
import sys
for line in open(sys.argv[1]):
    parts = line.split(" ", 2)
    tid = parts[0]
    if parts[1].strip() == "null":
        continue
    L = int(parts[1]); b = bytes.fromhex(parts[2].replace(" ", "")) if L else b""
    p, hits = 4, []
    while p < L:
        left = L - p
        n = (b[p+1] << 8 | b[p+2]) if left >= 3 else None
        if b[p] in (8, 9):
            hits.append("type %d @%d (%d left%s)" % (b[p], p, left, "" if n is None else ", N=%d" % n))
        if n is None or p + 5 + n > L:
            break
        p += 5 + n
    if hits:
        print(tid, "|", "; ".join(hits))
