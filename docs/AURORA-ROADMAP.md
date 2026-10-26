# Aurora Engine — Full Development Roadmap

> **Role:** milestone roadmap M0–M28. **Status:** Plan, not a schedule. Its milestones and release tracks are not scheduled 26.2 builds (see §12). The verified current state is the entry point's *Current state* table; §3 now points there. Milestone status lines M0–M4, M6, M18 and M19, the configuration-rewrite ticks and the async-path saturation contract were corrected against the code on 2026-10-25 (PR #62 tree, `build50/pm-completion`). Items that cite PR #62 are code written and statically reviewed; not built; not tested by the owner.
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md).

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

> **Replaced by a pointer (2026-10-25).** The verified current state of every Aurora pillar, with
> status words and file evidence, is [AURORA.md *Current state*](architecture/AURORA.md#current-state-verified-2026-10-07-re-checked-2026-10-25);
> priorities are in [docs/TODO.md](TODO.md). The lists below are the original snapshot, kept for
> history. Several of their "Not complete" items exist today, at least in part: lifecycle
> authority (`core/AuroraRuntime`, M3 below), the execution contract (`execution/OwnerHandoff`,
> `Admission`, the `execution/region` system, the Resource Governor), the compatibility layer
> (`bridge/AuroraBridge`, `config/upstream/UpstreamConfigBridge`, `execution/region/FoliaRegionBackend`),
> the storage engine (Aurora World Fabric, EXPERIMENTAL) and dependency isolation at source level
> (`UpstreamDependencyLedgerTest`, M19 below). The async-path snapshot audit
> (`architecture/async-path-snapshot-audit.md`, source audit only), Spark metadata
> (`spark/SourbyMetadataProvider`) and identity cleanup (M1) are done at source level; none of them
> is a qualification. Two ticks below are also stale: "no automatic config
> rewrite" (see M2) and "read-only legacy fallback" (PR #62 stops reading the pre-49 names; not in any published build;
> the one-time boot rewrite stays).

## Implemented / usable

- [x] Java 25 baseline
- [x] Aurora product/architecture definition
- [x] Folia-independent architecture direction
- [x] typed `AuroraConfig`
- [x] immutable config snapshots
- [x] `aurora.entity.async-pathfinding`
- [x] read-only legacy fallback — superseded: since PR #62 (not in any published build) the old name `perf.ai.async-pathfinding` and the pre-49 utility names are not read (one WARN each); the one-time boot rewrite renames them
- [x] no automatic config rewrite — superseded, see M2 "Existing": a reload never rewrites; Build 49 boot migrations rewrite once
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

**Status:** [x] Complete enough to build on (2026-10-25: every task below is ticked and each names
its evidence; the named deliverable file was never created, see *Deliverable*)

### Goal

Create a reliable baseline of the current 26.2 engine before changing runtime ownership.

### Tasks

- [x] capture current branch head — per release in `releases/26.2-build-47-aurora-nexus.md` (CI run/commit per gate)
- [x] record Java/JVM version — Java 25 (Temurin) in AGENTS.md, CI and docker/Dockerfile
- [x] record upstream revision — Paper only, pinned by `paperRef` in `gradle.properties`; private tools pinned separately in `build-data/private-toolchain.lock.json`; former Canvas/Folia baseline owned locally since 2026-10-05; `architecture/rebase-log.md`
- [x] record build number — `sourbyBuild` in `gradle.properties`; verified by `scripts/verify_build_identity.py` in CI
- [x] record patch count — the patch directories themselves are the current count (`scripts/patch_surface.py` reports the surface; on 2026-10-25: 87 Minecraft feature patches, 29 Paper server feature patches, 3 Paper API feature patches); `architecture/dependency-ledger.md` §2 classifies them (its §2.1 is a historical inventory with current additions below it; §2.3 was re-counted to the 29 Paper patches on 2026-10-25)
- [x] record direct Canvas references — `dependency-ledger.md` §1, enforced by `UpstreamDependencyLedgerTest`
- [x] record direct Folia references — same ledger and test
- [x] record Sourby-owned executors — `architecture/executor-inventory.md`
- [x] record active config surfaces — AGENTS.md "Configuration surfaces", `AURORA-CONFIG.md`
- [x] record current startup/shutdown lifecycle — `core/AuroraRuntime` states, `SourbyCraftBootstrap` stage list
- [x] record current Spark integration points — `SPARK.md`
- [x] record performance command outputs — the `/perf` table moved from README to [guides/commands.md](guides/commands.md) (README rewrite `c86b3be`, 2026-10-22)

### Deliverable

Planned as `docs/architecture/AURORA-BASELINE-LEDGER.md`; that file was never created. The
baseline is instead the set of documents each task above names: `releases/` (per-build head and
CI run), `gradle.properties` and `build-data/private-toolchain.lock.json` (revisions),
`architecture/dependency-ledger.md` (upstream references, test-enforced),
`architecture/executor-inventory.md`, `AURORA-CONFIG.md`, `core/AuroraRuntime` and
`core/SourbyCraftBootstrap` (lifecycle), `SPARK.md` and `guides/commands.md`.

### Gate

No large runtime refactor starts before this inventory exists.

---

## M1 — Identity Cleanup

**Status:** [x] Complete enough to build on (2026-10-25: all 13 tasks verified in source; the crash
report from a booted jar is still unchecked, see that task)

### Goal

Make source, build metadata, runtime identity, and documentation describe the actual Aurora architecture.

### Tasks

- [x] replace stale "utility layer" wording — last occurrences removed 2026-09-27
- [x] replace stale "Canvas re-platform" wording — last occurrence removed 2026-09-27
- [x] fix root `package-info.java`
- [x] fix `SourbyCraftBootstrap` architecture comments
- [x] remove `SourbyCraft-on-Canvas` build terminology — removed from `build.gradle.kts.patch` 2026-09-27
- [x] create canonical `BuildInfo` — `brand/BuildInfo`
- [x] remove hard-coded Spark `Build44` — now owned source `dev/iyanz/aurora/engine/spark/FoliaPlatformInfo.java` (`getVersion()` reads `BuildInfo`; it was a `.java.patch` before the engine moved into owned source)
- [x] feed build number from canonical metadata — `writeBuildInfo` + manifest, verified in CI
- [x] make startup banner use BuildInfo — the Build 49 console removed `StartupBanner`/`SourbyCraftBanner`; the launch line and the ready summary (`brand/LaunchLines`, `brand/ReadySummary`) render `BuildInfo#releaseIdentity` ([AURORA-UX.md](AURORA-UX.md))
- [x] make `/version` use BuildInfo — `VerCommand`
- [x] make Spark use BuildInfo — `aurora/engine/spark/FoliaPlatformInfo`, `SourbyMetadataProvider`
- [x] make crash reports use BuildInfo — Paper patch 0015 adds a `SourbyCraft:` build line first in the crash report system details from `brand/CrashReportIdentity` (`CrashReportIdentityTest` 3, includes a real `CraftCrashReport` without a server); the upstream `BrandInfo` line still reads the jar manifest and shows `26.2-DEV-...` when CI sets no `BUILD_NUMBER`; a crash-report file from a booted jar is not checked (needs a server)
- [x] expose upstream revisions only in developer/debug details — `/ver` shows no upstream revision

### Target output

```text
SourbyCraft Build 50 — Aurora Nexus for Minecraft 26.2
Java 25
```

(Current format, `BuildInfo#releaseIdentity`: `Build N — Aurora Nexus` for a release, `Build N —
Aurora Nexus (DEV, <branch>@<commit>)` for a development build, `Build N@<commit>PR!` for the REL
build of a `release=pre` prerelease. The earlier target read "Build 45".)

### Gate

No active runtime code hard-codes release/build identity.

---

## M2 — Aurora Configuration Expansion

**Status:** [-] Implemented / partial (2026-10-25: every section that has a consumer exists in
`sourbycraft_config/aurora.yml`; the upstream bridge is done for the reload path; the key contract
has one gap, below)

### Goal

Finish first-party configuration ownership without automatic tuning.

### Existing

- [x] `AuroraConfig`
- [x] `aurora.entity.async-pathfinding`
- [x] LIVE lifecycle
- [x] legacy fallback — superseded by PR #62 (not in any published build): old names are no longer read (one WARN each); the one-time boot rewrite to the current names stays
- [x] no automatic writeback — corrected 2026-10-25: `/sourbycraft reload` never rewrites a SourbyCraft file (it rewrites an engine file, `config/aurora-server.yml`/`aurora-worlds.yml`, only to add or remove a key, logged), but since Build 49 boot rewrites files once: `config/ConfigFormatMigration` (`*.toml` → `*.yml`), `config/ConfigMigration` (pre-49 layout → Build 49 layout) and `aurora/engine/configuration/EngineConfigFiles` (`canvas-*.yml` → `aurora-*.yml`), each keeping the old file as `*.migrated` and leaving values unchanged (owner decision for Build 49, superseding the brief's "never save migrated values")

### Add only when real consumers exist

Sections that exist (checked against `config/AuroraKeys.java` on 2026-10-25):

- [ ] `aurora.performance` — no consumer yet
- [x] `aurora.scheduler` — Resource Governor budgets
- [ ] `aurora.ai` — no consumer yet (the async-path switch is `aurora.entity.async-pathfinding`)
- [ ] `aurora.chunk` — no consumer yet (chunk-generation timing is `aurora.diagnostics.chunk-generation-metrics`)
- [x] `aurora.world` — `command-blocks`, `tick-functions` (both LIVE)
- [x] `aurora.network` — `counters` (LIVE)
- [ ] `aurora.memory` — no consumer yet
- [-] `aurora.storage` — AWF lives under `aurora.awf` instead (with `aurora.awf.multiprofile` and `aurora.awf.redis.fabric`)
- [x] `aurora.diagnostics` — `lane-sampling` (LIVE), `chunk-generation-metrics`, `stall-recorder.*` (RESTART_REQUIRED)
- [x] `aurora.entity` — `async-pathfinding` (LIVE)
- [x] `aurora.cpu` — `cores` (RESTART_REQUIRED, read before the configuration loads)
- [x] `aurora.bridge` — Aurora Compatibility Bridge (`mode`, `thread-checks`, routing and limits)
- [x] `aurora.abysslink` — optional control client (RESTART_REQUIRED, off by default)

### Configuration contract

Every key must declare:

- type
- default
- validation
- lifecycle
- consumer
- fallback
- compatibility behavior

Contract gap (2026-10-25): `config/ConfigKey` declares path, default, lifecycle, the comment and
legacy paths. Type and validation live in the parser that reads the key (`AuroraConfig.parse`,
`AwfSettings`, ...); the consumer is not a declared field, it is named in the key's comment and in
[AURORA-CONFIG.md](AURORA-CONFIG.md) only.

### Upstream bridge

**Status:** [x] done for the reload path. Replace direct config calls:

```text
SourbyCraftConfig
    ↓
dev.iyanz.aurora.engine.*
```

with:

```text
AuroraConfigService
    ↓
UpstreamConfigBridge
    ↓
engine baseline (former Canvas) / Paper implementation
```

What exists: `config/upstream/UpstreamConfigBridge` (interface) implemented by
`config/upstream/CanvasConfigBridge`, which re-reads the engine files (`config/aurora-*.yml`)
during `/sourbycraft reload`; `SourbyCraftConfig` holds only the interface type
(`SourbyCraftConfig#ENGINE_CONFIG`). There is no separate `AuroraConfigService` class;
`SourbyCraftConfig` plays that role.

### Gate

Aurora core no longer directly depends on the engine's configuration implementation classes. —
Met for the reload path (above); the independence policy records the remaining engine coupling.

---

# 6. Stage B — Runtime & Execution Ownership

## M3 — Aurora Runtime Lifecycle

**Status:** [-] Implemented / partial (2026-10-25). `core/AuroraRuntime` holds the states below
plus `DEGRADED`, with an explicit transition table and idempotent transitions;
`core/SourbyCraftBootstrap` publishes them, runs the 19 boot stages in order and stops services in
an explicit order in `close()` (MC feature patch 0081, after plugin disable). What keeps it partial
is listed under *Rules*.

### Goal

Create one authoritative Sourby runtime lifecycle.

### Required lifecycle

```text
NEW
BOOTSTRAPPING
STARTING
RUNNING
DEGRADED   (added: some boot stages failed, the server is up)
STOPPING
STOPPED
FAILED
```

### Runtime-owned services

- [x] configuration
- [-] execution — governed lanes are shut down by the runtime; region execution is the base's
- [x] metrics
- [x] GC telemetry
- [x] HUD
- [x] diagnostics
- [-] Spark bridge — Spark is Paper's; SourbyCraft supplies providers, not lifecycle
- [x] updater
- [x] I/O executor
- [x] CPU workers
- [-] scheduler bridge — Aurora Bridge is started lazily by the plugin loader, outside the stage list

### Rules

- no new task admission after STOPPING — partly enforced: only the admission points that consult
  `AuroraRuntime.stopping()` refuse (async-path submission, `/awf`, `/speedtest`, `/update` check
  and apply, `/sourbycraft reload`, the GeoIP download; PR #62 added all but the first); other
  lanes rely on their own shutdown, and STOPPING is published only after plugins are disabled.
  Drain work is admitted during STOPPING by design: the storage lane, AWF commits, region saves
  and the `VirtualExecutor` work AWF world operations use. `AuroraRuntime.acceptingWork()` is a
  status query with no admission consumer.
- shutdown must be idempotent — `close()` logs and continues past each failing step
- services stop in reverse dependency order — explicit order in `SourbyCraftBootstrap#close()`
- executor ownership must be explicit — see [executor-inventory.md](architecture/executor-inventory.md) §1 (several executors are outside the Resource Governor)
- startup failures must be isolated — a failed stage is logged and stepped past (DEGRADED)

### Gate

All Sourby-owned long-lived services have explicit lifecycle ownership.

---

## M4 — Aurora Execution Model

**Status:** [-] Implemented / partial (2026-10-25). The contracts exist under other names: the
Resource Governor's governed lanes (`execution/ResourceGovernor`, `GovernedLane`: bounded CPU and
blocking-I/O work that rejects instead of running on the caller), owner handoff
(`execution/OwnerHandoff`, `Admission`, adapter `RegionOwnerHandoff`), the region system
(`execution/region`) and stale-result rejection (`perf/AsyncPathValidity`). There is no
`AuroraExecution` class and no work-class enum; `execution/ExecutionLane` classifies threads for
CPU attribution only. The gate below names a class that does not exist; read as "runs on a
governed lane", it is met only by default-off subsystems (async pathfinding, M5; AWF commits;
async tasks of bridged plugins), none of them qualified. Contract reference: [execution-contract.md](architecture/execution-contract.md).

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

- [x] migrate pool behind AuroraExecution — 2026-10-20: the pool is `ResourceGovernor.Lane.PATHFINDING` (`GovernedLane`; the Resource Governor is the only Aurora execution fabric in code, there is no `AuroraExecution` class); threads, priority, queue 1024 reject, names and default-off unchanged; `ResourceGovernorTest` 10, `LiveIncidentSourcesTest` 1, `UnboundedQueueAuditTest` 3
- [x] add explicit owner-context callback — `execution/OwnerHandoff`, `RegionOwnerHandoff`, `perf/AsyncPathCompletion`, used by MC patch 0006; `AsyncPathCompletionTest` 8
- [x] add path freshness token — `AsyncPathValidity.stillRequested`, plus the navigation generation (`AsyncPathValidity.sameGeneration`, MC patch 0047) for a `stop()` that found the path already null
- [x] validate entity/navigation version — `AsyncPathValidity.sameSubject`
- [x] reject stale paths — `AsyncPathValidityTest`
- [x] test entity removal during solve — `AsyncPathCancellationTest` 17 over `AsyncPathNavigationModel` (model level; the patch lambda and a real `Mob` are not run)
- [x] test world unload during solve — `AsyncPathCancellationTest` (model level, as above)
- [x] test target movement during solve — `AsyncPathCancellationTest` (model level: a retarget that replaces the path makes the result stale, also when the new route has the same nodes, and a sync recompute during a solve; the patch lambda and a real `Mob` are not run)
- [x] test queue saturation — `AsyncPathQueueTelemetryTest`
- [x] test shutdown cancellation — `AsyncPathShutdownTest`
- [ ] benchmark sync vs async
- [ ] mob behavior compatibility soak
- [x] expose queue depth — `/perf async`
- [x] expose wait latency — `/perf async`
- [x] expose solve latency — `/perf async`

### Gate

Async pathfinding can be enabled without direct dependency on a concrete Folia/Canvas scheduler class in Sourby-owned code.

---

## M6 — Aurora Scheduler Contract

**Status:** Superseded (2026-10-25). The `AuroraScheduler` / `FoliaCanvasSchedulerAdapter` pair of
Phase 1 was deliberately not built and does not exist. Instead: owned services use the published
`io.papermc.paper.threadedregions.scheduler` API directly (`bridge/AuroraBridge`, `hud/HudBars`,
`update/SourbyUpdater`), owner handoff goes through `execution/OwnerHandoff`, and scheduler
internals are confined by `UpstreamDependencyLedgerTest` and the independence policy (seven
pinned files; [AURORA.md](architecture/AURORA.md) *Current state*). The current statement is
[execution-contract.md](architecture/execution-contract.md). (The "no AuroraScheduler wrapper"
rationale was in AURORA-FULL-TRANSITION.md T4, which left the repository in `c86b3be`, 2026-10-22.)
The capability list below stays as the requirement any future contract must meet.

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

- [x] scheduler queue depth — `/perf governor` (Sourby lanes); per-region queued task counts (tick/chunk) in `/perf scheduler` since MC patch 0045 (2026-10-20); the scheduler run queue, the global task queue and the world-wide global chunk-task queue are still `n/a`
- [x] task wait latency — recorded since boot per kind (tick/chunk) by MC patch 0049 + `perf/RegionTaskWaitLatency` (count/mean/max, p50/p99 as bucket upper bounds; `RegionTaskWaitLatencyTest` 8, `RegionTaskWaitLatencyHookTest` 4, 2026-10-20) and shown in `/perf scheduler` (`command/PerfTelemetryRows.taskWaitLines`, `PerfTelemetryRowsTest` 7); no server numbers, recording overhead not benchmarked (needs a server)
- [x] async-path queue/solve latency — `/perf async`; see below
- [x] chunk lifecycle counters — shown in `/perf chunks` since 2026-10-20 (`PerfTelemetryRows`); recorded since boot, all worlds, one adder per transition (holder create/delete, FULL in/out, ticking in/out, entity-ticking in/out) by MC patch 0051 + `perf/ChunkLifecycleCounters` (`ChunkLifecycleCountersTest` 2, `ChunkLifecycleHookTest` 3, 2026-10-20), no per-world split, no server numbers, overhead not benchmarked
- [x] entity tick counters — MC patch 0052 + `perf/EntityTickCounters` (per world plus the largest single-region loop; passengers/frozen entities not counted; `EntityTickCountersTest`, `EntityTickHookTest`), shown in `/perf entities` (2026-10-20); no per-region split (regions have no stable key), no server numbers, overhead not benchmarked
- [x] packet counters — `/perf network`
- [x] bytes/sec — `/perf network`
- [x] storage backlog — `/perf storage` (Moonrise pending I/O per world), `/perf awf`
- [x] save latency — AWF commit p50/p95/p99, and region-file save p50/p99/max over 60 s per world/type from MC patch 0043 (`perf/RegionFileSaveLatency`, `/perf storage`; times only the `.mca` write stage, never AWF worlds); no server numbers and overhead not benchmarked
- [x] worker utilization — `/perf lanes` (CPU by execution lane)
- [x] incident history — `/perf incidents` (`perf/IncidentLog`, ring of 20, merged per kind+subject, 9 signals; `IncidentLogTest`); watchdog and GC stop-the-world pauses are not sourced. Since PR #62 the async-path signal reads refused plus declined (label "async path pool refused or declined work", unit refused/declined), so real saturation, which shows mostly as declined, raises it

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

### Async-path telemetry

> Rewritten 2026-10-25 for the current saturation contract. This section used to describe a
> caller-runs rejection handler and an `inline` count as the deciding line; that policy was
> replaced (the pool is the governed `PATHFINDING` lane with `AbortPolicy`), and PR #62 added the
> `declined`, applied/discarded and retired rows.

`/perf async` reports solves admitted and outstanding, mean and slowest solve time, mean and
slowest queue wait, queue depth / active workers / pool size, and then the lines that decide
whether the feature is worth having:

- **Declined, pool full / stopping (ran sync)** — the navigation asks the pool for capacity
  before it fires the pathfind event and builds the snapshot; a full queue or a stopping pool
  declines there. This is where saturation shows.
- **Refused at submit (ran sync)** — the rarer submission the lane still rejected after that
  check (another region filled the queue in between, or shutdown raced it).
- **Results applied**, **Results discarded (entity / path / generation / no path)** and
  **Results retired before delivery** — what became of finished solves on the owner, and those
  that never reached it (the mob was removed or unloaded first, or the handoff was refused).
- **Legacy caller-run solves** — kept for older telemetry; it should stay zero on this build.
- the busiest mob types (admitted / solved / refused / declined).

Neither a decline nor a refusal drops the recompute: the mob's own region thread runs the vanilla
synchronous recompute for that slot, the same cost as with the flag off. So a rising declined (or
refused) count means the pool is at capacity for this workload and the region thread is doing that
A* itself. A refused slot also paid for the snapshot and the pathfind event first; a declined one
paid only for the capacity check. It is measured rather than inferred, so an operator deciding
whether to keep the feature on has the number the decision turns on. The rows are PR #62 code:
written and statically reviewed; not built; not tested by the owner.

Mean solve time is NaN until something has solved, not zero: "0.00ms mean" reads as an
impossibly fast pool rather than an idle one.

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

**Status:** [-] Partial (2026-10-25). Adapters exist, though not in one `compat/` tree:
`config/upstream/UpstreamConfigBridge` (engine configuration reload), `execution/region/FoliaRegionBackend`
and `execution/RegionOwnerHandoff` (region scheduler), `compat/luminol` (the relaxed-thread-checks
world-data pool) and the Aurora Compatibility Bridge for legacy plugins (`bridge/AuroraBridge`,
default off). Spark integration is the owned `aurora/engine/spark` package. The list of files
allowed to name scheduler internals is pinned (M19, `scripts/test_independence_policy.py`).

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

**Status:** [x] for runtime source (2026-10-25). [architecture/dependency-ledger.md](architecture/dependency-ledger.md)
uses exactly the six classifications below, and `UpstreamDependencyLedgerTest` fails when a file of
`dev.iyanz.sourbycraft` (in `sourbycraft-server/src/main/java`) reaches upstream internals
(`dev.iyanz.aurora.engine.*`, `io.papermc.paper.threadedregions.*` outside the public `scheduler`
package, `ca.spottedleaf.*`, `io.canvasmc.*`) without being listed. The ledger's patch sections
and header were re-counted on 2026-10-25 (§2.3: 29 Paper patches; the header marks
AURORA-FULL-TRANSITION.md as no longer in the repository). "Legacy sourbypatcher" in the
inventory below is not legacy: SourbyPatcher is the required private patch applier (`docs/development/PRIVATE-TOOLCHAIN.md`).

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

- `dev.iyanz.aurora.engine.*`
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

> **Not scheduled (note 2026-10-25).** The tracks below group milestones by theme; they are not
> tied to 26.2 builds. The owner's build plan ([releases/ROADMAP-47-50.md](releases/ROADMAP-47-50.md),
> 2026-10-21: 47 ship, 48 refinement, 49 full stable, 50 optimization) allocates none of M3, M4, M6
> or M9–M28 to a 26.2 build and lists the end-state subsystems under "Later / not planned". 26.2
> stays below Build 100; Minecraft 26.3 starts at Build 100, codename "Aurora Forge" (owner
> decision 2026-10-24, `gradle.properties`, `scripts/release_version.py`). For each milestone's
> real state read its *Status* line (M0–M4, M6, M18 and M19 corrected 2026-10-25) and the
> [AURORA.md](architecture/AURORA.md) *Current state* table.

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
