# Aurora Engine — Full Transition Completion Plan

## SourbyCraft 26.2

**Document type:** Architecture transition execution plan  
**Branch:** `26.2`  
**Runtime:** Java 25  
**Engine:** Aurora  
**Minecraft target:** 26.2  
**Primary objective:** Complete the transition from a Canvas/Folia-derived runtime into a SourbyCraft-owned Aurora Engine while preserving correctness, compatibility, observability, and measurable performance.

---

# 1. Purpose

This document defines the work required to finish the Aurora transition completely.

The transition is not complete when SourbyCraft only:

- changes branding,
- owns commands,
- owns configuration,
- owns metrics,
- patches Spark,
- or adds optimizations around the existing Folia/Canvas execution model.

The transition is complete when:

> SourbyCraft owns the runtime contract, execution model, configuration model, observability model, lifecycle, performance policy, compatibility boundary, and engine integration points required to run Minecraft as Aurora.

Paper, Folia, and Canvas may still be used as upstream source inputs where practical, but they must no longer be the architectural authority of the running server.

---

# 2. Current State

The `26.2` branch already has useful Aurora foundations.

Implemented or partially implemented today:

- Java 25 production baseline,
- `AuroraConfig` typed immutable configuration,
- `aurora.entity.async-pathfinding`,
- read-only legacy configuration fallback,
- SourbyCraft metrics runtime,
- immutable performance snapshots,
- region tick metrics,
- runtime and GC telemetry,
- async path worker with bounded CPU execution,
- SourbyCraft Spark metrics bridge,
- SourbyCraft Spark configuration provider,
- SourbyCraft HUD and performance commands,
- direct Minecraft/NMS patches,
- benchmark/JFR tooling,
- explicit shutdown hooks,
- architecture documentation defining Aurora beyond Folia.

However, several critical areas still depend directly on Canvas/Folia implementation details.

Examples include:

- direct Canvas configuration reload calls,
- Canvas/Folia scheduler assumptions,
- Spark integration patched through Canvas classes,
- build metadata still carrying Canvas-era identity,
- runtime comments and package documentation still describing SourbyCraft as a utility layer,
- no first-class Aurora execution contract,
- no Aurora-owned scheduler boundary,
- no Aurora compatibility layer that isolates upstream APIs,
- no independent Entity/Chunk/Network/Storage engine contracts,
- no complete dependency ledger proving which Canvas/Folia internals remain required.

Therefore the current state is:

```text
Aurora identity            = established
Aurora configuration       = started
Aurora observability       = strong foundation
Aurora execution model     = partial
Aurora engine ownership    = partial
Aurora scheduler ownership = not complete
Aurora compatibility layer = not complete
Canvas/Folia isolation     = not complete
```

---

# 3. Final Target

The completed architecture must look conceptually like this:

```text
                           SOURBYCRAFT
                                │
                          AURORA ENGINE
                                │
         ┌──────────────────────┼──────────────────────┐
         │                      │                      │
   Runtime Core           Execution Core         Observability
         │                      │                      │
  Configuration          Ownership Model          Telemetry
  Lifecycle              Scheduler                /perf
  Services               Task Routing             HUD
  Build Identity         Async Compute            Spark Bridge
         │                      │                      │
         └──────────────┬───────┴────────┬─────────────┘
                        │                │
                 Engine Domains     Compatibility
                        │                │
       ┌────────────────┼────────────┐   │
       │                │            │   │
    Entity/AI       Chunk/World   Network/Storage
       │                │            │
       └────────────────┴────────────┘
                        │
                        ▼
                 Minecraft / NMS
                        │
                        ▼
               Upstream Source Inputs
               Paper / Folia / Canvas
```

The important rule is:

> Upstream may provide implementation code, but Aurora defines ownership and runtime behavior.

---

# 4. Transition Principles

The full transition must follow these rules.

## 4.1 No big-bang rewrite

Do not delete Folia or Canvas internals first and then rebuild everything from scratch.

Transition one subsystem at a time behind Aurora-owned contracts.

## 4.2 Compatibility is not architecture

Bukkit, Paper, and Folia APIs may remain compatible even when their internal implementation is replaced.

## 4.3 Ownership before concurrency

Any new scheduler or async design must define:

- owner,
- allowed reads,
- allowed writes,
- handoff,
- cancellation,
- stale-result validation,
- shutdown behavior.

## 4.4 No automatic gameplay degradation

Aurora must never gain performance by silently changing:

- view distance,
- simulation distance,
- entity limits,
- mob AI,
- chunk limits,
- compression,
- JVM flags,
- GC selection,
- operator config.

## 4.5 Measured optimization only

Every deep engine optimization must be justified by:

- CPU evidence,
- allocation evidence,
- MSPT evidence,
- I/O evidence,
- or reproducible workload evidence.

## 4.6 Stable semantics over benchmark vanity

A lower benchmark score is not a success if gameplay, persistence, plugins, or shutdown correctness regress.

---

# 5. Transition Workstreams

The transition is divided into twelve workstreams.

```text
T0  Identity cleanup
T1  Aurora runtime contract
T2  Aurora configuration ownership
T3  Aurora execution model
T4  Aurora scheduler boundary
T5  Aurora engine domains
T6  Aurora compatibility layer
T7  Aurora observability ownership
T8  Build and bootstrap independence
T9  Canvas/Folia dependency isolation
T10 Qualification and regression
T11 Final cutover
```

Each workstream has a completion gate.

---

# 6. T0 — Identity Cleanup

Before deeper architectural work, remove stale source-level assumptions that still describe SourbyCraft as a Canvas utility layer.

## Required changes

Update:

- root `package-info.java`,
- `SourbyCraftBootstrap` comments,
- build comments,
- Spark version identity,
- README terminology,
- internal docs,
- generated metadata.

Remove or replace terminology such as:

```text
utility layer
Canvas re-platform
SourbyCraft-on-Canvas
display-only perf enum
```

when it no longer describes reality.

## Build metadata

Spark, startup banner, `/version`, crash reports, and manifest metadata must consume one canonical build source.

Target:

```text
gradle.properties
        ↓
generated build metadata
        ↓
BuildInfo
 ├── startup banner
 ├── /version
 ├── Spark
 ├── crash report
 └── diagnostics
```

No hard-coded `Build44`, `Build45`, or similar strings may remain in runtime integration code.

## T0 completion gate

- one canonical build identity,
- no stale Canvas-utility-layer documentation in active runtime packages,
- no hard-coded build number in Spark integration,
- Aurora terminology consistently describes runtime ownership.

---

# 7. T1 — Aurora Runtime Contract

Create the central runtime contract that all Sourby-owned subsystems use.

The goal is not a giant service locator.

The goal is a small, explicit runtime ownership model.

Suggested conceptual structure:

```text
AuroraRuntime
├── config
├── lifecycle
├── execution
├── telemetry
├── diagnostics
└── compatibility
```

The implementation may remain split into focused services.

## Required capabilities

Aurora runtime must own:

- startup order,
- service initialization,
- service shutdown,
- configuration publication,
- executor lifecycle,
- metrics publication,
- compatibility bridges.

## Lifecycle states

At minimum:

```text
NEW
BOOTSTRAPPING
STARTING
RUNNING
STOPPING
STOPPED
FAILED
```

Subsystems must not admit new work after the runtime enters `STOPPING`.

## T1 completion gate

- Sourby runtime lifecycle exists explicitly,
- every Sourby-owned executor has runtime ownership,
- every long-lived Sourby service has explicit startup/shutdown ownership,
- no subsystem relies on accidental static initialization for correctness.

### T1 status

`core/AuroraRuntime` holds the seven states and the legal transitions between them.
`SourbyCraftBootstrap` drives it: BOOTSTRAPPING and STARTING as the stage runner begins, RUNNING
or FAILED when it finishes, STOPPING before the first service goes down, STOPPED after the last.

The problem it solves is concrete. Five services each answered "are we shutting down" their own
way — a `stopped` flag in `AsyncPathProcessor`, `isShutdown()` in `VirtualExecutor`, `running` in
`MetricsRuntime`, `taskStarted` in `HudBars`, `closed` in `PerformanceCollector`. Five answers to
one question is five chances for one to be wrong during the seconds when shutdown is in flight and
work is still arriving, which is the only time it matters. `acceptingWork()` is that question, in
one place; a service's own state still answers its own lifecycle.

An illegal transition is reported and then made. Refusing it would leave the runtime claiming to
run while its services go down, which is worse than the inconsistency it guards — shutdown has to
be able to finish. FAILED is entered only when no stage came up at all: a server missing one
service that says which is more useful than one that refuses to start.

Still open against this gate: `HudBars`, `GcTracker` and `MetricsRuntime` have explicit
startup/shutdown but do not yet consult `acceptingWork()`, so the fourth bullet is partly met —
their correctness does not depend on static initialisation, but their admission does not consult
the runtime either.

---

# 8. T2 — Aurora Configuration Ownership

Continue the existing typed Aurora configuration work.

Current implementation begins with:

```toml
[aurora.entity]
async-pathfinding = false
```

The final logical domains should be introduced only as real settings are implemented:

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

Do not generate empty files or empty sections purely for branding.

## Configuration rules

Every setting must define:

- type,
- default,
- validation,
- lifecycle,
- consumer,
- legacy fallback if applicable.

Lifecycle must be one of:

```text
LIVE
RESTART_REQUIRED
IMMUTABLE_FOR_RUN
```

## Upstream config isolation

Today Sourby reload still directly invokes Canvas configuration classes.

Transition target:

```text
/sourbycraft reload
        ↓
AuroraConfigService
        ├── Sourby-owned config
        └── UpstreamConfigBridge
                └── Canvas/Paper implementation
```

The runtime should not directly depend on Canvas config classes outside the compatibility bridge.

## T2 completion gate

- all Sourby settings use typed snapshots,
- no hot-path dotted-string lookups for Aurora settings,
- upstream config reloads are isolated behind a bridge,
- no automatic config migration/writeback,
- Spark config reporting reads Aurora-owned metadata safely.

### T2 status

Aurora settings live in `sourbycraft_config/aurora.toml`, separate from SourbyCraft's own file.
The unified file is still read underneath and the Aurora file layered over it, so a deployment
that predates the split keeps the values it had; only a newly created Aurora file is seeded,
because writing into an existing one would mask that fallback and turn an operator's setting into
a default without them touching anything.

`config/upstream/UpstreamConfigBridge` isolates the engine's own reload. `CanvasConfigBridge` is
now the only file in SourbyCraft that names Canvas — the independence tests pin that file, not
just the symbols — and the reload path above it names no engine at all. A part the engine cannot
re-read is reported and the previous values stay in force, rather than aborting the SourbyCraft
reload around it.

Two settings exist because two behaviours exist: `aurora.entity.async-pathfinding` and
`aurora.diagnostics.lane-sampling`, both LIVE, both with type, default, validation, consumer and
legacy fallback. The other eight namespaces this section lists are deliberately absent. The rule
above — *do not generate empty sections purely for branding* — is the same rule the config file
itself follows, and a namespace with no behaviour behind it is a promise the server cannot keep.

---

# 9. T3 — Aurora Execution Model

This is the most important transition step.

Aurora must define how work is executed independently of Folia implementation classes.

## Work classes

Aurora should classify runtime work into explicit categories:

```text
GAME_STATE_MUTATION
CPU_COMPUTE
BLOCKING_IO
NETWORK_EVENT
SERIALIZATION
BACKGROUND_MAINTENANCE
DIAGNOSTICS
```

Each category receives an execution policy.

## Base policy

```text
GAME_STATE_MUTATION
→ owner context only

CPU_COMPUTE
→ bounded CPU pool

BLOCKING_IO
→ virtual thread / bounded I/O service

NETWORK_EVENT
→ Netty event loop unless handed off

SERIALIZATION
→ explicit bounded execution policy

BACKGROUND_MAINTENANCE
→ low-priority bounded execution

DIAGNOSTICS
→ read-only snapshots
```

## Prototype

Async pathfinding should become the first proven Aurora execution pipeline.

Target model:

```text
owner context
    ↓
immutable snapshot
    ↓
Aurora CPU task
    ↓
result
    ↓
freshness/staleness validation
    ↓
owner-context commit
```

## Required abstractions

Use the smallest useful contracts.

Possible concepts:

```text
AuroraExecution
AuroraTaskClass
AuroraOwnerContext
AuroraCancellation
AuroraFreshnessToken
```

Do not introduce abstractions with no real consumer.

## T3 completion gate

- async pathfinding is migrated behind Aurora execution contracts,
- stale-result validation is explicit,
- cancellation is explicit,
- shutdown behavior is deterministic,
- queue saturation behavior is tested,
- no direct Folia scheduler class is required by Sourby-owned CPU/I/O work.

### T3 status

Every bullet is met. Async pathfinding runs the pipeline this section describes — owner context,
immutable snapshot, Aurora CPU task, validation, owner-context commit — through `OwnerHandoff`,
with `RegionOwnerHandoff` the only file naming the backend. Stale results are rejected on the
path-identity token derived from a traced set of invalidation events; cancellation is four
separate operations with separate tests; shutdown refuses admission and disposes what is
outstanding.

Saturation is now tested, and the test records something an operator should know before enabling
the feature. When the bounded queue fills, the rejection handler runs the solve on the submitting
thread — which is a region thread. So a saturated pool moves A* onto the thread the feature exists
to keep free, and because the immutable snapshot has already been built by that point, a saturated
async solve costs *more* than the synchronous path it replaced. "A slow path, never a dropped
path" is accurate, and the slow path is slower than not having the feature on.

That is a property of the current policy, not a defect in it: dropping the solve would leave the
mob on a stale path, and rejecting it would fall back to the same region-thread work anyway. It is
recorded here because the failure is invisible until tick times move, and because it is the
argument for sizing the pool against the workload rather than leaving it at cores/4.

### Deliberately not built

`AuroraTaskClass` and the seven work categories. Five of the seven have real consumers today —
owner handoff, the CPU pool, the I/O executors, the diagnostics samplers, the maintenance threads
— and an enum naming them would restate what those types already say. The section's own rule
applies: *do not introduce abstractions with no real consumer*. The categories become worth
declaring when a policy has to be enforced against them, for example when a second CPU-compute
pipeline needs the same admission rules as pathfinding. One pipeline does not need a taxonomy.

---

# 10. T4 — Aurora Scheduler Boundary

Aurora does not need to immediately replace every region scheduler implementation.

First, it must stop depending directly on one concrete scheduler everywhere.

## Scheduler contract

The Aurora scheduling boundary must support:

- owner-context task,
- player-context task,
- world-context task,
- delayed task,
- cancellable task,
- shutdown-aware task,
- CPU task handoff,
- I/O completion handoff.

## Compatibility implementation

Initially:

```text
AuroraScheduler
      ↓
FoliaCanvasSchedulerBridge
      ↓
existing region scheduler
```

Later:

```text
AuroraScheduler
      ↓
Aurora-native scheduler implementation
```

The public/runtime contract must remain stable while the implementation changes.

## T4 completion gate

- Sourby-owned code no longer requires direct concrete Folia/Canvas scheduler classes,
- compatibility bridge is isolated,
- scheduler metrics are exposed through Aurora telemetry,
- shutdown drains or rejects work predictably.

### T4 status

Met, by work already done rather than by anything new.

Concrete backend classes appear in three files and no others — `RegionOwnerHandoff`,
`FoliaRegionBackend`, `CanvasConfigBridge` — and the independence tests pin that file set.
Scheduler metrics reach the operator through `/sys`: busiest and average region utilisation, and
CPU starvation as "owed but not scheduled", which is the metric that says the scheduler wanted to
tick and could not. `/perf lanes` adds where the machine's time actually went. Shutdown is tested
per executor: the path pool refuses and disposes, the I/O executor rejects without blocking its
caller and cannot be lazily resurrected, and blocking I/O is interruptible.

### No AuroraScheduler wrapper

This section sketches `AuroraScheduler` over a `FoliaCanvasSchedulerBridge`. Six of the eight
capabilities it lists are already owned: owner-context through `OwnerHandoff`, CPU handoff through
`AsyncPathProcessor`, I/O completion through `VirtualExecutor`, shutdown-awareness through
`AuroraRuntime`. The remaining two — player-context and world-context tasks — are reached through
`org.bukkit.*` schedulers, which is the *published* Bukkit API rather than a concrete Folia class.

`docs/architecture/execution-contract.md` draws that line deliberately: depending on a published
contract is the goal, not the problem. Wrapping the Bukkit scheduler in an Aurora interface would
add a layer over a stable API without removing a dependency, and the gate's own wording asks for
no *concrete* scheduler classes, which is already true. The wrapper becomes worth building when an
Aurora-native scheduler actually exists to swap in behind it — at which point the interface can be
shaped by two implementations instead of guessed from one.

---

# 11. T5 — Aurora Engine Domains

Once execution ownership is stable, migrate major engine domains one by one.

## 11.1 Entity Engine

Own the optimization policy for:

- entity tick,
- living entity tick,
- mob tick,
- movement,
- collision,
- tracking,
- projectiles,
- item entities,
- metadata sync.

Direct NMS patches remain valid where local hot-path code is the best implementation location.

## 11.2 AI Engine

Own:

- GoalSelector,
- Brain,
- Sensor,
- navigation,
- pathfinding,
- target scans,
- async/snapshot computation policy.

## 11.3 Chunk Engine

Own:

- holder lookup,
- ticket processing,
- load,
- integration,
- unload,
- send,
- generation scheduling.

## 11.4 World Engine

Own optimization policy for:

- ServerLevel hot paths,
- scheduled ticks,
- random ticks,
- block updates,
- fluid updates,
- block entities.

## 11.5 Network Engine

Own:

- packet instrumentation,
- allocation/copy analysis,
- compression policy implementation,
- chunk packet path,
- entity tracking packet path,
- queue health.

Operator-visible compression settings remain operator-owned.

## 11.6 Storage Engine

Own:

- save queue behavior,
- serialization handoff,
- region-file write policy,
- shutdown flush,
- persistence validation.

No performance gain may come from silently weakening durability.

## Engine-domain rule

Do not create wrapper classes for local hot-loop algorithms merely to make them look "Aurora-owned".

Use:

```text
local algorithm change
→ direct NMS patch

shared subsystem
→ Aurora-owned service
```

## T5 completion gate

At least:

- Entity/AI,
- Chunk/World,
- Network,
- Storage

have explicit Aurora ownership documents, metrics, and implementation boundaries.

---

# 12. T6 — Aurora Compatibility Layer

Create a narrow compatibility boundary for Paper/Folia/Canvas-derived APIs and internals.

Conceptual structure:

```text
dev.iyanz.sourbycraft.compat
├── scheduling
├── config
├── spark
├── paper
└── upstream
```

Exact package names may differ.

The compatibility layer should contain:

- scheduler adapters,
- upstream config bridges,
- upstream version/build helpers,
- Spark platform adapter,
- compatibility shims.

It should not contain core Aurora business logic.

## Dependency direction

Allowed:

```text
Aurora core
    ↓
Aurora interface
    ↑
compat implementation
    ↓
Canvas/Folia/Paper
```

Avoid:

```text
Aurora core
    ↓
io.canvasmc....
```

except in direct NMS/upstream patches where indirection would be harmful and the dependency is intentionally documented.

## T6 completion gate

- direct upstream references are inventoried,
- accidental coupling is removed,
- intentional coupling is documented,
- core runtime can be reasoned about without reading Canvas service classes.

---

# 13. T7 — Aurora Observability Ownership

Observability is already one of the strongest Aurora areas.

Finish the transition by making every performance consumer use Aurora-owned data contracts.

Target:

```text
Minecraft / runtime hooks
        ↓
Aurora Metrics
        ↓
Immutable Performance Snapshot
        ├── /tps
        ├── /mspt
        ├── /ram
        ├── /perf
        ├── HUD
        └── Spark adapter
```

## Remove metric duplication

No independent TPS/MSPT calculation should survive unless required for compatibility.

## Add engine-domain telemetry

Complete:

- scheduler queue depth,
- task wait latency,
- chunk lifecycle metrics,
- entity counts/tick cost,
- network packet/byte rates,
- storage backlog,
- async-path queue depth,
- worker utilization.

All metrics must remain low overhead.

## Spark transition

Spark remains upstream for now.

Target integration:

```text
Spark
  ↑
AuroraSparkBridge
  ↑
Aurora Metrics + Aurora Config
```

Canvas Spark classes should become thin integration points only.

No Spark web viewer fork is required in this transition.

## T7 completion gate

- all Sourby performance commands use one snapshot model,
- Spark reads the same metrics source,
- domain telemetry exists without global scans,
- profiler integration contains no duplicated runtime truth.

---

# 14. T8 — Build and Bootstrap Independence

The server should build and boot as SourbyCraft.

## Build identity

Replace Canvas-era build assumptions.

Target build metadata:

```text
SourbyCraft
Minecraft 26.2
Aurora Engine
Build <canonical number>
Java 25
commit
channel
```

Upstream revision details may remain available under developer/debug output.

## Bootstrap

Aurora startup must not require a remote Canvas service.

Required behavior:

- first build may fetch source dependencies,
- first runtime bootstrap may fetch optional dependencies where documented,
- successful cached runtime must boot offline,
- updater failure must not break core server startup,
- profiler/upload failure must not break server startup.

## T8 completion gate

- clean checkout build is reproducible,
- packaged server identifies as SourbyCraft/Aurora,
- cached runtime boots offline,
- optional remote features fail independently,
- no runtime dependency on Canvas-hosted service exists.

---

# 15. T9 — Canvas/Folia Dependency Isolation

Create a dependency ledger.

Every direct dependency must be classified:

```text
REQUIRED_UPSTREAM_CONTRACT
TEMPORARY_IMPLEMENTATION
COMPATIBILITY_ONLY
DIRECT_NMS_PATCH
REMOVABLE
LEGACY
```

Inventory at minimum:

- `io.canvasmc.*`,
- Folia scheduler classes,
- Paper internals,
- Canvas config classes,
- Canvas Spark classes,
- build-time patch tooling,
- legacy sourbypatcher,
- old Folia compatibility code.

## Removal rule

Do not remove a dependency merely because it contains "Canvas" or "Folia".

Remove it only when:

- Aurora owns the required behavior,
- tests cover the replacement,
- benchmark/persistence checks pass,
- rebase complexity is improved or unchanged.

## T9 completion gate

- dependency ledger is complete,
- every direct Canvas/Folia dependency has a reason,
- no unexplained runtime dependency remains,
- all removable legacy layers are deleted.

---

# 16. T10 — Qualification and Regression

The transition is not done until the new architecture survives realistic load.

## Required workloads

- idle,
- 10 connected/equivalent players,
- 50 players,
- 100 players,
- entity stress,
- AI stress,
- chunk traversal,
- generation stress,
- save stress,
- network stress,
- plugin-heavy representative workload.

## Required metrics

- TPS,
- MSPT average,
- MSPT p50/p95/p99/max,
- CPU,
- heap,
- RSS,
- allocation rate,
- GC pauses,
- queue depths,
- task latency,
- chunk latency,
- entity cost,
- network throughput,
- storage backlog.

## Soak

Minimum qualification:

- 2h+ concurrency soak,
- repeated joins/quits,
- chunk load/unload cycles,
- restart cycle,
- shutdown under load,
- heap recovery after load.

## Persistence

Validate:

- clean shutdown,
- restart,
- chunk save integrity,
- player data,
- entity data,
- region files,
- world metadata.

## T10 completion gate

- representative benchmark exists,
- no unexplained >3% regression in affected workloads,
- no known region ownership bug,
- no known persistence bug,
- no unbounded queue,
- heap/threads/tasks stabilize after load,
- shutdown completes predictably.

---

# 17. T11 — Final Cutover

The final cutover happens only after all previous gates are met.

## Cutover actions

1. Make Aurora runtime the only Sourby-owned runtime entry point.
2. Route all Sourby-owned scheduling through Aurora contracts.
3. Route all Sourby-owned config through Aurora config services.
4. Route all performance commands/HUD/Spark through Aurora metrics.
5. Isolate remaining upstream internals behind compatibility or documented direct patches.
6. Delete obsolete Canvas/Folia-era wrappers.
7. Delete stale branding and comments.
8. Update README and architecture docs to describe the actual implementation.
9. Freeze transition-only compatibility shims for one release.
10. After one stable release, remove shims proven unnecessary.

## Final runtime model

```text
SourbyCraft
   │
   ▼
Aurora Engine
   │
   ├── Aurora Runtime
   ├── Aurora Execution
   ├── Aurora Scheduler Contract
   ├── Aurora Config
   ├── Aurora Metrics
   ├── Aurora Diagnostics
   ├── Aurora Entity/AI
   ├── Aurora Chunk/World
   ├── Aurora Network
   └── Aurora Storage
          │
          ▼
    Minecraft / NMS
          │
          ▼
 Compatibility adapters
          │
          ▼
 Paper/Folia/Canvas-derived internals
 where still intentionally retained
```

---

# 18. Concrete Package Direction

The final codebase should trend toward a structure similar to:

```text
dev.iyanz.sourbycraft
├── aurora
│   ├── runtime
│   ├── execution
│   ├── scheduler
│   ├── config
│   ├── entity
│   ├── ai
│   ├── chunk
│   ├── world
│   ├── network
│   ├── storage
│   ├── memory
│   ├── telemetry
│   └── diagnostics
├── compat
├── command
├── hud
├── spark
├── brand
├── bootstrap
└── update
```

This is directional, not a requirement to perform a massive package rename in one commit.

Migration should happen when code ownership changes for real.

---

# 19. Transition Safety Rules

The following are forbidden during transition:

- deleting upstream systems before replacement tests exist,
- bypassing owner-context mutation checks,
- unbounded executor queues,
- async mutation of live world state without ownership,
- automatic config rewrites,
- automatic gameplay degradation,
- hiding upstream licenses or attribution,
- fake benchmark claims,
- optimizing JFR percentages without checking absolute cost,
- changing persistence semantics for benchmark gains,
- mass package moves with no architectural value.

---

# 20. Rollback Strategy

Every transition phase must remain revertable.

For major runtime changes:

- keep the old compatibility implementation until the new path qualifies,
- use explicit feature flags only when needed for safe operator-controlled rollout,
- default experimental execution paths off until qualified,
- preserve existing data/config formats where possible,
- never require automatic migration to roll back.

Rollback must not require restoring world data from backup solely because an execution implementation changed.

---

# 21. Transition Checkpoints

## Checkpoint A — Identity complete

- canonical version identity,
- source docs aligned,
- no hard-coded build strings.

## Checkpoint B — Runtime complete

- Aurora lifecycle owns Sourby services,
- explicit startup/shutdown.

## Checkpoint C — Config complete

- typed Aurora config,
- upstream config bridge isolated.

## Checkpoint D — Execution complete

- CPU/I/O/owner-context work routed through Aurora execution contracts.

## Checkpoint E — Scheduler complete

- Sourby core no longer requires concrete Folia/Canvas scheduler classes.

## Checkpoint F — Engine domains complete

- entity/AI, chunk/world, network, storage have Aurora ownership boundaries.

## Checkpoint G — Observability complete

- one metrics source for commands, HUD, Spark.

## Checkpoint H — Upstream isolation complete

- dependency ledger complete,
- accidental coupling removed.

## Checkpoint I — Qualification complete

- benchmarks,
- soak,
- persistence,
- restart,
- offline cached boot.

## Checkpoint J — Cutover complete

- Aurora is the runtime authority,
- Folia/Canvas are implementation/compatibility inputs only.

---

# 22. Definition of Full Transition

The Aurora transition is considered fully complete only when all statements below are true.

1. SourbyCraft publicly and internally identifies its runtime as Aurora.
2. Aurora has a real runtime lifecycle.
3. Aurora owns configuration semantics for Sourby features.
4. Aurora owns the execution classification model.
5. Sourby-owned CPU work is not tied to Folia implementation classes.
6. Sourby-owned blocking I/O is not tied to region threads.
7. Sourby-owned scheduling uses Aurora contracts.
8. Folia/Canvas scheduling is an implementation adapter, not the core contract.
9. Aurora owns performance telemetry.
10. `/tps`, `/mspt`, `/ram`, `/perf`, HUD, and Spark use the same metrics source.
11. Aurora owns entity/AI optimization strategy.
12. Aurora owns chunk/world optimization strategy.
13. Aurora owns network optimization strategy.
14. Aurora owns storage optimization strategy.
15. Direct NMS patches are documented as intentional engine work.
16. Direct Canvas/Folia dependencies are inventoried and justified.
17. Accidental Canvas/Folia coupling is removed.
18. Build metadata is SourbyCraft/Aurora-first.
19. Core runtime can operate without Canvas remote services.
20. Cached runtime can boot offline.
21. Shutdown is deterministic.
22. No unbounded Aurora queue exists.
23. No automatic configuration tuning exists.
24. No automatic gameplay degradation exists.
25. Representative performance baselines exist.
26. JFR CPU/allocation/lock/I/O evidence is reviewed.
27. 2h+ soak passes.
28. Restart and persistence tests pass.
29. Heap/task/thread state recovers after load.
30. Plugin compatibility remains acceptable.
31. Upstream attribution and licenses remain intact.
32. README, PRD, architecture docs, and source documentation describe the same real architecture.

When all 32 conditions are true:

> Aurora is no longer a transition layer. Aurora is the SourbyCraft engine architecture.

---

# 23. Recommended Execution Order

Do not execute every stream at once.

Use this order:

```text
1. Identity cleanup
2. Aurora runtime lifecycle
3. Finish typed config ownership
4. Aurora execution contract
5. Move async pathfinding behind execution contract
6. Scheduler compatibility boundary
7. Real workload baseline + JFR
8. Entity/AI ownership
9. Chunk/World ownership
10. Network ownership
11. Storage ownership
12. Observability completion
13. Canvas/Folia dependency isolation
14. Build/bootstrap independence
15. Full soak + persistence qualification
16. Final cutover
```

This order minimizes the risk of rebuilding multiple subsystems on top of a runtime contract that is still changing.

---

# 24. Immediate Next Milestone

The next milestone should be **Aurora Transition M1 — Runtime & Execution Ownership**.

Scope:

- clean stale Canvas-era identity in active source,
- remove hard-coded Spark build number,
- introduce canonical Aurora runtime lifecycle,
- define Aurora execution task classes,
- define owner-context handoff contract,
- migrate `AsyncPathProcessor` behind Aurora execution,
- add queue depth and task-latency telemetry,
- add explicit stale-result validation tests,
- keep async pathfinding default-off until qualification,
- create the direct Canvas/Folia dependency ledger.

M1 is complete when SourbyCraft has its first real execution subsystem operating through Aurora-owned contracts while still using the existing region scheduler safely underneath.

That is the point where the transition changes from:

```text
Aurora documentation + Aurora features
```

into:

```text
Aurora runtime architecture
```

---

# 25. Final Principle

The goal is not to prove that SourbyCraft has removed every line inherited from Folia or Canvas.

The goal is stronger:

> SourbyCraft must be able to replace any inherited implementation without redesigning its product architecture.

A completed Aurora transition means:

```text
Upstream can change.
Aurora contracts remain.
SourbyCraft still runs as SourbyCraft.
```

That is the definition of real engine independence.
