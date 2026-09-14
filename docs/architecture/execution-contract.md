# Execution contract — what SourbyCraft needs from the region scheduler

Stage 1 of the Aurora scheduler program (`AURORA-INDEPENDENT-ENGINE.md` §10) asks for
one thing: *document exactly what SourbyCraft depends on from the current region
scheduler*. This is that document. It is measured, not recalled —
`scripts/independence_policy.py` produces the inventory and
`scripts/test_independence_policy.py` pins it, so the contract is designed against a
fixed list and a new reach into the scheduler has to be added deliberately.

## What counts

The public Bukkit-facing Folia API — `io.papermc.paper.threadedregions.scheduler` —
is **not** coupling. Depending on a published contract is the goal. What matters is the
internal surface: `TickRegionScheduler`, `RegionizedServer`, `RegionizedWorldData`,
`ThreadedRegionizer`, `TickRegions`.

## The inventory

Five sites in SourbyCraft-owned code, across 80 files:

| Where | Symbol | Why |
| --- | --- | --- |
| `perf/PerformanceCollector.java` | `RegionizedServer.getGlobalTickData()` | read the global tick handle's metrics |
| `perf/PerformanceCollector.java` | `TickRegionScheduler::getTickRate` | the configured tick rate, for TPS |
| `perf/AsyncPathCompletion.java` | `EntityScheduler` | hand a finished path back to the owning entity's region |

One site in the fourteen engine-integration patches:

| Patch | Symbol | Why |
| --- | --- | --- |
| `0006` async pathfinding | `TickRegionScheduler.getCurrentRegionizedWorldData() != null` | am I on a region thread, so is offload legal |

Two further patches (`0002`, `0013`) *edit* `TickRegionScheduler.java` and
`TickRegions.java` rather than call into them — a thread-count default and the tick
metrics hook. Those are engine modifications, not call-site coupling, and an Aurora
contract does not remove them.

## The contract that covers it

Four capabilities, no more:

1. **`boolean isRegionThread()`** — whether the calling thread owns a region right now.
   The only behavioural dependency in the whole set: patch `0006` uses it to decide
   whether offloading a path computation is legal, falling back to the vanilla sync path
   when it is not.
2. **`double tickRateHz()`** — the configured tick rate.
3. **A tick-metrics handle for the global tick** — already Sourby-owned
   (`RegionTickMetrics`); only *reaching* it goes through `RegionizedServer` today.
4. **Entity-affine scheduling** — already satisfied by the public Folia API. It stays
   as it is.

Capabilities 2 and 3 are read-only telemetry and 4 is a published contract, which leaves
**exactly one** capability that a replacement scheduler would have to implement
behaviourally: *am I on a region thread*.

## What this means for the roadmap

The scheduler dependency is not the obstacle to independence. Phase 2's second bullet —
"isolate direct Canvas/Folia scheduler access" — is a small, mechanical change against
five sites, not an architectural project. Canvas coupling is the same story: two sites,
both config reload bridges.

The expensive parts of independence lie elsewhere (storage, network, entity and chunk
execution), and this document exists so that effort is not spent abstracting a surface
that measurement shows is already thin.
