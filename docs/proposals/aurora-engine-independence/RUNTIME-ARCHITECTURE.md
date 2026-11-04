# Aurora Runtime Architecture — Source-Owned Engine Contracts

**Role:** future architecture. Existing code has some contracts and services already; this is not a claim that the full diagram exists.

## Runtime composition

~~~mermaid
flowchart TB
    ENTRY["SourbyCraft Bootstrap"]
    LIFE["AuroraRuntime state + Lifecycle Coordinator (proposed)"]
    CONFIG["Versioned immutable Aurora configuration"]
    EXEC["Aurora Execution Contracts"]
    REG["Region ownership / handoff"]
    CHUNK["Chunk & World Lifecycle Facade"]
    ENT["Entity / AI Services"]
    NET["Network & Packet Boundary"]
    GOV["Resource Governor"]
    STORAGE["Aurora World Fabric"]
    PROF["MultiProfile (separate authority)"]
    PERF["Observability / Spark / JFR"]
    COMPAT["Paper/Bukkit/Folia Plugin API"]

    ENTRY --> LIFE
    LIFE --> CONFIG
    LIFE --> EXEC
    LIFE --> GOV
    EXEC --> REG
    EXEC --> CHUNK
    EXEC --> ENT
    EXEC --> NET
    LIFE --> STORAGE
    LIFE --> PROF
    GOV --> EXEC
    EXEC --> PERF
    CHUNK --> PERF
    STORAGE --> PERF
    COMPAT --> REG
    COMPAT --> NET
~~~

The runtime foundation may still call Paper/Minecraft internals through **narrow, approved adapters**. A genuine independent Aurora engine has internal service ownership, controlled upgrade points and testable boundaries; it does not require rewriting NMS or the public plugin API wholesale.

## What already exists vs what must be built

| Layer | Existing concrete source | Further work |
|---|---|---|
| Boot/lifecycle | `core/SourbyCraftBootstrap`, `core/AuroraRuntime` | explicit service dependencies, stop budget, DEGRADED meaning, idempotent partial-start cleanup; do **not** invent `AuroraRuntime.start()` wrapper |
| Configuration | `config/AuroraConfig`, immutable snapshots, `aurora.yml` | validate transition semantics and every LIVE/RESTART_REQUIRED key against code |
| Execution | `execution/OwnerHandoff`, `Admission`, `execution/region` interfaces | audited region owner dispatch, lifecycle-aware cancellation and trace context |
| Resource governance | `execution/ResourceGovernor`, three governed lanes | queue fairness and per-subsystem admission evidence; keep no dynamic worker resize unless owner explicitly changes policy |
| Regionizer | current source-owned Folia-derived engine | move only concrete coupling behind existing adapters; maintain merge/split correctness |
| Chunk | Moonrise-derived chunk pipeline and `awf/RegionFileStorageHooks` | optimize measured bottlenecks; no blind “replace Moonrise” program |
| Entity/AI | async path snapshot compute and validation | guard stale results, bounded parallelism and cancellation; verify with mobs/navigation under load |
| Network | Netty/Paper event path and Aurora metrics | optimize measured packet allocation and source-owned observability without protocol break |
| Storage | AWF and current persistence hooks | confirmed commits, durable unload, bounded I/O and recovery first |
| Plugin bridge | `bridge/AuroraBridge` and Folia public schedulers | enforce capability and tick-thread rules; no “relaxed is safe” claim |
| Diagnostics | `/perf`, Spark/JFR, `scripts/run_baseline.py` | target SLOs grounded in workload; low overhead and incident evidence |

## Three paths to implement a change

**A — Engine-level Minecraft/NMS patch:** only for a profiled local hot path where a narrow source patch is smallest and preserves vanilla/Paper semantics. Source of truth is the tracked feature patch, not generated `paper-server` copies.

**B — Aurora-owned service:** when change needs lifecycle, queues, durable state, configuration, shared policy or cross-feature coordination. Expose typed internal API and immutable views.

**C — Compatibility adapter:** when SourbyCraft-owned code must read a specific Paper/Folia/Moonrise internal; use approved files from [dependency ledger](../../architecture/dependency-ledger.md). Changes should reduce proven coupling, not introduce an abstraction for appearance.

## Lifecycle target

`NEW → BOOTSTRAPPING → STARTING → RUNNING`; on missing optional capabilities -> `DEGRADED` with explicit disabled features; on irrecoverable world/storage authority -> safe halt; graceful `STOPPING → STOPPED`; failure -> `FAILED` with cleanup guarantee.

A proposed lifecycle coordinator can declare ordered start and reverse stop dependencies. It must never report RUNNING while required world/state prerequisites are unready. Worker termination uses bounded budgets and an error summary; no indefinite tick-thread join. A partial boot should still close all already-started services.

## Ownership semantics

Region, entity and world global state have distinct owner schedulers. Global service registry records lifecycle handles but does not grant arbitrary thread access to Minecraft internals. “Async” means work safe to execute away from the owner, usually **immutable snapshots or I/O**; any mutation returns to a verified owning thread.

## API stability and de-risking

- Public `org.bukkit.*` and `io.papermc.paper.threadedregions.scheduler.*` contracts remain the supported compatibility surface; they are not undesirable “coupling”.
- SourbyCraft-owned internal APIs are versioned only when code exists; the example layers above are **not classes currently present**.
- Prefer a feature flag with explicit default-off for risky optimizations. No mechanism may silently repair corrupted snapshots by creating an empty world.
- Preserve Java 25 and private toolchain reproducibility. Paper revision bump is an independent large change, not a prerequisite for incremental Aurora source ownership.

## Major architectural decisions

**AD-01** Own lifecycle and deterministic operation outcomes.  
**AD-02** Preserve Bukkit/Paper/Folia public API; reduce coupling only to **internals**.  
**AD-03** Reuse existing execution/region contracts; do not restart abstraction migration from scratch.  
**AD-04** Never replace region threading with a global executor or unchecked cross-region access.  
**AD-05** Optimize real JFR/Spark hotspot evidence before replacing algorithms.  
**AD-06** Treat AWF durability and plugin player-data safety as release blockers before chasing performance.  
**AD-07** Document actual implementation status and evidence on each code PR.
