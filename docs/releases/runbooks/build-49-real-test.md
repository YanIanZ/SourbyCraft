# Build 49 real-test runbook

## One-page tester checklist

Human testers and operators own all testing from Build 49 onward (owner direction,
2026-10-21). Agents prepare this handover; they do not execute these runs. Check a box
only after attaching evidence and recording PASS, FAIL or BLOCKED. No gate is passed here.
The gate sections retain the authoritative order; this checklist orders execution by risk.

- [ ] [g01-ci — Compile, unit/integration, patch regeneration](#g01-ci)
- [ ] [g07-plugins — Plugin compatibility](#g07-plugins)
- [ ] [r01-pearl — Ender pearl: cancellation and redirect](#r01-pearl)
- [ ] [r08-teleport — Owned teleport plugin API](#r08-teleport)
- [ ] [r02-command-blocks — Command blocks across two regions](#r02-command-blocks)
- [ ] [r04-respawn — Bed-less respawn and End credits](#r04-respawn)
- [ ] [r03-functions — Scheduled functions and datapack — CONDITIONAL on MC 0066](#r03-functions)
- [ ] [r06-trader — Wandering trader spawn — CONDITIONAL on MC 0068](#r06-trader)
- [ ] [g02-ownership — Region ownership](#g02-ownership)
- [ ] [g03-persistence — World load/unload/save + crash recovery](#g03-persistence)
- [ ] [g09-backend — Backend failure](#g09-backend)
- [ ] [g10-cache — Cache corruption](#g10-cache)
- [ ] [g11-security — Security](#g11-security)
- [ ] [g12-bootstrap — Bootstrap / network](#g12-bootstrap)
- [ ] [r07-forceload — Forceload after crash — CONDITIONAL on MC 0067](#r07-forceload)
- [ ] [g08-shutdown — Shutdown](#g08-shutdown)
- [ ] [g04-soak — Soak](#g04-soak)
- [ ] [g05-recovery — Heap/thread/queue stabilization](#g05-recovery)
- [ ] [g06-io — Region threads never block on external I/O](#g06-io)
- [ ] [g13-supply-chain — Reproducibility + supply chain](#g13-supply-chain)
- [ ] [g14-licence — Attribution / licence](#g14-licence)
- [ ] [g15-claims — Claims audit](#g15-claims)
- [ ] [r05-hud — HUD bars with a real client](#r05-hud)
- [ ] [g16-publication — Publication](#g16-publication)

## Shared run requirements

These requirements are part of **every** Preconditions and Capture field below.
Use the [Build 49 gate document](../26.2-build-49.md) for gate decisions and
[roadmap](../ROADMAP-47-50.md) for scope. Do not change gameplay fidelity or defaults
to improve a result. Functional, MEASURED, CERTIFIED and QUALIFIED have distinct
meanings under [DEVELOPMENT.md](../../DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy).
Build 49 stability evidence need not be a certified performance baseline; certified
baselines and optimization belong to Build 50.

### Preconditions for every run

- Download `SourbyCraft-26.2-REL.jar` from the official
  [Build 49 release tag](https://github.com/YanIanZ/SourbyCraft/releases/tag/v26.2-r49)
  when published. Do not substitute a local build or a Build 47/48 jar. Verify its
  SHA-256 against the release document or release-tag asset digest before boot.
  **SHA-256: <fill in from release tag>**. Record the tag commit and `/ver` output.
  If the release or digest is unavailable, mark BLOCKED. Pre-tag CI evidence must
  identify the same commit and bytes eventually published; reconcile it after tagging.
- Use a human-operated, backed-up test server, not a production world for destructive
  checks. Host: named OS/version, **Temurin JDK 25**, CPU model/core quota, RAM/heap,
  disk type/free space, container limits, network class and competing load recorded.
  Suggested hardware class for repeatable multi-player runs: dedicated Linux host,
  8 available CPU cores and 8 GiB RAM or more; this is a runbook starting point,
  not a source-defined qualification threshold. Record smaller/larger hosts exactly.
  Bootstrap additionally requires a remote Linux host; network failure tests require
  a separate Redis host. Keep private addresses and access details out of evidence.
- Preserve and attach sanitized effective copies of `config/canvas-server.yml`,
  `config/canvas-worlds.yml`, `config/paper-global.yml` (especially `threaded-regions`:
  `threads`, `grid-exponent`, `scheduler`), `sourbycraft-security.yml`, and
  `sourbycraft_config/sourbycraft_global_config.toml`. List **every non-default value**;
  use `<fill in>` until captured. Also attach `server.properties`, JVM flags and,
  where used, `sourbycraft_config/aurora.toml`. Do not infer current defaults from
  historical runs. Start a fresh JVM after config changes unless a cited consumer
  explicitly supports LIVE changes. Scheduler sizing is in Paper global YAML,
  not the Canvas files. Record `region-scheduler.guard-severity` (default `LOG`).
- Unless a section says otherwise, install **P10**: the `plugins-10` set from
  [representative-plugins.json](../../../build-data/representative-plugins.json):
  EssentialsX (`Essentials`, 2.22.1-dev+25-cfb6f12) and MilkBowl Vault 1.7.3-b131
  bridged; SuperiorSkyblock2 2026.3 Folia port native with its SSB-AuroraWorlds module;
  spark bundled (no replacement spark jar). Record actual jar versions and hashes.
  `plugins-10` names a workload, not ten plugin jars. Set `aurora.bridge.mode = "safe"`
  in Aurora TOML and restart before testing. Record any real-site additions separately,
  including EconomyShopGUI, ViaVersion/ViaBackwards, ProtocolLib and dependents.
- Minimum counts below are runbook coverage minima where the gate specifies no number.
  They mean real people on real game clients, with operator-observed joins and actions;
  synthetic protocol clients are supplemental evidence only. Sourby Demo requires
  authenticated clients (`online-mode=true`). A script that rewrites properties or
  launches its own fixtures must run on a separate disposable instance.

### Capture for every run

Create the evidence directory when collecting actual evidence:
`docs/releases/evidence/49/<gate-id>/`. This runbook creates no evidence directories.
Use `<YYYYMMDDTHHMMSSZ>-<host-label>-<scenario>-<artifact>.<ext>` for attachments;
script-generated names remain unchanged inside a run-specific subdirectory.
Capture server-relative `logs/latest.log` before rotation, previous boot logs, full
console transcript, `/ver` line, jar filename/SHA-256, config changes, plugin inventory,
player count/names (consented tester handles), timestamps and operator observations.
Use relative raw-file links in the [evidence template](#evidence-template).

Capture `/perf`, `/perf memory`, `/perf lanes`, `/perf scheduler`, `/perf governor`,
`/perf plugins`, `/perf awf` at idle, under load and after load removal; record
unavailable metrics as unavailable, never zero. Include OS thread counts and thread
stack dumps; `/perf` cannot prove uninstrumented queues are empty. Record a spark
report URL and exported report for gameplay/load checks, and screenshots for visible
behaviour. `/spark profiler start` is referenced by owned command views; further
spark syntax, including `/spark profiler stop`, is **(verify command exists)** against
the bundled version before use. Redact private endpoints, credentials and tokens.

**Where to find stall dumps.** Build 49 records a hung tick by itself
([STALL-RECORDER.md](../../development/STALL-RECORDER.md)). On a hosted panel with no shell, open the
file manager at `logs/stalls/` (next to `logs/latest.log`): each `stall-<UTC time>.txt` holds, for one
stall, up to six dumps taken 10 s apart (full thread dump with lock owners, the stalled thread, heap,
RSS, container `memory.current`/`memory.max`/`memory.events`, GC counts, loaded chunks/entities/players),
plus a final dump if the JVM was told to shut down during the stall. The console shows one WARN,
`Server stall: the global tick has completed no tick for N s ... written to logs/stalls/stall-....txt`,
when it starts. Download the **whole directory** before restarting the server when a run hangs, stops
answering or dies, and attach it to the evidence folder next to `logs/latest.log` and the panel console
history; `memory.events` `oom_kill > 0` in a dump is the kernel's own record of an OOM kill. An empty or
missing `logs/stalls/` after a hang is itself a result (recorder disabled with
`aurora.diagnostics.stall-recorder.enabled = false`, a boot hang before the first tick, or a kill before
the first dump): record it, do not infer a cause. The recorder detects "no tick completed for 10 s",
not slow ticks; it is RESTART_REQUIRED and on by default.

Owned command registration and syntax were checked in `SourbyCraftCommands`,
`PerfViews`, `AwfCommand`, `PluginsCommand`, `UpdateCommand`, `HudBarCommand` and
`PerfBarCommand` under `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/`.
Use leading `/` in game, omit it in the console. Vanilla console `stop` is documented
by the harness and watchdog sources; its registration is outside owned command code
**(verify command exists)**. Vanilla commands explicitly flagged below also need
runtime help/registration verification before use. Do not invent plugin test commands:
record the installed plugin's documented command or GUI action before running it.

### Failure procedure F (every gate and runtime check)

For a hang, a stall or an unexplained process death, first save `logs/stalls/` (see "Where to find
stall dumps" under Capture for every run) before the server restarts; the panel may rotate or clear
`logs/latest.log` and the next boot starts a fresh recorder, but the stall files stay until 20 newer ones exist.

Record FAIL for an observed defect and BLOCKED for missing prerequisites. File an
issue with the gate/check id, evidence folder path, exact `/ver` line, jar SHA-256,
config/non-default values, plugin hashes, human actions, expected/actual result and
reproduction timestamps. Attach raw logs, not only screenshots. Never tick PASS
because a command accepted work that later failed. Owner exceptions must be written
in the [gate document](../26.2-build-49.md); do not weaken its criteria here.

Rollback follows [AUTO-UPDATER.md](../../development/AUTO-UPDATER.md#previous-jar-and-rollback-sec-3--updaterollbackguard):
stop the server; if a valid `<jar>.prev` exists, copy it over the active launch jar
(keep `.prev`), then restart and record identity/readiness. The updater creates it
before a successful automatic swap; a manual jar install does not guarantee one.
If missing, mark rollback BLOCKED and ask the operator for the known prior artifact.
This restores jar bytes only; no world/config restoration is promised.

For the **automatic** rollback scenario: apply writes `APPLIED`; the first boot
records `BOOTING`; `ServerLoadEvent STARTUP` marks `OK`. A later boot seeing `BOOTING`
copies `.prev` over the launch jar and records `ROLLED_BACK`, holding updates.
That JVM still runs the updated bytes: stop/restart again to execute the restored
jar. A launcher failure before bootstrap can leave `APPLIED`, delaying restoration
another cycle. Missing/failed copies yield `RESTORE_FAILED`. Capture `/update status`;
use `/update resume` only after diagnosis. Windows file locking may refuse the swap;
do not report a POSIX apply/rollback PASS from that fallback.

## Build 49 gates

Each Pass criteria field below reproduces its gate's **Evidence required** cell verbatim.


<a id="g01-ci"></a>

### G01. Compile, unit/integration, patch regeneration

**Goal:** Verify CI and patch/identity evidence for the exact tag commit.

**Preconditions:** Shared requirements; Linux CI host and recorded runner hardware; P10 boot inventory; minimum 0 players for CI review. The human gameplay gates still apply.

**Steps:**

1. Open the tag commit’s full CI run, not only the PR checks. Verify compile, Java/Python suites, patch regeneration and boot/shutdown steps actually ran; attach job/step results.
2. Have the release maintainer supply `patch_surface.py --check-rebuild` output from that commit and build identity verification. Do not rebuild during this documentation task.
3. Boot the release jar under operator control, run `/ver`, and compare its clean identity with the CI artifact and tag.

**Capture:** Common capture plus CI run/step links, patch-rebuild output, clean identity and common capture; record skipped steps explicitly. Store in `docs/releases/evidence/49/g01-ci/` using the shared filename pattern.

**Pass criteria:** green CI on the tag commit with every step; `patch_surface.py --check-rebuild` clean; clean build identity

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g02-ownership"></a>

### G02. Region ownership

**Goal:** Show that ownership checks survive watchdog changes and normal client load.

**Preconditions:** Shared requirements; P10; minimum 2 players in demonstrably distinct regions. Use a disposable server and an operator-reviewed stall/wrong-owner probe; probe invocation is (unverified), BLOCKED until supplied.

**Steps:**

1. Run watchdog acceptance cases 1–4 from [REGION-WATCHDOG.md](../../development/REGION-WATCHDOG.md): new JVM unset, then explicit `-Dsourbycraft.region-watchdog.enabled=false`; confirm no `Folia Watchdog Thread`, normal ticks, saves and clean stop.
2. Restart with `-Dsourbycraft.region-watchdog.enabled=true` (RESTART_REQUIRED). Trigger the reviewed controlled stall and capture diagnostics. Repeat disabled direct-start/add/remove retention checks; the diagnostic loop must not accumulate records.
3. Trigger the reviewed wrong-owner probe with the flag off and on; record the ownership error, scheduler handling and bridge quarantine. Do not count this deliberate fault as a clean soak.
4. Run the separate clean 2 h+ soak in G04 and inspect every ownership violation. Attach the tag CI fixture that queues region/entity work and then unloads its world (the `SOURBY_UNLOAD_FIXTURE_*` markers of the CI boot step; how to read them is below).

**The unload fixture (step 4's CI fixture).** The test plugin's `UnloadFixtureProbe` (loaded only with `-Dsourby.testplugin.probes=true`, which the CI boot step now passes; the other probes need players or commands) runs once after `ServerLoadEvent` STARTUP and on the console command `unloadprobe`. It creates a temporary AWF world (`unloadfixture`, void generator, through `AuroraWorlds`), keeps four chunks 256 chunks apart loaded with plugin tickets so the world has at least four regions, and on each region's own thread summons two armor stands and schedules region-scheduler and entity-scheduler tasks that are only due in an hour (delayed and fixed-rate). It then puts one task on each region that queues more work for the region's next tick (`RegionScheduler#execute`, `#run`, entity `#run`) and holds the region thread (at most 2 s plus 150 ms, the only blocking in the fixture) while the global tick calls `Server#unloadWorldAsync`, so that work is still queued when the engine drains the world. Afterwards it reads every task's fate, waits 60 global ticks, checks that the world is gone from Bukkit and that this JVM holds no open file under the world folder (`/proc/self/fd`, else `lsof`; `openFiles=unchecked` when neither exists, which is not a pass), then deletes the world through `AuroraWorlds`. Markers (log substrings; `_WRONG` and `_FAILED` fail the CI step): `START`, `WORLD_OK regions=N` (fewer than 2 regions is `REGIONS_WRONG`), `QUEUED_OK`, `UNLOAD_OK`, `ACCOUNTED_OK` (`tasks ran retired cancelled leaked ranDuringUnload farFutureRanAtUnload executeNotRun ranAfterUnload`), `NOTE_DRAIN_RAN_BODIES` (informational), `QUIET_OK`, `RELEASED_OK`, and the final `OK`, all prefixed `SOURBY_UNLOAD_FIXTURE_`.

How to read it. PASS needs every marker, `leaked=0`, `ranAfterUnload=0`, no task body that threw or ran in a region that did not own its target, and no `_WRONG`/`_FAILED`. The CI step also fails on the engine's `Failed to drain|retire an entity scheduler|cancel region scheduler tasks|cancel queued region tasks` errors, and when `executeNotRun` (queued `execute` tasks that never ran) exceeds the `... and N queued region task(s)` count in the engine's `Drained unloadfixture for unload:` line (that is a leak). What the numbers mean: the 12 region-scheduler tasks (8 delayed, 4 fixed-rate) must show as cancelled; `executeNotRun` is the cancel-only queue path (non-zero when the hold works; zero means the region won the race and ran its queue, which still passes); `ran` can include tasks that ran after the unload was requested but before it completed. **Known finding (reported, not hidden):** `NOTE_DRAIN_RAN_BODIES` with `retiredCallbacks=0` means the unload's `EntityScheduler#retire` ran the entity task bodies, including ones due in an hour, on the world's shutdown thread instead of invoking their retired callbacks: `FoliaEntityScheduler.EntityScheduledTask#accept` chooses the retired callback only when `entity.isRemoved()`, and the drain retires schedulers without removing entities. `WorldUnloadDrain`'s documentation says the retired callbacks run. Whether a plugin's one-hour task firing at unload is acceptable is an owner decision; the fixture asserts only what the engine guarantees today (one of run or retired, never both, never after the unload completed, nothing leaked). Not covered: a player in the world, a cross-region teleport in flight, a region stuck in a long tick, the Redis backend, region-file (non-AWF) worlds, chunk-load tasks for chunks outside any region.

Manual rerun on the agent test server: `python3 scripts/agent_testserver.py start --name own-1 --config-file plugins/SourbyCraft-Test-Plugin.jar=test-plugin/build/libs/test-plugin-1.0.0.jar --jvm-property sourby.testplugin.probes=true`, then `wait-for --name own-1 "SOURBY_UNLOAD_FIXTURE_(OK|FAILED|[A-Z_]*_WRONG)"` (the fixture also starts by itself about 40 ticks after startup); `cmd --name own-1 "unloadprobe" --raw` runs it again in `unloadfixture2`.

**Capture:** Common capture plus Thread dumps for each flag state, probe source/hash/invocation, diagnostics, ownership/quarantine counters, CI fixture (the `SOURBY_UNLOAD_FIXTURE_*` log lines and the `Drained ... for unload` line) and G04 log. Store in `docs/releases/evidence/49/g02-ownership/` using the shared filename pattern.

**Pass criteria:** watchdog acceptance (`REGION-WATCHDOG.md` cases 1–4: no `Folia Watchdog Thread` with the flag off, flag on with a controlled stall, controlled wrong-owner access); soak log with zero ownership violations; CI fixture that queues region/entity tasks then unloads the world

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g03-persistence"></a>

### G03. World load/unload/save + crash recovery

**Goal:** Demonstrate durable world/player state and honest crash recovery on region files and AWF.

**Preconditions:** Shared requirements; P10 plus operator-provided persistence fixtures; minimum 2 human players for live-world checks. Local script clients are supplemental. Back up disposable region-file and AWF worlds; record Aurora TOML backend settings.

**Steps:**

1. On separate disposable fixtures run `python3 scripts/verify_persistence.py <release-jar> --output <fresh-run-dir> --restarts 3 --load-clients 6 --load-seconds 45 --keep`; retain `persistence.json`. This CLI has no `--awf` flag.
2. Run `python3 scripts/verify_crash.py <release-jar> --output <fresh-region-run-dir> --clients 4 --keep`, then a separate run with `--awf`. Retain both `crash.json` files; review expected failures and unexpected passes individually.
3. On the human-operated server, build identifiable blocks, store items and reconnect/restart; verify each player’s saved inventory/location and world state. Repeat region-file and AWF scenarios. Observe actual durable AWF commits through `/perf awf`, not only a save acknowledgement.
4. For an AWF world named `<world>`, try `/awf unload <world>` while players remain online and capture the result. Move players out, create dirty chunks, run `/awf unload <world> nosave`, then `/awf load <world>` and inspect saved versus discarded state.
5. On a separate panel fixture run `python3 scripts/verify_persistence_panel.py --panel <panel-url> --server <server-id> --restarts 3` and `python3 scripts/verify_crash_panel.py --panel <panel-url> --server <server-id>`. The crash script refuses online players; retain text and repeat human gameplay separately. Repeat with the fixture configured for each backend; neither panel CLI has `--awf`.
6. Reproduce the panel’s 529-chunk initial commit; timestamp generation, enqueue, durable completion and region responsiveness. Investigate the latency with stacks and a spark report; attach a cause or mark investigation BLOCKED.

**Capture:** Common capture plus `persistence.json` (`checks`, `passed`, `restarts`, `load_clients`); region/AWF `crash.json` (`mode`, `kill_delays_s`, `checks`, `passed`, `expected_failures`, `unexpected_passes`); panel stdout/exit status (no JSON), live-client notes, unload replies and 529-chunk timeline. Store in `docs/releases/evidence/49/g03-persistence/` using the shared filename pattern.

**Pass criteria:** `verify_persistence*.py` and `verify_crash*.py` (region files and `--awf`) on the 49 jar, with clients, on a named host; AWF unload with players online and `unload nosave` with dirty chunks; panel 529-chunk initial-commit latency investigated

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g04-soak"></a>

### G04. Soak

**Goal:** Complete a continuous multi-hour soak with real player and plugin activity.

**Preconditions:** Shared requirements; stable named host reserved for more than 2 h plus recovery; P10; minimum 10 human players throughout the measurement window. Record interruptions rather than joining partial windows.

**Steps:**

1. Record idle heap/thread/queue baselines and all config/plugin hashes; connect ten authenticated clients on Sourby Demo.
2. For at least 2 h continuously, roam separate regions, generate/load chunks, build, trade, teleport and use island/economy workflows. Record the actual plugin commands/UI actions and which player drove them.
3. Every 60 s record timestamped `/perf` and memory/thread/queue samples, player count and error/violation totals. Keep raw series, not only averages.
4. Remove the workload and disconnect players; continue sampling through recovery. Explain any retained threads/queues/heap and attach G05 results.

**Capture:** Common capture plus TPS/MSPT, heap, threads and queue time series; player attendance/actions, spark report, full log and error/ownership/bridge violation counts. If separately used, `bench_panel.py` produces `bench.json`; its synthetic workload cannot replace this human soak. Store in `docs/releases/evidence/49/g04-soak/` using the shared filename pattern.

**Pass criteria:** 2 h+ with real clients and the `plugins-10` set: TPS/MSPT series, heap series, thread and queue counts before/after load removal, error and violation counts

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g05-recovery"></a>

### G05. Heap/thread/queue stabilization

**Goal:** Check post-load recovery and real-player disconnect retention.

**Preconditions:** Shared requirements; P10; minimum 10 humans for the G04 recovery observation; use a separate same-jar fixture for the baseline runner and record its actual clients/hardware.

**Steps:**

1. Sample idle, load and post-disconnect heap/thread/queue states on the real server. Repeat joins/disconnects and inspect retained-player objects with operator tooling; record method and timestamps.
2. On a disposable runner host execute `python3 scripts/run_baseline.py <release-jar> --workload plugins-10 --plugins-dir <plugin-directory> --output <fresh-run-dir> --connected-players 10 --warmup 120 --duration 600 --recovery 900`. Record who actually connected; the flag alone is not attendance evidence.
3. Read `baseline.json` certification and fidelity, `heap_recovery`, thread/queue samples and raw heap files. Forced full GC is part of this script; keep that result separate from unforced recovery on the real server.
4. Compare threads/queues against the captured idle baseline after workload removal. Treat uninstrumented queues and unexplained player retention as open.

**Capture:** Common capture plus `baseline.json` provenance, certification/status, metrics and `heap_recovery`; `profile.jfr`, `gc.log`, `server.log`, `threads.txt`, `heap.txt`, `heap-recovery-idle.txt`, `heap-recovery-loaded.txt`, `heap-recovery-recovered.txt`; human disconnect records and common capture. Store in `docs/releases/evidence/49/g05-recovery/` using the shared filename pattern.

**Pass criteria:** post-load heap recovery (`run_baseline.py --recovery`), thread/queue counts back to baseline; player-disconnect retention with real clients

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g06-io"></a>

### G06. Region threads never block on external I/O

**Goal:** Observe region stacks under plugin traffic and a disk stall.

**Preconditions:** Shared requirements; P10; minimum 2 players driving storage/plugin activity; disposable storage with an operator-reviewed disk-stall method (unverified until recorded).

**Steps:**

1. Capture unstalled region stacks while players load terrain and use plugins.
2. Apply the reviewed bounded disk stall to the disposable storage, timestamp it and repeatedly sample all region-thread stacks. Keep the host responsive for observation.
3. Release the stall, repeat player actions and verify region progress and storage recovery. Compare with the existing Redis-freeze scenario without treating that earlier sample as a disk test.

**Capture:** Common capture plus Full timestamped stack samples, stall method/duration, spark report, `/perf storage`, `/perf lanes`, plugin actions and recovery logs. Store in `docs/releases/evidence/49/g06-io/` using the shared filename pattern.

**Pass criteria:** sampling under plugins and a disk stall, in addition to the Redis-freeze sample

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g07-plugins"></a>

### G07. Plugin compatibility

**Goal:** Exercise real plugin semantics, bridged costs and ProtocolLib dependents.

**Preconditions:** Shared requirements; P10 plus ProtocolLib and named actual dependent plugins (operator must supply list/versions; source defines no dependent set); minimum 10 players during soak, at least 2 for focused workflows. Use authenticated clients on Sourby Demo.

**Steps:**

1. Set `[protocollib] auto-provision = true` in utility TOML (default true, RESTART_REQUIRED); record `[viaversion] auto-provision` separately. Boot and verify ProtocolLib enables before dependents; capture any 26.2 warning.
2. Run `/plugins`, `/plugins Essentials`, `/plugins Vault`, `/perf plugins`. Have real players use the installed EssentialsX teleport/home flow and a Vault-backed economy consumer, plus native island create/visit/leave and ProtocolLib-dependent gameplay. Record plugin-specific command syntax before use.
3. Repeat during G04’s representative legacy-plugin soak. Read per-plugin bridged timings in that run; inspect cancellations, event delivery, quarantine and errors. Include dependent-plugin observations, not only enabled state.

**Capture:** Common capture plus Boot order, plugin inventory/hashes, per-plugin detail/timing, transaction and island results, client screenshots, spark report and G04 link. Store in `docs/releases/evidence/49/g07-plugins/` using the shared filename pattern.

**Pass criteria:** real player flow with bridged EssentialsX/Vault on Sourby Demo; representative legacy plugins in the soak; per-plugin bridged timing read in that run; ProtocolLib (auto-provision default `true`) booted with dependents and soaked

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g08-shutdown"></a>

### G08. Shutdown

**Goal:** Show bounded AWF shutdown and a clean stop under client load.

**Preconditions:** Shared requirements; P10; minimum 2 players actively modifying dirty AWF chunks; disposable network Redis. D6 overall deadline must be implemented and its actual value documented before a PASS is possible. **CONDITIONAL on MC feature patch 0071 (AWF shutdown failure fails the stop) and the D6 code being in the jar:** check that the boot's `sourbycraft_config/aurora.toml` has `[aurora.awf] shutdown-deadline-ms` (seeded, default `30000`, RESTART_REQUIRED) and that a stop logs `Aurora World Fabric: shutdown began; every AWF commit and release must finish within <N> ms`. Record N.

**Steps:**

1. First stop normally under real client load; time from console `stop` (verify command exists) to process exit and record save/player/plugin completion.
2. On a separate disposable run make chunks dirty, freeze only the test Redis process using the operator’s reviewed SIGSTOP method (unverified until target/process command recorded), then issue console `stop` (verify command exists).
3. Measure total AWF shutdown against the implemented D6 overall deadline. If only per-store timeouts exist, mark BLOCKED on D6 rather than accepting the historical 91–115 s stop. Expected with the deadline: the time from the `shutdown began` line to process exit is at most N plus a few seconds (vanilla steps after AWF); the log has one `ERROR ... could not confirm the shutdown commit of <storage>: UNCERTAIN|REFUSED|FAILED - <n> unconfirmed dirty position(s) [...]` per affected storage and one `AWF-SHUTDOWN-INCOMPLETE: ...` alert listing them; the process exit status is **70**, not 0 (record how the panel/script reports it). A line that says these chunks were saved, or exit 0 with a frozen Redis, is a FAIL. With a healthy backend the stop logs `AWF-SHUTDOWN-OK: Aurora World Fabric confirmed <n> storage(s) at shutdown in <ms> ms` and exits 0; if a healthy-backend stop ever logs the incomplete alert, the deadline is too short for that world's chunk save (it counts from the start of shutdown): record the elapsed time, raise the key, and report it.
4. Resume the test backend using the paired reviewed SIGCONT method; wait until the leases expire (`lease-seconds`) or the same server directory restarts; boot again and inspect durable state against the alert: positions of a REFUSED storage must hold their last confirmed state; positions of an UNCERTAIN storage may hold either the old or the new state (the commit may have been applied after SIGCONT, as seen in the agent run below) — record which, never call it a loss or a success without checking.

**Capture:** Common capture plus Stop-to-exit timeline/exit status, frozen-backend stacks, dirty/pending counts, deadline config/code reference and post-boot integrity observations. Store in `docs/releases/evidence/49/g08-shutdown/` using the shared filename pattern.

**Pass criteria:** AWF overall shutdown deadline (D6) implemented and shown with a frozen Redis; `stop` under client load

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g09-backend"></a>

### G09. Backend failure

**Goal:** Test network backend loss, recovery and bounded reporting.

**Preconditions:** Shared requirements; P10; minimum 2 players; Redis on a separate real-network host; capture sanitized backend settings and lease/timeout values. D4 requires closure evidence or written owner acceptance. **D4 closure evidence is CONDITIONAL on MC feature patch 0072 (save-all reports AWF durability)** being in the jar.

**Steps:**

1. Follow the [AWF backend-failure scenarios](../../architecture/aurora-world-fabric.md#backend-failure-redis-gone--lease-lost-on-a-running-server) on disposable worlds: kill/unavailable Redis, expiry/recovery and lease loss; record each injected condition and expected durable checkpoint.
2. Repeat the SIGSTOP/SIGCONT freeze scenario on the release jar while players modify chunks and request stored/new terrain; observe dirty chunks, retries and region stacks.
3. Record save acknowledgements against actual durable commits to assess D4. Exercise the Moonrise read-failure path and count log lines per time window; do not call a flood bounded from a short excerpt. D4 check with 0072: with the backend healthy, `save-all flush` must print `Aurora World Fabric: waiting for <n> storage(s) to commit what this save wrote (pending; ...)` and then `Saved the game` followed by `Aurora World Fabric: <n> storage(s) durable, <m> chunk(s) committed`; with the backend frozen or down it must print the pending line and then `Save NOT durable: ... could not commit <k> of <n> storage(s), <c> chunk(s) only in memory: <storage> (<dirty> dirty: <error>)` and **no** `Saved the game` line for that command (it can take commit-attempts x timeout-ms, behind a running periodic commit). Place a marker block before each `save-all flush`, restart after a "Saved the game" acknowledgement and confirm the marker; a "Saved the game" for a save whose marker is missing after a restart is a FAIL. Plain `save-all` (no flush) still prints `Saved the game` at once plus a line with the AWF chunks still in memory: it never claimed durability.
4. Restore the backend, inspect recovery and persistence, and attach either the D4 fix evidence or explicit owner acceptance.

**Capture:** Common capture plus Per-scenario network/failure timeline, `/perf awf`, stacks, durable-state comparisons, Moonrise log rate, D4 disposition and client observations. Store in `docs/releases/evidence/49/g09-backend/` using the shared filename pattern.

**Pass criteria:** D4 closed or accepted in writing; Redis over a real network; SIGSTOP rerun on current code; Moonrise read-failure log flood bounded

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g10-cache"></a>

### G10. Cache corruption

**Goal:** Prove a corrupt startup cache is handled at boot.

**Preconditions:** Shared requirements; P10; minimum 1 player after boot; disposable cache copy. `aurora.startup.cache` is RESTART_REQUIRED; do not disable it to avoid the corrupt-cache path.

**Steps:**

1. Boot once to create `sourbycraft_config/cache/startup-index.cache`, stop, and preserve the original cache and plugin hashes.
2. On the disposable copy truncate the cache, then boot with cache enabled. Capture discard/rebuild diagnostics, readiness and plugin enable state.
3. Repeat with altered cache bytes; join, run `/plugins` and `/perf`, and stop cleanly. Confirm the subsequent valid-cache boot also works.

**Agent rehearsal (not the gate):** `scripts/agent_testserver.py` can seed every shape below before boot and judge the boot, so the operator knows what a correct log looks like. Boot a donor with at least one plugin jar (a cache with no jar has no entries and is not rewritten), stop it, then `start --name cache-2 --startup-cache-from <donor> --startup-cache-corrupt <shape>` and `cache-check --name cache-2` (exit 0 = every check held); boot again with `--startup-cache-from cache-2` and no shape for the warm second boot. Shapes, the exact WARN line each must produce and the check names are in [AGENT-TEST-SERVER.md](../../development/AGENT-TEST-SERVER.md#startup-cache-seeding---startup-cache-from---startup-cache-corrupt-cache-check). It runs a local jar with no clients; the human steps above still decide the gate. How to read a result: exactly one `Aurora startup cache ...; affected entries are rebuilt and boot continues` WARN (none for `stale-hashes`, whose entries are plain misses), then `Aurora startup index: N jar(s), cold|mixed start (hit H, miss M, discarded D)`, `Done (`, the plugin's `Enabling` line, and a rewritten cache that passes every integrity check; the next boot logs `warm start (hit N, miss 0, discarded 0)` and no cache WARN. A second WARN, a stack trace, a missing plugin or a cache that is still damaged after the boot is a FAIL.

**Capture:** Common capture plus Before/after cache size/hash, exact corruption operation, both boot logs, startup-index diagnostics and client/plugin results. Store in `docs/releases/evidence/49/g10-cache/` using the shared filename pattern.

**Pass criteria:** boot-level corrupted startup-cache test

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g11-security"></a>

### G11. Security

**Goal:** Activate signing and prove real apply/failure/rollback plus proxy guards.

**Preconditions:** Shared requirements; P10 and actual proxy/dependent plugin inventory; minimum 2 players for proxy flow. Owner must supply signing activation and staged-rollout decision; never put keys or panel access in evidence.

**Steps:**

1. Inspect CI signing results and the published release assets. Confirm verification is enforced by the jar and the signed release manifest is active; the no-key warning is a blocker, not acceptance.
2. On a disposable POSIX server capture `/update status`, use `/update apply` only with a reviewed same-Minecraft signed release candidate, and preserve `.prev` plus rollback state before the controlled failed boot.
3. Cause the applied jar’s boot to end before ready using an operator-reviewed fault (unverified until supplied). Follow the automatic rollback sequence in F; capture APPLIED/BOOTING/ROLLED_BACK or failure states and the extra restart needed to run the restored bytes.
4. Boot behind the real proxy and exercise SEC-1 packet guards and SEC-11 connection limits per [SECURITY-GUARDS.md](../../development/SECURITY-GUARDS.md). Record configured LOG/KICK policy, ordinary player joins and controlled limit violations. LOG observes; it does not block.

**Capture:** Common capture plus Signing/manifest verification, sanitized `/update status`, state transitions, `.prev` and active jar hashes, failed/restore/next boot logs, proxy topology class and limit counters. Store in `docs/releases/evidence/49/g11-security/` using the shared filename pattern.

**Pass criteria:** SEC-2 signing in CI and verification in the jar; signed manifest active; `.prev` rollback exercised on a real boot (apply → failed boot → restore); SEC-1 guards and SEC-11 connection limits booted behind a proxy

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g12-bootstrap"></a>

### G12. Bootstrap / network

**Goal:** Validate bounded bootstrap downloads and verified offline reuse.

**Preconditions:** Shared requirements; remote Linux host, Temurin JDK 25 and recorded hardware/network; P10 including provisioned plugins; minimum 1 real player after each successful boot. New SourbyClip deadline/size-cap release and pin are prerequisites: SourbyClip 3.1.3 (`e5de0f4b`, jar SHA-256 `56d317b8…435e`) implements them and is pinned in the working change; the private commit must be pushed and the release built by CI before this gate can start.

**Steps:**

1. Obtain maintainer evidence that the release jar embeds SourbyClip 3.1.3 or later (whole-transfer deadline, byte cap, bounded single-source retry): `java -jar <release jar> --sourbyclip-info` prints `SourbyClip 3.1.3`; `gradle.properties` `clipVersion`/`clipSha256` and the `SourbyClip.revision` in `build-data/private-toolchain.lock.json` at the release tag match. Block if the fix is only planned or the pin is older.
2. Boot the release jar on a remote Linux disposable host with empty bootstrap caches. Capture download start/end, readiness and actual client login.
3. Using a reviewed disposable download/cache fault setup (unverified until supplied), inject a hash mismatch and exercise the deadline/size-cap failures. Shorten the deadline only through the launcher property (`-Dsourbyclip.transferDeadlineSeconds=<n>` before `-jar`; also `-Dsourbyclip.maxTransferBytes`, `-Dsourbyclip.transferRetries`, all RESTART_REQUIRED). Expected per failed attempt: `Transfer of <what> from <url> failed ...` with `whole-transfer deadline of N s exceeded`, `exceeds the cap of N bytes`, `SHA-256 mismatch` or `retrying in N ms`; when every source fails, a non-zero exit naming the coordinate (library) or `Cannot prepare original JAR ... source: <url>`. Record rejection and process outcome; do not weaken verification.
4. Boot again with verified caches, then with `-Dsourbyclip.offline=true` before `-jar` (RESTART_REQUIRED). Confirm cached offline success and real client play; separately test a missing/corrupt required library in offline mode and retain its non-zero failure.

**Capture:** Common capture plus Clip release/pin evidence, cold/cached/offline timelines, network activity observations, mismatch/deadline/cap logs, exit codes and client screenshots. Store in `docs/releases/evidence/49/g12-bootstrap/` using the shared filename pattern.

**Pass criteria:** SourbyClip release with transfer deadline + size cap pinned; cold download from a remote Linux host; hash-mismatch injection; cached/offline boot

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g13-supply-chain"></a>

### G13. Reproducibility + supply chain

**Goal:** Check independent reproducibility and release supply-chain artifacts.

**Preconditions:** Shared release identity and SHA requirements; record both clean Linux builder environments/hardware, Temurin JDK 25, inputs and plugin/config manifests; minimum 0 players for artifact review.

**Steps:**

1. Ask the human release maintainers for clean-checkout build evidence on the tag commit and a separate independent slim-jar rebuild job.
2. Compare complete slim-jar SHA-256 values with each other and the published asset; record any difference without normalizing bytes away.
3. Inspect and verify the SBOM and signed provenance attestation against the release artifact and commit, using [BUILD-PROVENANCE.md](../../development/BUILD-PROVENANCE.md). Embedded unsigned metadata alone does not satisfy attestation.

How to verify (needs `gh`; `JAR` is the downloaded release asset; the tag commit is `git rev-list -n1 v26.2-r49`):

```bash
sha256sum "$JAR"                                         # equals the release notes and "$JAR.sha256"
gh attestation verify "$JAR" --repo YanIanZ/SourbyCraft  # attestation exists, signed for this repo's workflow
gh run list --workflow build.yml --commit "$(git rev-list -n1 v26.2-r49)" --json databaseId,conclusion,event
gh run view <run id> --log | grep -A3 'Slim jar rebuild\|REPRODUCIBLE'   # rebuild job: both hashes, "byte-identical"
python3 scripts/sbom.py verify "$JAR" SourbyCraft-26.2-REL.cdx.json
```

Pass needs all of: the rebuild job of the publishing run is green and its summary lists two equal SHA-256 values;
that value equals the published asset's; `gh attestation verify` succeeds and names this repository and the tag
commit. Record the run id, both hashes and the verify output. A red or missing `rebuild` job, a missing
attestation, or a hash that differs from the asset is a FAIL. A later green re-run does not erase the failing run:
record both. Optional local redo (private toolchain, Temurin 25.0.4+1):
`export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)` on the tag commit, `./gradlew applyAllPatches`, then
`./gradlew slimServerJar -PsourbyBuild=49 -PsourbyChannel=REL`, and compare `build/libs/SourbyCraft-slim.jar`.

**Capture:** Common capture plus Both job ids/environment manifests, input pins, complete hash comparison, SBOM verification and attestation verification output; common capture where a server is booted. Store in `docs/releases/evidence/49/g13-supply-chain/` using the shared filename pattern.

**Pass criteria:** clean-checkout build reproduces the slim jar hash; independent rebuild job; SBOM verified; signed provenance attestation

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g14-licence"></a>

### G14. Attribution / licence

**Goal:** Resolve distribution licensing and inspect the actual artifacts.

**Preconditions:** Shared release identity/SHA and host-record requirements; human licence/release owner available; minimum 0 players, no plugin load required for artifact inspection (record inventory as not loaded).

**Steps:**

1. Obtain the written owner decision covering repository/server licensing and Docker licence label.
2. Inspect the tag jar for the required GPL text and source offer if the decision requires them; record archive entry names and actual contents.
3. Inspect the distributed Docker label and compare it with the written decision; attach a dated review.

**Capture:** Common capture plus Owner decision link, jar entry/content evidence, source-offer location if required and Docker label output. Store in `docs/releases/evidence/49/g14-licence/` using the shared filename pattern.

**Pass criteria:** owner decision recorded; GPL text / source offer in the jar if required; Docker label

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g15-claims"></a>

### G15. Claims audit

**Goal:** Audit published claims against tag-specific evidence.

**Preconditions:** Shared release identity/SHA and review host record; minimum 0 players; use actual plugin/config/player records of every cited run.

**Steps:**

1. Read README, the tag release document and SPEC §161 at the tag commit.
2. For each performance/status claim, link its exact evidence with workload, hardware, effective configuration, window, fidelity, noise/certification state and statistic. Remove or flag unsupported claims through the human reviewer.
3. Confirm old functional runs are context only, planned subsystems stay planned, and LIVE versus RESTART_REQUIRED settings match their consumers. Record the reviewer and disposition for every discrepancy.

**Capture:** Common capture plus Dated claim-to-evidence audit, tag commit, source snapshots and unresolved discrepancies; no invented percentage improvements. Store in `docs/releases/evidence/49/g15-claims/` using the shared filename pattern.

**Pass criteria:** README, release doc, SPEC §161 audited on the tag commit; no performance claim beyond certified evidence

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


<a id="g16-publication"></a>

### G16. Publication

**Goal:** Publish STABLE only after explicit owner approval of all gate dispositions.

**Preconditions:** Shared release identity/SHA and review host record; minimum 0 players; completed G01–G15 evidence and any written owner acceptances. No agent publication.

**Steps:**

1. Have the human release owner review every preceding gate’s PASS or written exception on the exact release tree.
2. Record the owner’s STABLE declaration in release notes only after that review; verify tag/artifact/hash links and the selected channel.
3. Check the publication notes selection described in the gate doc: its filename must match `docs/releases/26.2-build-N-*.md`. The workflow concatenates every existing match, so this runbook would also be appended. Resolve that inclusion with the release owner before publishing and inspect the assembled release body.

**Capture:** Common capture plus Owner declaration, gate disposition links, release-note body selection, tag commit, asset hash and publication record. Store in `docs/releases/evidence/49/g16-publication/` using the shared filename pattern.

**Pass criteria:** owner declares STABLE in the release notes after every row above is PASS or accepted in writing

**Fail handling:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check); attach this gate’s evidence and block dependent gates until fixed or accepted in writing. Stop, restore the valid `<jar>.prev` over the active jar, and restart as described there.


## Additional runtime checks

These checks supplement the gates; they do not replace their verbatim evidence requirements.
Source status below was checked on 2026-10-21. Every check inherits shared release-jar,
SHA-256, host/JDK/hardware, config, capture and rollback requirements.


<a id="r01-pearl"></a>

### Ender pearl: cancellation and redirect

**Goal:** Confirm real pearl events preserve cancellation and redirected destinations with protection plugins.

**Preconditions:** Shared requirements; P10 plus a named anticheat or claims plugin and a reviewed event-observation fixture; minimum 2 human players. MC 0064 exists; real-client evidence remains open in [inherited-guards.md](../../architecture/inherited-guards.md).

**Steps:**

1. Join with protection enabled. Record starting position, health, inventory and the event trace. Throw one allowed pearl, then one into a protected area where `PlayerTeleportEvent` is cancelled.
2. With the reviewed listener using `PlayerTeleportEvent.setTo()`, throw another pearl with a nearby destination, then a destination across regions/worlds. Record callback ownership and actual landing.
3. Check cancelled pearls are consumed without teleport damage or endermite creation. Confirm redirects complete once and no duplicate transfer or anticheat false correction occurs.

**Capture:** Common capture plus Event cancel/setTo trace including ENDER_PEARL cause, before/after position/health/items, protection decisions, screenshots and logs. Store in `docs/releases/evidence/49/r01-pearl/` using the shared filename pattern.

**Pass:** Cancellation consumes the pearl with no transfer, damage or endermite; redirects land at the chosen destination through ownership-safe transfer; no unexplained duplicates or ownership errors.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r02-command-blocks"></a>

### Command blocks across two regions

**Goal:** Exercise owned command-block rules with players in two actual regions.

**Preconditions:** Shared requirements; P10; minimum 2 humans, creative/operator access, separate regions confirmed by `/perf region <world> <x> <z>`; `aurora.world.command-blocks = true` in Aurora TOML (LIVE, default false). Use [command-blocks.md](../../architecture/command-blocks.md) as the contract.

**Steps:**

1. Place impulse, repeating, chain/conditional blocks and a command-block minecart in region A; place a tagged entity and block target in B. Verify GUI editing and same-region execution first.
2. From A test local and foreign selectors, foreign name/UUID lookup, and local/foreign block mutations. Exact vanilla syntax for `setblock`, `fill`, `fillbiome`, `clone`, `data` and `scoreboard` is (verify command exists) before entering it; record actual commands and outputs.
3. Test a chain crossing the ownership boundary, a conditional predecessor in B, unloaded local target chunks, and a global-state command with deferred output. Check comparator/success behaviour and one-per-command diagnostic logging.
4. Repeat the documented unaudited synchronous read paths (`execute if/unless block[s]`, `execute store ... block`, `loot ... mine`; verify command exists). Keep these open unless their mechanism is audited, even if one attempt emits no error.
5. Turn the LIVE switch off and confirm inherited disable behaviour; turn it back on and re-arm repeating blocks via redstone/GUI save/chunk reload. Test GUI edits from the foreign region and minecart behaviour.

**Capture:** Common capture plus Block layout/coordinates and region evidence, entered commands, GUI screenshots, success/conditional results, deferred-output logs and ownership errors. Store in `docs/releases/evidence/49/r02-command-blocks/` using the shared filename pattern.

**Pass:** Local execution succeeds; foreign selectors exclude before reading; foreign mutation/chain/edit paths refuse as documented without unsafe access; deferred output is logged and off-switch semantics match. Any unresolved synchronous-read audit remains BLOCKED for arbitrary content.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r03-functions"></a>

### Scheduled functions and datapack — CONDITIONAL on MC 0066

**Goal:** Test loaded datapack functions and persisted scheduling end to end.

**Preconditions:** Shared requirements; **CONDITIONAL: only once MC patch 0066 lands and is included in the release jar.** No `0066-*` exists in `sourbycraft-server/minecraft-patches/features/` on this tree. P10, minimum 2 humans in different regions, a reviewed datapack with function/tag definitions and observable outcomes. [scheduled-functions.md](../../architecture/scheduled-functions.md) says MC 0065 registers scheduling but function loading and `/function` remain disabled.

**Steps:**

1. After the prerequisite lands, record its patch/commit and recheck function loading, command registration and datapack format. Do not infer support from `/schedule` registration alone.
2. Boot with the datapack and invoke `/function <namespace:id>` (verify command exists). Exercise `/schedule function <namespace:id> 20t append`, repeat with `replace`, a function tag and `/schedule clear <namespace:id>` (verify command exists for these vanilla registrations).
3. Schedule events, save/restart before due time and observe persisted execution; exercise freeze/unfreeze through an available reviewed control (unverified). Test later events after one function failure and explicit cross-region block/entity operations.
4. Record asynchronous completion ordering and source dimension/position; do not interpret a returned acceptance value as completed region work.

**Capture:** Common capture plus Datapack files/hash, boot loading logs, command/event timeline, save/restart and freeze results, both clients’ screenshots. Store in `docs/releases/evidence/49/r03-functions/` using the shared filename pattern.

**Pass:** Only after 0066: functions load; direct invocation, append/replace/clear, tags, persisted deadlines and resumed overdue execution follow the reviewed contract; failure isolation and owner routing hold. Until then BLOCKED.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r04-respawn"></a>

### Bed-less respawn and End credits

**Goal:** Exercise retained guards ServerPlayer#1 and EndPortalBlock#1 through real gameplay.

**Preconditions:** Shared requirements; P10; minimum 1 human using a real client with a backed-up player profile; remove bed/anchor spawn before death; have a usable End exit portal. [Inherited guards §6](../../architecture/inherited-guards.md#6-undecided-what-must-be-checked) leaves these runtime checks open.

**Steps:**

1. With no valid bed/anchor, die through ordinary gameplay and respawn. Record spawn dimension/position and client readiness; inspect ownership and player-state errors.
2. Enter the End and use the exit portal once with a profile that has not seen credits; capture the credits and resulting respawn.
3. Reconnect, enter/exit again and verify credits are not shown again for that already-credited profile. Record event ordering and inventory/location consistency.

**Capture:** Common capture plus Respawn/portal/credits video or screenshots, player/event traces, reconnect timeline, state observations and full logs. Store in `docs/releases/evidence/49/r04-respawn/` using the shared filename pattern.

**Pass:** Bed-less respawn succeeds through the owned path; End exit returns the player safely; credits occur once for the profile and remain once-only across reconnects, without ownership errors.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r05-hud"></a>

### HUD bars with a real client

**Goal:** Verify visible HUD rendering and lifecycle against the shared metrics snapshot.

**Preconditions:** Shared requirements; P10; minimum 2 human clients for per-player isolation; appropriate HUD permissions. Contract: [DEVELOPMENT.md §14](../../DEVELOPMENT.md#14-hud-continuation); implementation `hud/HudBars.java` and owned command classes.

**Steps:**

1. In game run `/tpsbar on`, `/rambar on` and `/perfbar on` separately. Compare visible values with `/perf`, `/perf memory`, `/tps` and `/mspt` over timestamped refresh cycles.
2. Exercise `/perfbar bossbar` and `/perfbar actionbar`; repeat the corresponding modes for `/tpsbar` and `/rambar`. Test off, reconnect and two-player independent preferences.
3. During G04 load and recovery capture legibility, updates and stale/unavailable labels. Disconnect viewers and verify no retained-player updater leak; record console-only HUD refusal.

**Capture:** Common capture plus Timestamped screenshots for each mode/client, matching command output, reconnect results and updater/retention observations. Store in `docs/releases/evidence/49/r05-hud/` using the shared filename pattern.

**Pass:** Bars display readable, consistent shared-snapshot values, respect per-player toggles/modes and permissions, and stop retaining disconnected viewers. Console output alone cannot pass this check.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r06-trader"></a>

### Wandering trader spawn — CONDITIONAL on MC 0068

**Goal:** Check natural wandering-trader spawning through real gameplay.

**Preconditions:** Shared requirements; **CONDITIONAL on MC patch 0068 landing and appearing in the release jar.** No `0068-*` feature patch exists on this tree. P10; minimum 1 human in suitable naturally ticking terrain. Spawn settings, observation window and exact expected behaviour must come from the landed feature (currently unverified).

**Steps:**

1. After landing, read the patch and record all spawn rules/config values and the required observation window; do not change gameplay settings to fabricate success.
2. Play in eligible terrain through the recorded spawn window. Observe natural trader/llama appearance, interaction, movement and subsequent lifecycle; do not count a manually summoned trader as spawner evidence.
3. Record ownership diagnostics and natural spawn outcomes across regions during G04.

**Capture:** Common capture plus Landed patch/commit, effective spawn configuration, timestamps, natural-spawn/player interaction screenshots and logs. Store in `docs/releases/evidence/49/r06-trader/` using the shared filename pattern.

**Pass:** BLOCKED until 0068 supplies a verified contract and the release includes it; then natural spawning/lifecycle must match that contract without ownership violations.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r07-forceload"></a>

### Forceload after crash — CONDITIONAL on MC 0067

**Goal:** Verify forceload persistence through a crash, rather than only a clean shutdown.

**Preconditions:** Shared requirements; **CONDITIONAL on MC patch 0067 landing and inclusion in the release jar.** No `0067-*` feature patch exists on this tree. P10; minimum 1 human before/after crash; disposable world and a recorded durable checkpoint. The current ledger says forceload tickets remain shutdown-only.

**Steps:**

1. After landing, record the patch’s persistence contract and checkpoint boundary. Use `forceload add <x> <z>` and `forceload query` (verify command exists) on a chosen chunk; retain exact replies.
2. Allow the documented durable checkpoint, then have the operator crash the disposable server without a clean stop using the reviewed process-targeted method (unverified until recorded).
3. Restart the same world; query retained forceload tickets before re-adding anything, join and inspect chunk ticking. Repeat on region files and AWF if supported by the landed contract.

**Capture:** Common capture plus Before/after ticket replies, coordinates/world, checkpoint and crash timeline, patch identity, boot logs and ticking observations. Store in `docs/releases/evidence/49/r07-forceload/` using the shared filename pattern.

**Pass:** BLOCKED until 0067; after landing, tickets covered by its durable checkpoint survive crash/restart and restore correctly without duplicate/manual re-add masking.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


<a id="r08-teleport"></a>

### Owned teleport plugin API

**Goal:** Exercise plugin-facing synchronous owned teleports and async fallback with real players.

**Preconditions:** Shared requirements; P10 plus a reviewed plugin fixture compiled against sourbyapi; minimum 2 humans in different regions. Public surface is `org.bukkit.entity.Entity.teleport(Location)` and `teleport(Location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause, io.papermc.paper.entity.TeleportFlag...)`, as present in `sourbyapi/paper-patches/files/src/main/java/org/bukkit/entity/Entity.java.patch`. `dev.iyanz.sourbycraft.entity.teleport.OwnedTeleport` is server implementation, not a new public plugin API. `aurora.compat.sync-teleport` defaults enabled; setting it false is RESTART_REQUIRED.

**Steps:**

1. Record fixture jar/hash, invocation (unverified until supplied), event listeners and scheduler context. From the entity owner call the public teleport overload with a nearby loaded/owned destination; record boolean return and final position.
2. Repeat with a far region, other world and off-thread caller. Record async fallback, false return, eventual transfer and once-per-plugin warning. Test velocity flags and event cancellation/redirect through the same API.
3. Have a listener invalidate the original transfer or redirect outside the owned region; verify revalidation prevents stale/duplicate movement. Restart with `-Daurora.compat.sync-teleport=false` and confirm the documented refusal, then restore the run configuration.

**Capture:** Common capture plus API overload/caller context, boolean result versus eventual destination, event order, warning count, screenshots and ownership/transfer logs. Store in `docs/releases/evidence/49/r08-teleport/` using the shared filename pattern.

**Pass:** Owned loaded local calls complete synchronously; foreign/off-thread calls use the documented async fallback with false acceptance result; cancellation, redirected destinations and post-event revalidation preserve ownership. Disabled mode refuses. Existing `@Unsupported` annotations/javadocs remain an API-doc gap, not proof that the owned implementation is absent.

**Fail:** Apply [procedure F](#failure-procedure-f-every-gate-and-runtime-check), include this check’s folder, `/ver`, jar SHA and config in the issue; stop, restore valid `<jar>.prev` over the active jar and restart under F. Missing conditional prerequisites stay BLOCKED.


## Evidence template

Copy this table for **each run** into the human evidence record. Each row below is a
blank placeholder example, not a claimed result. Raw-file targets are relative to
this runbook; from inside an evidence folder adjust relative links accordingly.

| Date (UTC) | Host and hardware | Jar filename and SHA-256 | Config files and every non-default value | Players: count and who | Plugins: list, versions and hashes | Results per gate/check: pass/fail/blocked | Raw-file links |
| --- | --- | --- | --- | --- | --- | --- | --- |
| <fill in> | <fill in: host label, OS, Temurin 25, CPU/quota, RAM/heap, disk, network, competing load> | <fill in: tag, commit, jar, SHA-256 verified against tag> | <fill in: five required config files, server.properties, Aurora TOML, JVM flags; non-default values and lifecycle> | <fill in: count, consenting handles, attendance window/actions> | <fill in: complete actual list and load modes> | <fill in: G01–G16 and R01–R08, disposition and reason for each applicable item> | [<fill in: raw artifact>](../evidence/49/<gate-id>/<fill-in-filename>) |

Record start/end times, workload and certification/noise state beside the table.
Keep errors and failed runs. One screenshot or one JSON `passed` field cannot satisfy
a gate that also requires clients, an owner decision, a soak or a different backend.

### Script evidence reference

All script executions below are by human operators on disposable instances, never
against another operator’s live session. Run from the repository root; `<release-jar>`
is the verified tag asset. Record tool versions, stdout/stderr and exit status.

- `verify_persistence.py`: positional jar; `--output`, `--heap-mib`, `--port`,
  `--cache-from`, `--keep`, `--restarts`, `--load-clients`, `--load-seconds`.
  No `--awf`. Its synthetic load is not a real-player qualification run.
- `verify_crash.py`: positional jar; `--output`, `--awf`, `--clients`, `--heap-mib`,
  `--port`, `--cache-from`, `--keep`. Keep fixture output; expected failures require
  explicit review even when the overall `passed` field is true.
- `verify_persistence_panel.py`: `--panel`, `--server`, `--restarts`.
  `verify_crash_panel.py`: `--panel`, `--server`; refuses online players. Both print
  checks and summary text, **neither writes JSON or accepts `--awf`**. Attach stdout
  and server logs; configure AWF through the server’s actual config separately.
- `bench_panel.py`: `--panel`, `--server`, `--host`, `--port`, `--workload`,
  `--clients`, `--warmup`, `--duration`, `--sample-seconds`, `--seed`, `--world`,
  `--output`, repeatable `--edit`, `--allow-online-players`. Supplemental example:
  `python3 scripts/bench_panel.py --panel <panel-url> --server <server-id> --host <game-host> --workload plugins-10 --clients 10 --warmup 120 --duration 7200 --sample-seconds 60 --output <fresh-run-dir>`.
  This tool changes properties/whitelist and drives synthetic clients; it is not
  the real-player soak procedure. Do not bypass the online-player refusal to run
  it over human testers. `bench.json` includes `samples`, `summary_metrics`,
  `bridged_plugin_details`, `clients_end`, `restored` and, on interruption, `aborted`.
- `run_baseline.py`: positional jar; `--workload`, `--output`, `--warmup`, `--duration`,
  `--heap-mib`, `--gc`, `--port`, `--jfr-settings`, `--connected-players`, `--world`,
  `--cache-from`, `--plugins-dir`, `--property`, `--recovery`; diagnosis flags
  `--allow-command-errors`, `--allow-dirty`, `--allow-shared-machine` must not mask
  qualification gaps. `baseline.json` records workload/fidelity, provenance,
  certification, metrics/status and optional `heap_recovery`. Recovery forces full
  GC outside the timed window. See [BASELINE.md](../../BASELINE.md) for raw files and
  certification limits; an asserted count does not prove human client activity.

## What agents already verified (functional, not qualified)

**Functional verification is not qualification. These results do not satisfy any
Build 49 gate by themselves.** Read each dated caveat; older branch/config/default
statements are historical. Some raw artifacts are ignored/local or temporary and
may no longer exist; a document link is not a replacement for the raw evidence.

- [Linux VPS verification, 2026-10-21](../../development/linux-vps-verification-2026-10-21.md): Build 47 memory reporting and an 80-minute load plus 15-minute recovery on a shared VPS, no Minecraft clients; both verdicts PARTIAL, scheduler queues unobserved.
- [Handoff, 2026-10-14 with 2026-10-20 updates](../../development/HANDOFF-2026-10-14.md): records CI/test integrations, local AWF/backend scenarios and outstanding owner/runtime questions; scratch evidence is outside git.
- [Test coverage gaps](../../development/TEST-COVERAGE-GAPS.md): dated unit-test closures and remaining server/behaviour gaps; a coverage backlog, not a runtime qualification record.
- [Region watchdog](../../development/REGION-WATCHDOG.md): records applied/built context and partial flag-off evidence; flag-on, retention and wrong-owner acceptance remain unverified there.
- [ProtocolLib provisioning](../../development/PROTOCOLLIB.md): records ProtocolLib 5.4.0 enable/clean stop on the slim 26.2 jar and upstream-version caveats; dependent-plugin soak remains separate.
- [LightingLuminol bridge port](../../development/LIGHTINGLUMINOL-BRIDGE-PORT.md): records dated unit/fixture and boot evidence plus explicit remaining adapter/acceptance limits.
- [SuperiorSkyblock bridge](../../development/SUPERIORSKYBLOCK-BRIDGE.md): records adapter/materialization and partial acceptance status; asks for final-snapshot build/test/boot evidence.
- [Chunk-generation stutter](../../development/CHUNK-GENERATION-STUTTER.md): records uncertified fresh-terrain delivery observations and metrics limitations; no release-wide throughput result.
- [ASP 26.2 AWF port review](../../development/ASP-26.2-AWF-PORT-REVIEW.md): records format/persistence findings, dated local fixes/tests and unverified service wiring, not completed qualification.
- [Build provenance](../../development/BUILD-PROVENANCE.md): records build-identity/SBOM regression checks and embedded metadata limits; explicitly distinguishes metadata from signed attestation.
- [Auto-updater](../../development/AUTO-UPDATER.md): records unit-tested verification/rollback state machines and the lack of an apply/rollback boot test in that wave; signing activation was still absent.
- AWF D6/D4, 2026-10-22 (agent `opus-awf-d6`, runs `opus-awf-d6-redis-1/-2`, jar built from the worker tree, loopback Redis 8.10.2 with AOF, default 30000 ms deadline, no players): with Redis SIGSTOPped, `stop` exited after 31.1 s with exit status 70 and the `AWF-SHUTDOWN-INCOMPLETE` alert (was 91–115 s); `save-all flush` printed pending then `Save NOT durable` (no success line) with Redis frozen and pending then `Saved the game` when healthy; after SIGCONT the next boot read all four markers, including the two in the UNCERTAIN commit. See [aurora-world-fabric.md](../../architecture/aurora-world-fabric.md) "Fixes 2026-10-22". Not network Redis, not under load.
- [Agent test server](../../development/AGENT-TEST-SERVER.md): documents isolated functional harness guarantees/output and why those runs are not certified baselines or client evidence; not an authorization for agent testing from Build 49.

## Known gaps

- All 16 authoritative Build 49 gates are still OPEN/PARTIAL/NOT RUN on their source
  document; this runbook changes no state. Real players, a stable host, exact plugin
  inventories and human evidence are still required. Owner must resolve codename,
  licence, signing key activation, rollout percentage, launcher deadline/cap and
  the final STABLE declaration. Never include signing material in attachments.
- All specifically named source **files** in the task were present. The directory
  `docs/releases/evidence/` does **not** exist in this tree. Humans create
  `docs/releases/evidence/49/<gate-id>/` only when collecting real evidence.
- MC feature patches `0066-*`, `0067-*`, `0068-*` are absent. Function loading/direct
  `/function`, crash-persistent forceload and natural wandering-trader acceptance
  remain conditional/planned. Recheck source and jar after they land. The trader
  contract/window is not yet verified; do not invent a spawn default or acceleration.
- Scheduled-functions MC 0065 alone registered `/schedule` but integration could not
  load functions; no end-to-end datapack result. Append/replace/clear, tags,
  save/restart, freeze/unfreeze and multi-region execution remain open. No verified
  freeze control is supplied by that doc.
- Command blocks still have unaudited synchronous foreign-block read paths. A clean
  refusal in one observed case does not prove arbitrary command content safe.
  Bed-less respawn, End exit/once-only credits and pearl cancel/redirect need clients.
- Public teleport overloads still carry inherited `@Unsupported` annotations and
  old return documentation in the sourbyapi file patch. The implementation is owned
  routing behind the existing Bukkit API, not a verified new SourbyCraft teleport
  class for plugin authors. Real-plugin API fixture invocation remains unverified.
- Controlled stall/wrong-owner, disk-stall, boot-fault and process-targeted backend
  freeze/crash probes need reviewed human fixture/method definitions. This runbook
  marks them unverified instead of inventing an administrative command. Spark stop,
  vanilla stop and the listed vanilla datapack/block/forceload command registrations
  must be checked at runtime; they are outside the verified owned command registry.
- D4 reporting, D6 overall shutdown deadline, real-network Redis, Moonrise read-error
  rate, boot-level corrupt cache, signed apply/rollback and launcher transfer limits
  remain gate work. `/perf` reports instrumented queues only; attach independent
  observations or leave uninstrumented queue stabilization BLOCKED.
- Source supplies no universal minimum player count or hardware threshold for every
  gate. This runbook’s explicit coverage minima are not benchmark certification rules.
  P10 is defined; the real ProtocolLib-dependent plugin list is operator-supplied.
  Script clients/console sampling cannot replace real people and plugin semantics.
- Panel persistence/crash scripts have no JSON output or `--awf`; local persistence
  also has no `--awf`. Do not fabricate those flags or reports. Panel crash refuses
  players, so additional human crash/recovery evidence is mandatory.
- Publication selects `docs/releases/26.2-build-N-*.md`, per the gate doc. This new
  runbook also matches the Build 49 pattern, and the workflow concatenates matches;
  the human release owner must resolve its inclusion before publication.
  No workflow/build file is changed here.
