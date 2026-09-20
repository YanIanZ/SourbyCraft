"""Process-level regression coverage for SPEC V13 (bootstrap exit status)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class ClipLauncherTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = shutil.which("java")
        javac = shutil.which("javac")
        if not cls.java or not javac:
            raise RuntimeError("Launcher regression tests require a JDK on PATH")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        # Test the exact privately published launcher class, not a public source copy.
        import zipfile
        from private_toolchain import properties, verify
        repository = Path.home() / '.m2/repository'
        verify(repository)
        version = properties()['clipVersion']
        artifact = repository / 'dev/iyanz/sourbyclip' / version / f'sourbyclip-{version}.jar'
        with zipfile.ZipFile(artifact) as jar:
            for name in ('dev/iyanz/sourbyclip/Main.class', 'META-INF/sourbyclip.properties'):
                target = cls.root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(jar.read(name))
        cls.fixtures = {}
        for name, body in {
            "success": 'System.out.println("forwarded:" + args[0]);',
            "failure": 'throw new IllegalStateException("fixture hash check failed");',
            "error": 'throw new AssertionError("fixture bootstrap error");',
        }.items():
            directory = cls.root / name
            directory.mkdir()
            fixture = directory / "Sourbyclip.java"
            fixture.write_text('package dev.iyanz.sourbyclip; public class Sourbyclip {'
                               'public static void main(String[] args) {' + body + '}}')
            subprocess.run([javac, "--release", "8", "-d", str(directory), str(fixture)],
                           check=True, capture_output=True, text=True, timeout=60)
            cls.fixtures[name] = directory

    def launch(self, fixture=None):
        import os
        classpath = str(self.root)
        if fixture:
            classpath += os.pathsep + str(self.fixtures[fixture])
        return subprocess.run([self.java, "-cp", classpath, "dev.iyanz.sourbyclip.Main", "--nogui"],
                              capture_output=True, text=True, timeout=30)

    def test_success_preserves_arguments_and_zero_exit(self):
        result = self.launch("success")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("forwarded:--nogui", result.stdout)

    def test_bootstrap_exception_exits_nonzero_with_cause(self):
        result = self.launch("failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture hash check failed", result.stderr)

    def test_bootstrap_error_exits_nonzero_with_cause(self):
        result = self.launch("error")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture bootstrap error", result.stderr)

    def test_missing_bootstrap_class_exits_nonzero(self):
        result = self.launch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ClassNotFoundException", result.stderr)
