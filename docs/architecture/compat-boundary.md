# Aurora Compatibility Boundary

T6 deliverable for §12 of the former transition plan `AURORA-FULL-TRANSITION.md` (no longer in the
repository; the § numbers below cite it). File sets re-derived from `UpstreamDependencyLedgerTest`
on 2026-10-25 (PR #62 docs pass).

T6 asks for a **narrow** compatibility boundary for Paper/Folia/Canvas-derived APIs and
internals, and for one property above all: *the core runtime can be reasoned about without
reading Canvas service classes*.

The boundary is narrow already. This document states where it runs, which way the dependencies
point, and what enforces that — the [dependency ledger](dependency-ledger.md) inventories
**what**, this states **which direction**.

## 1. Dependency direction

§12's rule:

```text
allowed                         avoid
-------                         -----
Aurora core                     Aurora core
    ↓                               ↓
Aurora interface                dev.iyanz.aurora.engine....
    ↑
compat implementation
    ↓
Canvas/Folia/Paper
```

Every file in Aurora's own source that touches upstream internals is one of two shapes, and
nothing else is permitted. There are fourteen such files (`LEDGERED` in the test): eight adapters
and six patch seams.

### 1.1 Adapters behind an Aurora interface

| Aurora interface | Compat implementation | Upstream reached |
|---|---|---|
| `execution/region/RegionBackend`, `CurrentRegion`, `RegionPopulation`, `EnginePools`, `RegionTickRate` | `execution/region/FoliaRegionBackend` | `RegionizedServer`, `TickRegionScheduler`, Moonrise `MoonriseCommon` pools |
| `execution/region/RegionOwnership`, `RegionChunkLoading` | `execution/region/MoonriseRegionOwnership` | Moonrise `TickThread`, concurrentutil `Priority` |
| `execution/OwnerHandoff` | `execution/RegionOwnerHandoff` | `threadedregions.EntityScheduler` (internal) |
| `perf/StorageBacklog` | `perf/RegionIoQueue` | Moonrise `MoonriseRegionFileIO` |
| `config/upstream/UpstreamConfigBridge` | `config/upstream/CanvasConfigBridge` | `dev.iyanz.aurora.engine.GlobalConfiguration`, `WorldConfig` |
| `api/world/AuroraWorlds` (sourbyapi) | `awf/world/AuroraWorldsService` | `RegionizedServer`, engine `SaveAllTicket`, Moonrise `MoonriseRegionFileIO.flush` |
| `perf/SchedulerQueueCollector.Source` (with `RegionProbe`) | `perf/LiveSchedulerSource` | `TickRegions`, `TickRegionScheduler`, `RegionizedTaskQueue`, `ThreadedRegionizer`, engine `SchedulerUtil` (read-only) |
| `perf/RegionLocator.Topology` | `perf/RegionLocator` (`LiveTopology`) | `ThreadedRegionizer`, `TickRegions` (read-only) |

Aurora core depends on the interface. The implementation depends on upstream. The arrow never
runs core → upstream.

### 1.2 Patch seams

Six files name upstream types directly and implement no Aurora interface (`PATCH_SEAM` in the
test). This is the exception §12 grants in its own words:

> except in direct NMS/upstream patches where indirection would be harmful and the dependency is
> intentionally documented

| Seam | Patches | Upstream types it names |
|---|---|---|
| `perf/RegionTickMetrics`, `perf/RegionTickMetricsHolder`, `perf/RegionTickDataView` | MC 0013, 0079 | `ca.spottedleaf.common.time.TickData` (subclassed by `RegionTickDataView`), `TickTime`, `TickData.TickReportData` |
| `awf/RegionFileStorageHooks` | MC 0021, 0053 | Moonrise `RegionDataController.ReadData` / `WriteData` |
| `compat/luminol/RegionizedWorldDataPool`, `compat/luminol/WorldDataAccess` | MC 0085, Paper 0029 | `RegionizedWorldData`, the region types, Moonrise chunk tickets |

They qualify because **the dependency actually runs the other way**. MC 0013 makes the region
schedule handle hold a `RegionTickMetricsHolder` and fill its five `TickData` windows with
`RegionTickDataView`; MC 0079 calls the holder's lifecycle statics; MC 0021/0053 make
`RegionFileStorage` delegate each method to `RegionFileStorageHooks`; MC 0085 / Paper 0029 open
`WorldDataAccess` leases at the patched sites. Upstream calls into Aurora, and these classes name
upstream's types because those are the contract the patched call sites hand over and expect back.
Wrapping them would mean duplicating upstream's value types to gain an indirection nobody reads
through.

`compat/luminol` is the one **shim** in the tree: LightingLuminol's cross-region world-data pool
(GPL-3.0, revision 72d51c4d), ported for the owner-decided relaxed thread checks (2026-10-22). It is
dead code while `aurora.bridge.thread-checks` is enforced (`ThreadChecks.RELAXED_SCOPE` is a
`static final`), and unsafe by design while relaxed
([aurora-plugin-bridge.md, *Thread checks*](aurora-plugin-bridge.md#thread-checks-owner-decision-2026-10-22)).

## 2. What the boundary contains

§12 lists what a compatibility layer should hold. Against what exists:

| §12 expects | Where it is | Note |
|---|---|---|
| scheduler adapters | `execution/region/`, `execution/RegionOwnerHandoff` | ✔ |
| upstream config bridges | `config/upstream/` | ✔ |
| upstream version/build helpers | `brand/BuildInfo` | ✔ — no upstream internals; reads SourbyCraft's own build identity |
| Spark platform adapter | `dev/iyanz/aurora/engine/spark/FoliaPlatformInfo.java` (engine source; formerly a canvas-patch), `spark/SourbyServerConfigProvider` | Engine source since the 2026-10-05 migration, outside the ledger test's scope (it audits `dev/iyanz/sourbycraft`) |
| compatibility shims | `compat/luminol` (relaxed thread checks, §1.2) | One shim, owner decision 2026-10-22 |

**It contains no core Aurora business logic.** The adapters are dispatch and translation;
the policy they serve (`AsyncPathProcessor`, `AuroraRuntime`, `AuroraConfig`, `LanePortions`)
lives in core and names no upstream internal.

### On package layout

§12 sketches `dev.iyanz.sourbycraft.compat` with `scheduling` / `config` / `spark` / `paper` /
`upstream`, and says *"exact package names may differ"*. They do. The boundary is organised by
the subsystem it serves — `execution/region`, `config/upstream` — rather than gathered into one
`compat` package; `compat/` holds only the LightingLuminol shim and the PaperLib version adapter.

This is deliberate, not drift. Collecting the adapters into a `compat` tree would move them away
from the interfaces they implement, so a reader following `RegionBackend` would leave the
package to find its only implementation. The property T6 actually asks for is that the core can
be read without upstream, and that is enforced by test rather than by directory name.

## 3. What enforces this

`UpstreamDependencyLedgerTest` (7 tests), which fails on:

| Test | Catches |
|---|---|
| `onlyLedgeredFilesReachIntoUpstreamInternals` | a new file reaching into internals |
| `everyLedgeredFileStillEarnsItsPlace` | a ledger entry that outlived its dependency |
| `everyUpstreamDependencyIsBehindAnAuroraInterfaceOrADocumentedPatchSeam` | **direction** — core → upstream coupling with no interface and no seam |
| `canvasIsReachableOnlyThroughItsRecordedDoorways` | Canvas or the engine gaining a doorway beyond the three recorded in `dependency-ledger.md` |
| `aPatchSeamThatStoppedTouchingUpstreamIsNotStillExcused` | an exception outliving its reason |
| `publicUpstreamApiIsNotTreatedAsALeak` | the pattern drifting onto supported API |
| `theLedgerDocumentExists` | the document the failures point at going missing |

Public upstream API is deliberately **not** treated as coupling. Bukkit, the Paper plugin
lifecycle and the published `threadedregions.scheduler` types are what the product is built on.
Only internals are pinned.

## 4. T6 completion gate

| Gate requirement | State |
|---|---|
| direct upstream references are inventoried | ✔ [dependency ledger](dependency-ledger.md) |
| accidental coupling is removed | ✔ every internal reference is an adapter or a documented seam, by test |
| intentional coupling is documented | ✔ ledger §1.2/§1.3, plus §1.2 here for the seams |
| core runtime can be reasoned about without reading Canvas service classes | ✔ the engine package is reachable from exactly three files, asserted |

The engine package (`dev.iyanz.aurora.engine`, the former Canvas internals) is reachable from
**three** files in the runtime source, the doorways the test asserts:
`config/upstream/CanvasConfigBridge` (engine config reload), `awf/world/AuroraWorldsService`
(world lifecycle, `SaveAllTicket`) and `perf/LiveSchedulerSource` (read-only `/perf scheduler`,
`SchedulerUtil`). Folia/Paper/Moonrise internals are reached from **fourteen** files, all listed in
§1. That is the narrow boundary T6 asks for, and the tests are what keep it narrow. Status recorded
from the test sources on 2026-10-25; the tests were not run for this edit.
