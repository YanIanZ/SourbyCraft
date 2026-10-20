# Patch classification

DEVELOPMENT-TASKS K. Every active SourbyCraft patch, as of 2026-09-27 (Build 47), with its
role and what should happen to it. Sizes are added lines (`+`) in the patch file.

> Historical, as of 2026-09-27: numbers predate the 2026-10-06 rebuild (0019–0023 → 0018–0022,
> closing the 0018 gap) and the 2026-10-13 renumbering, so the 0019/0020 rows are today's 0018/0019.
> Current Minecraft features run 0001–0048 and Paper features 0001–0014 (0001–0004 are the Paper
> file patches below). Of the Minecraft features, 0021–0038 (0020 and 0021 are the two Minecraft
> source patches below, moved into features) are not classified here in their current form;
> 0039–0048 are summarised in the second table below (added 2026-10-20). Current files:
> `sourbycraft-server/{minecraft,paper}-patches/features/`.

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
| 0016 refuse a cross-region block test | 45 | SECURITY | MOVE TO SOURBY SOURCE (partial) | Region-safety fix (PRD §108). The new `AuroraCommandErrors` class could move out; the `ExecuteCommand` check stays a patch. Known behaviour (audit ENG-11, 2026-10-14, from code, not a boot): in a chained `execute if/unless block <pos> <block> run ...` the condition is a fork, and Brigadier swallows a fork's error without a message (`BuildContexts`, `CommandSourceStack.handleError` with `forked=true`), so a cross-region refusal runs neither the `if` nor the `unless` chain and prints nothing. Only a terminal `execute if block ...` (no `run`) shows "Cannot test a block cross-region". Not changed: routing the test to the owning region cannot answer within the chain. |
| 0017 hoist query bounds out of the entity intersection loop | 28 | PERFORMANCE | KEEP | Moonrise `ChunkEntitySlices`; reviewed in `region-safety-review-0017-0019.md`. |
| 0019 translate block collision boxes without allocating | 20 | PERFORMANCE | KEEP | Moonrise `CollisionUtil`; same review. |
| 0020 commit AWF stores at shutdown | 1 | INTEGRATION | KEEP | One call; no-op without AWF worlds. |

There is no 0018 in the snapshot above; the number was never reused. (Today's numbering has no gap:
Minecraft features are 0001–0048 contiguous.)

### Minecraft feature patches added after this snapshot (0039–0048, summarised 2026-10-20)

Classified by subject and the hunks' role as reported in the patch notes, not by a fresh hunk-by-hunk
review; added lines are `+` lines of the patch file today. Dispositions are provisional.

| Patch | + lines | Category | Disposition | Reason |
| --- | --- | --- | --- | --- |
| 0039 immutable mob snapshot for async pathfinding | 171 | PERFORMANCE | KEEP; MOVE TO SOURBY SOURCE (partial candidate) | The evaluator `prepare(region, snapshot)` edits are algorithm changes and stay ([compat-boundary.md](compat-boundary.md) §1.2); the snapshot-capturing bodies are extraction candidates (M-2). `MobPathSnapshotTest`. |
| 0040 consume rejected network frames | 4 | SECURITY | KEEP | Hook only; the logic is `security/SecurityGuards`. |
| 0041 proxy-aware admission keys | 15 | SECURITY | KEEP | Hook for the admission rate limiter. |
| 0042 snapshot the creaking home position for async pathfinding | 33 | PERFORMANCE | KEEP | Extends the 0039 snapshot to `Creaking`. |
| 0043 time region-file chunk saves for `/perf storage` | 16 | INTEGRATION | KEEP | Telemetry hook around `moonrise$finishWrite`; the recorder is `perf/RegionFileSaveLatency` (`RegionFileSaveLatencyHookTest`). |
| 0044 read the horizontal speed from one field | 15 | PERFORMANCE | KEEP | Fixes a cross-thread tear found in the 0009 scratch (DEV-A); reworked by 0046. |
| 0045 lock-free region task queue depth and late-tick counts | 71 | INTEGRATION | KEEP | `LongAdder` telemetry read by `/perf scheduler` (`RegionTaskQueueDepthTest`, `TickLateStartCounterTest`); a split of telemetry from the queue code is a candidate. |
| 0046 horizontal-movement predicate as a volatile boolean, float threshold | 17 | PERFORMANCE | KEEP | DEV-A re-review fix: atomic publication and the vanilla `1.0E-5F` threshold (`ScratchReentrancyTest`). |
| 0047 invalidate in-flight async paths with a navigation generation | 28 | PERFORMANCE | KEEP | DEV-A F3 fix: a second `stop()` or a retarget can no longer re-arm an old solve (`AsyncPathPatchShapeTest`, `AsyncPathValidityTest`). |
| 0048 unloaded chunks are not loaded for the snapshot's `*IfLoaded` reads | 33 | PERFORMANCE | KEEP | Restores vanilla's `null`/BLOCKED answer for unloaded chunks (`SnapshotPathRegionTest`). |

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
- **Duplicate upstream optimisations (checked 2026-10-20, from the patch hunks and the materialised
  sources at `paperRef` 9240f586; not a diff against newer Paper):** none found among the
  PERFORMANCE patches. The evidence is the `-` lines of each hunk: they are upstream's own
  unoptimised code, and the patches apply cleanly, so the pinned revision does not already contain
  the optimisation. Per patch: 0004 (`PoiManager` stream scan), 0007 (`LivingEntity` elytra
  stream), 0009 (`Entity.lastKnownSpeed` `Vec3` subtract), 0010 (`ServerLevel` random-tick
  `BlockPos`), 0012 (`Entity.isInWall` AABB/`MutableBlockPos`), 0014 (`ServerChunkCache` spawn-state
  scan), 0017 (`ChunkEntitySlices` `intersects(box)`), 0018 (`CollisionUtil` `AABB.move`) and 0005
  (`PathNavigationRegion` live reads) each remove or replace a still-present upstream expression.
  0003 (`NaturalSpawner`), 0011 (`Level.getEntitiesOfClass` overload) and 0006 (async pathfinding)
  are additions with no upstream counterpart: `rg` for async path solving in `paper-server/` and
  the Minecraft sources finds only SourbyCraft's own classes (`PathNavigation`,
  `AmphibiousPathNavigation`). Limits: this proves "not duplicated at the pinned ref", not
  "equivalent behaviour to a future upstream change"; the check must be repeated on each `paperRef`
  bump (a patch that stops applying, or whose `-` lines vanish, is the signal). Moonrise
  (0017/0018) is vendored in the Paper tree, so its own newer releases are the likeliest source of
  a future duplicate. No benchmark was run.
- **Obsolete Folia-era patches:** none identified. Every patch above touches code present at
  the pinned revisions (CI run 445 applied them all).
- **Regression tests before removal:** 0006 has `AsyncPathValidityTest`, 0013 has the
  metrics tests, 0016 and 0017/0019 have review docs, and AWF has its storage tests. The
  allocation patches (0007, 0009–0012) have no dedicated tests; add them before changing those.
