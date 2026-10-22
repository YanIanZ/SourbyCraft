# Aurora Executor Inventory

Roadmap items *"inventory all Sourby-owned executors"* and *"audit implicit common-pool usage"*
(`docs/AURORA-TASKS.md`). Measured over `sourbycraft-server/src/main/java` on 2026-09-20.

This exists because "the multithreading needs an overhaul" is a reasonable thing to suspect and
a bad thing to act on unchecked. The historical service inventory below predates the current engine integration and does not count every thread in the running server.

## 1. Every Sourby-owned executor

| Owner | Threads | Bound | Runs |
|---|---|---|---|
| `perf/PerformanceCollector` | 1 platform, `SourbyCraft-PerformanceCollector` | one thread, one loop | Samples telemetry once a second |
| `perf/GcTracker` | 1 platform | one thread | GC notification listener |
| `util/VirtualExecutor` → `BoundedIoExecutor` | virtual, `SourbyCraft-IO-` | **Semaphore, ≤64 admitted, no queue** | Administrative blocking I/O only — never world data |
| `perf/AsyncPathProcessor` → `execution/ResourceGovernor` `PATHFINDING` | `max(1, cores / 4)` platform, `SourbyCraft-AsyncPath-`, `NORM_PRIORITY - 1` | `ArrayBlockingQueue(1024)`, reject; the navigation then recomputes synchronously (inline solve only before the pool has started) | Off-region A*, **disabled by default** |
| `startup/PluginStartupIndex` | ≤ 4 platform, `SourbyCraft-Startup-`, only during the boot stage | `ArrayBlockingQueue(jar count)`, AbortPolicy | Jar hashing, descriptor parsing, bytecode scan |
| `execution/ResourceGovernor` `BRIDGE_IO` | max(2, cores/4) platform, `SourbyCraft-BridgeIO-`, idle threads released | `ArrayBlockingQueue(256)`, reject | Async tasks of bridged legacy plugins (bridge `off` by default) |
| `execution/ResourceGovernor` `STORAGE` | 1 platform, `SourbyCraft-Storage-` | `ArrayBlockingQueue(64)`, reject | AWF commits; unused until AWF is wired |

Two shutdown hooks exist (`bootstrap/SourbyBootstrap`, `update/UpdateApplier`); neither is a
running thread.

The historical two-thread statement referred to service samplers, not the running server's
region pool or every engine/library worker. The current EDF selection constructs LeafPile's
`EDFSchedulerThreadPool` (baseline `SchedulerUtil.java.patch`). The parked EDF patch
(`minecraft-patches/parked/0028-…`, held since 2026-10-07, not built) would wire it to the
LeafPile-derived `engine/threadedregions/scheduler/AuroraEdfScheduler` instead. That backend's thread count is the existing boot-computed `initialThreads`;
it replaces the original EDF pool and adds no second executor or CPU capacity. A runner has
one assigned task; remaining handles queue in the original uncapped priority queue.
See [EDF scope/acceptance](../development/AURORA-EDF-UPGRADE.md).

## 2. Implicit common-pool usage: none in Sourby code, three spark completion callbacks

`CompletableFuture`'s `*Async` methods without an explicit executor run on
`ForkJoinPool.commonPool()` — uncontrolled parallelism that competes with region ticking and
answers to no configuration.

Re-audited 2026-10-20 over `sourbycraft-server/src/main/java` and the added lines of
`{minecraft,paper}-patches/features` (`rg` for every `CompletableFuture` `*Async` method, `ForkJoinPool`,
`commonPool`, `parallelStream`/`.parallel()`, `Executors.new*`). The baseline patches and the
materialized Minecraft/Paper sources were not audited.

- **SourbyCraft code (`dev.iyanz.sourbycraft.*`, `dev.iyanz.aurora.*`): none.** Every `supplyAsync`/
  `runAsync`/`thenRunAsync` names its executor: `awf/AwfWorld#save` and `awf/GenerationStore#commitAsync`
  the storage lane, `awf/world/AuroraWorldsService` `VirtualExecutor.executor()` or its `ioExecutor`,
  `command/SpeedtestCommand` its own executor, and spark's `FoliaWorldInfoProvider` the owning region's
  task queue (`queueOrExecuteTickTask`).
- **Spark's `SamplerModule` (`me.lucko.spark.paper.common.command.modules`, the spark class carried in
  this tree with the former Canvas region-profiler edits): three common-pool callbacks.**
  `profilerStart` registers `future.whenCompleteAsync(...)` (failure message + `RegionProfiler.endPinning`),
  `sampler.getFuture().whenCompleteAsync(...)` (`unsetActiveSampler`) and, for a timed profile,
  `future.thenAcceptAsync(...)` whose body calls `handleUpload` — a blocking upload to the spark viewer, or a
  file write with `--save-to-file`. They run once per profiler run, never on a region thread, and their
  shape matches upstream spark's own `SamplerModule` (not compared byte for byte here). Not changed: the
  obvious fix is to pass `platform.getPlugin()::executeAsync` (spark's `PaperScheduler` async path) as the
  executor, which needs a decision on how far this copy may diverge from the spark it shadows, and a test
  that drives a sampler to completion.
- Non-`Async` `whenComplete`/`thenAccept` run on the completing thread, not the common pool.
- `ForkJoinPool` appears only in an `ExecutionLane.BACKGROUND` comment: upstream's
  `Util.backgroundExecutor()` (`Worker-Main-*`) is its own ForkJoinPool, attributed to its own lane.
- `Executors.new*` in Sourby code: `util/BoundedIoExecutor` (virtual thread per task, semaphore-bounded) and
  two daemon single-thread schedulers in the AWF Redis backend, `SourbyCraft-Storage-redis-watchdog`
  (`awf/redis/RedisClient`, one for all clients) and `SourbyCraft-Storage-redis-lease` (`awf/redis/RedisBackend`, one per
  backend), which the 2026-09-20 table in section 1 predates.

## 3. What this means for CPU efficiency

Aurora's own threading is small, bounded, named and attributable. There is no pool here that
grows without limit, no hidden common-pool work, and nothing that would be improved by being
rewritten. So when CPU sits idle under load, the cause is not in this table.

The three things that actually bound CPU use are documented in
[region-parallelism.md](region-parallelism.md):

1. **Region tick threads** — was `cores / 2`, now every processor, configurable through
   `aurora.cpu.cores`. Measured: 32% better mean MSPT, 62% better tail.
2. **Region count** — a region is a connected component of loaded chunks and ticks on exactly
   one thread, so parallelism is bounded by how many *disconnected* areas carry load. No thread
   count raises this, and no rewrite of the table above changes it.
3. **Heap configuration** — outside the server entirely. A container-relative maximum that
   leaves no room for metaspace, code cache, thread stacks and direct buffers produces stalls
   that look exactly like a threading problem.

The one lever Aurora owns that moves work *off* region threads is async pathfinding, which is
implemented, instrumented, and **default-off pending T10 qualification**. Enabling it without a
certified baseline is the thing §4.5 forbids, and the `inline` counter in `/perf async` exists
precisely because an undersized pool makes it cost more than it saves.
