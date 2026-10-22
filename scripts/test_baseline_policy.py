from pathlib import Path
import tempfile
import unittest

import baseline_policy

REPO = Path(__file__).resolve().parents[1]

CANVAS_HUNK = """--- a/net/minecraft/server/level/ServerLevel.java
+++ b/net/minecraft/server/level/ServerLevel.java
@@ -1,1 +_,4 @@
 context
+        // Canvas start - region threading
+        dev.iyanz.aurora.engine.threadedregions.TickRegions.tick();
+        // Canvas end - region threading
"""


class Fixture:
    """A temporary repository with one clean patch in each baseline root and a manifest."""

    def __enter__(self):
        self._directory = tempfile.TemporaryDirectory()
        self.root = Path(self._directory.name)
        for index, directory in enumerate(baseline_policy.BASELINE_ROOTS):
            self.write(f"{directory}/net/Example{index}.java.patch", CANVAS_HUNK)
        (self.root / "build-data").mkdir()
        baseline_policy.write_manifest(self.root)
        return self

    def __exit__(self, *exc):
        self._directory.cleanup()

    def write(self, relative, body):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        return relative


class ManifestTest(unittest.TestCase):
    def test_a_recorded_baseline_is_clean(self):
        with Fixture() as f:
            self.assertEqual(baseline_policy.check(f.root), [])

    def test_reports_a_changed_baseline_file(self):
        with Fixture() as f:
            relative = f.write("sourbycraft-server/paper-patches/files/net/Example1.java.patch",
                               CANVAS_HUNK + "+        int unmarked = 1;\n")
            drift = baseline_policy.manifest_drift(f.root)
            self.assertEqual(drift["changed"], [relative])
            self.assertEqual(len(baseline_policy.check(f.root)), 1)

    def test_reports_an_added_and_a_removed_file(self):
        with Fixture() as f:
            added = f.write("sourbycraft-server/minecraft-patches/sources/net/New.java.patch",
                            CANVAS_HUNK)
            removed = "sourbyapi/paper-patches/files/net/Example2.java.patch"
            (f.root / removed).unlink()
            f.write("sourbyapi/paper-patches/files/net/Keep.java.patch", CANVAS_HUNK)
            drift = baseline_policy.manifest_drift(f.root)
            self.assertIn(added, drift["added"])
            self.assertEqual(drift["removed"], [removed])

    def test_line_endings_do_not_count_as_a_change(self):
        with Fixture() as f:
            relative = "sourbycraft-server/paper-patches/files/net/Example1.java.patch"
            (f.root / relative).write_bytes(CANVAS_HUNK.replace("\n", "\r\n").encode())
            self.assertEqual(baseline_policy.check(f.root), [])

    def test_a_missing_manifest_or_root_is_an_error_not_a_pass(self):
        with Fixture() as f:
            (f.root / baseline_policy.MANIFEST).unlink()
            with self.assertRaises(baseline_policy.BaselineError):
                baseline_policy.check(f.root)
        with Fixture() as f:
            for path in (f.root / "sourbyapi/paper-patches/files").rglob("*.patch"):
                path.unlink()
            with self.assertRaises(baseline_policy.BaselineError):
                baseline_policy.check(f.root)

    def test_a_malformed_manifest_line_is_an_error(self):
        with Fixture() as f:
            with (f.root / baseline_policy.MANIFEST).open("a") as manifest:
                manifest.write("not-a-hash  some/file\n")
            with self.assertRaises(baseline_policy.BaselineError):
                baseline_policy.read_manifest(f.root)


class MarkerTest(unittest.TestCase):
    def violations_for(self, *added):
        with Fixture() as f:
            f.write("sourbycraft-server/minecraft-patches/sources/net/Example0.java.patch",
                    CANVAS_HUNK + "".join(line + "\n" for line in added))
            return baseline_policy.marker_violations(f.root)

    def test_engine_package_and_canvas_markers_are_baseline_content(self):
        self.assertEqual(self.violations_for(
            "+        dev.iyanz.aurora.engine.util.map.Foo.bar();",
            "+import dev.iyanz.aurora.engine.threadedregions.entities.*;"), [])

    def test_catches_a_sourbycraft_marker_pair(self):
        found = self.violations_for("+        // SourbyCraft start - sneaky",
                                    "+        doIt();", "+        // SourbyCraft end")
        self.assertEqual([v["reason"] for v in found], ["SourbyCraft marker"] * 2)
        self.assertEqual(found[0]["line"], 8)

    def test_catches_a_sourbycraft_package_reference(self):
        found = self.violations_for("+        dev.iyanz.sourbycraft.perf.Probe.hit();")
        self.assertEqual(sorted(v["reason"] for v in found),
                         ["SourbyCraft marker", "non-engine package dev.iyanz.sourbycraft.perf.Probe.hit"])

    def test_catches_an_aurora_package_outside_the_engine(self):
        # dev.iyanz.aurora.cpu / .level are introduced by feature patches 0002 / 0005.
        found = self.violations_for("+        int n = dev.iyanz.aurora.cpu.AuroraCpu.cores();")
        self.assertEqual(len(found), 1)
        self.assertIn("non-engine package dev.iyanz.aurora.cpu", found[0]["reason"])

    def test_context_and_removed_lines_are_ignored(self):
        self.assertEqual(self.violations_for(" // SourbyCraft start", "-// SourbyCraft end",
                                             "+++ b/dev/iyanz/sourbycraft/X.java"), [])

    def test_write_manifest_refuses_a_marked_baseline(self):
        with Fixture() as f:
            f.write("sourbycraft-server/paper-patches/files/net/Example1.java.patch",
                    CANVAS_HUNK + "+        // SourbyCraft start\n")
            before = (f.root / baseline_policy.MANIFEST).read_text()
            with self.assertRaises(baseline_policy.BaselineError):
                baseline_policy.write_manifest(f.root)
            self.assertEqual((f.root / baseline_policy.MANIFEST).read_text(), before)


class RepositoryTest(unittest.TestCase):
    def test_the_committed_baseline_matches_its_manifest_and_carries_no_sourbycraft_edit(self):
        problems = baseline_policy.check(REPO)
        self.assertEqual(problems, [], "the engine baseline changed or gained a SourbyCraft edit; "
                                       "SourbyCraft changes belong in */features/ patches. "
                                       "For a deliberate baseline change run "
                                       "scripts/baseline_policy.py --write-manifest")

    def test_the_check_actually_sees_the_baseline(self):
        # Guards against passing because nothing was read.
        files = baseline_policy.baseline_files(REPO)
        self.assertGreater(len(files), 500)
        for directory in baseline_policy.BASELINE_ROOTS:
            self.assertTrue(any(p.startswith(directory + "/") for p in files), directory)

    def test_the_main_entry_point_exits_zero_on_the_repository(self):
        self.assertEqual(baseline_policy.main(["--root", str(REPO)]), 0)


if __name__ == "__main__":
    unittest.main()
