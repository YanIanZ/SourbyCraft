"""Tests for scripts/gradle_locked.sh (per-tree Gradle lock + machine-wide build semaphore).

Every test uses temporary trees with a fake ./gradlew and a temporary lock root, so nothing here
touches the real /private/tmp/claude-501 locks or runs Gradle.
"""
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
WRAPPER = HERE / "gradle_locked.sh"
FAKE = """#!/bin/bash
echo "ran in $(pwd -P) tmpdir=$TMPDIR args=$*" >> "$EVENTS"
exit "${FAKE_EXIT:-0}"
"""


def git_init(path):
    path.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init", "-q", str(path)], check=True)


class GradleLockedTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="gradle-locked-py.")).resolve()
        self.addCleanup(shutil.rmtree, self.work, True)
        self.locks = self.work / "locks"
        self.events = self.work / "events"
        self.main = self.work / "main"
        self.wt = self.work / "wt-one"
        for tree in (self.main, self.wt):
            git_init(tree)
            (tree / "gradlew").write_text(FAKE)
            (tree / "gradlew").chmod(0o755)
        self.env = dict(os.environ, SOURBY_MAIN_TREE=str(self.main), SOURBY_LOCK_ROOT=str(self.locks),
                        SOURBY_TMP_ROOT=str(self.work / "tmp"), SOURBY_GRADLE_POLL_SECS="1",
                        EVENTS=str(self.events), AGENT_NAME="py-test")
        self.env.pop("SOURBY_TREE", None)

    def run_wrapper(self, *args, cwd=None, timeout=30, **env):
        return subprocess.run(["bash", str(WRAPPER), *args], cwd=cwd or self.work, env=dict(self.env, **env),
                              capture_output=True, text=True, timeout=timeout)

    def ran(self):
        return self.events.read_text().splitlines() if self.events.exists() else []

    def test_semaphore_shell_test_passes(self):
        result = subprocess.run(["bash", str(HERE / "test_gradle_locked.sh")], capture_output=True, text=True,
                                timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("max 2 at once", result.stdout)

    def test_cwd_inside_nested_repo_runs_in_enclosing_tree(self):
        nested = self.wt / "sourbycraft-server" / "src" / "minecraft" / "java"
        git_init(nested)
        result = self.run_wrapper("compileJava", cwd=nested)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.ran(), [f"ran in {self.wt} tmpdir={self.work}/tmp/sourbycraft-wt/wt-one/ args=compileJava"])
        self.assertIn(f"tree lock {self.locks}/sourby-gradle-wt-one.lock taken", result.stdout)
        self.assertEqual(os.listdir(self.locks), [])

    def test_sourby_tree_and_main_tree_lock(self):
        result = self.run_wrapper("build", SOURBY_TREE=str(self.main))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn(f"tree lock {self.locks}/sourby-gradle.lock taken", result.stdout)
        self.assertEqual(self.ran(), [f"ran in {self.main} tmpdir={self.work}/tmp/sourbycraft/ args=build"])

    def test_outside_any_git_tree_defaults_to_main(self):
        plain = self.work / "plain"
        plain.mkdir()
        result = self.run_wrapper("x", cwd=plain)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(self.ran()[0].startswith(f"ran in {self.main} "))

    def test_exit_code_is_propagated_and_locks_released(self):
        result = self.run_wrapper("x", SOURBY_TREE=str(self.wt), FAKE_EXIT="7")
        self.assertEqual(result.returncode, 7)
        self.assertEqual(os.listdir(self.locks), [])

    def test_lock_held_by_live_pid_blocks(self):
        lock = self.locks / "sourby-gradle-wt-one.lock"
        lock.mkdir(parents=True)
        (lock / "owner").write_text(f"other pid={os.getpid()} tree=wt-one\n")
        old = time.time() - 10_000
        os.utime(lock, (old, old))  # a live holder is never broken, however old the lock
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_wrapper("x", SOURBY_TREE=str(self.wt), timeout=4)
        self.assertEqual(self.ran(), [])
        self.assertTrue(lock.is_dir())

    def test_lock_of_dead_pid_is_broken(self):
        dead = subprocess.Popen(["true"])
        dead.wait()
        lock = self.locks / "sourby-gradle-wt-one.lock"
        lock.mkdir(parents=True)
        (lock / "owner").write_text(f"gone pid={dead.pid} tree=wt-one\n")
        result = self.run_wrapper("x", SOURBY_TREE=str(self.wt))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("is gone", result.stdout)
        self.assertEqual(len(self.ran()), 1)

    def test_old_format_owner_broken_only_when_stale(self):
        lock = self.locks / "sourby-gradle.lock"
        lock.mkdir(parents=True)
        (lock / "owner").write_text("opus-w7\n")  # the old wrapper wrote only the agent name
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_wrapper("x", SOURBY_TREE=str(self.main), timeout=4)
        old = time.time() - 3000
        os.utime(lock, (old, old))
        result = self.run_wrapper("x", SOURBY_TREE=str(self.main))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("breaking stale", result.stdout)

    def test_all_slots_held_blocks_until_one_frees(self):
        for i in (1, 2):
            slot = self.locks / f"sourby-gradle-slot-{i}"
            slot.mkdir(parents=True)
            (slot / "owner").write_text(f"busy pid={os.getpid()} tree=other\n")
        proc = subprocess.Popen(["bash", str(WRAPPER), "x"], env=dict(self.env, SOURBY_TREE=str(self.wt)),
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            time.sleep(2.5)
            self.assertIsNone(proc.poll())
            self.assertEqual(self.ran(), [])
            shutil.rmtree(self.locks / "sourby-gradle-slot-2")
            out, _ = proc.communicate(timeout=20)
        finally:
            if proc.poll() is None:
                proc.kill()
        self.assertEqual(proc.returncode, 0, out)
        self.assertIn("slot 2 taken", out)
        self.assertEqual(len(self.ran()), 1)
        self.assertTrue((self.locks / "sourby-gradle-slot-1").is_dir())  # someone else's slot is untouched

    def test_sourby_tree_without_gradlew_is_refused(self):
        result = self.run_wrapper("x", SOURBY_TREE=str(self.work))
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.ran(), [])


if __name__ == "__main__":
    unittest.main()
