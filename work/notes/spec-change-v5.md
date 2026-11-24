# PROMPT.md change v4 → v5: L4 snapshot reads, L5 stated scale, L6 ranges (P8 continued)

Date: 2026-11-24. Reason: rounds 2-3 (Gemini 4 Carbon) passed every test on v3 and v4; differential fuzzing
found no bug. The user requires a task that Gemini fails; design pilots in work/notes/pilot-round4-5.md.
Status: DRAFT v5, 466 lines. Spec audit: work/notes/spec-audit-v5.md (3 M, 10 L; all applied but V13).

## 1. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | READ = type 9: txn, k, key, f (0 absent / 1 present), and for f = 1 v + value. N = 6 + k or 8 + k + v. | A point read whose expectation is checked. |
| D2 | View of a key (R19d): the last write of the key in the transaction's write list (PUT present, DELETE absent), else the transaction's snapshot. The current state is never used. | Snapshot isolation: the classic trap is reading the current state. |
| D3 | Snapshot = the state as it is at BEGIN (§4, R20), unchanged by later commits. Wording avoids "copy" (audit V3). | Defines the snapshot without prescribing a data structure. |
| D4 | Read set (§4, R23d): keys of all READ records; ROLLBACK never shrinks it. | Stated outright; a natural mistake is to roll it back. |
| D5 | R22 conflict: T is aborted at COMMIT (not an error) when some U committed by R22, whose COMMIT lies after T's BEGIN and before T's COMMIT, has a written key in T's read set or inside one of T's read ranges. Written keys = keys of U's write list when committed (rolled-back writes excluded, DELETE of a missing key included). Conflict-aborted U never count. Only reads count, so logs without READ/RANGE behave as in v4. | Optimistic concurrency with backward validation; read-set validation keeps every v4 test and Example 1 unchanged (a write-write rule would not). |
| D6 | RANGE = type 10: txn, k1, lo, k2, hi, count (u32). N = 10 + k1 + k2 ≤ 520. R19e: n = number of keys lo ≤ K ≤ hi whose view is present; lo > hi gives n = 0; mismatch → RANGE_MISMATCH. lo and hi are keys for R15. | Range reads over a snapshot; phantom protection via read ranges. |
| D7 | Key order (§4): byte by byte unsigned, prefix shorter first (= String.compareTo on the result's keys). | Needed for ranges; one order only. |
| D8 | §2: logs up to 16777216 bytes, 5 s per call, 512 MiB heap; the large tests' shapes are stated (state up to 200000 keys; one transaction open while hundreds of thousands commit; hundreds of thousands of READ/RANGE records in one transaction, ranges covering most keys). No algorithm is named. | L5. A stated, checkable requirement; the shapes make it fair (audit V3) without giving the design away. |
| D9 | New codes appended: READ_MISMATCH, RANGE_MISMATCH. New rules R19d, R19e, R23d, R23e; no renumbering. Check order ends R19, R19a, R19b, R19c, R19d, R19e. P4: TXN_NOT_OPEN before READ_MISMATCH / RANGE_MISMATCH. | Same scheme as v3/v4. |
| D10 | No worked example for READ/RANGE (audit V13 declined): the rules decide every case (audit: 32 inputs walked, one outcome each); the examples already show the format. | Keeps the text the single source; examples are not required for fairness. |

## 2. Tests

- H09, H11, H20 moved to type 11 (types 9 and 10 are now valid): 0B 00 04 00 00 00 01 → checksum 62 10 by hand.
- Group N (H124-H141) READ/snapshot/read conflicts; O (H142-H156) RANGE/key order/phantoms; P (H157-H162) scale,
  each ending with an error at an offset that is a sum of record sizes (derivation by induction over the loop).
- Harness: LogBuilder.readAbsent/read/range (HarnessCheck literals traced by hand); TestMain `only ID`; javatest
  re-runs each test after the first TIMEOUT alone in a fresh JVM (a timed-out call keeps running); per-process
  build dir for run.py --test.
