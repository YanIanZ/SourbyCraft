# Aurora Dependency Ledger

T9 deliverable (`docs/AURORA-FULL-TRANSITION.md` §15) and the last open item of
**M1 — Runtime & Execution Ownership** (§24).

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

These are the only files permitted to touch upstream **internals**. Aurora defines the contract;
these supply today's implementation of it. This is the set the ledger test pins.

| File | Upstream internals | Contract it implements |
|---|---|---|
| `execution/region/FoliaRegionBackend.java` | `io.papermc.paper.threadedregions.RegionizedServer`, `TickRegionScheduler` (incl. `getCurrentRegion()` / `ThreadedRegion.getCenterChunk()`); `ca.spottedleaf.moonrise.common.util.MoonriseCommon.WORKER_POOL` / `IO_POOL` (`BalancedPrioritisedThreadPool.getCoreThreads()`) | `execution/region/RegionBackend` — region topology and tick scheduling; `execution/region/CurrentRegion` — the region the calling thread ticks, for the Aurora Bridge's caller-region route.; `execution/region/RegionOwnership` (global-tick part, via `MoonriseRegionOwnership`) — `RegionizedServer.ensureGlobalTickThread` and `scheduleToOrExecute`; `execution/region/RegionPopulation` — per-world chunk/entity/player counts summed from each region's `RegionStats` (`/perf chunks`, `/perf entities`); `execution/region/EnginePools` — the chunk worker and I/O pools' configured thread counts for the startup thread ledger and `/perf governor` (audit ENG-9, report only). Swapping the backend does not touch callers. |
| `execution/region/MoonriseRegionOwnership.java` | `ca.spottedleaf.moonrise.common.util.TickThread`, `ca.spottedleaf.concurrentutil.util.Priority`; `ServerLevel.moonrise$loadChunksAsync` | `execution/region/RegionOwnership` — calling-thread ownership queries (incl. `isTickThread`, which `guards/LegacyChat` uses to decide whether a caller may wait), entity ownership enforcement, and the global-tick requirement/handoff (`ensureGlobalTickThread`, `executeOnGlobalTick`: inline on the global tick thread, else queued for it; used by `world/ScheduledFunctionTicker`; delegated to `FoliaRegionBackend`, which names `RegionizedServer`); `execution/region/RegionChunkLoading` — preserves the existing asynchronous chunk-load continuation outside a command-block scope. The patch-supplied priority is an opaque pass-through token above the adapter; only the adapter casts it to the backend type. This is chunk loading, not an entity `OwnerHandoff` (no entity identity, admission or retirement delivery). |
| `execution/RegionOwnerHandoff.java` | `io.papermc.paper.threadedregions.EntityScheduler` (internal, no `.scheduler.`) | `execution/OwnerHandoff` — moving work to the thread that owns an entity or region. |
| `perf/RegionTickMetrics.java` | `ca.spottedleaf.common.time.TickData`, `TickTime` | Aurora's region tick telemetry, reading upstream's tick accounting rather than duplicating it. |
| `perf/RegionTickMetricsHolder.java` | `ca.spottedleaf.common.time.TickTime` | Generation ownership for the above. |
| `perf/RegionIoQueue.java` | `ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO` (`getControllerFor`, `getTotalWorkingTasks`) | `perf/StorageBacklog` — `/perf storage` backlog: reads Moonrise's per-world I/O task counters instead of keeping a second count. |
| `awf/world/AuroraWorldsService.java` | `io.papermc.paper.threadedregions.RegionizedServer` (global tick task queue), `dev.iyanz.aurora.engine.util.ticket.SaveAllTicket` (per-region save ticket), `dev.iyanz.sourbycraft.api.world.WorldUnloadResult` (the engine's unload outcome) and `dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent` (the point after which an unload is certain) — SourbyCraft's own plugin API since Build 48, listed because they carry the engine's unload semantics | `api/world/AuroraWorlds` — runtime AWF world lifecycle. World creation and unloading must run on the global tick, and a durable save has to wait for every region of the world to save before the AWF stores commit; the engine exposes neither as public API. The engine's unload outcome is mapped to the API's own `UnloadResult`, so `AuroraWorlds` callers see one result type. The engine ignores its unload save flag, so an unload without saving makes the world's AWF storages discard once the unload event has passed. |
| `perf/LiveSchedulerSource.java` | `io.papermc.paper.threadedregions.TickRegions` (`hasStarted`, `getScheduler`, `getRegionChunkShift`), `TickRegionScheduler` (`scheduler`, `getTotalThreadCount`, `getTickRate`, `TickThreadRunner.currentTickingTask`, `RegionScheduleHandle` scheduled start / `hasTasks` / non-schedulable flag / `sourbyTickMetrics`; the late-tick-start `LongAdder` reads `sourbyRegionTicksStarted` / `sourbyLateRegionTicks` / `sourbyGlobalTicksStarted` / `sourbyLateGlobalTicks` added by MC feature patch 0045), `RegionizedTaskQueue.RegionTaskQueueData` (`sourbyQueuedTickTasks` / `sourbyQueuedChunkTasks`, volatile per-queue entry counts added by patch 0045) via `TickRegions.TickRegionData.getTaskQueueData()`, `RegionizedServer.getGlobalTickData`, `ThreadedRegionizer` (`computeForAllRegionsUnsynchronised`, `ThreadedRegion.getState()`); `ca.spottedleaf.concurrentutil.scheduler.{Scheduler, SchedulableTick}`; **engine/Canvas:** `dev.iyanz.aurora.engine.threadedregions.SchedulerUtil` (`getSchedulerInfo()`: scheduler kind and configured threads), `dev.iyanz.sourbycraft.api.region.WorldRegionizer.ChunkRegion.State` (READY vs TRANSIENT; SourbyCraft plugin API since Build 48) | `perf/SchedulerQueueCollector.Source` — `/perf scheduler`: scheduler kind/threads, runner activity, per-world region counts by state, lateness, pending and queued tasks, late tick starts since boot, read lock-free (racy reads labelled approximate; what needs a lock is shown as n/a). The third declared Canvas/engine doorway (§5). Report only, writes nothing. |
| `perf/RegionLocator.java` | `io.papermc.paper.threadedregions.ThreadedRegionizer` (`getRegionAtUnsynchronised`, `ThreadedRegion.getData()`), `TickRegions.TickRegionData.id` | `RegionLocator.Topology` / `RegionLocator.WorldHandle` (nested `LiveTopology`, `LiveWorld`) — `/perf region <world> <x> <z>`: the world (matched case-insensitively, like Bukkit) and the region id owning a chunk, read from the single-writer/multi-reader region table without the write lock (one re-read when a merge/split has the owner unpublished). Telemetry comes from `RegionMetricsRegistry.forEachUnexpiredOf`, which reads published snapshots without refreshing any generation. |

### 1.3 `COMPATIBILITY_ONLY`

| File | Dependency | Reason |
|---|---|---|
| `config/upstream/CanvasConfigBridge.java` | `dev.iyanz.aurora.engine.GlobalConfiguration`, `WorldConfig` (formerly `io.canvasmc.canvas`) | Reloads the engine config so an existing deployment's `canvas-server.yml` keeps working. No Aurora behaviour reads it; it sits behind `config/upstream/UpstreamConfigBridge`. This is the **only** engine-config reference in Aurora's own source. The other engine doorways are `awf/world/AuroraWorldsService.java` (§1.2), for world lifecycle, and `perf/LiveSchedulerSource.java` (§1.2), for read-only `/perf scheduler` diagnostics. |
| `perf/NetworkMetrics.java` | `io.papermc.paper.network.ChannelInitializeListenerHolder`, Netty `ChannelPipeline` | The only hook for adding a handler to every connection's pipeline. Paper marks it unofficial. If it disappears, the `network counters` boot stage fails in isolation and `/perf network` reports nothing. It is not a threaded-regions internal, so the ledger test does not pin it; it is listed here by hand. |
| `engine/threadedregions/scheduler/AuroraEdfScheduler.java` | LeafPile `Scheduler`, public SourbyCraft `SchedulableTick` fields/methods, `LinkedSortedSet`, `ConcurrentUtil`, `TimeUtil`, `LazyRunnable` | Source-owned derivative of LeafPile v1.2.0. The parked EDF patch (`minecraft-patches/parked/0028-…`, held since 2026-10-07, not built) would select it for EDF and publish task state through a compatible public volatile field. Existing EDF worker budget, parking and region thread factory retained. GPL/credits in `NOTICE_LEAFPILE`; source acceptance pending, not an independent scheduler. |

---

## 2. Patch dependencies — `DIRECT_NMS_PATCH`

### 2.1 `sourbycraft-server/minecraft-patches/features/` — historical inventory; current additions below

Patches against the materialised Minecraft source. They exist because the behaviour is inside a
vanilla or engine class; individual patches may connect an Aurora-owned backend.
The table below is a historical inventory, not a current patch count. Historical, numbering as of
2026-10-05 or earlier: its numbers predate the 2026-10-06 rebuild (0019–0023 → 0018–0022) and the 2026-10-13
renumbering (old 0024 removed, 0025–0029 → 0024–0028); current names are in
`sourbycraft-server/minecraft-patches/features/` (29 files on 2026-10-14). The parked EDF patch would add
the EDF backend selection and compatible task-state publication described above.

| Patch | Keeps |
|---|---|
| 0001 boot hook in `DedicatedServer.initServer` | Aurora's bootstrap entry |
| 0002 sane default region tick thread count | Thread sizing |
| 0003, 0014 spawn-category / spawn-state scan skips | Measured spawn-path optimisations |
| 0004 POI consistency scan without per-block lookups | Measured optimisation |
| 0005 `SnapshotPathRegion` immutable block snapshot | Async pathfinding's safety precondition (`dev.iyanz.aurora.level`) |
| 0006 async pathfinding offload | The async path itself |
| 0007, 0009, 0010, 0011, 0012 allocation/reuse fixes | Measured optimisations |
| 0008 `Projectile` tick ticket typo | Upstream bug fix |
| 0013 custom tick metrics | Aurora telemetry hooks |
| 0015 reject a landed arrow before the projectile tag lookup | Upstream bug fix |
| 0016 refuse a cross-region block test | Region-safety fix (PRD §108) |
| 0017 hoist query bounds out of the entity intersection loop, 0019 translate block collision boxes without allocating | Measured optimisations |
| 0020 commit Aurora World Fabric stores at shutdown | One call in `MinecraftServer.stopServer` after the region-file I/O drain; no-op without AWF worlds |
| 0021 do not register the `/canvas` command tree, 0022 count chunk-system storage traffic, 0023 greedy shapeless match only for plain ingredients | Formerly per-file source patches; feature patches since the 2026-10-05 migration so the policy scripts see them (see §2.2) |

Patch 0006 calls `perf/AsyncPathValidity` for its two staleness checks, so the rule is tested in
Sourby's own test suite.

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
| `sourbycraft-server/minecraft-patches/sources/` (476 files) | Region threading and Canvas changes to Minecraft code, as one file patch per class |
| `sourbycraft-server/paper-patches/files/` (234 files) | The same for Paper's server code |
| `sourbyapi/paper-patches/files/` (16 files) | The same for Paper's API |
| `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/`, `me/lucko/` | Former `canvas-server` sources (engine config, region scheduler, Spark platform); `io.canvasmc.canvas` internals renamed to `dev.iyanz.aurora.engine` on 2026-10-05 |
| `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/{event,region,simd,world}`, `api/Unsupported.java` | Former `canvas-api` sources: the region-threading plugin API. Left at `io.canvasmc.canvas` until Build 48, then moved to `dev.iyanz.sourbycraft.api` with no shim (owner decision 2026-10-21; see §2.4) |

The baseline is not an Aurora contract and the policy scripts do not scan it: every SourbyCraft
change to Minecraft or Paper code is a feature patch (§2.1, §2.3), and changes to the former
Canvas sources are ordinary source edits. The five former `canvas-patches` are now part of those
sources: the Spark platform info, Spark plugin and tick statistics edits (`DIRECT_NMS_PATCH`:
SourbyCraft identity and tick statistics in Spark), and the `GlobalConfiguration`/`WorldConfig`
defaults (`COMPATIBILITY_ONLY`, pinned by `scripts/test_patch_policy.py`).

### 2.3 `sourbycraft-server/paper-patches/features/` — 4 patches (7 files)

| Patch | Class | Reason |
|---|---|---|
| `PaperBootstrap.java`, `log4j2.xml` | `DIRECT_NMS_PATCH` | Branded boot line and console logger naming. |
| `com/destroystokyo/paper/Metrics.java` | `DIRECT_NMS_PATCH` | Removes the bStats phone-home. |
| `PaperPluginProviderFactory.java`, `SpigotPluginProviderFactory.java`, `CraftMagicNumbers.java` | `COMPATIBILITY_ONLY` | The three Folia "not marked as supporting" load gates ask `AuroraBridge.admitLegacy` before refusing. With `aurora.bridge.mode = off` (default), behaviour is unchanged. |
| `CraftScheduler.java` | `COMPATIBILITY_ONLY` | Bridged plugins' Bukkit tasks go to the Aurora Bridge instead of the Folia `UnsupportedOperationException`; `cancelTask`/`cancelTasks` reach them. Non-bridged plugins are unchanged. |

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

Aurora's own source touches upstream **internals** in exactly ten files: the nine in §1.2 and
the one in §1.3. The Canvas/engine package (`dev.iyanz.aurora.engine`; the `io.canvasmc` API package no longer exists) is reached
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
| SourbyCraft's own network I/O | GitHub (updater, Via), local GeoIP, speedtest, bootstrap libraries; see [region-io-audit.md](region-io-audit.md). None of it is a Canvas service |

The core runtime therefore needs no Canvas remote service or API. The first boot still needs the
library mirror that SourbyClip downloads from; that is SourbyClip's configuration, not Canvas's.
