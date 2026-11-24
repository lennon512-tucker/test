#!/usr/bin/env python3
"""Fletcher-16 arithmetic only: prints s2 s1 (hex) for each hex byte string given."""
import sys
for arg in sys.argv[1:]:
    s1 = s2 = 0
    for t in arg.split():
        s1 = (s1 + int(t, 16)) % 255; s2 = (s2 + s1) % 255
    print('%-45s -> %02X %02X' % (arg, s2, s1))
