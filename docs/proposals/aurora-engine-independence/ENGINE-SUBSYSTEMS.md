# Aurora Engine Subsystems — Focused Optimization Backlog

**Status: planned feature scopes, not implemented or promised performance improvements.** Treat all changes as isolated hypotheses requiring a baseline and owner qualification.

## Chunk & World Engine

**Current basis:** Paper/Moonrise-derived chunk pipeline, Folia-derived local region ownership, Aurora hooks and AWF. Source reference: [engine-chunk-world](../../architecture/engine-chunk-world.md), [chunk-workers](../../architecture/chunk-workers.md).

**Near-term objective:** make chunk load/save/unload, player view subscription and chunk generation observably predictable under concurrency without rewriting the chunk lifecycle all at once.

Experiments: measure chunk-holder lookup allocations; ticket churn; queued vs in-flight generation; load/unload false positives; main/region handoff cost; off-thread world read snapshots; chunk serialization and I/O budget. Validate ticket lifetimes and every callback on the correct region. A faster lookup with wrong unload semantics is rejected.

**Avoid:** blind worker count increases. Existing measured 2-vs-6 chunk-worker example has slower median but better tail in a specific 10-player workload; neither is universally better.

## Entity and AI Engine

**Current basis:** source-owned async pathfinding snapshot computation, validity/generation checks, `perf/AsyncPathProcessor`; [engine-entity-ai](../../architecture/engine-entity-ai.md).

Plan: versioned path request and target retirement semantics, bounded goal-selection work, immutable AI snapshot shape, duplicate task suppression, cancellable search, spatial query hot-path investigation, entity tracking rate measurements. Benchmark typical and p99 region tick under realistic mobs. Stale results must never steer wrong entity or tick outside owner.

**Avoid:** promising “all AI multithreaded.” Entity state mutation stays on its region; only independent CPU computations can be offloaded.

## Scheduler and Execution Engine

**Current basis:** Aurora execution contracts and Folia-owned region ticking; Resource Governor currently has three governed lanes. Plan: complete admission/rejection metrics and fair queueing, per-service limits, structured operation lifecycle, wrong-thread diagnostics, startup/shutdown order.

**Avoid:** enabling parked experimental EDF without independent proof, or shipping automatic thread resizing against existing maintainer policy.

## Network Engine

**Current basis:** Paper/Netty networking and Aurora diagnostic sources; [engine-network](../../architecture/engine-network.md). Plan: measure packet creation/copying, compression allocations, flush latency, entity tracker fanout, backpressure propagation; improve only where server-owned changes can preserve Minecraft protocol compatibility. ProtocolLib/PacketEvents/legacy plugin bridges demand regression tests.

**Avoid:** unbounded packet batching (increases latency) and exposing unauthenticated admin operations.

## Storage and AWF

**Current basis:** `RegionFileStorageHooks`, AWF store/registry and Redis Fabric; [engine-storage](../../architecture/engine-storage.md) and [AWF architecture](../../architecture/aurora-world-fabric.md). Plan: robust durable save/unload, confirmed vs unknown commit, bounded immutable snapshot staging, parallelism separated by world/account while preserving order, recovery and backpressure monitoring.

**Avoid:** introducing a second unrelated cache that can become an alternate authoritative world store. Do not treat Redis as faster than disk without measuring end-to-end durability semantics.

## Plugin Compatibility Engine

**Current basis:** `bridge/AuroraBridge` and public Folia scheduler APIs; [bridge](../../architecture/aurora-plugin-bridge.md). Plan: strict default where owner approves, explicitly scoped compatibility tier, clear warning for unsupported unsafe legacy operations, per-plugin dispatch overhead and capability diagnostics. Do not suppress thread-safety errors merely to improve compatibility percentage.

## Performance and Memory Engine

**Current basis:** `/perf`, Spark, JFR, `MetricsRuntime`, `scripts/run_baseline.py` and A/B comparison tooling. Plan: low-allocation counters, sampling budgets, immutable telemetry snapshots, cache lifetime ownership, executor inventory, RSS/direct memory tracking and per-region p99. Do not add always-on high-overhead hooks.

## Startup, Shutdown & Recovery

**Current basis:** `SourbyCraftBootstrap` (ordered stages), `AuroraRuntime` lifecycle state, updater and AWF data flush. Plan: dependency-ordered starts/stops with bounded deadlines, optional-feature DEGRADED reasons, partial startup cleanup, exactly once finalization, controlled shutdown when storage inaccessible, startup readiness gates.

## Priority and dependency order

1. **P0 data integrity/ownership**: AWF confirmed saves, profile safety, wrong-thread and crash recovery.
2. **P1 correctness + observability**: reproducible baseline, precise hotpath and queue metrics.
3. **P2 local optimizations**: small chunk/entity/network/IO patches under measured workload.
4. **P3 architecture replacement**: only after stable adapters and rollback rehearsals.
5. **P4 advanced experimental scheduler**: research unless separate owner gate approves.

An area is “Aurora-owned” when SourbyCraft controls its semantics, lifecycle, tests and migration path. Merely renaming a Paper/Moonrise/Folia class to `Aurora` is **not** independence.
