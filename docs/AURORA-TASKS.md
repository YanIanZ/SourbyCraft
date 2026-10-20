# Aurora Task Matrix

> **Role:** Aurora task checklist with evidence links. **Status:** Active checklist; priorities live in [TODO.md](../TODO.md). Two baseline/soak ticks in §C disagree with [BASELINE.md](BASELINE.md) — see *Open contradictions* in the entry point.
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md).

This checklist supplements `docs/DEVELOPMENT-TASKS.md`. It does not replace existing unfinished tasks; it groups the next work around Aurora configuration ownership, deep Minecraft optimization, stability, and independence.

Current focus (2026-10-06): persistence and plugin ownership gates in [TODO.md](../TODO.md),
plus the user's new-terrain generation stutter report. Optional generic generation-stage timing
is written, with build/runtime acceptance assigned to Claude; see
[CHUNK-GENERATION-STUTTER.md](development/CHUNK-GENERATION-STUTTER.md).
Paper is the only upstream; former Canvas/Folia engine sources are SourbyCraft-owned.
[Execution contracts](architecture/execution-contract.md) are partially implemented;
an independent Aurora scheduler remains unfinished. Custom Spark viewer work is deferred.

Status:

- `[x]` complete enough to build on
- `[-]` implemented but requires validation/refinement
- `[ ]` not complete
- `[!]` blocked/high risk

---

## A. Aurora architecture

- [x] define Aurora as SourbyCraft architecture, not only codename
- [x] document Sourby-first runtime ownership model
- [x] document direct Minecraft/NMS optimization policy
- [x] document no-auto-tuning rule under Aurora
- [x] define `AuroraConfig` typed root model
- [x] define config lifecycle and reload status model
- [x] Aurora engine package inside the Minecraft tree — `dev.iyanz.aurora.*`, see
  [aurora-engine-package.md](architecture/aurora-engine-package.md)
- [x] expose Aurora architecture/build metadata in `/version` where appropriate — engine, Aurora runtime state, bridge mode, update channel

## B. Aurora configuration ownership

- [ ] add `[aurora.performance]` logical namespace — intentionally absent: no implemented key belongs there (empty namespaces are not added)
- [x] add `[aurora.scheduler]` logical namespace — Resource Governor lane budgets (RESTART_REQUIRED)
- [x] add `[aurora.entity]` logical namespace
- [ ] add `[aurora.chunk]` logical namespace — intentionally absent: no implemented chunk key yet
- [x] add `[aurora.network]` logical namespace — `aurora.network.counters` (LIVE)
- [ ] add `[aurora.memory]` logical namespace — intentionally absent: no implemented memory key yet
- [x] add `[aurora.diagnostics]` logical namespace — `lane-sampling`, LIVE
- [x] migrate `perf.ai.async-pathfinding` to `aurora.entity.async-pathfinding` with legacy read fallback
- [x] never auto-save migrated keys
- [x] mark each config key LIVE / RESTART_REQUIRED / IMMUTABLE_FOR_RUN — every key has a `Setting` constant; see [AURORA-CONFIG.md](AURORA-CONFIG.md)
- [x] report restart-required changes accurately on reload — `aurora.cpu.cores`, `aurora.bridge.mode` and `aurora.scheduler` budgets are reported as restart-required, never counted as applied
- [x] add typed immutable config records/classes
- [x] remove hot-path dotted-string config lookup where present — none remains on a tick path; audit in [AURORA-CONFIG.md](AURORA-CONFIG.md)
- [x] ensure Spark shows SourbyCraft config — `sourbycraft/` group includes `aurora.toml` (provider test); viewer rendering still open below
- [ ] verify Aurora config rendering in Spark web report
- [x] preserve secret filtering requirements

## C. Certified baseline and soak

Carry forward all unfinished baseline work from `docs/DEVELOPMENT-TASKS.md`.

- [ ] certified idle baseline
- [x] 10-player connected baseline — certified both sides of the chunk-worker A/B; see [chunk-workers.md](architecture/chunk-workers.md)
- [ ] 50-player baseline
- [ ] 100-player baseline
- [ ] entity-stress baseline
- [ ] chunk generation/load/unload baseline
- [ ] gameplay network baseline
- [ ] 2h+ concurrency soak
- [ ] post-load heap recovery
- [ ] restart persistence qualification
- [x] rank JFR CPU hot spots — [hotspots-entity-ai.md](architecture/hotspots-entity-ai.md) (ranking only; run not certified)
- [x] rank JFR allocation hot spots — [hotspots-entity-ai.md](architecture/hotspots-entity-ai.md) (ranking only; run not certified)
- [ ] inspect JFR contention and I/O hot spots

## D. Deep Minecraft/NMS optimization

No task in this group is considered complete without before/after evidence.

Started 2026-09-21: [Entity/AI validation](architecture/aurora-task-d-validation.md).
Existing collision/query/tracker profiles are exploratory, not certified baselines.
The tracker range-hoisting candidate crosses plugin callbacks and has been withdrawn;
collision candidate 0019 (feature patch 0018 after renumbering) passes arithmetic checks and ownership review; live integration remains open.

### Entity / AI

- [ ] profile `Entity.tick`
- [ ] profile `LivingEntity.tick`
- [ ] profile `Mob.tick`
- [ ] profile GoalSelector
- [ ] profile Brain/Sensor
- [ ] profile path navigation
- [-] profile collision — exploratory before/after samples; certified comparison pending
- [-] profile entity section queries — exploratory before/after samples; certified comparison pending
- [ ] profile item entity merge/pickup
- [-] profile entity tracker updates — exploratory samples retained; unsafe range-hoisting candidate withdrawn
- [ ] implement first measured Aurora entity/NMS optimization
- [x] region-safety review for every entity/NMS change — 0017/0018/0019 reviewed in [region-safety-review-0017-0019.md](architecture/region-safety-review-0017-0019.md); standing obligation for future entity/NMS patches

### Chunk / World

- [ ] profile chunk holder lookup
- [ ] profile ticket processing
- [-] measure generation latency — optional queue/run timing for generic stages in `/perf chunks`, feature patch 0026 (formerly 0027); built and unit-tested (`ChunkGenerationMetricsTest`, `ChunkGenerationMetricsEarlyConfigTest` 7, `PerfStartupAndChunkHintTest` 3) and booted on the demo with metrics off (RESTART_REQUIRED, default off); not measured on a live server with metrics on; dedicated lighting and full integration remain outside coverage
- [ ] measure load/integration latency
- [ ] measure save latency/backlog — instrumentation exists (`/perf storage`: per-world pending I/O and region-file save p50/p99/max from MC patch 0043, `RegionFileSaveLatencyTest` 8; AWF commit latency); no measurement taken on a booted server under a save workload
- [ ] profile region-file I/O
- [ ] profile player chunk tracking
- [ ] profile chunk packet construction
- [ ] implement first measured Aurora chunk/NMS optimization
- [ ] restart/crash persistence validation

### Network

- [x] instrument packets/sec — `perf/NetworkCounters`, `/perf network` (uncertified; handler cost unmeasured)
- [x] instrument bytes/sec — same, wire bytes after compression/encryption
- [ ] profile encode/decode
- [ ] profile compression
- [ ] profile chunk-send path
- [x] inspect packet allocation/copy count — static code read 2026-10-20 (nothing measured): [network-allocation-copy-audit.md](architecture/network-allocation-copy-audit.md); natives + compression + encryption = 3 pooled buffers and 1 whole-body memcpy per packet per connection, the same packet object is encoded once per viewer, chunk packets are built per player per chunk; not done: any measurement, whether natives load on a given server
- [-] verify Netty event loops remain non-blocking — static audit done 2026-10-20 (DEV-H): [region-io-audit.md](architecture/region-io-audit.md) (H1 fixed by MC 0035, H4/H5 by MC 0037, H2/H3/H6 = [PLUGIN-NETTY-GUIDANCE.md](development/PLUGIN-NETTY-GUIDANCE.md)), [engine-network.md](architecture/engine-network.md) §6, pinned by `BlockingIoBoundaryTest`; no runtime evidence (thread dump, blocked-loop profile, disk-stall test), third-party handlers not covered
- [ ] implement first measured Aurora network optimization

## E. Scheduler / concurrency

- [-] EDF lifecycle upgrade — LeafPile-derived `AuroraEdfScheduler`, the parked EDF patch (`minecraft-patches/parked/0028-…`, held since 2026-10-07, not built); six unexecuted
  regressions, build/boot/ownership/shutdown acceptance assigned to Claude; no independent
  scheduler or speed claim ([source/profile handoff](development/AURORA-EDF-UPGRADE.md))

- [x] inventory all Sourby-owned executors — [executor-inventory.md](architecture/executor-inventory.md)
- [x] audit implicit common-pool usage — [executor-inventory.md](architecture/executor-inventory.md)
- [-] async pathfinding shutdown/cancellation work — unit level done 2026-10-20 (`AsyncPathProcessor.shutdown()`, `AsyncPathCancellationTest` 17, `AsyncPathShutdownTest` 9, `AsyncPathPatchShapeTest`, `AsyncPathRefusalTest`); runtime pending: the patch 0006 lambda and a real `Mob` are not run, cancellation under a real region-thread shutdown order is untested
- [x] complete async path snapshot correctness review — source audit 2026-10-20 (flag stays off): [async-path-snapshot-audit.md](architecture/async-path-snapshot-audit.md), `SnapshotPathRegionAuditTest` 3, `SnapshotPathRegionTest`; the unloaded-chunk divergence (AIR/OPEN instead of null/BLOCKED on `*IfLoaded`) is fixed in MC patch 0048; no flag-on run
- [x] path result staleness test — `AsyncPathValidityTest` (rules used by patch 0006)
- [x] queue saturation test — `AsyncPathQueueTelemetryTest`, `AsyncPathShutdownTest` (saturation refuses, never caller-runs)
- [ ] sync-vs-async path benchmark
- [ ] mob behavior compatibility soak
- [x] collect worker queue depth/latency where useful — `/perf async` (queue depth, wait), `/perf governor` (governed lanes)
- [-] verify no external I/O blocks region execution — static audit [region-io-audit.md](architecture/region-io-audit.md), pinned by `BlockingIoBoundaryTest` (network-file inventory only); Redis chunk-read paths traced statically 2026-10-20 (R1 chunk load on a WORLD_IO thread, which a tick thread parked in `syncLoad` waits on; R2 the structure scan runs on the caller, which can be a region thread via Dolphin, EnderEyeItem, ExplorationMapFunction or LocateCommand: single attempt, each Redis step capped by `timeout-ms`, cached per chunk; R3 other sync `RegionFileStorage.read` callers not traced); the global world-creation `ready.join()` is fixed (SPEC B80/B85); runtime evidence (Redis down/slow with a structure search on a region thread) still open

## F. Memory / GC

- [x] runtime heap metrics foundation
- [x] GC tracker foundation
- [ ] allocation MB/s validation against JFR
- [ ] RSS/cgroup validation
- [x] top allocated classes workflow — `scripts/rank_hotspots.py` `allocation_hotspots` (by class/site/thread, cross-checked against `jdk.ThreadAllocationStatistics`) over a `scripts/profile_server.py` JFR, workflow in [BASELINE.md](BASELINE.md) "Ranking hot spots"; `test_rank_hotspots.py` 37 OK; a certified real-run report does not exist yet
- [x] cache ownership/bounds inventory — [cache-inventory.md](architecture/cache-inventory.md); `AwfWorld.owned` evicts clean chunks at the configured resident limit; dirty chunks stay pinned and can exceed it; limit 0 is unbounded
- [x] player-disconnect retention test — unit level: `HudBarsRetentionTest` 17, `BridgeRetentionTest` 8, `SpeedtestRetentionTest` 4; no heap check on a live server
- [x] chunk-unload retention test — unit level for the AWF layer: `AwfChunkUnloadRetentionTest` 4 (mutation-checked); not covered: Minecraft-side chunk unload (holders, entities, block entities), Redis backend, a live heap comparison
- [x] world-unload retention test — unit level: `AnchorCacheRetentionTest` 6, `RegionMetricsRetentionTest` 4, `AwfEngineRetentionTest` 5, `AuroraWorldsUnloadRetentionTest` 3; not covered: a heap/GC check after a real unload on a running server, the engine's Minecraft-side holders
- [x] completed future/task retention test — bridge one-shot tasks, async path accounting, AWF pending saves (tests named in cache-inventory.md)

## G. Spark / observability

- [x] route Spark tick stats through Sourby metrics
- [x] report SourbyCraft profiler identity
- [x] report Spark platform version through current SourbyCraft build metadata; `Build44` was a historical example, not the current build identity
- [x] provide Sourby config group
- [ ] verify Spark web-viewer config rendering
- [x] add cheap Sourby runtime metadata — `SourbyMetadataProvider` via `createExtraMetadataProvider` ([SPARK.md](SPARK.md#aurora-runtime-metadata)); viewer rendering still unverified
- [x] improve region thread classification — `SparkThreadClass`, `LabelledThreadDumper`, `sourbycraft-threads` metadata (`ThreadCensusMetadataProvider`); `SparkThreadClassTest` 9, `ThreadCensusMetadataProviderTest` 8, `RegionThreadDumperTest` 7; limits in [SPARK.md](SPARK.md) "Thread classification": global tick and region ticks share threads, virtual threads invisible; viewer rendering unverified
- [x] expose useful slow-region context — `/perf region` names the slowest active region (world/region/generation ids, 5 s average and maximum MSPT); coordinates not tracked
- [x] document Spark update procedure — [SPARK.md](SPARK.md#updating-spark)
- [!] replace the upstream viewer text `engine async` with `Aurora Engine`: the current spark upload protocol serializes profiler engine as the fixed `JAVA`/`ASYNC` enum, and spark.lucko.me renders that enum itself; an exact custom label therefore requires a Sourby/Aurora viewer fork or compatible custom viewer layer rather than a server-only metadata patch
- [ ] design Aurora viewer presentation so the primary label is `Aurora Engine` while the underlying implementation (`async-profiler` or Java sampler) remains visible in technical details
- [ ] decide adapter vs deep SourbySpark fork only after requirements are proven

## H. Independence

- [x] inventory engine accesses from `dev.iyanz.sourbycraft.*` — `CanvasConfigBridge` retains its compatibility name and references Aurora engine config; world lifecycle also has engine dependencies ([dependency-ledger.md](architecture/dependency-ledger.md), pinned by `UpstreamDependencyLedgerTest`)
- [x] classify direct accesses as required contract / accidental coupling / removable — ledger classifications
- [x] define minimal region access contract — `execution/region/RegionBackend` ([compat-boundary.md](architecture/compat-boundary.md))
- [x] define minimal scheduler access contract — `execution/OwnerHandoff` ([execution-contract.md](architecture/execution-contract.md))
- [x] define minimal engine config bridge contract — `config/upstream/UpstreamConfigBridge`
- [-] move large Sourby service bodies out of upstream classes — slices done 2026-10-20: `AuroraCpu` (out of MC patch 0002), `AuroraCommandErrors` (0016), `AwfChunkNbt` (0021), `GliderSlots` (0007); remaining: 0005 `SnapshotPathRegion`, 0006/0013 splits, the AWF read/write/scan/flush/attach branches in 0021 (~60 code lines), 0026 timing
- [x] retain direct NMS algorithm patches when external indirection would be worse — policy in [compat-boundary.md §1.2](architecture/compat-boundary.md) and ledger §2.1; applied to patch 0006 (rules extracted to `AsyncPathValidity`, algorithm left in place)
- [x] active build-path dependency inventory — ledger §3–4 and AGENTS.md CI description
- [x] determine whether active CI still requires legacy `sourbypatcher` — no: CI requires the private SourbyPatcher `paper-toolchain` adapter; the legacy Folia patcher is not used
- [ ] clean-checkout reproducibility
- [ ] cached/offline boot validation
- [x] track rebase conflict count — [rebase-log.md](architecture/rebase-log.md) (last bump: 7 patch files needed intervention); recorded per bump from now on

## I. SourbyClip / free-running requirement

- [-] downloader timeout audit — server-side `LibDownloader` has a whole-transfer deadline (10 min) and size bounds; SourbyClip 3.1.2 (`85b4dd70`, pinned in `5a3806f1`) bounds sockets with 30 s connect/read only; open: launcher whole-transfer deadline/size cap (private SourbyClip repo: change, release, re-pin; owner decision) ([bootstrap-download-audit.md](architecture/bootstrap-download-audit.md))
- [-] retry/failure handling — server side: 3 attempts with 2 s then 4 s backoff, `.tmp` always cleaned; unit-tested; the launcher falls back between sources but does not retry a single source (e.g. the Mojang jar); a bounded retry belongs with the whole-transfer deadline work in the private repo
- [-] SHA/cache validation audit — historical utility checks plus SourbyClip staged original-JAR verification/cache preservation; the official pin rolled out (3.1.2, `5a3806f1`); remote cold-download qualification pending
- [-] bounded concurrency audit — SourbyClip 3.1.2 source audit re-verified 2026-10-20 (pool cap `sourbyclip.downloadThreads` 1-16, default 4; close() drains queued tasks); the pin rollout landed (`5a3806f1`); open: launcher whole-transfer bounds (private repo)
- [x] first-boot failure recovery test — unit tests plus CI step "Bootstrap failure recovery and offline boot": no network fails fast with a download error and no partial files, then online reaches Done (green in run 450)
- [x] offline-after-success test — same CI step: third boot with no network reached Done from the cache without downloading (run 450). Mojang public-key lookup fails and is logged; with online-mode=true player auth would of course need the network
- [x] remote repository/fallback documentation — [bootstrap-download-audit.md](architecture/bootstrap-download-audit.md): SourbyClip has Maven mirror fallback; the historical utility manifest's single-URL behavior is a separate surface
- [x] verify core runtime needs no Canvas remote service/API — [dependency-ledger.md §6](architecture/dependency-ledger.md)

## J. Aurora release gate

The checked gates below cite historical commits/runs. They do not validate the current
working-tree changes (bridge, AWF, watchdog or generation metrics); those remain pending
Claude's build/tests/boot and the required release-specific evidence.

- [x] patch regeneration clean — CI run 437 (`994025aa`, 2026-09-26): `applyAllPatches` including the Build 47 Paper patches
- [x] Java tests pass — CI run 437 (`994025aa`, 2026-09-26): `:sourbyapi:test :sourbycraft-server:test :test-plugin:test` (9480 tests, 7 skipped) and 219 Python tests
- [x] boot pass — CI run 437 (`994025aa`, 2026-09-26): boot to `Done (` with the test plugin, both metrics markers, `/tps` `/mspt` spark RAM PERF output markers (single CI boot, not a soak)
- [x] shutdown pass — CI run 437 (`994025aa`, 2026-09-26): `stop`, worlds and player data saved, RegionFile I/O drained, clean exit
- [ ] restart persistence pass
- [ ] representative benchmark report
- [ ] JFR reviewed
- [ ] 2h+ soak pass
- [ ] heap/thread/queue stabilization confirmed
- [ ] no known region ownership regression
- [ ] no known persistence regression
- [x] dependency ledger updated — Build 47 Paper patches, bridge scheduler use, network hook
- [-] attribution/license review complete — Build 47 code is original and upstream edits are identified ([release doc](releases/26.2-build-47-aurora-nexus.md#attribution-and-license-notes-2026-09-27)); the PolyForm-NC vs GPLv3 (Paper server) question for the distributed jar is open for the owner
- [-] README and release notes contain measured claims only — audited 2026-09-27: stale `/perf` and Spark statements corrected; the remaining numeric claims name their workload and link their evidence. Re-audit before tagging

## K. Build 47 Aurora Nexus pillars

See the status table in [releases/26.2-build-47-aurora-nexus.md](releases/26.2-build-47-aurora-nexus.md).
`[-]` here means implemented and unit-tested; several rows below have also booted in CI or on the demo (stated per row) but lack player-workflow or soak qualification.

- [x] compatibility state model and `/plugins` palette (NATIVE/BRIDGED/FAILED/DISABLED)
- [x] capture plugin enable failures for FAILED state
- [-] Aurora Bridge admission (`aurora.bridge.mode`, Paper load-gate patches) — implemented: `aurora.bridge.mode` (`AuroraConfig.BRIDGE_MODE_KEY`, RESTART_REQUIRED), `BridgeRuntime.admit`/`reopenAdmission`, Paper patches 0003 (admit and route) and 0012 (reopen on enable); boots in CI since run 485 and on the demo; bridge package 84 tests green (2026-10-20); open: real-plugin player workflows and soak
- [-] bridge scheduler routing (sync → global region, async → governed `BRIDGE_IO`), cancellation, disable cleanup — unit level (`BridgeRuntimeTest` 35, `BridgeTargetRoutingTest` 9, `BridgeRetentionTest` 8, `BridgeReviewTest` 11); open: real-server evidence beyond the CI synthetic fixture and the demo post-boot sample, and a boot marker for re-enabling the same instance after disable (Paper 0012)
- [-] SAFE-mode violation counting, quarantine, per-plugin telemetry (`/plugins <name>`) — implemented and unit-tested (`BridgeRuntimeTest` quarantine cases, `ViolationClassifierTest` 6, `PluginsCommandTest` 5; `aurora.bridge.quarantine-after` is LIVE); no CI or boot assertion that a plugin actually reaches quarantine (the legacy fixture has one deliberate fatal and is not quarantined by design)
- [-] entity-owner / region-owner routes with real callers — region-owner route for sync tasks scheduled from region context (`sync-route = "caller-region"`); entity-owner route implemented for explicit `EntityTask` (V39, Paper 0011; precedence explicit entity > explicit region > caller-region/global), not inferred for legacy scheduler calls, which name no entity (inferring one is an owner decision); CI fixture asserts a region-context runTask reads a block; open: a real plugin using `EntityTask`/`RegionTask` on a booted server
- [-] run representative legacy plugins through the bridge — synthetic fixture passes in CI; Vault and EssentialsX enabled on the demo panel at `9a15f51` with 0 bridge violations at the post-boot sample, but player workflows and soak remain open
- [x] startup cache mechanics: fingerprints, environment key, per-entry integrity, atomic write
- [x] plugin descriptor index on a bounded STARTUP lane, with boot diagnostics
- [x] class index and compatibility scan (constant pool), dependency graph, startup profile
- [ ] cold vs warm startup benchmark
- [ ] transform output caching — blocked on an owner decision: no plugin bytecode transform exists in SourbyCraft to cache (the only class reader is the read-only constant-pool `BytecodeScanner`, already cached as the compatibility scan; Cherry mixin is external and off by default); a cache with no producer and no cold/warm benchmark would be an unmeasurable claim
- [x] AWF: world roles, atomic generations, `.awf` lazy images, FULL/INCREMENTAL/CHECKPOINT/READ_ONLY, COW instances, metrics
- [-] AWF integration with the engine's chunk load/save — FILE backend under `RegionFileStorage` for worlds in `aurora.awf.worlds` (off by default), `/perf awf`, tombstones; CI and demo-panel boot/read-back/export passed. Not qualified: server-level crash measured only locally (`verify_crash.py --awf` 56/56, 2026-10-13, headless clients); no sustained player load ([aurora-world-fabric.md](architecture/aurora-world-fabric.md#engine-integration-regionfilestorage))
- [-] AWF export back to region files — `aurora.awf.export`, resumable, store kept; unit-tested, CI export boot added
- [-] AWF qualification — storage layer in-process: crash at each commit stage, 200 load/unload cycles, shutdown with a pending commit, concurrent writers, 1/50/250/1000 storages, corrupt object (`AwfQualificationTest`). Open: server-level crash with players, backend disconnect, sustained load measurement, and investigation of the demo panel's 105.5-second initial 529-chunk commit
- [x] AWF backend SPI (`AwfStore`, `AwfBackend`, `aurora.awf.backend`), FILE built in plus a `redis` backend (`awf/redis/RedisBackend`), no fallback on a missing backend — `AwfBackendTest` 5 (unregistered backend fails the load naming it, unlisted worlds keep region files), `RedisBackendTest` 19
- [-] AWF database backends — Redis shipped (`awf/redis/`: own RESP client, Lua atomic commit, single-writer lease; backend-failure fixes `d97fe247`, 2026-10-14, local scenarios only); MongoDB/MySQL none
- [-] SlimeLoader adapter — a Slime v12/v13 importer exists (`awf/world/SlimeImporter`, `/awf import`, `/awf convert` to `.awf`); no live SlimeLoader adapter (implementing ASP's SlimeLoader/SlimeWorld API surface is an owner decision). Untrusted-file limits are done (AWF-9, `ebb9c1fb`: `-Dsourbycraft.awf.slime.max-bytes`, default 256 MiB, checks before allocation; `SlimeImporterTest` 9)
- [x] Resource Governor specification
- [-] governed lanes with reject-not-caller-runs, `/perf governor` — implemented for the lanes that exist (`GovernedLane`: `ThreadPoolExecutor` + bounded queue + `AbortPolicy`; lanes BRIDGE_IO, STORAGE and, since 2026-10-20, PATHFINDING; callers do not run rejected work inline; `ResourceGovernorTest` 10, `UnboundedQueueAuditTest` 3); stays `[-]` because only three lanes are governed (pathfinding rejection falls back to the mob's sync path by design) and the `/perf governor` render itself has no test
- [x] operator-configurable governor budgets (`[aurora.scheduler]`)
- [ ] existing executors under the governor — deferred; each already has its own bound ([executor-inventory.md](architecture/executor-inventory.md))
- [-] network counters: bytes/packets per direction, connections, `/perf network` — implemented and unit-tested (`NetworkCountersTest` 3, `perf/NetworkMetrics`; limits in [engine-network.md](architecture/engine-network.md): zero-player idle numbers only); stays `[-]`: no run with real clients to check counter accuracy against an external measure
- [x] vanilla save-queue / region-file storage metrics — `/perf storage`: chunk-system reads/writes/deletes (counted in the `RegionFileStorage` patch), per-world pending I/O from Moonrise's controllers, and region-file save latency p50/p99/max over 60 s per world/type (MC patch 0043, `perf/RegionFileSaveLatency`; `RegionFileSaveLatencyTest` 8, `RegionFileSaveLatencyHookTest` 3); limits: times only the `.mca` write stage (not serialization/compression or the flush-regions-on-save flush), never AWF worlds; no boot sample of real values recorded and the overhead is not benchmarked

---

## Recommended immediate order

```text
1. Aurora typed config model
2. legacy-safe async-pathfinding config migration
3. Spark/Aurora config verification
4. certified workload baselines
5. JFR CPU/allocation ranking
6. first deep Entity/AI optimization
7. chunk/world measured optimization
8. network measured optimization
9. independence isolation
10. soak + release qualification
```

## Aurora-1 implementation notes

See [AURORA-CONFIG.md](AURORA-CONFIG.md) for precedence, validation, lifecycle, admitted-work
draining, and Spark report limitations. Empty namespaces and unimplemented feature switches
remain intentionally absent. Existing baseline, async safety, and release gates remain open.
