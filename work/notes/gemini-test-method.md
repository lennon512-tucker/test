# How to run the Gemini tests in the Gemini web app

Updated 2026-11-24 for Sam Joe. You run the Gemini attempts by hand in the Gemini web app (gemini.google.com). Claude does the testing and judging.

---

## Before the first attempt: set up once

1. **Check that the client allows the web app.** Our plan (PLAN.md §1) quotes the client's PDF §4 as "Gemini API, default settings". If the PDF says "API", ask Ivan whether the web app is acceptable, and keep his answer.
2. **Turn off memory.** In Gemini **Settings**, turn off the options that let Gemini use your past chats or saved info ("Personal context", "Saved info", or a similar name). Otherwise your earlier chats (SQL engine, BM25) can affect the answers. A **Temporary chat** also works if your account offers one.
3. **Choose the model.** Open the model picker (it shows "Hydrogen" now) and choose the newest **Pro** model. Write down its exact name **as shown**. If you can't tell which entry is Pro, take a screenshot of the open list and send it to Claude.
4. **Choose Chat mode**, not Task. Task mode lets Gemini work like an agent and run code, which is a different test.
5. **Don't add files or tools.** Paste text only. Don't attach files, and don't turn on Canvas, Deep Research or code execution.

---

## Rules for every attempt (the client checks these)

- **One attempt = one new chat.** Click **New chat** before every attempt.
- **Paste the exact text, unchanged.** Open `work/pilot/web/prompt_to_paste.txt` in VS Code, press **Ctrl+A**, **Ctrl+C**, then paste into Gemini with **Ctrl+V** and press Enter. Don't type anything before or after it. This file is exactly what the API would have sent: the wrapper sentence, a blank line, then the task.
- **Send once and wait.** Don't reply, don't press **Regenerate**, don't pick another draft, and don't ask follow-up questions.
- **Never re-roll.** A bad answer, an answer with no code, or an answer that stops halfway all count. Repeat an attempt only if Gemini gave **no answer at all** (an error message such as "Something went wrong"). Write down that you repeated it, and why.
- **Never edit the answer** after saving it, not even to fix an obvious typo.
- **Never show Gemini the tests**, the results or anything from this folder except `prompt_to_paste.txt`.

---

## Saving one attempt

The folders are ready: `work/pilot/web/round1/gemini_run_1` to `gemini_run_5`.

1. When Gemini has finished, click the **copy** button under the answer (the two-squares icon). Don't select the text with the mouse: the copy button keeps the ```` ```java ```` code fences, which the runner needs to find the code.
2. In VS Code, choose **File → New Text File**, press **Ctrl+V**, then **File → Save As…** and type `/root/Documents/test/work/pilot/web/round1/gemini_run_1/answer.txt` (use `gemini_run_2` for attempt 2, and so on).
3. Take a **screenshot** that shows the model name and the start of the answer. Save it in the same folder as `screenshot.png`.
4. Click **New chat** and do the next attempt.

If an answer is so long that Gemini shows "Show more" or splits it, expand it fully before you copy.

---

## Stage 1: one smoke attempt (optional, recommended)

Do **attempt 1 of round 1** first, save it, then run this test (see "Testing the answers" below). If the code is found and the tests run, do attempts 2–5. This catches a copying problem before you've spent all 5 attempts.

---

## Testing the answers (you run one command, or ask Claude)

Open a terminal in VS Code (**Terminal → New Terminal**) and run (put the exact model name in quotes, and use today's date):

```
cd /root/Documents/test/wal-recovery
python3 runner/run.py --import-web ../work/pilot/web/round1 --model "MODEL NAME AS SHOWN" --date 2026-11-24
```

It prints one line per attempt, for example:

```
gemini_run_1: rule=first_java_block | public: 3 / 3 passed | hidden: 61 / 69 passed
```

- `rule=first_java_block` means the code was found. `rule=whole_answer` means no code block was found; tell Claude.
- `hidden: 61 / 69` means Gemini failed 8 hidden tests in that attempt. Any number below 69 is a failure, which is what we want.
- The details of each failure are in `gemini_run_k/test_output.txt`.

Instead of running it yourself, you can tell Claude "round 1 saved, model = …", and Claude will run it.

---

## Stage 2: Pilot rounds (plan step P8)

**Goal:** at least **4 of 5** attempts fail at least one hidden test, and every failure is **fair**, meaning the task text states the rule clearly and Gemini broke it.

1. Do 5 attempts as above into `round1/gemini_run_1` … `gemini_run_5`.
2. Test them with `--import-web`.
3. Tell Claude **"round 1 done"**. A fresh Claude agent that never wrote the tests labels every failure:
   - **(a)** a fair failure;
   - **(b)** the task text is unclear, so Claude clarifies it;
   - **(c)** the test is wrong, so Claude fixes it.
4. Claude tells you the next step:
   - **Freeze**, if 4 or 5 of 5 have a fair failure and nothing needed fixing;
   - or **round 2**: Claude changes the task text and makes a new `prompt_to_paste.txt` and `round2` folders, and you do 5 new attempts there.

   There are at most 3 rounds. **Always paste the newest `prompt_to_paste.txt`.** Claude rewrites it whenever the task text changes.

Pilot results stay in `work/pilot/`; they never go to the client.

---

## Stage 3: Official run (plan step P11). Do it ONCE.

Start only when Claude says that **R, P9 and P10 are done**: the reference solution passes all tests, the runner is finished, and the mutants are killed. Claude will then:

- make a fresh `work/pilot/web/prompt_to_paste.txt` from the **frozen** task text and confirm its fingerprint (SHA-256);
- create the empty folders `wal-recovery/results/gemini_run_1` … `gemini_run_5`.

Then you:

1. Check the model picker again. If a newer **Pro** model has appeared since the pilot, tell Claude before you start.
2. Do 5 attempts exactly as above, saving each answer as `wal-recovery/results/gemini_run_k/answer.txt`, with a screenshot.
3. Tell Claude **"official run done, model = …, date = …"**.

**Never redo the official run** because the result looks bad. If fewer than 4 of 5 fail, tell Claude; PLAN.md §10 has the fallback.

---

## If something goes wrong

| What you see | What to do |
|---|---|
| Gemini shows an error and no answer | Repeat that attempt in a new chat; write down that you did |
| Gemini stops halfway ("cut off") | It still counts. Save what is there, and tell Claude |
| The answer has no code block, or several | Save it as is; the runner's rule decides (first `java` block, else first block, else the whole text) |
| `rule=whole_answer` after import | You probably copied by selecting text: copy again with the copy button, from the **same** chat, if it's still open |
| `COMPILE_ERROR` on every test | Gemini's code doesn't compile. That counts as a failure; don't fix it |
| Gemini asks a question instead of answering | Don't reply. It counts as an answer with no code |
