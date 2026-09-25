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
   own `PathFinder`/`NodeEvaluator` (mutable A* buffers are never shared across threads).
   Saturation uses **backpressure**: a full queue refuses the periodic recompute instead of
   CallerRuns. The mob keeps its current valid path and can retry on a later recompute.

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
worker capacity available -> solve asynchronously
queue full               -> refuse this periodic recompute
refused result            -> keep current live path
later recompute           -> may try again
shutdown/failure          -> refuse admission
```

A rejected solve must **never** be executed on the submitting region thread. This is a reliability
rule, not a throughput claim: snapshot creation has already consumed region-thread work, so adding
a full synchronous A* after rejection creates positive feedback exactly when the server is loaded.

`/perf async` exposes refused submissions. A rising refusal count means the async feature is at
capacity and needs workload-specific investigation; it does not justify adding threads blindly.
