from pathlib import Path
import tempfile
import unittest

import toolchain_policy as policy

REPO = Path(__file__).resolve().parents[1]
BASELINE = 25


class DeclaredTargetTest(unittest.TestCase):
    """PRD section 6: Java 25 baseline, and no production module silently older.

    Silently is the point. One shipped module is deliberately far older and has to be,
    so the exceptions are listed here with the reason rather than found by surprise.
    """

    EXCEPTIONS = {
        # Not shipped. The NMS-compat harness is driven only by a workflow_dispatch
        # workflow that predates the 26.2 rebase and is marked stale in its own header.
        "test-harness/sanity-harness-plugin/build.gradle.kts": [21],
    }

    def test_every_module_is_on_the_baseline_or_a_recorded_exception(self):
        targets = policy.declared_targets(REPO)
        offenders = {path: versions for path, versions in targets.items()
                     if versions != [BASELINE] and self.EXCEPTIONS.get(path) != versions}
        self.assertEqual(offenders, {},
                         "a module declares a Java target that is neither the baseline nor a "
                         "recorded exception; raise it to 25, or record why it cannot be")

    def test_the_recorded_exceptions_still_exist_and_are_unchanged(self):
        targets = policy.declared_targets(REPO)
        for path, versions in self.EXCEPTIONS.items():
            with self.subTest(path=path):
                self.assertIn(path, targets, "recorded exception no longer exists; drop it")
                self.assertEqual(targets[path], versions)

    def test_the_shipped_modules_are_on_the_baseline(self):
        targets = policy.declared_targets(REPO)
        for path in ("build.gradle.kts", "sourbycraft-server/build.gradle.kts"):
            with self.subTest(path=path):
                self.assertEqual(targets.get(path), [BASELINE])

    def test_the_policy_actually_found_the_build_scripts(self):
        self.assertTrue({"build.gradle.kts", "sourbycraft-server/build.gradle.kts",
                         "test-harness/sanity-harness-plugin/build.gradle.kts"}
                        .issubset(policy.declared_targets(REPO)))


class ExtractionTest(unittest.TestCase):
    def extract(self, text, name="build.gradle.kts"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / name).write_text(text)
            return policy.declared_targets(root).get(name)

    def test_reads_both_assignment_and_setter_forms(self):
        self.assertEqual(self.extract("languageVersion = JavaLanguageVersion.of(25)"), [25])
        self.assertEqual(self.extract("languageVersion.set(JavaLanguageVersion.of(25))"), [25])
        self.assertEqual(self.extract("options.release = 25"), [25])
        self.assertEqual(self.extract("options.release.set(25)"), [25])

    def test_reads_the_legacy_compatibility_form(self):
        self.assertEqual(self.extract("sourceCompatibility = JavaVersion.VERSION_21\n"
                                      "targetCompatibility = JavaVersion.VERSION_21"), [21])

    def test_reports_every_distinct_version_in_one_script(self):
        self.assertEqual(self.extract("languageVersion.set(JavaLanguageVersion.of(11))\n"
                                      "options.release.set(6)"), [6, 11])

    def test_a_script_declaring_nothing_is_absent(self):
        self.assertIsNone(self.extract("plugins { java }"))

    def test_generated_and_upstream_trees_are_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for part in ("build", ".gradle", "upstreams"):
                nested = root / part
                nested.mkdir()
                (nested / "build.gradle.kts").write_text("options.release = 8")
            self.assertEqual(policy.declared_targets(root), {})


if __name__ == "__main__":
    unittest.main()
