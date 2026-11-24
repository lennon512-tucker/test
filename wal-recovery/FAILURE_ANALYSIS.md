# Failure analysis

Model: `gemini-4-carbon` (Gemini 4 Carbon) via the OneProvider gateway, 5 attempts on 2026-11-24, same prompt each time (`results/prompt_sent.txt`). Details and the reason for this model are in `results/summary.json`.

| solution | public | hidden | failing hidden tests |
|---|---|---|---|
| reference (3 consecutive runs) | 5 / 5 | 173 / 173 | none |
| gemini attempt 1 | 5 / 5 | 172 / 173 | H151 |
| gemini attempt 2 | 5 / 5 | 173 / 173 | none |
| gemini attempt 3 | 5 / 5 | 173 / 173 | none |
| gemini attempt 4 | 5 / 5 | 173 / 173 | none |
| gemini attempt 5 | 5 / 5 | 172 / 173 | H151 |

**Result: 2 of 5 attempts fail at least one hidden test.** The brief asks for at least 4 of 5, so this run does not meet that threshold. There is one distinct failure, explained below.

---

## Failure 1: H151 `phantom_committed_before_range_record_still_conflicts`

### 1. The test and what it checks

Transaction 1 begins first. Transaction 2 then writes key `b` and commits. After that commit, transaction 1 records a RANGE over `a`..`c`, then commits. The test checks that transaction 2's commit still counts as a conflict for transaction 1. Transaction 2's COMMIT comes **after transaction 1's BEGIN**, so it is inside the conflict window, even though it comes **before** transaction 1's RANGE record.

Input (71 bytes, hex):

```
57 41 4C 31 01 00 04 00 00 00 01 1C 06 01 00 04 00 00 00 02 1D 07 02 00 09 00 00 00 02 01 62 00
01 31 40 A2 04 00 04 00 00 00 02 32 0A 0A 00 0C 00 00 00 01 01 61 01 63 00 00 00 00 E4 DD 04 00
04 00 00 00 01 31 09
```

### 2. The rules that decide it (quoted from task/PROMPT.md)

- R22: "T conflicts when there is another transaction U that was committed by this rule (not aborted by it), whose COMMIT record lies after T's BEGIN record and before T's COMMIT record in log order, and whose written keys at the time it was committed include a key in T's read set, or a key K with lo ≤ K ≤ hi for one of T's read ranges."
- §4: "The read ranges of a transaction are the (lo, hi) pairs of all its RANGE records processed so far (R23e)." The conflict is decided in the COMMIT action ("R22. COMMIT action. First decide whether the transaction T conflicts."), so "so far" means up to T's COMMIT.
- R22, conflict case: "If T conflicts, it is aborted: discard its writes without applying them, and append txn to `aborted`."

### 3. Gemini's result, the correct result, and the derivation

| | line |
|---|---|
| expected | `OK state={"b"="1"} committed=[2] aborted=[1] incomplete=[] validLength=71 tornTail=false` |
| Gemini (attempts 1 and 5) | `OK state={"b"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=71 tornTail=false` |

Step by step:

1. **Header.** Bytes 0–3 are `57 41 4C 31` ("WAL1", §5.1). The first record is at 4.
2. **Phase 1, frames.** Each record is type (1 byte), N (u16, big-endian), payload, then the 2 checksum bytes s2, s1 (§5.2, §5.4). The record size is 5 + N.

   | offset | record | N | size | ends | stored s2 s1 |
   |---|---|---|---|---|---|
   | 4 | BEGIN txn 1: `01 00 04 00 00 00 01` | 4 | 9 | 13 | `1C 06` |
   | 13 | BEGIN txn 2: `01 00 04 00 00 00 02` | 4 | 9 | 22 | `1D 07` |
   | 22 | PUT txn 2, k=1 `b`, v=1 `1`: `02 00 09 00 00 00 02 01 62 00 01 31` | 9 | 14 | 36 | `40 A2` |
   | 36 | COMMIT txn 2: `04 00 04 00 00 00 02` | 4 | 9 | 45 | `32 0A` |
   | 45 | RANGE txn 1, lo `a`, hi `c`, count 0: `0A 00 0C 00 00 00 01 01 61 01 63 00 00 00 00` | 12 | 17 | 62 | `E4 DD` |
   | 62 | COMMIT txn 1: `04 00 04 00 00 00 01` | 4 | 9 | 71 | `31 09` |

   The BEGIN 1 checksum is the check value given in §5.4 (`1C 06`). BEGIN 2 differs only in its last byte (02 instead of 01): s1 = 5 + 2 = 7 and s2 = 22 + 7 = 29 = `1D`. COMMIT 2 has s1 = 4 + 4 + 2 = 10 and s2 = 4 + 4 + 8 + 8 + 8 + 8 + 10 = 50 = `32`. COMMIT 1 has s1 = 9 and s2 = 49 = `31`. The two longer records:

   PUT at 22:

   | b | 02 | 00 | 09 | 00 | 00 | 00 | 02 | 01 | 62 | 00 | 01 | 31 |
   |---|---|---|---|---|---|---|---|---|---|---|---|---|
   | s1 | 2 | 2 | 11 | 11 | 11 | 11 | 13 | 14 | 112 | 112 | 113 | 162 |
   | s2 | 2 | 4 | 15 | 26 | 37 | 48 | 61 | 75 | 187 | 44 | 157 | 64 |

   s2 = 64 = `40` and s1 = 162 = `A2`, which match. (At the `62` step, s2 = 75 + 112 = 187, and at the next step 187 + 112 = 299 − 255 = 44.)

   RANGE at 45:

   | b | 0A | 00 | 0C | 00 | 00 | 00 | 01 | 01 | 61 | 01 | 63 | 00 | 00 | 00 | 00 |
   |---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
   | s1 | 10 | 10 | 22 | 22 | 22 | 22 | 23 | 24 | 121 | 122 | 221 | 221 | 221 | 221 | 221 |
   | s2 | 10 | 20 | 42 | 64 | 86 | 108 | 131 | 155 | 21 | 143 | 109 | 75 | 41 | 7 | 228 |

   s2 = 228 = `E4` and s1 = 221 = `DD`, which match.

   Every record is intact, and the last one ends at 71 = L. So R5 gives **validLength = 71, tornTail = false**.
3. **Phase 2, replay.** The state starts empty.
   - @4 BEGIN 1: txn 1 opens. Its snapshot is the state at this point, `{}` (R20).
   - @13 BEGIN 2: txn 2 opens. Its snapshot is `{}`.
   - @22 PUT 2 b=1: all checks pass (key and value bytes are in range, txn 2 is open, 0 < 8 writes). R21: txn 2's write list is [PUT b=1].
   - @36 COMMIT 2: txn 2 has no READ or RANGE records, so it cannot conflict (R22: "a transaction without READ and RANGE records never conflicts"). It commits, so the state becomes `{b=1}` and committed = [2].
   - @45 RANGE 1 [a, c], count 0. R19e counts the keys from `a` to `c` whose view is present. The view comes from txn 1's snapshot `{}` and its empty write list (R19d), so n = 0 = count and the check passes. R23e adds (a, c) to txn 1's read ranges.
   - @62 COMMIT 1. Apply R22 with T = txn 1 and U = txn 2. U was committed by R22. U's COMMIT is at 36, which is after T's BEGIN at 4 and before T's COMMIT at 62. U wrote `b`, and `a` ≤ `b` ≤ `c` is inside T's read range (a, c), which T recorded before its COMMIT. So **T conflicts**: txn 1 is aborted and aborted = [1]. Its writes (none) are discarded.
4. **Result.** state = `{"b"="1"}`, committed = [2], aborted = [1], and incomplete = [] (no transaction is still open, R26).

Gemini's answer lists txn 1 as committed. The rule says it is aborted.

### 4. Root cause in the model's code

Both attempts check for conflicts **eagerly, at the other transaction's COMMIT**. When U commits, the code looks at the read sets and read ranges that the open transactions hold at that moment and marks the conflicting ones. A RANGE (or READ) recorded after U's COMMIT is never checked against U's writes. So the code shrinks the window "after T's BEGIN record and before T's COMMIT record" to "after T's RANGE record".

- **Attempt 1** (`results/gemini_run_1/WalRecovery.java`). In the COMMIT case, lines 321–333 ("Notify remaining open transactions of written keys") loop over the open transactions and call `other.conflictsWith(...)` against their current ranges. At txn 2's COMMIT (@36), txn 1 has no ranges yet, so it is not marked. Line 306 (`if (txn.conflicted)`) later decides txn 1's COMMIT from that stale flag, and `addRange` (line 614) never looks back at earlier commits.
- **Attempt 5** (`results/gemini_run_5/WalRecovery.java`). The COMMIT case, lines 273–287, calls `openTxns[o].onOtherCommitWrite(wKey)` for each written key. `onOtherCommitWrite` (lines 656–669) tests only the ranges recorded so far. Line 270 (`if (txn.conflicted)`) then decides txn 1's COMMIT. The RANGE case (lines 349–390) adds the range with `txn.addReadRange(lo, hi)` and does not check keys that were already committed.

Attempts 2, 3 and 4 remember the keys committed since each transaction's BEGIN. Attempts 2 and 4 keep a commit log that they check at T's COMMIT. Attempt 3 keeps a per-transaction set of concurrent writes and also checks each new RANGE against it. So in all three, a RANGE recorded after U's COMMIT still sees U's writes, as R22 requires.

### 5. Category

**Misread a rule.** The model read R22's window ("after T's BEGIN record and before T's COMMIT record") as if it started at the RANGE record. This is a classic optimistic-concurrency shortcut: notify readers at write time. That shortcut is wrong for reads recorded later.

### 6. Attempts affected

**2 of 5** (attempts 1 and 5).

The gateway's added system text does not explain the split. Attempt 1 had no added prompt tokens, and attempts 2–5 each had 2,229 (`hidden_prompt_tokens_estimate` in `results/summary.json`). One failing attempt is in each group.

---

## What makes this task hard, and why the failure is not caused by unclear text

The task combines a byte-exact log format (Fletcher-16, big-endian frames, torn-tail rules) with a transactional replay of about 30 interacting rules: a fixed check order, error precedence, savepoints with offset-based removal, write limits, checkpoints, snapshot reads and phantom-range conflicts. A small slip in any one of them changes the result line.

The failure above is not a reading problem. R22 states the window explicitly by log position, in terms of the two records of T that bound it (its BEGIN and its COMMIT). §4 says read ranges are "all its RANGE records processed so far". §10 item 19 describes the same situation for a READ: a READ that passes against the snapshot after another transaction committed a newer value, followed by "Such a transaction then conflicts at its COMMIT, R22". The independent reference solution was written from PROMPT.md alone, without seeing the tests, and passes H151. So do 3 of the 5 Gemini attempts.
