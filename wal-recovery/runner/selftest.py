import os, re, subprocess, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract import extract
import javatest


def check(tc, text, code, rule):
    c, i = extract(text)
    tc.assertEqual(c, code)
    tc.assertEqual(i["rule"], rule)
    tc.assertEqual(text[i["start"]:i["end"]], c)
    tc.assertTrue(i["self_check_substring"])


class T(unittest.TestCase):
    def test_one_java(self):
        check(self, "Hi\n```java\nclass A {}\n```\nbye\n", "class A {}\n", "first_java_block")

    def test_two_blocks_java_wins(self):
        check(self, "```\nplain\n```\n```Java\nclass B {}\n```\n", "class B {}\n", "first_java_block")

    def test_untagged(self):
        check(self, "```\nclass C {}\n```\n", "class C {}\n", "first_fenced_block")

    def test_no_fence(self):
        check(self, "class D {}\n", "class D {}\n", "whole_answer")

    def test_trailing_spaces(self):
        check(self, "```java   \nclass E {}\n```   \nx", "class E {}\n", "first_java_block")

    def test_crlf(self):
        check(self, "```java\r\nclass F {\r\n}\r\n```\r\n", "class F {\r\n}\r\n", "first_java_block")

    def test_inner_backticks_in_longer_fence(self):
        check(self, "````java\nString s = \"```\";\n````\n", "String s = \"```\";\n", "first_java_block")


def prompt_examples():
    """Hex of the worked examples in task/PROMPT.md section 11: {n: (hex, L)} (text parsing only)."""
    with open(os.path.join(javatest.ROOT, "task", "PROMPT.md"), encoding="utf-8") as f:
        text = f.read()
    sec = text[text.index("## 11. Worked examples"):]
    res = {}
    for n, body in re.findall(r"### Example (\d): [^\n]*\n.*?```\n(offset.*?)```", sec, re.S):
        toks, offs, length = [], [], None
        for line in body.splitlines()[1:]:
            m = re.match(r"\s*(\d+)  (.*)", line)
            if m:
                offs.append((int(m.group(1)), len(toks)))
                toks += re.split(r"\s{2,}", m.group(2).strip())[0].split()
            m = re.match(r"L = (\d+)", line)
            if m:
                length = int(m.group(1))
        # each record starts at the offset the table says
        for off, have in offs:
            assert off == have, "offset column does not match the bytes in example " + n
        res[n] = (" ".join(toks), length)
    return res


class HarnessCheck(unittest.TestCase):
    """LogBuilder reproduces the PROMPT.md hex byte for byte; Render and LogBuilder literal checks."""

    @classmethod
    def setUpClass(cls):
        extra = [os.path.join(javatest.ROOT, "runner", "selfcheck", "HarnessCheck.java")]
        ok, log, d = javatest.compile_files("_selfcheck", javatest.skeleton_source().encode("utf-8"),
                                            extra_files=extra)
        if not ok:
            raise RuntimeError(log)
        p = subprocess.run(["java", "-cp", os.path.join(d, "classes"), "HarnessCheck"],
                           capture_output=True, timeout=60)
        cls.lines = p.stdout.decode().splitlines()
        cls.ex = prompt_examples()

    def get(self, tag, name):
        for ln in self.lines:
            parts = ln.split(" ", 2)
            if parts[0] == tag and parts[1] == name:
                return parts[2] if len(parts) > 2 else ""
        self.fail("missing %s %s" % (tag, name))

    def test_check_value(self):
        with open(os.path.join(javatest.ROOT, "task", "PROMPT.md"), encoding="utf-8") as f:
            m = re.search(r"BEGIN txn 1 is the record `([0-9A-F ]+)`", f.read())
        self.assertEqual(self.get("BYTES", "check_value"), m.group(1))

    def test_examples_byte_for_byte(self):
        self.assertEqual(sorted(self.ex), ["1", "2", "3"])
        for n, (hexs, length) in self.ex.items():
            self.assertEqual(len(hexs.split()), length)
            self.assertEqual(self.get("BYTES", "example" + n), hexs)
            self.assertEqual(self.get("PUBLIC", "P0" + n), hexs)

    def test_literal_checks(self):
        checks = [ln for ln in self.lines if ln.startswith("CHECK ")]
        self.assertGreaterEqual(len(checks), 15)
        for ln in checks:
            self.assertEqual(ln.split(" ")[2], "OK", ln)


if __name__ == "__main__":
    unittest.main()
