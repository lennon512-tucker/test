# PROMPT.md change v3 → v4: L3 CHECKPOINT (P8 round 3 hardening)

Date: 2026-11-24. Reason: pilot round 2 (Gemini 4 Carbon via OneProvider, work/notes/pilot-round2.md): 0 of 5
attempts failed a hidden test (all 105/105, plus the smoke attempt). Per P8 step 3 ("fewer than 4 of 5"), the
user approved adding the next lever, L3 (PLAN.md §4.7), and running round 3 (the last round P8 allows).
Status: PROMPT.md DRAFT v4, 442 lines (was 401).

## 1. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | CHECKPOINT = type 8. Payload: c (u8), then c txn ids (u32 each). N = 1 + 4c, record size 6 + 4c. c may be 0. | PLAN.md §4.7 L3. A count byte keeps the layout checkable by R13 like the other types. |
| D2 | c ≤ 255 gives N ≤ 1021 ≤ MAX_PAYLOAD, so R8 never interacts with a well-formed CHECKPOINT. No separate limit on c: a list longer than the open set (at most 4) is simply wrong (R19c). | No new constant, no new error. |
| D3 | R13: CHECKPOINT "N ≥ 1; then, with c = payload byte 0, N = 1 + 4 × c". | Same "test in order" style as PUT/DELETE; N = 0 has no c to read. |
| D4 | R14 extended: for CHECKPOINT, any listed id 0 → BAD_TXN_ID. For types 1–7 the old text, now "types 1 to 7". | Keeps "txn 0 is invalid" uniform; gives a field-before-transaction ordering point (T32) like T11/T24. |
| D5 | New R19c (transaction check, last in the order): the list must equal the open txn ids at this record in ascending numeric u32 order, element by element; otherwise CHECKPOINT_MISMATCH. Wrong order, repeats, missing ids and ids that are not open are all named as wrong. | PLAN.md §4.7: "lists the open txn ids in ascending order; it must match the real open set". Element-by-element equality leaves one outcome for every list. |
| D6 | "Ascending" is the numeric order of the u32 values (same wording as R26); ids ≥ 2^31 are large, not negative. | The spec already says all integers are unsigned (§4, R26, §7.4); a stated, fair trap (T28). |
| D7 | New §4 definition: "the open transactions at a record" = open just before that record is processed in phase 2. | Makes "open set" exact for R19c and §10 item 17. |
| D8 | R19 (TXN_NOT_OPEN) does not apply to CHECKPOINT; stated in R19c. A CHECKPOINT has no txn field and belongs to no transaction (§5.3). | Avoids two readings for a listed id that is not open (TXN_NOT_OPEN vs CHECKPOINT_MISMATCH). |
| D9 | R23c: the CHECKPOINT action changes nothing (no transaction opens/closes; no write list, savepoint or state change). R25: CHECKPOINT adds nothing to the lists. | A checkpoint is a check only. |
| D10 | New Code constant CHECKPOINT_MISMATCH appended at the end of the enum (ordinal 14). Old ordinals unchanged. | Same as D17 of v3. |
| D11 | No renumbering: new rules R19c, R23c (letter suffixes). | Existing tests cite rule numbers. |
| D12 | Example 5 added (§11): BEGIN 2, BEGIN 1, PUT, CHECKPOINT [1, 2], COMMIT 1, CHECKPOINT [2]; it shows that the list is ascending, not BEGIN order. It does not show the unsigned-order or exact-list traps. | Every lever so far got a worked example (v3 Example 4); the rule text states the traps. |

## 2. Rule numbers

Renumbering: none. New rules: R19c (CHECKPOINT_MISMATCH), R23c (CHECKPOINT action).
Changed rules (same number, new text): R7 (types 1 to 8), R13 (CHECKPOINT line), R14 (types 1 to 7; CHECKPOINT
listed ids), R25 (CHECKPOINT adds nothing), §7.3 intro (order ends with R19c; actions R20 to R23c; transaction
checks R17 to R19c), P2 (adds CHECKPOINT_MISMATCH), P4 (order ends with R19c; new CHECKPOINT pair sentence).

New phase-2 check order: R13, R14, R15, R16, R16a, R17, R18, R19, R19a, R19b, R19c. For CHECKPOINT: R13, R14,
R19c → R23c.

## 3. Changes by section of PROMPT.md

- §1: one sentence on checkpoints.
- §3: enum Code gets `CHECKPOINT_MISMATCH` at the end. Skeleton = §3 block = stub, compiles
  (work/scratch/skeleton, work/scratch/stub).
- §4: definition "The open transactions at a record".
- §5.3: row for type 8; `c` described; payload positions for CHECKPOINT; "txn is payload bytes 0 to 3 (types 1 to 7)".
- §7: R7, §7.3 intro, R13, R14, R19c, R23c, R25.
- §8: P2, P4.
- §9: UNKNOWN_TYPE / RECORD_TOO_LARGE "1 to 8"; BAD_TXN_ID row adds the CHECKPOINT case; new CHECKPOINT_MISMATCH row.
- §10: items 16 and 17.
- §11: Example 5 (bytes from work/scratch/encode_examples.py ex5, encode only; check_prompt_hex.py
  ALL_HEX_MATCH; checksum traces D3 16 and 65 10 re-added by hand; outcome by hand).

## 4. Harness and tests

- LogBuilder.checkpoint(long... ids): writes c and the ids as given (any order, repeats, 0), correct checksum.
  HarnessCheck: example5 bytes compared with PROMPT.md (selftest), literals checkpoint_empty_record
  (08 00 01 00 22 09) and checkpoint_ids_as_given (08 00 09 02 FF FF FF FF 00 00 00 00 CC 13), both by hand.
- P05 = Example 5 (bytes copied from PROMPT.md; line by hand).
- Affected existing tests: mechanical scan work/scratch/p8l3/scan_frames.py (frame walk by N only): type-8
  frame starts only in H11 and H20 → moved to type 9 (re-derived by hand, expected lines unchanged). H08/H09
  (type 9) unchanged in outcome; comments "1..7" → "1..8". Quotes of P4 and R25 updated to v4 wording.
- New group M: H102-H118 (17 tests), traps T27-T34 below.

## 5. Traps (T27-T34)

| Trap | What it catches | Tests |
|---|---|---|
| T27 | list in BEGIN/log order instead of ascending | H103, H104 |
| T28 | ids compared as signed ints | H105, H106 |
| T29 | open set taken at the wrong time (committed/aborted still counted, later BEGIN counted) | H107, H110, H116 |
| T30 | list compared as a set or as a subset (missing / repeated id) | H108, H109 |
| T31 | c = 0 rejected | H102 |
| T32 | CP field checks and their order: N = 0, c vs N, listed 0; MALFORMED → BAD_TXN_ID → MISMATCH | H111, H112, H113 |
| T33 | type 8 in phase 1 (R7/R8 apply), CP in torn tail ignored, phase 1 beats CP mismatch | H118, H114, H115 |
| T34 | CP treated as a write or changing savepoints / lists | H117 |

## 6. v4 audit (independent, 2026-11-24; work/notes/test-audit-v4.md, blind lines in test-audit-v4-blind.md)

- Tests: P05, H11, H20, H102-H118 re-derived blind from input code only: 20/20 agree, derivations and all 21
  checksum traces right, all quotes verbatim. Comment nits fixed (H104 quote, H110 citation, H113 wording).
- Spec findings, all applied (wording only; no outcome of any test changed):
  S1 (M) §10 item 4 "ids in any order" now says BEGIN records, plus "(A CHECKPOINT still lists its ids in
  ascending order, R19c.)". S2 (M, weak) §10 items 16 and 17 now say "when the list is right" / "A right list".
  S3 (L) R19c "(no BEGIN record for it has been processed yet, ...)". S4 (L) R19a: CHECKPOINT records are not
  writes. S5 (L) §1 "open at that point in the log". S6 (L) Example 5 "so R19c passes again". S7 (L) R14
  heading "Txn ids". The H75 quote of R19a updated to the new sentence.
- Coverage: tests H119-H123 added from the audit's proposals (H123 its "next in line"), each re-derived by
  hand before adding (inputs checked: L, offsets and CP checksums D4 17, 5F 0E, 8C 2D match).
