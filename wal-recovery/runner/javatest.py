"""Compile a WalRecovery.java together with the harness and tests, run them, parse the results.

Standard library only. Build output goes to wal-recovery/build/<name>/ (git-ignored).
"""
import os, re, shutil, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, "build")
SUITES = ("public", "hidden")
COMPILE_TIMEOUT = 120
RUN_TIMEOUT = 300          # whole JVM, per suite
JAVAC_ARGS = ["--release", "21", "-encoding", "UTF-8"]
JAVA_ARGS = ["-Xss8m", "-Xmx512m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8"]


def _java_files(*dirs):
    out = []
    for d in dirs:
        full = os.path.join(ROOT, d)
        out += sorted(os.path.join(full, f) for f in os.listdir(full) if f.endswith(".java"))
    return out


def harness_files():
    return _java_files("runner/harness", "tests/public", "tests/hidden")


def skeleton_source():
    """The API skeleton: the first java block of task/PROMPT.md (its recover() only throws)."""
    with open(os.path.join(ROOT, "task", "PROMPT.md"), encoding="utf-8") as f:
        text = f.read()
    m = re.search(r"^```java\n(.*?)^```", text, re.S | re.M)
    if not m:
        raise RuntimeError("no java block in task/PROMPT.md")
    return m.group(1)


def _fresh_dir(name):
    d = os.path.abspath(os.path.join(BUILD, name))
    if os.path.dirname(d) != os.path.abspath(BUILD):
        raise ValueError("bad build name: %r" % name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(os.path.join(d, "src"))
    os.makedirs(os.path.join(d, "classes"))
    return d


def compile_files(name, solution_bytes, extra_files=(), only_files=None):
    """Compile the solution (bytes of WalRecovery.java) with the harness and tests.
    Returns (ok, log_text, build_dir). Writes build/<name>/compile.log."""
    d = _fresh_dir(name)
    src = os.path.join(d, "src", "WalRecovery.java")
    with open(src, "wb") as f:
        f.write(solution_bytes)
    files = [src] + (list(only_files) if only_files is not None else harness_files()) + list(extra_files)
    cmd = ["javac"] + JAVAC_ARGS + ["-d", os.path.join(d, "classes")] + files
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=COMPILE_TIMEOUT, stdin=subprocess.DEVNULL)
        log = (p.stdout + p.stderr).decode("utf-8", "replace").replace(ROOT + os.sep, "")
        ok = p.returncode == 0
    except subprocess.TimeoutExpired:
        log, ok = "javac timed out after %d s\n" % COMPILE_TIMEOUT, False
    except FileNotFoundError:
        log, ok = "javac not found\n", False
    with open(os.path.join(d, "compile.log"), "w", encoding="utf-8") as f:
        f.write(log)
    return ok, log, d


def _unesc(s):
    return re.sub(r"\\(.)", lambda m: {"t": "\t", "n": "\n", "r": "\r"}.get(m.group(1), m.group(1)), s)


def _run(build_dir, args, timeout=RUN_TIMEOUT):
    cmd = ["java"] + JAVA_ARGS + ["-cp", os.path.join(build_dir, "classes"), "TestMain"] + args
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
        return p.stdout.decode("utf-8", "replace"), None
    except subprocess.TimeoutExpired as e:
        return (e.stdout or b"").decode("utf-8", "replace"), "JVM time limit (%d s) exceeded" % timeout


def _parse(out, tag):
    rows = []
    for line in out.split("\n"):
        if line.startswith(tag + "\t"):
            rows.append([_unesc(x) for x in line.split("\t")[1:]])
    return rows


def list_cases():
    """{suite: [{id, name, rules, expected}]}, from a build with the PROMPT.md skeleton."""
    ok, log, d = compile_files("_list", skeleton_source().encode("utf-8"))
    if not ok:
        raise RuntimeError("harness does not compile:\n" + log)
    res = {}
    for s in SUITES:
        out, _ = _run(d, ["list", s], timeout=60)
        res[s] = [dict(id=r[0], name=r[1], rules=r[2], expected=r[3]) for r in _parse(out, "@@CASE")]
    return res


def test_solution(solution_path, name="adhoc", suites=SUITES):
    """Returns {"compiled", "compile_log", "tests": [{suite,id,name,status,expected,actual}], "notes": []}.
    A solution that does not compile fails every test with actual COMPILE_ERROR; a test whose line
    is missing (the JVM died) fails with actual NO_RESULT."""
    with open(solution_path, "rb") as f:
        code = f.read()
    cases = list_cases()
    ok, log, d = compile_files(name, code)
    result = {"compiled": ok, "compile_log": log, "build_dir": d, "tests": [], "notes": []}
    for s in suites:
        got = {}
        if ok:
            out, err = _run(d, [s])
            if err:
                result["notes"].append("%s: %s" % (s, err))
            for r in _parse(out, "@@TEST"):
                if len(r) == 4:
                    got[r[0]] = (r[1], r[2], r[3])
        for c in cases[s]:
            if not ok:
                st, ex, ac = "FAIL", c["expected"], "COMPILE_ERROR"
            elif c["id"] in got:
                st, ex, ac = got[c["id"]]
            else:
                st, ex, ac = "FAIL", c["expected"], "NO_RESULT"
            result["tests"].append(dict(suite=s, id=c["id"], name=c["name"], status=st, expected=ex, actual=ac))
    return result


def print_report(result, out=print):
    if not result["compiled"]:
        out("COMPILE ERROR (first 30 lines of javac output; full log in %s):" %
            os.path.relpath(os.path.join(result["build_dir"], "compile.log"), ROOT))
        for line in result["compile_log"].splitlines()[:30]:
            out("  " + line)
    for n in result["notes"]:
        out("NOTE " + n)
    for s in SUITES:
        ts = [t for t in result["tests"] if t["suite"] == s]
        out("%s: %d / %d passed" % (s, sum(t["status"] == "PASS" for t in ts), len(ts)))
    fails = [t for t in result["tests"] if t["status"] != "PASS"]
    if fails:
        out("")
        out("FAILURES (%d):" % len(fails))
        for t in fails:
            out("%s %s (%s)" % (t["id"], t["name"], t["suite"]))
            out("  expected: " + t["expected"])
            out("  actual:   " + t["actual"])
    total = len(result["tests"])
    out("")
    out("TOTAL: %d / %d passed" % (total - len(fails), total))
