import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract import extract


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


if __name__ == "__main__":
    unittest.main()
