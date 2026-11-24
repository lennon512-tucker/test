"""Code extraction per PLAN 6.3. Never edits the code."""
import hashlib, json, re


def answer_text(response_bytes):
    d = json.loads(response_bytes.decode("utf-8"))
    parts = (((d.get("candidates") or [{}])[0].get("content") or {}).get("parts")) or []
    return "".join(p.get("text", "") for p in parts if not p.get("thought"))


_FENCE = re.compile(r"^[ \t]*(`{3,}|~{3,})[ \t]*([^\r\n]*?)[ \t]*$")


def _lines(text):
    """(start, end_incl_newline, content_without_eol) per line."""
    pos = 0
    for raw in text.splitlines(keepends=True):
        c = raw.rstrip("\r\n")
        yield pos, pos + len(raw), c
        pos += len(raw)


def find_blocks(text):
    """List of dicts: info, start, end (offsets of content). Content = lines between fences."""
    blocks, open_ = [], None
    for ls, le, c in _lines(text):
        m = _FENCE.match(c)
        if open_ is None:
            if m:
                open_ = {"fence": m.group(1), "info": m.group(2), "start": le}
        else:
            if m and m.group(1)[0] == open_["fence"][0] and len(m.group(1)) >= len(open_["fence"]) and not m.group(2):
                blocks.append({"info": open_["info"], "start": open_["start"], "end": ls})
                open_ = None
    if open_ is not None:  # unclosed fence: runs to end of text
        blocks.append({"info": open_["info"], "start": open_["start"], "end": len(text), "unclosed": True})
    return blocks


def extract(text):
    blocks = find_blocks(text)
    chosen, rule = None, None
    for i, b in enumerate(blocks):
        if b["info"].split()[:1] and b["info"].split()[0].lower() == "java":
            chosen, rule = i, "first_java_block"
            break
    if chosen is None and blocks:
        chosen, rule = 0, "first_fenced_block"
    if chosen is None:
        code, start, end, rule = text, 0, len(text), "whole_answer"
    else:
        start, end = blocks[chosen]["start"], blocks[chosen]["end"]
        code = text[start:end]
    info = {"rule": rule, "block_index": chosen, "blocks_found": len(blocks),
            "start": start, "end": end,
            "sha256": hashlib.sha256(code.encode("utf-8")).hexdigest(),
            "self_check_substring": code == text[start:end] and text.find(code) >= 0}
    return code, info
