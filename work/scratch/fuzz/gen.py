#!/usr/bin/env python3
"""Fuzz input generator (encode only). Prints one hex log per line. It never computes an outcome.
It tracks which ids were begun/finished only to bias choices toward plausible records."""
import random, sys
MAGIC = bytes([0x57, 0x41, 0x4C, 0x31])

def f16(d):
    s1 = s2 = 0
    for b in d:
        s1 = (s1 + b) % 255; s2 = (s2 + s1) % 255
    return bytes([s2, s1])

def frame(t, p, n=None):
    n = len(p) if n is None else n
    body = bytes([t]) + n.to_bytes(2, "big") + p
    return body + f16(body)

u32 = lambda x: (x & 0xFFFFFFFF).to_bytes(4, "big")
IDS = [1, 2, 3, 5, 7, 255, 256, 65536, 2**31 - 1, 2**31, 2**31 + 1, 2**32 - 1]
KEYS = [b"a", b"b", b"c", b"k", b"!", b"~", b"ab"]
NAMES = [0x73, 0x74, 0x21, 0x7E, 0x61]

def rec(R, st):
    began, live = st["began"], st["live"]
    r = R.random()
    pick_live = lambda: R.choice(sorted(live)) if live and R.random() < 0.9 else R.choice(IDS)
    if r < 0.14 or not live:
        cand = [i for i in IDS if i not in began]
        t = R.choice(cand) if cand and R.random() < 0.93 else R.choice(IDS)
        began.add(t); live.add(t)
        return frame(1, u32(t))
    if r < 0.42:
        t = pick_live(); k = R.choice(KEYS)
        v = bytes(R.choice(b"0123 xyz~") for _ in range(R.choice([0, 1, 1, 2, 3])))
        return frame(2, u32(t) + bytes([len(k)]) + k + len(v).to_bytes(2, "big") + v)
    if r < 0.52:
        t = pick_live(); k = R.choice(KEYS)
        return frame(3, u32(t) + bytes([len(k)]) + k)
    if r < 0.60:
        t = pick_live(); live.discard(t); return frame(4, u32(t))
    if r < 0.64:
        t = pick_live(); live.discard(t); return frame(5, u32(t))
    if r < 0.74:
        return frame(6, u32(pick_live()) + bytes([R.choice(NAMES)]))
    if r < 0.84:
        return frame(7, u32(pick_live()) + bytes([R.choice(NAMES)]))
    # checkpoint: usually the sorted live set, sometimes perturbed
    ids = sorted(live)
    x = R.random()
    if x < 0.15 and ids: ids = ids[::-1]
    elif x < 0.25 and ids: ids = ids[:-1]
    elif x < 0.32: ids = sorted(ids + [R.choice(IDS)])
    elif x < 0.38 and len(ids) > 1: R.shuffle(ids)
    elif x < 0.42: ids = sorted(ids, key=lambda v: v - 2**32 if v >= 2**31 else v)  # signed order
    elif x < 0.44: ids = ids + [0]
    return frame(8, bytes([len(ids)]) + b"".join(u32(i) for i in ids))

def mutate(R, b):
    b = bytearray(b)
    m = R.random()
    if m < 0.25 and len(b) > 4:
        b = b[:R.randrange(4, len(b))]                       # truncate
    elif m < 0.35 and len(b) > 6:
        i = R.randrange(4, len(b)); b[i] ^= 1 << R.randrange(8)  # bit flip
    elif m < 0.40:
        b += bytes(R.randrange(256) for _ in range(R.choice([1, 2, 3])))
    return bytes(b)

def one(R):
    st = {"began": set(), "live": set()}
    out = MAGIC
    for _ in range(R.choice([1, 2, 4, 6, 8, 12, 16, 24, 32])):
        r = rec(R, st)
        if R.random() < 0.03:  # bad frame details inside
            r = bytearray(r); r[-1] ^= 1; r = bytes(r)
        out += r
    return mutate(R, out) if R.random() < 0.3 else out

if __name__ == "__main__":
    R = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
    for _ in range(int(sys.argv[1])):
        print(one(R).hex(" ").upper())
