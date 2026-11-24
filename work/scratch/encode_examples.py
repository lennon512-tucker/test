#!/usr/bin/env python3
"""Throwaway encoder for the PROMPT.md worked examples (P1).

Encoding and checksums only. It never decodes or recovers a log (PLAN.md G3).
It prints each record's offset, size and hex, so the bytes can be pasted into
PROMPT.md. Expected outcomes are derived by hand in work/notes/examples.md.
"""

MAGIC = bytes([0x57, 0x41, 0x4C, 0x31])


def fletcher16(data):
    s1 = 0
    s2 = 0
    for b in data:
        s1 = (s1 + b) % 255
        s2 = (s2 + s1) % 255
    return s1, s2


def frame(rtype, payload):
    body = bytes([rtype]) + len(payload).to_bytes(2, "big") + payload
    s1, s2 = fletcher16(body)
    return body + bytes([s2, s1])


def u32(x):
    return x.to_bytes(4, "big")


def begin(t):
    return frame(1, u32(t))


def commit(t):
    return frame(4, u32(t))


def abort(t):
    return frame(5, u32(t))


def put(t, key, value):
    k = key.encode("ascii")
    v = value.encode("ascii")
    return frame(2, u32(t) + bytes([len(k)]) + k + len(v).to_bytes(2, "big") + v)


def delete(t, key):
    k = key.encode("ascii")
    return frame(3, u32(t) + bytes([len(k)]) + k)


def hx(bs):
    return " ".join(f"{b:02X}" for b in bs)


def show(title, parts):
    """parts: list of (label, bytes). Prints offset, size, hex, label."""
    print(f"== {title}")
    off = 0
    for label, bs in parts:
        print(f"{off:>4}  size {len(bs):>2}  {hx(bs):<56}  {label}")
        off += len(bs)
    print(f"L = {off}")
    print()


# Check value from PLAN.md 4.3
assert begin(1) == bytes.fromhex("01 00 04 00 00 00 01 1C 06".replace(" ", ""))

# Example 1: interleaved transactions, commit order != log order, abort, incomplete.
ex1 = [
    ("header", MAGIC),
    ("BEGIN txn 1", begin(1)),
    ("BEGIN txn 2", begin(2)),
    ('PUT txn 1 "a"="1"', put(1, "a", "1")),
    ('PUT txn 2 "a"="2"', put(2, "a", "2")),
    ('PUT txn 2 "b"="x"', put(2, "b", "x")),
    ("COMMIT txn 2", commit(2)),
    ("BEGIN txn 3", begin(3)),
    ('PUT txn 3 "b"="y"', put(3, "b", "y")),
    ("COMMIT txn 1", commit(1)),
    ("ABORT txn 3", abort(3)),
    ("BEGIN txn 4", begin(4)),
    ('DELETE txn 4 "a"', delete(4, "a")),
]
show("Example 1", ex1)

# Example 2: torn tail, the last record (COMMIT txn 2) is cut short after 6 bytes.
ex2 = [
    ("header", MAGIC),
    ("BEGIN txn 1", begin(1)),
    ('PUT txn 1 "x"="hello"', put(1, "x", "hello")),
    ("COMMIT txn 1", commit(1)),
    ("BEGIN txn 2", begin(2)),
    ('PUT txn 2 "x"="bye"', put(2, "x", "bye")),
    ("COMMIT txn 2 (first 6 of 9 bytes)", commit(2)[:6]),
]
show("Example 2", ex2)

# Example 3: PUT in the middle has one damaged value byte (0x76 'v' -> 0x77 'w');
# the stored checksum is the one computed for the undamaged record.
good_put = put(1, "k", "v")
damaged = bytearray(good_put)
value_index = 3 + 4 + 1 + 1 + 2  # type, N(2), txn(4), keyLen(1), key(1), valueLen(2)
assert damaged[value_index] == 0x76
damaged[value_index] = 0x77
ex3 = [
    ("header", MAGIC),
    ("BEGIN txn 1", begin(1)),
    ('PUT txn 1 "k"="v", value byte damaged to 77', bytes(damaged)),
    ("COMMIT txn 1", commit(1)),
]
show("Example 3", ex3)
print("Example 3 PUT: stored checksum", hx(good_put[-2:]),
      "; checksum of damaged bytes (s2 s1):",
      "%02X %02X" % tuple(reversed(fletcher16(bytes(damaged[:-2])))))
