"""Unit tests for updater_rollback_fixture.py: the pure parts and the fake release server. No JVM."""
import hashlib
import contextlib
import io
import json
from pathlib import Path
import shutil
import socket
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import urllib.error
import urllib.request
import zipfile

import release_manifest
import release_signing_key
import updater_rollback_fixture as fx

REPO = Path(__file__).resolve().parents[1]
JAVA = REPO / "sourbycraft-server" / "src" / "main" / "java" / "dev" / "iyanz" / "sourbycraft"


def openssl3():
    try:
        return release_signing_key.find_openssl()
    except release_signing_key.SigningKeyError:
        return None


class TempCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="sc-fixture-test-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)

    def slim_like(self, name="slim.jar", manifest=b"Manifest-Version: 1.0\r\nMain-Class: dev.iyanz.sourbyclip.Main\r\n"
                                                    b"Clip-Version: 3.1.2\r\nSourbyClip-Protocol: 1\r\n\r\n"):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("META-INF/MANIFEST.MF", manifest)
            zf.writestr("META-INF/sourbyclip.properties", "version=3.1.2\nprotocol=1\n")
            zf.writestr("version.json", "{}")
            zf.writestr("libs/a.bin", b"\x00" * 2048, compress_type=zipfile.ZIP_STORED)
        return path


class JarTest(TempCase):
    def test_the_marker_is_the_only_difference_between_variants(self):
        source = self.slim_like()
        good = fx.repack_jar(source, self.root / "good.jar", fx.marker_text("new-good"))
        broken = fx.repack_jar(source, self.root / "broken.jar", fx.marker_text("new-broken", fail=True, exit_code=3))
        with zipfile.ZipFile(source) as a, zipfile.ZipFile(good) as g, zipfile.ZipFile(broken) as b:
            self.assertEqual(g.namelist(), a.namelist() + [fx.MARKER_ENTRY])
            for name in a.namelist():
                self.assertEqual(a.read(name), g.read(name), name)
                self.assertEqual(a.read(name), b.read(name), name)
            self.assertEqual(g.read(fx.MARKER_ENTRY).decode(), "role=new-good\nboot=ok\n")
            self.assertEqual(b.read(fx.MARKER_ENTRY).decode(), "role=new-broken\nboot=fail\nexit-code=3\n")
            self.assertEqual(a.getinfo("libs/a.bin").compress_type, g.getinfo("libs/a.bin").compress_type)
        self.assertNotEqual(fx.sha256_file(good), fx.sha256_file(broken))

    def test_repacking_twice_replaces_the_marker_instead_of_duplicating_it(self):
        once = fx.repack_jar(self.slim_like(), self.root / "once.jar", fx.marker_text("original"))
        twice = fx.repack_jar(once, self.root / "twice.jar", fx.marker_text("new-good"))
        with zipfile.ZipFile(twice) as z:
            self.assertEqual(z.namelist().count(fx.MARKER_ENTRY), 1)
            self.assertIn(b"new-good", z.read(fx.MARKER_ENTRY))

    def test_main_class_replacement_keeps_the_launcher_metadata(self):
        out = fx.repack_jar(self.slim_like(), self.root / "dead.jar", fx.marker_text("new-dead"),
                            main_class="FixtureDeadMain", extra_entries={"FixtureDeadMain.class": b"\xca\xfe"})
        with zipfile.ZipFile(out) as z:
            manifest = z.read("META-INF/MANIFEST.MF").decode()
            self.assertIn("Main-Class: FixtureDeadMain\r\n", manifest)
            self.assertNotIn("sourbyclip.Main", manifest)
            self.assertIn("Clip-Version: 3.1.2", manifest)       # LauncherCompatibility still passes
            self.assertIn("SourbyClip-Protocol: 1", manifest)
            self.assertTrue(manifest.endswith("\r\n\r\n"), repr(manifest))
            self.assertEqual(z.read("FixtureDeadMain.class"), b"\xca\xfe")
            self.assertIn("protocol=1", z.read("META-INF/sourbyclip.properties").decode())

    def test_a_manifest_without_main_class_is_refused(self):
        with self.assertRaises(fx.FixtureError):
            fx.replace_main_class(b"Manifest-Version: 1.0\r\n\r\n", "X")

    def test_a_folded_main_class_line_is_replaced_whole(self):
        manifest = b"Manifest-Version: 1.0\r\nMain-Class: very.long.\r\n name.Main\r\nOther: 1\r\n\r\n"
        out = fx.replace_main_class(manifest, "X").decode()
        self.assertEqual(out, "Manifest-Version: 1.0\r\nMain-Class: X\r\nOther: 1\r\n\r\n")

    def test_the_dead_main_source_exits_with_the_documented_code(self):
        self.assertIn(f"System.exit({fx.EXIT_DEAD})", fx.DEAD_MAIN_SOURCE)
        self.assertIn("no SourbyCraft code has run", fx.DEAD_MAIN_SOURCE)


class ReleaseTest(TempCase):
    def test_the_listing_has_what_the_updater_parses(self):
        jar = b"jar bytes"
        listing = fx.release_listing("http://127.0.0.1:9", "v26.2-r9001",
                                     {"A.jar": jar, "A.jar.sig": b"s" * 64, "m.json": b"{}"})
        self.assertEqual(len(listing), 1)
        release = listing[0]
        self.assertEqual((release["tag_name"], release["draft"], release["prerelease"]), ("v26.2-r9001", False, False))
        by_name = {a["name"]: a for a in release["assets"]}
        self.assertEqual(by_name["A.jar"]["digest"], "sha256:" + hashlib.sha256(jar).hexdigest())
        self.assertEqual(by_name["A.jar"]["size"], len(jar))
        self.assertNotIn("digest", by_name["A.jar.sig"])
        self.assertEqual(by_name["m.json"]["browser_download_url"], "http://127.0.0.1:9/assets/v26.2-r9001/m.json")
        self.assertTrue(release["tag_name"].startswith("v26.2-r") and "-" not in release["tag_name"].split("-r")[1],
                        "tag must stay on the REL channel and above any real build number")

    @unittest.skipUnless(openssl3(), "OpenSSL 3 required")
    def test_assets_carry_a_manifest_bound_to_the_jar_and_verifiable_signatures(self):
        signer = fx.Signer(self.root / "keys", openssl3())
        self.assertFalse(release_signing_key.inside(signer.key, REPO))
        jar = b"the release jar"
        assets = fx.release_assets(signer, fx.FAKE_TAG, jar)
        self.assertEqual(sorted(assets), sorted([fx.ASSET_NAME, fx.ASSET_NAME + ".sig",
                                                 release_manifest.ASSET_NAME, release_manifest.ASSET_NAME + ".sig"]))
        manifest = json.loads(assets[release_manifest.ASSET_NAME])
        self.assertEqual((manifest["tag"], manifest["asset"], manifest["sha256"], manifest["rollout_percent"], manifest["yanked"]),
                         (fx.FAKE_TAG, fx.ASSET_NAME, hashlib.sha256(jar).hexdigest(), 100, []))
        release_manifest.validate(manifest, tag=fx.FAKE_TAG, asset=fx.ASSET_NAME, sha256=hashlib.sha256(jar).hexdigest())
        for data, sig in ((jar, assets[fx.ASSET_NAME + ".sig"]), (assets[release_manifest.ASSET_NAME],
                                                                    assets[release_manifest.ASSET_NAME + ".sig"])):
            self.assertEqual(len(sig), 64)
            blob, sigfile = self.root / "blob", self.root / "sig"
            blob.write_bytes(data)
            sigfile.write_bytes(sig)
            release_signing_key.run(signer.openssl, "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(signer.pub),
                                    "-in", str(blob), "-sigfile", str(sigfile))
            blob.write_bytes(data + b"x")                      # a tampered copy must not verify
            with self.assertRaises(release_signing_key.SigningKeyError):
                release_signing_key.run(signer.openssl, "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey",
                                        str(signer.pub), "-in", str(blob), "-sigfile", str(sigfile))
        self.assertTrue(signer.key.exists())
        signer.destroy()
        self.assertFalse(signer.key.exists(), "the private key is deleted when the run ends")
        self.assertTrue(signer.pub.exists())
        signer.destroy()                                       # idempotent

    @unittest.skipUnless(openssl3(), "OpenSSL 3 required")
    def test_a_key_directory_inside_the_repository_is_refused(self):
        with self.assertRaises(release_signing_key.SigningKeyError):
            fx.Signer(REPO / "build" / "never-created-keys", openssl3())
        self.assertFalse((REPO / "build" / "never-created-keys").exists())


class FakeServerTest(unittest.TestCase):
    def setUp(self):
        self.server = fx.FakeReleaseServer().start()
        self.addCleanup(self.server.stop)

    def get(self, path):
        return urllib.request.urlopen(self.server.base_url + path, timeout=5)

    def test_it_listens_on_loopback_only_and_serves_listing_and_assets(self):
        self.assertTrue(self.server.base_url.startswith("http://127.0.0.1:"))
        self.assertEqual(self.server._server.server_address[0], "127.0.0.1")
        self.server.publish("v26.2-r9001", {"A.jar": b"jar", "A.jar.sig": b"sig"})
        listing = json.load(self.get(f"/repos/{fx.REPO_SLUG}/releases?per_page=100"))
        self.assertEqual(listing[0]["tag_name"], "v26.2-r9001")
        url = next(a["browser_download_url"] for a in listing[0]["assets"] if a["name"] == "A.jar")
        self.assertTrue(url.startswith(self.server.base_url))
        self.assertEqual(urllib.request.urlopen(url, timeout=5).read(), b"jar")
        self.assertEqual(self.server.request_paths(), [f"/repos/{fx.REPO_SLUG}/releases?per_page=100",
                                                       "/assets/v26.2-r9001/A.jar"])

    def test_unknown_paths_repos_and_tags_are_404(self):
        for path in ("/", "/repos/other/repo/releases", f"/repos/{fx.REPO_SLUG}/releases"):
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.get(path)
            self.assertEqual(error.exception.code, 404, path)       # nothing published yet
        self.server.publish("v26.2-r9001", {"A.jar": b"jar"})
        for path in ("/assets/v26.2-r9002/A.jar", "/assets/v26.2-r9001/B.jar", "/assets/v26.2-r9001/../A.jar"):
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.get(path)
            self.assertEqual(error.exception.code, 404, path)

    def test_the_port_is_not_reachable_from_a_non_loopback_address(self):
        port = self.server.port
        addresses = {info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
        for address in addresses - {"127.0.0.1"}:
            with socket.socket() as probe:
                probe.settimeout(1)
                self.assertNotEqual(probe.connect_ex((address, port)), 0, address)


class LauncherVersionTest(TempCase):
    def test_runtime_pin_gates_launcher_restore(self):
        properties = self.root / "gradle.properties"
        for version, expected in (("3.1.2", False), ("3.1.3", False), ("3.1.4", True),
                                  ("3.1.10", True), ("4.0.0", True)):
            properties.write_text(f"# test pin\nclipVersion={version}\n")
            self.assertEqual(fx.launcher_restore_expected(properties), expected, version)
        properties.write_text("clipVersion=unknown\n")
        with self.assertRaises(fx.FixtureError):
            fx.launcher_restore_expected(properties)

    def test_skipped_expectation_is_explicit_and_does_not_fail_run(self):
        checks = fx.Checks()
        checks.skip("launcher restore requires SourbyClip >= 3.1.4", "pinned 3.1.3")
        self.assertTrue(checks.ok)
        self.assertTrue(checks.items[0]["skipped"])


class DeadLauncherScenarioTest(TempCase):
    def run_scenario(self, modern, restore=True):
        original = self.root / "original.jar"
        original.write_bytes(b"original")
        dead = self.root / "dead.jar"
        dead.write_bytes(b"dead")
        launch = self.root / "server.jar"
        launch.write_bytes(b"dead")
        prev = self.root / "server.jar.prev"
        prev.write_bytes(b"original")
        fixture = object.__new__(fx.Fixture)
        fixture.checks = fx.Checks()
        fixture.jars = {"original": original, "new": dead}
        fixture.name = "test"
        fixture.workdir = lambda: self.root
        fixture.apply_phase = lambda: (launch, prev)
        fixture.server = SimpleNamespace(request_paths=lambda: [])
        fixture.harness = SimpleNamespace(
            stop=lambda *a, **kw: {"exit_code": 0, "lock_released": True},
            command=lambda *a, **kw: (True, "Updater is HELD after a rollback"))
        boot = 1
        records = {}

        def restart():
            nonlocal boot
            boot += 1
            state = {"phase": "APPLIED"}
            log = "SOURBY_UPDATER_FIXTURE_DEAD_LAUNCHER role=new-dead"
            run = {"status": "exited", "exit_code": 4}
            if modern and boot == 3 and restore:
                launch.write_bytes(b"original")
                state = {"phase": "ROLLED_BACK", "tag": fx.FAKE_TAG,
                         "prev_sha256": hashlib.sha256(b"original").hexdigest(),
                         "note": "launcher: the boot after applying " + fx.FAKE_TAG
                                 + " did not reach the updater stage or ready; server.jar.prev copied back over server.jar"}
                log = "UPDATE ROLLED BACK by launcher:"
                run["exit_code"] = 71
            elif boot == 4:
                run = {"status": "ready", "done_line": "Done ("}
                log = ("SOURBY_UPDATER_FIXTURE jar=server.jar role=original disk-sha256="
                       + hashlib.sha256(launch.read_bytes()).hexdigest() + " boot=ok\n"
                       + "AUTO-UPDATER HELD: update " + fx.FAKE_TAG + " was rolled back: launcher:")
            (self.root / "server.log").write_text(log)
            records[id(run)] = {"state": state, "log": log, "launch_sha256": fx.sha256_file(launch)}
            return run

        fixture.restart = restart
        fixture.record_boot = lambda run: records.get(id(run), {})
        with patch.object(fx, "launcher_restore_expected", return_value=modern), \
             patch.object(fx, "pinned_clip_version", return_value="3.1.4" if modern else "3.1.3"):
            with contextlib.redirect_stdout(io.StringIO()):
                fixture.run_dead_launcher()
        return fixture.checks

    def test_old_pin_skips_launcher_expectation_and_recovers_manually(self):
        checks = self.run_scenario(False)
        self.assertTrue(checks.ok, checks.items)
        self.assertTrue(any(item.get("skipped") for item in checks.items))

    def test_new_pin_requires_launcher_restore_and_held_recovery(self):
        checks = self.run_scenario(True)
        self.assertTrue(checks.ok, checks.items)
        self.assertFalse(any(item.get("skipped") for item in checks.items))

    def test_new_pin_fails_when_launcher_does_not_restore(self):
        self.assertFalse(self.run_scenario(True, restore=False).ok)


class EvidenceTest(unittest.TestCase):
    def test_properties_files_written_by_java_are_read_back(self):
        text = ("#SourbyCraft auto-updater rollback guard\n#Thu Oct 22 02:00:00 UTC 2026\n"
                "phase=ROLLED_BACK\nlaunch=/Volumes/Ext/x/SourbyCraft-slim.jar\nprev=C\\:\\\\dir\\\\a.jar.prev\n"
                "note=the boot after applying it did not reach ready; a.jar.prev was copied back\ntag=v26.2-r9001\n")
        state = fx.parse_properties(text)
        self.assertEqual(state["phase"], "ROLLED_BACK")
        self.assertEqual(state["prev"], "C:\\dir\\a.jar.prev")
        self.assertEqual(state["tag"], "v26.2-r9001")
        self.assertNotIn("#SourbyCraft auto-updater rollback guard", state)

    def test_log_helpers(self):
        log = fx.clean_log("\x1b[32m[02:00:00 INFO]: [SourbyCraft] auto-updater: applying v26.2-r9001 \u2014 restart in 5s (mode=exit).\x1b[0m\nnext\n")
        self.assertTrue(fx.log_has(log, fx.LINES["applying"]))
        self.assertIn("restart in 5s", fx.log_line(log, fx.LINES["applying"]))
        self.assertIsNone(fx.log_line(log, "never-logged"))
        probe = f"[x] SOURBY_UPDATER_FIXTURE jar=a.jar role=new-good disk-sha256={'a' * 64} boot=ok"
        self.assertEqual(fx.fixture_jar_line(probe), ("new-good", "a" * 64, "ok"))
        self.assertIsNone(fx.fixture_jar_line("no probe line"))

    def test_checks_collect_and_decide(self):
        checks = fx.Checks()
        self.assertTrue(checks.ok)
        checks.add("one", True)
        self.assertTrue(checks.ok)
        checks.add("two", False, "why")
        self.assertFalse(checks.ok)
        self.assertEqual([c["check"] for c in checks.items], ["one", "two"])


class ResourceRetryTest(unittest.TestCase):
    def test_a_busy_machine_is_waited_out_and_anything_else_is_raised_at_once(self):
        import agent_testserver as ats
        sleeps, lines = [], []
        answers = [ats.HarnessError("only 2.98 GiB RAM available (vm_stat); refusing below 3.0 GiB"),
                   ats.HarnessError("3 Gradle builds are running; refusing a server above 2"),
                   ats.LockBusy({"name": "other"}),
                   "booted"]

        def call():
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer

        self.assertEqual(fx.with_resource_retry(call, attempts=5, delay=7, sleep=sleeps.append,
                                                log=lambda text, flush=False: lines.append(text)), "booted")
        self.assertEqual(sleeps, [7, 7, 7])
        self.assertEqual(len(lines), 3)
        with self.assertRaises(ats.HarnessError):
            fx.with_resource_retry(lambda: (_ for _ in ()).throw(ats.HarnessError("port 1 is not free")),
                                   attempts=5, delay=7, sleep=sleeps.append)
        self.assertEqual(sleeps, [7, 7, 7], "a different refusal is not retried")
        with self.assertRaisesRegex(fx.FixtureError, "gave up after 2 tries"):
            fx.with_resource_retry(lambda: (_ for _ in ()).throw(ats.HarnessError("1 Gradle builds are running")),
                                   attempts=2, delay=1, sleep=sleeps.append, log=lambda *a, **k: None)


class LogContractTest(unittest.TestCase):
    """The fixture greps log lines the Java side writes; this pins each pattern to the message text
    in the source, so a reworded log line breaks here instead of in a long real boot."""

    def source(self, relative):
        return (JAVA / relative).read_text()

    def test_every_pattern_still_has_its_message_in_the_java_source(self):
        settings = self.source("update/AutoUpdateSettings.java")
        applier = self.source("update/UpdateApplier.java")
        updater = self.source("update/SourbyUpdater.java")
        verifier = self.source("update/ReleaseSignatureVerifier.java")
        expectations = {
            "signatures_enforced": (verifier, "release signatures ENFORCED (TEST key from -D"),
            "override_warning": (updater, "TEST OVERRIDE -D"),
            "staged": (updater, '"auto-updater: staged "'),
            "applying": (applier, '"auto-updater: applying " + tag'),
            "swapped": (applier, '"auto-updater: swapped "'),
            "restarting": (applier, '"auto-updater: restarting now (plan="'),
            "first_boot": (settings, "auto-updater: first boot after applying "),
            "confirmed": (settings, "reached ready; update confirmed"),
            "rolled_back": (settings, "UPDATE ROLLED BACK: the boot after applying "),
            "held": (settings, "AUTO-UPDATER HELD: "),
            "already_applied": (updater, " already applied; nothing to do."),
            "fixture_jar": (self.probe(), "SOURBY_UPDATER_FIXTURE jar="),
            "fixture_fail": (self.probe(), "SOURBY_UPDATER_FIXTURE_FAIL role="),
        }
        for key, (text, fragment) in expectations.items():
            self.assertIn(fragment, text, key)
            self.assertIn(key, fx.LINES)

    def probe(self):
        return (REPO / "test-plugin" / "src" / "main" / "java" / "dev" / "iyanz" / "sourbycraft"
                / "testplugin" / "UpdaterFixtureProbe.java").read_text()

    def test_the_held_line_uses_the_guard_hold_reason_wording(self):
        guard = self.source("update/UpdateRollbackGuard.java")
        self.assertIn('"update " + st.tag() + " was rolled back: "', guard)

    def test_the_jvm_properties_match_the_java_constants(self):
        overrides = self.source("update/UpdateOverrides.java")
        import agent_testserver as ats
        self.assertIn(f'"{ats.UPDATER_API_PROPERTY}"', overrides)
        self.assertIn(f'"{ats.UPDATER_KEY_PROPERTY}"', overrides)

    def test_the_probe_property_and_marker_match_the_java_probe(self):
        text = self.probe()
        self.assertIn(f'"{fx.MARKER_ENTRY}"', text)
        self.assertIn('"sourby.testplugin.updater-fixture"', text)
        self.assertIn(f"DEFAULT_EXIT_CODE = {fx.EXIT_BROKEN}", text)


if __name__ == "__main__":
    unittest.main()
