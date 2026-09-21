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
- [ ] expose Aurora architecture/build metadata in `/version` where appropriate

## B. Aurora configuration ownership

- [ ] add `[aurora.performance]` logical namespace
- [ ] add `[aurora.scheduler]` logical namespace
- [x] add `[aurora.entity]` logical namespace
- [ ] add `[aurora.chunk]` logical namespace
- [ ] add `[aurora.network]` logical namespace
- [ ] add `[aurora.memory]` logical namespace
- [x] add `[aurora.diagnostics]` logical namespace — `lane-sampling`, LIVE
- [x] migrate `perf.ai.async-pathfinding` to `aurora.entity.async-pathfinding` with legacy read fallback
- [x] never auto-save migrated keys
- [-] mark each config key LIVE / RESTART_REQUIRED / IMMUTABLE_FOR_RUN — both implemented keys are LIVE; future keys require their own policy
- [-] report restart-required changes accurately on reload — Aurora-only live/invalid summary implemented; no Aurora restart-required keys exist yet
- [x] add typed immutable config records/classes
- [-] remove hot-path dotted-string config lookup where present — async path setting now resolves once into typed snapshot
- [-] ensure Spark shows SourbyCraft config
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

- [ ] instrument packets/sec
- [ ] instrument bytes/sec
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
- [ ] path result staleness test
- [ ] queue saturation test
- [ ] sync-vs-async path benchmark
- [ ] mob behavior compatibility soak
- [ ] collect worker queue depth/latency where useful
- [ ] verify no external I/O blocks region execution

## F. Memory / GC

- [x] runtime heap metrics foundation
- [x] GC tracker foundation
- [ ] allocation MB/s validation against JFR
- [ ] RSS/cgroup validation
- [ ] top allocated classes workflow
- [ ] cache ownership/bounds inventory
- [ ] player-disconnect retention test
- [ ] chunk-unload retention test
- [ ] world-unload retention test
- [ ] completed future/task retention test

## G. Spark / observability

- [x] route Spark tick stats through Sourby metrics
- [x] report SourbyCraft profiler identity
- [x] report Spark platform version as `Build44 (MC:26.2)` instead of the upstream `26.2-DEV-<git>` string
- [x] provide Sourby config group
- [ ] verify Spark web-viewer config rendering
- [ ] add cheap Sourby runtime metadata
- [ ] improve region thread classification
- [ ] expose useful slow-region context
- [ ] document Spark update procedure
- [!] replace the upstream viewer text `engine async` with `Aurora Engine`: the current spark upload protocol serializes profiler engine as the fixed `JAVA`/`ASYNC` enum, and spark.lucko.me renders that enum itself; an exact custom label therefore requires a Sourby/Aurora viewer fork or compatible custom viewer layer rather than a server-only metadata patch
- [ ] design Aurora viewer presentation so the primary label is `Aurora Engine` while the underlying implementation (`async-profiler` or Java sampler) remains visible in technical details
- [ ] decide adapter vs deep SourbySpark fork only after requirements are proven

## H. Independence

- [ ] inventory direct Canvas accesses from `dev.iyanz.sourbycraft.*`
- [ ] classify direct accesses as required contract / accidental coupling / removable
- [ ] define minimal region access contract
- [ ] define minimal scheduler access contract
- [ ] define minimal engine config bridge contract
- [ ] move large Sourby service bodies out of upstream classes
- [ ] retain direct NMS algorithm patches when external indirection would be worse
- [ ] active build-path dependency inventory
- [ ] determine whether active CI still requires legacy `sourbypatcher`
- [ ] clean-checkout reproducibility
- [ ] cached/offline boot validation
- [ ] track rebase conflict count

## I. SourbyClip / free-running requirement

- [ ] downloader timeout audit
- [-] retry/failure handling
- [ ] SHA/cache validation audit
- [ ] bounded concurrency audit
- [ ] first-boot failure recovery test
- [ ] offline-after-success test
- [ ] remote repository/fallback documentation
- [ ] verify core runtime needs no Canvas remote service/API

## J. Aurora release gate

- [ ] patch regeneration clean
- [ ] Java tests pass
- [ ] boot pass
- [ ] shutdown pass
- [ ] restart persistence pass
- [ ] representative benchmark report
- [ ] JFR reviewed
- [ ] 2h+ soak pass
- [ ] heap/thread/queue stabilization confirmed
- [ ] no known region ownership regression
- [ ] no known persistence regression
- [ ] dependency ledger updated
- [ ] attribution/license review complete
- [ ] README and release notes contain measured claims only

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
