# Aurora Independent Engine Architecture

> **Role:** independence design argument (execution domains, hybrid scheduling, snapshot work, replacement policy). **Status:** Design proposal: it describes a target direction, and most of it is not built. Sections that once stated current facts (§6, §10, §24, §31, §36, §37, §38) carry a dated *Status 2026-10-25* note with the current state; the authoritative current statements are [execution-contract.md](execution-contract.md), [dependency-ledger.md](dependency-ledger.md) and the entry point's *Current state* ([AURORA.md](AURORA.md)).
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

## SourbyCraft 26.2 — Beyond Folia Constraints

**Project:** SourbyCraft  
**Branch:** `26.2`  
**Architecture:** Aurora Engine  
**Runtime:** Java 25  
**Status:** Architecture proposal (target direction; current state annotated per section on 2026-10-25)\
**Primary goals:** Speed, reliability, performance, stability, efficiency, independence

---

# 1. Purpose

This document defines the next architectural stage of **Aurora Engine**.

Aurora must no longer be treated as an optimization layer constrained by the concrete runtime architecture inherited from Folia or Canvas.

Folia-derived code may remain as an implementation input, compatibility source, or temporary execution backend, but it must not permanently define how SourbyCraft is allowed to schedule work, structure ownership, divide runtime services, or optimize Minecraft internals.

The long-term model is:

```text
SourbyCraft
    ↓
Aurora Engine
    ↓
Aurora Execution Model
    ↓
Minecraft / NMS
    ↓
Compatibility Layer
    ↓
Paper / Folia / Canvas-derived components where still useful
```

Aurora owns the runtime contract.

Upstream projects become implementation inputs rather than architectural authorities.

---

# 2. What “Not Limited by Folia” Means

This statement does **not** mean:

- delete all Folia code immediately,
- remove region threading without a replacement,
- abandon Bukkit/Paper/Folia compatibility,
- permit arbitrary unsafe multithreading,
- move Minecraft state between threads without ownership rules,
- rewrite the whole server before measuring anything.

It means Aurora is free to design and introduce better execution models where they are justified.

Examples include:

- Aurora-owned region scheduling,
- subsystem-specific worker models,
- snapshot-based asynchronous computation,
- staged world-save pipelines,
- specialized pathfinding workers,
- independent chunk-generation execution,
- immutable cross-domain messages,
- dedicated networking pipelines,
- Aurora-owned task admission and backpressure,
- subsystem-local execution contracts rather than one global scheduling assumption.

---

# 3. Architectural Principle

Aurora follows this rule:

> Ownership is mandatory. Folia is optional.

The server must always know:

- who owns mutable state,
- which execution context may mutate it,
- how work crosses context boundaries,
- how results are validated,
- how stale work is rejected,
- how shutdown and cancellation are handled.

But the owner does not have to be a concrete Folia region implementation forever.

---

# 4. Aurora Execution Domains

Aurora should model server work by domain instead of assuming all work belongs to one scheduling mechanism.

```text
Aurora Execution Model
│
├── Gameplay State Domain
├── Entity / AI Domain
├── Chunk / World Domain
├── Generation Domain
├── Storage Domain
├── Network Domain
├── External I/O Domain
├── Diagnostics Domain
└── Background CPU Domain
```

Each domain gets an explicit ownership and execution contract.

---

# 5. Gameplay State Domain

Live mutable gameplay state includes:

- players,
- entities,
- chunks,
- inventories,
- block entities,
- world state,
- block/fluid mutation,
- event-visible state.

The initial Aurora implementation may continue using region ownership inherited from Folia/Canvas.

However, SourbyCraft code must increasingly depend on an Aurora-owned contract rather than concrete Folia classes.

Target abstraction concepts:

```text
AuroraExecutionContext
AuroraStateOwner
AuroraWorldContext
AuroraTaskDispatcher
AuroraOwnershipGuard
```

These names are conceptual. Do not create unnecessary abstraction layers unless they reduce real coupling.

---

# 6. Aurora Region Runtime

Aurora may initially retain region scheduling, but region execution becomes an implementation behind an Aurora contract.

Target direction:

```text
Minecraft code
    ↓
Aurora execution contract
    ↓
Current backend: the inherited (Folia-lineage, formerly Canvas) region scheduler
    ↓
Future backend: Aurora scheduler
```

This allows migration without forcing a full scheduler rewrite in one release.

*Status 2026-10-25:* the current backend is the region scheduler inherited from Folia through Canvas,
SourbyCraft-owned engine source since the 2026-10-05 migration (Paper is the only upstream). Aurora
contracts exist for owner handoff and region queries (`execution/OwnerHandoff`, `execution/region`);
no Aurora scheduler exists. Stage status in §10.

---

# 7. Hybrid Scheduling

Aurora is allowed to use different execution strategies for different workload classes.

Example:

```text
Live entity mutation
    → owning gameplay context

Pathfinding computation
    → bounded CPU workers

Chunk generation
    → generation workers

Disk / database / HTTP
    → virtual-thread or dedicated I/O execution

Packet encode / network event handling
    → Netty event loop / network workers

Diagnostics aggregation
    → low-frequency telemetry worker
```

This model is preferred over forcing unrelated workloads through one scheduler.

---

# 8. Snapshot-Based Asynchronous Work

Aurora may move expensive calculation away from gameplay execution when mutable state is not shared directly.

Required model:

```text
owned mutable state
      ↓
immutable snapshot
      ↓
background computation
      ↓
result
      ↓
owner-context handoff
      ↓
validate state/version
      ↓
commit or discard
```

Suitable candidates:

- pathfinding,
- expensive AI planning,
- certain visibility calculations,
- map/metadata generation,
- some chunk-generation stages,
- compression preparation where safe.

---

# 9. Stale Result Protection

Every async computation that depends on mutable game state must be able to reject stale results.

Possible techniques:

- entity generation/version id,
- chunk lifecycle id,
- task epoch,
- position/state version,
- ownership token,
- cancellation token.

Aurora must never blindly apply delayed asynchronous output to changed state.

---

# 10. Aurora Scheduler Program

The Aurora scheduler should evolve in stages.

## Stage 1 — Contract extraction

Document exactly what SourbyCraft depends on from the current region scheduler.

## Stage 2 — Aurora-owned dispatch API

Route new Sourby-owned work through Aurora contracts.

## Stage 3 — Backend isolation

Concrete Canvas/Folia scheduler access becomes isolated to a small adapter surface.

## Stage 4 — Specialized execution

Introduce Aurora-owned execution for selected domains where benchmarking shows benefit.

## Stage 5 — Optional scheduler replacement

Only after correctness, compatibility, and workload evidence exist may core scheduling be replaced more broadly.

*Status 2026-10-25* (same statement as [AURORA.md *Current state*](AURORA.md) and
[execution-contract.md *Status boundary*](execution-contract.md#status-boundary)): Stage 1
**implemented** (execution-contract.md, dependency-ledger.md, pinned inventories); Stage 2
**partial** (`OwnerHandoff` carries async-path delivery and the wandering-trader handoff, the
`execution/region` contracts carry several owned services, others use the published scheduler API
directly); Stage 3 **implemented for SourbyCraft's own sources**: internal scheduler symbols appear in
seven pinned files (four adapters, two read-only views; `scripts/test_independence_policy.py`) and
feature patches reach the scheduler only at recorded sites; Stage 4 **partial** (the async-path and
governed lanes exist, default off or idle, no benchmark shows a benefit); Stage 5 **planned, not
started**.

---

# 11. Deep Minecraft/NMS Optimization

Aurora is explicitly allowed to modify Minecraft internals directly.

This is a first-class requirement, not an exception.

Priority targets include:

```text
Entity.tick
LivingEntity.tick
Mob.tick
GoalSelector
TargetSelector
Brain
Sensor
PathNavigation
PathFinder
collision resolution
entity queries
ServerLevel
ServerChunkCache
ChunkMap
ChunkHolder
DistanceManager
block ticks
fluid ticks
block entities
entity tracking
packet construction
packet serialization
compression
NBT serialization
chunk save/load
region-file I/O
```

---

# 12. Direct Patch vs Aurora Service

Use a **direct Minecraft/NMS patch** when:

- the optimization is local to the hot path,
- moving it outside creates extra indirection,
- the data already exists locally,
- the change is an algorithm/data-structure improvement,
- performance benefit is measurable.

Use an **Aurora-owned service** when:

- the subsystem has lifecycle,
- the behavior spans multiple classes,
- state needs central ownership,
- configuration/telemetry is shared,
- it reduces large downstream patch bodies.

Preferred balance:

```text
local algorithm optimization → direct patch
large reusable subsystem     → Aurora service
compatibility requirement    → compatibility layer
```

---

# 13. Entity Engine Independence

Aurora Entity Engine should own the performance policy for entity processing.

Areas:

- entity lifecycle,
- spatial lookup,
- collision,
- activation decisions when explicitly configured,
- item entities,
- projectile processing,
- entity tracker updates,
- nearby-player calculations.

The implementation may continue to use upstream data structures, but Aurora must be free to replace inefficient algorithms where profiling supports it.

---

# 14. AI Engine Independence

Aurora AI Engine is not required to follow one Folia task model.

Potential architecture:

```text
Mob state
  ↓
AI snapshot
  ↓
bounded AI worker
  ↓
AI result
  ↓
owner-context validation
  ↓
commit
```

Suitable work may include:

- pathfinding,
- expensive sensory search,
- navigation planning,
- target candidate ranking.

Live world mutation remains owned and validated.

---

# 15. Chunk Engine Independence

Aurora Chunk Engine should eventually control the contract around:

- lookup,
- ticketing,
- load,
- generation,
- integration,
- unload,
- save,
- player tracking.

The current upstream implementation may remain temporarily, but Sourby-specific optimization must not be permanently blocked by upstream scheduler assumptions.

---

# 16. Generation Engine

World generation is CPU-heavy and should be evaluated independently from live gameplay mutation.

Aurora may introduce specialized generation execution when:

- data dependencies are explicit,
- output is deterministic,
- integration occurs on the correct owner context,
- memory overhead remains bounded,
- generation workers do not starve gameplay workers.

---

# 17. Storage Engine

Storage should become a separately managed Aurora domain.

Responsibilities:

- chunk serialization,
- region-file writes,
- playerdata,
- world metadata,
- save batching,
- durability tracking,
- flush/shutdown coordination.

Performance must never come from silently skipping persistence.

---

# 18. Network Engine

Aurora Network Engine may optimize:

- packet encoding,
- packet decoding,
- compression,
- buffer reuse,
- packet batching,
- chunk packet generation,
- entity tracker output,
- queueing and flush behavior.

Rules:

- Netty event loops must stay responsive,
- unrelated disk/database work must not run on Netty threads,
- queue growth must be bounded,
- buffer ownership must be explicit.

---

# 19. Memory Model

Aurora should optimize for both throughput and memory efficiency.

Priority metrics:

- allocation MB/s,
- retained heap,
- RSS,
- GC pause,
- GC overhead,
- object count,
- cache size,
- queue size.

Aurora must not replace short-lived safe allocation with shared mutable state unless ownership is proven.

---

# 20. Java 25 Modernization

Aurora may modernize Minecraft/Sourby-owned code with stable Java 25-era features where useful.

Candidates:

- records,
- sealed hierarchies,
- pattern matching,
- switch expressions,
- immutable data carriers,
- virtual threads for blocking I/O,
- improved collection APIs.

Modernization must improve clarity, safety, or performance.

Do not rewrite stable hot paths merely to use new syntax.

---

# 21. Virtual Threads

Virtual threads are allowed for blocking workloads such as:

- HTTP,
- database operations,
- administrative file access,
- remote API calls,
- update/download tasks.

They are not the default replacement for:

- region/gameplay execution,
- Netty event loops,
- CPU-heavy world generation,
- CPU-heavy AI workers.

---

# 22. Worker Isolation

Aurora must prevent one workload class from starving another.

Examples:

```text
Gameplay workers
Generation workers
AI/pathfinding workers
Storage I/O
Administrative I/O
Network event loops
```

Each important worker class should define:

- maximum concurrency,
- queue/admission policy,
- shutdown behavior,
- metrics,
- backpressure behavior.

---

# 23. Backpressure

Aurora must prefer bounded admission over unlimited queue growth.

If a background subsystem cannot keep up, it must have explicit behavior such as:

- reject,
- merge duplicate work,
- replace stale work,
- defer,
- apply bounded queueing.

Never allow silent unbounded memory growth.

---

# 24. Configuration Independence

Aurora configuration should become authoritative for Sourby-owned behavior.

Logical target:

```toml
[aurora.performance]
[aurora.scheduler]
[aurora.entity]
[aurora.ai]
[aurora.chunk]
[aurora.world]
[aurora.network]
[aurora.storage]
[aurora.memory]
[aurora.diagnostics]
```

Configuration must be parsed into typed immutable runtime snapshots.

Hot paths must not repeatedly perform dotted-string config lookup.

*Status 2026-10-25:* implemented in YAML, not TOML: `sourbycraft_config/aurora.yml` with
`aurora.<service>` sections (`aurora.entity`, `aurora.diagnostics`, `aurora.cpu`, `aurora.bridge`,
`aurora.scheduler`, `aurora.network`, `aurora.world`, `aurora.awf`, with `multiprofile` and
`redis.fabric` under it; key table `config/AuroraKeys.java`), parsed into the typed record
`config/AuroraConfig` (Build 49 layout; see [AURORA-CONFIG.md](../AURORA-CONFIG.md)). The namespaces
`performance`, `ai`, `chunk`, `storage` and `memory` above do not exist.

---

# 25. No Automatic Config Mutation

Architecture independence does not permit hidden auto-tuning.

Forbidden:

```text
high MSPT → lower view distance
high RAM → lower simulation distance
high CPU → disable AI
many players → reduce mobs
lag → rewrite chunk settings
```

Aurora optimizes implementation, not operator intent.

---

# 26. Internal Adaptive Algorithms

Aurora may adapt internal algorithms when externally visible semantics remain unchanged.

Examples:

```text
small collection → linear scan
large collection → indexed lookup

small batch → direct processing
large batch → grouped processing
```

This is implementation optimization, not automatic config tuning.

---

# 27. Reliability First

Aurora must be designed for predictable failure modes.

Requirements:

- bounded queues,
- explicit timeouts,
- explicit cancellation,
- clean shutdown,
- stale task rejection,
- error isolation,
- no silent corruption,
- no infinite retry loops.

---

# 28. Stability Requirements

Any new execution model must pass:

- repeated boot/shutdown,
- restart persistence,
- multi-region activity,
- player movement between ownership domains,
- entity teleport,
- chunk load/unload stress,
- world save stress,
- plugin interaction,
- disconnect/reconnect,
- long soak.

---

# 29. Compatibility Layer

Aurora independence must preserve intentional plugin compatibility.

Target:

```text
Plugin ecosystem
      ↓
Paper/Bukkit/Folia-compatible API surface
      ↓
Aurora compatibility layer
      ↓
Aurora runtime
```

Plugins should not need to understand internal Aurora execution details unless they explicitly use Aurora-specific APIs.

---

# 30. Folia Compatibility vs Folia Dependency

These are different concepts.

Aurora may remain **Folia-compatible** while reducing **Folia implementation dependency**.

Compatibility may include:

- region-safe scheduling semantics,
- API expectations,
- plugin behavior.

Dependency refers to concrete runtime classes and scheduler internals.

The long-term goal is to preserve useful compatibility without making Aurora architecture subordinate to those internals.

---

# 31. Canvas Relationship

*Status 2026-10-25:* superseded. Canvas is no longer an upstream: since the 2026-10-05 migration Paper
is the only upstream (`paperRef` in `gradle.properties`), and the region threading and Canvas features
are SourbyCraft-owned engine source (`dev.iyanz.aurora.engine`, the engine baseline patches). The
original sentence below, "Canvas remains a valuable upstream source during migration", describes the
pre-migration state. The rule that follows still applies, to the inherited **Canvas-derived code**.

Canvas remains a valuable upstream source during migration.

Aurora must not remove Canvas-derived code merely for branding.

Instead:

1. identify a dependency,
2. classify whether it is useful or accidental,
3. define an Aurora contract if needed,
4. reduce direct coupling,
5. benchmark replacements,
6. replace only when the result is better or more maintainable.

---

# 32. Performance Evidence

Every major execution or NMS optimization must include evidence.

At minimum:

```text
baseline SHA
candidate SHA
workload
Java version
JVM arguments
CPU/RAM
TPS/MSPT
p95/p99
CPU usage
allocation rate
GC
memory
correctness notes
```

---

# 33. Tail Latency

Aurora must optimize consistency, not only averages.

Primary metrics:

- MSPT average,
- p50,
- p95,
- p99,
- maximum,
- slow-region duration,
- queue wait time.

A low average does not compensate for frequent severe spikes.

---

# 34. Efficiency Metrics

Track efficiency using ratios such as:

```text
players / CPU core
players / GB RAM
entities / MSPT
chunks / CPU-second
packets / CPU-second
allocation / player
```

These help determine whether Aurora improves useful work per resource rather than only one isolated number.

---

# 35. Aurora Observability

All execution domains should expose low-cost health information to Sourby telemetry.

Target:

```text
Aurora runtime
   ├── gameplay
   ├── AI workers
   ├── chunk workers
   ├── storage
   ├── network
   └── I/O
        ↓
PerformanceSnapshot
        ↓
/perf / HUD / Spark
```

No expensive global scan should be required for routine diagnostics.

---

# 36. `/perf` Evolution

The command should eventually expose execution domains:

```text
/perf
/perf tick
/perf region
/perf scheduler
/perf ai
/perf chunks
/perf storage
/perf network
/perf memory
/perf gc
/perf health
```

Only metrics with reliable instrumentation should be shown.

*Status 2026-10-25:* `/perf` lists `tick`, `cpu`, `memory`, `gc`, `lanes`, `async`, `network`,
`governor`, `scheduler`, `incidents`, `storage`, `awf`, `plugins`, `history`, `player`, `chunks`,
`entities`, `region` and `health` (`command/PerfViews.java`; the bare command shows an overview).
There is no `/perf ai` (the async-path view is `/perf async`). Each view's limits are in
[docs/guides/commands.md](../guides/commands.md).

---

# 37. Spark Integration

Spark remains an upstream profiler for now.

Aurora should feed Spark:

- SourbyCraft platform identity,
- the SourbyCraft public version identity (*status 2026-10-25:* "Build 44-style" when written; since
  Build 47 the public identity is `Build N`, formatted by `BuildInfo.formatReleaseIdentity`),
- Aurora-owned TPS/MSPT metrics,
- region/runtime context where supported,
- SourbyCraft configuration metadata.

A custom Spark web viewer is explicitly deferred.

---

# 38. Migration Roadmap

## Phase 0 — Certified baseline

- real gameplay workloads,
- JFR CPU ranking,
- allocation ranking,
- contention analysis,
- multi-hour soak.

Contention analysis is done: all measurable blocking is region thread against region
thread on `EDFSchedulerThreadPool$TickThreadRunner.takeTask`, with safepoints
negligible.

The multi-hour soak is **certified**: two hours at ten players with resident memory
down 5.9%, heap after GC down 1.3% and tick down 0.6%, ten of ten clients still
connected and a clean 8.1 s shutdown. There is no memory leak. An earlier fifty-player
attempt exhausted its 6 GiB heap in sixteen minutes and is not certified; that was
working-set size against the ceiling, not retention. Both are recorded in
`docs/BASELINE.md`.

Phase 0 is therefore complete, with two faults logged against later phases: SourbyCraft
telemetry never leaves `WARMING` even after two hours, and the `players-N` workloads
deliver roughly half the regions they declare.

*Status 2026-10-25:* the `WARMING` fault is fixed (readiness now asks whether the collector has
watched long enough, not whether one region generation spanned the window; a 20-minute run reported
`AVAILABLE`; [BASELINE.md](../BASELINE.md), "Two things this run exposed"). "Phase 0 is complete"
overstates the qualification state: [qualification-readiness.md](qualification-readiness.md) still has
no certified comparable before/after reference pair, so the >3% regression gate cannot be evaluated.
The contention measurement above names `EDFSchedulerThreadPool`, which is again the active default
backend while the LeafPile-derived `AuroraEdfScheduler` stays parked (owner decision 2026-10-07).

## Phase 1 — Aurora configuration ownership

- typed config,
- Aurora namespaces,
- legacy read fallback,
- no automatic rewrite.

*Status 2026-10-25:* typed config and Aurora namespaces are implemented (§24). The last two bullets
were superseded by owner decision 2026-10-22 (Build 49): renamed or reformatted files are migrated
**once at boot, keeping every value**, with the original kept as `*.migrated` and the migration
logged: `config/canvas-*.yml` → `config/aurora-*.yml` (`EngineConfigFiles`), `sourbycraft_config/*.toml`
→ `*.yml` (`ConfigFormatMigration`) and the pre-49 key names (`ConfigMigration`). On the Build 50
candidate (PR #62; not in the published `v26.2-r50`) the pre-49 key names are no longer read as a
fallback; a leftover old name is ignored with one WARN. Code written and statically reviewed; not
built; not tested by the owner.
Rules: [independence.md §5](independence.md#5-configuration-independence).

## Phase 2 — Execution contract extraction

- document current region/scheduler dependency,
- isolate direct Canvas/Folia scheduler access,
- define minimal Aurora contracts.

The first bullet is done and measured rather than recalled:
`docs/architecture/execution-contract.md`. SourbyCraft-owned code reaches the internal
region scheduler at five sites across eighty files, and the integration patches at one.

That count settles the size of the mechanical edit and nothing else. The contract the
same document specifies — owner identity across dimension transfer, rejection, retirement,
stale-result validation — is where the work is, and a generic `execute(Runnable)` facade
would satisfy none of it while looking like independence. The third bullet is therefore
specified but not closed, and the second is not started.

*Status 2026-10-25:* the counts above are the first measurement and are historical. The current
inventory (`scripts/test_independence_policy.py`, recounted in
[execution-contract.md *Measured inventory*](execution-contract.md#measured-inventory)) is 59
non-comment lines in seven pinned files of SourbyCraft code: four adapters and two read-only views.
The second bullet ("isolate direct scheduler access") is therefore **done for SourbyCraft's own
sources** (Stage 3, §10), and the third is partial: the owner-handoff contract and the
`execution/region` contracts exist and are used, the remaining owned services use the published
scheduler API.

## Phase 3 — Deep entity and AI optimization

- direct NMS profiling,
- entity/collision/AI changes,
- async snapshot experiments where justified.

## Phase 4 — Chunk and world execution

- chunk lookup/tickets,
- generation,
- load/integration,
- world tick efficiency.

## Phase 5 — Storage independence

- save pipeline,
- serialization,
- region-file I/O,
- shutdown durability.

## Phase 6 — Network independence

- packet pipeline,
- serialization,
- buffer efficiency,
- queue health.

## Phase 7 — Scheduler independence

- move selected workloads behind Aurora execution,
- benchmark against current backend,
- gradually reduce concrete Folia/Canvas scheduler coupling.

## Phase 8 — Runtime qualification

- compatibility,
- soak,
- persistence,
- crash recovery,
- regression benchmark.

---

# 39. Replacement Policy

Aurora must not replace a working upstream subsystem unless one of these is true:

- measurable performance problem,
- serious reliability limitation,
- architectural coupling blocks Aurora development,
- maintenance/rebase cost is excessive,
- required functionality cannot be added safely.

Replacement for branding alone is not sufficient.

---

# 40. Definition of Independent Aurora Engine

Aurora can be considered architecture-independent when:

1. SourbyCraft defines the runtime contract.
2. Folia is no longer the architecture authority.
3. Canvas is no longer the product runtime identity.
4. Aurora owns its configuration model.
5. Aurora owns lifecycle and telemetry.
6. New subsystems can choose execution models based on workload rather than Folia constraints.
7. At least one major execution subsystem operates behind an Aurora-owned contract.
8. Direct Minecraft/NMS optimization is routine and benchmarked.
9. SourbyCraft builds and runs without a separate Canvas server binary.
10. Remaining Folia/Canvas dependencies are explicit and replaceable in principle.

---

# 41. Core Engineering Principle

Aurora should be:

```text
Aggressive in optimization
Conservative in correctness
Flexible in execution architecture
Strict in ownership
Measured in performance claims
Independent in runtime identity
Compatible where compatibility has value
```

The target is not to become “anti-Folia.”

The target is to make Folia one implementation influence among several rather than the permanent boundary of what SourbyCraft can become.

---

# 42. End State

The desired long-term result is:

```text
Minecraft workload
        ↓
Aurora Engine
        ↓
workload-specific execution
        ↓
optimized NMS algorithms
        ↓
explicit state ownership
        ↓
measured performance
        ↓
reliable persistence and compatibility
```

SourbyCraft should be able to evolve its scheduler, entity processing, AI, chunk pipeline, storage pipeline, memory model, and networking architecture independently when evidence supports the change.

Aurora Engine is therefore not defined by Folia, Canvas, or any single upstream implementation.

It is defined by SourbyCraft's own requirements:

> **speed, reliability, performance, stability, efficiency, and control over its own runtime architecture.**
