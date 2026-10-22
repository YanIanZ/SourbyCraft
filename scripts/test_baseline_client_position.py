import math
import struct
import unittest

import baseline_client as client
from baseline_network import write_varint


class FakeConnection:
    def __init__(self):
        self.sent = []

    def sendall(self, data):
        self.sent.append(data)


def position_packet(teleport_id, xyz, relatives=0, delta=(0.0, 0.0, 0.0)):
    return (write_varint(teleport_id) + struct.pack(">ddd", *xyz) + struct.pack(">ddd", *delta)
            + struct.pack(">ff", 0.0, 0.0) + struct.pack(">i", relatives))


class PositionSyncTest(unittest.TestCase):
    def setUp(self):
        self.client = client.HeadlessClient("127.0.0.1", 1, "Bench000")
        self.connection = FakeConnection()

    def sync(self, payload):
        self.client._sync(self.connection, payload, -1)

    def test_absolute_teleport_is_adopted(self):
        self.sync(position_packet(1, (10.5, 70.0, -3.25)))
        self.assertEqual((self.client._x, self.client._y, self.client._z), (10.5, 70.0, -3.25))
        self.assertEqual(self.client.relative_syncs, 0)
        self.assertEqual(len(self.connection.sent), 1)          # the teleport was confirmed

    def test_zero_offset_relative_teleport_keeps_the_position(self):
        self.client._x, self.client._y, self.client._z = 1800.0, 90.0, -2000.0
        self.sync(position_packet(2, (0.0, 0.0, 0.0), relatives=0b111))
        self.assertEqual((self.client._x, self.client._y, self.client._z), (1800.0, 90.0, -2000.0))
        self.assertEqual(self.client.relative_syncs, 1)

    def test_only_the_relative_axes_are_offsets(self):
        self.client._x, self.client._y, self.client._z = 100.0, 80.0, 200.0
        self.sync(position_packet(3, (5.0, 64.0, 7.0), relatives=0b101))      # X and Z relative
        self.assertEqual((self.client._x, self.client._y, self.client._z), (105.0, 64.0, 207.0))

    def test_a_non_finite_position_is_never_adopted(self):
        self.client._x, self.client._y, self.client._z = 4.0, 5.0, 6.0
        self.sync(position_packet(4, (math.nan, 1.0, 2.0)))
        self.assertEqual((self.client._x, self.client._y, self.client._z), (4.0, 5.0, 6.0))
        self.assertEqual(self.client.non_finite_syncs, 1)


class TeleportConfirmationTest(unittest.TestCase):
    def test_a_resent_teleport_is_confirmed_once(self):
        subject = client.HeadlessClient("127.0.0.1", 1, "Bench000")
        connection = FakeConnection()
        for _ in range(3):                      # the server resent id 5 while this client was behind
            subject._sync(connection, position_packet(5, (1.0, 2.0, 3.0)), -1)
        self.assertEqual(len(connection.sent), 1)
        self.assertEqual(subject.duplicate_teleports, 2)
        subject._sync(connection, position_packet(6, (4.0, 2.0, 3.0)), -1)      # a new teleport is confirmed
        self.assertEqual(len(connection.sent), 2)


class ShardingTest(unittest.TestCase):
    def test_shard_sizes_are_even_and_never_empty(self):
        self.assertEqual(client.shard_sizes(10, 3), [4, 3, 3])
        self.assertEqual(client.shard_sizes(2, 5), [1, 1])
        self.assertEqual(client.shard_sizes(50, 1), [50])

    def test_reports_merge_by_adding_and_keeping_the_first_error(self):
        one = {"requested": 3, "in_play": 2, "failed": 1, "keep_alives": 5, "moves": 7, "flying": 1,
               "corrections": 0, "syncs": 3, "first_error": "TimeoutError: timed out",
               "failure_reasons": {"TimeoutError: timed out": 1}, "failure_traces": {"Bench001": [1]},
               "stages": ["play", "failed"], "cpu_seconds": 1.5}
        two = {"requested": 2, "in_play": 2, "failed": 0, "keep_alives": 4, "moves": 1, "flying": 1,
               "corrections": 2, "syncs": 2, "first_error": None, "failure_reasons": {},
               "failure_traces": {}, "stages": ["play"], "cpu_seconds": 2.0}
        merged = client.merge_reports([one, two])
        self.assertEqual((merged["requested"], merged["in_play"], merged["failed"]), (5, 4, 1))
        self.assertEqual(merged["first_error"], "TimeoutError: timed out")
        self.assertEqual(merged["stages"], ["failed", "play"])
        self.assertEqual(merged["cpu_seconds"], 3.5)
        self.assertEqual(merged["processes"], 2)

    def test_sharded_swarm_names_and_reports_across_processes(self):
        import socket
        with socket.socket() as closed:
            closed.bind(("127.0.0.1", 0))
            port = closed.getsockname()[1]               # nothing listens here: every client fails
        swarm = client.ShardedSwarm("127.0.0.1", port, 3, prefix="Bench", processes=2)
        try:
            report = swarm.start(timeout=20.0)
        finally:
            swarm.stop()
        self.assertEqual((report["requested"], report["failed"], report["in_play"]), (3, 3, 0))
        self.assertEqual(report["processes"], 2)


class TimerMovesTest(unittest.TestCase):
    def test_locked_socket_serialises_sends_and_delegates_the_rest(self):
        class Raw:
            def __init__(self):
                self.sent = []
                self.timeout = None

            def sendall(self, data):
                self.sent.append(data)

            def settimeout(self, value):
                self.timeout = value

        raw = Raw()
        locked = client._LockedSocket(raw)
        locked.sendall(b"abc")
        locked.settimeout(5)
        self.assertEqual((raw.sent, raw.timeout), ([b"abc"], 5))

    def test_mover_steps_on_a_clock_while_in_play_and_stops_after(self):
        subject = client.HeadlessClient("127.0.0.1", 1, "Bench000", timer_moves=True)
        subject._synced = True
        subject.stage = "play"
        steps = []
        real_sleep = client.time.sleep
        calls = {"n": 0}

        def fake_step(connection, threshold):
            steps.append(1)
            if len(steps) == 3:
                subject.stage = "failed"

        def fake_sleep(seconds):
            calls["n"] += 1

        subject._step = fake_step
        client.time.sleep = fake_sleep
        try:
            subject._mover(FakeConnection(), -1)
        finally:
            client.time.sleep = real_sleep
        self.assertEqual(len(steps), 3)


if __name__ == "__main__":
    unittest.main()
