#!/usr/bin/env python3
"""Rate-limited Minecraft status-protocol load for the network baseline workload.

Drives the real Netty pipeline through handshake, status and ping round-trips. It
stays in the status phase on purpose: the login and play phases are protocol-version
specific, and a half-correct play client would produce load that is not reproducible
across Minecraft versions.
"""
import socket
import threading
import time

CONNECT_TIMEOUT = 5.0
READ_TIMEOUT = 10.0
_MAX_RESPONSE_BYTES = 1 << 20


def write_varint(value):
    if value < 0:
        value += 1 << 32
    data = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        data.append(byte | (0x80 if value else 0x00))
        if not value:
            return bytes(data)


def read_varint(stream):
    result = shift = 0
    for _ in range(5):
        chunk = stream.read(1)
        if not chunk:
            raise ConnectionError("connection closed while reading a VarInt")
        result |= (chunk[0] & 0x7F) << shift
        if not chunk[0] & 0x80:
            return result
        shift += 7
    raise ValueError("VarInt longer than five bytes")


def _frame(packet_id, payload=b""):
    body = write_varint(packet_id) + payload
    return write_varint(len(body)) + body


def _read_packet(stream):
    length = read_varint(stream)
    if not 0 < length <= _MAX_RESPONSE_BYTES:
        raise ValueError(f"implausible packet length {length}")
    data = stream.read(length)
    if len(data) != length:
        raise ConnectionError("connection closed mid-packet")
    return data


def round_trip(host, port, protocol_version=-1):
    """One handshake + status + ping exchange. Returns (bytes_sent, bytes_received)."""
    address = host.encode()
    handshake = _frame(0x00, write_varint(protocol_version) + write_varint(len(address))
                       + address + port.to_bytes(2, "big") + write_varint(1))
    request = _frame(0x00)
    ping = _frame(0x01, (time.time_ns() // 1_000_000).to_bytes(8, "big", signed=True))
    with socket.create_connection((host, port), timeout=CONNECT_TIMEOUT) as connection:
        connection.settimeout(READ_TIMEOUT)
        connection.sendall(handshake + request)
        with connection.makefile("rb") as stream:
            status = _read_packet(stream)
            connection.sendall(ping)
            pong = _read_packet(stream)
    sent = len(handshake) + len(request) + len(ping)
    return sent, len(status) + len(pong)


class Load:
    """Concurrent status load held at a target rate until stopped."""

    def __init__(self, host, port, clients, rate_per_second):
        if clients < 1 or rate_per_second < 1:
            raise ValueError("clients and rate_per_second must be positive")
        self._host, self._port = host, port
        self._interval = clients / rate_per_second
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._threads = [threading.Thread(target=self._run, name=f"baseline-net-{index}", daemon=True)
                         for index in range(clients)]
        self.completed = self.failed = self.bytes_sent = self.bytes_received = 0
        self.latencies_ms = []
        self.first_error = None
        self.started_at = self.stopped_at = None

    def _run(self):
        while not self._stop.is_set():
            began = time.monotonic()
            try:
                sent, received = round_trip(self._host, self._port)
            except Exception as failure:            # Any client-side failure is load data.
                with self._lock:
                    self.failed += 1
                    if self.first_error is None:
                        self.first_error = f"{type(failure).__name__}: {failure}"
            else:
                elapsed = (time.monotonic() - began) * 1000.0
                with self._lock:
                    self.completed += 1
                    self.bytes_sent += sent
                    self.bytes_received += received
                    self.latencies_ms.append(elapsed)
            self._stop.wait(max(0.0, self._interval - (time.monotonic() - began)))

    def start(self):
        self.started_at = time.monotonic()
        for thread in self._threads:
            thread.start()

    def stop(self):
        self._stop.set()
        for thread in self._threads:
            thread.join(timeout=CONNECT_TIMEOUT + READ_TIMEOUT)
        self.stopped_at = time.monotonic()

    def report(self):
        """Client-observed throughput; server-side cost belongs to the JFR recording."""
        elapsed = (self.stopped_at or time.monotonic()) - self.started_at
        with self._lock:
            return {"available": True, "source": "baseline_network.Load (client side)",
                    "seconds": elapsed, "completed": self.completed, "failed": self.failed,
                    "round_trips_per_second": self.completed / elapsed if elapsed > 0 else 0.0,
                    "bytes_sent": self.bytes_sent, "bytes_received": self.bytes_received,
                    "bytes_per_second": (self.bytes_sent + self.bytes_received) / elapsed
                    if elapsed > 0 else 0.0,
                    "latencies_ms": list(self.latencies_ms), "first_error": self.first_error}
