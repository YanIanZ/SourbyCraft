import unittest
from release_version import resolve, resolve_hotfix


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


class HotfixReleaseVersionTest(unittest.TestCase):
    PROPS = "releaseVersion=26.2\nsourbyBuild=48.1"
    TAGS = "v26.2-r47\nv26.2-r48"

    def test_hotfix_branch_resolves_its_own_tag_and_base(self):
        self.assertEqual(("v26.2-r48.1", "48.1", "v26.2-r48"),
                         resolve_hotfix(self.PROPS, self.TAGS, "hotfix/26.2-r48.1"))

    def test_hotfix_of_an_older_build_is_allowed_after_a_newer_release(self):
        self.assertEqual(("v26.2-r48.1", "48.1", "v26.2-r48"),
                         resolve_hotfix(self.PROPS, self.TAGS + "\nv26.2-r49", "hotfix/26.2-r48.1"))

    def test_refuse_when_the_hotfix_tag_exists(self):
        with self.assertRaises(ValueError):
            resolve_hotfix(self.PROPS, self.TAGS + "\nv26.2-r48.1", "hotfix/26.2-r48.1")

    def test_refuse_when_the_base_release_was_never_published(self):
        with self.assertRaises(ValueError):
            resolve_hotfix(self.PROPS, "v26.2-r47", "hotfix/26.2-r48.1")

    def test_second_hotfix_must_exceed_the_first(self):
        tags = self.TAGS + "\nv26.2-r48.2"
        with self.assertRaises(ValueError):
            resolve_hotfix(self.PROPS, tags, "hotfix/26.2-r48.1")
        self.assertEqual(("v26.2-r48.3", "48.3", "v26.2-r48"), resolve_hotfix(
            "releaseVersion=26.2\nsourbyBuild=48.3", tags, "hotfix/26.2-r48.3"))

    def test_sourby_build_must_name_the_branch_hotfix(self):
        for props in ("releaseVersion=26.2\nsourbyBuild=48",
                      "releaseVersion=26.2\nsourbyBuild=48.2",
                      "releaseVersion=26.2\nsourbyBuild=49.1",
                      "releaseVersion=26.1\nsourbyBuild=48.1"):
            with self.subTest(props=props):
                with self.assertRaises(ValueError):
                    resolve_hotfix(props, self.TAGS, "hotfix/26.2-r48.1")

    def test_refuse_malformed_hotfix_branches(self):
        for branch in ("26.2", "hotfix/26.2-r48", "hotfix/26.2-r48.0", "hotfix/26.2-r0.1",
                       "hotfix/26.2-r48.1-extra", "hotfix/26.2-r048.1", "release/26.2-r48.1",
                       "hotfix/26.2-r48.1.2"):
            with self.subTest(branch=branch):
                with self.assertRaises(ValueError):
                    resolve_hotfix(self.PROPS, self.TAGS, branch)

    def test_development_build_validates_identity_without_tags(self):
        self.assertEqual(("v26.2-r48.1", "48.1", "v26.2-r48"), resolve_hotfix(
            self.PROPS, "", "hotfix/26.2-r48.1", development=True))
        with self.assertRaises(ValueError):
            resolve_hotfix("releaseVersion=26.2\nsourbyBuild=48", "", "hotfix/26.2-r48.1",
                           development=True)

    def test_normal_release_still_refuses_a_hotfix_number(self):
        with self.assertRaises(ValueError):
            resolve(self.PROPS, self.TAGS)
        with self.assertRaises(ValueError):
            resolve(self.PROPS, "", development=True)


if __name__ == "__main__":
    unittest.main()


class VersionLineRuleTest(unittest.TestCase):
    """Owner decision 2026-10-24: 26.3 starts at Build 100, codename Aurora Forge."""

    def test_26_2_stays_below_100(self):
        props = "releaseVersion=26.2\nsourbyBuild=99\ncodename=aurora-nexus"
        self.assertEqual(("v26.2-r99", 99), resolve(props, "v26.2-r98"))
        with self.assertRaises(ValueError):
            resolve("releaseVersion=26.2\nsourbyBuild=100\ncodename=aurora-nexus", "")

    def test_26_3_starts_at_100_and_uses_aurora_forge(self):
        props = "releaseVersion=26.3\nsourbyBuild=100\ncodename=aurora-forge"
        self.assertEqual(("v26.3-r100", 100), resolve(props, ""))
        with self.assertRaises(ValueError):
            resolve("releaseVersion=26.3\nsourbyBuild=99\ncodename=aurora-forge", "")
        with self.assertRaises(ValueError):
            resolve("releaseVersion=26.3\nsourbyBuild=100\ncodename=aurora-nexus", "")

    def test_26_3_hotfix_uses_the_same_floor(self):
        props = "releaseVersion=26.3\nsourbyBuild=100.1\ncodename=aurora-forge"
        self.assertEqual(("v26.3-r100.1", "100.1", "v26.3-r100"),
                         resolve_hotfix(props, "v26.3-r100", "hotfix/26.3-r100.1"))
        with self.assertRaises(ValueError):
            resolve_hotfix("releaseVersion=26.3\nsourbyBuild=99.1\ncodename=aurora-forge",
                           "v26.3-r99", "hotfix/26.3-r99.1")
