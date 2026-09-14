# Product Requirements Document

## SourbyCraft Aurora Engine — Independent Minecraft Runtime & Performance Architecture

**Project:** SourbyCraft  
**Repository:** `YanIanZ/SourbyCraft`  
**Active Branch:** `26.2`  
**Minecraft Target:** 26.2  
**Runtime:** Java 25  
**Architecture:** Aurora Engine  
**Status:** Active Product & Engineering PRD  
**Primary Focus:** Speed, Reliability, Performance, Stability, Efficiency, Independence  

---

# 1. Executive Summary

Aurora Engine is the next-generation runtime and performance architecture of SourbyCraft.

Aurora is no longer defined as a thin optimization layer on top of Canvas, Folia, or any other upstream server architecture. Paper, Folia, and Canvas remain valuable upstream implementation sources and compatibility inputs, but they no longer define the architectural limits of SourbyCraft.

The core product direction is:

> SourbyCraft must become capable of owning and evolving the execution model of Minecraft itself.

Aurora may modify, replace, reorganize, or reimplement Minecraft/NMS execution paths when doing so measurably improves speed, reliability, stability, resource efficiency, or maintainability.

Aurora is therefore not restricted to:

- Folia's region scheduler design,
- Canvas's implementation boundaries,
- Paper's single upstream structure,
- existing helper-patch conventions,
- existing NMS ownership assumptions when they can be safely redesigned.

The target is not to delete all upstream code.

The target is:

> SourbyCraft owns the runtime contract and can replace any implementation detail without changing the product identity or operational model.

---

# 2. Product Vision

Aurora Engine should make SourbyCraft feel and behave like its own server engine.

The long-term product model is:

```text
                         SOURBYCRAFT
                              │
                        AURORA ENGINE
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
    Runtime Core         Execution Core        Observability
        │                     │                     │
 Configuration          Scheduling Model       Telemetry
 Lifecycle              Entity / AI            Diagnostics
 Services               Chunk / World          Spark Bridge
                        Network / Storage       HUD / /perf
                              │
                              ▼
                     Minecraft / NMS
                              │
                     Compatibility Layer
                              │
                  Paper / Folia / Canvas APIs
```

The important difference is that Folia becomes one compatibility and implementation source, not the architectural ceiling.

---

# 3. Primary Product Goals

Aurora Engine has six primary goals.

## 3.1 Speed

Reduce latency and execution cost in critical Minecraft paths.

Focus on:

- lower MSPT,
- lower p95/p99 tick latency,
- faster entity processing,
- faster AI,
- faster chunk loading/generation,
- faster packet processing,
- faster world save/serialization,
- faster startup where practical.

## 3.2 Reliability

Performance must remain predictable under sustained load.

Aurora must reduce:

- queue explosions,
- uncontrolled worker growth,
- scheduler starvation,
- deadlocks,
- stalled I/O,
- invalid async state,
- incomplete shutdown,
- silent data corruption.

## 3.3 Performance

Optimization must target actual CPU, memory, allocation, scheduler, and I/O bottlenecks.

No optimization is considered complete without evidence.

## 3.4 Stability

Aurora must prioritize:

- deterministic behavior,
- safe ownership,
- plugin compatibility,
- persistence correctness,
- crash recovery,
- controlled failure modes.

## 3.5 Efficiency

Aurora must perform more useful Minecraft work per unit of hardware.

Target metrics include:

```text
players / CPU core
players / GB RAM
entities / MSPT
chunks / CPU-second
packets / CPU-second
allocation bytes / player
```

## 3.6 Independence

SourbyCraft must progressively own:

- runtime execution,
- configuration,
- scheduler contracts,
- performance semantics,
- diagnostics,
- build identity,
- profiler metadata,
- release model,
- NMS optimization strategy.

---

# 4. Aurora Is Not Folia-Limited

This PRD explicitly removes Folia architecture as a hard product constraint.

Folia remains valuable for:

- region ownership concepts,
- plugin scheduling semantics,
- region-threaded compatibility,
- proven concurrent-world techniques.

However, Aurora may evolve beyond Folia where measurement and correctness justify it.

Aurora is allowed to introduce execution strategies that Folia does not provide.

Examples include:

- hybrid region/task execution,
- per-subsystem worker models,
- read-only parallel jobs,
- snapshot-based asynchronous computation,
- dedicated chunk generation execution,
- specialized entity/AI workers,
- bounded background serialization,
- staged world-save pipelines,
- adaptive internal algorithms,
- Aurora-owned scheduling primitives.

This does NOT mean unsafe cross-thread Minecraft mutation is allowed.

It means:

> Thread ownership rules are defined by Aurora's correctness model, not permanently frozen to one upstream scheduler implementation.

---

# 5. Compatibility vs Architecture

Aurora separates compatibility from internal architecture.

The following may remain compatible:

```text
Bukkit API
Paper API
Folia scheduling API where supported
existing plugin expectations
```

while internally Aurora may use a different implementation.

Target:

```text
Plugin/API Contract
        │
        ▼
Aurora Compatibility Layer
        │
        ▼
Aurora Runtime
```

This allows SourbyCraft to evolve without forcing every plugin to understand its internals.

---

# 6. Architectural Principles

Every Aurora implementation must follow these principles.

1. Correctness before benchmark scores.
2. Measured optimization before speculative optimization.
3. Explicit ownership before shared mutable state.
4. Bounded concurrency before uncontrolled parallelism.
5. Lower tail latency, not only lower averages.
6. No automatic gameplay degradation.
7. No automatic config mutation.
8. Direct NMS optimization is allowed when it is the best implementation location.
9. Sourby-owned services should live in Sourby-owned source.
10. Upstream compatibility must not become permanent architectural lock-in.

---

# 7. Aurora Runtime Model

Aurora will gradually converge on:

```text
AuroraRuntime
├── AuroraConfig
├── AuroraLifecycle
├── AuroraScheduler
├── AuroraExecutionModel
├── AuroraTelemetry
├── AuroraMemory
├── AuroraEntityEngine
├── AuroraAIEngine
├── AuroraChunkEngine
├── AuroraWorldEngine
├── AuroraNetworkEngine
├── AuroraStorageEngine
├── AuroraDiagnostics
└── AuroraCompatibility
```

Not every component must become a Java class with exactly these names.

They represent ownership domains.

---

# 8. Execution Model Independence

Aurora must not assume that one scheduler model is correct for every subsystem.

The execution model should classify work into categories.

```text
REGION MUTATION
CPU COMPUTE
BLOCKING I/O
NETWORK EVENT LOOP
SERIALIZATION
BACKGROUND MAINTENANCE
DIAGNOSTICS
```

Each category may use the execution strategy best suited for it.

---

# 9. Region-Compatible Game State

Region-local mutation remains an important safe default.

Game state such as:

- entities,
- blocks,
- chunks,
- inventories,
- players,
- block entities,
- world-local gameplay state,

must not be concurrently mutated without explicit ownership.

Aurora may redefine ownership boundaries, but the new model must be provably safe.

---

# 10. Aurora Scheduler

Aurora should progressively define its own scheduler contract.

The scheduler should support at minimum:

```text
region-bound tasks
world tasks
player-bound tasks
CPU work
blocking I/O
scheduled delayed work
shutdown-aware tasks
cancellable work
```

Long-term, the runtime should not require direct coupling to concrete Folia/Canvas scheduler internals.

---

# 11. Hybrid Scheduling

Aurora may use a hybrid model.

Example:

```text
Gameplay mutation
      ↓
region owner

Path calculation
      ↓
CPU worker using immutable snapshot
      ↓
region validation + commit

Database / HTTP
      ↓
virtual thread
      ↓
region callback
```

This enables more performance freedom without sacrificing correctness.

---

# 12. Aurora Entity Engine

Aurora should directly optimize Minecraft entity execution.

Primary targets:

```text
Entity.tick
LivingEntity.tick
Mob.tick
entity movement
entity lookup
entity tracking
collision
item entities
projectiles
metadata synchronization
```

Focus areas:

- reduce repeated world lookup,
- reduce broad spatial scans,
- reduce allocation,
- reduce unnecessary state comparison,
- reduce duplicate tracking work,
- improve spatial locality,
- avoid temporary collection churn.

---

# 13. Aurora AI Engine

AI is a major performance domain.

Targets include:

```text
GoalSelector
TargetSelector
Brain
Sensor
Behavior
PathNavigation
PathFinder
NodeEvaluator
```

Aurora may redesign AI computation architecture when compatible with expected gameplay behavior.

Potential approaches:

- snapshot-based path calculations,
- safe asynchronous path search,
- cached immutable navigation inputs,
- better invalidation,
- reduced target rescanning,
- reduced duplicate sensor work,
- locality-aware entity queries.

No lag-triggered AI disabling is allowed unless explicitly operator-controlled.

---

# 14. Aurora Collision Engine

Collision paths must be profiled as first-class hot paths.

Targets:

- entity collision,
- block collision,
- voxel shapes,
- AABB queries,
- movement resolution,
- collision list generation.

Efficiency goals:

- fewer temporary objects,
- fewer duplicate shape lookups,
- safe scratch reuse,
- primitive coordinate calculations where appropriate,
- faster query filtering.

---

# 15. Aurora Chunk Engine

Aurora should own the optimization strategy for chunk lifecycle.

Targets include:

```text
ChunkHolder
ChunkMap
ServerChunkCache
DistanceManager
tickets
load
integration
unload
generation
sending
saving
```

Goals:

- lower holder lookup overhead,
- lower ticket processing cost,
- faster chunk integration,
- better queue control,
- reduced duplicate map access,
- reduced key conversion,
- lower chunk retention.

---

# 16. Aurora Generation Engine

Chunk generation should be evaluated independently of gameplay mutation.

Targets:

- terrain noise,
- biome calculation,
- structures,
- lighting,
- heightmaps,
- decoration.

Aurora may use dedicated compute scheduling where:

- inputs are isolated,
- determinism is preserved,
- outputs are validated before world commit,
- scheduling overhead is justified by measurement.

---

# 17. Aurora World Engine

Targets include:

```text
ServerLevel
scheduled ticks
random ticks
block updates
fluid updates
weather
world events
redstone-related propagation
```

Shared `ServerLevel` mutable scratch state should be treated as unsafe by default under concurrent execution.

Aurora must make ownership explicit.

---

# 18. Aurora Block Engine

Aurora should profile and optimize block-intensive systems.

Priority candidates:

- hoppers,
- furnaces,
- comparators,
- redstone,
- containers,
- block entity ticking,
- neighbor updates.

Goals:

- reduce repeated inventory scans,
- reduce unnecessary dirty propagation,
- reduce duplicate state access,
- improve event batching where semantics remain unchanged.

---

# 19. Aurora Network Engine

Aurora must optimize the full packet pipeline.

Targets:

```text
Netty decode
Minecraft packet decode
packet handling
packet encode
compression
flush
chunk packets
entity tracking packets
buffer copying
```

Netty event loops must never be used for unrelated blocking work.

---

# 20. Low-Copy Networking

Aurora should identify avoidable copy chains.

Example:

```text
object
  ↓
temporary byte[]
  ↓
ByteBuffer
  ↓
new byte[]
  ↓
ByteBuf
```

Where correctness permits, reduce intermediary buffers and allocations.

---

# 21. Aurora Serialization Engine

Targets include:

- NBT,
- chunk serialization,
- entity serialization,
- player data,
- DataComponent serialization,
- packet serialization.

Goals:

- fewer copies,
- fewer temporary buffers,
- better buffer ownership,
- less repeated encoding,
- reduced serialization allocation.

---

# 22. Aurora Storage Engine

Aurora should improve persistence performance while maintaining durability.

Targets:

- chunk saving,
- region files,
- player data,
- world metadata,
- async save pipelines.

Storage optimization must not weaken:

```text
persistence
ordering
crash recovery
world consistency
```

---

# 23. Aurora Memory Engine

Memory efficiency is a first-class performance feature.

Goals:

- reduce hot-path allocation,
- reduce retained heap,
- reduce duplicate caches,
- reduce buffer waste,
- reduce wrapper allocation,
- reduce GC pressure.

---

# 24. Allocation Policy

Allocation reduction follows:

```text
profile
  ↓
identify dominant source
  ↓
understand lifetime
  ↓
change representation
  ↓
benchmark
```

Aurora must not replace temporary allocation with unsafe permanent shared objects.

---

# 25. Primitive Data Structures

Primitive collections may be used in measured hot paths when boxing is material.

Candidates include:

```text
Long → chunk lookup
int → entity identifiers
packed coordinate sets
primitive counters
```

No library should be introduced without evaluating maintenance and runtime cost.

---

# 26. Cache Architecture

Every Aurora cache must define:

```text
owner
key
value
size bound
lifetime
invalidation
thread model
cleanup
```

Unbounded caches are prohibited unless the domain is naturally and demonstrably bounded.

---

# 27. Java 25 Modernization

Aurora uses Java 25 as the engineering baseline.

Use stable Java features where useful:

- records,
- sealed types,
- pattern matching,
- switch expressions,
- immutable collections,
- virtual threads for suitable blocking I/O.

Do not rewrite code merely for style.

---

# 28. Java Hot-Path Policy

In measured hot paths, review use of:

- streams,
- Optional,
- boxing,
- reflection,
- lambda chains,
- temporary collections,
- strings,
- general-purpose abstractions.

The goal is not to ban modern Java.

The goal is to use it appropriately.

---

# 29. Virtual Threads

Virtual threads are permitted for blocking operations such as:

- HTTP,
- database calls,
- filesystem operations,
- external service calls.

They are not the default replacement for:

- region/game ticks,
- CPU-heavy AI,
- chunk generation,
- Netty event loops.

---

# 30. Aurora Configuration Ownership

Aurora must own configuration for Sourby-specific engine behavior.

Target logical layout:

```toml
[aurora.performance]
[aurora.scheduler]
[aurora.entity]
[aurora.ai]
[aurora.chunk]
[aurora.world]
[aurora.network]
[aurora.memory]
[aurora.storage]
[aurora.diagnostics]
```

Existing Canvas/Paper configuration remains supported while required for compatibility.

---

# 31. Typed Immutable Configuration

Runtime configuration should become typed immutable structures.

Example:

```java
public record AuroraEntitySettings(
    boolean optimizedCollision,
    boolean asyncPathfinding
) {}
```

Hot paths must not parse dotted config paths repeatedly.

---

# 32. Configuration Lifecycle

Every setting must be classified:

```text
LIVE
RESTART_REQUIRED
IMMUTABLE_FOR_RUN
```

Reload must never claim to apply a setting that still requires restart.

---

# 33. No Automatic Configuration

Aurora must never silently rewrite operator performance configuration.

Forbidden:

- TPS-based config changes,
- MSPT-based gameplay reduction,
- automatic view-distance reduction,
- automatic simulation-distance reduction,
- automatic mob-limit reduction,
- automatic AI reduction,
- automatic compression changes,
- automatic JVM flag changes,
- automatic GC selection,
- automatic config rewrite.

Aurora optimizes implementation, not administrator intent.

---

# 34. Internal Adaptive Algorithms

Aurora may adapt internal algorithms when observable semantics stay unchanged.

Examples:

```text
small collection → linear scan
large collection → indexed lookup
```

or:

```text
small batch → direct processing
large batch → grouped processing
```

This is permitted because it changes implementation, not gameplay configuration.

---

# 35. Observability

Aurora must understand its own runtime.

Canonical flow:

```text
engine counters
region/runtime counters
GC events
network counters
scheduler counters
        ↓
Aurora/Sourby Metrics Runtime
        ↓
immutable PerformanceSnapshot
        ↓
/tps /mspt /ram /perf / HUD / Spark
```

---

# 36. Spark Integration

Spark remains the profiler backend for the current milestone.

No Spark web-viewer fork is required at this stage.

Platform metadata should report:

```text
SourbyCraft
Build44 (MC:26.2)
```

Aurora should enrich server-side metadata where possible without duplicating expensive scans.

---

# 37. `/perf` as Primary Diagnostic Surface

`/perf` becomes the primary operational command.

Target command tree:

```text
/perf
/perf tick
/perf cpu
/perf memory
/perf gc
/perf region
/perf player
/perf chunks
/perf entities
/perf network
/perf scheduler
/perf plugins
/perf health
/perf history
/perf profile
```

---

# 38. Aurora Health Model

Runtime health states:

```text
EXCELLENT
HEALTHY
PRESSURE
CRITICAL
```

Health classification must diagnose.

It must not automatically alter gameplay settings.

---

# 39. Performance Incident History

Maintain a bounded history for significant events:

- MSPT spikes,
- slow execution domains,
- GC pauses,
- worker backlog,
- memory pressure,
- network queue spikes,
- storage stalls.

The history must be bounded and lightweight.

---

# 40. Performance UX

Aurora diagnostics should feel powerful without creating overhead.

Target presentation:

```text
AURORA PERFORMANCE
TPS      20.00
MSPT      7.8ms
P95      11.3ms
CPU        34%
RAM        51%
STATUS  EXCELLENT
```

HUD/commands use shared snapshots, not per-player scans.

---

# 41. Tail-Latency Focus

Aurora must optimize for consistency.

Priority metrics:

```text
average MSPT
p50
p95
p99
max
```

A low average with severe p99 spikes is not considered healthy performance.

---

# 42. Benchmark Requirements

Required benchmark profiles:

- idle,
- 10-player workload,
- 50-player workload,
- 100-player workload,
- entity stress,
- AI stress,
- chunk generation stress,
- chunk load/unload stress,
- save stress,
- network stress.

---

# 43. Representative Gameplay Workload

Benchmarks must include real gameplay behaviors such as:

- movement,
- teleportation,
- chunk traversal,
- combat,
- mob spawning,
- inventory activity,
- block placement/breaking,
- plugin callbacks.

Synthetic status pings alone are not sufficient.

---

# 44. Measurement Metadata

Every performance report should record:

```text
baseline SHA
candidate SHA
Java version
JVM flags
OS
CPU
RAM
world seed
plugin set
config identity
warmup duration
measurement duration
```

---

# 45. Performance Regression Gates

Recommended policy:

```text
>3% unexplained regression → investigate
>5% regression → written justification
>10% regression → block merge
```

Exceptions are allowed for correctness/security fixes when documented.

---

# 46. Performance Success Targets

Project goals, not guaranteed claims:

```text
CPU usage              ↓ 10–20%
p95 MSPT               ↓ 10%+
hot-path allocation    ↓ 15%+
GC pressure            ↓ measurable
player-per-GB capacity ↑ measurable
```

Performance claims must only be published after controlled validation.

---

# 47. Reliability Requirements

Aurora must provide:

- bounded worker queues,
- explicit timeout behavior,
- deterministic cancellation,
- controlled shutdown,
- failure isolation,
- no infinite retry loops,
- no silent queue growth,
- no stuck non-daemon workers.

---

# 48. Stability Requirements

Every major optimization must validate:

- server boot,
- clean shutdown,
- restart,
- world persistence,
- player reconnect,
- chunk unload/reload,
- entity unload/reload,
- cross-region movement,
- plugin interaction,
- extended soak.

---

# 49. Persistence Requirements

Storage-affecting optimization must test:

- normal stop,
- restart,
- high-load saving,
- chunk stress,
- interrupted operations,
- crash recovery where appropriate.

Speed must never come from silently weakening save guarantees.

---

# 50. Memory Leak Requirements

After workload removal:

- player state should be collectible,
- unloaded chunks should be collectible,
- unloaded worlds should release Aurora state,
- completed tasks/futures should retire,
- queues should return to steady state,
- heap should stabilize.

---

# 51. Patch Architecture

Aurora supports direct NMS patches and Sourby-owned implementations.

Patch categories:

```text
AURORA-FOUNDATION
AURORA-ENTITY
AURORA-AI
AURORA-CHUNK
AURORA-WORLD
AURORA-SCHEDULER
AURORA-NETWORK
AURORA-MEMORY
AURORA-STORAGE
AURORA-COMPATIBILITY
```

---

# 52. Direct NMS Patch Rule

Direct patching is preferred when optimization is local to a hot algorithm.

Example:

```text
Mob.aiStep()
  ↓
measured duplicate entity lookup
  ↓
optimize directly in NMS
```

Do not add an external manager call simply to make the code appear architecturally pure.

---

# 53. Sourby-Owned Service Rule

Use Sourby-owned source when the subsystem has independent lifecycle or broad reuse.

Examples:

- telemetry,
- configuration,
- diagnostics,
- health,
- scheduler adapters,
- profiler bridge,
- background I/O.

Preferred pattern:

```text
small upstream hook
      ↓
Aurora service
      ↓
Sourby-owned implementation
```

---

# 54. Patch Acceptance Template

Every significant performance patch must document:

```text
Problem
Profiler Evidence
Root Cause
Implementation
Execution Ownership
Thread Safety
Memory Impact
Compatibility Impact
Before Metrics
After Metrics
Regression Coverage
Rollback Plan
```

---

# 55. Folia Dependency Reduction

Folia-derived behavior should be classified as:

```text
COMPATIBILITY CONTRACT
USEFUL IMPLEMENTATION
TEMPORARY DEPENDENCY
REPLACEABLE
OBSOLETE
```

Aurora does not remove working Folia concepts just for branding.

It replaces them when a better Aurora-owned implementation is justified.

---

# 56. Canvas Dependency Reduction

Canvas-specific code should be audited for:

- hard build dependency,
- runtime dependency,
- config dependency,
- scheduler dependency,
- Spark integration dependency,
- branding-only dependency.

The long-term target is that Canvas components are replaceable inputs rather than runtime identity.

---

# 57. Build Independence

A clean SourbyCraft checkout must be able to produce its own server artifact reproducibly.

Target:

```text
SourbyCraft repository
       ↓
pinned source inputs
       ↓
Aurora build pipeline
       ↓
SourbyCraft server artifact
```

No separate Canvas server JAR should be required at runtime.

---

# 58. Free-Running Requirement

After initial dependency acquisition, normal runtime should not require:

- Canvas remote services,
- Paper remote services,
- Folia remote services,
- external tuning services.

Optional update/profile upload features remain operator-controlled.

---

# 59. Plugin Compatibility

Aurora should preserve expected Bukkit/Paper compatibility where practical.

Folia-compatible plugin behavior may remain supported through a compatibility layer.

Internal scheduler independence must not unnecessarily break plugin APIs.

---

# 60. Security

Performance optimization must not weaken:

- packet validation,
- malformed-data protection,
- rate limits,
- resource-exhaustion protection,
- world/data integrity.

A faster insecure server is a failed implementation.

---

# 61. Performance Efficiency Features

Aurora should actively pursue the following features when supported by evidence:

- allocation-aware hot paths,
- low-copy packet pipelines,
- primitive collections in measured hotspots,
- typed immutable configuration,
- bounded work queues,
- lifecycle-managed executors,
- safe snapshot-based asynchronous computation,
- region/locality-aware entity queries,
- efficient chunk-holder lookup,
- serialization batching,
- bounded telemetry buffers,
- shared immutable performance snapshots,
- efficient cache invalidation,
- reduced duplicate state calculations,
- reduced synchronization contention.

---

# 62. Non-Goals

Aurora will not:

- automatically reduce gameplay quality,
- automatically rewrite config,
- claim every upstream subsystem as Sourby-written,
- remove attribution,
- rewrite every system merely for branding,
- create more threads without measured reason,
- parallelize inherently shared mutation unsafely,
- trade persistence correctness for benchmark numbers.

---

# 63. Development Program

## Phase 0 — Correctness Baseline

- complete persistence checks,
- complete concurrency soak,
- complete ownership audits.

## Phase 1 — Certified Performance Baseline

- real-player workloads,
- entity stress,
- chunk stress,
- network stress,
- JFR CPU/allocation ranking.

## Phase 2 — Aurora Configuration

- typed config,
- lifecycle classes,
- legacy-safe migration reads,
- no automatic rewrite.

## Phase 3 — Entity & AI Engine

- optimize top measured entity/AI bottlenecks,
- validate gameplay semantics,
- benchmark.

## Phase 4 — Chunk & World Engine

- holder/ticket optimization,
- generation,
- world ticking,
- saving.

## Phase 5 — Scheduler Independence

- define Aurora execution contracts,
- isolate direct Folia/Canvas scheduler coupling,
- introduce Aurora-owned scheduling implementation where justified.

## Phase 6 — Network & Serialization

- low-copy packet paths,
- compression profiling,
- queue control,
- serialization efficiency.

## Phase 7 — Memory & GC

- top allocation removal,
- cache audit,
- retention testing,
- RSS efficiency.

## Phase 8 — Build & Runtime Independence

- isolate remaining Canvas-specific build assumptions,
- validate clean checkout build,
- validate offline-after-bootstrap operation.

## Phase 9 — Release Qualification

- regression benchmarks,
- persistence testing,
- 2h+ soak,
- plugin compatibility,
- measured release notes.

---

# 64. Definition of Done

Aurora Engine milestone is considered complete when:

1. SourbyCraft defines Aurora as its runtime architecture.
2. Folia is no longer the architecture authority.
3. Canvas is no longer the public runtime identity.
4. Java 25 is consistently used.
5. Aurora configuration is first-party and typed.
6. No automatic performance config mutation exists.
7. Certified gameplay benchmarks exist.
8. Entity hot paths have been profiled and improved where justified.
9. AI hot paths have been profiled and improved where justified.
10. Chunk/world hot paths have been profiled and improved where justified.
11. Network hot paths have been profiled and improved where justified.
12. Memory allocation/retention has been profiled and improved where justified.
13. Aurora has explicit scheduler/execution contracts.
14. At least one execution subsystem operates behind an Aurora-owned contract rather than a concrete Folia/Canvas implementation dependency.
15. No new region/state ownership regression exists.
16. No world persistence regression exists.
17. No unbounded Aurora worker queue exists.
18. Clean shutdown succeeds.
19. Extended soak succeeds.
20. Heap/queues stabilize after load removal.
21. `/perf` reports Aurora runtime health.
22. Spark consumes SourbyCraft/Aurora telemetry.
23. Build is reproducible from the SourbyCraft repository.
24. No separate Canvas runtime is required.
25. Remaining Folia/Canvas dependencies are explicit, intentional, and replaceable in principle.

---

# 65. Final Product Principle

Aurora Engine must be:

```text
FAST
without sacrificing correctness

RELIABLE
without hiding failures

PERFORMANT
without fake benchmark tricks

STABLE
under sustained load

EFFICIENT
with CPU, memory, I/O and network resources

INDEPENDENT
without unnecessary compatibility breakage

MODERN
without needless complexity
```

The final direction is:

> SourbyCraft Aurora is not a Folia server with extra patches.
>
> Aurora is a SourbyCraft-owned Minecraft runtime architecture that may use, replace, or evolve beyond Paper/Folia/Canvas implementation details whenever a better measured and validated architecture exists.
