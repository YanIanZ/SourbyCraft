#!/usr/bin/env python3
"""Restart persistence on a live Pterodactyl-hosted SourbyCraft server.

verify_persistence.py runs a local fixture it fully controls. This is its counterpart for a
deployed server: commands go through the panel's client API, output is read back from
logs/latest.log, and restarts are real panel stop/start cycles.

It must not change the world it runs against. The probe floor and armor stands sit in the
air at y=300, far from spawn; no gamerule, time or terrain is touched; everything is
removed at the end, even after a failure. Vanilla command ids (minecraft:time,
minecraft:kill) are used because plugins such as EssentialsX replace the bare names.

A connected-player check is not possible here: an online-mode server rejects the offline
headless client. verify_persistence.py covers player data locally.

Usage:
    PTERODACTYL_KEY=ptlc_... python3 scripts/verify_persistence_panel.py \\
        --panel https://panel.example --server <server-id> --restarts 3
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = ""   # set by main() from --panel and --server
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def call(method, path, body=None, raw=False):
    request = urllib.request.Request(BASE + path, method=method, headers={
        "Authorization": "Bearer " + os.environ["PTERODACTYL_KEY"],
        "Accept": "application/json", "Content-Type": "application/json",
        "User-Agent": "sourbycraft-persistence-check/1.0"})
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(request, data, timeout=60) as response:
        payload = response.read()
    if raw:
        return payload.decode("utf-8", "replace")
    return json.loads(payload) if payload else None


def state():
    return call("GET", "/resources")["attributes"]["current_state"]


def power(signal):
    call("POST", "/power", {"signal": signal})


def command(text):
    call("POST", "/command", {"command": text})


def log():
    return ANSI.sub("", call("GET", "/files/contents?file=" + urllib.parse.quote("/logs/latest.log"),
                             raw=True))


def rename(source, target):
    call("PUT", "/files/rename", {"root": "/", "files": [{"from": source, "to": target}]})


def wait_state(wanted, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if state() == wanted:
            return True
        time.sleep(3)
    return False


def wait_log(pattern, timeout, after=0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = log()
        match = re.search(pattern, text[after:])
        if match:
            return text, match
        time.sleep(3)
    return log(), None


def run(text, expect, timeout=40):
    """Send a console command and return the log text it produced, read back from latest.log."""
    before = len(log())
    command(text)
    _, match = wait_log(expect, timeout, before)
    return log()[before:], match

X, Y, Z = 3008, 300, -3008
SIDE, ENTITIES = 8, 24
TAG = "persist_probe"
BLOCKS = ("minecraft:diamond_block", "minecraft:emerald_block")
results = []


def record(name, ok, detail=""):
    results.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def forceload():
    run(f"forceload add {X} {Z} {X + SIDE} {Z + SIDE}", r"[Mm]arked|already|forceloaded", 30)
    time.sleep(5)


def fill(block, replace=None):
    cmd = f"fill {X} {Y} {Z} {X + SIDE - 1} {Y} {Z + SIDE - 1} {block}" + (f" replace {replace}" if replace else "")
    for _ in range(10):
        out, _ = run(cmd, r"Successfully filled|No blocks were filled|not loaded", 30)
        if "not loaded" not in out:
            break
        time.sleep(5)
    m = re.search(r"Successfully filled (\d+) block", out)
    return int(m.group(1)) if m else 0


def probe_count():
    out, _ = run(f"execute if entity @e[tag={TAG}]", r"Test (passed|failed)", 30)
    m = re.search(r"Test passed[.,] [Cc]ount: (\d+)", out)
    return int(m.group(1)) if m else 0


def gametime():
    out, _ = run("minecraft:time query gametime", r"game time is \d+", 30)
    m = re.search(r"game time is (\d+)", out)
    return int(m.group(1)) if m else -1


def restart(label):
    power("stop")
    stopped = wait_state("offline", 600)
    tail = log()
    record(f"{label}: clean shutdown", stopped and "All RegionFile I/O tasks to complete" in tail,
           "region I/O drained" if stopped else "did not stop")
    for marker in ("Failed to save", "Exception in thread", "Watchdog"):
        if marker in tail:
            record(f"{label}: no '{marker}' during shutdown", False)
    power("start")
    # The panel reports "running" only once the new process prints Done; latest.log still holds
    # the previous boot's Done until the new process rotates it, so it cannot be the signal.
    running = wait_state("running", 600)
    text = log()
    record(f"{label}: boot reached Done", running and bool(re.search(r"Done \(\d", text)))
    record(f"{label}: no ERROR lines at boot", "ERROR]" not in text)


def main():
    global BASE
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--panel", required=True, help="Panel base URL")
    parser.add_argument("--server", required=True, help="Server identifier from the panel")
    parser.add_argument("--restarts", type=int, default=3)
    args = parser.parse_args()
    if "PTERODACTYL_KEY" not in os.environ:
        parser.error("set PTERODACTYL_KEY to a client API key; it is never read from a file")
    BASE = f"{args.panel.rstrip('/')}/api/client/servers/{args.server}"
    rounds = args.restarts
    print("write: probe in the air at y=300", flush=True)
    forceload()
    written = fill(BLOCKS[0])
    for i in range(ENTITIES):
        command(f"summon minecraft:armor_stand {X + i % SIDE + 0.5} {Y + 1} {Z + i // SIDE + 0.5} "
                      f"{{Tags:[\"{TAG}\"],NoGravity:1b,Invulnerable:1b}}")
    time.sleep(3)
    entities = probe_count()
    run("save-all flush", r"Saved the game|saved", 120)
    before = gametime()
    record("write: probe written", written == SIDE * SIDE and entities == ENTITIES,
           f"{written} blocks, {entities} entities, game time {before}")
    try:
        for n in range(1, rounds + 1):
            label = f"restart {n}"
            restart(label)
            forceload()
            present, other = BLOCKS[(n - 1) % 2], BLOCKS[n % 2]
            count = fill(other, present)
            record(f"{label}: blocks survived", count == SIDE * SIDE, f"{count} of {SIDE * SIDE}")
            entities = probe_count()
            record(f"{label}: entities survived", entities == ENTITIES, f"{entities} of {ENTITIES}")
            now = gametime()
            record(f"{label}: game time survived and advanced", now >= before > 0, f"{now} now, {before} before")
            run("save-all flush", r"Saved the game|saved", 120)
            before = gametime()
    finally:
        print("cleanup: removing the probe", flush=True)
        run(f"minecraft:kill @e[tag={TAG}]", r"Killed|No entity", 30)
        run(f"fill {X} {Y} {Z} {X + SIDE - 1} {Y} {Z + SIDE - 1} minecraft:air", r"filled|No blocks", 30)
        run(f"forceload remove {X} {Z} {X + SIDE} {Z + SIDE}", r"[Uu]nmarked|not", 30)
        left = probe_count()
        record("cleanup: no probe entities left", left == 0, f"{left} left")
    failed = [n for n, ok in results if not ok]
    print(f"{'PASSED' if not failed else 'FAILED'} {len(results) - len(failed)}/{len(results)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
