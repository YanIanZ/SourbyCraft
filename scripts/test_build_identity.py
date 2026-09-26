import tempfile
from pathlib import Path
import unittest
import zipfile
from verify_build_identity import verify


def jar(directory, properties, manifest):
    path = Path(directory) / "server.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/sourbycraft-build.properties", properties)
        archive.writestr("META-INF/MANIFEST.MF", manifest)
    return path


class BuildIdentityTest(unittest.TestCase):
    def test_channel_mismatch_blocks_mislabeled_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = jar(directory, "buildNumber=47\nbuild=47\nversion=26.2-DEV\n",
                       "Implementation-Version: Build 47\r\n")
            self.assertEqual("26.2-DEV", verify(path, 47, "DEV")["version"])
            with self.assertRaises(ValueError):
                verify(path, 47, "REL")

    def test_mismatched_manifest_blocks_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            for manifest_number in (46, 47):
                path = jar(directory, "buildNumber=47\nbuild=47\n",
                           f"Implementation-Version: Build {manifest_number}\r\n")
                if manifest_number == 46:
                    with self.assertRaises(ValueError):
                        verify(path, 47)
                else:
                    self.assertEqual("47", verify(path, 47)["buildNumber"])

    def test_an_upstream_platform_letter_is_rejected(self):
        # Build 47+ public identity carries no Canvas/Folia/Paper suffix (AGENTS.md).
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                verify(jar(directory, "buildNumber=47\nbuild=47c\n", "Implementation-Version: Build 47\r\n"), 47)
            with self.assertRaises(ValueError):
                verify(jar(directory, "buildNumber=47\nbuild=47\n", "Implementation-Version: build 47c\r\n"), 47)


if __name__ == "__main__":
    unittest.main()
