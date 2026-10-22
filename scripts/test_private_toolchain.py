"""Pinned private artifacts must fail closed before the official build consumes them."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import private_toolchain as toolchain

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.iyanz.sourbypatcher</groupId>
  <artifactId>paper-toolchain</artifactId>
  <version>2</version>
  <dependencies>
    <dependency>
      <groupId>io.papermc.paperweight.patcher</groupId>
      <artifactId>io.papermc.paperweight.patcher.gradle.plugin</artifactId>
      <version>{paperweight}</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</project>
"""

MODULE = """{{
  "formatVersion": "1.1",
  "component": {{"group": "dev.iyanz.sourbypatcher", "module": "paper-toolchain", "version": "2"}},
  "variants": [
    {{
      "name": "runtimeElements",
      "dependencies": [
        {{"group": "io.papermc.paperweight.patcher",
          "module": "io.papermc.paperweight.patcher.gradle.plugin",
          "version": {{"requires": "{paperweight}"}}}}
      ]
    }}
  ]
}}
"""


class PrivateToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.config = {'clipVersion': '1', 'patcherVersion': '2', 'paperweightVersion': '3.0.0-beta.1'}
        for base, artifact, version, key in [
            ('dev/iyanz/sourbyclip', 'sourbyclip', '1', 'clipSha256'),
            ('dev/iyanz/sourbypatcher/paper-toolchain', 'paper-toolchain', '2', 'patcherSha256'),
        ]:
            path = self.repo / base / version / f'{artifact}-{version}.jar'
            path.parent.mkdir(parents=True)
            path.write_bytes(artifact.encode())
            self.config[key] = toolchain.digest(path)
        self.base = self.repo / 'dev/iyanz/sourbypatcher/paper-toolchain/2'
        self.pom = self.base / 'paper-toolchain-2.pom'
        self.module = self.base / 'paper-toolchain-2.module'
        self.write_metadata(self.config['paperweightVersion'])
        self.override = patch.object(toolchain, 'properties', return_value=self.config)
        self.override.start()
        self.addCleanup(self.override.stop)

    def write_metadata(self, paperweight):
        self.pom.write_text(POM.format(paperweight=paperweight))
        self.module.write_text(MODULE.format(paperweight=paperweight))
        self.config['patcherPomSha256'] = toolchain.digest(self.pom)
        self.config['patcherModuleSha256'] = toolchain.digest(self.module)

    def test_accepts_both_approved_artifacts(self):
        toolchain.verify(self.repo)

    def test_missing_private_artifact_fails_with_recovery_message(self):
        (self.repo / 'dev/iyanz/sourbyclip/1/sourbyclip-1.jar').unlink()
        with self.assertRaisesRegex(ValueError, 'Publish the pinned private checkout'):
            toolchain.verify(self.repo)

    def test_substituted_patcher_is_refused(self):
        (self.repo / 'dev/iyanz/sourbypatcher/paper-toolchain/2/paper-toolchain-2.jar').write_bytes(b'replacement')
        with self.assertRaisesRegex(ValueError, 'SourbyPatcher SHA-256 mismatch'):
            toolchain.verify(self.repo)

    def test_substituted_pom_is_refused(self):
        # Same jar, different POM: a republished POM must not move paperweight unnoticed.
        self.pom.write_text(POM.format(paperweight='4.0.0-beta.1'))
        with self.assertRaisesRegex(ValueError, 'SourbyPatcher POM SHA-256 mismatch'):
            toolchain.verify(self.repo)

    def test_substituted_module_metadata_is_refused(self):
        # Gradle resolves the .module, so it needs the same treatment as the POM.
        self.module.write_text(MODULE.format(paperweight='4.0.0-beta.1'))
        with self.assertRaisesRegex(ValueError, 'SourbyPatcher module metadata SHA-256 mismatch'):
            toolchain.verify(self.repo)

    def test_pom_declaring_another_paperweight_version_is_refused(self):
        # Even an approved POM hash fails when it disagrees with the pinned paperweightVersion.
        self.pom.write_text(POM.format(paperweight='4.0.0-beta.1'))
        self.config['patcherPomSha256'] = toolchain.digest(self.pom)
        with self.assertRaisesRegex(ValueError, 'paperweightVersion is 3.0.0-beta.1'):
            toolchain.verify(self.repo)

    def test_module_declaring_another_paperweight_version_is_refused(self):
        self.module.write_text(MODULE.format(paperweight='4.0.0-beta.1'))
        self.config['patcherModuleSha256'] = toolchain.digest(self.module)
        with self.assertRaisesRegex(ValueError, 'module metadata declares paperweight 4.0.0-beta.1'):
            toolchain.verify(self.repo)

    def test_missing_metadata_fails_with_recovery_message(self):
        for artifact in (self.pom, self.module):
            with self.subTest(artifact=artifact.suffix):
                self.write_metadata(self.config['paperweightVersion'])
                artifact.unlink()
                with self.assertRaisesRegex(ValueError, 'SourbyPatcher .* missing'):
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
