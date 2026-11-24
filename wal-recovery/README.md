# WAL crash recovery: a hard task for Gemini (Java 21)
Needs Java 21 (`javac`, `java`) and Python 3.10+, no packages. Run every command from this folder.
Rerun all tests (reference 3 times, plus the 5 saved Gemini answers), no API key needed: `python3 runner/run.py`
  Writes `results/summary.json`, `results/report.md`, and `results/gemini_run_k/test_results.json`.
Mutation check (8 bugs in the reference, `reference/mutants/`): `python3 runner/run.py --mutants`
New Gemini run (replaces `results/`): `python3 runner/run.py --fresh --out results`
  Needs an OneProvider API key in `ONEPROVIDER_API_KEY` or `~/.oneprovider_api_key`. Model `gemini-4-carbon` and settings are in `runner/config.json`.
Task: `task/PROMPT.md`. Reference: `reference/WalRecovery.java`. Tests: `tests/public`, `tests/hidden` (173), harness in `runner/harness`.
Each test has a 5 s limit. The slowest reference case takes about 1.7 s here, which leaves room for slower machines.
Analysis: `FAILURE_ANALYSIS.md`. Raw model answers: `results/gemini_run_k/response.sse` and `answer.txt`.
