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
- [x] **P0** audit remaining reusable mutable state / scratch buffers — scoped Sourby audit; [findings and remaining unknowns](architecture/reuse-audit.md)
- [x] **P0** audit all `ThreadLocal` usage relevant to Sourby performance patches — no Sourby-owned mutable storage found; upstream internals outside scope
- [x] **P0** audit remaining object pools — no Sourby-owned object recycler found; solver-state ownership reviewed
- [-] **P0** validate entity-owned scratch reentrancy assumptions — retained collection reuse removed; scalar-position reentrancy proof remains open
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
- [x] **P1** `/perf tick`
- [x] **P1** `/perf cpu`
- [x] **P1** `/perf memory`
- [x] **P1** `/perf gc`
- [x] **P1** `/perf region` — region count, MSPT spread, slowest region
- [ ] **P1** `/perf region <world> <x> <z>`
- [x] **P1** `/perf player <player>` — gathered on the player's entity scheduler
- [x] **P1** `/perf chunks` — per world, from Folia region counters
- [x] **P1** `/perf entities` — per world, from Folia region counters
- [x] **P1** `/perf network` — wire bytes/packets per direction, connections
- [-] **P1** `/perf scheduler` — `/perf governor` (Sourby lanes) and `/perf async`; the region scheduler's own queues are not shown
- [x] **P1** `/perf plugins`
- [x] **P1** `/perf health`
- [x] **P1** bounded `/perf history` — 60 one-minute samples (`perf/PerformanceHistory`)
- [ ] **P3** actionbar HUD mode

---

# D. Spark refinement

- [x] **P1** route Spark tick statistics through Sourby metrics
- [x] **P2** report SourbyCraft platform identity
- [x] **P2** add SourbyCraft configuration provider
- [-] **P1** verify SourbyCraft config renders correctly in Spark web report
- [x] **P0** verify secret filtering for Sourby config — parser/provider regression tests; see [scope and limits](SPARK.md)
- [x] **P1** add Sourby runtime metadata without duplicating expensive scans — `spark/SourbyMetadataProvider` (in-memory reads only)
- [ ] **P1** improve region-thread classification
- [ ] **P1** expose useful region context to profiles where supported
- [x] **P2** document upstream Spark version/update procedure — `SPARK.md`
- [ ] **P2** decide adapter vs dedicated SourbySpark fork after requirements are proven
- [ ] **P2** if forking Spark, complete GPL/source-distribution compliance review before release

Do not create a deep Spark fork merely for naming/branding.

---

# E. Scheduler / concurrency

- [x] **P0** bounded administrative I/O execution
- [x] **P0** explicit administrative executor shutdown
- [-] **P0** async pathfinding shutdown/cancellation repair
- [ ] **P0** complete async pathfinding snapshot correctness audit
- [x] **P0** path result staleness validation — `AsyncPathValidity`, used by minecraft patch 0006; `AsyncPathValidityTest`
- [x] **P0** queue saturation test — `AsyncPathQueueTelemetryTest.saturationRefusesInsteadOfRunningOnSubmittingThread`
- [ ] **P1** synchronous-vs-async pathfinding benchmark
- [ ] **P1** mob behavior compatibility soak
- [x] **P1** inventory all Sourby-owned executors — `architecture/executor-inventory.md`
- [ ] **P1** audit implicit common-pool usage
- [x] **P1** record queue depth/task latency for relevant workers — `/perf async`, `/perf governor`
- [-] **P0** validate no region thread blocks on external I/O — static audit `architecture/region-io-audit.md` + `BlockingIoBoundaryTest`; no runtime proof

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
- [-] **P1** save latency/backlog metric — backlog: `/perf storage`; latency: AWF commits only
- [ ] **P1** unload/reference-retention test
- [ ] **P0** region-file write concurrency review
- [ ] **P0** restart/crash persistence test for any async save changes
- [ ] **P1** identify duplicate serialization/copies before optimizing

---

# H. Network efficiency

- [x] **P1** packets/sec instrumentation where reliable — `NetworkCounters`, `/perf network`
- [x] **P1** bytes/sec instrumentation where reliable — same
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
- [x] **P1** cache ownership/bounds inventory — `architecture/cache-inventory.md`
- [ ] **P1** player-disconnect retention test
- [ ] **P1** chunk-unload retention test
- [ ] **P1** world-unload retention test
- [x] **P1** task/future retention test — tests named in `cache-inventory.md`

---

# J. SourbyCraft independence

- [x] **P2** branch `26.2` established as active continuation
- [-] **P2** SourbyCraft public profiler identity
- [-] **P2** Sourby config visible to Spark metadata
- [x] **P2** inventory direct Canvas accesses from `dev.iyanz.sourbycraft.*` — `dependency-ledger.md` §1, enforced by `UpstreamDependencyLedgerTest`
- [x] **P2** inventory active Canvas patch files by dependency role — `dependency-ledger.md` §2
- [ ] **P2** move large Sourby implementation bodies out of upstream classes
- [x] **P2** define minimal region/scheduler contract Sourby depends on — `execution/region/RegionBackend`, `execution/OwnerHandoff`
- [x] **P2** define minimal config bridge contract — `config/upstream/UpstreamConfigBridge`
- [ ] **P2** isolate Canvas Spark bridge behind Sourby-owned integration where useful
- [x] **P2** active build-path dependency inventory — `dependency-ledger.md` §3–4
- [x] **P2** determine whether active 26.2 CI still needs legacy `sourbypatcher` — yes: settings.gradle.kts requires the hash-verified private SourbyPatcher (`development/PRIVATE-TOOLCHAIN.md`)
- [ ] **P2** remove legacy active-line build dependency only after clean-build proof
- [ ] **P2** clean-checkout reproducibility test
- [ ] **P2** cached/offline boot test
- [ ] **P2** document every remaining hard Canvas dependency in `architecture/independence.md`
- [x] **P2** track large downstream patch count/rebase conflict count per upstream update — `architecture/rebase-log.md`

---

# K. Patch architecture

- [x] **P2** initial patch inventory documentation
- [x] **P2** classify all active patches: FOUNDATION / INTEGRATION / PERFORMANCE / COMPATIBILITY / SECURITY / BRANDING / LEGACY — `architecture/patch-classification.md`
- [x] **P2** assign KEEP / SPLIT / MOVE TO SOURBY SOURCE / REPLACE / REMOVE / DEFER — same doc
- [ ] **P2** identify duplicate upstream optimizations
- [x] **P2** identify obsolete Folia-era patches — none found; all apply at the pinned revisions
- [-] **P2** reduce giant mixed-responsibility patches — identified (0006, 0013 split; 0002, 0005, 0016 move classes out); not done
- [ ] **P2** keep implementation in Sourby-owned source where possible
- [ ] **P2** add or preserve regression tests before patch removal

---

# L. SourbyClip / bootstrap

- [-] **P0** downloader timeout audit — bootstrap downloads fixed and tested (`architecture/bootstrap-download-audit.md`); SourbyClip (private) not audited
- [-] **P0** retry/failure behavior audit — SPEC B16 launcher exit status and transfer loop shipped in repo bootstrap 3.0.22; process and local multi-chunk/cache probes pass; remote cold-download qualification remains pending (see `BOOTSTRAP.md`)
- [-] **P0** SHA/cache validation audit — same scope
- [-] **P1** concurrency/boundedness audit — bootstrap downloads are sequential and size-bounded; SourbyClip not audited
- [ ] **P1** thread/executor ownership audit
- [-] **P1** first-boot failure recovery test — downloader state after failure unit-tested; no whole-boot test
- [-] **P1** offline-after-success test — cache hit touches no network (unit test); no offline CI boot
- [ ] **P2** document required remote repositories and fallback order

---

# M. Release qualification

Before marking the next performance release stable:

- [x] **P0** patch regeneration clean — CI runs 437–442
- [x] **P0** all Java tests pass — CI runs 437–442
- [x] **P0** server boot test pass — CI boot steps (plain, bridge, AWF) in run 442
- [x] **P0** clean shutdown pass — same
- [ ] **P0** no known region ownership regression
- [ ] **P0** no known persistence/world corruption regression
- [ ] **P1** representative benchmark report complete
- [ ] **P1** JFR reviewed
- [ ] **P1** multi-hour soak complete
- [ ] **P1** heap/thread/queue stabilization confirmed
- [x] **P2** dependency ledger updated — 2026-09-27
- [-] **P2** upstream attribution/license check complete — release doc notes; PolyForm-NC vs GPLv3 question open for the owner
- [-] **P3** release notes contain measured claims only — audited 2026-09-27; re-audit before tagging

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

## Phase 46 continuation evidence (2026-09-14)

Spark secret filtering: inherited upstream base exclusions and recursive TOML/YAML
credential removal are covered by six parser/provider tests. Full local verification:
patch regeneration, 9867 Java tests (24 skipped, no failures/errors), slim JAR build,
and saved-world boot/JFR with unchanged utility configuration and clean shutdown.
See [Spark reporting](SPARK.md) for scope. This does not close Spark web-viewer
verification, representative workloads, restart inventory persistence, or soak gates.

Claude completed the scoped reuse/ThreadLocal/pool audit through CLI session
`988a8822-80be-44bc-9c26-9bbc2aa1467b`. Integration review corrected unsupported
CME/extrema claims, removed unbenchmarked retained entity collections, and added
explicit async completion retirement cleanup. The audit is not a concurrency soak
or a complete async pathfinding snapshot/staleness review. See
[reuse audit and follow-up](architecture/reuse-audit.md).

Final integration Java verification: 9874 tests (24 skipped, no failures/errors),
including retained-list removal guards, preserved Level query-overload descriptor,
and four async completion cleanup tests. Python verification at baseline commit
7ebd1eb: 65 tests passed. Subsequent in-progress baseline edits are separate work.
Cold bootstrap on a new cache failed the Mojang download hash check and exited 0;
that is tracked under SourbyClip (SPEC B16), not recorded as a successful boot.
Runtime validation uses a cached Mojang JAR verified against META-INF/download-context.
