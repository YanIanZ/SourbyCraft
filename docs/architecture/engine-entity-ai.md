# Aurora Entity & AI Engine — Ownership

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
| `perf/AsyncPathProcessor` | Admission policy for off-thread pathfinding: pool sizing, bounded queue, inline degradation, refusal after shutdown | `dev.iyanz.sourbycraft.perf` |
| `execution/ExecutionLane` + `LaneCpuSampler` | Which lane entity/AI work is attributed to, and its CPU cost | `dev.iyanz.sourbycraft.execution` |
| `execution/OwnerHandoff` → `RegionOwnerHandoff` | Moving work to the thread that owns an entity | `dev.iyanz.sourbycraft.execution` |
| `config/AuroraConfig.Entity` | The operator-facing switch (`aurora.entity.async-pathfinding`), its lifecycle class and its default | `dev.iyanz.sourbycraft.config` |

### 1.2 Direct NMS patches (local algorithm changes)

These are correctly *not* Aurora services. Each changes a local hot path in the class that owns
the data.

| Patch | File(s) | Domain | Nature |
|---|---|---|---|
| 0005 | `dev/iyanz/aurora/level/SnapshotPathRegion.java`, `PathNavigationRegion.java` | AI | The immutable block snapshot that makes off-thread solving safe — Aurora's own class, in the Minecraft tree because it implements a vanilla interface |
| 0006 | `Mob`, `PathNavigation`, `AmphibiousPathNavigation`, `Sniffer`, `PathfindingContext` | AI | The offload call sites for periodic path recomputation |
| 0003 | `NaturalSpawner` | Entity | Skip provably capped spawn categories |
| 0014 | `ServerChunkCache` | Entity | Skip a spawn-state scan a region cannot use |
| 0009, 0012 | `Entity` | Entity | Allocation/reuse in movement and collision |
| 0011 | `Level` | Entity | Caller-owned entity query overloads |
| 0015 | `AbstractArrow` | Entity (projectiles) | Reject a landed arrow before the projectile tag lookup |

---

## 2. Metrics

The gate asks for metrics, not for a promise of them.

| Metric | Source | Surfaced by |
|---|---|---|
| Solves admitted / outstanding | `AsyncPathProcessor.PathStats` | `/perf async` |
| Solve time mean / slowest | `PathStats.meanMillis` / `slowestMillis` | `/perf async` |
| Solves run on the caller (pool saturated) | `PathStats.inline` | `/perf async` |
| Solves refused after shutdown | `PathStats.refused` | `/perf async` |
| Per-lane CPU attribution | `LaneCpuSampler` / `LanePortions` | `/perf lanes` |
| Region tick cost | `RegionTickMetrics` | `/tps`, `/perf` |

The **inline** count is the one that judges this domain. An inline solve ran on a region thread
*after* paying to build the snapshot, so it costs more than not offloading at all. A rising
inline count means the pool is undersized and the feature is losing.

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
