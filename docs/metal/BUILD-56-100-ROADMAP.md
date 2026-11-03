# Aurora Metal Program — Planned Build 56–100 Milestones

**Classification:** forward-looking dependency plan for SourbyCraft's next major evolution. **Not** an assertion that individual features have shipped, that build numbers are reserved, or that performance gains are known. Existing Build 50 still requires owner qualification; no retroactive relabeling. Planned Build 100 on 26.3 is separately tracked as Aurora Forge.

## Release philosophy

Each range has a **deliverable contract**, not a promised throughput delta. Promote a feature when correctness, classpath ABI, production-like profile and owner testing support it; otherwise it slips without forcing unsafe code into a numbered release. Leave experimental provider flags off by default until owner approval.

### B56–B60 — Metal Foundation / zero-regression integration

| ID | Deliverable | Definition of done |
|---|---|---|
| AMR-001 | Three provider candidates: legacy monolith, modular released LeafPile, source-owned vendored Metal | Default unchanged; canary builds and classpath resolution isolated |
| AMR-002 | Generated-source reproducibility | Java 25 generator, ignored outputs, two runs produce identical content and no partially written file |
| AMR-003 | SWMR equality/hash correctness | regression tests and source comparator audit |
| AMR-004 | ABI/dependency provenance | upstream tag/commit, class-origin inventory, no unintended duplicate classes, NOTICE/license |
| AMR-005 | Observability baseline | Spark/JFR profiles, p95/p99 MSPT, alloc/GC, chunk lifecycle/queue metrics before optimizations |
| AMR-006 | Protected rollback | owner can revert to last-qualified provider without world/profile data changes |

**Exit:** owner accepts reproducible safe baseline and chooses which provider, if any, is qualified. Do not turn on experimental EDF, territory mirrors or extra off-thread world access.

### B61–B70 — Hot-path containers and memory efficiency

| ID | Deliverable | Evidence gate |
|---|---|---|
| AMR-101 | Inventory of hot maps/queues by owner, contention and cardinality | no unowned data structure migrations |
| AMR-102 | Chunk holder/full-chunk map candidate replacement | correctness under load/unload, p99 + alloc non-regression |
| AMR-103 | Region task/queue allocation profiling | no new lost tasks, cancellation or starvation |
| AMR-104 | Entity tracking lookup/cached array candidates | client tab/entity consistency; lower measured allocation |
| AMR-105 | SWMR key/value and iterator semantics audit | actual single-writer enforced; no concurrent modifying iterator surprises |
| AMR-106 | Optimized memory pooling only for immutable/owned buffers | no use-after-release, zero silent corruption |

**Gate:** every optimization has a before/after exact SHA, same-workload benchmark, concurrency/region ownership proof and owner PASS. Do not replace globally if gains are isolated to a microbenchmark.

### B71–B80 — Scheduler adjacency and bounded queues

| ID | Deliverable | Evidence gate |
|---|---|---|
| AMR-201 | Time-budgeted queue/admission candidate integrated with Aurora Governor | bounded backlog, no tick-thread blocking |
| AMR-202 | Fair per-account async MultiProfile dispatch | preserves in-account order; removes global head-of-line where proven |
| AMR-203 | Chunk task cancellation and visibility review | no queue leak or stale task execution |
| AMR-204 | Scheduler backend experiment separate from Folia default | EDF remains parked until independently qualified and patch-safe |
| AMR-205 | Fine-grained contention/cancel/queue wait metrics | measurable low instrumentation overhead |

**Gate:** soak and failure injection demonstrate bounded behavior under overload, queue saturation and region merge/split. No promotion from "completes tests" alone.

### B81–B90 — AWF, storage and network efficiency

| ID | Deliverable | Evidence gate |
|---|---|---|
| AMR-301 | Immutable snapshot staging and data-layout audit | no durable state loss after node kill |
| AMR-302 | Bounded AWF storage I/O queue and retry semantics | no unbounded queue or duplicate commits |
| AMR-303 | Optimized chunk encode/IO work distribution | owner threads never block on I/O, p95 save latency visible |
| AMR-304 | Read-mostly cache with explicit version/invalidation | cache never authority, stale data rejected |
| AMR-305 | Zero-copy candidate in bounded immutable data path | prove object lifetime and no writable alias |
| AMR-306 | Advanced memory/GC and executor budgeting | improvements repeat under production-like loads |

**Gate:** all earlier AWF save/unload, snapshot commit/fencing, MultiProfile journal and Redis failure cases still pass. A fast but unsafe write path is rejected.

### B91–B99 — Territory-aware foundation and source ownership

| ID | Deliverable | Evidence gate |
|---|---|---|
| AMR-401 | Aurora internal facade over selected Metal utilities | stable internal API, no plugin ABI leakage |
| AMR-402 | World/territory-aware immutable mirror cache candidates | mirror never ticks or writes |
| AMR-403 | Placement/affinity telemetry with bounded samples | no dynamic relocation by unverified heuristics |
| AMR-404 | Cross-node queue/transport pressure propagation | isolate remote backpressure; no Netty/region blocking |
| AMR-405 | Source fork delta register and targeted upstream backports | reviewable provenance and minimal patches |
| AMR-406 | Provider upgrade/rollback rehearsal on exact builds | old and new class origins consistent; no content corruption |

**Gate:** territory placement and storage authority continue to follow their own separate RFC. Metal cannot be counted as ARF implementation or proof of distributed consistency.

### Build 100 — Aurora Forge consolidation candidate

- Review all Metal fork deltas and provider candidates against the latest **released** upstream at that time.
- Freeze and audit a reproducible provider version/classpath and roll back option.
- Preserve stable API/ABI for supported plugins; explicitly document any breaking changes in a migration guide.
- Record owner-run correctness, performance, 2+ hour soak where relevant, crash recovery, class-loading and migration evidence.
- Publish truthful per-feature status: STABLE/EXPERIMENTAL/DISABLED; no global "10/10 performance" guarantee.
- Build 100 release is **not automatically approved** because the calendar or roadmap reached this row.

## Cross-team ownership

| Area | Source of truth / ownership |
|---|---|
| Metal source + generator | `Metal/`, Gradle, pinned upstream ledger |
| Threading and region permission | Aurora execution contract / Folia region ownership |
| Scheduling/resource caps | Aurora Resource Governor |
| World persistence | AWF store and journal authority |
| Profiles | AuroraProfiles and adapters |
| Territory network | ARF RFC + Leviathan handoff contract |
| Player UX & staff | Atrium; never a direct Metal dependency |
| Qualification and performance claims | Owner acceptance on exact candidate SHA |

## Mandatory hold points

- A known concurrency correctness regression blocks provider promotion regardless of average TPS.
- Missing build/profiling evidence blocks performance language.
- No source rebase without preserving third-party license/credits.
- No direct `ca.spottedleaf.*` public API dependencies unless owner explicitly approves long-term ABI support.
- No deployment with overlapping duplicate packages from vendored and upstream providers.
- No world data, UUID or territory fencing policy change in a Metal-only optimization PR.

## Future epic naming

`AMR-0xx` integration/ABI; `AMR-1xx` containers; `AMR-2xx` queues/scheduling; `AMR-3xx` persistence/memory; `AMR-4xx` territory awareness; `AMR-5xx` release reliability. These are **new proposal IDs**, not replacements for `docs/TODO.md` or existing Build 50 `PM-xxx` items.
