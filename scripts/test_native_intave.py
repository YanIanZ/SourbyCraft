"""Execute native lifecycle/failure probes against the real JDK-only server controller."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class NativeIntaveTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        if not cls.java or not javac:
            raise RuntimeError("JDK 25 is required for native lifecycle probes")
        subprocess.run([javac, "--release", "25", "-d", cls.temp.name,
                        str(ROOT / "sourbycraft-server/src/main/java/dev/yanianz/intave/NativeIntave.java"),
                        str(ROOT / "scripts/fixtures/intave/NativeIntaveLifecycleProbe.java")],
                       check=True, capture_output=True, text=True, timeout=30)

    def probe(self, scenario):
        result = subprocess.run([self.java, "-cp", self.temp.name,
                                 "NativeIntaveLifecycleProbe", scenario],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_disabled_never_constructs_or_restarts(self): self.probe("disabled")
    def test_missing_provider_is_not_active(self): self.probe("missing")
    def test_waits_for_deferred_initialization(self): self.probe("deferred")
    def test_failed_start_disposes_once(self): self.probe("start-failure")
    def test_ready_is_required_after_completion(self): self.probe("not-ready")
    def test_deferred_failure_disables_callbacks(self): self.probe("deferred-failure")
    def test_late_completion_and_failure_cannot_revive_stop(self): self.probe("late-completion")
    def test_v26_failure_during_ready_cannot_publish_active(self): self.probe("failure-during-ready")
    def test_owner_failure_defers_cleanup_to_shutdown(self): self.probe("tick-failure")
    def test_cleanup_failure_is_retained(self): self.probe("close-failure")
    def test_shutdown_drains_owner_callback(self): self.probe("drain-owner")
    def test_packet_filter_context_is_thread_local_and_restored(self): self.probe("filter-context")


if __name__ == "__main__":
    unittest.main()
