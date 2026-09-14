import argparse
import io
import json
from pathlib import Path
import tempfile
import unittest

import baseline_metrics as metrics
import baseline_network as network
import baseline_workloads as workloads
import compare_baseline as compare
import run_baseline


class DurationTest(unittest.TestCase):
    def test_parses_the_jfr_duration_forms(self):
        self.assertAlmostEqual(metrics.duration_seconds("PT0.003590667S"), 0.003590667)
        self.assertAlmostEqual(metrics.duration_seconds("PT2M1.5S"), 121.5)
        self.assertAlmostEqual(metrics.duration_seconds("PT1H0M0S"), 3600.0)

    def test_rejects_anything_that_is_not_a_duration(self):
        for text in ("0.003S", "PT", "", "P1D"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                metrics.duration_seconds(text)


class PercentileTest(unittest.TestCase):
    def test_interpolates_between_neighbouring_samples(self):
        self.assertEqual(metrics.percentile([1, 2, 3, 4], 0.0), 1)
        self.assertEqual(metrics.percentile([1, 2, 3, 4], 1.0), 4)
        self.assertAlmostEqual(metrics.percentile([1, 2, 3, 4], 0.5), 2.5)
        self.assertAlmostEqual(metrics.percentile([10, 20], 0.95), 19.5)

    def test_ignores_input_ordering(self):
        self.assertEqual(metrics.percentile([9, 1, 5], 0.5), metrics.percentile([1, 5, 9], 0.5))

    def test_rejects_empty_and_out_of_range(self):
        with self.assertRaises(ValueError):
            metrics.percentile([], 0.5)
        with self.assertRaises(ValueError):
            metrics.percentile([1], 1.5)


class DistributionTest(unittest.TestCase):
    def test_reports_unavailable_rather_than_zero_for_no_samples(self):
        result = metrics.distribution([])
        self.assertFalse(result["available"])
        self.assertIn("reason", result)
        self.assertNotIn("mean", result)

    def test_summarizes_every_field_the_prd_requires(self):
        result = metrics.distribution([1.0, 2.0, 3.0])
        self.assertTrue(result["available"])
        self.assertEqual(result["samples"], 3)
        for field in ("mean", "min", "p50", "p95", "p99", "max"):
            self.assertIn(field, result)


class GcMetricsTest(unittest.TestCase):
    def test_reports_pause_distribution_and_collectors(self):
        pauses = [{"duration": "PT0.004S"}, {"duration": "PT0.020S"}]
        collections = [{"name": "G1New"}, {"name": "G1New"}, {"name": "G1Old"}]
        result = metrics.gc_metrics(pauses, collections)
        self.assertEqual(result["collectors"], ["G1New", "G1Old"])
        self.assertEqual(result["collections"], 3)
        self.assertEqual(result["pause_count"], 2)
        self.assertAlmostEqual(result["total_pause_ms"], 24.0)
        self.assertAlmostEqual(result["pause_ms"]["max"], 20.0)

    def test_unavailable_without_pause_events(self):
        self.assertFalse(metrics.gc_metrics([], [])["available"])


class AllocationTest(unittest.TestCase):
    @staticmethod
    def summaries(pairs):
        events = []
        for gc_id, (before, after) in enumerate(pairs):
            events.append({"gcId": gc_id, "when": "Before GC", "heapUsed": before})
            events.append({"gcId": gc_id, "when": "After GC", "heapUsed": after})
        return events

    def test_derives_allocated_bytes_from_consecutive_collections(self):
        # 100 -> 10, then allocate 90 to reach 100 again; twice.
        result = metrics.allocation_metrics(self.summaries([(100, 10), (100, 10), (100, 10)]), 2.0)
        self.assertEqual(result["allocated_bytes"], 180)
        self.assertEqual(result["bytes_per_second"], 90.0)
        self.assertEqual(result["collection_pairs"], 2)

    def test_clamps_a_shrinking_heap_instead_of_reporting_negative_allocation(self):
        result = metrics.allocation_metrics(self.summaries([(100, 90), (50, 10), (60, 10)]), 1.0)
        self.assertEqual(result["allocated_bytes"], 50)      # max(0, 50-90) + max(0, 60-10)

    def test_unavailable_with_fewer_than_two_collections(self):
        self.assertFalse(metrics.allocation_metrics(self.summaries([(100, 10)]), 1.0)["available"])

    def test_rejects_a_non_positive_window(self):
        with self.assertRaises(ValueError):
            metrics.allocation_metrics(self.summaries([(100, 10), (100, 10)]), 0)


class InstantTest(unittest.TestCase):
    def test_truncates_nanoseconds_datetime_cannot_parse(self):
        self.assertAlmostEqual(metrics.instant_seconds("2026-09-14T08:29:56.859497667+07:00"),
                               metrics.instant_seconds("2026-09-14T01:29:56.859497Z"), places=6)

    def test_rejects_a_timestamp_without_a_zone(self):
        with self.assertRaises(ValueError):
            metrics.instant_seconds("2026-09-14T08:29:56.859497667")


class ThreadAllocationTest(unittest.TestCase):
    @staticmethod
    def event(thread_id, name, second, allocated):
        return {"startTime": f"2026-09-14T00:00:{second:02d}.000000000+00:00",
                "thread": {"javaThreadId": thread_id, "javaName": name},
                "allocated": allocated}

    def test_sums_each_threads_growth_over_the_observed_span(self):
        result = metrics.thread_allocation_metrics([
            self.event(1, "a", 0, 100), self.event(1, "a", 10, 1100),
            self.event(2, "b", 0, 0), self.event(2, "b", 10, 500)])
        self.assertEqual(result["allocated_bytes"], 1500)
        self.assertEqual(result["observed_seconds"], 10.0)
        self.assertEqual(result["bytes_per_second"], 150.0)
        self.assertEqual(result["threads_observed"], 2)

    def test_ranks_the_heaviest_allocators_first(self):
        result = metrics.thread_allocation_metrics([
            self.event(1, "light", 0, 0), self.event(1, "light", 10, 10),
            self.event(2, "heavy", 0, 0), self.event(2, "heavy", 10, 900)])
        self.assertEqual([entry["thread"] for entry in result["top_threads"]], ["heavy", "light"])

    def test_ignores_event_ordering(self):
        forward = metrics.thread_allocation_metrics([self.event(1, "a", 0, 10), self.event(1, "a", 10, 90)])
        backward = metrics.thread_allocation_metrics([self.event(1, "a", 10, 90), self.event(1, "a", 0, 10)])
        self.assertEqual(forward["allocated_bytes"], backward["allocated_bytes"])

    def test_a_counter_that_went_backwards_is_clamped(self):
        result = metrics.thread_allocation_metrics([self.event(1, "a", 0, 500), self.event(1, "a", 10, 100)])
        self.assertEqual(result["allocated_bytes"], 0)

    def test_unavailable_without_events_or_without_a_span(self):
        self.assertFalse(metrics.thread_allocation_metrics([])["available"])
        single = metrics.thread_allocation_metrics([self.event(1, "a", 5, 10), self.event(2, "b", 5, 20)])
        self.assertFalse(single["available"])
        self.assertIn("one timestamp", single["reason"])

    def test_survives_a_thread_with_only_an_os_name(self):
        result = metrics.thread_allocation_metrics([
            {"startTime": "2026-09-14T00:00:00.000000000+00:00",
             "thread": {"osThreadId": 7, "javaName": None, "osName": "VM Thread"}, "allocated": 0},
            {"startTime": "2026-09-14T00:00:10.000000000+00:00",
             "thread": {"osThreadId": 7, "javaName": None, "osName": "VM Thread"}, "allocated": 80}])
        self.assertEqual(result["top_threads"][0]["thread"], "VM Thread")


class SnapshotTest(unittest.TestCase):
    @staticmethod
    def sample(state="AVAILABLE", mspt=10.0):
        return {"state": state, "build": "44c", "targetTps": 20.0, "activeRegions": 3,
                "worstTps": 20.0, "worstAverageMspt": mspt, "estimatedP95Mspt": mspt * 1.5,
                "estimatedP99Mspt": mspt * 2, "heapUsedBytes": 1 << 30}

    def test_excludes_stale_samples_that_republish_earlier_values(self):
        result = metrics.snapshot_metrics([self.sample(mspt=10.0), self.sample(mspt=20.0),
                                           self.sample(state="STALE", mspt=999.0)])
        self.assertEqual(result["usable_samples"], 2)
        self.assertEqual(result["samples_by_state"], {"AVAILABLE": 2, "STALE": 1})
        self.assertAlmostEqual(result["mspt"]["mean"], 15.0)
        self.assertEqual(result["target_tps"], 20.0)

    def test_warming_samples_are_usable_because_the_short_window_is_covered(self):
        # WARMING means only that a longer window is not yet fully covered; the JFR
        # event reads the five-second window, which is populated from the first minute.
        result = metrics.snapshot_metrics([self.sample(state="WARMING", mspt=12.0)])
        self.assertTrue(result["available"])
        self.assertEqual(result["usable_samples"], 1)
        self.assertFalse(result["long_windows_covered"])

    def test_flags_a_recording_whose_long_windows_are_all_covered(self):
        result = metrics.snapshot_metrics([self.sample(mspt=12.0)])
        self.assertTrue(result["long_windows_covered"])

    def test_unavailable_when_the_event_is_missing(self):
        result = metrics.snapshot_metrics([])
        self.assertFalse(result["available"])
        self.assertIn("build 44", result["reason"])

    def test_unavailable_when_every_sample_is_stale_or_unavailable(self):
        result = metrics.snapshot_metrics([self.sample(state="STALE"),
                                           self.sample(state="UNAVAILABLE")])
        self.assertFalse(result["available"])
        self.assertIn("STALE", result["reason"])


class WorkloadTest(unittest.TestCase):
    # PRD section 5: the harness may not alter administrator-controlled behaviour.
    FORBIDDEN = ("view-distance", "simulation-distance", "max-tick-time", "mobcap",
                 "spawn-limits", "canvas", "paper", "tickrate", "sleep")

    def test_every_prd_section_9_workload_is_defined(self):
        self.assertEqual(set(workloads.NAMES),
                         {"idle", "players-10", "players-50", "players-100",
                          "entity-stress", "chunk-stress", "network-stress"})

    def test_every_plan_states_its_fidelity_limits(self):
        for name in workloads.NAMES:
            with self.subTest(name=name):
                plan = workloads.build(name)
                self.assertTrue(plan.fidelity, "a workload must say what it does not measure")
                self.assertTrue(plan.summary.strip())
                self.assertGreaterEqual(plan.minimum_heap_mib, 2048)

    def test_no_workload_touches_a_performance_setting(self):
        for name in workloads.NAMES:
            plan = workloads.build(name)
            for command in (*plan.setup, *plan.steady):
                for token in self.FORBIDDEN:
                    with self.subTest(name=name, command=command, token=token):
                        self.assertNotIn(token, command.lower())

    def test_player_sites_do_not_overlap(self):
        plan = workloads.build("players-50")
        radius = plan.parameters["chunk_radius"]
        occupied = set()
        for command in plan.setup:
            if not command.startswith("forceload add"):
                continue
            _, _, x1, z1, x2, z2 = command.split()
            chunks = {(cx, cz)
                      for cx in range(int(x1) // 16, int(x2) // 16 + 1)
                      for cz in range(int(z1) // 16, int(z2) // 16 + 1)}
            self.assertEqual(len(chunks), (2 * radius + 1) ** 2)
            self.assertFalse(chunks & occupied, "player sites must be spatially disjoint")
            occupied |= chunks
        self.assertEqual(len(occupied), plan.parameters["forceloaded_chunks"])

    def test_entity_counts_match_the_declared_parameters(self):
        plan = workloads.build("players-10")
        summons = [command for command in plan.setup if command.startswith("summon")]
        self.assertEqual(len(summons), plan.parameters["entities_total"])

    def test_player_workloads_declare_their_dependence_on_connected_clients(self):
        for name in ("players-10", "players-50", "players-100", "entity-stress"):
            with self.subTest(name=name):
                self.assertTrue(workloads.build(name).requires_connected_players)
        for name in ("idle", "chunk-stress", "network-stress"):
            with self.subTest(name=name):
                self.assertFalse(workloads.build(name).requires_connected_players)

    def test_moving_window_advances_to_fresh_terrain(self):
        plan = workloads.build("chunk-stress")
        first, second = (workloads.window_commands(plan, step) for step in (0, 1))
        self.assertNotEqual(first, second)
        self.assertTrue(first.startswith("forceload add"))
        with self.assertRaises(ValueError):
            workloads.window_commands(workloads.build("idle"), 0)

    def test_unknown_workload_names_are_rejected(self):
        with self.assertRaises(KeyError):
            workloads.build("players-1000")


class NetworkCodecTest(unittest.TestCase):
    def test_matches_the_published_varint_vectors(self):
        for value, encoded in ((0, b"\x00"), (1, b"\x01"), (127, b"\x7f"), (128, b"\x80\x01"),
                               (255, b"\xff\x01"), (25565, b"\xdd\xc7\x01"),
                               (2097151, b"\xff\xff\x7f"), (-1, b"\xff\xff\xff\xff\x0f")):
            with self.subTest(value=value):
                self.assertEqual(network.write_varint(value), encoded)
                self.assertEqual(network.read_varint(io.BytesIO(encoded)), value & 0xFFFFFFFF)

    def test_rejects_an_overlong_varint(self):
        with self.assertRaises(ValueError):
            network.read_varint(io.BytesIO(b"\x80\x80\x80\x80\x80\x01"))

    def test_reports_a_truncated_stream_as_a_connection_failure(self):
        with self.assertRaises(ConnectionError):
            network.read_varint(io.BytesIO(b""))

    def test_rejects_an_implausible_packet_length(self):
        with self.assertRaises(ValueError):
            network._read_packet(io.BytesIO(network.write_varint(1 << 21)))


def record(name="players-50", commit="a" * 40, **overrides):
    base = {
        "schema": "sourbycraft.baseline/1",
        "workload": {"name": name, "summary": "s", "level_type": "minecraft:flat",
                     "fidelity": [], "parameters": {}},
        "provenance": {"commit": commit, "worktree_dirty": False, "jar_sha256": "b" * 64, "java_version": "openjdk 25",
                       "platform": "macOS", "machine": "arm64", "cpu_count": 10, "heap_mib": 6144,
                       "jvm_args": ["-Xmx6144M"], "jfr_settings": "profile", "warmup_seconds": 120,
                       "duration_seconds": 600, "connected_players_asserted": 0, "plugins": [],
                       "competing_servers_at_start": []},
        "certified": True, "certification": "meets baseline requirements",
        "metrics": {"tick": {"available": True, "mspt": {"mean": 10.0, "p50": 9.0, "p95": 20.0,
                                                         "p99": 30.0, "max": 40.0},
                             "tps": {"mean": 20.0}},
                    "cpu": {"available": True, "process_fraction": {"mean": 0.5},
                            "foreign_fraction": {"available": True, "mean": 0.01, "max": 0.05}},
                    "gc": {"total_pause_ms": 100.0, "collections": 20,
                           "pause_ms": {"p95": 5.0}},
                    "allocation": {"bytes_per_second": 1000.0},
                    "allocation_from_gc": {"bytes_per_second": 900.0,
                                           "heap_used_after_gc": {"mean": 500.0}},
                    "rss": {"bytes": {"mean": 2000.0}}}}
    base.update(overrides)
    return base


def scaled(factor, **overrides):
    candidate = json.loads(json.dumps(record(**overrides)))
    tick = candidate["metrics"]["tick"]["mspt"]
    for key in tick:
        tick[key] *= factor
    return candidate


class CompareTest(unittest.TestCase):
    def test_v8_uncertified_input_blocks_the_gate(self):
        candidate = record(certified=False, certification="short window")
        _, blocking = compare.compare(record(), candidate, 0.03, {"CPU"})
        self.assertTrue(any("certified" in reason for reason in blocking))

    def test_v8_network_regression_uses_top_level_report(self):
        before = record(network={"round_trips_per_second": 100.0})
        after = record(network={"round_trips_per_second": 80.0})
        _, blocking = compare.compare(before, after, 0.03, {"Network round-trips"})
        self.assertTrue(any("Network" in reason for reason in blocking))

    def test_v8_report_cannot_pass_with_provenance_drift(self):
        report = compare.render(record(), record(), [], [], ["cpu_count differs"], 0.03)
        self.assertNotIn("**Gate (3%):** PASS", report)

    def test_blocks_a_regression_beyond_the_threshold(self):
        rows, blocking = compare.compare(record(), scaled(1.05), 0.03, set(compare.METRICS))
        self.assertTrue(blocking)
        self.assertTrue(any("MSPT p95" in entry for entry in blocking))

    def test_allows_a_change_inside_the_threshold(self):
        _, blocking = compare.compare(record(), scaled(1.02), 0.03, set(compare.METRICS))
        self.assertFalse(blocking)

    def test_an_improvement_is_never_a_regression(self):
        _, blocking = compare.compare(record(), scaled(0.5), 0.03, set(compare.METRICS))
        self.assertFalse(blocking)

    def test_a_falling_tps_counts_as_a_regression(self):
        candidate = json.loads(json.dumps(record()))
        candidate["metrics"]["tick"]["tps"]["mean"] = 18.0
        _, blocking = compare.compare(record(), candidate, 0.03, set(compare.METRICS))
        self.assertTrue(any("TPS avg" in entry for entry in blocking))

    def test_an_ungated_metric_is_reported_but_not_blocking(self):
        rows, blocking = compare.compare(record(), scaled(1.5), 0.03, {"CPU"})
        self.assertFalse(blocking)
        self.assertTrue(any(label == "MSPT p95" for label, *_ in rows))

    def test_a_missing_metric_is_reported_as_unavailable(self):
        candidate = json.loads(json.dumps(record()))
        candidate["metrics"]["rss"] = {"available": False, "reason": "none"}
        rows, _ = compare.compare(record(), candidate, 0.03, set(compare.METRICS))
        self.assertIn(("RAM (RSS)", "2000 B", "unavailable", None, ""), rows)

    def test_provenance_drift_is_detected(self):
        candidate = json.loads(json.dumps(record()))
        candidate["provenance"]["cpu_count"] = 8
        drift = compare.provenance_drift(record(), candidate)
        self.assertEqual(len(drift), 1)
        self.assertIn("cpu_count", drift[0])
        self.assertFalse(compare.provenance_drift(record(), record()))

    def test_the_report_carries_the_section_85_fields_and_the_verdict(self):
        rows, blocking = compare.compare(record(), scaled(1.05), 0.03, set(compare.METRICS))
        report = compare.render(record(), scaled(1.05), rows, blocking, [], 0.03)
        for field in ("Baseline SHA", "Candidate SHA", "Hardware", "Java", "JVM flags",
                      "World", "Plugins", "Duration", "Warmup", "MSPT p99", "Allocation"):
            self.assertIn(field, report)
        self.assertIn("BLOCKED", report)

    def test_an_uncertified_run_is_called_out_in_the_report(self):
        candidate = scaled(1.0)
        candidate["certified"], candidate["certification"] = False, "no clients attached"
        report = compare.render(record(), candidate, [], [], [], 0.03)
        self.assertIn("not a certified baseline", report)
        self.assertIn("no clients attached", report)

    def test_loading_rejects_a_file_that_is_not_a_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.json"
            path.write_text(json.dumps({"schema": "something-else"}))
            with self.assertRaises(SystemExit):
                compare.load(directory)


class CertifyTest(unittest.TestCase):
    @staticmethod
    def args(**overrides):
        values = {"connected_players": 0, "duration": 600}
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_a_player_gated_workload_is_uncertified_without_asserted_clients(self):
        certified, reason = run_baseline.certify(workloads.build("players-50"), record(), self.args())
        self.assertFalse(certified)
        self.assertIn("activation range", reason)

    def test_the_same_workload_certifies_once_clients_are_asserted(self):
        certified, reason = run_baseline.certify(workloads.build("players-50"), record(),
                                                 self.args(connected_players=50))
        self.assertTrue(certified)
        self.assertEqual(reason, "meets baseline requirements")

    def test_a_short_window_is_uncertified(self):
        certified, reason = run_baseline.certify(workloads.build("idle"), record(),
                                                 self.args(duration=60))
        self.assertFalse(certified)
        self.assertIn("300s", reason)

    def test_missing_tick_telemetry_is_uncertified(self):
        blind = record()
        blind["metrics"]["tick"] = {"available": False, "reason": "event disabled"}
        certified, reason = run_baseline.certify(workloads.build("idle"), blind, self.args())
        self.assertFalse(certified)
        self.assertIn("event disabled", reason)

    def test_a_dirty_worktree_is_uncertified(self):
        dirty = record()
        dirty["provenance"]["worktree_dirty"] = True
        certified, reason = run_baseline.certify(workloads.build("idle"), dirty, self.args())
        self.assertFalse(certified)
        self.assertIn("dirty", reason)


class ForeignCpuTest(unittest.TestCase):
    def test_reports_what_the_machine_did_that_this_server_did_not(self):
        result = metrics.cpu_metrics([{"jvmUser": 0.20, "jvmSystem": 0.05, "machineTotal": 0.80}])
        self.assertAlmostEqual(result["foreign_fraction"]["mean"], 0.55)
        self.assertAlmostEqual(result["process_fraction"]["mean"], 0.25)

    def test_a_quiet_machine_reports_near_zero_foreign_cpu(self):
        result = metrics.cpu_metrics([{"jvmUser": 0.30, "jvmSystem": 0.02, "machineTotal": 0.33}])
        self.assertLess(result["foreign_fraction"]["mean"], 0.02)

    def test_never_reports_negative_foreign_cpu(self):
        # The two figures are sampled independently and can disagree slightly.
        result = metrics.cpu_metrics([{"jvmUser": 0.50, "jvmSystem": 0.10, "machineTotal": 0.55}])
        self.assertEqual(result["foreign_fraction"]["min"], 0.0)


class ContentionCertificationTest(unittest.TestCase):
    @staticmethod
    def args(**overrides):
        values = {"connected_players": 0, "duration": 600}
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_a_contended_window_is_not_certified(self):
        busy = record()
        busy["metrics"]["cpu"]["foreign_fraction"] = {"available": True, "mean": 0.42, "max": 0.90}
        certified, reason = run_baseline.certify(workloads.build("idle"), busy, self.args())
        self.assertFalse(certified)
        self.assertIn("sharing the box", reason)
        self.assertIn("42", reason)

    def test_a_quiet_window_certifies(self):
        certified, reason = run_baseline.certify(workloads.build("idle"), record(), self.args())
        self.assertTrue(certified)
        self.assertEqual(reason, "meets baseline requirements")

    def test_a_competing_server_at_start_is_not_certified(self):
        shared = record()
        shared["provenance"]["competing_servers_at_start"] = ["pid 2760: java -jar Sourby.jar"]
        certified, reason = run_baseline.certify(workloads.build("idle"), shared, self.args())
        self.assertFalse(certified)
        self.assertIn("already running", reason)

    def test_unavailable_cpu_metrics_do_not_crash_certification(self):
        blind = record()
        blind["metrics"]["cpu"] = {"available": False, "reason": "no events"}
        certified, reason = run_baseline.certify(workloads.build("idle"), blind, self.args())
        self.assertTrue(certified)


class CompetingServerTest(unittest.TestCase):
    def test_finds_another_process_running_the_same_jar(self):
        from pathlib import Path as P
        import subprocess as sp
        listing = ("  111 /usr/bin/java -Xmx2G -jar /x/SourbyCraft-slim.jar --nogui\n"
                   "  222 /bin/zsh -c something-else\n")
        original = sp.run
        sp.run = lambda *a, **k: type("R", (), {"stdout": listing})()
        try:
            found = run_baseline.competing_servers(P("/x/SourbyCraft-slim.jar"), -1)
        finally:
            sp.run = original
        self.assertEqual(len(found), 1)
        self.assertIn("pid 111", found[0])

    def test_ignores_its_own_pid_and_non_jar_matches(self):
        from pathlib import Path as P
        import os as _os
        import subprocess as sp
        listing = f"  {_os.getpid()} java -jar /x/SourbyCraft-slim.jar\n  333 grep SourbyCraft-slim.jar\n"
        original = sp.run
        sp.run = lambda *a, **k: type("R", (), {"stdout": listing})()
        try:
            found = run_baseline.competing_servers(P("/x/SourbyCraft-slim.jar"), -1)
        finally:
            sp.run = original
        self.assertEqual(found, [])


class MidRunChurnTest(unittest.TestCase):
    @staticmethod
    def args(**overrides):
        values = {"connected_players": 0, "duration": 600}
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_a_commit_landing_mid_run_is_not_certified(self):
        moved = record()
        moved["provenance"]["commit_moved_during_run"] = True
        certified, reason = run_baseline.certify(workloads.build("idle"), moved, self.args())
        self.assertFalse(certified)
        self.assertIn("HEAD moved", reason)

    def test_an_unchanged_repository_certifies(self):
        steady = record()
        steady["provenance"]["commit_moved_during_run"] = False
        certified, _ = run_baseline.certify(workloads.build("idle"), steady, self.args())
        self.assertTrue(certified)


class SeedCacheTest(unittest.TestCase):
    def test_copies_a_cache_directory_into_the_new_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            donor = root / "donor" / "cache"
            donor.mkdir(parents=True)
            (donor / "mojang_26.2.jar").write_bytes(b"payload")
            target = root / "run"
            target.mkdir()
            seeded = run_baseline.seed_cache(target, root / "donor")
            self.assertEqual(seeded, ["mojang_26.2.jar"])
            self.assertEqual((target / "cache" / "mojang_26.2.jar").read_bytes(), b"payload")

    def test_accepts_the_cache_directory_itself(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            donor = root / "donor" / "cache"
            donor.mkdir(parents=True)
            (donor / "a.jar").write_bytes(b"x")
            target = root / "run"
            target.mkdir()
            self.assertEqual(run_baseline.seed_cache(target, donor), ["a.jar"])

    def test_rejects_a_source_with_no_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "empty").mkdir()
            (root / "run").mkdir()
            with self.assertRaises(RuntimeError):
                run_baseline.seed_cache(root / "run", root / "empty")


class HeapGuardTest(unittest.TestCase):
    def test_refuses_to_run_a_workload_under_its_minimum_heap(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "run"
            with self.assertRaises(RuntimeError) as raised:
                run_baseline.prepare(target, workloads.build("entity-stress"), 25585, 2048)
            self.assertIn("needs at least", str(raised.exception))

    def test_writes_an_isolated_fixture_that_disables_external_provisioning(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "run"
            config = run_baseline.prepare(target, workloads.build("idle"), 25585, 4096)
            properties = (target / "server.properties").read_text()
            self.assertIn("server-ip=127.0.0.1", properties)
            self.assertIn("online-mode=false", properties)
            self.assertIn(r"level-type=minecraft\:flat", properties)
            self.assertIn("auto-provision=false", config.read_text())
            self.assertEqual((target / "eula.txt").read_text(), "eula=true\n")

    def test_refuses_to_overwrite_an_existing_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileExistsError):
                run_baseline.prepare(Path(directory), workloads.build("idle"), 25585, 4096)


if __name__ == "__main__":
    unittest.main()
