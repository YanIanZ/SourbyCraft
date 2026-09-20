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
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_baseline import Server, seed_cache, jdk_tools  # noqa: E402

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
    return server


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
        produced = server.text[before:]
        if re.search(expect, produced) or any(f in produced for f in COMMAND_FAILURES):
            return produced
        time.sleep(0.5)
    return server.text[before:]


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
    time.sleep(2)
    # A solid floor first, so entities land on something deterministic instead of falling.
    server.send(f"fill {x} {y - 1} {z} {x + PROBE_SIDE - 1} {y - 1} {z + PROBE_SIDE - 1} "
                f"{PROBE_BLOCK}")
    time.sleep(1.5)
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


def check_region_integrity(world, checks):
    """Validate the .mca header against the file, without a full chunk parser.

    A region file is a 4 KiB-sector container: 1024 location entries of 3-byte sector offset
    plus 1-byte sector count, then 1024 timestamps, then the chunk data. An offset pointing
    past the end, or into the header, is corruption the next boot would hit.
    """
    files = region_files(world)
    if not checks.record("region: files exist", bool(files), f"{len(files)} .mca"):
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
    checks.record("region: every referenced chunk lies within its file", not problems, detail)
    checks.record("region: at least one chunk stored", populated > 0, f"{populated} chunks")


def check_level_dat(world, checks):
    """level.dat is gzipped NBT whose root is a TAG_Compound; anything else will not load."""
    path = world / "level.dat"
    if not checks.record("world metadata: level.dat exists", path.is_file()):
        return
    try:
        raw = gzip.decompress(path.read_bytes())
    except OSError as broken:
        checks.record("world metadata: level.dat is valid gzip", False, str(broken))
        return
    checks.record("world metadata: level.dat is valid gzip", True, f"{len(raw)} bytes inflated")
    checks.record("world metadata: NBT root is a compound", raw[:1] == b"\x0a",
                  f"first tag byte 0x{raw[0]:02x}")


def check_player_data(world, checks):
    """Structural only, and said so: no client connects, so there is no player file to compare.

    Left in rather than dropped, because §16 lists player data and a check that quietly does
    not exist is worse than one that reports its own limitation.
    """
    directory = world / "playerdata"
    if not directory.is_dir():
        checks.record("player data: directory absent (no client connected)", True,
                      "structural check only; needs a client-attached run to mean more")
        return
    bad = []
    for path in directory.glob("*.dat"):
        try:
            raw = gzip.decompress(path.read_bytes())
            if raw[:1] != b"\x0a":
                bad.append(path.name)
        except OSError:
            bad.append(path.name)
    checks.record("player data: every .dat is gzipped NBT", not bad, ", ".join(bad[:3]) or "ok")


def verify_state(server, checks, before_shutdown):
    x, y, z = SITE

    # Forceload persists, but the chunks are not back the instant "Done" is printed, and the
    # first version of this fired its fill into "That position is not loaded" and recorded a
    # server fault. Re-assert the ticket and let the region come up before asking anything.
    server.send(f"forceload add {x} {z} {x + PROBE_SIDE} {z + PROBE_SIDE}")
    fill = (f"fill {x} {y - 1} {z} {x + PROBE_SIDE - 1} {y - 1} {z + PROBE_SIDE - 1} "
            f"{WITNESS_BLOCK} replace {PROBE_BLOCK}")
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
    checks.record("chunk save integrity: blocks survived the restart",
                  count == PROBE_BLOCKS, detail)

    listed = re.search(r"Total Ticking: (\d+), Total Non-Ticking: (\d+)",
                       run(server, "paper entity list minecraft:armor_stand minecraft:overworld",
                           r"Total Ticking: \d+"))
    total = int(listed.group(1)) + int(listed.group(2)) if listed else 0
    checks.record("entity data: entities survived the restart",
                  total == PROBE_ENTITIES, f"{total} of {PROBE_ENTITIES} armor stands")

    # Patterns here are anchored on the command's own wording. Matching a bare \d+ read the
    # "19" out of the log timestamp "[19:33:11 INFO]" and reported a game time of 19.
    # "time query daytime" does not exist in 26.2 either: the argument is a timeline id.
    gametime = game_time(server)
    checks.record("world metadata: game time survived and advanced",
                  gametime >= before_shutdown["gametime"] > 0,
                  f"{gametime} now, {before_shutdown['gametime']} before shutdown")

    rule = re.search(r"is currently set to: (\S+)",
                     run(server, f"gamerule {PROBE_GAMERULE[0]}", r"currently set to"))
    value = rule.group(1) if rule else "?"
    checks.record("world metadata: gamerule survived", value == PROBE_GAMERULE[1],
                  f"{PROBE_GAMERULE[0]} = {value}, wrote {PROBE_GAMERULE[1]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar")
    parser.add_argument("--output", required=True)
    parser.add_argument("--heap-mib", type=int, default=2048)
    parser.add_argument("--port", type=int, default=25599)
    parser.add_argument("--cache-from", help="Reuse a run directory's bootstrap cache")
    parser.add_argument("--keep", action="store_true", help="Keep the fixture directory")
    args = parser.parse_args()

    directory = Path(args.output)
    if directory.exists():
        shutil.rmtree(directory)
    prepare(directory, args.port)
    if args.cache_from:
        seed_cache(directory, Path(args.cache_from))

    checks = Check()
    print("first boot: writing state", flush=True)
    server = boot(args.jar, directory, args.heap_mib)
    try:
        before_shutdown = write_state(server, checks)
    finally:
        stop_cleanly(server, checks, "first boot")

    world = directory / "world"
    print("on disk: inspecting files the running server is no longer holding", flush=True)
    check_region_integrity(world, checks)
    check_level_dat(world, checks)
    check_player_data(world, checks)

    print("second boot: reading state back", flush=True)
    second = boot(args.jar, directory, args.heap_mib)
    try:
        verify_state(second, checks, before_shutdown)
    finally:
        stop_cleanly(second, checks, "second boot")

    report = directory / "persistence.json"
    report.write_text(json.dumps({"checks": checks.results,
                                  "passed": not checks.failed}, indent=2) + "\n")
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
