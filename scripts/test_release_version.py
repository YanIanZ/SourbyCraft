import unittest
from release_version import resolve


class ReleaseVersionTest(unittest.TestCase):
    def test_development_build_may_reuse_published_identity(self):
        self.assertEqual(("v26.2-r45", 45), resolve(
            "releaseVersion=26.2\nsourbyBuild=45", "v26.2-r45", development=True))

    def test_development_build_still_requires_positive_integer(self):
        with self.assertRaises(ValueError):
            resolve("releaseVersion=26.2\nsourbyBuild=0", "", development=True)

    def test_composite_and_legacy_tags_reserve_their_integer(self):
        for tag in ("v26.2-r43", "v26.2-r43.1", "v26.2-r43-hotfix", "v26.2-43c"):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    resolve("releaseVersion=26.2\nsourbyBuild=43", tag)
                self.assertEqual(("v26.2-r44", 44),
                                 resolve("releaseVersion=26.2\nsourbyBuild=44", tag))

    def test_other_minecraft_versions_do_not_advance_counter(self):
        self.assertEqual(("v26.2-r44", 44), resolve(
            "releaseVersion=26.2\nsourbyBuild=44", "v26.1-r999\nv26.2-r43"))

    def test_refuse_noninteger_and_zero_release(self):
        for value in ("0", "43-hotfix", "44.1", "-1", "dev"):
            with self.assertRaises(ValueError):
                resolve(f"releaseVersion=26.2\nsourbyBuild={value}", "")


if __name__ == "__main__":
    unittest.main()
