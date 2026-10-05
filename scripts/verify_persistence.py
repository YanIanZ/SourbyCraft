#!/usr/bin/env python3
"""Validate that a SourbyCraft server does not lose or corrupt world data.

Transition §16 (T10) requires validating clean shutdown, restart, chunk save integrity,
player data, entity data, region files and world metadata. Nothing checked any of it: the
other verify scripts check the build, not the data.

The shape of the test is deliberately end-to-end. Persistence fails between processes --
state that a running server reports correctly can still be absent after a restart, and a
region file can be written in a form the next boot silently drops. So this writes known
state through the console, stops the server, inspects the files on disk directly, starts a
second server over the same directory, and reads the state back.

Structural file checks and round-trip checks answer different questions and both are kept:
a region file can be structurally valid and hold the wrong chunk, and a chunk can read back
correctly while its region file has a malformed header that a later compaction trips over.

Usage:
    python3 scripts/verify_persistence.py build/libs/SourbyCraft-slim.jar \\
        --output build/persistence --cache-from build/baselines/idle
"""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_baseline import Server, seed_cache, jdk_tools  # noqa: E402
from baseline_client import ClientSwarm, HeadlessClient  # noqa: E402

READY_TIMEOUT = 300
SHUTDOWN_TIMEOUT = 900

# A block that never occurs naturally, so a survivor cannot be terrain.
PROBE_BLOCK = "minecraft:diamond_block"
WITNESS_BLOCK = "minecraft:emerald_block"
# Likewise an entity that does not spawn on its own, so the count is unambiguous.
PROBE_ENTITY = "minecraft:armor_stand"
PROBE_ENTITIES = 24
# Far from spawn, so the probe sits in its own region file rather than the spawn one.
SITE = (3008, 100, -3008)
PROBE_SIDE = 8                      # 8x8 = 64 blocks, one layer
PROBE_BLOCKS = PROBE_SIDE * PROBE_SIDE
PROBE_TIME = 6000
PROBE_GAMERULE = ("max_entity_cramming", "17")   # snake_case: MC 26.2 renamed the ids
# A connected player, so player data is round-tripped rather than only checked for shape.
PROBE_PLAYER = "PersistProbe"
PROBE_DIAMONDS = 37
PROBE_XP_LEVEL = 13
# Standing on the probe floor, inside the force-loaded chunks.
PROBE_PLAYER_POS = (SITE[0] + 2.5, SITE[1], SITE[2] + 2.5)
JOIN_TIMEOUT = 60
# Paper's own log lines, not the broadcast join/quit messages: SourbyCraft replaces those with
# localized text, so matching "joined the game" never fires.
JOINED = re.compile(re.escape(PROBE_PLAYER) + r"\[[^\]]*\] logged in with entity id")
LEFT = re.compile(re.escape(PROBE_PLAYER) + r" lost connection")


class Check:
    """One named result, so a report can distinguish a failure from an unrun check."""

    def __init__(self):
        self.results = []

    def record(self, name, ok, detail=""):
        self.results.append({"check": name, "ok": bool(ok), "detail": detail})
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""),
              flush=True)
        return ok

    @property
    def failed(self):
        return [r for r in self.results if not r["ok"]]


def prepare(directory, port):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
        "level-seed=440044\nspawn-protection=0\nenable-query=false\nenable-rcon=false\n"
        # The point of this tool is that writes reach disk, so buffering them away would
        # measure the wrong thing.
        "sync-chunk-writes=true\n")
    config = directory / "sourbycraft_config"
    config.mkdir(exist_ok=True)
    (config / "sourbycraft_global_config.toml").write_text(
        "# Isolated persistence fixture; no external plugin or version changes.\n"
        "[viaversion]\nauto-provision=false\n"
        "[misc.auto_update]\nenabled=false\n")


def boot(jar, directory, heap_mib):
    # Also asserts Java 25, which is the runtime the packaged server targets.
    java, _jcmd, _jfr, _version = jdk_tools()
    command = [str(java), f"-Xms{heap_mib}M", f"-Xmx{heap_mib}M",
               "-jar", str(Path(jar).resolve()), "--nogui"]
    server = Server(command, directory)
    server.await_ready(READY_TIMEOUT)
    server.console_ready_seconds = await_console(server)
    return server


def await_console(server, timeout=60):
    """Wait until a console command actually runs, and return how long that took.

    "Done (" is not that moment on this engine. Rebooting a world stored through Aurora World
    Fabric, the first console commands sent right after "Done" failed with a
    NullPointerException in Commands.executeCommandInContext (the console CommandSourceStack had
    no level) for about two seconds; a probe that sends its setup at "Done" then measures
    nothing. Tracked in TODO.md as its own finding.
    """
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        before = len(server.text)
        server.send("time query gametime")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            produced = server.text[before:]
            if "game time is" in produced:
                return round(time.monotonic() - started, 1)
            if "Command exception" in produced or "unexpected error" in produced:
                break
            time.sleep(0.2)
        time.sleep(0.5)
    raise RuntimeError(f"console commands still failing {timeout}s after Done")


def stop_cleanly(server, checks, label):
    """Stop through the console, not a signal: a kill would prove nothing about saving."""
    server.send("stop")
    deadline = time.monotonic() + SHUTDOWN_TIMEOUT
    while time.monotonic() < deadline:
        if server.process.poll() is not None:
            break
        time.sleep(1)
    else:
        server.close()
        checks.record(f"{label}: clean shutdown", False,
                      f"did not exit within {SHUTDOWN_TIMEOUT}s")
        return

    code = server.process.returncode
    text = server.text
    checks.record(f"{label}: clean shutdown", code == 0, f"exit code {code}")
    checks.record(f"{label}: saved before exit",
                  "Saving worlds" in text or "Saving chunks" in text or "All chunks are saved"
                  in text, "shutdown log mentions saving")
    for marker in ("DataFixer", "Exception in thread", "Failed to save", "Watchdog"):
        if marker in text:
            checks.record(f"{label}: no {marker} during shutdown", False, "see server.log")


def run(server, command, expect, timeout=30):
    """Send a command and return only the console text it produced.

    Sliced by log length, not by a marker line. An earlier version said a marker first and
    read everything after it, which is racy in the one direction that matters: ``say`` is
    dispatched on the global tick while a command answers on a region thread, so the marker
    can land *after* the output it was supposed to introduce. That read 24 surviving entities
    as 0.
    """
    before = len(server.text)
    server.send(command)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        produced = ANSI.sub("", server.text[before:])
        if re.search(expect, produced) or any(f in produced for f in COMMAND_FAILURES):
            return produced
        time.sleep(0.5)
    return ANSI.sub("", server.text[before:])


# The console colours NBT values ("[\x1b[38;5;3m3010.5\x1b[38;5;9md\x1b[0m, ...]"), which splits
# every number from its suffix; output is matched with the colour codes removed.
ANSI = re.compile(r"\x1b\[[0-9;]*m")

COMMAND_FAILURES = ("That position is not loaded", "Unknown or incomplete command",
                    "Incorrect argument for command", "Expected ")


def game_time(server):
    """The world's monotonic tick counter, or -1 if the command did not answer.

    Anchored on "The game time is <n> tick(s)" rather than any number, because the appended console text
    starts with a log timestamp and a loose pattern reads the hour out of it.
    """
    match = re.search(r"game time is (\d+)",
                      run(server, "time query gametime", r"game time is \d+"))
    return int(match.group(1)) if match else -1


def write_state(server, checks):
    x, y, z = SITE
    server.send(f"forceload add {x} {z} {x + PROBE_SIDE} {z + PROBE_SIDE}")
    # A solid floor first, so entities land on something deterministic instead of falling.
    # Retried until the chunks exist: on a fresh world the force-loaded chunks are still being
    # generated for a few seconds, and a fill sent then answers "That position is not loaded"
    # and writes nothing -- which a crash test then reported as 64 lost blocks.
    fill = (f"fill {x} {y - 1} {z} {x + PROBE_SIDE - 1} {y - 1} {z + PROBE_SIDE - 1} "
            f"{PROBE_BLOCK}")
    produced = ""
    for _ in range(20):
        produced = run(server, fill, r"Successfully filled|No blocks were filled|not loaded")
        if "not loaded" not in produced:
            break
        time.sleep(3)
    written = re.search(r"Successfully filled (\d+) block", produced)
    if not checks.record("write: probe floor placed", written is not None and int(written.group(1)) == PROBE_BLOCKS,
                         f"{written.group(1) if written else 0} of {PROBE_BLOCKS} blocks"):
        raise RuntimeError("probe floor was not written; nothing later in this run would mean anything")
    for i in range(PROBE_ENTITIES):
        server.send(f"summon {PROBE_ENTITY} {x + (i % PROBE_SIDE)} {y} "
                    f"{z + (i // PROBE_SIDE)} {{Tags:[\"persist_probe\"],NoGravity:1b}}")
    time.sleep(2)
    server.send(f"time set {PROBE_TIME}")
    server.send(f"gamerule {PROBE_GAMERULE[0]} {PROBE_GAMERULE[1]}")
    time.sleep(1.5)
    server.send("save-all flush")
    time.sleep(5)
    recorded = game_time(server)
    checks.record("write: state written and flushed", True,
                  f"{PROBE_BLOCKS} blocks, {PROBE_ENTITIES} entities, gamerule, "
                  f"game time {recorded}")
    return {"gametime": recorded}


def region_files(world):
    """Every region file under the world, wherever the layout puts them.

    This build stores the overworld at world/dimensions/minecraft/overworld/region, not the
    legacy world/region. Globbing for the directory name rather than hard-coding a path keeps
    this working across both, and a hard-coded path is what reported "0 .mca" on a world that
    had five.
    """
    return sorted(world.glob("**/region/*.mca"))


def check_region_integrity(world, checks, label=""):
    """Validate the .mca header against the file, without a full chunk parser.

    A region file is a 4 KiB-sector container: 1024 location entries of 3-byte sector offset
    plus 1-byte sector count, then 1024 timestamps, then the chunk data. An offset pointing
    past the end, or into the header, is corruption the next boot would hit.
    """
    files = region_files(world)
    if not checks.record(f"{label + ': ' if label else ''}region: files exist", bool(files), f"{len(files)} .mca"):
        return
    problems = []
    unaligned = []
    populated = 0
    for path in files:
        size = path.stat().st_size
        if size == 0:
            continue                      # An empty region file is legal: nothing written yet.
        if size < 8192:
            problems.append(f"{path.name}: {size} bytes cannot hold the 8 KiB header")
            continue
        if size % 4096:
            # Informational, not a fault. Files were observed unaligned straight after one
            # clean shutdown and aligned after the next, while every chunk in them read back
            # correctly -- so a partial trailing sector is padding the writer had not gotten
            # to, and calling it corruption would be a false alarm about working data.
            unaligned.append(f"{path.name} (+{size % 4096}B)")
        header = path.read_bytes()[:4096]
        for index in range(1024):
            entry = header[index * 4:index * 4 + 4]
            offset = int.from_bytes(entry[:3], "big")
            sectors = entry[3]
            if offset == 0 and sectors == 0:
                continue                  # Chunk absent, which is normal.
            populated += 1
            if offset < 2:
                problems.append(f"{path.name}#{index}: offset {offset} overlaps the header")
            elif (offset + sectors) * 4096 > size:
                overrun = (offset + sectors) * 4096 - size
                if overrun < 4096:
                    # One unpadded tail sector. Observed in every unused nether/end region
                    # file while the overworld's are exact, and the server reads them back
                    # without complaint -- the region reader tolerates a short final sector.
                    # Real truncation loses whole sectors, so that still fails below.
                    unaligned.append(f"{path.name}#{index} (-{overrun}B)")
                else:
                    problems.append(f"{path.name}#{index}: references {overrun} bytes past "
                                    f"the end of a {size}-byte file")
    detail = "; ".join(problems[:3]) if problems else f"{populated} chunks referenced"
    if unaligned and not problems:
        detail += f"; {len(unaligned)} unpadded tail sector(s), under 4 KiB (benign)"
    checks.record(f"{label + ': ' if label else ''}region: every referenced chunk lies within its file", not problems, detail)
    checks.record(f"{label + ': ' if label else ''}region: at least one chunk stored", populated > 0, f"{populated} chunks")


def check_level_dat(world, checks, label=""):
    """level.dat is gzipped NBT whose root is a TAG_Compound; anything else will not load."""
    path = world / "level.dat"
    if not checks.record(f"{label + ': ' if label else ''}world metadata: level.dat exists", path.is_file()):
        return
    try:
        raw = gzip.decompress(path.read_bytes())
    except OSError as broken:
        checks.record(f"{label + ': ' if label else ''}world metadata: level.dat is valid gzip", False, str(broken))
        return
    checks.record(f"{label + ': ' if label else ''}world metadata: level.dat is valid gzip", True, f"{len(raw)} bytes inflated")
    checks.record(f"{label + ': ' if label else ''}world metadata: NBT root is a compound", raw[:1] == b"\x0a",
                  f"first tag byte 0x{raw[0]:02x}")


def offline_uuid(name):
    """The UUID an offline-mode server gives a name: Java's nameUUIDFromBytes("OfflinePlayer:" + name)."""
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30          # version 3
    digest[8] = (digest[8] & 0x3F) | 0x80          # IETF variant
    return str(uuid.UUID(bytes=bytes(digest)))


def read_nbt(raw):
    """Parse an uncompressed NBT root compound into plain Python values.

    Only what player files contain is needed, but every tag type is handled so an unexpected
    one is a parse result rather than a crash halfway through someone's inventory.
    """
    view = memoryview(raw)
    position = 0

    def take(fmt):
        nonlocal position
        value = struct.unpack_from(fmt, view, position)
        position += struct.calcsize(fmt)
        return value[0]

    def string():
        nonlocal position
        length = take(">H")
        value = bytes(view[position:position + length]).decode("utf-8", "replace")
        position += length
        return value

    def payload(tag):
        if tag == 1: return take(">b")
        if tag == 2: return take(">h")
        if tag == 3: return take(">i")
        if tag == 4: return take(">q")
        if tag == 5: return take(">f")
        if tag == 6: return take(">d")
        if tag in (7, 11, 12):
            size = take(">i")
            return [take({7: ">b", 11: ">i", 12: ">q"}[tag]) for _ in range(size)]
        if tag == 8: return string()
        if tag == 9:
            inner, size = take(">b"), take(">i")
            return [payload(inner) for _ in range(size)]
        if tag == 10:
            result = {}
            while True:
                inner = take(">b")
                if inner == 0:
                    return result
                key = string()
                result[key] = payload(inner)
        raise ValueError(f"unknown NBT tag {tag}")

    if take(">b") != 10:
        raise ValueError("root is not a compound")
    string()
    return payload(10)


def player_file(world, name):
    """The player's .dat wherever this layout keeps it (playerdata/ or players/data/)."""
    matches = sorted(world.glob(f"**/{offline_uuid(name)}.dat"))
    return matches[0] if matches else None


def diamonds_in(inventory):
    return sum(item.get("count", item.get("Count", 0)) for item in inventory
               if item.get("id") == "minecraft:diamond")


def check_player_data(world, checks, expected=None, label="player data"):
    """Every player file must be gzipped NBT; the probe player's must hold what was written.

    ``expected`` is {"diamonds", "xp_level", "pos"} for the probe client, or None when no client
    has connected yet and only the structural check applies.
    """
    files = sorted(world.glob("**/playerdata/*.dat")) + sorted(world.glob("**/players/data/*.dat"))
    bad = []
    for path in files:
        try:
            if gzip.decompress(path.read_bytes())[:1] != b"\x0a":
                bad.append(path.name)
        except OSError:
            bad.append(path.name)
    checks.record(f"{label}: every .dat is gzipped NBT", not bad,
                  ", ".join(bad[:3]) or f"{len(files)} file(s)")
    if expected is None:
        return
    path = player_file(world, PROBE_PLAYER)
    if not checks.record(f"{label}: probe player file exists", path is not None,
                         f"{offline_uuid(PROBE_PLAYER)}.dat"):
        return
    data = read_nbt(gzip.decompress(path.read_bytes()))
    diamonds = diamonds_in(data.get("Inventory", []))
    checks.record(f"{label}: inventory on disk", diamonds == expected["diamonds"],
                  f"{diamonds} diamonds, expected {expected['diamonds']}")
    checks.record(f"{label}: experience level on disk", data.get("XpLevel") == expected["xp_level"],
                  f"level {data.get('XpLevel')}, expected {expected['xp_level']}")
    pos = data.get("Pos", [])
    near = len(pos) == 3 and all(abs(a - b) < 1.5 for a, b in zip(pos, expected["pos"]))
    checks.record(f"{label}: position on disk", near,
                  f"{[round(p, 2) for p in pos]}, expected about {list(expected['pos'])}")


def verify_state(server, checks, before_shutdown, label="second boot", present=PROBE_BLOCK,
                 replacement=WITNESS_BLOCK):
    """Read back what the previous boot wrote, then overwrite it for the next boot to read.

    The floor alternates between two blocks, so every restart checks the save made by the boot
    before it, not only the first one.
    """
    x, y, z = SITE

    # Forceload persists, but the chunks are not back the instant "Done" is printed, and the
    # first version of this fired its fill into "That position is not loaded" and recorded a
    # server fault. Re-assert the ticket and let the region come up before asking anything.
    server.send(f"forceload add {x} {z} {x + PROBE_SIDE} {z + PROBE_SIDE}")
    fill = (f"fill {x} {y - 1} {z} {x + PROBE_SIDE - 1} {y - 1} {z + PROBE_SIDE - 1} "
            f"{replacement} replace {present}")
    produced = ""
    for attempt in range(10):
        produced = run(server, fill, r"Successfully filled|No blocks were filled")
        if "That position is not loaded" not in produced:
            break
        time.sleep(3)
    filled = re.search(r"Successfully filled (\d+) block", produced)
    count = int(filled.group(1)) if filled else 0
    detail = f"{count} of {PROBE_BLOCKS} blocks"
    if "That position is not loaded" in produced:
        detail += " (chunks never loaded; inconclusive, not a persistence result)"
    checks.record(f"{label}: blocks survived the restart",
                  count == PROBE_BLOCKS, detail)

    listed = re.search(r"Total Ticking: (\d+), Total Non-Ticking: (\d+)",
                       run(server, "paper entity list minecraft:armor_stand minecraft:overworld",
                           r"Total Ticking: \d+"))
    total = int(listed.group(1)) + int(listed.group(2)) if listed else 0
    checks.record(f"{label}: entities survived the restart",
                  total == PROBE_ENTITIES, f"{total} of {PROBE_ENTITIES} armor stands")

    # Patterns here are anchored on the command's own wording. Matching a bare \d+ read the
    # "19" out of the log timestamp "[19:33:11 INFO]" and reported a game time of 19.
    # "time query daytime" does not exist in 26.2 either: the argument is a timeline id.
    gametime = game_time(server)
    checks.record(f"{label}: game time survived and advanced",
                  gametime >= before_shutdown["gametime"] > 0,
                  f"{gametime} now, {before_shutdown['gametime']} before shutdown")

    rule = re.search(r"is currently set to: (\S+)",
                     run(server, f"gamerule {PROBE_GAMERULE[0]}", r"currently set to"))
    value = rule.group(1) if rule else "?"
    checks.record(f"{label}: gamerule survived", value == PROBE_GAMERULE[1],
                  f"{PROBE_GAMERULE[0]} = {value}, wrote {PROBE_GAMERULE[1]}")
    return {"gametime": gametime}


def await_log(server, pattern, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if re.search(pattern, server.text):
            return True
        time.sleep(0.5)
    return False


def join_probe(server, port, checks, label):
    """Connect the probe player and wait until the server has it in the world."""
    before = len(server.text)
    client = HeadlessClient("127.0.0.1", port, PROBE_PLAYER, move=False)
    client.start()
    deadline = time.monotonic() + JOIN_TIMEOUT
    joined = False
    while time.monotonic() < deadline and not client.failure:
        if JOINED.search(server.text[before:]):
            joined = True
            break
        time.sleep(0.5)
    checks.record(f"{label}: probe player joined", joined, client.failure or client.stage)
    return client if joined else None


def leave_probe(server, client, checks, label):
    """Disconnect and wait for the server to finish the quit, which is where it saves the player."""
    before = len(server.text)
    client.stop()
    client.join(timeout=30)
    left = False
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if LEFT.search(server.text[before:]):
            left = True
            break
        time.sleep(0.5)
    checks.record(f"{label}: probe player left (saved on quit)", left)


def write_player(server, port, checks):
    client = join_probe(server, port, checks, "first boot")
    if client is None:
        return None
    x, y, z = PROBE_PLAYER_POS
    run(server, f"give {PROBE_PLAYER} minecraft:diamond {PROBE_DIAMONDS}", r"Gave \d+")
    run(server, f"experience set {PROBE_PLAYER} {PROBE_XP_LEVEL} levels", r"experience|levels")
    run(server, f"tp {PROBE_PLAYER} {x} {y} {z}", r"Teleported")
    time.sleep(3)                     # Let the teleport be confirmed before anything saves.
    return client


def verify_player(server, port, checks, label, diamonds):
    """Reconnect the probe player and read its state back through the server."""
    client = join_probe(server, port, checks, label)
    if client is None:
        return None
    found = re.search(r"Found (\d+) matching item",
                      run(server, f"clear {PROBE_PLAYER} minecraft:diamond 0",
                          r"Found \d+ matching item|No items were found"))
    count = int(found.group(1)) if found else 0
    checks.record(f"{label}: player inventory survived", count == diamonds,
                  f"{count} diamonds, expected {diamonds}")
    level = re.search(r"has (\d+) experience level",
                      run(server, f"experience query {PROBE_PLAYER} levels",
                          r"has \d+ experience level"))
    checks.record(f"{label}: player experience survived",
                  level is not None and int(level.group(1)) == PROBE_XP_LEVEL,
                  f"level {level.group(1) if level else '?'}, expected {PROBE_XP_LEVEL}")
    pos = re.search(r"entity data: \[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]",
                    run(server, f"data get entity {PROBE_PLAYER} Pos", r"entity data: \["))
    near = pos is not None and all(abs(float(a) - b) < 1.5
                                   for a, b in zip(pos.groups(), PROBE_PLAYER_POS))
    checks.record(f"{label}: player position survived", near,
                  f"{pos.groups() if pos else '?'}, expected about {PROBE_PLAYER_POS}")
    return client


def inspect_disk(world, checks, label, player):
    print(f"{label}: inspecting files the stopped server is no longer holding", flush=True)
    check_region_integrity(world, checks, label)
    check_level_dat(world, checks, label)
    check_player_data(world, checks, player, f"{label}: player data")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar")
    parser.add_argument("--output", required=True)
    parser.add_argument("--heap-mib", type=int, default=2048)
    parser.add_argument("--port", type=int, default=25599)
    parser.add_argument("--cache-from", help="Reuse a run directory's bootstrap cache")
    parser.add_argument("--keep", action="store_true", help="Keep the fixture directory")
    parser.add_argument("--restarts", type=int, default=3,
                        help="Restarts that each read back the previous boot's writes (min 1)")
    parser.add_argument("--load-clients", type=int, default=6,
                        help="Moving clients connected while the last restart's stop is issued; "
                             "0 stops without load")
    parser.add_argument("--load-seconds", type=int, default=45,
                        help="How long the load runs before stop is issued")
    args = parser.parse_args()
    restarts = max(1, args.restarts)

    directory = Path(args.output)
    if directory.exists():
        shutil.rmtree(directory)
    prepare(directory, args.port)
    if args.cache_from:
        seed_cache(directory, Path(args.cache_from))
    world = directory / "world"

    checks = Check()
    print("boot 1: writing state", flush=True)
    server = boot(args.jar, directory, args.heap_mib)
    try:
        previous = write_state(server, checks)
        probe = write_player(server, args.port, checks)
        if probe is not None:
            # Quit before stop: this boot proves the save-on-quit path.
            leave_probe(server, probe, checks, "boot 1")
    finally:
        stop_cleanly(server, checks, "boot 1")
    diamonds = PROBE_DIAMONDS
    expected_player = {"diamonds": diamonds, "xp_level": PROBE_XP_LEVEL, "pos": PROBE_PLAYER_POS}
    inspect_disk(world, checks, "after boot 1", expected_player)

    blocks = (PROBE_BLOCK, WITNESS_BLOCK)
    # restarts verify-and-rewrite boots, then one final boot that only verifies, so the last
    # (loaded) shutdown is itself read back.
    for number in range(2, restarts + 3):
        label = f"boot {number}"
        last = number == restarts + 2
        under_load = number == restarts + 1 and args.load_clients > 0
        present, replacement = blocks[number % 2], blocks[(number + 1) % 2]
        print(f"{label}: reading back boot {number - 1}"
              + (" (final)" if last else "") + (", then stopping under load" if under_load else ""),
              flush=True)
        server = boot(args.jar, directory, args.heap_mib)
        swarm = None
        try:
            previous = verify_state(server, checks, previous, label, present, replacement)
            probe = verify_player(server, args.port, checks, label, diamonds)
            if probe is not None and not last:
                run(server, f"give {PROBE_PLAYER} minecraft:diamond 1", r"Gave \d+")
                diamonds += 1
            if not last:
                run(server, "save-all flush", r"Saved the game|saved", timeout=60)
                previous = {"gametime": game_time(server)}
            if under_load:
                swarm = ClientSwarm("127.0.0.1", args.port, args.load_clients, prefix="Load",
                                    view_distance=4, move=True)
                report = swarm.start()
                checks.record(f"{label}: load clients connected",
                              report["in_play"] == args.load_clients,
                              f"{report['in_play']} of {args.load_clients} in play")
                server.hold(args.load_seconds)
                # Probe stays online: this stop proves players are saved at shutdown, with
                # chunk generation and entity movement still in flight.
            elif probe is not None:
                leave_probe(server, probe, checks, label)
        finally:
            stop_cleanly(server, checks, label)
            if swarm is not None:
                swarm.stop()
        expected_player = {"diamonds": diamonds, "xp_level": PROBE_XP_LEVEL,
                           "pos": PROBE_PLAYER_POS}
        inspect_disk(world, checks, f"after {label}", expected_player)

    report = directory / "persistence.json"
    report.write_text(json.dumps({"checks": checks.results,
                                  "passed": not checks.failed,
                                  "restarts": restarts,
                                  "load_clients": args.load_clients}, indent=2) + "\n")
    print(f"\nreport: {report}")
    if checks.failed:
        print(f"FAILED {len(checks.failed)} of {len(checks.results)} checks")
        return 1
    print(f"PASSED {len(checks.results)} checks")
    if not args.keep:
        shutil.rmtree(directory, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
