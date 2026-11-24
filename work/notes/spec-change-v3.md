# PROMPT.md change v2 → v3: L1 write limit and L2 savepoints (P8 hardening)

Date: 2026-11-24. Reason: pilot round 1 (Gemini web app, "Hydrogen"), attempt 1 passed every test (public 3/3,
hidden 69/69). The user chose to add two hardening levers from PLAN.md §4.7 at once: L1 and L2.
Status: PROMPT.md DRAFT v3 (audited: wording fixes V1-V10, see spec-audit.md "v3 audit"). PROMPT.md is 401 lines (was 346).

## 1. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | MAX_WRITES = 8 per transaction; a PUT or DELETE when the write list already holds 8 writes → TXN_TOO_LARGE (R19a). | PLAN.md §4.7 L1. |
| D2 | L1 counts the writes **currently in the write list**, not the PUT/DELETE records seen so far. A ROLLBACK that removes writes frees room. Stated outright in R19a and in §10 item 10. | Intended trap; it has to be explicit to be fair. |
| D3 | Every PUT and every DELETE in the list is one write, also when it repeats a key; a DELETE of a missing key counts too. | Makes the count a plain list length; no doubt about repeats. |
| D4 | SAVEPOINT and ROLLBACK are not writes and never count for L1; they are allowed while the list holds 8 writes (§10 item 11). | Recommended in the brief; keeps L1 about writes only. |
| D5 | SAVEPOINT = type 6, ROLLBACK = type 7, payload txn (u32) + name (u8), N = 5, record size 10. Phase 1 (R7) now accepts types 1 to 7. | PLAN.md §4.7 L2. |
| D6 | Name bytes 0x21–0x7E are valid (NAME_BYTE_MIN/MAX, same range as key bytes); any other name byte → new code BAD_NAME (R16a). Not BAD_KEY, and not "any byte valid". | A separate code keeps the error table unambiguous (a name is not a key). R16a is a field check, so it adds a field-before-state ordering point like T11. |
| D7 | R16a is a field check placed after R16 and before R17: R13 (N = 5), R14 (txn ≠ 0), R16a (name), then R19 (open). | Field checks before transaction checks, as for all other types. |
| D8 | R19 (TXN_NOT_OPEN) now also covers SAVEPOINT and ROLLBACK. | A savepoint belongs to an open transaction. |
| D9 | Order of the new transaction checks: R19 → R19a (PUT/DELETE) and R19 → R19b (ROLLBACK). R19a and R19b never apply to the same record. | Brief: "TXN_TOO_LARGE after the open check, then NO_SAVEPOINT". |
| D10 | A savepoint has a name and a position = the number of writes in the write list when it was set (R23a). | Brief: "records the current position". |
| D11 | SAVEPOINT with a name the transaction already has moves it: new position, and it now counts as set by this record. Moving changes no other savepoint. | Brief; the "set by" record defines "later" for D13. |
| D12 | ROLLBACK keeps the first n writes (n = the savepoint's position) and removes the rest; removed writes are never applied. The transaction stays open; the state does not change. | Brief. The text also notes the list always holds at least n writes (an invariant of these rules, so the action is always defined). |
| D13 | ROLLBACK removes every other savepoint of the transaction that was set by a SAVEPOINT record at a **larger offset** than the record that set the named one. "Later" is by the offset of the setting record, not by position (two savepoints can share a position). The named savepoint stays (same position) and can be named again. | Brief; offset order makes moved savepoints and equal positions unambiguous. |
| D14 | ROLLBACK to a name the transaction has no savepoint for → new code NO_SAVEPOINT (R19b). This includes a savepoint removed by an earlier ROLLBACK, and a name that only another transaction has. | Brief; savepoints are per transaction (§10 item 14). |
| D15 | COMMIT and ABORT discard the transaction's savepoints (R22, R23). BEGIN starts with no savepoints (R20). | Brief. Ids cannot be reused (R17), so this is only for completeness. |
| D16 | No limit on the number of savepoints (at most 94 names exist per transaction anyway). | Nothing to check; no extra code. |
| D17 | New Code constants appended at the end of the enum: TXN_TOO_LARGE (ordinal 11), BAD_NAME (12), NO_SAVEPOINT (13). Old ordinals 0–10 unchanged. | Brief. |
| D18 | No rule was renumbered. New rules use letter suffixes: R16a, R19a, R19b, R23a, R23b. | 69 hidden tests cite rule numbers; suffixes keep every old number and keep §7.3 in reading order. |

## 2. Rule numbers

Renumbering: **none**. Mapping old → new is the identity for R1–R27 and P1–P5.

New rules: R16a (name check, BAD_NAME), R19a (write limit, TXN_TOO_LARGE), R19b (savepoint check, NO_SAVEPOINT),
R23a (SAVEPOINT action), R23b (ROLLBACK action).

Changed rules (same number, new text):
- R7: valid types are 1 to 7 (was 1 to 5).
- R13: new layout line "SAVEPOINT, ROLLBACK: N = 5".
- R19: also applies to SAVEPOINT and ROLLBACK.
- R20: the new transaction also has no savepoints.
- R22, R23: the transaction's savepoints are discarded.
- §7.3 intro: check order and the field/transaction split now R13–R16a / R17–R19b; actions R20–R23b.
- P2: the list of phase-2 errors adds BAD_NAME, TXN_TOO_LARGE, NO_SAVEPOINT.
- P4: new order; adds "TXN_NOT_OPEN (R19) before TXN_TOO_LARGE (R19a)" and "TXN_NOT_OPEN (R19) before NO_SAVEPOINT (R19b)".

New phase-2 check order (P4): **R13, R14, R15, R16, R16a, R17, R18, R19, R19a, R19b**, then the action.
Per type: BEGIN R13, R14, R17, R18 → R20. PUT R13, R14, R15, R16, R19, R19a → R21. DELETE R13, R14, R15, R19,
R19a → R21. COMMIT/ABORT R13, R14, R19 → R22/R23. SAVEPOINT R13, R14, R16a, R19 → R23a. ROLLBACK R13, R14, R16a,
R19, R19b → R23b.

## 3. Changes by section of PROMPT.md

- §1: one sentence on savepoints and the 8-write limit.
- §3: enum Code gets `TXN_TOO_LARGE, BAD_NAME, NO_SAVEPOINT` at the end. Skeleton compiles (javac --release 21,
  work/scratch/skeleton/WalRecovery.java); work/scratch/stub/WalRecovery.java is the same text.
- §4: new definition: write list, write, savepoint (name and position).
- §5.3: rows for types 6 and 7; `name` field; payload positions per type.
- §6: MAX_WRITES = 8; NAME_BYTE_MIN, NAME_BYTE_MAX = 0x21, 0x7E.
- §7: R7, R13, R16a, R19, R19a, R19b, R20, R22, R23, R23a, R23b (see section 2).
- §8: P2, P4.
- §9: UNKNOWN_TYPE / RECORD_TOO_LARGE rows say 1 to 7; new rows BAD_NAME, TXN_TOO_LARGE, NO_SAVEPOINT; TXN_NOT_OPEN
  row lists SAVEPOINT and ROLLBACK.
- §10: items 10–15 (exactly 8 writes; room freed by ROLLBACK; SAVEPOINT/ROLLBACK at 8 writes; reused name moves;
  ROLLBACK removing nothing and repeated ROLLBACK to one savepoint; same name in two transactions; unused savepoint).
- §11: Example 4 (one savepoint, one rollback, then commit). The tricky parts (moved savepoints, ROLLBACK freeing
  L1 room, later savepoints removed, check order) are rules only, not examples.

Example 4 expected line (hand derivation in work/notes/examples.md):
`OK state={"a"="1", "c"="3"} committed=[1] aborted=[] incomplete=[] validLength=95 tornTail=false`

## 4. Existing hidden tests whose outcome might change (for a later session; HiddenTests.java not edited)

Method (mechanical, no outcome computed): `work/scratch/p8h/DumpAll.java` prints every test input as hex
(`inputs.txt`); `work/scratch/p8h/scan_frames.py inputs.txt` walks frame starts from offset 4 using only N
(next = p + 5 + N, no checks of any kind) and reports (a) every frame start with type byte 6 or 7 and (b) every
txn id with more than 8 type-2/3 frames. Cross-check: grep of HiddenTests.java for `record(6`, `record(7`,
`frame(6`, `frame(7` and raw `.bytes(` calls found the same two records and no other type 6/7 frame start.

| Test | Why it might change | Old expected |
|---|---|---|
| H11 frame_type_six_first_record | frame at 4 has type 6 (N = 4). Type 6 is now SAVEPOINT, so R7 no longer throws there; the record goes on to R8–R11 and then phase 2. | `ERR UNKNOWN_TYPE@4` |
| H20 frame_last_record_unknown_type_bad_checksum_is_error | frame at 13 has type 7 (N = 4, stored checksum 00 00, ends at L). Type 7 is now ROLLBACK, so R7 no longer throws; R10 is reached. The test's purpose (R7 before R10 on the last record) is lost. | `ERR UNKNOWN_TYPE@13` |

Criterion (b): no test has more than 8 PUT/DELETE frames for one txn id (the most is 4, in H51). Public tests
P01–P03: no type 6/7 frame, at most 2 writes per txn; unchanged.

Suggested fix for the later session (it must re-derive by hand): use type 8 (the new "one above the highest
type") in H11 and H20 to keep their intent, or keep the bytes and re-derive the new outcome.

Stale wording only (outcome not affected, a later session may update the comments):
- HiddenTests.java header lines 5, 13–14: "R1-R27, P1-P5", "R13-R19", "R20-R23".
- Lines 147, 162 (H09, H10): "R7 type ... is not 1..5" (still not 1..7, so the outcome holds).
- Lines 175, 340 (H11, H20): see the table above.
- Lines 1077–1078 (H56), 1315–1316 (H68), 1332–1333 (H69): quotes of the old P4 text "(R13 to R16) ... (R17 to R19)"; the new P4
  says "(R13 to R16a) ... (R17 to R19b)". The deciding statement (field checks before transaction checks) holds.
- PublicTests.java P01 comment "type 1-5", "R13-R19": still true of those records.

## 5. Traps the new rules set (for the test-writing session; PLAN.md §4.7 asks for at least 4 tests per lever)

- T18 R19a counts the current list: 8 writes ok, the 9th → TXN_TOO_LARGE; after a ROLLBACK removes writes, more
  PUT/DELETE records are ok; DELETE counts like PUT; SAVEPOINT/ROLLBACK at 8 writes are ok.
- T19 order: BAD_KEY/BAD_VALUE (field) before TXN_TOO_LARGE; TXN_NOT_OPEN before TXN_TOO_LARGE.
- T20 a reused name moves the savepoint (new position, new setting record); moving removes nothing else.
- T21 ROLLBACK removes savepoints set later (by offset of the setting record), so a later ROLLBACK to one of them
  → NO_SAVEPOINT; the named savepoint stays and can be named again.
- T22 equal positions: two savepoints set with no write between them; "later" is by setting record, not position;
  a moved savepoint is "later" than one set between its old and new setting.
- T23 savepoints are per transaction: the same name in another transaction → NO_SAVEPOINT.
- T24 BAD_NAME at 0x20 and 0x7F, ok at 0x21 and 0x7E; BAD_NAME before TXN_NOT_OPEN; SAVEPOINT/ROLLBACK with
  N = 4 or 6 → MALFORMED_PAYLOAD; txn 0 → BAD_TXN_ID; TXN_NOT_OPEN before NO_SAVEPOINT.
- T25 types 6 and 7 valid in phase 1, type 8 → UNKNOWN_TYPE; a SAVEPOINT/ROLLBACK in a torn tail is ignored.
- T26 ROLLBACK then ABORT (nothing applied); ROLLBACK with no later COMMIT (incomplete); SAVEPOINT/ROLLBACK
  never appear in committed/aborted.

Harness: `LogBuilder.savepoint(long txn, int name)` and `LogBuilder.rollback(long txn, int name)` write correct
records (true N = 5, correct checksum); `name` may be any value 0–255, so BAD_NAME inputs can be built. Checked in
runner/selfcheck/HarnessCheck.java against hand-computed literals (savepoint_record, rollback_record) and via
Example 4 byte for byte.

## 6. Checks run

- Skeleton from PROMPT.md §3 compiles with javac --release 21 (work/scratch/skeleton); stub is identical.
- work/scratch/check_prompt_hex.py: ALL_HEX_MATCH (Examples 1–4 against encode_examples.py, encode only).
- Example 4 checksums re-computed by hand (examples.md §0.6); outcome derived by hand (examples.md "Example 4").
- python3 runner/selftest.py: 10/10 OK (Example 4 hex = LogBuilder output = P04 input, byte for byte).
- python3 runner/run.py --test ../work/scratch/stub/WalRecovery.java: public 0/4, hidden 0/69, all 73 FAIL with
  THROW java.lang.UnsupportedOperationException (compile and run ok).

## Affected by v3 audit

The v3 audit (work/notes/spec-audit.md, "v3 audit", findings V1-V10) made wording fixes only. No rule now gives a
different outcome for any existing test: **none**. (H11 and H20 remain affected by v3 itself, see section 4.)
