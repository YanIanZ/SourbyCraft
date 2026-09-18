# Aurora Engine — Engine System & Patch Innovation Architecture

## SourbyCraft 26.2

**Branch:** `26.2`  
**Runtime:** Java 25  
**Minecraft:** 26.2  
**Architecture:** Aurora Engine  
**Purpose:** Define how Aurora changes Minecraft internals, how patch innovation is structured, how engine subsystems communicate, and how a performance idea moves from profiler evidence into production.

---

# 1. Core Idea

Aurora Engine is not a collection of random performance patches.

Aurora is a structured engine architecture where each patch belongs to one of three implementation paths:

```text
PATH A — Direct NMS Optimization
PATH B — Aurora Engine Service
PATH C — Compatibility / Integration Adapter
```

The correct path depends on where the problem actually lives.

The main rule is:

> Put optimization logic as close as possible to the real bottleneck, but keep shared lifecycle/stateful systems inside Aurora-owned services.

This avoids two bad extremes:

```text
Bad extreme #1:
everything becomes a giant external manager

Bad extreme #2:
everything is patched directly into upstream classes
```

Aurora uses both approaches deliberately.

---

# 2. The Three Implementation Paths

## 2.1 Path A — Direct NMS Optimization

Use direct Minecraft/NMS patches for local hot-path algorithms.

Examples:

- entity tick loop
- collision query
- GoalSelector iteration
- Brain/Sensor scanning
- chunk-holder lookup
- packet serialization
- NBT encoding
- block/fluid ticking
- tracker calculations
- primitive-coordinate math

Flow:

```text
Profiler / benchmark
        ↓
Minecraft hot path identified
        ↓
root cause is local algorithm/data structure
        ↓
direct NMS patch
        ↓
unit/regression test
        ↓
benchmark
        ↓
KEEP or REVERT
```

Example:

```text
Mob.tick
  ↓
repeated target scan
  ↓
avoid duplicate lookup
  ↓
patch Mob / GoalSelector directly
```

Do not add an external manager just to make the change look "Aurora-owned".

The patch itself is Aurora-owned because Aurora owns the optimization policy and validation process.

---

## 2.2 Path B — Aurora Engine Service

Use an Aurora-owned service when the logic:

- spans multiple classes,
- owns lifecycle,
- owns queues/workers,
- owns configuration,
- owns shared immutable snapshots,
- owns telemetry,
- requires startup/shutdown,
- needs a stable contract independent of upstream.

Examples:

- scheduler service
- execution service
- telemetry
- configuration
- async path execution
- storage pipeline
- network metrics
- performance incident history
- lifecycle
- health evaluator

Flow:

```text
Minecraft / upstream hook
        ↓
small integration patch
        ↓
Aurora-owned interface/service
        ↓
service implementation
        ↓
metrics/config/lifecycle
```

The upstream patch should stay small.

---

## 2.3 Path C — Compatibility / Integration Adapter

Use an adapter when Aurora needs to retain compatibility with an upstream system while preventing that system from defining Aurora architecture.

Examples:

- Folia scheduler adapter
- Canvas config adapter
- Spark adapter
- Paper API adapter
- upstream build metadata adapter

Flow:

```text
Aurora contract
     ↓
compat adapter
     ↓
Canvas / Folia / Paper implementation
```

The dependency direction matters.

Aurora must not become:

```text
Aurora core
   ↓
direct Canvas internals everywhere
```

The preferred direction is:

```text
Aurora core
   ↓
Aurora interface
   ↑
compat implementation
   ↓
upstream
```

---

# 3. Aurora Engine System Map

The full engine is divided into runtime domains.

```text
                           SOURBYCRAFT
                                │
                         AURORA ENGINE
                                │
      ┌───────────────┬─────────┼─────────┬───────────────┐
      │               │         │         │               │
   Runtime        Execution   Engine    Telemetry      Compatibility
      │               │       Domains      │               │
      │               │         │         │               │
 Config/Lifecycle  Scheduler    │      Metrics/Spark   Paper/Folia/Canvas
                               │
       ┌───────────────┬───────┼──────────┬───────────────┐
       │               │       │          │               │
    Entity/AI       Chunk    World     Network         Storage
       │               │       │          │               │
   Collision        Generation Blocks  Serialization    Region I/O
```

---

# 4. Runtime Flow

Aurora runtime owns startup, services, and shutdown.

```text
Minecraft bootstrap
       ↓
AuroraRuntime.start()
       ↓
AuroraConfig
       ↓
AuroraExecution
       ↓
AuroraScheduler
       ↓
AuroraTelemetry
       ↓
engine services
       ↓
RUNNING
```

Shutdown:

```text
STOPPING
   ↓
reject new work
   ↓
cancel/finish bounded tasks
   ↓
flush storage work
   ↓
stop telemetry
   ↓
shutdown executors
   ↓
STOPPED
```

No subsystem should own an executor that Aurora runtime does not know about.

---

# 5. Engine Execution Flow

Aurora classifies all work before choosing where it runs.

```text
incoming work
    ↓
classify task
    ↓
┌───────────────────────────────┐
│ GAME_STATE_MUTATION           │ → owner context
│ CPU_COMPUTE                   │ → bounded CPU pool
│ BLOCKING_IO                   │ → virtual-thread/I/O service
│ NETWORK_EVENT                 │ → Netty event loop / controlled handoff
│ SERIALIZATION                 │ → bounded serialization path
│ BACKGROUND_MAINTENANCE        │ → low-priority bounded executor
│ DIAGNOSTICS                   │ → immutable/read-only snapshot
└───────────────────────────────┘
```

This is the architectural point where Aurora becomes independent from Folia.

Folia may still implement owner-context scheduling under the hood initially, but Aurora decides the task class and contract.

---

# 6. Entity Engine Flow

```text
Entity tick begins
     ↓
Aurora ownership check / existing owner context
     ↓
NMS entity hot path
     ↓
local direct optimizations
     ↓
optional Aurora shared service
     ↓
telemetry counters
     ↓
entity tick completes
```

Direct patch candidates:

- duplicate lookup removal
- primitive position calculations
- entity-section query filtering
- metadata diff reduction
- item merge optimization
- projectile update optimization

Shared service candidates:

- entity-domain telemetry
- async compute bridge
- entity workload classification
- long-lived caches with strict bounds

---

# 7. AI Engine Flow

AI work has two paths.

## 7.1 Synchronous AI

```text
Mob owner context
      ↓
Brain / GoalSelector
      ↓
local direct optimization
      ↓
result applied immediately
```

## 7.2 Snapshot Async AI

```text
Mob owner context
      ↓
capture immutable input
      ↓
Aurora CPU_COMPUTE task
      ↓
path/decision result
      ↓
freshness validation
      ↓
owner-context commit
```

A stale result must be rejected.

Examples of freshness inputs:

- entity id
- entity lifecycle generation
- navigation version
- target version
- world identity
- position/version token

Aurora must never let an old result overwrite newer state.

---

# 8. Collision Engine Flow

```text
movement request
    ↓
broad-phase query
    ↓
candidate filtering
    ↓
voxel/AABB resolution
    ↓
movement result
```

Optimization focus:

- fewer temporary lists
- fewer wrapper objects
- fewer duplicate lookups
- better primitive math
- safe thread-local/owner-local scratch only when proven useful

Forbidden design:

```text
shared mutable collision buffer on ServerLevel
```

unless ownership guarantees prove it safe.

---

# 9. Chunk Engine Flow

```text
player/entity requires chunk
      ↓
ticket / holder lookup
      ↓
chunk state decision
      ↓
load / generation / integration
      ↓
owner-context publication
      ↓
tracking / send
      ↓
save / unload
```

Optimization points:

```text
ticket map lookup
holder lookup
state transitions
generation scheduling
integration
packet generation
save queue
unload cleanup
```

Aurora separates:

```text
chunk computation
        from
world-state commit
```

This allows independent optimization without unsafe state mutation.

---

# 10. Generation Engine Flow

```text
generation request
       ↓
immutable generation inputs
       ↓
Aurora generation compute
       ↓
noise / biome / structures / lighting
       ↓
generated result
       ↓
owner-context validation
       ↓
chunk integration
```

Aurora may use dedicated compute workers.

Requirements:

- deterministic result
- bounded queue
- cancellation
- no live-world mutation in compute stage
- measured scheduling benefit

---

# 11. World Engine Flow

```text
world owner contexts
       ↓
scheduled ticks
random ticks
block updates
fluid updates
block entities
       ↓
local NMS optimizations
       ↓
Aurora telemetry
```

Aurora must treat world-global mutable state carefully because multiple owner contexts may touch one world instance concurrently.

Any cache/scratch structure must have a defined owner.

---

# 12. Network Engine Flow

Inbound:

```text
socket
 ↓
Netty
 ↓
decode
 ↓
protocol validation
 ↓
handoff to owner context
 ↓
game-state mutation
```

Outbound:

```text
game-state event
 ↓
packet construction
 ↓
serialization
 ↓
compression
 ↓
Netty queue
 ↓
socket
```

Optimization targets:

- reduce packet copies
- reduce temporary buffers
- reduce duplicate serialization
- avoid blocking Netty
- improve queue visibility
- improve chunk-packet path

Aurora must not silently modify compression settings for performance.

---

# 13. Storage Engine Flow

```text
owner state
    ↓
immutable serialization snapshot / safe state extraction
    ↓
serialization
    ↓
bounded save queue
    ↓
region file I/O
    ↓
completion / durability semantics
```

Shutdown:

```text
STOPPING
  ↓
reject new non-critical work
  ↓
drain required save work
  ↓
flush
  ↓
close
```

Performance must never come from silently dropping persistence work.

---

# 14. Memory Engine Flow

Aurora memory work is continuous, but low overhead.

```text
allocation sources
      ↓
JFR / counters / metrics
      ↓
identify high-cost allocation
      ↓
classify:
  temporary
  retained
  cache
  duplicate
  buffer
      ↓
optimize
      ↓
heap recovery validation
```

Memory innovation paths include:

- primitive collections
- fewer temporary lists
- fewer boxed values
- immutable shared snapshots
- bounded caches
- lifecycle-aware caches
- lower-copy buffers

Do not use object pooling by default.

Pooling is accepted only when:

- allocation is proven expensive,
- retained memory is bounded,
- ownership is safe,
- benchmark proves benefit.

---

# 15. Telemetry Flow

Aurora telemetry is a one-way observation path.

```text
engine counters/events
      ↓
Aurora Metrics
      ↓
aggregation
      ↓
immutable PerformanceSnapshot
      ↓
┌─────────┬─────────┬────────┬───────┐
│ /perf   │ HUD     │ Spark  │ API   │
└─────────┴─────────┴────────┴───────┘
```

Telemetry must not:

- rewrite config,
- change gameplay,
- trigger hidden tuning.

It may diagnose and recommend only.

---

# 16. Patch Innovation Pipeline

Every performance idea must pass through a defined lifecycle.

```text
IDEA
 ↓
MEASURE
 ↓
ROOT CAUSE
 ↓
SELECT IMPLEMENTATION PATH
 ↓
PATCH / SERVICE / ADAPTER
 ↓
CORRECTNESS TEST
 ↓
THREAD-SAFETY TEST
 ↓
BENCHMARK
 ↓
SOAK
 ↓
KEEP / REWORK / REVERT
```

---

# 17. Patch Categories

Use these logical categories when naming/documenting patches:

```text
AURORA-RUNTIME
AURORA-EXECUTION
AURORA-SCHEDULER
AURORA-ENTITY
AURORA-AI
AURORA-COLLISION
AURORA-CHUNK
AURORA-GENERATION
AURORA-WORLD
AURORA-NETWORK
AURORA-STORAGE
AURORA-MEMORY
AURORA-TELEMETRY
AURORA-COMPAT
AURORA-INTEGRATION
```

---

# 18. Patch File Design

One patch should solve one coherent problem.

Preferred:

```text
AURORA-ENTITY-reduce-tracker-lookup.patch
AURORA-AI-snapshot-path-solve.patch
AURORA-CHUNK-reduce-holder-lookup.patch
AURORA-NETWORK-reduce-chunk-packet-copy.patch
```

Avoid:

```text
performance-fixes.patch
misc-optimizations.patch
aurora-improvements.patch
```

because they become impossible to benchmark, rebase, and revert independently.

---

# 19. Patch Metadata

Every meaningful performance patch should document:

```text
Problem
Profiler Evidence
Root Cause
Implementation Path
Modified Classes
Thread Ownership
Memory Impact
Behavior Impact
Compatibility Impact
Before Metrics
After Metrics
Regression Tests
Rollback Notes
```

This metadata may live in:

- commit message,
- patch header,
- docs/performance entry,
- PR description.

---

# 20. Patch Acceptance Rules

A patch should be rejected when:

- no measurable problem exists,
- the benchmark is too idle,
- the profiler percentage is misleading,
- the change adds unsafe shared state,
- it creates an unbounded cache,
- it introduces hidden gameplay reduction,
- it worsens tail latency,
- it increases rebase complexity with negligible value,
- it duplicates an upstream optimization,
- it cannot be validated.

---

# 21. Patch Innovation Types

Aurora can innovate in several ways.

## 21.1 Algorithm innovation

Examples:

- better lookup algorithm
- reduced search space
- better spatial filtering
- incremental recomputation
- cheaper invalidation

## 21.2 Data structure innovation

Examples:

- primitive maps
- compact immutable snapshots
- specialized indices
- bounded ring buffers
- locality-aware collections

## 21.3 Execution innovation

Examples:

- snapshot-based CPU offload
- dedicated compute pool
- staged pipeline
- owner-context commit
- cancellation-aware work

## 21.4 I/O innovation

Examples:

- virtual-thread blocking I/O
- bounded save pipelines
- reduced copy serialization
- batched non-gameplay writes

## 21.5 Observability innovation

Examples:

- region p95/p99
- task wait latency
- queue pressure
- allocation rate
- slow-region history
- performance incident timeline

## 21.6 Compatibility innovation

Examples:

- stable Aurora contract over replaceable upstream implementation
- compatibility adapter that allows future scheduler replacement

---

# 22. Innovation Guardrail

Aurora innovation must be aggressive in implementation but conservative in correctness.

```text
AGGRESSIVE:
algorithms
data structures
execution design
profiling
allocation reduction
parallel compute

CONSERVATIVE:
world state mutation
persistence
plugin semantics
operator config
network protocol
shutdown correctness
```

---

# 23. Aurora Scheduler Evolution Path

Aurora scheduler transition should happen in four steps.

## Step 1 — Adapter

```text
AuroraScheduler
    ↓
Folia/Canvas adapter
```

## Step 2 — Internal task model

```text
Aurora task classification
Aurora cancellation
Aurora owner context
Aurora telemetry
```

## Step 3 — Independent compute domains

```text
AI compute
generation compute
serialization
I/O
diagnostics
```

become independent from region scheduler implementation.

## Step 4 — Native owner scheduler where justified

Only after real evidence shows value.

Aurora does not rewrite Folia merely to remove the name Folia.

---

# 24. Engine Independence Path

The architecture progresses like this:

```text
PHASE 0
Canvas/Folia defines runtime
Sourby adds patches

        ↓

PHASE 1
Aurora owns config + telemetry

        ↓

PHASE 2
Aurora owns execution contracts
Folia/Canvas implements adapter

        ↓

PHASE 3
Aurora owns engine-domain services
direct NMS optimizations grow

        ↓

PHASE 4
Aurora owns scheduler-facing contract
upstream dependencies isolated

        ↓

PHASE 5
Aurora can replace upstream implementation
without changing its product architecture
```

This is full independence.

---

# 25. Example: New Entity Optimization

Suppose JFR shows entity tracking consumes significant CPU.

Correct Aurora process:

```text
JFR
 ↓
entity tracker identified
 ↓
absolute CPU verified
 ↓
root cause = repeated map lookup
 ↓
local algorithm problem
 ↓
DIRECT NMS PATCH
 ↓
benchmark
 ↓
plugin/entity behavior test
 ↓
keep
```

Incorrect process:

```text
JFR says tracker high
 ↓
create AuroraEntityOptimizationManager
 ↓
wrap every tracker call
 ↓
more indirection
```

---

# 26. Example: New Storage Optimization

Suppose world save stalls under load.

Correct Aurora process:

```text
storage latency metrics
 ↓
save queue bottleneck
 ↓
problem spans lifecycle + queue + I/O
 ↓
AURORA SERVICE
 ↓
bounded save pipeline
 ↓
durability tests
 ↓
shutdown tests
 ↓
benchmark
```

This is not a direct one-method patch because the problem spans a subsystem.

---

# 27. Example: Scheduler Migration

Current:

```text
Sourby service
 ↓
direct Folia scheduler API
```

Transition:

```text
Sourby service
 ↓
AuroraScheduler
 ↓
FoliaSchedulerAdapter
```

Future:

```text
Sourby service
 ↓
AuroraScheduler
 ↓
Aurora-native owner scheduler
```

The service does not need to change during the final implementation swap.

---

# 28. Engine Performance Loop

Aurora should continuously use this loop:

```text
Observe
 ↓
Rank
 ↓
Validate absolute cost
 ↓
Select bottleneck
 ↓
Optimize
 ↓
Measure
 ↓
Soak
 ↓
Document
 ↓
Repeat
```

Do not optimize from static code inspection alone unless the work is provably redundant/correctness-driven.

---

# 29. Required Engine Metrics by Domain

## Entity / AI

- entities ticking
- entity tick cost
- path queue
- path solve latency
- stale result count

## Chunk / World

- loaded chunks
- ticking chunks
- load latency
- generation latency
- unload backlog
- ticket processing cost

## Scheduler

- queue depth
- task wait latency
- task run latency
- rejection/fallback count
- worker utilization

## Network

- packets/sec
- bytes/sec
- compression cost
- encode cost
- queue depth

## Storage

- save queue
- save latency
- bytes written
- flush latency

## Memory

- heap
- RSS
- allocation rate
- GC pause
- retained cache size

---

# 30. Final Architecture Rule

The final Aurora codebase should obey:

```text
If it is a local Minecraft hot-loop problem:
    patch the hot loop.

If it is a shared runtime subsystem:
    build an Aurora service.

If it is an upstream dependency:
    isolate it behind compatibility.

If it is not measurable:
    do not call it a performance optimization.
```

---

# 31. Relationship to Existing Documents

This document complements:

- `PRD.md` — product requirements
- `docs/AURORA-ROADMAP.md` — complete milestone roadmap
- `docs/AURORA-FULL-TRANSITION.md` — transition completion plan
- `docs/architecture/AURORA.md` — architecture definition
- `docs/architecture/AURORA-INDEPENDENT-ENGINE.md` — independence model
- `docs/AURORA-CONFIG.md` — configuration implementation
- `docs/AURORA-TASKS.md` — current task matrix
- `docs/AURORA-UX.md` — operator experience

This document specifically defines:

> how engine work flows, how patches are selected, where code should live, and how Aurora innovates without becoming an unmaintainable patch pile.

---

# 32. Final Vision

Aurora should eventually behave like this:

```text
                 SOURBYCRAFT
                      │
                 AURORA ENGINE
                      │
      ┌───────────────┼────────────────┐
      │               │                │
   Runtime        Execution         Telemetry
      │               │                │
      │          AuroraScheduler        │
      │               │                │
      └──────┬────────┴────────┬───────┘
             │                 │
        Engine Domains     Compatibility
             │                 │
 Entity / AI / Chunk /      Paper/Folia/
 World / Network / Storage  Canvas adapters
             │
             ▼
        Minecraft / NMS
```

Upstream may still exist.

The difference is that Aurora decides:

- what owns state,
- where work runs,
- how results are committed,
- how performance is measured,
- how configuration is interpreted,
- how dependencies are isolated,
- and where optimization belongs.

That is what turns SourbyCraft from a downstream fork into an engine architecture.
