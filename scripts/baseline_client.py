#!/usr/bin/env python3
"""Headless Minecraft clients, so a workload can have players in it.

Entity activation range is computed around connected players, so a run with no clients
leaves mob AI, goal selection and pathfinding switched off entirely and measures only
block and chunk work. Every player workload was uncertifiable for exactly that reason.

The protocol here was not written from memory. Packet identifiers and field layouts were
observed against a running 26.2 server with scripts/protocol_probe.py, and the
ClientInformation layout is taken from net.minecraft.server.level.ClientInformation#write
in the materialized sources. A different Minecraft version will need the probe re-run.
"""
import socket
import threading
import time
import zlib

from baseline_network import read_varint, write_varint

CONNECT_TIMEOUT = 10.0
READ_TIMEOUT = 30.0

# Observed against protocol 776. See module docstring.
LOGIN_SET_COMPRESSION = 0x03
CONFIG_KEEP_ALIVE = 0x04
CONFIG_KNOWN_PACKS = 0x0E
CONFIG_FINISH = 0x03
SERVERBOUND_LOGIN_ACK = 0x03
SERVERBOUND_CLIENT_INFORMATION = 0x00
SERVERBOUND_KNOWN_PACKS = 0x07
SERVERBOUND_CONFIG_KEEP_ALIVE = 0x04
SERVERBOUND_FINISH_CONFIG = 0x03
# Play-stage keep-alive, observed rather than derived. Counting addPacket registrations
# in GameProtocols gave 0x2B for the clientbound id, which is wrong — 0x2B arrives several
# times a second and is something else entirely. Replying to it with a keep-alive id makes
# the server see a response whose id matches no pending keep-alive, and Paper drops that
# connection with "Timed out" in about two seconds.
#
# The real clientbound id is 0x2C. Counting addPacket registrations misses that the
# clientbound builder calls withBundlePacket first, which takes id 0, so every clientbound
# id is one higher than its addPacket index. The serverbound builder has no bundle, so its
# ids are the plain index. The serverbound id is
# confirmed by the server's own decoder: sending 0x1C with an over-long body is rejected as
# "Packet play/serverbound/minecraft:keep_alive was larger than I expected", so 0x1C is
# being decoded as keep_alive. Both carry exactly one long.
PLAY_KEEP_ALIVE_CLIENTBOUND = 0x2C
PLAY_KEEP_ALIVE_SERVERBOUND = 0x1C


class _Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def read(self, count):
        chunk = self.data[self.pos:self.pos + count]
        self.pos += len(chunk)
        return chunk

    def rest(self):
        return self.data[self.pos:]


def _frame(packet_id, payload=b"", threshold=-1):
    body = write_varint(packet_id) + payload
    if threshold < 0:
        return write_varint(len(body)) + body
    if len(body) < threshold:
        body = write_varint(0) + body
        return write_varint(len(body)) + body
    compressed = write_varint(len(body)) + zlib.compress(body)
    return write_varint(len(compressed)) + compressed


def _read_frame(stream, threshold):
    length = read_varint(stream)
    if length <= 0:
        raise ConnectionError(f"implausible frame length {length}")
    data = stream.read(length)
    if len(data) != length:
        raise ConnectionError("connection closed mid-frame")
    if threshold >= 0:
        view = _Reader(data)
        uncompressed = read_varint(view)
        data = zlib.decompress(view.rest()) if uncompressed else view.rest()
    view = _Reader(data)
    return read_varint(view), view.rest()


def client_information(view_distance):
    """Field order from ClientInformation#write; enums are VarInt ordinals."""
    locale = b"en_us"
    return (write_varint(len(locale)) + locale
            + bytes([view_distance & 0xFF])
            + write_varint(0)                 # chat visibility: FULL
            + bytes([1])                      # chat colours
            + bytes([0x7F])                   # model customisation
            + write_varint(1)                 # main hand: RIGHT
            + bytes([0])                      # text filtering
            + bytes([1])                      # allows listing
            + write_varint(0))                # particle status: ALL


class HeadlessClient(threading.Thread):
    """One connected player. Reaches the play stage and stays there answering keep-alives.

    View distance defaults to 2, the same as the server's own ClientInformation default.
    A load-generating client never renders anything, and at distance 10 the chunk traffic
    is heavy enough that decompressing every frame in Python falls behind the keep-alive
    deadline and the server times the client out.
    """

    def __init__(self, host, port, name, view_distance=2):
        super().__init__(name=f"client-{name}", daemon=True)
        self._host, self._port, self._name = host, port, name
        self._view_distance = view_distance
        self._halt = threading.Event()
        self.stage = "new"
        self.failure = None
        self.keep_alives = 0
        self.reached_play = threading.Event()

    def stop(self):
        self._halt.set()

    def run(self):
        try:
            self._session()
        except Exception as problem:                  # A client failure is data, not a crash.
            self.failure = f"{type(problem).__name__}: {problem}"
            self.stage = "failed"

    def _session(self):
        address = self._host.encode()
        name = self._name.encode()
        threshold = -1
        with socket.create_connection((self._host, self._port), timeout=CONNECT_TIMEOUT) as connection:
            connection.settimeout(READ_TIMEOUT)
            connection.sendall(_frame(0x00, write_varint(776) + write_varint(len(address))
                                      + address + self._port.to_bytes(2, "big") + write_varint(2)))
            connection.sendall(_frame(0x00, write_varint(len(name)) + name + b"\x00" * 16, threshold))
            self.stage = "login"
            with connection.makefile("rb") as stream:
                while not self._halt.is_set():
                    packet_id, payload = _read_frame(stream, threshold)
                    if self.stage == "login":
                        if threshold < 0 and packet_id == LOGIN_SET_COMPRESSION:
                            threshold = read_varint(_Reader(payload))
                            continue
                        if len(payload) > 16:          # Login Success
                            connection.sendall(_frame(SERVERBOUND_LOGIN_ACK, b"", threshold))
                            connection.sendall(_frame(SERVERBOUND_CLIENT_INFORMATION,
                                                      client_information(self._view_distance),
                                                      threshold))
                            self.stage = "configuration"
                        continue
                    if self.stage == "configuration":
                        if packet_id == CONFIG_KEEP_ALIVE:
                            connection.sendall(_frame(SERVERBOUND_CONFIG_KEEP_ALIVE, payload, threshold))
                        elif packet_id == CONFIG_KNOWN_PACKS:
                            # An empty list asks the server for full registry data.
                            connection.sendall(_frame(SERVERBOUND_KNOWN_PACKS, write_varint(0), threshold))
                        elif packet_id == CONFIG_FINISH:
                            connection.sendall(_frame(SERVERBOUND_FINISH_CONFIG, b"", threshold))
                            self.stage = "play"
                            self.reached_play.set()
                        continue
                    # Play: answer only the keep-alive, by its own id. Everything else is
                    # read and discarded, which is all a load-generating client needs to do.
                    if packet_id == PLAY_KEEP_ALIVE_CLIENTBOUND:
                        # The reply carries exactly the eight-byte id and nothing else; echoing
                        # the whole clientbound payload is rejected as "larger than I expected".
                        connection.sendall(_frame(PLAY_KEEP_ALIVE_SERVERBOUND, payload[:8], threshold))
                        self.keep_alives += 1


class ClientSwarm:
    """A group of headless clients, reported on as a whole."""

    def __init__(self, host, port, count, prefix="Baseline", view_distance=2):
        self._clients = [HeadlessClient(host, port, f"{prefix}{index:03d}", view_distance)
                         for index in range(count)]

    def start(self, timeout=60.0):
        for client in self._clients:
            client.start()
            time.sleep(0.05)                          # Stagger, so logins do not arrive as a burst.
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if all(c.reached_play.is_set() or c.failure for c in self._clients):
                break
            time.sleep(0.5)
        return self.report()

    def stop(self):
        for client in self._clients:
            client.stop()

    def report(self):
        playing = [c for c in self._clients if c.stage == "play"]
        failed = [c for c in self._clients if c.failure]
        return {"requested": len(self._clients), "in_play": len(playing), "failed": len(failed),
                "keep_alives": sum(c.keep_alives for c in self._clients),
                "first_error": failed[0].failure if failed else None,
                "stages": sorted({c.stage for c in self._clients})}
