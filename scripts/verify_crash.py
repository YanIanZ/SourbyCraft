#!/usr/bin/env python3
"""Kill a SourbyCraft server while saves are in flight, and check what survives.

verify_persistence.py proves clean shutdowns keep data. This proves the other half of the
storage contract, the one an operator meets after a power cut or an OOM kill:

* region files (the default Moonrise path): nothing written by a completed `save-all flush`
  may be lost, and no region file or level.dat may be left unreadable;
* Aurora World Fabric (`--awf`): the world comes back as of the last commit — the documented
  promise is "a crash loses the writes since the last commit" — and the store keeps
  committing afterwards.

Each cycle writes a checkpoint and waits until it is durable (flush, and for AWF until every
storage reports no dirty chunk and no pending commit), then starts real save traffic —
moving clients generating terrain, a block site being rewritten — and SIGKILLs the JVM after a
different delay. The next boot must come up, must not log chunk corruption, and must hold the
checkpoint exactly. Writes made after the checkpoint may or may not survive; that is allowed.

Usage:
    python3 scripts/verify_crash.py build/libs/SourbyCraft-slim.jar --output build/crash
    python3 scripts/verify_crash.py build/libs/SourbyCraft-slim.jar --output build/crash-awf --awf
"""
import argparse
import json
import re
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from baseline_client import ClientSwarm  # noqa: E402
from run_baseline import seed_cache  # noqa: E402
from verify_persistence import (  # noqa: E402
    PROBE_BLOCK, WITNESS_BLOCK, Check, boot, check_level_dat, check_region_integrity, game_time,
    prepare, run, stop_cleanly, verify_state, write_state)

# Somewhere else than the probe, so churn never touches what the checkpoint is judged on.
CHURN = (-3008, 100, 3008)
CHURN_SIDE = 16
KILL_DELAYS = (3, 8, 15)

# Checks that fail for a known, recorded reason. They are reported as XFAIL and do not fail the
# run; if one starts passing it is reported as XPASS so the entry gets removed.
KNOWN_LIMITATIONS = {
    "game time survived and advanced": (
        "level.dat (which holds the world's game time and spawn) is written only at startup and "
        "at a clean stop: region-threaded save-all saves chunks and the 'safe' global data but not "
        "level.dat, so a crash rewinds game time to the last boot. Inherited from Folia/Canvas; "
        "tracked in TODO.md P0"),
}
AWF_COMMIT_SECONDS = 5

# Console text that means the storage layer met damage it should never see after a crash.
CORRUPTION = ("Failed to read chunk", "Couldn't load chunk", "Failed to load chunk",
              "Corrupt", "corrupted", "Failed to deserialize", "Error loading chunk",
              "is in the wrong location", "Failed to load level.dat", "Exception reading",
              "Aurora World Fabric: failed", "could not be opened")


def classify(results):
    """Split check results into failures, expected failures and unexpected passes."""
    failed, xfail, xpass = [], [], []
    for result in results:
        known = next((reason for name, reason in KNOWN_LIMITATIONS.items()
                      if result["check"].endswith(name)), None)
        if known and not result["ok"]:
            xfail.append({**result, "known": known})
        elif known:
            xpass.append(result)
        elif not result["ok"]:
            failed.append(result)
    return failed, xfail, xpass


def corruption_in(log_text):
    """Every corruption marker in a boot log, with the line it appeared on."""
    found = []
    for line in log_text.splitlines():
        for marker in CORRUPTION:
            if marker in line:
                found.append(line.strip()[-200:])
                break
    return found


def enable_awf(directory, interval):
    config = directory / "sourbycraft_config"
    config.mkdir(exist_ok=True)
    (config / "aurora.toml").write_text(
        "# Crash fixture: the overworld and its dimensions stored through AWF.\n"
        f"[aurora.awf]\nworlds = [\"world\"]\ncommit-interval-seconds = {interval}\n")


def allow_flight(directory):
    """Half the clients fly; without this the server kicks them for floating."""
    path = directory / "server.properties"
    path.write_text(path.read_text() + "allow-flight=true\n")


def parse_awf_backlog(text):
    """(dirty chunks, pending commits) summed over every storage in /perf awf, or None."""
    dirty = [int(m) for m in re.findall(r"resident / dirty / evicted: \d+ / (\d+) / \d+", text)]
    pending = [int(m) for m in re.findall(r"pending commits / oldest: (\d+) /", text)]
    if not dirty:
        return None
    return sum(dirty), sum(pending)


def awf_backlog(server):
    return parse_awf_backlog(run(server, "perf awf", r"retries / failures / written|no world is stored", 20))


def make_durable(server, checks, label, awf):
    """Flush, and for AWF wait until the commit has landed. Returns the checkpoint game time."""
    run(server, "save-all flush", r"Saved the game|saved", 120)
    if awf:
        deadline = time.monotonic() + 180
        backlog = None
        while time.monotonic() < deadline:
            backlog = awf_backlog(server)
            if backlog == (0, 0):
                break
            time.sleep(2)
        checks.record(f"{label}: AWF checkpoint committed", backlog == (0, 0),
                      f"dirty / pending = {backlog}")
    return {"gametime": game_time(server)}


def check_storage(world, checks, label, awf):
    """Region-file integrity, or for AWF: stores present and no region file written under them.

    A fresh world stored through AWF never writes .mca files while it is attached (the region
    files are only a read-only base), so a region check there would test nothing.
    """
    if not awf:
        check_region_integrity(world, checks, label)
        return
    stores = sorted(world.glob("**/*.awf"))
    checks.record(f"{label}: AWF stores present", bool(stores), f"{len(stores)} stores")
    # A store nothing was ever written to (no POI in the nether, say) has no generation yet and
    # nothing to lose; any store holding objects must point at a committed generation.
    holding = [s for s in stores if any((s / "objects").rglob("*"))]
    uncommitted = [s.name for s in holding if not (s / "generations-root" / "CURRENT").is_file()]
    checks.record(f"{label}: every AWF store holding data has a committed generation",
                  not uncommitted, f"{len(holding) - len(uncommitted)} of {len(holding)} "
                  f"({len(stores) - len(holding)} never written)")
    written = sorted(world.glob("**/region/*.mca")) + sorted(world.glob("**/entities/*.mca"))
    checks.record(f"{label}: no region file written while attached", not written,
                  f"{len(written)} .mca")


def churn(server, round_number):
    """Keep a block site changing, so there are chunk writes to interrupt."""
    x, y, z = CHURN
    block = ("minecraft:gold_block", "minecraft:iron_block")[round_number % 2]
    server.send(f"forceload add {x} {z} {x + CHURN_SIDE} {z + CHURN_SIDE}",
                f"fill {x} {y} {z} {x + CHURN_SIDE - 1} {y + 3} {z + CHURN_SIDE - 1} {block}",
                "save-all")


def crash(server, delay, checks, label, port, clients):
    """Start save traffic, then SIGKILL the JVM after ``delay`` seconds of it."""
    swarm = ClientSwarm("127.0.0.1", port, clients, prefix="Crash", view_distance=4, move=True)
    report = swarm.start(timeout=60)
    checks.record(f"{label}: load clients connected", report["in_play"] == clients,
                  f"{report['in_play']} of {clients}")
    started = time.monotonic()
    round_number = 0
    while time.monotonic() - started < delay:
        churn(server, round_number)
        round_number += 1
        time.sleep(1)
    server.process.kill()                     # SIGKILL: no shutdown hook, no final save.
    code = server.process.wait(timeout=60)
    swarm.stop()
    checks.record(f"{label}: killed with saves in flight", code != 0,
                  f"exit {code} after {delay}s of churn ({round_number} rounds)")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar")
    parser.add_argument("--output", required=True)
    parser.add_argument("--awf", action="store_true", help="Store the world through AWF")
    parser.add_argument("--clients", type=int, default=4)
    parser.add_argument("--heap-mib", type=int, default=2048)
    parser.add_argument("--port", type=int, default=25598)
    parser.add_argument("--cache-from", help="Reuse a run directory's bootstrap cache")
    parser.add_argument("--keep", action="store_true")
    args = parser.parse_args()

    directory = Path(args.output)
    if directory.exists():
        shutil.rmtree(directory)
    prepare(directory, args.port)
    allow_flight(directory)
    if args.awf:
        enable_awf(directory, AWF_COMMIT_SECONDS)
    if args.cache_from:
        seed_cache(directory, Path(args.cache_from))
    world = directory / "world"
    checks = Check()
    mode = "AWF" if args.awf else "region files"

    print(f"[{mode}] boot 1: writing the first checkpoint", flush=True)
    server = boot(args.jar, directory, args.heap_mib)
    write_state(server, checks)
    checkpoint = make_durable(server, checks, "boot 1", args.awf)
    crash(server, KILL_DELAYS[0], checks, "crash 1", args.port, args.clients)

    blocks = (PROBE_BLOCK, WITNESS_BLOCK)
    for number, delay in enumerate(KILL_DELAYS[1:] + (None,), start=2):
        label = f"boot {number}"
        check_storage(world, checks, f"after crash {number - 1}", args.awf)
        check_level_dat(world, checks, f"after crash {number - 1}")
        print(f"[{mode}] {label}: recovering from crash {number - 1}", flush=True)
        try:
            server = boot(args.jar, directory, args.heap_mib)
        except Exception as problem:                       # noqa: BLE001 - the result is data
            checks.record(f"{label}: boots after the crash", False, str(problem)[:200])
            break
        checks.record(f"{label}: boots after the crash", True,
                      f"console accepted commands {server.console_ready_seconds}s after Done")
        damage = corruption_in(server.text)
        checks.record(f"{label}: no chunk corruption logged", not damage, "; ".join(damage[:3]) or "none")
        # The checkpoint must be there exactly; verify_state then rewrites it for the next crash.
        present, replacement = blocks[number % 2], blocks[(number + 1) % 2]
        verify_state(server, checks, checkpoint, label, present, replacement)
        checkpoint = make_durable(server, checks, label, args.awf)
        if delay is None:
            stop_cleanly(server, checks, label)
            break
        crash(server, delay, checks, f"crash {number}", args.port, args.clients)

    check_storage(world, checks, "final", args.awf)
    check_level_dat(world, checks, "final")
    failed, xfail, xpass = classify(checks.results)
    report = directory / "crash.json"
    report.write_text(json.dumps({"mode": mode, "kill_delays_s": KILL_DELAYS,
                                  "checks": checks.results, "passed": not failed,
                                  "expected_failures": xfail, "unexpected_passes": xpass},
                                 indent=2) + "\n")
    print(f"\nreport: {report}")
    for result in xfail:
        print(f"  XFAIL {result['check']} — {result['known']}")
    for result in xpass:
        print(f"  XPASS {result['check']} — now passes; remove it from KNOWN_LIMITATIONS")
    if failed:
        print(f"FAILED {len(failed)} of {len(checks.results)} checks")
        return 1
    print(f"PASSED {len(checks.results) - len(xfail)} checks, {len(xfail)} known limitation(s)")
    if not args.keep:
        shutil.rmtree(directory, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
