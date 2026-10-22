# Build 48 gates 1 and 5: startup log and command/Spark consistency (2026-10-22)

Status: **evidence, local agent test server only.** Not CI, not the tag commit, no real client.
Worker: `opus-gate48-log`, worktree `sc-worktrees/opus-gate48-log` (base `6176d517`, uncommitted, not applied to the main tree).

Gates (`docs/releases/26.2-build-48.md`, `ROADMAP-47-50.md` Build 48):

- Gate 1 "Startup log boot→`Done` clean": no raw ANSI in non-terminal logs, no `dev.iyanz.*`/`io.canvasmc.*` logger names, repeated lines collapsed. CI run 39687296516 was clean on these except the scope item task_d2d28a6f780c9b3d: `Initialised EDF Folia scheduler ...`.
- Gate 5 "Command/HUD/Spark consistency": `/tps`, `/mspt`, `/perf` against the Sourby metrics; Spark shows SourbyCraft/Aurora config.

## Change (gate 1)

`Initialised {} Folia scheduler with initial {} target thread(s)` comes from the engine baseline
(`minecraft-patches/sources/io/papermc/paper/threadedregions/TickRegions.java.patch`, `TickRegions#init`).
The baseline was not edited (no manifest change). New Minecraft feature patch
`0075-SourbyCraft-Aurora-wording-for-the-region-scheduler-start-line.patch` (edited in the materialized
repo, committed there, `rebuildMinecraftFeaturePatches`, then the rebuild's file-name churn of 0024–0068
reverted so only 0075 is new) changes it to:

```
[TickRegions] Initialised Aurora region scheduler (EDF) with initial 8 target thread(s)
```

Text only: same arguments, same logger, same level. No script or test read the old line (`rg` over
`scripts/`, `sourbycraft-server/src/test`, CI workflow). Machine-read markers (`Done (`, `SOURBY_*`,
`LEGACY_*`, metrics, the agent harness's boot-summary regex) are untouched. Pinned by the new
`RegionSchedulerLogWordingTest` (one line replaced, no added line names Folia).
Docs: `patches.md` row + count 75, `inherited-guards.md` generated summary (feature patches 101 → 102).
Number note: fable-logdesign's unapplied Build 49 worktree also creates an MC 0075; whichever lands second renumbers.

## Build and run

| Item | Value |
| --- | --- |
| Source | `6176d517` + this worktree's changes (`/ver`: `Build 48-dirty — Aurora Nexus (DEV, HEAD@6176d51)`, `Source: 6176d517c05a tree 61e0b8946ee0 (dirty)`; the only tracked change at build time was `docs/architecture/patches.md`, plus the untracked 0075) |
| Commands | `SOURBY_TREE=<wt> AGENT_NAME=opus-gate48-log scripts/gradle_locked.sh rebuildMinecraftFeaturePatches` → `applyAllPatches` → `slimServerJar` |
| Jar | `build/libs/SourbyCraft-slim.jar`, 41066929 bytes, SHA-256 `28c19d441c4e91617297c4227646e26be983e395bc3fb9653f7cec3526f974a8` |
| Boot | `python3 scripts/agent_testserver.py start --name opus-gate48-log-1 --max-lifetime 1500` (slot 1, harness defaults: 2048 MiB heap, view 4 / sim 5, offline, `minecraft:normal`, seed 440044, no plugins) |
| Result | `Done (19.251s)`, harness boot 22.4 s, verdict COMPLETE; `stop` exit 0, lock released |
| Host | Apple M2, 8 processors, 16 GiB, JDK 25.0.2; shared laptop, other agents' Gradle may have been running (not a measurement) |
| Raw files | `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/opus-gate48-log/` (`latest.log`, `run.json`, `round1-3.txt`, the `.sparkprofile`, `sparkprofile-config-strings.txt`) |

## Gate 1: boot → `Done` excerpt (`logs/latest.log`, lines 1–99, verbatim)

Checks on the whole `logs/latest.log` (906 lines incl. commands and shutdown) and the harness's stdout capture `server.log`:

| Check | Result |
| --- | --- |
| ESC (0x1B) bytes | 0 in `logs/latest.log`, 0 in `server.log` |
| Logger tags `[dev.iyanz...` / `[io.canvasmc...` | 0 / 0 |
| Identical consecutive lines (time stamp stripped), lines 1–99 | none; `Preparing spawn area: 100%` appears 3 times, once per world, not consecutive |
| `Initialised ... Folia scheduler` | gone; line 28 is the new wording |

Remaining "Folia"/"Canvas" text in this boot (not part of the gate wording, left as is, listed for the owner):

- line 56, thread ledger: `Async scheduler core 4 (Folia async scheduler: ...)` — `execution/ThreadLedger.java:44`; names Folia's async scheduler API; the ledger wording is owned by the fable-logdesign Build 49 worktree, not touched here.
- thread names `Folia Region Scheduler Thread #n` in every region-thread line after `Done` (file log thread column, not a logger name). Machine-read by `SparkThreadClass`, `RegionThreadDumper` and tests; renaming them is a separate change.
- `config/canvas-server.yml`, `canvas-worlds.yml` (lines 6, 7, 71): file names kept by owner decision until the Build 49 rename (fable-configdesign).
- line 98 `[TickRegionScheduler] Region profiling not supported in this environment` WARN (EDF backend; `/spark tps` still works, see below).

```
[06:18:16] [ServerMain/INFO]: [bootstrap] Running Java 25 (OpenJDK 64-Bit Server VM 25.0.2+10-69; Oracle Corporation null) on Mac OS X 27.0.1 (aarch64)
[06:18:16] [ServerMain/INFO]: [bootstrap] Loading SourbyCraft Build 48-dirty Thursday, 22 October 2026 06:15 for Minecraft 26.2
[06:18:16] [ServerMain/INFO]: [SourbyCraft] ViaVersion auto-provision disabled (viaversion.auto-provision=false) — old clients will not be bridged.
[06:18:16] [ServerMain/INFO]: [PluginInitializerManager] Initializing plugins...
[06:18:17] [ServerMain/INFO]: [PluginInitializerManager] Initialized 0 plugins
[06:18:17] [ServerMain/INFO]: [Aurora] Loading Aurora region configuration (config/canvas-server.yml)
[06:18:17] [ServerMain/INFO]: [ConfigurationProvider] canvas-server.yml not found; writing the default configuration
[06:18:17] [ServerMain/WARN]: [Aurora] SIMD is off: add "--add-modules=jdk.incubator.vector" to the startup flags, before "-jar", to enable it (if the flag is already there, this JVM or CPU does not support SIMD).
[06:18:17] [ServerMain/INFO]: [Aurora] Aurora region settings loaded; use /aurora for status and configuration
[06:18:17] [ServerMain/INFO]: [Aurora] Autosave every 5m; default tick rate 20.0 TPS
[06:18:17] [CrashReport preload thread/WARN]: [OSHI] Unable to parse version 27.0 to a codename.
[06:18:19] [DataConverter MCTypeRegistry init thread/INFO]: [DataConverter] Initialising converters for DataConverter...
[06:18:20] [ServerMain/INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]
[06:18:20] [DataConverter MCTypeRegistry init thread/INFO]: [DataConverter] Finished initialising converters for DataConverter in 479,9ms
[06:18:20] [ServerMain/INFO]: Found new data pack paper, loading it automatically
[06:18:21] [Worker-Main-2/INFO]: No existing world data, creating new world
[06:18:22] [ServerMain/INFO]: Loaded 1585 recipes
[06:18:22] [ServerMain/INFO]: Loaded 1688 advancements
[06:18:22] [Server thread/INFO]: Starting minecraft server version 26.2
[06:18:22] [Server thread/INFO]: Loading properties
[06:18:22] [Server thread/INFO]: This server is running SourbyCraft version 26.2-48-HEAD@6176d517c05a2decfef985f41db6d3a0995b9182 (2026-10-21T23:15:43Z) (Implementing API version 26.2-R0.1-SNAPSHOT)
[06:18:22] [Server thread/INFO]: [spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling
[06:18:22] [Server thread/INFO]: Server Ping Player Sample Count: 12
[06:18:22] [Server thread/INFO]: Using 4 threads for Netty based IO
[06:18:22] [Server thread/INFO]: [MoonriseCommon] Setting up Paper chunk system
[06:18:22] [Server thread/INFO]: [MoonriseCommon] SourbyCraft is using 2 worker threads, 1 I/O threads
[06:18:22] [Server thread/INFO]: [TickRegions] Region tick threads: 8 (aurora.cpu.cores unset; using all 8 available processor(s))
[06:18:22] [Server thread/INFO]: [TickRegions] Initialised Aurora region scheduler (EDF) with initial 8 target thread(s)
[06:18:22] [Server thread/INFO]: [Aurora]  1/15   7% [=...............]  OK    metrics runtime  (14ms)
[06:18:22] [Server thread/INFO]: [Aurora]  2/15  13% [==..............]  OK    network counters  (1ms)
[06:18:23] [Server thread/INFO]: [SourbyCraft] seeded Aurora engine defaults into sourbycraft_config/aurora.toml
[06:18:23] [Server thread/INFO]: [Aurora]  3/15  20% [===.............]  OK    configuration  (52ms)
[06:18:23] [Server thread/INFO]: [Aurora]  4/15  27% [====............]  OK    stall recorder  (4ms)
[06:18:23] [Server thread/INFO]: [Aurora]  5/15  33% [=====...........]  OK    aurora worlds  (13ms)
[06:18:23] [Server thread/INFO]: [Aurora]  6/15  40% [======..........]  OK    plugin provisioning  (0ms)
[06:18:23] [Server thread/INFO]: [Aurora]  7/15  47% [=======.........]  OK    banner  (8ms)
[06:18:23] [Server thread/INFO]: [Aurora]  8/15  53% [=========.......]  OK    plugin diagnostics  (2ms)
[06:18:23] [Server thread/INFO]: [SourbyCraft] Aurora startup index: 0 jar(s), empty start (hit 0, miss 0, discarded 0) in 1.7 ms
[06:18:23] [Server thread/INFO]: [Aurora]  9/15  60% [==========......]  OK    startup index  (7ms)
[06:18:23] [Server thread/INFO]: [SourbyCraft/VirtualExecutor] Virtual thread executor initialized
[06:18:23] [Server thread/INFO]: [SourbyCraft] Registered 18 SourbyCraft commands (bare names; /minecraft:<name> or /paper:<name> still reach the built-ins). Native /spark is provided by the server integration.
[06:18:23] [Server thread/INFO]: [Aurora] 10/15  67% [===========.....]  OK    commands  (32ms)
[06:18:23] [Server thread/INFO]: [Aurora] 11/15  73% [============....]  OK    messages  (9ms)
[06:18:23] [Server thread/INFO]: [SourbyCraft] max-players bypass: disabled (default) — no PlayerLoginEvent listener, fast joins kept. Set sourbycraft.maxplayers.bypass-enabled=true to allow bypass holders onto a full server (costs the config-phase fast join path).
[06:18:23] [Server thread/INFO]: [Aurora] 12/15  80% [=============...]  OK    player slots  (0ms)
[06:18:23] [Server thread/INFO]: [Aurora] 13/15  87% [==============..]  OK    virtual executor  (0ms)
[06:18:23] [Server thread/INFO]: [Aurora] 14/15  93% [===============.]  OK    auto updater  (0ms)
[06:18:23] [Server thread/INFO]: [Aurora] 15/15 100% [================]  OK    gc tracker  (1ms)
[06:18:23] [Server thread/INFO]: [Aurora] COMPLETE | 15/15 stages completed without error | 0 failed | 160ms
[06:18:23] [Server thread/INFO]: [Aurora] Minecraft continues loading; wait for the server-ready message before connecting.
[06:18:23] [Server thread/INFO]: [Aurora] Thread ledger: 22 configured platform thread(s) in 8 pool(s) for 8 available processor(s) (2.8x); configured ceilings, not measured load
[06:18:23] [Server thread/INFO]: [Aurora]   Region tick             8  (paper-global.yml threaded-regions.threads)
[06:18:23] [Server thread/INFO]: [Aurora]   Chunk workers           2  (paper-global.yml chunk-system.worker-threads)
[06:18:23] [Server thread/INFO]: [Aurora]   Chunk I/O               1  (paper-global.yml chunk-system.io-threads)
[06:18:23] [Server thread/INFO]: [Aurora]   Netty event loops       4  (spigot.yml settings.netty-threads)
[06:18:23] [Server thread/INFO]: [Aurora]   Async scheduler core    4  (Folia async scheduler: max(4, processors / 2) core threads, more on demand)
[06:18:23] [Server thread/INFO]: [Aurora]   Async pathfinding     off  (aurora.entity.async-pathfinding (processors / 4 when on))
[06:18:23] [Server thread/INFO]: [Aurora]   Bridge I/O lane         2  (aurora.scheduler.bridge-io-threads)
[06:18:23] [Server thread/INFO]: [Aurora]   Storage lane            1  (aurora.scheduler.storage-threads)
[06:18:23] [Server thread/INFO]: [Aurora]   Not counted: virtual-thread executors, and async scheduler threads beyond its core size.
[06:18:23] [Server thread/INFO]: Default game type: SURVIVAL
[06:18:23] [Server thread/INFO]: Generating keypair
[06:18:23] [Server thread/INFO]: Starting Minecraft server on 127.0.0.1:55011
[06:18:23] [Server thread/INFO]: Paper: Using libdeflate (macOS ARM64 / Apple Silicon) compression from Velocity.
[06:18:23] [Server thread/INFO]: Paper: Using native (macOS ARM64 / Apple Silicon) cipher from Velocity.
[06:18:23] [Server thread/WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!
[06:18:23] [Server thread/WARN]: The server will make no attempt to authenticate usernames. Beware.
[06:18:23] [Server thread/WARN]: While this makes the game possible to play without internet access, it also opens up the ability for hackers to connect with any username they choose.
[06:18:23] [Server thread/WARN]: To change this, set "online-mode" to "true" in the server.properties file.
[06:18:23] [Server thread/INFO]: Preparing level "world"
[06:18:24] [Server thread/INFO]: [ConfigurationProvider] canvas-worlds.yml not found; writing the default configuration
[06:18:24] [Server thread/INFO]: [Aurora] Loaded Aurora world settings for level minecraft:overworld
[06:18:24] [Server thread/INFO]: Selecting spawn point for level 'minecraft:overworld'...
[06:18:31] [Server thread/INFO]: [Aurora] Loaded Aurora world settings for level minecraft:the_nether
[06:18:31] [Server thread/INFO]: Selecting spawn point for level 'minecraft:the_nether'...
[06:18:31] [Server thread/INFO]: [Aurora] Loaded Aurora world settings for level minecraft:the_end
[06:18:31] [Server thread/INFO]: Selecting spawn point for level 'minecraft:the_end'...
[06:18:32] [Server thread/INFO]: Loading 0 persistent chunks for level 'minecraft:overworld'...
[06:18:32] [Server thread/INFO]: Preparing spawn area: 100%
[06:18:32] [Server thread/INFO]: Prepared spawn area in 7901 ms
[06:18:32] [Server thread/INFO]: Loading 0 persistent chunks for level 'minecraft:the_nether'...
[06:18:32] [Server thread/INFO]: Preparing spawn area: 100%
[06:18:32] [Server thread/INFO]: Prepared spawn area in 1227 ms
[06:18:32] [Server thread/INFO]: Loading 0 persistent chunks for level 'minecraft:the_end'...
[06:18:32] [Server thread/INFO]: Preparing spawn area: 100%
[06:18:32] [Server thread/INFO]: Prepared spawn area in 314 ms
[06:18:32] [Server thread/INFO]: Done preparing level "world" (8.346s)
[06:18:32] [Server thread/INFO]: [spark] Starting background profiler...
[06:18:34] [Server thread/INFO]: [SourbyCraft] Aurora startup: 22139 ms to ready, empty start; no previous profile
[06:18:34] [Server thread/INFO]: Saving chunks for level 'ServerLevel[world]'/minecraft:overworld
[06:18:34] [Server thread/INFO]: Saving chunks for level 'ServerLevel[world]'/minecraft:the_nether
[06:18:34] [Server thread/INFO]: Saving chunks for level 'ServerLevel[world]'/minecraft:the_end
[06:18:34] [Server thread/INFO]: ThreadedAnvilChunkStorage (world): All chunks are saved
[06:18:34] [Server thread/INFO]: ThreadedAnvilChunkStorage (DIM-1): All chunks are saved
[06:18:34] [Server thread/INFO]: ThreadedAnvilChunkStorage (DIM1): All chunks are saved
[06:18:34] [Server thread/INFO]: ThreadedAnvilChunkStorage: All dimensions are saved
[06:18:34] [Server thread/INFO]: [TickRegionScheduler] Starting EDF region scheduler
[06:18:34] [Server thread/WARN]: [TickRegionScheduler] Region profiling not supported in this environment
[06:18:34] [Server thread/INFO]: Done (19.251s)! For help, type "help"
```

## Gate 5: command transcripts

Load: no players. After boot, `forceload add -64 -64 64 64` (overworld, 81 chunks) and
`execute in minecraft:the_nether run forceload add 1000 1000 1100 1100` (49 chunks), then 60 villagers +
60 zombies summoned near 0,0 (57 villagers alive at 06:20:46). Two active regions.
Commands sent with `agent_testserver.py cmd --name opus-gate48-log-1 "<cmd>"` (bracketed capture);
`spark ...` with `--raw` (spark answers on an async thread, after the end marker).

### Before any region had samples (06:18:58–06:19:01, idle, 0 active regions)

`/tps`: all region windows `unavailable`, `Active regions: 0`, `Global: TPS 19.99 / MSPT 0.20ms`,
`Freshness: WARMING: FIVE_SECONDS: no samples`. `/mspt`, `/perf tick`, `/perf region`: `unavailable`, same
freshness. `/spark tps` at 06:19:01 printed:

```
[⚡] TPS from last 5s, 10s, 1m, 5m, 15m:
[⚡]  0.0, 0.0, 0.0, 0.0, 0.0
[⚡] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:
[⚡]  NaN/NaN/NaN/NaN;  0.8/3.6/75.6/73.6
```

**Finding:** with no region samples the provider's `worstTps()` is NaN; `/tps` prints `unavailable`, Spark
prints `0.0` TPS (spark's own formatting of `FoliaTickStatistics.tps()`), which reads as a stalled server.
Not changed here.

### Round 1 and 2 (same data source, back to back)

```
=== /tps
[06:20:56 INFO]: ╭─ SourbyCraft / TPS
[06:20:56 INFO]:   Aurora region metrics · tick execution and freshness
[06:20:56 INFO]:   Target 20.00 TPS
[06:20:56 INFO]:   5s  [████████████████████]  Worst 20.00
[06:20:56 INFO]:       Median 20.00 / Aggregate 20.00
[06:20:56 INFO]:   1m  [████████████████████]  Worst 20.00
[06:20:56 INFO]:       Median 20.00 / Aggregate 20.00
[06:20:56 INFO]:   5m  [████████████████████]  Worst 19.73
[06:20:56 INFO]:       Median 19.84 / Aggregate 19.83
[06:20:56 INFO]:   15m [████████████████████]  Worst 19.73
[06:20:56 INFO]:       Median 19.84 / Aggregate 19.83
[06:20:56 INFO]:   Worst average MSPT: 3.21ms
[06:20:56 INFO]:   Active regions: 2
[06:20:56 INFO]:   Global: TPS 19.99 / MSPT 0.040ms
[06:20:56 INFO]:   Memory 35.2%
[06:20:56 INFO]:   GC: 0.1% time  4.0/min  ~10.5ms avg
[06:20:56 INFO]:   Tier: GREEN
[06:20:56 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 151s of 300s
[06:20:56 INFO]:   [/perf tick]  [/perf region]  
[06:20:56 INFO]: ╰─ ────────────────────────────
=== /mspt
[06:20:56 INFO]: ╭─ SourbyCraft / MSPT
[06:20:56 INFO]:   Aurora region metrics · tick execution and freshness
[06:20:56 INFO]:   Worst average 3.21ms  [█···················]  (6% of 50.0ms budget)
[06:20:56 INFO]:   Estimated p95 4.35ms / p99 6.21ms (approximate)
[06:20:56 INFO]:   Exact recent max 5.67ms
[06:20:56 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 151s of 300s
[06:20:56 INFO]:   [/perf tick]  [/perf region]  
[06:20:56 INFO]: ╰─ ────────────────────────────
=== /perf tick
[06:20:56 INFO]: ╭─ SourbyCraft / Performance / tick
[06:20:56 INFO]:   Ticks
[06:20:56 INFO]:   Target TPS: 20.00
[06:20:56 INFO]:   Worst region TPS: 20.00
[06:20:56 INFO]:   Worst average MSPT: 3.21ms
[06:20:56 INFO]:   Estimated p95 / p99: 4.35ms / 6.21ms (approximate)
[06:20:56 INFO]:   Recent maximum: 5.67ms
[06:20:56 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 151s of 300s
[06:20:56 INFO]:   [/perf]  [/perf lanes]  [/perf network]  [/perf plugins]  
[06:20:56 INFO]: ╰─ ────────────────────────────
=== /spark tps (raw)
[06:20:57 INFO]: [⚡] TPS from last 5s, 10s, 1m, 5m, 15m:
[06:20:57 INFO]: [⚡]  20.0, 20.0, 20.0, 19.73, 19.73
[06:20:57 INFO]: [⚡] 
[06:20:57 INFO]: [⚡] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:
[06:20:57 INFO]: [⚡]  0.1/0.7/5.2/14.5;  0.1/0.5/4.3/14.5
[06:20:57 INFO]: [⚡] 
[06:20:57 INFO]: [⚡] CPU usage from last 10s, 1m, 15m:
[06:20:57 INFO]: [⚡]  59%, 48%, 64%  (system)
[06:20:57 INFO]: [⚡]  1%, 2%, 6%  (process)
=== /tps
[06:20:59 INFO]: ╭─ SourbyCraft / TPS
[06:20:59 INFO]:   Aurora region metrics · tick execution and freshness
[06:20:59 INFO]:   Target 20.00 TPS
[06:20:59 INFO]:   5s  [████████████████████]  Worst 19.98
[06:20:59 INFO]:       Median 20.00 / Aggregate 20.00
[06:20:59 INFO]:   1m  [████████████████████]  Worst 20.00
[06:20:59 INFO]:       Median 20.00 / Aggregate 20.00
[06:20:59 INFO]:   5m  [████████████████████]  Worst 19.75
[06:20:59 INFO]:       Median 19.85 / Aggregate 19.84
[06:20:59 INFO]:   15m [████████████████████]  Worst 19.75
[06:20:59 INFO]:       Median 19.85 / Aggregate 19.84
[06:20:59 INFO]:   Worst average MSPT: 3.02ms
[06:20:59 INFO]:   Active regions: 2
[06:20:59 INFO]:   Global: TPS 20.00 / MSPT 0.099ms
[06:20:59 INFO]:   Memory 22.9%
[06:20:59 INFO]:   GC: 0.1% time  5.0/min  ~10.6ms avg
[06:20:59 INFO]:   Tier: GREEN
[06:20:59 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 155s of 300s
[06:20:59 INFO]:   [/perf tick]  [/perf region]  
[06:20:59 INFO]: ╰─ ────────────────────────────
=== /mspt
[06:20:59 INFO]: ╭─ SourbyCraft / MSPT
[06:20:59 INFO]:   Aurora region metrics · tick execution and freshness
[06:20:59 INFO]:   Worst average 3.02ms  [█···················]  (6% of 50.0ms budget)
[06:20:59 INFO]:   Estimated p95 4.35ms / p99 5.20ms (approximate)
[06:20:59 INFO]:   Exact recent max 14.5ms
[06:20:59 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 155s of 300s
[06:20:59 INFO]:   [/perf tick]  [/perf region]  
[06:20:59 INFO]: ╰─ ────────────────────────────
=== /perf tick
[06:20:59 INFO]: ╭─ SourbyCraft / Performance / tick
[06:20:59 INFO]:   Ticks
[06:20:59 INFO]:   Target TPS: 20.00
[06:20:59 INFO]:   Worst region TPS: 19.98
[06:20:59 INFO]:   Worst average MSPT: 3.02ms
[06:20:59 INFO]:   Estimated p95 / p99: 4.35ms / 5.20ms (approximate)
[06:20:59 INFO]:   Recent maximum: 14.5ms
[06:20:59 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 155s of 300s
[06:20:59 INFO]:   [/perf]  [/perf lanes]  [/perf network]  [/perf plugins]  
[06:20:59 INFO]: ╰─ ────────────────────────────
=== /spark tps (raw)
[06:21:00 INFO]: [⚡] TPS from last 5s, 10s, 1m, 5m, 15m:
[06:21:00 INFO]: [⚡]  19.99, 19.99, *20.0, 19.75, 19.75
[06:21:00 INFO]: [⚡] 
[06:21:00 INFO]: [⚡] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:
[06:21:00 INFO]: [⚡]  0.1/0.4/4.3/14.5;  0.1/0.5/4.3/14.5
[06:21:00 INFO]: [⚡] 
[06:21:00 INFO]: [⚡] CPU usage from last 10s, 1m, 15m:
[06:21:00 INFO]: [⚡]  54%, 47%, 63%  (system)
[06:21:00 INFO]: [⚡]  1%, 2%, 6%  (process)
```

### `/perf` overview, `/perf region` (summary and per location), `/perf memory`

```
=== /perf
[06:21:19 INFO]: ╭─ SourbyCraft / Performance / overview
[06:21:19 INFO]:   Ticks
[06:21:19 INFO]:   Target TPS: 20.00
[06:21:19 INFO]:   Worst region TPS: 19.99
[06:21:19 INFO]:   Worst average MSPT: 3.33ms
[06:21:19 INFO]:   Estimated p95 / p99: 5.20ms / 10.6ms (approximate)
[06:21:19 INFO]:   Recent maximum: 37.8ms
[06:21:19 INFO]:   CPU
[06:21:19 INFO]:   Process CPU / system CPU: 1.2% / 57.9%
[06:21:19 INFO]:   Available processors / live platform threads: 8 / 46
[06:21:19 INFO]:   Uptime: 186s
[06:21:19 INFO]:   Memory
[06:21:19 INFO]:   Heap used / committed / maximum: 434M / 770M / 2.0G
[06:21:19 INFO]:   Heap usage: 21.2% of maximum
[06:21:19 INFO]:   Non-heap: 207M
[06:21:19 INFO]:   Process RSS: unavailable
[06:21:19 INFO]:   Container memory usage: unavailable
[06:21:19 INFO]:   Allocation rate: n/a (allocation rate is not collected)
[06:21:19 INFO]:   Garbage collection
[06:21:19 INFO]:   GC collections / cumulative collection time: 78 / 626ms
[06:21:19 INFO]:   GC collections/min: 5.0
[06:21:19 INFO]:   GC young / old collections: n/a (all pools are aggregated)
[06:21:19 INFO]:   Last GC pause: n/a (only the mean per cycle is collected)
[06:21:19 INFO]:   Pause distribution / allocation samples: use JFR or /spark profiler start
[06:21:19 INFO]:   GC scope: MXBean collection time is not stop-the-world pause time
[06:21:19 INFO]:   Regions
[06:21:19 INFO]:   Active regions / retained generations: 2 / 5
[06:21:19 INFO]:   Worst / median / aggregate average MSPT: 3.33ms / 1.76ms / 1.76ms
[06:21:19 INFO]:   Slowest region (5s): world #1 region #4 gen 4: 3.33ms avg, 37.8ms max over 100 ticks
[06:21:19 INFO]:   Per-location region metrics: /perf region <world> <x> <z>
[06:21:19 INFO]:   Region coordinates / queues: not instrumented
[06:21:19 INFO]:   Health
[06:21:19 INFO]:   Tick health: WARMING
[06:21:19 INFO]:   Diagnostics: settings are operator-owned; /spark profiler start for samples
[06:21:19 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 175s of 300s
[06:21:19 INFO]:   [/perf]  [/perf lanes]  [/perf network]  [/perf plugins]  
[06:21:19 INFO]: ╰─ ────────────────────────────
=== /perf region
[06:21:19 INFO]: ╭─ SourbyCraft / Performance / region
[06:21:19 INFO]:   Regions
[06:21:19 INFO]:   Active regions / retained generations: 2 / 5
[06:21:19 INFO]:   Worst / median / aggregate average MSPT: 3.33ms / 1.76ms / 1.76ms
[06:21:19 INFO]:   Slowest region (5s): world #1 region #4 gen 4: 3.33ms avg, 37.8ms max over 100 ticks
[06:21:19 INFO]:   Per-location region metrics: /perf region <world> <x> <z>
[06:21:19 INFO]:   Region coordinates / queues: not instrumented
[06:21:19 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 175s of 300s
[06:21:19 INFO]:   [/perf]  [/perf lanes]  [/perf network]  [/perf plugins]  
[06:21:19 INFO]: ╰─ ────────────────────────────
=== /perf region world 0 0
[06:21:19 INFO]: ╭─ SourbyCraft / Performance / region
[06:21:19 INFO]:   World / block: world / 0, 0
[06:21:19 INFO]:   Chunk: 0, 0
[06:21:19 INFO]:   Region: world #1 region #4 gen 4
[06:21:19 INFO]:   TPS (5s / 1m): 19.99 / 20.00
[06:21:19 INFO]:   Average MSPT (5s / 1m): 3.29ms / 3.08ms
[06:21:19 INFO]:   Maximum MSPT (5s): 37.8ms
[06:21:19 INFO]:   Samples (5s): 100 ticks
[06:21:19 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 175s of 300s
[06:21:19 INFO]: ╰─ ────────────────────────────
=== /perf region world_nether 1050 1050
[06:21:19 INFO]: ╭─ SourbyCraft / Performance / region
[06:21:19 INFO]:   World / block: world_nether / 1050, 1050
[06:21:19 INFO]:   Chunk: 65, 65
[06:21:19 INFO]:   Region: world #2 region #5 gen 5
[06:21:19 INFO]:   TPS (5s / 1m): 19.98 / 20.00
[06:21:19 INFO]:   Average MSPT (5s / 1m): 0.18ms / 0.18ms
[06:21:19 INFO]:   Maximum MSPT (5s): 0.57ms
[06:21:19 INFO]:   Samples (5s): 100 ticks
[06:21:19 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 176s of 300s
[06:21:19 INFO]: ╰─ ────────────────────────────
=== /perf memory
[06:21:20 INFO]: ╭─ SourbyCraft / Memory
[06:21:20 INFO]:   Memory
[06:21:20 INFO]:   Heap used / committed / maximum: 447M / 770M / 2.0G
[06:21:20 INFO]:   Heap usage: 21.8% of maximum
[06:21:20 INFO]:   Non-heap: 208M
[06:21:20 INFO]:   Process RSS: unavailable
[06:21:20 INFO]:   Container memory usage: unavailable
[06:21:20 INFO]:   Allocation rate: n/a (allocation rate is not collected)
[06:21:20 INFO]:   Garbage collection
[06:21:20 INFO]:   GC collections / cumulative collection time: 78 / 626ms
[06:21:20 INFO]:   GC collections/min: 5.0
[06:21:20 INFO]:   GC young / old collections: n/a (all pools are aggregated)
[06:21:20 INFO]:   Last GC pause: n/a (only the mean per cycle is collected)
[06:21:20 INFO]:   Pause distribution / allocation samples: use JFR or /spark profiler start
[06:21:20 INFO]:   GC scope: MXBean collection time is not stop-the-world pause time
[06:21:20 INFO]:   Freshness: WARMING: FIVE_MINUTES: observed 176s of 300s
[06:21:20 INFO]:   [/perf]  [/perf lanes]  [/perf network]  [/perf plugins]  
[06:21:20 INFO]: ╰─ ────────────────────────────
```

Earlier idle `/perf` and `/perf memory` (06:18:59–06:19:00) and `/ver` are in `logs/latest.log` lines 100–260 of the raw copy.

### Do the numbers agree? (from code)

| Surface | Source | Window / statistic |
| --- | --- | --- |
| `/tps` `Worst average MSPT`, window rows | `MetricsRuntime.provider().snapshot()` | FIVE_SECONDS (rows: 5s/1m/5m/15m) `worstTps` = min over regions, `worstAverageMspt` = max over region averages |
| `/mspt` | same snapshot | FIVE_SECONDS `worstAverageMspt`, `estimatedP95/P99Mspt`, `maximumMspt` |
| `/perf`, `/perf tick`, `/perf region` (summary) | same snapshot (`PerfCommand.render` reads it once) | FIVE_SECONDS |
| `/perf region <world> <x> <z>` | `RegionLocator.locate(..., System.nanoTime())`: the region's live `RegionTickMetrics`, not the published snapshot | that region's 5s / 1m |
| `/spark tps` | `FoliaTickStatistics(MetricsRuntime.provider())` = same snapshot | TPS: `worstTps` per window; durations: min / pooled median / estimated p95 / max of the 10s and 1m windows |

The snapshot is republished by `PerformanceCollector` once per second (`PERIOD_NANOS` = 1 s), so commands
answered in the same second read the same generation.

Observed:

- Round 1 (06:20:56): `/tps` worst-average 3.21 ms = `/mspt` 3.21 ms = `/perf tick` 3.21 ms; p95/p99 4.35/6.21 and max 5.67 identical in `/mspt` and `/perf tick`; worst TPS 20.00 in `/tps` and `/perf tick`. Round 2 (06:20:59): 3.02 ms / 3.02 / 3.02, worst TPS 19.98 in both, max 14.5 in both. **Exact agreement within one generation.**
- `/spark tps` 06:20:57: 5s TPS `20.0` vs `/tps` 20.00; 06:21:00: `19.99` vs `/tps` 19.98 one second earlier (next generation). Spark's 10s max 14.5 ms appears in `/mspt`'s 5s max only at 06:20:59 because the 14.5 ms tick happened between 06:20:56 and 06:20:57 (Spark's 10s window already held it; the 5s window at 06:20:56 was published before it).
- Spark's `med` (0.4–0.7 ms) is not the worst-region average (3.0–3.2 ms): it is the pooled median over every region's ticks (`PerformanceCollector.finish`: histogram quantile 0.5); the idle nether region contributes half the ticks. Spark's `tps` output does not print the mean (where `FoliaTickStatistics` puts `worstAverageMspt`).
- `/perf region world 0 0` 5s average 3.29 ms vs `/perf` worst average 3.33 ms in the same second: the per-location view reads the region's live metrics at call time, the summary reads the snapshot published up to 1 s earlier. Its max (37.8 ms) and 100 samples match the summary's slowest region.
- `/perf` `median / aggregate average MSPT: 1.76 / 1.76` with two regions (3.33 and 0.18 ms) is consistent (median of two = mean).
- Estimated p95 can exceed the exact max: Spark 06:19:08 `0.3/0.4/1.0/0.9` and 1m `.../75.6/73.6`; histogram-bin estimate vs exact maximum. `/mspt` and `/perf` label p95/p99 "approximate"; Spark's column does not.
- `Global: TPS 19.99 / MSPT 0.040–0.20ms` in `/tps` is the global tick's own window (`globalWindow`), separate from the region figures, shown only in `/tps`.

### Spark report with SourbyCraft/Aurora config (offline)

No upload (offline rule). `spark profiler start` at 06:21:20, `spark profiler stop --save-to-file` at 06:21:30:

```
[⚡] Profiler stopped & save complete!
[⚡] Data has been written to: plugins/spark/profile-2026-10-22_06.21.31.sparkprofile
```

File SHA-256 `923d6cf7fdad95dc4ded46755e7716c7f41cc1e4aec5c4e80068be0d9517cefa`, 120281 bytes. Not opened in
the Spark web viewer; contents read with `strings -n 6` (protobuf, uncompressed strings). It contains:

- platform: `SourbyCraft`, `Build 48-dirty (MC:26.2)`;
- server configurations from `SourbyServerConfigProvider`: `server.properties`, `bukkit.yml`, `spigot.yml`, group `aurora/` (`canvas-server.yml`: `region-scheduler.guard-severity: "LOG"`, `default-tick-rate: 20.0`, `server-mod-name: "SourbyCraft"`, ...), `paper/` (`global.yml`, ...), group `sourbycraft/` (`global.toml`, `aurora.toml` with `aurora.scheduler`, `aurora.awf`, `aurora.diagnostics.stall-recorder`, `aurora.bridge.mode: "off"`, `aurora.entity.async-pathfinding: false`, ...; `security.yml` crash-prevention and packet-guard limits);
- extra metadata `sourbycraft` (`"identity":"Build 48-dirty — Aurora Nexus (DEV, HEAD@6176d51)"`, `"engine":"Aurora"`, runtime RUNNING, bridge `not-started`, switches, startup 22139 ms) and `sourbycraft-threads` (thread classes; `"Region and global tick": 8 threads, pool "Folia Region Scheduler Thread"`).

Full extracted strings: `sparkprofile-config-strings.txt` in the raw directory.

## Not covered

- HUD bars (TPS/RAM boss/action bars): need a real client; not observed, not claimed.
- Spark web viewer rendering and an uploaded report: not done (offline); only the local file's contents were read.
- CI boot excerpt for this change: CI has not run it (worktree only). The tag-commit CI run is still the gate's evidence.
- No player-driven load, one short run, shared machine: these transcripts show agreement between surfaces, not performance.
- `/perf lanes`, `/perf network`, `/perf plugins`, `/perf governor` were not captured.
- Remaining Folia text (thread names, thread-ledger description) and `canvas-*.yml` names: listed above, not changed.
