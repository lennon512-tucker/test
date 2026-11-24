"""P5 arithmetic helper: Fletcher-16 over hex bytes (PROMPT.md 5.4). Arithmetic only, no decoding."""
import sys

def f16(bs):
    s1 = s2 = 0
    for b in bs:
        s1 = (s1 + b) % 255
        s2 = (s2 + s1) % 255
    return s1, s2

def show(label, hexstr, trace=True):
    bs = [int(x, 16) for x in hexstr.split()]
    s1 = s2 = 0
    t1, t2 = [], []
    for b in bs:
        s1 = (s1 + b) % 255; s2 = (s2 + s1) % 255
        t1.append(s1); t2.append(s2)
    print(f"{label}: bytes [{hexstr if trace else str(len(bs)) + ' bytes'}]")
    if trace:
        print("   s1:", t1)
        print("   s2:", t2)
    print(f"   -> stored s2 s1 = {s2:02X} {s1:02X}")

cases = [
 ("BEGIN 1", "01 00 04 00 00 00 01"),
 ("BEGIN 2", "01 00 04 00 00 00 02"),
 ("COMMIT 1", "04 00 04 00 00 00 01"),
 ("COMMIT 2", "04 00 04 00 00 00 02"),
 ("PUT 1 a=1", "02 00 09 00 00 00 01 01 61 00 01 31"),
 ("PUT 2 b=2", "02 00 09 00 00 00 02 01 62 00 01 32"),
 ("PUT 1 k=v", "02 00 09 00 00 00 01 01 6B 00 01 76"),
 ("PUT 2 x=y", "02 00 09 00 00 00 02 01 78 00 01 79"),
 ("type0 txn1", "00 00 04 00 00 00 01"),
 ("type6 txn1", "06 00 04 00 00 00 01"),
 ("type7 txn1", "07 00 04 00 00 00 01"),
 ("payload only of PUT 1 a=1", "00 00 00 01 01 61 00 01 31"),
 ("BEGIN FFFFFFFF", "01 00 04 FF FF FF FF"),
 ("PUT FFFFFFFF k=v", "02 00 09 FF FF FF FF 01 6B 00 01 76"),
 ("ABORT FFFFFFFF", "05 00 04 FF FF FF FF"),
 ("PUT 1 k=ok", "02 00 0A 00 00 00 01 01 6B 00 02 6F 6B"),
 ("DELETE 1 v", "03 00 06 00 00 00 01 01 76"),
 ("PUT 1 n=200", "02 00 0B 00 00 00 01 01 6E 00 03 32 30 30"),
]
for l, h in cases:
    show(l, h)
# long records: s1, s2 only
show("PUT 1 v=x*192 (N=200)", "02 00 C8 00 00 00 01 01 76 00 C0 " + " ".join(["78"]*192), trace=False)
show("PUT 1 k=a*1016 (N=1024)", "02 04 00 00 00 00 01 01 6B 03 F8 " + " ".join(["61"]*1016), trace=False)
show("PUT 1 k=a*1017 (N=1025)", "02 04 01 00 00 00 01 01 6B 03 F9 " + " ".join(["61"]*1017), trace=False)
