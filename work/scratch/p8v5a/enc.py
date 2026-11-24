# Encoding helper only (frames + Fletcher-16). Not a recovery implementation.
import sys
def fl(bs):
    s1=s2=0
    for b in bs:
        s1=(s1+b)%255; s2=(s2+s1)%255
    return bytes([s2,s1])
def rec(t,payload):
    h=bytes([t,len(payload)>>8,len(payload)&255])+payload
    return h+fl(h)
u32=lambda x:x.to_bytes(4,'big')
def key(k): return bytes([len(k)])+k.encode()
recs=[("BEGIN txn 1",rec(1,u32(1))),("BEGIN txn 2",rec(1,u32(2))),
("READ txn 1, \"a\", f = 0",rec(9,u32(1)+key("a")+b"\x00")),
("PUT txn 2, \"a\" = \"1\"",rec(2,u32(2)+key("a")+b"\x00\x01"+b"1")),
("COMMIT txn 2",rec(4,u32(2))),
("PUT txn 1, \"b\" = \"2\"",rec(2,u32(1)+key("b")+b"\x00\x01"+b"2")),
("COMMIT txn 1",rec(4,u32(1))),
("BEGIN txn 3",rec(1,u32(3))),
("RANGE txn 3, lo \"a\", hi \"b\", count 1",rec(10,u32(3)+key("a")+key("b")+u32(1))),
("COMMIT txn 3",rec(4,u32(3)))]
off=4
print("     0  57 41 4C 31  header")
for n,r in recs:
    print(f"{off:6d}  {' '.join('%02X'%b for b in r)}  {n}"); off+=len(r)
print("L =",off)
