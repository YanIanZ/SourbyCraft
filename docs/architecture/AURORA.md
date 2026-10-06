# Aurora Architecture

## SourbyCraft 26.2 Deep Engine Architecture

**Codename:** Aurora  
**Branch:** `26.2`  
**Java:** 25  
**Status:** Active architecture direction  
**Primary objective:** Make SourbyCraft independently own its runtime behavior, configuration, performance model, and Minecraft engine optimizations while preserving required upstream compatibility and attribution.

---

> **This is the entry point for every Aurora document.** The four sections below (document map,
> verified current state, open contradictions, deepening candidates) were added on 2026-10-07.
> Sections 1–23 further down are the architecture principles and are unchanged. Where any Aurora
> document disagrees with the code, the code wins (DEVELOPMENT.md, truthfulness policy §1) and the
> disagreement is listed under *Open contradictions* until its source is corrected.

## Document map

Roles: **Active** — describes the current branch and is kept in sync with code. **Plan** — states
intended direction; its checkboxes and "status" paragraphs are snapshots, not the current state.
**Proposal** — design argument; not a statement of what exists. **Historical** — a dated record;
read for evidence and reasoning, not for current numbers.

| Document | Role | Status | Read it for | Overlaps / superseded parts |
|---|---|---|---|---|
| `docs/architecture/AURORA.md` (this file) | Entry point; architecture principles (§1–23) | Active | Where to start; what exists now; the rules every Aurora change follows | §2 diagram and §3 file layout predate the 2026-10-05 migration and `aurora.toml` (see contradictions) |
| `docs/AURORA-CONFIG.md` | Effective configuration contract | Active | Every implemented `aurora.*` key: type, default, LIVE/RESTART_REQUIRED, consumer; boot order of `aurora.toml` | Checked against `config/AuroraConfig.java` and `awf/AwfSettings.java`; omits two bridge keys (contradictions) |
| `docs/AURORA-UX.md` | Operator console and command surface | Active | Startup banner, stage lines, `/aurora`, `/perf` presentation | Stage count corrected 2026-10-07 |
| `docs/AURORA-TASKS.md` | Task checklist grouped by Aurora area | Active (checklist) | Per-item state with evidence links | Priorities live in `TODO.md`; two baseline ticks disagree with `docs/BASELINE.md` |
| `docs/architecture/execution-contract.md` | Execution contract and measured coupling inventory | Active | `OwnerHandoff`/`Admission`, region system, which files name the region backend, async-path invalidation trace | Adapter-file count corrected 2026-10-07; supersedes the status notes in AURORA-INDEPENDENT-ENGINE.md §38 Phase 2 |
| `docs/architecture/aurora-resource-governor.md` | Resource Governor specification | Active | The two governed lanes, budgets, rejection policy, what is out of scope | Configurable budgets and STORAGE use corrected 2026-10-07 |
| `docs/architecture/aurora-instant-startup.md` | Instant Startup cache specification and status | Active | What is cached, never cached, fingerprints, what is not implemented | — |
| `docs/architecture/aurora-engine-package.md` | Source-tree placement | Active | `dev.iyanz.aurora.*` versus `dev.iyanz.sourbycraft.*` | — |
| `docs/architecture/engine-entity-ai.md` | T5 ownership: Entity and AI | Active | What Aurora owns and does not own in Entity/AI; the client-gap measurement constraint | Async-path overload metric corrected 2026-10-07 |
| `docs/architecture/engine-chunk-world.md` | T5 ownership: Chunk and World | Active | Chunk/world ownership; why `chunk-stress` certifies without clients | Patch list predates 0017/0018/0027 |
| `docs/architecture/engine-network.md` | T5 ownership: Network | Active | Network counters and the unowned network policy | Patch-count sentence corrected 2026-10-07 |
| `docs/architecture/engine-storage.md` | T5 ownership: Storage | Active | Durability rule; what Aurora does not own in persistence | AWF wiring sentence corrected 2026-10-07; AWF detail is in `aurora-world-fabric.md` |
| `docs/architecture/independence.md` | Independence levels, rules, build work items | Active (levels and rules); §2 ledger qualitative | Independence levels 0–5, B1–B5 build items | Counted coupling corrected 2026-10-07; the authoritative ledger is `dependency-ledger.md` |
| `docs/AURORA-FULL-TRANSITION.md` | Transition plan, gates T0–T11 | Plan; T-gate numbering is the one other documents cite (T5, T7, T10) | Gate definitions; §4 transition principles (§4.5 measured-only) | Its T1–T4 "status" paragraphs are partly stale (notes added 2026-10-07) |
| `docs/AURORA-ROADMAP.md` | Milestone roadmap M0–M28 | Plan | Milestone gates and dependency graph | §3 "Current Baseline" is a stale snapshot; current state is this file and `TODO.md` |
| `docs/architecture/AURORA-ENGINE-SYSTEM.md` | Engine design guide | Plan | Path A/B/C (direct NMS patch / Aurora service / adapter), patch innovation pipeline, per-domain metric lists | §23 scheduler path (AuroraScheduler adapter) superseded by FULL-TRANSITION T4 and `execution-contract.md` |
| `docs/architecture/AURORA-INDEPENDENT-ENGINE.md` | Independence design argument | Proposal (self-described "Active architecture proposal") | Execution domains, hybrid scheduling, snapshot work, stale-result protection, replacement policy (§39) | Phase 0/2 status notes and §31 Canvas relationship are stale; current statements are in `execution-contract.md`, `dependency-ledger.md` and this file |
| `docs/AURORA-DEVELOPMENT.md` | The 26.2 continuation brief that started the Aurora phase | Historical | Original intent and track list | Its "immediate next tasks" (config model, key migration, typed snapshots) are done; work tracking moved to `TODO.md` and `AURORA-TASKS.md` |
| `docs/architecture/aurora-task-d-validation.md` | Entity/AI Task D validation record, 2026-09-21 | Historical | Why tracker candidate 0018 was withdrawn; collision arithmetic coverage | Patch numbers shifted after renumbering (note added) |

Related documents owned elsewhere: `aurora-plugin-bridge.md` (Bridge), `aurora-world-fabric.md`
(AWF), `chunk-workers.md`, `dependency-ledger.md` (the enforced coupling ledger),
`executor-inventory.md`, `qualification-readiness.md` (T10 gates and the evidence vocabulary),
`compat-vs-performance.md`, `docs/development/AURORA-EDF-UPGRADE.md`, `docs/BASELINE.md`.

**Seven stage schemes.** The documents above number the same journey seven ways: AURORA.md A1–A7,
AURORA-ROADMAP M0–M28, AURORA-FULL-TRANSITION T0–T11, AURORA-INDEPENDENT-ENGINE Stages 1–5 and
Phases 0–8, AURORA-ENGINE-SYSTEM scheduler Steps 1–4, AURORA-DEVELOPMENT Phases Aurora-1–8, and
independence.md Levels 0–5. None is a schedule. When a gate has to be named, use the T-numbers:
they are the ones `qualification-readiness.md` and the `engine-*.md` documents cite.

## Current state (verified 2026-10-07)

> Ruling 2026-10-07 (owner): defaults derived once at boot from the processor count — region tick
> threads, `BRIDGE_IO`, the async-path pool — are **not** auto-tuning under DEVELOPMENT.md §2.2;
> only values that change under load are. Rows below that mention `processors/N` are therefore
> compliant as stated.


Verified by reading the code in the 2026-10-07 working tree of branch `26.2`, which contains
uncommitted changes from other sessions; rows that depend on uncommitted files say so. No build,
test or server was run for this table. Status words follow `qualification-readiness.md` §8 and
README's vocabulary: IMPLEMENTED, MEASURED, CERTIFIED RUN, QUALIFIED, PARTIAL, EXPERIMENTAL,
PLANNED. Paths are relative to `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/` unless
they start with `sourbycraft-server/` or `docs/`.

| Pillar | Status | What the code has | Evidence (file) |
|---|---|---|---|
| Region execution — scheduler | IMPLEMENTED (inherited) | The Folia-lineage region scheduler, SourbyCraft-owned since 2026-10-05; default scheduler type `EDF` | `sourbycraft-server/paper-patches/files/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java.patch` (`SchedulerType.EDF`) |
| Region execution — tick-thread default | IMPLEMENTED; MEASURED (one 8-core block-tick A/B, not a certified pair) | AUTO = every JVM-visible processor; `aurora.cpu.cores` caps it (RESTART_REQUIRED); explicit `threaded-regions.threads` wins | `sourbycraft-server/src/minecraft/java/dev/iyanz/aurora/cpu/AuroraCpu.java` (`regionTickThreads`), feature patch 0002, `docs/architecture/region-parallelism.md` |
| Region execution — Aurora EDF backend | PARTIAL — source only, unaccepted | Feature patch 0028 makes the default `EDF` type construct the LeafPile-derived `AuroraEdfScheduler`. The patch file is untracked; build, tests, boot and profiling are pending, and `test_independence_policy.py` currently fails on it. If built as is, it is the default region scheduler | `sourbycraft-server/minecraft-patches/features/0028-…patch`, `docs/development/AURORA-EDF-UPGRADE.md`, `AGENT-COORDINATION.md` |
| Execution lanes (CPU attribution) | IMPLEMENTED; MEASURED (uncertified lane observations on the demo server) | `ExecutionLane` maps thread names to 11 lanes; `/perf lanes` | `execution/ExecutionLane.java`, `execution/LaneCpuSampler.java`, tests `ExecutionLaneTest`, `LaneCpuSamplerTest`; `compat-vs-performance.md` §5 |
| Execution contracts | PARTIAL | `OwnerHandoff`/`Admission` used by async path completion; region backend named only in `RegionOwnerHandoff`, `FoliaRegionBackend` and `awf/world/AuroraWorldsService` (11 sites, pinned by tests) | `execution/OwnerHandoff.java`, `scripts/test_independence_policy.py`, `execution-contract.md` |
| Resource Governor | PARTIAL | Two governed lanes: `BRIDGE_IO` (max(2, processors/4) threads, queue 256) and `STORAGE` (1 thread, queue 64); budgets from `aurora.scheduler.*` (RESTART_REQUIRED); reject, never caller-runs. Not governed: region ticks, chunk workers, Netty, `AsyncPathProcessor`, `BoundedIoExecutor`, startup pool. No CPU arbitration | `execution/ResourceGovernor.java`, `config/AuroraConfig.java` (`Scheduler`) |
| Aurora Compatibility Bridge | IMPLEMENTED, default off; not QUALIFIED | `aurora.bridge.mode` default `OFF` (RESTART_REQUIRED); routing, violation counting, quarantine. Entity-owner route implemented 2026-10-07 (`EntityTask`, Paper 0011; demo fixture `LEGACY_ENTITY_TASK_OK`/`LEGACY_ENTITY_RETIRED_OK` on a real boot). Per-plugin bridged body timing in `BridgeTelemetry` (demo `/perf plugins` 2026-10-06). Same-instance re-enable reopened by Paper 0012 (unit-tested). All of this is in the 2026-10-07 commit set, not yet through CI | `config/AuroraConfig.java` (`Bridge.DEFAULT`), `bridge/AuroraBridge.java`, `bridge/BridgeTelemetry.java`, `docs/AURORA-TASKS.md` |
| Aurora World Fabric | EXPERIMENTAL, default off | No world uses AWF unless listed in `aurora.awf.worlds` (default empty) or managed through `/awf`; FILE and Redis backends; wired under `RegionFileStorage` (feature patch 0021) and committed at shutdown (0019). Crash test 53/53 is MEASURED | `awf/AwfSettings.java` (`DEFAULT`), `awf/redis/RedisBackend.java`, `awf/AwfEngine.java`, `qualification-readiness.md` §4 |
| Instant Startup cache | PARTIAL | Descriptor, compatibility scan and dependency-graph index cached with integrity checks; transform outputs and AWF indexes not cached; `-Daurora.startup.cache=false` disables the file. No cold/warm measurement, so no startup-time claim | `startup/StartupCache.java`, `startup/StartupIndexStage.java`, `aurora-instant-startup.md` |
| Telemetry / perf | IMPLEMENTED; one CERTIFIED RUN (2 h, 10 players, soak); regression reference pair absent; not QUALIFIED | `MetricsRuntime`, immutable snapshots, 17 `/perf` views including `network`, `governor`, `storage`, `awf`; per-packet cost of the network counters unmeasured | `perf/MetricsRuntime.java`, `command/PerfCommand.java`, `perf/StorageBacklog.java`, `docs/BASELINE.md` ("Second soak"), `qualification-readiness.md` §1 |
| Async pathfinding | EXPERIMENTAL, default off | `aurora.entity.async-pathfinding` default `false` (LIVE); pool max(1, processors/4), queue 1024; saturation refuses (`AbortPolicy`), never runs on the region thread; no certified client-attached measurement | `config/AuroraConfig.java` (`DEFAULT`), `perf/AsyncPathProcessor.java` |
| Independence — upstream | IMPLEMENTED | Paper is the only upstream, pinned by `paperRef`; Canvas named only in `config/upstream/CanvasConfigBridge` and `awf/world/AuroraWorldsService` (5 sites, pinned by tests) | `gradle.properties`, `scripts/test_independence_policy.py` |
| Independence — scheduler stages | Stages 1–3 IMPLEMENTED for SourbyCraft-owned code; Stage 4 PARTIAL (async-path pool, governed lanes); Stage 5 PLANNED | The 0028 EDF backend swaps the pool inside the same region model; it is not an independent scheduler | `execution-contract.md`, `AURORA-INDEPENDENT-ENGINE.md` §10 |
| T10 qualification | not QUALIFIED | Gate items partial or blocked: no certified comparable reference pair, two workloads missing, one open region-ownership bug | `qualification-readiness.md` §1–§6 |

## Open contradictions

One line each: what disagrees, and with what. "Fixed" means the code settled it and the stale
sentence was corrected or annotated on 2026-10-07; everything else is open.

1. README.md (Aurora World Fabric row) says "no database backends"; `awf/redis/RedisBackend.java` and `aurora-world-fabric.md` ("Implemented backends: FILE and Redis") say otherwise. README overstates the absence. — settled 2026-10-07: code decides: `awf/redis/RedisBackend.java` exists (commit `1fe984ed` "Redis backend and .awf world files"), so the FILE-only statement is wrong; README.md line 138 still said "no database backends" at the time of this check (README is outside this pass).
2. README.md ("region backend confined to two adapter files") versus `scripts/test_independence_policy.py` (three files: + `awf/world/AuroraWorldsService.java`). execution-contract.md said the same — **fixed there**. — settled 2026-10-07: code decides: `scripts/test_independence_policy.py` (`test_the_backend_is_named_only_inside_the_adapters`) enumerates three files, `RegionOwnerHandoff`, `FoliaRegionBackend` and `AuroraWorldsService`; README.md line 96 still said two at the time of this check.
3. AURORA-FULL-TRANSITION.md T4 status ("three files and no others": `RegionOwnerHandoff`, `FoliaRegionBackend`, `CanvasConfigBridge`) versus the policy test (backend in three files including `AuroraWorldsService`; Canvas in two). **Annotated.**
4. AURORA-FULL-TRANSITION.md T2 status ("Two settings exist"; "`CanvasConfigBridge` is now the only file in SourbyCraft that names Canvas") versus AURORA-CONFIG.md (19 keys) and the policy test (`AuroraWorldsService` also names Canvas). **Annotated.**
5. AURORA-FULL-TRANSITION.md T3 status (saturation "runs the solve on the submitting thread") versus `perf/AsyncPathProcessor.java` (`AbortPolicy`, refusal) and AURORA-TASKS.md ("saturation refuses, never caller-runs"). **Annotated.**
6. engine-entity-ai.md §1.1/§2 (inline degradation; "the inline count is the one that judges this domain") versus `AsyncPathProcessor.PathStats` (`inline` is a legacy count; `refused` is the saturation signal). **Fixed.**
7. engine-storage.md summary ("the engine's chunk save path does not use it"; "none of the 16 feature patches touch region files") versus feature patches 0019 and 0021 and `RegionFileStorage`'s AWF hook. **Fixed.**
8. aurora-resource-governor.md ("budget comes from code defaults"; operator budgets "not in scope yet"; STORAGE "idle until AWF is wired") versus `config/AuroraConfig.java` (`aurora.scheduler.*`) and `awf/AwfEngine.java` (commits on `STORAGE`). **Fixed.**
9. engine-network.md ("no direct NMS patch touches networking — all 16 feature patches") versus feature patch 0024 (untracked), which edits `Connection` and `ServerGamePacketListenerImpl`. **Fixed** with the untracked caveat. — settled 2026-10-07: 0024 and 0025 were applied to the materialized tree on 2026-10-07 01:35 (commits `049aabd`, `392a8e5`, AGENT-COORDINATION.md); the patch files are still untracked in the root repository (`git status` 2026-10-07).
10. independence.md §2.1 (Canvas coupling "two calls in `SourbyCraftConfig`"; region-threading coupling "4 sites" in `PerformanceCollector`, `HudBars`, `AsyncPathCompletion`) versus `scripts/independence_policy.py` (5 Canvas sites in two files; 11 scheduler sites in three files). **Fixed.**
11. AURORA-INDEPENDENT-ENGINE.md §38 Phase 2 ("five sites across eighty files"; isolation "not started") versus `execution-contract.md` and the policy test (backend isolated to three pinned adapter files). Open; the banner points to execution-contract.md.
12. AURORA-INDEPENDENT-ENGINE.md §38 ("Phase 0 is therefore complete") versus `qualification-readiness.md` §1/§6 (no certified comparable reference pair; the >3% gate is blocked) and AURORA-TASKS.md §C ("[ ] certified idle baseline").
13. AURORA-TASKS.md §C ("[ ] 2h+ concurrency soak") versus `docs/BASELINE.md` ("Second soak — 10 players, two hours … CERTIFIED") and README.
14. AURORA-TASKS.md §C ("[x] 10-player connected baseline — certified both sides of the chunk-worker A/B") versus `docs/BASELINE.md` (the certified `ab-workers-2`/`ab-workers-6` baselines "are superseded … a workload that silently contained no entities").
15. AURORA-ROADMAP.md §3 lists the execution contract, compatibility layer, storage engine and dependency isolation as "Not complete" with no partial state, while `OwnerHandoff`, `AuroraBridge`, AWF and the test-enforced ledger exist (and its own M0 ticks the ledger).
16. AURORA-ENGINE-SYSTEM.md §23 Step 1 ("AuroraScheduler → Folia/Canvas adapter") versus AURORA-FULL-TRANSITION.md T4 ("No AuroraScheduler wrapper", deliberately not built).
17. AURORA-INDEPENDENT-ENGINE.md §31 ("Canvas remains a valuable upstream source during migration"), README.md ("Canvas remains an upstream implementation source where required") and this file's §2 diagram ("Canvas retained internals") versus AGENTS.md and `gradle.properties` (Paper is the only upstream since 2026-10-05). — settled 2026-10-07: code decides: `gradle.properties` (lines 23-25) pins Paper as SourbyCraft's only upstream; the quoted Canvas-upstream sentences remain in their documents at the time of this check.
18. independence.md §9 B2 ("the active Canvas-based 26.2 build") versus the same migration. — settled 2026-10-07: same migration as 17; `independence.md` §9 B2 still says "Canvas-based" at the time of this check.
19. AURORA-DEVELOPMENT.md §3.1 (add Aurora namespaces to `sourbycraft_global_config.toml`) and this file's §3 (`sourbycraft_config/aurora/*.toml` split) versus the implemented `sourbycraft_config/aurora.toml` layered over the unified file (AURORA-CONFIG.md, FULL-TRANSITION T2). — settled 2026-10-07: code decides: `SourbyCraftConfig.java` (`AURORA_PATH = sourbycraft_config/aurora.toml`) layers that one file over the unified TOML; the `aurora/*.toml` split and the namespaces-in-the-unified-file plan are not what the code does. The two stale documents were not corrected by this pass.
20. AURORA-UX.md ("its 13 bootstrap service stages") versus `core/SourbyCraftBootstrap.java` (`TOTAL_STAGES = 14`). **Fixed.**
21. aurora-task-d-validation.md and AURORA-TASKS.md ("collision candidate 0019") versus the feature-patch directory, where the collision patch is now `0018-SourbyCraft-translate-block-collision-boxes-without-.patch` (staged rename). **Annotated.**
22. `qualification-readiness.md` §3 ("storage backlog ✘") and README ("no vanilla save-queue metrics") versus `perf/StorageBacklog.java`/`RegionIoQueue` and `/perf storage` (per-world chunk-system I/O tasks accepted and not finished). Whether that satisfies the T7/T10 "storage backlog" metric is a decision, not settled here.
23. README.md calls AURORA-INDEPENDENT-ENGINE.md "the phased roadmap", while AURORA-ROADMAP.md is the "Full Development Roadmap" and AURORA-FULL-TRANSITION.md carries the T-gates that other documents cite.
24. AURORA-TASKS.md Bridge section ("`[-]` here means implemented and unit-tested but not compiled into a server or run") versus its own next lines (Vault and EssentialsX enabled through the bridge on the demo panel at `9a15f51`).
25. AURORA-CONFIG.md omits `aurora.bridge.max-pending-tasks-per-plugin` and `aurora.bridge.max-running-async-tasks-per-plugin` (both LIVE, default `0` = unlimited) defined in `config/AuroraConfig.java`.
26. AURORA-INDEPENDENT-ENGINE.md §38 Phase 0 names `EDFSchedulerThreadPool` as the contended scheduler; with feature patch 0028 the default `EDF` type constructs `AuroraEdfScheduler`. The measurement is historical and does not describe the 0028 backend. — settled 2026-10-07: patch 0028 was held and parked by owner decision (`sourbycraft-server/minecraft-patches/parked/README.md`); the baseline `SchedulerUtil.java.patch` again constructs LeafPile's `EDFSchedulerThreadPool`, so the Phase 0 measurement describes the active default backend again. `AuroraEdfScheduler` stays in the main tree, unselected. See `docs/development/AURORA-EDF-UPGRADE.md`.

## Deepening the engine — candidates and constraints

What Aurora does **not** own yet, collected from `engine-entity-ai.md` §3, `engine-chunk-world.md`
§3, `engine-network.md` §3, `engine-storage.md` §3 and AURORA-ROADMAP M9–M17/M22. This is a menu,
not a schedule: no row is planned for a date, and choosing one is the owner's decision.

Constraints that bind **every** row:

- **Measured only** — AURORA-FULL-TRANSITION §4.5: no deep engine optimization without CPU,
  allocation, MSPT, I/O or reproducible workload evidence; uncertified runs guide investigation
  only (DEVELOPMENT.md truthfulness policy §3).
- **No automatic performance configuration of any kind** — DEVELOPMENT.md §2.2. Nothing on this
  list may resize pools, change view/simulation distance, mob limits, AI, compression, JVM flags
  or any other setting in response to load. Diagnostics may warn and recommend; the operator
  decides.
- **Region ownership is authoritative** — DEVELOPMENT.md §2.3: region-local state, immutable
  publication, owner handoff; no shared mutable scratch (§12 of this file).
- **The product workload is undecided** — `compat-vs-performance.md` §4/§6.3: "one player
  exploring new terrain" and "ten settled players" give opposite answers (for example on chunk
  workers). A candidate whose benefit depends on that choice cannot be justified until the owner
  records it in TODO.md.

| Candidate area | What Aurora has today | Not owned (source) | Evidence needed before any change | Extra constraint |
|---|---|---|---|---|
| AI scheduling | Async periodic path recompute (EXPERIMENTAL, off) | GoalSelector, Brain, Sensor scheduling; target scans; navigation beyond periodic recompute (engine-entity-ai §3) | AI-stress workload — missing (`qualification-readiness` §2) — with real clients attached | No AI disabling or reduction under lag (§2.2); mob AI does not run without players in range |
| Entity tick and tracking | Local patches 0003, 0009, 0011, 0012, 0014, 0015, 0017 | Tracking and metadata sync, item merge/despawn policy, living/mob tick ordering (engine-entity-ai §3) | Certified `entity-stress`/`players-N` pair with clients | Tracker paths cross synchronous plugin callbacks (`aurora-task-d-validation.md`); plugin semantics are part of correctness |
| Collision | Patch 0018 (arithmetic-tested, not gameplay-qualified) | Broader collision engine (ROADMAP M11) | Gameplay qualification of 0018 first | Vanilla full-block versus epsilon rules must not be unified |
| Chunk system | Lane attribution, region tick metrics, optional generation-stage timing (0027, unverified) | Holder lookup, tickets, load/integration/unload/send, generation scheduling (engine-chunk-world §3) | Certified `chunk-stress` reference — certifiable without clients, the cheapest first reference | Worker count is product-workload-dependent; no default change and no automatic sizing |
| World tick | Patches 0004, 0010 | Scheduled ticks, block and fluid updates, block entities (engine-chunk-world §3) | Certified `chunk-stress`; random-tick volume is a weak lever on the measured host | — |
| Network | Lane CPU, byte/packet/connection counters | Per-packet-type counts, allocation/copy analysis, compression policy, chunk and entity packet paths, queue health (engine-network §3) | Certified `network-stress` with clients (never certified so far) | Compression settings stay operator-owned; no adaptive compression (§2.2) |
| Storage / persistence | AWF (EXPERIMENTAL, off), storage backlog view, persistence tooling | Save queue, serialization handoff, region-file write policy, shutdown flush (engine-storage §3) | Persistence and crash validation before any throughput number | No performance gain from weakened durability (FULL-TRANSITION §11.6) |
| Memory | Heap/GC telemetry, cache inventory | No Aurora memory owner document exists (ROADMAP M17) | Allocation profiles with provenance; a soak for any leak claim | Reuse only with proven ownership (§12) |
| Scheduler | Contracts, three pinned adapter files; the LeafPile-derived EDF backend (0028) is HELD and parked by owner decision 2026-10-07 (`minecraft-patches/parked/`) | Independent compute domains beyond async path; scheduler replacement (INDEPENDENT-ENGINE Stages 4–5, ROADMAP M22) | Ownership/shutdown acceptance of 0028 first; then workload evidence against the current backend | Replacement policy (INDEPENDENT-ENGINE §39); thread counts must account for total CPU (truthfulness §6) |
| Plugin cost | Bridge counts plus per-plugin body timing and onLoad/onEnable wall time (2026-10-07); first joined measurement: demo `plugins-10` 2026-10-06, uncertified | A plugin-heavy representative workload (`qualification-readiness` §2) | A defined plugin set in `scripts/baseline_workloads.py` | Bridged sync work lands on the global region, which no thread count parallelises |


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
