# Aurora Executor Inventory

> **Role:** the current inventory of every SourbyCraft-owned executor, thread and shutdown hook.
> **Status:** Active. Re-measured 2026-10-25 on the PR #62 tree (`build50/pm-completion`) by a
> static `rg` over `sourbycraft-server/src/main/java/dev/iyanz` (`sourbycraft` and `aurora`) for
> `Executors.new*`, `new ThreadPoolExecutor`, `new ScheduledThreadPoolExecutor`, `new Thread(`,
> `Thread.ofVirtual`, `NioEventLoopGroup`, `CompletableFuture.delayedExecutor` and
> `addShutdownHook`. Nothing was built or run. The first version (2026-09-20) listed seven
> executors; [executors.md](executors.md) is the earlier audit and is kept as history.
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

Roadmap items *"inventory all Sourby-owned executors"* and *"audit implicit common-pool usage"*.

This exists because "the multithreading needs an overhaul" is a reasonable thing to suspect and
a bad thing to act on unchecked. It covers SourbyCraft's own threads only: region tick threads,
chunk workers, chunk I/O, Netty and the region scheduler's async pool are sized upstream and
listed, as configured ceilings, by the thread ledger (`execution/ThreadLedger`, startup log and
`/perf governor`).

## 1. Every Sourby-owned executor, thread and hook

Paths are relative to `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/` unless they start
with `aurora/`. "Daemon" threads do not hold the JVM open. `ExecutionLane` is the `/perf lanes`
attribution of the thread name.

| Owner | Threads (name) | Bound | Runs | Started / stopped by |
|---|---|---|---|---|
| `perf/PerformanceCollector` | 1 platform daemon, `SourbyCraft-PerformanceCollector` (TELEMETRY) | one loop | Telemetry snapshot and incident detection about once a second | `MetricsRuntime.start` (stage "metrics runtime") / `MetricsRuntime.close` in `SourbyCraftBootstrap#close()` |
| `awf/AwfMaintenanceDriver` | 1 platform daemon, `SourbyCraft-AWF-Maintenance` (WORLD_IO) | one thread, fixed delay 1 s | Starts due AWF commits (they run on the `STORAGE` lane) and watches world operations | `MetricsRuntime` start / close |
| `perf/GcTracker` | 1 platform daemon, `SourbyCraft-GcTracker` (OTHER) | one thread | GC MXBean sampling | stage "gc tracker" / `GcTracker.stop()` in `close()` |
| `util/VirtualExecutor` → `util/BoundedIoExecutor` | virtual, `SourbyCraft-IO-N` (WORLD_IO) | **semaphore, ≤ 64 admitted, no queue**; a 65th task is rejected | Administrative blocking I/O, never world chunk data: `/speedtest`, `/update`, `/ping` GeoIP, `/spec`, `/sys`, AuroraWorlds registry writes and world operations, `/awf redis` diagnostics (PR #62), `/sourbycraft reload` (PR #62), `/maxp` persistence from tick threads (PR #62), AbyssLink DNS/TLS preparation, the MultiProfile file-to-Redis repository migration | stage "virtual executor" / `VirtualExecutor.shutdown()` in `close()`, waits up to 30 s |
| `execution/ResourceGovernor` `BRIDGE_IO` | `max(2, cores/4)` platform, `SourbyCraft-BridgeIO-` (PLUGIN_ASYNC), idle threads released after 30 s | `ArrayBlockingQueue(256)`, reject | Async tasks of bridged legacy plugins (bridge `OFF` by default) | created on first use / `ResourceGovernor.GLOBAL.shutdown(10 s)`, last step of `close()` |
| `execution/ResourceGovernor` `STORAGE` | 1 platform, `SourbyCraft-Storage-` (WORLD_IO) | `ArrayBlockingQueue(64)`, reject | AWF commits; AWF MultiProfile repository/ownership steps, one at a time through `ProfileIoQueue` (PR #62) | same as above |
| `perf/AsyncPathProcessor` → `ResourceGovernor` `PATHFINDING` | `max(1, cores/4)` platform, `SourbyCraft-AsyncPath-` (ASYNC_COMPUTE), `NORM_PRIORITY - 1` | `ArrayBlockingQueue(1024)`, reject; a declined or refused offload runs the vanilla synchronous recompute on the mob's region thread (inline solve only before the pool has started) | Off-region A*, **disabled by default** | `AsyncPathProcessor.ensureStarted` when `aurora.entity.async-pathfinding` turns on / `AsyncPathProcessor.shutdown()` in `close()` (queued solves cancelled) |
| `startup/PluginStartupIndex` | `min(4, max(1, processors/2))` platform, at most one per jar, `SourbyCraft-Startup-` (STARTUP) | `ArrayBlockingQueue(jar count)`, AbortPolicy | Jar hashing, descriptor parsing, bytecode scan | inside stage "startup index" / `shutdownNow()` in the same method |
| `world/WorldMetadataAutosave` | 1 platform daemon, `SourbyCraft level.dat writer` (OTHER), created with the class | queue 1, AbortPolicy; a write is skipped, never queued, while one is outstanding | The periodic autosave's `level.dat` write (feature patch 0077) | never shut down (daemon); the shutdown save waits for an outstanding write (`awaitLevelDatWrites`, patch 0077) before writing `level.dat` itself |
| `diagnostics/StallRecorder` | 1 platform daemon, `SourbyCraft-StallRecorder` (OTHER), plus the JVM shutdown hook `SourbyCraft-StallRecorder-Shutdown` | one thread; off unless `aurora.diagnostics.stall-recorder.enabled` | Watches for a hung tick and writes stall dumps | stage "stall recorder" / `StallRecorderRuntime.stop()` in `close()`, which removes the hook unless a stall is still open |
| `diagnostics/BoundedProbe` | 1 platform daemon, `SourbyCraft-StallRecorder-Probe` (OTHER) | queue 1; a probe is only offered when the previous one finished | Reads live server state for a stall dump with a deadline | the stall recorder / closed at `StallRecorderRuntime.stop()`, or after the FINAL dump when a stall is open (PR #62); a closed probe returns its fallback |
| `awf/AwfShutdownBudget` | 1 platform daemon, `SourbyCraft-Storage-awf-shutdown-deadline` (WORLD_IO), static | one timer | Fires the AWF shutdown deadline's expiry actions once | created with the class / never shut down (daemon) |
| `awf/redis/RedisBackend` | 1 platform daemon per backend, `SourbyCraft-Storage-redis-lease` (WORLD_IO) | one scheduled task | Lease renewal of the AWF Redis backend | backend construction / `shutdownNow()` when the backend shuts down or closes |
| `awf/redis/RedisClient` | 1 platform daemon, `SourbyCraft-Storage-redis-watchdog` (WORLD_IO), static, shared by all clients | one thread | Closes connections whose writes stall | created with the class / never shut down (daemon) |
| `awf/multiprofile/ProfileRuntime` | 1 platform daemon, `SourbyCraft-AWF-profile-idle` (OTHER) | every 30 s | Idle profile-world sweep; only with MultiProfile on and `idle-unload-seconds > 0` | stage "awf multiprofile" / `ProfileRuntime.stop()` in `close()` |
| `awf/multiprofile/ProfileRuntime` | 1 platform daemon, `SourbyCraft-AWF-profile-heartbeat` (OTHER) | one thread | Session-claim renewal and, with fabric routing, node advertising; only with MultiProfile on | same as above |
| `abysslink/AbyssLinkConnectionManager` | Netty `NioEventLoopGroup(1)`, daemon `Aurora-AbyssLink` (OTHER) | one event loop; only with AbyssLink on | AbyssLink control-plane connection | stage "abysslink" / `AbyssLinkRuntime.close()` in `close()` (`shutdownGracefully`, 2 s) |
| `command/SaveAllFeedback` | one virtual thread per `save-all` with AWF storages open, `SourbyCraft-Storage-awf-save-all` (WORLD_IO) | waits at most `AwfFlushReporting.IO_WAIT_NANOS` (30 s) | Waits for the region saves and chunk I/O to settle, then reports the AWF flush | ends by itself |
| `aurora/engine/util/ticket/SaveAllTicket` | one virtual thread per completed save-all ticket, `Aurora save ticket completion` (OTHER) | one per ticket | Completes the save-all future off the tick thread | ends by itself |
| `core/SourbyCraftBootstrap` slow-stage warning | the JDK's shared `CompletableFuture.delayedExecutor` delayer thread (JDK-owned daemon) | one delayed check per boot stage, run in place (`Runnable::run`), not on the common pool | The "stage still running after 10 s" WARN | JDK-managed |

Not running threads: `execution/FairLane` exists but is not wired (no lane uses it);
`aurora/engine/threadedregions/scheduler/AuroraEdfScheduler` is unselected while the EDF patch is
parked. Owned services that only use the server's published schedulers and own no threads:
`hud/HudBars` (global region and player schedulers), `update/SourbyUpdater` and
`update/UpdateApplier` (Folia async scheduler), `bridge/AuroraBridge` (async scheduler for timing,
`BRIDGE_IO` for the body), `command/SourbyReply` (entity/region schedulers), AbyssLink telemetry
(global region scheduler).

**Shutdown hooks.** One: the stall recorder's. The hooks the 2026-09-20 version listed
(`bootstrap/SourbyBootstrap`, `update/UpdateApplier`) no longer exist in source.

**What is outside the Resource Governor.** Every row above except the three governed lanes. Each
has its own bound; none grows without limit. Moving any of them under the governor is a separate,
owner-approved change (`docs/TODO.md`; [aurora-resource-governor.md](aurora-resource-governor.md)
"Not in scope yet").

The historical two-thread statement referred to service samplers, not the running server's
region pool or every engine/library worker. The current EDF selection constructs LeafPile's
`EDFSchedulerThreadPool` (baseline `SchedulerUtil.java.patch`). The parked EDF patch
(`minecraft-patches/parked/0028-…`, held since 2026-10-07, not built) would wire it to the
LeafPile-derived `engine/threadedregions/scheduler/AuroraEdfScheduler` instead. That backend's thread count is the existing boot-computed `initialThreads`;
it replaces the original EDF pool and adds no second executor or CPU capacity. A runner has
one assigned task; remaining handles queue in the original uncapped priority queue.
See [EDF scope/acceptance](../development/AURORA-EDF-UPGRADE.md).

## 2. Implicit common-pool usage: none in Sourby code or the in-tree spark copy

`CompletableFuture`'s `*Async` methods without an explicit executor run on
`ForkJoinPool.commonPool()` — uncontrolled parallelism that competes with region ticking and
answers to no configuration.

Re-audited 2026-10-20 over `sourbycraft-server/src/main/java` and the added lines of
`{minecraft,paper}-patches/features` (`rg` for every `CompletableFuture` `*Async` method, `ForkJoinPool`,
`commonPool`, `parallelStream`/`.parallel()`, `Executors.new*`); the spark finding was closed in
PR #62 (2026-10-25, see below). The baseline patches and the materialized Minecraft/Paper sources
were not audited.

- **SourbyCraft code (`dev.iyanz.sourbycraft.*`, `dev.iyanz.aurora.*`): none.** Every `supplyAsync`/
  `runAsync`/`thenRunAsync` names its executor: `awf/AwfWorld#save` and `awf/GenerationStore#commitAsync`
  the storage lane, `awf/world/AuroraWorldsService` `VirtualExecutor.executor()` or its `ioExecutor`,
  `command/SpeedtestCommand` its injected executor (`VirtualExecutor::run` in production), the
  `level.dat` writer its own thread, and spark's `FoliaWorldInfoProvider` the owning region's
  task queue (`queueOrExecuteTickTask`).
- **Spark's `SamplerModule` (`me.lucko.spark.paper.common.command.modules`, the spark class carried in
  this tree with the former Canvas region-profiler edits): fixed in PR #62.** The three
  continuations that used the common pool (`future.whenCompleteAsync(...)` for the failure message and
  `RegionProfiler.endPinning`, `sampler.getFuture().whenCompleteAsync(...)` for
  `unsetActiveSampler`, and the timed profile's `future.thenAcceptAsync(...)` that calls the blocking
  `handleUpload`) now pass `platform.getPlugin()::executeAsync`, spark's own async executor; each
  divergence from upstream spark is marked `// SourbyCraft`. `scripts/concurrency_policy.py` now
  scans `sourbycraft-server/src/main/java/me/lucko/spark` too. Code written and statically reviewed;
  not built; not tested by the owner (no test drives a sampler to completion).
- Non-`Async` `whenComplete`/`thenAccept` run on the completing thread, not the common pool.
- `ForkJoinPool` appears only in an `ExecutionLane.BACKGROUND` comment: upstream's
  `Util.backgroundExecutor()` (`Worker-Main-*`) is its own ForkJoinPool, attributed to its own lane.

## 3. Do region threads block on external I/O?

Task-matrix section E asks to "validate no region thread blocks on external I/O". This inventory
does not answer it by itself; the answer lives in two documents:

- [region-io-audit.md](region-io-audit.md) — the source audit: every network and file I/O site
  in SourbyCraft's code with the thread it runs on (the network list is pinned by
  `BlockingIoBoundaryTest`), the Redis chunk-read trace (R1–R4) and the Netty event-loop audit.
- [region-io-blocking-2026-10-22.md](../development/region-io-blocking-2026-10-22.md) — a dated
  functional run under plugins and a disk stall (agent test server, no player; it qualifies
  nothing).

Their result: **not established, and known not to hold in every path.** Structure scans and
synchronous non-full chunk loads on a region thread (`/locate`, dolphins, explorer maps; R2 and
the 2026-10-22 F1/F4) read region files, or the AWF backend, on that thread: an open defect. The
periodic `level.dat` write on the global tick (F2) was fixed by feature patch 0077; `save-all`
stays synchronous by design (F3); player-data saves on the owning region (F5) are open, static
only.

Sites checked again for this inventory on 2026-10-25 (static `rg` for `Future#get`/`join`,
`Thread.sleep`, `parkNanos` and file writes in `dev.iyanz.sourbycraft`; nothing was run), as
deltas to those documents:

| Site | Thread | Blocks on | Status |
|---|---|---|---|
| `save-all` (feature patches 0056, 0067, 0077): `WorldMetadataAutosave#saveLevelDat`, `saveContained`, `WorldStateAutosave#saveItems` with `sync` | global tick | Waits (unbounded `join`) for an outstanding scheduled `level.dat` write, writes `level.dat`, and joins the data-storage writes | By design (F3): an explicit synchronous save. A stalled disk stalls `save-all` without a bound. |
| AWF chunk read (`awf/AwfRegionStorage#readStored`) on a region thread (R2 path) | region tick | One backend read: a disk read (FILE) or a Redis call bounded by `aurora.awf.redis.timeout-ms` (default 5000 ms) | Partly mitigated: one attempt on a region thread, no sleep-and-retry. Only for worlds that use AWF (default none). |
| `AuroraWorlds#setAutoload` (synchronous API; console `/awf autoload`) | the caller's thread, the global tick for the console | Registry write forced to disk (file and directory) | Open: needs an API change (owner decision; PR #62 WP02 note). Other AuroraWorlds registry writes run on `VirtualExecutor`. |
| `/sourbycraft reload` and `/maxp` persistence | ran on the dispatching tick thread | File reads and writes | **Moved to `VirtualExecutor` in PR #62** (`/maxp` only when called from a tick thread; RCON and tests still write inline). (`/awf redis` diagnostics, which ran on the `STORAGE` lane, moved there too.) Code written and statically reviewed; not built; not tested by the owner. |
| `entity/teleport/OwnedTeleport#joinAsync` (relaxed thread checks, the default) | a tick thread teleporting an entity it does not own | Waits up to 30 s for the owner to finish the teleport | Not external I/O but a blocking cross-region wait; `false` and a WARN on timeout. Part of the unqualified relaxed-checks behaviour (owner decision). |
| `guards/OffThreadChunkLoad#await` | only a thread that ticks nothing (`applies()`) | Waits up to 30 s for a chunk load | Not a region thread by construction. |

Not traced in this pass: the waits in `awf/multiprofile/ProfileEngine` (its I/O runs on the
`STORAGE` lane by design).

## 4. What this means for CPU efficiency

Aurora's own threading is small, bounded, named and attributable. There is no pool here that
grows without limit and no hidden common-pool work. So when CPU sits idle under load, the cause
is unlikely to be in this table; nothing here is measured, so that is a reading of the code, not
a measurement.

The three things that actually bound CPU use are documented in
[region-parallelism.md](region-parallelism.md):

1. **Region tick threads** — the upstream default resolved `threads: -1` to total cores / 2, then
   1 when that is ≤ 4, otherwise a further / 4 (one thread on an 8-core host); feature patch 0002
   resolves it to every JVM-visible processor, capped by `aurora.cpu.cores`. One uncertified
   8-core block-tick A/B (8 threads against 4, 2026-09-20) measured 32% lower mean MSPT and 62%
   lower maximum MSPT; it is one host and one workload, not a universal recommendation
   (region-parallelism.md §1 and §5).
2. **Region count** — a region is a connected component of loaded chunks and ticks on exactly
   one thread, so parallelism is bounded by how many *disconnected* areas carry load. No thread
   count raises this, and no rewrite of the table above changes it.
3. **Heap configuration** — outside the server entirely. A container-relative maximum that
   leaves no room for metaspace, code cache, thread stacks and direct buffers produces stalls
   that look exactly like a threading problem.

The one lever Aurora owns that moves work *off* region threads is async pathfinding, which is
implemented, instrumented, and **default-off pending T10 qualification**. Recommending it
without a certified baseline is what docs/DEVELOPMENT.md §2.4 and the truthfulness policy §3
forbid. When its pool is too small for the arrival rate, `/perf async` shows rising `declined`
(and, rarely, `refused`) counts: each of those slots ran the vanilla synchronous recompute on the
region thread. A declined offload cost only the capacity check before it; a refused one had also
paid for the block snapshot and fired the pathfind event. The legacy `inline` counter should stay
zero on this build.
