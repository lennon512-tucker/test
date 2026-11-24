# Hidden test coverage

Rule numbers are those of task/PROMPT.md (DRAFT v2): R1-R27, precedence P1-P5, "Not errors" items 1-9 (section 10).
Traps T1-T17 are PLAN.md section 4.6.
"Main" means the test was built to check that rule; "also" means the rule is exercised on the way (for example, R13 on a well-formed record) but is not what the test is about.

Status: P5 wrote groups A-C (H01-H24). P6 adds groups D-H (H25 upward) and must extend both tables.

## PROMPT.md rule -> tests

| Rule | Main | Also | Notes |
|---|---|---|---|
| R1 null input | - | - | P6 group H |
| R2 L = 0 | H01 | | |
| R3 L = 1..3 | H03, H04, H05 | | H03/H04 torn branch, H05 BAD_MAGIC branch |
| R4 magic, L >= 4 | H06 | H02 | H06: only byte 3 wrong ("WAL2") |
| R5 p = L | H02 | H17, H22, H23 | |
| R6 1 or 2 bytes left | H07, H08 | H09 (3 bytes: R6 does not apply) | |
| R7 unknown type | H09, H10, H11, H20 | | types 9, 0, 6, 7 |
| R8 N > 1024 | H16, H18, H19 | H17 (N = 1024 ok), H23 (N = 200 ok) | |
| R9 does not fit | H12 | H19 (R8 wins) | |
| R10 checksum | H13, H14, H15, H21, H24 | H20 (R7 wins), H22, H23 (correct checksums with FF / C8 bytes) | |
| R11 intact | | H17, H22, H23 (and every OK test) | |
| R12 torn tail | H07, H08, H12, H13 | | |
| R13 payload layout | | H17, H23 (well-formed, large N) | malformed cases: P6 group D |
| R14 txn 0 | - | every phase-2 test (txn != 0) | P6 group E |
| R15 key | - | H12, H17, H22, H23 (valid keys) | P6 group E |
| R16 value | - | H12, H17, H22, H23 (valid values) | P6 group E |
| R17 duplicate id | - | - | P6 groups F, G |
| R18 open limit | - | - | P6 groups F, G |
| R19 open check | - | every PUT/COMMIT/ABORT (open) | P6 groups F, G |
| R20 BEGIN | | H07, H08, H12, H13, H17, H22, H23 | |
| R21 write list | H17 | H23 | |
| R22 COMMIT apply | H17, H23 | H07 (empty commit), H12 | |
| R23 ABORT | H22 | | |
| R24 state | | all OK tests | |
| R25 committed/aborted, torn COMMIT not listed | H13, H22 | H07 | |
| R26 incomplete | | H08, H12, H13 | sort order: P6 group F |
| R27 validLength/tornTail | H02 | all OK tests | |
| P1 header first | H05, H06 | | |
| P2 phase 1 beats phase 2 | - | - | P6 group G |
| P3 phase 1 stops at first problem | H15 | | |
| P4 phase-2 order | - | - | P6 group G |
| P5 torn tail not checked | H08, H13 | | |
| Not errors 3 (empty commit) | | H07 | |
| Not errors 5 (header only) | H02 | | |
| Not errors 6 (1-2 leftover bytes) | H07, H08 | | |
| Not errors 7 (never finished) | | H08, H12, H13 | |
| Not errors 8 (same key twice) | | H17 | |
| Not errors 9 (bad bytes in torn tail) | H08 | | |

## PLAN.md 4.6 trap -> tests

| Trap | Tests | Notes |
|---|---|---|
| T1 two-phase precedence | - | P6 group G |
| T2 MALFORMED_PAYLOAD is phase 2 | - | P6 groups D, G |
| T3 R7/R8 beat R9/R10, even on the last record | H19 (R8 beats R9), H20 (R7 beats R10) | |
| T4 R6 boundary: 2 bytes torn, 3 bytes checked | H08 (2 bytes), H09 (3 bytes), H07 (1 byte) | |
| T5 R10 boundary: ends at L torn, L+1 mismatch | H13, H14 | |
| T6 checksum covers type and N; bytes unsigned | H21 (payload only), H22 (FF bytes), H23 (C8/C0 bytes), H24 (s1/s2 order) | |
| T7 txn ids >= 2^31 | H22 (4294967295) | end to end commit: P6 group F |
| T8 N >= 32768 unsigned | H18 (N = 0x8001) | H19 has N = 2000 |
| T9 commit order, not log order | - | P6 group F (H17, H23 check write-list order inside one txn) |
| T10 DUPLICATE_TXN on finished ids; before TOO_MANY_OPEN | - | P6 groups F, G |
| T11 field checks before transaction checks | - | P6 group G |
| T12 key/value byte ranges and lengths | - | P6 group E |
| T13 4-open limit, slot freed | - | P6 group F |
| T14 partial magic | H03, H04, H05 | |
| T15 empty vs header only | H01, H02 | |
| T16 input not modified; null input | - | P6 group H (the harness checks INPUT_MODIFIED on every test) |
| T17 incomplete sorted unsigned | - | P6 group F |

## Changes to PLAN.md 5 cases (P5)

- Group B lists 13 cases with "type 0 and type 6" as one; P5 made them two tests (H10, H11), so B has 14 tests and A-C has 24.
- H17 (N = 1024) and H23 (N = 200) need long values. To keep every expected line a short literal a reviewer can check, the long value is overwritten (H17) or deleted (H23) later in the same transaction; the final state holds only a short value. The long record must still be parsed exactly (its N, v and checksum), or the frames after it shift and the outcome changes.
- H22 (txn FF FF FF FF) ends with ABORT, not COMMIT, so it does not repeat P6 group F's "id 4294967295 end to end" case.

## Code vs test disagreements

- None. There is no pilot code: work/pilot/smoke/gemini_run_1/WalRecovery.java is empty (HTTP 429). The only run was the throwing stub (work/scratch/stub): 27/27 FAIL with THROW java.lang.UnsupportedOperationException, which only shows that the tests compile and run.

## Checks done in P5 (arithmetic only, G3)

- work/scratch/p5_fletcher.py: Fletcher-16 traces for the short records quoted in the derivations, plus s2 s1 of the 3 long PUTs. Encoding/arithmetic only.
- work/scratch/p5_inputs/DumpInputs.java: prints L and the hex of each hidden-test input (no decoding). Every L and every quoted byte in the derivations matched.
