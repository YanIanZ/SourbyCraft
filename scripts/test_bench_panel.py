import json
import socket
import unittest
from pathlib import Path

import bench_panel as bench
import verify_persistence as persistence


class BenchPanelTest(unittest.TestCase):
    def test_properties_are_changed_in_place_and_nothing_else_moves(self):
        original = "#comment\nonline-mode=true\nlevel-name=world\nmotd=hi\n"
        changed = bench.set_properties(original, {"online-mode": "false", "level-name": "bench",
                                                  "white-list": "true"})
        self.assertEqual(changed, "#comment\nonline-mode=false\nlevel-name=bench\nmotd=hi\n"
                                  "white-list=true\n")

    def test_plugin_shadowed_commands_go_to_vanilla(self):
        self.assertEqual(bench.vanilla("time set noon"), "minecraft:time set noon")
        self.assertEqual(bench.vanilla("weather clear"), "minecraft:weather clear")
        self.assertEqual(bench.vanilla("gamerule spawn_mobs false"), "gamerule spawn_mobs false")
        self.assertEqual(
            bench.vanilla("execute positioned 8 0 8 positioned over world_surface run tp Bench000 ~ ~1 ~"),
            "execute positioned 8 0 8 positioned over world_surface run minecraft:tp Bench000 ~ ~1 ~")

    def test_whitelist_lists_exactly_the_bench_clients_by_offline_uuid(self):
        entries = json.loads(bench.bench_whitelist(3))
        self.assertEqual([e["name"] for e in entries], ["Bench000", "Bench001", "Bench002"])
        self.assertEqual(entries[0]["uuid"], persistence.offline_uuid("Bench000"))

    def test_offline_uuid_matches_the_server_harness_copy(self):
        for name in ("Bench000", "Bench049", "Notch"):
            self.assertEqual(bench.offline_uuid(name), persistence.offline_uuid(name))

    def test_bench_script_needs_no_server_harness_modules(self):
        # It is copied to a remote load-generator host; verify_persistence pulls in run_baseline & co.
        source = Path(bench.__file__).read_text()
        self.assertNotIn("import verify_persistence", source)
        self.assertNotIn("from verify_persistence", source)

    def test_connect_rtt_reports_failures_instead_of_hiding_them(self):
        with socket.socket() as closed:
            closed.bind(("127.0.0.1", 0))
            port = closed.getsockname()[1]
        result = bench.connect_rtt("127.0.0.1", port, attempts=2, timeout=1.0)
        self.assertEqual(result, {"attempts": 2, "failed": 2})
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(4)
            result = bench.connect_rtt("127.0.0.1", listener.getsockname()[1], attempts=3, timeout=1.0)
        self.assertEqual((result["attempts"], result["failed"]), (3, 0))
        self.assertLessEqual(result["min_ms"], result["median_ms"])
        self.assertLessEqual(result["median_ms"], result["max_ms"])

    def test_readiness_wait_survives_a_bad_gateway_while_the_server_boots(self):
        import urllib.error
        replies = iter([urllib.error.HTTPError("u", 502, "Bad Gateway", {}, None), None, "ok"])
        clock = {"now": 0.0}

        def probe():
            reply = next(replies)
            if isinstance(reply, Exception):
                raise reply
            return reply

        def sleep(seconds):
            clock["now"] += seconds

        self.assertTrue(bench.wait_until_answering(probe, 300, sleep, lambda: clock["now"]))
        self.assertEqual(clock["now"], 6.0)      # two waits: the 502 and the unanswered probe

    def test_panel_api_retries_outlast_a_boot_window_of_bad_gateways(self):
        # 2**0 + ... + 2**(n-2) seconds of backoff before the last try.
        self.assertGreaterEqual(sum(2 ** i for i in range(bench.BENCH_API_RETRIES - 1)), 100)

    def test_several_edits_to_one_file_all_apply(self):
        originals = {"/a.yml": "x: 1\ny: 2\n", "/b.yml": "z: 3\n"}
        edited = bench.apply_edits(originals, [("/a.yml", "x: 1", "x: 9"), ("/a.yml", "y: 2", "y: 8"),
                                               ("/b.yml", "z: 3", "z: 4")])
        self.assertEqual(edited, {"/a.yml": "x: 9\ny: 8\n", "/b.yml": "z: 4\n"})
        self.assertEqual(originals["/a.yml"], "x: 1\ny: 2\n")          # the saved originals stay intact

    def test_an_edit_whose_text_is_missing_stops_the_bench(self):
        with self.assertRaises(SystemExit):
            bench.apply_edits({"/a.yml": "x: 1\n"}, [("/a.yml", "q: 1", "q: 2")])

    def test_readiness_wait_gives_up_at_the_deadline(self):
        clock = {"now": 0.0}

        def sleep(seconds):
            clock["now"] += seconds

        self.assertFalse(bench.wait_until_answering(lambda: None, 10, sleep, lambda: clock["now"]))

    def test_connection_throttle_is_disabled_and_nothing_else_changes(self):
        self.assertEqual(bench.without_throttle("settings:\n  connection-throttle: 4000\n  x: 1\n"),
                         "settings:\n  connection-throttle: -1\n  x: 1\n")

    def test_guard_stops_on_a_real_intruder_line_and_lets_bench_clients_in(self):
        # Both lines are from the Sourby Demo log of 2026-10-05, when bench mode was opened.
        intruder = ("[16:17:25] [Folia Region Scheduler Thread #2/INFO]: iYanZ[/140.213.187.153:7052] "
                    "logged in with entity id 448 at ([minecraft:overworld]-2.5, 66.0, 3.5)")
        client = ("[16:20:07] [Folia Region Scheduler Thread #7/INFO]: Bench000[/140.213.187.153:24747] "
                  "logged in with entity id 1009 at ([minecraft:overworld]9.5, 75.0, 2.5)")
        with self.assertRaises(bench.BenchBreached):
            bench.guard(intruder)
        bench.guard(client)
        with self.assertRaises(bench.BenchBreached):
            bench.guard("[16:17:19] [Folia Region Scheduler Thread #1/INFO]: Whitelist is now turned off")


if __name__ == "__main__":
    unittest.main()
