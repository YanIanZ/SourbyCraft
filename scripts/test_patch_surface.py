"""Tests for patch_surface.py on synthetic patch fixtures.

Only SmokeTest reads the real repository, and only to check that the report runs and finds
a plausible number of Minecraft feature patches; every other assertion uses a temp dir.
"""

from pathlib import Path
import contextlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest

import patch_surface as surface

REPO = Path(__file__).resolve().parents[1]
MC = "sourbycraft-server/minecraft-patches/features"
PAPER = "sourbycraft-server/paper-patches/features"
MC_SOURCES = "sourbycraft-server/minecraft-patches/sources"


def feature(subject, diffs, body=""):
    """A git-format patch. ``diffs`` is a list of (path, hunk_lines, new_file)."""
    out = [
        "From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001",
        "From: Test <test@example.invalid>",
        "Date: Tue, 6 Oct 2026 12:00:00 +0700",
        "Subject: [PATCH] SourbyCraft - " + subject,
        "",
    ]
    if body:
        out += [body, ""]
    for path, hunk, new_file in diffs:
        out.append("diff --git a/%s b/%s" % (path, path))
        old = sum(1 for l in hunk if l[:1] in (" ", "-"))
        new = sum(1 for l in hunk if l[:1] in (" ", "+"))
        if new_file:
            out += ["new file mode 100644", "--- /dev/null", "+++ b/%s" % path,
                    "@@ -0,0 +1,%d @@" % new]
        else:
            out += ["--- a/%s" % path, "+++ b/%s" % path, "@@ -10,%d +10,%d @@ class X" % (old, new)]
        out += hunk
    out += ["-- ", "2.50.1", ""]
    return "\n".join(out)


HOOK = feature("boot hook", [("net/minecraft/server/Main.java", [
    "     init();",
    "+    // SourbyCraft start - hook",
    "+    dev.iyanz.sourbycraft.core.Boot.init(",
    "+        options.valueOf(\"plugins\"));",
    "+    // SourbyCraft end",
    "     load();",
], False)])

LOGIC = feature("scan without allocation", [("net/minecraft/world/Poi.java", [
    "     void scan() {",
    "-        pos.blocksInside().forEach(p -> visit(p));",
    "+        for (int dy = 0; dy < 16; ++dy) {",
    "+            for (int dz = 0; dz < 16; ++dz) {",
    "+                for (int dx = 0; dx < 16; ++dx) {",
    "+                    visit(base.offset(dx, dy, dz));",
    "+                }",
    "+            }",
    "+        }",
    "     }",
], False)])

DEFAULT = feature("lower the default guard severity", [("io/canvas/GlobalConfiguration.java", [
    "     public static class Scheduler {",
    "-        public Severity guardSeverity = Severity.of(\"THROW\");",
    "-        public int threads = -1;",
    "+        public Severity guardSeverity = Severity.of(\"LOG\"); // SourbyCraft",
    "+        public int threads = 4;",
    "     }",
], False)])

FIX = feature("fix ticket typo in Projectile", [("net/minecraft/world/Projectile.java", [
    "     void tick() {",
    "-        level.addTicket(TicketType.PORTL, pos);",
    "+        level.addTicket(TicketType.PORTAL, pos);",
    "     }",
], False)])

NEW_CLASS_HOOK = feature("carry a class", [
    ("dev/iyanz/aurora/Thing.java", ["+package dev.iyanz.aurora;", "+public final class Thing {}"], True),
    ("net/minecraft/A.java", ["     a();", "+    dev.iyanz.aurora.Thing.go();", "     b();"], False),
])


class ParseTest(unittest.TestCase):
    def test_counts_lines_and_hunks_and_ignores_signature_trailer(self):
        p = surface.parse_patch(LOGIC, "minecraft", "0002-x.patch")
        self.assertEqual(p.number, 2)
        self.assertEqual(p.subject, "SourbyCraft - scan without allocation")
        self.assertEqual(p.upstream_files, ["net/minecraft/world/Poi.java"])
        self.assertEqual((p.hunks, p.added, p.removed), (1, 7, 1))

    def test_new_file_is_not_an_upstream_file(self):
        p = surface.parse_patch(NEW_CLASS_HOOK)
        self.assertEqual(p.new_files, ["dev/iyanz/aurora/Thing.java"])
        self.assertEqual(p.upstream_files, ["net/minecraft/A.java"])

    def test_multi_line_subject_is_joined(self):
        text = HOOK.replace("Subject: [PATCH] SourbyCraft - boot hook",
                            "Subject: [PATCH] SourbyCraft - boot hook in a very\n long place")
        self.assertEqual(surface.parse_patch(text).subject, "SourbyCraft - boot hook in a very long place")


class ClassifyTest(unittest.TestCase):
    def classify(self, text):
        return surface.classify(surface.parse_patch(text))

    def test_thin_call_into_owned_code_is_a_hook(self):
        kind, rule = self.classify(HOOK)
        self.assertEqual(kind, "hook", rule)
        self.assertIn("dev.iyanz", rule)

    def test_behaviour_written_in_upstream_is_logic(self):
        kind, rule = self.classify(LOGIC)
        self.assertEqual(kind, "logic", rule)
        self.assertIn("no call into dev.iyanz", rule)

    def test_literal_swap_is_a_default(self):
        kind, rule = self.classify(DEFAULT)
        self.assertEqual(kind, "default", rule)
        self.assertIn("2 line pair", rule)

    def test_fix_subject_is_a_fix_when_not_a_hook_or_default(self):
        kind, rule = self.classify(FIX)
        # the typo fix changes an identifier, not a literal, so it is not a default
        self.assertEqual(kind, "fix", rule)

    def test_patch_carrying_a_new_class_is_not_a_hook(self):
        kind, rule = self.classify(NEW_CLASS_HOOK)
        self.assertEqual(kind, "logic", rule)
        self.assertIn("new file", rule)

    def test_a_hook_that_rewrites_upstream_lines_is_logic(self):
        text = feature("hook that rewrites", [("net/minecraft/server/Main.java", [
            "-    init();",
            "-    a();",
            "-    b();",
            "+    dev.iyanz.sourbycraft.core.Boot.init();",
            "     load();",
        ], False)])
        kind, rule = self.classify(text)
        self.assertEqual(kind, "logic", rule)
        self.assertIn("over hook limits", rule)


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="patch-surface-")
        self.addCleanup(shutil.rmtree, self.root)

    def test_hot_spot_when_two_patches_touch_one_file(self):
        write(self.root, MC + "/0001-a.patch", HOOK)
        write(self.root, MC + "/0002-b.patch", HOOK.replace("boot hook", "second hook"))
        write(self.root, MC + "/0003-c.patch", LOGIC)
        report = surface.build_report(self.root)
        self.assertEqual(report["hot_spots"], [
            {"directory": "minecraft", "file": "net/minecraft/server/Main.java", "patches": ["0001", "0002"]}])

    def test_overlap_with_a_baseline_file_patch(self):
        write(self.root, MC + "/0001-a.patch", LOGIC)
        write(self.root, MC_SOURCES + "/net/minecraft/world/Poi.java.patch", "--- a\n+++ b\n")
        write(self.root, MC_SOURCES + "/net/minecraft/world/Other.java.patch", "--- a\n+++ b\n")
        report = surface.build_report(self.root)
        self.assertEqual(report["baseline"]["minecraft-sources"]["file_patches"], 2)
        self.assertEqual([o["file"] for o in report["baseline_overlap"]], ["net/minecraft/world/Poi.java"])

    def test_numbering_gap_and_duplicate(self):
        write(self.root, MC + "/0001-a.patch", HOOK)
        write(self.root, MC + "/0003-c.patch", LOGIC)
        write(self.root, PAPER + "/0001-a.patch", HOOK)
        write(self.root, PAPER + "/0001-b.patch", FIX)
        report = surface.build_report(self.root)
        self.assertEqual(report["numbering"]["minecraft"], {"duplicates": [], "gaps": [2]})
        self.assertEqual(report["numbering"]["paper"], {"duplicates": [1], "gaps": []})
        self.assertIn("duplicate patch number 0001 in paper", surface.check_failures(report))

    def test_largest_is_ordered_by_changed_lines(self):
        write(self.root, MC + "/0001-a.patch", HOOK)
        write(self.root, MC + "/0002-b.patch", LOGIC)
        report = surface.build_report(self.root)
        self.assertEqual([p["file"] for p in report["largest"]], ["0002-b.patch", "0001-a.patch"])

    def test_not_a_git_tree_reports_none_and_check_does_not_fail_on_tracking(self):
        write(self.root, MC + "/0001-a.patch", HOOK)
        if surface._git(self.root, ["rev-parse", "--is-inside-work-tree"]) is not None:
            self.skipTest("temp dir is inside a git work tree")
        report = surface.build_report(self.root)
        self.assertIsNone(report["git"])
        self.assertEqual(surface.check_failures(report), [])


@unittest.skipIf(shutil.which("git") is None, "git not installed")
class GitTrackingTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="patch-surface-git-")
        self.addCleanup(shutil.rmtree, self.root)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "test")
        self.git("config", "commit.gpgsign", "false")

    def git(self, *args):
        subprocess.run(["git", "-C", self.root] + list(args), check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_untracked_patch_fails_check_and_committed_one_does_not(self):
        write(self.root, MC + "/0001-a.patch", HOOK)
        self.git("add", ".")
        self.git("commit", "-q", "-m", "one")
        write(self.root, MC + "/0002-b.patch", LOGIC)
        write(self.root, MC + "/0003-c.patch", FIX)
        self.git("add", MC + "/0003-c.patch")

        report = surface.build_report(self.root)
        self.assertEqual(report["git"]["untracked"], [MC + "/0002-b.patch"])
        self.assertEqual(report["git"]["staged_not_committed"], [MC + "/0003-c.patch"])
        self.assertEqual(surface.check_failures(report), ["untracked patch file: %s/0002-b.patch" % MC])

        devnull = open(os.devnull, "w")
        self.addCleanup(devnull.close)
        with contextlib.redirect_stdout(devnull), contextlib.redirect_stderr(devnull):
            self.assertEqual(surface.main(["--root", self.root]), 0)
            self.assertEqual(surface.main(["--root", self.root, "--check"]), 1)
            self.git("add", ".")
            self.assertEqual(surface.main(["--root", self.root, "--check"]), 0)

    def test_index_entry_missing_on_disk_is_reported(self):
        write(self.root, MC + "/0001-a.patch", HOOK)
        self.git("add", ".")
        os.remove(os.path.join(self.root, MC, "0001-a.patch"))
        report = surface.build_report(self.root)
        self.assertEqual(report["git"]["missing_on_disk"], [MC + "/0001-a.patch"])


@unittest.skipIf(shutil.which("git") is None, "git not installed")
class RebuildPredictionTest(unittest.TestCase):
    """The 2026-10-06 incident: a patch file with no materialized commit is deleted by a
    rebuild, and the files after it are renumbered."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="patch-surface-rebuild-")
        self.addCleanup(shutil.rmtree, self.root)
        self.repo = os.path.join(self.root, surface.MATERIALIZED["minecraft"])
        os.makedirs(self.repo)
        for args in (["init", "-q"], ["config", "user.email", "t@example.invalid"],
                     ["config", "user.name", "t"], ["config", "commit.gpgsign", "false"]):
            self.git(*args)

    def git(self, *args):
        subprocess.run(["git", "-C", self.repo] + list(args), check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def commit(self, subject):
        self.git("commit", "-q", "--allow-empty", "-m", subject)

    def test_patch_without_commit_is_deleted_and_later_ones_renumbered(self):
        self.commit("Import repo from upstream")
        self.commit("sourbycraft File Patches")
        self.commit("SourbyCraft - boot hook")
        self.commit("SourbyCraft - scan without allocation")
        write(self.root, MC + "/0001-a.patch", HOOK)
        write(self.root, MC + "/0002-b.patch", FIX)  # never committed in the materialized repo
        write(self.root, MC + "/0003-c.patch", LOGIC)

        report = surface.build_report(self.root)
        self.assertEqual(report["rebuild"]["minecraft"], {
            "deleted": ["0002-b.patch"],
            "added": [],
            "renumbered": [{"file": "0003-c.patch", "from": 3, "to": 2}],
        })
        self.assertIsNone(report["rebuild"]["paper"])
        self.assertEqual(surface.rebuild_failures(report),
                         ["a rebuild of minecraft would delete 0002-b.patch (no materialized commit)"])

    def test_commit_without_patch_file_is_reported_as_added(self):
        self.commit("sourbycraft File Patches")
        self.commit("SourbyCraft - boot hook")
        self.commit("SourbyCraft - not exported yet")
        write(self.root, MC + "/0001-a.patch", HOOK)
        prediction = surface.build_report(self.root)["rebuild"]["minecraft"]
        self.assertEqual(prediction["added"], ["SourbyCraft - not exported yet"])
        self.assertEqual(prediction["deleted"], [])


class SmokeTest(unittest.TestCase):
    def test_report_runs_on_the_repository(self):
        if not (REPO / MC).is_dir():
            self.skipTest("no Minecraft feature patch directory")
        report = surface.build_report(str(REPO))
        self.assertGreaterEqual(report["summary"]["minecraft"]["patches"], 20)
        markdown = surface.render_markdown(report)
        self.assertIn("| minecraft |", markdown)
        json.dumps(report)  # the --json output must be serialisable


if __name__ == "__main__":
    unittest.main()
