> Phase 46 follow-up (2026-09-14): patches 0009/0014 and the Mob portion of 0016 below are now
> removed after the [reuse audit](reuse-audit.md); upstream per-call collections
> are restored. Patch 0017 remains with updated context, and 0006 delegates
> completion cleanup to AsyncPathCompletion. The inventory below records the
> earlier build-45 review; consult the follow-up for current dispositions.

# Patch inventory and ownership audit (PRD Phase 1)

Every SourbyCraft patch on `release/26.2-canvas`, classified per PRD section 22 with
the section 87 fields. Counts are added and removed lines in the patch file itself.

## Headline findings

**The patch-sprawl premise in PRD section 13 does not hold on this branch.** There are
27 patches totalling 1,636 added lines. 915 of those are a single new file. The real
downstream modification of upstream code is about **721 lines across 26 patches** —
an average of 28 lines each. Nothing here is obsolete, nothing duplicates an upstream
optimization, and no patch mixes unrelated systems in the way section 16 warns about.

**`folia-patches/` is empty.** The section 24 Folia legacy audit has nothing to audit:
the Folia-era patch line lives on the unused `sourbypatcher` branch, not here. That
item of the Definition of Done is satisfied by construction.

**Patch 0018 was not actually a large patch — and is no longer one.** It read as 1,229
lines, but 915 of them created one new file, `RegionTickMetrics.java`, from `/dev/null`;
its real modification of upstream code was 96 lines across six files. Action 1 below
has been carried out: the patch is now **309 lines, +85 −12 across six files**.

**The section 113 integration pattern is already followed.** Consumers reach Sourby
code through `dev.iyanz.sourbycraft.perf.RegionTickMetricsHolder`,
`RegionMetricsRegistry` and `MetricsRuntime.provider()` rather than carrying
implementation inside upstream methods. The gap is section 112 — *where the
implementation class lives* — not section 113.

**No patch carries a benchmark.** That is the honest state of the whole set, and it is
what Phase 0 now exists to fix. The `Benchmark` column below is `none` for all 27
entries; it is not repeated per row.

## Inventory

### `minecraft-patches/features/` — 17 patches (0013 and 0015 removed by action 2)

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| 0001 boot hook in `DedicatedServer#initServer` | 000-bootstrap | `DedicatedServer` | +7 | KEEP | None. Exemplary thin hook. |
| 0002 sane default region tick-thread count | 100-runtime | `TickRegions` | +7 −7 | KEEP | Benchmark. Changes what `threads: -1` resolves to, not an explicit setting. |
| 0003 skip provably-capped spawn categories | 200-performance | `NaturalSpawner` | +45 | KEEP | Benchmark. Verify capped-category logic cannot suppress a legal spawn. |
| 0004 POI scan without per-block `BlockPos` | 200-performance | `PoiManager` | +19 −8 | KEEP | Benchmark. |
| 0005 `SnapshotPathRegion` immutable snapshot | 300-region | `PathNavigationRegion` | +161 | KEEP | Foundation for 0006/0007. Region-thread safety review. |
| 0006 async pathfinding: offload periodic path | 500-entity | `PathNavigation` | +66 −3 | KEEP | Default off. Benchmark and threading review before enabling. |
| 0007 async pathfinding: don't touch regionized state | 500-entity | `PathfindingContext` | +7 −1 | **MERGE** | Fold into 0006. Seven lines of the same feature, meaningless alone. |
| 0008 eliminate elytra glide-slot stream allocation | 200-performance | `LivingEntity` | +17 −2 | KEEP | Benchmark. |
| 0009 reuse collision scratch lists in `Entity#collide` | 200-performance | `Entity` | +18 −3 | KEEP | Reviewed: entity-owned, no escape. Benchmark still missing. |
| 0010 fix `Projectile` tick-ticket typo, drop dead projectiles | *correctness* | `Projectile` | +22 −8 | KEEP | Recategorize — this is a bug fix, not an optimization. |
| 0011 store `Entity#lastKnownSpeed` as three doubles | 200-performance | `Entity` | +33 −5 | KEEP | Benchmark. |
| 0012 reuse `BlockPos` in `ServerLevel` random tick | 200-performance | `ServerLevel` | +20 −1 | KEEP | Reviewed: method-local, shares nothing. |
| 0014 reuse `ParticleOptions` scratch list | 200-performance | `LivingEntity` | +23 −4 | **REWORKED** | Published the buffer into `SynchedEntityData`; fixed to copy-on-change. |
| 0016 reuse `ItemEntity` scratch list in `Mob#aiStep` | 200-performance | `Mob`, `Level` | +33 −2 | KEEP | Reviewed: mob-owned, filled in place. |
| 0017 inline AABB, reuse `MutableBlockPos` in `isInWall` | 200-performance | `Entity` | +46 −17 | **REWORK** | Needed three follow-up fixes after landing. Add a regression test. |
| 0018 custom tick metrics | 100-runtime | 6 files | +85 −12 | KEEP | **Done.** Class relocated to the Sourby source tree; 1,229 → 309 lines. |
| 0019 close lifecycle-owned runtime services | 100-runtime | `MinecraftServer` | +1 −1 | **MERGE** | One line. Belongs with 0018's lifecycle wiring. |

### `minecraft-patches/sources/` — 1 patch

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| `Commands.java` remove `/canvas` command tree | 900-branding | `Commands` | +4 −1 | KEEP | Paired with the `GlobalConfiguration` change; note the dependency. |

### `canvas-patches/` — 4 patches

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| `GlobalConfiguration` rebrand + drop build-status broadcast | 900-branding | Canvas config | +18 −10 | **SPLIT** | Two unrelated changes: a logger rename and removing a runtime broadcast. |
| `WorldConfig` rebrand + disable Canvas TPS bar | 900-branding | Canvas config | +9 −3 | KEEP | A section 23 duplicate-feature resolution; the reasoning is in the patch. |
| `FoliaSparkPlugin` wire Spark to `MetricsRuntime` | 700-api | Canvas Spark bridge | +3 −2 | KEEP | Exemplary integration: three lines, all delegation. |
| `FoliaTickStatistics` reimplement on Sourby telemetry | 700-api | Canvas Spark bridge | +61 −78 | KEEP | Net −17 lines. Satisfies section 81, one metrics source. |

### `paper-patches/` — 3 patches

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| `Metrics.java` remove bStats phone-home | 800-security | Paper metrics | +24 −13 | KEEP | None. |
| `PaperBootstrap` brand the boot line | 900-branding | Paper bootstrap | +36 −6 | KEEP | None. |
| `log4j2.xml` colour the SourbyCraft prefix | 900-branding | Paper resources | +5 | KEEP | None. |

## Recommended actions, in priority order

### 1. Move `RegionTickMetrics` out of the upstream tree (section 112) — DONE

Patch 0018 creates a 915-line SourbyCraft class inside `ca.spottedleaf.common.time`,
the vendored Metal namespace. It does not belong there, and nothing forces it:

* `TickData` is a `public final class`; `TickReportData`, `SegmentedAverage`,
  `SegmentData` and `MSPTData` are all `public record`s; `TickTime` is a
  `public final record`. There is no package-private access to preserve.
* `EMPTY_RAW_DATA`, the only constant that looks shared, is private and declared
  inside `RegionTickMetrics` itself.
* Its only consumers are `PerformanceCollector`, `RegionTickMetricsHolder` and
  `RegionMetricsRegistry` — all three already in `dev.iyanz.sourbycraft.perf`, all
  three currently importing it across the package boundary.

Moving the file to `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/`
lets those three drop the import, and takes patch 0018 from 1,229 lines to about 314
— inside the section 19 threshold — while leaving the genuine upstream work (the
48-line `TickData` change and 48 lines of hooks) in the patch where it belongs.

**Outcome.** `RegionTickMetrics.java` now lives at
`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/RegionTickMetrics.java`.
Patch 0018 went from 1,229 lines to 309, +85 −12 across six files — inside the section
19 threshold. Its three Sourby consumers and five test classes dropped the now-redundant
import; `FoliaTickStatistics`, which is in another package, was repointed to the new FQN,
as was a reflective nested-class lookup in `RegionTickMetricsTest`.

One visibility change was required: `tickCompletedWithoutTarget` was package-private so
the vendored `ca.spottedleaf.common.time.TickData` could call it, and that call now
crosses a package boundary, so the method is public with a comment saying why. The
package-private `RegionTickMetrics(Runnable)` constructor stayed package-private — it is
only reached through the public no-arg constructor, and it is now reachable from the perf
tests, which previously could not see it.

`TickData` gained one import. It already imported `RegionTickMetricsHolder` from the same
Sourby package, so the dependency direction is unchanged, and `canvas-dev-imports.txt`
needed no edit because `RegionTickMetrics` was never a vendored import.

**Verified:** `applyAllPatches` clean, `compileJava` clean, 129 perf tests passing
(including the classfile allocation-opcode assertions and the binary-compatibility suite
that both cross the new boundary), `slimServerJar` builds, and the server boots, ticks
and shuts down cleanly — TPS 19.9999, 60 telemetry samples published through the
relocated class, no new log errors.

The change is behaviour-neutral by construction. A certified before/after baseline still
needs the full 600-second runs on a quiet machine; the short runs available here vary by
more than any real signal would.

### 2. Region-thread safety review of the scratch-reuse cluster (section 108) — DONE

Patches 0009 and 0012–0016 are six variations on one mechanism: replace a
per-invocation collection with a reused scratch collection. They share one failure
mode — if a scratch instance is reachable from more than one region thread, or if a
reentrant call reuses a buffer mid-iteration, the result is silent cross-region state
corruption, not a crash.

**Outcome — three of the six were unsafe.** Full review in
[threading.md](threading.md).

* **0013 and 0015 held their buffer on `ServerLevel`**, which every region thread ticks
  concurrently — a data race on a non-thread-safe collection that could drop block
  events or hand one region's events to another. Both **removed**: each saved about 20
  allocations per second per region, neither carried a benchmark, and section 116 lists
  correctness risk as grounds for removal. `ServerLevel` is back to Folia's per-call
  locals.
* **0014 published its buffer into `SynchedEntityData`**, which stores the reference and
  only marks an entry dirty when the new value is `notEqual` to the stored one — so after
  the first publish every comparison was the list against itself and clients stopped being
  told the effect particles changed. **Fixed** by publishing an immutable copy only on a
  real change, which allocates less than upstream did.
* **0009, 0012 and 0016 are safe.** Their buffers are entity-owned or method-local and
  never escape. Reentrancy is argued by inspection, not proven.

`ScratchBufferConfinementTest` now pins both rules and was mutation-checked against the
original 0014 bug.

### 3. Merge the two fragments

0007 into 0006 (seven lines of the same async-pathfinding feature) and 0019 into 0018
(one line of the same lifecycle wiring). Both are currently patches that cannot be
understood or reverted alone.

### 4. Add a regression test for 0017

`isInWall` needed three follow-up commits after landing (`676be16`, `c08e691`,
`75e8a64`), twice for the same missing `boundingBox.move` inline. A rebase will
reintroduce that class of error unless a test pins the behaviour.

### 5. Split the `GlobalConfiguration` patch

It does two unrelated things — renames a logger and removes a runtime build-status
broadcast. Section 16 wants one problem per patch, and the second change is a
behaviour change hiding inside a branding patch.

## Category assignment (section 15)

Applying the proposed banding to the current set:

```text
000-bootstrap    0001, PaperBootstrap
100-runtime      0002, 0018, 0019
200-performance  0003, 0004, 0008, 0009, 0011, 0012, 0014, 0016, 0017
300-region       0005
500-entity       0006, 0007
700-api          FoliaSparkPlugin, FoliaTickStatistics
800-security     Metrics.java
900-branding     Commands.java, GlobalConfiguration, WorldConfig, log4j2.xml
```

`400-chunk` and `600-network` are empty — no patch currently touches chunk or network
hot paths, which is worth knowing before Phases 8 and 9 claim improvements there.

Renumbering `minecraft-patches/features/` into these bands is mechanical but not free:
the filenames are the apply order, so renumbering rewrites every file name and any
reference to them. It is worth doing once, at a patch-freeze boundary, not
incrementally. Categories 000/100/200 already roughly match the existing 0001–0019
order, so the churn is smaller than it looks.

## What this audit did not do

* No patch was benchmarked. Every `Action` naming a benchmark is unfinished work.
* Correctness of each optimization was not re-derived; classification is based on the
  patch content, its stated reasoning and its follow-up history.
* Section 23's duplicate-optimization audit was checked only by inspection. No Canvas
  or Paper equivalent was found for any of these, but that is not a proof.
