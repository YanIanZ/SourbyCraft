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
| Jar | `build/libs/SourbyCraft-slim.jar`, copied into the run directory, so a rebuild cannot change a running server. Missing jar (or `--rebuild-jar`): `slimServerJar` through this checkout’s `scripts/gradle_locked.sh`. `run.json` records its SHA-256, mtime and `older_than_head_commit`; the harness does not know which commit built the jar, `/ver`'s `Git:` line does. |
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
python3 $S start --name ssb-1 --config-file plugins/SuperiorSkyblock2.jar=/path/SuperiorSkyblock2-2026.3.jar \
    --plugin-file plugins/SuperiorSkyblock2/modules/SSB-AuroraWorlds.jar=/path/SSB-AuroraWorlds.jar   # file inside a plugin's data folder
python3 $S start --name fawe-1 --large-plugin-jar --config-file plugins/FastAsyncWorldEdit.jar=/path/FastAsyncWorldEdit-Paper-2.15.4.jar   # plugins/<name>.jar up to 32 MiB (only those; recorded as run.json large_plugin_jars)
python3 $S start --name cache-2 --startup-cache-from cache-1 --startup-cache-corrupt truncated   # boot over a damaged startup cache
python3 $S cache-check --name cache-2       # judge that boot against the seed (exit 1 = a check failed)
# with probes=true the unload fixture runs by itself after startup: wait-for "SOURBY_UNLOAD_FIXTURE_(OK|FAILED|[A-Z_]*_WRONG)"
# and `cmd --name tp-1 unloadprobe --raw` runs it again (see docs/releases/runbooks/build-49-real-test.md, G02)
python3 $S run --name my-scn --scenario path/to/scenario.json
python3 $S cleanup --keep 5 [--dry-run]
```

With the test-plugin probes on, `cmd --name tp-1 "stallprobe global sleep 15"` blocks the global tick for 15 s
(`global|region`, `sleep|lock|spin`, 1-600 s; `STALLPROBE start/end` log lines) so the stall recorder writes
`<workdir>/logs/stalls/stall-*.txt` ([STALL-RECORDER.md](STALL-RECORDER.md)). The command itself returns at once; `stop` after
the probe ends. Test servers only: it freezes the world it runs on.

### Restart a run in its own directory (`restart`, `--auto-update`) and the updater rollback fixture

`restart --name N` boots a **finished** run again in the same directory: the jar copy (as the auto-updater
left it), `auto_update/`, config, world and port are kept; the previous boot is archived under `boots/NN/`
(`run.json`, `server.log`, `supervisor.log`) and summarised in `run.json` `previous_boots` (exit code, whether it
reached ready, jar SHA-256). It takes a free slot like `start`, is refused while the run is live, and returns once
the new boot is `ready` **or has ended** (a boot that dies is data, exit 1 unless ready). The jar provenance in
`run.json` is recomputed from the directory's jar.

`start --auto-update KEY=VALUE` (keys: `apply_mode`, `restart_mode`, `restart_delay_seconds`, `repo`, `channel`,
`check_interval_minutes`, `allow_prerelease`; written to the `[updater]` table under their Build 49 names, e.g.
`apply-mode`, `repository`) enables the auto-updater, which the harness otherwise switches off. It cannot be
combined with `--global-toml`.
It is refused unless the start also passes `--jvm-property sourbycraft.update.api-base=http://127.0.0.1:PORT`
(a loopback fake release server; the updater refuses any non-loopback value) and
`--jvm-property sourbycraft.update.release-key-file=FILE` (a release public key, so signature and manifest checks
stay on): a test server can never reach the real GitHub or install an unsigned release. Both properties are
test-only (`UpdateOverrides`, `ReleaseSignatureVerifier.resolve`), unset by default, and not config keys.
`scripts/updater_rollback_fixture.py` uses all of this for the Build 49 `.prev` rollback gate; see
[updater-rollback-fixture-2026-10-22.md](updater-rollback-fixture-2026-10-22.md).

`--world-from RUN` copies a finished (exited/failed, lock released) run's world directories —
the `level-name` directory and its `_nether`/`_the_end` siblings, nothing else — into the new run,
so a restart-persistence or post-crash boot reads that run's saves; the source is never modified
and `run.json` `world_from` records its name, exit code, stop reason, git head and jar hash. The
source's `sourbycraft_config/aurora.toml` is carried over unless `--aurora-toml` is given, so a
world stored through AWF comes back through AWF. `kill` exits 0 when the server was SIGKILLed on
request (`stop_reason` "killed on request") and the lock was released.

### Files inside a plugin's data folder (`--plugin-file`)

`--config-file` puts a jar only directly into `plugins/` (and text config files elsewhere). Some
plugins load extra jars or data from their own folder before or during `onEnable` (SuperiorSkyblock2
loads modules only from `plugins/SuperiorSkyblock2/modules/*.jar`), so
`--plugin-file plugins/<Plugin>/<path>=FILE` (repeatable) copies FILE there before boot:

- The destination must be a relative POSIX path with at least one folder below `plugins/`; refused:
  absolute paths, `..`, `.`, empty parts, backslashes, `:`, a trailing `/`, anything outside
  `plugins/`, and `plugins/<name>.jar` itself (that is `--config-file`). A destination equal to,
  inside or containing a `--config-file` destination is refused as a collision.
- The source is checked like a plugin jar: a regular file, not a symlink, at most 8 MiB, read once
  with the cap enforced on the bytes read. Any file type is accepted and copied as bytes.
- `run.json` `plugin_files` records each `dest`, `source`, `bytes` and `sha256`.
- Everything is validated before the run directory or a slot lock is created.
- Not carried over by `--world-from` or `--startup-cache-from` (those copy worlds and `plugins/*.jar`
  only); pass it again for a second boot.

### Startup-cache seeding (`--startup-cache-from`, `--startup-cache-corrupt`, `cache-check`)

Build 49 gate "Cache corruption" needs a boot over a damaged `sourbycraft_config/cache/startup-index.cache`
(`StartupCache` / `PluginStartupIndex`, see `docs/architecture/aurora-instant-startup.md`). The cache only
has entries when `plugins/` holds a jar, so the donor run needs one (the test plugin jar via `--config-file`).
`--startup-cache-from RUN` installs a finished run's cache **and its `plugins/*.jar`** (copied with their
mtimes, because an entry is reused only when size, mtime and SHA-256 match; an explicit `--config-file`
jar wins over the donor's copy). `--startup-cache-corrupt SHAPE` damages the installed cache first:

| Shape | Needs donor | What the server must do (`PluginStartupIndex.describe`) |
| --- | --- | --- |
| `empty` | no | one WARN `was not a valid startup cache and was discarded` (status CORRUPT) |
| `garbage` | no | one WARN `could not be read and was ignored` (not UTF-8: IO_ERROR) |
| `truncated` | yes | one WARN `had 1 corrupt entry discarded` (last entry torn mid-line, rest kept) |
| `flipped-entry` | yes | one WARN `had 1 corrupt entry discarded` (entry hash fails) |
| `wrong-version` | yes | one WARN `was written by a different Minecraft/ABI/format/Java and was discarded` (format field +1) |
| `stale-hashes` | yes | **no WARN**: entries stay valid but their jar SHA-256 no longer matches, so they are misses, rebuilt |
| `unreadable` | yes | one WARN `could not be read and was ignored` (valid bytes, mode 000) |

With `--startup-cache-from` and no shape the donor cache is copied unchanged, which is the warm second boot
(`cache-check` then expects no cache WARN and `warm start (hit N, miss 0)`). The seed is recorded in
`run.json` as `startup_cache` (shape, donor, bytes, SHA-256, mode, jars carried). `cache-check --name N` reads
the run's log and cache file and prints one JSON verdict: `booted` (Done line), `index_line` (the `Aurora
startup index:` line), `plugins_enabled` (an `Enabling` line), `cache_valid_after` (header, env and every
entry pass the same integrity checks as `StartupCache.decode`), plus per shape `one_clear_warning` (exactly
one `Aurora startup cache` line and it matches the table), `entries_rebuilt` (miss >= 1) and `cache_changed`
(not for `unreadable`, whose rebuilt bytes can equal the seed). Exit 0 only when every check holds. Scenario
`settings` accept `startup_cache_from` / `startup_cache_corrupt`. Functional evidence only: it proves the
recovery path of the running jar, not that the cache makes boot faster.

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

The 2026-10-21 command-block probe's region-thread replies arrived after the end marker.
This is the capture timing limit above, not a log-prefix matching bug: markers are recognized
anywhere in a line. A closed window with no output returns exit 1 even if the command later
succeeds. Use `cmd --raw --expect '<reply>'` for such commands; raw output is uncorrelated.
The existing delayed-reply test pins this behavior. The rebuild-wrapper path is now repository
local; its path regression test passes, but no Gradle build was run for this change.

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
