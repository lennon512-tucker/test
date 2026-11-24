#!/usr/bin/env python3
"""v5 fuzz generator (encode only, never computes an outcome). Like gen.py but focused on
concurrent transactions, READ records (type 9), savepoints/rollbacks and few keys/values, so
READ expectations match often enough to reach COMMIT conflict decisions."""
import random, sys
sys.path.insert(0, ".")
from gen import frame, u32, MAGIC, mutate
IDS = [1, 2, 3, 4, 5, 2**31, 2**32 - 1]
KEYS = [b"a", b"b", b"c"]
VALS = [b"", b"1", b"2"]
NAMES = [0x73, 0x74]

def put(t, k, v): return frame(2, u32(t) + bytes([len(k)]) + k + len(v).to_bytes(2, "big") + v)
def read(t, k, v):  # v None = expect absent
    if v is None: return frame(9, u32(t) + bytes([len(k)]) + k + b"\x00")
    return frame(9, u32(t) + bytes([len(k)]) + k + b"\x01" + len(v).to_bytes(2, "big") + v)

def one(R):
    began, live, out = set(), set(), MAGIC
    for _ in range(R.choice([4, 8, 12, 16, 24, 32, 48])):
        r = R.random()
        if (r < 0.15 and len(live) < 4) or not live:
            cand = [i for i in IDS if i not in began]
            if not cand: break
            t = R.choice(cand); began.add(t); live.add(t); out += frame(1, u32(t)); continue
        t = R.choice(sorted(live)) if R.random() < 0.95 else R.choice(IDS)
        if r < 0.40: out += put(t, R.choice(KEYS), R.choice(VALS))
        elif r < 0.47: out += frame(3, u32(t) + b"\x01" + R.choice(KEYS))
        elif r < 0.70: out += read(t, R.choice(KEYS), R.choice(VALS + [None, None]))
        elif r < 0.80: live.discard(t); out += frame(4, u32(t))
        elif r < 0.83: live.discard(t); out += frame(5, u32(t))
        elif r < 0.89: out += frame(6, u32(t) + bytes([R.choice(NAMES)]))
        elif r < 0.94: out += frame(7, u32(t) + bytes([R.choice(NAMES)]))
        else:
            ids = sorted(live)
            if R.random() < 0.2 and ids: ids = ids[:-1]
            out += frame(8, bytes([len(ids)]) + b"".join(u32(i) for i in ids))
    return mutate(R, out) if R.random() < 0.1 else out

R = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
for _ in range(int(sys.argv[1])):
    print(one(R).hex(" ").upper())
