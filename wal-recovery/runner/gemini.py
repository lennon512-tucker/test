"""Gemini API access (standard library only). Never prints or stores the key."""
import json, os, time, urllib.request, urllib.error

BASE = "https://generativelanguage.googleapis.com/v1beta"


def get_key():
    k = os.environ.get("GEMINI_API_KEY", "").strip()
    if not k:
        p = os.path.expanduser("~/.gemini_api_key")
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                k = f.read().strip()
    if not k:
        raise SystemExit("No Gemini key: set GEMINI_API_KEY or create ~/.gemini_api_key")
    return k


def _get(url, key, timeout=60):
    req = urllib.request.Request(url, headers={"x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def list_models(key=None):
    """All models (follows nextPageToken)."""
    key = key or get_key()
    out, token = [], None
    while True:
        url = BASE + "/models?pageSize=200" + ("&pageToken=" + urllib.request.quote(token) if token else "")
        d = json.loads(_get(url, key))
        out += d.get("models", [])
        token = d.get("nextPageToken")
        if not token:
            return out


def list_pro_models(key=None):
    return [m for m in list_models(key)
            if "pro" in m.get("name", "").lower()
            and "generateContent" in m.get("supportedGenerationMethods", [])]


def build_body(text):
    return {"contents": [{"role": "user", "parts": [{"text": text}]}]}


def generate(model, text, key=None, timeout=600, waits=(10, 30, 90), sleep=time.sleep):
    """Returns (raw_response_bytes, info). Retries only when no answer arrived
    (network error, 429, 5xx). A received answer, even an error 4xx, is final."""
    key = key or get_key()
    body = json.dumps(build_body(text), ensure_ascii=False).encode("utf-8")
    url = "%s/models/%s:generateContent" % (BASE, model)
    retries, t0 = 0, time.time()
    while True:
        req = urllib.request.Request(url, data=body, method="POST", headers={
            "x-goog-api-key": key, "Content-Type": "application/json"})
        status, raw, err = None, None, None
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        except Exception as e:  # network error / timeout
            err = type(e).__name__
        if err is None and status != 429 and status < 500:
            return raw, {"http_status": status, "retries": retries,
                         "seconds": round(time.time() - t0, 1), "request_bytes": body}
        if retries >= len(waits):
            return (raw or b""), {"http_status": status, "retries": retries, "error": err,
                                  "seconds": round(time.time() - t0, 1), "request_bytes": body}
        sleep(waits[retries])
        retries += 1


def count_tokens(model, text, key=None, timeout=60):
    """Google's exact input-token count for text as one user turn (free; no generation).
    Used only as a baseline next to a provider's billed prompt_tokens. Returns int or None."""
    try:
        key = key or get_key()
        body = json.dumps(build_body(text), ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request("%s/models/%s:countTokens" % (BASE, model), data=body, method="POST",
                                     headers={"x-goog-api-key": key, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read()).get("totalTokens")
    except BaseException:
        return None
