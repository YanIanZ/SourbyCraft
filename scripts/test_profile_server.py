import hashlib
from pathlib import Path
import tempfile
import unittest
from profile_server import sha256


class ProfileScriptTest(unittest.TestCase):
    def test_stream_hash_matches_digest_for_multiple_blocks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.bin"
            data = b"profile-fixture" * 200000
            path.write_bytes(data)
            self.assertEqual(hashlib.sha256(data).hexdigest(), sha256(path))


if __name__ == "__main__":
    unittest.main()
