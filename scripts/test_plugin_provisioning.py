"""Run actual bootstrap plugin installation and configuration regression probes."""
import os
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/bootstrap"


class PluginProvisioningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        names = ("PluginProvisioner", "ProtocolLibProvisioner", "BootstrapPluginSettings",
                 "LibDownloader", "BootstrapManifest", "Sha256Verifier")
        subprocess.run([javac, "--release", "25", "-d", cls.classes.name,
                        *[str(PACKAGE / (name + ".java")) for name in names],
                        str(ROOT / "scripts/fixtures/PluginProvisioningProbe.java")],
                       check=True, capture_output=True, text=True, timeout=45)
        resources = Path(cls.classes.name) / "sourbycraft/via"
        resources.parent.mkdir(parents=True)
        shutil.copytree(ROOT / "sourbycraft-server/src/main/resources/sourbycraft/via", resources)

    def probe(self, scenario):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([self.java, "-cp", self.classes.name,
                "dev.iyanz.sourbycraft.bootstrap.PluginProvisioningProbe", scenario],
                cwd=directory, capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_independent_table_and_dotted_settings(self): self.probe("settings")
    def test_verified_install_reuses_offline_cache(self): self.probe("install")
    def test_wrong_size_hash_and_http_are_rejected(self): self.probe("bad-download")
    def test_renamed_paper_plugin_and_operator_config_are_preserved(self): self.probe("preserve")
    def test_operator_jar_arriving_during_download_is_preserved(self): self.probe("late-operator")
    def test_manual_via_jars_are_not_duplicated(self): self.probe("preserve-via")
    def test_quarantine_restore_preserves_conflicting_quarantine(self): self.probe("quarantine")
    def test_clip_offline_mode_never_downloads(self): self.probe("offline")
    def test_native_intave_does_not_install_second_packet_injector(self): self.probe("native")
    def test_cli_plugin_directory_is_used_for_configs(self): self.probe("custom-directory")

    def test_runtime_pin_matches_official_asset_provenance(self):
        metadata = json.loads((ROOT / "build-data/protocollib-pin.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([self.java, "-cp", self.classes.name,
                "dev.iyanz.sourbycraft.bootstrap.PluginProvisioningProbe", "pin"],
                cwd=directory, capture_output=True, text=True, check=True, timeout=10)
        self.assertEqual([metadata["file_name"], metadata["download_url"], metadata["sha256"],
                          str(metadata["size_bytes"])], result.stdout.splitlines())

    def test_hook_precedes_the_actual_plugin_scan(self):
        patch = ROOT / "sourbycraft-server/minecraft-patches/features/0025-SourbyCraft-provision-plugins-before-plugin-scan.patch"
        source = Path("net/minecraft/server/Main.java")
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / source
            target.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / "sourbycraft-server/src/minecraft/java" / source, target)
            if "PluginProvisioner.provisionJars(" not in target.read_text():
                subprocess.run(["git", "apply", str(patch)], cwd=directory,
                               check=True, capture_output=True, text=True, timeout=15)
            code = target.read_text()
        self.assertLess(code.index("PluginProvisioner.provisionJars("),
                        code.index("PluginInitializerManager.load(options)"))
        self.assertIn('((java.io.File) options.valueOf("plugins")).toPath()', code)


if __name__ == "__main__":
    unittest.main()
