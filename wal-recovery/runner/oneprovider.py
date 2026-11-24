"""OneProvider access (OpenAI-compatible chat completions, streamed). Standard library only.
Never prints or stores the key.

Docs: https://oneprovider.dev/docs/api (read 2026-11-24). Notes:
- POST https://api.oneprovider.dev/v1/chat/completions, Authorization: Bearer <key>.
- Non-streamed requests must start answering within 6 minutes, any request is cut at 10 minutes,
  so the answer is streamed (SSE: "data: {chunk}" lines, then "data: [DONE]").
- Only model, messages, max_tokens, stream and stream_options are sent: no system message, no
  temperature/top_p or other sampling settings. max_tokens is the model's own output maximum.
- The provider adds hidden system text upstream (it varies per request); usage.prompt_tokens is
  recorded so each attempt shows how much was added.
- Retry: network error, 429 rate_limit_error, 5xx, an error event inside the stream, or a stream
  that ends without a finish_reason. Never retry 429 insufficient_balance (x-should-retry: false)
  or any other 4xx.
"""
import json, os, time, urllib.request, urllib.error

URL = "https://api.oneprovider.dev/v1/chat/completions"


def get_key():
    k = os.environ.get("ONEPROVIDER_API_KEY", "").strip()
    if not k:
        p = os.path.expanduser("~/.oneprovider_api_key")
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                k = f.read().strip()
    if not k:
        raise SystemExit("No OneProvider key: set ONEPROVIDER_API_KEY or create ~/.oneprovider_api_key")
    return k


def build_body(model, text, max_tokens):
    return {"model": model, "messages": [{"role": "user", "content": text}],
            "max_tokens": max_tokens, "stream": True, "stream_options": {"include_usage": True}}


def parse_stream(raw):
    """Assemble an SSE byte stream. Returns dict: text (answer, reasoning excluded), reasoning_chars,
    finish_reason, model, id, usage, done (saw [DONE]), error (error object from the stream or None)."""
    out = {"text": "", "reasoning_chars": 0, "finish_reason": None, "model": None, "id": None,
           "usage": None, "done": False, "error": None, "chunks": 0}
    parts = []
    for line in raw.decode("utf-8", "replace").splitlines():
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            out["done"] = True
            continue
        try:
            d = json.loads(data)
        except ValueError:
            continue
        out["chunks"] += 1
        if d.get("type") == "error" or "error" in d:
            out["error"] = d.get("error", d)
            continue
        out["model"] = d.get("model") or out["model"]
        out["id"] = d.get("id") or out["id"]
        if d.get("usage"):
            out["usage"] = d["usage"]
        for c in d.get("choices") or []:
            delta = c.get("delta") or {}
            if delta.get("content"):
                parts.append(delta["content"])
            if delta.get("reasoning_content"):
                out["reasoning_chars"] += len(delta["reasoning_content"])
            if c.get("finish_reason"):
                out["finish_reason"] = c["finish_reason"]
    out["text"] = "".join(parts)
    return out


def _retryable_http(status, raw, headers):
    if status is None:
        return True
    if status >= 500:
        return True
    if status == 429:
        if (headers.get("x-should-retry") or "").lower() == "false":
            return False
        try:
            e = json.loads(raw.decode("utf-8")).get("error", {})
            if e.get("type") == "insufficient_balance" or e.get("code") == "insufficient_quota":
                return False
        except Exception:
            pass
        return True
    return False


def generate(model, text, key=None, max_tokens=32768, timeout=600, waits=(10, 30, 90), sleep=time.sleep):
    """Returns (raw_stream_bytes, parsed, info). raw/parsed are from the last try."""
    key = key or get_key()
    body = json.dumps(build_body(model, text, max_tokens), ensure_ascii=False).encode("utf-8")
    retries, t0, history = 0, time.time(), []
    while True:
        req = urllib.request.Request(URL, data=body, method="POST", headers={
            "Authorization": "Bearer " + key, "Content-Type": "application/json",
            "Accept": "text/event-stream"})
        status, raw, err, headers = None, b"", None, {}
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status, headers = r.status, {k.lower(): v for k, v in r.headers.items()}
                raw = r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
            headers = {k.lower(): v for k, v in (e.headers or {}).items()}
        except Exception as e:  # network error / timeout / connection cut mid-stream
            err = type(e).__name__
        parsed = parse_stream(raw) if status == 200 else None
        req_id = headers.get("x-request-id")
        if err is None and status == 200:
            retry = parsed["error"] is not None or parsed["finish_reason"] is None
            why = "stream_error" if parsed["error"] is not None else ("no_finish_reason" if retry else None)
        else:
            retry = err is not None or _retryable_http(status, raw, headers)
            why = err or ("http_%s" % status)
        history.append({"http_status": status, "error": err, "retry_reason": why if retry else None,
                        "x_request_id": req_id, "seconds": round(time.time() - t0, 1)})
        if not retry or retries >= len(waits):
            return raw, parsed, {"http_status": status, "retries": retries, "error": err,
                                 "seconds": round(time.time() - t0, 1), "request_bytes": body,
                                 "tries": history, "gave_up": bool(retry)}
        sleep(waits[retries])
        retries += 1
