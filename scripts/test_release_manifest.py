import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import release_manifest as rm

ROOT = Path(__file__).resolve().parent.parent
SHA = "ab" * 32


class ReleaseManifestScriptTest(unittest.TestCase):
    def test_generate_writes_the_server_shape_bound_to_the_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "SourbyCraft-26.2-REL.jar"
            jar.write_bytes(b"jar bytes")
            yanked = Path(directory) / "yanked.txt"
            yanked.write_text("# comment\nv26.2-r46\n\nv26.2-r47  # bad build\nv26.2-r46\n")
            out = Path(directory) / rm.ASSET_NAME
            env = dict(os.environ, SOURCE_DATE_EPOCH="1792454400")
            result = subprocess.run([sys.executable, str(ROOT / "scripts/release_manifest.py"), "generate", "--tag", "v26.2-r48",
                                     "--jar", str(jar), "--rollout", "25", "--yanked-file", str(yanked), "-o", str(out)],
                                    capture_output=True, text=True, env=env)
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads(out.read_text())
            self.assertEqual({"schema": 1, "tag": "v26.2-r48", "asset": "SourbyCraft-26.2-REL.jar",
                              "sha256": rm.sha256_file(jar), "rollout_percent": 25,
                              "yanked": ["v26.2-r46", "v26.2-r47"], "policy_revision": 1792454400,
                              "issued_at": "2026-10-20T00:00:00Z", "expires_at": "2027-01-18T00:00:00Z"}, manifest)
            self.assertEqual(0, rm.main(["check", str(out), "--tag", "v26.2-r48", "--jar", str(jar)]))
            self.assertEqual(1, rm.main(["check", str(out), "--tag", "v26.2-r49", "--jar", str(jar)]))
            jar.write_bytes(b"other bytes")
            self.assertEqual(1, rm.main(["check", str(out), "--tag", "v26.2-r48", "--jar", str(jar)]))

    def test_every_rule_the_server_enforces_is_enforced_here(self):
        good = rm.build("v26.2-r48", "a.jar", SHA, 100, [], issued="2026-10-20T00:00:00Z")
        rm.validate(good)
        broken = {
            "schema 2": dict(good, schema=2),
            "schema true": dict(good, schema=True),
            "rollout 101": dict(good, rollout_percent=101),
            "rollout -1": dict(good, rollout_percent=-1),
            "rollout float": dict(good, rollout_percent=50.5),
            "rollout bool": dict(good, rollout_percent=True),
            "upper sha": dict(good, sha256=SHA.upper()),
            "short sha": dict(good, sha256="ab"),
            "blank tag": dict(good, tag=" "),
            "not a tag": dict(good, tag="latest"),
            "yanked str": dict(good, yanked="v26.2-r1"),
            "yanked int": dict(good, yanked=[1]),
            "revision 0": dict(good, policy_revision=0),
            "revision str": dict(good, policy_revision="5"),
            "revision bool": dict(good, policy_revision=True),
            "no revision": {k: v for k, v in good.items() if k != "policy_revision"},
            "no expiry": {k: v for k, v in good.items() if k != "expires_at"},
            "bad expiry": dict(good, expires_at="tomorrow"),
            "expiry before issue": dict(good, expires_at="2026-10-19T00:00:00Z"),
        }
        for name, manifest in broken.items():
            with self.subTest(name), self.assertRaises(rm.ManifestError):
                rm.validate(manifest)

    def test_revision_and_expiry_defaults_and_overrides(self):
        m = rm.build("v26.2-r48", "a.jar", SHA, 100, [], issued="2026-10-20T00:00:00Z", revision=7, valid_days=1)
        self.assertEqual(7, m["policy_revision"])
        self.assertEqual("2026-10-21T00:00:00Z", m["expires_at"])
        from datetime import datetime, timezone
        rm.validate(m, now=datetime(2026, 10, 20, 12, tzinfo=timezone.utc))
        with self.assertRaisesRegex(rm.ManifestError, "expired"):
            rm.validate(m, now=datetime(2026, 10, 22, tzinfo=timezone.utc))
        with self.assertRaises(rm.ManifestError):
            rm.build("v26.2-r48", "a.jar", SHA, 100, [], issued="2026-10-20T00:00:00Z", valid_days=0)

    def test_duplicate_member_names_are_refused_including_escaped_spellings(self):
        good = json.dumps(rm.build("v26.2-r48", "a.jar", SHA, 100, [], issued="2026-10-20T00:00:00Z"))
        self.assertEqual(100, rm.loads_strict(good.encode())["rollout_percent"])
        fixtures = {
            "plain": good[:-1] + ', "rollout_percent": 0}',
            "escaped": good[:-1] + ', "rollout\\u005fpercent": 0}',
            "nested": '{"a": {"x": 1, "x": 2}}',
            "yanked twice": good[:-1] + ', "yanked": ["v26.2-r47"]}',
        }
        for name, text in fixtures.items():
            with self.subTest(name), self.assertRaisesRegex(rm.ManifestError, "duplicate"):
                rm.loads_strict(text.encode())
        with self.assertRaises(rm.ManifestError):
            rm.loads_strict(b'{"a": NaN}')

    def test_check_refuses_a_duplicate_key_file(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "a.jar"
            jar.write_bytes(b"x")
            m = rm.build("v26.2-r48", "a.jar", rm.sha256_file(jar), 0, [])
            text = json.dumps(m)[:-1] + ', "rollout_percent": 100}'
            out = Path(directory) / "m.json"
            out.write_text(text)
            self.assertEqual(1, rm.main(["check", str(out), "--tag", "v26.2-r48", "--jar", str(jar)]))

    def test_yank_file_rejects_non_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "y.txt"
            path.write_text("v26.2-r48\nr49\n")
            with self.assertRaisesRegex(rm.ManifestError, "y.txt:2"):
                rm.read_yanked(path)

    def test_the_checked_in_yank_list_and_rollout_are_valid(self):
        tags = rm.read_yanked(ROOT / "build-data/yanked-releases.txt")
        self.assertIsInstance(tags, list)
        rollout = rm.default_rollout()
        self.assertTrue(0 <= rollout <= 100, rollout)

    def test_the_java_reader_and_this_writer_agree_on_names(self):
        source = (ROOT / "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/update/ReleaseManifest.java").read_text()
        self.assertIn(f'ASSET_NAME = "{rm.ASSET_NAME}"', source)
        self.assertIn(f"SCHEMA = {rm.SCHEMA};", source)
        self.assertIn(f"MAX_BYTES = {rm.MAX_BYTES // 1024} * 1024;", source)
        for key in ("schema", "tag", "asset", "sha256", "rollout_percent", "yanked", "policy_revision", "expires_at"):
            self.assertIn(f'"{key}"', source)


if __name__ == "__main__":
    unittest.main()
