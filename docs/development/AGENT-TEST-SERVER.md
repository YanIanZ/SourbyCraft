# Agent test server harness

Status: **Active** (2026-10-20). `scripts/agent_testserver.py` is the only sanctioned way for an
agent to boot a server (owner decision 2026-10-20: isolated, outside the repository, time-limited,
localhost, one at a time). Tests: `scripts/test_agent_testserver.py` (fake server process, no JVM).

## What it guarantees

| Rule | How |
| --- | --- |
| Isolation | Run directory `/Volumes/Ext/Sourby/agent-testservers/<name>/`, created fresh (an existing name is refused). Refused: names that are not `[A-Za-z0-9._-]`, anything inside the repository or `$HOME`, anything else under `/Volumes/Ext/Sourby` (the owner's servers). `eula=true` is written only there. |
| Jar | `build/libs/SourbyCraft-slim.jar`, copied into the run directory, so a rebuild cannot change a running server. Missing jar (or `--rebuild-jar`): `slimServerJar` through the shared `gradle_locked.sh`. `run.json` records its SHA-256, mtime and `older_than_head_commit`; the harness does not know which commit built the jar, `/ver`'s `Git:` line does. |
| One server | `mkdir` lock `/private/tmp/claude-501/sourby-testserver.lock` with `owner.json` (token, pid, JVM pid, workdir, lifetime). Broken when the owner pid is dead or the lock is older than its lifetime + 300 s; the dead run's JVM is killed first (only if its command line names that run directory). |
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
python3 $S status --name my-check
python3 $S stop --name my-check             # console stop; kill after --timeout (180 s)
python3 $S kill --name my-check             # crash test: SIGKILL, no console stop, no save
python3 $S start --name my-check-2 --world-from my-check   # boot again over my-check's world
python3 $S start --name awf-1 --aurora-toml awf.toml       # sourbycraft_config/aurora.toml (e.g. AWF worlds)
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

Exit codes: 0 ok; 1 the check failed (expect not matched, no line, unclean stop); 2 refused
(lock busy, bad name/path, low disk, startup failure). `stop`, `restart` and `end` are refused as
console commands: lifecycle goes through `stop`.

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

A harness run is functional evidence (it boots, a command answers, it stops cleanly). It is **not**
a baseline: no JFR window, no certification, a shared machine (load average is recorded in
`hardware.json`; other agents and the owner's servers may be running), and usually a dirty tree and
a jar that predates HEAD. Never turn a number from it into a release or performance claim; use
`run_baseline.py` and [BASELINE.md](../BASELINE.md). Workloads that need connected players run with
none here, so mob AI stays inactive; the step notes say so.
