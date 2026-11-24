#!/usr/bin/env python3
"""Runner entry point. Run from wal-recovery/. Standard library only."""
import argparse, datetime, hashlib, json, os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import gemini, extract, javatest


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", action="store_true")
    ap.add_argument("--pilot", type=int, metavar="N")
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--attempts", type=int)
    ap.add_argument("--out")
    ap.add_argument("--test", metavar="FILE")
    ap.add_argument("--mutants", action="store_true")
    a = ap.parse_args()
    cfg = load_config()
    if a.models:
        for m in gemini.list_pro_models():
            print(m["name"], "|", m.get("displayName", ""), "|", m.get("version", ""))
    elif a.pilot:
        if not a.out:
            ap.error("--pilot needs --out")
        gemini_attempts(a.out, a.pilot, cfg)
    elif a.fresh:
        if not a.out:
            ap.error("--fresh needs --out")
        gemini_attempts(a.out, a.attempts or cfg["attempts"], cfg)
    elif a.test:
        if not os.path.isfile(a.test):
            sys.exit("no such file: %s" % a.test)
        res = javatest.test_solution(a.test, "adhoc")
        javatest.print_report(res)
        sys.exit(0 if all(t["status"] == "PASS" for t in res["tests"]) else 1)
    elif a.mutants:
        sys.exit("not implemented yet (later session)")
    else:
        sys.exit("offline default mode not implemented yet (later session)")


if __name__ == "__main__":
    main()
