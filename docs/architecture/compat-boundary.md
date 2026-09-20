# Aurora Compatibility Boundary

T6 deliverable for §12 of `docs/AURORA-FULL-TRANSITION.md`.

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
Aurora interface                io.canvasmc....
    ↑
compat implementation
    ↓
Canvas/Folia/Paper
```

Every file in Aurora's own source that touches upstream internals is one of two shapes, and
nothing else is permitted:

### 1.1 Adapters behind an Aurora interface

| Aurora interface | Compat implementation | Upstream reached |
|---|---|---|
| `execution/region/RegionBackend` | `execution/region/FoliaRegionBackend` | `RegionizedServer`, `TickRegionScheduler` |
| `execution/OwnerHandoff` | `execution/RegionOwnerHandoff` | `threadedregions.EntityScheduler` (internal) |
| `config/upstream/UpstreamConfigBridge` | `config/upstream/CanvasConfigBridge` | `io.canvasmc.canvas.GlobalConfiguration`, `WorldConfig` |

Aurora core depends on the interface. The implementation depends on upstream. The arrow never
runs core → upstream.

### 1.2 Patch seams

`perf/RegionTickMetrics` and `perf/RegionTickMetricsHolder` name
`ca.spottedleaf.common.time.TickTime` and `TickData.TickReportData` directly and implement no
Aurora interface. This is the exception §12 grants in its own words:

> except in direct NMS/upstream patches where indirection would be harmful and the dependency is
> intentionally documented

It qualifies because **the dependency actually runs the other way**. Patch 0013 adds
`import dev.iyanz.sourbycraft.perf.RegionTickMetrics` to upstream's `TickData`: upstream calls
into Aurora, and these classes name upstream's tick-time types because those are the contract
the patched call site hands over and expects back. Wrapping them would mean duplicating
upstream's value types to gain an indirection nobody reads through.

## 2. What the boundary contains

§12 lists what a compatibility layer should hold. Against what exists:

| §12 expects | Where it is | Note |
|---|---|---|
| scheduler adapters | `execution/region/`, `execution/RegionOwnerHandoff` | ✔ |
| upstream config bridges | `config/upstream/` | ✔ |
| upstream version/build helpers | `brand/BuildInfo` | ✔ — no upstream internals; reads SourbyCraft's own build identity |
| Spark platform adapter | `canvas-patches/.../spark/FoliaPlatformInfo.java.patch`, `spark/SourbyServerConfigProvider` | Patch, because Spark's platform identity lives in a Canvas class |
| compatibility shims | none | Nothing needs one today |

**It contains no core Aurora business logic.** The three adapters are dispatch and translation;
the policy they serve (`AsyncPathProcessor`, `AuroraRuntime`, `AuroraConfig`, `LanePortions`)
lives in core and names no upstream internal.

### On package layout

§12 sketches `dev.iyanz.sourbycraft.compat` with `scheduling` / `config` / `spark` / `paper` /
`upstream`, and says *"exact package names may differ"*. They do. The boundary is organised by
the subsystem it serves — `execution/region`, `config/upstream` — rather than gathered into one
`compat` package.

This is deliberate, not drift. Collecting five files into a `compat` tree would move them away
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
| `canvasIsReachableFromExactlyOnePlace` | Canvas gaining a second doorway |
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
| core runtime can be reasoned about without reading Canvas service classes | ✔ Canvas is reachable from exactly one file, asserted |

Canvas specifically is reachable from **one** file in the entire runtime source:
`config/upstream/CanvasConfigBridge`. Folia/Paper internals from **four**. That is the narrow
boundary T6 asks for, and the tests are what keep it narrow.
