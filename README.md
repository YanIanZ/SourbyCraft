<p align="center">
  <img src="assets/SourbyCraft.png" alt="SourbyCraft" width="380">
</p>

<h1 align="center">SourbyCraft — 26.2 Aurora</h1>

<p align="center"><strong>Java 25 · region-threaded · SourbyCraft-owned runtime · deep Minecraft/NMS performance work · first-party observability</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-26.2-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/architecture-Aurora-8a2be2?style=flat-square">
  <img src="https://img.shields.io/badge/runtime-SourbyCraft-00bcd4?style=flat-square">
  <img src="https://img.shields.io/badge/build-46c%2B-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/mixins-Cherry-e83e8c?style=flat-square">
  <img src="https://img.shields.io/badge/license-PolyForm--NC--1.0.0-lightgrey?style=flat-square">
</p>

---

## What is SourbyCraft 26.2 Aurora?

**SourbyCraft 26.2 Aurora** is a Java 25, region-threaded Minecraft server project focused on **stable performance, efficient resource usage, deep engine optimization, observability, and progressive runtime independence**.

The current codebase still consumes Paper/Folia/Canvas-derived implementation where it is useful, but **Aurora is the SourbyCraft architecture** that defines the public runtime contract, configuration ownership, diagnostics, telemetry, lifecycle, and future engine direction.

Aurora is not just a rebrand of Canvas and it is not limited to helper patches outside Minecraft code. SourbyCraft may modify **Minecraft/NMS hot paths directly** when profiling proves that the optimization belongs there.

The architecture direction is:

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

Canvas remains an upstream implementation source where required. It is **not** the SourbyCraft product architecture.

Read the architecture contract: **[Aurora Architecture](docs/architecture/AURORA.md)**.

---

## Aurora goals

Aurora optimizes in this order: **correctness, reliability, stability, predictable latency, resource efficiency, scalability, observability, then peak throughput**. A faster result is not accepted as an engine improvement if it weakens ownership safety, persistence, shutdown behavior, compatibility, or overload behavior.

| Area | Goal |
|---|---|
| **Correctness** | Preserve Minecraft, plugin, ownership and persistence semantics before optimizing them |
| **Reliability** | Fail and degrade predictably; close admission during shutdown/failure; avoid hidden state corruption |
| **Stability** | Region-safe execution, clean shutdown, bounded workers/queues and stable long-running resource use |
| **Latency** | Optimize p95/p99/tail behavior as well as averages; avoid overload cliffs |
| **Efficiency** | Lower CPU, allocation, GC pressure, memory, I/O and hot-path overhead |
| **Scalability** | Add parallelism only where independent work and measured capacity justify it |
| **Independence** | SourbyCraft-owned runtime, configuration, diagnostics and build/release contract |
| **Observability** | One first-party source for TPS, MSPT, memory, GC, region and performance diagnostics |

Performance claims require evidence with the exact workload, hardware, configuration and certification state. Aurora does not silently reduce gameplay settings to produce better numbers.

---

## Current architecture status

The `26.2` branch already includes or is actively refining:

- Java 25 production baseline
- region-aware SourbyCraft metrics API
- immutable performance snapshots
- custom region tick metrics
- GC/runtime sampling
- `/tps`, `/mspt`, `/perf` and HUD modernization
- Spark statistics routed through SourbyCraft metrics
- SourbyCraft platform identity in Spark
- SourbyCraft configuration reporting in Spark
- bounded administrative I/O
- explicit Sourby runtime shutdown
- JFR and baseline tooling
- patch/threading architecture audits
- removal of unsafe cross-region scratch-buffer optimizations
- direct Minecraft/NMS performance patches where ownership is proven safe
- Aurora execution contracts, with the region backend confined to two adapter files
- the Aurora region system: region identity, topology and lifecycle, backend-free
- execution lanes with per-lane CPU attribution, surfaced by `/perf lanes`
- an Aurora engine package inside the Minecraft tree, `dev.iyanz.aurora`
- ownership documents for all four engine domains, with metrics and boundaries
- a Canvas/Folia dependency ledger, enforced by tests rather than prose
- reproducible builds via `SOURCE_DATE_EPOCH`
- persistence validation: `scripts/verify_persistence.py`, 16 checks

**Not yet fully qualified.** A certified two-hour 10-player stability soak now exists and showed stable heap-after-GC, resident memory and tick duration over the window. That is stability evidence; it is **not** a certified performance reference pair and does not by itself clear the regression gate. Certified comparable reference runs, remaining representative workloads, missing domain telemetry, and the open region-ownership issue still block T10. The exact gate state is tracked in **[Qualification Readiness](docs/architecture/qualification-readiness.md)** and raw measurement history in **[Performance Baseline](docs/BASELINE.md)**.

Deeper entity/chunk/network profiling and further Aurora ownership work remain active
development tasks.


### Status vocabulary

These labels are normative across README, architecture docs, release notes and commit messages:

| Label | Meaning |
|---|---|
| **IMPLEMENTED** | Code exists and functional verification has passed; no performance/stability claim is implied |
| **EXPERIMENTAL** | Implemented but intentionally not treated as production-qualified; normally default-off when risk is material |
| **MEASURED** | A workload produced an observation; the result may still be noisy or uncertified |
| **CERTIFIED** | The measurement harness accepted the run under its provenance/noise rules |
| **QUALIFIED** | All applicable correctness, regression, soak, persistence and compatibility gates are satisfied |
| **PLANNED** | Design/roadmap only; it must never be described as current runtime behavior |

A single workload, machine, percentile or profiler sample must never be generalized into a universal performance claim. When documentation and runtime disagree, inspect the current branch code/config resolution first and correct the document; do not preserve a stale statement for narrative consistency.

### Current Aurora status

| Area | Status |
|---|---|
| Runtime lifecycle / metrics / region adapters | **IMPLEMENTED** |
| Persistence validation | **IMPLEMENTED**; current checks pass |
| Async pathfinding | **EXPERIMENTAL**, default-off |
| Two-hour stability soak | **CERTIFIED evidence exists** |
| Certified performance regression reference pair | **INCOMPLETE** |
| Network throughput / storage backlog telemetry | **PARTIAL / INCOMPLETE** |
| Aurora Resource Governor / unified execution fabric | **PLANNED** |
| Fully independent Aurora scheduler | **RESEARCH / PLANNED**, not current runtime |

### Aurora engine, and where it lives

Aurora is the engine; SourbyCraft is what surrounds it. The console says so — a line from
`net.minecraft`, `io.papermc.paper`, `io.canvasmc` or `dev.iyanz.aurora` prints as **Aurora
Engine**, a line from `dev.iyanz.sourbycraft` prints as **SourbyCraft** — and the source tree
says so too: engine code SourbyCraft wrote lives in `dev.iyanz.aurora.*` inside the Minecraft
tree, rather than hiding in a vanilla package. See
[aurora-engine-package.md](docs/architecture/aurora-engine-package.md).

Startup reports the engine coming up stage by stage, as a bar whose percentage is stages
finished over stages declared — never elapsed time. A stage that fails is named, and the closing
line reads *degraded*, not *online*.

See **[DEVELOPMENT.md](DEVELOPMENT.md)** and **[Development Task Matrix](docs/DEVELOPMENT-TASKS.md)**.

---

## Deep Minecraft/NMS optimization

Aurora explicitly permits optimization inside Minecraft server code.

Primary profiling domains include:

```text
Entity / LivingEntity / Mob
GoalSelector / Brain / Sensor
PathNavigation / collision
ServerLevel
chunk holders / tickets
chunk generation / save / unload
block and fluid ticks
block entities
entity tracking
packet construction / serialization
network compression
```

A direct NMS optimization should have:

1. profiler or reproducible benchmark evidence,
2. region-ownership review,
3. compatibility analysis,
4. before/after measurement,
5. regression coverage where practical.

Aurora does **not** treat “more patches” as a performance metric.

---

## Configuration — SourbyCraft first

SourbyCraft is moving toward first-party configuration ownership.

Existing files remain supported:

- `sourbycraft_config/sourbycraft_global_config.toml` — SourbyCraft utility/global configuration
- `sourbycraft_config/aurora.toml` — early-boot Aurora CPU budget consumed before normal SourbyCraft bootstrap
- `sourbycraft-security.yml`
- `config/canvas-server.yml`
- `config/canvas-worlds.yml`

Configuration documentation must describe the **effective consumer and lifecycle**, not only the intended future layout. In particular, settings read before bootstrap must not be documented as live-reloadable.

New SourbyCraft-specific performance behavior should live under the **Aurora** configuration domain rather than adding new Canvas-owned keys.

Preferred logical layout:

```toml
[aurora.performance]

[aurora.scheduler]

[aurora.entity]

[aurora.chunk]

[aurora.network]

[aurora.memory]

[aurora.diagnostics]
```

If the configuration grows enough to justify file separation, Aurora may evolve toward:

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
    └── diagnostics.toml
```

This is an architectural direction, not permission to create unnecessary config files.

### No auto tuning

Aurora does not automatically rewrite operator performance configuration.

It will not silently:

- lower view distance,
- lower simulation distance,
- reduce entity limits,
- disable AI,
- change compression,
- change JVM flags,
- choose a different GC,
- rewrite gameplay settings because TPS/MSPT is bad.

Diagnostics may recommend actions. The operator remains in control.

---

## Observability and Spark

SourbyCraft performance consumers should converge on one metric source:

```text
engine / region counters
runtime sampler
GC events
network + scheduler counters
        ↓
Sourby Metrics Runtime
        ↓
immutable PerformanceSnapshot
        ↓
/tps /mspt /ram /perf / HUD / Spark
```

Spark currently remains the upstream profiler implementation, but SourbyCraft extends its integration so that profiler data can understand SourbyCraft runtime semantics instead of maintaining a conflicting TPS/MSPT calculation.

Current direction includes:

- SourbyCraft platform identity
- SourbyCraft configuration metadata
- Sourby tick/MSPT statistics
- region-aware performance context
- improved worker/thread classification
- Sourby runtime metadata without expensive duplicate scans

A dedicated deeper SourbySpark fork will only be justified if the adapter layer can no longer provide the required region/runtime visibility.

---

## Performance commands

The long-term operator surface is centered around SourbyCraft telemetry.

Current/active command family:

| Command | Purpose |
|---|---|
| `/tps` | SourbyCraft TPS overview |
| `/mspt` | tick-duration / MSPT overview |
| `/ram` | first-party memory information as implementation reaches feature-complete state |
| `/perf` | primary SourbyCraft performance overview |
| `/tpsbar` | TPS performance HUD |
| `/rambar` | RAM performance HUD |
| `/perfbar` | combined performance HUD |
| `/sys` | server/JVM/host diagnostics |
| `/ping [player]` | latency + offline GeoIP where enabled |
| `/ver` · `/version` | SourbyCraft build/runtime information |
| `/plugins` | Plugin list coloured by Aurora compatibility state (native / bridged / failed / disabled) |
| `/maxp [n]` | max-player management |
| `/perf lanes` | where the machine's time went, by execution lane |
| `/spec` | full machine specification: processor, clock, cores, memory and heap allocation |
| `/perf async` | async-path pool: solve times, and how often saturation put a solve back on a region thread |
| `/update` | SourbyCraft updater status/check |

Planned `/perf` depth includes:

```text
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

Only cheaply and reliably collected metrics should be exposed as routine commands.

---

## Independence

SourbyCraft independence does **not** mean deleting every upstream line.

It means SourbyCraft can define and operate its own:

- public runtime identity,
- configuration surface,
- performance semantics,
- diagnostics,
- lifecycle,
- release/build contract,
- Sourby-specific engine behavior.

A normal SourbyCraft server should not require:

- a separate Canvas JAR,
- a separately running Canvas process,
- a Canvas remote API,
- upstream network availability after required dependencies are already cached.

Read **[Independence Architecture](docs/architecture/independence.md)**.

---

## Cherry — server-side mixins

**Cherry** is SourbyCraft's unified server-side mixin engine. It combines server-side mixin/access transformation capabilities used by SourbyCraft and plugin authors.

Enable it with:

```text
-Dcherry.enable.mixin=true
```

Cherry is intended for server-side transformations and does not turn SourbyCraft into a full Fabric client/server mod loader.

Project and plugin-author documentation: **https://github.com/YanIanZ/Cherry**

---

## SourbyLoader / SourbyClip

SourbyCraft ships as a slim server artifact and resolves externalized libraries during bootstrap.

After successful dependency acquisition, cached operation should not depend on upstream network availability for normal runtime.

The bootstrap path is under active reliability review for timeout behavior, cache validation, bounded concurrency, retry policy and offline-after-success operation.

---

## Quick start

Requires **Java 25**.

```bash
java -Xmx4G -jar SourbyCraft-slim.jar --nogui
```

The first boot may require network access to acquire externalized dependencies. Normal cached operation should remain self-contained afterward.

---

## Maintainer build (private toolchain required)

Requires **JDK 25** and Git.

```bash
python3 scripts/private_toolchain.py --publish .private-toolchain
./gradlew applyAllPatches
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar
```

The active build currently materializes pinned upstream source inputs and applies SourbyCraft changes. Aurora's long-term build goal is reproducible SourbyCraft ownership with upstream inputs treated as replaceable implementation sources rather than runtime requirements.

Scheduler replacement is **not** on the near path, and the measurements are the reason. On this
hardware the region lane — the part Folia's architecture governs — accounts for roughly a third of
consumed CPU at ten players, and the machine sits mostly idle; chunk work dominates, and chunk work
is the same noise mathematics in any architecture. Aurora therefore owns its contracts first, so a
backend *can* be replaced, and defers replacing one until a workload measurement argues for it.

---

## Development documents

The active architecture/development set is:

- **[PRD.md](PRD.md)** — product/performance requirements
- **[SPEC.md](SPEC.md)** — technical implementation rules
- **[PLAN.md](PLAN.md)** — performance/observability roadmap
- **[DEVELOPMENT.md](DEVELOPMENT.md)** — unified continuation contract
- **[Aurora Architecture](docs/architecture/AURORA.md)** — deep engine/configuration architecture
- **[Aurora Independent Engine](docs/architecture/AURORA-INDEPENDENT-ENGINE.md)** — the phased roadmap beyond Folia constraints
- **[Execution Contract](docs/architecture/execution-contract.md)** — what SourbyCraft needs from whatever schedules it
- **[Aurora Engine Package](docs/architecture/aurora-engine-package.md)** — which tree engine code belongs in, and why
- **[Independence Architecture](docs/architecture/independence.md)** — upstream decoupling strategy
- **[Dependency Ledger](docs/architecture/dependency-ledger.md)** — every direct Canvas/Folia dependency, classified, enforced by test
- **[Compatibility Boundary](docs/architecture/compat-boundary.md)** — which way the upstream arrows point, and what keeps them pointing that way
- **[Entity & AI Engine](docs/architecture/engine-entity-ai.md)** — ownership, metrics, and why this domain is hard to measure honestly
- **[Chunk & World Engine](docs/architecture/engine-chunk-world.md)** — ownership and the one domain qualifiable without clients
- **[Network Engine](docs/architecture/engine-network.md)** — instrumented, unowned, and said so
- **[Storage Engine](docs/architecture/engine-storage.md)** — ownership, and the durability rule that outranks throughput
- **[Qualification Readiness](docs/architecture/qualification-readiness.md)** — T10 against what actually exists
- **[Performance Baseline](docs/BASELINE.md)** — how a run is measured, certified, and what the measurements found
- **[Development Task Matrix](docs/DEVELOPMENT-TASKS.md)** — actionable implementation state

### Measuring this server

Baselines are captured by `scripts/run_baseline.py`, which refuses to certify a run it cannot
trust — a dirty worktree, a moved HEAD, a shared machine, rejected setup commands, clients that
dropped out. Two rules are worth knowing before reading any number from it:

- **Seed a pre-generated world with `--world`.** Without it, the run builds terrain inside its own
  measurement window; on this project that accounted for half the measured CPU and most of the
  tail latency, and made a workload deliver six regions where it declared ten.
- **Measure on a quiet machine.** The foreign-CPU guard exists because a desktop running a game
  alongside the server silently doubles the numbers, and no accounting recovers that afterwards.
- **[Threading Review](docs/architecture/threading.md)** — region-safety findings
- **[Profiling](docs/PROFILING.md)** — JFR/profiling workflow

---

## Release direction

The active `26.2` branch is the continuation point for Aurora development.

Historical/release branches may still exist for older Folia/Canvas work, but new architecture work should treat `26.2` as the SourbyCraft-first line unless a release-specific branch is intentionally created.

---

## Credits & license

SourbyCraft uses and derives work from upstream Minecraft server projects including **[Paper](https://github.com/PaperMC/Paper)**, Folia-derived region-threading work and **[CanvasMC](https://github.com/CraftCanvasMC/Canvas)**. Upstream attribution, copyright notices and license obligations remain preserved.

Cherry incorporates work inspired by/derived from projects including **[LeavesMC](https://github.com/LeavesMC)** and **[CraftCanvasMC/Horizon](https://github.com/CraftCanvasMC/Horizon)** where applicable. Profiling integration uses **[spark](https://spark.lucko.me)**.

SourbyCraft project licensing is described in [`LICENSE`](LICENSE). Third-party components remain subject to their respective licenses.

## Maintainer build access

Official binaries are distributed through [Releases](https://github.com/YanIanZ/SourbyCraft/releases).
The current build requires private SourbyPatcher/SourbyClip checkouts published to Maven Local.
See [private toolchain setup](docs/development/PRIVATE-TOOLCHAIN.md) for pinned revisions, verification and CI policy.
