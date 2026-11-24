#!/usr/bin/env python3
"""Encode-only stress inputs (binary files). Never computes an outcome."""
import sys
sys.path.insert(0, ".")
from gen import frame, u32, MAGIC
def put(t, k, v): return frame(2, u32(t) + bytes([len(k)]) + k + len(v).to_bytes(2, "big") + v)
def read0(t, k): return frame(9, u32(t) + bytes([len(k)]) + k + b"\x00")
def A(n):
    out = bytearray(MAGIC)
    for t in range(1, n + 1):
        out += frame(1, u32(t)) + read0(t, b"z") + frame(4, u32(t))
    return out
def B(keys, n):
    out = bytearray(MAGIC); t = 0
    for i in range(0, keys, 8):
        t += 1; out += frame(1, u32(t))
        for j in range(i, min(i + 8, keys)): out += put(t, b"k%06d" % j, b"v")
        out += frame(4, u32(t))
    for _ in range(n):
        t += 1; out += frame(1, u32(t)) + read0(t, b"z") + frame(4, u32(t))
    return out
if __name__ == "__main__" and len(sys.argv) == 4:
    open("stressA.bin", "wb").write(A(int(sys.argv[1])))
    open("stressB.bin", "wb").write(B(int(sys.argv[2]), int(sys.argv[3])))

def C(n, m):
    """Long-lived reader: BEGIN 1; n txns each overwrite "a" and commit; then txn 1 READs "a" m times."""
    out = bytearray(MAGIC) + frame(1, u32(1))
    for t in range(2, n + 2):
        out += frame(1, u32(t)) + put(t, b"a", b"x") + frame(4, u32(t))
    r = read0(1, b"a")
    for _ in range(m): out += r
    return out
def D(n, m):
    """Like C, but the reader begins in the middle, so binary search over versions matters both ways."""
    out = bytearray(MAGIC); t = 1
    for _ in range(n // 2):
        t += 1; out += frame(1, u32(t)) + put(t, b"a", b"x") + frame(4, u32(t))
    out += frame(1, u32(1))
    for _ in range(n // 2):
        t += 1; out += frame(1, u32(t)) + put(t, b"a", b"y") + frame(4, u32(t))
    r = frame(9, u32(1) + b"\x01a\x01\x00\x01x")
    for _ in range(m): out += r
    return out
if __name__ == "__main__" and len(sys.argv) == 6:
    open("stressC.bin", "wb").write(C(int(sys.argv[4]), int(sys.argv[5])))
    open("stressD.bin", "wb").write(D(int(sys.argv[4]), int(sys.argv[5])))

def rng(t, lo, hi, cnt): return frame(10, u32(t) + bytes([len(lo)]) + lo + bytes([len(hi)]) + hi + u32(cnt))
def base(out, keys, t0):
    t = t0
    for i in range(0, keys, 8):
        t += 1; out += frame(1, u32(t))
        for j in range(i, min(i + 8, keys)): out += put(t, b"k%06d" % j, b"v")
        out += frame(4, u32(t))
    return t
def E(keys, m):
    out = bytearray(MAGIC); t = base(out, keys, 1)
    out += frame(1, u32(1)); r = rng(1, b"k000000", b"k999999", keys)
    for _ in range(m): out += r
    return out
def F(keys, m):
    out = bytearray(MAGIC); t = base(out, keys, 1)
    out += frame(1, u32(1)); r = rng(1, b"k000000", b"k999999", keys)
    for i in range(m):
        t += 1; out += frame(1, u32(t)) + put(t, b"k%06d" % (keys + i), b"w") + frame(4, u32(t)) + r
    return out
def G(r, c):
    out = bytearray(MAGIC) + frame(1, u32(1)); rr = rng(1, b"a", b"a", 0)
    for _ in range(r): out += rr
    for t in range(2, c + 2): out += frame(1, u32(t)) + put(t, b"b%06d" % t, b"x") + frame(4, u32(t))
    out += frame(4, u32(1))
    return out
if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "range":
    open("stressE.bin", "wb").write(E(100000, 300000))
    open("stressF.bin", "wb").write(F(50000, 150000))
    open("stressG.bin", "wb").write(G(200000, 250000))

def Hmem(keys, n, reads, readers=1):
    """Within the stated shapes: `readers` long-lived txns begin early; then n single-write commits rewrite keys
    of a `keys`-key state (round robin); then reader 1 counts the whole range `reads` times."""
    out = bytearray(MAGIC); t = 100
    t = base(out, keys, t)              # build the state (8 writes per txn)
    for r in range(1, readers + 1): out += frame(1, u32(r))
    for i in range(n):
        t += 1; out += frame(1, u32(t)) + put(t, b"k%06d" % (i % keys), b"w") + frame(4, u32(t))
    rr = rng(1, b"k000000", b"k999999", keys)
    for _ in range(reads): out += rr
    return out
if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "mem":
    a = Hmem(200000, 300000, 1000, 1); open("stressH1.bin", "wb").write(a)
    b = Hmem(200000, 280000, 1000, 3); open("stressH2.bin", "wb").write(b)
    print(len(a), len(b))
