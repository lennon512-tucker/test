# STATE: WAL recovery assignment
Initials: SJ   Name: Sam Joe   Started: 2026-11-23
PROMPT.md: DRAFT v2 (audited)    (when frozen: FROZEN sha256=... on YYYY-MM-DD)
Gemini model: not chosen         (model id, chosen on, how)
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

## Disputed tests (written by R, ruled on by H1)
- none

## Open issues
- none
