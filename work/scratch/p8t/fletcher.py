"""P8 hardening test-writing helper: Fletcher-16 traces over hex bytes (PROMPT.md 5.4).
Arithmetic/encoding only: no decoding, no recover() logic, no expected results."""

def show(label, hexstr):
    bs = [int(x, 16) for x in hexstr.split()]
    s1 = s2 = 0
    t1, t2 = [], []
    for b in bs:
        s1 = (s1 + b) % 255; s2 = (s2 + s1) % 255
        t1.append(s1); t2.append(s2)
    print(f"{label:14s} {hexstr}")
    print(f"{'':14s}   s1 {','.join(map(str,t1))}")
    print(f"{'':14s}   s2 {','.join(map(str,t2))} -> {s2:02X} {s1:02X}")

def h(s): return " ".join(f"{ord(c):02X}" for c in s)
def u32(v): return " ".join(f"{(v >> s) & 0xFF:02X}" for s in (24, 16, 8, 0))
def rec(t, payload_hex):
    n = len(payload_hex.split()) if payload_hex else 0
    return f"{t:02X} {n >> 8:02X} {n & 0xFF:02X}" + (" " + payload_hex if payload_hex else "")
def put(txn, k, v):
    return rec(2, f"{u32(txn)} {len(k):02X} {h(k)} {len(v) >> 8:02X} {len(v) & 0xFF:02X}" + (" " + h(v) if v else ""))
def dele(txn, k): return rec(3, f"{u32(txn)} {len(k):02X} {h(k)}")
def sp(txn, name): return rec(6, f"{u32(txn)} {name:02X}")
def rb(txn, name): return rec(7, f"{u32(txn)} {name:02X}")

show("ABORT 1", rec(5, u32(1)))
show("type8 N=4", rec(8, u32(1)))
for k, v in [("a","1"),("a","2"),("a","3"),("a","4"),("a","5"),("a","6"),("a","7"),("a","8"),("a","9"),
             ("b","2"),("c","3"),("d","4"),("e","5"),("f","6"),("g","7"),("h","8"),("i","9"),("j","0"),
             ("k","1"),("b","5"),("c","4"),("i","\x7f")]:
    show(f"PUT 1 {k}={v!r}", put(1, k, v))
for k, v in [("z","0"),("a","1"),("c","3"),("b","2"),("b","1")]:
    show(f"PUT 2 {k}={v}", put(2, k, v))
for k in "abdfh":
    show(f"DEL 1 {k}", dele(1, k))
for n in (0x73, 0x74, 0x78, 0x79, 0x7A, 0x21, 0x7E, 0x20):
    show(f"SP 1 {n:02X}", sp(1, n))
show("SP 2 73", sp(2, 0x73)); show("SP 2 7F", sp(2, 0x7F))
for n in (0x73, 0x74, 0x78, 0x79, 0x7E, 0x7F, 0x71):
    show(f"RB 1 {n:02X}", rb(1, n))
show("RB 2 73", rb(2, 0x73)); show("RB 0 20", rb(0, 0x20))
show("SP N=4", rec(6, "00 00 00 01"))
show("SP N=6", rec(6, "00 00 00 01 20 73"))
show("RB N=6", rec(7, "00 00 00 01 73 73"))
show("RB N=4", rec(7, "00 00 00 01"))
show("RB 1 7A", rb(1, 0x7A))
