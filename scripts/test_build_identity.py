import tempfile
from pathlib import Path
import unittest
import zipfile
from verify_build_identity import verify


class BuildIdentityTest(unittest.TestCase):
    def test_channel_mismatch_blocks_mislabeled_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.jar"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("META-INF/sourbycraft-build.properties",
                                 "buildNumber=45\nbuild=45c\nversion=26.2-DEV\n")
                archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: build 45c\r\n")
            self.assertEqual("26.2-DEV", verify(path, 45, "DEV")["version"])
            with self.assertRaises(ValueError):
                verify(path, 45, "REL")

    def test_mismatched_manifest_blocks_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.jar"
            for manifest_number in (43, 44):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("META-INF/sourbycraft-build.properties", "buildNumber=44\nbuild=44c\n")
                    archive.writestr("META-INF/MANIFEST.MF", f"Implementation-Version: build {manifest_number}c\r\n")
                if manifest_number == 43:
                    with self.assertRaises(ValueError):
                        verify(path, 44)
                else:
                    self.assertEqual("44", verify(path, 44)["buildNumber"])


if __name__ == "__main__":
    unittest.main()
