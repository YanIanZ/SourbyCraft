# Aurora Task Matrix

This checklist supplements `docs/DEVELOPMENT-TASKS.md`. It does not replace existing unfinished tasks; it groups the next work around Aurora configuration ownership, deep Minecraft optimization, stability, and independence.

Current focus (2026-09-15): Phase 2 of [Aurora Independent Engine](architecture/AURORA-INDEPENDENT-ENGINE.md),
starting with [execution contract extraction](architecture/execution-contract.md).
Preserve completed config/baseline work; isolate current backend dependencies before introducing
new execution machinery. The first candidate is owner-bound async result delivery, with explicit
owner identity, rejection, retirement, and stale-result validation. Custom Spark viewer work is deferred.

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
collision candidate 0019 passes arithmetic checks and ownership review; live integration remains open.

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
- [ ] measure generation latency
- [ ] measure load/integration latency
- [ ] measure save latency/backlog
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
- [ ] inspect packet allocation/copy count
- [ ] verify Netty event loops remain non-blocking
- [ ] implement first measured Aurora network optimization

## E. Scheduler / concurrency

- [x] inventory all Sourby-owned executors — [executor-inventory.md](architecture/executor-inventory.md)
- [x] audit implicit common-pool usage — [executor-inventory.md](architecture/executor-inventory.md)
- [-] async pathfinding shutdown/cancellation work
- [ ] complete async path snapshot correctness review
- [x] path result staleness test — `AsyncPathValidityTest` (rules used by patch 0006)
- [x] queue saturation test — `AsyncPathQueueTelemetryTest`, `AsyncPathShutdownTest` (saturation refuses, never caller-runs)
- [ ] sync-vs-async path benchmark
- [ ] mob behavior compatibility soak
- [x] collect worker queue depth/latency where useful — `/perf async` (queue depth, wait), `/perf governor` (governed lanes)
- [x] verify no external I/O blocks region execution — static audit [region-io-audit.md](architecture/region-io-audit.md), pinned by `BlockingIoBoundaryTest` (SourbyCraft code only)

## F. Memory / GC

- [x] runtime heap metrics foundation
- [x] GC tracker foundation
- [ ] allocation MB/s validation against JFR
- [ ] RSS/cgroup validation
- [ ] top allocated classes workflow
- [x] cache ownership/bounds inventory — [cache-inventory.md](architecture/cache-inventory.md); `AwfWorld.owned` is the one unbounded collection
- [ ] player-disconnect retention test
- [ ] chunk-unload retention test
- [ ] world-unload retention test
- [x] completed future/task retention test — bridge one-shot tasks, async path accounting, AWF pending saves (tests named in cache-inventory.md)

## G. Spark / observability

- [x] route Spark tick stats through Sourby metrics
- [x] report SourbyCraft profiler identity
- [x] report Spark platform version as `Build44 (MC:26.2)` instead of the upstream `26.2-DEV-<git>` string
- [x] provide Sourby config group
- [ ] verify Spark web-viewer config rendering
- [x] add cheap Sourby runtime metadata — `SourbyMetadataProvider` via `createExtraMetadataProvider` ([SPARK.md](SPARK.md#aurora-runtime-metadata)); viewer rendering still unverified
- [ ] improve region thread classification
- [x] expose useful slow-region context — `/perf region` names the slowest active region (world/region/generation ids, 5 s average and maximum MSPT); coordinates not tracked
- [x] document Spark update procedure — [SPARK.md](SPARK.md#updating-spark)
- [!] replace the upstream viewer text `engine async` with `Aurora Engine`: the current spark upload protocol serializes profiler engine as the fixed `JAVA`/`ASYNC` enum, and spark.lucko.me renders that enum itself; an exact custom label therefore requires a Sourby/Aurora viewer fork or compatible custom viewer layer rather than a server-only metadata patch
- [ ] design Aurora viewer presentation so the primary label is `Aurora Engine` while the underlying implementation (`async-profiler` or Java sampler) remains visible in technical details
- [ ] decide adapter vs deep SourbySpark fork only after requirements are proven

## H. Independence

- [x] inventory direct Canvas accesses from `dev.iyanz.sourbycraft.*` — one file, `CanvasConfigBridge` ([dependency-ledger.md](architecture/dependency-ledger.md), pinned by `UpstreamDependencyLedgerTest`)
- [x] classify direct accesses as required contract / accidental coupling / removable — ledger classifications
- [x] define minimal region access contract — `execution/region/RegionBackend` ([compat-boundary.md](architecture/compat-boundary.md))
- [x] define minimal scheduler access contract — `execution/OwnerHandoff` ([execution-contract.md](architecture/execution-contract.md))
- [x] define minimal engine config bridge contract — `config/upstream/UpstreamConfigBridge`
- [ ] move large Sourby service bodies out of upstream classes
- [x] retain direct NMS algorithm patches when external indirection would be worse — policy in [compat-boundary.md §1.2](architecture/compat-boundary.md) and ledger §2.1; applied to patch 0006 (rules extracted to `AsyncPathValidity`, algorithm left in place)
- [x] active build-path dependency inventory — ledger §3–4 and AGENTS.md CI description
- [x] determine whether active CI still requires legacy `sourbypatcher` — no: CI requires the private SourbyPatcher `canvas-toolchain` adapter; the legacy Folia patcher is not used
- [ ] clean-checkout reproducibility
- [ ] cached/offline boot validation
- [x] track rebase conflict count — [rebase-log.md](architecture/rebase-log.md) (last bump: 7 patch files needed intervention); recorded per bump from now on

## I. SourbyClip / free-running requirement

- [-] downloader timeout audit — bootstrap downloads: whole-transfer deadline added (a stalled body could hang boot); SourbyClip (private) not audited ([bootstrap-download-audit.md](architecture/bootstrap-download-audit.md))
- [-] retry/failure handling — 3 attempts with backoff, `.tmp` always cleaned; unit-tested
- [-] SHA/cache validation audit — bootstrap downloads: verified before install, corrupt cache replaced, body cut off past the pinned size; SourbyClip not audited
- [-] bounded concurrency audit — bootstrap downloads are sequential; SourbyClip not audited
- [-] first-boot failure recovery test — downloader state after failure unit-tested; no whole-boot test
- [-] offline-after-success test — cache hit touches no network (unit test); no offline CI boot
- [ ] remote repository/fallback documentation — one URL per library, no mirrors exist to document
- [x] verify core runtime needs no Canvas remote service/API — [dependency-ledger.md §6](architecture/dependency-ledger.md)

## J. Aurora release gate

- [x] patch regeneration clean — CI run 437 (`a5fc62a7`, 2026-09-26): `applyAllPatches` including the Build 47 Paper patches
- [x] Java tests pass — CI run 437 (`a5fc62a7`, 2026-09-26): `:sourbyapi:test :sourbycraft-server:test :test-plugin:test` (9480 tests, 7 skipped) and 219 Python tests
- [x] boot pass — CI run 437 (`a5fc62a7`, 2026-09-26): boot to `Done (` with the test plugin, both metrics markers, `/tps` `/mspt` spark RAM PERF output markers (single CI boot, not a soak)
- [x] shutdown pass — CI run 437 (`a5fc62a7`, 2026-09-26): `stop`, worlds and player data saved, RegionFile I/O drained, clean exit
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
`[-]` here means implemented and unit-tested but not compiled into a server or run.

- [x] compatibility state model and `/plugins` palette (NATIVE/BRIDGED/FAILED/DISABLED)
- [x] capture plugin enable failures for FAILED state
- [-] Aurora Bridge admission (`aurora.bridge.mode`, Paper load-gate patches)
- [-] bridge scheduler routing (sync → global region, async → governed `BRIDGE_IO`), cancellation, disable cleanup
- [-] SAFE-mode violation counting, quarantine, per-plugin telemetry (`/plugins <name>`)
- [ ] entity-owner / region-owner routes with real callers
- [-] run representative legacy plugins through the bridge — one synthetic fixture (`legacy-test-plugin`) passes in CI run 440; no real plugin run yet
- [x] startup cache mechanics: fingerprints, environment key, per-entry integrity, atomic write
- [x] plugin descriptor index on a bounded STARTUP lane, with boot diagnostics
- [x] class index and compatibility scan (constant pool), dependency graph, startup profile
- [ ] cold vs warm startup benchmark
- [ ] transform output caching
- [x] AWF: world roles, atomic generations, `.awf` lazy images, FULL/INCREMENTAL/CHECKPOINT/READ_ONLY, COW instances, metrics
- [-] AWF integration with the engine's chunk load/save — FILE backend under `RegionFileStorage` for worlds in `aurora.awf.worlds` (off by default), `/perf awf`, tombstones; CI double-boot gate added. Not qualified: no crash, load or multi-world run ([aurora-world-fabric.md](architecture/aurora-world-fabric.md#engine-integration-regionfilestorage))
- [-] AWF export back to region files — `aurora.awf.export`, resumable, store kept; unit-tested, CI export boot added
- [-] AWF qualification — storage layer in-process: crash at each commit stage, 200 load/unload cycles, shutdown with a pending commit, concurrent writers, 1/50/250/1000 storages, corrupt object (`AwfQualificationTest`). Open: server-level crash with players, backend disconnect, load measurement
- [-] AWF backend SPI (`AwfStore`, `AwfBackend`, `aurora.awf.backend`), FILE built in, no fallback on a missing backend
- [ ] AWF database backends (MongoDB/MySQL/Redis) — none shipped
- [ ] SlimeLoader adapter — needs a Slime-format converter
- [x] Resource Governor specification
- [-] governed lanes with reject-not-caller-runs, `/perf governor`
- [x] operator-configurable governor budgets (`[aurora.scheduler]`)
- [ ] existing executors under the governor — deferred; each already has its own bound ([executor-inventory.md](architecture/executor-inventory.md))
- [-] network counters: bytes/packets per direction, connections, `/perf network`
- [-] vanilla save-queue / region-file storage metrics — `/perf storage`: chunk-system reads/writes/deletes (counted in the `RegionFileStorage` patch) and per-world pending I/O from Moonrise's controllers; no latency percentiles yet

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
