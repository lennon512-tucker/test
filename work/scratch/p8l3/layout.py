#!/usr/bin/env python3
"""P8 L3 helper (encode only, PLAN G3/G4): for each planned group-M input, print every record's
offset, size, end, hex and Fletcher-16 trace. Never decodes a log and never computes an outcome."""
import sys
sys.path.insert(0, "/root/Documents/test/work/scratch")
import io, contextlib
with contextlib.redirect_stdout(io.StringIO()):
    import encode_examples as e

def trace(body):
    s1 = s2 = 0; a = []; b = []
    for x in body:
        s1 = (s1 + x) % 255; s2 = (s2 + s1) % 255; a.append(s1); b.append(s2)
    return a, b

def bad(rec):  # corruptLastChecksum: XOR both checksum bytes with 01
    return rec[:-2] + bytes([rec[-2] ^ 1, rec[-1] ^ 1])

B, C, A, P, S, R, K = e.begin, e.commit, e.abort, e.put, e.savepoint, e.rollback, e.checkpoint
def raw8(payload):  # type 8 with the true N and correct checksum, payload as given
    return e.frame(8, payload)

T = {
 "H102": [K(), B(1), P(1, "a", "1"), C(1)],
 "H103": [B(5), B(3), K(3, 5)],
 "H104": [B(5), B(3), K(5, 3)],
 "H105": [B(4294967295), B(2147483648), B(7), K(7, 2147483648, 4294967295), C(7)],
 "H106": [B(4294967295), B(2147483648), B(7), K(2147483648, 4294967295, 7), C(7)],
 "H107": [B(1), B(2), C(1), K(1, 2)],
 "H108": [B(1), B(2), K(1)],
 "H109": [B(1), K(1, 1)],
 "H110": [K(9), B(9), C(9)],
 "H111": [B(1), K(0, 1)],
 "H112": [B(1), raw8(b""), C(1)],
 "H113": [B(1), raw8(bytes.fromhex("0200000000")), C(1)],
 "H114": [B(1), P(1, "a", "1"), C(1), bad(K(5))],
 "H115": [K(9), B(1), bad(P(1, "a", "1")), C(1)],
 "H116": [B(1), B(2), A(2), K(1), B(3), P(3, "c", "3"), C(3), K(1), C(1)],
 "H117": [B(1), P(1, "a", "1"), S(1, 0x73), P(1, "b", "2"), K(1), R(1, 0x73), C(1)],
 "H118": [B(1), bytes.fromhex("08 04 01 00 00".replace(" ", ""))],
}
only = set(sys.argv[1:])
for tid, recs in T.items():
    if only and tid not in only: continue
    print("==", tid)
    off = 4
    for r in recs:
        n = (r[1] << 8) | r[2] if len(r) >= 3 else None
        line = "  @%-4d size %-3d end %-4d %s" % (off, len(r), off + len(r), " ".join("%02X" % x for x in r))
        print(line)
        if r[0] == 8 or r[0] not in (1, 4, 5) and len(r) == 5 + (n or 0):
            a, b = trace(r[:-2])
            print("        s1 %s\n        s2 %s -> correct %02X %02X" % (",".join(map(str, a)), ",".join(map(str, b)), b[-1], a[-1]))
        off += len(r)
    print("  L =", off)
