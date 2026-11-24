# P8 pilot round 3: Gemini 4 Carbon via OneProvider, PROMPT.md v4 (2026-11-24)

Task text: PROMPT.md DRAFT v4 (442 lines; L1 + L2 + L3). Same runner and settings as round 2
(work/notes/pilot-round2.md): streamed, one user message, max_tokens 32768, nothing else.

## Results

| Folder | finish | prompt_tokens (Google count 10990) | hidden tokens | out tokens | seconds | public | hidden |
|---|---|---|---|---|---|---|---|
| round3_carbon/gemini_run_1 | stop | 10990 | 0 | 11635 | 149 | 5/5 | 123/123 |
| round3_carbon/gemini_run_2 | stop | 12777 | 1787 | 4852 | 58 | 5/5 | 123/123 |
| round3_carbon/gemini_run_3 | stop | 12777 | 1787 | 12997 | 150 | 5/5 | 123/123 |
| round3_carbon/gemini_run_4 | stop | 12777 | 1787 | 4827 | 62 | 5/5 | 123/123 |
| round3_carbon/gemini_run_5 | stop | 12777 | 1787 | 5025 | 64 | 5/5 | 123/123 |

No retries; extraction first_java_block in all 5. Cost of the round about $0.87 (balance $24.74 -> $23.87).

## Failures and classification (a)/(b)/(c)
None: 0 failing tests in 5 attempts.

## Decision per P8 step 3
0 of 5 attempts fail a hidden test. Rounds used: 3 (round 1 web/Hydrogen on v2, round 2 Carbon on v3, round 3
Carbon on v4). P8: "Stop after 3 rounds in total. If the freeze condition is still not met, tell me and
record it." The freeze condition (at least 4 of 5 attempts failing a hidden test for a fair reason) is not
met, and PLAN.md §4.7 has no lever left after L3. PROMPT.md is NOT frozen. Over rounds 2 and 3, Gemini 4
Carbon passed every public and hidden test in all 11 attempts (smoke + 10), across v3 (105 tests) and v4
(128 tests). Hidden prompt 0 or 1787 tokens here; both kinds pass.
