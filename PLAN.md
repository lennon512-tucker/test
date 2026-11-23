# WAL Recovery Assignment: Plan and Session Prompts

One file with everything: the decisions, the task design, the roadmap, and one copy-paste prompt per Claude Code session.

- Workspace: `/root/Documents/test`
- Package (becomes the ZIP): `/root/Documents/test/wal-recovery`
- Working notes (never shipped): `/root/Documents/test/work`

## Contents
0. Read this first
1. Decisions
2. Roadmap at a glance
3. Layout, formats and ground rules
4. Task design brief
5. Test plan
6. Runner specification
7. Session prompts (P0–P14, R, H1)
8. Keeping the reference independent
9. Final acceptance checklist
10. Risks and fallbacks

---

## 0. Read this first

**How it works.** There are 16 prompts (P0–P14 and R), plus one helper (H1) that you run only if R reports a disputed test. Each prompt is one Claude Code session. Run them in order: open a new session in `/root/Documents/test`, set the model and effort shown, paste the prompt. Each session reads this file and `work/STATE.md`, does one task, checks its own work, updates `STATE.md`, and commits to git. If a session ends with an open issue, run the same prompt again in a new session; it picks up from `STATE.md`.

**Setting model and effort.** Set both before you paste a prompt. In the VS Code extension, click the model name under the prompt box (or run "Switch model…" from the command menu), pick the model, and set the Effort row in the same picker. In a terminal session, type `/model opus` or `/model sonnet`, then `/effort low`, `medium`, `high` or `xhigh`. Fable also appears in the model list. The docs don't show it doing better than Opus on this kind of work and it costs more, so the plan uses Opus 5.5 for the hard sessions.

**Claude writes the reference solution too (session R).** The updated PDF no longer says "hand-written", and Ivan confirmed that AI may write the solution. Keep his confirmation in writing, because the earlier email said AI-written solutions are disqualified automatically. What still matters is independence: the PDF wants expected values "worked out independently". So the tests are derived by hand in their own sessions, the reference is written in a separate session from PROMPT.md alone, and neither side is ever changed just to match the other (§8).

**Sending.** Claude can't email the client. P14 builds the ZIP and drafts your reply; you attach the ZIP and send it.

**Be open about AI help (recommended).** The reply draft in P14 includes one line saying the package was built with Claude Code, as agreed with Ivan. It costs nothing and heads off any later question.

**Before P3 you need a Gemini API key** (Google AI Studio). Store it yourself; never paste it into a chat. P0 shows how.

---

## 1. Decisions

| Item | Choice | Why |
|---|---|---|
| Assignment | The PDF brief ("a hard coding task that an AI model fails") | You confirmed it is the latest version |
| Task language | Java 21: task, reference solution, Gemini's code | Your choice |
| Runner language | Python 3, standard library only | Nothing to install; reviewers rerun with one command |
| Test framework | Small custom Java harness, no JUnit or Maven | No dependencies; same command for every solution |
| Topic | Write-ahead log (WAL) crash recovery | In the job post's list; many interacting rules; deterministic; small inputs can be checked by hand |
| Reference solution | Written by Claude in session R, from PROMPT.md alone | Ivan confirmed AI may write it; a separate session keeps it independent of the hand-derived tests |
| Model under test | Newest Gemini Pro on the Gemini API on the run day, found from the API's model list and Google's docs | PDF §4.2 |
| Settings | API defaults (no `generationConfig` sent) | PDF: "Leave the settings at their defaults" |
| Wrapper | `Implement the following task. Return the complete code in one file and nothing else.` then a blank line, then PROMPT.md | PDF §4.1 wording, identical for every attempt |
| Attempts | 5, recorded once, never re-rolled | PDF §4.3; honesty |
| Hidden tests | 48–55, each with a written hand derivation | PDF asks for at least 40 |
| Mutants | 8 | PDF: "strongly preferred" |
| ZIP name | `<INITIALS>-wal-recovery-<YYYYMMDD>.zip` | PDF §7 |

---

## 2. Roadmap at a glance

| # | Session | Model | Effort | Time | Needs |
|---|---|---|---|---|---|
| P0 | Set up machine and folders | Sonnet 5.5 | low | 20 min | — |
| P1 | Write `task/PROMPT.md` | Opus 5.5 | xhigh | 60–90 min | P0 |
| P2 | Audit PROMPT.md for fairness | Opus 5.5 | high | 45–60 min | P1 |
| P3 | Runner core: Gemini call, code extraction | Sonnet 5.5 | medium | 45–60 min | P0, Gemini key |
| P4 | Java test harness and public tests | Sonnet 5.5 | high | 60–90 min | P2, P3 |
| P5 | Hidden tests, part 1 (header, framing, checksum) | Opus 5.5 | high | 60–90 min | P4 |
| P6 | Hidden tests, part 2 (payload, transactions, precedence) | Opus 5.5 | high | 60–90 min | P5 |
| P7 | Independent test audit | Opus 5.5 | xhigh | 60–90 min | P6 |
| P8 | Gemini pilot, fairness check, freeze PROMPT.md | Opus 5.5 | high | 60–90 min | P7 |
| P9 | Runner: full pipeline, reports, summary.json | Sonnet 5.5 | medium | 45–60 min | P4 |
| R | Reference solution, from PROMPT.md alone | Opus 5.5 | high | 60–90 min | P8 |
| H1 | Only if R lists disputed tests: decide who is right | Opus 5.5 | high | 20–40 min | R |
| P10 | 8 mutants and the mutation check | Sonnet 5.5 | high | 30–60 min | R, P9 |
| P11 | Official run: 5 Gemini attempts | Sonnet 5.5 | medium | 30–60 min | P10 |
| P12 | Write FAILURE_ANALYSIS.md | Opus 5.5 | xhigh | 60–120 min | P11 |
| P13 | Independent check of the analysis | Opus 5.5 | high | 45–60 min | P12 |
| P14 | README, final checks, ZIP, reply draft | Sonnet 5.5 | medium | 30 min | P13 |

Total: about 13–18 hours of sessions. Keep a note of the time you spend; the client asks for it.

Why these choices: Opus where a wrong judgment sinks the submission (task text, expected values, reference solution, fairness, failure analysis). Sonnet for well-specified coding. `xhigh` only for the three sessions with long, error-sensitive reasoning (P1, P7, P12).

---

## 3. Layout, formats and ground rules

### 3.1 Folders

```
/root/Documents/test/
├── PLAN.md                      this file (not shipped)
├── work/                        not shipped
│   ├── STATE.md                 progress and decisions (every session updates it)
│   ├── notes/                   audits, derivations, pilot notes, checklists, reply draft
│   ├── pilot/                   Gemini runs on draft task text
│   └── scratch/                 throwaway files (stubs, small arithmetic scripts)
└── wal-recovery/                becomes the ZIP (these items sit at the ZIP root)
    ├── README.md
    ├── task/PROMPT.md
    ├── reference/WalRecovery.java          written in session R
    ├── reference/mutants/mutants.json
    ├── tests/public/PublicTests.java       the worked examples from PROMPT.md
    ├── tests/hidden/HiddenTests.java       48+ hidden tests
    ├── runner/run.py, gemini.py, extract.py, javatest.py, report.py, selftest.py, config.json
    ├── runner/harness/*.java               LogBuilder, Hex, TestCase, Render, TestMain
    ├── results/gemini_run_1 … gemini_run_5/
    ├── results/summary.json, report.md, reference_runs.json, mutants.json, prompt_sent.txt
    └── FAILURE_ANALYSIS.md
```

Build output goes to `wal-recovery/build/`. It is git-ignored and never zipped.

### 3.2 Canonical result format

Every test compares one line of text. The harness and all tests use exactly this format.

| Outcome | Line |
|---|---|
| Normal result | `OK state={"a"="1", "b"=""} committed=[1, 3] aborted=[2] incomplete=[4] validLength=57 tornTail=false` |
| `WalException` | `ERR CHECKSUM_MISMATCH@31` |
| Any other exception | `THROW java.lang.NullPointerException` (class name only) |
| No answer within 5 s | `TIMEOUT` |
| Input array changed by the call | the line above, plus ` +INPUT_MODIFIED` |

- `state`: entries sorted by key (`String.compareTo`), each written `"key"="value"`, joined with `, `. Inside quotes, `\` becomes `\\` and `"` becomes `\"`. Empty map: `{}`.
- Lists: `[`, elements joined with `, `, `]`. Numbers in decimal; `null` for a null element. Empty: `[]`.
- A null `Result` renders as `NULL_RESULT`. A null field renders as `null`.
- The harness copies the input before each call and compares it after, for every test.

### 3.3 Test format

```java
// H07 tail_bad_checksum_last_record_is_torn
// Rules: R11, R12
// Derivation:
//   1. Header bytes 0-3 are the magic (R2). Record A = BEGIN 1 at offset 4, size 9, ends at 13.
//   2. Record B = PUT at 13, size 14, ends at 27 = L. Stored checksum is wrong.
//   3. R12: the record ends exactly at L, so it is a torn tail: validLength = 13, tornTail = true.
//   4. Phase 2 replays record A only: txn 1 opens and never closes -> incomplete = [1].
// Expected: OK state={} committed=[] aborted=[] incomplete=[1] validLength=13 tornTail=true
TestCase.of("H07", "tail_bad_checksum_last_record_is_torn", "R11,R12",
    LogBuilder.wal().begin(1).put(1, "k", "v").corruptLastChecksum().build(),
    "OK state={} committed=[] aborted=[] incomplete=[1] validLength=13 tornTail=true"),
```

(Illustration only. Rule numbers come from the real PROMPT.md.)

Expected strings are always literals worked out by hand. They are never computed by code and never copied from any program's output.

### 3.4 Ground rules for every session

- **G1** Read PLAN.md §1–§6 and `work/STATE.md` first. Do only the task in your prompt.
- **G2** Work only under `/root/Documents/test`.
- **G3** Only session R writes or changes `reference/WalRecovery.java`. No other session creates a working implementation of `recover()` anywhere (no oracle, no "scratch" version), so the hand-derived tests stay independent of the code. Small scripts for arithmetic (checksums, hex, offsets) are fine. A stub whose methods only throw is fine. Deliberately broken mutant copies are allowed in P10 only.
- **G4** Never set an expected test value from the output of any program (reference, Gemini's code, anything). Work it out by hand from `task/PROMPT.md` and write the derivation next to the test. If code disagrees with a test, re-derive by hand; change the test only if the derivation was wrong.
- **G5** Once `STATE.md` marks PROMPT.md as FROZEN, never edit `task/PROMPT.md`.
- **G6** Never edit Gemini's saved answers in `results/gemini_run_*/` (request.json, response.json, answer.txt, WalRecovery.java, extraction.json, meta.json). Only the runner writes its own output files there.
- **G7** Never print, log or save the Gemini API key. The runner reads the env var `GEMINI_API_KEY`, or the file `~/.gemini_api_key`.
- **G8** Standard library only: Java 21 (`javac --release 21`) and Python 3. No Maven, Gradle, JUnit or pip packages.
- **G9** Shipped files contain no absolute paths, random values or timestamps, except the recorded run metadata (date, model, durations in meta.json and summary.json).
- **G10** Finish every session: update `work/STATE.md` (status fields, one log row, open issues), then commit in `/root/Documents/test` with the message given in the prompt.
- **G11** If a "done when" check fails and you can't fix it, stop. Write it under Open issues in `STATE.md`, commit, and tell the user. Never report a check as passed when it isn't.

### 3.5 STATE.md template

```
# STATE: WAL recovery assignment
Initials: ??   Name: ??   Started: YYYY-MM-DD
PROMPT.md: DRAFT                 (when frozen: FROZEN sha256=... on YYYY-MM-DD)
Gemini model: not chosen         (model id, chosen on, how)
Hidden tests: 0                  audited: no
Reference: not started
Mutants: 0/8 killed
Official run: not done           (date; attempts failing >=1 hidden test: x/5)
Analysis: not started

## Session log
| Session | Date | Result | Notes |
|---|---|---|---|

## Disputed tests (written by R, ruled on by H1)
- none

## Open issues
- none
```

---

## 4. Task design brief: WAL crash recovery

This is the direction for P1. P1 turns it into exact numbered rules; P2 and P8 may adjust it for fairness. The rule names here (H1, F1, S1 …) are working names; PROMPT.md uses its own R-numbers.

### 4.1 The task in one paragraph
Implement `WalRecovery.recover(byte[] log)`. It rebuilds a key-value store from a write-ahead log after a crash. Recovery has two phases. Phase 1 checks the header and the record frames, to find where the valid log ends and whether it is corrupt. Phase 2 replays the intact records, validates them, and applies committed transactions in commit order.

### 4.2 Fixed API (goes into PROMPT.md exactly)

```java
// File: WalRecovery.java (no package declaration, Java 21, standard library only)
import java.util.List;
import java.util.Map;

public final class WalRecovery {
    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        throw new UnsupportedOperationException("implement me");
    }

    public record Result(Map<String, String> state,
                         List<Long> committed,
                         List<Long> aborted,
                         List<Long> incomplete,
                         int validLength,
                         boolean tornTail) {}

    public enum Code {
        BAD_MAGIC, UNKNOWN_TYPE, RECORD_TOO_LARGE, CHECKSUM_MISMATCH,
        MALFORMED_PAYLOAD, BAD_TXN_ID, BAD_KEY, BAD_VALUE,
        DUPLICATE_TXN, TOO_MANY_OPEN, TXN_NOT_OPEN
    }

    public static final class WalException extends Exception {
        private final Code code;
        private final int offset;
        public WalException(Code code, int offset) {
            super(code + "@" + offset);
            this.code = code;
            this.offset = offset;
        }
        public Code code() { return code; }
        public int offset() { return offset; }
    }
}
```

### 4.3 Byte format
- All multi-byte integers are big-endian and unsigned.
- Header: 4 bytes `57 41 4C 31` ("WAL1").
- Record at offset p: `type` (1 byte), `N` payload length (2 bytes), payload (N bytes), checksum (2 bytes). Record size = 5 + N.
- Checksum = Fletcher-16 over the 3 + N bytes `type, N, payload`, each byte taken as 0–255:
  `s1 = 0; s2 = 0; for each byte b: s1 = (s1 + b) mod 255; s2 = (s2 + s1) mod 255`. Stored as `s2` then `s1`.
- Check value (verified): BEGIN txn 1 is `01 00 04 00 00 00 01 1C 06`.
- Note for test writers: Fletcher-16 cannot tell `00` from `FF`. To corrupt a record, change its stored checksum (or use a byte change other than 00↔FF), and say so in the derivation.

| Type | Name | Payload | Record size |
|---|---|---|---|
| 1 | BEGIN | txn (4) | 9 |
| 2 | PUT | txn (4), keyLen (1), key, valueLen (2), value | 12 + k + v |
| 3 | DELETE | txn (4), keyLen (1), key | 10 + k |
| 4 | COMMIT | txn (4) | 9 |
| 5 | ABORT | txn (4) | 9 |

### 4.4 Limits
- Payload length N ≤ 1024.
- Txn id 1 … 4294967295 (0 is invalid). Returned as `long`.
- Key: 1 … 32 bytes, each 0x21 … 0x7E (printable ASCII, no space).
- Value: 0 or more bytes, each 0x20 … 0x7E (space allowed).
- At most 4 transactions open at the same time.

### 4.5 Rules and their check order

Header (L = `log.length`):
- **H0** `log == null` → `NullPointerException`.
- **H1** L = 0 → empty result, `validLength = 0`, `tornTail = false`.
- **H2** 0 < L < 4: if the bytes equal the first L bytes of the magic → torn: empty result, `validLength = 0`, `tornTail = true`. Otherwise `BAD_MAGIC@0`.
- **H3** L ≥ 4 and the first 4 bytes are not the magic → `BAD_MAGIC@0`.

Phase 1, frames. p starts at 4. Repeat while p < L, with checks in this order:
- **F1** fewer than 3 bytes left (L − p < 3) → torn at p, whatever those bytes are.
- **F2** type not in 1 … 5 → `UNKNOWN_TYPE@p`.
- **F3** N > 1024 → `RECORD_TOO_LARGE@p`.
- **F4** p + 5 + N > L → torn at p.
- **F5** checksum wrong → if p + 5 + N = L (it is the last record), torn at p; otherwise `CHECKSUM_MISMATCH@p`.
- **F6** otherwise the record is intact; p ← p + 5 + N.
- Torn at p: stop scanning; `validLength = p`, `tornTail = true`. If the scan reaches p = L: `validLength = L`, `tornTail = false`.

Phase 2, replay. Runs only if phase 1 raised no error. Intact records in log order; for each record, checks in this order:
- **S1** Payload structure. BEGIN, COMMIT and ABORT need N = 4 exactly. PUT needs N ≥ 5, then N ≥ 5 + k + 2, then N = 4 + 1 + k + 2 + v exactly. DELETE needs N ≥ 5, then N = 4 + 1 + k exactly. Otherwise `MALFORMED_PAYLOAD@p`.
- **S2** txn = 0 → `BAD_TXN_ID@p`.
- **S3** PUT/DELETE key: length 0 or more than 32, or any byte outside 0x21 … 0x7E → `BAD_KEY@p`.
- **S4** PUT value: any byte outside 0x20 … 0x7E → `BAD_VALUE@p`.
- **S5** BEGIN: the id was used by any earlier BEGIN (open, committed or aborted) → `DUPLICATE_TXN@p`; else if 4 are already open → `TOO_MANY_OPEN@p`; else open it.
- **S6** PUT/DELETE/COMMIT/ABORT: the txn is not open → `TXN_NOT_OPEN@p`.
- **S7** PUT/DELETE: add to the transaction's write list, in log order.
- **S8** COMMIT: apply its writes in order to the state (PUT sets, DELETE removes; deleting a missing key does nothing); append the id to `committed`; close it.
- **S9** ABORT: drop its writes; append the id to `aborted`; close it.
- End: `incomplete` = ids still open, ascending.

Precedence (PROMPT.md gets its own section for this):
- **P-a** Header errors first, then phase-1 errors in log order. The first one stops recovery.
- **P-b** A phase-1 error anywhere beats any phase-2 error, even one earlier in the log.
- **P-c** In phase 2, the first failing record in log order wins; within a record, S1 → S6 order.
- **P-d** Bytes at or after a torn tail are never checked in phase 2.

Not errors (PROMPT.md lists these explicitly): DELETE of a missing key; PUT with an empty value; COMMIT of a transaction with no writes; txn ids in any order; a log of exactly the 4 header bytes; 1–2 leftover bytes at the end.

### 4.6 Traps this design sets (tests must cover each one)
- **T1** Two-phase precedence (P-b).
- **T2** MALFORMED_PAYLOAD belongs to phase 2, not phase 1.
- **T3** F2 and F3 beat F4 and F5, even on the last record.
- **T4** F1 boundary: 2 leftover bytes are torn; 3 leftover bytes get full header checks.
- **T5** F5 boundary: the record ends exactly at L → torn; one more byte after it → CHECKSUM_MISMATCH.
- **T6** The checksum covers type and length too, and bytes are unsigned (Java `byte` is signed).
- **T7** Txn ids ≥ 2^31 (u32 into `long`).
- **T8** N ≥ 32768 is read as unsigned → RECORD_TOO_LARGE.
- **T9** Writes apply in commit order, not log order.
- **T10** DUPLICATE_TXN also covers finished ids; DUPLICATE_TXN is checked before TOO_MANY_OPEN.
- **T11** Field checks (S2–S4) come before state checks (S5–S6).
- **T12** Key and value byte ranges and lengths at the edges.
- **T13** The 4-open limit, and a COMMIT or ABORT freeing a slot.
- **T14** Partial magic (L = 1–3).
- **T15** Empty log vs header-only log.
- **T16** Input not modified; null input.
- **T17** `incomplete` sorted as unsigned values.

### 4.7 Hardening levers (only if the pilot shows Gemini passes)
Use one at a time, in this order. Each needs new numbered rules, an error code with its place in the check order, and at least 4 new tests.
- **L1** Write limit: at most 8 PUT/DELETE per transaction → `TXN_TOO_LARGE` (checked after S6).
- **L2** Savepoints: SAVEPOINT (type 6: txn, 1-byte name) and ROLLBACK (type 7: txn, name). ROLLBACK drops writes made after the named savepoint and removes later savepoints; the named one stays; reusing a name moves it; an unknown name → `NO_SAVEPOINT`.
- **L3** CHECKPOINT (type 8): the payload lists the open txn ids in ascending order; it must match the real open set → `CHECKPOINT_MISMATCH`.

---

## 5. Test plan

Target: 48–55 hidden tests, ids H01 upward, in this group order. Every error code gets at least 2 tests; every limit is tested on both sides.

| Group | Session | Count | Cases |
|---|---|---|---|
| A Header | P5 | 6 | empty log; header only; L=1 `57` (torn); L=2 `57 41` (torn); L=3 `57 41 58` (BAD_MAGIC); L≥4 with wrong magic |
| B Framing and tail | P5 | 12 | 1 leftover byte; 2 leftover bytes with type 0x09 (torn); 3 leftover bytes with type 0x09 (UNKNOWN_TYPE); type 0 and type 6; payload cut short at the end (torn); last record with bad checksum ending exactly at L (torn); the same plus 1 extra byte (CHECKSUM_MISMATCH); bad checksum in the middle; N=1025 (RECORD_TOO_LARGE); N=1024 valid PUT; N=0x8001 (RECORD_TOO_LARGE); cut-short last record with N=2000 (RECORD_TOO_LARGE beats torn); last record with unknown type and bad checksum (UNKNOWN_TYPE) |
| C Checksum details | P5 | 4 | checksum computed over the payload only (must be rejected); txn id `FF FF FF FF`; N = 200 (length byte ≥ 0x80); s1 and s2 stored swapped |
| D Payload structure | P6 | 5 | BEGIN with N=3 and N=5; PUT with keyLen past the payload end; PUT with 1 extra byte; DELETE with N=4; COMMIT with N=0 |
| E Field checks | P6 | 8 | txn 0; key length 0; key length 32 (ok); key length 33; key containing 0x20; key containing 0x7F; value containing 0x20 (ok); value containing 0x0A |
| F Transactions | P6 | 10 | commit order differs from log order; abort drops writes; incomplete sorted; DUPLICATE on an open id; DUPLICATE on a committed id; TXN_NOT_OPEN after commit; TOO_MANY_OPEN on the 5th; 4 open, one commits, a new BEGIN is ok; DELETE of a missing key; PUT then DELETE in one transaction; empty commit; id 4294967295 end to end |
| G Precedence and interactions | P6 | 8 | phase-2 error early + checksum error later (CHECKSUM_MISMATCH wins); MALFORMED early + RECORD_TOO_LARGE later; BAD_TXN_ID + BAD_KEY in one record; BAD_KEY + TXN_NOT_OPEN in one record; DUPLICATE + TOO_MANY_OPEN; COMMIT inside the torn tail (txn incomplete); phase-2 error before a torn tail (error); invalid content inside a torn record (ignored) |
| H API contract | P6 | 2 | input not modified (content-rich log); null input |

### 5.1 Mutant ideas (for P10)
Each mutant is one small, realistic change in a different area: single-pass error precedence; `>` instead of `>=` (or the reverse) in the tail check; length read as signed; checksum over the payload only; DUPLICATE checks only open ids; writes applied in log order; open limit off by one; key range allows 0x20.

---

## 6. Runner specification

### 6.1 Commands (run from `wal-recovery/`)

| Command | What it does |
|---|---|
| `python3 runner/run.py` | Offline (the default). Tests the reference 3 times and `results/gemini_run_1…5`. Writes test_results.json per attempt, reference_runs.json, report.md and summary.json. Same output every time. |
| `python3 runner/run.py --fresh --out DIR [--attempts 5]` | New Gemini attempts into `DIR/gemini_run_k`, then tests them. |
| `python3 runner/run.py --test FILE` | Public and hidden tests on one Java file; prints a summary and the failures. |
| `python3 runner/run.py --pilot N --out DIR` | Like `--fresh`, for design work (into `work/pilot`). |
| `python3 runner/run.py --models` | Lists Gemini models whose name contains "pro" and that support generateContent. |
| `python3 runner/run.py --mutants` | Runs the 8 mutants; writes results/mutants.json. |

### 6.2 Gemini call
- Confirm against Google's current Gemini API docs first. Expected shape: `POST https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent`, header `x-goog-api-key`, body `{"contents":[{"role":"user","parts":[{"text": WRAPPER + "\n\n" + PROMPT}]}]}`, and no `generationConfig`.
- HTTP timeout 600 s. Retry only when no answer arrived (network error, HTTP 429 or 5xx): up to 3 retries, waiting 10, 30 and 90 s. A received answer is final, even a bad one.
- Attempts are separate requests with no shared history, run one after another.

### 6.3 Code extraction (never edit the code)
- Answer text = the `text` of every part in `candidates[0].content.parts` that is not a thought, joined with nothing.
- Take the first fenced code block whose info string is `java` (any case); if there is none, the first fenced block; if there is no fence, the whole answer. Block content runs from the line after the opening fence to the line before the closing fence, copied exactly.
- Save it as `WalRecovery.java` (UTF-8). Write `extraction.json` with the rule used, the block index, the start and end character offsets in answer.txt, and a SHA-256. Self-check: the saved text equals `answer.txt[start:end]`.

### 6.4 Java test run
- Per solution: copy it to `build/<name>/src/WalRecovery.java` and compile it with `javac --release 21 -encoding UTF-8 -d build/<name>/classes`, together with `runner/harness/*.java`, `tests/public/*.java` and `tests/hidden/*.java`.
- Run `java -Xss8m -Xmx512m -cp build/<name>/classes TestMain public`, then the same with `hidden`. Each test runs on a daemon thread with a 5 s limit. Whole-JVM limit: 300 s.
- The harness catches `Throwable` and uses `instanceof WalRecovery.WalException`, so it still compiles if a solution's `recover()` lacks the `throws` clause.
- The harness prints one line per test: `@@TEST<TAB>id<TAB>PASS|FAIL<TAB>expected<TAB>actual` (tab, newline and backslash escaped). Other output is ignored.
- A solution that doesn't compile: every test FAILs with actual `COMPILE_ERROR`; the first 30 lines of javac output go to compile.log and the report. A missing test line (the JVM died): FAIL with actual `NO_RESULT`.

### 6.5 Files per attempt (`results/gemini_run_k/`)
`request.json` (body only, no key), `response.json` (raw bytes as received), `answer.txt`, `WalRecovery.java`, `extraction.json`, `meta.json` (attempt number, requested model, `modelVersion` from the response, date UTC, HTTP status, finishReason, usage, retries, seconds), `test_results.json`, `compile.log`.
Once per run: `results/prompt_sent.txt` (the exact text sent), with its SHA-256 in summary.json.

### 6.6 summary.json and report.md

```json
{
  "task": "wal-recovery",
  "languages": {"task": "Java 21", "runner": "Python 3"},
  "model": {
    "requested": "...",
    "reported_versions": ["..."],
    "api": "Gemini API v1beta generateContent",
    "settings": "API defaults (no generationConfig sent)",
    "run_date_utc": "YYYY-MM-DD",
    "wrapper": "Implement the following task. Return the complete code in one file and nothing else.",
    "prompt_sha256": "..."
  },
  "tests": {"public": 3, "hidden": 52},
  "reference": {"runs": 3, "all_passed": true, "identical": true},
  "attempts": [
    {"name": "gemini_run_1", "compiled": true, "public_passed": 3,
     "hidden_passed": 44, "hidden_total": 52, "failed": ["H07", "H31"]}
  ],
  "acceptance": {"attempts_failing_a_hidden_test": 5, "required": 4, "met": true},
  "mutants": {"total": 8, "killed": 8}
}
```

report.md: first a summary table shaped like the PDF example (`reference 52 / 52`, `gemini attempt 1 44 / 52`, …), then each attempt's failures (id, name, expected, actual), then the full test × solution matrix.

### 6.7 README.md (10 lines or fewer)

```
# WAL recovery: a hard task Gemini fails
Needs JDK 21+ (javac, java) and Python 3.10+. Nothing else to install.
Re-test the reference and the 5 saved Gemini answers (no API key needed):
    python3 runner/run.py        -> results/report.md, results/summary.json
Ask Gemini again (needs GEMINI_API_KEY; writes a new folder, keeps results/):
    python3 runner/run.py --fresh --out results_new
Mutation check of the hidden tests:  python3 runner/run.py --mutants
Task: task/PROMPT.md | Reference: reference/ | Tests: tests/ | Analysis: FAILURE_ANALYSIS.md
```

---

## 7. Session prompts

Open a new Claude Code session in `/root/Documents/test`, set the model and effort, and paste everything between the `~~~` lines.

### P0: Set up (Sonnet 5.5, effort low, ~20 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md sections 1 and 3 first, and follow its ground rules (§3.4). Do only this task.

Task: prepare the machine and the folders.
1. Java: if `javac -version` does not report 21 or newer, install it (`apt-get update && apt-get install -y openjdk-21-jdk-headless`). Confirm `javac -version` and `java -version`.
2. Confirm `python3 --version` is 3.10 or newer.
3. Create the folder layout from PLAN.md §3.1 (folders only; put a .gitkeep in each empty folder). Do not create any Java or Python source files yet.
4. Create /root/Documents/test/.gitignore containing: build/, *.class, __pycache__/, .vscode/, .idea/, *.zip, results_new*/, .env
5. Create work/STATE.md from the template in PLAN.md §3.5. Ask me for my initials (for the ZIP name) and my full name (for the reply to the client), and fill them in with today's date.
6. Gemini key: check whether GEMINI_API_KEY is set or ~/.gemini_api_key exists. Print only "found" or "missing", never the value. If missing, tell me how to create a key in Google AI Studio and store it myself with: printf '%s' 'MY_KEY' > ~/.gemini_api_key && chmod 600 ~/.gemini_api_key. Do not ask me to paste the key into this chat.
7. Run `git init` in /root/Documents/test if needed, and commit everything.

Done when: javac and java report 21+; the folders match §3.1; STATE.md has my initials and name; git has a commit; you told me whether the Gemini key was found.
Finish per ground rule G10 with the commit message "P0: setup".
~~~

### P1: Write task/PROMPT.md (Opus 5.5, effort xhigh, 60–90 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6 and /root/Documents/test/work/STATE.md first, and follow the ground rules in PLAN.md §3.4 (especially G3: never write an implementation of recover()). Do only this task.

Goal: write /root/Documents/test/wal-recovery/task/PROMPT.md, the exact text Gemini will see. It must be self-contained, fair and precise, and hard because many rules interact. The source is the design brief in PLAN.md §4. The client rejects tasks whose text is vague or whose tests check something the text never states.

Required sections, in this order:
1. Title and a 3–5 sentence overview.
2. What to submit: one file WalRecovery.java, no package declaration, Java 21, standard library only, deterministic, no output to stdout or stderr, recover() must not modify its input array.
3. The fixed API: the exact skeleton from PLAN.md §4.2 in a java code block, plus a sentence saying names, signatures and types must not change.
4. Definitions: offsets are 0-based byte positions in log; L = log.length; all multi-byte integers are big-endian unsigned; a byte is a value 0–255.
5. Log format: header, record frame, a type/payload table, record sizes, and the Fletcher-16 definition with the check value from PLAN.md §4.3 (show the s1/s2 steps for it).
6. Limits as named constants.
7. Recovery rules as numbered rules R1, R2, … grouped as Header, Phase 1 (frames), Phase 2 (replay), Result. Each rule states one decision in one or two sentences. Inside each group, state the check order explicitly ("The checks are made in this order: …").
8. Error precedence: its own short numbered section (PLAN.md §4.5 P-a to P-d), including the sentence that a phase-1 error anywhere in the log beats a phase-2 error anywhere in the log.
9. Error table: every Code value, when it happens, which rule, and which offset is reported. Also NullPointerException for null input.
10. "Not errors" list (PLAN.md §4.5).
11. Worked examples, at least 3. Each has a one-line story, the exact input bytes in hex grouped per record with labels and offsets, and the exact expected outcome as Result field values (or the exception code and offset).
    - Example 1: a normal log with interleaved transactions where commit order differs from log order, plus an abort and an incomplete transaction.
    - Example 2: a torn tail where the last record (a COMMIT) is cut short, so that transaction is incomplete.
    - Example 3: a checksum error in the middle of the log (CHECKSUM_MISMATCH and its offset).
    Do not turn the trickier rules (two-phase precedence, the "ends exactly at L" boundary, field-before-state check order) into examples; they stay as clearly stated rules.

Method:
- Build the example bytes with a small throwaway encoder script in work/scratch/ (encoding and checksums only, never decoding). Re-check one record's checksum by hand.
- Derive each example's expected outcome by hand, step by step (offset by offset, rule by rule), in work/notes/examples.md.
- Make every rule testable: no "should", "usually", "etc." or undefined terms. Every input must have exactly one correct outcome.
- Aim for 250–400 lines in plain, simple English.

Done when: PROMPT.md has all 11 sections; every Code appears in the error table with a rule number; the skeleton compiles (copy it to work/scratch/skeleton/WalRecovery.java and run javac --release 21 there); examples.md has a full derivation for each example; STATE.md says "PROMPT.md: DRAFT v1".
Finish per G10 with the commit message "P1: task text draft".
~~~

### P2: Audit PROMPT.md (Opus 5.5, effort high, 45–60 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6 and work/STATE.md, and follow the ground rules in PLAN.md §3.4. Do only this task.

You are a strict reviewer whose job is to reject this task as unfair if you can. Review /root/Documents/test/wal-recovery/task/PROMPT.md. The client rejects tasks where the text is vague or the tests check something the text never states.

Check:
1. Ambiguity: any sentence a careful engineer could read two ways.
2. Gaps: walk through every decision point (each header case, each phase-1 check, each phase-2 check, each result field) and look for inputs where the rules give no answer or two answers. Try at least 25 tricky inputs in your head: empty log; 1–3 bytes; leftover bytes; the last record cut at every position; N = 0, 1024, 1025, 32768; txn 0 and 4294967295; 4 and 5 open transactions; every pair of errors in one record; a phase-1 error after a phase-2 error.
3. Contradictions between the rules, the error table, the examples and the "not errors" list.
4. Determinism: map ordering, list ordering, anything depending on hash order or locale.
5. API: the skeleton compiles (javac --release 21 in work/scratch/skeleton/), and names in the text match the code.
6. Examples: re-derive every expected outcome yourself, by hand, before reading work/notes/examples.md. Recompute every checksum with your own small script. Confirm the hex, the offsets and the sizes.
7. Fairness of traps: every trap in PLAN.md §4.6 must be stated plainly in the text, not hidden in an example.

Write each finding to work/notes/spec-audit.md (location, problem, proposed fix, status). Then fix PROMPT.md, update examples.md if needed, and re-verify the examples after your edits.

Done when: every finding is fixed or explained as not a problem; the examples are re-verified; the skeleton compiles; STATE.md says "PROMPT.md: DRAFT v2 (audited)".
Finish per G10 with the commit message "P2: task text audit".
~~~

### P3: Runner core (Sonnet 5.5, effort medium, 45–60 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1, §3 and §6 and work/STATE.md, and follow the ground rules in PLAN.md §3.4 (G7: never reveal the API key; G8: standard library only). Do only this task.

Build the Gemini part of the runner in /root/Documents/test/wal-recovery/runner/, using the Python 3 standard library only:
1. First confirm Google's current Gemini API details from the official docs (ai.google.dev) with a web search: the generateContent endpoint, the auth header, the response fields (candidates, content.parts, text, thought, finishReason, usageMetadata, modelVersion), and models.list with pagination. Note the doc URLs in work/notes/gemini-api.md.
2. gemini.py: list models; call generateContent exactly as PLAN.md §6.2 (no generationConfig); read the key from env GEMINI_API_KEY or ~/.gemini_api_key; apply the timeout and retry rules of §6.2; return the raw response bytes untouched.
3. extract.py: the extraction rule of §6.3, with extraction.json and the substring self-check.
4. run.py with --models and --pilot N --out DIR. Each attempt goes to DIR/gemini_run_k/ with request.json, response.json, answer.txt, WalRecovery.java, extraction.json and meta.json; also write DIR/prompt_sent.txt. Leave clear places for the other commands in §6.1; later sessions add them.
5. config.json: model id, the exact wrapper text from PLAN.md §1, attempts = 5, timeouts.
6. Choose the model: run --models, then check Google's docs and changelog for the newest Gemini Pro model available through the API today (a preview model counts if it is the newest Pro). Record the id, today's date and how you decided, in config.json and STATE.md.
7. runner/selftest.py (unittest) for extraction: one java block; two blocks (the java one wins); an untagged block; no fence; a fence line with trailing spaces; Windows line endings. All must pass.
8. Make one real call: python3 runner/run.py --pilot 1 --out ../work/pilot/smoke (from wal-recovery/). Show me the file list and the first 20 lines of the extracted code. Do not run tests on it yet.

Done when: selftest passes; one smoke attempt is saved with all files; the extracted file is an exact substring of answer.txt; the model choice is recorded; a check in Python (which never prints the key) confirms that no file under /root/Documents/test contains the key.
Finish per G10 with the commit message "P3: runner core".
~~~

### P4: Java test harness and public tests (Sonnet 5.5, effort high, 60–90 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6, work/STATE.md and wal-recovery/task/PROMPT.md, and follow the ground rules in PLAN.md §3.4 (G3, G4, G8). Do only this task.

Build the test machinery and the public tests (all paths under /root/Documents/test/wal-recovery/):
1. runner/harness/LogBuilder.java builds logs for tests:
   - static wal() (starts with the magic) and static raw() (starts empty)
   - begin(long), put(long, String key, String value), del(long, String key), commit(long), abort(long): correct records
   - record(int type, byte[] payload): correct length and checksum
   - frame(int type, int declaredLength, byte[] payload, int checksum): anything, for corrupt frames
   - bytes(int...): raw bytes
   - corruptLastChecksum(): XOR the last record's stored checksum with 0x0101
   - truncate(int n): drop the last n bytes
   - build(): returns a fresh copy
   - static fletcher16(byte[] data, int from, int to)
   Txn ids are long values written as u32 big-endian.
2. runner/harness/Hex.java: Hex.bytes("57 41 4C 31 ...") parses spaced hex.
3. runner/harness/TestCase.java: TestCase.of(id, name, rules, byte[] input, expected) and TestCase.ofNull(id, name, rules, expected).
4. runner/harness/Render.java: the canonical format of PLAN.md §3.2, exactly.
5. runner/harness/TestMain.java: java TestMain public|hidden. It runs PublicTests.cases() or HiddenTests.cases() in order. Per test it copies the input, calls WalRecovery.recover on a daemon thread with a 5 s limit, catches Throwable (using instanceof WalRecovery.WalException, so solutions without a throws clause still compile), renders the result, checks that the input is unchanged, compares to the expected line, and prints the @@TEST line of §6.4. It never stops early.
6. tests/public/PublicTests.java: the worked examples from PROMPT.md. Build their inputs with Hex.bytes(...) from the exact hex in PROMPT.md, and give the exact expected lines.
7. tests/hidden/HiddenTests.java: an empty list for now.
8. runner/javatest.py and run.py --test FILE: compile and run as in §6.4, parse the @@TEST lines, handle compile errors and missing lines, and print a summary plus every failure (expected vs actual).

Self-checks (all required):
a. LogBuilder reproduces the Fletcher-16 check value in PROMPT.md, and LogBuilder calls reproduce every worked example's hex byte for byte (a small check run by javatest.py or a Java check class).
b. A stub (the PROMPT.md skeleton whose recover() only throws UnsupportedOperationException) in work/scratch/stub/WalRecovery.java: every test FAILs with THROW java.lang.UnsupportedOperationException.
c. A file with a syntax error: every test FAILs with COMPILE_ERROR, and a compile log is written.
d. The smoke pilot file work/pilot/smoke/gemini_run_1/WalRecovery.java runs to completion (any results).
e. A stub that loops forever gives TIMEOUT for each test, and the run still finishes.

Done when: a–e pass; no test-framework jars are used; STATE.md is updated.
Finish per G10 with the commit message "P4: harness and public tests".
~~~

### P5: Hidden tests, part 1 (Opus 5.5, effort high, 60–90 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6, work/STATE.md and wal-recovery/task/PROMPT.md, and follow the ground rules in PLAN.md §3.4. G3 and G4 are critical: no implementation of recover() anywhere, and no expected value taken from any program's output. Do only this task.

Write the hidden tests for groups A, B and C of PLAN.md §5 (about 22 tests, ids H01 upward) in wal-recovery/tests/hidden/HiddenTests.java, in the format of PLAN.md §3.3.

For every test:
- Build the input with LogBuilder (raw bytes only where needed).
- Cite the PROMPT.md rule numbers it checks.
- Write the derivation as a comment: each record's offset and size, each check in the order PROMPT.md gives, and the final outcome. A reviewer must be able to verify it without running anything.
- Write the expected line as a literal in the canonical format (§3.2).

Also keep work/notes/coverage.md: a table of PROMPT.md rule → test ids, and PLAN.md §4.6 trap → test ids.

Then run python3 runner/run.py --test ../work/scratch/stub/WalRecovery.java (from wal-recovery/). Everything should FAIL with THROW java.lang.UnsupportedOperationException; this only proves the tests compile and run. If pilot files exist in work/pilot/, you may run them too. Where pilot code disagrees with a test, re-derive that test by hand from PROMPT.md and write in coverage.md who is right and why. Change an expected value only if your re-derivation shows it was wrong.

Done when: at least 22 tests in groups A–C, each with rules, a derivation and a literal expected line; they compile and run; coverage.md is updated; STATE.md shows the test count.
Finish per G10 with the commit message "P5: hidden tests A-C".
~~~

### P6: Hidden tests, part 2 (Opus 5.5, effort high, 60–90 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6, work/STATE.md, wal-recovery/task/PROMPT.md, the existing tests in wal-recovery/tests/hidden/HiddenTests.java and work/notes/coverage.md. Follow the ground rules in PLAN.md §3.4; G3 and G4 are critical. Do only this task.

Add the hidden tests for groups D, E, F, G and H of PLAN.md §5 (at least 26 more tests, continuing the ids), in the format of PLAN.md §3.3: LogBuilder input, rule numbers, step-by-step derivation comment, literal expected line.

Requirements for the whole suite after this session:
- At least 48 hidden tests in total.
- Every Code value is the expected outcome of at least 2 tests.
- Every limit is tested on both sides (for example key length 32 and 33).
- Every trap T1–T17 in PLAN.md §4.6 has at least one test, and coverage.md says which.
- The input-not-modified test uses a content-rich log; the null test uses TestCase.ofNull and expects THROW java.lang.NullPointerException.
- For the precedence tests (group G), the derivation must say which rule decides between the two problems and quote PROMPT.md's precedence section.

Then run the suite on the stub (python3 runner/run.py --test ../work/scratch/stub/WalRecovery.java) to prove it compiles and runs. Pilot files, if any, may be run; handle disagreements as in P5 (re-derive by hand, record who is right; change a test only on a derivation error).

Done when: at least 48 hidden tests compile and run; coverage.md shows every rule, Code and trap covered; STATE.md shows the count.
Finish per G10 with the commit message "P6: hidden tests D-H".
~~~

### P7: Independent test audit (Opus 5.5, effort xhigh, 60–90 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6, work/STATE.md and wal-recovery/task/PROMPT.md. Follow the ground rules in PLAN.md §3.4; G3 and G4 are critical. Do only this task.

You did not write the tests. Your job is to find the wrong ones.

1. Write a small script that prints, for each test in tests/public and tests/hidden, only its id and its input-building code, with the comments and the expected string stripped out.
2. For each test, from that stripped view only, work out the outcome yourself by hand from PROMPT.md, step by step, and write it in work/notes/test-audit.md.
3. Then compare with the test's expected line and derivation. For any difference, read the rules again to find who is wrong. If the test is wrong, fix its expected line and derivation, and record the fix.
4. Check that the cited rules exist and actually decide the outcome.
5. Check that nothing tested depends on behaviour PROMPT.md does not state. If something does, either clarify PROMPT.md (only if STATE.md does not say FROZEN) or change or delete the test; record which.
6. Coverage: every rule; every Code with at least 2 tests; each side of every limit; every trap in PLAN.md §4.6; the input-not-modified and null checks. Add missing tests, with derivations.
7. The public tests must equal PROMPT.md's worked examples exactly (bytes and outcomes).

Run the suite on the stub afterwards to confirm it still compiles and runs.

Done when: test-audit.md has one row per test marked verified, with your own derived outcome; all fixes are listed; at least 48 hidden tests; STATE.md says "Hidden tests: N, audited: yes".
Finish per G10 with the commit message "P7: test audit".
~~~

### P8: Gemini pilot, fairness check, freeze (Opus 5.5, effort high, 60–90 min plus waiting for Gemini)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1–§6 and §10, work/STATE.md, wal-recovery/task/PROMPT.md and work/notes/coverage.md. Follow the ground rules in PLAN.md §3.4. Do only this task.

Goal: check that the current task text makes Gemini fail fairly, then freeze it. Pilot runs are design work: they stay in work/pilot and never go into results/.

1. From wal-recovery/, run python3 runner/run.py --pilot 5 --out ../work/pilot/round1, then python3 runner/run.py --test on each of the 5 extracted files.
2. For every failing test in every pilot attempt, decide and record in work/notes/pilot-round1.md:
   (a) Gemini broke a rule that PROMPT.md states clearly: a fair failure. Quote the rule.
   (b) PROMPT.md is unclear or silent: fix PROMPT.md by clarifying, not by adding tricks. Update the affected tests and derivations, and record it.
   (c) The test is wrong: fix it with a new hand derivation, and record it.
3. Decide:
   - If you changed PROMPT.md in step 2, run another round (round2, round3) with the new text and repeat.
   - If all 5 attempts fail at least one hidden test for reason (a), freeze.
   - If 4 of 5 do, add the next hardening lever from PLAN.md §4.7 and run one more round. If that round is 4 or 5 of 5 again, freeze.
   - If fewer than 4 of 5 do, add the next hardening lever and run another round.
   - Adding a lever means: new numbered rules, error table rows and its place in the check order, "not errors" notes if needed, at least 4 new hand-derived tests (format of PLAN.md §3.3), the P2 audit checklist applied to the changed parts, and coverage.md updated.
   - Stop after 3 rounds in total. If the freeze condition is still not met, tell me and record it.
4. Freeze: run sha256sum task/PROMPT.md and write "PROMPT.md: FROZEN sha256=... on YYYY-MM-DD" in STATE.md.

Done when: PROMPT.md is frozen (or 3 rounds are used up and I'm told why); the pilot notes list every failure with (a), (b) or (c); all tests compile and run on the stub.
Finish per G10 with the commit message "P8: pilot and freeze".
~~~

### P9: Runner full pipeline (Sonnet 5.5, effort medium, 45–60 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1, §3 and §6, work/STATE.md and the existing files in wal-recovery/runner/. Follow the ground rules in PLAN.md §3.4. Do only this task.

Finish the runner (PLAN.md §6):
1. Default offline mode (python3 runner/run.py): test the reference 3 times (if reference/WalRecovery.java exists; otherwise mark it "missing" in the report) and every results/gemini_run_k/. Write test_results.json per attempt, results/reference_runs.json, results/report.md and results/summary.json (schema §6.6; model data comes from the meta.json files and config.json).
2. --fresh --out DIR [--attempts N]: the Gemini attempts (reuse the P3 code), then the offline steps on DIR.
3. Parse the --mutants flag but leave its work to P10.
4. report.md layout as in §6.6. Its summary table is shaped like the PDF example: "reference 52 / 52", "gemini attempt 1 44 / 52", and so on.
5. Deterministic output: no timestamps, stable ordering, and the same bytes on a second run.

Test it without touching results/: copy the latest work/pilot/roundN folder to work/scratch/fake_results/, add a --results DIR option for the offline mode, run it twice on that folder, and diff -r the outputs (they must be identical). Also run --fresh --out ../work/scratch/fresh_test --attempts 1 once.

Done when: both modes work; two offline runs give identical files; a missing reference is handled; STATE.md is updated.
Finish per G10 with the commit message "P9: runner pipeline".
~~~

### R: Reference solution (Opus 5.5, effort high, 60–90 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §3, §6.4 and §8, work/STATE.md and wal-recovery/task/PROMPT.md. Follow the ground rules in PLAN.md §3.4. You are the only session allowed to write wal-recovery/reference/WalRecovery.java.

Precondition: STATE.md says PROMPT.md is FROZEN. If not, stop and tell me.

Independence rules for this session:
- Write the implementation from PROMPT.md alone. Do not open tests/hidden/, work/notes/ or work/pilot/ until your first complete version compiles.
- Never open any Gemini code (work/pilot/, results/), at any point.
- Never edit anything under tests/ or task/.

Steps:
1. If reference/WalRecovery.java already exists and STATE.md records H1 rulings, apply those rulings and go to step 4. Otherwise write reference/WalRecovery.java from scratch: the exact API from PROMPT.md, rule by rule, in the check order PROMPT.md gives. Plain, readable Java 21, standard library only, with a short comment naming the R-rule each check implements.
2. Read PROMPT.md once more against your code, rule by rule, and fix anything you missed.
3. From wal-recovery/, run: python3 runner/run.py --test reference/WalRecovery.java
4. For each failing test, work out the expected outcome by hand from PROMPT.md. If your code is wrong, fix the code. If you believe the test is wrong, do not touch it: add it under "Disputed tests" in STATE.md with your derivation and the rule, and move on.
5. Repeat steps 3–4 until every test passes or only disputed tests fail.
6. Run the test command 3 times in a row and confirm the results are identical.

Done when: every public and hidden test passes on 3 runs (STATE.md: "Reference: PASSING (3 runs)"), or the only failures are listed under Disputed tests. In that case, tell me to run H1 and then this prompt again.
Finish per G10 with the commit message "R: reference solution".
~~~

### H1: Decide disputed tests, only if R lists any (Opus 5.5, effort high, 20–40 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §3–§5, work/STATE.md and wal-recovery/task/PROMPT.md. Follow the ground rules in PLAN.md §3.4.

You are a neutral judge between the hand-derived tests and the reference code. The tests in question are listed under "Disputed tests" in STATE.md. Do not edit reference/ (G3).

For each disputed test:
1. Before reading the test's derivation or the reference code, work out the expected outcome yourself by hand from PROMPT.md, step by step.
2. Then compare it with the test's expected line, and with what the reference returns (from wal-recovery/: python3 runner/run.py --test reference/WalRecovery.java).
3. Rule on it:
   - The test is wrong: fix its expected line and derivation from your derivation (G4: never from the code's output), and update work/notes/test-audit.md.
   - The code is wrong: write the ruling in STATE.md with the rule and your derivation. Session R applies it.
   - PROMPT.md is ambiguous at this point: stop and tell me. PROMPT.md is frozen, so this needs a decision (PLAN.md §10).
4. Move the entry from "Disputed tests" to the session log with your ruling.

Done when: every disputed test has a ruling recorded in STATE.md, and you have told me whether to run R again.
Finish per G10 with the commit message "H1: rulings on disputed tests".
~~~

### P10: Mutants (Sonnet 5.5, effort high, 30–60 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §3, §5 and §6, work/STATE.md and wal-recovery/task/PROMPT.md. Follow the ground rules in PLAN.md §3.4. Do only this task.

Precondition: python3 runner/run.py --test reference/WalRecovery.java (from wal-recovery/) passes every test. If not, stop and tell me.

1. Read reference/WalRecovery.java. Do not modify it (G3).
2. Create reference/mutants/mutants.json with exactly 8 mutants: id M1–M8, area, description (the bug in one sentence), find (exact text that occurs exactly once in the file), replace. Each is one small, realistic bug in a different area; use the ideas in PLAN.md §5.1 where they fit the code.
3. Implement --mutants in run.py: for each mutant, copy the reference to build/mutants/Mk/, apply find → replace (fail loudly if find does not occur exactly once), compile, and run the hidden tests. Killed = it compiles and fails at least 1 hidden test. A mutant that does not compile does not count; replace it with another.
4. Write results/mutants.json (id, description, killed, failing tests) and add a mutants section to report.md and summary.json.
5. If a mutant survives, write a new hidden test that kills it, derived by hand from PROMPT.md with a full derivation (G3, G4), add it to coverage.md and test-audit.md, and run everything again. The reference must still pass 100%.

Done when: 8 of 8 mutants are killed; the reference passes every test; STATE.md says "Mutants: 8/8 killed".
Finish per G10 with the commit message "P10: mutants".
~~~

### P11: Official run (Sonnet 5.5, effort medium, 30–60 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1, §3, §6 and §10, and work/STATE.md. Follow the ground rules in PLAN.md §3.4. Do only this task.

Preconditions. Check all of them; if any fails, stop and tell me:
- STATE.md says PROMPT.md is FROZEN, and sha256sum task/PROMPT.md matches the recorded hash.
- STATE.md says the hidden tests are audited and the mutants are 8/8.
- python3 runner/run.py --test reference/WalRecovery.java passes everything.
- results/ contains no gemini_run_* folders yet.

Steps (from wal-recovery/):
1. Re-check the newest Gemini Pro model today (--models, plus Google's docs and changelog). If it differs from config.json, update config.json and STATE.md with the reason.
2. Run once: python3 runner/run.py --fresh --out results. If an attempt gets no answer after all retries, re-run only that attempt and log it in STATE.md. Never re-run an attempt that received an answer, and never re-run the set to get a better result.
3. Run python3 runner/run.py twice and diff the outputs; they must be identical.
4. Read summary.json, count the attempts that fail at least one hidden test, and write the count in STATE.md.
5. If fewer than 4 of 5 fail: stop. Do not re-run. Tell me, and point me to PLAN.md §10.

Done when: results/ holds 5 attempts with all their files; summary.json has the model, settings and date; the reference passed 3 runs; the acceptance result is recorded.
Finish per G10 with the commit message "P11: official run".
~~~

### P12: Failure analysis (Opus 5.5, effort xhigh, 60–120 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §3 and §6, work/STATE.md, wal-recovery/task/PROMPT.md, wal-recovery/results/ (summary.json, report.md, each gemini_run_k/test_results.json and WalRecovery.java) and the test derivations in tests/. Follow the ground rules in PLAN.md §3.4 (G6: never edit Gemini's saved files). Do only this task.

Write wal-recovery/FAILURE_ANALYSIS.md.

1. List every failing test (public and hidden) in every attempt. Group them: the same test with the same root cause is one entry covering all its attempts; the same test with a different root cause gets a separate entry.
2. For each entry, use this structure:
   ## F1: <test id> <test name>
   1. The test and what it checks.
   2. The rule: its R-number and the exact sentence(s) copied from PROMPT.md, in quotes.
   3. Expected vs actual: the expected line, and what Gemini returned (with attempt numbers). Then the derivation of the expected value as numbered steps a reviewer can check by hand (offsets, sizes, each check in order).
   4. Root cause: results/gemini_run_k/WalRecovery.java lines a–b, what the code does, and why that gives the wrong answer. Confirm it by tracing the code on the test input. You may run a temporary copy with debug prints under build/; never edit results/.
   5. Category, exactly one of: misread a rule / wrong boundary / wrong ordering or tie-break / missed an error case / race or concurrency bug / performance failure / other.
   6. Attempts: n of 5 (list them).
3. Start the file with a summary: the pass/fail table from summary.json, then a table of entries (entry, test, category, attempts).
4. End with two short paragraphs, "Why this task is hard for a model" and "Why these failures are not caused by unclear text". Be concrete: cite the rules, and the fact that each rule is one stated sentence with a stated check order. Also say that the task text was piloted against Gemini during design, and that the five reported attempts are the first five runs of the frozen text. And say that every expected value was worked out by hand from PROMPT.md and cross-checked against a reference solution written separately from PROMPT.md alone.
5. Check with a script: every quoted rule sentence is an exact substring of PROMPT.md; every failing (attempt, test) pair in summary.json appears in some entry; the counts match.

Done when: the checks in step 5 pass; STATE.md says "Analysis: draft".
Finish per G10 with the commit message "P12: failure analysis".
~~~

### P13: Independent check of the analysis (Opus 5.5, effort high, 45–60 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §3, work/STATE.md, wal-recovery/task/PROMPT.md, wal-recovery/FAILURE_ANALYSIS.md and wal-recovery/results/. Follow the ground rules in PLAN.md §3.4. Do only this task.

You did not write FAILURE_ANALYSIS.md. Check it like a reviewer who rejects unfair failures. For each entry:
1. The quoted rule exists word for word and, with its stated check order, decides the answer.
2. Re-derive the expected value yourself by hand before reading the entry's derivation, then compare.
3. The actual value matches test_results.json.
4. The root cause is right: open the Gemini file at the cited lines, trace the test input through it, and confirm both the line numbers and the explanation.
5. The category and the attempt count are right.
6. Fairness: could a careful reader of PROMPT.md reach Gemini's answer? If yes, that failure is not fair. Tell me clearly, record it in STATE.md, do not hide it, and recount how many attempts still have a fair hidden-test failure.

Write work/notes/fa-verify.md (one row per entry: ok / fixed / unfair). Fix the errors in FAILURE_ANALYSIS.md.

Done when: every entry is ok or fixed; any unfair failure is reported to me with the recount; STATE.md says "Analysis: verified".
Finish per G10 with the commit message "P13: analysis verified".
~~~

### P14: README, final checks, ZIP, reply draft (Sonnet 5.5, effort medium, ~30 min)

~~~
You are one session in a chain that builds a coding-assignment package. Read /root/Documents/test/PLAN.md §1, §3, §6 and §9, and work/STATE.md. Follow the ground rules in PLAN.md §3.4. Do only this task.

1. Write wal-recovery/README.md from PLAN.md §6.7: 10 lines or fewer; change it only if the commands changed.
2. Go through the checklist in PLAN.md §9 item by item. Prove each item with a command or a file reference, and write the result to work/notes/final-checklist.md. Fix small gaps; for a big gap, stop and tell me.
3. Prove the README works on its own: copy wal-recovery/ (without build/) to /root/Documents/test/work/scratch/fresh-copy/, run python3 runner/run.py there, and confirm the reference passes 3 runs and the results match the committed ones.
4. Build the ZIP with Python's zipfile. Name: <INITIALS>-wal-recovery-<YYYYMMDD>.zip (initials from STATE.md, today's date), saved in /root/Documents/test/. The ZIP root holds README.md, task/, reference/, tests/, runner/, results/ and FAILURE_ANALYSIS.md. Exclude build/, *.class, __pycache__/, .git, .vscode, .idea, .gitkeep, and everything outside wal-recovery/. List the ZIP's contents and check them against PLAN.md §3.1.
5. Draft my reply to the client in work/notes/reply.md (short, plain English): thanks; the ZIP is attached; the topic in one sentence; the result in one line (reference N/N on 3 runs; Gemini model X failed at least one hidden test in k of 5 attempts); language: Java 21 for the task and reference, Python 3 for the runner; time spent: [HOURS: fill in]; and this honest line: "As agreed with Ivan, I built the package with help from Claude Code, including the reference solution." End with my name from STATE.md.

Done when: the ZIP exists and its listing matches; the fresh-copy run matches; reply.md is ready; STATE.md is updated.
Finish per G10 with the commit message "P14: package".
~~~

---

## 8. Keeping the reference independent

The PDF still requires expected values "worked out independently" and "derivable from PROMPT.md". Now that AI writes both the tests and the reference, these rules keep them from simply agreeing with each other:
- The tests are derived by hand in P5–P7, before any implementation exists.
- The reference is written in its own session (R) from PROMPT.md alone. R doesn't read the hidden tests until its first version compiles, and it never reads Gemini's code.
- Neither side is changed just to match the other. A disagreement is settled by hand derivation from PROMPT.md: R fixes its own code, and a separate session (H1) rules on any test R disputes.
- FAILURE_ANALYSIS.md says the expected values were worked out by hand and cross-checked against the separately written reference. That cross-check is the "independent second method" in PDF §3.3.

---

## 9. Final acceptance checklist

Structure and packaging (PDF §2, §7)
- [ ] ZIP named `<INITIALS>-wal-recovery-<YYYYMMDD>.zip`, with the §2 structure at the ZIP root
- [ ] No build output, `.class` files, binaries or editor folders in the ZIP
- [ ] README.md is 10 lines or fewer, gives one command, and names what is needed (JDK 21+, Python 3, GEMINI_API_KEY only for `--fresh`)

Task text (PDF §3.2)
- [ ] Self-contained: fixed API with exact signatures, numbered rules, at least 2 worked examples with exact outputs, every error case listed
- [ ] Fair: every behaviour a hidden test checks is stated in the text (P2, P7 and P13 notes)
- [ ] Hard through interacting rules: ordering, boundaries, error precedence, limits
- [ ] Deterministic: no clock, time zone, locale, randomness, hash order or thread timing
- [ ] Frozen: the hash in STATE.md matches

Reference and tests (PDF §3.3)
- [ ] The reference passes 100% of public and hidden tests on 3 consecutive runs (`results/reference_runs.json`)
- [ ] At least 40 hidden tests (plan: 48+), each with rule numbers and a hand derivation; audit recorded in `work/notes/test-audit.md`
- [ ] Boundaries, every error case, validation order, interacting rules and input-not-modified are all covered (`coverage.md`)
- [ ] 8 of 8 mutants killed (`results/mutants.json`)

Runner (PDF §4)
- [ ] PROMPT.md sent with the fixed wrapper, identical for all attempts (`results/prompt_sent.txt` plus its SHA-256)
- [ ] Newest Gemini Pro on the run day; exact model name, settings and date in `summary.json`; defaults unchanged
- [ ] 5 independent attempts; raw answers saved untouched (`response.json`)
- [ ] Code extracted without edits (`extraction.json` offsets match `answer.txt`)
- [ ] Same test files and commands for the reference and all 5 attempts
- [ ] Report: every attempt × every test, pass or fail, and actual vs expected for each failure, plus a summary table
- [ ] One command reruns everything and gives the same results

Result and analysis (PDF §5, §6)
- [ ] At least 4 of 5 attempts fail at least one hidden test, for a fair reason
- [ ] FAILURE_ANALYSIS.md: every distinct failing test, with all 6 items, rules quoted word for word, and the closing paragraphs
- [ ] Every failure confirmed fair in P13

Reply (PDF §7)
- [ ] Hours spent (yours, honest) and language(s) used, in the message with the ZIP

---

## 10. Risks and fallbacks

| Risk | How you notice | What to do |
|---|---|---|
| Gemini passes the task | P8 pilot attempts pass every hidden test | Hardening levers (§4.7), inside P8 |
| A failure is not fair | P8 or P13 finds that Gemini's reading is also reasonable | Before the freeze: clarify PROMPT.md. After the freeze: that failure doesn't count; recount |
| Official run: fewer than 4 of 5 fail | P11 step 5 | Don't re-run the same text. Make a version 2: add one hardening lever (P8-style), run P2 on the changed parts, add tests (P6-style) and audit them (P7-style), pilot again, then do a new official run into a fresh `results/`. Keep the old run in `work/` and say so in the reply ("the first official run on version 1 had k of 5 failing; I tightened the task and re-ran") |
| The reference disagrees with a test | R lists disputed tests in STATE.md | H1 rules by hand derivation. A test changes only on a derivation error, never to match code |
| Gemini API problems | HTTP 429 or 5xx, quota errors | Built-in retries; wait; re-run only attempts that got no answer |
| Gemini's answer is cut off | `finishReason` is MAX_TOKENS | It still counts as the model's answer (settings stay at defaults); the analysis says so |
| Running out of time | — | Don't skip P7, P8 or P13: they are what protect you from rejection |
