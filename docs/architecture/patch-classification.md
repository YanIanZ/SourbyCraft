# Patch classification

DEVELOPMENT-TASKS K. Every active SourbyCraft patch, as of 2026-09-27 (Build 47), with its
role and what should happen to it. Sizes are added lines (`+`) in the patch file.

> Historical, as of 2026-09-27: numbers predate the 2026-10-06 rebuild (0019–0023 → 0018–0022,
> closing the 0018 gap) and the 2026-10-13 renumbering, so the 0019/0020 rows are today's 0018/0019.
> Current Minecraft features run 0001–0053 and Paper features 0001–0015 (0001–0004 are the Paper
> file patches below). Of the Minecraft features, 0021–0038 (0020 and 0021 are the two Minecraft
> source patches below, moved into features) are not classified here in their current form;
> 0039–0053 are summarised in the second table below (added 2026-10-20); Paper 0015 (2026-10-20,
> BRANDING, KEEP, hook: one `CraftCrashReport` line from `brand/CrashReportIdentity`, i.e.
> `BuildInfo`, ahead of upstream's manifest-based `BrandInfo` line; `CrashReportIdentityTest`). Current files:
> `sourbycraft-server/{minecraft,paper}-patches/features/`.

**Categories:** FOUNDATION (the product cannot boot or identify itself without it), INTEGRATION
(connects SourbyCraft code to an upstream seam), PERFORMANCE (a measured or measurable
optimisation), COMPATIBILITY (keeps plugins/config working), SECURITY, BRANDING, LEGACY.

**Dispositions:** KEEP, SPLIT, MOVE TO SOURBY SOURCE, REPLACE, REMOVE, DEFER.

## Minecraft feature patches (`minecraft-patches/features/`)

| Patch | + lines | Category | Disposition | Reason |
| --- | --- | --- | --- | --- |
| 0001 boot hook in `DedicatedServer.initServer` | 7 | FOUNDATION | KEEP | The one call that starts SourbyCraft. Minimal already. |
| 0002 region tick thread count / `aurora.cpu.cores` | 24 | INTEGRATION | KEEP | Moved 2026-10-20 (M-2 slice): `AuroraCpu` now lives in Sourby source (`src/main/java/dev/iyanz/aurora/cpu/AuroraCpu.java`); the patch keeps only the `getTickThreads` default branch in `TickRegions` (call + startup log line, 3 code lines, plus the replaced upstream formula). |
| 0003 skip provably capped spawn categories | 45 | PERFORMANCE | KEEP | Measured spawn-path saving; confined to `NaturalSpawner`. |
| 0004 POI consistency scan without per-block lookups | 19 | PERFORMANCE | KEEP | Small, measured. |
| 0005 `SnapshotPathRegion` immutable block snapshot | 200 | PERFORMANCE | MOVE TO SOURBY SOURCE | Largest patch; most of it is the new `dev/iyanz/aurora/level/SnapshotPathRegion.java`. Only the `PathNavigationRegion` hook needs to be a patch. |
| 0006 async pathfinding offload | 44 | PERFORMANCE | SPLIT — DONE 2026-10-22 | Was +181 −1 over five files (`Mob`, `PathNavigation`, `AmphibiousPathNavigation`, `Sniffer`, `PathfindingContext`; 95 added code lines). The offload logic (admission checks, `EntityPathfindEvent`, block and mob snapshots, submission, owner-thread delivery and its staleness checks) moved to `perf/AsyncPathRecompute`; the per-class refusal overrides in `Mob`, `Sniffer` and `AmphibiousPathNavigation` became `AsyncPathRecompute.solveWritesMob` (`instanceof AmphibiousPathNavigation` / `Sniffer`, same set: the frog and turtle navigations inherit, nothing re-overrides); the `PathfindingContext` guard is its own patch (0082). Now `PathNavigation` only (+44 −0, 27 code lines): one flag-gated call in `recomputePath` (`isEnabled()` read first, so the default-off path costs one volatile read as before) and the seams that touch its private state: the pending flag, `sourbyMaxPathLength` / `sourbyVisitedNodesMultiplier` (read lazily after the event, as before), `sourbyIsolatedPathFinder` (flag copy moved to `AsyncPathRecompute.restoreLiveEvaluator`) and `sourbyApplyAsyncPath`. Still `logic` by `patch_surface.py` (over the 10-line hook limit): those seams cannot leave `PathNavigation` without an access transformer. The only rule change: the kick's second `canUpdatePath()` call is dropped (the caller evaluated the same pure predicate on the same thread just before). `AsyncPathRecomputeTest` runs the delivery checks on a real `PathNavigation`. |
| 0007 elytra glide slot stream allocation | 9 | PERFORMANCE | KEEP | Hook since 2026-10-20: the pick is `entity/GliderSlots` (bit mask of glider slots, one `nextInt(count)`, n-th set bit) = vanilla's `Util.getRandom` slot and random draw for any number of glider slots (`GliderSlotsTest`, every 8-slot mask x 64 seeds). The earlier reservoir pick drew once per glider slot, so with two or more (datapack/plugin gliders) it shifted the entity's later random rolls. |
| 0008 `Projectile` tick ticket typo | 22 | COMPATIBILITY | KEEP (upstream candidate) | An upstream bug fix. Offer it upstream; remove on the rebase that contains it. |
| 0009 `Entity.lastKnownSpeed` as three doubles | 33 | PERFORMANCE | KEEP | Allocation removal. |
| 0010 reuse `BlockPos` in `ServerLevel.optimiseRandomTick` | 24 | PERFORMANCE | KEEP | Allocation removal. |
| 0011 caller-owned entity query overloads | 12 | PERFORMANCE | KEEP | Small. |
| 0012 inline AABB, reuse `MutableBlockPos` | 46 | PERFORMANCE | KEEP | Allocation removal in `Entity`. |
| 0013 custom tick metrics | 9 | INTEGRATION | SPLIT — DONE 2026-10-22 | Now only the region schedule handle's wiring (`TickData`, `TickRegionScheduler`; +9 −11): `TickData` loses `final`, the handle gets one `RegionTickMetricsHolder` field, its five `tickTimesN` windows become `RegionTickDataView`s (`holder.view(...)`), and one `tickStarted` / one `tickCompleted` call replace the five per-window `addDataFrom` inserts. Every removed line is a one-for-one replacement of an upstream statement (the modifier, five constructor assignments, five inserts folded into one call), which is why `patch_surface.py` still says `logic` (9 added / 11 removed code lines; hook limit 10/2). The query/report logic that was inside `TickData` (+30 code lines) moved to `perf/RegionTickDataView` (overrides `addDataFrom`, `getTPSAverage`, `getMSPTData`, `generateTickReport`); standalone `new TickData(interval)` is upstream code again. Split out: lifecycle -> 0079, percentile order -> 0080, bootstrap close -> 0081. |
| 0014 skip spawn-state scan a region cannot use | 25 | PERFORMANCE | KEEP | Measured. |
| 0015 reject a landed arrow before the tag lookup | 7 | COMPATIBILITY | KEEP (upstream candidate) | Upstream bug fix. |
| 0016 refuse a cross-region block test | 18 | SECURITY | KEEP | Region-safety fix (PRD §108). Since 2026-10-20 `AuroraCommandErrors` lives in Sourby source (`src/main/java/dev/iyanz/aurora/command/`, message pinned by `AuroraCommandErrorsTest`); the `ExecuteCommand` check stays a patch (hook). Known behaviour (audit ENG-11, 2026-10-14, from code, not a boot): in a chained `execute if/unless block <pos> <block> run ...` the condition is a fork, and Brigadier swallows a fork's error without a message (`BuildContexts`, `CommandSourceStack.handleError` with `forked=true`), so a cross-region refusal runs neither the `if` nor the `unless` chain and prints nothing. Only a terminal `execute if block ...` (no `run`) shows "Cannot test a block cross-region". Not changed: routing the test to the owning region cannot answer within the chain. |
| 0017 hoist query bounds out of the entity intersection loop | 28 | PERFORMANCE | KEEP | Moonrise `ChunkEntitySlices`; reviewed in `region-safety-review-0017-0019.md`. |
| 0019 translate block collision boxes without allocating | 20 | PERFORMANCE | KEEP | Moonrise `CollisionUtil`; same review. |
| 0020 commit AWF stores at shutdown | 1 | INTEGRATION | KEEP | One call; no-op without AWF worlds. |

There is no 0018 in the snapshot above; the number was never reused. (Today's numbering has no gap:
Minecraft features are 0001–0053 contiguous.)

### Minecraft feature patches added after this snapshot (0039–0053, summarised 2026-10-20; 0079–0081 split from 0013 and 0082 split from 0006 on 2026-10-22)

Classified by subject and the hunks' role as reported in the patch notes, not by a fresh hunk-by-hunk
review; added lines are `+` lines of the patch file today. Dispositions are provisional.

| Patch | + lines | Category | Disposition | Reason |
| --- | --- | --- | --- | --- |
| 0039 immutable mob snapshot for async pathfinding | 150 | PERFORMANCE | KEEP; MOVE TO SOURBY SOURCE (partial candidate) | The evaluator `prepare(region, snapshot)` edits are algorithm changes and stay ([compat-boundary.md](compat-boundary.md) §1.2); the snapshot-capturing bodies are extraction candidates (M-2). `MobPathSnapshotTest`. 2026-10-22: its `PathNavigation` hunks (snapshot capture in the kick) and the Creaking `sourbyAsyncSolveSafe` override left with the 0006 split (the capture is in `perf/AsyncPathRecompute`); +178 −79 -> +150 −62. |
| 0040 consume rejected network frames | 4 | SECURITY | KEEP | Hook only; the logic is `security/SecurityGuards`. |
| 0041 proxy-aware admission keys | 15 | SECURITY | KEEP | Hook for the admission rate limiter. |
| 0042 snapshot the creaking home position for async pathfinding | 35 | PERFORMANCE | KEEP | Extends the 0039 snapshot to `Creaking`. 2026-10-22: no longer removes the Creaking override (0039 no longer adds it); +35 −12 -> +35 −4. |
| 0043 time region-file chunk saves for `/perf storage` | 16 | INTEGRATION | KEEP | Telemetry hook around `moonrise$finishWrite`; the recorder is `perf/RegionFileSaveLatency` (`RegionFileSaveLatencyHookTest`). |
| 0044 read the horizontal speed from one field | 15 | PERFORMANCE | KEEP | Fixes a cross-thread tear found in the 0009 scratch (DEV-A); reworked by 0046. |
| 0045 lock-free region task queue depth and late-tick counts | 71 | INTEGRATION | KEEP | `LongAdder` telemetry read by `/perf scheduler` (`RegionTaskQueueDepthTest`, `TickLateStartCounterTest`); a split of telemetry from the queue code is a candidate. |
| 0046 horizontal-movement predicate as a volatile boolean, float threshold | 17 | PERFORMANCE | KEEP | DEV-A re-review fix: atomic publication and the vanilla `1.0E-5F` threshold (`ScratchReentrancyTest`). |
| 0047 invalidate in-flight async paths with a navigation generation | 23 | PERFORMANCE | KEEP | DEV-A F3 fix: a second `stop()` or a retarget can no longer re-arm an old solve (`AsyncPathPatchShapeTest`, `AsyncPathValidityTest`). 2026-10-22: the token/check lines moved with the kick to `perf/AsyncPathRecompute`; the patch keeps the field, getter and six bumps (the delivery bump is now in `sourbyApplyAsyncPath`); +29 -> +23, 10 code lines, `logic` only because none of them calls `dev.iyanz`. |
| 0048 unloaded chunks are not loaded for the snapshot's `*IfLoaded` reads | 33 | PERFORMANCE | KEEP | Restores vanilla's `null`/BLOCKED answer for unloaded chunks (`SnapshotPathRegionTest`). |
| 0049 time how long region tasks wait in their queue | 4 | INTEGRATION | KEEP | Hook only (3 code lines): stamp on first `tryPush`, record before `executeTask` runs the task; the recorder is `perf/RegionTaskWaitLatency` (`RegionTaskWaitLatencyHookTest`). No view reads it yet. |
| 0050 time region-file chunk loads for `/perf storage` | 15 | INTEGRATION | KEEP | Telemetry wrapper around the region-file part of `moonrise$readData` (found-data reads and failures; the external-stream retry calls the inner method so a retry stays one load); recorder `perf/RegionFileLoadLatency`, same ring as 0043 (`RegionFileLoadLatencyHookTest`). No view reads it yet. |
| 0051 count chunk lifecycle transitions | 8 | INTEGRATION | KEEP | Hook only (8 code lines): first statement of each `BaseChunkSystemHooks` lifecycle callback (holder create/delete, FULL in/out, ticking in/out, entity ticking in/out) counts into `perf/ChunkLifecycleCounters` (since boot, all worlds; `ChunkLifecycleCountersTest`, `ChunkLifecycleHookTest`). Shown in `/perf chunks` (`command/PerfTelemetryRows`); overhead not benchmarked. |
| 0052 count entity ticks per world in the region entity loop | 4 | INTEGRATION | KEEP | Hook only (4 code lines): `RegionizedWorldData.sourbyEntityTicks` (plain int, region-thread owned) is zeroed before `ServerLevel.tick`'s `forEachTickingEntity` loop, incremented right before each `guardEntityTick(tickNonPassenger)`, and after the loop `perf/EntityTickCounters.regionTicked(bukkitName, count, loop nanos)` records once per region loop (per world since boot: ticks, loops, loop time, largest single-region loop; passengers and frozen entities not counted). Shown in `/perf entities`. `EntityTickCountersTest`, `EntityTickHookTest` (bytecode order). Overhead not benchmarked. |
| 0053 structure scans read AWF once and fail fast while the store is failing | 1 | INTEGRATION | KEEP | One changed line in the 0021 AWF branch of `RegionFileStorage.scanChunk`: `read` -> `awf/AwfRegionStorage.scanRead` (one attempt, no fence, refused with a stackless `ScanRefused` for 5 s after a scan read failed or while reads fail fast, only for chunks whose bytes must come from the store). Vanilla `StructureCheck` answers a throwing scan with CHUNK_LOAD_NEEDED and loads the chunk, so answers are unchanged; the load still waits for the store. `patch_surface` = logic (1/1 line, the call goes through the `sourby$awf` field). `AwfStructureScanTest`. |
| 0079 region tick metrics lifecycle (split from 0013, 2026-10-22) | 9 | INTEGRATION | KEEP | Hook (8 added / 0 removed code lines): six calls in `TickRegions` (destroy, active, inactive, preMerge, preSplit, mergeInto) on the handle's `RegionTickMetricsHolder` (`retireDestroyed`, `activate`, `retireInactive`, `retireMerged`, `retireSplit`, `rotateForMerge`), `ServerLevel.sourbyTelemetryWorldId` and the `WorldShutdownThread` world retire (holder statics). No import; the helper names no engine type (independence policy). Timestamps stay at the call sites. |
| 0080 fix swapped worst-percentile segments in `TickData` reports (split from 0013) | 1 | COMPATIBILITY | KEEP (upstream candidate) | Upstream passes the 1%-worst segment into the record's `segment5PercentWorst` slot and vice versa; the patch swaps the two arguments (+1 −1, classified `fix`). |
| 0081 close SourbyCraft services at shutdown (split from 0013) | 1 | INTEGRATION | KEEP | One `SourbyCraftBootstrap.close()` call after plugin disable / async-task wait in `MinecraftServer` (hook). |
| 0082 async pathfinding: no shared path-type cache off a region thread (split from 0006, 2026-10-22) | 7 | PERFORMANCE | KEEP | `PathfindingContext(level, mob)` uses the regionized `PathTypeCache` only when `TickRegionScheduler.getCurrentRegionizedWorldData()` is non-null (+7 −1, 2/1 code lines). `logic` by the heuristic only because the guard calls the engine accessor directly (no `dev.iyanz` call); a Sourby wrapper would add an upstream-internals ledger entry for no behavioural gain. |

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
| `CraftScheduler.java` | COMPATIBILITY | KEEP | Bridge routing, cancel, `isQueued`, `isCurrentlyRunning`, `getPendingTasks`. Build 50 (2026-10-22): now Paper feature patches 0003, 0006–0011 (and 0019's refusal line); every site is one call into `bridge/AuroraBridge` / `FoliaSchedulerCompatibility`, behaviour pinned by `CraftSchedulerHooksTest`. `patch_surface.py` before -> after: 0003 logic +39/−3 -> logic +23/−3 (three loader gates + 11-line `bridgeTask` adapter of package-private `CraftTask` state); 0006 logic +6/−0 -> hook +1/−1; 0007 logic +43/−1 -> logic +40/−1 (the LightingLuminol `FoliaGlobalRegionScheduler`/`PaperPluginInstanceManager` port is unchanged); 0008 logic +10/−3 -> hook +2/−2; 0009 logic +6/−0 -> hook +1/−1; 0010, 0011 hook +3/−0 unchanged; 0019 logic +29/−23 unchanged. |

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
- **Split (2):** 0006 and 0013 each mixed unrelated changes. 0013 was split on 2026-10-22 into 0013 (handle wiring), 0079 (lifecycle hook), 0080 (upstream percentile fix) and 0081 (bootstrap close hook). 0006 was split on 2026-10-22: logic to `perf/AsyncPathRecompute`, refusal overrides removed, `PathfindingContext` guard to MC 0082.
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
  allocation patches are covered as of 2026-10-20 (checked against the test tree): 0009 by
  `ScratchReentrancyTest` (Vec3 arithmetic, upstream float threshold, single-field publication),
  0010 by `RandomTickPosEscapeTest` (the capture points that must copy the reused random-tick
  position: `ScheduledTick`, `CraftBlock.at`, the `BlockPos` neighbour lookups) plus
  `ScratchBufferConfinementTest` (no `ServerLevel` scratch field), 0011 by
  `ScratchBufferConfinementTest` (overload signature), 0012 by `IsInWallBoundsTest` and
  `ScratchReentrancyTest`. None replays a live tick; no policy script enforces "test before
  change". 0007 has `GliderSlotsTest` since 2026-10-20.
