# Aurora Architecture

## SourbyCraft 26.2 Deep Engine Architecture

**Codename:** Aurora  
**Branch:** `26.2`  
**Java:** 25  
**Status:** Active architecture direction  
**Primary objective:** Make SourbyCraft independently own its runtime behavior, configuration, performance model, and Minecraft engine optimizations while preserving required upstream compatibility and attribution.

---

# 1. Meaning of Aurora

Aurora is not only a release codename and not only a branding layer.

Aurora is the name of the SourbyCraft architecture used to progressively move important runtime behavior out of loosely coupled downstream patches and into a coherent SourbyCraft-owned engine model.

Aurora means:

```text
SourbyCraft public runtime
        +
Sourby-owned configuration
        +
Sourby-owned lifecycle
        +
Sourby-owned telemetry
        +
Sourby-owned performance policy
        +
measured Minecraft/NMS optimization
        +
small upstream integration hooks
```

It does **not** mean blindly copying Canvas or Paper classes into `dev.iyanz.*`.

It also does **not** mean refusing to modify Minecraft/NMS internals. Aurora explicitly allows deep engine changes where measurement proves the change is valuable and correctness can be validated.

---

# 2. Core Architectural Goal

The target architecture is:

```text
                         SOURBYCRAFT
                              │
                    Aurora Runtime Core
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
 Configuration          Performance Core       Diagnostics
        │                     │                     │
        │               Metrics / Health        Spark / HUD
        │                     │                     │
        └─────────────── Aurora Services ──────────┘
                              │
                      Aurora Engine Hooks
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
       Minecraft            Paper               Canvas
        / NMS           compatibility       retained internals
```

The important point is ownership:

- SourbyCraft defines the runtime contract.
- SourbyCraft defines its own configuration surface.
- SourbyCraft owns diagnostics and performance semantics.
- SourbyCraft may modify Minecraft/NMS code directly when an optimization belongs in the engine hot path.
- Paper/Canvas remain implementation inputs where useful, not the product architecture.

---

# 3. Aurora Configuration Model

Aurora must deepen SourbyCraft configuration ownership instead of continuing to add new Sourby behavior into Canvas configuration files.

Existing files remain supported:

```text
sourbycraft_config/sourbycraft_global_config.toml
sourbycraft-security.yml
config/canvas-server.yml
config/canvas-worlds.yml
```

But new SourbyCraft-owned behavior should move toward an Aurora namespace.

Recommended evolution:

```text
sourbycraft_config/
├── sourbycraft_global_config.toml
└── aurora/
    ├── performance.toml
    ├── scheduler.toml
    ├── entity.toml
    ├── chunk.toml
    ├── network.toml
    ├── memory.toml
    ├── diagnostics.toml
    └── worlds/
```

This file split is only created when the number of settings justifies it. Do not create empty files for appearance.

The first implementation may keep one TOML while using logical groups such as:

```toml
[aurora.performance]

[aurora.scheduler]

[aurora.entity]

[aurora.chunk]

[aurora.network]

[aurora.memory]

[aurora.diagnostics]
```

---

# 4. Configuration Ownership Rule

A setting belongs to Aurora when it controls SourbyCraft-owned implementation or a SourbyCraft-specific optimization.

Examples:

```text
Aurora-owned:
- async pathfinding policy
- telemetry collection intervals
- profiler integration behavior
- performance warning thresholds
- Sourby scheduler helper queues
- Sourby entity optimization modes
- Sourby chunk optimization modes
- diagnostic/HUD behavior
- Sourby network instrumentation

Upstream compatibility-owned:
- values still directly required by Canvas internals
- Paper plugin compatibility settings
- Folia-region contracts not yet owned by Aurora
```

New Sourby features must not add new keys to Canvas config simply because Canvas already has a file.

---

# 5. No Auto-Tuning Still Applies

Aurora configuration ownership does not permit automatic configuration mutation.

Aurora may provide explicit settings such as:

```toml
[aurora.entity]
async-pathfinding = false

[aurora.performance]
telemetry-enabled = true
```

but it must never do this internally:

```text
MSPT high → reduce entity AI
RAM high → reduce view distance
CPU high → rewrite region worker setting
```

Aurora optimizes implementation, not operator intent.

---

# 6. Immutable Runtime Configuration

Aurora settings should be parsed into immutable runtime snapshots.

Target flow:

```text
operator config
     ↓
parser
     ↓
validation
     ↓
AuroraConfigSnapshot
     ↓
service constructors / atomic snapshot publication
```

Hot paths must not repeatedly parse TOML/YAML or perform dotted-string lookups.

Preferred runtime model:

```java
record AuroraEntitySettings(
    boolean asyncPathfinding,
    boolean optimizeCollisionQueries
) {}
```

Settings that are read every tick should be reduced to primitive/final fields or immutable snapshots.

---

# 7. Reload Policy

Aurora settings must declare one of three behaviors:

```text
LIVE
RESTART_REQUIRED
IMMUTABLE_FOR_RUN
```

Examples:

- HUD refresh interval: LIVE
- warning thresholds: LIVE
- executor topology: RESTART_REQUIRED
- fundamental region ownership mode: IMMUTABLE_FOR_RUN

A reload command may update only settings explicitly marked live.

Do not pretend a setting was applied when a constructor-cached value still requires restart.

---

# 8. Aurora Engine Layers

Aurora uses four technical layers.

## Layer A — Runtime Services

Owned source under `dev.iyanz.sourbycraft.*`.

Examples:

- metrics runtime
- health evaluator
- HUD service
- profiler bridge
- lifecycle registry
- bounded I/O executor
- configuration service

## Layer B — Engine Adapters

Small Sourby-owned interfaces used only where they reduce concrete upstream coupling.

Examples:

```text
AuroraRegionAccess
AuroraSchedulerAccess
AuroraChunkMetricsSource
AuroraNetworkMetricsSource
```

Do not wrap every upstream class.

## Layer C — Integration Patches

Small patches that call Sourby-owned logic or expose required engine data.

Target: small, reviewable, low-conflict patches.

## Layer D — Deep Minecraft/NMS Optimizations

Direct modifications to Minecraft server code for measured hot paths.

These are first-class Aurora work, not forbidden exceptions.

---

# 9. Deep Minecraft/NMS Optimization Policy

Aurora must optimize code **inside Minecraft/NMS** when that is the correct technical location.

Examples of valid deep optimization domains:

- `Entity`
- `LivingEntity`
- `Mob`
- `GoalSelector`
- `Brain`
- `PathNavigation`
- `ServerLevel`
- `ServerChunkCache`
- chunk holder/ticket paths
- collision routines
- random/block/fluid ticks
- entity tracking
- packet generation
- serialization hot paths
- block entity ticking

Aurora is not limited to external helper classes.

The decision rule is:

> If the cost is inside a hot Minecraft method, and the optimization requires local state or algorithm changes there, modify the NMS path directly rather than adding an inefficient external wrapper.

---

# 10. Direct NMS Patch Requirements

Every deep engine optimization must include:

1. profiler/JFR evidence or a reproducible benchmark reason
2. identified hot method or allocation source
3. region ownership analysis
4. behavior/compatibility analysis
5. before/after benchmark
6. regression test where practical
7. explanation of retained upstream semantics

A patch must not be accepted only because it reduces source lines or appears clever.

---

# 11. Optimization Classes

Aurora deep optimizations should be classified as:

```text
ALGORITHM
ALLOCATION
LOOKUP
LOCALITY
BATCHING
LOCK_CONTENTION
SERIALIZATION
NETWORK
SCHEDULING
```

This classification helps explain why a patch exists and what metric should improve.

---

# 12. Allocation Optimization

Aurora may reuse mutable state only when ownership is proven.

The branch already found unsafe cases where shared `ServerLevel` scratch state crossed region threads. This failure class must not return.

Rules:

- entity-owned scratch may be valid when the entity is region-confined and data does not escape
- region-owned scratch belongs in region-local state
- `ServerLevel` fields are not automatically region-local
- static scratch state is prohibited for mutable hot-path buffers
- published values must not reuse mutable scratch storage unless ownership is transferred permanently

If allocation is cheap and reuse adds correctness risk, keep the allocation.

---

# 13. Entity / AI Aurora Work

The entity engine is a priority deep-optimization domain.

Aurora should profile and potentially optimize:

```text
Entity.tick
LivingEntity.tick
Mob.tick
GoalSelector.tick
Brain.tick
Sensor.tick
PathNavigation.tick
collision queries
entity section lookups
item merge/pickup scans
entity tracking
```

Potential techniques:

- avoid duplicate spatial queries
- eliminate repeated coordinate/object conversion
- use region-confined reusable state where proven safe
- reduce unnecessary AI recalculation
- reduce repeated path validation
- improve data locality
- reduce allocation in collision/entity query paths

Gameplay semantics must remain explicit and stable.

---

# 14. Chunk / World Aurora Work

Aurora should own a measured chunk optimization program.

Profile:

```text
chunk holder lookup
tickets
chunk loading
chunk generation
chunk integration
chunk saving
unload
region file I/O
player chunk tracking
chunk packet construction
```

Potential direct NMS changes include:

- reducing duplicate holder/map lookup
- eliminating repeated packed-coordinate calculation
- batching safe save work
- reducing serialization copies
- reducing temporary collection creation
- improving queue ownership

Persistence correctness is a release-blocking requirement.

---

# 15. Scheduler Aurora Work

Aurora should progressively define the scheduling contract it needs rather than directly depending everywhere on Canvas implementation details.

Aurora owns:

- Sourby runtime worker lifecycle
- administrative I/O execution
- telemetry scheduling
- diagnostics tasks
- any Sourby-specific CPU workers

Upstream region scheduling remains the execution authority until a proven replacement exists.

The goal is replaceability, not an immediate scheduler rewrite.

---

# 16. Network Aurora Work

Deep network optimization may touch NMS packet construction and the Netty-facing pipeline where appropriate.

Profile first:

- encode/decode CPU
- compression CPU
- packet allocation
- entity tracking traffic
- chunk packet traffic
- flush behavior
- queue growth

Rules:

- no disk/HTTP/database work on event loops
- no adaptive compression mutation
- preserve packet guards and security limits
- avoid unsafe packet object reuse

---

# 17. Aurora Performance Core

Aurora should become the canonical source of performance semantics.

```text
engine counters
region counters
GC/JVM sampler
network counters
scheduler counters
      ↓
Aurora Metrics Runtime
      ↓
immutable PerformanceSnapshot
      ↓
/tps /mspt /ram /perf HUD Spark/API
```

No command should invent its own TPS or MSPT definition.

---

# 18. SourbySpark under Aurora

Spark integration is an Aurora observability component.

Aurora should provide Spark with:

- SourbyCraft platform identity
- Sourby config group
- Sourby tick/MSPT values
- region-aware information
- runtime metadata
- worker/thread classification
- health context where cheap

Do not duplicate expensive scans simply to enrich Spark metadata.

A dedicated Spark fork is only justified if adapter-level integration cannot deliver required region/runtime visibility.

---

# 19. Aurora Health Model

Aurora diagnostics should evaluate:

```text
Tick health
Region health
CPU pressure
Memory pressure
GC pressure
Scheduler backlog
Chunk pressure
Network pressure
```

The result is advisory.

Example:

```text
HEALTH: WARNING
Primary cause: world region 12,-8 p95 MSPT 46.2ms
Secondary: GC overhead 4.1%
```

Aurora must never respond by silently changing gameplay settings.

---

# 20. Aurora Independence Milestones

## Aurora A1 — Identity

- SourbyCraft public identity everywhere
- Canvas only visible as upstream/debug metadata

## Aurora A2 — Configuration

- new Sourby performance settings live under Aurora config
- Spark displays Aurora/Sourby config
- no new Sourby feature requires Canvas config

## Aurora A3 — Runtime

- lifecycle, telemetry, diagnostics, HUD, async I/O fully Sourby-owned

## Aurora A4 — Engine Integration

- repeated Canvas internals hidden behind narrow Sourby contracts where useful
- patches shrink to integration points

## Aurora A5 — Deep Engine Optimization

- measured NMS optimization exists across entity/chunk/network/scheduler domains
- each optimization has evidence and tests

## Aurora A6 — Build Freedom

- clean repository builds reproducibly
- no separate Canvas binary/runtime service required
- cached runtime remains functional without upstream network availability

## Aurora A7 — Replaceability

- selected upstream components can be replaced/rebased without changing the Sourby public contract

---

# 21. Aurora Development Order

Required order:

```text
Correctness
   ↓
Certified baseline
   ↓
Aurora config ownership
   ↓
Runtime/telemetry stabilization
   ↓
JFR hot-path ranking
   ↓
Deep NMS optimization
   ↓
Patch reduction / ownership migration
   ↓
Soak + persistence qualification
   ↓
Stable release
```

Do not reverse this into “add many patches first, benchmark later.”

---

# 22. Aurora Acceptance Criteria

Aurora can be considered mature when:

- SourbyCraft owns its public runtime contract
- SourbyCraft owns new performance configuration
- all new performance settings are explicit and operator-controlled
- NMS optimization is evidence-driven rather than patch-count-driven
- major hot paths have benchmark/JFR evidence
- telemetry is low-overhead and unified
- region safety remains intact
- persistence and restart tests pass
- no standalone Canvas JAR or service is required
- SourbyCraft can run after first bootstrap with cached dependencies while upstream network is unavailable
- large Sourby implementations live in Sourby-owned code where practical
- remaining direct upstream dependencies are documented and intentional

---

# 23. Final Principle

Aurora is successful when SourbyCraft becomes more independent **and** more efficient without sacrificing correctness.

The target is not:

```text
more patches = more performance
```

The target is:

```text
measured engine improvements
+ clear ownership
+ first-party configuration
+ smaller integration surface
+ stable region-thread behavior
+ predictable operation
```

Aurora should make SourbyCraft a server engine that can stand on its own runtime contract while still using upstream code responsibly where doing so remains technically beneficial.
