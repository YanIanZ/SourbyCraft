# SourbyCraft 26.2 — Development Task Matrix

This file is the actionable checklist for `docs/DEVELOPMENT.md`.

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
- [x] **P0** validate entity-owned scratch reentrancy assumptions — retained collection reuse removed; scalar-position proof for SourbyCraft feature patches done 2026-10-20 (`docs/architecture/scratch-reentrancy-proof.md`, fix in MC 0044); the independent re-review findings (non-volatile derived double can tear, F3 stop resurrection incl. same-nodes retarget, 0009 threshold drift, ASM proof gaps) are fixed in MC patches 0046 and 0047; F3 is SAFE only for the enumerated invalidations (hand-written model, not the real patch lambda); engine-baseline scratch (`minecraft-patches/sources`) and runtime/soak evidence are not covered
- [ ] **P0** full restart persistence test after performance changes — tools exist (`verify_persistence.py`, `verify_crash.py`); last ran on RC tree `cbef3fb9` (2026-10-13, release doc rows 100-101); needs a rerun, plain and `--awf`, on the current tree after the AWF/MC 0043-0048 changes (needs a booted server; CI does not run them); 2026-10-20 evidence, agent test server 2026-10-20 (server-w1, PARTIAL, functional only, not certified; jar `f84e9c10…` built from HEAD `69189766` on a dirty tree, Apple M2 16 GiB, shared machine load avg 6-12, no players, no plugins): region files 61/61 (`server-w1-persist-region-1..4`: write, 2 clean restarts, churn + SIGKILL exit -9, recover; 64/64 blocks, 24/24 armor stands, game time advanced each boot, 2352 chunks intact) and AWF FILE 65/65 (9 stores, no `.mca` while attached), 0 corruption markers in 8 logs; two clean restarts + one SIGKILL recovery per backend. Still open: player data round trip (no client), the stop-under-load phase of `verify_persistence.py`, multi-world, soak, quiet-machine certification; left `[ ]` because the item asks for the full tool run on a real server with clients
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
- [ ] **P1** post-load heap recovery measurement — tooling done 2026-10-20 (`run_baseline.py --recovery SECONDS`, `baseline_metrics.heap_recovery`, `docs/BASELINE.md` "Post-load heap recovery", `HeapRecoveryTest` 7); no recovery run recorded yet (needs a server)
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
- [x] **P1** `/ram` complete first-party command if not yet feature-complete — PLAN.md fields; allocation/GC split/last pause `n/a` (not instrumented)
- [-] **P1** `/perf` base command
- [-] **P1** `/perfbar`
- [-] **P1** `/tpsbar`
- [-] **P1** `/rambar`
- [x] **P1** `/perf tick`
- [x] **P1** `/perf cpu`
- [x] **P1** `/perf memory`
- [x] **P1** `/perf gc`
- [x] **P1** `/perf region` — region count, MSPT spread, slowest region
- [x] **P1** `/perf region <world> <x> <z>` — `RegionLocator`, approximate regioniser lookup; not yet observed on a live server
- [x] **P1** `/perf player <player>` — gathered on the player's entity scheduler
- [x] **P1** `/perf chunks` — per world, from Folia region counters
- [x] **P1** `/perf entities` — per world, from Folia region counters
- [x] **P1** `/perf network` — wire bytes/packets per direction, connections
- [x] **P1** `/perf scheduler` — region scheduler view (kind, threads, regions by state, late regions, longest tick); per-region queued task counts (tick/chunk) and late-tick-start counters are real since MC patch 0045 (2026-10-20; `RegionTaskQueueDepthTest`, `TickLateStartCounterTest`, `PerfSchedulerViewTest`). Still `n/a`: the scheduler run queue and idle threads, the global task queue (private, no size accessor) and the world-wide global chunk-task queue; not yet observed on a live server
- [x] **P1** `/perf plugins`
- [x] **P1** `/perf health`
- [x] **P1** bounded `/perf history` — 60 one-minute samples (`perf/PerformanceHistory`)
- [x] **P3** actionbar HUD mode — `/tpsbar|/rambar|/perfbar actionbar|bossbar`, per-player, LIVE

---

# D. Spark refinement

- [x] **P1** route Spark tick statistics through Sourby metrics
- [x] **P2** report SourbyCraft platform identity
- [x] **P2** add SourbyCraft configuration provider
- [-] **P1** verify SourbyCraft config renders correctly in Spark web report
- [x] **P0** verify secret filtering for Sourby config — parser/provider regression tests; see [scope and limits](SPARK.md)
- [x] **P1** add Sourby runtime metadata without duplicating expensive scans — `spark/SourbyMetadataProvider` (in-memory reads only)
- [x] **P1** improve region-thread classification — 2026-10-20: `SparkThreadClass` (REGION_TICK, CHUNK_WORKER, CHUNK_IO, SOURBY_LANE, ASYNC_PATH, AWF_MAINTENANCE, NETWORK, OTHER), `LabelledThreadDumper`, `sourbycraft-threads` metadata (`ThreadCensusMetadataProvider`); `SparkThreadClassTest` 9, `ThreadCensusMetadataProviderTest` 8; limits: global tick and region ticks share threads, virtual `SourbyCraft-IO-n` is invisible to spark, spark web-viewer rendering unverified (see [SPARK.md](SPARK.md))
- [ ] **P1** expose useful region context to profiles where supported
- [x] **P2** document upstream Spark version/update procedure — `SPARK.md`
- [ ] **P2** decide adapter vs dedicated SourbySpark fork after requirements are proven
- [ ] **P2** if forking Spark, complete GPL/source-distribution compliance review before release

Do not create a deep Spark fork merely for naming/branding.

---

# E. Scheduler / concurrency

- [x] **P0** bounded administrative I/O execution
- [x] **P0** explicit administrative executor shutdown
- [x] **P0** async pathfinding shutdown/cancellation repair — 2026-10-20, unit level: `AsyncPathProcessor.shutdown()` (shutdownNow, queued futures completed, `start()` lifts the refusal); `AsyncPathShutdownTest` 9, `AsyncPathCancellationTest` (entity removal, world unload, moving target mid-solve), `AsyncPathPatchShapeTest`; the patch lambda and a real `Mob` on a live server are not run
- [x] **P0** complete async pathfinding snapshot correctness audit — 2026-10-20, source audit (flag stays off): [async-path-snapshot-audit.md](architecture/async-path-snapshot-audit.md), `SnapshotPathRegionAuditTest` 3, `SnapshotPathRegionTest`; the one correctness divergence (unloaded chunk answered AIR/OPEN instead of null/BLOCKED on the `*IfLoaded` reads) is fixed in MC patch 0048; no flag-on run
- [x] **P0** path result staleness validation — `AsyncPathValidity`, used by minecraft patch 0006; `AsyncPathValidityTest`
- [x] **P0** queue saturation test — `AsyncPathQueueTelemetryTest.saturationRefusesInsteadOfRunningOnSubmittingThread`
- [ ] **P1** synchronous-vs-async pathfinding benchmark
- [ ] **P1** mob behavior compatibility soak
- [x] **P1** inventory all Sourby-owned executors — `architecture/executor-inventory.md`
- [x] **P1** audit implicit common-pool usage — 2026-10-20 re-audit in `architecture/executor-inventory.md` §2: no implicit common-pool use in Sourby/Aurora code (every `*Async` call passes an executor); one finding left unfixed by design: spark's `SamplerModule` (`whenCompleteAsync`/`thenAcceptAsync` without an executor, `handleUpload` blocking HTTP/file write once per profile, never on a region thread) — fixing it means diverging from the shadowed spark copy (owner decision); baseline patches and materialized sources not audited
- [x] **P1** record queue depth/task latency for relevant workers — `/perf async`, `/perf governor`
- [-] **P0** validate no region thread blocks on external I/O — static audit `architecture/region-io-audit.md` + `BlockingIoBoundaryTest`; no runtime proof; 2026-10-20 runtime sampling, agent test server 2026-10-20 (server-w1, PARTIAL, functional only, not certified; jar `f84e9c10…` built from HEAD `69189766` on a dirty tree, Apple M2 16 GiB, shared machine load avg 6-12, no players, no plugins, AWF redis backend, resident-chunks=1): jstack every ~0.35 s under a console workload; healthy 120 s = 342 dumps, 2736 region-scheduler stacks, 0 socket/Redis/NIO frames (3 frames = lazy class loading from the server jar); Redis SIGSTOP 33 s = 142 dumps, 1136 region stacks, 0 I/O frames, `/mspt` worst avg 0.38 ms, recent max 13.3 ms. Limits: region threads parked in ~99% of samples, sampling misses short calls, no disk-stall test, no plugins

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
Standing rule for this section (not a task with a done state): every new entity or performance patch must retain region ownership and Bukkit/Paper semantics. It is enforced by `docs/DEVELOPMENT.md` §4 (correctness outranks benchmark wins; ownership, reentrancy and persistence recorded per NMS change), `scripts/test_independence_policy.py` and `baseline_policy.py` on every patch, `GuardViolations` with `guard-severity: LOG` at runtime, and the per-patch proofs (`region-safety-review-0017-0019.md`, `scratch-reentrancy-proof.md`).

Only measured hot spots should produce new performance patches.

---

# G. Chunk / world efficiency

- [ ] **P1** chunk lookup/holder CPU profile
- [ ] **P1** ticket processing profile
- [-] **P1** generation latency metric — metric implemented: `perf/ChunkGenerationMetrics` (queue and run time per generic generation stage, `/perf chunks`) fed by MC patch 0026; built, unit-tested (`ChunkGenerationMetricsTest`, `ChunkGenerationMetricsEarlyConfigTest`, `PerfStartupAndChunkHintTest`) and booted on the demo with metrics off; RESTART_REQUIRED, default off (`aurora.diagnostics.chunk-generation-metrics`); no run with metrics on, lighting and full-status integration not covered
- [x] **P1** load latency metric — recorded 2026-10-20: MC patch 0050 times the region-file read stage (`.mca` lookup/open + sector read) into `perf/RegionFileLoadLatency` (p50/p99/max over 60 s per world/type); `RegionFileLoadLatencyHookTest` 3; shown in `/perf storage` (`command/PerfTelemetryRows.loadLines`, `PerfTelemetryRowsTest` 7); ordinary decompress/NBT parse and AWF-attached storages are not timed, no server numbers, overhead not benchmarked
- [x] **P1** save latency/backlog metric — backlog: `/perf storage` (`perf/StorageBacklog`); region-file save latency: MC patch 0043 times `RegionFileStorage.moonrise$finishWrite` into `perf/RegionFileSaveLatency` (p50/p99/max over 60 s per world/type, `/perf storage`; `RegionFileSaveLatencyTest` 8, `RegionFileSaveLatencyHookTest` 3); AWF commit latency as before. Not timed: serialization/compression and the flush-regions-on-save flush; AWF worlds not covered; no server numbers and the overhead is not benchmarked
- [x] **P1** unload/reference-retention test — unit level, 2026-10-20: `retention/Reachability` helper plus 9 retention classes, 55 tests green (`AwfChunkUnloadRetentionTest` 4, `AwfEngineRetentionTest` 5, `AuroraWorldsUnloadRetentionTest` 3, `AnchorCacheRetentionTest` 6, `RegionMetricsRetentionTest` 4, `BridgeRetentionTest` 8, `SpeedtestRetentionTest` 4, `HudBarsRetentionTest` 17, `RedisLeaseRetentionTest` 4); not covered: GeoUtil cache, pearls engine, Minecraft-side holders, a heap comparison on a live server
- [x] **P0** region-file write concurrency review — 2026-10-20 (DEV-G): section "Region-file write concurrency review" in `architecture/aurora-world-fabric.md` (4 findings); empty-INCREMENTAL-commit guard in `AwfWorld.commitInOrder`; `AwfConcurrentFlushTest` 3; unit level, no boot or soak
- [x] **P0** restart/crash persistence test for any async save changes — 2026-10-20: `AwfAsyncSaveRestartTest` 5 (crash before/after the lane commit, at every fsync of the lane commit, failed lane commit carried by the next flush, `close()` racing a mid-fsync lane commit), mutation-checked; a `kill -9` during `save-all` on a live server is not run
- [x] **P1** identify duplicate serialization/copies before optimizing — static identification 2026-10-20: [chunk-io-copy-audit.md](architecture/chunk-io-copy-audit.md) (region-file and AWF FILE paths, copies per step with file:line); nothing measured and nothing changed; a JFR allocation profile must precede any optimization

---

# H. Network efficiency

- [x] **P1** packets/sec instrumentation where reliable — `NetworkCounters`, `/perf network`
- [x] **P1** bytes/sec instrumentation where reliable — same
- [x] **P1** packet queue health instrumentation — `perf/ConnectionQueueHealth` (point sample: connections, closed, unwritable, pending outbound bytes sum/max; Netty buffer only) + `ConnectionQueueHealthTest` 4, shown in `/perf network` (`command/PerfTelemetryRows.queueLines`, `PerfTelemetryRowsTest` 7); no server numbers; the game-side `Connection.pendingActions` needs an AT or patch
- [ ] **P1** encode/decode CPU profile
- [ ] **P1** compression CPU profile
- [ ] **P1** chunk-send CPU/bytes profile
- [x] **P0** verify Netty event loops never perform unrelated blocking I/O — static audit 2026-10-20: `architecture/region-io-audit.md` (handshake/status/login/configuration/game `handle*` paths) and `architecture/engine-network.md` §6 (SourbyCraft's own on-loop code); pinned by `BlockingIoBoundaryTest`, `HandshakeAddressesTest`, `AdmissionHookBytecodeTest`, `PacketDecoderHookTest`, `SecurityNbtHookTest`; no runtime (BlockHound-style) evidence, third-party handlers (ProtocolLib, Via) and upstream loop code not re-audited
- [x] **P0** preserve packet/security limits — consumers rebuilt in SEC-1 (`41a444f5`); `SecurityLimitPinTest` 3 pins all 19 numeric limits plus `ipv6-prefix` 64 (code defaults == documented table == shipped `sourbycraft-security.yml`) and `SecurityGuardsTest` pins the key set and LOG modes, so a loosened limit fails the suite; no live-server re-verification
- [x] **P0** no adaptive compression/config tuning — audit clean 2026-10-20 (no SourbyCraft source or feature-patch added line touches compression encoder/decoder/threshold; runtime config writes are only `/maxp`, `StartupProfile` and the default security YAML when absent; `KnobEnforcer`/`SelfTuneController`/`SimulationThrottle` do not exist) and pinned by `scripts/patch_policy.py` `network_compression_references()` (`NetworkCompressionTest` in `test_patch_policy.py`); AWF zstd storage compression is out of scope by design

---

# I. Memory / GC efficiency

- [x] **P1** basic heap/runtime metric support
- [x] **P1** GC tracker foundation
- [ ] **P1** allocation MB/s verification against JFR
- [-] **P1** RSS/container memory verification on Linux/cgroup environments — PARTIAL (2026-10-21, functional, shared VPS, cgroup v2, no clients): heap/non-heap/Process RSS/limit match jcmd, NMT, `/proc` and cgroup files in a private-cgroup-namespace emulation; evidence [linux-vps-verification-2026-10-21.md](development/linux-vps-verification-2026-10-21.md). F1 (host cgroup view, systemd scope/service: container usage unavailable, fixed root paths) is fixed in the working tree (uncommitted) and unit-tested against fixtures only (`ContainerMemoryCgroupTest` 11); not re-run on Linux with the new jar. F2/F3 relabelled (`/sys` Process RSS vs Cgroup memory incl. page cache, "Heap" instead of "RAM"). Not verified: real Docker/Pterodactyl, real cgroup v1, host-view retest
- [x] **P1** top allocated classes report workflow — `scripts/profile_server.py` (JFR `settings=profile`) + `scripts/rank_hotspots.py` `allocation_hotspots` (by class/site/thread, cross-checked against `jdk.ThreadAllocationStatistics`), documented in `BASELINE.md` "Ranking hot spots"; `test_rank_hotspots.py` 37 OK; a certified real-run report does not exist yet
- [x] **P1** cache ownership/bounds inventory — `architecture/cache-inventory.md`
- [x] **P1** player-disconnect retention test — unit level: `HudBarsRetentionTest` 17, `BridgeRetentionTest` 8, `SpeedtestRetentionTest` 4 (`retention/Reachability`); no heap check on a live server
- [x] **P1** chunk-unload retention test — unit level for SourbyCraft-owned (AWF) state: `AwfChunkUnloadRetentionTest` 4 (dropped chunk arrays collectable vs kept chunks reachable, 50 load cycles stay at the resident limit; mutation-checked); not covered: Minecraft-side chunk unload (holders, entities, block entities), Redis backend, a live heap comparison
- [x] **P1** world-unload retention test — unit level: `AnchorCacheRetentionTest` 6 (found and fixed the `FoliaRegionBackend` World leak), `RegionMetricsRetentionTest` 4, `AwfEngineRetentionTest` 5, `AuroraWorldsUnloadRetentionTest` 3; no field in `sourbycraft-server/src/main` holds a World/ServerLevel strongly; not covered: a heap/GC check after a real unload, the engine's Minecraft-side holders
- [x] **P1** task/future retention test — tests named in `cache-inventory.md`

---

# J. SourbyCraft independence

- [x] **P2** branch `26.2` established as active continuation
- [-] **P2** SourbyCraft public profiler identity
- [-] **P2** Sourby config visible to Spark metadata
- [x] **P2** inventory direct Canvas accesses from `dev.iyanz.sourbycraft.*` — `dependency-ledger.md` §1, enforced by `UpstreamDependencyLedgerTest`
- [x] **P2** inventory active Canvas patch files by dependency role — `dependency-ledger.md` §2
- [-] **P2** move large Sourby implementation bodies out of upstream classes — slices done 2026-10-20: `AuroraCpu` out of MC patch 0002 (`dev.iyanz.aurora.cpu.AuroraCpu`), `AuroraCommandErrors` out of 0016 (`AuroraCommandErrorsTest` 3), `AwfChunkNbt` out of 0021 (`AwfChunkNbtTest` 3), `GliderSlots` out of 0007 (`GliderSlotsTest` 5); remaining: 0005 `SnapshotPathRegion` (check package-private access first), 0006/0013 splits, the AWF branches in 0021, 0026 timing; no in-game check
- [x] **P2** define minimal region/scheduler contract Sourby depends on — `execution/region/RegionBackend`, `execution/OwnerHandoff`
- [x] **P2** define minimal config bridge contract — `config/upstream/UpstreamConfigBridge`
- [ ] **P2** isolate Canvas Spark bridge behind Sourby-owned integration where useful
- [x] **P2** active build-path dependency inventory — `dependency-ledger.md` §3–4
- [x] **P2** determine whether active 26.2 CI still needs legacy `sourbypatcher` — yes: settings.gradle.kts requires the hash-verified private SourbyPatcher (`development/PRIVATE-TOOLCHAIN.md`)
- [ ] **P2** remove legacy active-line build dependency only after clean-build proof — n/a while SourbyPatcher is the patch applier (the active build requires it, see the row above); reopen if the owner plans an in-repo applier
- [ ] **P2** clean-checkout reproducibility test
- [ ] **P2** cached/offline boot test
- [x] **P2** document every remaining hard Canvas dependency in `architecture/independence.md` — 2026-10-20: section 2.2 "Remaining hard Canvas-named surfaces" (plugin API, Paper-patch entry points, config/data dir, permission nodes, spark group, descriptor key, engine member names, test env var, branch name); counts from `rg`, not pinned by a test
- [x] **P2** track large downstream patch count/rebase conflict count per upstream update — `architecture/rebase-log.md`

---

# K. Patch architecture

- [x] **P2** initial patch inventory documentation
- [x] **P2** classify all active patches: FOUNDATION / INTEGRATION / PERFORMANCE / COMPATIBILITY / SECURITY / BRANDING / LEGACY — `architecture/patch-classification.md`
- [x] **P2** assign KEEP / SPLIT / MOVE TO SOURBY SOURCE / REPLACE / REMOVE / DEFER — same doc
- [x] **P2** identify duplicate upstream optimizations — 2026-10-20, scoped to the pinned `paperRef`: none found among the PERFORMANCE patches (`architecture/patch-classification.md` Findings); not compared with newer Paper/Moonrise, no benchmark
- [x] **P2** identify obsolete Folia-era patches — none found; all apply at the pinned revisions
- [-] **P2** reduce giant mixed-responsibility patches — identified (0006, 0013 split; 0002, 0005, 0016 move classes out); 2026-10-20: `AuroraCpu` left 0002 and `AuroraCommandErrors` left 0016; still to do: 0006/0013 splits, 0005 class move
- [-] **P2** keep implementation in Sourby-owned source where possible — standing rule; slices done 2026-10-20 (`AuroraCpu`, `AuroraCommandErrors`, `AwfChunkNbt`, `GliderSlots`, see the move row above); remaining as in that row; the 0026 chunk-generation timing bodies moved into `perf/ChunkGenerationMetrics` (patch 0026 keeps one call per hook; still `logic` class under `patch_surface` because the try/finally must stay)
- [-] **P2** add or preserve regression tests before patch removal — 2026-10-20: 0007 now has `GliderSlotsTest` (found and fixed a random-draw divergence from vanilla with 2+ glider slots), 0016 `AuroraCommandErrorsTest` written before the move, 0021 `AwfChunkNbtTest`; 0009 (`ScratchReentrancyTest`), 0011 (`ScratchBufferConfinementTest`) and 0012 (`IsInWallBoundsTest`) were already covered, and 0010 now has `RandomTickPosEscapeTest` (the capture points copy the reused random-tick `MutableBlockPos`); not proven: that no block's `randomTick` itself stores `pos`; no policy-script enforcement

---

# L. SourbyClip / bootstrap

- [-] **P0** downloader timeout audit — historical utility audit plus SourbyClip connect/read timeouts tested; **SourbyClip 3.1.3 (`e5de0f4b`, private branch `feat/transfer-deadline`) adds a whole-transfer deadline (600 s unknown size, else size/64 KiB/s clamped 120–1800 s; `sourbyclip.transferDeadlineSeconds`) and a byte cap (`sourbyclip.maxTransferBytes`, 256 MiB; expected size + 1% when known)**, pinned in the re-pin change (jar `56d317b8…435e`); 48 private Java tests incl. `BoundedTransferTest` (trickle body/headers, stall, endless, oversized, truncated) pass; 2026-10-22 local run (claude-clip worktree, PARTIAL, slim jar `c730ebf4…06ed052` with SourbyClip 3.1.3 `e5de0f4b`, built from HEAD `5851c8e7` plus the uncommitted re-pin, not certified): `clip313-cold-2 --no-seed-cache` downloaded the Mojang jar (60.9 MB) + 112 SHA-256-verified entries through the bounded transfer path from the real mirrors (one `Transfer of me.lucko:spark-paper:1.10.180:jar ... HTTP 404; not retrying this source` then the next mirror), classpath 15.9 s, `Done` 17.686 s, stop exit 0, no `.part` left; offline cold boot (`clip313-cold-offline-1`, empty cache) exits 1 naming `cache/mojang_26.2.jar` and its source URL. Open: push of the private commit + CI build of the pin, remote/Linux cold download, a real slow/oversized network source (`architecture/bootstrap-download-audit.md`)
- [-] **P0** retry/failure behavior audit — SPEC B16 launcher exit status and transfer loop shipped in repo bootstrap 3.0.22; server-side `LibDownloader` retries 3 attempts with 2 s then 4 s backoff; **SourbyClip 3.1.3 now retries a single source (including the Mojang jar) on transient failures — connect error, 30 s idle, truncated body, HTTP 5xx/408/429 — `sourbyclip.transferRetries` times (default 2, backoff 1 s doubling); deadline/cap/SHA-256/other 4xx fall through to the next source without retry** (`transientHttpStatusIsRetriedUntilTheSourceRecovers`, `originalJarSingleSourceIsRetriedThenDownloaded`, loopback only); 2026-10-22 local run (claude-clip worktree, PARTIAL, slim jar `c730ebf4…06ed052` with SourbyClip 3.1.3 `e5de0f4b`, built from HEAD `5851c8e7` plus the uncommitted re-pin, not certified): `clip313-cold-2 --no-seed-cache` downloaded the Mojang jar (60.9 MB) + 112 SHA-256-verified entries through the bounded transfer path from the real mirrors (one `Transfer of me.lucko:spark-paper:1.10.180:jar ... HTTP 404; not retrying this source` then the next mirror), classpath 15.9 s, `Done` 17.686 s, stop exit 0, no `.part` left; offline cold boot (`clip313-cold-offline-1`, empty cache) exits 1 naming `cache/mojang_26.2.jar` and its source URL. Not exercised: a real transient failure on a real network (the local boot saw only a 404 fallback), remote/Linux host (needs-linux/panel); push + CI build pending
- [-] **P0** SHA/cache validation audit — SourbyClip original-JAR staging, failed-download preservation and offline/cache tests pass; 3.1.2 is pinned (`5a3806f1`); remote cold-download qualification pending (`architecture/bootstrap-download-audit.md`); 2026-10-20 local run (server-w1, PARTIAL, jar `f84e9c10…` built from HEAD `69189766` on a dirty tree, not certified): `server-w1-cold-1 --no-seed-cache` with pinned SourbyClip 3.1.2 downloaded the Mojang jar + 112 SHA-256-verified entries from the real mirrors, ready in 26.7 s (`Done` 8.755 s), stop exit 0; offline cold boot (`server-w1-cold-offline-1`, empty cache) exits 1 naming `cache/mojang_26.2.jar`. Not exercised: tampered-cache hash mismatch (the harness can only seed the shared cache), remote/Linux host (needs-linux/panel)
- [-] **P1** concurrency/boundedness audit — SourbyClip download worker capacity/lifecycle tests pass (pool cap `sourbyclip.downloadThreads` 1-16, default 4; thread creators `BootstrapWorkers.create`, `Downloader` `supplyAsync(..., worker)`, the ServerMain thread, and since 3.1.3 the JDK `HttpClient`'s own daemon threads); 3.1.3 bounds each transfer attempt by deadline and byte cap, so a batch's worst case is now finite (per library ≤ 4 mirrors × one deadline or 3 × 30 s idle + backoff); open: the pool still drains queued work after a failure (no `shutdownNow`), private push + CI pending
- [x] **P1** thread/executor ownership audit — SourbyClip batch workers joined, location lookup removed in 3.1.2 (SEC-5), HTTP client closed; controlled lifecycle/process tests pass; the official pin is 3.1.2 (`5a3806f1`)
- [x] **P1** first-boot failure recovery test — CI run 450
- [x] **P1** offline-after-success test — CI run 450
- [x] **P2** document required remote repositories and fallback order — `architecture/bootstrap-download-audit.md`, launcher cache/embedded/original/Maven order and historical utility scope kept explicit

---

# M. Release qualification

Before marking the next performance release stable:

- [x] **P0** patch regeneration clean — CI runs 437–442
- [x] **P0** all Java tests pass — CI runs 437–442
- [x] **P0** server boot test pass — CI boot steps (plain, bridge, AWF) in run 442
- [x] **P0** clean shutdown pass — same
- [ ] **P0** no known region ownership regression; 2026-10-20 evidence, agent test server 2026-10-20 (server-w1, PARTIAL, functional only, not certified; jar `f84e9c10…` built from HEAD `69189766` on a dirty tree, Apple M2 16 GiB, shared machine load avg 6-12, no players, no plugins): scenario `server-w1-ownership-1` (guard-severity default LOG), 28 steps ok: three forceloaded areas (0,0 / 160,0 / 2000,2000), mobs in each, cross-region console teleports, region merge via forceload bridge, `save-all`, 30 s idle, stop exit 0; 0 "Thread failed main thread check"/cross-region/guard lines in `server.log`, plus 0 in the 8 persistence/crash boots. Not covered: connected players crossing regions, bridged plugins (GuardViolations quarantine), soak, quiet-machine run; left `[ ]`
- [ ] **P0** no known persistence/world corruption regression; 2026-10-20 evidence, agent test server 2026-10-20 (server-w1, PARTIAL, functional only, not certified; jar `f84e9c10…` built from HEAD `69189766` on a dirty tree, Apple M2 16 GiB, shared machine load avg 6-12, no players, no plugins): region files 61/61 (`server-w1-persist-region-1..4`: write, 2 clean restarts, churn + SIGKILL exit -9, recover; 64/64 blocks, 24/24 armor stands, game time advanced each boot, 2352 chunks intact) and AWF FILE 65/65 (9 stores, no `.mca` while attached), 0 corruption markers in 8 logs; plus Redis outage run (stored markers kept, regenerated chunk never saved). Not covered: player data round trip, stop under client load, multi-world, soak; left `[ ]`
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
0203e69: 65 tests passed. Subsequent in-progress baseline edits are separate work.
Cold bootstrap on a new cache failed the Mojang download hash check and exited 0;
that is tracked under SourbyClip (SPEC B16), not recorded as a successful boot.
Runtime validation uses a cached Mojang JAR verified against META-INF/download-context.
