> **2026-10-05:** Paper became the only upstream. The `canvas-patches/` below are now plain edits in
> the owned sources under `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/` (formerly `io/canvasmc/canvas`), the per-file
> `minecraft-patches/sources/` and `paper-patches/files/` entries (plus `sourbyapi/paper-patches/files/`)
> are the engine baseline inherited from Canvas/Folia; `features/` holds SourbyCraft feature patches. See `dependency-ledger.md` §2. The inventory below is
> kept as recorded.

> Phase 46 follow-up (2026-09-14): patches 0009/0014 and the Mob portion of 0016 below are now
> removed after the [reuse audit](reuse-audit.md); upstream per-call collections
> are restored. Patch 0017 remains with updated context, and 0006 delegates
> completion cleanup to AsyncPathCompletion. The inventory below records the
> earlier build-45 review; consult the follow-up for current dispositions.

# Patch inventory and ownership audit (PRD Phase 1)

> **Historical numbering (updated 2026-10-21):** the inventory table uses the build-45 numbers; the "thirteen patches, 0001-0013" set after action 2 matches today's 0001-0013, and 0014-0044 were added later in that historical snapshot. Current numbered feature-file counts: **83 Minecraft (0001–0083), 25 Paper (0001–0025), 3 sourbyapi (0001–0003)**, counted from `NNNN-*.patch` files on 2026-10-21. Old -> current Minecraft feature patch: 0001-0006 unchanged; old 0007 was folded into 0006; old 0008 -> 0007 (elytra); old 0009 removed (collision scratch lists); old 0010 -> 0008 (Projectile); old 0011 -> 0009 (lastKnownSpeed); old 0012 -> 0010 (random-tick BlockPos); old 0013, 0014, 0015 removed (world-border set, particle list, block-event list); old 0016 -> only its `Level` query overload remains, as 0011; old 0017 -> 0012 (`Entity#isInWall`); old 0018 -> 0013 (custom tick metrics); old 0019 folded into 0013. Current numbers 0014 and up are newer patches. Verify against `sourbycraft-server/minecraft-patches/features/` (`python3 scripts/patch_surface.py`).

Historical build-45 review on `release/26.2-canvas`, classified per PRD section 22 with
the section 87 fields. Counts are added and removed lines in the patch file itself.

## Headline findings

**Historical build-45 finding:** the patch-sprawl premise in PRD section 13 did not hold for
that snapshot: 27 patches totalling 1,636 added lines (not current totals; current counts above). 915 of those are a single new file. The real
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

### Historical `minecraft-patches/features/` — 17 patches (0013 and 0015 removed by action 2)

Current directory count: **83** numbered feature patches (0001–0083), 2026-10-22.

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| 0001 boot hook in `DedicatedServer#initServer` | 000-bootstrap | `DedicatedServer` | +7 | KEEP | None. Exemplary thin hook. |
| 0002 sane default region tick-thread count | 100-runtime | `TickRegions` | +7 −7 | KEEP | Benchmark. Changes what `threads: -1` resolves to, not an explicit setting. |
| 0003 skip provably-capped spawn categories | 200-performance | `NaturalSpawner` | +45 | KEEP | Benchmark. Verify capped-category logic cannot suppress a legal spawn. |
| 0004 POI scan without per-block `BlockPos` | 200-performance | `PoiManager` | +19 −8 | KEEP | Benchmark. |
| 0005 `SnapshotPathRegion` immutable snapshot | 300-region | `PathNavigationRegion` | +161 | KEEP | Foundation for 0006/0007. Region-thread safety review. |
| 0006 async pathfinding: offload periodic path | 500-entity | `PathNavigation` | +66 −3 (current: +44 −0) | KEEP | Default off. Benchmark and threading review before enabling. **Split 2026-10-22:** logic in `perf/AsyncPathRecompute`; the patch keeps one flag-gated call in `recomputePath` and the navigation-state seams; the `PathfindingContext` guard (old 0007) is current MC 0082 (+7 −1); `Mob`/`Sniffer`/`AmphibiousPathNavigation` overrides replaced by `AsyncPathRecompute.solveWritesMob`. |
| 0007 async pathfinding: don't touch regionized state | 500-entity | `PathfindingContext` | +7 −1 | **MERGE** | Fold into 0006. Seven lines of the same feature, meaningless alone. |
| 0008 eliminate elytra glide-slot stream allocation | 200-performance | `LivingEntity` | +17 −2 | KEEP | Benchmark. |
| 0009 reuse collision scratch lists in `Entity#collide` | 200-performance | `Entity` | +18 −3 | KEEP | Reviewed: entity-owned, no escape. Benchmark still missing. |
| 0010 fix `Projectile` tick-ticket typo, drop dead projectiles | *correctness* | `Projectile` | +22 −8 | KEEP | Recategorize — this is a bug fix, not an optimization. |
| 0011 store `Entity#lastKnownSpeed` as three doubles | 200-performance | `Entity` | +33 −5 | KEEP | Benchmark. |
| 0012 reuse `BlockPos` in `ServerLevel` random tick | 200-performance | `ServerLevel` | +20 −1 | KEEP | Reviewed: method-local, shares nothing. |
| 0014 reuse `ParticleOptions` scratch list | 200-performance | `LivingEntity` | +23 −4 | **REWORKED** | Published the buffer into `SynchedEntityData`; fixed to copy-on-change. |
| 0016 reuse `ItemEntity` scratch list in `Mob#aiStep` | 200-performance | `Mob`, `Level` | +33 −2 | KEEP | Reviewed: mob-owned, filled in place. |
| 0017 inline AABB, reuse `MutableBlockPos` in `isInWall` | 200-performance | `Entity` | +46 −17 | **REWORK** | Needed three follow-up fixes after landing. Add a regression test. |
| 0018 custom tick metrics (current 0013, 0079–0081) | 100-runtime | `TickData`, `TickRegionScheduler` (0013); `TickRegions`, `ServerLevel`, `WorldShutdownThread` (0079); `TickData` (0080); `MinecraftServer` (0081) | +9 −11; +9 −2; +1 −1; +1 −0 | KEEP | **Split 2026-10-22** (was one patch, +97 −14 at HEAD 6176d517). 0013 = handle wiring only: `TickData` non-final, five windows are `perf/RegionTickDataView` (shared-owner queries moved out of `TickData`), one start + one completion call; removed lines are one-for-one statement replacements, so still `logic` (9/11 code lines). 0079 lifecycle = hook (8/0) into `perf/RegionTickMetricsHolder`; 0080 upstream percentile-order fix; 0081 one bootstrap-close call (hook). Same final sources except `TickData`'s body is upstream again. |
| 0019 close lifecycle-owned runtime services | 100-runtime | `MinecraftServer` | +1 −1 | **MERGE** | One line. Belongs with 0018's lifecycle wiring. |

### Current storage integration (2026-10-22)

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| storage traffic counters + AWF routing (minecraft 0021) | 100-runtime | `RegionFileStorage`, Moonrise `RegionDataController` | +9 −0 | KEEP | Generic hook: one `RegionFileStorageHooks` delegation per affected I/O/attachment site; AWF routing, counters, codec handoff and attachment live in the ledgered owned adapter. Upstream fallback and locks remain. Scan routing is in 0053. |
| structure scans read AWF once and fail fast while the store is failing (minecraft 0053) | 100-runtime | `RegionFileStorage.scanChunk` | +1 −0 | KEEP | Introduces the AWF scan branch via `RegionFileStorageHooks.scanChunk`: unchanged single `scanRead`, refusal propagation, deletion short-circuit, owned-byte parsing and untouched-chunk region-file fallback. |

### Historical `minecraft-patches/sources/` — 1 patch

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| `Commands.java` remove `/canvas` command tree | 900-branding | `Commands` | +4 −1 | KEEP | Paired with the `GlobalConfiguration` change; note the dependency. |

### Historical `canvas-patches/` — 4 patches

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| `GlobalConfiguration` rebrand + drop build-status broadcast | 900-branding | Canvas config | +18 −10 | **SPLIT** | Two unrelated changes: a logger rename and removing a runtime broadcast. |
| `WorldConfig` rebrand + disable Canvas TPS bar | 900-branding | Canvas config | +9 −3 | KEEP | A section 23 duplicate-feature resolution; the reasoning is in the patch. |
| `FoliaSparkPlugin` wire Spark to `MetricsRuntime` | 700-api | Canvas Spark bridge | +3 −2 | KEEP | Exemplary integration: three lines, all delegation. |
| `FoliaTickStatistics` reimplement on Sourby telemetry | 700-api | Canvas Spark bridge | +61 −78 | KEEP | Net −17 lines. Satisfies section 81, one metrics source. |

### Historical `paper-patches/` — 3 patches

| Patch | Category | Upstream dependency | Lines | Status | Action |
| --- | --- | --- | ---: | --- | --- |
| `Metrics.java` remove bStats phone-home | 800-security | Paper metrics | +24 −13 | KEEP | None. |
| `PaperBootstrap` launch lines (paper 0002) | 900-branding | Paper bootstrap | +14 −10 | KEEP | Build 49 console: Paper's two bootstrap lines replaced by `brand/LaunchLines.bootstrap(...)` (build identity + runtime); the patch is the call. |
| `log4j2.xml` colour the SourbyCraft prefix | 900-branding | Paper resources | +5 | KEEP | None. |
| `log4j2.xml` Build 49 console layout: time, level, tag columns; plain file lines; repeat filter (paper 0016) | 900-branding | Paper resources | +14 −14 | KEEP | One pattern per appender; the `sc*` converters in `src/log4jPlugins` (`%scLogger`, `%scLevel`, `%scHighlight`, `%scDim`, `%ansiIfTerminal`, `%plainText`, `RepeatedMessageFilter`) write escape codes only on a real terminal. Removes 0004's `%style` line; the two could be squashed. See `docs/AURORA-UX.md`. |
| data command ownership (minecraft 0069) | 300-correctness | `DataCommands`, `StorageDataAccessor`, `BlockDataAccessor` | +93 −63 | REVIEW | Restores `/data … storage` through `command/DataCommandThreading`: storage-only commands and storage suggestions run on the global tick; a mixed storage + block/entity `modify … from` reads the source on its owner, copies it, and writes the target on its owner. Storage touched from a region by `/execute … storage` or `/function … with storage` is a command error. Console/RCON block data is handed to the owning region again (the block entity is looked up there); regions refuse an unowned block before any world access. Inline runs return the real result; handed-off runs return 0. See `persistence-save-paths.md`. |
| global-tick autosave of the world border, random sequences, `/data storage` and `level.dat` (minecraft 0056) | 300-correctness | `RegionizedServer` (2 call sites) | +11 −0 | KEEP | Persistence: these were written only at a clean stop (Folia/Canvas). Logic in `world/WorldMetadataAutosave` (per-type `saveSpecific`, re-dirty on a failing encode); ledger in `persistence-save-paths.md`. |
| `MinecraftServer.processQueue` fails loudly (minecraft 0060) | 300-correctness | `MinecraftServer` (constructor) | +1 −0 | KEEP | Nothing drains the queue under region threading; `UndrainedQueue` throws and logs the caller once. Logic in `guards/UndrainedQueue`; ledger `inherited-guards.md`. |
| owned refusal messages for the main-thread-only server calls (minecraft 0061) | 300-correctness | `MinecraftServer` (14 throws) | +14 −14 | KEEP | Same refusals, message now names the alternative. Text in `guards/RegionRefusal`. |
| player-collision team on the global tick + Conversation input on the player's region (minecraft 0062) | 300-correctness | `PlayerList`, `ServerGamePacketListenerImpl` | +4 −19 | KEEP | `guards/PlayerCollisionTeam`, `guards/ConversationInput`; replaces three Folia-disabled blocks and a throw. |
| End-platform `PortalCreateEvent` + per-region ticking block-entity count (minecraft 0063) | 300-correctness | `EndPlatformFeature`, `TickRegions.RegionStats` | +9 −1 | KEEP | `guards/EndPlatformEvents` gate (entity known and owned), `guards/RegionStatistics`. |
| scheduled functions on the global tick (minecraft 0065) | 300-correctness | `RegionizedServer`, `Commands`, `ScheduleCommand` | +8 −5 | REVIEW | `world/ScheduledFunctionTicker`: drain per-level timers after game-time advance, isolate callback exceptions, register `/schedule` and serialize queue mutations/completions on global; [semantics and evidence](datapack-functions.md). |
| owned datapack functions on the global tick (minecraft 0066) | 300-correctness | `ReloadableServerResources`, `Commands`, `ServerFunctionManager`, `RegionizedServer` | +7 −3 | REVIEW | `world/DatapackFunctions` + `FunctionLifecycle`: function library loads again, `/function` + `/return` registered (`/reload` not), `#minecraft:load` once per published library and `#minecraft:tick` behind `aurora.world.tick-functions` (LIVE, default off) from the global tick; [contract](datapack-functions.md). |
| scheduled functions on the global tick (minecraft 0065) | 300-correctness | `RegionizedServer`, `Commands`, `ScheduleCommand` | +8 −5 | REVIEW | `world/ScheduledFunctionTicker`: drain per-level timers after game-time advance, isolate callback exceptions, register `/schedule` and serialize queue mutations/completions on global; [semantics and pending validation](scheduled-functions.md). |
| detached persistent-ticket autosave (minecraft 0067) | 300-correctness | `RegionizedServer`, `TicketStorage`, `ChunkHolderManager`, `SavedDataStorage` | +71 −6 | REVIEW | `world/WorldStateAutosave`: copy serialized ticket fields under existing section locks, encode on global and chain existing I/O; raids/PDC remain shutdown-only. Source change only; not compiled, no Gradle. [Persistence ledger](persistence-save-paths.md#5-feature-patch-0067-periodic-persistent-ticket-snapshots). |
| one wandering-trader spawner per world on global (minecraft 0068) | 300-correctness | `RegionizedServer`, `RegionizedWorldData`, `ServerLevel`, `WanderingTraderSpawner`, `WanderingTraderData` | +31 −50 | REVIEW | `world/WanderingTraderCoordinator`: one persisted world countdown (counts down by the ticks game time advanced), world-wide player selection and owner handoff; a spawned trader always counts as the spawn; removes the dead region-local trader data. [Semantics and validation](wandering-traders.md). |
| AWF shutdown failure fails the stop (minecraft 0071) | 100-runtime | `MinecraftServer` (the 0019 line) | +1 −1 | REVIEW | `AwfEngine.closeAll()` returns the storages AWF could not confirm at shutdown (failed, refused by `aurora.awf.shutdown-deadline-ms`, or sent and unanswered, counting those closed by their world's region save); non-zero sets the existing `abnormalExit`, so the process exits 70 after the `AWF-SHUTDOWN-INCOMPLETE` alert. Deadline logic is Sourby-side (`AwfShutdownBudget`, `AwfShutdownReport`, `RedisClient.deadline`); the budget starts in `SourbyCraftBootstrap.close`, no patch. Tests: `AwfShutdownDeadlineTest`, `RedisShutdownDeadlineTest`. |
| save-all reports AWF durability (minecraft 0072) | 300-correctness | `SaveAllCommand` (engine-baseline body) | +1 −1 | REVIEW | One call replaces the unconditional `commands.save.success`: `command/SaveAllFeedback` sends exactly the vanilla success when no AWF storage is open; otherwise a pending line, then for `save-all flush` the vanilla success only after every `AwfRegionStorage.flushBarrier()` confirmed a commit (waited for off the region threads, chunk I/O idle first via `RegionIoQueue`), else a failure naming each storage and its dirty count (`awf/AwfFlushReporting`, audit D4). Tests: `AwfFlushReportingTest`. |
| owned command blocks under region rules (minecraft 0055) | 300-correctness | `AbstractCommandExecution`, selectors, clone/fill/fillbiome, block data, command-block entities | +97 −28 | KEEP | Logic in `commandblock/` (`CommandBlockRegions`, owned scope); [command-blocks.md](command-blocks.md). |
| owned sync teleport on the owning region (minecraft 0057, paper 0017) | 300-correctness | `Entity`, `ServerPlayer`; `CraftEntity`, `CraftPlayer` | +6 −0; +11 −2 | KEEP | Logic in `entity/teleport/` (`OwnedTeleport`, `TeleportRoute`); Bukkit sync `teleport` routed before Paper's checks. |
| `PlayerSpawnLocationEvent` on the global tick (minecraft 0058) | 300-correctness | `PrepareSpawnTask` | +14 −0 | KEEP | Logic in `world/SpawnLocationEvents`; replaces Folia's `if (false && ...)`. |
| plugin API types from `dev.iyanz.sourbycraft.api` (minecraft 0059, paper 0018, sourbyapi 0001) | 700-api | 7 MC engine/vanilla files; `CraftServer`, `CraftWorld`; 7 Bukkit/Paper API files | +15 −0; +5 −0; +12 −0 | KEEP | Imports only: the baseline uses simple names, these patches add the `dev.iyanz.sourbycraft.api` imports; `io.canvasmc` removed with no shim (see `sourbyapi/README.md`). |
| ender-pearl `PlayerTeleportEvent` (minecraft 0064) | 300-correctness | `ThrownEnderpearl` | +5 −72 | REVIEW | Logic in `entity/teleport/PearlTeleport` + `PearlTeleportFlow` over the Aurora `RegionOwnership`/`RegionChunkLoading` contracts; test-plugin `PearlTeleportProbe`. |
| Aurora Bridge `CraftScheduler` sites (paper 0003, 0006, 0007, 0008, 0009, 0010, 0011) | 100-runtime | `CraftScheduler` (+ 0003: the three loader gates; 0007: `FoliaGlobalRegionScheduler`, `PaperPluginInstanceManager`) | `CraftScheduler` net +30 −3 (was +68 −3) | KEEP | Build 50 (2026-10-22): each site is one call into `bridge/AuroraBridge` (`cancelTask`, `cancelTasks`, `currentlyRunning`, `knows`, `pendingBukkitTasks`, `submitIfBridged`, `withBridgedWorkers`, `idInUse`, `runBukkitTask`, `targetRegion`, `targetEntity`) or `bridge/FoliaSchedulerCompatibility` (`isGlobalCallback`, `bridgeCancelled`); 0003's `bridgeTask` adapter stays because it reads package-private `CraftTask` state. `patch_surface.py`: 0006, 0008, 0009 logic -> hook; 0003, 0007 stay logic (0003 +39 −3 -> +23 −3; 0007 +43 −1 -> +40 −1); 0010, 0011 hook unchanged. Tests: `CraftSchedulerHooksTest`. |
| owned refusal messages, off-region `getAverageTickTime`, `World#getTickableTileEntityCount` (paper 0019) | 300-correctness | `CraftServer`, `CraftWorld`, `CraftScheduler`, `CraftServerTickManager`, `PaperDatapack`, `MCUtil`, `RestartCommand` | +29 −23 | KEEP | `guards/RegionRefusal`, `guards/RegionStatistics`; refusals still match the bridge's `ViolationClassifier`. |
| SourbyCraft brand id and fallback, bStats names (paper 0020, sourbyapi 0002) | 900-branding | `ServerBuildInfoImpl`, `Metrics`; `ServerBuildInfo` | +6 −6; +5 −3 | KEEP | `BRAND_CANVAS_ID` removed with no shim, `BRAND_SOURBYCRAFT_ID` added (`sourbyapi/README.md`); bStats names only, nothing is submitted (paper 0001). Owner decision 2026-10-21. |
| retired entity scheduler runs the retired callback (paper 0021) | 300-correctness | `FoliaEntityScheduler` | +3 −1 | REVIEW | ENG-3 follow-up: a Bukkit entity task is its own run and retired callback and chose by `entity.isRemoved()` alone, so `WorldUnloadDrain` (MC 0033), which retires schedulers of saved, not removed, entities, ran every pending body on the shutdown thread (test-plugin unload fixture: 16 far-future bodies ran, 0 retired callbacks). Now `isRemoved() \|\| taskScheduler.isRetired()`: exactly the retired callback or nothing, never the body, no fast-forward. Tests: `WorldUnloadEntityRetirementTest` (real `EntityScheduler`/`FoliaEntityScheduler`, bytecode + patch shape). |
| legacy `PlayerPreLoginEvent` on the global tick (minecraft 0073) | 300-correctness | `ServerLoginPacketListenerImpl` | +4 −11 | REVIEW | Logic in `guards/LegacyPreLogin` (+ `guards/BoundedHandoff`): fired after the async event only with listeners, on the global tick while the authentication thread waits (10 s + 10 s; unanswered = login refused); replaces Folia's `if (false && ...)` and the undrained `processQueue` Waitable. |
| server-wide `ServerTickManager` state on the global tick (minecraft 0074, paper 0023, sourbyapi 0003) | 300-correctness | `ScheduledHandleTickState`, `RegionizedServer` (engine), `ServerPlayer`, `ServerGamePacketListenerImpl`; `CraftServerTickManager`; `ServerTickManager` | +20 −5; +15 −13; +7 −2 | REVIEW | Logic in `guards/ServerTickControl`: the state is vanilla's own `ServerTickRateManager` (one source of truth), advanced once per global tick from the global tick handle's `tickRegion` (after it is marked ticking), copied by each region at its tick start; vanilla's own state/step broadcasts are off (they carried only the server-wide freeze and overwrote a paused region's state): `ServerTickControl` has each player's owner send its region's combined state and sends the step count, after releasing its lock; reads are lock-free snapshots (defaults before the server exists, mutations refused); vanilla and engine rate set together under the lock; `endTickWork` after each sprinted global tick; `CraftServerTickManager` delegates instead of refusing (`isFrozen(Entity)`: region + server-wide state on the owner, server-wide off it); push/move checks honour freeze again; `@Unsupported` dropped. `/tick` stays unregistered. |
| legacy `PlayerChatEvent` / sync `ChatEvent` on the sender's region (paper 0022) | 300-correctness | `ChatProcessor` | +12 −33 | REVIEW | Logic in `guards/LegacyChat`, decided by the calling thread: inline on the owning thread; from the async chat thread the player's entity scheduler with a bounded wait (5 s + 5 s); from a non-owning tick thread a continuation on the player's next tick (no wait). The message is sent only after a completed event; not delivered / overran = dropped, never sent late (WARN, rate-limited). A failure in the non-owning-tick-thread continuation (caller already returned) is logged once at ERROR with the listening plugins and counted; `VirtualMachineError` is never swallowed. |
| Aurora wording for the region scheduler start line (minecraft 0075) | 900-branding | `TickRegions` (engine baseline) | +1 −1 | KEEP | `Initialised EDF Folia scheduler with initial N target thread(s)` -> `Initialised Aurora region scheduler (EDF) with initial N target thread(s)`; text only, scheduler type and thread count unchanged; no script/test reads the line. Build 48 gate 1 (startup log). |
| hold a stop requested during startup until startup ends (minecraft 0076) | 300-correctness | `MinecraftServer`, `DedicatedServer` | +13 −0 | REVIEW | Logic in `startup/StartupStopGate`: a stop the server thread asks for before startup has ended (`Bukkit.shutdown()` from `onEnable`) is only marked; startup goes on as on Paper, `initServer` then skips query/rcon, the startup save and the started notification, and `runServer` logs `Startup stopped: ...` instead of starting the region scheduler and `Done (`, then runs the normal stop (one save, storage open). Before: the region shutdown thread ran concurrently with the rest of startup (`ClosedChannelException` flushes, `Done (` after `Stopping server`, or `System.exit` mid-startup). A startup crash after such a request still runs the stop; stops from other threads unchanged. Evidence `docs/development/stop-during-startup-2026-10-22.md`. |
| write the periodic level.dat off the global tick (minecraft 0077) | 300-correctness | `MinecraftServer` | +1 −0 | REVIEW | Hook in `saveGlobalData`: `WorldMetadataAutosave.awaitLevelDatWrites()` before the shutdown `level.dat` write. Logic in `world/WorldMetadataAutosave`: the periodic global autosave builds the `level.dat` tag on the global tick and writes the file on one bounded daemon thread (`SourbyCraft level.dat writer`), skipping while a write is outstanding; `save-all` still writes on the caller after it. Before: the 0056 periodic write ran `write0` on the global tick and held it for a whole disk stall (57-60 s). Evidence `docs/development/region-io-blocking-2026-10-22.md`. Local number; renumber on top if another 0077 lands first. |
| console section rules before plugin load, world preparation and the post-world enable pass (minecraft 0078) | 900-branding | `DedicatedServer#initServer` (2 lines), `MinecraftServer#loadLevel` (1 line) | +3 −0 | KEEP | Three one-line calls into `brand/ConsoleSections` (`plugins()`, `worlds()`, `pluginsPostWorld()`); each never throws. Build 49 console. |
| `MinecraftServer.currentTick` again, as a global-tick counter (minecraft 0083) | 700-api | `MinecraftServer`, `RegionizedServer` (engine) | +2 −0 | REVIEW | Paper's `public static int currentTick`, removed by the region-threading baseline, is declared again (`volatile`) so plugins that link it (`getstatic`, FastAsyncWorldEdit 2.15.4 and 2.16.1 `v26_2` adapter) load; `bridge/LegacyServerTick.globalTick()` advances it once per global tick at the start of `tickRegion`. Not a region tick: approximate from region/async threads; no server code reads it (Paper's former readers stay commented out). [Compat note](../development/fawe-currenttick-compat.md). Tests: `LegacyServerTickTest`. |
| world settings on the startup thread before regions tick (paper 0025) | 300-correctness | `CraftWorld` | +12 −12 | REVIEW | The 12 world-settings setters (time x2, weather x5, hardcore, ticks-per-spawn, spawn limit, game rule x2) call `guards/WorldSettingsOwner.ensure` instead of `RegionizedServer.ensureGlobalTickThread`, same messages: before the region scheduler starts (`TickRegions.hasStarted()` false; plugins enable before `RegionizedServer.init`) the server's running thread owns them, as on Paper; afterwards the global tick only (unchanged); the shutdown thread always. Force-loaded chunks and metadata stay global-tick-only. Found with Multiverse-Core 5.8.1 (per-world config refused at enable). Ledger: `F-GLOBAL-TICK` 29 -> 17. Tests: `WorldSettingsOwnerTest`. |
| server GUI icon (minecraft 0070) | 900-branding | `MinecraftServerGui` | +1 −1 | KEEP | `sourbycraft-logo.png` replaces `canvas-logo.png`. The engine's other Canvas-era names (ticket ids, `aurora:pearls`, `Aurora.*` JVM flags, `Aurora.Distance`, `AuroraVersionFetcher`, `AuroraFileMigration`) were renamed in the engine baseline (no `sourby` text there, manifest regenerated) and the dormant `/canvas` command sources were deleted from it; see `independence.md` §2.2. |

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

### 3. Merge the two fragments — DONE

**Done.** 0007 folded into 0006 and 0019 into 0018; both files removed.

0019 was the clearer case: it rewrote a line that 0018 itself adds, so 0018 could not
be reverted without 0019 breaking, and 0019 alone said nothing. 0007 guarded the
`PathfindingContext` path-type cache that 0006's off-region solve NPEs without, in a
different file but as one feature. 0007's explanation was carried into 0006's message
rather than deleted with the file.

At that historical action-3 checkpoint, the Minecraft feature set was thirteen patches,
renumbered `0001-0013` with no gaps. Current counts (2026-10-22): **82 Minecraft, 24 Paper,
3 sourbyapi** numbered feature patches; the tables above retain their historical numbering.

The gaps this originally left were not sustainable. `rebuildMinecraftFeaturePatches`
renumbers the whole set every time it runs — it regenerates the filenames from the
commit order in `sourbycraft-server/src/minecraft/java`, which is a real git repository
with one commit per patch. Deciding to keep gaps "until a patch-freeze boundary" only
held until the next regeneration, which is a routine operation rather than a rare one.

### 4. Add a regression test for 0017 — DONE

**Done**, in two halves, because one test cannot cover both failure modes.

0017 replaces `AABB.ofSize(eyePosition, w, 1.0E-6, w)` and
`boundingBox.move(-blockX, -blockY, -blockZ)` with scalar arithmetic to drop two
allocations per entity per tick. What shipped broken was the translation: once a
dangling reference to the deleted variable, once bounds left in world space. An
untranslated box makes `isInWall` test the wrong region of space, so suffocation is
decided against blocks the entity is not in — silent, not a crash.

`IsInWallBoundsTest` pins the **contract**: for representative entities and block
coordinates, including negative ones, the inlined scalars equal what `AABB.ofSize` and
`AABB#move` produce. The assertions are exact rather than tolerance-based on purpose —
halving by `* 0.5` and by `/ 2.0` are the same exact power-of-two scaling, and `a - b`
is exactly `a + (-b)`, so any difference is a real change in the formula. If upstream
ever changes how `ofSize` or `move` computes, this fails and says the inlined copy must
follow.

`patch_policy.translated_bounds` pins the **patch**: it extracts the inline
`toCollide` construction and counts the translation per axis, which must be two per
axis — one for each corner. The contract test cannot see the patch drifting, and this
one cannot see upstream drifting; together they cover both directions. Its own tests
check that it detects a box left in world space, a partially translated box, one
corner translated, and a construction split across lines.

### 5. Split the `GlobalConfiguration` patch — NOT POSSIBLE; pinned instead

**This recommendation was wrong, and the count was wrong too.** The patch does four
things, not two: renames a logger, suppresses Canvas's build-channel broadcast, and
changes two operator-facing defaults (`guardSeverity` THROW to LOG,
`logEnderPearlRewriteActions` true to false).

It cannot be split. `canvas-patches/files/` patches are keyed by target file path —
one patch per file — and carry no commit message, only the diff. There is no second
patch for `GlobalConfiguration.java` to move anything into. The original
recommendation was made without checking the layout.

The concern underneath it is real: a default that differs from upstream is only
discoverable by reading a diff. That is addressed by pinning instead. `patch_policy.
upstream_default_changes` extracts every upstream field default any patch redefines,
and `UpstreamDefaultTest` asserts the set matches an approved list carrying the reason
for each. A new default change fails the test until somebody writes down why:

| Field | Upstream | SourbyCraft | Why |
| --- | --- | --- | --- |
| `guardSeverity` | `THROW` | `LOG` | THROW crashes the server when a plugin touches state off-region; production should log. Operators can restore THROW. |
| `logEnderPearlRewriteActions` | `true` | `false` | Logs on every pearl save/load — console spam on an active server. |
| `enableTpsBar` | `true` | `false` | Duplicates the SourbyCraft HUD; section 23 says keep one. |
| `enableRamBar` | `true` | `false` | Same. |
| `LOGGER` (x2) | `CanvasMC`, `CanvasWorlds` | `SourbyCraft` | Console prefix branding. |

Matching required an access modifier rather than treating it as optional: Java locals
cannot have one, and without that the scan reported local initialisers inside rewritten
method bodies as default changes.

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
