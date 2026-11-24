# How to run the Gemini tests yourself

Written 2026-11-24 for Sam Joe. Follow the stages in order.

**Where to type commands:** a terminal in VS Code (**Terminal → New Terminal**). Before each stage, run:

```
cd /root/Documents/test/wal-recovery
```

All commands below assume you are in that folder.

---

## The three stages

| Stage | What it is | Gemini requests | When |
|---|---|---|---|
| 1. Smoke test | One request, to prove the key and the runner work | 1 | Now, once billing is on |
| 2. Pilot (plan step P8) | 5 attempts on the draft task, to check that Gemini fails **fairly**, then freeze the task | 5 per round, at most 3 rounds | Right after stage 1 |
| 3. Official run (plan step P11) | The 5 attempts that go to the client | 5, **only once** | After Claude finishes steps R, P9 and P10 |

Pilot results are design work: they stay in `work/pilot/` and never go to the client. Only stage 3 goes into `results/` and the ZIP.

---

## Rules you must keep (the client checks these)

1. **Never change the settings.** The runner sends the task with Google's default settings and the fixed wrapper sentence from `runner/config.json`. Do not edit `config.json` or `task/PROMPT.md` by hand.
2. **Use the runner, not the Gemini website.** The Gemini app and the AI Studio chat page use their own settings and don't save the raw answer. The client wants the raw API answer (`response.json`) and the exact model version.
3. **Never re-roll.** An attempt that got an answer counts, even a bad or cut-off answer. You may repeat an attempt **only** if it got no answer at all (HTTP 429, 5xx or a network error after all retries).
4. **Never edit Gemini's saved files** (`request.json`, `response.json`, `answer.txt`, `WalRecovery.java`, `extraction.json`, `meta.json`). If code looks broken, that is the result.
5. **Keep the key secret.** Never paste it into a chat, a file in this folder or a command. The runner reads it from `~/.gemini_api_key`.
6. **Don't show Gemini the tests.** Don't paste tests, results or notes into any Gemini chat.
7. **Write down what you did:** date, model, and anything unusual (a retry, an error). You can also just tell Claude, and Claude will record it in `work/STATE.md`.

---

## Stage 1: Smoke test (1 request)

**Before you start:** billing must be on for your key (https://aistudio.google.com/apikey → **Set up billing**). On the free tier, every request fails with HTTP 429.

**1a. Check that the model still exists:**

```
python3 runner/run.py --models
```

The list should include `models/gemini-3.1-pro-preview`. If Google has since released a newer Gemini **Pro** model, stop and tell Claude before going on.

**1b. Send one request** (it can take several minutes, because Gemini thinks for a long time):

```
python3 runner/run.py --pilot 1 --out ../work/pilot/smoke2
```

**1c. Check the result:**

```
cat ../work/pilot/smoke2/gemini_run_1/meta.json
```

Good result:
- `"http_status": 200`
- `"finishReason": "STOP"`
- `"modelVersion"` filled in, not `null`

If the status is 429, billing is not active yet: wait 10–15 minutes and repeat 1b. If `finishReason` is `MAX_TOKENS`, the answer was cut off. That's allowed, but tell Claude.

```
cat ../work/pilot/smoke2/gemini_run_1/extraction.json
```

Good result: `"rule": "java_block"` (or `"first_block"`), `"self_check_substring": true`, and `"end"` larger than 0.

**1d. Check that the extracted code runs through the tests:**

```
python3 runner/run.py --test ../work/pilot/smoke2/gemini_run_1/WalRecovery.java
```

It must finish and print `TOTAL: x / 72 passed`. Any number is fine here: this only proves the pipeline works.

**Tell Claude:** "smoke done". Claude closes the two open issues in STATE.md and commits.

---

## Stage 2: Pilot (plan step P8)

Goal: Gemini should fail at least one hidden test in **at least 4 of 5** attempts, and each failure must be **fair**. Fair means the task text states the rule clearly and Gemini broke it.

### 2a. Run round 1 (5 requests, one after another; allow 15–60 minutes)

```
python3 runner/run.py --pilot 5 --out ../work/pilot/round1
```

Each attempt prints one line with its HTTP status and finishReason. If an attempt has no answer after all retries (HTTP 429 or 5xx), note its number and tell Claude; don't run the whole round again.

### 2b. Test each attempt and save the output

```
for k in 1 2 3 4 5; do
  python3 runner/run.py --test ../work/pilot/round1/gemini_run_$k/WalRecovery.java \
    > ../work/pilot/round1/gemini_run_$k/test_output.txt 2>&1
  echo "attempt $k: $(grep '^hidden' ../work/pilot/round1/gemini_run_$k/test_output.txt)"
done
```

Example output: `attempt 1: hidden: 61 / 69 passed`. Any attempt below 69/69 failed at least one hidden test.

### 2c. Judge every failing test

Open `../work/pilot/round1/gemini_run_k/test_output.txt`. Each failure shows the test id (for example `H07`), the expected line and Gemini's actual line.

For each failure:

1. Find the test in `tests/hidden/HiddenTests.java` (search for `// H07`). Its comment gives the rules (`Rules: R10, R12`) and a step-by-step derivation.
2. Read those rules in `task/PROMPT.md`.
3. Decide on one label:
   - **(a) Fair failure:** the rule is stated clearly and Gemini broke it. Copy the rule sentence.
   - **(b) The task text is unclear or silent:** a careful reader could reach Gemini's answer. The task text must be clarified.
   - **(c) The test is wrong:** the derivation has a mistake.

Write the results in `../work/pilot/round1/notes.md`:

```
| Attempt | Test | Expected | Gemini | Label | Rule / reason |
|---|---|---|---|---|---|
| 1 | H07 | OK ... validLength=13 tornTail=true | ERR CHECKSUM_MISMATCH@13 | (a) | R10: "..." |
```

**If labelling is too much work, you can stop after 2b and tell Claude "round 1 done".** Claude can do 2c in a fresh session that never wrote the tests. (b) and (c) mean changing the task text or a test; Claude must do those, because each change needs a hand derivation and a re-check.

### 2d. Decide what happens next

Count the attempts that have at least one **(a)** failure:

| Result | What happens |
|---|---|
| Any (b) or (c) found | Claude fixes the task text or the test. Then run round 2 (`--out ../work/pilot/round2`) with the new text. |
| 5 of 5 have an (a) failure | **Freeze** (2e). |
| 4 of 5 | Claude adds one "hardening" rule (PLAN.md §4.7) and you run one more round. If that round gives 4 or 5 of 5, freeze. |
| 3 of 5 or fewer | Claude adds a hardening rule and you run another round. |

There are **at most 3 rounds**. If the result is still not enough after round 3, stop and decide with Claude.

### 2e. Freeze the task text

Claude does this when you say "freeze". For reference, it means:

```
sha256sum task/PROMPT.md
```

The hash is written into `work/STATE.md` as `PROMPT.md: FROZEN sha256=... on YYYY-MM-DD`. **After this, `task/PROMPT.md` must never change.**

---

## Between stage 2 and stage 3 (Claude, no Gemini needed)

- **R:** the reference solution, written from the frozen task text alone. It must pass all 72 tests on 3 runs.
- **P9:** finishes the runner (`--fresh` mode, the offline re-test, `report.md` and `summary.json`). P9 normally makes one test request to Gemini; you can make it yourself, or Claude skips it.
- **P10:** 8 mutants (deliberately broken copies of the reference); the tests must catch all 8.

Claude tells you when these are done. Don't start stage 3 before that: `--fresh` is not finished until P9.

---

## Stage 3: Official run (plan step P11). Do it ONCE.

### 3a. Check the preconditions (all must be true)

```
grep 'PROMPT.md: FROZEN' ../work/STATE.md       # shows the frozen hash
sha256sum task/PROMPT.md                         # must equal that hash
python3 runner/run.py --test reference/WalRecovery.java   # must say TOTAL: 72 / 72 passed (or the final count)
ls results/                                      # must have NO gemini_run_* folders
```

If anything is wrong, stop and tell Claude.

### 3b. Check the model on the day

```
python3 runner/run.py --models
```

Also check Google's model page (https://ai.google.dev/gemini-api/docs/models). If a newer Gemini **Pro** model exists, tell Claude before you start; `config.json` must be updated and the reason recorded.

### 3c. Run the 5 attempts (once)

```
python3 runner/run.py --fresh --out results
```

- If an attempt got **no** answer after all retries, tell Claude. Only that attempt is repeated, and the repeat is written down.
- **Never** run it again because the results look bad, or too good.

### 3d. Re-test offline twice, and check that the results are identical

```
python3 runner/run.py
cp results/summary.json ../work/scratch/summary_1.json
python3 runner/run.py
diff ../work/scratch/summary_1.json results/summary.json && echo IDENTICAL
```

### 3e. Read the result

```
cat results/report.md
```

The summary table shows, for example, `gemini attempt 1   61 / 69`. You need **at least 4 of 5 attempts below full marks on the hidden tests**. If fewer fail, **don't re-run**: tell Claude (PLAN.md §10 has the fallback).

**Tell Claude:** "official run done". Claude writes the failure analysis (P12), checks it (P13), and builds the ZIP and your reply (P14).

---

## If something goes wrong

| What you see | Meaning | What to do |
|---|---|---|
| HTTP 429, "free tier", "limit: 0" | Billing is not active | Turn billing on; wait 10–15 min |
| HTTP 429 after billing is on | Too many requests per minute | The runner already retries 3 times; wait 10 minutes, then repeat only the failed attempt |
| HTTP 400 "API key not valid" | Key problem | Check `~/.gemini_api_key` (one line, the key only) |
| HTTP 404 model not found | Model renamed or withdrawn | Run `--models` and tell Claude |
| `finishReason: MAX_TOKENS` | The answer was cut off | Still counts; tell Claude so the analysis mentions it |
| `COMPILE_ERROR` on every test | Gemini's code doesn't compile | Still counts as a failure; don't fix it |
| The command seems stuck | Gemini is thinking (up to 10 min per attempt) | Wait; the timeout is 600 s per request |
