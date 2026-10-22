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
import collections
import math
import queue
import random
import re
import socket
import struct
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

# Serverbound movement, from the same registration order as the keep-alive above (the
# serverbound builder has no bundle packet, so an id is its plain addPacket index).
# A stationary player loads its chunks once and then generates no further work: no chunk
# streaming, no entity tracking churn, no movement handling. Moving players are what make
# a workload resemble a server with people on it.
SERVERBOUND_MOVE_POS = 0x1E          # SERVERBOUND_MOVE_PLAYER_POS
SERVERBOUND_MOVE_POS_ROT = 0x1F      # SERVERBOUND_MOVE_PLAYER_POS_ROT
SERVERBOUND_ACCEPT_TELEPORT = 0x00   # SERVERBOUND_ACCEPT_TELEPORTATION
# Index 7 of GameProtocols.SERVERBOUND_TEMPLATE, counted the way that gives keep_alive 0x1C and
# move_player_pos 0x1E above. The body is one String: the command without its slash.
SERVERBOUND_CHAT_COMMAND = 0x07
# Index 121 of GameProtocols.CLIENTBOUND_TEMPLATE, counted the same way (keep_alive is 44 = 0x2C).
CLIENTBOUND_SYSTEM_CHAT = 0x79
# Chunk delivery, same counting (bundle at index 0, so template index + 1):
# chunk_batch_finished is template index 10, level_chunk_with_light 44, forget_level_chunk 36.
CLIENTBOUND_CHUNK_BATCH_FINISHED = 0x0B
CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT = 0x2D
CLIENTBOUND_FORGET_LEVEL_CHUNK = 0x25
# Serverbound template index 11: chunk_batch_received(float desiredChunksPerTick).
SERVERBOUND_CHUNK_BATCH_RECEIVED = 0x0B

# The server's authoritative position. Without reading this a client invents coordinates,
# the server rejects every one of them and snaps the player back, and the only thing that
# survives is the rotation — which looks exactly like a bot that only turns its head.
CLIENTBOUND_PLAYER_POSITION = 0x48


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


class _LockedSocket:
    """A socket whose sends are serialised, so a mover thread and the reader can share it."""

    def __init__(self, raw):
        self._raw = raw
        self._lock = threading.Lock()

    def sendall(self, data):
        with self._lock:
            self._raw.sendall(data)

    def __getattr__(self, name):
        return getattr(self._raw, name)


class HeadlessClient(threading.Thread):
    """One connected player. Reaches the play stage and stays there answering keep-alives.

    View distance defaults to 2, the same as the server's own ClientInformation default.
    A load-generating client never renders anything, and at distance 10 the chunk traffic
    is heavy enough that decompressing every frame in Python falls behind the keep-alive
    deadline and the server times the client out.
    """

    def __init__(self, host, port, name, view_distance=2, move=True, origin=None, roam=None,
                 ack_chunk_batches=False, timer_moves=False):
        super().__init__(name=f"client-{name}", daemon=True)
        self._host, self._port, self._name = host, port, name
        self._view_distance = view_distance
        self._move = move
        self._origin = origin
        # How far a client may wander from where it spawned, or None to explore without limit.
        # A workload measuring entities wants its players beside the entities: clients that fly
        # outward generate terrain continuously, and terrain generation then dominates the
        # profile of whatever the workload was actually built to measure.
        self._roam = roam
        self._home = None
        self._random = random.Random(name)          # Deterministic per client, varied across them.
        self.moves = 0
        self._halt = threading.Event()
        self.stage = "new"
        self.failure = None
        self.failure_trace = []
        self.keep_alives = 0
        self._next_move = float("inf")
        # Move on a clock instead of when a packet happens to arrive. A client that only moves
        # after reading something stops moving the moment the server goes quiet, and a server that
        # holds its outgoing packets until the player sends one (anti-cheat "blink" protection)
        # then never sends again: both ends wait until one times out.
        self._timer_moves = timer_moves
        self._x, self._y, self._z = 0.0, 80.0, 0.0
        self._angle = self._random.uniform(0.0, math.tau)
        self._flying = self._random.random() < 0.5
        self._cruise = self._random.uniform(self.FLOOR, self.CEILING)
        self._synced = False
        self._corrected = False
        self.syncs = 0
        self.relative_syncs = 0
        self.non_finite_syncs = 0
        self.last_relatives = 0
        self._last_confirmed_teleport = None
        self.duplicate_teleports = 0
        # The last few things this client claimed or was told, so a disconnect can be explained.
        self.trace = collections.deque(maxlen=6)
        self.corrections = 0
        self.reached_play = threading.Event()
        self._commands = queue.Queue()
        self.commands_sent = 0
        # Text of the system messages received (chat components flattened to their strings).
        self.messages = []
        # (monotonic time, chunk x, chunk z) of every level_chunk_with_light received.
        self.chunks = []
        self.forgotten = 0
        # A real client acknowledges each chunk batch; the server holds further batches until it
        # does. Off by default so load-generating swarms keep the workload they were measured with.
        self._ack_chunk_batches = ack_chunk_batches
        self.batches = 0

    def command(self, text):
        """Queue a command (without the slash); sent once the client is in play."""
        self._commands.put(text.lstrip("/"))

    def _sync(self, connection, payload, threshold):
        """Adopt the server's position and confirm the teleport.

        Payload is a VarInt teleport id, then PositionMoveRotation: position as three
        doubles, delta movement as three more, then yaw and pitch. Confirming is not
        optional — an unconfirmed teleport leaves the server re-sending it and ignoring
        everything the client claims about where it is.
        """
        view = _Reader(payload)
        teleport_id = read_varint(view)
        body = view.read(24)
        if len(body) == 24:
            x, y, z = struct.unpack(">ddd", body)
            # Delta movement (3 doubles), yaw and pitch (2 floats), then the Relative bit set (an
            # int): X=1, Y=2, Z=4. A relative axis is an offset from where the client stands, not a
            # place. Anti-cheats send zero-offset relative teleports as position feedback, and
            # adopting that 0.0 as absolute puts the client at the origin.
            rest = view.read(24 + 8 + 4)
            relatives = struct.unpack(">i", rest[-4:])[0] if len(rest) == 36 else 0
            self.last_relatives = relatives
            self.trace.append(("sync", teleport_id, x, y, z, relatives))
            if relatives & 0x7:
                self.relative_syncs += 1
            x = self._x + x if relatives & 0x1 else x
            y = self._y + y if relatives & 0x2 else y
            z = self._z + z if relatives & 0x4 else z
            if all(math.isfinite(value) for value in (x, y, z)):
                self._x, self._y, self._z = x, y, z
            else:
                self.non_finite_syncs += 1       # Adopting NaN makes the server kick the client.
        # The server resends an unconfirmed teleport once a second with the same id. A client that
        # is behind (a loaded load-generator host, a slow link) then holds several copies when it
        # catches up, and confirming each one gets the second kicked with "Invalid move player
        # packet": a confirmation with no teleport awaiting. Ids only ever increase, so a repeat
        # of the id just confirmed is a resend and is not confirmed again.
        if teleport_id == self._last_confirmed_teleport:
            self.duplicate_teleports += 1
            return
        self._last_confirmed_teleport = teleport_id
        connection.sendall(_frame(SERVERBOUND_ACCEPT_TELEPORT, write_varint(teleport_id), threshold))
        if self._synced:
            # Not the login teleport: the server disagreed with where we claimed to be and put
            # us back. Walking the same heading into the same hillside is what turned two of ten
            # clients into 2757 of one run's 4354 "moved wrongly" warnings.
            self._corrected = True
            self.corrections += 1
        self._synced = True
        self.syncs += 1

    def _step(self, connection, threshold):
        """Walk or fly a short distance along a wandering heading.

        Half the clients fly and half walk, and each turns by a small random amount every
        step, so the swarm spreads out and keeps loading new chunks instead of orbiting one
        point.

        "Moved wrongly" is not a speed limit. The server runs collision physics on the move
        and compares where we claimed to be against where physics put us; a claim it cannot
        reach by more than movedWronglyThreshold is refused and teleported back. This client
        has no heightmap, so it avoids the refusal three ways: it steps slowly enough to stay
        inside vanilla speeds, it turns away when the server corrects it rather than pushing
        into the same obstacle, and a flier climbs when corrected until it is over the terrain
        instead of inside it.
        """
        if self._home is None:
            self._home = (self._x, self._z)     # First step after the login teleport settles.
        if self._corrected:
            self._corrected = False
            self._angle = self._random.uniform(0.0, math.tau)
        elif self._roam is not None:
            # Turn back toward home once past the leash, rather than bouncing off an invisible
            # wall: a client pinned against a boundary stops loading new chunks entirely, which
            # is its own distortion.
            drift = math.hypot(self._x - self._home[0], self._z - self._home[1])
            if drift > self._roam:
                self._angle = math.atan2(self._home[1] - self._z, self._home[0] - self._x)
            if self._flying:
                # We were inside something, so terrain here is higher than we assumed. Raise
                # the cruise altitude; the climb itself is rate-limited below.
                self._cruise = min(self.CEILING, self._cruise + self.CLIMB_ON_CORRECTION)
        self._angle += self._random.uniform(-0.6, 0.6)
        # Vanilla walking is about 4.3 blocks per second and creative flight about 10.9; at
        # four steps a second these stay under both, because a longer step is likelier to
        # cross into a hillside and produces a bigger discrepancy when it does.
        speed = 2.0 if self._flying else 0.85
        self._x += math.cos(self._angle) * speed
        self._z += math.sin(self._angle) * speed
        if self._flying:
            # Climb toward cruise rather than snapping to it. Clients spawn near the surface,
            # and a jump of a hundred blocks in one step is refused exactly like walking into a
            # hill. Altitude itself costs the workload nothing: chunks load by horizontal
            # position, so cruising above the terrain loses no chunk traffic.
            wanted = self._cruise + self._random.uniform(-1.0, 1.0) - self._y
            self._y += max(-self.CLIMB_RATE, min(self.CLIMB_RATE, wanted))
        yaw = math.degrees(self._angle) % 360.0 - 180.0
        # PosRot: three doubles, two floats, then a packed flags byte whose low bit is
        # onGround (ServerboundMovePlayerPacket#packFlags).
        payload = (struct.pack(">ddd", self._x, self._y, self._z)
                   + struct.pack(">ff", yaw, 0.0)
                   # onGround: a flier is never on the ground, a walker always claims to be
                   # and lets the server correct the height it disagrees with.
                   + bytes([0x00 if self._flying else 0x01]))
        connection.sendall(_frame(SERVERBOUND_MOVE_POS_ROT, payload, threshold))
        self.trace.append(("move", round(self._x, 2), round(self._y, 2), round(self._z, 2), round(yaw, 1)))
        self.moves += 1
        self._next_move = time.monotonic() + 0.25   # Four updates a second, like a real client.

    def _mover(self, connection, threshold):
        """Send a move four times a second for as long as the session is in play."""
        time.sleep(2.0)
        while not self._halt.is_set() and self.stage == "play":
            if self._move and self._synced:
                try:
                    self._step(connection, threshold)
                except OSError:
                    return                      # The reader sees the same failure and records it.
            time.sleep(0.25)

    FLOOR = 180.0                 # Above normal terrain; mountains reach past 200.
    CEILING = 250.0               # Below the build limit, with room to climb.
    CLIMB_ON_CORRECTION = 12.0    # One correction should clear a hillside, not creep over it.
    CLIMB_RATE = 2.0              # Per step, so eight blocks a second: inside creative flight.

    def stop(self):
        self._halt.set()

    def run(self):
        try:
            self._session()
        except Exception as problem:                  # A client failure is data, not a crash.
            self.failure = f"{type(problem).__name__}: {problem}"
            self.stage = "failed"
            self.failure_trace = list(self.trace)

    def _session(self):
        address = self._host.encode()
        name = self._name.encode()
        threshold = -1
        with socket.create_connection((self._host, self._port), timeout=CONNECT_TIMEOUT) as raw_connection:
            connection = _LockedSocket(raw_connection)
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
                            self._next_move = time.monotonic() + 2.0
                            self.reached_play.set()
                            if self._timer_moves:
                                threading.Thread(target=self._mover, args=(connection, threshold),
                                                 name=f"mover-{self._name}", daemon=True).start()
                        continue
                    # Play: answer only the keep-alive, by its own id. Everything else is
                    # read and discarded, which is all a load-generating client needs to do.
                    if packet_id == CLIENTBOUND_PLAYER_POSITION:
                        self._sync(connection, payload, threshold)
                        continue
                    if packet_id == CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT:
                        self.chunks.append((time.monotonic(),) + struct.unpack(">ii", payload[:8]))
                        continue
                    if packet_id == CLIENTBOUND_FORGET_LEVEL_CHUNK:
                        self.forgotten += 1
                        continue
                    if packet_id == CLIENTBOUND_CHUNK_BATCH_FINISHED:
                        self.batches += 1
                        if self._ack_chunk_batches:
                            # Vanilla clients ask for up to 64 chunks a tick when they keep up.
                            connection.sendall(_frame(SERVERBOUND_CHUNK_BATCH_RECEIVED, struct.pack(">f", 64.0), threshold))
                        continue
                    if packet_id == CLIENTBOUND_SYSTEM_CHAT:
                        # A network-NBT component: keep the readable strings, in order.
                        text = " ".join(part.decode("utf-8", "replace")
                                        for part in re.findall(rb"[\x20-\x7e\xc2-\xf4][\x20-\x7e\x80-\xbf]{2,}", payload))
                        self.messages.append(text)
                    if (self._move and not self._timer_moves and self._synced
                            and time.monotonic() >= self._next_move):
                        self._step(connection, threshold)
                    while self._synced:
                        try:
                            text = self._commands.get_nowait()
                        except queue.Empty:
                            break
                        body = text.encode("utf-8")
                        connection.sendall(_frame(SERVERBOUND_CHAT_COMMAND, write_varint(len(body)) + body,
                                                  threshold))
                        self.commands_sent += 1
                    if packet_id == PLAY_KEEP_ALIVE_CLIENTBOUND:
                        # The reply carries exactly the eight-byte id and nothing else; echoing
                        # the whole clientbound payload is rejected as "larger than I expected".
                        connection.sendall(_frame(PLAY_KEEP_ALIVE_SERVERBOUND, payload[:8], threshold))
                        self.keep_alives += 1


class ClientSwarm:
    """A group of headless clients, reported on as a whole."""

    def __init__(self, host, port, count, prefix="Baseline", view_distance=2, move=True, roam=None,
                 first_index=0, timer_moves=False):
        self._clients = [HeadlessClient(host, port, f"{prefix}{first_index + index:03d}", view_distance,
                                        move, None, roam, timer_moves=timer_moves)
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
                "moves": sum(c.moves for c in self._clients),
                "flying": sum(1 for c in self._clients if c._flying),
                "corrections": sum(c.corrections for c in self._clients),
                "syncs": sum(c.syncs for c in self._clients),
                "relative_syncs": sum(c.relative_syncs for c in self._clients),
                "non_finite_syncs": sum(c.non_finite_syncs for c in self._clients),
                "duplicate_teleports": sum(c.duplicate_teleports for c in self._clients),
                "cpu_seconds": round(time.process_time(), 1),
                "first_error": failed[0].failure if failed else None,
                "failure_reasons": dict(collections.Counter(c.failure for c in failed)),
                "failure_traces": {c._name: c.failure_trace for c in failed[:4]},
                "stages": sorted({c.stage for c in self._clients})}


# Report fields that add up across processes.
_SUMMED = ("requested", "in_play", "failed", "keep_alives", "moves", "flying", "corrections", "syncs",
           "relative_syncs", "non_finite_syncs", "duplicate_teleports", "cpu_seconds")


def merge_reports(reports):
    """One swarm report from the reports of several processes' swarms."""
    merged = {key: round(sum(report.get(key, 0) for report in reports), 1) if key == "cpu_seconds"
              else sum(report.get(key, 0) for report in reports) for key in _SUMMED}
    merged["first_error"] = next((r["first_error"] for r in reports if r.get("first_error")), None)
    reasons = collections.Counter()
    for report in reports:
        reasons.update(report.get("failure_reasons", {}))
    merged["failure_reasons"] = dict(reasons)
    traces = {}
    for report in reports:
        traces.update(report.get("failure_traces", {}))
    merged["failure_traces"] = dict(list(traces.items())[:4])
    merged["stages"] = sorted({stage for report in reports for stage in report.get("stages", [])})
    merged["processes"] = len(reports)
    return merged


def shard_sizes(count, processes):
    """Client counts per process, as even as possible, none empty."""
    processes = max(1, min(processes, count))
    base, extra = divmod(count, processes)
    return [base + (1 if index < extra else 0) for index in range(processes)]


def _shard_main(connection, host, port, first_index, count, prefix, roam, view_distance, move,
                timer_moves):
    swarm = ClientSwarm(host, port, count, prefix=prefix, view_distance=view_distance, move=move,
                        roam=roam, first_index=first_index, timer_moves=timer_moves)
    while True:
        message = connection.recv()
        if message[0] == "start":
            connection.send(swarm.start(timeout=message[1]))
        elif message[0] == "report":
            connection.send(swarm.report())
        else:
            swarm.stop()
            connection.send(swarm.report())
            return


class ShardedSwarm:
    """The same clients as ClientSwarm, spread over several processes.

    Every client is a Python thread, and decoding packets holds the interpreter lock, so one
    process runs all of its clients on about one core. A slow host then answers keep-alives and
    teleport confirmations late, which the server punishes with "Timed out" and "Invalid move
    player packet" kicks that say nothing about the server. Processes remove that ceiling.
    """

    def __init__(self, host, port, count, prefix="Baseline", view_distance=2, move=True, roam=None,
                 processes=2, timer_moves=False):
        import multiprocessing
        context = multiprocessing.get_context("spawn")
        self._workers = []
        first = 0
        for size in shard_sizes(count, processes):
            parent, child = context.Pipe()
            process = context.Process(target=_shard_main, args=(
                child, host, port, first, size, prefix, roam, view_distance, move, timer_moves),
                daemon=True)
            self._workers.append((process, parent))
            first += size
        self._started = False

    def _ask(self, message, wait):
        for _, pipe in self._workers:
            pipe.send(message)
        return [pipe.recv() for _, pipe in self._workers] if wait else None

    def start(self, timeout=60.0):
        for process, _ in self._workers:
            process.start()
        self._started = True
        return merge_reports(self._ask(("start", timeout), True))

    def report(self):
        return merge_reports(self._ask(("report",), True))

    def stop(self):
        if not self._started:
            return
        try:
            self._ask(("stop",), True)
        except (EOFError, OSError):
            pass
        for process, _ in self._workers:
            process.join(10)
            if process.is_alive():
                process.terminate()
