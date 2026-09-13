# SourbyCraft 26.2 — Development Task Matrix

This file is the actionable checklist for `DEVELOPMENT.md`.

Status values:

- `[x]` complete enough to build on
- `[-]` implemented but requires validation/refinement
- `[ ]` not complete
- `[!]` blocked/high risk

Priority:

- P0 correctness/stability
- P1 performance evidence/efficiency
- P2 independence/maintainability
- P3 feature polish

---

# A. Foundation and correctness

- [x] **P0** Java 25 production baseline
- [x] **P0** immutable SourbyCraft config snapshot
- [x] **P0** retire automatic swap/memory tuning behavior
- [x] **P0** explicit runtime service shutdown work
- [x] **P0** remove unsafe shared `ServerLevel` scratch collections
- [x] **P0** fix effect-particle scratch publication semantics
- [ ] **P0** audit remaining reusable mutable state / scratch buffers
- [ ] **P0** audit all `ThreadLocal` usage relevant to Sourby performance patches
- [ ] **P0** audit remaining object pools
- [ ] **P0** validate entity-owned scratch reentrancy assumptions
- [ ] **P0** full restart persistence test after performance changes
- [ ] **P0** multi-hour concurrency soak

---

# B. Baseline and measurement

- [x] **P1** JFR capture tooling
- [x] **P1** baseline metric extraction tooling
- [x] **P1** provenance-aware baseline comparison
- [x] **P1** network baseline field comparison
- [ ] **P1** certified idle baseline
- [ ] **P1** certified 10-player-equivalent workload
- [ ] **P1** certified 50-player-equivalent workload
- [ ] **P1** certified 100-player-equivalent workload
- [ ] **P1** real connected-client gameplay load
- [ ] **P1** entity-stress baseline
- [ ] **P1** chunk-generation/load/unload baseline
- [ ] **P1** gameplay network baseline
- [ ] **P1** 2h+ soak baseline
- [ ] **P1** post-load heap recovery measurement
- [ ] **P1** rank top CPU hot spots from JFR
- [ ] **P1** rank top allocation hot spots from JFR

No major new optimization batch should be accepted before the missing representative baselines exist.

---

# C. Sourby metrics / observability

- [x] **P1** Sourby metrics API surface
- [x] **P1** immutable performance snapshots
- [x] **P1** region metrics registry
- [x] **P1** custom region tick metrics
- [x] **P1** GC tracking lifecycle
- [x] **P1** runtime sampler foundation
- [-] **P1** `/tps` unified with Sourby metrics
- [-] **P1** `/mspt` unified with Sourby metrics
- [ ] **P1** `/ram` complete first-party command if not yet feature-complete
- [-] **P1** `/perf` base command
- [-] **P1** `/perfbar`
- [-] **P1** `/tpsbar`
- [-] **P1** `/rambar`
- [ ] **P1** `/perf tick`
- [ ] **P1** `/perf cpu`
- [ ] **P1** `/perf memory`
- [ ] **P1** `/perf gc`
- [ ] **P1** `/perf region`
- [ ] **P1** `/perf region <world> <x> <z>`
- [ ] **P1** `/perf player <player>`
- [ ] **P1** `/perf chunks`
- [ ] **P1** `/perf entities`
- [ ] **P1** `/perf network`
- [ ] **P1** `/perf scheduler`
- [ ] **P1** `/perf plugins`
- [ ] **P1** `/perf health`
- [ ] **P1** bounded `/perf history`
- [ ] **P3** actionbar HUD mode

---

# D. Spark refinement

- [x] **P1** route Spark tick statistics through Sourby metrics
- [x] **P2** report SourbyCraft platform identity
- [x] **P2** add SourbyCraft configuration provider
- [-] **P1** verify SourbyCraft config renders correctly in Spark web report
- [ ] **P0** verify secret filtering for Sourby config
- [ ] **P1** add Sourby runtime metadata without duplicating expensive scans
- [ ] **P1** improve region-thread classification
- [ ] **P1** expose useful region context to profiles where supported
- [ ] **P2** document upstream Spark version/update procedure
- [ ] **P2** decide adapter vs dedicated SourbySpark fork after requirements are proven
- [ ] **P2** if forking Spark, complete GPL/source-distribution compliance review before release

Do not create a deep Spark fork merely for naming/branding.

---

# E. Scheduler / concurrency

- [x] **P0** bounded administrative I/O execution
- [x] **P0** explicit administrative executor shutdown
- [-] **P0** async pathfinding shutdown/cancellation repair
- [ ] **P0** complete async pathfinding snapshot correctness audit
- [ ] **P0** path result staleness validation
- [ ] **P0** queue saturation test
- [ ] **P1** synchronous-vs-async pathfinding benchmark
- [ ] **P1** mob behavior compatibility soak
- [ ] **P1** inventory all Sourby-owned executors
- [ ] **P1** audit implicit common-pool usage
- [ ] **P1** record queue depth/task latency for relevant workers
- [ ] **P0** validate no region thread blocks on external I/O

---

# F. Entity / AI efficiency

- [ ] **P1** JFR entity-tick profile
- [ ] **P1** GoalSelector profile
- [ ] **P1** Brain/Sensor profile
- [ ] **P1** pathfinding profile
- [ ] **P1** collision profile
- [ ] **P1** entity-query allocation profile
- [ ] **P1** entity-tracker packet profile
- [ ] **P1** item-entity merge/pickup profile
- [ ] **P1** benchmark existing reusable entity buffers
- [ ] **P0** retain region ownership and Bukkit/Paper semantics for all changes

Only measured hot spots should produce new performance patches.

---

# G. Chunk / world efficiency

- [ ] **P1** chunk lookup/holder CPU profile
- [ ] **P1** ticket processing profile
- [ ] **P1** generation latency metric
- [ ] **P1** load latency metric
- [ ] **P1** save latency/backlog metric
- [ ] **P1** unload/reference-retention test
- [ ] **P0** region-file write concurrency review
- [ ] **P0** restart/crash persistence test for any async save changes
- [ ] **P1** identify duplicate serialization/copies before optimizing

---

# H. Network efficiency

- [ ] **P1** packets/sec instrumentation where reliable
- [ ] **P1** bytes/sec instrumentation where reliable
- [ ] **P1** packet queue health instrumentation
- [ ] **P1** encode/decode CPU profile
- [ ] **P1** compression CPU profile
- [ ] **P1** chunk-send CPU/bytes profile
- [ ] **P0** verify Netty event loops never perform unrelated blocking I/O
- [ ] **P0** preserve packet/security limits
- [ ] **P0** no adaptive compression/config tuning

---

# I. Memory / GC efficiency

- [x] **P1** basic heap/runtime metric support
- [x] **P1** GC tracker foundation
- [ ] **P1** allocation MB/s verification against JFR
- [ ] **P1** RSS/container memory verification on Linux/cgroup environments
- [ ] **P1** top allocated classes report workflow
- [ ] **P1** cache ownership/bounds inventory
- [ ] **P1** player-disconnect retention test
- [ ] **P1** chunk-unload retention test
- [ ] **P1** world-unload retention test
- [ ] **P1** task/future retention test

---

# J. SourbyCraft independence

- [x] **P2** branch `26.2` established as active continuation
- [-] **P2** SourbyCraft public profiler identity
- [-] **P2** Sourby config visible to Spark metadata
- [ ] **P2** inventory direct Canvas accesses from `dev.iyanz.sourbycraft.*`
- [ ] **P2** inventory active Canvas patch files by dependency role
- [ ] **P2** move large Sourby implementation bodies out of upstream classes
- [ ] **P2** define minimal region/scheduler contract Sourby depends on
- [ ] **P2** define minimal config bridge contract
- [ ] **P2** isolate Canvas Spark bridge behind Sourby-owned integration where useful
- [ ] **P2** active build-path dependency inventory
- [ ] **P2** determine whether active 26.2 CI still needs legacy `sourbypatcher`
- [ ] **P2** remove legacy active-line build dependency only after clean-build proof
- [ ] **P2** clean-checkout reproducibility test
- [ ] **P2** cached/offline boot test
- [ ] **P2** document every remaining hard Canvas dependency in `architecture/independence.md`
- [ ] **P2** track large downstream patch count/rebase conflict count per upstream update

---

# K. Patch architecture

- [x] **P2** initial patch inventory documentation
- [ ] **P2** classify all active patches: FOUNDATION / INTEGRATION / PERFORMANCE / COMPATIBILITY / SECURITY / BRANDING / LEGACY
- [ ] **P2** assign KEEP / SPLIT / MOVE TO SOURBY SOURCE / REPLACE / REMOVE / DEFER
- [ ] **P2** identify duplicate upstream optimizations
- [ ] **P2** identify obsolete Folia-era patches
- [ ] **P2** reduce giant mixed-responsibility patches
- [ ] **P2** keep implementation in Sourby-owned source where possible
- [ ] **P2** add or preserve regression tests before patch removal

---

# L. SourbyClip / bootstrap

- [ ] **P0** downloader timeout audit
- [ ] **P0** retry/failure behavior audit
- [ ] **P0** SHA/cache validation audit
- [ ] **P1** concurrency/boundedness audit
- [ ] **P1** thread/executor ownership audit
- [ ] **P1** first-boot failure recovery test
- [ ] **P1** offline-after-success test
- [ ] **P2** document required remote repositories and fallback order

---

# M. Release qualification

Before marking the next performance release stable:

- [ ] **P0** patch regeneration clean
- [ ] **P0** all Java tests pass
- [ ] **P0** server boot test pass
- [ ] **P0** clean shutdown pass
- [ ] **P0** no known region ownership regression
- [ ] **P0** no known persistence/world corruption regression
- [ ] **P1** representative benchmark report complete
- [ ] **P1** JFR reviewed
- [ ] **P1** multi-hour soak complete
- [ ] **P1** heap/thread/queue stabilization confirmed
- [ ] **P2** dependency ledger updated
- [ ] **P2** upstream attribution/license check complete
- [ ] **P3** release notes contain measured claims only

---

# Recommended Execution Order

```text
1. P0 correctness audits
2. certified baselines + soak
3. telemetry/Spark validation
4. executor/pathfinding audit
5. JFR-ranked entity/chunk/network optimization
6. independence isolation work
7. full regression benchmark
8. release qualification
```

Do not invert this order by doing broad low-level optimization before baseline and stability work are complete.
