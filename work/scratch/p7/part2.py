#!/usr/bin/env python3
"""Builds the Part 2 table of test-audit.md: copies the auditor's own outcomes (Parts 1 and 3) next
to the result of the text comparison with each test's expected literal, plus the auditor's notes.
Text handling only."""
import re, sys
sys.path.insert(0, '/root/Documents/test/work/scratch/p7')
from strip_tests import strip_comments, split_args

def expected(path):
    src = strip_comments(open(path, encoding='utf-8').read())
    out, i = {}, 0
    while True:
        a = src.find('TestCase.of', i)
        if a < 0: return out
        p = src.find('(', a); kind = src[a:p].strip(); args, i = split_args(src, p + 1)
        out[eval(args[0])] = (eval(args[2]), eval(args[4] if kind == 'TestCase.of' else args[3]))

base = '/root/Documents/test/wal-recovery/tests/'
exp = {**expected(base + 'public/PublicTests.java'), **expected(base + 'hidden/HiddenTests.java')}
audit = open('/root/Documents/test/work/notes/test-audit.md').read()
mine = {}
for line in audit.splitlines():
    m = re.match(r'\| ([PH]\d\d) \|.*\| `([^`]*)` \|$', line)
    if m: mine[m.group(1)] = m.group(2)

N = {
 'P01': 'Bytes = PROMPT.md Example 1 byte for byte (134, offsets as printed); outcome = its "Expected result". R5/R22/R23/R26 decide. Derivation (examples.md, PROMPT.md "Why", now also in PublicTests.java) agrees.',
 'P02': 'Bytes = Example 2 (71); outcome = its Expected result. R9 + R12 decide the tail, R22/R26 the rest. Derivation agrees.',
 'P03': 'Bytes = Example 3 (36); outcome = its Expected outcome. R10 decides. Checksum A4 F0 vs stored A3 EF re-done by hand.',
 'H01': 'Derivation ok. R2 decides.',
 'H02': 'Derivation ok. R4 then R5 decide; R27 reports them.',
 'H03': 'Derivation ok. R3 (torn branch).',
 'H04': 'Derivation ok. R3 (torn branch).',
 'H05': 'Derivation ok. R3 (BAD_MAGIC branch); P1.',
 'H06': 'Derivation ok. R4; P1 (records never read).',
 'H07': 'Offsets 4/13/22, COMMIT trace 31 09 ok. R6 + R12 decide the tail; R22/R25 the lists.',
 'H08': 'Derivation ok. R6 before R7 ("whatever those bytes are"); R26.',
 'H09': 'Derivation ok. R6 does not apply (3 left); R7 decides. Also R7 before R9 (13+5+0 = 18 > 16).',
 'H10': 'Type-0 trace 15 05 ok (dump). R7 decides.',
 'H11': 'Type-6 trace 3F 0B ok (dump). R7 decides.',
 'H12': 'Offsets, L = 59 - 5 = 54, R9 sum 59 > 54 ok. R9 + R12 decide.',
 'H13': 'PUT trace A3 EF and corrupted COMMIT 30 08 ok (dump). R10 torn branch (ends at L) + R12; R25, P5.',
 'H14': 'Derivation ok (36 < 37). R10 mismatch branch ("even when only 1 or 2 bytes follow").',
 'H15': 'PUT x=y trace E0 01 -> stored E1 00 ok (dump). R10; P3.',
 'H16': 'N bytes 04 01 = 1025 ok. R8 decides.',
 'H17': 'N = 1024, v bytes 03 F8 ok; PUT checksum 13 E9 recomputed (s1 by hand, s2 by arithmetic) and in the dump; second PUT trace FE 56 ok. R8 (not >), R13, R21/R22 decide.',
 'H18': 'N = 0x8001 = 32769, L = 32796 <= 65536 (section 2). R8 decides (unsigned).',
 'H19': 'N = 07 D0 = 2000. R8 before R9 decides (section 7.2 says so in words).',
 'H20': 'Correct checksum 46 0C vs stored 00 00 ok. R7 before R10 decides.',
 'H21': 'Payload-only trace C3 95 ok; correct 36 A0 (= Example 1 @22). R10 (checksum covers type and N, 5.4).',
 'H22': 'FF traces 1B 05, 9D EE, 37 09 ok (dump). R10/R11 (bytes unsigned, section 4, 5.4), R23, R25 and 7.4 (non-negative long).',
 'H23': 'N 00 C8 = 200, v 00 C0 = 192; s1 5E by hand, 64 5E in dump; DELETE C0 81, PUT 79 13 traces ok. R8/R10/R13 (unsigned), R22.',
 'H24': 'Stored 06 1C vs correct 1C 06 ok. R10 (stored order s2, s1).',
 'H25': 'Trace 14 06 ok. R11 (phase 1 ignores layout), R13 decides.',
 'H26': 'Trace 28 07 ok. R13 decides (N = 5 != 4).',
 'H27': 'Trace C3 37 ok. R13 second PUT condition (N >= 7 + k) decides.',
 'H28': 'Trace 16 6C ok. R13 N >= 7 + k boundary (7 < 8).',
 'H29': 'Trace 14 D3 ok. R13 third PUT condition (N = 7 + k + v, one byte too many).',
 'H30': 'Trace 2A 08 ok. R13 DELETE N >= 5.',
 'H31': 'Trace 0C 04 ok. R13 COMMIT N = 4.',
 'H32': 'Traces 1B 05, 30 08 ok. R14 decides.',
 'H33': 'Trace 96 3D ok. R13 passes with k = 0 (v read at payload byte 5); R15 k < 1 decides.',
 'H34': 'Key 32 bytes 21..7E; s1 0E by hand, 78 0E in dump. R15 passes at both ends ("Every range includes both ends"); R22.',
 'H35': 'Key 33 bytes; s1 10 by hand, 8F 10 in dump. R15 k > 32 decides.',
 'H36': 'Trace 63 27 ok. R15 byte 20 decides.',
 'H37': 'Trace A2 ED ok. R15 byte 7F (DELETE) decides.',
 'H38': 'Trace AA 25 ok. R16 passes at 20 and 7E; R22.',
 'H39': 'Trace CD 07 ok. R16 byte 1F decides.',
 'H40': 'Trace F7 6A ok. R16 byte 7F decides.',
 'H41': 'Traces ok. R21/R22 (commit order), R25 (committed in log order of COMMITs).',
 'H42': 'Traces ok. R22, R23, R25; Not errors 3.',
 'H43': 'Traces 1B 05, 20 0A, 1D 85, 19 84 ok. R18 (0-3 open) and R26 (ascending u32) decide.',
 'H44': 'Derivation ok. R17 (open id).',
 'H45': 'Derivation ok. R17 ("committed").',
 'H46': 'Derivation ok. R19 (already committed).',
 'H47': 'Derivation ok. R18 (4 open).',
 'H48': 'Derivation ok. R18 second sentence ("does not count"); R26.',
 'H49': 'Derivation ok. R23 frees one slot, R18 decides at @58.',
 'H50': 'Traces 92 B7, C5 84, C6 85 ok. R22 (DELETE of a missing key does nothing; commit order).',
 'H51': 'Traces 3B A2, AB 6C, 8E 6E ok. R13 (v = 0), R21/R22 write-list order.',
 'H52': 'Trace DE 37 ok. R14 (max id valid), R22, R25, R26.',
 'H53': 'Stored A2 EE vs A3 EF ok. P2 quote exact; R10 decides, R14 never reached.',
 'H54': 'Offsets 13 (N = 0, ends 18), 18 (N = 1025) ok. P2 quote exact; R8 decides, R13 never reached.',
 'H55': 'Trace 5B 26 ok. P4 quote exact; R14 before R15/R19.',
 'H56': 'Trace 5E 2A ok. P4 quote exact; R15 before R19.',
 'H57': 'Offsets ok. P4 quote exact; R17 (aborted id) before R18.',
 'H58': 'L = 54 - 1 = 53 ok. R9/R12; R25 and P5 quotes exact.',
 'H59': 'Trace 39 0B ok. R6 torn at 31; P5 second sentence; R19 decides.',
 'H60': 'Correct 2B F5, stored 2A F4 ok (dump). R10 torn branch; P5 quote exact.',
 'H61': 'Trace D8 04 ok. P4 first sentence; R19 at @13 decides before R16 at @22.',
 'H62': 'All 11 traces ok; tail 10 bytes as stated. R9/R12, R21-R26 decide; section 2 (input not modified) cited in the comment; harness adds +INPUT_MODIFIED.',
 'H63': 'R1 decides; ofNull; rendered as the class name.',
 'H64': 'Added by P7 (Part 3). R3 decides.',
 'H65': 'Added by P7 (Part 3). R13 DELETE N = 5 + k decides.',
 'H66': 'Added by P7 (Part 3). R8 before R10 (section 7.2 sentence) decides.',
 'H67': 'Added by P7 (Part 3). R13 PUT N = 7 + k + v (one byte short) decides.',
 'H68': 'Added by P7 (Part 3). P4; R14 before R18 decides.',
 'H69': 'Added by P7 (Part 3). P4; R16 before R19 decides.',
}
rows = ['| Test | Cited rules | Own derived outcome | Test expected line | Derivation and rule check | Status |',
        '|---|---|---|---|---|---|']
for tid, (rules, e) in exp.items():
    m = mine.get(tid)
    same = 'equal to own outcome' if m == e else 'DIFFERENT: `%s`' % e
    rows.append('| %s | %s | `%s` | %s | %s | %s |' % (tid, rules, m, same, N[tid], 'verified' if m == e else 'CHECK'))
print('\n'.join(rows))
print('\nrows: %d, verified: %d' % (len(rows) - 2, sum(1 for r in rows if r.endswith('| verified |'))), file=sys.stderr)
