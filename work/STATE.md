# STATE: WAL recovery assignment
Initials: SJ   Name: Sam Joe   Started: 2026-11-23
PROMPT.md: DRAFT v2 (audited)    (when frozen: FROZEN sha256=... on YYYY-MM-DD)
Gemini model: gemini-3.1-pro-preview   (chosen 2026-11-24: the only current non-deprecated Pro text model in --models output and the docs models page/changelog; concrete id preferred over alias gemini-pro-latest; gemini-2.5-pro deprecated)
Hidden tests: 0                  audited: no
Reference: not started
Mutants: 0/8 killed
Official run: not done           (date; attempts failing >=1 hidden test: x/5)
Analysis: not started

## Session log
| Session | Date | Result | Notes |
|---|---|---|---|
| P0 | 2026-11-23 | done, 2 open issues | Installed OpenJDK 21.0.13.1 (javac + java); Python 3.12.3; folders per PLAN §3.1 (no gemini_run_* yet); .gitignore; git init. Gemini key: missing. |
| P0 follow-up | 2026-11-24 | done | Gemini key stored by the user in ~/.gemini_api_key (chmod 600), outside the repo; never committed. Verified with models.list: HTTP 200. Key is 53 chars and does not start with "AIza" (newer key format), so P3 must not validate the key's format. Added .gemini_api_key to .gitignore. |
| P1 | 2026-11-24 | done | task/PROMPT.md DRAFT v1: 344 lines, 11 sections, rules R1-R27, precedence P1-P5, 3 worked examples. Skeleton compiles (work/scratch/skeleton, javac --release 21). Hand derivations and canonical lines for the public tests in work/notes/examples.md; every example checksum re-checked by hand. Example bytes from work/scratch/encode_examples.py (encode only); work/scratch/check_prompt_hex.py confirms the hex in PROMPT.md. |
| P2 | 2026-11-24 | done | Audit in work/notes/spec-audit.md: 13 findings fixed (1 M, 12 L), 8 explained as not a problem. Wording fixes only; rule numbers R1-R27/P1-P5 unchanged; example bytes and expected lines unchanged. Examples re-derived by hand and re-checked with work/scratch/p2_verify_examples.py; skeleton compiles and equals the PROMPT.md API block. PROMPT.md now 346 lines. |
| P3 | 2026-11-24 | partial: runner done, smoke call blocked | runner/{gemini,extract,run,selftest}.py, config.json, work/notes/gemini-api.md. selftest 7/7 pass. Smoke call to gemini-3.1-pro-preview returned HTTP 429 (free tier quota limit 0 for this model) after 3 retries; saved in work/pilot/smoke/. |
| P4 | 2026-11-24 | done | runner/harness/{Hex,LogBuilder,TestCase,Render,TestMain}.java, runner/javatest.py, run.py --test, runner/selfcheck/HarnessCheck.java (+ HarnessCheck class in selftest.py: LogBuilder vs PROMPT.md hex byte for byte, Fletcher check value, Render literals; selftest 10/10), tests/public/PublicTests.java (P01-P03; expected lines copied from examples.md and re-confirmed against PROMPT.md section 11), tests/hidden/HiddenTests.java (empty). TestMain also has a `list` mode used by javatest.py to get ids and expected lines for COMPILE_ERROR/NO_RESULT rows. Self-checks a-c, e pass (stubs in work/scratch/stub, stub_loop, stub_syntax, stub_misc, made from the PROMPT.md skeleton by text replacement only). (d) ran on the EMPTY smoke file: all 3 tests FAIL as COMPILE_ERROR (path works), but is not yet proven on a real Gemini answer. |

## Disputed tests (written by R, ruled on by H1)
- none

## Open issues
- P3: Gemini API key is on the Free Tier, where gemini-3.1-pro has quota limit 0 (HTTP 429, RESOURCE_EXHAUSTED). Enable billing on the key's Google Cloud project (or use a paid-tier key), then rerun: python3 runner/run.py --pilot 1 --out ../work/pilot/smoke. The smoke "done when" checks (answer saved, extraction substring) are unverified on a real answer.
- P4: self-check (d) was run on an empty WalRecovery.java (the 429 left it empty). Once billing is enabled and the smoke call has a real answer, repeat: python3 runner/run.py --test ../work/pilot/smoke/gemini_run_1/WalRecovery.java (must run to completion). Not a blocker for P4.
