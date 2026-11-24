#!/usr/bin/env python3
"""Encode-only helper for the v4 audit: builds records from fields and computes Fletcher-16.
Never decodes or interprets a log."""
def f16(bs):
    s1 = s2 = 0
    for b in bs:
        s1 = (s1 + b) % 255; s2 = (s2 + s1) % 255
    return s2, s1
def u32(v): return [(v >> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255]
def rec(t, payload):
    n = len(payload); cov = [t, n >> 8, n & 255] + payload
    s2, s1 = f16(cov); return cov + [s2, s1]
def ckpt(*ids):
    p = [len(ids)]
    for i in ids: p += u32(i)
    return rec(8, p)
def h(bs): return ' '.join('%02X' % b for b in bs)
if __name__ == '__main__':
    # Example 5, re-encoded from its story
    recs = [('BEGIN 2', rec(1, u32(2))), ('BEGIN 1', rec(1, u32(1))),
            ('PUT 1 a=1', rec(2, u32(1) + [1, 0x61, 0, 1, 0x31])),
            ('CKPT 1,2', ckpt(1, 2)), ('COMMIT 1', rec(4, u32(1))), ('CKPT 2', ckpt(2))]
    off = 4
    for name, r in recs:
        print('%5d  %-45s %s' % (off, h(r), name)); off += len(r)
    print('L =', off)
    # misc checksums used by tests
    for name, r in [('ckpt()', ckpt()), ('ckpt(3,5)', ckpt(3,5)), ('ckpt(5,3)', ckpt(5,3)),
                    ('ckpt(7,2^31,max)', ckpt(7, 2147483648, 4294967295)),
                    ('ckpt(2^31,max,7)', ckpt(2147483648, 4294967295, 7)),
                    ('ckpt(1)', ckpt(1)), ('ckpt(1,1)', ckpt(1,1)), ('ckpt(9)', ckpt(9)),
                    ('ckpt(0,1)', ckpt(0,1)), ('rec8 empty', rec(8, [])),
                    ('rec8 02 00 00 00 00', rec(8, [2,0,0,0,0])), ('ckpt(5)', ckpt(5)),
                    ('BEGIN max', rec(1, u32(4294967295))), ('BEGIN 2^31', rec(1, u32(2147483648))),
                    ('BEGIN 7', rec(1, u32(7))), ('COMMIT 7', rec(4, u32(7))),
                    ('ABORT 2', rec(5, u32(2))), ('PUT 3 c=3', rec(2, u32(3)+[1,0x63,0,1,0x33])),
                    ('type9 txn1', rec(9, u32(1)))]:
        print('%-20s %s' % (name, h(r)))
