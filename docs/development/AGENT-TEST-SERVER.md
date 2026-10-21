# Agent test server harness

Status: **Active** (2026-10-20). `scripts/agent_testserver.py` is the only sanctioned way for an
agent to boot a server (owner decision 2026-10-20: isolated, outside the repository, time-limited,
localhost). At most **2 functional servers** run at once (one per slot); `--exclusive` holds both
slots for anything performance-related (board task testserver-slots, 2026-10-20). Tests:
`scripts/test_agent_testserver.py` (fake server process, no JVM).

## What it guarantees

| Rule | How |
| --- | --- |
| Isolation | Run directory `/Volumes/Ext/Sourby/agent-testservers/<name>/`, created fresh (an existing name is refused). Refused: names that are not `[A-Za-z0-9._-]`, anything inside the repository or `$HOME`, anything else under `/Volumes/Ext/Sourby` (the owner's servers). `eula=true` is written only there. |
| Jar | `build/libs/SourbyCraft-slim.jar`, copied into the run directory, so a rebuild cannot change a running server. Missing jar (or `--rebuild-jar`): `slimServerJar` through the shared `gradle_locked.sh`. `run.json` records its SHA-256, mtime and `older_than_head_commit`; the harness does not know which commit built the jar, `/ver`'s `Git:` line does. |
| Slots | Two `mkdir` slot locks, each with `owner.json` (token, pid, JVM pid, workdir, port, Redis port, lifetime): slot 1 = `/private/tmp/claude-501/sourby-testserver.lock` (the historical single-server lock, so a supervisor started before slots existed still counts as "slot 1 busy"), slot 2 = `sourby-testserver-slot-2.lock` beside it. `start` takes the first free slot; with both busy it is refused (exit 2) naming both holders. Each slot lock is broken on its own when its owner pid is dead or it is older than its lifetime + 300 s; the dead run's JVM is killed first (only if its command line names that run directory). |
| Exclusive | `--exclusive` first takes `sourby-testserver-exclusive.lock` (from then on every functional `start` is refused with "an --exclusive run holds or waits for every slot"), then waits for slot 1 and slot 2 in turn (default up to 3600 s, `--exclusive-wait`), and holds all three locks for the whole run; the supervisor releases them together. Giving up releases whatever it had taken. While waiting, the locks carry lifetime + wait so they are not broken as stale; a dead waiter's locks are broken by the next start. Use it for anything performance-related. It still does not make the machine quiet (Gradle builds, other agents and the owner's servers keep running): not a certified baseline. |
| Per-slot ports | Server port: a random free localhost port, excluding the other slot's port and the Redis ports. Redis convention for a scenario that starts its own `redis-server`: **slot N uses port 6400 + N** (slot 1 = 6401, slot 2 = 6402; an exclusive run uses 6401). `run.json` `slot.redis_port` and `status` show it; the harness itself never starts Redis. Each run has its own workdir (`<name>/`) as before. |
| Resources | Before a server starts (after its slot is taken; released again on refusal): refused when less than **3 GiB RAM** is available or **more than 2 Gradle builds** run. RAM on macOS: `vm_stat` (free + inactive + speculative + purgeable pages) × page size; active, wired and compressor pages are not counted, so it is lower than `memory_pressure`'s "free percentage" (one sample on 2026-10-20: vm_stat 2.78–3.0 GiB vs memory_pressure 35 %). Linux: `MemAvailable`. Gradle builds: running Gradle client processes (`GradleWrapperMain` / `GradleMain`); idle daemons do not count. Both are one point sample recorded in `run.json` `resources_at_start`, not a reservation. |
| Network | `server-ip=127.0.0.1`, random free port, `online-mode=false`, rcon/query off; these properties cannot be overridden. ViaVersion/ProtocolLib provisioning and auto-update off. |
| Size | `view-distance=4`, `simulation-distance=5`, 2048 MiB heap, `minecraft:normal`, seed 440044, all overridable. |
| Lifetime | Default 20 min (`--max-lifetime`, 60 s to 3 h). A watchdog thread in the supervisor sends `stop` at the deadline and kills the JVM process group 30 s later. |
| Lock release | A detached supervisor owns the JVM, its stdin, the watchdog and the lock, and releases the lock in `finally` (normal exit, crash, SIGTERM/SIGINT/SIGHUP, startup failure). A SIGKILLed supervisor leaves a lock that the next `start` breaks. |
| Disk | `start` refuses below 20 GiB free on the base volume. `cleanup --keep N` deletes old run directories (only ones carrying the `.agent-testserver` marker, never a live one). |
| Downloads | The first boot downloads the Mojang jar and libraries (SourbyClip). After a clean exit, `cache/` + `libraries/` are kept in `agent-testservers/_bootstrap-cache/` and seeded into later runs (`run_baseline.seed_cache`). |

Reused from `run_baseline.py`: startup failure markers, the Aurora boot summary regex,
`git_state`, `sha256`, `jdk_tools` (asserts JDK 25), `seed_cache`, `competing_servers`,
`COMMAND_FAILURES`, and `Server.apply` for workload setup; `verify_persistence.await_console` for
the "console actually answers" check after `Done (`.

## Commands

```sh
S=scripts/agent_testserver.py
python3 $S start --name my-check            # waits for "Done (" (600 s timeout), prints workdir/port/boot time
python3 $S cmd --name my-check "ver" --expect "Build"   # prints the log text the command produced
python3 $S cmd --name my-check "perf chunks"
python3 $S wait-for --name my-check "Saved all worlds" --new --timeout 60
python3 $S log --name my-check --lines 80
python3 $S status --name my-check            # every slot: owner, port, redis_port; free_slots; exclusive
python3 $S start --name perf-1 --exclusive   # waits for both slots, blocks new starts (measurements)
python3 $S stop --name my-check             # console stop; kill after --timeout (180 s)
python3 $S kill --name my-check             # crash test: SIGKILL, no console stop, no save
python3 $S start --name my-check-2 --world-from my-check   # boot again over my-check's world
python3 $S start --name awf-1 --aurora-toml awf.toml       # sourbycraft_config/aurora.toml (e.g. AWF worlds)
python3 $S start --name tp-1 --config-file plugins/SourbyCraft-Test-Plugin.jar=test-plugin/build/libs/test-plugin-1.0.0.jar \
    --jvm-property sourby.testplugin.probes=true    # plugin jar (bytes, <= 8 MiB, run.json keeps its sha256) + test-plugin probes
python3 $S run --name my-scn --scenario path/to/scenario.json
python3 $S cleanup --keep 5 [--dry-run]
```

`--world-from RUN` copies a finished (exited/failed, lock released) run's world directories —
the `level-name` directory and its `_nether`/`_the_end` siblings, nothing else — into the new run,
so a restart-persistence or post-crash boot reads that run's saves; the source is never modified
and `run.json` `world_from` records its name, exit code, stop reason, git head and jar hash. The
source's `sourbycraft_config/aurora.toml` is carried over unless `--aurora-toml` is given, so a
world stored through AWF comes back through AWF. `kill` exits 0 when the server was SIGKILLed on
request (`stop_reason` "killed on request") and the lock was released.

`status` keeps `lock`/`lock_path` meaning slot 1 (the old single lock) and adds `slots` (number,
lock path, busy, owner, redis_port), `free_slots`, `exclusive` and `any_busy`. `run.json` has
`slot` (number, exclusive, slots_held, redis_port, lock_paths), `lock.paths` and `resources_at_start`.

Exit codes: 0 ok; 1 the check failed (expect not matched, no line, unclean stop); 2 refused
(both slots busy, exclusive run active, bad name/path, low disk, low RAM, too many Gradle
builds, `--exclusive` wait timeout, startup failure). `stop`, `restart` and `end` are refused as
console commands: lifecycle goes through `stop`.

### Output capture (`cmd`, scenario `command`/`perf` steps)

`cmd` sends `help sc-capture-<token>-begin`, the command and `help sc-capture-<token>-end` in ONE
spool file (token = 96 random bits per call) and returns only the log lines between the two
markers (the markers are not returned). Why `help`: for an unknown topic the Bukkit HelpCommand
logs `No help for <topic>` synchronously on the console thread (`HelpCommand` ->
`ConsoleCommandSender.sendMessage` -> `System.out`), run in order by `handleConsoleInputs`. `say` is
not used: on this branch it decorates the chat on an executor and broadcasts through the task queue,
so its line can be logged out of order. The supervisor writes one spool file to the console without
interruption, so another `cmd` process or a churn loop (separate spool files) cannot land between
the markers.

A capture counts only when BOTH markers were logged, in order. Otherwise the call fails
(`ok` false, exit 1) with `delivery uncertain: ...` (begin missing, end missing, or end logged
before begin) plus any partial output labelled uncorrelated; a begin-less result does not claim the
command did not run (a plugin may replace `help`). `--expect` is matched inside the closed region
only. Limits, not detected: a reply logged BEFORE the begin marker (a command that is not
synchronous on the console thread) is lost; output logged after the end marker (it answers on a
later tick or another thread, e.g. `perf player <name>`) is not captured; unrelated server log lines
logged between the markers are. The two `help` lines also appear in the server log. For delayed
replies use `cmd --raw` or a scenario step with `"bracket": false` (allowed on `command` and
`perf` steps): every new log line after sending, which DOES leak concurrent commands' output (seen
in run asyncpath-soak-2: `count-*` outputs with "Successfully filled N block(s)" from a parallel
loop). `wait-for` is not correlated; it matches log lines, not commands.

## Scenario file

```json
{
  "settings": {"view_distance": 4, "heap_mib": 2048, "max_lifetime_s": 900},
  "steps": [
    {"command": "ver", "expect": "Build \\d+", "save_as": "ver.txt"},
    {"perf": ""},
    {"perf": "chunks"},
    {"workload": "idle", "duration": 60},
    {"wait_for": "Done \\(", "timeout": 10},
    {"sleep": 5}
  ]
}
```

One action per step (`command`, `wait_for`, `sleep`, `perf`, `workload`); optional `label`,
`continue_on_failure`, `timeout`, `expect`, `save_as`, `new_only`, `duration`. The file is validated
before anything boots. `workload` applies a `baseline_workloads.py` plan (setup + settle, then the
steady phase for `duration` seconds) to the running server; plugin workloads are refused. Outputs:
`outputs/NN-<kind>.txt`, `scenario-summary.json` (steps, boot time, exit code, lock release,
hardware, git, jar), `hardware.json`, `run.json`, `server.log`, `commands.log`.

## What the output is not

A harness run is functional evidence (it boots, a command answers, it stops cleanly). Two
functional runs share the machine with each other; a timing taken while another slot runs is not
even comparable between runs, so take timings only with `--exclusive`. It is **not** a baseline: no JFR window, no certification, a shared machine (load average is recorded in
`hardware.json`; other agents and the owner's servers may be running), and usually a dirty tree and
a jar that predates HEAD. Never turn a number from it into a release or performance claim; use
`run_baseline.py` and [BASELINE.md](../BASELINE.md). Workloads that need connected players run with
none here, so mob AI stays inactive; the step notes say so.
