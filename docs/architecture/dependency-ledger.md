# Aurora Dependency Ledger

T9 deliverable of the former transition plan (`AURORA-FULL-TRANSITION.md` §15; that plan is no
longer in the repository, and the § numbers below that cite it are historical) and the last open
item of its **M1 — Runtime & Execution Ownership** (§24). Today it is roadmap milestone M19
([AURORA-ROADMAP.md](../AURORA-ROADMAP.md)), done for runtime source. Counts re-checked against
`UpstreamDependencyLedgerTest` and the patch directories on 2026-10-25 (PR #62 docs pass).

Every direct dependency on Canvas, Folia, Paper internals or NMS is listed here with a
classification and a reason. The point is not to count upstream lines. It is §25's principle:
*upstream can change, Aurora contracts remain*. A dependency with no stated reason is a
dependency nobody has decided to keep.

This ledger is enforced by `UpstreamDependencyLedgerTest`. A file that reaches for upstream
internals without being listed here fails that test. Editing the ledger is therefore a
deliberate act, not a side effect.

## Classifications

| Class | Meaning |
|---|---|
| `REQUIRED_UPSTREAM_CONTRACT` | Aurora depends on it because the product depends on it. Removing it removes a feature, not a dependency. |
| `TEMPORARY_IMPLEMENTATION` | Aurora owns the contract; upstream still supplies the implementation behind it. Replaceable without redesign. |
| `COMPATIBILITY_ONLY` | Kept so existing deployments keep working. No Aurora behaviour depends on it. |
| `DIRECT_NMS_PATCH` | A patch against the materialised Minecraft source. |
| `REMOVABLE` | Nothing depends on it; it can go once someone decides to. |
| `LEGACY` | Superseded, still present. |

---

## 1. Runtime source dependencies

Measured over `sourbycraft-server/src/main/java` (Aurora/SourbyCraft's own source, not patches).

### 1.1 `REQUIRED_UPSTREAM_CONTRACT`

| Dependency | Used by | Reason |
|---|---|---|
| `org.bukkit.*` (commands, events, plugins, `Bukkit`) | ~30 files | The plugin surface *is* the product. A server that cannot run Bukkit plugins is a different product, not a more independent one. |
| `io.papermc.paper.plugin.lifecycle.*` | command registration | Paper's public plugin lifecycle API; the supported way to register commands. |
| `io.papermc.paper.threadedregions.scheduler.EntityScheduler` | `command/SourbyReply.java` | **Public** Folia-style scheduler API (note the `.scheduler.` segment), the documented way to run work on an entity's owning thread. |
| `io.papermc.paper.threadedregions.scheduler.ScheduledTask` | `hud/HudBars.java` | Same public API; the handle type its own scheduling returns. |
| `net.minecraft.server.Main` | bootstrap entry | The process entry point the launcher invokes. |
| `io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler`, `AsyncScheduler` | `bridge/AuroraBridge.java` | Public scheduler API; the Aurora Bridge times bridged legacy tasks with them. |
| `org.bukkit.event.server.ServerLoadEvent` | `startup/StartupTimeline.java` | Public event marking "server ready" for the startup profile. |

### 1.2 `TEMPORARY_IMPLEMENTATION`

Together with `CanvasConfigBridge` (§1.3), these thirteen are the only files permitted to touch
upstream **internals**. Aurora defines the contract; these supply today's implementation of it. This
is the set the ledger test pins (`LEDGERED`, 14 files).

| File | Upstream internals | Contract it implements |
|---|---|---|
| `execution/region/FoliaRegionBackend.java` | `io.papermc.paper.threadedregions.RegionizedServer`, `TickRegionScheduler` (incl. `getCurrentRegion()` / `ThreadedRegion.getCenterChunk()`); `ca.spottedleaf.moonrise.common.util.MoonriseCommon.WORKER_POOL` / `IO_POOL` (`BalancedPrioritisedThreadPool.getCoreThreads()`) | `execution/region/RegionBackend` — region topology and tick scheduling; `execution/region/CurrentRegion` — the region the calling thread ticks, for the Aurora Bridge's caller-region route.; `execution/region/RegionOwnership` (global-tick part, via `MoonriseRegionOwnership`) — `RegionizedServer.ensureGlobalTickThread` and `scheduleToOrExecute`; `execution/region/RegionPopulation` — per-world chunk/entity/player counts summed from each region's `RegionStats` (`/perf chunks`, `/perf entities`); `execution/region/EnginePools` — the chunk worker and I/O pools' configured thread counts for the startup thread ledger and `/perf governor` (audit ENG-9, report only); `execution/region/RegionTickRate` — the tick rate every region aims for. Swapping the backend does not touch callers. |
| `execution/region/MoonriseRegionOwnership.java` | `ca.spottedleaf.moonrise.common.util.TickThread`, `ca.spottedleaf.concurrentutil.util.Priority`; `ServerLevel.moonrise$loadChunksAsync` | `execution/region/RegionOwnership` — calling-thread ownership queries (incl. `isTickThread`, which `guards/LegacyChat` uses to decide whether a caller may wait), entity ownership enforcement, and the global-tick requirement/handoff (`ensureGlobalTickThread`, `executeOnGlobalTick`: inline on the global tick thread, else queued for it; used by `world/ScheduledFunctionTicker`; delegated to `FoliaRegionBackend`, which names `RegionizedServer`); `execution/region/RegionChunkLoading` — preserves the existing asynchronous chunk-load continuation outside a command-block scope. The patch-supplied priority is an opaque pass-through token above the adapter; only the adapter casts it to the backend type. This is chunk loading, not an entity `OwnerHandoff` (no entity identity, admission or retirement delivery). |
| `execution/RegionOwnerHandoff.java` | `io.papermc.paper.threadedregions.EntityScheduler` (internal, no `.scheduler.`) | `execution/OwnerHandoff` — moving work to the thread that owns an entity or region. |
| `perf/RegionTickMetrics.java` | `ca.spottedleaf.common.time.TickData`, `TickTime` | Aurora's region tick telemetry, reading upstream's tick accounting rather than duplicating it. |
| `perf/RegionTickMetricsHolder.java` | `ca.spottedleaf.common.time.TickData`, `TickTime` | Generation ownership for the above; `view(interval)` builds the handle's `TickData` windows (MC feature patch 0013); the lifecycle calls of MC feature patch 0079 (region active/inactive/destroy/merge/split on the handle's holder, world id and retire statics) take plain ids, so it names no region-backend type. |
| `perf/RegionTickDataView.java` | `ca.spottedleaf.common.time.TickData` (subclass), `TickTime` | The region schedule handle's five `tickTimesN` windows (MC feature patch 0013): upstream `TickData` type, answers from the shared `RegionTickMetrics` owner. |
| `perf/RegionIoQueue.java` | `ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO` (`getControllerFor`, `getTotalWorkingTasks`) | `perf/StorageBacklog` — `/perf storage` backlog: reads Moonrise's per-world I/O task counters instead of keeping a second count. |
| `awf/RegionFileStorageHooks.java` | Moonrise `RegionDataController.ReadData` / `WriteData` / `WriteResult`, NMS `RegionFileStorage`, `ChunkPos`, `CompoundTag`, `SharedConstants`, `StreamTagVisitor`, `NbtIo`, `NbtAccounter` | MC features 0021/0053 adapter: AWF attachment, storage routing, codec handoff, structure scan parsing through `scanRead`, close logging and traffic counters. Keeps upstream fallback and lock boundaries in `RegionFileStorage`; does not add an engine dependency. |
| `awf/world/AuroraWorldsService.java` | `io.papermc.paper.threadedregions.RegionizedServer` (global tick task queue), `dev.iyanz.aurora.engine.util.ticket.SaveAllTicket` propagated through each region's `canvas$saveAllTicket` (per-region save ticket), `ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush` (drains a world's chunk I/O queue before AWF confirms a save), `dev.iyanz.sourbycraft.api.world.WorldUnloadResult` (the engine's unload outcome) and `dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent` (the point after which an unload is certain) — SourbyCraft's own plugin API since Build 48, listed because they carry the engine's unload semantics | `api/world/AuroraWorlds` — runtime AWF world lifecycle. World creation and unloading must run on the global tick, and a durable save has to wait for every region of the world to save before the AWF stores commit; the engine exposes neither as public API. The engine's unload outcome is mapped to the API's own `UnloadResult`, so `AuroraWorlds` callers see one result type. The engine ignores its unload save flag, so an unload without saving makes the world's AWF storages discard once the unload event has passed. |
| `perf/LiveSchedulerSource.java` | `io.papermc.paper.threadedregions.TickRegions` (`hasStarted`, `getScheduler`, `getRegionChunkShift`), `TickRegionScheduler` (`scheduler`, `getTotalThreadCount`, `getTickRate`, `TickThreadRunner.currentTickingTask`, `RegionScheduleHandle` scheduled start / `hasTasks` / non-schedulable flag / `sourbyTickMetrics`; the late-tick-start `LongAdder` reads `sourbyRegionTicksStarted` / `sourbyLateRegionTicks` / `sourbyGlobalTicksStarted` / `sourbyLateGlobalTicks` added by MC feature patch 0045), `RegionizedTaskQueue.RegionTaskQueueData` (`sourbyQueuedTickTasks` / `sourbyQueuedChunkTasks`, volatile per-queue entry counts added by patch 0045) via `TickRegions.TickRegionData.getTaskQueueData()`, `RegionizedServer.getGlobalTickData`, `ThreadedRegionizer` (`computeForAllRegionsUnsynchronised`, `ThreadedRegion.getState()`); `ca.spottedleaf.concurrentutil.scheduler.{Scheduler, SchedulableTick}`; **engine/Canvas:** `dev.iyanz.aurora.engine.threadedregions.SchedulerUtil` (`getSchedulerInfo()`: scheduler kind and configured threads), `dev.iyanz.sourbycraft.api.region.WorldRegionizer.ChunkRegion.State` (READY vs TRANSIENT; SourbyCraft plugin API since Build 48) | `perf/SchedulerQueueCollector.Source` — `/perf scheduler`: scheduler kind/threads, runner activity, per-world region counts by state, lateness, pending and queued tasks, late tick starts since boot, read lock-free (racy reads labelled approximate; what needs a lock is shown as n/a). The third declared Canvas/engine doorway (§5). Report only, writes nothing. |
| `perf/RegionLocator.java` | `io.papermc.paper.threadedregions.ThreadedRegionizer` (`getRegionAtUnsynchronised`, `ThreadedRegion.getData()`), `TickRegions.TickRegionData.id` | `RegionLocator.Topology` / `RegionLocator.WorldHandle` (nested `LiveTopology`, `LiveWorld`) — `/perf region <world> <x> <z>`: the world (matched case-insensitively, like Bukkit) and the region id owning a chunk, read from the single-writer/multi-reader region table without the write lock (one re-read when a merge/split has the owner unpublished). Telemetry comes from `RegionMetricsRegistry.forEachUnexpiredOf`, which reads published snapshots without refreshing any generation. |
| `compat/luminol/RegionizedWorldDataPool.java`, `compat/luminol/WorldDataAccess.java` | `io.papermc.paper.threadedregions.RegionizedWorldData` (the region's data handed out), `ThreadedRegionizer` (`getRegionAtUnsynchronised` / `getRegionAtSynchronised`, `sectionChunkShift`), `TickRegions.TickRegionData.getRegionizedData`, `TickRegionScheduler.getCurrentRegionizedWorldData`, Moonrise `ChunkHolderManager` tickets (own `POOL_TICKET`) | LightingLuminol's `ReferenceCountedRegionizedWorldDataPool` (GPL-3.0, revision 72d51c4d), ported 2026-10-22 for the owner-decided relaxed thread checks: hands a foreign thread the `RegionizedWorldData` of the region owning a chunk while holding a ticket, so the MC 0085 / Paper 0029 `WorldDataAccess` leases route an off-thread write to the right region. The fourth region-backend adapter (§5); dead code while `aurora.bridge.thread-checks` is enforced (`ThreadChecks.RELAXED_SCOPE` is a `static final`). Unsafe by design: the ticket prevents an unload, not a merge or split ([aurora-plugin-bridge.md, Thread checks](aurora-plugin-bridge.md#thread-checks-owner-decision-2026-10-22)). |

### 1.3 `COMPATIBILITY_ONLY`

| File | Dependency | Reason |
|---|---|---|
| `config/upstream/CanvasConfigBridge.java` | `dev.iyanz.aurora.engine.GlobalConfiguration`, `WorldConfig` (formerly `io.canvasmc.canvas`) | Reloads the engine config (`config/aurora-server.yml` / `aurora-worlds.yml`; the pre-49 `canvas-*.yml` are migrated once by `EngineConfigFiles`) on `/sourbycraft reload`. No Aurora behaviour reads it; it sits behind `config/upstream/UpstreamConfigBridge`. This is the **only** engine-config reference in Aurora's own source. The other engine doorways are `awf/world/AuroraWorldsService.java` (§1.2), for world lifecycle, and `perf/LiveSchedulerSource.java` (§1.2), for read-only `/perf scheduler` diagnostics. |
| `perf/NetworkMetrics.java` | `io.papermc.paper.network.ChannelInitializeListenerHolder`, Netty `ChannelPipeline` | The only hook for adding a handler to every connection's pipeline. Paper marks it unofficial. If it disappears, the `network counters` boot stage fails in isolation and `/perf network` reports nothing. It is not a threaded-regions internal, so the ledger test does not pin it; it is listed here by hand. |

### 1.4 Parked (`LEGACY` until the owner unparks it)

| File | Dependency | Reason |
|---|---|---|
| `dev/iyanz/aurora/engine/threadedregions/scheduler/AuroraEdfScheduler.java` | LeafPile `Scheduler`, public SourbyCraft `SchedulableTick` fields/methods, `LinkedSortedSet`, `ConcurrentUtil`, `TimeUtil`, `LazyRunnable` | **Parked by owner decision 2026-10-07** (`minecraft-patches/parked/README.md`): source-owned derivative of LeafPile v1.2.0 that is compiled into the jar but **ships unselected** — nothing constructs it, because the patch that would select it for the EDF scheduler type and publish task state through a compatible public volatile field (`minecraft-patches/parked/0028-…`) is held out of the build. Nothing depends on it for compatibility, so it is not `COMPATIBILITY_ONLY`. It sits in the engine package, so `UpstreamDependencyLedgerTest` does not pin it. GPL/credits in `NOTICE_LEAFPILE`; source acceptance pending; not an independent scheduler. No code change while parked. |

---

## 2. Patch dependencies — `DIRECT_NMS_PATCH`

### 2.1 `sourbycraft-server/minecraft-patches/features/` — historical inventory; current additions below

Patches against the materialised Minecraft source. They exist because the behaviour is inside a
vanilla or engine class; individual patches may connect an Aurora-owned backend.
The table below is a historical inventory, not a current patch count. Historical, numbering as of
2026-10-05 or earlier: its numbers predate the 2026-10-06 rebuild (0019–0023 → 0018–0022) and the 2026-10-13
renumbering (old 0024 removed, 0025–0029 → 0024–0028); current names are in
`sourbycraft-server/minecraft-patches/features/` (29 files on 2026-10-14; **87** on 2026-10-25, 0001–0087,
every one of them `DIRECT_NMS_PATCH`; per-patch category and disposition in
[patch-classification.md](patch-classification.md) and [patches.md](patches.md)). The parked EDF patch would add
the EDF backend selection and compatible task-state publication described in §1.4.

| Patch | Keeps |
|---|---|
| 0001 boot hook in `DedicatedServer.initServer` | Aurora's bootstrap entry |
| 0002 sane default region tick thread count | Thread sizing |
| 0003, 0014 spawn-category / spawn-state scan skips | Measured spawn-path optimisations |
| 0004 POI consistency scan without per-block lookups | Measured optimisation |
| 0005 `SnapshotPathRegion` immutable block snapshot | Async pathfinding's safety precondition (`dev.iyanz.aurora.level`) |
| 0006 async pathfinding offload (logic in `perf/AsyncPathRecompute` since 2026-10-22), 0082 no shared path-type cache off a region thread | The async path itself |
| 0007, 0009, 0010, 0011, 0012 allocation/reuse fixes | Measured optimisations |
| 0008 `Projectile` tick ticket typo | Upstream bug fix |
| 0013 custom tick metrics, 0079 region tick metrics lifecycle, 0081 close SourbyCraft services at shutdown | Aurora telemetry and lifecycle hooks (split 2026-10-22) |
| 0080 swapped worst-percentile segments in `TickData` reports | Upstream bug fix |
| 0015 reject a landed arrow before the projectile tag lookup | Upstream bug fix |
| 0016 refuse a cross-region block test | Region-safety fix (PRD §108) |
| 0017 hoist query bounds out of the entity intersection loop, 0019 translate block collision boxes without allocating | Measured optimisations |
| 0020 commit Aurora World Fabric stores at shutdown | One call in `MinecraftServer.stopServer` after the region-file I/O drain; no-op without AWF worlds |
| 0021 do not register the `/canvas` command tree, 0022 count chunk-system storage traffic, 0023 greedy shapeless match only for plain ingredients | Formerly per-file source patches; feature patches since the 2026-10-05 migration so the policy scripts see them (see §2.2) |

Patch 0006 delegates the offload to `perf/AsyncPathRecompute` (since 2026-10-22), which applies the
`perf/AsyncPathValidity` staleness checks (with patch 0047's generation), so the rules are tested in
Sourby's own test suite (`AsyncPathRecomputeTest`, `AsyncPathValidityTest`).

Patches 0021–0023, in detail:

| Patch | Class | Reason |
|---|---|---|
| 0021 `net/minecraft/commands/Commands.java` | `DIRECT_NMS_PATCH` | Removes the `/canvas` command tree. |
| 0022 `net/minecraft/world/level/chunk/storage/RegionFileStorage.java` | `DIRECT_NMS_PATCH` | Aurora World Fabric: reads, writes, scans, flush and close go to `AwfRegionStorage` for worlds in `aurora.awf.worlds`. Without a listed world or an existing store, `sourby$awf` is null and every path is upstream's. Depends on Paper's Moonrise `RegionDataController` contract (`ReadData`/`WriteData`), so a Paper rebase that changes it breaks this patch. |
| 0023 `net/minecraft/world/item/crafting/ShapelessRecipe.java` | `DIRECT_NMS_PATCH` | The inherited Pufferfish greedy matcher skips Paper's exact/predicate ingredient logic; it now runs only when every ingredient is plain. |

### 2.2 Engine baseline (formerly Canvas/Folia)

Since 2026-10-05 Paper is the only upstream. What Canvas and Folia used to supply — region
threading, Canvas's own features and configuration — is SourbyCraft-owned:

| Location | Content |
|---|---|
| `sourbycraft-server/minecraft-patches/sources/` (472 files on 2026-10-25) | Region threading and Canvas changes to Minecraft code, as one file patch per class |
| `sourbycraft-server/paper-patches/files/` (234 files: 232 main, 2 test) | The same for Paper's server code |
| `sourbyapi/paper-patches/files/` (16 files) | The same for Paper's API |
| `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/`, `me/lucko/` | Former `canvas-server` sources (engine config, region scheduler, Spark platform); `io.canvasmc.canvas` internals renamed to `dev.iyanz.aurora.engine` on 2026-10-05 |
| `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/{event,region,simd,world}`, `api/Unsupported.java` | Former `canvas-api` sources: the region-threading plugin API. Left at `io.canvasmc.canvas` until Build 48, then moved to `dev.iyanz.sourbycraft.api` with no shim (owner decision 2026-10-21; see §2.4) |

The baseline is not an Aurora contract and the policy scripts do not scan it: every SourbyCraft
change to Minecraft or Paper code is a feature patch (§2.1, §2.3), and changes to the former
Canvas sources are ordinary source edits. The five former `canvas-patches` are now part of those
sources: the Spark platform info, Spark plugin and tick statistics edits (`DIRECT_NMS_PATCH`:
SourbyCraft identity and tick statistics in Spark), and the `GlobalConfiguration`/`WorldConfig`
defaults (`COMPATIBILITY_ONLY`, pinned by `scripts/test_patch_policy.py`).

### 2.3 `sourbycraft-server/paper-patches/features/` — 29 patches (0001–0029, 2026-10-25)

Was "4 patches (7 files)" when Build 47 was written. Grouped by class; per-patch detail in
[patches.md](patches.md) and [patch-classification.md](patch-classification.md).

| Patches | Class | Reason |
|---|---|---|
| 0001 `Metrics.java` | `DIRECT_NMS_PATCH` | Removes the bStats phone-home. |
| 0002 `PaperBootstrap`, 0004 and 0016 `log4j2.xml`, 0015 `CraftCrashReport`, 0020 `ServerBuildInfoImpl`/`Metrics` | `DIRECT_NMS_PATCH` | Build identity: branded boot line, console logger naming and layout, the build named in crash reports, the SourbyCraft brand id. |
| 0003, 0006–0012 (`CraftScheduler`, the three loader gates `PaperPluginProviderFactory`/`SpigotPluginProviderFactory`/`CraftMagicNumbers`, `FoliaGlobalRegionScheduler`, `PaperPluginInstanceManager`), 0024 `FileProviderSource`, 0026 `PaperEventManager`/`BukkitCommandNode` | `COMPATIBILITY_ONLY` | Aurora Bridge: the load gates ask `AuroraBridge.admitLegacy` before refusing; bridged plugins' Bukkit tasks go to the bridge (cancel, queries, owned and entity routes, re-enable); a refused legacy plugin gets one clear line; event dispatch and Bukkit commands publish the player they act for. With `aurora.bridge.mode = off` (default) behaviour is unchanged (0024 adds the one log line); native plugins are unchanged. |
| 0005, 0019 (`CraftServer`, `CraftWorld`, `CraftScheduler`, `MCUtil`, …) | `DIRECT_NMS_PATCH` | Global-tick `getTPS`/`getAverageTickTime` off any region, owned refusal messages, `getTickableTileEntityCount`. |
| 0013 `PaperPluginInstanceManager`, `ServerPluginProviderStorage` | `DIRECT_NMS_PATCH` | Per-plugin `onLoad`/`onEnable` wall time into `PluginTimings` (hook). |
| 0014 `CraftServer#createWorld` | `DIRECT_NMS_PATCH` | Staged world creation that prefetches the spawn chunk on the global tick. |
| 0017, 0021, 0022, 0023, 0025, 0028 | `DIRECT_NMS_PATCH` | Region-safe replacements of inherited refusals: owned sync teleport, the retired entity scheduler's callback, legacy chat events, `ServerTickManager`, world settings on the startup thread, `World#getChunkAt` waiting off a tick thread. |
| 0018 `CraftServer`, `CraftWorld` | `DIRECT_NMS_PATCH` | Imports for the plugin API moved to `dev.iyanz.sourbycraft.api` (Build 48, §2.4). |
| 0027 `CraftMagicNumbers`, `PaperClassloaderBytecodeModifier` | `COMPATIBILITY_ONLY` | Shaded PaperLib sees a 26.x server (`compat/PaperLibVersionAdapter`). |
| 0029 `TickThread`, `ReferenceList`, `IteratorSafeOrderedReferenceSet`, `CraftWorld`, `CraftBlock` | `COMPATIBILITY_ONLY` | Relaxed thread checks (owner decision 2026-10-22, LightingLuminol port): every check asks `guards/ThreadChecks`; unsafe and unqualified while relaxed. |

---

### 2.4 Plugin API ownership (Build 48)

Owner decision 2026-10-21 ("canvasmc dihapus total, tidak ada shim tipis"): the former Canvas plugin API
moved from `io.canvasmc.canvas` to `dev.iyanz.sourbycraft.api` and the old package was deleted, with no
deprecated shim. It is a deliberate binary break for plugins compiled against the old package (they get
`NoClassDefFoundError` until rebuilt); the class table for plugin authors is in `sourbyapi/README.md`.

The Bukkit entry points that hand out these types (`World.getRegionizer()`, `Server.unloadWorldAsync`,
`createRegionizedData`, `getLocalRegionizedData`, `getRegionThreadingTickRateManager`, the `MapPalette`
SIMD path and the `@Unsupported` annotations) keep their names; only the types they name moved. Because an
engine-baseline line may not name a SourbyCraft package (`scripts/baseline_policy.py`), the baseline now
names the types by their simple names only and three feature patches add the imports:
`minecraft-patches/features/0059-…`, `paper-patches/features/0018-…` and `sourbyapi/paper-patches/features/0001-…`
(the first API feature patch). The baseline therefore no longer compiles without its feature patches, which
was already true of the materialized tree as built. The baseline change regenerated
`build-data/engine-baseline.sha256` deliberately (17 files, rename only).
`scripts/test_independence_policy.py` (`CanvasPackageRemovedTest`) fails on any `io.canvasmc` string in the
API sources, the server sources or any patch tree.

## 3. Build-time dependencies

| Dependency | Class | Reason |
|---|---|---|
| `io.papermc.paperweight.patcher` / `.core` 2.0.0-beta.24 | `REQUIRED_UPSTREAM_CONTRACT` | Paper's own toolchain; applied through SourbyPatcher `paper-toolchain`. Build-time only. |
| `paperRef` pin (`gradle.properties`) | `REQUIRED_UPSTREAM_CONTRACT` | Pins the only upstream revision patches apply to. Currently Paper `9240f586`. |

---

## 4. Private build tooling

| Item | Class | Reason |
|---|---|---|
| `SourbyPatcher paper-toolchain` 3.0.0 (private) | `REQUIRED_UPSTREAM_CONTRACT` | Official build adapter: applies paperweight with Paper as the only upstream and verifies pinned SourbyClip. `canvas-toolchain` (Weaver) is archived. See [private toolchain](../development/PRIVATE-TOOLCHAIN.md). |

The maintainer moved the legacy sources out of the public tree. The Paper adapter is the active
build dependency; the Canvas adapter and the Folia implementation remain archived in the private repo.

---

## 5. What this ledger says about independence

Aurora's own source touches upstream **internals** in exactly fourteen files (`LEDGERED` in
`UpstreamDependencyLedgerTest`, 2026-10-25): the thirteen in §1.2 and `CanvasConfigBridge` in §1.3
(`perf/NetworkMetrics` in §1.3 is listed by hand; it reaches Paper's unofficial network hook, which
the test's pattern does not cover). Six of the fourteen are documented patch seams rather than
adapters behind an Aurora interface (`PATCH_SEAM`: `RegionTickMetrics`, `RegionTickMetricsHolder`,
`RegionTickDataView`, `RegionFileStorageHooks`, `RegionizedWorldDataPool`, `WorldDataAccess`). The
Canvas/engine package (`dev.iyanz.aurora.engine`; the `io.canvasmc` API package no longer exists) is reached
from exactly three of them: `config/upstream/CanvasConfigBridge.java` (§1.3, engine config),
`awf/world/AuroraWorldsService.java` (§1.2, world lifecycle) and `perf/LiveSchedulerSource.java`
(§1.2, `/perf scheduler` diagnostics, read-only). Everything else is either public API the product
needs, a patch against vanilla behaviour, or build tooling.

That is the shape §25 asks for. The upstream implementation is replaceable at the contracts listed
in §1.2 and §1.3 without redesigning anything above them — which is the claim, and now the test
(`UpstreamDependencyLedgerTest`).

---

## 6. Remote services at runtime

Audited 2026-09-27 against the then `canvasRef` pin (`6a600b89`; the migrated engine baseline is that code at Canvas `2a3bf65c`): Canvas `canvas-server` sources and
every Canvas patch's added lines, plus SourbyCraft's own source and patches.

| Candidate | Finding |
|---|---|
| Paper's startup update check (`PaperVersionFetcher`) | Removed by Canvas (`MinecraftServer.java.patch`, "Canvas - Rebrand") |
| `CanvasVersionFetcher` (`/version`; renamed `AuroraVersionFetcher` 2026-10-21, commit link now SourbyCraft) | **Corrected 2026-10-05:** it did call `https://canvasmc.io/api/v2/` (`getLatestBuild`) whenever the jar's `Build-Number` was set, and `GlobalConfiguration` did the same (`getBuild`) after every config load. SourbyCraft's CI never sets `BUILD_NUMBER`, so released jars took the local path and made no request. Both calls were removed with `httpclient`; `/version` now always reports the local status it already showed |
| `io.canvasmc.httpclient:httpclient` | **Corrected 2026-10-05:** the jar's only class is `io.canvasmc.canvas.ClientV2`, used by the two calls above; the original audit searched for the artifact's group name instead. Removed with the Canvas Maven repository |
| `https://maven.canvasmc.io` | No longer used, at build time or runtime (removed 2026-10-05) |
| SourbyCraft's own network I/O | Listed below (re-audited 2026-10-25 from the `https://` literals and the network clients in `dev.iyanz.sourbycraft`; the `https://` strings in `dev.iyanz.aurora.engine` are attribution links in comments, the Mojang bug-tracker links written into the engine config's comments, and the SourbyCraft repository link `AuroraVersionFetcher` prints; none is fetched). None of it is a Canvas service; see also [region-io-audit.md](region-io-audit.md) |

| Endpoint | Code | When it is contacted |
|---|---|---|
| `https://api.github.com/repos/…` (release metadata) and the release assets it names | `update/SourbyUpdater`, `UpdateOverrides.DEFAULT_REPOS_BASE`, `HttpTransport` | Auto-updater checks, `updater.enabled` default `true` (RESTART_REQUIRED); applying is `updater.apply-mode`, default `notify` (LIVE). Assets must be https (plain http only from a loopback host while the API-base test override is set) |
| `https://cdn.modrinth.com/…/ViaVersion-5.10.0.jar`, `…/ViaBackwards-5.10.0.jar` | `bootstrap/PluginProvisioner` (through `LibDownloader`) | Boot, before Paper's plugin scan, only when the pinned jar is missing; SHA-256 and size pinned; `plugins.viaversion.auto-provision` default `true` (RESTART_REQUIRED) |
| `https://api.modrinth.com/v2/project/<id>/version` | `update/ViaAutoUpdate` | On the updater schedule, only when a pinned Via jar is missing; it reinstalls the reviewed pin, never upgrades; `updater.via-auto-update` default `true` (LIVE) |
| `https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar` | `bootstrap/ProtocolLibProvisioner` | Boot, only when the pinned jar is missing; pin in `build-data/protocollib-pin.json`; `plugins.protocollib.auto-provision` default `true` (RESTART_REQUIRED) |
| `https://download.db-ip.com/free/dbip-city-lite-<YYYY-MM>.mmdb.gz` | `util/GeoUtil` | The first GeoIP lookup (`/ping` on a player address) when no cached database exists; retried at most hourly after a failure; size-bounded |
| `https://install.speedtest.net/app/cli/…` | `command/SpeedtestCommand` | Only when an operator runs `/speedtest` and the binary is not cached; the archive is SHA-256-verified before it is extracted or run |
| Operator-configured Redis host | `awf/redis/RedisClient` | Only with a Redis AWF backend, the Aurora Redis Fabric (`aurora.awf.redis.fabric.enabled`, default `false`) or `aurora.awf.multiprofile.repository: redis`; all off by default |
| Operator-configured AbyssLink (Leviathan) host | `abysslink/AbyssLinkConnectionManager` | Only with `aurora.abysslink.enabled` (default `false`, RESTART_REQUIRED); failure never blocks startup |

The plugin downloads (Via, ProtocolLib and the Via reinstall) are refused when SourbyClip runs offline
(`-Dsourbyclip.offline=true`); the updater, GeoIP and speedtest paths do not read that flag and are
governed by their own settings or commands. The core runtime therefore needs no Canvas remote service or API. The first boot still needs the library
mirror that SourbyClip downloads from (and, on a cold boot, the Mojang server jar); that is
SourbyClip's configuration, not Canvas's.
