# Patch classification

DEVELOPMENT-TASKS K. Every active SourbyCraft patch, as of 2026-09-27 (Build 47), with its
role and what should happen to it. Sizes are added lines (`+`) in the patch file.

**Categories:** FOUNDATION (the product cannot boot or identify itself without it), INTEGRATION
(connects SourbyCraft code to an upstream seam), PERFORMANCE (a measured or measurable
optimisation), COMPATIBILITY (keeps plugins/config working), SECURITY, BRANDING, LEGACY.

**Dispositions:** KEEP, SPLIT, MOVE TO SOURBY SOURCE, REPLACE, REMOVE, DEFER.

## Minecraft feature patches (`minecraft-patches/features/`)

| Patch | + lines | Category | Disposition | Reason |
| --- | --- | --- | --- | --- |
| 0001 boot hook in `DedicatedServer.initServer` | 7 | FOUNDATION | KEEP | The one call that starts SourbyCraft. Minimal already. |
| 0002 region tick thread count / `aurora.cpu.cores` | 125 | INTEGRATION | MOVE TO SOURBY SOURCE | Adds `dev/iyanz/aurora/cpu/AuroraCpu.java` inside the patch. The class could live in Sourby source, leaving a one-line call in `TickRegions`. |
| 0003 skip provably capped spawn categories | 45 | PERFORMANCE | KEEP | Measured spawn-path saving; confined to `NaturalSpawner`. |
| 0004 POI consistency scan without per-block lookups | 19 | PERFORMANCE | KEEP | Small, measured. |
| 0005 `SnapshotPathRegion` immutable block snapshot | 200 | PERFORMANCE | MOVE TO SOURBY SOURCE | Largest patch; most of it is the new `dev/iyanz/aurora/level/SnapshotPathRegion.java`. Only the `PathNavigationRegion` hook needs to be a patch. |
| 0006 async pathfinding offload | 135 | PERFORMANCE | SPLIT | Touches `Mob` and several navigation classes. Split the navigation hooks from the `Mob` scheduling so each can be rebased and reverted on its own. Validity rules are already in `perf/AsyncPathValidity`. |
| 0007 elytra glide slot stream allocation | 17 | PERFORMANCE | KEEP | Small, local. |
| 0008 `Projectile` tick ticket typo | 22 | COMPATIBILITY | KEEP (upstream candidate) | An upstream bug fix. Offer it upstream; remove on the rebase that contains it. |
| 0009 `Entity.lastKnownSpeed` as three doubles | 33 | PERFORMANCE | KEEP | Allocation removal. |
| 0010 reuse `BlockPos` in `ServerLevel.optimiseRandomTick` | 24 | PERFORMANCE | KEEP | Allocation removal. |
| 0011 caller-owned entity query overloads | 12 | PERFORMANCE | KEEP | Small. |
| 0012 inline AABB, reuse `MutableBlockPos` | 46 | PERFORMANCE | KEEP | Allocation removal in `Entity`. |
| 0013 custom tick metrics | 85 | INTEGRATION | SPLIT | Mixes tick telemetry (`TickData`, `TickRegions`), world shutdown and the `SourbyCraftBootstrap.close()` call. Split telemetry from lifecycle. |
| 0014 skip spawn-state scan a region cannot use | 25 | PERFORMANCE | KEEP | Measured. |
| 0015 reject a landed arrow before the tag lookup | 7 | COMPATIBILITY | KEEP (upstream candidate) | Upstream bug fix. |
| 0016 refuse a cross-region block test | 45 | SECURITY | MOVE TO SOURBY SOURCE (partial) | Region-safety fix (PRD §108). The new `AuroraCommandErrors` class could move out; the `ExecuteCommand` check stays a patch. |
| 0017 hoist query bounds out of the entity intersection loop | 28 | PERFORMANCE | KEEP | Moonrise `ChunkEntitySlices`; reviewed in `region-safety-review-0017-0019.md`. |
| 0019 translate block collision boxes without allocating | 20 | PERFORMANCE | KEEP | Moonrise `CollisionUtil`; same review. |
| 0020 commit AWF stores at shutdown | 1 | INTEGRATION | KEEP | One call; no-op without AWF worlds. |

There is no 0018; the number was never reused.

## Minecraft source patches (`minecraft-patches/sources/`)

| Patch | Category | Disposition | Reason |
| --- | --- | --- | --- |
| `Commands.java` | BRANDING | KEEP | Removes the `/canvas` command tree. |
| `RegionFileStorage.java` | INTEGRATION | KEEP | AWF hooks and `/perf storage` counters; every added path is a null check or a counter when AWF is not attached. Depends on Moonrise's `RegionDataController` contract. |

## Paper patches (`paper-patches/files/`)

| Patch | Category | Disposition | Reason |
| --- | --- | --- | --- |
| `PaperBootstrap.java` | BRANDING | KEEP | Branded boot line from `BuildInfo`. |
| `log4j2.xml` | BRANDING | KEEP | Console logger naming. |
| `com/destroystokyo/paper/Metrics.java` | SECURITY | KEEP | Removes the bStats phone-home. |
| `PaperPluginProviderFactory.java`, `SpigotPluginProviderFactory.java`, `CraftMagicNumbers.java` | COMPATIBILITY | KEEP | Aurora Bridge admission; unchanged behaviour when `aurora.bridge.mode = off`. |
| `CraftScheduler.java` | COMPATIBILITY | KEEP | Bridge routing, cancel, `isQueued`, `isCurrentlyRunning`, `getPendingTasks`. |

## Canvas patches (`canvas-patches/files/`)

| Patch | Category | Disposition | Reason |
| --- | --- | --- | --- |
| `GlobalConfiguration.java` | COMPATIBILITY | KEEP | Safer default (`guard-severity: LOG`) and the Sourby config bridge. |
| `WorldConfig.java` | COMPATIBILITY | KEEP | HUD coexistence. |
| `spark/FoliaPlatformInfo.java` | BRANDING | KEEP | One build identity in Spark. |
| `spark/FoliaSparkPlugin.java`, `spark/plugin/FoliaTickStatistics.java` | INTEGRATION | KEEP | Spark reads Sourby tick statistics, config and metadata. |

## Findings

- **Move to Sourby source (3):** 0002, 0005 and 0016 carry whole new classes inside patches.
  Moving the classes shrinks the patches to their hooks and lets the classes be unit-tested
  normally. Not done yet: each move must keep the class in the package the patched upstream
  code imports (`dev.iyanz.aurora.*`, compiled with the Minecraft sources), which is why they
  were written inside the patches.
- **Split (2):** 0006 and 0013 each mix unrelated changes.
- **Upstream candidates (2):** 0008 and 0015 fix upstream bugs.
- **Duplicate upstream optimisations:** not checked against the pinned Paper/Canvas revisions.
  That check needs a diff per patch against upstream's own changes and is open.
- **Obsolete Folia-era patches:** none identified. Every patch above touches code present at
  the pinned revisions (CI run 445 applied them all).
- **Regression tests before removal:** 0006 has `AsyncPathValidityTest`, 0013 has the
  metrics tests, 0016 and 0017/0019 have review docs, and AWF has its storage tests. The
  allocation patches (0007, 0009–0012) have no dedicated tests; add them before changing those.
