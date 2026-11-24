"""P6 arithmetic helper: Fletcher-16 traces over hex bytes (PROMPT.md 5.4).
Arithmetic/encoding only: no decoding, no recover() logic, no expected results."""

def show(label, hexstr, trace=True):
    bs = [int(x, 16) for x in hexstr.split()]
    s1 = s2 = 0
    t1, t2 = [], []
    for b in bs:
        s1 = (s1 + b) % 255; s2 = (s2 + s1) % 255
        t1.append(s1); t2.append(s2)
    if trace:
        print(f"{label}: {hexstr}")
        print(f"   s1 {','.join(map(str,t1))}")
        print(f"   s2 {','.join(map(str,t2))} -> {s2:02X} {s1:02X}")
    else:
        print(f"{label}: {len(bs)} bytes, byte sum {sum(bs)} -> {s2:02X} {s1:02X}")

def h(s):  # latin1 text -> hex
    return " ".join(f"{ord(c):02X}" for c in s)

def u32(v):
    return " ".join(f"{(v >> s) & 0xFF:02X}" for s in (24, 16, 8, 0))

def rec(t, payload_hex):
    n = len(payload_hex.split()) if payload_hex else 0
    return f"{t:02X} {n >> 8:02X} {n & 0xFF:02X}" + (" " + payload_hex if payload_hex else "")

def put(txn, k, v):
    return rec(2, f"{u32(txn)} {len(k):02X}" + (" " + h(k) if k else "") + f" {len(v) >> 8:02X} {len(v) & 0xFF:02X}" + (" " + h(v) if v else ""))

def dele(txn, k):
    return rec(3, f"{u32(txn)} {len(k):02X} {h(k)}")

for name, t in (("BEGIN", 1), ("COMMIT", 4), ("ABORT", 5)):
    for txn in (0, 1, 2, 3, 4, 5, 6, 7, 200, 2147483647, 2147483648, 4294967295):
        show(f"{name} {txn}", rec(t, u32(txn)))

show("rec1 N3", rec(1, "00 00 02"))
show("rec1 N5", rec(1, "00 00 00 01 00"))
show("rec2 keypast", rec(2, "00 00 00 01 05 61 62 63"))
show("rec2 N7", rec(2, "00 00 00 01 01 61 00"))
show("rec2 extra", rec(2, "00 00 00 01 01 61 00 01 31 32"))
show("rec3 N4", rec(3, "00 00 00 01"))
show("rec4 N0", rec(4, ""))

K32 = "!abcdefghijklmnopqrstuvwxyz0123~"
K33 = "abcdefghijklmnopqrstuvwxyz0123456"
assert len(K32) == 32 and len(K33) == 33
puts = [
 (1, "", "1"), (1, "a b", "1"), (1, "a", "1"), (1, "v", " ~"), (1, "v", "a\x1f"), (1, "b", "x\x7f"),
 (3, "k", "b"), (7, "k", "a"), (3, "m", "1"), (2, "a", "2"), (2, "b", "2"), (1, "x", "1"), (1, "b", "2"),
 (1, "b", ""), (4294967295, "max", "u32"), (1, "k", "v"), (0, "a b", "1"), (2, "\xe9", "1"),
 (1, "v", "\x7f"), (4294967295, "k", "v x"), (2, "a", ""), (3, "z", "~"), (200, "q", "1"), (0, "a b", "\x01"),
]
for txn, k, v in puts:
    show(f"PUT {txn} {k!r}={v!r}", put(txn, k, v))
for txn, k in ((1, "a\x7f"), (2, "x"), (2, "y"), (1, "a"), (2, "k")):
    show(f"DELETE {txn} {k!r}", dele(txn, k))
show("PUT 1 K32='1'", put(1, K32, "1"), trace=False)
show("PUT 1 K33='1'", put(1, K33, "1"), trace=False)
