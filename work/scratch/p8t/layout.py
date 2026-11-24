#!/usr/bin/env python3
"""P8 hardening test-writing helper: prints the frame layout of selected test inputs so the
offsets in the hand derivations can be compared. Walks frame starts from offset 4 using only N
(next = p + 5 + N); prints offset, type, N, end and the two stored checksum bytes, plus the
payload hex. No PROMPT.md checks, no state, no outcome (PLAN.md G3/G4)."""
import sys
want = set(sys.argv[2:])
for line in open(sys.argv[1]):
    parts = line.split(" ", 2)
    if parts[0] not in want or parts[1].strip() == "null":
        continue
    L = int(parts[1]); b = bytes.fromhex(parts[2].replace(" ", ""))
    out = []
    p = 4
    while p < L:
        if L - p < 3:
            out.append(f"@{p}: {L-p} leftover"); break
        t = b[p]; n = b[p+1] << 8 | b[p+2]; e = p + 5 + n
        if e > L:
            out.append(f"@{p} t{t} N{n} needs {e} > L"); break
        out.append(f"@{p} t{t} N{n} e{e} pl[{b[p+3:p+3+n].hex(' ')}] ck {b[e-2]:02X} {b[e-1]:02X}")
        p = e
    print(parts[0], "L=%d" % L)
    for o in out: print("   ", o)
