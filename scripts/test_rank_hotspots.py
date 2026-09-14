from pathlib import Path
import unittest

import rank_hotspots as ranker


def frame(owner, method):
    return {"method": {"type": {"name": owner}, "name": method}}


def sample(frames, thread="Region Thread #0", state="STATE_RUNNABLE"):
    return {"sampledThread": {"javaName": thread}, "state": state,
            "stackTrace": {"frames": [frame(*pair) for pair in frames]}}


def allocation(class_name, weight, frames=(), thread="Region Thread #0"):
    return {"objectClass": {"name": class_name}, "weight": weight,
            "eventThread": {"javaName": thread},
            "stackTrace": {"frames": [frame(*pair) for pair in frames]}}


class FrameTest(unittest.TestCase):
    def test_normalizes_internal_class_names_to_dotted_form(self):
        self.assertEqual(ranker._dotted("net/minecraft/world/entity/Entity"),
                         "net.minecraft.world.entity.Entity")

    def test_missing_names_do_not_raise(self):
        self.assertEqual(ranker._dotted(None), "unknown")
        self.assertEqual(ranker._frames({}), [])
        self.assertEqual(ranker._frames({"stackTrace": {"frames": []}}), [])

    def test_reads_owner_and_method_in_order(self):
        self.assertEqual(ranker._frames(sample([("a/B", "leaf"), ("c/D", "caller")])),
                         ["a.B.leaf", "c.D.caller"])


class CpuHotspotTest(unittest.TestCase):
    def test_self_counts_only_the_leaf_frame(self):
        result = ranker.cpu_hotspots([sample([("a/B", "leaf"), ("c/D", "caller")]),
                                      sample([("a/B", "leaf"), ("e/F", "other")])], 10)
        self.assertEqual(result["self"][0]["name"], "a.B.leaf")
        self.assertEqual(result["self"][0]["samples"], 2)
        self.assertEqual([row["name"] for row in result["self"]], ["a.B.leaf"])

    def test_inclusive_counts_a_method_anywhere_on_the_stack(self):
        result = ranker.cpu_hotspots([sample([("a/B", "leaf"), ("c/D", "caller")]),
                                      sample([("e/F", "other"), ("c/D", "caller")])], 10)
        inclusive = {row["name"]: row["samples"] for row in result["inclusive"]}
        self.assertEqual(inclusive["c.D.caller"], 2)
        self.assertEqual(inclusive["a.B.leaf"], 1)

    def test_a_method_appearing_twice_in_one_stack_counts_once_inclusively(self):
        result = ranker.cpu_hotspots([sample([("a/B", "rec"), ("a/B", "rec")])], 10)
        inclusive = {row["name"]: row["samples"] for row in result["inclusive"]}
        self.assertEqual(inclusive["a.B.rec"], 1)

    def test_shares_are_fractions_of_total_samples(self):
        result = ranker.cpu_hotspots([sample([("a/B", "x")]), sample([("c/D", "y")])], 10)
        self.assertAlmostEqual(result["self"][0]["share"], 0.5)
        self.assertEqual(result["total_samples"], 2)

    def test_separates_profiler_self_time_from_server_work(self):
        result = ranker.cpu_hotspots([
            sample([("me/lucko/spark/Sampler", "run")]),
            sample([("dev/iyanz/sourbycraft/perf/Collector", "run")]),
            sample([("net/minecraft/server/MinecraftServer", "tick")]),
            sample([("net/minecraft/server/MinecraftServer", "tick")])], 10)
        self.assertAlmostEqual(result["observer_self_share"], 0.5)

    def test_groups_by_thread_and_state(self):
        result = ranker.cpu_hotspots([sample([("a/B", "x")], thread="T1"),
                                      sample([("a/B", "x")], thread="T1"),
                                      sample([("a/B", "x")], thread="T2", state="STATE_SLEEPING")], 10)
        self.assertEqual(result["threads"][0], {"name": "T1", "samples": 2, "share": 2 / 3})
        self.assertEqual({row["name"] for row in result["states"]},
                         {"STATE_RUNNABLE", "STATE_SLEEPING"})

    def test_honours_the_limit(self):
        samples = [sample([(f"p/C{i}", "m")]) for i in range(10)]
        self.assertEqual(len(ranker.cpu_hotspots(samples, 3)["self"]), 3)

    def test_a_sample_with_no_frames_still_counts_toward_the_thread(self):
        result = ranker.cpu_hotspots([{"sampledThread": {"javaName": "T"}, "state": "X",
                                       "stackTrace": {"frames": []}}], 10)
        self.assertEqual(result["total_samples"], 1)
        self.assertEqual(result["self"], [])
        self.assertEqual(result["threads"][0]["name"], "T")

    def test_unavailable_without_samples(self):
        result = ranker.cpu_hotspots([], 10)
        self.assertFalse(result["available"])
        self.assertIn("jdk.ExecutionSample", result["reason"])


class AllocationHotspotTest(unittest.TestCase):
    def test_ranks_types_by_extrapolated_weight(self):
        result = ranker.allocation_hotspots([allocation("java/util/ArrayList", 900),
                                             allocation("net/minecraft/world/phys/AABB", 100)], 10)
        self.assertEqual(result["by_class"][0]["name"], "java.util.ArrayList")
        self.assertAlmostEqual(result["by_class"][0]["share"], 0.9)
        self.assertEqual(result["estimated_bytes"], 1000)

    def test_attributes_bytes_to_the_leaf_allocation_site(self):
        result = ranker.allocation_hotspots(
            [allocation("java/util/ArrayList", 500, [("n/m/Entity", "collide")]),
             allocation("java/util/ArrayList", 500, [("n/m/Entity", "collide")])], 10)
        self.assertEqual(result["by_site"][0]["name"], "n.m.Entity.collide")
        self.assertEqual(result["by_site"][0]["bytes"], 1000)

    def test_groups_by_thread(self):
        result = ranker.allocation_hotspots([allocation("A", 10, thread="T1"),
                                             allocation("A", 30, thread="T2")], 10)
        self.assertEqual(result["by_thread"][0]["name"], "T2")

    def test_a_sample_without_a_stack_is_still_counted_by_type(self):
        result = ranker.allocation_hotspots([allocation("A", 40)], 10)
        self.assertEqual(result["by_class"][0]["bytes"], 40)
        self.assertEqual(result["by_site"], [])

    def test_zero_total_weight_does_not_divide_by_zero(self):
        result = ranker.allocation_hotspots([allocation("A", 0)], 10)
        self.assertEqual(result["by_class"][0]["share"], 0.0)

    def test_unavailable_without_samples(self):
        self.assertFalse(ranker.allocation_hotspots([], 10)["available"])


class OverstatementTest(unittest.TestCase):
    """The sampled allocation total, checked against a counter-based one.

    On a real recording these disagreed 6.5-fold and the site at the top of the table
    turned out not to be a meaningful allocator at all: JFR's allocation sampler favours
    large objects, so a site allocating big arrays dominates out of proportion.
    """

    def test_reports_the_factor_when_a_measured_total_is_supplied(self):
        result = ranker.allocation_hotspots([allocation("A", 1000)], 5, measured_bytes=250)
        self.assertEqual(result["overstatement_factor"], 4.0)
        self.assertEqual(result["measured_bytes"], 250)

    def test_absent_when_no_measured_total_is_available(self):
        result = ranker.allocation_hotspots([allocation("A", 1000)], 5)
        self.assertNotIn("overstatement_factor", result)

    def test_a_large_divergence_is_called_out_prominently(self):
        report = ranker.render(Path("p.jfr"), ranker.cpu_hotspots([], 5),
                               ranker.allocation_hotspots([allocation("A", 1000)], 5, 100), None)
        self.assertIn("10.0x the allocation the counters actually measured", report)
        self.assertIn("hint", report)

    def test_agreement_is_noted_without_alarm(self):
        report = ranker.render(Path("p.jfr"), ranker.cpu_hotspots([], 5),
                               ranker.allocation_hotspots([allocation("A", 1000)], 5, 900), None)
        self.assertIn("Cross-checked against the counters", report)
        self.assertNotIn("out of proportion", report)


class TickBudgetTest(unittest.TestCase):
    """A CPU ranking describes where time went, not whether any of it was a problem."""

    @staticmethod
    def tick(mspt):
        return {"available": True, "mspt": {"mean": mspt}}

    def test_computes_the_fraction_of_budget_used(self):
        note = ranker.tick_budget_note(self.tick(25.0), 20.0)
        self.assertAlmostEqual(note["fraction_used"], 0.5)
        self.assertAlmostEqual(note["budget_ms"], 50.0)

    def test_a_higher_target_tps_shrinks_the_budget(self):
        note = ranker.tick_budget_note(self.tick(25.0), 40.0)
        self.assertAlmostEqual(note["budget_ms"], 25.0)
        self.assertAlmostEqual(note["fraction_used"], 1.0)

    def test_defaults_to_twenty_tps_when_the_target_is_missing(self):
        self.assertAlmostEqual(ranker.tick_budget_note(self.tick(5.0), None)["budget_ms"], 50.0)

    def test_absent_when_tick_metrics_are_unavailable(self):
        self.assertIsNone(ranker.tick_budget_note({"available": False}, 20.0))
        self.assertIsNone(ranker.tick_budget_note(None, 20.0))

    def test_an_idle_run_is_called_out_before_the_rankings(self):
        report = ranker.render(Path("p.jfr"), ranker.cpu_hotspots([], 5),
                               ranker.allocation_hotspots([], 5), None,
                               ranker.tick_budget_note(self.tick(0.53), 20.0))
        self.assertIn("1.1% of its tick budget", report)
        self.assertIn("no bottleneck here to find", report)
        self.assertLess(report.index("tick budget"), report.index("## CPU"))

    def test_a_loaded_run_is_not_second_guessed(self):
        report = ranker.render(Path("p.jfr"), ranker.cpu_hotspots([], 5),
                               ranker.allocation_hotspots([], 5), None,
                               ranker.tick_budget_note(self.tick(30.0), 20.0))
        self.assertNotIn("no bottleneck here to find", report)


class RenderTest(unittest.TestCase):
    def test_states_every_caveat_that_limits_the_ranking(self):
        report = ranker.render(Path("profile.jfr"),
                               ranker.cpu_hotspots([sample([("a/B", "x")])], 5),
                               ranker.allocation_hotspots([allocation("A", 10)], 5), None)
        for caveat in ("proportional to time", "extrapolated", "mob AI inactive",
                       "candidate to investigate, not a defect"):
            self.assertIn(caveat, report)

    def test_carries_the_workload_fidelity_statements(self):
        report = ranker.render(Path("p.jfr"), ranker.cpu_hotspots([], 5),
                               ranker.allocation_hotspots([], 5),
                               {"name": "players-100", "summary": "s",
                                "fidelity": ["does not model player network traffic"]})
        self.assertIn("players-100", report)
        self.assertIn("does not model player network traffic", report)

    def test_renders_an_unavailable_ranking_without_failing(self):
        report = ranker.render(Path("p.jfr"), ranker.cpu_hotspots([], 5),
                               ranker.allocation_hotspots([], 5), None)
        self.assertIn("Unavailable:", report)


if __name__ == "__main__":
    unittest.main()
