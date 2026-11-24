# P8 design pilots after round 3 (2026-11-24): differential fuzzing, L4, L5, L6

User decision after round 3 (0/5 failing on v4): "Test must be failed" — the deliverable needs a task that
Gemini fails, so P8 continues past its 3-round limit. Fairness rule kept: a counted failure must be a
clearly stated rule (P8 step 2 (a)). All runs below are design work (work/pilot), never results.

## 1. Differential fuzzing of v4 (no spec change)
work/scratch/fuzz: gen.py (encode-only random structured logs), FuzzMain.java (runs a solution on hex lines
and prints the canonical line). 20000 + 100000 logs on the 5 round-3 solutions: 0 disagreements. 40653 logs
without type-8 records on all 11 Carbon solutions (smoke, round 2, round 3): 0 disagreements. Conclusion:
Carbon's v4 code is consistent (very likely correct); more tests of the same kind would not make it fail.

## 2. L4 snapshot reads + read conflicts (draft; pilot round4_v5draft, 5 attempts)
READ (type 9) checked against the transaction's snapshot + own writes (R19d, READ_MISMATCH); COMMIT
becomes an abort when a key it READ was written by a transaction committed after its BEGIN (R22). First
draft used write-write conflicts, changed to read-set validation so that Example 1 and every v4 test keep
their outcome. Result: 5/5 pass the 128 existing tests; 50000 random + 30000 guided logs (Guide.java uses
one solution only to choose READ expectations that pass; no expected value is taken from it): 0
disagreements. Carbon implements the semantics correctly.
Observation: 4 of 5 copy the whole state map at every BEGIN and scan the whole commit history at every
COMMIT (O(n^2)); only attempt 2 keeps per-key versions. Stress logs A/B (15 MB): 4/5 > 20 s, attempt 2 1.3 s.

## 3. L5 stated input limit (round4_v5, 5 attempts)
§2: logs up to 16 MiB, 5 s per call, 512 MiB heap, large tests announced. With the limit stated, all 5
attempts became efficient on A-D (< 1 s), but the efficient designs disagree on 3932/30000 guided logs:
attempt 1 has a snapshot-visibility bug (a READ in a txn that began before another txn's COMMIT sees the
committed value). 1/5 failing.

## 4. L6 RANGE counts + phantom conflicts (round5_v5range, 5 attempts)
RANGE (type 10) counts keys lo <= K <= hi present in the view (R19e, RANGE_MISMATCH); COMMIT also
conflicts when a later-committed txn wrote a key inside one of its read ranges. Results:
- attempt 3: does not compile (it left out the required Result/Code/WalException types of §3).
- attempts 1 and 5: phantom bug — they ignore a writer that committed after T's BEGIN but before T's RANGE
  record (hand-derived; now hidden test H151).
- attempts 1, 2, 4, 5: stress G (one txn with 200000 read ranges commits after 250000 commits) > 15 s;
  each compares every committed key with every read range at that COMMIT (now hidden test H162).
- E/F (range counts over a 100k-key snapshot, snapshot with growing state): all 4 efficient and correct.
=> 5/5 attempts would fail at least one hidden test for a stated reason. Adopted as PROMPT.md v5.
Later, on the full v5 suite (167 tests), attempts 1, 2, 4, 5 pass 160-161 hidden tests and fail exactly
H162 (all four) and H151 (1 and 5) — independent agreement with the hand-derived lines elsewhere.

Note on test selection: H151 and H162 were written after seeing these pilot failures. They test stated
rules (R22 wording; §2 limits and test shapes) and are hand-derived; the official run uses fresh attempts.

## 5. Round 6: fresh pilot on the audited v5 (round6_v5, 5 attempts; attempts 2-5 resumed with --first 2)
All 5 attempts pass every test (public 5/5, hidden 173/173). Output 39k-49k tokens each (round 5: ~12k).
Guided differential fuzz (30000 logs, Guide2 with RANGE): 0 disagreements. Stress A-G: all 5 in 0.4-1.2 s.
Memory probe inside the stated shapes (one or three long-lived snapshots, 200000-key state rewritten by
~300000 commits, then range counts; 15-16 MB, -Xmx512m): all 5 OK in 1.2-1.7 s.
=> 0/5 failing. The audit's fairness fixes (V3: the shapes of the large tests stated in §2; V4: the exact
conflict window in R22) removed exactly the two failure modes seen in round 5 (H162 timeouts, H151 phantom
bug); with them stated, Carbon writes persistent/versioned structures and is correct and fast.
Freeze condition not met; PROMPT.md stays DRAFT v5.
