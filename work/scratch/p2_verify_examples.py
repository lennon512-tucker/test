#!/usr/bin/env python3
"""P2 audit: independent checksum/offset check of the hex in PROMPT.md section 11.
Encode/checksum arithmetic only (G3): no decoding into a state, no recover()."""
import re, sys

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

def u32(x): return x.to_bytes(4, 'big')
def begin(x): return rec(1, u32(x))
def commit(x): return rec(4, u32(x))
def abort(x): return rec(5, u32(x))
def put(x, k, v): return rec(2, u32(x) + bytes([len(k)]) + k.encode() + len(v).to_bytes(2,'big') + v.encode())
def delete(x, k): return rec(3, u32(x) + bytes([len(k)]) + k.encode())

def hx(b): return ' '.join('%02X' % c for c in b)

# Expected records, written from the "record" column descriptions (not from the hex).
ex = {
 1: [begin(1), begin(2), put(1,'a','1'), put(2,'a','2'), put(2,'b','x'), commit(2),
     begin(3), put(3,'b','y'), commit(1), abort(3), begin(4), delete(4,'a')],
 2: [begin(1), put(1,'x','hello'), commit(1), begin(2), put(2,'x','bye'), commit(2)[:6]],
}
# example 3: PUT 1 k=v with the value byte changed to w but checksum of the original
orig = put(1,'k','v')
dam = bytearray(orig); dam[11] = 0x77
ex[3] = [begin(1), bytes(dam), commit(1)]

text = open(sys.argv[1], encoding='utf-8').read()
blocks = re.findall(r'### Example (\d+).*?```\n(.*?)```', text, re.S)
ok = True
for num, blk in blocks:
    num = int(num)
    lines = [l for l in blk.splitlines() if re.match(r'\s*\d+\s+[0-9A-F]{2}', l)]
    Lline = re.search(r'L = (\d+)', blk).group(1)
    pos = 4
    exp = ex[num]
    assert len(lines) == len(exp) + 1, (num, len(lines))
    hdr = lines[0].split()
    assert hdr[0] == '0' and hdr[1:5] == ['57','41','4C','31'], hdr
    for line, want in zip(lines[1:], exp):
        m = re.match(r'\s*(\d+)\s+((?:[0-9A-F]{2} )*[0-9A-F]{2})', line)
        off, h = int(m.group(1)), m.group(2)
        if off != pos or h != hx(want):
            ok = False
            print('MISMATCH ex%d off %d (want %d): %s vs %s' % (num, off, pos, h, hx(want)))
        pos += len(want)
    if int(Lline) != pos:
        ok = False; print('ex%d L=%s but computed %d' % (num, Lline, pos))
    print('example %d: %d records, L=%d %s' % (num, len(exp), pos, 'ok' if ok else ''))
# extra facts quoted in the prose
print('check value', hx(begin(1)))
print('ex3 true checksum of damaged body', ['%02X' % c for c in fletcher(dam[:-2])], 'stored', hx(dam[-2:]))
print('ex3 original record', hx(orig))
print('ALL OK' if ok else 'FAILURES')
