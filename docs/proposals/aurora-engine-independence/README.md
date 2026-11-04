# Aurora Engine — Independent Runtime Program (Build 56–100)

**Status: ARCHITECTURE PROPOSAL / DOCS ONLY.** No new runtime, scheduler, chunk engine, network engine, configuration defaults, Paper pin, build number or measured performance improvement is implemented in this PR.

**Maintainer direction, 2026-11-03:** focus exclusively on **Aurora Engine independence, reliability, stability, performance and efficiency**. The Metal-specific modernization/provider-selection proposal [PR #69](https://github.com/YanIanZ/SourbyCraft/pull/69) was closed unmerged. This program does **not** plan a separate Moonrise/LeafPile/Spottedleaf version upgrade. Existing upstream libraries remain implementation dependencies as required. Do not remove `Metal/` code generation or other existing build inputs without a separate proven replacement.

## North star

Make Aurora **the owner of runtime semantics**: admission, scheduling contract, region ownership boundaries, chunk/world lifecycle, entity AI, plugin bridge, observability, storage/recovery, and shutdown. Preserve the supported Paper/Bukkit/Folia plugin contract and Minecraft protocol. Independence is not deleting upstream source: SourbyCraft must remain reproducibly buildable from pinned Paper sources and be able to evolve its internal engine through explicit Sourby-owned contracts and reviewable patches.

**Honesty rule:** Build 50 readiness remains unresolved in [current readiness](../../releases/BUILD-50-READINESS.md), and a source-level refactor is not an owner-qualified release.

## Read order

1. [RUNTIME-ARCHITECTURE.md](RUNTIME-ARCHITECTURE.md) — truth-based engine layering, authority, lifecycle and plugin boundaries.
2. [EXECUTION-AND-SAFETY.md](EXECUTION-AND-SAFETY.md) — region ownership, dispatch, backpressure, merge/split, safe async work.
3. [ENGINE-SUBSYSTEMS.md](ENGINE-SUBSYSTEMS.md) — chunk, entity, network, AWF, governor, startup, telemetry.
4. [PERFORMANCE-AND-RELIABILITY.md](PERFORMANCE-AND-RELIABILITY.md) — profiling-led optimization and invariant-based certification.
5. [ROADMAP-56-100.md](ROADMAP-56-100.md) — planned epic milestones and deployment ordering.
6. [IMPLEMENTATION-AND-ROLLBACK.md](IMPLEMENTATION-AND-ROLLBACK.md) — change boundaries, patch workflow, compatibility, observability, rollback.
7. [OWNER-ACCEPTANCE.md](OWNER-ACCEPTANCE.md) — owner-run test cases and qualification criteria.

## Current baseline (read source and current docs, never infer from this proposal)

| Current project surface | Status and source |
|---|---|
| Upstream | Paper-only `paperRef`, pinned in `gradle.properties`; SourbyPatcher and SourbyClip private build tools |
| Engine code | `dev.iyanz.aurora.engine` already contains source-owned engine elements and inherited region-threading implementation |
| Runtime states | `core/AuroraRuntime` is **lifecycle-state holder**, not a service orchestrator |
| Lifecycle orchestration | `core/SourbyCraftBootstrap` owns current ordered startup/shutdown |
| Region adapter | `execution/region/RegionBackend` and `FoliaRegionBackend`; `RegionOwnerHandoff` and `MoonriseRegionOwnership` cover existing ownership paths |
| Resource governor | `execution/ResourceGovernor` bounds BRIDGE_IO/STORAGE/PATHFINDING; no cross-pool dynamic CPU arbiter |
| Async pathfinding | Snapshot-only compute + generation/owner validation; still must qualify under owner tests |
| AWF | Source-owned world/store lifecycle; distributed semantics and recovery still have Build 50 open gates |
| Public API | Bukkit/Paper/Folia-compatible plugin surface remains contract; not targeted for gratuitous breakage |
| Metrics | `/perf`, Spark/JFR and [baseline harness](../../BASELINE.md) exist; results require exact candidate evidence |

## Release separation

- Build 56–99: candidates for targeted source-owned Aurora optimizations and deeper contracts; no implicit auto-release.
- Build 100: 26.3/Aurora Forge **planned milestone**, requiring Minecraft/Paper/toolchain upgrade work and owner sign-off. It is not automatically qualified by completing these docs.
- The separate [Aurora Identity and Territory proposal](../aurora-identity-territory-v3/README.md) concerns future features. This program focuses on **single-server engine independence and correctness first**, and does not pretend multi-JVM territory simulation ships within this roadmap.

## Mandatory invariants

1. Each mutable world/entity/region operation executes on its owning thread; plugin compatibility paths may dispatch, but may not mutate unsafely.
2. No caller on Netty, region, entity or global tick waits synchronously for disk/Redis/HTTP/SQL or an async task that could need that same owner.
3. Every async result has an owner/generation/identity validity check before mutation; stale results are discarded with telemetry.
4. Every save/unload reports the **confirmed** durable outcome; UNKNOWN is not SUCCESS.
5. All work queues are bounded, have explicit rejection/cancellation and follow shutdown ordering.
6. No internal optimization changes plugin-visible behavior silently; compatibility changes need separate versioned release notes.
7. No auto-tuning based solely on instantaneous load; existing owner decision forbids silently changing configured worker budgets.
8. Published release quality claims require owner tests at exact SHA plus reproducible benchmark and rollback evidence.

## Sources of truth

[Active architecture map](../../architecture/AURORA.md), [execution contract](../../architecture/execution-contract.md), [dependency ledger](../../architecture/dependency-ledger.md), [independence levels](../../architecture/independence.md), [Aurora roadmap M0–M28](../../AURORA-ROADMAP.md), [TODO](../../TODO.md) and [Build 50 readiness](../../releases/BUILD-50-READINESS.md). These take precedence for implemented status. Nothing here marks an open item complete.
