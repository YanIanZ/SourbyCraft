# SourbyCraft 26.2 — Unified Development Continuation

## Purpose

This document is the active development contract for the `26.2` branch. It consolidates the intent of `PRD.md`, the technical rules of `SPEC.md`, the execution direction of `PLAN.md`, and the work already completed in builds 44–45.

The older documents remain useful historical and design references. This file defines what must happen next, what is already complete, what remains unfinished, and how SourbyCraft moves from a Canvas-derived downstream fork into an independently owned SourbyCraft runtime while preserving upstream attribution and compatibility.

The priority order is:

1. correctness and world safety
2. runtime stability
3. measurable performance efficiency
4. maintainability and patch clarity
5. independence from upstream implementation details
6. new performance features

A performance change that violates correctness, region ownership, persistence, or plugin compatibility is a regression even if a benchmark is faster.

---

# 1. Product Direction

SourbyCraft must no longer behave as a thin product layer whose identity, diagnostics, configuration model, and runtime ownership are defined by Canvas.

The target is:

```text
Minecraft
   │
   ▼
SourbyCraft Runtime
   │
   ├── region execution
   ├── scheduler integration
   ├── performance telemetry
   ├── profiler integration
   ├── diagnostics / HUD
   ├── network integration
   ├── configuration ownership
   └── lifecycle ownership
   │
   ▼
Upstream implementation sources
(Paper / Folia / Canvas components where still required)
```

Upstream code may remain beneath SourbyCraft. Runtime behavior must not depend on upstream branding, user-facing command identity, remote services, or a second server distribution being installed alongside SourbyCraft.

Independence means ownership, not erasing history. Licenses, source headers, upstream copyright notices, and required attribution remain intact.

---

# 2. Governing Rules Carried Forward from PRD + SPEC

The following requirements remain mandatory.

## 2.1 Java 25

- production runtime targets Java 25
- production code must not require preview mode
- runtime architecture should use stable Java 25-era patterns where they improve ownership or efficiency
- new blocking administrative I/O may use virtual threads where appropriate
- region ticks, Netty event loops, and CPU-bound workers are not to be replaced blindly with virtual threads

## 2.2 No automatic performance configuration

SourbyCraft must not silently rewrite or dynamically reduce operator-controlled gameplay settings.

Forbidden examples include:

- automatic view-distance reduction
- automatic simulation-distance reduction
- dynamic mob-limit reduction
- lag-triggered AI disabling
- automatic compression changes
- automatic JVM flag rewriting
- automatic GC selection
- RAM-based config mutation
- player-count-based gameplay degradation

Diagnostics may warn and recommend. The operator decides.

## 2.3 Region ownership is authoritative

Any code touching entities, chunks, worlds, inventories, players, block entities, or region-local scheduler state must respect region ownership.

Prefer:

```text
region-local state
immutable publication
message / scheduler handoff
```

over shared mutable state and broad locking.

## 2.4 Performance changes require evidence

Each non-trivial optimization should answer:

- what was expensive?
- how was it observed?
- what is the baseline?
- what changed?
- what is the correctness risk?
- what is the threading risk?
- what is the memory impact?
- what does the before/after result show?

Speculative optimization is allowed only as an experiment, not as a release claim.

---

# 3. Current 26.2 Baseline

The branch already contains important modernization work and must build on it instead of restarting.

Completed or substantially completed:

- Java 25 build/runtime baseline
- immutable config snapshot for SourbyCraft utility config
- legacy automatic memory/swap tuning retired
- bounded administrative I/O executor behavior
- lifecycle shutdown repair for Sourby-owned services
- region-aware SourbyCraft metric API
- immutable performance snapshots
- custom region tick metrics
- GC tracking lifecycle repair
- `/tps`, `/mspt`, `/perf`, `/perfbar` modernization work
- HUD shared snapshot work
- Spark tick-statistics bridge to SourbyCraft metrics
- Spark platform identity refinement for SourbyCraft
- Spark configuration reporting for SourbyCraft-owned configuration
- JFR/baseline capture tooling
- baseline comparison tooling and provenance gates
- patch inventory / threading review documentation
- removal of unsafe shared `ServerLevel` scratch buffers
- safer immutable publication for effect-particle state
- build/release identity cleanup through build 45

Do not duplicate these systems under new names unless an existing implementation is being intentionally replaced with tests and migration notes.

---

# 4. Known Unfinished Work

The following work is explicitly carried forward and must not be lost when the branch is reorganized.

## 4.1 Benchmark proof remains incomplete

Build 45 added instrumentation, but controlled performance proof is still outstanding.

Required:

- before/after controlled gameplay workloads
- connected-client player simulations or real controlled clients
- long-running entity workload
- chunk load/unload/generation workload
- network workload with real gameplay traffic
- multi-hour soak testing
- memory-retention checks after load is removed

Status labels must distinguish:

```text
instrumented
measured
improved
regressed
inconclusive
```

Do not describe an instrumented subsystem as optimized until the measurements support that claim.

## 4.2 Async pathfinding audit remains incomplete

`AsyncPathProcessor` exists and is optional. It still requires a deeper review covering:

- snapshot completeness
- region handoff correctness
- cancellation
- queue saturation
- path result staleness
- shutdown
- CPU scalability
- comparison against synchronous upstream pathfinding
- mob behavior compatibility

It remains default-off until the validation gate is satisfied.

## 4.3 Object reuse audit remains incomplete

Existing object pools, reusable collections, `ThreadLocal` state, and scratch buffers require workload-specific review.

Every candidate must be classified:

```text
KEEP
REMOVE
REDESIGN
BENCHMARK REQUIRED
```

Do not assume allocation reuse is faster on Java 25.

## 4.4 SourbyClip audit remains incomplete

Audit:

- downloader concurrency
- timeout behavior
- retry policy
- first-boot failure handling
- cache validation
- offline operation after first successful boot
- memory / thread ownership
- shutdown behavior
- unnecessary parallelism

SourbyClip must remain bootstrap infrastructure, not an auto-tuning system.

## 4.5 Deep engine profiling remains incomplete

The following require evidence-driven profiling:

- entity tick
- AI / Brain / GoalSelector
- collision
- pathfinding
- chunk holder / ticket work
- generation
- world save
- region-file I/O
- entity tracking
- packet encode/decode
- compression
- plugin callback cost
- scheduler queue latency

---

# 5. Primary Development Goal: Efficiency + Stability

The next phase is not a race to add the largest number of optimization patches.

The target is stable useful work per unit of resource:

```text
players / CPU core
players / GB RAM
entities / MSPT
chunks / CPU-second
packets / CPU-second
```

Primary metrics:

- mean MSPT
- p50 / p95 / p99 MSPT
- worst-region MSPT
- process CPU
- region-worker utilization
- heap used
- RSS / container memory
- allocation rate
- GC pause and GC overhead
- scheduler backlog
- chunk load/generation latency
- network throughput

Performance improvements should reduce one or more costs without moving the cost into another hidden subsystem.

---

# 6. Stability Budget

A new optimization cannot be considered successful only because average MSPT improves.

A release candidate must also satisfy:

- no new cross-region unsafe access
- no new world corruption behavior
- no unexplained entity state loss
- no packet protocol breakage
- no persistence regression
- no executor/thread leaks
- no unbounded queue growth
- no sustained heap growth after workload removal
- clean shutdown
- repeatable boot

A small throughput regression may be accepted if it fixes a correctness or corruption hazard. Such a decision must be documented.

---

# 7. Performance Regression Gate

For stable, relevant benchmarks:

- unexplained >3% regression requires investigation before merge
- >5% regression requires explicit written justification
- >10% regression blocks merge unless the change fixes a correctness/security defect that cannot reasonably be separated

Noise-sensitive microbenchmarks must not be treated as release blockers until variance is understood.

Performance reports must record:

```text
baseline SHA
candidate SHA
Canvas/Paper upstream ref where relevant
Java version
JVM arguments
OS
CPU
RAM
world/seed
plugin set
config hash or archive
warmup duration
measurement duration
```

---

# 8. SourbyCraft Independence Program

Independence is delivered in stages.

## Stage A — Identity independence

Status: in progress.

Requirements:

- SourbyCraft is the public platform name
- `/version` and profiler metadata identify SourbyCraft
- Canvas is not the headline runtime identity
- upstream revision remains visible in developer/debug information

## Stage B — Observability independence

Status: in progress.

Requirements:

- SourbyCraft owns TPS/MSPT metrics
- SourbyCraft owns runtime memory/GC metrics
- HUD consumes SourbyCraft snapshots
- `/perf` consumes SourbyCraft snapshots
- Spark consumes SourbyCraft tick statistics rather than maintaining a conflicting calculation

## Stage C — Configuration independence

Status: partial.

Requirements:

- SourbyCraft configuration appears as a first-class config group in profiling reports
- Sourby-owned configuration has explicit Sourby paths/names
- no automatic destructive migration of Canvas configs
- new Sourby features must prefer Sourby-owned configuration surfaces
- legacy upstream config is treated as an engine compatibility interface until replaced safely

## Stage D — Runtime-service independence

Status: planned.

Move Sourby-owned behavior into lifecycle-managed services under `dev.iyanz.sourbycraft`.

Upstream patches should become narrow hooks rather than large feature implementations.

Preferred pattern:

```text
upstream method
    ↓ small integration hook
Sourby service
    ↓
Sourby-owned state / metrics / logic
```

## Stage E — Build independence

Status: planned and high-risk.

Goals:

- SourbyCraft builds reproducibly from its own repository
- no runtime dependency on Canvas distribution artifacts
- upstream source revisions remain pinned and reproducible
- Canvas-specific build assumptions are progressively isolated
- legacy `sourbypatcher` use is either removed from the active line or clearly isolated to historical/release compatibility

Build independence does **not** require immediately deleting all Canvas-derived source.

## Stage F — Engine ownership

Long-term target.

Subsystems that are heavily modified by SourbyCraft should progressively become Sourby-owned implementations when doing so reduces patch conflict and improves maintainability.

Candidates are chosen based on actual ownership pressure, not branding.

---

# 9. Free-Running Requirement

A normal production SourbyCraft server must be able to run as SourbyCraft without requiring a separate Canvas installation or Canvas runtime service.

After successful bootstrap/dependency acquisition, normal runtime should not require upstream network availability.

Target behavior:

```text
SourbyCraft JAR
   ↓
local dependency/cache validation
   ↓
SourbyCraft runtime
   ↓
worlds + plugins + local configs
```

No runtime system should periodically contact an upstream project simply to remain operational.

Optional update checking must be SourbyCraft-controlled and independently disableable.

---

# 10. Patch Independence Strategy

The patch system remains valid, but patch complexity must decrease over time.

Every patch should be classified:

```text
FOUNDATION
INTEGRATION
PERFORMANCE
COMPATIBILITY
SECURITY
BRANDING
LEGACY
```

Each patch should also have a disposition:

```text
KEEP
SPLIT
MOVE TO SOURBY SOURCE
REPLACE WITH UPSTREAM
REMOVE
DEFER
```

A major KPI is:

> More SourbyCraft capability with less invasive downstream patch surface.

Move large implementation bodies into Sourby-owned classes where possible.

Avoid repository-wide formatting and unrelated renames in patch files.

---

# 11. SourbySpark / Spark Integration Continuation

The current branch uses the existing Spark integration with SourbyCraft telemetry. Do not claim a fully independent Spark fork until it actually exists.

Next required work:

- ensure profiler platform metadata says SourbyCraft consistently
- ensure SourbyCraft config appears in configuration metadata
- redact secrets from Sourby-owned configs
- expose Sourby runtime metadata where supported
- keep TPS/MSPT values consistent with SourbyCraft metrics
- classify region threads and Sourby runtime workers clearly
- expose slow-region context to profiler metadata where technically appropriate
- avoid duplicating expensive metric scans solely for Spark

Potential later step:

```text
SourbySpark adapter/fork
```

Only create a deeper fork when it provides real Sourby-specific capability that cannot be maintained cleanly as a narrow integration layer.

License obligations must remain satisfied.

---

# 12. Telemetry Architecture Continuation

All consumer-facing diagnostics should converge on one source:

```text
region/tick counters
runtime sampler
GC events
memory sampler
network counters
scheduler counters
        ↓
MetricsRuntime
        ↓
immutable PerformanceSnapshot
        ↓
/tps  /mspt  /ram  /perf  HUD  Spark
```

Rules:

- consumers read one immutable snapshot per refresh/command invocation
- commands must not trigger global entity/chunk scans
- unsupported values remain explicitly unavailable rather than fabricated
- collection cadence must match metric cost
- history buffers must be bounded
- telemetry must be stoppable and lifecycle-owned

Core telemetry overhead target: below 0.5% CPU under representative normal workloads where practical.

---

# 13. `/perf` Continuation

`/perf` is the primary operator performance command.

Required roadmap:

```text
/perf
/perf tick
/perf cpu
/perf memory
/perf gc
/perf region
/perf region <world> <x> <z>
/perf player <player>
/perf chunks
/perf entities
/perf network
/perf scheduler
/perf plugins
/perf health
/perf history
/perf profile
```

Implementation order:

1. only expose metrics already collected cheaply
2. add instrumentation to missing domains
3. validate overhead
4. expose the metric

Do not implement a command by performing a full-world scan on demand if the result is intended to be routinely used.

---

# 14. HUD Continuation

Required operator HUD features:

- `/tpsbar`
- `/rambar`
- `/perfbar`
- bossbar first
- actionbar optional after bossbar stability

HUD refresh should normally remain around one second.

One global/shared snapshot should serve all viewers for a refresh cycle.

Player preference storage must not leak player references after disconnect.

---

# 15. Memory Efficiency Program

Order of work:

1. allocation profile
2. identify dominant source
3. prove lifetime/ownership
4. optimize
5. compare retained heap + CPU + allocation rate

Priority targets:

- temporary entity-query collections
- packet temporary buffers
- pathfinding nodes/snapshots
- chunk lookup wrappers
- repeated map/set allocation
- plugin-facing metadata generation
- serialization copies

Do not retain large objects merely to reduce short-lived allocation.

Cache requirements:

- owner
- natural or explicit bound
- invalidation
- shutdown/cleanup
- thread model

---

# 16. CPU Efficiency Program

Profile first.

Priority domains:

- entity AI
- collision
- pathfinding
- chunk holder lookup
- region scheduler task drain
- block entities
- entity tracking
- packet encode/compression

Avoid moving CPU-heavy work to generic async executors without addressing total CPU cost.

Parallelism is useful only when work is independent and scheduling overhead/coordination do not erase the gain.

---

# 17. Chunk and World Stability Program

Before optimization, establish metrics for:

- load latency
- generation latency
- save latency
- pending saves
- unload rate
- ticket pressure

Rules:

- no unsafe live mutable chunk state passed to background threads
- no concurrent region-file corruption risk
- no persistence weakening to improve benchmarks
- async save/generation changes require restart and crash-recovery tests

---

# 18. Network Efficiency Program

Collect before optimizing:

- packets in/out per second
- bytes in/out per second
- queue depth where available
- encode/decode cost
- compression cost
- chunk-send cost

Netty event loops must not perform disk, database, HTTP, or unrelated expensive server work.

No automatic compression-level tuning.

---

# 19. Plugin Cost Visibility

Long-term performance analysis should distinguish:

```text
SourbyCraft engine
plugin execution
GC/runtime
network
other workers
```

Plugin percentages must come from actual profiler samples or supported instrumentation, never estimates.

Do not break Bukkit/Paper event semantics just to make the engine portion of a profile look smaller.

---

# 20. Testing Matrix

Every release candidate should cover applicable items from this matrix.

## Build

- apply patches
- API compile/tests
- server compile/tests
- slim JAR
- boot test
- shutdown test
- Docker test where applicable

## Runtime correctness

- player join/leave
- world load/unload
- chunk load/unload
- entity spawn/despawn
- teleport across regions
- plugin enable/disable lifecycle where supported

## Stress

- entity stress
- chunk movement/generation
- region transition
- scheduler backlog
- packet/network load

## Long-running

- multi-hour soak
- heap stabilization
- thread-count stabilization
- queue stabilization
- post-load recovery

---

# 21. Release Gates

A release may not be marked performance-stable until:

- boot/shutdown are clean
- region ownership tests pass
- no known data-corruption regression exists
- controlled baseline comparison exists for major performance claims
- a soak test has been completed for changes affecting concurrency/lifecycle
- profiler/telemetry overhead is measured where significantly changed
- config files are not silently rewritten
- upstream attribution and licenses remain correct

---

# 22. Development Phases from This Point

## Phase 46A — Consolidate and stabilize

- make this document the active continuation plan
- remove duplicate/contradictory implementation paths
- ensure `PRD.md`, `SPEC.md`, and `PLAN.md` remain reference documents
- close incomplete tests with zero-byte/placeholder implementations if any remain
- run patch regeneration and full unit/integration suite

## Phase 46B — Performance evidence

- run certified baseline workloads
- produce before/after reports
- capture JFR for CPU/allocation hot spots
- create a ranked optimization backlog from evidence

## Phase 46C — Runtime stability

- complete async-path audit
- complete object reuse / ThreadLocal audit
- complete executor ownership audit
- complete SourbyClip lifecycle/downloader audit
- run multi-hour soak

## Phase 46D — Observability completeness

- finish RAM command and memory metrics surface if incomplete
- finish `/perf` subcommands progressively
- refine Spark metadata/config integration
- add bounded performance incident history
- ensure HUD and commands read the same snapshot

## Phase 46E — Hot-path optimization

Implement only the top measured bottlenecks from JFR/baseline evidence.

Candidate domains:

- entity/AI
- collision
- chunk
- network
- scheduler
- serialization

## Phase 46F — Independence

- isolate Canvas-facing runtime hooks
- move Sourby implementations out of upstream patch bodies
- introduce Sourby-owned adapters/interfaces where they reduce upstream coupling
- document each remaining hard Canvas dependency
- remove hard dependency only after equivalent Sourby ownership exists

## Phase 46G — Release stabilization

- full benchmark rerun
- regression analysis
- multi-hour soak
- plugin compatibility smoke suite
- release notes with measured claims only

---

# 23. Independence Dependency Ledger

Maintain a dependency ledger in `docs/architecture/independence.md`.

Every Canvas/Paper/Folia hard dependency should record:

- dependency
- reason it exists
- runtime or build-time
- public or internal
- replacement difficulty
- Sourby replacement target
- current status

This avoids deleting upstream dependencies blindly and makes independence measurable.

---

# 24. Definition of Done for the 26.2 Independence/Performance Program

The program is complete when all of the following are true:

1. Java 25 is the stable production baseline.
2. SourbyCraft starts, runs, profiles, and shuts down under its own public identity.
3. No separate Canvas server installation is required.
4. Runtime does not require Canvas network availability.
5. SourbyCraft owns its public performance telemetry.
6. `/tps`, `/mspt`, `/ram`, `/perf`, and HUD consume the same metrics source.
7. Spark integration reports SourbyCraft identity and configuration correctly.
8. Region-aware performance diagnosis is available.
9. Performance claims have reproducible evidence.
10. Multi-hour soak tests show no new thread/heap/queue growth.
11. Async I/O and executor ownership are explicit.
12. Region-thread correctness is preserved.
13. No auto-tuning or hidden config rewriting exists.
14. Large downstream implementations are increasingly Sourby-owned instead of embedded in upstream patches.
15. Remaining Canvas/Paper/Folia dependencies are documented and intentional.
16. SourbyCraft can update upstream components without redefining its product architecture.
17. Patch surface is smaller or better isolated than before the independence program.
18. Operator configuration remains explicit and predictable.
19. World persistence remains correct under restart and stress.
20. Performance is equal or better on controlled workloads, except for explicitly justified correctness fixes.

---

# 25. Final Principle

SourbyCraft independence must be achieved through engineering ownership, not cosmetic renaming.

The target is not:

```text
rename Canvas symbols -> SourbyCraft
```

The target is:

```text
SourbyCraft owns lifecycle
SourbyCraft owns metrics
SourbyCraft owns diagnostics
SourbyCraft owns configuration for its features
SourbyCraft owns its runtime services
SourbyCraft owns its release engineering
upstream becomes replaceable implementation detail
```

A mature SourbyCraft release should remain stable even as upstream internals change, because the public runtime contract and most Sourby-specific behavior are controlled by SourbyCraft itself.


---

# Development truthfulness and non-misleading policy

This policy is mandatory for Aurora development, documentation, commit messages, PR descriptions and release notes.

## 1. Source-of-truth order

When statements conflict:

1. **current branch code and effective runtime resolution** define what the server currently does;
2. **generated/runtime evidence** defines what a specific build actually did;
3. **certified baseline artifacts** define measured performance for their exact workload/environment;
4. architecture/roadmap prose defines intended direction only.

A stale document must be corrected; do not change code merely to preserve old prose.

## 2. Status words are not interchangeable

Use only the strongest status actually proven:

- **PLANNED** — no active implementation claim;
- **IMPLEMENTED** — code exists and functional tests pass;
- **EXPERIMENTAL** — implemented but not production-qualified;
- **MEASURED** — observation from a named workload/run;
- **CERTIFIED** — harness accepted the run under its certification rules;
- **QUALIFIED** — all applicable correctness, regression, soak, persistence and compatibility gates pass.

"Build passes", "tests pass", "boot verified", "profile improved", "soak passed" and "qualified" are different claims.

## 3. Performance claim requirements

Every performance claim must identify enough provenance to reproduce or correctly scope it:

- commit/build;
- workload;
- hardware/container CPU and memory;
- relevant thread/worker/config values;
- seeded/fresh world state when relevant;
- connected-player/entity fidelity when relevant;
- measurement window;
- certification/noise state;
- metric and statistic (mean/p95/p99/max, allocation, RSS, CPU, etc.).

Do not generalize one host or workload into "Aurora is X% faster". Prefer: "On workload W, build B reduced metric M from X to Y under conditions C."

Uncertified runs may guide investigation but must be labeled uncertified and must not support release-wide throughput claims.

## 4. Correctness outranks benchmark wins

Do not keep an optimization merely because a profiler or benchmark improved if it changes observable semantics, ownership safety, persistence, plugin callback behavior, lifecycle guarantees or shutdown reliability.

For NMS/entity/scheduler changes, record:

- ownership/thread-safety assumptions;
- reentrancy/plugin callback effects;
- persistence implications;
- overload/failure behavior;
- before/after evidence;
- rollback/default-off strategy for material risk.

## 5. No benchmark-by-configuration trick

Do not claim engine performance gains obtained by silently reducing gameplay work, including view/simulation distance, spawn/entity limits, AI, compression quality, save durability or equivalent behavior, unless the change itself is the explicitly evaluated product policy and is disclosed.

## 6. No thread-count folklore

"More threads", "all cores", "virtual threads", "async" and "native" are not optimizations by themselves.

Any concurrency change must account for total CPU ownership across region ticks, chunk workers, Netty, GC/JIT, plugins and Aurora workers. Measure queue wait/age and tail latency, not only average CPU/TPS.

## 7. Overload behavior is part of correctness

Bound queues and define what happens at saturation. A fallback that moves CPU-heavy work back onto a latency-critical region thread must be treated as a potential performance cliff and measured explicitly.

Prefer graceful degradation/admission control for deferrable work over unbounded backlog.

## 8. Documentation synchronization

A change that alters any of these must update the relevant docs in the same change:

- default value or precedence;
- config file/path;
- live vs restart-required lifecycle;
- feature default-on/off status;
- benchmark/qualification state;
- supported command/status surface;
- upstream/compatibility ownership.

Historical benchmark documents may retain old configurations, but must label them historical instead of presenting them as current defaults.

## 9. Required review question

Before merging a performance change, answer:

> What exact claim will this change allow us to make, and what evidence prevents that claim from being stronger than the data?

If that cannot be answered precisely, the change may still be experimental, but it must not be marketed as a qualified performance improvement.
