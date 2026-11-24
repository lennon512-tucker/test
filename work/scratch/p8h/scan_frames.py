#!/usr/bin/env python3
"""P8 hardening helper: find existing tests whose outcome might change under PROMPT.md v3.

Mechanical scan only. For each input it walks frame starts from offset 4 using only the N field
(next = p + 5 + N), without any of the PROMPT.md checks (no magic, type, size or checksum checks,
no payload checks, no state). It reports:
  (a) every frame start whose type byte is 6 or 7, with the bytes remaining at that point;
  (b) every txn id (payload bytes 0-3 of type 2/3 frames) with more than 8 type-2/3 frames.
It never computes an outcome (PLAN.md G3/G4).
"""
import sys
from collections import Counter

for line in open(sys.argv[1]):
    parts = line.split(" ", 2)
    tid = parts[0]
    if parts[1].strip() == "null":
        continue
    L = int(parts[1])
    b = bytes.fromhex(parts[2].replace(" ", "")) if L else b""
    assert len(b) == L
    p = 4
    hits67 = []
    writes = Counter()
    while p < L:
        left = L - p
        t = b[p]
        n = (b[p + 1] << 8 | b[p + 2]) if left >= 3 else None
        if t in (6, 7):
            hits67.append("type %d @%d (%d bytes left%s)" % (t, p, left, "" if n is None else ", N=%d" % n))
        if n is None or p + 5 + n > L:
            break
        if t in (2, 3) and n >= 4:
            writes[b[p + 3:p + 7].hex()] += 1
        p += 5 + n
    big = ["txn %s: %d PUT/DELETE frames" % (k, v) for k, v in writes.items() if v > 8]
    maxw = max(writes.values()) if writes else 0
    if hits67 or big:
        print(tid, "|", "; ".join(hits67 + big))
    else:
        print(tid, "| none (max PUT/DELETE frames per txn id: %d)" % maxw)
