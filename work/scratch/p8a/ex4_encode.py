# P8 audit (v3): encode-only rebuild of PROMPT.md Example 4 from its description column.
# Computes Fletcher-16 per record and prints offsets, sizes and hex. No decoding, no recovery.
import sys

def fletcher(bs):
    s1 = s2 = 0
    for b in bs:
        s1 = (s1 + b) % 255
        s2 = (s2 + s1) % 255
    return s2, s1

def rec(t, payload):
    body = bytes([t, len(payload) >> 8, len(payload) & 0xFF]) + payload
    s2, s1 = fletcher(body)
    return body + bytes([s2, s1])

def u32(x): return x.to_bytes(4, "big")
def put(txn, k, v): return rec(2, u32(txn) + bytes([len(k)]) + k + len(v).to_bytes(2, "big") + v)
def dele(txn, k): return rec(3, u32(txn) + bytes([len(k)]) + k)
def begin(txn): return rec(1, u32(txn))
def commit(txn): return rec(4, u32(txn))
def sp(txn, n): return rec(6, u32(txn) + bytes([n]))
def rb(txn, n): return rec(7, u32(txn) + bytes([n]))

recs = [begin(1), put(1, b"a", b"1"), sp(1, 0x73), put(1, b"b", b"2"), dele(1, b"a"),
        rb(1, 0x73), put(1, b"c", b"3"), commit(1)]
p = 4
out = ["57 41 4C 31"]
for r in recs:
    print(f"{p:6d}  size {len(r):2d}  {' '.join(f'{b:02X}' for b in r)}")
    p += len(r)
print("L =", p)
