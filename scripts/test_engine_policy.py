from pathlib import Path
import unittest


class EnginePolicyTest(unittest.TestCase):
    def test_owned_runtime_has_no_automatic_jvm_tuning(self):
        root = Path(__file__).resolve().parents[1]
        for path in (root / "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft").rglob("*.java"):
            with self.subTest(path=path):
                source = path.read_text()
                self.assertNotIn(".setVMOption(", source)
                self.assertNotIn('cmd.add("-XX:MaxRAMPercentage=', source)
                self.assertNotIn("applyGcFlags(", source)


if __name__ == "__main__":
    unittest.main()
