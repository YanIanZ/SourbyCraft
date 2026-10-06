"""Private source preparation must preserve provenance and never imply runtime protection."""
import json
import hashlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import private_intave_workspace as workspace
from prepare_private_intave import blob_hash


class PrivateIntaveWorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.plan = {
            "commit": "a" * 40, "scope": "personal-server-only", "dependencies": [],
            "gates": [{"id": "native-boot", "description": "prove active native checks"}],
        }
        self.files = {
            "LICENSE.md": b"Required Notice: Intave copyright\n",
            "src/main/java/de/jpx3/intave/Core.java": b"package de.jpx3.intave;\n",
            "src/test/java/de/jpx3/intave/CoreTest.java": b"package de.jpx3.intave;\n",
            "src/test/resources/recording.ptr": b"\xff\x00unchanged recording",
        }
        for name, data in self.files.items():
            p = self.source / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        self.inventory = {"commit": self.plan["commit"], "files": [
            {"path": name, "sha": blob_hash(data)} for name, data in self.files.items()
        ], "missing_files": []}
        self.manifest = self.root / "inventory.json"
        self.save_manifest()
        (self.root / "build-data").mkdir()
        (self.root / "build-data/intave-private-plan.json").write_text(json.dumps(self.plan))
        templates = self.root / "scripts/templates/intave-private"
        templates.mkdir(parents=True)
        (templates / "build.gradle.kts").write_text("@DEPENDENCIES@\n@FIXTURES@\ntasks.jar { enabled = false }\n")
        override = patch.object(workspace, "ROOT", self.root)
        override.start()
        self.addCleanup(override.stop)

    def save_manifest(self):
        self.manifest.write_text(json.dumps(self.inventory))

    def prepare(self):
        return workspace.prepare(self.source, self.manifest)

    def test_relocates_tests_and_preserves_binary_recordings_and_notice(self):
        result = self.prepare()
        self.assertIn("package dev.yanianz.intave;", (result / "src/test/java/dev/yanianz/intave/CoreTest.java").read_text())
        self.assertEqual(self.files["src/test/resources/recording.ptr"], (result / "src/test/resources/recording.ptr").read_bytes())
        self.assertEqual(self.files["LICENSE.md"], (result / "LICENSE.md").read_bytes())
        self.assertEqual(self.files["src/main/java/de/jpx3/intave/Core.java"],
                         (result / "verified-upstream/src/main/java/de/jpx3/intave/Core.java").read_bytes())

    def test_tampering_is_rejected_before_workspace_creation(self):
        (self.source / "LICENSE.md").write_text("changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.prepare()
        self.assertFalse((self.root / ".private-intave").exists())

    def test_wrong_revision_is_rejected(self):
        self.inventory["commit"] = "b" * 40
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "pinned"):
            self.prepare()

    def test_traversal_and_duplicate_paths_are_rejected(self):
        for name in ["../outside", "/outside", "src/../outside", "LICENSE.md"]:
            with self.subTest(name=name):
                inventory = {**self.inventory, "files": self.inventory["files"] + [{"path": name, "sha": "0" * 40}]}
                with self.assertRaisesRegex(ValueError, "Unsafe or duplicate"):
                    workspace.verify_sources(self.source, inventory, self.plan)

    def test_snapshot_symlink_escape_is_rejected(self):
        external = self.root / "outside.txt"
        external.write_bytes(self.files["LICENSE.md"])
        (self.source / "LICENSE.md").unlink()
        (self.source / "LICENSE.md").symlink_to(external)
        with self.assertRaisesRegex(ValueError, "escapes"):
            self.prepare()

    def test_repeat_preparation_preserves_port_edits(self):
        result = self.prepare()
        target = result / "src/main/java/dev/yanianz/intave/Core.java"
        target.write_text("port work")
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual("port work", target.read_text())

    def test_preparation_does_not_claim_native_gates_passed(self):
        result = self.prepare()
        metadata = json.loads((result / "workspace.json").read_text())
        matrix = json.loads((result / "verification-matrix.json").read_text())
        self.assertFalse(metadata["native_active"])
        self.assertFalse(matrix["native_active"])
        self.assertEqual("PENDING", matrix["gates"][0]["status"])
        self.assertIn("enabled = false", (result / "build.gradle.kts").read_text())

    def test_doctor_tracks_source_edits(self):
        result = self.prepare()
        target = result / "src/main/java/dev/yanianz/intave/Core.java"
        target.write_text("port work")
        report = workspace.inspect(result, self.root / "cache")
        self.assertFalse(report["compile_preflight_ready"])
        self.assertFalse(report["native_active"])
        self.assertEqual([str(target.relative_to(result))], report["changed_sources"])

    def test_v28_doctor_rejects_unrecorded_build_inputs(self):
        result = self.prepare()
        for name in ("src/main/java/Unexpected.java", "src/test/java/UnexpectedTest.java",
                     "generated/main/java/Unexpected.java", "src/main/resources/unexpected.yml"):
            with self.subTest(name=name):
                target = result / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("unexpected build input")
                try:
                    report = workspace.inspect(result, self.root / "cache")
                    self.assertFalse(report["compile_preflight_ready"])
                    self.assertIn(name, report["changed_sources"])
                    self.assertEqual("unexpected build input", target.read_text())
                finally:
                    target.unlink()

    def test_v28_doctor_rejects_unrecorded_compile_library(self):
        result = self.prepare()
        (result / "libs/Unexpected.jar").write_bytes(b"unrecorded library")
        report = workspace.inspect(result, self.root / "cache")
        self.assertFalse(report["compile_preflight_ready"])
        self.assertEqual(["Unexpected.jar"], report["changed_local_compile_libraries"])

    def test_v28_workspace_source_symlink_escape_is_rejected(self):
        result = self.prepare()
        target = result / "src/main/java/dev/yanianz/intave/Core.java"
        external = self.root / "outside.java"
        external.write_bytes(target.read_bytes())
        target.unlink()
        target.symlink_to(external)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            workspace.inspect(result, self.root / "cache")

    def test_doctor_accepts_native_overlay_but_detects_later_edits(self):
        result = self.prepare()
        target = result / "src/main/java/dev/yanianz/intave/Core.java"
        target.write_text("native port")
        (result / "native-port.json").write_text(json.dumps({
            "commit": self.plan["commit"], "state": "NATIVE_CODE_INTEGRATED_UNVERIFIED",
            "removed_files": [], "files": [{"path": str(target.relative_to(result)),
                                             "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}],
        }))
        report = workspace.inspect(result, self.root / "cache")
        self.assertEqual([], report["changed_sources"])
        self.assertEqual("NATIVE_CODE_INTEGRATED_UNVERIFIED", report["state"])
        self.assertFalse(report["native_active"])
        target.write_text("edited port")
        self.assertFalse(workspace.inspect(result, self.root / "cache")["compile_preflight_ready"])

    def test_doctor_rejects_returned_legacy_entrypoint(self):
        result = self.prepare()
        target = result / "src/main/java/dev/yanianz/intave/Core.java"
        target.unlink()
        (result / "native-port.json").write_text(json.dumps({
            "commit": self.plan["commit"], "state": "NATIVE_CODE_INTEGRATED_UNVERIFIED",
            "removed_files": [str(target.relative_to(result))], "files": [],
        }))
        self.assertEqual([], workspace.inspect(result, self.root / "cache")["changed_sources"])
        target.write_text("legacy entrypoint")
        self.assertEqual([str(target.relative_to(result))], workspace.inspect(result, self.root / "cache")["changed_sources"])

    def test_doctor_rejects_native_overlay_escape(self):
        result = self.prepare()
        (result / "native-port.json").write_text(json.dumps({
            "commit": self.plan["commit"], "state": "NATIVE_CODE_INTEGRATED_UNVERIFIED",
            "removed_files": [], "files": [{"path": "../outside", "sha256": "0" * 64}],
        }))
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            workspace.inspect(result, self.root / "cache")

    def test_v25_utf8_binary_fixture_is_not_relocated(self):
        name = "src/test/resources/text-readable.ptr"
        data = b"\x00de.jpx3.intave.Check\x00de/jpx3/intave/Check"
        self.files[name] = data
        path = self.source / name
        path.write_bytes(data)
        self.inventory["files"].append({"path": name, "sha": blob_hash(data)})
        self.save_manifest()
        result = self.prepare()
        self.assertEqual(data, (result / name).read_bytes())

    def test_wrong_fixture_cannot_satisfy_full_suite_readiness(self):
        recording = b"pinned binary"
        self.inventory["missing_files"] = [{"path": "src/test/resources/needed.ptr", "sha": blob_hash(recording)}]
        self.save_manifest()
        result = self.prepare()
        fixture = result / "src/test/resources/needed.ptr"
        fixture.write_bytes(b"placeholder")
        self.assertFalse(workspace.inspect(result, self.root / "cache")["fixture_suite_preflight_ready"])
        fixture.write_bytes(recording)
        report = workspace.inspect(result, self.root / "cache")
        self.assertTrue(report["fixture_suite_preflight_ready"])
        self.assertFalse(report["native_active"])

    def fixture_workspace(self, data=b"expected binary"):
        self.inventory["missing_files"] = [{"path": "src/test/resources/needed.ptr", "sha": blob_hash(data)}]
        self.save_manifest()
        return self.prepare()

    def response(self, data):
        response = io.BytesIO(data)
        response.geturl = lambda: "https://raw.githubusercontent.com/intave/intave/pinned/resource"
        return response

    def test_fixture_download_rejects_wrong_hash_without_creating_file(self):
        result = self.fixture_workspace()
        with patch.object(workspace.urllib.request, "urlopen", return_value=self.response(b"wrong")):
            with self.assertRaisesRegex(ValueError, "hash verification"):
                workspace.fetch_fixtures(result)
        self.assertFalse((result / "src/test/resources/needed.ptr").exists())

    def test_fixture_download_verifies_pin_and_is_offline_when_cached(self):
        result = self.fixture_workspace()
        with patch.object(workspace.urllib.request, "urlopen", return_value=self.response(b"expected binary")) as fetch:
            self.assertEqual(1, workspace.fetch_fixtures(result))
            self.assertIn(self.plan["commit"], fetch.call_args.args[0])
        with patch.object(workspace.urllib.request, "urlopen", side_effect=AssertionError("network unnecessary")):
            self.assertEqual(0, workspace.fetch_fixtures(result))

    def test_fixture_download_preserves_existing_mismatched_file(self):
        result = self.fixture_workspace()
        target = result / "src/test/resources/needed.ptr"
        target.write_bytes(b"local work")
        with self.assertRaisesRegex(ValueError, "preserved"):
            workspace.fetch_fixtures(result)
        self.assertEqual(b"local work", target.read_bytes())

    def test_archive_recovery_imports_only_pinned_resource_bytes(self):
        result = self.fixture_workspace()
        archive = self.root / "old.jar"
        with zipfile.ZipFile(archive, "w") as jar:
            jar.writestr("needed.ptr", b"expected binary")
            jar.writestr("dev/yanianz/intave/OldApi.class", b"must never be imported")
        report = workspace.recover_archived_fixtures(result, archive)
        self.assertEqual(b"expected binary", (result / "src/test/resources/needed.ptr").read_bytes())
        self.assertEqual(1, len(report["recovered"]))
        self.assertFalse(report["native_active"])
        self.assertFalse((result / "dev").exists())
        self.assertEqual([], workspace.recover_archived_fixtures(result, archive)["recovered"])

    def test_archive_recovery_does_not_import_wrong_version(self):
        result = self.fixture_workspace()
        archive = self.root / "wrong.jar"
        with zipfile.ZipFile(archive, "w") as jar:
            jar.writestr("needed.ptr", b"wrong version")
        self.assertEqual([], workspace.recover_archived_fixtures(result, archive)["recovered"])
        self.assertFalse((result / "src/test/resources/needed.ptr").exists())

    def test_archive_recovery_preserves_operator_resource_edits(self):
        result = self.fixture_workspace()
        target = result / "src/test/resources/needed.ptr"
        target.write_bytes(b"local work")
        archive = self.root / "old.jar"
        with zipfile.ZipFile(archive, "w") as jar:
            jar.writestr("needed.ptr", b"expected binary")
        with self.assertRaisesRegex(ValueError, "preserved"):
            workspace.recover_archived_fixtures(result, archive)
        self.assertEqual(b"local work", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
