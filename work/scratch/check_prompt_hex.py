#!/usr/bin/env python3
"""Compare the example hex in PROMPT.md with the encoder's bytes (P1 self-check).

Text comparison only: it parses the offset/hex lines of each example block in
PROMPT.md and checks them against encode_examples.py. No decoding of records.
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import encode_examples as enc  # noqa: E402  (prints the encoder tables on import)

prompt = pathlib.Path(__file__).resolve().parents[2] / "wal-recovery/task/PROMPT.md"
text = prompt.read_text(encoding="utf-8")

blocks = re.findall(r"^### Example (\d)[^\n]*\n(.*?)(?=^### Example|\Z)", text, re.S | re.M)
expected = {"1": enc.ex1, "2": enc.ex2, "3": enc.ex3}
ok = True
for num, body in blocks:
    code = re.search(r"```\n(.*?)```", body, re.S).group(1)
    rows = []
    L = None
    for line in code.splitlines():
        m = re.match(r"^\s*(\d+)\s+((?:[0-9A-F]{2} )*[0-9A-F]{2})(?:\s{2,}|\s+\S)", line)
        if m:
            rows.append((int(m.group(1)), bytes.fromhex(m.group(2).replace(" ", ""))))
        m2 = re.match(r"^L = (\d+)$", line)
        if m2:
            L = int(m2.group(1))
    exp = expected[num]
    off = 0
    for (got_off, got_bytes), (label, exp_bytes) in zip(rows, exp):
        if got_off != off or got_bytes != exp_bytes:
            ok = False
            print(f"Example {num}: MISMATCH at {label}: text {got_off} {got_bytes.hex()} vs {off} {exp_bytes.hex()}")
        off += len(exp_bytes)
    if len(rows) != len(exp) or L != off:
        ok = False
        print(f"Example {num}: rows {len(rows)} vs {len(exp)}, L text {L} vs {off}")
    print(f"Example {num}: {len(rows)} rows, L = {L}, {'OK' if ok else 'FAIL'}")
print("ALL_HEX_MATCH" if ok else "HEX_MISMATCH")
