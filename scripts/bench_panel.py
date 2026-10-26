#!/usr/bin/env python3
"""Run a baseline workload with real connected clients on a panel-hosted server.

run_baseline.py measures a server it boots itself, on loopback, with JFR. A deployed server is
different hardware and the only place real chunk streaming over a real network happens, but it
runs online-mode, which the offline headless clients cannot join. This tool puts the server in a
temporary bench mode, measures, and puts it back:

* bench mode: level-name=<bench world> with a fixed seed, so the operator's world is untouched and
  runs are comparable; online-mode=false with white-list and enforce-whitelist on, listing only
  the bench client names (their offline UUIDs), so nobody else can join and the bots hold no op;
* the original server.properties and whitelist.json are saved locally before anything changes and
  restored in a finally block, then the restore is verified;
* workloads come from baseline_workloads.py unchanged; only command ids that plugins replace
  (EssentialsX's time, weather, difficulty, tp) are sent as their minecraft: ids.

Results are labelled NOT CERTIFIED by construction: clients run on the operator's machine over
the internet, and metrics are SourbyCraft's /perf readings sampled through the console rather
than a JFR recording. They describe this server under this load; compare panel runs only with
other panel runs of the same workload, seed and client count.

Usage:
    PTERODACTYL_KEY=ptlc_... python3 scripts/bench_panel.py --panel https://panel.example \\
        --server <server-id> --host <game host> --port 25555 --workload players-10 \\
        --output build/panel-bench/players-10
"""
import argparse
import datetime
import hashlib
import json
import re
import shlex
import socket
import statistics
import sys
import time
import urllib.error
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import baseline_client  # noqa: E402
import baseline_workloads as workloads  # noqa: E402
import pterodactyl_panel as panel  # noqa: E402
from external_swarm import ExternalSwarm  # noqa: E402

PREFIX = "Bench"
BENCH_API_RETRIES = 8
FAILURES = ("Unknown or incomplete command", "Incorrect argument for command", "Expected ",
            "Invalid or unknown entity type", "Unable to summon", "That position is not loaded")
# Plugins such as EssentialsX register these bare names; the workload means the vanilla ones.
VANILLA = ("time", "weather", "difficulty", "tp", "kill")


def offline_uuid(name):
    """The UUID an offline-mode server gives a name (same as verify_persistence.offline_uuid).

    Kept here so the tool stays a handful of stdlib-only files that can be copied to a remote
    load-generator host without verify_persistence's server-harness imports.
    """
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30          # version 3
    digest[8] = (digest[8] & 0x3F) | 0x80          # IETF variant
    return str(uuid.UUID(bytes=bytes(digest)))


def connect_rtt(host, port, attempts=10, timeout=5.0):
    """TCP connect round trips from this host to the game port, in milliseconds.

    The network path the clients really use, measured from where they run (SYN to SYN-ACK, no
    Minecraft traffic). A failed attempt is counted, not hidden.
    """
    times, failed = [], 0
    for _ in range(attempts):
        start = time.monotonic()
        try:
            with socket.create_connection((host, port), timeout=timeout):
                times.append((time.monotonic() - start) * 1000)
        except OSError:
            failed += 1
        time.sleep(0.2)
    if not times:
        return {"attempts": attempts, "failed": failed}
    return {"attempts": attempts, "failed": failed, "min_ms": round(min(times), 1),
            "median_ms": round(statistics.median(times), 1), "max_ms": round(max(times), 1)}


def apply_edits(originals, edits):
    """The edited text of every file named by --edit, applying several edits to one file in turn.

    Each edit replaces its OLD in the text the earlier edits left, so two --edit options on the
    same file both take effect (writing each from the original would silently drop all but the
    last).
    """
    edited = {}
    for path, old, new in edits:
        text = edited.get(path, originals[path])
        if old not in text:
            raise SystemExit(f"--edit: {old!r} not found in {path}")
        edited[path] = text.replace(old, new)
    return edited


def wait_until_answering(probe, timeout, sleep=time.sleep, clock=time.monotonic):
    """True once probe() is truthy, tolerating the panel's transient errors while the server boots.

    Wings reports `running` before the console is attached and answers 502 to a command sent in
    that window (seen from the remote load-generator host, 2026-10-21); that is not a failed
    bench, so errors are waited out until the deadline.
    """
    deadline = clock() + timeout
    while clock() < deadline:
        try:
            if probe():
                return True
        except (urllib.error.URLError, ConnectionError, TimeoutError):   # HTTPError is a URLError
            pass
        sleep(3)
    return False


def vanilla(command):
    head, _, rest = command.partition(" ")
    if head in VANILLA:
        return f"minecraft:{head} {rest}".strip()
    return command.replace(" run tp ", " run minecraft:tp ")


def set_properties(text, values):
    lines, seen = [], set()
    for line in text.splitlines():
        key = line.split("=", 1)[0].strip()
        if key in values and not line.lstrip().startswith("#"):
            lines.append(f"{key}={values[key]}")
            seen.add(key)
        else:
            lines.append(line)
    lines += [f"{key}={value}" for key, value in values.items() if key not in seen]
    return "\n".join(lines) + "\n"


def bench_whitelist(count):
    names = [f"{PREFIX}{index:03d}" for index in range(count)]
    return json.dumps([{"uuid": offline_uuid(name), "name": name} for name in names], indent=2) + "\n"


def without_throttle(bukkit_yml):
    """bukkit.yml with connection-throttle off: every bench client logs in from one address."""
    return re.sub(r"(?m)^(\s*connection-throttle:).*$", r"\1 -1", bukkit_yml)


def relaxed_connection_guard(security_yml):
    """sourbycraft-security.yml with the per-address login limits raised for the bench window.

    Every bench client logs in from one address, so SourbyCraft's per-address connection guard
    would count them as one abusive host (it only logs in LOG mode, but a stricter mode refuses).
    Only the two per-address numbers change; the original text is restored afterwards.
    """
    head, marker, tail = security_yml.partition("per-address:")
    if not marker:
        return security_yml
    tail = re.sub(r"(?m)^(\s+max-(?:connections|login-starts)-per-second:).*$", r"\1 1000", tail)
    return head + marker + tail


class BenchBreached(RuntimeError):
    """Bench mode lost its guard: the whitelist went off, or someone else got in."""


def guard(log_text):
    """Abort if bench mode stopped being closed: offline mode is only safe behind the whitelist."""
    if "Whitelist is now turned off" in log_text:
        raise BenchBreached("whitelist was turned off during bench mode")
    for name in re.findall(r"\]: (\w+)\[/[^\]]*\] logged in with entity id", log_text):
        if not name.startswith(PREFIX):
            raise BenchBreached(f"{name} joined during bench mode")


def number(pattern, text, default=None):
    match = re.search(pattern, text)
    return float(match.group(1)) if match else default


def sample():
    """One reading of the SourbyCraft /perf surface, raw text kept beside the parsed values."""
    text = ""
    for cmd in ("perf tick", "perf region", "perf cpu", "perf memory"):
        out, _ = panel.run(cmd, r"Freshness", 20)
        text += out
    return {
        "time": time.time(),
        "worst_region_tps": number(r"Worst region TPS: ([\d.]+)", text),
        "worst_avg_mspt": number(r"Worst average MSPT: ([\d.]+)", text),
        "p95_mspt": number(r"Estimated p95 / p99: ([\d.]+)", text),
        "p99_mspt": number(r"Estimated p95 / p99: [\d.]+\S* / ([\d.]+)", text),
        "recent_max_mspt": number(r"Recent maximum: ([\d.]+)", text),
        "active_regions": number(r"Active regions / retained generations: (\d+)", text),
        "process_cpu_pct": number(r"Process CPU / system CPU: ([\d.]+)%", text),
        "system_cpu_pct": number(r"Process CPU / system CPU: [\d.]+% / ([\d.]+)%", text),
        "heap_used": re.search(r"Heap used / committed / maximum: (\S+)", text).group(1)
        if "Heap used" in text else None,
        "freshness": (re.search(r"Freshness: (\S+)", text) or [None, None])[1] if "Freshness" in text else None,
        "raw": text,
    }


def online_count():
    """Players online according to the server's own `list`."""
    out, _ = panel.run("minecraft:list", r"players online", 20)
    return int(number(r"There are (\d+)", out, 0))


def summarise(samples, key):
    values = [s[key] for s in samples if s.get(key) is not None]
    if not values:
        return None
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "n": len(values)}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--panel", required=True)
    parser.add_argument("--server", required=True)
    parser.add_argument("--host", required=True, help="Game address the clients connect to")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--workload", default="players-10", choices=workloads.NAMES)
    parser.add_argument("--clients", type=int, help="Defaults to N for players-N, else 10")
    parser.add_argument("--warmup", type=int, default=120)
    parser.add_argument("--duration", type=int, default=600)
    parser.add_argument("--sample-seconds", type=int, default=30)
    parser.add_argument("--seed", default="440044")
    parser.add_argument("--world", default="bench", help="level-name used while benching")
    parser.add_argument("--output", required=True)
    parser.add_argument("--edit", action="append", default=[], metavar="PATH=OLD=>NEW",
                        help="Replace OLD with NEW in a server file for the bench run (restored afterwards). "
                             "E.g. a plugin's spawn world when it names the operator's world.")
    parser.add_argument("--max-players", type=int,
                        help="max-players while benching (restored afterwards). Required when the "
                             "client count exceeds the server's own max-players.")
    parser.add_argument("--clients-on", metavar="LABEL",
                        help="Where the clients run when it is not the operator's machine, e.g. "
                             "'a shared Linux VPS'. Recorded, and used in the NOT CERTIFIED reasons.")
    parser.add_argument("--client-processes", type=int, default=1,
                        help="Spread the clients over this many processes (default 1: all threads of "
                             "one process, which a slow load-generator host cannot keep up with)")
    parser.add_argument("--external-clients-cmd", metavar="COMMAND",
                        help="Join the bench clients with this command (shell-split) instead of the "
                             "built-in headless clients, e.g. a SoulFire launcher on the machine this "
                             "tool runs on. It must log in Bench000..BenchNNN offline; it is stopped "
                             "when the bench ends. Per-address login limits are relaxed meanwhile.")
    parser.add_argument("--external-clients-timeout", type=int, default=900,
                        help="Seconds to wait for every external client to be in play")
    parser.add_argument("--allow-online-players", action="store_true",
                        help="Bench even if real players are online (they will be disconnected)")
    args = parser.parse_args()

    plan = workloads.build(args.workload)
    clients = args.clients if args.clients is not None else (
        int(args.workload.split("-")[1]) if args.workload.startswith("players-") else 10)
    output = Path(args.output)
    if output.exists():
        raise SystemExit(f"{output} exists; choose a new output directory")
    output.mkdir(parents=True)
    panel.configure(args.panel, args.server)
    # From a remote host the panel answers 502 for a stretch right after the server boots; the shared
    # default (5 tries, 15 s) gave up in it. Eight tries back off to about two minutes.
    panel.RETRIES = BENCH_API_RETRIES

    if panel.state() == "running" and not args.allow_online_players:
        out, _ = panel.run("minecraft:list", r"players online", 20)
        online = number(r"There are (\d+)", out, 0)
        if online:
            raise SystemExit(f"{int(online)} player(s) online; refusing to restart under them")

    # Everything bench mode changes, saved locally before anything is touched.
    edits = []
    for spec in args.edit:
        path, replacement = spec.split("=", 1)
        old, new = replacement.split("=>", 1)
        edits.append((path if path.startswith("/") else "/" + path, old, new))
    originals = {path: panel.read_file(path)
                 for path in ("/server.properties", "/whitelist.json", "/ops.json", "/bukkit.yml")
                 + tuple(path for path, _, _ in edits)}
    if args.external_clients_cmd:
        try:
            originals["/sourbycraft-security.yml"] = panel.read_file("/sourbycraft-security.yml")
        except Exception:                      # noqa: BLE001 - absent file: nothing to relax or restore
            pass
    for path, text in originals.items():
        (output / ("original-" + path.strip("/").replace("/", "__"))).write_text(text)
    original_properties = originals["/server.properties"]
    current_max = number(r"(?m)^max-players=(\d+)", original_properties, 20)
    if args.max_players is None and clients > current_max:
        raise SystemExit(f"{clients} clients exceed the server's max-players={int(current_max)}; "
                         f"pass --max-players {clients} (restored afterwards)")

    record = {"workload": plan.name, "summary": plan.summary, "fidelity": list(plan.fidelity),
              "clients_requested": clients, "seed": args.seed, "world": args.world,
              "warmup_s": args.warmup, "duration_s": args.duration,
              "certified": False, "max_players_bench": args.max_players,
              "clients_on": args.clients_on,
              "not_certified_because": [
                  ("clients are driven by an external command (see external_clients_cmd), not the "
                   "built-in headless clients" if args.external_clients_cmd
                   else f"clients run on {args.clients_on} over the internet" if args.clients_on
                   else "clients run on the operator's machine over the internet"),
                  "metrics are console-sampled /perf readings, not a JFR recording",
                  "the panel node is not known to be otherwise idle"],
              "started": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    swarm = None
    try:
        print("bench mode: stopping server", flush=True)
        panel.stop()
        panel.write_file("/server.properties", set_properties(original_properties, {
            "level-name": args.world, "level-seed": args.seed, "online-mode": "false",
            "white-list": "true", "enforce-whitelist": "true",
            # Half the clients fly; vanilla kicks them for "floating too long" otherwise.
            "allow-flight": "true",
            **({"max-players": str(args.max_players)} if args.max_players is not None else {})}))
        panel.write_file("/whitelist.json", bench_whitelist(clients))
        panel.write_file("/bukkit.yml", without_throttle(originals["/bukkit.yml"]))
        if "/sourbycraft-security.yml" in originals:
            panel.write_file("/sourbycraft-security.yml",
                             relaxed_connection_guard(originals["/sourbycraft-security.yml"]))
        for path, text in apply_edits(originals, edits).items():
            panel.write_file(path, text)
        record["edits"] = [{"path": path, "old": old, "new": new} for path, old, new in edits]
        # The plugin set is whatever the panel has installed: recorded, and checked against the
        # workload's declared set by name. A workload with plugins needs the bridge for the
        # bridged ones; that is the server's own setting here, read back from the log.
        installed = [entry["attributes"] for entry in panel.call("GET", "/files/list?directory=%2Fplugins")["data"]
                     if entry["attributes"]["is_file"] and entry["attributes"]["name"].endswith(".jar")]
        record["plugins_installed"] = [{"jar": a["name"], "size": a["size"]} for a in installed]
        declared = [entry["name"] for entry in (plan.plugins or [])]
        record["plugins_declared"] = declared
        if not panel.start():
            raise RuntimeError("server did not reach running in bench mode")
        # `running` is the process state, and latest.log still holds the previous boot's "Done ("
        # until it rotates, so the only reliable readiness signal is a command being answered.
        if not wait_until_answering(lambda: panel.run("minecraft:list", r"players online", 15)[1], 300):
            raise RuntimeError("server did not answer console commands within 300 s of starting")
        boot = panel.log()
        version = re.search(r"This server is running (.*)", boot)
        record["server_version"] = version.group(1).strip() if version else None
        record["bridge_admitted"] = re.findall(r"Aurora Bridge admitted (\S+)", boot)
        states, _ = panel.run("sourbycraft:plugins", r"╰", 30)
        # One plugin per line: "  Native  Name"; the counts line ("Failed / Disabled: 0 / 0") must not match.
        record["plugin_states"] = {name: state for state, name in
                                   re.findall(r"(?m)^\s*(Native|Bridged|Failed|Disabled)\s{2,}(\S+)\s*$",
                                              "\n".join(l.split("INFO]: ", 1)[-1] for l in states.splitlines()))}
        missing = [name for name in declared
                   if not any(name.lower().split("x")[0] in jar.lower() for jar in record["plugin_states"])]
        if missing:
            print(f"declared plugins not found by name on the panel: {missing}", flush=True)
        record["plugins_declared_missing"] = missing
        cpu, _ = panel.run("perf cpu", r"Freshness", 20)
        record["available_processors"] = number(r"Available processors / live (?:platform|Java) threads: (\d+)", cpu)
        memory, _ = panel.run("perf memory", r"Freshness", 20)
        heap = re.search(r"Heap used / committed / maximum: \S+ / \S+ / (\S+)", memory)
        record["heap_maximum"] = heap.group(1) if heap else None

        # The bench world persists between runs; clear the previous run's load first.
        panel.run("minecraft:kill @e[type=!minecraft:player]", r"Killed|No entity", 30)
        panel.run("forceload remove all", r"[Uu]nmarked|No chunks|forceload", 30)

        print(f"setup: {len(plan.setup)} commands", flush=True)
        setup_start = len(panel.log())
        for command in plan.setup:
            if command.startswith(workloads.SETTLE_TOKEN):
                time.sleep(int(command.split()[1]))
                continue
            panel.command(vanilla(command))
            time.sleep(panel.COMMAND_SPACING)
        time.sleep(5)
        # Only what the server said since setup began: earlier boot output is not the workload's.
        produced = panel.log()[setup_start:]
        record["setup_errors"] = {f: produced.count(f) for f in FAILURES if f in produced}
        print(f"settle {plan.settle_seconds}s", flush=True)
        time.sleep(plan.settle_seconds)

        record["client_network"] = connect_rtt(args.host, args.port)
        print(f"connecting {clients} clients to {args.host}:{args.port}; "
              f"tcp connect rtt {record['client_network']}", flush=True)
        if args.external_clients_cmd:
            swarm = ExternalSwarm(shlex.split(args.external_clients_cmd), clients, online_count,
                                  output / "external-clients.log")
            record["external_clients_cmd"] = args.external_clients_cmd
        elif args.client_processes > 1:
            swarm = baseline_client.ShardedSwarm(args.host, args.port, clients, prefix=PREFIX,
                                                 roam=plan.client_roam_blocks,
                                                 processes=args.client_processes, timer_moves=True)
        else:
            swarm = baseline_client.ClientSwarm(args.host, args.port, clients, prefix=PREFIX,
                                                roam=plan.client_roam_blocks, timer_moves=True)
        record["client_processes"] = args.client_processes
        record["clients"] = swarm.start(
            timeout=args.external_clients_timeout if args.external_clients_cmd else 180.0)
        print(f"clients: {record['clients']}", flush=True)
        sites = plan.parameters.get("site_coordinates") or []
        for index in range(clients if sites else 0):
            x, z = sites[index % len(sites)]
            panel.command(f"execute positioned {x} 0 {z} positioned over world_surface "
                          f"run minecraft:tp {PREFIX}{index:03d} ~ ~1 ~")
            time.sleep(panel.COMMAND_SPACING)
        print(f"warmup {args.warmup}s", flush=True)
        warm_until = time.monotonic() + args.warmup
        while time.monotonic() < warm_until:
            guard(panel.log())
            time.sleep(min(15, max(0, warm_until - time.monotonic())))

        samples = []
        deadline = time.monotonic() + args.duration
        clientless = 0
        while time.monotonic() < deadline:
            guard(panel.log())
            current = sample()
            current["clients_in_play"] = swarm.report()["in_play"]
            samples.append(current)
            record["samples"] = samples      # kept even when the run aborts below
            # Without clients the entity path is inactive and the samples measure nothing; stop and
            # restore instead of holding the server in bench mode for the rest of the window.
            clientless = clientless + 1 if clients and not current["clients_in_play"] else 0
            if clientless >= 2:
                raise BenchBreached("all clients disconnected (host asleep or network lost); bench aborted")
            print(f"  tps {current['worst_region_tps']} mspt {current['worst_avg_mspt']} "
                  f"p99 {current['p99_mspt']} regions {current['active_regions']} "
                  f"cpu {current['process_cpu_pct']}% clients {current['clients_in_play']}", flush=True)
            time.sleep(max(0, min(args.sample_seconds, deadline - time.monotonic())))
        history, _ = panel.run("perf history", r"Freshness", 20)
        record["perf_history"] = history
        # Plugin cost: the bridge's per-plugin body timing and the perf plugin view, raw.
        plugin_perf, _ = panel.run("perf plugins", r"╰|Freshness", 20)
        record["perf_plugins"] = plugin_perf
        record["bridged_plugin_details"] = {}
        for name, state in record.get("plugin_states", {}).items():
            if state == "Bridged":
                detail, _ = panel.run(f"plugins {name}", r"╰", 20)
                record["bridged_plugin_details"][name] = detail
        record["clients_end"] = swarm.report()
        record["samples"] = samples
        record["summary_metrics"] = {key: summarise(samples, key) for key in (
            "worst_region_tps", "worst_avg_mspt", "p95_mspt", "p99_mspt", "recent_max_mspt",
            "active_regions", "process_cpu_pct", "system_cpu_pct", "clients_in_play")}
    finally:
        print("restoring: stopping server", flush=True)
        if swarm is not None:
            swarm.stop()
        panel.stop()
        for path, text in originals.items():
            panel.write_file(path, text)
        restored = all(panel.read_file(path) == text for path, text in originals.items())
        running = panel.start()
        record["restored"] = {"properties_identical": restored, "running": running}
        if "samples" in record and "summary_metrics" not in record:
            record["summary_metrics"] = {key: summarise(record["samples"], key) for key in (
                "worst_region_tps", "worst_avg_mspt", "p95_mspt", "p99_mspt", "recent_max_mspt",
                "active_regions", "process_cpu_pct", "system_cpu_pct", "clients_in_play")}
            record["aborted"] = True
        record["finished"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / "bench.json").write_text(json.dumps(record, indent=2) + "\n")
        print(f"restored: {record['restored']}  report: {output / 'bench.json'}", flush=True)
    summary = record.get("summary_metrics", {})
    print(json.dumps({k: v and v["median"] for k, v in summary.items()}, indent=2))
    return 0 if record["restored"]["properties_identical"] and record["restored"]["running"] else 1


if __name__ == "__main__":
    sys.exit(main())
