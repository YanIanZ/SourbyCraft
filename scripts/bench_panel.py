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
import json
import re
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import baseline_client  # noqa: E402
import baseline_workloads as workloads  # noqa: E402
import pterodactyl_panel as panel  # noqa: E402
from verify_persistence import offline_uuid  # noqa: E402

PREFIX = "Bench"
FAILURES = ("Unknown or incomplete command", "Incorrect argument for command", "Expected ",
            "Invalid or unknown entity type", "Unable to summon", "That position is not loaded")
# Plugins such as EssentialsX register these bare names; the workload means the vanilla ones.
VANILLA = ("time", "weather", "difficulty", "tp", "kill")


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
    for path, text in originals.items():
        (output / ("original-" + path.strip("/").replace("/", "__"))).write_text(text)
    original_properties = originals["/server.properties"]

    record = {"workload": plan.name, "summary": plan.summary, "fidelity": list(plan.fidelity),
              "clients_requested": clients, "seed": args.seed, "world": args.world,
              "warmup_s": args.warmup, "duration_s": args.duration,
              "certified": False,
              "not_certified_because": [
                  "clients run on the operator's machine over the internet",
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
            "allow-flight": "true"}))
        panel.write_file("/whitelist.json", bench_whitelist(clients))
        panel.write_file("/bukkit.yml", without_throttle(originals["/bukkit.yml"]))
        for path, old, new in edits:
            if old not in originals[path]:
                raise SystemExit(f"--edit: {old!r} not found in {path}")
            panel.write_file(path, originals[path].replace(old, new))
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
        ready_by = time.monotonic() + 300
        while time.monotonic() < ready_by:
            _, answered = panel.run("minecraft:list", r"players online", 15)
            if answered:
                break
        else:
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
        record["available_processors"] = number(r"Available processors / live platform threads: (\d+)", cpu)
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

        print(f"connecting {clients} clients to {args.host}:{args.port}", flush=True)
        swarm = baseline_client.ClientSwarm(args.host, args.port, clients, prefix=PREFIX,
                                            roam=plan.client_roam_blocks)
        record["clients"] = swarm.start(timeout=180.0)
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
        record["finished"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / "bench.json").write_text(json.dumps(record, indent=2) + "\n")
        print(f"restored: {record['restored']}  report: {output / 'bench.json'}", flush=True)
    summary = record.get("summary_metrics", {})
    print(json.dumps({k: v and v["median"] for k, v in summary.items()}, indent=2))
    return 0 if record["restored"]["properties_identical"] and record["restored"]["running"] else 1


if __name__ == "__main__":
    sys.exit(main())
