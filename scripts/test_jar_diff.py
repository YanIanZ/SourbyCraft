import io
import tempfile
import unittest
import zipfile
from pathlib import Path

import jar_diff


def make(path, entries, when=(2026, 1, 1, 0, 0, 0)):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, when)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


def nested(entries):
    buffer = io.BytesIO()
    make(buffer, entries)
    return buffer.getvalue()


class JarDiffTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp, ignore_errors=True))

    def run_diff(self, a, b):
        out = []
        count = jar_diff.compare(self.tmp / a, self.tmp / b, "jar", out)
        return count, "\n".join(out)

    def test_identical_jars_have_no_differences(self):
        entries = [("a.txt", b"one"), ("b.txt", b"two")]
        make(self.tmp / "a.jar", entries)
        make(self.tmp / "b.jar", entries)
        self.assertEqual((0, ""), self.run_diff("a.jar", "b.jar"))

    def test_a_different_time_stamp_alone_is_reported(self):
        make(self.tmp / "a.jar", [("a.txt", b"one")], (2026, 1, 1, 0, 0, 0))
        make(self.tmp / "b.jar", [("a.txt", b"one")], (2026, 1, 1, 0, 0, 2))
        count, text = self.run_diff("a.jar", "b.jar")
        self.assertEqual(1, count)
        self.assertIn("date_time", text)

    def test_order_only_difference_is_reported(self):
        make(self.tmp / "a.jar", [("a", b"1"), ("b", b"2")])
        make(self.tmp / "b.jar", [("b", b"2"), ("a", b"1")])
        count, text = self.run_diff("a.jar", "b.jar")
        self.assertEqual(1, count)
        self.assertIn("ORDER", text)

    def test_missing_entries_and_property_diff_inside_a_nested_jar(self):
        props_a = b"treeHash=aaa\ngitBranch=26.2\n"
        props_b = b"treeHash=bbb\ngitBranch=HEAD\n"
        make(self.tmp / "a.jar", [("versions/s.jar", nested([("META-INF/p.properties", props_a)])), ("x", b"1")])
        make(self.tmp / "b.jar", [("versions/s.jar", nested([("META-INF/p.properties", props_b)]))])
        count, text = self.run_diff("a.jar", "b.jar")
        self.assertGreaterEqual(count, 3)
        self.assertIn("only in first: x", text)
        self.assertIn("-treeHash=aaa", text)
        self.assertIn("+gitBranch=HEAD", text)

    def test_main_exit_codes(self):
        make(self.tmp / "a.jar", [("a", b"1")])
        make(self.tmp / "b.jar", [("a", b"2")])
        self.assertEqual(0, jar_diff.main([str(self.tmp / "a.jar"), str(self.tmp / "a.jar")]))
        self.assertEqual(1, jar_diff.main([str(self.tmp / "a.jar"), str(self.tmp / "b.jar")]))
        self.assertEqual(2, jar_diff.main([str(self.tmp / "a.jar"), str(self.tmp / "missing.jar")]))


if __name__ == "__main__":
    unittest.main()
