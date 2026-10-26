# Aurora Entity & AI Engine — Ownership

> **Role:** T5 ownership document for Entity and AI. **Status:** Active; tables and async-path metrics refreshed
> against the Build 50 branch on 2026-10-25. The PR #62 changes named below are code written and statically
> reviewed; not built; not tested by the owner, and in no published build (`v26.2-r50` predates them).
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

T5 deliverable for §11.1 (Entity) and §11.2 (AI) of `docs/AURORA-FULL-TRANSITION.md`.

The T5 gate asks each domain for three things: **ownership**, **metrics**, and an
**implementation boundary**. This document states all three for Entity and AI, and is equally
explicit about what Aurora does *not* own yet, because a domain document that claims finished
work is worse than no document.

## The boundary rule

§11 is blunt about this, and it is the rule that decides every row below:

```text
local algorithm change   → direct NMS patch
shared subsystem         → Aurora-owned service
```

Aurora does **not** wrap hot loops to make them look owned. A tighter `AABB` test inside
`Entity` belongs in `Entity`. What Aurora owns is the policy around such code: whether work is
admitted, where it runs, who owns the data it touches, and what it costs.

---

## 1. What Aurora owns today

### 1.1 Aurora-owned services (shared subsystems)

| Service | Owns | Where |
|---|---|---|
| `perf/AsyncPathProcessor` | Admission policy for off-thread pathfinding: capacity check, submission and refusal on saturation and after shutdown (inline only before the pool starts), and the counters behind `/perf async` | `dev.iyanz.sourbycraft.perf` |
| `execution/ResourceGovernor`, `Lane.PATHFINDING` | The pathfinding pool's size (`max(1, availableProcessors/4)` threads) and queue (1024); not operator-configurable | `dev.iyanz.sourbycraft.execution` |
| `perf/AsyncPathRecompute` | The offloaded periodic recompute: owner-thread check (*PR #62*), admission checks, `EntityPathfindEvent`, snapshots, submission, and the owner-thread delivery with its staleness checks; patch 0006 only calls it | `dev.iyanz.sourbycraft.perf` |
| `perf/MobPathSnapshot` | The immutable mob members an off-thread solve reads (MC 0039) | `dev.iyanz.sourbycraft.perf` |
| `perf/AsyncPathCompletion`, `AsyncPathValidity` | Delivery of a finished solve through the owner handoff (or its retirement), and the subject/path/generation checks before it is applied | `dev.iyanz.sourbycraft.perf` |
| `perf/AsyncPathTypeStats` | Per-entity-type admitted/solved/refused/declined counts (bounded table with an "other" row) | `dev.iyanz.sourbycraft.perf` |
| `perf/EntityTickCounters` | Entity ticks per world, counted in the region entity loop (MC 0052) | `dev.iyanz.sourbycraft.perf` |
| `execution/ExecutionLane` + `LaneCpuSampler` | Which lane entity/AI work is attributed to, and its CPU cost | `dev.iyanz.sourbycraft.execution` |
| `execution/OwnerHandoff` → `RegionOwnerHandoff` | Moving work to the thread that owns an entity | `dev.iyanz.sourbycraft.execution` |
| `config/AuroraConfig.Entity` | The operator-facing switch (`aurora.entity.async-pathfinding`), its lifecycle class and its default | `dev.iyanz.sourbycraft.config` |

### 1.2 Direct NMS patches (local algorithm changes)

These are correctly *not* Aurora services. Each changes a local hot path in the class that owns
the data.

| Patch | File(s) | Domain | Nature |
|---|---|---|---|
| 0005 | `dev/iyanz/aurora/level/SnapshotPathRegion.java`, `PathNavigationRegion.java` | AI | The immutable block snapshot that makes off-thread solving safe — Aurora's own class, in the Minecraft tree because it implements a vanilla interface |
| 0006 | `PathNavigation` | AI | The offload call site for periodic path recomputation (`AsyncPathRecompute.offload`) and the navigation's private-state hooks; the code itself moved to Sourby source on 2026-10-22 |
| 0039 | `NodeEvaluator`, `PathFinder`, `PathfindingContext`, `Walk`/`Fly`/`Swim`/`AmphibiousNodeEvaluator`, `Frog` | AI | Off-thread solves read a `MobPathSnapshot`, never the live mob |
| 0042 | `Creaking` | AI | The Creaking home position captured on the owner for an off-thread solve |
| 0047 | `PathNavigation` | AI | A navigation generation that invalidates in-flight async results |
| 0048 | `SnapshotPathRegion` | AI | `*IfLoaded` reads answer `null` for an unloaded chunk, as vanilla |
| 0082 | `PathfindingContext` | AI | No shared path-type cache on a thread that ticks no region |
| 0003 | `NaturalSpawner` | Entity | Skip provably capped spawn categories |
| 0014 | `ServerChunkCache` | Entity | Skip a spawn-state scan a region cannot use |
| 0009, 0012, 0044, 0046 | `Entity` | Entity | Allocation/reuse in movement and collision; horizontal speed read from one field; the horizontal-movement predicate published as a volatile boolean |
| 0011 | `Level` | Entity | Caller-owned entity query overloads |
| 0017 | `ChunkEntitySlices` | Entity | Query bounds hoisted out of the entity intersection loop |
| 0018 | `CollisionUtil` | Entity | Block collision boxes translated without allocating on a miss |
| 0015 | `AbstractArrow` | Entity (projectiles) | Reject a landed arrow before the projectile tag lookup |
| 0052 | `RegionizedWorldData`, `ServerLevel` | Entity (metrics) | Entity ticks per world for `/perf entities` |
| 0057, 0064, 0086 | `Entity`, `ServerPlayer`, `ThrownEnderpearl` | Entity (teleport) | Owned synchronous teleport on the owning region, the ender pearl teleport event, legacy `PlayerTeleportEvent`/`PlayerPortalEvent` on the async routes |
| 0068 | `WanderingTraderSpawner` and world data | Entity (spawning) | One wandering trader spawner per world on the global tick ([wandering-traders.md](wandering-traders.md)) |
| 0084, 0085 | `EntityLookup`, `ChunkMap` and others | Entity (relaxed thread checks only) | Relaxed ownership checks and cross-region world data; in effect only while `aurora.bridge.thread-checks` relaxes them ([aurora-plugin-bridge.md](aurora-plugin-bridge.md)) |

---

## 2. Metrics

The gate asks for metrics, not for a promise of them.

| Metric | Source | Surfaced by |
|---|---|---|
| Solves admitted / outstanding | `AsyncPathProcessor.PathStats` | `/perf async` |
| Solve time mean / slowest | `PathStats.meanMillis` / `slowestMillis` | `/perf async` |
| Queue wait mean / slowest; queue depth / active workers / pool size | `PathStats.meanWaitMillis` / `slowestWaitMillis`, `queueDepth`, `activeWorkers`, `poolSize` | `/perf async` |
| Offloads declined before the snapshot (pool full or stopping); the synchronous recompute ran (*PR #62*) | `PathStats.declined` (per type too) | `/perf async` |
| Submissions refused after the capacity check (queue filled in between, shutdown race); the synchronous recompute ran | `PathStats.refused` | `/perf async` |
| Results applied / discarded (entity replaced, path replaced, generation moved on, no path) (*PR #62*) | `PathStats.applied`, `staleSubject`, `stalePath`, `staleGeneration`, `noPath` | `/perf async` |
| Results retired before delivery (mob removed or unloaded, or the owner handoff refused) (*PR #62*) | `PathStats.retired` | `/perf async` |
| Busiest mob types: admitted / solved / refused / declined | `AsyncPathTypeStats.top` | `/perf async` |
| Legacy caller-run count (should stay zero) | `PathStats.inline` | `/perf async` |
| Entities and players per world; entity ticks per world | world population (as `/perf chunks`); `EntityTickCounters` (MC 0052) | `/perf entities` |
| Per-lane CPU attribution | `LaneCpuSampler` / `LanePortions` | `/perf lanes` |
| Region tick cost | `RegionTickMetrics` | `/tps`, `/perf` |

The **declined** count, with refused, is the one that judges this domain. Since 2026-10-14 (audit
ENG-7/ENG-14) a saturated pool declines the offload before the snapshot is built, and the vanilla
synchronous recompute runs on the region thread for that slot; a submission refused after the
capacity check also falls back to the synchronous recompute. Before *PR #62* those declines were
counted nowhere, so a saturated pool showed `refused` near zero. A rising declined count means the
pool is at capacity for the workload: investigate the workload rather than adding threads blindly.
(This paragraph described an earlier keep-the-stale-path policy until 2026-10-25.)

---

## 3. What Aurora does not own yet

Stated plainly, because §11.1/§11.2 list these and Aurora has no policy for them today:

| Not owned | Domain |
|---|---|
| GoalSelector, Brain, Sensor scheduling | AI |
| Target scans | AI |
| Navigation beyond the periodic-recompute offload | AI |
| Entity tracking, metadata sync | Entity |
| Item entity merge/despawn policy | Entity |
| Living/mob tick ordering | Entity |

Async pathfinding is the *only* AI policy Aurora currently owns, and it is
**default-off, unqualified** (`AuroraConfig.DEFAULT` has `Entity(false)`), pending T10.

---

## 4. The measurement constraint this domain has

Entity and AI are the two domains hardest to measure honestly on this project, for a reason
that is a property of Minecraft, not of the harness:

> Entity activation range is computed around connected players. With zero players connected,
> no entity is activated, so mob AI, goal selection and pathfinding do not run.

`docs/BASELINE.md` states this as the *client gap*, and it was confirmed independently on the
deployment server on 2026-09-18: ramping 750 → 3750 zombies across dispersed forceloaded sites
moved worst-region MSPT **down** (15.3ms → 12.9ms) and left the machine at ~0.28 of 8 cores.
The entities existed and were chunk-ticking; their AI never ran.

Consequences for this domain, both of which are binding:

1. **Any entity/AI baseline without connected clients measures the inactive path.**
   `players-*` and `entity-stress` set `requires_connected_players`, and `run_baseline.py`
   refuses to certify them without `--connected-players N`.
2. **No optimization in this domain may be accepted on an uncertified run** (§4.5, PRD §115).
   That includes turning async pathfinding on by default.

---

## 5. Gate status

| T5 requirement | Entity | AI |
|---|---|---|
| Ownership document | this file | this file |
| Metrics | region tick, lane CPU | `PathStats` via `/perf async` |
| Implementation boundary | §1, by the §11 rule | §1, by the §11 rule |

What remains before this domain can claim more than documentation is **T10 qualification** of
async pathfinding on a certified `entity-stress` or `players-N` run with real clients attached.
Until then the honest status is: the boundary is drawn, the metrics exist, and the one policy
Aurora owns here is switched off.
