import unittest
from pathlib import Path

import pr_checks


def fixture_class():
    """Built on demand so discovery does not collect it as a real test."""
    class Fixture(unittest.TestCase):
        def test_kept(self):
            pass

        def test_dropped(self):
            raise AssertionError("must not run")
    return Fixture


class PrChecksTest(unittest.TestCase):
    def test_every_listed_test_exists_in_the_suite(self):
        suite = unittest.TestLoader().discover(str(pr_checks.SCRIPTS), pattern="test_*.py")
        _, missing = pr_checks.filtered_suite(suite)
        self.assertEqual([], missing)

    def test_listed_tests_become_skips_and_the_rest_run(self):
        fixture = fixture_class()
        suite = unittest.TestLoader().loadTestsFromTestCase(fixture)
        key = f"{fixture.__module__}.{fixture.__qualname__}.test_dropped"
        filtered, missing = pr_checks.filtered_suite(suite, {key: "reason", "gone.Test": "x"})
        self.assertEqual(["gone.Test"], missing)
        result = unittest.TestResult()
        filtered.run(result)
        self.assertTrue(result.wasSuccessful(), result.failures + result.errors)
        self.assertEqual(2, result.testsRun)
        self.assertEqual(1, len(result.skipped))
        self.assertIn("needs the private toolchain: reason", result.skipped[0][1])

    def test_a_listed_class_skips_all_its_tests(self):
        fixture = fixture_class()
        suite = unittest.TestLoader().loadTestsFromTestCase(fixture)
        filtered, _ = pr_checks.filtered_suite(suite, {f"{fixture.__module__}.{fixture.__qualname__}": "whole class"})
        result = unittest.TestResult()
        filtered.run(result)
        self.assertEqual(2, len(result.skipped))


if __name__ == "__main__":
    unittest.main()
