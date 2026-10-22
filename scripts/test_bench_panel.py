import json
import unittest

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
