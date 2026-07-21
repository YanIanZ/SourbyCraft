# SourbyCraft — Async Pathfinding (Phase 1a of the async-offload MT uplift)

Status: foundation in progress. Flag **OFF by default** until a live mob-behaviour test pass signs off.

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
   NOT virtual threads). Submits `() -> pathFinder.findPath(snapshot, ...)`. Each worker owns
   its own `PathFinder`/`NodeEvaluator` (they hold reusable mutable A* buffers — never share
   across threads).

3. **`AsyncPath` (Path subclass)** — returned immediately from `createPath` in async mode,
   flagged `processing`. `PathNavigation.tick()` / `followThePath()` no-op while
   `!path.isProcessed()`. On completion the worker schedules the fill+swap on the mob's
   `EntityScheduler` (region thread), so the live path state is only ever mutated on the owning
   thread. Callbacks that need the finished path (`moveTo` post-processing) run in that same
   apply step.

4. **Config**: `perf.ai.async-pathfinding` (default `false`). When false, `createPath` is the
   exact vanilla synchronous path — zero new surface on the hot path.

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
