# Patch classification

Task K of the former development task list (`DEVELOPMENT-TASKS.md`, no longer in the repository).
Every active SourbyCraft patch, as of 2026-09-27 (Build 47), with its role and what should happen
to it, plus the later additions dated in each section. Sizes are added lines (`+`) in the patch file.

> Historical, as of 2026-09-27: numbers predate the 2026-10-06 rebuild (0019–0023 → 0018–0022,
> closing the 0018 gap) and the 2026-10-13 renumbering, so the 0019/0020 rows are today's 0018/0019.
> On 2026-10-25 the Minecraft features run **0001–0087**, the Paper features **0001–0029** and the
> sourbyapi features 0001–0003. Coverage: 0001–0021 in the first table (historical numbering as
> above); 0022–0038 and 0054 in the table *added 2026-10-25*; 0039–0053 and 0079–0082 in the second
> table; 0055–0078 and 0083–0087 in [patches.md](patches.md) (*Current storage integration* and the
> rows after it). Paper: 0004, 0005, 0012–0015 and 0024 in the table added 2026-10-25; the others in
> [patches.md](patches.md) (0001 `Metrics.java` and 0002 `PaperBootstrap` in its Paper rows, 0003 and
> 0006–0011 in the `CraftScheduler` row, 0029 with MC 0084/0085, 0016–0023 and 0025–0028 by number).
> Current files: `sourbycraft-server/{minecraft,paper}-patches/features/`.

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
| 0006 async pathfinding offload | 44 | PERFORMANCE | SPLIT — DONE 2026-10-22 | Was +181 −1 over five files (`Mob`, `PathNavigation`, `AmphibiousPathNavigation`, `Sniffer`, `PathfindingContext`; 95 added code lines). The offload logic (admission checks, `EntityPathfindEvent`, block and mob snapshots, submission, owner-thread delivery and its staleness checks) moved to `perf/AsyncPathRecompute`; the per-class refusal overrides in `Mob`, `Sniffer` and `AmphibiousPathNavigation` became `AsyncPathRecompute.solveWritesMob` (`instanceof AmphibiousPathNavigation` / `Sniffer`, same set: the frog and turtle navigations inherit, nothing re-overrides); the `PathfindingContext` guard is its own patch (0082). Now `PathNavigation` only (+44 −0, 27 code lines): one flag-gated call in `recomputePath` (`isEnabled()` read first, so the default-off path costs one volatile read as before) and the seams that touch its private state: the pending flag, `sourbyMaxPathLength` / `sourbyVisitedNodesMultiplier` (read lazily after the event, as before), `sourbyIsolatedPathFinder` (flag copy moved to `AsyncPathRecompute.restoreLiveEvaluator`) and `sourbyApplyAsyncPath`. Still `logic` by `patch_surface.py` (over the 10-line hook limit): those seams cannot leave `PathNavigation` without an access transformer. The only rule change: the kick's second `canUpdatePath()` call is dropped (the caller evaluated the same pure predicate on the same thread just before). `AsyncPathRecomputeTest` runs the delivery checks on a real `PathNavigation`. PR #62 (PM-304): one `+` comment line now names `aurora.entity.async-pathfinding` (hand edit, same counts); the commit message still names `perf.ai.async-pathfinding` until the owner's `rebuildMinecraftFeaturePatches` run ([patches.md](patches.md) status note). |
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
| 0021 storage traffic + AWF routing | 9 | INTEGRATION | KEEP | Generic hook: one `RegionFileStorageHooks` delegation per start/finish write, read-data, direct read/write, constructor attachment, close and flush site. AWF branches, counters, codec handoff and attachment live in the ledgered owned adapter. Upstream fallback and lock boundaries stay in `RegionFileStorage`; scan routing is introduced separately by 0053. `patch_surface` = hook (9 added / 0 removed code lines). |

There is no 0018 in the snapshot above; the number was never reused. (Today's numbering has no gap:
Minecraft features are 0001–0087 contiguous on 2026-10-25.)

### Minecraft feature patches added after this snapshot (0039–0053, summarised 2026-10-20; 0079–0081 split from 0013 and 0082 split from 0006 on 2026-10-22)

Classified by subject and the hunks' role as reported in the patch notes, not by a fresh hunk-by-hunk
review; added lines are `+` lines of the patch file today. Dispositions are provisional.

| Patch | + lines | Category | Disposition | Reason |
| --- | --- | --- | --- | --- |
| 0039 immutable mob snapshot for async pathfinding | 150 | PERFORMANCE | KEEP; MOVE TO SOURBY SOURCE (partial candidate) | The evaluator `prepare(region, snapshot)` edits are algorithm changes and stay ([compat-boundary.md](compat-boundary.md) §1.2); the snapshot-capturing bodies are extraction candidates (M-2). `MobPathSnapshotTest`. 2026-10-22: its `PathNavigation` hunks (snapshot capture in the kick) and the Creaking `sourbyAsyncSolveSafe` override left with the 0006 split (the capture is in `perf/AsyncPathRecompute`); +178 −79 -> +150 −62. PR #62 (PM-238), a hand edit of one `+` line (same counts): the swim evaluator's `getPathType` passes the `subject` field instead of `subject()`, so it works outside a solve as vanilla did; needs the owner's `applyAllPatches` / `rebuildMinecraftFeaturePatches` ([patches.md](patches.md) status note). |
| 0040 consume rejected network frames | 4 | SECURITY | KEEP | Hook only; the logic is `security/SecurityGuards`. |
| 0041 proxy-aware admission keys | 15 | SECURITY | KEEP | Hook for the admission rate limiter. |
| 0042 snapshot the creaking home position for async pathfinding | 35 | PERFORMANCE | KEEP | Extends the 0039 snapshot to `Creaking`. 2026-10-22: no longer removes the Creaking override (0039 no longer adds it); +35 −12 -> +35 −4. |
| 0043 time region-file chunk saves for `/perf storage` | 16 | INTEGRATION | KEEP | Telemetry hook around `moonrise$finishWrite`; the recorder is `perf/RegionFileSaveLatency` (`RegionFileSaveLatencyHookTest`). |
| 0044 read the horizontal speed from one field | 15 | PERFORMANCE | KEEP | Fixes a cross-thread tear found in the 0009 scratch (DEV-A); reworked by 0046. |
| 0045 lock-free region task queue depth and late-tick counts | 71 | INTEGRATION | KEEP | `LongAdder` telemetry read by `/perf scheduler` (`RegionTaskQueueDepthTest`, `TickLateStartCounterTest`); a split of telemetry from the queue code is a candidate. |
| 0046 horizontal-movement predicate as a volatile boolean, float threshold | 17 | PERFORMANCE | KEEP | DEV-A re-review fix: atomic publication and the vanilla `1.0E-5F` threshold (`ScratchReentrancyTest`). |
| 0047 invalidate in-flight async paths with a navigation generation | 23 | PERFORMANCE | KEEP | DEV-A F3 fix: a second `stop()` or a retarget can no longer re-arm an old solve (`AsyncPathPatchShapeTest`, `AsyncPathValidityTest`). 2026-10-22: the token/check lines moved with the kick to `perf/AsyncPathRecompute`; the patch keeps the field, getter and six bumps (the delivery bump is now in `sourbyApplyAsyncPath`); +29 -> +23, 10 code lines, `logic` only because none of them calls `dev.iyanz`. |
| 0048 unloaded chunks are not loaded for the snapshot's `*IfLoaded` reads | 33 | PERFORMANCE | KEEP | Restores vanilla's `null`/BLOCKED answer for unloaded chunks (`SnapshotPathRegionTest`). |
| 0049 time how long region tasks wait in their queue | 4 | INTEGRATION | KEEP | Hook only (3 code lines): stamp on first `tryPush`, record before `executeTask` runs the task; the recorder is `perf/RegionTaskWaitLatency` (`RegionTaskWaitLatencyHookTest`). Shown in `/perf scheduler` (`PerfTelemetryRows.renderTaskWait`; "No view reads it yet" when this row was written). |
| 0050 time region-file chunk loads for `/perf storage` | 15 | INTEGRATION | KEEP | Telemetry wrapper around the region-file part of `moonrise$readData` (found-data reads and failures; the external-stream retry calls the inner method so a retry stays one load); recorder `perf/RegionFileLoadLatency`, same ring as 0043 (`RegionFileLoadLatencyHookTest`). Shown in `/perf storage` (`PerfTelemetryRows.renderLoads`; "No view reads it yet" when this row was written). |
| 0051 count chunk lifecycle transitions | 8 | INTEGRATION | KEEP | Hook only (8 code lines): first statement of each `BaseChunkSystemHooks` lifecycle callback (holder create/delete, FULL in/out, ticking in/out, entity ticking in/out) counts into `perf/ChunkLifecycleCounters` (since boot, all worlds; `ChunkLifecycleCountersTest`, `ChunkLifecycleHookTest`). Shown in `/perf chunks` (`command/PerfTelemetryRows`); overhead not benchmarked. |
| 0052 count entity ticks per world in the region entity loop | 4 | INTEGRATION | KEEP | Hook only (4 code lines): `RegionizedWorldData.sourbyEntityTicks` (plain int, region-thread owned) is zeroed before `ServerLevel.tick`'s `forEachTickingEntity` loop, incremented right before each `guardEntityTick(tickNonPassenger)`, and after the loop `perf/EntityTickCounters.regionTicked(bukkitName, count, loop nanos)` records once per region loop (per world since boot: ticks, loops, loop time, largest single-region loop; passengers and frozen entities not counted). Shown in `/perf entities`. `EntityTickCountersTest`, `EntityTickHookTest` (bytecode order). Overhead not benchmarked. |
| 0053 structure scans read AWF once and fail fast while the store is failing | 1 | INTEGRATION | KEEP | Adds the AWF scan branch through one `RegionFileStorageHooks.scanChunk` delegation. The owned adapter calls `AwfRegionStorage.scanRead` once: no fence, refused with a stackless `ScanRefused` for 5 s after a scan read failed or while reads fail fast, only for chunks whose bytes must come from the store. Deleted chunks stop the scan; owned bytes are parsed; untouched chunks fall back to the region file. Vanilla `StructureCheck` answers a throwing scan with CHUNK_LOAD_NEEDED and loads the chunk; the load still waits for the store. `patch_surface` = hook (1 added / 0 removed code lines). `AwfStructureScanTest`. |
| 0079 region tick metrics lifecycle (split from 0013, 2026-10-22) | 9 | INTEGRATION | KEEP | Hook (8 added / 0 removed code lines): six calls in `TickRegions` (destroy, active, inactive, preMerge, preSplit, mergeInto) on the handle's `RegionTickMetricsHolder` (`retireDestroyed`, `activate`, `retireInactive`, `retireMerged`, `retireSplit`, `rotateForMerge`), `ServerLevel.sourbyTelemetryWorldId` and the `WorldShutdownThread` world retire (holder statics). No import; the helper names no engine type (independence policy). Timestamps stay at the call sites. |
| 0080 fix swapped worst-percentile segments in `TickData` reports (split from 0013) | 1 | COMPATIBILITY | KEEP (upstream candidate) | Upstream passes the 1%-worst segment into the record's `segment5PercentWorst` slot and vice versa; the patch swaps the two arguments (+1 −1, classified `fix`). |
| 0081 close SourbyCraft services at shutdown (split from 0013) | 1 | INTEGRATION | KEEP | One `SourbyCraftBootstrap.close()` call after plugin disable / async-task wait in `MinecraftServer` (hook). |
| 0082 async pathfinding: no shared path-type cache off a region thread (split from 0006, 2026-10-22) | 7 | PERFORMANCE | KEEP | `PathfindingContext(level, mob)` uses the regionized `PathTypeCache` only when `TickRegionScheduler.getCurrentRegionizedWorldData()` is non-null (+7 −1, 2/1 code lines). `logic` by the heuristic only because the guard calls the engine accessor directly (no `dev.iyanz` call); a Sourby wrapper would add an upstream-internals ledger entry for no behavioural gain. |

### Feature patches with no other classification row (added 2026-10-25)

Classified from each patch's subject, commit message and hunks (static reading; nothing applied or
built), so the "large Sourby patches inside upstream classes" metric of
[independence.md §14](independence.md#14-independence-metrics) can be tracked. Dispositions are
provisional, as in the table above. Categories as defined at the top; an inherited engine bug fix is
`COMPATIBILITY (engine fix)`.

| Patch | + lines | Category | Disposition | Reason |
| --- | --- | --- | --- | --- |
| MC 0022 greedy shapeless match only for plain ingredients | 5 | COMPATIBILITY (engine fix) | KEEP | The inherited Pufferfish greedy matcher skipped Paper's exact/predicate ingredient logic; it now runs only when every ingredient is plain (exposed by Paper's `PredicateChoice` test, [rebase-log.md](rebase-log.md) 2026-10-05). |
| MC 0023 keep worldgen sculk inside the feature's write zone | 25 | SECURITY | KEEP | Worker safety in parallel world generation: a sculk cursor near the generating chunk's edge read neighbours two chunks away, outside the step's write radius, where another worker may be generating. |
| MC 0024 provision packet plugins before the plugin scan | 3 | INTEGRATION | KEEP | Hook in `Main` (SourbyClip-loaded server path) into `bootstrap/PluginProvisioner` (ViaVersion, ViaBackwards, ProtocolLib) before Paper scans the plugin folder; logic in Sourby source. |
| MC 0025 disable region watchdog by default | 17 | INTEGRATION | KEEP | The inherited region watchdog is opt-in (`-Dsourbycraft.region-watchdog.enabled`, read once before the scheduler starts); disabled ticks keep no watchdog record. `FoliaWatchdogDisabledTest`. |
| MC 0026 measure generic chunk generation stages | 16 | INTEGRATION | KEEP | Optional timing hooks into `perf/ChunkGenerationMetrics` (`aurora.diagnostics.chunk-generation-metrics` or its JVM property, RESTART_REQUIRED, default off: each hook is a constant branch). Overhead not measured. |
| MC 0027 persist world metadata on autosave and save-all | 7 | INTEGRATION | KEEP | Global-owned level data (game time, spawn, game type, difficulty) written on autosave and `save-all`; before, a crash after `save-all` rewound game time to the last clean stop. Related: MC 0056, 0077. |
| MC 0028 rebind early console command sources | 6 | COMPATIBILITY (engine fix) | KEEP | A console line read during startup captured a null level and threw on the first global tick after `Done`; the source is rebound. |
| MC 0029 stage world creation around the initial spawn search | 77 | INTEGRATION | KEEP | With Paper 0014: the spawn search's first chunk loads asynchronously and spawn setup runs on the next global tick, so `/awf create` no longer stalls the global tick (fixed 2026-10-13, [compat-vs-performance.md](compat-vs-performance.md) §2). |
| MC 0030 always complete a world unload's callback | 7 | COMPATIBILITY (engine fix) | KEEP | The world shutdown thread runs the unload through `WorldUnloadCompletion`, so the ticket's callback always gets `SUCCESS` or `FAIL_UNKNOWN`, also when the unload throws (since PR #62 an unconfirmed AWF commit is logged and answered `FAIL_UNKNOWN` without stopping the server). `WorldUnloadCompletionTest`. |
| MC 0031 keep save tickets across region merge and split | 16 | COMPATIBILITY (engine fix) | KEEP | A region holding an unconsumed save ticket that merged or split reported the save complete although its chunks were not saved; the ticket now moves to the merge target or is split. `SaveTicketRegionChangeTest`. |
| MC 0032 drop cancelled tasks when splitting a region task queue | 6 | COMPATIBILITY (engine fix) | KEEP | A cancelled queued task whose section had unloaded made a later queue split throw. |
| MC 0033 drain region work and close storage when a world unloads | 85 | INTEGRATION | KEEP | Hooks in `WorldShutdownThread`, `RegionizedTaskQueue` and `FoliaRegionScheduler` into the engine's `WorldUnloadDrain`: entity schedulers retire, region scheduler and queued tasks are cancelled, bridged tasks released, storage closed. `WorldUnloadDrainTest`, `WorldUnloadEntityRetirementTest`. |
| MC 0034 security guard hooks | 35 | SECURITY | KEEP | Hooks (10 files) into `security/SecurityGuards`: NBT limits scoped to network decode, native codec limits kept, LOG mode by default. `PacketDecoderHookTest`. |
| MC 0035 hand code-of-conduct acceptance to the global thread | 1 | COMPATIBILITY (engine fix) | KEEP | The next configuration tasks (player-data read, world joining) started on the Netty event loop. |
| MC 0036 connection rate limit hook | 10 | SECURITY | KEEP | Observes TCP accepts and login starts before expensive initialization; closes only when the operator enables KICK for admission. |
| MC 0037 no DNS lookup for handshake addresses | 13 | SECURITY | KEEP | Handshake and forwarded addresses are parsed as IP literals: no synchronous DNS on the Netty event loop. `HandshakeAddressesTest`. |
| MC 0038 separate NBT depth from codec bias | 4 | SECURITY | KEEP | Observes real NBT container nesting without Paper's codec depth; the native combined limit is unchanged. |
| MC 0054 region watchdog prints the elapsed time with one unit | 2 | BRANDING | KEEP | Log text only. `WatchdogMessageTest`. |
| Paper 0004 yellow SourbyCraft logger prefix on the console | 5 | BRANDING | KEEP (squash candidate with 0016) | `log4j2.xml`; Paper 0016 replaces its `%style` line ([patches.md](patches.md)). |
| Paper 0005 report the global tick TPS off any region | 6 | COMPATIBILITY | KEEP | `Bukkit#getTPS` off any region answers from the global tick's data (`RegionizedServer.getGlobalTickData`, a recorded scheduler patch site) instead of throwing. |
| Paper 0012 reopen bridge admission when a plugin is enabled again | 1 | COMPATIBILITY | KEEP | Hook: re-enabling the same plugin instance reopens the Aurora Bridge admission that disable closed; native plugins and quarantine unaffected. |
| Paper 0013 record per-plugin onLoad and onEnable wall time | 5 | INTEGRATION | KEEP | Hook into `PluginTimings` (`/plugins <name>`, `/perf plugins`); `PluginTimingsTest`. |
| Paper 0014 staged createWorld that prefetches the spawn chunk | 157 | INTEGRATION | KEEP; MOVE TO SOURBY SOURCE (candidate, not assessed) | With MC 0029: staged world creation in `CraftServer#createWorld`. The largest Paper feature patch (over 100 added lines in one upstream class), so the first candidate for extraction under the §14 metric. |
| Paper 0015 name the build in crash reports from BuildInfo | 1 | BRANDING | KEEP | One `CraftCrashReport` line from `brand/CrashReportIdentity` (`BuildInfo`) ahead of upstream's `BrandInfo` line; `CrashReportIdentityTest`. |
| Paper 0024 one clear line for a legacy plugin refused while the bridge is off | 6 | COMPATIBILITY | KEEP | `FileProviderSource` skips such a jar with one WARN naming `aurora.bridge.mode`. |

## Minecraft source patches (`minecraft-patches/sources/`)

> Historical (layout before 2026-10-05): these per-file patches became feature patches in the
> migration (`Commands.java` is MC 0020, `RegionFileStorage.java` is MC 0021/0053); today
> `minecraft-patches/sources/` holds only the engine baseline.

| Patch | Category | Disposition | Reason |
| --- | --- | --- | --- |
| `Commands.java` | BRANDING | KEEP | Removes the `/canvas` command tree. |
| `RegionFileStorage.java` | INTEGRATION | KEEP | Generic `RegionFileStorageHooks` delegations (0021), plus the scan delegation (0053). AWF routing and `/perf storage` counters live in the ledgered owned adapter; upstream fallback and lock boundaries stay here. Depends on Moonrise's `RegionDataController` contract. |

## Paper patches (`paper-patches/files/`)

> Historical (layout before 2026-10-05): these became Paper feature patches (`PaperBootstrap` 0002,
> `log4j2.xml` 0004/0016, `Metrics` 0001, the loader gates and `CraftScheduler` 0003 and 0006–0011);
> today `paper-patches/files/` holds only the engine baseline.

| Patch | Category | Disposition | Reason |
| --- | --- | --- | --- |
| `PaperBootstrap.java` | BRANDING | KEEP | Branded boot line from `BuildInfo`. |
| `log4j2.xml` | BRANDING | KEEP | Console logger naming. |
| `com/destroystokyo/paper/Metrics.java` | SECURITY | KEEP | Removes the bStats phone-home. |
| `PaperPluginProviderFactory.java`, `SpigotPluginProviderFactory.java`, `CraftMagicNumbers.java` | COMPATIBILITY | KEEP | Aurora Bridge admission; unchanged behaviour when `aurora.bridge.mode = off`. |
| `CraftScheduler.java` | COMPATIBILITY | KEEP | Bridge routing, cancel, `isQueued`, `isCurrentlyRunning`, `getPendingTasks`. Build 50 (2026-10-22): now Paper feature patches 0003, 0006–0011 (and 0019's refusal line); every site is one call into `bridge/AuroraBridge` / `FoliaSchedulerCompatibility`, behaviour pinned by `CraftSchedulerHooksTest`. `patch_surface.py` before -> after: 0003 logic +39/−3 -> logic +23/−3 (three loader gates + 11-line `bridgeTask` adapter of package-private `CraftTask` state); 0006 logic +6/−0 -> hook +1/−1; 0007 logic +43/−1 -> logic +40/−1 (the LightingLuminol `FoliaGlobalRegionScheduler`/`PaperPluginInstanceManager` port is unchanged); 0008 logic +10/−3 -> hook +2/−2; 0009 logic +6/−0 -> hook +1/−1; 0010, 0011 hook +3/−0 unchanged; 0019 logic +29/−23 unchanged. |

## Canvas patches (`canvas-patches/files/`)

> Historical (layout before 2026-10-05): `canvas-patches/` no longer exists. These are now plain
> edits in the owned engine sources under `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/`
> (`GlobalConfiguration`, `WorldConfig`, `spark/…`); the shipped engine defaults are pinned by
> `UpstreamDefaultTest.ENGINE_DEFAULTS` in `scripts/test_patch_policy.py`.

| Patch | Category | Disposition | Reason |
| --- | --- | --- | --- |
| `GlobalConfiguration.java` | COMPATIBILITY | KEEP | Safer default (`guard-severity: LOG`) and the Sourby config bridge. |
| `WorldConfig.java` | COMPATIBILITY | KEEP | HUD coexistence. |
| `spark/FoliaPlatformInfo.java` | BRANDING | KEEP | One build identity in Spark. |
| `spark/FoliaSparkPlugin.java`, `spark/plugin/FoliaTickStatistics.java` | INTEGRATION | KEEP | Spark reads Sourby tick statistics, config and metadata. |

## Findings

- **Move to Sourby source (3):** 0002, 0005 and 0016 carried whole new classes inside patches.
  Moving the classes shrinks the patches to their hooks and lets the classes be unit-tested
  normally; each move must keep the class in the package the patched upstream code imports
  (`dev.iyanz.aurora.*`). Status 2026-10-25: **0002 and 0016 done** on 2026-10-20 (`AuroraCpu`,
  `AuroraCommandErrors` live in `src/main/java/dev/iyanz/aurora/`, see their rows); **0005 open**:
  `SnapshotPathRegion` is still created by MC 0005 (with MC 0048's `IfLoaded` fix); moving it needs the
  materialized repository and `rebuildMinecraftFeaturePatches` (PM-303, blocked on the toolchain). New
  candidate: Paper 0014 (157 added lines in `CraftServer`).
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
