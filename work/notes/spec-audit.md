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

# v3 audit (P8 hardening, 2026-11-24)

Reviewed: `wal-recovery/task/PROMPT.md` DRAFT v3 (401 lines): everything L1/L2 touched (§1, §3, §4, §5.3, §6, R7,
R13, R16a, R19, R19a, R19b, R20, R22, R23, R23a, R23b, P2, P4, §9, §10 items 10-15, Example 4) and how the new rules
interact with the old ones. Design notes read: `work/notes/spec-change-v3.md`. Result: DRAFT v3 (audited), 401 lines.
Wording fixes only: no rule numbers, no check order, no example bytes and no expected line changed. No rule now gives
a different outcome for any input than the DRAFT v3 text gave when read as its design notes intend (D1-D18).

Severity as above (M, L, I).

## Findings

| # | Sev | Location | Problem | Fix | Status |
|---|---|---|---|---|---|
| V1 | M | §10 item 13 | "two or more ROLLBACK records that name the same savepoint" is listed as not an error. Read literally, it says RB x, RB y (earlier, removes x), RB x is valid, but R19b throws NO_SAVEPOINT at the 3rd record (trap T21). The "not errors" list and R19b disagree. | Item 13 now says a ROLLBACK may name the same savepoint as an earlier ROLLBACK "only while the savepoint still exists; if a ROLLBACK in between removed it, R19b applies". | fixed |
| V2 | L | R19b, 2nd sentence | "This covers ... a savepoint that an earlier ROLLBACK removed" can be read as "once removed, the name is dead", even after a later SAVEPOINT created it again (R23a creates a new one; R19b's 1st sentence then finds it). | Now "... removed (R23b) and that no SAVEPOINT record has created again since then". | fixed |
| V3 | L | R23b (trap T22) | Equal positions: whether a savepoint with the same position as s counts as "later" followed only from the word "offset". T22 (equal positions; "later" by setting record, not position) must be plain. | Added: "Only these offsets decide, not the positions: another savepoint with the same position as s is removed when its setting record is at a larger offset, and it stays when its setting record is at a smaller offset." | fixed |
| V4 | L | §4 definition; R23a | §4 said a savepoint has a name and a position, but R23b also needs the record that set it, defined only inside R23a by two overlapping sentences ("from now on ... counts as the record that set it" and "A savepoint is set by ..."). | §4: a savepoint has a name, a position and the SAVEPOINT record that set it (R23a). R23a: "In both cases, this SAVEPOINT record is now the record that set the savepoint; R23b compares the offsets of these records. So a savepoint is set by the SAVEPOINT record that created it or that moved it most recently." | fixed |
| V5 | L | R19a, 4th sentence | "so a ROLLBACK frees room for new writes" is too general: a ROLLBACK that removes no writes frees nothing. | Now "so a ROLLBACK that removes writes frees room for new writes". | fixed |
| V6 | L | R25 (trap T26) | That SAVEPOINT/ROLLBACK records never appear in `committed`/`aborted` followed only from "the txn of each COMMIT record". | Added: "SAVEPOINT and ROLLBACK records add nothing to these lists." | fixed |
| V7 | L | P4 (trap T24) | BAD_NAME before TXN_NOT_OPEN followed from the general "field checks before transaction checks" sentence only; P4 spells out the other new pairs. | Added: "For a SAVEPOINT or ROLLBACK record, BAD_NAME (R16a) comes before TXN_NOT_OPEN (R19)." | fixed |
| V8 | L | §10 item 14 | "The same savepoint name in two different transactions" does not say what is independent (a reader could ask whether a SAVEPOINT in txn 2 moves txn 1's savepoint of the same name). | Now: a SAVEPOINT or ROLLBACK of one transaction never creates, moves, removes or uses a savepoint of another transaction (R19b, R23a, R23b). | fixed |
| V9 | L | §10 item 10 | "holds fewer than MAX_WRITES writes at that point": "that point" has no clear referent in a long sentence. | Now "just before this record"; sentence shortened. | fixed |
| V10 | L | R23b, last sentences | "a later ROLLBACK may name it again": "may" reads as permission, not fact. | Now "can name it again". | fixed |
| W1 | I | R23b "(The write list always holds at least n writes at this point.)" | Is this claim true for every input? If not, the action is undefined. | True. Invariant, by induction over the records of one transaction: (a) every savepoint's position is at most the list length; (b) ordered by the offset of the setting record, positions do not decrease. R23a sets position = current length at the largest offset so far, so (a) and (b) hold (a move takes the savepoint out of its old place). R21 only grows the list. R23b to s sets the length to n and removes every savepoint set later; the rest were set earlier, so by (b) their positions are at most n. | explained |
| W2 | I | R23b "these are the writes added after s was set" | Explanatory clause; could it be false after moves and other rollbacks? | True by W1: since s was last set, the list never dropped below n (a ROLLBACK to an earlier-set savepoint would have removed s), so the first n writes are the ones present when s was set. | explained |
| W3 | I | R19a "holds MAX_WRITES writes" | Exactly 8, not "8 or more"? | Not a problem: R19a keeps the list at 8 or fewer, so it can never hold more (same as I3 for R18). | explained |
| W4 | I | No savepoint-count limit | Gap? | Not a problem: no rule needs one; at most 94 names (0x21-0x7E) exist per transaction. | explained |
| W5 | I | Code enum, §9, P2 | Names in the text vs the code. | 14 constants; the 3 new ones are last (TXN_TOO_LARGE, BAD_NAME, NO_SAVEPOINT; old ordinals 0-10 unchanged). P2 lists 3 phase-1 + 10 phase-2 codes = all but BAD_MAGIC (header). §9 has one row per code. Skeleton `work/scratch/skeleton/WalRecovery.java` = PROMPT.md §3 block = stub, compiles with javac --release 21. | explained |
| W6 | I | Example 4 shows only the simple case | Are the traps hidden in an example? | No: Example 4 shows one savepoint, one rollback, a commit. Every trap T18-T26 is in the rules (table below), not only in an example. | explained |
| W7 | I | H11, H20 | v3 itself (not this audit) changes their outcome (types 6/7 now valid). | Already listed in spec-change-v3.md §4 and in STATE.md open issues. This audit changes no outcome, so "Affected by v3 audit" lists none. tests/hidden/ not edited. | explained |
| W8 | I | Word scan | "should", "usually", "etc.", "normally", "typically" | None in PROMPT.md. "may" remains only in §3 ("You may add ... private ...", a permission). | explained |

## Walkthrough of tricky inputs (v3 rules applied by hand)

M = header. B t / C t / A t = BEGIN / COMMIT / ABORT; P t k = PUT; D t k = DELETE; SP t x / RB t x = SAVEPOINT /
ROLLBACK with name byte x. All records intact (correct checksum) unless said otherwise. Positions in (). Every input
got exactly one outcome.

| # | Input (after M) | Rules | Outcome |
|---|---|---|---|
| 1 | B1, SP1 x (0), P1 a, P1 b, RB1 x, C1 | R23a, R23b n = 0 | list emptied; state {}, committed [1] |
| 2 | B1, SP1 x (0), SP1 y (0), P1 a, RB1 x, RB1 y | R23b (y set at larger offset), R19b | NO_SAVEPOINT at RB1 y |
| 3 | B1, SP1 x (0), SP1 y (0), P1 a, RB1 y, RB1 x, C1 | R23b (x set at smaller offset stays) | valid; state {} |
| 4 | B1, SP1 x (0), P1 a, SP1 x (moved, 1), P1 b, RB1 x, C1 | R23a move, R23b n = 1 | state {a} |
| 5 | B1, SP1 x (0), P1 a, RB1 x, P1 b, RB1 x, C1 | R23b (x stays), §10.13 | valid; state {} |
| 6 | B1, SP1 x (0), P1 a, SP1 y (1), P1 b, RB1 x, RB1 y | R23b removes y, R19b | NO_SAVEPOINT at RB1 y |
| 7 | B1, SP1 x@A (0), P1 a, SP1 y@B (1), P1 b, SP1 x@C (2), RB1 y, RB1 x | R23a (x now set @C > B), R23b, R19b | NO_SAVEPOINT at RB1 x |
| 8 | as 7, but RB1 x, then RB1 y, C1 | R23b (y set @B < C stays) | valid; list [a]; state {a} |
| 9 | B1, SP1 x (0), P1 a, SP1 y (1), P1 b, RB1 x, SP1 y (0), RB1 y, C1 | R23b removes y, R23a creates y again, R19b (V2) | valid; state {} |
| 10 | B1, 2 writes, SP1 x (2), 6 writes (8), RB1 x, 6 writes (8), C1 | R19a counts the list, §10.10 | valid |
| 11 | as 10 plus one more PUT before C1 | R19a | TXN_TOO_LARGE at that PUT |
| 12 | B1, 8 PUTs, D1 k (k not in state) | R19a (every DELETE counts) | TXN_TOO_LARGE at the DELETE |
| 13 | B1, 8 PUTs, P1 with value byte 0A | R16 before R19a | BAD_VALUE |
| 14 | B1, 8 PUTs, P1 with k = 0 | R15 before R19a | BAD_KEY |
| 15 | B1, 8 PUTs, SP1 x (8), RB1 x, P1 a | R19a skips SP/RB, §10.11, R23b keeps 8 | TXN_TOO_LARGE at the last PUT |
| 16 | B1, 8 PUTs, C1 | R19a, §10.10 | valid |
| 17 | B1, B2, 8 writes each, C1, C2 | R19a per transaction | valid |
| 18 | SP1 x with no BEGIN 1 | R19 | TXN_NOT_OPEN |
| 19 | B1, C1, SP1 x / B1, A1, RB1 x | R19 | TXN_NOT_OPEN |
| 20 | B1, B2, SP1 x, RB2 x | R19b per transaction | NO_SAVEPOINT at RB2 x |
| 21 | B1, B2, SP1 x (0), SP2 x (0), P1 a, P2 b, RB1 x, C1, C2 | R23a/R23b per transaction, §10.14 | state {b} |
| 22 | SP txn 0, name 20, N = 5 | R14 before R16a | BAD_TXN_ID |
| 23 | SP with N = 4; SP with N = 6; RB with N = 4 | R13 | MALFORMED_PAYLOAD |
| 24 | RB1 name 20, txn 1 not open | R16a before R19 (P4, V7) | BAD_NAME |
| 25 | SP1 name 7F; name 80 (unsigned 128); names 21 and 7E | R16a, §4 unsigned, §6 ranges include ends | BAD_NAME; BAD_NAME; valid |
| 26 | B1, 8 PUTs, C1, P1 a | R19 before R19a | TXN_NOT_OPEN |
| 27 | RB1 y, txn 1 never begun, no savepoint y | R19 before R19b | TXN_NOT_OPEN |
| 28 | B1, type 6 record last with wrong checksum, ends at L | R10 | torn at its p; incomplete [1] |
| 29 | type 7 record with wrong checksum, 1 byte after it | R10 | CHECKSUM_MISMATCH |
| 30 | type 8 with 3+ bytes left; type 8 with 2 bytes left | R7; R6 | UNKNOWN_TYPE; torn |
| 31 | type 6, N = 4, correct checksum, first record | R7 ok, R11, R13 | MALFORMED_PAYLOAD@4 (cf. H11, W7) |
| 32 | SP with N = 1025 | R8 | RECORD_TOO_LARGE |
| 33 | B1, SP1 x, C1, B1 | R17 | DUPLICATE_TXN at the 2nd B1 |
| 34 | B1, SP1 x, P1 a, then RB1 x cut short (torn) | P5, R12 | RB ignored; incomplete [1] |
| 35 | B1, SP1 x, P1 a, RB1 x, A1 | R23 | aborted [1], state {} |
| 36 | B1, SP1 x, P1 a, RB1 x (no COMMIT) | R23b stays open, R26 | incomplete [1]; committed [], aborted [] (R25, V6) |
| 37 | MALFORMED SP at 4, CHECKSUM_MISMATCH later | P2 | CHECKSUM_MISMATCH |
| 38 | RB1 y (NO_SAVEPOINT) early, 5th BEGIN later | P4 first failing record | NO_SAVEPOINT |
| 39 | B1, SP1 x, RB1 x | R23b removes nothing, §10.13 | valid; incomplete [1] |
| 40 | B1, SP1 y (0), P1 a, SP1 x (1), RB1 y, SP1 x (0), RB1 x, C1 | R23b removes x, R23a creates x again | valid; state {} |

## Traps T18-T26 (spec-change-v3.md §5): where each is stated

| Trap | Stated in |
|---|---|
| T18 current list counts; ROLLBACK frees room; DELETE counts; SP/RB at 8 ok | R19a (all four points), §10 items 10, 11, MAX_WRITES row in §6 |
| T19 BAD_KEY/BAD_VALUE before TXN_TOO_LARGE; TXN_NOT_OPEN before TXN_TOO_LARGE | §7.3 intro (field vs transaction checks), P4 (both sentences) |
| T20 reused name moves; moving removes nothing else | R23a, §10 item 12 |
| T21 later savepoints removed; NO_SAVEPOINT after; named one stays | R23b, R19b 2nd sentence (V2), §10 item 13 (V1) |
| T22 equal positions; "later" by setting record; moved savepoint is later | §4 definition (V4), R23a (V4), R23b new sentence (V3) |
| T23 per-transaction savepoints | R19b last sentence, §10 item 14 (V8) |
| T24 BAD_NAME edges; BAD_NAME before TXN_NOT_OPEN; N = 4/6; txn 0; TXN_NOT_OPEN before NO_SAVEPOINT | R16a, §6 NAME_BYTE row + "Every range includes both ends", P4 (V7), R13, R14, P4 |
| T25 types 6/7 valid, type 8 unknown; SP/RB in torn tail ignored | R7, §9 rows, R12, P5 |
| T26 ROLLBACK then ABORT; ROLLBACK without COMMIT; SP/RB not in lists | R23, R23b ("stays open"), R26, R25 (V6) |

## Example 4 (re-verified)

- Outcome derived by hand from the rules before `work/notes/examples.md` was opened: 8 intact records, p = 95 = L;
  SP 73 at position 1; ROLLBACK keeps [PUT a=1]; PUT c=3; COMMIT applies a=1, c=3. Expected
  `OK state={"a"="1", "c"="3"} committed=[1] aborted=[] incomplete=[] validLength=95 tornTail=false`. Same as
  examples.md and P04.
- Own encode-only script `work/scratch/p8a/ex4_encode.py` rebuilds the 8 records from the description column:
  offsets 4, 13, 27, 37, 51, 62, 72, 86, sizes 9, 14, 10, 14, 11, 10, 14, 9, L = 95, and every hex byte equals
  PROMPT.md. SAVEPOINT and ROLLBACK checksums also traced by hand (C3 7F, CB 80).
- P04 in `tests/public/PublicTests.java` has the same bytes (also checked by selftest byte for byte) and the same
  expected line. The audit edits do not touch Example 4 or any rule it uses in a way that changes its outcome.
