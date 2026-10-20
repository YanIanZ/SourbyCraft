"""Tests for scripts/agent_worktree.py against temporary git repos (never the real tree or lock dirs)."""
import fcntl
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import threading
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_worktree as aw  # noqa: E402

GIT_ENV = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
TEN = "".join(f"line {i}\n" for i in range(1, 11))


def sh(repo, *args, check=True):
    return subprocess.run(["git", "-C", str(repo), *args], env=GIT_ENV, capture_output=True, text=True,
                          check=check)


def make_repo(path, files):
    path.mkdir(parents=True, exist_ok=True)
    sh(path, "init", "-q")
    for rel, text in files.items():
        (path / rel).parent.mkdir(parents=True, exist_ok=True)
        (path / rel).write_text(text)
    sh(path, "add", "-A")
    sh(path, "commit", "-q", "-m", "base")


class WorktreeTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="agent-wt-test.")).resolve()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.main = self.tmp / "main"
        make_repo(self.main, {
            ".gitignore": "paper-server/\npaper-api/\n/sourbycraft-server/src/minecraft\n"
                          "/sourbycraft-server/build.gradle.kts\n/sourbyapi/build.gradle.kts\n"
                          "Metal/src/main/java/ca/spottedleaf/concurrentutil/map/concurrent/*\n"
                          "build/\n.board-queue.json\n.gradle\n",
            "README.md": "readme\n",
            "src/A.java": TEN,
            "src/Gone.java": "delete me\n",
            "Metal/src/main/java/Kept.java": "tracked metal\n",
            "gradlew": "#!/bin/sh\n",
        })
        make_repo(self.main / "paper-server", {"src/P.java": "paper server\n"})
        make_repo(self.main / "paper-api", {"src/Api.java": "paper api\n"})
        make_repo(self.main / "sourbycraft-server/src/minecraft/java", {"net/minecraft/Mc.java": TEN})
        make_repo(self.main / "sourbycraft-server/src/minecraft/resources", {"data.json": "{}\n"})
        gen = self.main / "Metal/src/main/java/ca/spottedleaf/concurrentutil/map/concurrent"
        gen.mkdir(parents=True)
        (gen / "Generated.java").write_text("generated\n")
        (self.main / "sourbycraft-server/build.gradle.kts").write_text("// generated server build\n")
        (self.main / "sourbyapi").mkdir()
        (self.main / "sourbyapi/build.gradle.kts").write_text("// generated api build\n")
        self.out = io.StringIO()
        self.cfg = aw.Config(main=self.main, root=self.tmp / "wts", file_locks=self.tmp / "filelocks",
                             gradle_lock_root=self.tmp / "gradle-locks", tmp_root=self.tmp / "agent-tmp",
                             mc_lock=self.tmp / "mc.lock", out=self.out, daemon_lister=lambda: [])

    def run_cli(self, *argv):
        self.out.seek(0)
        self.out.truncate()
        with mock.patch("sys.stderr", new_callable=io.StringIO) as err:
            code = aw.main(list(argv), cfg=self.cfg)
        self.last_err = err.getvalue()
        return code

    def output(self):
        return json.loads(self.out.getvalue())

    def wt(self, name="w1"):
        return self.cfg.wt(name)


class CreateTest(WorktreeTestBase):
    def test_create_clones_generated_inputs_without_main_changes(self):
        (self.main / "README.md").write_text("main edit\n")
        (self.main / "untracked.txt").write_text("x\n")
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        wt = self.wt()
        self.assertEqual((wt / "README.md").read_text(), "readme\n")
        self.assertFalse((wt / "untracked.txt").exists())
        self.assertEqual((wt / "sourbycraft-server/build.gradle.kts").read_text(), "// generated server build\n")
        self.assertEqual((wt / "Metal/src/main/java/ca/spottedleaf/concurrentutil/map/concurrent/Generated.java")
                         .read_text(), "generated\n")
        self.assertEqual((wt / "Metal/src/main/java/Kept.java").read_text(), "tracked metal\n")
        for repo in aw.NESTED_REPOS:
            self.assertEqual(sh(wt / repo, "log", "--format=%s").stdout.strip(), "base", repo)
        self.assertEqual(sh(wt, "rev-parse", "HEAD").stdout, sh(self.main, "rev-parse", "HEAD").stdout)
        meta = self.output()
        self.assertTrue(meta["ok"])
        self.assertEqual(set(meta["base_trees"]), {".", *aw.NESTED_REPOS})
        self.assertIn(f"SOURBY_TREE={wt}", meta["gradle"])
        # the main tree is not touched: still dirty the same way, no new worktree files inside it
        self.assertEqual(sh(self.main, "status", "--porcelain").stdout, " M README.md\n?? untracked.txt\n")

    def test_existing_name_bad_name_and_root_inside_repo_are_refused(self):
        self.assertEqual(self.run_cli("create", "w1"), 0)
        self.assertEqual(self.run_cli("create", "w1"), 2)
        self.assertIn("already exists", self.last_err)
        self.assertEqual(self.run_cli("create", "../evil"), 2)
        self.assertEqual(self.run_cli("create", ".meta"), 2)
        self.cfg.root = self.main / "wts"
        self.assertEqual(self.run_cli("create", "w2"), 2)
        self.assertIn("inside the repo", self.last_err)
        self.assertFalse((self.main / "wts").exists())

    def test_missing_generated_input_is_refused(self):
        (self.main / "sourbyapi/build.gradle.kts").unlink()
        self.assertEqual(self.run_cli("create", "w1"), 2)
        self.assertIn("sourbyapi/build.gradle.kts", self.last_err)
        self.assertFalse(self.wt().exists())

    def test_from_main_tree_carries_changes_but_not_coordination_files(self):
        (self.main / "README.md").write_text("main edit\n")
        (self.main / "src/Gone.java").unlink()
        (self.main / "scripts").mkdir()
        (self.main / "scripts/new_tool.py").write_text("print(1)\n")
        (self.main / "AGENT-COORDINATION.md").write_text("coordination\n")
        (self.main / ".board-queue.json").write_text("[]\n")
        self.assertEqual(self.run_cli("create", "w1", "--from-main-tree"), 0, self.last_err)
        wt = self.wt()
        self.assertEqual((wt / "README.md").read_text(), "main edit\n")
        self.assertFalse((wt / "src/Gone.java").exists())
        self.assertEqual((wt / "scripts/new_tool.py").read_text(), "print(1)\n")
        self.assertFalse((wt / "AGENT-COORDINATION.md").exists())
        self.assertFalse((wt / ".board-queue.json").exists())
        self.assertEqual(self.output()["carried"]["untracked_files"], 1)
        # carried-over changes are part of the base, so the worktree's own diff starts empty
        self.assertEqual(self.run_cli("diff", "w1"), 0)
        self.assertEqual(self.output()["patches"], [])

    def test_plain_copy_fallback_when_clone_is_unavailable(self):
        real_run = subprocess.run

        def no_clone(cmd, *a, **k):
            if cmd[:2] == ["cp", "-cRp"]:
                return subprocess.CompletedProcess(cmd, 1, b"", b"cp: illegal option -- c")
            return real_run(cmd, *a, **k)

        with mock.patch.object(aw.subprocess, "run", side_effect=no_clone):
            self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertEqual(set(self.output()["copied"].values()), {"copy"})
        self.assertEqual((self.wt() / "paper-api/src/Api.java").read_text(), "paper api\n")

    def test_partial_clone_failure_leaves_no_leftovers(self):
        real_run = subprocess.run

        def half_clone(cmd, *a, **k):
            if cmd[:2] == ["cp", "-cRp"]:
                dst = Path(cmd[-1])
                dst.mkdir(parents=True, exist_ok=True)
                (dst / "half-written.junk").write_text("x")  # cp -c died part-way
                return subprocess.CompletedProcess(cmd, 1, b"", b"cp: clonefile failed")
            return real_run(cmd, *a, **k)

        with mock.patch.object(aw.subprocess, "run", side_effect=half_clone):
            self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertFalse((self.wt() / "paper-api/half-written.junk").exists())
        self.assertEqual((self.wt() / "paper-api/src/Api.java").read_text(), "paper api\n")


CACHE = ".gradle/caches/paperweight/upstreams/paper"


class PaperweightCacheTest(WorktreeTestBase):
    def make_cache(self, pin_in_properties=True):
        make_repo(self.main / CACHE, {"paper-server/build.gradle.kts": "// upstream build\n",
                                      "paper-api/build.gradle.kts": "// upstream api\n"})
        pin = sh(self.main / CACHE, "rev-parse", "HEAD").stdout.strip()
        ref = pin if pin_in_properties else "0123456789abcdef0123456789abcdef01234567"
        (self.main / "gradle.properties").write_text(f"version=1\npaperRef={ref}\n")
        sh(self.main, "add", "gradle.properties")
        sh(self.main, "commit", "-q", "-m", "pin")
        return pin

    def test_cache_is_cloned_when_present_and_matches_the_pin(self):
        pin = self.make_cache()
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        wt_cache = self.wt() / CACHE
        self.assertTrue(wt_cache.is_dir())
        self.assertFalse(wt_cache.is_symlink())
        self.assertEqual(sh(wt_cache, "show", f"{pin}:paper-server/build.gradle.kts").stdout, "// upstream build\n")
        self.assertIn(CACHE, self.output()["copied"])
        self.assertNotIn(CACHE, self.output().get("cache_skipped", {}))
        self.assertEqual(self.run_cli("diff", "w1"), 0)  # .gradle is ignored: never part of the diff
        self.assertEqual(self.output()["patches"], [])

    def test_missing_cache_does_not_block_create(self):
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertFalse((self.wt() / CACHE).exists())
        self.assertNotIn(CACHE, self.output()["copied"])

    def test_cache_without_the_worktrees_pin_is_dropped(self):
        self.make_cache(pin_in_properties=False)
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertFalse((self.wt() / CACHE).exists())
        self.assertNotIn(CACHE, self.output()["copied"])
        self.assertIn("paperRef", self.output()["cache_skipped"][CACHE])

    def test_cache_copy_holds_the_main_tree_gradle_lock(self):
        self.make_cache()
        lock = self.cfg.gradle_lock_root / "sourby-gradle.lock"
        seen = []
        real = aw.clone_copy

        def spy(src, dst):
            if str(src).endswith(CACHE):
                seen.append(lock.is_dir() and "agent_worktree" in (lock / "owner").read_text())
            return real(src, dst)

        with mock.patch.object(aw, "clone_copy", side_effect=spy):
            self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertEqual(seen, [True])
        self.assertFalse(lock.exists())  # released afterwards

    def test_busy_main_tree_build_skips_the_cache(self):
        self.make_cache()
        lock = self.cfg.gradle_lock_root / "sourby-gradle.lock"
        lock.mkdir(parents=True)
        (lock / "owner").write_text(f"someone pid={os.getpid()} tree=main\n")  # a live holder
        self.cfg.cache_lock_timeout = 0
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertFalse((self.wt() / CACHE).exists())
        self.assertIn("Gradle", self.output()["cache_skipped"][CACHE])
        self.assertTrue(lock.is_dir())  # someone else's lock is left alone

    def test_failed_cache_copy_is_cleaned_up_and_skipped(self):
        self.make_cache()
        real_run = subprocess.run

        def broken(cmd, *a, **k):
            if cmd[0] == "cp" and str(cmd[-1]).endswith(CACHE):
                Path(cmd[-1]).mkdir(parents=True, exist_ok=True)
                (Path(cmd[-1]) / "partial").write_text("x")
                if cmd[1] == "-Rp":
                    raise subprocess.CalledProcessError(1, cmd)
                return subprocess.CompletedProcess(cmd, 1, b"", b"failed")
            return real_run(cmd, *a, **k)

        with mock.patch.object(aw.subprocess, "run", side_effect=broken):
            self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.assertFalse((self.wt() / CACHE).exists())
        self.assertIn(CACHE, self.output()["cache_skipped"])

    def test_writes_to_the_worktree_cache_do_not_reach_the_main_cache(self):
        self.make_cache()
        main_head = sh(self.main / CACHE, "rev-parse", "HEAD").stdout
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        wt_cache = self.wt() / CACHE
        (wt_cache / "paper-server/build.gradle.kts").write_text("// rewritten by a worktree build\n")
        sh(wt_cache, "commit", "-q", "-am", "worktree only")
        self.assertEqual((self.main / CACHE / "paper-server/build.gradle.kts").read_text(), "// upstream build\n")
        self.assertEqual(sh(self.main / CACHE, "rev-parse", "HEAD").stdout, main_head)


class DiffApplyTest(WorktreeTestBase):
    def setUp(self):
        super().setUp()
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        self.head = sh(self.main, "rev-parse", "HEAD").stdout

    def edit_worktree(self):
        wt = self.wt()
        (wt / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 from worktree\n"))
        (wt / "src/Gone.java").unlink()
        (wt / "src/New.java").write_text("brand new\n")
        mc = wt / "sourbycraft-server/src/minecraft/java"
        (mc / "net/minecraft/Mc.java").write_text(TEN.replace("line 2\n", "line 2 patched\n"))
        sh(mc, "commit", "-q", "-am", "patch work")  # a committed change in the MC repo counts too
        (mc / "net/minecraft/Extra.java").write_text("extra\n")

    def test_diff_writes_repo_and_mc_patches(self):
        self.edit_worktree()
        self.assertEqual(self.run_cli("diff", "w1"), 0, self.last_err)
        out = self.output()
        by_repo = {p["repo"]: p for p in out["patches"]}
        self.assertEqual(set(by_repo), {".", "sourbycraft-server/src/minecraft/java"})
        self.assertEqual(sorted(by_repo["."]["files"]), ["src/A.java", "src/Gone.java", "src/New.java"])
        mc = by_repo["sourbycraft-server/src/minecraft/java"]
        self.assertEqual(sorted(mc["main_paths"]), ["sourbycraft-server/src/minecraft/java/net/minecraft/Extra.java",
                                                    "sourbycraft-server/src/minecraft/java/net/minecraft/Mc.java"])
        self.assertTrue(mc["patch"].endswith("sourbycraft-server__src__minecraft__java.patch"))
        self.assertIn("+line 5 from worktree", Path(by_repo["."]["patch"]).read_text())
        self.assertIn("+line 2 patched", Path(mc["patch"]).read_text())

    def test_apply_clean_touches_working_tree_only(self):
        self.edit_worktree()
        self.assertEqual(self.run_cli("apply", "w1"), 0, self.out.getvalue())
        self.assertIn("line 5 from worktree", (self.main / "src/A.java").read_text())
        self.assertFalse((self.main / "src/Gone.java").exists())
        self.assertEqual((self.main / "src/New.java").read_text(), "brand new\n")
        mc = self.main / "sourbycraft-server/src/minecraft/java"
        self.assertIn("line 2 patched", (mc / "net/minecraft/Mc.java").read_text())
        self.assertEqual((mc / "net/minecraft/Extra.java").read_text(), "extra\n")
        # nothing staged, nothing committed, in the main repo or the MC repo
        self.assertEqual(sh(self.main, "diff", "--cached", "--name-only").stdout, "")
        self.assertEqual(sh(self.main, "rev-parse", "HEAD").stdout, self.head)
        self.assertEqual(sh(mc, "diff", "--cached", "--name-only").stdout, "")
        self.assertEqual(sh(mc, "log", "--format=%s").stdout.strip(), "base")
        self.assertFalse(self.cfg.mc_lock.exists())

    def test_apply_three_way_merges_nearby_main_change(self):
        (self.wt() / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 wt\n"))
        (self.main / "src/A.java").write_text(TEN.replace("line 3\n", "line 3 main\n"))  # inside the context
        self.assertEqual(self.run_cli("apply", "w1"), 0, self.out.getvalue())
        text = (self.main / "src/A.java").read_text()
        self.assertIn("line 3 main\n", text)
        self.assertIn("line 5 wt\n", text)
        self.assertEqual(sh(self.main, "diff", "--cached", "--name-only").stdout, "")

    def test_apply_reports_conflicts(self):
        (self.wt() / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 wt\n"))
        (self.main / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 main\n"))
        self.assertEqual(self.run_cli("apply", "w1"), 1)
        out = self.output()
        self.assertFalse(out["ok"])
        self.assertEqual(out["conflicts"], ["src/A.java"])
        self.assertIn("<<<<<<<", (self.main / "src/A.java").read_text())
        self.assertEqual(sh(self.main, "ls-files", "-u").stdout, "")  # the real index is untouched

    def lock(self, rel, worker):
        lock = self.cfg.file_locks / hashlib.sha1(str((self.main / rel).resolve()).encode()).hexdigest()
        lock.mkdir(parents=True)
        (lock / "owner").write_text(f"{worker} {rel} 1792500000\n")

    def test_apply_refuses_paths_locked_by_another_worker(self):
        self.edit_worktree()
        self.lock("src/New.java", "other-w")  # a file the patch creates
        self.lock("sourbycraft-server/src/minecraft/java/net/minecraft/Mc.java", "mc-w")
        self.assertEqual(self.run_cli("apply", "w1", "--as", "me"), 3)
        held = {h["path"]: h["by"] for h in self.output()["held"]}
        self.assertEqual(set(held), {"src/New.java", "sourbycraft-server/src/minecraft/java/net/minecraft/Mc.java"})
        self.assertTrue(held["src/New.java"].startswith("other-w "))
        self.assertEqual((self.main / "src/A.java").read_text(), TEN)  # nothing applied
        self.assertFalse((self.main / "src/New.java").exists())

    def test_apply_allows_locks_held_by_the_applying_worker(self):
        self.edit_worktree()
        self.lock("src/A.java", "me")
        self.assertEqual(self.run_cli("apply", "w1"), 3)  # without --as every lock blocks
        self.assertEqual(self.run_cli("apply", "w1", "--as", "me"), 0, self.out.getvalue())
        self.assertIn("line 5 from worktree", (self.main / "src/A.java").read_text())

    def test_remove_after_clean_apply_needs_no_discard(self):
        self.edit_worktree()
        self.assertEqual(self.run_cli("apply", "w1"), 0, self.out.getvalue())
        self.assertIn(".", load := json.loads(self.cfg.meta_file("w1").read_text())["applied_trees"])
        self.assertIn("sourbycraft-server/src/minecraft/java", load)
        self.assertEqual(self.run_cli("remove", "w1"), 0, self.last_err)
        self.assertFalse(self.wt().exists())

    def test_remove_refuses_changes_made_after_the_apply(self):
        self.edit_worktree()
        self.assertEqual(self.run_cli("apply", "w1"), 0, self.out.getvalue())
        (self.wt() / "README.md").write_text("edited after apply\n")
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("1 changed file(s)", self.last_err)
        self.assertIn("--discard", self.last_err)
        self.run_cli("list")
        self.assertEqual(self.output()["worktrees"][0]["unapplied"], {".": 1})

    def test_conflicted_apply_is_not_recorded_as_applied(self):
        (self.wt() / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 wt\n"))
        (self.main / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 main\n"))
        self.assertEqual(self.run_cli("apply", "w1"), 1)
        self.assertNotIn(".", json.loads(self.cfg.meta_file("w1").read_text()).get("applied_trees", {}))
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("--discard", self.last_err)

    def test_apply_with_no_changes_is_a_no_op(self):
        self.assertEqual(self.run_cli("apply", "w1"), 0)
        self.assertEqual(self.output()["applied"], [])


class ReceiptTest(WorktreeTestBase):
    """apply receipts: what `remove` may treat as taken by the main tree."""

    def setUp(self):
        super().setUp()
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)

    def write(self, rel, text, tree=None):
        path = (tree or self.wt()) / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def apply_ok(self, name="w1"):
        self.assertEqual(self.run_cli("apply", name), 0, self.out.getvalue() + self.last_err)

    def meta(self):
        return json.loads(self.cfg.meta_file("w1").read_text())

    def test_reapply_carries_a_revert_of_an_earlier_apply(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        self.write("src/A.java", TEN)            # back to the creation base
        self.write("README.md", "edit B\n")
        self.apply_ok()
        self.assertEqual((self.main / "src/A.java").read_text(), TEN)  # the revert reached the main tree
        self.assertEqual((self.main / "README.md").read_text(), "edit B\n")
        self.assertEqual(self.run_cli("remove", "w1"), 0, self.last_err)

    def test_diff_shows_only_what_the_next_apply_sends(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        self.write("README.md", "edit B\n")
        self.assertEqual(self.run_cli("diff", "w1"), 0)
        self.assertEqual([p["files"] for p in self.output()["patches"]], [["README.md"]])

    def conflict(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n").replace("line 7\n", "line 7 wt\n"))
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n").replace("line 7\n", "line 7 main\n"),
                   tree=self.main)
        self.assertEqual(self.run_cli("apply", "w1"), 1)

    def test_conflicting_reapply_blocks_remove_even_when_the_worktree_matches_the_old_receipt(self):
        self.conflict()
        self.assertIn(".", self.meta()["unresolved"])
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))  # matches the first receipt again
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("unresolved", self.last_err)
        self.assertTrue(self.wt().exists())

    def test_conflict_must_be_resolved_before_another_apply_or_remove(self):
        self.conflict()
        self.assertEqual(self.run_cli("apply", "w1"), 3)
        self.assertIn("resolve", self.out.getvalue())
        self.assertEqual(self.run_cli("resolve", "w1"), 2)  # markers still in src/A.java
        self.assertIn("src/A.java", self.last_err)
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n").replace("line 7\n", "line 7 wt\n"),
                   tree=self.main)
        self.assertEqual(self.run_cli("resolve", "w1"), 0, self.last_err)
        self.assertEqual(self.meta()["unresolved"], {})
        self.assertEqual(self.run_cli("remove", "w1"), 0, self.last_err)

    def test_failed_apply_without_markers_blocks_remove_until_a_retry_succeeds(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        failed = {"returncode": 1, "conflicts": [], "output": "error: patch failed"}
        with mock.patch.object(aw, "apply_patch", return_value=failed):
            self.assertEqual(self.run_cli("apply", "w1"), 1)
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("unresolved", self.last_err)
        self.apply_ok()  # a plain failure may be retried
        self.assertEqual(self.meta()["unresolved"], {})
        self.assertEqual(self.run_cli("remove", "w1"), 0, self.last_err)

    def test_tampered_receipt_is_not_trusted(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        self.write("README.md", "never applied\n")
        meta = self.meta()
        meta["applied_trees"]["."] = aw.snapshot(self.wt(), exclude=aw.CARRY_EXCLUDE_PATHS)  # forged
        self.cfg.meta_file("w1").write_text(json.dumps(meta))
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("receipt", self.last_err)
        self.assertTrue(self.wt().exists())

    def test_receipt_with_a_bogus_tree_is_not_trusted(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        meta = self.meta()
        meta["applied_trees"]["."] = "f" * 40
        meta["receipt_digest"] = aw.receipt_digest(meta)  # even with a matching digest
        self.cfg.meta_file("w1").write_text(json.dumps(meta))
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("receipt", self.last_err)

    def test_legacy_receipt_without_digest_is_ignored(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        meta = self.meta()
        del meta["receipt_digest"]
        self.cfg.meta_file("w1").write_text(json.dumps(meta))
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("--discard", self.last_err)

    def test_metadata_pointing_at_another_path_is_refused(self):
        meta = self.meta()
        meta["path"] = str(self.tmp / "elsewhere")
        self.cfg.meta_file("w1").write_text(json.dumps(meta))
        self.assertEqual(self.run_cli("remove", "w1", "--discard"), 2)
        self.assertIn("metadata", self.last_err)
        self.assertTrue(self.wt().exists())

    def test_stale_receipt_when_the_main_tree_changed_since_apply(self):
        self.write("src/A.java", TEN.replace("line 5\n", "line 5 A\n"))
        self.apply_ok()
        self.write("src/A.java", TEN, tree=self.main)  # someone reverted the applied file in the main tree
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("src/A.java", self.last_err)
        self.assertIn("main tree", self.last_err)
        self.assertEqual(self.run_cli("remove", "w1", "--discard"), 0, self.last_err)


class ApplyLockTest(WorktreeTestBase):
    def setUp(self):
        super().setUp()
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)
        (self.wt() / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 w1\n"))

    def lock_path(self, target):
        return self.cfg.apply_lock_root / f"sourby-apply-{aw.lock_key(target)}.lock"

    def test_apply_waits_for_another_apply_on_the_same_repository(self):
        path = self.lock_path(self.main)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a+") as other:
            fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.cfg.apply_lock_timeout = 0
            self.assertEqual(self.run_cli("apply", "w1"), 2)
        self.assertIn("another apply", self.last_err)
        self.assertEqual((self.main / "src/A.java").read_text(), TEN)  # nothing applied

    def test_patching_happens_under_the_repository_lock(self):
        held = []
        real = aw.apply_patch

        def probe(target, patch_file, files):
            with open(self.lock_path(target), "a+") as fh:
                try:
                    fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    held.append(False)
                    fcntl.flock(fh, fcntl.LOCK_UN)
                except BlockingIOError:
                    held.append(True)
            return real(target, patch_file, files)

        with mock.patch.object(aw, "apply_patch", side_effect=probe):
            self.assertEqual(self.run_cli("apply", "w1"), 0, self.out.getvalue())
        self.assertEqual(held, [True])

    def test_overlapping_applies_from_two_worktrees_never_both_succeed_silently(self):
        self.assertEqual(self.run_cli("create", "w2"), 0, self.last_err)
        (self.wt("w2") / "src/A.java").write_text(TEN.replace("line 5\n", "line 5 w2\n"))
        codes = {}
        barrier = threading.Barrier(2)

        def run(name):
            cfg = aw.Config(**{**self.cfg.__dict__, "out": io.StringIO()})
            barrier.wait()
            with mock.patch("sys.stderr", new_callable=io.StringIO):
                codes[name] = aw.main(["apply", name], cfg=cfg)

        threads = [threading.Thread(target=run, args=(n,)) for n in ("w1", "w2")]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=120)
        self.assertEqual(sorted(codes.values()), [0, 1])  # the second one sees the first and conflicts
        self.assertIn("<<<<<<<", (self.main / "src/A.java").read_text())


class RemoveListTest(WorktreeTestBase):
    def setUp(self):
        super().setUp()
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)

    def test_list_shows_base_and_dirty_state(self):
        self.assertEqual(self.run_cli("list"), 0)
        row = self.output()["worktrees"][0]
        self.assertEqual(row["base_commit"], sh(self.main, "rev-parse", "HEAD").stdout.strip()[:12])
        self.assertFalse(row["dirty"])
        (self.wt() / "README.md").write_text("changed\n")
        (self.wt() / "paper-server/src/P.java").write_text("changed\n")
        self.run_cli("list")
        row = self.output()["worktrees"][0]
        self.assertTrue(row["dirty"])
        self.assertEqual(row["changed_vs_base"], {".": 1, "paper-server": 1})

    def test_remove_refuses_unapplied_changes_and_running_build(self):
        (self.wt() / "README.md").write_text("changed\n")
        self.assertEqual(self.run_cli("remove", "w1"), 2)
        self.assertIn("--discard", self.last_err)
        gradle_lock = self.cfg.gradle_lock_root / "sourby-gradle-w1.lock"
        gradle_lock.mkdir(parents=True)
        self.assertEqual(self.run_cli("remove", "w1", "--discard"), 2)
        self.assertIn("Gradle build", self.last_err)
        gradle_lock.rmdir()
        self.assertTrue(self.wt().exists())

    def test_remove_stops_only_this_worktrees_gradle_daemon(self):
        mine, other = subprocess.Popen(["sleep", "60"]), subprocess.Popen(["sleep", "60"])
        self.addCleanup(other.kill)
        self.addCleanup(mine.kill)
        self.cfg.daemon_lister = lambda: [(mine.pid, f"{self.cfg.tmp_root}/w1/"),
                                          (other.pid, f"{self.cfg.tmp_root}/w10")]
        self.assertEqual(self.run_cli("remove", "w1"), 0, self.last_err)
        self.assertEqual(self.output()["stopped_gradle_daemons"], [mine.pid])
        self.assertEqual(mine.wait(timeout=10), -15)
        self.assertIsNone(other.poll())

    def test_remove_deletes_worktree_tmp_and_metadata(self):
        tmp = self.cfg.tmp_root / "w1"
        tmp.mkdir(parents=True)
        (tmp / "junit.tmp").write_text("x")
        self.assertEqual(self.run_cli("remove", "w1"), 0, self.last_err)
        self.assertFalse(self.wt().exists())
        self.assertFalse(tmp.exists())
        self.assertFalse(self.cfg.meta_file("w1").exists())
        self.assertNotIn(str(self.wt()), sh(self.main, "worktree", "list").stdout)
        self.assertEqual(self.run_cli("create", "w1"), 0, self.last_err)  # the name is free again


if __name__ == "__main__":
    unittest.main()
