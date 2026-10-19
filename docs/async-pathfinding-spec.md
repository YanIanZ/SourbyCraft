# SourbyCraft — Async Pathfinding (Phase 1a of the async-offload MT uplift)

Status: **EXPERIMENTAL / OFF by default**. Functional wiring exists, but default-on requires representative behavior, saturation and performance evidence.

## Goal
Move the A* path solve off the region tick thread onto a bounded worker pool, so a
region packed with pathing mobs sheds ~20% of its tick cost (measured: PathNavigation +
PathFinder + WalkNodeEvaluator were ~20% of the hot 500-player region). Vanilla-accurate;
this is a latency-shift, not a behaviour change.

## The hard constraint
`Level.getBlockState` races / NPEs off the region thread (the trap that killed antixray).
The whole solve must read world state from an **immutable snapshot taken on the region
thread**, never from the live level.

## World-access boundary (from source audit)
```
PathNavigation.createPath        (region thread; builds PathNavigationRegion, calls findPath)
  -> PathFinder.findPath(region, mob, targets, ...)
       -> NodeEvaluator.prepare(region, mob) -> new PathfindingContext(region, mob)
       -> A* loop: nodeEvaluator.getNeighbors(...) 
            -> PathfindingContext.getBlockState/getFluidState/getPathTypeFromState
            -> region.noCollision(...)          (collision shapes from block states)
```
Every read funnels through `PathNavigationRegion` (a `CollisionGetter`). Today it snapshots
only **chunk references**; block reads dereference the live mutable section palettes.

## Design
1. **`SnapshotPathRegion`** — a `PathNavigationRegion` whose constructor (on the region
   thread) copies each non-air `LevelChunkSection`'s block `PalettedContainer` via
   `getStates().copy()` (cheap: uniform/air sections are single-value palettes). Overrides
   `getBlockState / getFluidState / getBlockStateIfLoaded / getFluidIfLoaded /
   getChunkForCollisions` to read the copies. `getFluidState = getBlockState(pos).getFluidState()`
   (fluid is derived, no separate snapshot). Skipped sections read as AIR. Immutable after
   construction → safe to read from any thread.
   - Private per-solve `PathTypeCache` (not the shared regionized one which the region thread
     invalidates on block writes).

2. **`AsyncPathProcessor`** — bounded platform-thread pool (size ~`max(1, cores/4)`; CPU-bound,
   NOT virtual threads). Submits `() -> pathFinder.findPath(snapshot, ...)`. Each solve owns its
   own `PathFinder`/`NodeEvaluator` (mutable A* buffers are never shared across threads). The
   solver comes from `PathNavigation.sourbyIsolatedPathFinder`: `createPathFinder` assigns the
   evaluator it creates to the navigation's live `nodeEvaluator` field as a side effect, so the
   live evaluator is restored and the solver's copy takes over its `canPassDoors`,
   `canOpenDoors`, `canFloat` and `canWalkOverFences` flags (audit ENG-2; before 2026-10-14 each
   offload replaced the mob's live evaluator and reset those flags to their defaults).
   Saturation never runs a refused solve on the pool's caller (no CallerRuns); see the
   saturation contract below for what the navigation does instead.

3. **Periodic recompute only** — `PathNavigation.recomputePath` offloads only when the mob already
   has a live, unfinished path. Snapshot + event validation happen on the owning region thread;
   the A* solve runs on the worker; completion is handed back through the entity owner scheduler.
   A path identity token prevents a stale result from overwriting a newer retarget/stop/recompute.

4. **Config**: `aurora.entity.async-pathfinding` (default `false`), with legacy
   `perf.ai.async-pathfinding` compatibility where still supported. When false, the vanilla
   synchronous path remains the active behavior.

## Correctness notes
- Latency: path ready next tick instead of same tick. Invisible for the ~20-tick recompute
  cadence; a 1-tick delay on first path request. Matches Airplane/Petal shipping behaviour.
- A mob whose target chunk is unloaded still gets `PathType.BLOCKED` (Paper "don't load chunks
  during pathfinding") — snapshot stores only loaded sections, same result.
- Snapshot cost is charged to the region thread. Net win = A* cost − snapshot cost. Guarded:
  only snapshot occupied sections; skip when the region is `allEmpty`.

## Verification gate before default-on
1. Compile + boot clean with flag OFF (zero behaviour delta). ← this session
2. Flag ON on the test server: mobs path normally (villagers pathfind to workstations, zombies
   chase, pathing across water/fences), no async-access crash in logs over a soak.
3. Spark A/B: region-thread pathfinding frames drop; worst-region MSPT under a mob-heavy crowd.


## Saturation contract

Saturation must not create a latency cliff.

Current contract:

```text
worker capacity available       -> snapshot, solve asynchronously
no capacity (hasCapacity false) -> decline the offload before the event and the snapshot;
                                   the vanilla synchronous recompute runs this slot
queue filled since the check    -> trySubmit returns null; the vanilla recompute runs this
  (trySubmit null)                 slot (the snapshot was wasted and EntityPathfindEvent
                                   fires a second time; a narrow race)
shutdown/stopping               -> no capacity, as above
solver failure                  -> null result, the mob keeps its live path; logged at most
                                   once a minute with a suppressed count
```

The pool itself never executes a refused solve on the submitting thread. Since 2026-10-14 (audit
ENG-7/ENG-14) a refusal no longer consumes the recompute slot: the old contract kept the stale
path and skipped the recompute, and it paid for the snapshot before learning the pool was full.
Capacity is now checked first, so a declined offload costs exactly what the feature-off path
costs (one synchronous A*) and nothing more; under saturation the region thread does the
vanilla work, not vanilla work plus a snapshot. This is a correctness/latency-cliff statement,
not a throughput claim; no measurement backs a number here.

`/perf async` exposes refused submissions. A rising refusal count means the async feature is at
capacity and needs workload-specific investigation; it does not justify adding threads blindly.
