# Hidden test coverage

Rule numbers are those of task/PROMPT.md (DRAFT v3, audited): R1-R27 with the v3 rules R16a, R19a, R19b, R23a, R23b; precedence P1-P5; "Not errors" items 1-15 (section 10).
Traps T1-T17 are PLAN.md section 4.6; T18-T26 are work/notes/spec-change-v3.md section 5 (levers L1 and L2).
"Main" means the test was built to check that rule; "also" means the rule is exercised on the way (for example, R13 on a well-formed record) but is not what the test is about.

Status: P5 wrote groups A-C (H01-H24). P6 wrote groups D-H (H25-H63). P7 (audit) added group I (H64-H69). The P8 hardening test session (v3) re-derived H11 and H20 for v3 (type 8 instead of 6/7) and added groups J (L1, H70-H77) and K (L2, H78-H99). Total: 99 hidden tests. H70-H99 and the new H11/H20 are **not yet audited**.

Groups: A header H01-H06, B framing and tail H07-H20, C checksum details H21-H24, D payload structure H25-H31, E field checks H32-H40, F transactions H41-H52, G precedence and interactions H53-H61, H API contract H62-H63, I P7 coverage gaps H64-H69 (H64 header, H65/H67 payload, H66 framing, H68/H69 precedence), J write limit L1 H70-H77, K savepoints L2 H78-H99.

## PROMPT.md rule -> tests

| Rule | Main | Also | Notes |
|---|---|---|---|
| R1 null input | H63 | | TestCase.ofNull, expects THROW java.lang.NullPointerException |
| R2 L = 0 | H01 | | |
| R3 L = 1..3 | H03, H04, H05, H64 | | H03/H04/H64 torn branch (L = 1, 2, 3), H05 BAD_MAGIC branch |
| R4 magic, L >= 4 | H06 | H02 | H06: only byte 3 wrong ("WAL2") |
| R5 p = L | H02 | H17, H22, H23, most OK and phase-2 error tests | |
| R6 1 or 2 bytes left | H07, H08 | H09 (3 bytes: R6 does not apply), H59 | |
| R7 unknown type (valid 1..7) | H09, H10, H11, H20 | H78, H79, H80, H81, H99 and every group K test (types 6 and 7 pass R7) | types 9, 0, 8, 8; v3: H11/H20 use type 8, one above ROLLBACK |
| R8 N > 1024 | H16, H18, H19, H54, H66 | H17 (N = 1024 ok), H23 (N = 200 ok) | H66: R8 before R10 on the last record |
| R9 does not fit | H12, H58 | H19 (R8 wins), H62 | |
| R10 checksum | H13, H14, H15, H21, H24, H53, H98, H99 | H20 (R7 wins), H66 (R8 wins), H22, H23, H60 | H98: torn ROLLBACK ending at L; H99: mismatch on a PUT after a ROLLBACK |
| R11 intact | H25, H26, H78 | H17, H22, H23 and every OK test | malformed payloads (also SAVEPOINT N = 4) pass phase 1 |
| R12 torn tail | H07, H08, H12, H13 | H58, H59, H60, H62, H98 | |
| R13 payload layout | H25-H31, H65, H67, H78, H79, H80, H81 | H33, H35, H51 (v = 0), H54 (not a phase-1 check); N = 5 ok in every group K test | v3: SAVEPOINT N = 4 (H78), N = 6 (H79); ROLLBACK N = 6 (H80), N = 4 (H81) |
| R14 txn 0 | H32, H55, H68, H85 | H52 (txn 4294967295 ok), every test with txn 1 | H85: ROLLBACK txn 0 |
| R15 key | H33, H35, H36, H37, H56 | H34 (k = 32, bytes 21 and 7E ok) | k = 0, k = 33, byte 20, byte 7F (DELETE), byte E9 |
| R16 value | H39, H40, H69, H76 | H38 (bytes 20 and 7E ok), H51/H62 (v = 0), H61 (not reached) | H76: before R19a |
| R16a name (v3) | H82, H83, H86 | H84 (21 and 7E ok), H79 (R13 wins), H85 (R14 wins) | SAVEPOINT 20 (H82), ROLLBACK 7F (H83, before R19b), SAVEPOINT 7F txn not open (H86, before R19) |
| R17 duplicate id | H44, H45, H57 | H95 (new id after a committed txn) | open, committed, aborted id |
| R18 open limit | H47, H49 | H43, H48 (4 open ok), H57 (R17 wins), H68 (R14 wins) | |
| R19 open check | H46, H59, H61, H77, H87, H88 | H55, H56, H69, H86 (field check wins) | v3: SAVEPOINT never begun (H87), ROLLBACK after COMMIT (H88), PUT after COMMIT with 8 writes (H77) |
| R19a write limit (v3) | H70, H71, H72, H73, H74, H75 | H76 (R16 wins), H77 (R19 wins) | exactly 8 ok (H70, H73, H75); 9th PUT same key (H71); 9th write DELETE (H72); ROLLBACK frees room (H73); 9th in list after ROLLBACK (H74); SP/RB at 8 writes (H75); per-transaction list (H70) |
| R19b savepoint check (v3) | H89, H92, H93 | H83 (R16a wins), H88 (R19 wins), H99 (phase 1 wins) | other txn's name (H89); removed by an earlier ROLLBACK (H92 moved, H93 equal positions) |
| R20 BEGIN | | every OK test | |
| R21 write list | H17, H41, H51, H96 | H23, H62 | H96: append after a ROLLBACK |
| R22 COMMIT apply | H17, H23, H41, H50, H51, H70, H96 | H07, H12, H42, H52, H62, H73, H75, H84, H90, H91, H94, H95 | v3: savepoints discarded (H88) |
| R23 ABORT | H22, H42, H97 | H49, H62 | H97: after SAVEPOINT/ROLLBACK |
| R23a SAVEPOINT action (v3) | H91, H95 | H73, H75, H84, H90, H92, H93, H94, H96 | move on reuse (H91, H92); position 0 (H95); same name in two txns (H90) |
| R23b ROLLBACK action (v3) | H91, H92, H93, H94, H96 | H73, H74, H75, H84, H90, H95, H97, H98 | removes later-set savepoints by setting offset (H91, H92, H93); equal positions (H93); same savepoint twice (H94); kept writes in order (H96); removes nothing (H75) |
| R24 state | | all OK tests | |
| R25 committed/aborted, torn COMMIT not listed | H13, H22, H41, H58, H97 | H07, H42 | H97: SP/RB add nothing to the lists |
| R26 incomplete | H43, H98 | H08, H12, H13, H48, H52, H58, H62 | H43: unsigned sort; H98: incomplete txn with savepoints |
| R27 validLength/tornTail | H02 | all OK tests | |
| P1 header first | H05, H06 | | |
| P2 phase 1 beats phase 2 | H53, H54, H99 | | BAD_TXN_ID vs CHECKSUM_MISMATCH; MALFORMED_PAYLOAD vs RECORD_TOO_LARGE; NO_SAVEPOINT vs CHECKSUM_MISMATCH (H99) |
| P3 phase 1 stops at first problem | H15 | | |
| P4 phase-2 order | H55, H56, H57, H61, H68, H69, H76, H77, H79, H83, H85, H86, H88 | | v3 pairs: R16 before R19a (H76); R19 before R19a (H77); R13 before R16a (H79); R16a before R19b (H83); R14 before R16a (H85); R16a before R19 (H86); R19 before R19b (H88) |
| P5 torn tail not checked | H08, H13, H58, H60, H98 | H59 (error before a torn tail is thrown) | H98: ROLLBACK in the torn tail |
| Section 2 input not modified | H62 | harness check on every test | |
| Not errors 1 (DELETE missing key) | H50 | H62, H72 (DELETEs of missing keys count as writes) | |
| Not errors 2 (empty value) | H51 | H62 | |
| Not errors 3 (empty commit) | H42 | H07, H48, H90 (txn 2), H95 (list emptied by ROLLBACK) | |
| Not errors 4 (ids in any order) | H41, H43 | H57, H95 | |
| Not errors 5 (header only) | H02 | | |
| Not errors 6 (1-2 leftover bytes) | H07, H08 | H59 | |
| Not errors 7 (never finished) | H58, H98 | H08, H12, H13, H43, H48 | |
| Not errors 8 (same key twice) | H41, H51 | H17, H96 | |
| Not errors 9 (bad bytes in torn tail) | H08, H60, H98 | | |
| Not errors 10 (exactly MAX_WRITES; room freed by ROLLBACK) | H70, H73 | H75 | |
| Not errors 11 (SP/RB at MAX_WRITES) | H75 | H73 (ROLLBACK at 8) | |
| Not errors 12 (reused name moves) | H91 | H92 | |
| Not errors 13 (ROLLBACK removes nothing; same savepoint twice) | H75, H94 | | removed-in-between case is R19b: H92, H93 |
| Not errors 14 (same name in two txns) | H90 | H89 (the other txn's name does not count) | |
| Not errors 15 (unused savepoint) | H84 (name 21 never named by a ROLLBACK) | | |

## Outcome -> tests (each Code at least 2)

| Outcome | Tests | Count |
|---|---|---|
| BAD_MAGIC | H05, H06 | 2 |
| UNKNOWN_TYPE | H09, H10, H11, H20 | 4 |
| RECORD_TOO_LARGE | H16, H18, H19, H54, H66 | 5 |
| CHECKSUM_MISMATCH | H14, H15, H21, H24, H53, H99 | 6 |
| MALFORMED_PAYLOAD | H25, H26, H27, H28, H29, H30, H31, H65, H67, H78, H79, H80, H81 | 13 |
| BAD_TXN_ID | H32, H55, H68, H85 | 4 |
| BAD_KEY | H33, H35, H36, H37, H56 | 5 |
| BAD_VALUE | H39, H40, H69, H76 | 4 |
| DUPLICATE_TXN | H44, H45, H57 | 3 |
| TOO_MANY_OPEN | H47, H49 | 2 |
| TXN_NOT_OPEN | H46, H59, H61, H77, H87, H88 | 6 |
| TXN_TOO_LARGE | H71, H72, H74 | 3 |
| BAD_NAME | H82, H83, H86 | 3 |
| NO_SAVEPOINT | H89, H92, H93 | 3 |
| THROW java.lang.NullPointerException (not a Code) | H63 | 1 |
| OK (normal result) | H01-H04, H07, H08, H12, H13, H17, H22, H23, H34, H38, H41-H43, H48, H50-H52, H58, H60, H62, H64, H70, H73, H75, H84, H90, H91, H94-H98 | 35 |

Total 2+4+5+6+13+4+5+4+3+2+6+3+3+3+1+35 = 99. All 14 Codes have at least 2 tests.

## Limits -> tests (both sides)

| Limit | Allowed side | Rejected side |
|---|---|---|
| MAX_PAYLOAD 1024 | H17 (N = 1024) | H16 (N = 1025), H18 (N = 32769), H66 (N = 1025, last record) |
| MIN_TXN_ID 1 | every test with txn 1 | H32, H55, H68, H85 (txn 0) |
| MAX_TXN_ID 4294967295 | H22, H52, H43, H62 | none possible (largest u32) |
| MIN_KEY_LEN 1 | every 1-byte key | H33 (k = 0) |
| MAX_KEY_LEN 32 | H34 (k = 32) | H35 (k = 33) |
| KEY_BYTE_MIN 0x21 | H34 (byte 21) | H36 (byte 20) |
| KEY_BYTE_MAX 0x7E | H34 (byte 7E) | H37 (byte 7F), H56 (byte E9) |
| VALUE_BYTE_MIN 0x20 | H38 (byte 20), H62 | H39 (byte 1F), H69 (byte 0A) |
| VALUE_BYTE_MAX 0x7E | H38 (byte 7E), H62 | H40 (byte 7F), H76 (byte 7F) |
| value length 0 | H51, H62 (v = 0 ok) | no lower limit |
| MAX_OPEN_TXNS 4 | H43, H48 (4 open) | H47, H49 (5th BEGIN) |
| MAX_WRITES 8 (v3) | H70 (7 PUT + 1 DELETE, then COMMIT), H73 (8 again after a ROLLBACK), H75 (8 PUTs, then SAVEPOINT and ROLLBACK) | H71 (9th write a PUT), H72 (9th write a DELETE), H74 (9th in the list after a ROLLBACK) |
| NAME_BYTE_MIN 0x21 (v3) | H84 (SAVEPOINT 21) | H82 (SAVEPOINT 20), H85 (ROLLBACK 20, R14 wins), H79 (20, R13 wins) |
| NAME_BYTE_MAX 0x7E (v3) | H84 (SAVEPOINT and ROLLBACK 7E) | H83 (ROLLBACK 7F), H86 (SAVEPOINT 7F) |
| SAVEPOINT N = 5 (v3) | every group K SAVEPOINT | H78 (N = 4), H79 (N = 6) |
| ROLLBACK N = 5 (v3) | every group K ROLLBACK | H81 (N = 4), H80 (N = 6) |
| R7 highest type 7 (v3) | every group K test (types 6, 7) | H11, H20 (type 8) |
| R6 leftover bytes | H07 (1), H08 (2) torn | H09 (3) checked |
| R10 end at L | H13, H98 (= L, torn) | H14 (L - 1, mismatch) |
| R3 partial magic | H03, H04, H64 (prefix, L = 1, 2, 3) | H05 (not a prefix) |
| R13 N >= 7 + k | H29 and all PUTs (N >= 7 + k) | H28 (N = 6 + k), H27 (key past end) |
| R13 N = 7 + k + v | all well-formed PUTs | H29 (one extra byte), H67 (one byte short) |
| R13 DELETE N = 5 + k | H37, H50, H51, H62, H72 | H30 (N = 4), H65 (one extra byte) |

## Trap -> tests (T1-T17 PLAN.md 4.6, T18-T26 spec-change-v3.md 5)

| Trap | Tests | Notes |
|---|---|---|
| T1 two-phase precedence | H53, H54, H99 | P2 quoted in each derivation |
| T2 MALFORMED_PAYLOAD is phase 2 | H25-H31, H54, H65, H67, H78-H81 | malformed records are intact in phase 1 and the error comes from R13 |
| T3 R7/R8 beat R9/R10, even on the last record | H19 (R8 beats R9), H20 (R7 beats R10, type 8), H09 (R7 beats R9), H66 (R8 beats R10) | |
| T4 R6 boundary: 2 bytes torn, 3 bytes checked | H08 (2 bytes), H09 (3 bytes), H07 (1 byte) | |
| T5 R10 boundary: ends at L torn, L+1 mismatch | H13, H14 | |
| T6 checksum covers type and N; bytes unsigned | H21 (payload only), H22 (FF bytes), H23 (C8/C0 bytes), H24 (s1/s2 order) | |
| T7 txn ids >= 2^31 | H22 (abort), H43 (2147483648, 4294967295), H52 (4294967295 committed end to end), H62 | |
| T8 N >= 32768 unsigned | H18 (N = 0x8001) | H19 has N = 2000 |
| T9 commit order, not log order | H41, H50 | H17, H23, H96: write-list order inside one txn |
| T10 DUPLICATE_TXN on finished ids; before TOO_MANY_OPEN | H45 (committed id), H57 (aborted id, and R17 before R18) | H44: open id |
| T11 field checks before transaction checks | H55, H56, H69, H68, H76, H83, H86 | H61 is the other direction |
| T12 key/value byte ranges and lengths | H33, H34, H35, H36, H37, H38, H39, H40 | H56 (key byte E9), H69 (value byte 0A) |
| T13 4-open limit, slot freed | H47 (5th fails), H48 (COMMIT frees a slot), H49 (ABORT frees one slot only) | H43: exactly 4 open |
| T14 partial magic | H03, H04, H05, H64 | |
| T15 empty vs header only | H01, H02 | |
| T16 input not modified; null input | H62 (content-rich log), H63 (null) | the harness checks INPUT_MODIFIED on every test |
| T17 incomplete sorted unsigned | H43 | |
| T18 R19a counts the current list | H70 (exactly 8, other txn's write does not count), H71 (9th PUT, same key), H72 (9th a DELETE; DELETEs of missing keys count), H73 (ROLLBACK frees room, 8 again), H74 (9th in the list after a ROLLBACK), H75 (SAVEPOINT/ROLLBACK at 8 writes) | |
| T19 field before TXN_TOO_LARGE; TXN_NOT_OPEN before TXN_TOO_LARGE | H76 (BAD_VALUE), H77 (TXN_NOT_OPEN after COMMIT of 8 writes) | |
| T20 reused name moves; moving removes nothing | H91 | H92 (moved savepoint then counts as set by the later record) |
| T21 ROLLBACK removes later-set savepoints; named one stays | H91 (t removes moved s), H92 (removed s -> NO_SAVEPOINT), H94 (same savepoint twice) | H93 |
| T22 equal positions; later by setting record; moved savepoint is later | H93 (equal positions, both directions), H92 (moved s is later than t set between its old and new setting) | |
| T23 savepoints per transaction | H89 (other txn's name -> NO_SAVEPOINT), H90 (same name in two txns, independent) | |
| T24 name bytes, N = 4/6, txn 0, BAD_NAME before TXN_NOT_OPEN, TXN_NOT_OPEN before NO_SAVEPOINT | H82 (20), H83 (7F), H84 (21, 7E ok), H78/H79 (SAVEPOINT N = 4/6), H80/H81 (ROLLBACK N = 6/4), H85 (txn 0), H86 (BAD_NAME before TXN_NOT_OPEN), H88 (TXN_NOT_OPEN before NO_SAVEPOINT), H87 (SAVEPOINT txn not open) | H79: MALFORMED before BAD_NAME |
| T25 types 6/7 valid in phase 1, type 8 unknown; SP/RB in a torn tail ignored | H11, H20 (type 8), H78-H81 (types 6/7 intact in phase 1), H98 (ROLLBACK in the torn tail), H99 (ROLLBACK intact in phase 1) | |
| T26 ROLLBACK then ABORT; ROLLBACK without COMMIT; SP/RB not in lists | H97 (ABORT after ROLLBACK, aborted = [1] only), H98 (incomplete txn with SAVEPOINT and ROLLBACK) | every group K OK test: SP/RB add nothing to committed/aborted |

## Questions for the audit (v3)

- None that decide an outcome. Points a reviewer may want to confirm:
  - H77 and H88 rely on R19 being checked before R19a/R19b for a transaction that is no longer open; P4 states both orders outright, so whether R19a/R19b would "fail" for a closed transaction never matters.
  - H83 (ROLLBACK name 7F with no such savepoint) relies on the general P4 sentence "So the field checks (R13 to R16a) always come before the transaction checks (R17 to R19b)."; P4 has no separate sentence for BAD_NAME vs NO_SAVEPOINT, but the general one decides it.
  - H92/H93 rely on R23a "a savepoint is set by the SAVEPOINT record that created it or that moved it most recently" and R23b's offset sentences; both are explicit.

## P8 hardening tests (v3), 2026-11-24

- H11 (renamed frame_type_six_first_record -> frame_type_eight_first_record) and H20: the type-6/7 frames became type 8 (one above ROLLBACK), same N, payload and checksum rule (H11 correct checksum 4D 0D, H20 stored 00 00 vs correct 4D 0D). Re-derived by hand from v3: expected lines unchanged (ERR UNKNOWN_TYPE@4, ERR UNKNOWN_TYPE@13); each keeps its purpose (unknown type byte; R7 before R10 on the last record, T3).
- Comment fixes (outcomes unchanged): file header (rule list, phase-2 order R13 ... R19b, actions R20-R23b, groups J/K); H09, H10 "not 1..5" -> "not 1..7"; H55, H56, H57, H68, H69 P4 quotes now verbatim v3; H61 "R13-R19 order" -> "R13 to R19b order"; group E/F headings; PublicTests P01 "type 1-5", "R13-R19" -> v3 wording. A text check (work/scratch/p8t/text_checks.py and an inline variant) confirmed every quoted sentence of 20+ characters in HiddenTests.java comments occurs verbatim in PROMPT.md, and every "// Expected:" comment equals its literal (99/99).
- New tests H70-H99 (30): group J L1 H70-H77 (8), group K L2 H78-H99 (22). Inputs are small (L <= 196); every offset, size and checksum is written in the derivation or in the checksum table at the top of group J.
- Arithmetic/encoding only (G3): work/scratch/p8t/fletcher.py (Fletcher-16 traces), table.txt (the same traces as comment lines), DumpAll.java + inputs.txt (hex of each input), layout.py (frame starts by N only: offset, type, N, end, payload, stored checksum; no checks, no outcome). Every L, offset, payload and stored checksum in H11, H20, H70-H99 matched the derivations.
- No Gemini code (work/pilot/) was read or run. Stub run (work/scratch/stub): public 0/4, hidden 0/99, all 103 FAIL with THROW java.lang.UnsupportedOperationException; selftest 10/10 OK.

## Changes to PLAN.md 5 cases (P5)

- Group B lists 13 cases with "type 0 and type 6" as one; P5 made them two tests (H10, H11), so B has 14 tests and A-C has 24.
- H17 (N = 1024) and H23 (N = 200) need long values. To keep every expected line a short literal a reviewer can check, the long value is overwritten (H17) or deleted (H23) later in the same transaction; the final state holds only a short value. The long record must still be parsed exactly (its N, v and checksum), or the frames after it shift and the outcome changes.
- H22 (txn FF FF FF FF) ends with ABORT, not COMMIT, so it does not repeat P6 group F's "id 4294967295 end to end" case.

## Code vs test disagreements

- None. There is no pilot code: work/pilot/smoke/gemini_run_1/WalRecovery.java is empty (HTTP 429). The only run was the throwing stub (work/scratch/stub): 27/27 FAIL with THROW java.lang.UnsupportedOperationException, which only shows that the tests compile and run.

## Checks done in P5 (arithmetic only, G3)

- work/scratch/p5_fletcher.py: Fletcher-16 traces for the short records quoted in the derivations, plus s2 s1 of the 3 long PUTs. Encoding/arithmetic only.
- work/scratch/p5_inputs/DumpInputs.java: prints L and the hex of each hidden-test input (no decoding). Every L and every quoted byte in the derivations matched.

## Changes to PLAN.md 5 cases (P6)

- Group D (H25-H31, 7 tests): PLAN lists 6 cases (count says 5). Added H28, PUT with N = 6 + k, the boundary of R13's "N >= 7 + k".
- Group E (H32-H40, 9 tests): "key with 0x21/0x7E ok" is part of H34 (32-byte key whose first byte is 21 and last is 7E). PLAN's value byte 0x0A is replaced by 0x1F (H39), the byte just below VALUE_BYTE_MIN (0x0A is also below 0x20, so H39 covers that case); H40 adds 0x7F for the upper edge, and H38 checks 0x20 and 0x7E together.
- Group F (H41-H52, 12 tests): all 12 listed cases. "Empty commit" is the second half of H42 (after an ABORT, an empty COMMIT must not apply the dropped writes); H49 is an extra test (ABORT frees one slot; the second BEGIN after it is TOO_MANY_OPEN), giving TOO_MANY_OPEN its second test.
- Group G (H53-H61, 9 tests): PLAN's 8 cases plus H61 (P4 first sentence: an earlier record with an R19 failure beats a later record with an R16 failure). "phase-2 error early + checksum later" uses BAD_TXN_ID (H53); "DUPLICATE + TOO_MANY_OPEN" uses an aborted id (H57), so it also covers T10's "finished ids".
- Group H (H62-H63, 2 tests).
- Total 63 hidden tests, above PLAN's 48-55 target (the orchestrator allowed more).

## Questions for P7

- None found. Every P6 expected outcome is decided by one stated rule, quoted in the derivation where precedence is involved. Points a reviewer may want to confirm: H57 relies on R17's "whether that transaction is still open, committed or aborted"; H48/H49 rely on R18's "A transaction whose COMMIT or ABORT record has already been processed is no longer open and does not count"; H59 relies on P5's second sentence.

## Code vs test disagreements (P6)

- None. No pilot code exists (work/pilot/smoke/gemini_run_1/WalRecovery.java is empty after HTTP 429). Stub run (work/scratch/stub): public 0/3, hidden 0/63, all 66 FAIL with THROW java.lang.UnsupportedOperationException, which only shows that the tests compile and run.

## Checks done in P6 (arithmetic only, G3)

- work/scratch/p6_fletcher.py: Fletcher-16 s1/s2 traces for every new short record (copied into the checksum table at the top of group D in HiddenTests.java), and s2 s1 of the 32- and 33-byte-key PUTs (s1 also by hand in H34/H35). Encoding and arithmetic only.
- work/scratch/p6_inputs/DumpInputs.java: prints L and hex of each input from H25 on (no decoding). Every L and every quoted stored byte (H53 A2 EE, H60 2A F4, H58/H62 truncated tails) matched the derivations.
- A one-off text check that each "// Expected:" comment equals the literal in its TestCase (63 checked, 0 mismatches). It compares two hand-written strings; it computes nothing.


## P7 audit (2026-11-24)

- Independent audit in work/notes/test-audit.md: every test (P01-P03, H01-H63) re-derived by hand from a stripped view (id + input code only) before any expected line was read. 66 of 66 agree; no expected line or derivation changed.
- The three wording points above (R17 aborted id, R18 "does not count", P5 second sentence) were assessed as clear; PROMPT.md unchanged.
- Group I added (H64-H69) for gaps: R3 torn at L = 3 (H64); R13 DELETE N = 5 + k failing side (H65); R8 before R10 on the last record, T3 (H66); R13 PUT value one byte short (H67); R14 before R18 on a BEGIN, T11 (H68); R16 before R19, T11 (H69).
- Public tests P01-P03 now carry a short derivation comment (bytes and expected lines unchanged).
