#!/usr/bin/env python3
"""Paper formats Bukkit.getVersion() and VERSION_FULL from the jar manifest's Build-Number.

An empty Build-Number made the official REL jar of Build 47 print "26.2-DEV-<branch>@<commit>"
there while its build properties correctly said 26.2-REL. Build-Number must carry sourbyBuild,
never Paper's BUILD_NUMBER environment fallback.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_PATCH = ROOT / "sourbycraft-server" / "build.gradle.kts.patch"


class BuildNumberManifestTest(unittest.TestCase):
    def setUp(self):
        self.lines = BUILD_PATCH.read_text(encoding="utf-8").splitlines()

    def active(self):
        """Lines present in the patched build file: context and added lines."""
        return [line[1:] for line in self.lines if line[:1] in (" ", "+")]

    def test_build_number_comes_from_sourby_build(self):
        mapped = [line for line in self.active() if '"Build-Number" to' in line]
        self.assertEqual(len(mapped), 1, mapped)
        self.assertRegex(mapped[0], r'"Build-Number" to sourbyBuild\b')

    def test_environment_fallback_is_not_the_build_number(self):
        for line in self.active():
            self.assertIsNone(re.search(r'"Build-Number" to \(build \?: ""\)', line), line)


if __name__ == "__main__":
    unittest.main()
