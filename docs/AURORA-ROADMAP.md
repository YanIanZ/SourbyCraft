# Aurora Engine — Full Development Roadmap

## SourbyCraft 26.2

**Branch:** `26.2`  
**Runtime:** Java 25  
**Minecraft:** 26.2  
**Architecture:** Aurora Engine  
**Roadmap type:** Full transition + performance + independence roadmap  
**Primary goals:** Speed · Reliability · Performance · Stability · Efficiency · Independence

---

# 1. Roadmap Objective

This roadmap defines the complete path from the current SourbyCraft 26.2 state into a fully transitioned Aurora Engine architecture.

The roadmap is not limited to branding, configuration, observability, or wrapper services.

The final target is:

> Aurora becomes the runtime authority of SourbyCraft and can evolve independently of Folia/Canvas architecture while preserving compatibility where useful.

The completed system must be able to:

- own execution and lifecycle,
- own configuration semantics,
- own scheduler contracts,
- own performance/telemetry semantics,
- optimize Minecraft/NMS directly,
- isolate Paper/Folia/Canvas behind compatibility boundaries,
- build and run as SourbyCraft/Aurora,
- remain measurable, stable, and reversible.

---

# 2. Status Legend

```text
[x] Complete enough to build on
[-] Implemented / partial / requires qualification
[ ] Planned
[!] Blocked or high-risk
```

---

# 3. Current Baseline

The 26.2 branch already contains the following Aurora foundations.

## Implemented / usable

- [x] Java 25 baseline
- [x] Aurora product/architecture definition
- [x] Folia-independent architecture direction
- [x] typed `AuroraConfig`
- [x] immutable config snapshots
- [x] `aurora.entity.async-pathfinding`
- [x] read-only legacy fallback
- [x] no automatic config rewrite
- [x] MetricsRuntime
- [x] immutable performance snapshots
- [x] region tick metrics
- [x] runtime sampling
- [x] GC telemetry
- [x] Spark metrics integration
- [x] Sourby config reporting in Spark
- [x] bounded async path CPU pool
- [x] explicit async path shutdown
- [x] performance/JFR tooling
- [x] Aurora architecture docs
- [x] full-transition architecture plan

## Partial / not yet qualified

- [-] async path correctness/staleness audit
- [-] Spark Aurora metadata
- [-] source identity cleanup
- [-] representative benchmark suite
- [-] multi-hour soak qualification
- [-] direct NMS optimization program
- [-] dependency isolation

## Not complete

- [ ] Aurora runtime lifecycle authority
- [ ] Aurora execution contract
- [ ] Aurora scheduler contract
- [ ] Aurora compatibility layer
- [ ] Aurora Entity Engine
- [ ] Aurora AI Engine
- [ ] Aurora Chunk Engine
- [ ] Aurora World Engine
- [ ] Aurora Network Engine
- [ ] Aurora Storage Engine
- [ ] Aurora Memory Engine
- [ ] build/bootstrap independence
- [ ] final Canvas/Folia isolation
- [ ] final Aurora cutover

---

# 4. Program Structure

The roadmap is divided into five macro stages.

```text
STAGE A — Foundation & Identity
STAGE B — Runtime & Execution Ownership
STAGE C — Deep Engine Ownership
STAGE D — Upstream Independence
STAGE E — Qualification & Final Cutover
```

Each stage contains milestones.

---

# 5. Stage A — Foundation & Identity

## M0 — Architecture Baseline Freeze

**Status:** [-] Active

### Goal

Create a reliable baseline of the current 26.2 engine before changing runtime ownership.

### Tasks

- [ ] capture current branch head
- [ ] record Java/JVM version
- [ ] record upstream Canvas revision
- [ ] record build number
- [ ] record patch count
- [ ] record direct Canvas references
- [ ] record direct Folia references
- [ ] record Sourby-owned executors
- [ ] record active config surfaces
- [ ] record current startup/shutdown lifecycle
- [ ] record current Spark integration points
- [ ] record performance command outputs

### Deliverable

`docs/architecture/AURORA-BASELINE-LEDGER.md`

### Gate

No large runtime refactor starts before this inventory exists.

---

## M1 — Identity Cleanup

**Status:** [ ] Planned

### Goal

Make source, build metadata, runtime identity, and documentation describe the actual Aurora architecture.

### Tasks

- [ ] replace stale "utility layer" wording
- [ ] replace stale "Canvas re-platform" wording
- [ ] fix root `package-info.java`
- [ ] fix `SourbyCraftBootstrap` architecture comments
- [ ] remove `SourbyCraft-on-Canvas` build terminology
- [ ] create canonical `BuildInfo`
- [ ] remove hard-coded Spark `Build44`
- [ ] feed build number from canonical metadata
- [ ] make startup banner use BuildInfo
- [ ] make `/version` use BuildInfo
- [ ] make Spark use BuildInfo
- [ ] make crash reports use BuildInfo
- [ ] expose upstream revisions only in developer/debug details

### Target output

```text
SourbyCraft 26.2
Aurora Engine
Build 45
Java 25
Minecraft 26.2
```

### Gate

No active runtime code hard-codes release/build identity.

---

## M2 — Aurora Configuration Expansion

**Status:** [-] Started

### Goal

Finish first-party configuration ownership without automatic tuning.

### Existing

- [x] `AuroraConfig`
- [x] `aurora.entity.async-pathfinding`
- [x] LIVE lifecycle
- [x] legacy fallback
- [x] no automatic writeback

### Add only when real consumers exist

- [ ] `aurora.performance`
- [ ] `aurora.scheduler`
- [ ] `aurora.ai`
- [ ] `aurora.chunk`
- [ ] `aurora.world`
- [ ] `aurora.network`
- [ ] `aurora.memory`
- [ ] `aurora.storage`
- [ ] `aurora.diagnostics`

### Configuration contract

Every key must declare:

- type
- default
- validation
- lifecycle
- consumer
- fallback
- compatibility behavior

### Upstream bridge

Replace direct config calls:

```text
SourbyCraftConfig
    ↓
io.canvasmc.canvas.*
```

with:

```text
AuroraConfigService
    ↓
UpstreamConfigBridge
    ↓
Canvas/Paper implementation
```

### Gate

Aurora core no longer directly depends on Canvas config implementation classes.

---

# 6. Stage B — Runtime & Execution Ownership

## M3 — Aurora Runtime Lifecycle

**Status:** [ ] Planned

### Goal

Create one authoritative Sourby runtime lifecycle.

### Required lifecycle

```text
NEW
BOOTSTRAPPING
STARTING
RUNNING
STOPPING
STOPPED
FAILED
```

### Runtime-owned services

- [ ] configuration
- [ ] execution
- [ ] metrics
- [ ] GC telemetry
- [ ] HUD
- [ ] diagnostics
- [ ] Spark bridge
- [ ] updater
- [ ] I/O executor
- [ ] CPU workers
- [ ] scheduler bridge

### Rules

- no new task admission after STOPPING
- shutdown must be idempotent
- services stop in reverse dependency order
- executor ownership must be explicit
- startup failures must be isolated

### Gate

All Sourby-owned long-lived services have explicit lifecycle ownership.

---

## M4 — Aurora Execution Model

**Status:** [ ] Planned

### Goal

Define Aurora-owned execution semantics independent from concrete Folia/Canvas scheduler implementation.

### Work classes

```text
GAME_STATE_MUTATION
CPU_COMPUTE
BLOCKING_IO
NETWORK_EVENT
SERIALIZATION
BACKGROUND_MAINTENANCE
DIAGNOSTICS
```

### Target contracts

Possible minimal set:

```text
AuroraExecution
AuroraTaskClass
AuroraOwnerContext
AuroraTaskHandle
AuroraCancellation
AuroraFreshnessToken
```

Names may change; semantics may not.

### Required guarantees

- bounded CPU work
- no hidden common-pool usage
- virtual threads only where blocking I/O benefits
- live game state mutation through owner context
- explicit result handoff
- explicit cancellation
- deterministic shutdown
- stale-result rejection

### Gate

At least one production subsystem runs through AuroraExecution.

---

## M5 — Async Pathfinding Migration

**Status:** [-] Prototype exists

### Goal

Turn async pathfinding into the first fully Aurora-owned execution subsystem.

### Current pattern

```text
region thread
   ↓
SnapshotPathRegion
   ↓
bounded CPU pool
   ↓
result
```

### Required completion work

- [ ] migrate pool behind AuroraExecution
- [ ] add explicit owner-context callback
- [ ] add path freshness token
- [ ] validate entity/navigation version
- [ ] reject stale paths
- [ ] test entity removal during solve
- [ ] test world unload during solve
- [ ] test target movement during solve
- [ ] test queue saturation
- [ ] test shutdown cancellation
- [ ] benchmark sync vs async
- [ ] mob behavior compatibility soak
- [ ] expose queue depth
- [ ] expose wait latency
- [ ] expose solve latency

### Gate

Async pathfinding can be enabled without direct dependency on a concrete Folia/Canvas scheduler class in Sourby-owned code.

---

## M6 — Aurora Scheduler Contract

**Status:** [ ] Planned

### Goal

Introduce a stable scheduler contract owned by Aurora.

### Required capabilities

- owner-context task
- world task
- player task
- delayed task
- cancellation
- task handle
- shutdown-aware admission
- CPU completion handoff
- I/O completion handoff

### Phase 1 implementation

```text
AuroraScheduler
      ↓
FoliaCanvasSchedulerAdapter
      ↓
existing region scheduler
```

### Phase 2 implementation

Aurora may later replace selected internals without changing call sites.

### Gate

Sourby-owned core services do not import concrete Folia/Canvas scheduler classes.

---

## M7 — Runtime Telemetry Completion

**Status:** [-] Strong foundation

### Goal

Make Aurora telemetry the authoritative runtime truth.

### Existing

- [x] MetricsRuntime
- [x] RegionTickMetrics
- [x] PerformanceCollector
- [x] RuntimeSampler
- [x] GcTracker
- [x] immutable snapshots

### Add

- [ ] scheduler queue depth
- [ ] task wait latency
- [ ] async-path queue/solve latency
- [ ] chunk lifecycle counters
- [ ] entity tick counters
- [ ] packet counters
- [ ] bytes/sec
- [ ] storage backlog
- [ ] save latency
- [ ] worker utilization
- [ ] incident history

### Consumer model

```text
engine hooks
    ↓
Aurora Metrics
    ↓
PerformanceSnapshot
    ├── /tps
    ├── /mspt
    ├── /ram
    ├── /perf
    ├── HUD
    └── Spark
```

### Gate

No competing Sourby TPS/MSPT truth source exists.

---

# 7. Stage C — Deep Engine Ownership

## M8 — Certified Gameplay Baseline

**Status:** [ ] Required before deep optimization

### Goal

Collect realistic data before selecting NMS optimization targets.

### Workloads

- [ ] idle
- [ ] 10 connected players
- [ ] 50 connected/equivalent players
- [ ] 100 players
- [ ] mob AI load
- [ ] entity tracking load
- [ ] combat load
- [ ] chunk traversal
- [ ] chunk generation
- [ ] block activity
- [ ] inventory/plugin interaction
- [ ] network throughput
- [ ] save stress

### Required outputs

- TPS
- MSPT avg/p50/p95/p99/max
- process CPU
- system CPU
- heap
- RSS
- allocation rate
- GC pauses
- chunk latency
- entity cost
- packet rate
- storage latency
- queue metrics

### Important rule

A large profiler percentage is not enough.

Optimization requires absolute cost and realistic workload significance.

### Gate

JFR CPU/allocation rankings are representative enough to select targets.

---

## M9 — Aurora Entity Engine

**Status:** [ ] Planned

### Target paths

- `Entity.tick`
- `LivingEntity.tick`
- `Mob.tick`
- movement
- spatial lookups
- tracking
- projectiles
- item entities
- metadata updates

### Optimization categories

- repeated lookup removal
- spatial query reduction
- collection allocation reduction
- boxing removal
- duplicate state calculations
- tracking diff efficiency
- cache/locality improvements

### Implementation policy

Local hot-path algorithm:

```text
direct NMS patch
```

Shared subsystem:

```text
Aurora Entity service
```

### Gate

At least one measured Entity/NMS optimization produces a reproducible improvement without semantic regression.

---

## M10 — Aurora AI Engine

**Status:** [ ] Planned

### Target paths

- GoalSelector
- TargetSelector
- Brain
- Sensor
- Behavior
- PathNavigation
- PathFinder
- NodeEvaluator

### Goals

- reduce unnecessary scans
- reduce duplicate sensor work
- reduce path recomputation
- safely offload CPU-heavy calculations
- improve query locality
- avoid live-world async reads

### Forbidden

- automatic AI disabling under lag
- hidden AI interval reduction
- gameplay degradation without explicit operator choice

### Gate

AI optimization passes behavior regression and multi-hour mob soak.

---

## M11 — Aurora Collision Engine

**Status:** [ ] Planned

### Target paths

- AABB queries
- voxel shapes
- block collision
- entity collision
- movement resolution

### Goals

- reduce temporary lists
- reduce shape conversion
- reduce duplicate queries
- safe scratch reuse
- primitive calculations where useful

### Safety rule

No shared world-level mutable scratch unless ownership proves it safe.

### Gate

Collision optimization survives cross-region stress.

---

## M12 — Aurora Chunk Engine

**Status:** [ ] Planned

### Target paths

- ChunkHolder
- ChunkMap
- ServerChunkCache
- DistanceManager
- ticket processing
- load
- integration
- unload
- send

### Goals

- lower holder lookup overhead
- reduce duplicate map lookups
- reduce key conversion
- improve queue control
- lower retention
- reduce allocation

### Gate

Chunk traversal and load/unload benchmarks improve or remain neutral with no persistence regression.

---

## M13 — Aurora Generation Engine

**Status:** [ ] Planned

### Target domains

- terrain generation
- noise
- biome
- structures
- lighting
- heightmaps
- decoration

### Parallelism policy

Dedicated compute execution is allowed only when:

- inputs are isolated
- output is deterministic
- commit is owner-safe
- scheduling cost is justified

### Gate

Generation performance improves without nondeterministic world output.

---

## M14 — Aurora World Engine

**Status:** [ ] Planned

### Target paths

- ServerLevel
- scheduled ticks
- random ticks
- block updates
- fluid ticks
- world events
- weather
- block entities

### Critical rule

`ServerLevel` may be shared across concurrently ticking regions.

Do not introduce unsafe world-global mutable scratch.

### Gate

World stress passes region-concurrency qualification.

---

## M15 — Aurora Network Engine

**Status:** [ ] Planned

### Target domains

- packet encode/decode
- compression
- chunk packets
- entity tracking packets
- queue health
- flush behavior

### Metrics

- packets/sec
- bytes/sec
- queue depth
- encode time
- compression time
- copy/allocation counts

### Goals

- fewer copies
- fewer allocations
- non-blocking Netty loops
- predictable backpressure

### Gate

Network load benchmark passes without connection instability.

---

## M16 — Aurora Storage Engine

**Status:** [ ] Planned

### Target domains

- chunk save
- region files
- player data
- world metadata
- serialization
- save queues

### Goals

- bounded save pipelines
- lower serialization allocation
- lower save latency
- predictable shutdown flush

### Forbidden

- skip saves for performance
- weaken durability silently
- acknowledge save before required durability semantics

### Gate

Restart/crash/persistence qualification passes.

---

## M17 — Aurora Memory Engine

**Status:** [ ] Planned

### Goals

- lower hot-path allocation
- lower retained heap
- bounded caches
- buffer ownership
- lower GC pressure

### Required audits

- ThreadLocal usage
- object pools
- temporary collections
- lambda captures
- boxing
- cache bounds
- player retention
- chunk retention
- task/future retention

### Rule

Reduce garbage before recommending GC flags.

### Gate

Heap recovery and retained-object tests pass after stress.

---

# 8. Stage D — Upstream Independence

## M18 — Aurora Compatibility Layer

**Status:** [ ] Planned

### Goal

Isolate upstream contracts.

### Suggested domains

```text
compat/
├── scheduler
├── config
├── spark
├── paper
└── upstream
```

### Move into compatibility layer

- Canvas config calls
- Folia scheduler adapters
- Spark Canvas bridge
- upstream build/revision helpers
- compatibility shims

### Do not move

Local direct NMS algorithms merely for appearance.

### Gate

Aurora runtime can be understood without reading Canvas runtime services.

---

## M19 — Canvas/Folia Dependency Ledger

**Status:** [ ] Planned

### Classifications

```text
REQUIRED_UPSTREAM_CONTRACT
TEMPORARY_IMPLEMENTATION
COMPATIBILITY_ONLY
DIRECT_NMS_PATCH
REMOVABLE
LEGACY
```

### Inventory

- `io.canvasmc.*`
- Folia scheduler imports
- Paper internals
- Canvas configs
- Canvas Spark code
- legacy sourbypatcher
- old Folia compatibility wrappers
- build tooling

### Gate

Every direct upstream dependency has an explicit classification.

---

## M20 — Build Independence

**Status:** [ ] Planned

### Goal

Build SourbyCraft as SourbyCraft even when upstream is still consumed as source.

### Requirements

- canonical build metadata
- reproducible clean checkout
- deterministic upstream pinning
- explicit patch order
- no hidden local state
- CI patch application
- compile/test/boot pipeline

### Long-term target

Upstream can be replaced without rewriting Sourby product architecture.

### Gate

Clean checkout can build SourbyCraft with documented prerequisites only.

---

## M21 — Bootstrap / Free-Running Independence

**Status:** [ ] Planned

### Requirements

- cached runtime boots offline
- updater failure is non-fatal
- profiler upload failure is non-fatal
- optional plugins/services are isolated
- no runtime Canvas remote API dependency
- downloader has timeout/retry/checksum behavior
- first-boot failure recovery is tested

### Gate

After dependencies are acquired once, normal server operation does not require Canvas infrastructure.

---

## M22 — Scheduler Independence Phase 2

**Status:** [ ] Future

### Goal

After AuroraScheduler contract is mature, evaluate replacing selected Folia/Canvas scheduling implementation.

Possible paths:

- retain region ownership concepts
- redesign region sizing
- specialize worker assignment
- hybrid owner contexts
- workload-aware internal scheduling
- separate compute from mutation
- independent queueing structures

### Rule

Do not rewrite scheduler for branding.

Replace only when there is a correctness, scalability, maintainability, or measurable performance reason.

### Gate

At least one scheduler subsystem is implemented natively behind the Aurora contract without plugin-facing semantic regression.

---

# 9. Stage E — Qualification & Final Cutover

## M23 — Full Regression Matrix

**Status:** [ ] Planned

### Required functional coverage

- boot
- plugin load
- join
- quit
- teleport
- world switch
- combat
- inventory
- chunk load
- chunk unload
- world save
- shutdown
- restart
- config reload
- async path
- Spark
- HUD
- commands

### Gate

No known critical compatibility regression.

---

## M24 — Performance Qualification

**Status:** [ ] Planned

### Required benchmark comparison

```text
baseline SHA
candidate SHA
hardware
OS
Java
JVM flags
world
plugins
workload
warmup
duration
```

### Regression policy

```text
>3% unexplained regression  → investigate
>5% regression              → written justification
>10% regression             → block unless correctness/security requires it
```

### Aspirational targets

- CPU: 10–20% lower in at least one representative workload
- p95 MSPT: 10%+ lower under heavy workload
- hot-path allocation: 15%+ lower
- GC pressure: measurably lower
- player/GB efficiency: improved

### Gate

Claims in README/release notes are backed by benchmark evidence.

---

## M25 — Soak Qualification

**Status:** [ ] Planned

### Minimum

- 2h+ sustained concurrency
- entity load
- chunk churn
- joins/quits
- network traffic
- save activity

### Validate

- no thread leak
- no queue growth
- no retained futures
- no player retention
- no chunk retention
- stable heap
- stable allocation rate
- stable GC
- stable tail latency

### Gate

No unbounded growth or progressive degradation.

---

## M26 — Persistence Qualification

**Status:** [ ] Planned

### Validate

- normal shutdown
- restart
- repeated restart
- shutdown under load
- chunk persistence
- player persistence
- entity persistence
- world metadata
- region file validity

### Optional destructive test environment

- forced termination
- incomplete async work
- recovery behavior

### Gate

No known world/data corruption caused by Aurora.

---

## M27 — Final Aurora Cutover

**Status:** [ ] Final transition

### Actions

- make Aurora runtime canonical
- route Sourby scheduling through Aurora
- route Sourby config through Aurora
- route telemetry through Aurora
- route Spark through Aurora bridge
- isolate remaining upstream dependencies
- remove obsolete compatibility wrappers
- remove stale Canvas/Folia-era architecture comments
- update README
- update PRD
- update architecture docs
- freeze transition shims for one stable release

### Final architecture

```text
SourbyCraft
   ↓
Aurora Engine
   ├── Runtime
   ├── Execution
   ├── Scheduler
   ├── Config
   ├── Entity/AI
   ├── Chunk/World
   ├── Network
   ├── Storage
   ├── Memory
   ├── Telemetry
   └── Diagnostics
        ↓
Minecraft / NMS
        ↓
Compatibility Layer
        ↓
Paper / Folia / Canvas-derived components
```

### Gate

Aurora is the architectural authority.

---

## M28 — Post-Cutover Stabilization

**Status:** [ ] Planned

### Duration

At least one stable release cycle.

### Tasks

- monitor real server reports
- record regressions
- remove temporary transition shims
- review compatibility layer size
- review upstream conflict count
- review performance regressions
- audit deprecated config keys
- finalize Aurora documentation
- freeze stable execution contracts

### Gate

Aurora can evolve without reintroducing Canvas/Folia architectural coupling.

---

# 10. Milestone Dependency Graph

```text
M0 Baseline
 │
 ▼
M1 Identity ───────────────┐
 │                        │
 ▼                        │
M2 Config                  │
 │                        │
 ▼                        │
M3 Runtime Lifecycle       │
 │                        │
 ▼                        │
M4 Execution Model         │
 │                        │
 ▼                        │
M5 AsyncPath Prototype     │
 │                        │
 ▼                        │
M6 Scheduler Contract      │
 │                        │
 ├──────► M7 Telemetry ◄───┘
 │
 ▼
M8 Real Baseline
 │
 ├──► M9 Entity
 ├──► M10 AI
 ├──► M11 Collision
 ├──► M12 Chunk
 ├──► M13 Generation
 ├──► M14 World
 ├──► M15 Network
 ├──► M16 Storage
 └──► M17 Memory
          │
          ▼
      M18 Compatibility
          │
          ▼
      M19 Dependency Ledger
          │
       ┌──┴──┐
       ▼     ▼
     M20     M21
    Build   Bootstrap
       └──┬──┘
          ▼
      M22 Scheduler Independence
          │
          ▼
      M23 Regression
          │
          ▼
      M24 Performance
          │
          ▼
      M25 Soak
          │
          ▼
      M26 Persistence
          │
          ▼
      M27 Final Cutover
          │
          ▼
      M28 Stabilization
```

---

# 11. Recommended PR / Commit Strategy

Avoid one giant transition branch.

Prefer small reviewable slices.

Example:

```text
PR 01 — canonical BuildInfo
PR 02 — source identity cleanup
PR 03 — AuroraRuntime lifecycle
PR 04 — AuroraExecution contracts
PR 05 — AsyncPath migration
PR 06 — AuroraScheduler adapter
PR 07 — scheduler telemetry
PR 08 — real workload harness
PR 09 — first measured Entity optimization
...
```

Each performance PR should include:

```text
problem
evidence
root cause
implementation
thread ownership
memory impact
compatibility impact
before
after
tests
rollback
```

---

# 12. Release Tracks

## Track 26.2-A — Aurora Foundation

Includes:

- M0–M7

Goal:

> Aurora exists as real runtime/execution architecture.

## Track 26.2-B — Aurora Deep Engine

Includes:

- M8–M17

Goal:

> Aurora owns optimization strategy inside Minecraft hot paths.

## Track 26.2-C — Aurora Independence

Includes:

- M18–M22

Goal:

> Canvas/Folia become replaceable implementation/compatibility inputs.

## Track 26.2-D — Aurora Stable

Includes:

- M23–M28

Goal:

> qualify, cut over, and stabilize the independent architecture.

---

# 13. Definition of Roadmap Complete

The roadmap is complete when:

- Aurora runtime owns Sourby services,
- Aurora config owns Sourby settings,
- Aurora execution owns task classification,
- Aurora scheduler contract isolates upstream scheduling,
- async computation has freshness/cancellation semantics,
- Entity/AI optimization is Aurora-owned,
- Chunk/World optimization is Aurora-owned,
- Network optimization is Aurora-owned,
- Storage optimization is Aurora-owned,
- memory/caches are bounded,
- telemetry has one source of truth,
- Spark is an adapter to Aurora metrics/config,
- build identity is SourbyCraft/Aurora-first,
- direct Canvas/Folia dependencies are inventoried,
- accidental coupling is removed,
- cached runtime boots offline,
- no auto-tuning exists,
- benchmarks support performance claims,
- soak passes,
- persistence passes,
- final cutover is complete,
- one post-cutover stabilization release is complete.

At that point:

> Aurora Engine is no longer a migration project. It is the permanent SourbyCraft runtime architecture.
