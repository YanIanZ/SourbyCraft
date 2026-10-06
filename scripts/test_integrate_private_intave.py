"""Native source migration must validate the entire port before changing the baseline."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import integrate_private_intave as native


class PrivateNativeIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.commit = "a" * 40
        self.files = {
            "src/main/java/dev/yanianz/intave/IntavePlugin.java": """package dev.yanianz.intave;
public class IntavePlugin extends JavaPlugin {
  private final AtomicBoolean shutdownStarted = new AtomicBoolean();
  public void redirectPluginLogger() { }
  public File dataFolder() { return null; }
  public void initialize() {
    stage2();
      StartupTasks.runAll();
  }
  public IntaveAccess access() { return null; }
}
""",
            "src/main/java/dev/yanianz/intave/module/Requirements.java": "package dev.yanianz.intave.module;\n",
            "src/main/java/dev/yanianz/intave/adapter/ProtocolLibraryAdapter.java": "package dev.yanianz.intave.adapter;\n",
            "src/main/java/dev/yanianz/intave/packet/PacketSender.java": "package dev.yanianz.intave.packet;\n",
            "src/main/java/dev/yanianz/intave/command/stages/DiagnosticsStage.java": "package dev.yanianz.intave.command.stages;\n",
            "src/main/java/dev/yanianz/intave/block/fluid/v26FluidResolver.java":
                "package dev.yanianz.intave.block.fluid;\n// IBlockData @PatchyAutoTranslation\nclass v26FluidResolver {}\n",
            "src/main/java/dev/yanianz/intave/block/fluid/v18b2FluidResolver.java":
                "package dev.yanianz.intave.block.fluid;\n// IBlockData @PatchyAutoTranslation\nclass v18b2FluidResolver {}\n",
        }
        for name, package in (("v20BlockAccessor", "block.access"),
                              ("v14BlockAccessor", "block.access"),
                              ("v20ShapeDrill", "block.shape.resolve.drill"),
                              ("v17b1ShapeDrill", "block.shape.resolve.drill"),
                              ("ModernIndexer", "block.variant.index"),
                              ("v16ConversionBridge", "block.variant.convert")):
            self.files[f"src/main/java/dev/yanianz/intave/{package.replace('.', '/')}/{name}.java"] = (
                f"package dev.yanianz.intave.{package};\n// @PatchyAutoTranslation\nclass {name} {{}}\n")
        self.baseline = []
        for name, source in self.files.items():
            path = self.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            self.baseline.append({"path": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        (self.directory / "workspace.json").write_text(json.dumps({
            "commit": self.commit, "source_files": self.baseline,
            "local_compile_libraries": [], "missing_fixtures": [],
        }))
        override = patch.object(native, "read_plan", return_value={"commit": self.commit, "dependencies": []})
        override.start()
        self.addCleanup(override.stop)

    def snapshot(self):
        return {str(path.relative_to(self.directory)): path.read_bytes()
                for path in self.directory.rglob("*") if path.is_file()}

    def complete_baseline(self):
        diagnostics = self.directory / "src/main/java/dev/yanianz/intave/command/stages/DiagnosticsStage.java"
        diagnostics.write_text("package dev.yanianz.intave.command.stages;\nclass DiagnosticsStage {\n"
                               "  public void attackTraceCommand(User user) { }\n}\n")
        generated = self.directory / "generated/main/java/dev/yanianz/intave/IntaveBuildConfig.java"
        generated.parent.mkdir(parents=True)
        generated.write_text("package dev.yanianz.intave;\nclass IntaveBuildConfig {}\n")
        self.baseline = [{"path": str(path.relative_to(self.directory)),
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                         for path in sorted(self.directory.rglob("*.java"))]
        metadata = json.loads((self.directory / "workspace.json").read_text())
        metadata["source_files"] = self.baseline
        (self.directory / "workspace.json").write_text(json.dumps(metadata))

    def report(self, **overrides):
        report = {"commit": self.commit, "state": "NATIVE_CODE_INTEGRATED_UNVERIFIED",
                  "removed_files": [], "files": self.baseline}
        report.update(overrides)
        (self.directory / "native-port.json").write_text(json.dumps(report))

    def test_v29_failed_transformation_preserves_every_baseline_byte(self):
        before = self.snapshot()
        with self.assertRaises(ValueError):
            native.integrate(self.directory)
        self.assertEqual(before, self.snapshot())

    def test_existing_report_must_match_pinned_commit(self):
        self.report(commit="b" * 40)
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "provenance|commit"):
            native.integrate(self.directory)
        self.assertEqual(before, self.snapshot())

    def test_existing_report_must_not_accept_returned_legacy_entrypoint(self):
        name = self.baseline[0]["path"]
        self.report(removed_files=[name], files=self.baseline[1:])
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "preserved|changed|Legacy"):
            native.integrate(self.directory)
        self.assertEqual(before, self.snapshot())

    def test_port_publishes_only_after_rendering_and_second_run_changes_nothing(self):
        self.complete_baseline()
        report = native.integrate(self.directory)
        self.assertFalse((self.directory / "src/main/java/dev/yanianz/intave/IntavePlugin.java").exists())
        self.assertTrue((self.directory / "src/main/java/dev/yanianz/intave/IntaveEngine.java").is_file())
        self.assertFalse(report["anticheat_active"])
        before = self.snapshot()
        self.assertEqual(report, native.integrate(self.directory))
        self.assertEqual(before, self.snapshot())

    def test_v29_partial_write_error_rolls_back_without_publishing_ledger(self):
        self.complete_baseline()
        before = self.snapshot()
        original_write = Path.write_bytes
        writes = 0

        def fail_second_write(path, data, *args, **kwargs):
            nonlocal writes
            writes += 1
            if writes == 2:
                original_write(path, b"partial write")
                raise OSError("controlled disk write failure")
            return original_write(path, data, *args, **kwargs)

        with patch.object(Path, "write_bytes", fail_second_write):
            with self.assertRaisesRegex(OSError, "controlled"):
                native.integrate(self.directory)
        self.assertEqual(before, self.snapshot())

    def test_v29_written_hash_mismatch_rolls_back_before_ledger_publication(self):
        self.complete_baseline()
        before = self.snapshot()
        original_write = Path.write_bytes
        corrupted = False

        def corrupt_once(path, data):
            nonlocal corrupted
            if not corrupted:
                corrupted = True
                return original_write(path, data + b"corrupted")
            return original_write(path, data)

        with patch.object(Path, "write_bytes", corrupt_once):
            with self.assertRaisesRegex(ValueError, "Written native source"):
                native.integrate(self.directory)
        self.assertEqual(before, self.snapshot())

    def test_v29_method_transform_rejects_unbalanced_or_duplicate_targets(self):
        for source in ("void example() {", "void example() {} void example() {}"):
            with self.subTest(source=source):
                with self.assertRaises(ValueError):
                    native.replace_method(source, "void example()", "replacement")

    def test_v29_replace_method_ignores_braces_in_literals_and_comments(self):
        source = '''before
  public void example() {
    String text = "}"; // }
    char character = '{'; /* { */
  }
after
'''
        self.assertEqual("before\nREPLACED\nafter\n",
                         native.replace_method(source, "  public void example()", "REPLACED"))

    def refresh_render(self):
        self.complete_baseline()
        report = native.integrate(self.directory)
        name = "src/main/java/dev/yanianz/intave/IntaveEngine.java"
        source = (self.directory / name).read_text() + "\n// template upgrade\n"
        rendered = json.loads(json.dumps(report))
        for entry in rendered["files"]:
            if entry["path"] == name:
                entry["sha256"] = hashlib.sha256(source.encode()).hexdigest()
        return {name: source}, rendered

    def test_refresh_preserves_local_edits_before_rendering(self):
        updates, report = self.refresh_render()
        path = self.directory / next(iter(updates))
        path.write_text(path.read_text() + "// operator edit\n")
        before = self.snapshot()
        with patch.object(native, "render_from_verified") as render:
            with self.assertRaises(ValueError): native.refresh_port(self.directory)
            render.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_refresh_publishes_verified_hashes_and_is_idempotent(self):
        updates, report = self.refresh_render()
        with patch.object(native, "render_from_verified", return_value=(updates, report)):
            self.assertEqual(1, native.refresh_port(self.directory)["changed_files"])
            before = self.snapshot()
            self.assertEqual(0, native.refresh_port(self.directory)["changed_files"])
        self.assertEqual(before, self.snapshot())

    def test_refresh_rolls_back_source_and_partially_written_ledger(self):
        updates, report = self.refresh_render()
        before = self.snapshot()
        def fail_ledger(path, data):
            path.write_bytes(b"partial ledger")
            raise OSError("controlled publication failure")
        with patch.object(native, "render_from_verified", return_value=(updates, report)), \
                patch.object(native, "write_json", side_effect=fail_ledger):
            with self.assertRaises(OSError): native.refresh_port(self.directory)
        self.assertEqual(before, self.snapshot())

    def test_code_mapping_preserves_notices_literals_and_longer_identifiers(self):
        source = 'PotionEffectType.SLOW; /* PotionEffectType.SLOW */ "PotionEffectType.SLOW"; PotionEffectType.SLOWNESS; shape.toList();'
        mapped = native.replace_code(source, {"PotionEffectType.SLOW": "PotionEffectType.SLOWNESS", "shape.toList()": "shape.toAabbs()"})
        self.assertEqual('PotionEffectType.SLOWNESS; /* PotionEffectType.SLOW */ "PotionEffectType.SLOW"; PotionEffectType.SLOWNESS; shape.toAabbs();', mapped)


if __name__ == "__main__":
    unittest.main()
