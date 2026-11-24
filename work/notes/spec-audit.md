# PROMPT.md audit (P2, 2026-11-24)

Reviewed: `wal-recovery/task/PROMPT.md` DRAFT v1 (344 lines). Result: DRAFT v2 (audited), 346 lines.
Rule numbers did not change: R1-R27, P1-P5. No example bytes or expected outcomes changed.

Severity: **M** = a careful reader could reach a different outcome, or a test could check something not stated;
**L** = wording that is imprecise or makes a trap less plain, but the rules still give one answer;
**I** = looked at, not a problem (reason given).

## Findings

| # | Sev | Location | Problem | Fix | Status |
|---|---|---|---|---|---|
| A1 | M | §9 error table, "When" column | Rows read as stand-alone conditions. Some inputs match two rows (e.g. a PUT with txn 0 and a wrong N matches both MALFORMED_PAYLOAD and BAD_TXN_ID; a 5th BEGIN that reuses an id matches DUPLICATE_TXN and TOO_MANY_OPEN). A strict reader could say the table and the check order disagree. | Added a paragraph: the table is a summary; a row applies only when no earlier check has decided; with the PUT txn 0 example. | fixed |
| A2 | L | §9 MALFORMED_PAYLOAD row | "the payload layout does not match the record type" is vague (could be read as "the key bytes are wrong"). | Now "N does not match the payload layout of the record type". | fixed |
| A3 | L | §8 P2 | "Phase 1 runs over the whole log before phase 2 starts" is literally false when phase 1 throws or finds a torn tail, and could suggest phase 1 scans past a torn tail. Contradicts P3/R12 in wording. | Now "Phase 1 finishes before phase 2 starts." | fixed |
| A4 | L | R25 | "the txn of each COMMIT record" could include a COMMIT in the torn tail. P5 already excludes it, but only indirectly. | Now "each COMMIT (ABORT) record processed by phase 2", plus a sentence that a COMMIT or ABORT in the torn tail is not listed. | fixed |
| A5 | L | §10 item 6 | Says "after the last intact record". A log of the header plus 1 or 2 bytes has no intact record, so the item did not literally cover it (R6 still does). | Now "after the header or after the last intact record", and gives validLength/tornTail. | fixed |
| A6 | L | R12 | "nothing in them is checked again" - "again" is confusing (R7/R8 did read part of the tail). | Now "phase 2 does not process or check them (P5)". | fixed |
| A7 | L | §11 intro | "Each line is one record" - the header line and the "L = ..." line are not records. | Says which line is the header and that the last line gives L. | fixed |
| T3 | L | §7.2 sentence after the check order | Trap T3 (R7/R8 beat R9 and R10, even on the last record) was stated only for a record that does not fit; the "unknown type + wrong checksum, ending at L" case followed only from the order. | The sentence now says R7 and R8 apply to every record with at least 3 bytes left, including one that does not fit (R9) and a last record with a wrong checksum (R10). | fixed |
| T5 | L | R10 | Trap T5 (bad checksum, then 1 extra byte -> CHECKSUM_MISMATCH) followed from R10, but a reader might think the 1-2 trailing bytes make it "the tail". | Added "This holds even when only 1 or 2 bytes follow the record." | fixed |
| T7 | L | §7.4 | Trap T7 (ids >= 2^31) relied on the general "unsigned" line in §4 and on MAX_TXN_ID. | Added: each txn id in the lists is the u32 value as a non-negative long; `FF FF FF FF` gives 4294967295, not -1. | fixed |
| T8 | L | R8 | Trap T8 (N >= 32768) relied on the general "unsigned" line in §4. | Added: N is unsigned; `80 00` is N = 32768 > MAX_PAYLOAD. | fixed |
| T10 | L | §8 P4 | DUPLICATE_TXN before TOO_MANY_OPEN followed from the R17, R18 order only. | P4 now says so in words. | fixed |
| T13 | L | R18 | "a COMMIT or ABORT frees a slot" followed from the §4 definition of "open" only. | R18 now says "before this BEGIN record is applied" and that a committed or aborted transaction does not count. | fixed |
| I1 | I | R27 | Cites only R5/R12, not R2/R3. | Not a problem: §7.4 covers the case where phase 2 ran; R2/R3 return the empty result themselves with explicit validLength/tornTail. | explained |
| I2 | I | R17 "an earlier BEGIN record in the log" | Could "earlier BEGIN" include a BEGIN that failed a check? | Not a problem: any failing check throws, so every earlier BEGIN that phase 2 processed was accepted; records in the torn tail are later, not earlier. | explained |
| I3 | I | R18 "MAX_OPEN_TXNS ... are open" | Exactly 4, not "4 or more". | Not a problem: R18 keeps the count at 4 or below, so more than 4 can never be open. | explained |
| I4 | I | §7.2 p > L | No rule for p > L. | Not a problem: p only moves on at R11, after R9 confirmed p + 5 + N <= L, so p <= L always. | explained |
| I5 | I | §3 "You may add ... private ..." | Does not say whether non-private helpers are banned. | Not a problem for fairness: no test depends on it; the fixed names are listed exactly. | explained |
| I6 | I | Determinism (§2, R24, R25, R26) | Map order, list order. | Not a problem: map order is explicitly not checked (R24, set comparison); committed/aborted are log order, incomplete is ascending; no locale-dependent operation exists (Strings are one char per byte). | explained |
| I7 | I | Fletcher-16 `00` vs `FF` | A 00<->FF change is not detected. | Not a text problem: the text defines the checksum exactly, so an undetected change is just an intact record. Test writers must avoid it (PLAN §4.3). | explained |
| I8 | I | §4 hex convention | Any two-digit code-font number that is meant as decimal? | Checked every code-font two-digit token: all are byte values (hex). Offsets and lengths are plain text. | explained |

## Walkthrough of tricky inputs (rules applied by hand)

M = magic `57 41 4C 31`. "rec(...)" = a record with a correct checksum unless said otherwise.
Every input below got exactly one answer from the rules.

| # | Input | Rules | Outcome |
|---|---|---|---|
| 1 | null | R1 | NullPointerException |
| 2 | empty (L = 0) | R2 | empty result, 0, false |
| 3 | `57` | R3 | empty, 0, true |
| 4 | `00` | R3 | BAD_MAGIC@0 |
| 5 | `57 41 4C` | R3 | empty, 0, true |
| 6 | `57 41 58` | R3 | BAD_MAGIC@0 |
| 7 | `57 41 4C 32` (L = 4) | R4 | BAD_MAGIC@0 |
| 8 | M only | R5 | empty, 4, false (§10.5) |
| 9 | M + `09` | R6 | empty, 4, true |
| 10 | M + `09 00` | R6 | empty, 4, true (type not checked) |
| 11 | M + `09 00 00` | R7 | UNKNOWN_TYPE@4 |
| 12 | M + `01 00 04` | R9 | empty, 4, true |
| 13 | M + `01 04 01` (N = 1025, 3 bytes) | R8 before R9 | RECORD_TOO_LARGE@4 |
| 14 | M + `01 80 00` (N = 32768) | R8 (unsigned) | RECORD_TOO_LARGE@4 |
| 15 | M + BEGIN with N = 0 and correct checksum | R11, then R13 | MALFORMED_PAYLOAD@4 |
| 16 | M + BEGIN with N = 0, wrong checksum, ends at L | R10 | empty, 4, true |
| 17 | M + BEGIN 1 cut at each length 1..8 | R6 (1, 2), R9 (3..8) | empty, 4, true |
| 18 | M + BEGIN 1 with wrong checksum, ends at L | R10 | empty, 4, true |
| 19 | same as 18 + 1 extra byte | R10 | CHECKSUM_MISMATCH@4 |
| 20 | M + BEGIN 1 (good) + 2 extra bytes | R6 at 13 | incomplete [1], 13, true |
| 21 | M + type 6, wrong checksum, ends at L | R7 before R10 | UNKNOWN_TYPE@4 |
| 22 | PUT with N = 1024 (k = 1, v = 1016, value bytes ok), in a committed txn | R8 ok, R13 ok | valid; state has the 1016-char value |
| 23 | BEGIN txn 0 | R14 | BAD_TXN_ID@p |
| 24 | COMMIT txn 0 with no BEGIN | R14 before R19 | BAD_TXN_ID@p |
| 25 | BEGIN/COMMIT txn `FF FF FF FF` | R25 | committed [4294967295] |
| 26 | BEGIN 1..4 (all open), BEGIN 5 | R18 | TOO_MANY_OPEN at the 5th BEGIN |
| 27 | BEGIN 1..4, COMMIT 2, BEGIN 5 | R18 (3 open) | valid; incomplete [1, 3, 4, 5] |
| 28 | BEGIN 1..4, BEGIN 3 | R17 before R18 | DUPLICATE_TXN |
| 29 | BEGIN 1, COMMIT 1, BEGIN 1 | R17 | DUPLICATE_TXN at the 2nd BEGIN |
| 30 | PUT txn 0, k = 0 | R14 before R15 | BAD_TXN_ID |
| 31 | PUT txn 0, N one byte too long | R13 before R14 | MALFORMED_PAYLOAD (A1 example) |
| 32 | PUT k = 33, layout consistent | R15 | BAD_KEY |
| 33 | PUT k = 200, N = 9 | R13 (N < 7 + k) | MALFORMED_PAYLOAD |
| 34 | PUT bad key byte `20` and bad value byte `0A` | R15 before R16 | BAD_KEY |
| 35 | PUT with bad value byte, txn not open | R16 before R19 | BAD_VALUE |
| 36 | DELETE with N = 4 | R13 (N >= 5 fails) | MALFORMED_PAYLOAD |
| 37 | DELETE k = 0, N = 5 | R13 ok, R15 | BAD_KEY |
| 38 | Key byte `80` | R15 (unsigned 128 > 0x7E) | BAD_KEY |
| 39 | BEGIN with N = 4 but txn 0 + wrong checksum mid-log | R10 (phase 1) | CHECKSUM_MISMATCH |
| 40 | MALFORMED record at 4, CHECKSUM_MISMATCH record later | P2 | CHECKSUM_MISMATCH at the later p |
| 41 | MALFORMED record at 4, RECORD_TOO_LARGE later | P2 | RECORD_TOO_LARGE |
| 42 | Phase-2 error at 4, torn tail at the end | P5 | the phase-2 error |
| 43 | Only bad content is inside the torn record | P5 | normal result, tornTail true |
| 44 | COMMIT 1 in the torn tail | R25, P5 | 1 in incomplete, not committed |
| 45 | CHECKSUM_MISMATCH at 13 and UNKNOWN_TYPE at 27 | P3 | CHECKSUM_MISMATCH@13 |
| 46 | PUT a, DELETE a in one txn, COMMIT | R22 | a absent |
| 47 | DELETE of missing key, COMMIT | R22, §10.1 | valid, state unchanged |
| 48 | COMMIT 1 twice | R19 | TXN_NOT_OPEN at the 2nd |
| 49 | ABORT of never-begun id | R19 | TXN_NOT_OPEN |
| 50 | Incomplete {3000000000, 7} | R26 | [7, 3000000000] |

## Traps (PLAN §4.6): where each is stated in the text

| Trap | Stated in |
|---|---|
| T1 two-phase precedence | P2 (with both code lists) |
| T2 MALFORMED is phase 2 | §7.2 intro ("does not check the payload contents"), R13 in §7.3, P2 list |
| T3 R7/R8 beat R9/R10 | sentence after the §7.2 check order (A-fix T3), table rows for UNKNOWN_TYPE/RECORD_TOO_LARGE |
| T4 2 vs 3 leftover bytes | R6, §7.2 sentence ("at least 3 bytes left"), error table |
| T5 ends at L vs one more byte | R10 (with the new last sentence) |
| T6 checksum covers type and N; unsigned | §5.4 first sentence, §4 unsigned bytes, check-value table |
| T7 ids >= 2^31 | §4, §6 MAX_TXN_ID, §7.4 intro (new) |
| T8 N unsigned | §4, R8 (new example) |
| T9 commit order | §1, R22, example 1 |
| T10 DUPLICATE covers finished ids; before TOO_MANY_OPEN | R17, P4 (new clause) |
| T11 field checks before state checks | §7.3 intro, P4 |
| T12 key/value edges | §6 table, "Every range includes both ends", R15, R16 |
| T13 4-open limit; slot freed | R18 (new sentence), §4 definition of open |
| T14 partial magic | R3 |
| T15 empty vs header-only | R2, R5, §10.5 |
| T16 input not modified; null | §2, R1 |
| T17 incomplete ascending unsigned | R26, §7.4 intro |

## Examples (item 6)

- Re-derived all three outcomes by hand before opening `work/notes/examples.md`: same results.
- Own script `work/scratch/p2_verify_examples.py` (encode and checksum only) rebuilds every record from its
  description column and compares hex, offsets and L: all match. Check value `01 00 04 00 00 00 01 1C 06` matches.
  Example 3: checksum of the damaged bytes is s2 = A4, s1 = F0; stored A3 EF (the undamaged checksum).
- Re-run after the edits: all match; `check_prompt_hex.py` also prints ALL_HEX_MATCH.
- Expected lines are unchanged.

## Skeleton (item 5)

`work/scratch/skeleton/WalRecovery.java` compiles with `javac --release 21` and is byte-identical to the
```java block in PROMPT.md §3. All names used in the text (recover, Result and its six components, Code and its
11 constants, WalException, code(), offset()) match the code.
