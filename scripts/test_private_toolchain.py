"""Pinned private artifacts must fail closed before the official build consumes them."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import private_toolchain as toolchain


class PrivateToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.config = {'clipVersion': '1', 'patcherVersion': '2'}
        for base, artifact, version, key in [
            ('dev/iyanz/sourbyclip', 'sourbyclip', '1', 'clipSha256'),
            ('dev/iyanz/sourbypatcher/canvas-toolchain', 'canvas-toolchain', '2', 'patcherSha256'),
        ]:
            path = self.repo / base / version / f'{artifact}-{version}.jar'
            path.parent.mkdir(parents=True)
            path.write_bytes(artifact.encode())
            self.config[key] = toolchain.digest(path)
        self.override = patch.object(toolchain, 'properties', return_value=self.config)
        self.override.start()
        self.addCleanup(self.override.stop)

    def test_accepts_both_approved_artifacts(self):
        toolchain.verify(self.repo)

    def test_missing_private_artifact_fails_with_recovery_message(self):
        (self.repo / 'dev/iyanz/sourbyclip/1/sourbyclip-1.jar').unlink()
        with self.assertRaisesRegex(ValueError, 'Publish the pinned private checkout'):
            toolchain.verify(self.repo)

    def test_substituted_patcher_is_refused(self):
        (self.repo / 'dev/iyanz/sourbypatcher/canvas-toolchain/2/canvas-toolchain-2.jar').write_bytes(b'replacement')
        with self.assertRaisesRegex(ValueError, 'SourbyPatcher SHA-256 mismatch'):
            toolchain.verify(self.repo)

    def test_relative_checkout_directory_runs_each_wrapper_from_its_checkout(self):
        import json
        import os
        import subprocess
        root = self.repo / 'source'
        (root / 'build-data').mkdir(parents=True)
        lock = {}
        checkouts = self.repo / 'checkouts'
        for name in ('SourbyPatcher', 'SourbyClip'):
            checkout = checkouts / name
            checkout.mkdir(parents=True)
            wrapper = checkout / 'gradlew'
            wrapper.write_text('#!/bin/sh\nexit 0\n')
            wrapper.chmod(0o755)
            for args in (['init', '-q'], ['add', 'gradlew'],
                         ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                          '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture']):
                subprocess.run(['git', *args], cwd=checkout, check=True)
            revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip()
            lock[name] = {'revision': revision}
        (root / 'build-data/private-toolchain.lock.json').write_text(json.dumps(lock))
        previous = Path.cwd()
        try:
            os.chdir(self.repo)
            with patch.object(toolchain, 'ROOT', root):
                toolchain.publish(Path('checkouts'), self.repo)
        finally:
            os.chdir(previous)
