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

import baseline_client
import baseline_metrics
import baseline_network
import baseline_workloads

STARTUP_TIMEOUT = 600
# Region shutdown saves one region at a time on a single RegionShutdownThread. A
# two-hour fifty-player soak ended holding forty-six regions at roughly ten seconds
# each, so a flat two-minute budget killed the server mid-save. Budget for the serial
# walk; the duration actually taken is recorded either way.
SHUTDOWN_TIMEOUT = 900
SETUP_BATCH = 50
SETUP_BATCH_PAUSE = 0.2
RSS_INTERVAL = 1.0

# Console output that means a workload command did not take effect.
# Console output that means the server will never reach "Done (".
STARTUP_FAILURES = ("Failed to start the minecraft server", "Perhaps a server is already running",
                    "crash report has been saved", "FAILED TO BIND TO PORT")

COMMAND_FAILURES = ("Unknown or incomplete command", "Incorrect argument for command",
                    "Expected whitespace to end one argument", "Invalid or unknown entity type",
                    "Unable to summon", "Cannot place feature",
                    # Placing an entity before its forceloaded chunk finished generating.
                    # This one is quiet: the command is well-formed and the server answers
                    # in chat, so without the marker the run measures bare terrain while
                    # its summary claims an entity population.
                    "That position is not loaded")


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


# Untracked paths that can change the produced jar. An untracked file outside these
# cannot, so it must not fail a measurement: reading raw `git status --porcelain`
# counts every stray note and scratch file as a dirty tree.
BUILD_INPUT_PREFIXES = ("sourbycraft-server/", "sourbyapi/", "sourbyclip/", "Metal/",
                        "build-data/", "gradle/", "paper-server/", "canvas-server/")
BUILD_INPUT_SUFFIXES = (".gradle.kts", ".gradle", ".properties", ".patch", ".java", ".at")


def affects_build(path):
    return path.startswith(BUILD_INPUT_PREFIXES) or path.endswith(BUILD_INPUT_SUFFIXES)


def git_state():
    """The commit under measurement, and whether the tree still describes the jar.

    Tracked modifications always count. Untracked files count only when they sit where
    the build would read them; an untracked note beside the repository does not change
    what was compiled, and failing a ten-minute measurement over one is wrong.
    """
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    tracked = subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=no"], text=True).strip()
    untracked = [line[3:].strip() for line
                 in subprocess.check_output(["git", "status", "--porcelain"], text=True).splitlines()
                 if line.startswith("??")]
    relevant = sorted(path for path in untracked if affects_build(path))
    return commit, bool(tracked) or bool(relevant), sorted(untracked), relevant


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
        """Send workload setup in paced batches so setup itself does not spike a tick.

        A ``@settle <seconds>`` entry is a pause, not a command: the batch so far is
        flushed and the server is left alone, so work the previous commands started --
        terrain generation, above all -- has finished before the next group runs.
        """
        batch = []

        def flush(pause):
            if batch:
                self.send(*batch)
                batch.clear()
            self.hold(pause)

        for command in commands:
            if command.startswith(baseline_workloads.SETTLE_TOKEN):
                flush(float(command.split()[1]))
                continue
            batch.append(command)
            if len(batch) == SETUP_BATCH:
                flush(SETUP_BATCH_PAUSE)
        if batch:
            flush(SETUP_BATCH_PAUSE)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self._log.close()


# Both bootstrap downloads: the Mojang jar lands in cache/, the externalized libraries
# in libraries/. Seeding only the first still leaves a boot fetching a few hundred jars.
BOOTSTRAP_DIRS = ("cache", "libraries")


def seed_cache(directory, source):
    """Copy a previously downloaded bootstrap tree into a fresh run directory.

    A baseline should not depend on downloads succeeding. Seeding makes the run offline,
    deterministic and faster, and removes a failure mode that has already truncated two
    runs on this machine.
    """
    source = source.resolve(strict=True)
    if source.name in BOOTSTRAP_DIRS:            # Pointed straight at cache/ or libraries/.
        source = source.parent
    copied = []
    for name in BOOTSTRAP_DIRS:
        origin = source / name
        if origin.is_dir():
            shutil.copytree(origin, directory / name)
            copied.append(name)
    if not copied:
        raise RuntimeError(f"No bootstrap directories ({', '.join(BOOTSTRAP_DIRS)}) under {source}")
    return copied


def prepare(directory, plan, port, heap_mib, max_players=0):
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
        # The default cap is 20; a workload asking for more clients than that silently
        # loses the rest to "The server is full!" and measures a fraction of its load.
        f"max-players={max(20, max_players)}\n"
        # Headless clients hold altitude while moving, which a server with allow-flight
        # off treats as hovering and kicks with "Flying is not enabled on this server".
        # A load client is not a player to police; enabling flight is what lets the
        # workload include flying movement at all.
        "allow-flight=true\n"
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
    commit, dirty, untracked, untracked_build_inputs = git_state()
    if dirty and not args.allow_dirty:
        detail = (f" Untracked build inputs: {', '.join(untracked_build_inputs)}."
                  if untracked_build_inputs else "")
        raise RuntimeError(
            "The worktree has uncommitted changes that affect the build, so this run could "
            "not be tied to a commit and would not be certified." + detail
            + " Commit or stash first, or pass --allow-dirty to measure anyway.")
    competitors = competing_servers(jar, -1)
    if competitors and not args.allow_shared_machine:
        raise RuntimeError(
            "Another server is already running; a baseline needs the machine to itself:\n  "
            + "\n  ".join(competitors)
            + "\nStop it, or pass --allow-shared-machine to measure anyway (the run will "
              "not be certified).")
    config = prepare(output, plan, args.port, args.heap_mib, args.connected_players)
    seeded = seed_cache(output, args.cache_from) if args.cache_from else []
    if args.world:
        # A pre-generated world keeps terrain identical across runs and keeps generation
        # out of the measurement window. Section 85 wants the world named in provenance.
        shutil.copytree(args.world.resolve(strict=True), output / "world")
    command = [str(java), f"-Xms{args.heap_mib}M", f"-Xmx{args.heap_mib}M", f"-XX:+Use{args.gc}",
               "-Xlog:gc*:file=gc.log:time,uptime,level,tags"]
    # Extra -D properties, so a tuning question can be answered by measuring both sides
    # rather than by changing a default and hoping. They are recorded in provenance, and
    # compare_baseline treats jvm_args as pinned, so an A/B with different properties is
    # reported as provenance drift rather than silently compared.
    command += [f"-D{prop}" for prop in args.property]
    command += ["-jar", str(jar), "--nogui"]
    record = {
        "schema": "sourbycraft.baseline/1",
        "workload": {"name": plan.name, "summary": plan.summary, "level_type": plan.level_type,
                     "fidelity": list(plan.fidelity), "parameters": plan.parameters,
                     "setup_command_count": sum(
                         1 for entry in plan.setup
                         if not entry.startswith(baseline_workloads.SETTLE_TOKEN)),
                     "requires_connected_players": plan.requires_connected_players,
                     "client_roam_blocks": plan.client_roam_blocks},
        "provenance": {
            "commit": commit,
            "worktree_dirty": dirty,
            "untracked_files": untracked,
            "untracked_build_inputs": untracked_build_inputs,
            "jar": str(jar), "jar_sha256": sha256(jar),
            "java_version": java_version, "jvm_args": command[1:-3],
            "jfr_settings": args.jfr_settings,
            "platform": platform.platform(), "machine": platform.machine(),
            "cpu_count": os.cpu_count(), "heap_mib": args.heap_mib,
            "warmup_seconds": args.warmup, "duration_seconds": args.duration,
            "connected_players_asserted": args.connected_players,
            "competing_servers_at_start": competitors,
            "seeded_cache_files": seeded,
            "plugins": [], "world_source": str(args.world) if args.world else "generated fresh",
            "captured_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")},
        "status": "running"}
    record_path = output / "baseline.json"
    server = sampler = load = swarm = None
    try:
        server = Server(command, output)
        record["provenance"]["startup_seconds"] = server.await_ready(STARTUP_TIMEOUT)
        print(f"[{plan.name}] ready in {record['provenance']['startup_seconds']:.1f}s; "
              f"applying {len(plan.setup)} setup commands", flush=True)
        before_setup = len(server.text)
        server.apply(list(plan.setup))
        # Terrain generation triggered by setup continues after the last command returns.
        print(f"[{plan.name}] settling {plan.settle_seconds}s", flush=True)
        server.hold(plan.settle_seconds)
        setup_log = server.text[before_setup:]
        errors = [marker for marker in COMMAND_FAILURES if marker in setup_log]
        record["workload"]["setup_command_errors"] = errors
        if errors and not args.allow_command_errors:
            raise RuntimeError(f"Workload setup rejected by the server: {errors}. "
                               "The command syntax does not match this Minecraft version.")

        if args.connected_players > 0:
            # Real clients, because entity activation range is computed around players: without
            # them mob AI, goal selection and pathfinding never run and the profile is block and
            # chunk work only.
            print(f"[{plan.name}] connecting {args.connected_players} clients", flush=True)
            swarm = baseline_client.ClientSwarm("127.0.0.1", args.port, args.connected_players,
                                               roam=plan.client_roam_blocks)
            record["clients"] = swarm.start(timeout=120.0)
            print(f"[{plan.name}] clients: {record['clients']}", flush=True)
            if record["clients"]["in_play"] < args.connected_players:
                raise RuntimeError(f"only {record['clients']['in_play']} of "
                                   f"{args.connected_players} clients reached play: "
                                   f"{record['clients']['first_error']}")
            # Spread them over the workload's sites, so each site has a player in it rather
            # than every player standing in the spawn region.
            sites = plan.parameters.get("site_coordinates") or []
            if sites:
                moves = []
                for index in range(args.connected_players):
                    x, z = sites[index % len(sites)]
                    moves.append(f"execute positioned {x} 0 {z} positioned over world_surface "
                                 f"run tp Baseline{index:03d} ~ ~1 ~")
                server.apply(moves)
                server.hold(10)

        print(f"[{plan.name}] warming {args.warmup}s", flush=True)
        server.hold(args.warmup)

        sampler = ResidentSampler(server.process.pid)
        sampler.start()
        subprocess.run([str(jcmd), str(server.process.pid), "JFR.start", "name=SourbyCraft",
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

        if swarm is not None:
            record["clients"] = swarm.report()
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
        # A shutdown fault must not destroy the measurement. The timed recording was
        # flushed to profile.jfr above, so every metric below is still derivable from a
        # server that never exits cleanly -- and a soak that degrades badly enough to
        # stall its own shutdown is exactly the run whose evidence is worth keeping.
        # Record what happened and let certify() refuse it; do not raise.
        try:
            code = server.process.wait(timeout=SHUTDOWN_TIMEOUT)
            note = None if code == 0 else f"unclean exit: {code}"
        except subprocess.TimeoutExpired:
            note = (f"did not exit within {SHUTDOWN_TIMEOUT}s; region saves were "
                    "likely still in progress")
            server.process.kill()
            server.process.wait()
        record["provenance"]["shutdown_seconds"] = time.monotonic() - shutdown
        record["provenance"]["shutdown_clean"] = note is None
        if note is not None:
            record["provenance"]["shutdown_note"] = note

        after_commit, after_dirty, _, _ = git_state()
        record["provenance"]["worktree_dirty"] = dirty or after_dirty
        record["provenance"]["commit_moved_during_run"] = after_commit != commit
        if after_commit != commit:
            record["provenance"]["commit_at_end"] = after_commit
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
        for cleanup in (lambda: swarm and swarm.stop(),
                        lambda: sampler and sampler.stop(),
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
    if plan.requires_connected_players:
        clients = record.get("clients") or {}
        in_play = clients.get("in_play", 0)
        if in_play < 1:
            reasons.append("workload depends on entity activation range, which is computed "
                           "around connected players; none connected, so mob AI stayed "
                           "inactive")
        elif in_play < clients.get("requested", 0):
            reasons.append(f"only {in_play} of {clients['requested']} clients stayed in play")
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
    if record["provenance"].get("commit_moved_during_run"):
        reasons.append("HEAD moved while the measurement was running, so the jar and the "
                       "repository no longer describe the same thing")
    if not record["provenance"].get("shutdown_clean", True):
        reasons.append(f"shutdown was not clean ({record['provenance']['shutdown_note']}), "
                       "so the world this run leaves behind may be torn")
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
    parser.add_argument("--world", type=Path,
                        help="Copy a pre-generated world in, so terrain generation does not "
                             "happen inside the measurement window and every run measures the "
                             "same terrain.")
    parser.add_argument("--cache-from", type=Path,
                        help="Copy this run directory's bootstrap cache (or a cache/ directory) "
                             "into the new run, so it does not re-download on first boot.")
    parser.add_argument("--property", action="append", default=[], metavar="KEY=VALUE",
                        help="Extra -D system property for the server JVM; repeatable. "
                             "E.g. --property Paper.WorkerThreadCount=4")
    parser.add_argument("--allow-dirty", action="store_true",
                        help="Start with uncommitted changes. The run will not be certified.")
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
