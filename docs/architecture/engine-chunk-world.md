# Aurora Chunk & World Engine — Ownership

T5 deliverable for §11.3 (Chunk) and §11.4 (World) of `docs/AURORA-FULL-TRANSITION.md`.

Ownership, metrics and implementation boundary for the two domains that decide what a loaded
world costs. As in [the Entity/AI document](engine-entity-ai.md), what Aurora does *not* own is
stated as plainly as what it does.

## The boundary rule

Applied unchanged from §11:

```text
local algorithm change   → direct NMS patch
shared subsystem         → Aurora-owned service
```

Chunk and world hot paths are almost entirely local algorithms inside classes that own their
data (`ServerLevel`, `ServerChunkCache`, `PoiManager`). That is why this domain has fewer Aurora
services than the Entity domain and more patches — and why wrapping them would be the mistake
§11 warns about, not progress.

---

## 1. What Aurora owns today

### 1.1 Aurora-owned services

| Service | Owns | Note |
|---|---|---|
| `execution/ExecutionLane.CHUNK_WORKER` | Attribution of the chunk system's worker pool — *"the lane that answers how much world load costs"* | Measurement, not scheduling policy |
| `execution/ExecutionLane.BACKGROUND` | Attribution of `Util.backgroundExecutor()`, deliberately **not** folded into `CHUNK_WORKER` | Counting the two together made a chunk-worker A/B read 4 and 12 threads where the setting said 2 and 6 |
| `execution/region/RegionBackend` → `FoliaRegionBackend` | Region topology and tick scheduling behind an Aurora contract | Upstream supplies the implementation (see the [dependency ledger](dependency-ledger.md) §1.2) |
| `perf/RegionTickMetrics` | Per-region tick cost and generation lifetime | Feeds `/tps`, `/perf` |

Aurora owns **no** chunk-system policy: not holder lookup, not ticket processing, not load,
integration, unload, send, or generation scheduling. It measures them.

### 1.2 Direct NMS patches

| Patch | File | Domain | Nature |
|---|---|---|---|
| 0010 | `ServerLevel` | World | Reuse `BlockPos` in the random-tick path (§11.4 *random ticks*) |
| 0014 | `ServerChunkCache` | Chunk/Entity | Skip a spawn-state scan a region cannot use |
| 0004 | `PoiManager` | World | POI consistency scan without per-block lookups |
| 0002 | `TickRegions` | Runtime | Sane default region tick thread count — sizing, not chunk policy |

---

## 2. Metrics

| Metric | Source | Surfaced by |
|---|---|---|
| Chunk worker lane CPU | `LaneCpuSampler` / `ExecutionLane.CHUNK_WORKER` | `/perf lanes` |
| Engine background lane CPU | `ExecutionLane.BACKGROUND` | `/perf lanes` |
| Region tick cost, worst/median/aggregate MSPT | `RegionTickMetrics` | `/tps`, `/perf` |
| Active regions / retained generations | `RegionMetricsRegistry` | `/tps`, `/perf` |

**On `active regions`.** This number is a real measurement and does move: the local `idle`
baseline on the seed world reported a mean of **8.0** active regions, while the deployment
server reported **2** under every load tried on 2026-09-18. A low count is a property of the
world and the load, not a stuck counter — it was checked, because it had been wrongly called a
telemetry bug once.

---

## 3. What Aurora does not own yet

| Not owned | Domain |
|---|---|
| Holder lookup, ticket processing | Chunk |
| Load, integration, unload, send | Chunk |
| Generation scheduling | Chunk |
| Scheduled ticks, block updates, fluid updates | World |
| Block entities | World |

`ServerLevel` random-tick allocation (patch 0010) is the only world hot path Aurora has
touched, and it is a local allocation fix, not a policy.

---

## 4. A measured property of this domain

Unlike Entity/AI, **chunk and world load does not depend on connected players**. Random ticks
run in every loaded chunk section on the region thread that owns it, with no activation gate.
That was confirmed on the deployment server on 2026-09-18 with a control:

| `random_tick_speed` | farmland probes surviving at 6 dispersed sites |
|---|---|
| 0 (control) | 6 / 6 |
| 480 | 0 / 6 |

So the work reaches every site, and `chunk-stress` is one of the two workloads
(`docs/BASELINE.md`) that can be certified **without** attaching clients — which makes this
domain the cheaper of the four to qualify.

A caution recorded from the same session: raising `random_tick_speed` from 3 to 480 — a 160×
increase — moved total CPU only from 0.51 to 0.86 of 8 cores. Random-tick volume is not a
strong lever on this hardware, so a chunk/world optimization justified by that knob alone would
be justifying itself against noise.

---

## 5. Gate status

| T5 requirement | Chunk | World |
|---|---|---|
| Ownership document | this file | this file |
| Metrics | chunk worker + background lane CPU, region tick | region tick, active regions |
| Implementation boundary | §1, by the §11 rule | §1, by the §11 rule |

Aurora's position in this domain is honest measurement and four local patches. Taking ownership
of chunk scheduling policy is not started, and §4.5 means it should not start before a certified
`chunk-stress` reference exists.
