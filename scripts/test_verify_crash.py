import unittest

import verify_crash as crash


class AwfBacklogTest(unittest.TestCase):
    PERF_AWF = """  AWF settings: INCREMENTAL, commit every 5s, 1024 resident chunks per storage
  minecraft/overworld/region resident / dirty / evicted: 529 / 12 / 0
  minecraft/overworld/region pending commits / oldest: 1 / 4200 ms
  minecraft/overworld/entities resident / dirty / evicted: 40 / 0 / 0
  minecraft/overworld/entities pending commits / oldest: 0 / 0 ms
"""

    def test_sums_dirty_chunks_and_pending_commits_over_every_storage(self):
        self.assertEqual(crash.parse_awf_backlog(self.PERF_AWF), (12, 1))

    def test_a_drained_store_reads_zero_zero(self):
        drained = self.PERF_AWF.replace("529 / 12 / 0", "529 / 0 / 0").replace("1 / 4200 ms", "0 / 0 ms")
        self.assertEqual(crash.parse_awf_backlog(drained), (0, 0))

    def test_no_awf_world_is_unreadable_rather_than_drained(self):
        self.assertIsNone(crash.parse_awf_backlog("  Aurora World Fabric: no world is stored in AWF"))


class CorruptionTest(unittest.TestCase):
    def test_reports_the_line_carrying_a_corruption_marker(self):
        log = "[12:00:00 INFO]: Done (3.2s)!\n[12:00:01 ERROR]: Failed to read chunk [3, 4]\n"
        self.assertEqual(crash.corruption_in(log), ["[12:00:01 ERROR]: Failed to read chunk [3, 4]"])

    def test_a_clean_boot_has_nothing(self):
        self.assertEqual(crash.corruption_in("[12:00:00 INFO]: Done (3.2s)!\n"), [])


class KnownLimitationTest(unittest.TestCase):
    def test_a_known_failure_is_expected_and_anything_else_still_fails(self):
        results = [{"check": "boot 2: game time survived and advanced", "ok": False},
                   {"check": "boot 2: blocks survived the restart", "ok": False},
                   {"check": "boot 2: entities survived the restart", "ok": True}]
        failed, xfail, xpass = crash.classify(results)
        self.assertEqual([r["check"] for r in failed], ["boot 2: blocks survived the restart"])
        self.assertEqual([r["check"] for r in xfail], ["boot 2: game time survived and advanced"])
        self.assertEqual(xpass, [])

    def test_a_fixed_limitation_is_flagged_for_removal(self):
        _, xfail, xpass = crash.classify([{"check": "boot 3: game time survived and advanced", "ok": True}])
        self.assertEqual((len(xfail), len(xpass)), (0, 1))


if __name__ == "__main__":
    unittest.main()
