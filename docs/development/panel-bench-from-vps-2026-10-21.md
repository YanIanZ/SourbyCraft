# Panel bench from the Linux VPS (2026-10-21)

Status: **NOT CERTIFIED.** Functional evidence only. Owner decision 2026-10-21: the Linux VPS is the
load generator for the panel server "Sourby Demo"; the clients run on the VPS, not on the operator's
laptop. Raw output: `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/panel-bench-2026-10-21/` (not in git).

## Result in one paragraph

`players-10` ran to completion from the VPS on both jars with all 10 clients in play for the whole
window (after three client fixes below). `players-50` did **not** complete on either jar: about a minute
after the 48th client was teleported to its site the server stopped answering (global tick stuck,
spark "Timed out waiting for world statistics" for ~2.5-3 min), the process then disappeared with no
crash report and no shutdown log, and the panel restarted it on its own. Same symptom twice, once per
jar. The cause is not established (hang, deadlock or OOM kill are all consistent with the log; none is
proven). That is the main open finding.

## Setup

| Item | Value |
| --- | --- |
| Panel server | Sourby Demo, 8 logical CPUs seen by the JVM, heap max 10.0G (`-XX:MaxRAMPercentage=95`), Java 25.0.4.1 |
| Jar A (found deployed) | `SourbyCraft-slim.jar`, 40685561 bytes, SHA-256 `7b20d9d3c96876bbf22884d3a2f4ba71d991b34890da55394f499bb0a9562973`; `/ver` "Build 47 - Aurora Nexus", Git `26.2@aca6bf6`, built 2026-10-06. An old dev jar, not the release. Backup kept at `.../jar-backup/` (also on the panel as `SourbyCraft-slim.pre-r47-20261021.jar`) |
| Jar B (deployed afterwards, stays) | release `v26.2-r47` `SourbyCraft-26.2-REL.jar` uploaded as `SourbyCraft-slim.jar`, 39879235 bytes, SHA-256 `411eadb035cfde40ddb2264bd38f7c3cc603878b410ba1079e5a6dbb60af938d` (equals the release `.sha256`); `/ver` Git `26.2@2c8660b75ee1`, "source clean"; boot reached `Done (` in 34.1 s, 0 ERROR lines, 9 WARN |
| Plugins on the panel | EconomyShopGUI-Premium, Essentials (bridged), Intave (anticheat, trial mode), packetevents, ProtocolLib, SuperiorSkyblock2, Vault (bridged), ViaBackwards, ViaVersion, SourbyCraft-Test-Plugin |
| Load generator | VPS, Ubuntu 24.04, kernel 6.8, QEMU guest, 6 vCPU AMD EPYC, 11.7 GB RAM, shared with production Docker services. Python 3.12 stdlib only, `systemd-run --scope -p CPUQuota=400% -p MemoryMax=8G nice -n 10`, outbound only |
| Network VPS to game host (`hyka.parama.cloud:25555`) | ICMP 53 ms min, 81-141 ms mean, up to 2.1 s; TCP connect (10 samples at run start) median 85-170 ms, max 0.6-6.1 s; 90 s probe: p50 145 ms, p90 1.8 s, 29 % of connects over 500 ms. The laptop to the same host: p50 36 ms, max 99 ms. The VPS path is jittery |
| Workload | `baseline_workloads.py` `players-10` and `players-50`, seed 440044, bench world `bench`, offline-mode + whitelist of `BenchNNN` only (bench mode of `bench_panel.py`) |
| Panel edits during a run (restored by the tool) | server.properties (level-name, seed, online-mode, white-list, enforce-whitelist, allow-flight, and `max-players=50` for players-50), whitelist.json, bukkit.yml, plus `--edit` of `plugins/SuperiorSkyblock2/config.yml` `location: world,...` to `bench` (see "Findings") |

## Commands (credentials are only in the VPS-side `.env`, sourced, never printed)

On the VPS, in `/opt/sourby-agent/bench/` (the five stdlib files `bench_panel.py baseline_client.py
baseline_network.py baseline_workloads.py pterodactyl_panel.py`; `.env` exports `PTERODACTYL_KEY`,
`PTERODACTYL_PANEL`, `PTERODACTYL_SERVER`):

```bash
systemd-run --scope --quiet -p CPUQuota=400% -p MemoryMax=8G nice -n 10 \
  python3 -u bench_panel.py --panel "$PTERODACTYL_PANEL" --server "$PTERODACTYL_SERVER" \
  --host hyka.parama.cloud --port 25555 --seed 440044 \
  --edit 'plugins/SuperiorSkyblock2/config.yml=location: world, 0, 100, 0, 0, 0=>location: bench, 0, 100, 0, 0, 0' \
  --clients-on 'a shared Linux VPS (6 vCPU AMD EPYC, 12 GB, CPUQuota 400%)' \
  --workload players-10 --warmup 120 --duration 300 --sample-seconds 30 --client-processes 3 \
  --output out/<name>
# players-50: --workload players-50 --warmup 120 --duration 600 --client-processes 4 --max-players 50
```

Window lengths are shorter than the 600 s of the 2026-10-06 laptop run for players-10 (300 s), and
the sample count is 6-7, not 18, because each `/perf` sample takes about 45 s over the panel API.

## Results: players-10, old jar vs r47 (same workload, seed, 10 clients, 120 s warmup, 300 s window)

NOT CERTIFIED: shared VPS, jittery path, console-sampled `/perf`, panel node not known idle, 6-7 samples,
one run per jar. No performance claim between the jars is made or supported by these numbers.

| Metric (median of samples; min-max) | Old jar `aca6bf6` (6 samples) | r47 `2c8660b` (7 samples) |
| --- | ---: | ---: |
| clients in play | 10 (9 at last sample; 1 closed late) | 10 for all samples |
| worst region TPS | 20.0 (19.98-20.0) | 20.0 (19.99-20.0) |
| worst region average MSPT | 2.94 ms (2.30-3.44) | 2.73 ms (2.42-3.42) |
| estimated p95 / p99 MSPT | 5.2 / 7.43 ms | 6.21 / 8.88 ms |
| p99 max over samples | 10.6 ms | 15.2 ms |
| recent maximum MSPT | 5000 ms in every sample (see below) | 5000 ms in every sample |
| active regions | 10 | 10 |
| process CPU (of the 8-CPU machine) | 77.6 % (56.7-78.2) | 71.7 % (66.1-89.9) |
| heap used | 1.4-1.9 G | 1.6-2.5 G |
| setup command errors | none | none |
| client-side | 3 processes, 355 CPU-s, 164 duplicate teleports skipped | 3 processes, 183 CPU-s, 212 duplicate teleports skipped |
| connect RTT (TCP, start) | 73 / 168 / 634 ms | 60 / 92 / 453 ms |

What it does show: with real connected clients, both jars hold 20 TPS and about 3 ms worst-region
average MSPT at 10 regions. What stands out and is not explained: process CPU is 57-90 % of the machine
with ten clients, against 6-8 % with the same server and no clients in the same hour (`/perf history`).
That is much higher than the 17.8 % median of the 2026-10-06 laptop run (build `3562a859`), and MSPT
does not account for it. Candidates (not checked): off-thread chunk generation for flying clients,
network/Intave packet handling, GC (172 collections/min). `Recent maximum 5000.0ms` is the same value in
every sample of both jars, so it reads like a clamp or a single early stall, not a per-sample reading; treat it as
unreliable until the `/perf tick` source is checked.

The per-jar difference in client CPU-seconds and heap is run-to-run noise as far as can be said; there is
no basis here to attribute it to the jars.

## players-50: did not complete (both jars)

| | Old jar `aca6bf6` | r47 `2c8660b` |
| --- | --- | --- |
| clients connected | 50 of 50 in play | 50 of 50 in play |
| last teleport logged | Bench046 (15:16:07) | Bench047 (16:18:39) |
| server then | "spark Timed out waiting for world statistics" from 15:16:22, log ends 15:16:52 | same from 16:18:49, log ends 16:21:29 |
| process | gone, panel restarted it (new boot 15:17:50) | gone, panel restarted it (new boot 16:21:46) |
| crash-reports / hs_err on the panel | none | none |
| bench result | aborted ("all clients disconnected"), restored | same |

The 1558 setup commands and the settle period finished without errors before clients connected. The
metrics shown for those two runs in the tool's stdout belong to the restarted server (1.0 ms MSPT,
7 % CPU) and are not measurements. Needs a next step before any 50-player number exists: poll the panel
`/resources` (memory, CPU) every few seconds during the run to separate an out-of-memory kill from a hang,
and get a thread dump while the server is stuck. Heap max is 10 G of a 10 GiB container (`MaxRAMPercentage=95`),
so a native-memory kill near the cap is possible; that is a guess.

## Findings and fixes made on the way (scripts in worktree `sc-worktrees/vpsbench`, PM applies)

Each failed attempt cost one stop/start of the panel server (about 5 minutes each); about ten bench windows
were opened on the panel in total, all with the whitelist-only bench mode and verified restore.

1. **SuperiorSkyblock2 stops the server in bench mode.** Its `spawn.location` names world `world`; with
   `level-name=bench` it logs "The spawn location is in invalid world" and shuts the server down, and the
   panel restarts it in a loop (five boots in ten minutes). Fixed per run with the existing
   `--edit` option (restored). Needs `--edit` for any panel with that plugin.
2. **Remote hosts: HTTP 502 after boot.** The panel answers 502 to console commands for a stretch after
   `running`. `bench_panel.py` now waits through transient `URLError`/`HTTPError` (`wait_until_answering`) and
   uses 8 API retries in bench runs.
3. **`--edit` on one file twice silently kept only the last edit.** Fixed (`apply_edits`).
4. **`--max-players`** added (restored); a run with more clients than the server's `max-players` now stops
   before touching the panel.
5. **`bench_panel.py` no longer imports `verify_persistence`** (own `offline_uuid`), so it is five stdlib files.
6. **Client bug: duplicate teleport confirmations.** The server resends an unconfirmed teleport once a
   second; a client that is behind confirmed every copy and was kicked with "Invalid move player packet
   received" (a confirmation with no teleport awaiting). 6 of 10 clients were lost this way in 45 s on the
   first VPS run. `HeadlessClient._sync` now confirms an id once (and applies relative-flag teleports as
   offsets, and ignores non-finite positions). Applies to every user of `baseline_client`.
7. **Client bug: moves only when a packet arrives.** With `timer_moves=True` (set by `bench_panel.py`) a
   mover thread sends a move four times a second; without it, a quiet link stalled both ends until the
   server's 30 s "Timed out". Off by default elsewhere.
8. **GIL limit.** All clients as threads of one Python process; `--client-processes N` (`ShardedSwarm`)
   spreads them over processes. Client processes used 10-60 % of a core each at 10 clients; the VPS was
   93-98 % idle, so the earlier losses were not CPU.
9. Reports now carry `failure_reasons`, `failure_traces` (last events of up to 4 failed clients),
   `duplicate_teleports`, `cpu_seconds`, `processes`, plus the TCP connect RTT from the client host.

Intave on the panel flags every bot ("sending invalid packets", "connected through a VPN") but in its
trial configuration it only notifies; disabling `protocolscanner`, `reverse-blink` and
`position-feedback-sync-enforcement` in a bench window did not change the outcome (runs 4-5), so it is
not the cause of the client losses. Those edits were reverted by the tool (verified at the end:
`protocolscanner enabled: true`, `reverse-blink: true`).

Tests: `scripts/test_bench_panel.py` (13), new `scripts/test_baseline_client_position.py` (10); all pass.

## State left behind

- Panel runs release r47, normal mode: `server.properties`, `whitelist.json`, `ops.json`, `bukkit.yml`,
  SuperiorSkyblock2 and Intave configs are byte-identical to the originals except the
  `#<date>` comment line the server rewrites on every boot. 0 players, 0 ERROR lines in the current log.
- The bench world `bench/` was left in place (the tool never removes it). It now also holds the 50-site
  forceloads and entities of the aborted players-50 runs; `plugins/Essentials/userdata` and SuperiorSkyblock2
  data gained `Bench000-049` player records (Essentials had Bench000-009 files from 2026-10-06 already).
- The previous jar is the panel file `SourbyCraft-slim.pre-r47-20261021.jar` and the local backup above.
- VPS: bench directory and credential copy deleted, no scope or process left. See the coordination note
  about the Docker containers.

## Limitations

Shared VPS with a jittery path to the node (not a lab network); console-sampled `/perf` (about 45 s per
sample, 6-7 samples); one run per jar and workload; bots at view distance 2 with a Python protocol
implementation; the panel node is not known to be idle; Intave and the other plugins stay loaded, so the
numbers describe this deployment, not the engine; no JFR. Do not compare these numbers with the
2026-10-06 laptop run or with any local baseline.

## Hang analysis (read-only)

Source: the two server logs of the aborted players-50 runs (`players-50-run1/server-boot1.log`, old jar
`aca6bf6`; `r47-players-50/server-2026-10-21-41.log`, r47 `2c8660b`), both bench `bench.json` files,
`players-10`/`r47-players-10` samples for comparison, and the code at `26.2` HEAD. Nothing was run; no new
measurement. Times are the panel server's clock (UTC).

### 1. Timeline (r47 run; the old-jar run in the right column where it differs)

| Time (r47) | Event | Old jar |
| --- | --- | --- |
| 15:46:17-15:46:51 | boot, `Done (34.797s)`. JVM flag advisor WARN: heap max is 95 % of the container's 10.5G. 8 region tick threads (`aurora.cpu.cores = 8 of 8`), 6 chunk workers, 1 I/O, 4 Netty | boot 14:35:58, `Done` 14:36:28 |
| 15:47:11-15:48:00 | `/perf` by hand: 0 players, heap 1.6G of 10.0G, RSS 2.7G, container 25.3 %, 95 platform threads | same shape |
| 15:47:15-15:48:00, 15:53:01-16:15:21 | setup: kill 137 entities, 50 forceload sites (25 chunks each, last site `[-322,318]`), 1500 mobs summoned, villagers dying to zombies | same |
| 16:17:07 | `connection-guard ... max-connections-per-second measured=21 limit=20` (LOG only, VPS address) | |
| 16:17:08-16:17:38 | 50 clients log in, all at spawn (0,0) = one region | 15:13:35-15:14:09; Bench033 `lost connection: Timed out` 15:14:16 |
| 16:17:41-16:18:39 | 48 teleports, one per ~1.2 s (`execute positioned X 0 Z positioned over world_surface run tp BenchNNN`), Bench000..Bench047; 62 "was shot/slain" lines from skeletons/zombies at spawn | 15:14:21-15:16:07, last Bench046 (Bench033 skipped) |
| **16:18:39** | **last region/global-thread line: `Teleported Bench047`**. Bench048 and Bench049 are never teleported (their commands are never executed) | 15:16:07 |
| 16:18:49 | first `[spark] Timed out waiting for world statistics` (10 s after the last teleport) | 15:16:22 (15 s) |
| 16:18:49-16:21:29 | spark repeats it every 10 s, 19 times (the async sampler worker adds a 60 s one: 16:18:55, 16:19:55) | 4 times, 15:16:22-15:16:52 |
| 16:18:39-16:21:17 | the only other lines are Intave's cloud retry (`ConnectException`, every 15 s, unchanged) | same |
| 16:20:55, 16:21:08 | Intave `auth failed: IOException: timed out` (every earlier failure in that process was `ConnectException`; zero "timed out" lines in the three neighbouring healthy logs 39/40/42) | 15:16:34, 15:16:34 (earlier `HTTP connect timed out` at 15:14:05-15:15:57 during the join/teleport storm) |
| **16:21:29** | **last server line** (spark timeout). The Intave retry due at ~16:21:32 never appears | 15:16:52; Intave retry due ~15:17:04 absent |
| 16:21:46 | first line of the next process (`[bootstrap] Running Java 25`) | 15:17:13 |

- Gap last line to next boot: about 17 s (r47), about 21 s (old jar). The process ended in the 3-4 s after the last line, so the
  gap is the panel's reaction time plus JVM start. Time stalled before the end: about 170 s (r47), about 45-50 s (old jar). They differ by 3.5x.
- What is **not** in either log: any ERROR/SEVERE, `Can't keep up`, `running behind`, watchdog text, `OutOfMemoryError`, stack trace,
  "Stopping the server", `lost connection` after the stall began, chunk/IO warnings. Only the one security LOG line and the usual startup WARNs
  (174 WARN lines in log 41: 139 Intave cloud-connection retries, 19 spark timeouts, the rest startup/security).
- GC, memory, thread count, TPS and MSPT during the stall: **not recorded anywhere.** The "samples" in both `bench.json` files come from the
  restarted server (uptime 70-71 s, 0 players): 7.2-7.7 % process CPU, heap 1.0-1.6G, RSS 2.7-2.9G are the idle numbers of a fresh boot.
  The last healthy readings before the players-50 spawn crowd are the 15:47:12 ones (heap 1.6G/10.0G, RSS 2.7G, 95 threads, GC 65 collections).
- Baseline under load for scale (players-10, same panel): heap used 1.2-2.5G, committed 2.1-2.8G, RSS 3.2-3.9G, container 30-37 %, 441-1244
  cumulative GCs. Nothing near the 10.5G cap was ever observed in any run.

### 2. Hypotheses, ranked

Two separate facts need explaining: (a) the global tick stops, (b) the process dies with no trace 50-170 s later.

**(a) The global tick stops while the JVM keeps running. Supported, cause unknown.**
- spark's world statistics are requested through `FoliaSparkPlugin.executeSync` (`sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/spark/FoliaSparkPlugin.java:139`),
  i.e. a task on the global region; 10 s without it running gives that exact WARN. The bench's own `/tp` commands also go through the console
  and global tick, and stopped (Bench048/049 never moved). So the global task queue is not draining.
- The Paper `WatchdogThread` is fed by `tickServer` on every region tick (`MinecraftServer.java.patch:538`, `org.spigotmc.WatchdogThread.tick()`), and it
  prints "The server has not responded for N seconds! Creating thread dump" after `earlyWarningDelay` 10 s without a tick
  (`paper-server/.../WatchdogThread.java:66`, defaults `GlobalConfiguration.java:155-156`). Nothing was printed in 170 s, so at least one region kept calling
  `tick()` all along (assumes `-Dpaper.disable-watchdog` is not set and spigot.yml `timeout-time` is not 0: not verified on the panel). This argues against a
  JVM-wide stop (safepoint, STW GC, swap death of the whole process) and against "all 8 region threads deadlocked". It points at the global tick thread (or a
  lock only the global tick needs) being blocked or in a runaway loop.
- Both jars (`aca6bf6`, 10-06, and `2c8660b`, release r47) do it, so it is not a regression of the last two weeks of patches.
- Not the same site/index each time (old jar stalled on the Bench047 command, r47 on Bench048), so not a bad coordinate.
- There is **no** region watchdog to say more: patch 0025 turns `FoliaWatchdogThread` off unless `-Dsourbycraft.region-watchdog.enabled=true`
  (`minecraft-patches/features/0025-...patch`, `docs/development/REGION-WATCHDOG.md`).

**(b) What ends the process.**

1. **Container OOM kill (cgroup SIGKILL), possibly driven by a runaway in (a). Most likely terminator; unproven.**
   For: the end is instant and silent (no hs_err, no crash report, no `OutOfMemoryError`, no stop log; a graceful or JVM-handled exit would normally leave a line); heap max is 10.0G inside a 10.5G container (`-XX:MaxRAMPercentage=95`, the server's own advisor WARN at 15:46:24), and the idle RSS is already 0.8G above heap
   committed (RSS 2.7G vs 1.9G committed; non-heap 310-340M plus direct buffers, thread stacks), so the kernel limit is reached at about 9.7G of
   heap, before the JVM would throw its own OOM; Wings crash detection restarts a SIGKILLed server, which matches "the panel restarted it" within about 15 s;
   a runaway that retains memory would give exactly this shape, and a different growth rate explains 48 s vs 172 s; in both runs outbound HTTP changes from refused to **timed out** 25-35 s
   before the last line (15:16:34 before ~15:17:00; 16:20:55 before ~16:21:31), which looks like host/cgroup pressure building at the end (weak: could be the network path).
   Against: no heap or RSS growth was ever observed (nothing was recorded during the stall); the JVM was still scheduling Intave and spark timers 12 s before the end;
   a plain reclaim stall would hit all threads.
2. **JVM-internal OOM / fatal error: unlikely.** `OutOfMemoryError` on a region thread would be logged; `-XX:ErrorFile` default writes `hs_err_pid*.log`
   into the working dir and the doc says none exist. A silent `Runtime.halt` is the one path I could not rule out in code (only `WatchdogThread` halts, and its text was
   never printed).
3. **Panel / host health restart: possible, not supported.** Pterodactyl itself does not kill a hung server. A host-level monitor that kills an unanswering
   server would normally use one fixed timeout; the two runs ended 48 s and 172 s after the stall began. No evidence of an external actor in the logs
   (a stop via the panel would show the clean shutdown that log 42 shows at 16:23:10).
4. **Global-tick deadlock with no kill at all, and the process exits by another route: not needed.** A deadlock alone explains (a) but not (b).

Verdict: (a) is a server-side stall; (b) is most likely the container OOM killer, with a retaining loop in the stalled thread the best single
explanation for both together. Both are hypotheses until the series in section 4 exists.

### 3. Code suspects (read, not run)

| Where | Why it is a suspect | Strength |
| --- | --- | --- |
| `sourbycraft-server/minecraft-patches/features/0002-SourbyCraft-region-tick-thread-count-all-processors-.patch:70` (`tickThreads = AuroraCpu.regionTickThreads()`), `sourbycraft-server/src/main/java/dev/iyanz/aurora/cpu/AuroraCpu.java:94-96` | `threads: -1` resolves to every processor: 8 region threads on 8 CPUs, plus 6 chunk workers + 1 I/O (`[MoonriseCommon]`), 4 Netty, 2 AsyncPath, spark, bridge IO, ~95 platform threads. Region threads do not spin (`AuroraEdfScheduler.java` (run loop, ~520-541) parks), so this does not explain a hang. It does leave nothing for the OS/Netty/GC when all regions are busy; relevant to the unexplained 57-90 % CPU at only 10 clients, not to the stall | CPU: medium; hang: low |
| `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/spark/provider/FoliaWorldInfoProvider.java:77-105` and `FoliaChunkInfo` constructor `:249-259` | `pollChunks()` creates one `CompletableFuture` and queues one **BLOCKING-priority** task per loaded chunk (1250 forceloaded plus each client's chunks, thousands per poll) on the owning region; every 10 s timed-out poll leaves its tasks queued. It is a victim of the stall, but it also amplifies it: BLOCKING outranks normal tasks, and a stuck global queue means futures and tasks accumulate (small per item, not GBs). Also `getEntityCounts` waits up to 5 s per chunk (`:272-274`), so one poll can keep spark's monitoring thread waiting for a long time | amplifier: medium; cause: low |
| `sourbycraft-server/minecraft-patches/features/0057-SourbyCraft-owned-sync-teleport-on-the-owning-region.patch` (`OwnedTeleport.admitted/continueAfterEvents`), 0058 (PlayerSpawnLocationEvent on the global tick), 0062 (player collision team on the global tick), 0035 (code-of-conduct acceptance on the global thread) | work that runs on or hands to the global thread when many players join/teleport from one spawn region. The stall begins during the teleport burst out of a 50-player spawn region, so anything on the global tick keyed on player count or region split is where to look first. Nothing was found that blocks; this is a list of places to read the thread dump against, not findings | low until a dump exists |
| `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/world/chunk/BalancedChunkSystem.java:291` (`while ((first = queue.peekFirst()) != null && (state = first.getPriorityState()) == null);`) | an unbounded empty-body spin in `peekTask`. If a head task keeps a null priority state without leaving the queue (cancelled/executed but not yet unlinked), that chunk worker spins at 100 % and would also explain high CPU. It would not stall the global tick by itself | low-medium for CPU |
| `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/util/Util.java:88-93` (`joinFuture`) | unbounded `parkNanos(100 us)` loop with no timeout. Only referenced by tests today (`rg joinFuture` finds no production caller in `src/main` or the patches), so not a live suspect; flagged because a caller on the global thread would hang exactly like this | none now |
| `sourbycraft-server/minecraft-patches/features/0025-SourbyCraft-disable-region-watchdog-by-default.patch` | removes the one in-product way to get a stall dump. This is why the next run starts blind | process, not cause |

Config facts used: view-distance 10 / simulation-distance 10, `max-players=50` set by the tool, `network-compression-threshold=256`, `max-tick-time=60000`;
bots announce view distance 2, so a chunk-loading storm from 50 players is not indicated (50 sites were already forceloaded and generated during setup).

### 4. Capture plan for the next run (one change set, same workload and seed)

Goal: decide between "container OOM kill" and "something else kills it", and get a stack for the blocked global thread.

**A. Panel series (from the VPS, in `bench_panel.py`/`pterodactyl_panel.py`; no code on the server)**
1. `GET /api/client/servers/<id>/resources` every 2 s from the moment the 50th client connects until the state is `running` again after the restart. Append
   `{wall_utc, current_state, memory_bytes, cpu_absolute, uptime, network_rx_bytes, network_tx_bytes}` as JSONL (`resources.jsonl`). Read the memory limit once from
   `GET /api/client/servers/<id>` (`attributes.limits.memory`, `swap`, `cpu`) and store it in `bench.json` next to `heap_maximum`. (The tool today only polls `/resources` for `current_state`, `pterodactyl_panel.py:69`.)
2. A WebSocket listener on `/api/client/servers/<id>/websocket` writing every `console output`, `daemon message`, `status` and `stats` event with a timestamp to
   `console.jsonl`. `stats` pushes about 1 Hz memory/CPU without API polling; `daemon message` is where Wings prints the exit code and "out of memory" text, which the file log
   (`/logs/latest.log`, what the tool reads) never contains. This single file answers OOM vs not.
3. Trigger: on the first `Timed out waiting for world statistics` line, additionally log the panel's server details and `/resources` every 1 s until the process is gone; after the restart, save
   `/logs/latest.log` of the dead process before the next restart rotates it and list the working directory for `hs_err_pid*.log`, `dumps/`.

**B. JVM flags on the panel (Startup tab; `-XX:MaxRAMPercentage=95` shows the flag field is editable; if `-D` is really not accepted, ask the host for the egg variable)**
```
-Xlog:gc*,safepoint*:file=/home/container/dumps/gc.log:time,uptime,level,tags:filecount=3,filesize=50m
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/home/container/dumps/
-XX:+ExitOnOutOfMemoryError
-XX:ErrorFile=/home/container/dumps/hs_err_pid%p.log
-Dsourbycraft.region-watchdog.enabled=true
```
- `gc.log` shows whether the heap is growing during the stall, and the safepoint lines show a long pause if there is one. It survives a SIGKILL (file, flushed per line).
- `ExitOnOutOfMemoryError` plus the heap dump path turns a JVM OOM into a visible exit and a dump; a cgroup kill leaves neither, which is itself the answer.
- The region watchdog flag makes `FoliaWatchdogThread` print the stalled tick's stack (it prints diagnostics only, it does not stop or restart anything).
- Do **not** add `-XX:+CrashOnOutOfMemoryError` (core dump of a 10G process on a 10.5G container).
- First run: keep `MaxRAMPercentage=95` so the failure reproduces unchanged. Second run only if the first shows memory at the cap: `MaxRAMPercentage=75` (about 7.9G heap, 2.6G native headroom). If the stall remains and the kill disappears, memory was the terminator and the runaway is real; if the OOM becomes a Java `OutOfMemoryError`, the dump shows who retains.

**C. Thread dumps, if the host can give a shell on the node (the panel cannot)**
```
CID=<server uuid>; PID=$(docker exec $CID pgrep -f 'server.jar|SourbyCraft' | head -1)
while true; do T=$(date +%s); docker exec $CID jcmd $PID Thread.print -l > td-$T.txt 2>&1; docker exec $CID jcmd $PID GC.heap_info > hi-$T.txt 2>&1; sleep 10; done
```
Start it when the WebSocket listener sees the first spark timeout (or simply at the 50th client). Also take `docker exec $CID cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.events` every 10 s (`oom_kill` count in `memory.events` is the definitive OOM-kill record) and `docker inspect -f '{{.State.OOMKilled}} {{.State.ExitCode}}' $CID` right after the death.
`-XX:NativeMemoryTracking=summary` is only useful with this shell; add it with it (`jcmd $PID VM.native_memory summary`), otherwise skip.

**D. If there is no node shell: an in-JVM stall recorder (code change, not done here).** A daemon thread, not tied to any tick, that watches a global-tick heartbeat
(`RegionizedServer` global tick counter) and, once it is stale for 10 s, writes every 10 s to `dumps/stall-<epoch>.txt`: `ThreadMXBean.dumpAllThreads(true, true)` with lock owners,
`findDeadlockedThreads()`, heap used/committed, `Runtime.totalMemory`, `/sys/fs/cgroup/memory.current|max|events`, and the live count of `FoliaChunkInfo` futures. The pieces exist
(`dev.iyanz.aurora.engine.spark.LabelledThreadDumper`, `dev.iyanz.sourbycraft.perf.RuntimeSampler`, `execution/ThreadCpuSource`); the file survives a SIGKILL. Cheapest way to get a stack
without waiting for a shell.

**E. Isolate the trigger (one variable per run, 30 min each)**
1. Same run with the teleport phase skipped (all 50 clients stay at spawn): stall = population/global-tick work; no stall = the teleport/region-split path.
2. players-30 and players-40: is there a threshold near 46-48, or does the time-to-stall scale with uptime (both stalls came 32-40 min after boot)?
3. Same run on a boot that has had the spark background profiler disabled (`/spark` off in its config): rules spark's per-chunk BLOCKING polling in or out as the victim vs amplifier.
4. Keep `--sample-seconds` `/perf` calls out of the stall window (they go through the global tick, and 1 per 45 s is useless there); the series in A replaces them.

**Reading the result.** `stats` memory reaches about 10.5G then state goes offline, or `memory.events` shows `oom_kill`: OOM kill; the thread dump taken before it names the runaway.
Memory flat near 3-4G and the process still dies: external actor (host/panel) or a JVM halt; the console `daemon message` names it. Memory flat and no death: pure hang; the dump names the blocked frame.
Until one of these exists no cause should be written in release notes or `docs/TODO.md`.
