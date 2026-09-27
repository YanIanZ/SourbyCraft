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
| `execution/region/FoliaRegionBackend.java` | `io.papermc.paper.threadedregions.RegionizedServer`, `TickRegionScheduler` | `execution/region/RegionBackend` — region topology and tick scheduling. Swapping the backend does not touch callers. |
| `execution/RegionOwnerHandoff.java` | `io.papermc.paper.threadedregions.EntityScheduler` (internal, no `.scheduler.`) | `execution/OwnerHandoff` — moving work to the thread that owns an entity or region. |
| `perf/RegionTickMetrics.java` | `ca.spottedleaf.common.time.TickData`, `TickTime` | Aurora's region tick telemetry, reading upstream's tick accounting rather than duplicating it. |
| `perf/RegionTickMetricsHolder.java` | `ca.spottedleaf.common.time.TickTime` | Generation ownership for the above. |
| `execution/region/FoliaCurrentRegion.java` | `io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion()`, `ThreadedRegion.getCenterChunk()`/`id`/`regioniser.world` | `execution/region/CurrentRegion` — the Aurora Bridge's caller-region route: a chunk owned by the region that scheduled a legacy sync task. |
| `perf/RegionIoQueue.java` | `ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO` (`getControllerFor`, `getTotalWorkingTasks`) | `perf/StorageBacklog` — `/perf storage` backlog: reads Moonrise's per-world I/O task counters instead of keeping a second count. |

### 1.3 `COMPATIBILITY_ONLY`

| File | Dependency | Reason |
|---|---|---|
| `config/upstream/CanvasConfigBridge.java` | `io.canvasmc.canvas.GlobalConfiguration`, `WorldConfig` | Reloads upstream config so an existing deployment's `canvas-server.yml` keeps working. No Aurora behaviour reads it; it sits behind `config/upstream/UpstreamConfigBridge`. This is the **only** `io.canvasmc` reference in Aurora's own source. |
| `perf/NetworkMetrics.java` | `io.papermc.paper.network.ChannelInitializeListenerHolder`, Netty `ChannelPipeline` | The only hook for adding a handler to every connection's pipeline. Paper marks it unofficial. If it disappears, the `network counters` boot stage fails in isolation and `/perf network` reports nothing. It is not a threaded-regions internal, so the ledger test does not pin it; it is listed here by hand. |

---

## 2. Patch dependencies — `DIRECT_NMS_PATCH`

### 2.1 `sourbycraft-server/minecraft-patches/features/` — 19 patches

Patches against the materialised Minecraft source. They exist because the behaviour is inside a
vanilla class; none of them is an Aurora contract.

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

Patch 0006 calls `perf/AsyncPathValidity` for its two staleness checks, so the rule is tested in
Sourby's own test suite.

Per-file source patches (`minecraft-patches/sources/`):

| Patch | Class | Reason |
|---|---|---|
| `net/minecraft/commands/Commands.java` | `DIRECT_NMS_PATCH` | Removes the `/canvas` command tree. |
| `net/minecraft/world/level/chunk/storage/RegionFileStorage.java` | `DIRECT_NMS_PATCH` | Aurora World Fabric: reads, writes, scans, flush and close go to `AwfRegionStorage` for worlds in `aurora.awf.worlds`. Without a listed world or an existing store, `sourby$awf` is null and every path is upstream's. Depends on Paper's Moonrise `RegionDataController` contract (`ReadData`/`WriteData`), so a Paper rebase that changes it breaks this patch. |

### 2.2 `sourbycraft-server/canvas-patches/files/` — 5 patches

| Patch | Class | Reason |
|---|---|---|
| `spark/FoliaPlatformInfo.java` | `DIRECT_NMS_PATCH` | Platform identity and one canonical build version in Spark reports. |
| `spark/FoliaSparkPlugin.java`, `spark/plugin/FoliaTickStatistics.java` | `DIRECT_NMS_PATCH` | Spark reads SourbyCraft's tick statistics rather than a parallel set, SourbyCraft's config provider, and Aurora runtime metadata (`SourbyMetadataProvider`). |
| `GlobalConfiguration.java`, `WorldConfig.java` | `COMPATIBILITY_ONLY` | Upstream config shape, paired with `CanvasConfigBridge`. |

### 2.3 `sourbycraft-server/paper-patches/files/` — 7 patches

| Patch | Class | Reason |
|---|---|---|
| `PaperBootstrap.java`, `log4j2.xml` | `DIRECT_NMS_PATCH` | Branded boot line and console logger naming. |
| `com/destroystokyo/paper/Metrics.java` | `DIRECT_NMS_PATCH` | Removes the bStats phone-home. |
| `PaperPluginProviderFactory.java`, `SpigotPluginProviderFactory.java`, `CraftMagicNumbers.java` | `COMPATIBILITY_ONLY` | The three Folia "not marked as supporting" load gates ask `AuroraBridge.admitLegacy` before refusing. With `aurora.bridge.mode = off` (default), behaviour is unchanged. |
| `CraftScheduler.java` | `COMPATIBILITY_ONLY` | Bridged plugins' Bukkit tasks go to the Aurora Bridge instead of the Folia `UnsupportedOperationException`; `cancelTask`/`cancelTasks` reach them. Non-bridged plugins are unchanged. |

---

## 3. Build-time dependencies

| Dependency | Class | Reason |
|---|---|---|
| `io.canvasmc.weaver.patcher` (`build.gradle.kts`) | `REQUIRED_UPSTREAM_CONTRACT` | Canvas's own weaver toolchain sequences access transformers and base patches. Build-time only — nothing it produces is a runtime dependency on Canvas. |
| `canvasRef` pin (`gradle.properties`) | `REQUIRED_UPSTREAM_CONTRACT` | Pins the upstream revision patches apply to. Currently `6a600b89`. |

---

## 4. Private build tooling

| Item | Class | Reason |
|---|---|---|
| `SourbyPatcher canvas-toolchain` (private) | `REQUIRED_UPSTREAM_CONTRACT` | Official build adapter delegates nested patching to Weaver 2.4.5 and verifies pinned SourbyClip. Legacy Folia sources moved to private YanIanZ/SourbyPatcher; not reactivated. See [private toolchain](../development/PRIVATE-TOOLCHAIN.md). |

The maintainer moved the legacy sources out of the public tree. The new Canvas adapter is
an active build dependency; the archived Folia implementation remains unused in the private repo.

---

## 5. What this ledger says about independence

Aurora's own source touches upstream **internals** in exactly four files (§1.2), and Canvas
specifically in exactly one (§1.3). Everything else is either public API the product needs, a
patch against vanilla behaviour, or build tooling.

That is the shape §25 asks for. The upstream implementation is replaceable at four seams
without redesigning anything above them — which is the claim, and now the test.

---

## 6. Remote services at runtime

Audited 2026-09-27 against the `canvasRef` pin (`6a600b89`): Canvas `canvas-server` sources and
every Canvas patch's added lines, plus SourbyCraft's own source and patches.

| Candidate | Finding |
|---|---|
| Paper's startup update check (`PaperVersionFetcher`) | Removed by Canvas (`MinecraftServer.java.patch`, "Canvas - Rebrand") |
| `CanvasVersionFetcher` (`/version`) | Builds text and a click-through link only; opens no connection |
| `io.canvasmc.httpclient:httpclient` | Declared in Canvas's build, imported by no Canvas source or patch at this pin |
| `https://maven.canvasmc.io` | Build-time only: Gradle plugin and dependency resolution (`settings.gradle.kts`, `build.gradle.kts`) |
| SourbyCraft's own network I/O | GitHub (updater, Via), local GeoIP, speedtest, bootstrap libraries; see [region-io-audit.md](region-io-audit.md). None of it is a Canvas service |

The core runtime therefore needs no Canvas remote service or API. The first boot still needs the
library mirror that SourbyClip downloads from; that is SourbyClip's configuration, not Canvas's.
