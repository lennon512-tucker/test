# Audit of PROMPT.md v4 (lever L3, CHECKPOINT) and its tests

Independent auditor session. Report only: PROMPT.md and the test files were not edited.
Scratch files: work/scratch/p8l3a/ (strip_v4.py, stripped.txt, inputs.txt, enc.py, quotes_v4.py,
DumpProposed.java, h102_118.txt). Blind derivations: work/notes/test-audit-v4-blind.md (written
before any test comment or expected line was opened).

G3/G4: no implementation of recover() was written. The only scripts encode records, compute
Fletcher-16, dump the input bytes built by LogBuilder, strip test sources and check quotes. Every
expected outcome below was derived by hand from PROMPT.md v4.

## 1. Spec audit

### 1.1 Method

I read all of PROMPT.md v4 and the v3 to v4 diff (`git diff wal-recovery/task/PROMPT.md`). I checked
every changed or new sentence: §1 line 10, §3 enum, §4 "open transactions at a record", the §5.3
type-8 row and bullets, R7, the §7.3 intro, R13, R14, R19c, R23c, R25, P2, P4, the §9 rows, §10
items 16-17 and Example 5. I also checked them against the older rules that talk about "all
types", "txn", "1 to 7", the record lists, R17/R18/R19 and P5.

### 1.2 Consistency checks (all pass)

- Numbers and limits: c ≤ 255, so N = 1 + 4c ≤ 1021 ≤ MAX_PAYLOAD (1024) and the record size is at
  most 1026. R8 can never reject a well-formed CHECKPOINT. R18 keeps m ≤ 4, so only c = 0..4
  (N = 1, 5, 9, 13, 17) can ever be right. A longer list is just wrong (R19c), with no extra rule.
  The table's size 6 + 4c = 5 + (1 + 4c) is right.
- Id positions: listed id i is at payload byte 1 + 4(i − 1), so the last id (i = c) ends at payload
  byte 4c = N − 1. That matches N = 1 + 4c exactly.
- Type range: R7 says "1, 2, 3, 4, 5, 6, 7 or 8". §9 UNKNOWN_TYPE says "not 1 to 8" and
  RECORD_TOO_LARGE says "1 to 8". The §5.3 bullet says "txn is payload bytes 0 to 3 (types 1 to
  7)". R14 is split into types 1-7 and CHECKPOINT. I found no other stale "1 to 7" or "all types".
- Enum and lists: there are 15 constants. P2 lists 3 phase-1 errors and 11 phase-2 errors, which
  with BAD_MAGIC makes 15. §9 has a row for each of the 15, plus NPE.
- Check order: R19c is last in the §7.3 order and in the P4 order. The P4 sentence for CHECKPOINT
  (R13 < R14 < R19c) matches. R19 leaves out CHECKPOINT in its type list, and R19c says so again.
  R17, R18, R19a and R19b name types that exclude CHECKPOINT. R15, R16 and R16a do not apply.
- Open set: the §4 definition ("open just before that record is processed") matches the §4 open
  interval (BEGIN accepted ... COMMIT/ABORT accepted) and R18's wording. The ids are distinct
  because of R17, so "c = m and listed i = o_i" fully fixes the right list. A repeated id can never
  pass.
- P5 and the torn tail: a CHECKPOINT can be the torn-tail record through R6, R9 or R10, and it is
  then not checked. The torn tail always follows every intact record, so a BEGIN in the torn tail
  can never come before a CHECKPOINT. It can never change any m, and no input has two readings here.
- R25 is updated to say CHECKPOINT adds nothing. R26 and R24 are unaffected (R23c).
- Example 5: I re-encoded it from its story with work/scratch/p8l3a/enc.py (encode only) and it
  matches byte for byte. The offsets are 4, 13, 22, 36, 50, 59 and L = 69. Checksums traced by hand:
  CP [1, 2] has s1 8,8,17,19,19,19,19,20,20,20,20,22 and s2 8,16,33,52,71,90,109,129,149,169,189,211,
  giving `D3 16`. CP [2] has s1 ...,16 and s2 ...,101, giving `65 10`. The field split line is right.
  The stated outcome matches my hand derivation: state {a=1}, committed [1], aborted [], incomplete
  [2], validLength 69, tornTail false. The P05 bytes equal the Example 5 hex.

### 1.3 Findings

| # | Class | Location | Problem | Proposed minimal fix |
|---|---|---|---|---|
| S1 | M | §10 item 4 | "Txn ids in any order. Ids do not need to increase..." sits in the list of cases that "must not throw". Read literally, it also covers the txn ids in a CHECKPOINT list, and that clashes with R19c, which requires ascending order (H104, H106). R19c is explicit, so most readers will resolve it, but the text gives two readings. | "4. BEGIN records with txn ids in any order. Ids do not need to increase or be consecutive; any id from 1 to 4294967295 is valid. (A CHECKPOINT still lists its ids in ascending order, R19c.)" |
| S2 | M (weak) | §10 items 16, 17 | Item 16 says "a CHECKPOINT as the first record" must not throw, with no qualifier. H110 is a CHECKPOINT as the first record that throws CHECKPOINT_MISMATCH. Item 17 has the same gap: "a CHECKPOINT that ... leaves out a transaction whose BEGIN record comes later" throws if it also leaves out an open one. The other §10 items rely on an unstated convention that the rest of the input is valid. | Add one sentence to the §10 intro: "Each item assumes that nothing else in the log breaks a rule." Or end item 16's "Also" sentence with "when each list is right (R19c)". |
| S3 | L | R19c, parenthesis | "(it never had a BEGIN record, or it is already committed or aborted)". "Never had" can be read as "nowhere in the log", which leaves out an id whose BEGIN comes later (H110). The definition and item 17 settle it. | "(no BEGIN record for it has been processed yet, or it is already committed or aborted)" |
| S4 | L | R19a last sentence, §10 item 11 | They name only SAVEPOINT and ROLLBACK as non-writes. A CHECKPOINT is not a write by the §4 definition and R23c. | "SAVEPOINT, ROLLBACK and CHECKPOINT records are not writes and never count." |
| S5 | L | §1 line 10 | "list the transactions that are open" does not say when. | "list the transactions that are open at that point in the log" |
| S6 | L | Example 5 "Why" | "At the CHECKPOINT at 59, only txn 2 is open, and the list is 2." The verdict is only implied. | "... and the list is [2], so R19c passes again." |
| S7 | L | R14 heading | The heading "Txn id (all types)" now also covers the listed ids. | "Txn ids (all types)" (optional) |

No other ambiguity, contradiction or number mismatch found.

### 1.4 Tricky CHECKPOINT inputs walked by hand (each has exactly one outcome)

CK(x, y) is a well-formed CHECKPOINT with a correct checksum. Bn, Cn and An are BEGIN, COMMIT and
ABORT of txn n.

| # | Input after the header | Outcome (rule) |
|---|---|---|
| 1 | CK() only | OK, empty, validLength 10, tornTail false (R13 N = 1, R19c m = 0 = c) |
| 2 | `08` | torn tail at 4 (R6) |
| 3 | `08 00` | torn tail at 4 (R6) |
| 4 | `08 00 05 01 00 00` | torn tail at 4 (R9, 4 + 10 > 10) |
| 5 | CK(1) with a bad checksum, ending at L | torn tail at 4 (R10), empty result |
| 6 | CK(1) with a bad checksum, then 1 byte | CHECKSUM_MISMATCH@4 (R10, ends before L) |
| 7 | type 8, N = 1025 | RECORD_TOO_LARGE (R8 before R9) |
| 8 | type 8, N = 0 | MALFORMED_PAYLOAD (R13 N ≥ 1) |
| 9 | type 8, N = 1, payload `01` | MALFORMED_PAYLOAD (1 ≠ 5) |
| 10 | type 8, N = 5, payload `00 00 00 00 01` | MALFORMED_PAYLOAD (c = 0, 1 ≠ 5; trailing bytes are not ids) |
| 11 | type 8, N = 1024 | MALFORMED_PAYLOAD (1 + 4c ≤ 1021) |
| 12 | B1, CK with c = 255 (ids 1..254, 0) | BAD_TXN_ID (R14 before R19c; N = 1021 passes R8 and R13) |
| 13 | B1, CK with c = 255, all ids 1 | CHECKPOINT_MISMATCH (c ≠ m) |
| 14 | CK(0) with nothing open | BAD_TXN_ID (R14 before R19c) |
| 15 | B1, CK() | CHECKPOINT_MISMATCH (m = 1, c = 0) |
| 16 | B1, B2, C1, B3, CK(1, 3) | CHECKPOINT_MISMATCH (ascending, c = m, but 1 is not open) |
| 17 | B1, B2, B3, B4, CK(1, 2, 3, 4), B5 | TOO_MANY_OPEN at B5 (the CHECKPOINT closes nothing) |
| 18 | B1, CK(1), B1 | DUPLICATE_TXN at the second B1 (a CK is not a BEGIN) |
| 19 | B1, CK(1), A1, CK(1) | CHECKPOINT_MISMATCH at the second CK |
| 20 | CK(5), then `01` (1 byte) | CHECKPOINT_MISMATCH@4 (P5, second sentence) |
| 21 | CK(1), then a torn BEGIN 1 | CHECKPOINT_MISMATCH@4 (the BEGIN in the torn tail is never processed) |
| 22 | CK(5), then a type-9 record | UNKNOWN_TYPE (P2) |
| 23 | B1, PUT 1 with key "", CK(2) | BAD_KEY at the PUT (first failing record, P4) |
| 24 | B 2^31, B1, CK(1, 2^31) | OK, incomplete [1, 2147483648] |
| 25 | B1, CK with c = 2, N = 9, ids 0, 0 | BAD_TXN_ID (R13 passes, R14) |
| 26 | B1, CK with c = 2, N = 5, ids would be 0 | MALFORMED_PAYLOAD (R13 first) |
| 27 | B1, PUT, SAVEPOINT s, CK(1), PUT ×7, CK(1), ROLLBACK s, COMMIT | OK. A CK is not a write (8 writes is fine), the savepoint survives and the ROLLBACK keeps 1 write. |

## 2. Blind re-derivation table

The table and derivations are in work/notes/test-audit-v4-blind.md. The task said 19 tests, but
the scope holds 20: P05, H11, H20 and H102-H118.

| Id | Blind expected line |
|---|---|
| P05 | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[2] validLength=69 tornTail=false` |
| H11 | `ERR UNKNOWN_TYPE@4` |
| H20 | `ERR UNKNOWN_TYPE@13` |
| H102 | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=42 tornTail=false` |
| H103 | `OK state={} committed=[] aborted=[] incomplete=[3, 5] validLength=36 tornTail=false` |
| H104 | `ERR CHECKPOINT_MISMATCH@22` |
| H105 | `OK state={} committed=[7] aborted=[] incomplete=[2147483648, 4294967295] validLength=58 tornTail=false` |
| H106 | `ERR CHECKPOINT_MISMATCH@31` |
| H107 | `ERR CHECKPOINT_MISMATCH@31` |
| H108 | `ERR CHECKPOINT_MISMATCH@22` |
| H109 | `ERR CHECKPOINT_MISMATCH@13` |
| H110 | `ERR CHECKPOINT_MISMATCH@4` |
| H111 | `ERR BAD_TXN_ID@13` |
| H112 | `ERR MALFORMED_PAYLOAD@13` |
| H113 | `ERR MALFORMED_PAYLOAD@13` |
| H114 | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=36 tornTail=true` |
| H115 | `ERR CHECKSUM_MISMATCH@23` |
| H116 | `OK state={"c"="3"} committed=[3, 1] aborted=[2] incomplete=[] validLength=92 tornTail=false` |
| H117 | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=80 tornTail=false` |
| H118 | `ERR RECORD_TOO_LARGE@13` |

## 3. Comparison

- **Expected lines: 20 of 20 agree, 0 disagree.** I compared the strings with a script: blind
  table against the last string literal in each TestCase.of.
- **Derivations:** every step in the 20 comments agrees with my hand derivation. The comments
  give the offsets, sizes, L, the order of checks and the deciding rule.
- **Checksum traces:** I recomputed every s1/s2 trace in the group M preamble and in P05, H11 and
  H20: 21 of 21 are right. They are CP [], [1], [5], [9], [3,5], [5,3], [1,1], [0,1],
  [7,2^31,2^32−1], [2^31,2^32−1,7], type 8 with N = 0, type 8 with N = 5, BEGIN 5/7/9/2^31,
  COMMIT 3/7/9, ABORT 2 and PUT 3 c=3. The DumpAll hex matches.
- **Quotes:** 11 quoted PROMPT.md phrases in the in-scope comments are all verbatim in v4
  (quotes_v4.py, with `...` treated as an elision).
- **Cited rules:** all cited rules exist. Some are cited for the check that loses (the trap), and
  each comment says so: H20 R10, H110 R19, H111 R19c, H113 R14 and H115 R19c.
- **Minor comment points (L, no outcome effect):**
  - H113 step 3 has the stray "N = 1 + 4*2 = 9? 5 != 9". Read it as "1 + 4*2 = 9 != 5".
  - H104's quote "a list in another order ... [is] wrong" puts the editorial "[is]" inside the
    quote marks. The PROMPT text says "... are all wrong".
  - H110 cites §10 item 17 for an error case. Only that item's last sentence ("Only the open
    transactions at the CHECKPOINT record count") supports it; its listed cases are the reverse.
    coverage.md already says so.
- Older tests: no other hidden test uses type 8 as an unknown type, either through record/frame or
  raw bytes. The remaining record(9)/frame(9)/bytes(0x09...) uses (H08, H09, H11, H20) stay
  unknown under v4. The P01-P04 comments that say "type 1-7" describe their own records and are
  still true.

## 4. Coverage gaps (v4 clauses or traps no test in P05/H102-H118 exercises)

1. **Right count and ascending order, but an id that is not open.** This is the largest gap. An
   implementation that checks only `c == m` plus strictly ascending unsigned order passes all of
   H102-H118. Every wrong list in the suite either has c ≠ m (H107, H108, H109, H110) or is out of
   order (H104, H106).
2. **The R13 equality N = 1 + 4c when N is too large** (trailing bytes). H113 has N too small and
   H112 has N = 0. A `N >= 1 + 4c` implementation passes every test.
3. **c = MAX_OPEN_TXNS = 4 with 4 open** (N = 17, the largest right list), and evidence that a
   CHECKPOINT does not change the open count for R18. There is no CK test with 4 open.
4. **c > 4, up to c = 255** (N = 1021, record size 1026, near MAX_PAYLOAD), and R14 scanning all c
   ids. The only id-0 test (H111) has the 0 first and c = 2.
5. **c = 0 while a transaction is open.** H102 has c = 0 with nothing open. This case falls under
   the c ≠ m branch that H107-H110 cover.
6. **A CHECKPOINT as the torn tail through R6 (`08` or `08 00`) or R9 (too short).** Only R10 is
   covered (H114). R6 and R9 do not look at the type, so the risk is low.
7. **A CHECKPOINT that lists an id whose BEGIN is in the torn tail.** By the rules this is just
   gap 1 or c ≠ m. Low value.
8. **A phase-2 CHECKPOINT error before a torn tail** (P5, second sentence). Covered for other
   record types; low value.
9. **CHECKPOINT and DUPLICATE_TXN** (a listed id is not "used" by a BEGIN). Low value.

## 5. Proposed tests (at most 4)

The ids continue after H118. The inputs were built with LogBuilder in DumpProposed.java, and the
checksums were traced by hand. The expected lines were derived by hand.

```java
        // H119 cp_ascending_right_count_but_committed_id_is_mismatch
        // Rules: R19c, R22
        // Derivation:
        //   1. Header ok. BEGIN 1 @4 (1C 06), BEGIN 2 @13 (1D 07), COMMIT 1 @22, ends 31 (31 09),
        //      BEGIN 3 @31, ends 40 (1E 08), CP [1, 3] @40: N = 9, size 14, ends 54
        //      (08 00 09 02 00 00 00 01 00 00 00 03: s1 8,8,17,19,19,19,19,20,20,20,20,23;
        //      s2 8,16,33,52,71,90,109,129,149,169,189,212 -> D4 17). L = 54.
        //   2. Phase 1: all 5 intact; p = 54 = L (R5).
        //   3. Phase 2: 1, 2 open; COMMIT 1 -> open {2}; BEGIN 3 (1 open before) -> open {2, 3}.
        //      CP @40: R13 9 = 1 + 4*2; R14 ids 1, 3 not 0; R19c m = 2 = c, o_1 = 2, listed id 1
        //      is 1 != 2 -> throw CHECKPOINT_MISMATCH at 40. The list is strictly ascending and
        //      has the right count; only txn 1 (already committed) is not open.
        // Expected: ERR CHECKPOINT_MISMATCH@40
        c.add(TestCase.of("H119", "cp_ascending_right_count_but_committed_id_is_mismatch", "R19c,R22",
                LogBuilder.wal().begin(1).begin(2).commit(1).begin(3).checkpoint(1, 3).build(),
                "ERR CHECKPOINT_MISMATCH@40"));

        // H120 cp_payload_longer_than_count_is_malformed
        // Rules: R13
        // Derivation:
        //   1. Header ok. Record @4: type 08, N = 5, payload 00 00 00 00 01 (c = 0, then 4 more
        //      bytes), size 10, ends 14, correct checksum (08 00 05 00 00 00 00 01:
        //      s1 8,8,13,13,13,13,13,14; s2 8,16,29,42,55,68,81,95 -> 5F 0E). BEGIN 1 @14, ends 23
        //      (1C 06). COMMIT 1 @23, ends 32 (31 09). L = 32.
        //   2. Phase 1: all 3 intact (type 8, N = 5, fits, correct checksum); p = 32 = L (R5).
        //   3. Phase 2: record @4: R13 N = 5 >= 1; c = payload byte 0 = 0; N = 1 + 4*0 = 1 != 5
        //      -> throw MALFORMED_PAYLOAD at 4. (Accepting N >= 1 + 4c would treat it as an empty
        //      list with nothing open and return OK.)
        // Expected: ERR MALFORMED_PAYLOAD@4
        c.add(TestCase.of("H120", "cp_payload_longer_than_count_is_malformed", "R13",
                LogBuilder.wal().record(8, Hex.bytes("00 00 00 00 01")).begin(1).commit(1).build(),
                "ERR MALFORMED_PAYLOAD@4"));

        // H121 cp_four_open_full_list_then_fifth_begin_too_many_open
        // Rules: R19c, R23c, R18
        // Derivation:
        //   1. Header ok. BEGIN 9 @4 (24 0E), BEGIN 4294967295 @13 (1B 05), BEGIN 2 @22 (1D 07),
        //      BEGIN 5 @31, ends 40 (20 0A). CP [2, 5, 9, 4294967295] @40: N = 17 (00 11), c = 4,
        //      size 22, ends 62 (08 00 11 04 00 00 00 02 00 00 00 05 00 00 00 09 FF FF FF FF:
        //      s1 8,8,25,29,29,29,29,31,31,31,31,36,36,36,36,45,45,45,45,45;
        //      s2 8,16,41,70,99,128,157,188,219,250,26,62,98,134,170,215,5,50,95,140 -> 8C 2D).
        //      BEGIN 7 @62, ends 71 (22 0C). L = 71.
        //   2. Phase 1: all 6 intact; p = 71 = L (R5).
        //   3. Phase 2: the 4 BEGINs open (0, 1, 2, 3 open before each). CP @40: R13 17 = 1 + 4*4;
        //      R14 none 0; R19c m = 4 = c, ascending u32 order 2, 5, 9, 4294967295 = listed ->
        //      right; R23c nothing changes (still 4 open). BEGIN 7 @62: R17 7 is new; R18 4 open
        //      -> throw TOO_MANY_OPEN at 62.
        // Expected: ERR TOO_MANY_OPEN@62
        c.add(TestCase.of("H121", "cp_four_open_full_list_then_fifth_begin_too_many_open", "R18,R19c,R23c",
                LogBuilder.wal().begin(9).begin(4294967295L).begin(2).begin(5)
                        .checkpoint(2, 5, 9, 4294967295L).begin(7).build(),
                "ERR TOO_MANY_OPEN@62"));

        // H122 prec_cp_255_ids_last_is_zero_bad_txn_id
        // Rules: R8, R13, R14, P4
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). CP @13: c = 255 (FF), ids 1, 2, ..., 254,
        //      then 0; N = 1 + 4*255 = 1021 (03 FD), size 1026, ends 1039, correct checksum
        //      (written by record(); 7A 09). COMMIT 1 @1039, ends 1048 (31 09). L = 1048.
        //   2. Phase 1: p = 13: R7 type 8; R8 N = 1021 <= 1024; R9 1039 <= 1048; R10 correct ->
        //      intact. p = 1039 intact; p = 1048 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. CP @13: R13 N = 1021 >= 1, c = 255, 1 + 4*255 = 1021 ok;
        //      R14 listed id 255 (payload bytes 1017-1020 = 00 00 00 00) is 0 -> throw BAD_TXN_ID
        //      at 13. R19c (c = 255 != m = 1) is never reached (P4: "BAD_TXN_ID (R14) comes before
        //      CHECKPOINT_MISMATCH (R19c)").
        // Expected: ERR BAD_TXN_ID@13
        c.add(TestCase.of("H122", "prec_cp_255_ids_last_is_zero_bad_txn_id", "P4,R8,R13,R14",
                LogBuilder.wal().begin(1)
                        .checkpoint(java.util.stream.LongStream.concat(
                                java.util.stream.LongStream.range(1, 255),
                                java.util.stream.LongStream.of(0)).toArray())
                        .commit(1).build(),
                "ERR BAD_TXN_ID@13"));
```

Note for H122: the checksum 7A 09 comes from enc.py, not from a hand trace of 1024 bytes. The
expected line does not depend on it, because record() always writes the correct checksum. Next
in priority if a slot frees up: gap 5, a CK() while txn 1 is open, giving
`ERR CHECKPOINT_MISMATCH@13` for `wal().begin(1).checkpoint().commit(1)`.
