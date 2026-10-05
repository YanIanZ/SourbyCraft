import gzip
import struct
import tempfile
import unittest
from pathlib import Path

import verify_persistence as persistence


def nbt_string(value):
    data = value.encode()
    return struct.pack(">H", len(data)) + data


def named(tag, name, payload):
    return bytes([tag]) + nbt_string(name) + payload


def compound(*entries):
    return b"".join(entries) + b"\x00"


def player_nbt(diamonds, xp_level, pos):
    item = compound(named(8, "id", nbt_string("minecraft:diamond")),
                    named(3, "count", struct.pack(">i", diamonds)))
    inventory = bytes([10]) + struct.pack(">i", 1) + item
    position = bytes([6]) + struct.pack(">i", 3) + struct.pack(">ddd", *pos)
    root = compound(named(9, "Inventory", inventory),
                    named(3, "XpLevel", struct.pack(">i", xp_level)),
                    named(9, "Pos", position))
    return bytes([10]) + nbt_string("") + root


class OfflineUuidTest(unittest.TestCase):
    def test_matches_the_server_for_a_known_name(self):
        # UUID.nameUUIDFromBytes("OfflinePlayer:Notch") as an offline-mode server computes it.
        self.assertEqual(persistence.offline_uuid("Notch"), "b50ad385-829d-3141-a216-7e7d7539ba7f")

    def test_is_a_version_3_uuid(self):
        self.assertEqual(persistence.offline_uuid(persistence.PROBE_PLAYER)[14], "3")


class NbtTest(unittest.TestCase):
    def test_reads_inventory_level_and_position(self):
        data = persistence.read_nbt(player_nbt(37, 13, (1.5, 100.0, -2.5)))
        self.assertEqual(persistence.diamonds_in(data["Inventory"]), 37)
        self.assertEqual(data["XpLevel"], 13)
        self.assertEqual(data["Pos"], [1.5, 100.0, -2.5])

    def test_rejects_a_root_that_is_not_a_compound(self):
        with self.assertRaises(ValueError):
            persistence.read_nbt(b"\x08" + nbt_string("") + nbt_string("x"))


class ConsoleOutputTest(unittest.TestCase):
    def test_colour_codes_do_not_split_nbt_numbers(self):
        # Captured from a 26.2 console: every NBT number is wrapped in colour codes.
        line = ("PersistProbe has the following entity data: [\x1b[38;5;3m3010.5\x1b[38;5;9md\x1b[0m, "
                "\x1b[38;5;3m100.0\x1b[38;5;9md\x1b[0m, \x1b[38;5;3m-3005.5\x1b[38;5;9md\x1b[0m]")
        self.assertEqual(persistence.ANSI.sub("", line),
                         "PersistProbe has the following entity data: [3010.5d, 100.0d, -3005.5d]")

    def test_join_and_quit_are_read_from_paper_lines_not_broadcasts(self):
        self.assertTrue(persistence.JOINED.search(
            "PersistProbe[/127.0.0.1:56197] logged in with entity id 105 at ([minecraft:overworld]0, 66, 0)"))
        self.assertTrue(persistence.LEFT.search("PersistProbe lost connection: Disconnected"))


class PlayerFileCheckTest(unittest.TestCase):
    def run_check(self, diamonds, xp_level, pos):
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory)
            folder = world / "players" / "data"
            folder.mkdir(parents=True)
            name = persistence.offline_uuid(persistence.PROBE_PLAYER) + ".dat"
            (folder / name).write_bytes(gzip.compress(player_nbt(diamonds, xp_level, pos)))
            checks = persistence.Check()
            persistence.check_player_data(world, checks, {
                "diamonds": 37, "xp_level": 13, "pos": persistence.PROBE_PLAYER_POS})
            return {r["check"].split(": ", 1)[1]: r["ok"] for r in checks.results}

    def test_passes_when_the_file_holds_what_was_written(self):
        results = self.run_check(37, 13, persistence.PROBE_PLAYER_POS)
        self.assertTrue(all(results.values()), results)

    def test_fails_on_a_lost_item_or_a_moved_player(self):
        results = self.run_check(36, 13, (0.0, 64.0, 0.0))
        self.assertFalse(results["inventory on disk"])
        self.assertFalse(results["position on disk"])
        self.assertTrue(results["experience level on disk"])


if __name__ == "__main__":
    unittest.main()
