# PROMPT.md audit, draft v5 (L4 READ, L5 16 MiB, L6 RANGE), 2026-11-24

Reviewed: working copy of `wal-recovery/task/PROMPT.md` (466 lines) against v4 (`git show HEAD:wal-recovery/task/PROMPT.md`).
Every changed or new line in `git diff HEAD` was checked against the P2 checklist. Report only: PROMPT.md was not edited.
G3 kept: no recover() implementation was written. The only script is `work/scratch/p8v5a/enc.py` (frame + Fletcher-16 encoder for the proposed example).

Severity: **M** = could change an outcome, two readings, or a contradiction between parts of the text (or an unfair test risk);
**L** = wording; the rules still give one answer. **I** = checked, not a problem.

## Findings

| # | Sev | Location | Problem | Exact fix (old → new) |
|---|---|---|---|---|
| V1 | M | §10 item 19 | §10 says "These cases are valid and must not throw", but item 19 describes a READ that "fails (R19d)", i.e. throws READ_MISMATCH. Direct contradiction between the section header and the item; a solver who trusts §10 would not throw. | Old: "A READ that expects a value which another transaction committed after this transaction's BEGIN record: the READ is checked against the snapshot, so it fails (R19d) unless the transaction's own write list decides." → New: "A READ that expects the value from the transaction's snapshot, although another transaction committed a different value for that key after this transaction's BEGIN record: the READ is checked against the snapshot, not the current state, so it passes (R19d) unless the transaction's own write list decides. (Such a transaction then conflicts at its COMMIT, R22.)" |
| V2 | M | §10 item 3 | "A COMMIT of a transaction with no writes. Its txn is appended to `committed`" is now false for a transaction with READ/RANGE records that conflicts (R22 puts it in `aborted`). Example: B1, RD1 "a" absent, B2, P2 a=1, C2, C1 → R22: aborted [1]; item 3: committed [1]. | Old: "3. A COMMIT of a transaction with no writes. Its txn is appended to `committed`, and the state does not change." → New: "3. A COMMIT of a transaction with no writes. If the transaction does not conflict (R22), its txn is appended to `committed`; in either case the state does not change." |
| V3 | M (fairness) | §2 last bullet, R20 | The text says large tests exist but not which dimension is large. The rules as written ("Its snapshot is a copy of the state", "the number of keys K with lo ≤ K ≤ hi") lead directly to O(state) per BEGIN and O(range) per RANGE. A 16 MiB log can hold ~10^6 keys and ~5×10^5 BEGINs, so a literal solution can need ~10^11 steps, and 4 live copies of a 10^6-key HashMap (~100 bytes/key) can exceed 512 MiB (OutOfMemoryError = "another exception", §2). An efficient solution needs versioned state or per-open-transaction deltas, an order-statistic/Fenwick structure over keys for RANGE, and interval lookup for range conflicts. Whether this is required depends on test shapes that the text does not state: a hidden trap. | Either (a) state the shapes: Old: "Some tests use logs of more than 10 MiB with hundreds of thousands of transactions, so `recover` must be fast enough for those as well." → New: "Some tests use logs of more than 10 MiB with hundreds of thousands of transactions. In these tests the state can hold up to 1000000 keys, one transaction can stay open from near the start of the log to near its end, and one transaction can have tens of thousands of READ and RANGE records whose ranges cover most keys. So a solution that copies the whole state at each BEGIN, visits every key between lo and hi for a RANGE, or compares each committed key with every read range one by one is too slow: the work per record must grow at most logarithmically with the size of the state and the number of earlier records." (numbers must match the planned tests), and in R20 Old: "Its snapshot is a copy of the state at this point." → New: "Its snapshot is the state as it is at this point; later changes of the state do not change the snapshot (a solution does not have to copy the state to keep it)."; or (b) keep large tests whose state stays small (e.g. ≤ 1000 keys) and say so in §2. |
| V4 | L | R22, first sentence | "U that was committed by this rule ... whose COMMIT record is at a larger offset than T's BEGIN record": only the past tense excludes a U that commits later in the log than T. A two-pass reader could include it. | Old: "whose COMMIT record is at a larger offset than T's BEGIN record," → New: "whose COMMIT record lies after T's BEGIN record and before T's COMMIT record in log order," |
| V5 | L | R19d | "present with its value" is used for both the snapshot's value and the READ's value; equality of the values (including v = 0, `""`) is implied, not stated. | Old: "The READ expects \"absent\" when f = 0, and \"present with its value\" when f = 1. If what the READ expects is not the view, throw READ_MISMATCH at p." → New: "The READ expects \"absent\" when f = 0, and \"present with the READ's value\" when f = 1. The READ matches the view when both are \"absent\", or both are \"present\" with equal values (the same bytes; the empty value `\"\"` is present). Otherwise throw READ_MISMATCH at p." |
| V6 | L | R19e | "Keys that the view does not contain" is not a defined term (R19d defines a view of one key as present/absent). | Old: "Keys that the view does not contain do not count." → New: "A key whose view is \"absent\" does not count, and each key counts once, however many writes of it the write list holds." |
| V7 | L | §4 definitions bullet "Each open transaction has a write list ... and a set of savepoints" ; R20 | Not updated: an open transaction now also has a snapshot, read set and read ranges. R20 does not say the read set and read ranges start empty (only implied by "processed so far"). | §4 Old: "Each open transaction has a write list (R20, R21) and a set of savepoints (R23a)." → New: "Each open transaction has a write list (R20, R21), a set of savepoints (R23a), a snapshot, a read set and read ranges (defined below)." R20 Old: "with an empty write list and no savepoints." → New: "with an empty write list, no savepoints, an empty read set and no read ranges." |
| V8 | L | §4 "The keys written by a transaction" vs R22 "written keys" | Two names for one term. | §4 Old: "The keys written by a transaction are" → New: "The written keys of a transaction are" |
| V9 | L | §1 overview, new sentence | "Each transaction reads ..." (not every one does) and "counted a range holding a key" suggests the key must be present in the range, while R22 conflicts on any written key K with lo ≤ K ≤ hi, present or not (e.g. a DELETE of an absent key). | Old: "Each transaction reads single keys and counts key ranges in its own snapshot of the state, and a transaction that read a key, or counted a range holding a key, which another transaction wrote and committed after the first one began is aborted at its COMMIT." → New: "A transaction can read single keys and count the keys in a key range, both in its own snapshot of the state; a transaction is aborted at its COMMIT when another transaction that committed after it began wrote a key that it read or a key inside a range that it counted." |
| V10 | L | §10 item 18 | "without reading it" could be read as "without a READ", leaving a RANGE that covers the key unclear. | Old: "Also two transactions that write the same key at the same time without reading it: they never conflict (R22)." → New: "Also two transactions that write the same key at the same time, when neither has a READ of that key or a RANGE whose range holds it: they never conflict (R22)." |
| V11 | L | §10 item 1 | Still true, but a solver may infer a DELETE of a missing key "does nothing" for conflicts too; R22 counts it as a written key. | Append: "It is still a write of that key, so it can make another transaction conflict (R22)." |
| V12 | L | §9 MALFORMED_PAYLOAD row | Does not cover a READ with f not 0 or 1 (N may match a layout). | Old: "N does not match the payload layout of the record type" → New: "N does not match the payload layout of the record type, or a READ's f is not 0 or 1" |
| V13 | L | §11 | No worked example uses READ, RANGE or a conflict, although the overview says these interact. Proposed Example 6 below (bytes from `work/scratch/p8v5a/enc.py`, outcome derived by hand). | Add Example 6 (below). |
| I1 | I | §5.3 table, positions bullet, R13 (READ, RANGE) | Consistent: READ f=0 4+1+k+1 = 6+k, f=1 +2+v = 8+k+v; value at 8+k..7+k+v; RANGE 4+1+k1+1+k2+4 = 10+k1+k2, count at 6+k1+k2; sizes +5. The R13 bounds N ≥ 6+k (READ) and N ≥ 6+k1 (RANGE) guarantee f/k2 lie inside the payload; N ≥ 8+k guarantees v. The first "N ≥ 6" of each is implied by the second, harmless. | none |
| I2 | I | N vs MAX_PAYLOAD | A well-formed RANGE has N ≤ 10+255+255 = 520 < 1024, so R8 can never hide a RANGE layout; a READ with f=1 and large v can hit R8 first (phase 1), consistent with P2. | none |
| I3 | I | RANGE with k1 or k2 = 0 or > 32 | R13 runs first: if N = 10+k1+k2 it passes and R15 throws BAD_KEY; if N does not match (or N < 6+k1), MALFORMED_PAYLOAD. One outcome each. | none |
| I4 | I | R14 "every type except CHECKPOINT", R15/R16 type lists, R19 list, R19a non-writes, P2 list, P4 pairs, R25 lists, enum, §9 rows | All updated consistently for types 9 and 10. | none |
| I5 | I | ROLLBACK, savepoints, CHECKPOINT | R23b says read set/ranges unchanged; R23a/R23c "nothing else" changes; view uses the current write list, so rolled-back writes leave the view. Conflict-aborted transactions are "aborted" for R17, R18, R19, R19c. | none |
| I6 | I | Torn tail (P5) | A READ/RANGE/COMMIT in the torn tail is not processed, so it adds nothing to a read set and decides no conflict. | none |
| I7 | I | count u32 | n ≤ number of keys < 2^31; a signed read of count ≥ 2^31 still mismatches. No trap. | none |

## Proposed Example 6 (snapshot read, conflict, range count)

```
offset  bytes                                                  record
     0  57 41 4C 31                                            header
     4  01 00 04 00 00 00 01 1C 06                             BEGIN txn 1
    13  01 00 04 00 00 00 02 1D 07                             BEGIN txn 2
    22  09 00 07 00 00 00 01 01 61 00 5C 73                    READ txn 1, "a", f = 0
    34  02 00 09 00 00 00 02 01 61 00 01 31 3C A1              PUT txn 2, "a" = "1"
    48  04 00 04 00 00 00 02 32 0A                             COMMIT txn 2
    57  02 00 09 00 00 00 01 01 62 00 01 32 3B A2              PUT txn 1, "b" = "2"
    71  04 00 04 00 00 00 01 31 09                             COMMIT txn 1
    80  01 00 04 00 00 00 03 1E 08                             BEGIN txn 3
    89  0A 00 0C 00 00 00 03 01 61 01 62 00 00 00 01 F2 DF     RANGE txn 3, lo "a", hi "b", count 1
   106  04 00 04 00 00 00 03 33 0B                             COMMIT txn 3
L = 115
```
Expected: state {"a"="1"}, committed [2, 3], aborted [1], incomplete [], validLength 115, tornTail false.
Why (by hand): txn 1's snapshot is {} (R20 at 4), so READ "a" f=0 passes (R19d) and "a" joins its read set (R23d). COMMIT 2 at 48: txn 2 has no reads, commits a="1". COMMIT 1 at 71: txn 2 was committed, its COMMIT (48) is after txn 1's BEGIN (4), and it wrote "a", which is in txn 1's read set → conflict: txn 1 aborted, b="2" not applied (R22). BEGIN 3 at 80: snapshot {a="1"}. RANGE a..b: "a" present, n = 1 = count (R19e). COMMIT 3 at 106: no transaction committed after offset 80 → committed.
Checksums of the BEGIN, PUT b=2 and COMMIT 1 records match the existing examples (`1C 06`, `3B A2`, `31 09`).

## Walkthrough of tricky inputs (rules applied by hand)

Notation: B(t), P(t,key=val), D(t,key), C(t), A(t), S(t,n), RB(t,n), CP[..], RD(t,key,absent) / RD(t,key="v"), RG(t,lo,hi,count). All checksums correct. Offsets: header 4, B/C/A 9, P 12+k+v, D 10+k, S/RB 10, RD 11+k (f=0) or 13+k+v, RG 15+k1+k2.
Unless an error is given, incomplete = [] and tornTail = false. Every input got exactly one outcome from the rules (V1/V2 only make §10 disagree with them).

| # | Log | Outcome | Rules |
|---|---|---|---|
| W1 | B1, P1 a=1, C1, B2, RD2 a="1", C2 | state {a=1}, committed [1,2] | snapshot of 2 = {a=1}; U=1 committed before B2 → no conflict |
| W2 | B1, B2, P1 a=1, C1, RD2 a absent, C2 | state {a=1}, committed [1], aborted [2] | snapshot {} → pass; U=1 COMMIT after B2, wrote a → conflict |
| W3 | B1@4, B2@13, P1 a=1@22, C1@36, RD2 a="1"@45 | READ_MISMATCH@45 | snapshot {} (current state not used, R19d); this is the case §10.19 lists as "not an error" (V1) |
| W4 | B1, RD1 a absent, B2, P2 a=1, C2, C1 | state {a=1}, committed [2], aborted [1] | read before U's commit still conflicts |
| W5 | B1, B2, P2 a=1, A2, RD1 a absent, C1 | state {}, committed [1], aborted [2] | ABORTed U never counts |
| W6 | B1, B2, RD2 a absent, P2 b=1, RD1 b absent, B3, P3 a=1, C3, C2, C1 | state {a=1}, committed [3,1], aborted [2] | C2 conflicts with 3 (a); conflict-aborted 2 does not count for 1; 3's keys {a} vs 1's read set {b} → no |
| W7 | B1, P1 a=1, D1 a, RD1 a absent, C1 | state {}, committed [1] | last write of a is DELETE → absent |
| W8 | B1, D1 a, P1 a=2, RD1 a="2", C1 | state {a=2}, committed [1] | last write is PUT |
| W9 | B1, P1 a=1, C1, B2, P2 a=2, S2 s, D2 a, RB2 s, RD2 a="2", C2 | state {a=2}, committed [1,2] | ROLLBACK removes DELETE; view = PUT a=2 |
| W10 | B1, P1 a=1, C1, B2, S2 s, D2 a, RB2 s, RD2 a="1", C2 | state {a=1}, committed [1,2] | write list empty → snapshot decides |
| W11 | B1@4, P1 a=1@13, P1 c=1@27, C1@41, B2@50, P2 b=x@59, D2 c@73, P2 d=y@84, RG2(a,c,3)@98 | RANGE_MISMATCH@98 | view present in [a,c]: a (snapshot), b (own PUT); c own-deleted; d outside → n=2; with count 2 it passes |
| W12 | snapshot {a, a!, ab, b, b!}; RG(a,b,4) | passes | prefix order: a < a! < ab < b < b!; b! > hi |
| W13 | snapshot {a}; RG(a!,a!,0) and RG(a,a,1) | both pass | ends need not be keys; lo == hi counts K = lo |
| W14 | RG(b,a,0) pass; RG(b,a,1) | RANGE_MISMATCH at the second | lo > hi → n = 0; also never causes a conflict |
| W15 | B1, RG1(a,c,0), B2, D2 b, C2, C1 | state {}, committed [2], aborted [1] | DELETE of an absent key is a written key (V11) |
| W16 | B1, RG1(a,b,0), B2, P2 b=1, C2, C1 | state {b=1}, committed [2], aborted [1] | K = hi is inside |
| W17 | as W16 but P2 b!=1 | state {b!=1}, committed [2,1] | b! > b, outside |
| W18 | B1, RD1 a absent, B2, S2 s, P2 a=1, RB2 s, C2, C1 | state {}, committed [2,1] | U's written keys at commit = {} |
| W19 | B1, B2, P2 a=1, C2, RD1 a absent, C1 | state {a=1}, committed [2], aborted [1] | READ passes on snapshot; read set at T's COMMIT contains a |
| W20 | B1, B2, P1 a=1, P2 a=2, C2, C1 | state {a=1}, committed [2,1] | writes only, no conflict (§10.18) |
| W21 | B1, P1 a=1, RD1 a="1", B2, P2 a=2, C2, C1 | state {a=2}, committed [2], aborted [1] | own-write READ still in read set |
| W22 | B1, RD1 a absent, B2, P2 a=1, C2, C1 | state {a=1}, committed [2], aborted [1] | txn 1 has no writes but goes to aborted (contradicts §10.3, V2) |
| W23 | B1@4, RANGE@13 txn 1 k1=0 k2=1 N=11 | BAD_KEY@13 | R13 passes, R15 fails; with k1=40, N=20 → MALFORMED_PAYLOAD@13 (N ≥ 6+k1 fails) |
| W24 | RANGE@4 txn 0, lo with byte 20 | BAD_TXN_ID@4 | R14 before R15 |
| W25 | READ@4 f=2, N=6+k; READ@4 f=1 v=0 txn 9 not open; READ@4 f=1 value byte 7F txn not open | MALFORMED_PAYLOAD@4; TXN_NOT_OPEN@4; BAD_VALUE@4 | R13; R19; R16 before R19 |
| W26 | B1@4, C1@13, RD1@22 | TXN_NOT_OPEN@22 | same for a conflict-aborted txn |
| W27 | B1, B2, B3, RD1 a absent, P1 b=1, RD3 b absent, P2 a=1, C2, C1, C3 | state {a=1}, committed [2,3], aborted [1] | 1 conflicts (a); aborted 1 does not make 3 conflict |
| W28 | B1, P1 a=1, P1 a=2, RG1(a,a,1) | passes (count 2 → RANGE_MISMATCH) | keys counted once |
| W29 | B1, P1 a="", C1, B2, RD2 a absent | READ_MISMATCH at the READ | empty value is present; RD2 a f=1 v=0 passes (V5 wording) |
| W30 | B2, P2 a=1, C2, B1, RD1 a="1", C1 | state {a=1}, committed [2,1] | U COMMIT before T BEGIN does not count |
| W31 | B1, RD1 a absent, B2, P2 a=1, C2, C1, CP[] | committed [2], aborted [1] | conflict-aborted txn is closed for R19c |
| W32 | B1, P1 a=1, READ txn 1 whose checksum is wrong and which ends at L | validLength = READ offset, tornTail true, incomplete [1] | torn tail READ not processed (P5) |

## Fairness notes

- V3 is the only risk I see of failing a solver for something the text does not state: the text announces large tests but not the parameters that decide whether a literal "copy the state" / "count the keys" solution is fast enough or fits in 512 MiB.
- V1 and V2 are contradictions inside the text; a test that follows R19d/R22 (as it should) could fail a solver who trusted §10.
