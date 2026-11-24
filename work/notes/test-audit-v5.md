# Test audit v5 (H09, H11, H20, H124-H162)

Independent audit of 42 hidden tests against wal-recovery/task/PROMPT.md draft v5 (working tree,
uncommitted v5 diff) and the LogBuilder encoding. Report only: no test, harness or PROMPT.md file
was changed, and nothing was committed. G3 kept (no recover() logic written anywhere), and so was G4
(all outcomes derived by hand).

## Method

1. `work/scratch/p8v5t/strip_v5.py` (strip_v4.py with the v5 scope) printed only the id and the
   input-building code of each in-scope test, plus the non-test code of the file (the `cases()`
   scaffolding and the group-P builder methods `key7`, `base8`, `scale*`), with every comment,
   test name and expected string removed. Output: `work/scratch/p8v5t/stripped.txt`.
2. Every outcome was derived by hand from PROMPT.md v5 and written to
   `work/notes/test-audit-v5-blind.md` before any comment or expected line was opened. For the
   scale tests, I derived the outcome from the loop structure (every block valid by the same
   argument; final offset = sum of record sizes).
3. L and record offsets were then confirmed with `work/scratch/p8v5t/Frames.java` (copy of
   p8l3/Frames.java, splits by N only; output `work/scratch/p8v5t/frames.txt`). All 42 matched the
   hand arithmetic, including the 6 scale logs (L = 15000028, 14225034, 15700028, 10925042,
   11162542, 12900034; final records at 15000013, 14225013, 15700013, 10925013, 11162513, 12900022).
4. Then the test source was opened and compared. Quotes were checked with
   `work/scratch/p8v5t/quotes_v5.py` plus a manual `grep -F` for quotes the script mis-paired.

## Per-test result

| Test | Blind (mine) | File expected | Agree | Notes on the derivation comment |
|---|---|---|---|---|
| H09 | ERR UNKNOWN_TYPE@13 | same | yes | correct |
| H11 | ERR UNKNOWN_TYPE@4 | same | yes | checksum trace 62 10 is right (s1 ..16, s2 ..98) |
| H20 | ERR UNKNOWN_TYPE@13 | same | yes | correct; the §7.2 quote is elided with "..." but each piece is verbatim |
| H124 | OK {"a"="1"} [1] [] [] 51 false | same | yes | correct |
| H125 | OK {"a"="2"} [2] [1] [] 66 false | same | yes | correct |
| H126 | ERR READ_MISMATCH@45 | same | yes | correct |
| H127 | OK {} [1, 2] [] [] 77 false | same | yes | correct |
| H128 | OK {"a"="1"} [1, 2] [] [] 103 false | same | yes | correct |
| H129 | OK {"a"="2"} [2] [1] [] 80 false | same | yes | correct |
| H130 | OK {"a"="1"} [2, 1] [] [] 68 false | same | yes | correct |
| H131 | OK {"a"="2"} [2, 3] [1] [] 110 false | same | yes | correct |
| H132 | OK {"a"="2"} [2, 1] [] [] 69 false | same | yes | correct |
| H133 | OK {"b"="2"} [2, 1] [] [] 100 false | same | yes | correct |
| H134 | OK {"a"="2"} [2] [1] [] 86 false | same | yes | correct |
| H135 | OK {} [2] [1] [] 63 false | same | yes | correct |
| H136 | ERR MALFORMED_PAYLOAD@13 | same | yes | correct |
| H137 | ERR MALFORMED_PAYLOAD@13 | same | yes | correct |
| H138 | ERR TXN_NOT_OPEN@13 | same | yes | the aside "(Its view would be absent, so R19d would also fail.)" is loose (a txn that is not open has no view), but harmless |
| H139 | ERR BAD_VALUE@13 | same | yes | correct |
| H140 | ERR BAD_KEY@13 | same | yes | correct |
| H141 | OK {"a"="1"} [1] [] [2] 45 true | same | yes | correct |
| H142 | OK {"a"="1", "c"="1", "d"="1"} [1, 2] [] [] 141 false | same | yes | correct |
| H143 | OK {"a"="1"} [2] [1] [] 71 false | same | yes | correct |
| H144 | ERR RANGE_MISMATCH@45 | same | yes | correct |
| H145 | OK {"b"="1"} [1, 2] [] [] 71 false | same | yes | correct |
| H146 | ERR RANGE_MISMATCH@45 | same | yes | correct |
| H147 | OK {"a"="1", "ab"="1", "b"="1"} [1, 2] [] [] 137 false | same | yes | correct (N values 10, 13, 14 and key order a < aa < ab < az < b are right) |
| H148 | OK {"b"="1"} [2] [1] [] 71 false | same | yes | correct |
| H149 | OK {"d"="1"} [2, 1] [] [] 71 false | same | yes | correct |
| H150 | OK {"b"="1"} [2, 1] [] [] 71 false | same | yes | correct |
| H151 | OK {"b"="1"} [2] [1] [] 71 false | same | yes | correct |
| H152 | ERR MALFORMED_PAYLOAD@13 | same | yes | cosmetic: "N = 10+1+1 = 12? 11 != 12" has a stray "?" |
| H153 | ERR BAD_KEY@13 | same | yes | correct |
| H154 | ERR BAD_KEY@13 | same | yes | correct |
| H155 | ERR TXN_NOT_OPEN@13 | same | yes | correct |
| H156 | OK {} [1] [] [] 76 false | same | yes | correct |
| H157 | ERR READ_MISMATCH@15000013 | same | yes | correct ("N <= 10" is right: largest N is the READ f=1 k=1 v=1) |
| H158 | ERR READ_MISMATCH@14225013 | same | yes | correct |
| H159 | ERR READ_MISMATCH@15700013 | same | yes | correct |
| H160 | ERR RANGE_MISMATCH@10925013 | same | yes | correct |
| H161 | ERR RANGE_MISMATCH@11162513 | same | yes | correct |
| H162 | ERR TXN_NOT_OPEN@12900022 | same | yes | correct |

(OK rows are abbreviated as state, committed, aborted, incomplete, validLength, tornTail; the blind
file has the full canonical lines.)

**Totals: 42 agree, 0 disagree, 0 derivation errors** (two cosmetic nits: H138 aside, H152 "12?").

## Quotes and cited rules

- All quoted PROMPT.md text in the in-scope comments is verbatim in v5 (H20 §7.2 with an ellipsis,
  H125, H130, H131, H133, H134, H136, H138 P4, H139, H145, H147, H151, H153, H155 P4).
- Every cited rule exists in v5 and takes part in the outcome. Section-10 references are right:
  item 18 (H130), item 1 (H135), item 21 (H145). The group N header's "items 3, 18-20" is fine
  (but item 20 is not actually exercised, see below).
- Cosmetic inconsistency only: the `// Rules:` line and the TestCase rules argument differ in
  order (H132, H142, H150, H151, H162) and the argument leaves out "section N" references
  (H130, H134, H135, H145, H147, H157-H162). This matches the file's existing convention and does
  not affect anything.
- No test depends on behaviour PROMPT.md does not state. H151 (a phantom committed before the RANGE
  record still conflicts) follows from R22's literal text ("COMMIT record lies after T's BEGIN
  record", with read ranges taken at T's COMMIT, §4 "processed so far"). That is clear, although
  it is the least intuitive rule in the group.

## Coverage gaps (v5 clauses not exercised by H124-H162)

READ and RANGE appear in no other test (H01-H123 and the public tests have none), so these are
gaps for the whole suite.

R13 / R14 / R15 field checks
- READ: N < 6; N < 6 + k; f = 0 with extra bytes (N > 6 + k); f = 1 with N < 8 + k (no v field).
- RANGE: N < 6; N < 6 + k1.
- R14 txn 0 on READ and on RANGE (BAD_TXN_ID never tested for types 9/10).
- R15: READ with k = 0; READ or RANGE with k, k1 or k2 = 33 (and 32 accepted); RANGE lo with a bad
  byte (only hi is tested); RANGE k2 = 0.

R19 / R19d / R19e
- READ f = 1 with empty value "": present-empty vs absent (snapshot or own PUT of ""). Not tested.
- READ f = 0 of a key that is present (small test). Only f = 1 mismatches are tested.
- Own DELETE then PUT of the same key (last write is a PUT); two PUTs of one key, then READ.
- RANGE "each key counts once, however many writes of it the write list holds" (several PUTs of
  one key in range): not tested.
- READ/RANGE for a txn that already committed or aborted (only "never begun" is tested; H162 has a
  committed txn at scale).
- The snapshot excludes the uncommitted writes of another open txn written before T's BEGIN.
- RANGE count large u32 values (>= 65536 or >= 2^31). A u16 or signed read could pass by
  accident, for example count 65537 with n = 1.

R22 conflicts
- **U began before T** (U's BEGIN earlier than T's BEGIN; U's COMMIT after T's BEGIN). In every
  conflict test U begins after T. An implementation that compares begin order would pass all of them.
- **T's reads plus a key written by both T and U**: "Only T's read set and read ranges count here,
  not T's own writes" is tested only with transactions that have no reads (H130).
- U committed after T's COMMIT (reader first, writer later) does not count.
- Three or more txns where the conflicting U is not the most recent committer.
- U whose written keys hit both the read set and a range at once (low value).
- RANGE read ranges survive ROLLBACK (§4, R23b). Only the read set is tested (H134).

Interplay
- §10 item 20 / R19a: READ or RANGE while the write list holds 8 writes, and READ/RANGE not counted
  as writes before the 8th write. Not tested anywhere.
- CHECKPOINT after a conflict abort (R22 "In both cases the transaction is no longer open").
- P2 with the new codes: an earlier READ_MISMATCH/RANGE_MISMATCH vs a later CHECKSUM_MISMATCH.
- P5 second sentence with the new codes: READ_MISMATCH in an intact record before a torn tail.
- TOO_MANY_OPEN / R18 in relation to READ-only txns (low value).

## Proposed tests (most important first, at most 6)

Offsets and L were hand-computed and then confirmed by `work/scratch/p8v5t/ProposedFrames.java`
(splits by N only). Ids are placeholders (X1-X6).

```java
        // X1 conflict_writer_began_before_reader
        // Rules: R20, R19d, R22
        // Derivation:
        //   1. Header ok. BEGIN 2 @4 (ends 13), PUT 2 a=2 @13 (ends 27), BEGIN 1 @27 (ends 36), READ
        //      1 a absent @36 (ends 48), COMMIT 2 @48 (ends 57), COMMIT 1 @57 (ends 66). L = 66. All
        //      intact; p = 66 = L (R5).
        //   2. Phase 2: BEGIN 2; PUT 2 -> list2 [a=2], state still {}. BEGIN 1: snapshot = state {}
        //      (txn 2's write is not applied yet, R21). READ 1 a: no own write -> snapshot -> absent
        //      = expected (f = 0). COMMIT 2: no READ/RANGE -> committed, state {a=2}.
        //   3. COMMIT 1 @57: U = txn 2 was committed by R22, its COMMIT @48 "lies after T's BEGIN
        //      record and before T's COMMIT record" (36 < 48 < 57), and its written keys {a} include
        //      a in read set1 -> conflict -> aborted. U's BEGIN @4 lies before T's BEGIN; R22 does
        //      not look at U's BEGIN.
        // Expected: OK state={"a"="2"} committed=[2] aborted=[1] incomplete=[] validLength=66 tornTail=false
        c.add(TestCase.of("X1", "conflict_writer_began_before_reader", "R19d,R20,R22",
                LogBuilder.wal().begin(2).put(2, "a", "2").begin(1).readAbsent(1, "a").commit(2).commit(1).build(),
                "OK state={\"a\"=\"2\"} committed=[2] aborted=[1] incomplete=[] validLength=66 tornTail=false"));

        // X2 read_empty_value_is_present_not_absent
        // Rules: R19d, section 10 item 2
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, PUT 1 a="" @13 (N = 7+1+0 = 8, ends 26), COMMIT 1 @26 (ends
        //      35), BEGIN 2 @35 (ends 44), READ 2 a = "" @44 (f = 1, v = 0, N = 8+1+0 = 9, ends 58),
        //      READ 2 a absent @58 (ends 70), COMMIT 2 @70 (ends 79). L = 79. All intact.
        //   2. Phase 2: txn 1 commits a = "" -> state {a=""}. BEGIN 2: snapshot {a=""}.
        //   3. READ @44: no own write -> snapshot: present "" ; expected present "" -> match ("the
        //      empty value `""` is present").
        //   4. READ @58: view present "", expected absent (f = 0) -> throw READ_MISMATCH at 58.
        // Expected: ERR READ_MISMATCH@58
        c.add(TestCase.of("X2", "read_empty_value_is_present_not_absent", "R19d",
                LogBuilder.wal().begin(1).put(1, "a", "").commit(1).begin(2).read(2, "a", "").readAbsent(2, "a").commit(2).build(),
                "ERR READ_MISMATCH@58"));

        // X3 read_range_at_write_limit_last_write_counts_once
        // Rules: R19a, R19d, R19e, R22, section 10 item 20
        // Derivation:
        //   1. Header ok. BEGIN 1 @4 (ends 13). PUT 1 a=1 @13, PUT 1 a=2 @27, DELETE 1 a @41 (ends
        //      52), PUT 1 a=3 @52, PUT 1 b=1 @66, PUT 1 b=2 @80, PUT 1 c=1 @94 (ends 108), READ 1 a =
        //      "3" @108 (ends 123), RANGE 1 [a, c] 3 @123 (ends 140), DELETE 1 c @140 (ends 151),
        //      RANGE 1 [a, c] 2 @151 (ends 168), READ 1 c absent @168 (ends 180), COMMIT 1 @180
        //      (ends 189). L = 189. All intact.
        //   2. Phase 2: the 7 writes before @108 leave list1 with 7 writes. READ a: last write of a
        //      is PUT a=3 -> present "3", match. RANGE [a, c]: present a, b, c -> 3; "each key
        //      counts once, however many writes of it the write list holds" (a has 4, b 2) -> match.
        //   3. DELETE c @140: R19a list holds 7 < 8 (READ and RANGE are not writes) -> ok; now 8.
        //      RANGE [a, c] @151 with 8 writes (allowed): a, b present, c absent (own DELETE) -> 2,
        //      match. READ c absent @168: match.
        //   4. COMMIT 1: no other txn -> no conflict; apply the 8 writes in order -> a=3, b=2, c gone.
        // Expected: OK state={"a"="3", "b"="2"} committed=[1] aborted=[] incomplete=[] validLength=189 tornTail=false
        c.add(TestCase.of("X3", "read_range_at_write_limit_last_write_counts_once", "R19a,R19d,R19e,R22",
                LogBuilder.wal().begin(1).put(1, "a", "1").put(1, "a", "2").del(1, "a").put(1, "a", "3").put(1, "b", "1")
                        .put(1, "b", "2").put(1, "c", "1").read(1, "a", "3").range(1, "a", "c", 3).del(1, "c")
                        .range(1, "a", "c", 2).readAbsent(1, "c").commit(1).build(),
                "OK state={\"a\"=\"3\", \"b\"=\"2\"} committed=[1] aborted=[] incomplete=[] validLength=189 tornTail=false"));

        // X4 own_written_key_does_not_conflict_for_reader
        // Rules: R22
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, BEGIN 2 @13, READ 1 a absent @22 (ends 34), PUT 1 b=1 @34
        //      (ends 48), PUT 2 b=2 @48 (ends 62), COMMIT 2 @62 (ends 71), COMMIT 1 @71 (ends 80).
        //      L = 80. All intact.
        //   2. Phase 2: READ 1 a: snapshot {} -> absent, match; read set1 {a}. COMMIT 2: no reads ->
        //      committed, state {b=2}, written {b}.
        //   3. COMMIT 1: txn 2 committed after BEGIN 1, but its written keys {b} hold no key of read
        //      set1 {a} and txn 1 has no read ranges. b is in txn 1's own write list, and "Only T's
        //      read set and read ranges count here, not T's own writes" -> no conflict -> committed,
        //      b=1 replaces b=2. committed [2, 1].
        // Expected: OK state={"b"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=80 tornTail=false
        c.add(TestCase.of("X4", "own_written_key_does_not_conflict_for_reader", "R22",
                LogBuilder.wal().begin(1).begin(2).readAbsent(1, "a").put(1, "b", "1").put(2, "b", "2").commit(2).commit(1).build(),
                "OK state={\"b\"=\"1\"} committed=[2, 1] aborted=[] incomplete=[] validLength=80 tornTail=false"));

        // X5 checkpoint_after_conflict_abort_omits_txn
        // Rules: R22, R19c, R23c
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, BEGIN 2 @13, BEGIN 3 @22 (ends 31), RANGE 1 [a, c] 0 @31 (ends
        //      48), PUT 2 b=1 @48 (ends 62), COMMIT 2 @62 (ends 71), COMMIT 1 @71 (ends 80),
        //      CHECKPOINT c = 1: 3 @80 (N = 5, ends 90), COMMIT 3 @90 (ends 99). L = 99. All intact.
        //   2. Phase 2: RANGE: snapshot {} -> 0, match. COMMIT 2: committed, state {b=1}. COMMIT 1:
        //      txn 2 wrote b, a <= b <= c -> conflict -> aborted; "In both cases the transaction is
        //      no longer open".
        //   3. CHECKPOINT @80: open txns = {3} -> list [3] is right (R19c). COMMIT 3: no reads ->
        //      committed, no writes.
        // Expected: OK state={"b"="1"} committed=[2, 3] aborted=[1] incomplete=[] validLength=99 tornTail=false
        c.add(TestCase.of("X5", "checkpoint_after_conflict_abort_omits_txn", "R19c,R22,R23c",
                LogBuilder.wal().begin(1).begin(2).begin(3).range(1, "a", "c", 0).put(2, "b", "1").commit(2).commit(1)
                        .checkpoint(3).commit(3).build(),
                "OK state={\"b\"=\"1\"} committed=[2, 3] aborted=[1] incomplete=[] validLength=99 tornTail=false"));

        // X6 rollback_does_not_drop_read_range
        // Rules: R22, R23b, R23e, section 4 (read ranges)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, BEGIN 2 @13, SAVEPOINT 1 s @22 (ends 32), RANGE 1 [a, c] 0 @32
        //      (ends 49), ROLLBACK 1 s @49 (ends 59), PUT 2 b=1 @59 (ends 73), COMMIT 2 @73 (ends
        //      82), COMMIT 1 @82 (ends 91). L = 91. All intact.
        //   2. Phase 2: RANGE: snapshot {} -> 0, match; read ranges1 [(a, c)]. ROLLBACK: "a ROLLBACK
        //      does not change the read ranges". COMMIT 2: committed, written {b}, state {b=1}.
        //   3. COMMIT 1: txn 2 committed after BEGIN 1 and wrote b in [a, c] -> conflict -> aborted.
        // Expected: OK state={"b"="1"} committed=[2] aborted=[1] incomplete=[] validLength=91 tornTail=false
        c.add(TestCase.of("X6", "rollback_does_not_drop_read_range", "R22,R23b,R23e",
                LogBuilder.wal().begin(1).begin(2).savepoint(1, 0x73).range(1, "a", "c", 0).rollback(1, 0x73)
                        .put(2, "b", "1").commit(2).commit(1).build(),
                "OK state={\"b\"=\"1\"} committed=[2] aborted=[1] incomplete=[] validLength=91 tornTail=false"));
```

Quotes in X1-X6 checked against v5: "lies after T's BEGIN record and before T's COMMIT record"
(R22), "the empty value `""` is present" (R19d), "each key counts once, however many writes of it
the write list holds" (R19e), "Only T's read set and read ranges count here, not T's own writes"
(R22), "In both cases the transaction is no longer open" (R22), "a ROLLBACK does not change the
read ranges" (§4).

What each proposal catches:
- X1: compares U's BEGIN with T's BEGIN instead of U's COMMIT; puts another open txn's uncommitted
  writes into the snapshot.
- X2: treats "" as absent (fails at @44); uses getOrDefault("") so absent = "" (fails at @58 in
  the other direction, giving a different offset).
- X3: counts READ/RANGE as writes (TXN_TOO_LARGE@140); counts writes instead of keys in RANGE
  (RANGE_MISMATCH@123); uses the first write rather than the last.
- X4: counts T's own write set in the conflict check (would abort 1).
- X5: leaves a conflict-aborted txn open (CHECKPOINT_MISMATCH@80).
- X6: clears read ranges on ROLLBACK (would commit 1).

Next candidates if there is room: READ txn 0 -> BAD_TXN_ID; RANGE k2 = 33 -> BAD_KEY; READ f = 0
with one extra byte -> MALFORMED_PAYLOAD; P2 with an earlier READ_MISMATCH and a later
CHECKSUM_MISMATCH; RANGE count 65537 with n = 1 -> RANGE_MISMATCH.
