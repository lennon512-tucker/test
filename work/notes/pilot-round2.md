# P8 pilot round 2: Gemini 4 Carbon via OneProvider (2026-11-24)

Task text: PROMPT.md DRAFT v3 (401 lines, L1 + L2 added). Paste/prompt = wrapper + blank line + PROMPT.md
(prompt_sent.txt in each folder; sha256 in each meta.json).

Provider: OneProvider, https://api.oneprovider.dev/v1/chat/completions (OpenAI-compatible), streamed.
Sent: model, one user message, max_tokens 32768 (the model's output maximum), stream, stream_options.
No system message, no sampling settings. Runner: runner/oneprovider.py, run.py run_attempts().

## Results

| Folder | finish | prompt_tokens (Google count 9638) | hidden tokens | out tokens | extraction | public | hidden |
|---|---|---|---|---|---|---|---|
| smoke_op/gemini_run_1 | stop | 9638 | 0 | 4515 | first_java_block | 4/4 | 101/101 |
| round2_carbon/gemini_run_1 | stop | 11154 | 1516 | 4726 | first_java_block | 4/4 | 101/101 |
| round2_carbon/gemini_run_2 | stop | 11154 | 1516 | 4717 | first_java_block | 4/4 | 101/101 |
| round2_carbon/gemini_run_3 | stop | 9638 | 0 | 4650 | first_java_block | 4/4 | 101/101 |
| round2_carbon/gemini_run_4 | stop | 11154 | 1516 | 4641 | first_java_block | 4/4 | 101/101 |
| round2_carbon/gemini_run_5 | stop | 11154 | 1516 | 4495 | first_java_block | 4/4 | 101/101 |

No retries. Every attempt answered in about 1 minute. All code came through intact (no HTML stripping).

## Failures and classification (a)/(b)/(c)
None: 0 failing tests in 6 attempts, so nothing to classify.

## Hidden prompt
OneProvider adds hidden system text upstream on some requests (described by the model as
"Antigravity" agent instructions). In this round it was either 0 or exactly 1516 tokens. Attempts with 0
and with 1516 both pass 105/105, so it did not decide any outcome here. It remains a deviation from
"Gemini API, default settings" (PLAN §1) to report to the client.

## Decision per P8 step 3
0 of 5 attempts fail a hidden test ("fewer than 4 of 5") -> add the next lever (L3, CHECKPOINT) and run
another round. Rounds used: round 1 (web, Hydrogen, 1 attempt on v2), round 2 (this; the web round 2
copies were invalid and are not counted). L3 is the last lever in PLAN §4.7 and round 3 is the last
round P8 allows. L1 + L2 together did not produce a single failure, so L3 alone is unlikely to reach
4 of 5. Asked the user how to proceed.
