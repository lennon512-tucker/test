#!/usr/bin/env python3
"""Runner entry point. Run from wal-recovery/. Standard library only."""
import argparse, datetime, hashlib, json, os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import gemini, oneprovider, extract, javatest


def load_config():
    with open(os.path.join(HERE, "config.json"), encoding="utf-8") as f:
        return json.load(f)


def build_prompt(cfg):
    with open(os.path.join(ROOT, cfg["prompt_file"]), encoding="utf-8", newline="") as f:
        return cfg["wrapper"] + "\n\n" + f.read()


def write(path, data):
    with open(path, "wb") as f:
        f.write(data if isinstance(data, bytes) else data.encode("utf-8"))


def gemini_attempts(out_dir, n, cfg):
    prompt = build_prompt(cfg)
    os.makedirs(out_dir, exist_ok=True)
    write(os.path.join(out_dir, "prompt_sent.txt"), prompt)
    key = gemini.get_key()
    for k in range(1, n + 1):
        d = os.path.join(out_dir, "gemini_run_%d" % k)
        os.makedirs(d, exist_ok=True)
        raw, info = gemini.generate(cfg["model"], prompt, key, cfg["http_timeout_seconds"],
                                    tuple(cfg["retry_waits_seconds"]))
        write(os.path.join(d, "request.json"), info.pop("request_bytes"))
        write(os.path.join(d, "response.json"), raw)
        meta = {"attempt": k, "requested_model": cfg["model"], "modelVersion": None,
                "date_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
                "http_status": info["http_status"], "finishReason": None, "usage": None,
                "retries": info["retries"], "seconds": info["seconds"]}
        text = ""
        try:
            resp = json.loads(raw.decode("utf-8"))
            meta["modelVersion"] = resp.get("modelVersion")
            meta["usage"] = resp.get("usageMetadata")
            c = (resp.get("candidates") or [{}])[0]
            meta["finishReason"] = c.get("finishReason")
            text = extract.answer_text(raw)
        except Exception as e:
            meta["parse_error"] = type(e).__name__
        write(os.path.join(d, "answer.txt"), text)
        code, ex = extract.extract(text)
        write(os.path.join(d, "WalRecovery.java"), code)
        write(os.path.join(d, "extraction.json"), json.dumps(ex, indent=2) + "\n")
        write(os.path.join(d, "meta.json"), json.dumps(meta, indent=2) + "\n")
        print("attempt %d: HTTP %s finish=%s model=%s rule=%s" %
              (k, meta["http_status"], meta["finishReason"], meta["modelVersion"], ex["rule"]))
        # TODO (later sessions): test each attempt here (javatest.py) for --fresh.


def oneprovider_attempts(out_dir, n, cfg, test=True, first=1):
    """Attempts through OneProvider (OpenAI-compatible, streamed). Per attempt folder: request.json
    (key not included), response.sse (raw stream), answer.txt (content only, reasoning excluded),
    WalRecovery.java, extraction.json, meta.json and, with test=True, test_results.json and
    test_output.txt."""
    prompt = build_prompt(cfg)
    os.makedirs(out_dir, exist_ok=True)
    write(os.path.join(out_dir, "prompt_sent.txt"), prompt)
    key = oneprovider.get_key()
    baseline = gemini.count_tokens(cfg["model"], prompt)
    for k in range(first, first + n):
        d = os.path.join(out_dir, "gemini_run_%d" % k)
        os.makedirs(d, exist_ok=True)
        raw, parsed, info = oneprovider.generate(cfg["model"], prompt, key, cfg["max_tokens"],
                                                 cfg["http_timeout_seconds"], tuple(cfg["retry_waits_seconds"]))
        write(os.path.join(d, "request.json"), info.pop("request_bytes"))
        write(os.path.join(d, "response.sse" if parsed is not None else "response.json"), raw)
        parsed = parsed or {}
        usage = parsed.get("usage") or {}
        pt = usage.get("prompt_tokens")
        meta = {"attempt": k, "provider": "OneProvider (https://api.oneprovider.dev/v1/chat/completions)",
                "requested_model": cfg["model"], "model_in_response": parsed.get("model"),
                "response_id": parsed.get("id"),
                "date_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
                "settings": {"max_tokens": cfg["max_tokens"], "stream": True,
                             "other": "none sent (no system message, no sampling settings)"},
                "http_status": info["http_status"], "finish_reason": parsed.get("finish_reason"),
                "usage": usage or None, "reasoning_chars": parsed.get("reasoning_chars"),
                "google_count_tokens_of_prompt": baseline,
                "hidden_prompt_tokens_estimate": (pt - baseline) if (pt is not None and baseline) else None,
                "retries": info["retries"], "gave_up": info["gave_up"], "tries": info["tries"],
                "seconds": info["seconds"],
                "prompt_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest()}
        text = parsed.get("text", "")
        write(os.path.join(d, "answer.txt"), text)
        code, ex = extract.extract(text)
        write(os.path.join(d, "WalRecovery.java"), code)
        write(os.path.join(d, "extraction.json"), json.dumps(ex, indent=2) + "\n")
        write(os.path.join(d, "meta.json"), json.dumps(meta, indent=2) + "\n")
        summ = []
        if test and text:
            res = javatest.test_solution(os.path.join(d, "WalRecovery.java"), "op_%s_%d" % (os.path.basename(os.path.abspath(out_dir)), k))
            lines = []
            javatest.print_report(res, out=lines.append)
            write(os.path.join(d, "test_output.txt"), "\n".join(lines) + "\n")
            res.pop("build_dir", None)
            write(os.path.join(d, "test_results.json"), json.dumps(res, indent=2) + "\n")
            summ = [l for l in lines if l.startswith(("public:", "hidden:"))]
        print("attempt %d: HTTP %s finish=%s model=%s prompt_tokens=%s (baseline %s) out=%s retries=%d rule=%s %s" %
              (k, meta["http_status"], meta["finish_reason"], meta["model_in_response"], pt, baseline,
               usage.get("completion_tokens"), info["retries"], ex["rule"], " | ".join(summ)), flush=True)


def import_web(out_dir, model, date, mode, cfg):
    """Gemini web app attempts: for each subfolder of out_dir that holds an answer.txt saved by
    hand (copied with the app's copy button), extract the code with the same rule as the API path,
    test it, and write WalRecovery.java, extraction.json, meta.json, test_results.json and
    test_output.txt next to it. answer.txt itself is never changed."""
    subs = sorted((d for d in os.listdir(out_dir)
                   if os.path.isfile(os.path.join(out_dir, d, "answer.txt"))),
                  key=lambda d: (len(d), d))
    if not subs:
        sys.exit("no subfolder of %s contains answer.txt" % out_dir)
    write(os.path.join(out_dir, "prompt_sent.txt"), build_prompt(cfg))
    for k, sub in enumerate(subs, 1):
        d = os.path.join(out_dir, sub)
        with open(os.path.join(d, "answer.txt"), "rb") as f:
            text = f.read().decode("utf-8")
        code, ex = extract.extract(text)
        write(os.path.join(d, "WalRecovery.java"), code)
        write(os.path.join(d, "extraction.json"), json.dumps(ex, indent=2) + "\n")
        meta = {"attempt": k, "folder": sub, "source": "Gemini web app (gemini.google.com)",
                "mode": mode, "model_as_shown_in_app": model, "date": date,
                "settings": "app defaults; memory/personal context off; new chat per attempt",
                "prompt_sha256": hashlib.sha256(build_prompt(cfg).encode("utf-8")).hexdigest()}
        write(os.path.join(d, "meta.json"), json.dumps(meta, indent=2) + "\n")
        res = javatest.test_solution(os.path.join(d, "WalRecovery.java"), "web_" + sub)
        lines = []
        javatest.print_report(res, out=lines.append)
        write(os.path.join(d, "test_output.txt"), "\n".join(lines) + "\n")
        res.pop("build_dir", None)
        write(os.path.join(d, "test_results.json"), json.dumps(res, indent=2) + "\n")
        summ = [l for l in lines if l.startswith(("public:", "hidden:"))]
        print("%s: rule=%s | %s" % (sub, ex["rule"], " | ".join(summ)))


def _counts(res):
    out = {}
    for suite in javatest.SUITES:
        ts = [t for t in res["tests"] if t["suite"] == suite]
        out[suite] = [sum(t["status"] == "PASS" for t in ts), len(ts)]
    return out


def _save_test(d, res):
    lines = []
    javatest.print_report(res, out=lines.append)
    write(os.path.join(d, "test_output.txt"), "\n".join(lines) + "\n")
    res = dict(res)
    res.pop("build_dir", None)
    write(os.path.join(d, "test_results.json"), json.dumps(res, indent=2) + "\n")


def attempt_dirs(results):
    ds = [d for d in os.listdir(results) if d.startswith("gemini_run_")
          and os.path.isfile(os.path.join(results, d, "WalRecovery.java"))]
    return sorted(ds, key=lambda d: int(d.rsplit("_", 1)[1]))


def offline(cfg, results=None, ref_runs=3):
    """Default mode, no API key needed: run the tests on the reference solution (3 consecutive
    runs) and on every saved Gemini attempt, with the same test files and commands, then write
    results/summary.json and results/report.md. The saved Gemini answers are never changed."""
    results = results or os.path.join(ROOT, "results")
    ref = os.path.join(ROOT, "reference", "WalRecovery.java")
    rows, matrix, fails = [], {}, {}
    ref_runs_out = []
    for i in range(1, ref_runs + 1):
        res = javatest.test_solution(ref, "reference_run_%d" % i)
        c = _counts(res)
        ref_runs_out.append({"run": i, "compiled": res["compiled"], "public": c["public"], "hidden": c["hidden"]})
        print("reference run %d: public %d/%d, hidden %d/%d" % (i, *c["public"], *c["hidden"]), flush=True)
        if i == 1:
            ids = [(t["suite"], t["id"], t["name"]) for t in res["tests"]]
            matrix["reference"] = {t["id"]: t["status"] for t in res["tests"]}
            fails["reference"] = [t for t in res["tests"] if t["status"] != "PASS"]
    write(os.path.join(results, "reference_runs.json"), json.dumps(ref_runs_out, indent=2) + "\n")
    rows.append({"solution": "reference", "public": ref_runs_out[0]["public"], "hidden": ref_runs_out[0]["hidden"],
                 "all_3_runs_100_percent": all(r["public"][0] == r["public"][1] and r["hidden"][0] == r["hidden"][1]
                                               for r in ref_runs_out)})
    metas = []
    for a in attempt_dirs(results):
        d = os.path.join(results, a)
        res = javatest.test_solution(os.path.join(d, "WalRecovery.java"), "offline_" + a)
        _save_test(d, res)
        c = _counts(res)
        k = int(a.rsplit("_", 1)[1])
        name = "gemini attempt %d" % k
        matrix[name] = {t["id"]: t["status"] for t in res["tests"]}
        fails[name] = [t for t in res["tests"] if t["status"] != "PASS"]
        hf = [t["id"] for t in fails[name] if t["suite"] == "hidden"]
        rows.append({"solution": name, "folder": a, "compiled": res["compiled"], "public": c["public"],
                     "hidden": c["hidden"], "failed_hidden_tests": hf, "fails_at_least_one_hidden": bool(hf)})
        try:
            with open(os.path.join(d, "meta.json"), encoding="utf-8") as f:
                metas.append(json.load(f))
        except OSError:
            pass
        print("%s: public %d/%d, hidden %d/%d" % (name, *c["public"], *c["hidden"]), flush=True)
    prompt = build_prompt(cfg)
    with open(os.path.join(ROOT, cfg["prompt_file"]), "rb") as f:
        prompt_md_sha = hashlib.sha256(f.read()).hexdigest()
    mut = None
    mp = os.path.join(results, "mutants.json")
    if os.path.isfile(mp):
        with open(mp, encoding="utf-8") as f:
            m = json.load(f)
        mut = {"total": len(m), "killed": sum(x["killed"] for x in m)}
    att = [r for r in rows if r["solution"] != "reference"]
    summary = {
        "task": "WAL crash recovery (Java 21)",
        "model": {"requested": cfg["model"],
                  "as_returned": sorted({x.get("model_in_response") or x.get("modelVersion") for x in metas} - {None}),
                  "provider": cfg.get("provider"), "note": cfg.get("model_note")},
        "date_utc": sorted({x.get("date_utc") for x in metas} - {None}),
        "settings": {"max_tokens": cfg.get("max_tokens"), "stream": cfg.get("provider") == "oneprovider",
                     "other": "provider defaults; no system message, no sampling settings sent"},
        "wrapper": cfg["wrapper"], "prompt_md_sha256": prompt_md_sha,
        "prompt_sent_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
        "table": rows,
        "attempts_failing_at_least_one_hidden_test": "%d of %d" % (sum(r["fails_at_least_one_hidden"] for r in att), len(att)),
        "mutants": mut,
        "attempt_meta": [{k: x.get(k) for k in ("attempt", "date_utc", "finish_reason", "usage",
                                                 "hidden_prompt_tokens_estimate", "retries", "response_id")} for x in metas],
    }
    write(os.path.join(results, "summary.json"), json.dumps(summary, indent=2) + "\n")
    # report.md
    L = ["# Test report", "", "Same test files and commands for every solution (runner/javatest.py).", "",
         "| solution | public | hidden |", "|---|---|---|"]
    for r in rows:
        L.append("| %s | %d / %d | %d / %d |" % (r["solution"], *r["public"], *r["hidden"]))
    L += ["", "Reference: %s" % ", ".join("run %d %d/%d + %d/%d" % (r["run"], *r["public"], *r["hidden"]) for r in ref_runs_out),
          "", "Attempts failing at least one hidden test: " + summary["attempts_failing_at_least_one_hidden_test"]]
    if mut:
        L += ["", "Mutants killed by the hidden tests: %d of %d (results/mutants.json)" % (mut["killed"], mut["total"])]
    L += ["", "## Failures (actual vs expected)", ""]
    anyf = False
    for name, fl in fails.items():
        for t in fl:
            anyf = True
            L += ["- **%s**, %s %s (%s)" % (name, t["id"], t["name"], t["suite"]),
                  "  - expected: `%s`" % t["expected"], "  - actual:   `%s`" % t["actual"]]
    if not anyf:
        L.append("None: every solution passed every test.")
    cols = list(matrix)
    L += ["", "## Every test", "", "| suite | test | " + " | ".join(cols) + " |", "|---|---|" + "---|" * len(cols)]
    for suite, tid, tname in ids:
        L.append("| %s | %s %s | " % (suite, tid, tname) + " | ".join(matrix[c].get(tid, "-") for c in cols) + " |")
    write(os.path.join(results, "report.md"), "\n".join(L) + "\n")
    print("attempts failing >= 1 hidden test: " + summary["attempts_failing_at_least_one_hidden_test"])


def mutants(cfg):
    """Apply each mutant in reference/mutants/mutants.json to a copy of the reference, run the
    hidden tests, write results/mutants.json. Killed = compiles and fails >= 1 hidden test."""
    with open(os.path.join(ROOT, "reference", "mutants", "mutants.json"), encoding="utf-8") as f:
        ms = json.load(f)
    with open(os.path.join(ROOT, "reference", "WalRecovery.java"), encoding="utf-8") as f:
        src = f.read()
    out = []
    for m in ms:
        n = src.count(m["find"])
        if n != 1:
            sys.exit("mutant %s: find text occurs %d times (must be 1)" % (m["id"], n))
        d = os.path.join(javatest.BUILD, "mutant_src", m["id"])
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "WalRecovery.java")
        write(path, src.replace(m["find"], m["replace"]))
        res = javatest.test_solution(path, "mutant_" + m["id"], suites=("hidden",))
        failed = [t["id"] for t in res["tests"] if t["status"] != "PASS"]
        killed = res["compiled"] and bool(failed)
        out.append({"id": m["id"], "area": m["area"], "description": m["description"],
                    "compiled": res["compiled"], "killed": killed, "failing_hidden_tests": failed})
        print("%s %s: %s (%d hidden tests fail)" % (m["id"], m["area"], "KILLED" if killed else
              ("NOT COMPILED" if not res["compiled"] else "SURVIVED"), len(failed)), flush=True)
    write(os.path.join(ROOT, "results", "mutants.json"), json.dumps(out, indent=2) + "\n")
    print("killed %d of %d" % (sum(x["killed"] for x in out), len(out)))


def run_attempts(out_dir, n, cfg, first=1):
    if cfg.get("provider", "google") == "oneprovider":
        oneprovider_attempts(out_dir, n, cfg, first=first)
    else:
        gemini_attempts(out_dir, n, cfg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", action="store_true")
    ap.add_argument("--pilot", type=int, metavar="N")
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--attempts", type=int)
    ap.add_argument("--out")
    ap.add_argument("--first", type=int, default=1, help="number of the first attempt folder (to resume a round)")
    ap.add_argument("--test", metavar="FILE")
    ap.add_argument("--mutants", action="store_true")
    ap.add_argument("--import-web", metavar="DIR",
                    help="test answers saved by hand from the Gemini web app (DIR/<attempt>/answer.txt)")
    ap.add_argument("--model", help="with --import-web: the model name shown in the app")
    ap.add_argument("--date", help="with --import-web: the date of the attempts, YYYY-MM-DD")
    ap.add_argument("--mode", default="Chat", help="with --import-web: the app mode used (default Chat)")
    a = ap.parse_args()
    cfg = load_config()
    if a.import_web:
        if not (a.model and a.date):
            ap.error("--import-web needs --model and --date")
        import_web(a.import_web, a.model, a.date, a.mode, cfg)
    elif a.models:
        for m in gemini.list_pro_models():
            print(m["name"], "|", m.get("displayName", ""), "|", m.get("version", ""))
    elif a.pilot:
        if not a.out:
            ap.error("--pilot needs --out")
        run_attempts(a.out, a.pilot, cfg, a.first)
    elif a.fresh:
        if not a.out:
            ap.error("--fresh needs --out")
        run_attempts(a.out, a.attempts or cfg["attempts"], cfg)
        offline(cfg, os.path.abspath(a.out))
    elif a.test:
        if not os.path.isfile(a.test):
            sys.exit("no such file: %s" % a.test)
        res = javatest.test_solution(a.test, "adhoc_%d" % os.getpid())
        javatest.print_report(res)
        sys.exit(0 if all(t["status"] == "PASS" for t in res["tests"]) else 1)
    elif a.mutants:
        mutants(cfg)
    else:
        offline(cfg)


if __name__ == "__main__":
    main()
