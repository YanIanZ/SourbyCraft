#!/usr/bin/env python3
"""Capture a PRD Phase 0 performance baseline for one workload against one jar.

Boots an isolated Java 25 server, applies a declared workload, records JFR and
operating-system samples over a fixed measurement window, and writes a
``baseline.json`` carrying the full PRD section 85 provenance beside the raw
evidence. It changes no performance-related server setting.

    python3 scripts/run_baseline.py build/libs/SourbyCraft-slim.jar \
        --workload players-50 --output build/baselines/players-50

Run ``--workload all`` to capture the whole section 9 workload set in sequence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import threading
import time

import baseline_metrics
import baseline_network
import baseline_workloads

STARTUP_TIMEOUT = 600
SHUTDOWN_TIMEOUT = 120
SETUP_BATCH = 50
SETUP_BATCH_PAUSE = 0.2
SETTLE_AFTER_SETUP = 15
RSS_INTERVAL = 1.0

# Console output that means a workload command did not take effect.
# Console output that means the server will never reach "Done (".
STARTUP_FAILURES = ("Failed to start the minecraft server", "Perhaps a server is already running",
                    "crash report has been saved", "FAILED TO BIND TO PORT")

COMMAND_FAILURES = ("Unknown or incomplete command", "Incorrect argument for command",
                    "Expected whitespace to end one argument", "Invalid or unknown entity type",
                    "Unable to summon", "Cannot place feature")


# A measurement sharing the machine is not a measurement. This is the share of the
# machine that was busy with something other than the server under test.
FOREIGN_CPU_LIMIT = 0.10


def competing_servers(jar, own_pid):
    """Other live processes running a server jar, so a run does not silently share the box.

    Catches the common case directly — a second baseline, a profile_server.py run, a
    leftover server from an aborted run — before a long measurement is wasted on it.
    """
    try:
        listing = subprocess.run(["ps", "-eo", "pid=,command="],
                                 capture_output=True, text=True, check=True).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []                                     # Not fatal; the CPU check still applies.
    found = []
    for line in listing.splitlines():
        pid, _, command = line.strip().partition(" ")
        if not pid.isdigit() or int(pid) in (own_pid, os.getpid()):
            continue
        if jar.name in command and "-jar" in command:
            found.append(f"pid {pid}: {command.strip()[:110]}")
    return found


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def jdk_tools():
    """Resolve jcmd and jfr from the JDK that owns ``java``, not from PATH.

    On macOS ``java`` is usually the /usr/bin stub, which has no sibling jfr, so the
    home is read from the runtime itself rather than from the executable's directory.
    """
    java = shutil.which("java")
    if java is None:
        raise RuntimeError("No java on PATH")
    settings = subprocess.run([java, "-XshowSettings:properties", "-version"],
                              capture_output=True, text=True, check=True).stderr
    home = next((line.split("=", 1)[1].strip() for line in settings.splitlines()
                 if line.strip().startswith("java.home =")), None)
    if home is None:
        raise RuntimeError("Could not read java.home from the runtime")
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr
    if 'version "25' not in version:
        raise RuntimeError(f"Java 25 required; found: {version.splitlines()[0]}")
    tools = {name: Path(home) / "bin" / name for name in ("jcmd", "jfr")}
    missing = [name for name, path in tools.items() if not path.is_file()]
    if missing:
        raise RuntimeError(f"{home} is a JRE; {', '.join(missing)} not found. Use a JDK 25.")
    return Path(java).resolve(), tools["jcmd"], tools["jfr"], version.strip()


class ResidentSampler:
    """Sample process resident set size from the operating system once a second.

    Composed rather than subclassed: ``threading.Thread`` already owns a ``_stop``
    attribute, and shadowing it breaks joining the thread.
    """

    def __init__(self, pid):
        self._pid, self._halt, self.samples_kib = pid, threading.Event(), []
        self._thread = threading.Thread(target=self._run, name="baseline-rss", daemon=True)

    def _run(self):
        while not self._halt.is_set():
            try:
                output = subprocess.run(["ps", "-o", "rss=", "-p", str(self._pid)],
                                        capture_output=True, text=True, check=True).stdout.strip()
                if output:
                    self.samples_kib.append(int(output.split()[0]))
            except (subprocess.CalledProcessError, ValueError):
                pass                                  # The process has exited; stop sampling.
            self._halt.wait(RSS_INTERVAL)

    def start(self):
        self._thread.start()

    def stop(self):
        self._halt.set()
        self._thread.join(timeout=RSS_INTERVAL * 3)


class Server:
    """An isolated server process driven through its console."""

    def __init__(self, command, directory):
        self._directory = directory
        self._log = (directory / "server.log").open("w")
        self.process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                                        stdout=self._log, stderr=subprocess.STDOUT, text=True)

    @property
    def text(self):
        return (self._directory / "server.log").read_text(errors="replace")

    def send(self, *commands):
        for command in commands:
            self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def hold(self, seconds):
        """Wait, failing fast if the server dies during the wait."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f"Server exited early with code {self.process.returncode}")
            time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))

    def await_ready(self, timeout):
        started = time.monotonic()
        while True:
            text = self.text
            if "Done (" in text:
                return time.monotonic() - started
            for marker in STARTUP_FAILURES:
                if marker in text:
                    raise RuntimeError(f"Server failed to start: {marker!r}. See server.log.")
            if time.monotonic() - started > timeout:
                raise RuntimeError(f"Server startup timeout ({timeout}s)")
            self.hold(1)

    def apply(self, commands):
        """Send workload setup in paced batches so setup itself does not spike a tick."""
        for index in range(0, len(commands), SETUP_BATCH):
            self.send(*commands[index:index + SETUP_BATCH])
            self.hold(SETUP_BATCH_PAUSE)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self._log.close()


def seed_cache(directory, source):
    """Copy a previously downloaded bootstrap cache into a fresh run directory.

    A baseline should not depend on a 60 MB download succeeding. Seeding makes the run
    offline, deterministic and faster, and removes a failure mode that has already
    truncated two runs on this machine.
    """
    source = source.resolve(strict=True)
    if source.name != "cache":
        source = source / "cache"
    if not source.is_dir():
        raise RuntimeError(f"No cache directory at {source}")
    shutil.copytree(source, directory / "cache")
    return sorted(item.name for item in (directory / "cache").iterdir())


def prepare(directory, plan, port, heap_mib):
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
        f"level-type={plan.level_type.replace(':', chr(92) + ':')}\nlevel-seed=440044\n"
        "spawn-protection=0\nenable-query=false\nenable-rcon=false\nsync-chunk-writes=false\n")
    config = directory / "sourbycraft_config" / "sourbycraft_global_config.toml"
    config.parent.mkdir()
    config.write_text("# Isolated baseline fixture; no external plugin or version changes.\n"
                      "[viaversion]\nauto-provision=false\n"
                      "[misc.auto_update]\nenabled=false\n")
    if heap_mib < plan.minimum_heap_mib:
        raise RuntimeError(f"{plan.name} needs at least {plan.minimum_heap_mib} MiB of heap; "
                           f"got {heap_mib}. Raise --heap-mib or the baseline is heap-bound.")
    return config


def capture(jar, plan, output, args, tools):
    java, jcmd, jfr, java_version = tools
    output = output.resolve()
    competitors = competing_servers(jar, -1)
    if competitors and not args.allow_shared_machine:
        raise RuntimeError(
            "Another server is already running; a baseline needs the machine to itself:\n  "
            + "\n  ".join(competitors)
            + "\nStop it, or pass --allow-shared-machine to measure anyway (the run will "
              "not be certified).")
    config = prepare(output, plan, args.port, args.heap_mib)
    seeded = seed_cache(output, args.cache_from) if args.cache_from else []
    command = [str(java), f"-Xms{args.heap_mib}M", f"-Xmx{args.heap_mib}M", f"-XX:+Use{args.gc}",
               "-Xlog:gc*:file=gc.log:time,uptime,level,tags", "-jar", str(jar), "--nogui"]
    record = {
        "schema": "sourbycraft.baseline/1",
        "workload": {"name": plan.name, "summary": plan.summary, "level_type": plan.level_type,
                     "fidelity": list(plan.fidelity), "parameters": plan.parameters,
                     "setup_command_count": len(plan.setup),
                     "requires_connected_players": plan.requires_connected_players},
        "provenance": {
            "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
            "worktree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"])),
            "jar": str(jar), "jar_sha256": sha256(jar),
            "java_version": java_version, "jvm_args": command[1:-3],
            "jfr_settings": args.jfr_settings,
            "platform": platform.platform(), "machine": platform.machine(),
            "cpu_count": os.cpu_count(), "heap_mib": args.heap_mib,
            "warmup_seconds": args.warmup, "duration_seconds": args.duration,
            "connected_players_asserted": args.connected_players,
            "competing_servers_at_start": competitors,
            "seeded_cache_files": seeded,
            "plugins": [], "captured_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")},
        "status": "running"}
    record_path = output / "baseline.json"
    server = sampler = load = None
    try:
        server = Server(command, output)
        record["provenance"]["startup_seconds"] = server.await_ready(STARTUP_TIMEOUT)
        print(f"[{plan.name}] ready in {record['provenance']['startup_seconds']:.1f}s; "
              f"applying {len(plan.setup)} setup commands", flush=True)
        before_setup = len(server.text)
        server.apply(list(plan.setup))
        server.hold(SETTLE_AFTER_SETUP)
        setup_log = server.text[before_setup:]
        errors = [marker for marker in COMMAND_FAILURES if marker in setup_log]
        record["workload"]["setup_command_errors"] = errors
        if errors and not args.allow_command_errors:
            raise RuntimeError(f"Workload setup rejected by the server: {errors}. "
                               "The command syntax does not match this Minecraft version.")

        print(f"[{plan.name}] warming {args.warmup}s", flush=True)
        server.hold(args.warmup)

        sampler = ResidentSampler(server.process.pid)
        sampler.start()
        subprocess.run([str(jcmd), str(server.process.pid), "JFR.start", "name=SourbyBaseline",
                        f"settings={args.jfr_settings}", f"duration={args.duration}s",
                        f"filename={output / 'profile.jfr'}"], check=True, capture_output=True)
        if plan.network_clients:
            load = baseline_network.Load("127.0.0.1", args.port,
                                         plan.network_clients, plan.network_rate_per_second)
            load.start()
        print(f"[{plan.name}] measuring {args.duration}s", flush=True)

        measured = time.monotonic()
        steps = 0
        while time.monotonic() - measured < args.duration:
            if plan.steady_interval_seconds:
                for entry in plan.steady:
                    server.send(baseline_workloads.window_commands(plan, steps)
                                if entry == "@window" else entry)
                steps += 1
                server.hold(min(plan.steady_interval_seconds,
                                args.duration - (time.monotonic() - measured)))
            else:
                server.hold(min(5, args.duration - (time.monotonic() - measured)))
        record["workload"]["steady_steps"] = steps

        if load is not None:
            load.stop()
            report = load.report()
            report["latency_ms"] = baseline_metrics.distribution(report.pop("latencies_ms"))
            record["network"] = report
        sampler.stop()
        server.hold(3)                                # Let the timed recording close its file.
        server.send("perf", "tps", "mspt", "ram")
        for name, diagnostic in (("threads.txt", "Thread.print"), ("heap.txt", "GC.heap_info")):
            with (output / name).open("w") as target:
                subprocess.run([str(jcmd), str(server.process.pid), diagnostic],
                               stdout=target, check=True)
        server.send("save-all", "stop")
        shutdown = time.monotonic()
        server.process.stdin.close()
        if server.process.wait(timeout=SHUTDOWN_TIMEOUT) != 0:
            raise RuntimeError(f"Unclean exit: {server.process.returncode}")
        record["provenance"]["shutdown_seconds"] = time.monotonic() - shutdown

        record["metrics"] = baseline_metrics.collect(jfr, output / "profile.jfr",
                                                     args.duration, sampler.samples_kib)
        with (output / "jfr-summary.txt").open("w") as summary:
            subprocess.run([str(jfr), "summary", str(output / "profile.jfr")],
                           stdout=summary, check=True)
        record["certified"], record["certification"] = certify(plan, record, args)
        record["status"] = "captured"
        print(f"[{plan.name}] {'certified' if record['certified'] else 'NOT CERTIFIED'}: "
              f"{record['certification']}", flush=True)
        return record
    except BaseException as failure:
        record["status"] = "failed"
        record["failure"] = f"{type(failure).__name__}: {failure}"
        record["certified"], record["certification"] = False, "run failed"
        raise
    finally:
        for cleanup in (lambda: sampler and sampler.stop(),
                        lambda: load and load.stopped_at is None and load.stop(),
                        lambda: server and server.close()):
            try:
                cleanup()
            except Exception as problem:              # Never let one step skip the others.
                record.setdefault("cleanup_problems", []).append(str(problem))
        record_path.write_text(json.dumps(record, indent=2) + "\n")


def certify(plan, record, args):
    """Decide whether this run may be used as a comparison baseline, and say why not.

    A run is never silently downgraded: an uncertified run keeps all of its evidence
    and is simply refused as a regression reference.
    """
    reasons = []
    if plan.requires_connected_players and args.connected_players < 1:
        reasons.append("workload depends on entity activation range, which is computed "
                       "around connected players; no clients were asserted with "
                       "--connected-players, so mob AI stayed inactive")
    if not record["metrics"]["tick"]["available"]:
        reasons.append(f"tick telemetry unavailable: {record['metrics']['tick']['reason']}")
    if record["provenance"]["worktree_dirty"]:
        reasons.append("worktree is dirty, so the jar cannot be tied to a commit")
    if record["workload"].get("setup_command_errors"):
        reasons.append(f"setup commands rejected: {record['workload']['setup_command_errors']}")
    foreign = record["metrics"]["cpu"].get("foreign_fraction") if record["metrics"]["cpu"]["available"] else None
    if foreign and foreign.get("available") and foreign["mean"] > FOREIGN_CPU_LIMIT:
        reasons.append(
            f"the machine averaged {foreign['mean']:.1%} CPU on work other than this server "
            f"(peak {foreign['max']:.1%}, limit {FOREIGN_CPU_LIMIT:.0%}); the measurement "
            "was sharing the box")
    if record["provenance"].get("competing_servers_at_start"):
        reasons.append("another server was already running when this run started")
    if args.duration < 300:
        reasons.append(f"measurement window {args.duration}s is below the 300s minimum for a "
                       "stable percentile estimate")
    return (not reasons), "; ".join(reasons) if reasons else "meets baseline requirements"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--workload", default="idle",
                        choices=(*baseline_workloads.NAMES, "all"))
    parser.add_argument("--output", type=Path, required=True,
                        help="Directory to create; one subdirectory per workload with --workload all")
    parser.add_argument("--warmup", type=int, default=120)
    parser.add_argument("--duration", type=int, default=600)
    parser.add_argument("--heap-mib", type=int, default=6144)
    parser.add_argument("--gc", default="G1GC", help="JVM collector flag body, e.g. G1GC or ZGC")
    parser.add_argument("--port", type=int, default=25585)
    parser.add_argument("--jfr-settings", default="profile", choices=("default", "profile"))
    parser.add_argument("--connected-players", type=int, default=0,
                        help="Assert how many real clients the operator attached before the run")
    parser.add_argument("--allow-command-errors", action="store_true")
    parser.add_argument("--cache-from", type=Path,
                        help="Copy this run directory's bootstrap cache (or a cache/ directory) "
                             "into the new run, so it does not re-download on first boot.")
    parser.add_argument("--allow-shared-machine", action="store_true",
                        help="Start even though another server is running. The run will not "
                             "be certified; use it for a quick check, never for a reference.")
    args = parser.parse_args()
    if args.duration < 1 or args.warmup < 0:
        parser.error("duration must be positive and warmup cannot be negative")
    jar = args.jar.resolve(strict=True)
    tools = jdk_tools()
    names = baseline_workloads.NAMES if args.workload == "all" else (args.workload,)
    output = args.output.resolve()
    if output.exists():
        parser.error(f"{output} already exists; baselines are never written into an existing tree")

    results = []
    for name in names:
        plan = baseline_workloads.build(name)
        directory = output / name if len(names) > 1 else output
        results.append(capture(jar, plan, directory, args, tools))
    if len(names) > 1:
        (output / "index.json").write_text(json.dumps(
            {"schema": "sourbycraft.baseline-set/1",
             "workloads": {record["workload"]["name"]:
                           {"certified": record["certified"], "status": record["status"],
                            "certification": record["certification"]} for record in results}},
            indent=2) + "\n")
    if not all(record["certified"] for record in results):
        raise SystemExit("At least one run is not certified as a comparison baseline; "
                         "see the certification field in baseline.json")


if __name__ == "__main__":
    main()
