# Aurora Chunk & World Engine — Ownership

> **Role:** T5 ownership document for Chunk and World. **Status:** Active; patch and metric tables refreshed
> against the Build 50 branch on 2026-10-25 (static reading of `minecraft-patches/features/` and the `perf`
> package). World storage ownership (AWF) is in [engine-storage.md](engine-storage.md).
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

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
| `execution/ExecutionLane.WORLD_IO` | Attribution of world reads and writes: the chunk I/O pool, the Sourby storage lanes and the AWF maintenance thread | Measurement, not scheduling policy |
| `perf/ChunkGenerationMetrics`, `ChunkLifecycleCounters`, `RegionFileSaveLatency`/`RegionFileLoadLatency`, `RegionTaskWaitLatency` | Generation stage timing, chunk lifecycle transitions, region-file save/load latency, region task queue wait | Measurement only (§2) |
| Aurora World Fabric (`awf`) | Region-aware world storage attached from `RegionFileStorage` | Storage, not chunk scheduling: [engine-storage.md](engine-storage.md), [aurora-world-fabric.md](aurora-world-fabric.md) |

Aurora owns **no** chunk-system policy: not holder lookup, not ticket processing, not load,
integration, unload, send, or generation scheduling. It measures them.

### 1.2 Direct NMS patches

Feature patches whose subject is chunk or world code (local algorithm changes, measurement hooks, and
lifecycle/persistence fixes; none of them is a chunk scheduling policy):

| Patch | File | Domain | Nature |
|---|---|---|---|
| 0002 | `TickRegions` | Runtime | Sane default region tick thread count — sizing, not chunk policy |
| 0004 | `PoiManager` | World | POI consistency scan without per-block lookups |
| 0010 | `ServerLevel` | World | Reuse `BlockPos` in the random-tick path (§11.4 *random ticks*) |
| 0014 | `ServerChunkCache` | Chunk/Entity | Skip a spawn-state scan a region cannot use |
| 0017 | `ChunkEntitySlices` | Chunk/Entity | Query bounds hoisted out of the entity intersection loop |
| 0018 | `CollisionUtil` | World | Block collision boxes translated without allocating on a miss |
| 0021, 0043, 0050 | `RegionFileStorage` | Chunk (measurement) | Storage traffic counts; region-file save and load latency for `/perf storage` |
| 0026 | `ChunkUpgradeGenericStatusTask` | Chunk (measurement) | Generic generation stage timing (`aurora.diagnostics.chunk-generation-metrics`, RESTART_REQUIRED) |
| 0045, 0049 | `RegionizedTaskQueue`, `TickRegionScheduler` | Runtime (measurement) | Lock-free queue depth and late-tick counts; how long region tasks wait in their queue |
| 0051 | `BaseChunkSystemHooks` | Chunk (measurement) | Chunk lifecycle transition counts for `/perf chunks` |
| 0063 | `EndPlatformFeature`, `Entity`, `Level`, `TickRegions` | World | End platform portal event; tickable block entity count |
| 0031, 0032 | `TickRegions`, `RegionizedTaskQueue` | Chunk/Runtime | Save tickets kept across merge and split; cancelled tasks dropped when a region task queue splits |
| 0019, 0027, 0056, 0067, 0071, 0072, 0077 | `MinecraftServer`, `RegionizedServer`, ticket storage, `SaveAllCommand` | World (persistence) | AWF commit at shutdown, world metadata and global-owned level data on autosave, detached persistent tickets saved periodically, stop fails when AWF cannot confirm a storage, `save-all` reports AWF durability, periodic `level.dat` off the global tick ([persistence-save-paths.md](persistence-save-paths.md)) |
| 0029, 0030, 0033 | `ChunkTaskScheduler`, `MinecraftServer`, `WorldShutdownThread`, region scheduler/task queue | World (lifecycle) | Staged world creation around the initial spawn search; a world unload always completes its callback; region work drained and storage closed on unload |
| 0053 | `RegionFileStorage` | World | Read AWF once and fail fast while the store is failing |
| 0087 | `ServerChunkCache` | Chunk | Synchronous chunk loads refused on a region shutdown thread (server stop or world unload); the message was corrected in PR #62, the refusal is unchanged |

---

## 2. Metrics

| Metric | Source | Surfaced by |
|---|---|---|
| Chunk worker lane CPU | `LaneCpuSampler` / `ExecutionLane.CHUNK_WORKER` | `/perf lanes` |
| Engine background lane CPU | `ExecutionLane.BACKGROUND` | `/perf lanes` |
| World I/O lane CPU | `ExecutionLane.WORLD_IO` | `/perf lanes` |
| Region tick cost, worst/median/aggregate MSPT | `RegionTickMetrics` | `/tps`, `/perf` |
| Active regions / retained generations | `RegionMetricsRegistry` | `/tps`, `/perf` |
| Loaded chunks and regions per world; generic generation stage queue/run p95/p99 (when enabled); chunk lifecycle transitions | world population; `ChunkGenerationMetrics` (MC 0026); `ChunkLifecycleCounters` (MC 0051) | `/perf chunks` |
| Chunk-system reads / writes / deletes; region-file save and load latency (p50/p99/max); pending storage I/O per world | MC 0021, `RegionFileSaveLatency` (MC 0043), `RegionFileLoadLatency` (MC 0050) | `/perf storage` |
| Region task queue wait (tick and chunk tasks; bucket upper bounds) | `RegionTaskWaitLatency` (MC 0049) | `/perf scheduler` |

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

The world hot paths Aurora has touched (random-tick allocation, 0010; collision boxes, 0018; entity
query bounds, 0017) are local allocation or loop fixes, not policies; the other patches in §1.2 measure,
or fix lifecycle and persistence.

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
| Metrics | chunk worker + background + world I/O lane CPU, region tick, `/perf chunks`, `/perf storage`, `/perf scheduler` | region tick, active regions |
| Implementation boundary | §1, by the §11 rule | §1, by the §11 rule |

Aurora's position in this domain is honest measurement, a handful of local hot-path patches, and
lifecycle/persistence fixes (§1.2). Taking ownership
of chunk scheduling policy is not started, and §4.5 means it should not start before a certified
`chunk-stress` reference exists.
