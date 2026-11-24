# Draft reply to Ivan (not sent; attach SJ-wal-recovery-20261124.zip)

---

Hi Ivan,

Attached is my submission: **SJ-wal-recovery-20261124.zip**, in the structure from section 2 of the brief.

- **Task:** crash recovery from a write-ahead log, in Java 21. The runner is in Python 3 (standard library only). PROMPT.md defines a byte-level log format with checksums and torn-tail rules, plus a transactional replay. The replay covers savepoints, write limits, checkpoints, snapshot reads and range (phantom) conflicts, with a fixed check order and error precedence.
- **Reference solution:** passes all 178 tests (5 public, 173 hidden) on 3 consecutive runs.
- **Hidden tests:** 173. Each expected value was worked out by hand from PROMPT.md and checked again in a separate blind audit. The reference was written separately from PROMPT.md alone and agrees on all 173.
- **Mutants:** the hidden tests catch all 8 of 8.
- **Gemini result:** 2 of 5 attempts fail a hidden test (H151, 172/173). Both failures have the same root cause: the conflict check runs at the other transaction's commit, so a range recorded later is missed. This contradicts R22, which bounds the conflict window by the transaction's BEGIN and COMMIT. The full analysis is in FAILURE_ANALYSIS.md. This is below the 4 of 5 the brief asks for, and I wanted to say so plainly.
- **Model:** I used `gemini-4-carbon` (Gemini 4 Carbon) through an OpenAI-compatible gateway (OneProvider), not `gemini-3.1-pro-preview` through Google's Gemini API. My Gemini API key is on the free tier, where the Pro model has a quota of 0. Carbon is not named "Pro". The gateway sometimes adds its own system text, and it does not report an exact model version. All of this is recorded in results/summary.json. The only setting I changed is max_tokens, set to the model's output maximum.
- **Rerun:** `python3 runner/run.py` from the unpacked folder reruns every test with no API key. `--fresh` makes new Gemini attempts.

Time spent: **[X] hours**. Language: Java 21, with a Python 3 runner.

I built the package with the help of Claude Code, as we discussed.

Best regards,
Sam
