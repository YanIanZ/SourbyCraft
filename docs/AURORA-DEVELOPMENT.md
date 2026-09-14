# Aurora Development Continuation

## Purpose

This document extends the existing `PRD.md`, `SPEC.md`, `PLAN.md`, `DEVELOPMENT.md`, and `docs/DEVELOPMENT-TASKS.md` without replacing their completed work.

Its job is to deepen the next phase requested for SourbyCraft 26.2:

1. continue unfinished performance/stability work,
2. make Aurora the named SourbyCraft architecture,
3. move new Sourby behavior into Sourby-owned configuration,
4. optimize Minecraft/NMS internals directly when that is the correct hot-path location,
5. reduce dependence on Canvas implementation details,
6. keep performance work evidence-driven and region-safe.

---

# 1. Current State to Preserve

Do not restart or duplicate systems already present in branch `26.2`.

Preserve and continue:

- Java 25 baseline
- immutable Sourby config snapshot work
- no-auto-tuning policy
- MetricsRuntime / Sourby metrics API
- region tick metrics
- GC/runtime sampler
- `/tps`, `/mspt`, `/perf`, HUD work
- Spark bridge to Sourby metrics
- Spark SourbyCraft platform/config integration
- bounded administrative I/O
- async pathfinding work and its open audit items
- JFR/baseline tooling
- patch/threading/reuse audits
- build 44/45 correctness fixes
- bootstrap/SourbyClip reliability work already started

Any replacement requires explicit migration notes and regression tests.

---

# 2. New Architecture Name

The architecture is named **Aurora**.

Aurora is the SourbyCraft-owned architecture layer, not the name of an upstream engine.

Use the term consistently for:

- runtime architecture
- configuration ownership
- performance subsystem grouping
- deep NMS optimization program
- observability integration
- independence milestones

Avoid presenting Aurora as if it were a separate plugin.

---

# 3. Aurora Configuration Deepening

The next development phase must stop adding SourbyCraft-specific behavior to upstream config by default.

## 3.1 Immediate target

Continue using:

```text
sourbycraft_config/sourbycraft_global_config.toml
```

but add explicit logical namespaces:

```toml
[aurora.performance]
[aurora.scheduler]
[aurora.entity]
[aurora.chunk]
[aurora.network]
[aurora.memory]
[aurora.diagnostics]
```

## 3.2 Migration rule

Do not automatically rewrite existing operator values.

For an old Sourby setting that moves into Aurora:

1. read the new Aurora key first,
2. optionally read the legacy Sourby key as compatibility fallback,
3. log a deprecation notice,
4. never save the migrated value automatically,
5. document the replacement key.

Do not silently migrate Canvas settings into Aurora until SourbyCraft actually owns the corresponding behavior.

## 3.3 Typed runtime config

Create typed immutable config views rather than accessing dotted strings inside hot paths.

Target conceptual types:

```text
AuroraConfig
AuroraPerformanceConfig
AuroraSchedulerConfig
AuroraEntityConfig
AuroraChunkConfig
AuroraNetworkConfig
AuroraMemoryConfig
AuroraDiagnosticsConfig
```

These may be records or final immutable classes.

## 3.4 Reload behavior

Every setting must be marked:

```text
LIVE
RESTART_REQUIRED
IMMUTABLE_FOR_RUN
```

`/sourbycraft reload` must report when changed settings require restart.

---

# 4. First Aurora Config Candidates

Only introduce keys for implemented behavior.

Recommended first candidates based on current branch work:

```toml
[aurora.performance]
telemetry-enabled = true
runtime-sample-interval-ms = 1000
incident-history-size = 20

[aurora.entity]
async-pathfinding = false

[aurora.diagnostics]
hud-refresh-ticks = 20
spark-include-sourby-config = true
```

Do not create configuration switches for optimizations that should always preserve semantics and have no reason to be operator-selectable.

Example: a safe reduction of a redundant map lookup should not automatically become a config option.

---

# 5. Deep Minecraft/NMS Development Track

Aurora performance work must include direct Minecraft code optimization, not only external Sourby services.

The development model is:

```text
JFR / benchmark
      ↓
identify hot Minecraft method
      ↓
prove ownership + semantics
      ↓
modify NMS implementation
      ↓
regression tests
      ↓
benchmark candidate
      ↓
keep / revert
```

---

# 6. Entity / AI Track

Profile first and rank by total CPU/allocation.

Targets:

- `Entity.tick`
- `LivingEntity.tick`
- `Mob.tick`
- `GoalSelector`
- `Brain`
- sensors/behaviors
- path navigation
- entity collision
- entity section lookup
- item entity merge/pickup
- entity tracker updates

Potential optimizations:

- eliminate duplicate world/spatial lookups
- remove duplicate distance math
- reduce temporary collections
- avoid repeated immutable state reconstruction
- preserve region-confined scratch only where proven safe
- reduce repeated path recalculation when state has not changed
- reduce tracking work when no observable state changed

Do not reduce AI rate automatically because server load is high.

---

# 7. Chunk / World Track

Profile:

- chunk holder lookup
- ticket processing
- generation
- integration
- player chunk tracking
- serialization
- save queues
- unload
- region-file I/O

Potential Aurora NMS work:

- reduce repeated holder lookup
- reduce coordinate packing/unpacking
- avoid repeated serialization
- reduce temporary buffer/list creation
- improve batching where semantics permit
- move blocking disk operations away from region execution only with safe snapshots/ownership

Persistence failures are release-blocking.

---

# 8. Scheduler / Concurrency Track

Continue the current audit before introducing new pools.

Required work:

- inventory Sourby-owned executors
- find implicit common-pool usage
- finish async path snapshot correctness audit
- validate stale path result handling
- test queue saturation
- benchmark sync vs async pathfinding
- measure worker queue latency/depth
- verify external I/O never blocks region threads

Aurora should own Sourby worker lifecycle while upstream region scheduling remains authoritative until a replacement is justified.

---

# 9. Network Track

Add measurement before optimization:

- packets/sec
- bytes/sec
- encode/decode CPU
- compression CPU
- chunk-send cost
- packet queue behavior

Then consider direct packet/NMS changes such as:

- fewer temporary buffers/copies
- fewer duplicate metadata packets
- more efficient chunk packet construction
- improved flush/batching where protocol semantics allow

Never place unrelated blocking work on Netty event loops.

---

# 10. Memory / GC Track

Complete:

- JFR allocation ranking
- top allocated class workflow
- RSS/container verification
- post-load heap recovery
- player/chunk/world retention tests
- future/task retention tests
- cache ownership inventory

Aurora should prefer lower retained memory and lower allocation without creating risky permanent object pools.

---

# 11. Spark / Observability Track

Continue existing integration before considering a deep fork.

Required:

- verify Sourby config display in the Spark web report
- ensure secret redaction remains correct
- add Sourby runtime metadata cheaply
- improve region-thread classification
- add slow-region context where supported
- align all TPS/MSPT numbers with Sourby metrics
- document upstream Spark update procedure

Decision gate for a dedicated SourbySpark fork:

Only fork when required capabilities cannot be implemented cleanly through the current module/provider integration.

---

# 12. Patch Architecture under Aurora

Patches are still valid.

Aurora changes their role.

Preferred pattern:

```text
small upstream/NMS patch
      ↓
local optimized code or narrow Sourby hook
      ↓
Sourby-owned service where appropriate
```

Direct NMS algorithm changes may remain as patches when moving them to external services would create indirection or make the optimization worse.

Therefore:

- move **service logic** out of upstream classes,
- keep **local algorithmic NMS optimizations** close to the hot path.

This distinction is important.

---

# 13. Patch Review Template

Each Aurora performance patch should state:

```text
Subsystem:
Optimization class:
Hot method:
Profile evidence:
Region ownership:
Behavior impact:
Memory impact:
Expected metric:
Benchmark command/workload:
Before:
After:
Regression coverage:
Disposition:
```

---

# 14. Independence Track

Aurora independence proceeds without sacrificing performance.

Required next work:

- inventory direct Canvas accesses from Sourby packages
- classify which are stable contracts vs accidental coupling
- define minimal region/scheduler/config bridges
- isolate Canvas-specific Spark integration where useful
- remove active-line legacy build steps only after clean-build proof
- verify clean checkout reproducibility
- verify cached/offline runtime
- track rebase conflict count

Do not rename upstream packages wholesale.

---

# 15. Definition of “Free-Running”

SourbyCraft is considered free-running when:

- one SourbyCraft server artifact is enough to run the server,
- no separate Canvas installation is required,
- no Canvas service/API is required,
- runtime does not require Canvas network availability,
- Sourby-owned features use Sourby-owned runtime/config contracts,
- updater/profiler network features are optional,
- cached dependencies permit normal boot/run offline after successful acquisition.

Upstream source may still be used during build.

---

# 16. Next Execution Phases

## Phase Aurora-1 — Config Ownership

- add typed Aurora config model
- add logical Aurora namespaces
- migrate current Sourby-owned performance keys without rewriting operator files
- add restart-required reporting
- expose Aurora config in Spark metadata

## Phase Aurora-2 — Certified Baseline

- idle
- connected 10-player equivalent
- 50-player equivalent
- 100-player equivalent
- entity stress
- chunk stress
- gameplay network
- 2h+ soak

## Phase Aurora-3 — JFR Ranking

Produce ranked lists for:

- CPU
- allocation
- lock contention
- file I/O
- socket I/O

## Phase Aurora-4 — Deep Entity/AI Optimization

Implement only top-ranked candidates.

## Phase Aurora-5 — Chunk/World Optimization

Implement only measured bottlenecks with persistence qualification.

## Phase Aurora-6 — Network Optimization

Optimize encoding/compression/chunk-send paths based on profile evidence.

## Phase Aurora-7 — Independence Isolation

Reduce hard Canvas coupling without adding hot-path abstraction cost.

## Phase Aurora-8 — Release Qualification

- Java tests
- patch regeneration
- boot/shutdown
- restart persistence
- benchmark comparison
- multi-hour soak
- heap recovery
- offline cached boot
- license/attribution review

---

# 17. Immediate Next Tasks

The next development iteration should prioritize:

1. **Aurora config model** in Sourby-owned code.
2. Migrate `perf.ai.async-pathfinding` toward `aurora.entity.async-pathfinding` with read-only legacy fallback.
3. Add typed config snapshots consumed by runtime services.
4. Verify Spark displays the Aurora/Sourby config group correctly.
5. Complete certified baseline and JFR ranking.
6. Choose the first deep NMS optimization from measured CPU/allocation hot spots.
7. Keep every direct Minecraft optimization region-safe and benchmarked.
8. Continue unfinished SourbyClip reliability qualification in parallel, because bootstrap failures block independent operation.

---

# 18. Success Criteria

The next Aurora milestone succeeds when:

- Aurora exists as a real configuration/runtime architecture, not only a codename,
- at least one current Sourby performance setting has a typed Aurora-owned config path,
- legacy operator config is preserved without automatic rewrite,
- Spark can report Sourby/Aurora configuration safely,
- representative baselines exist,
- JFR identifies ranked hot paths,
- at least one measured deep Minecraft/NMS optimization is validated,
- no new region/persistence stability regression is introduced,
- SourbyCraft moves measurably closer to running under its own runtime contract.
