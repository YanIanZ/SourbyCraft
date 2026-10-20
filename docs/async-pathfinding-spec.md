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
thread**, never from the live level — and, since 2026-10-20 (audit ENG-8), the same holds for
the mob: the worker reads an immutable `MobPathSnapshot`, never the live `Mob`.

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

2b. **`MobPathSnapshot`** (`dev.iyanz.sourbycraft.perf`, audit ENG-8) — every mob member the
   solve reads, captured on the owner at submit time (`MobPathSnapshot.captureDetached(mob)` in
   `PathNavigation.sourbyKickAsyncRecompute`). The worker is handed
   `PathFinder.findPath(SnapshotPathRegion, MobPathSnapshot, ...)`, which runs
   `NodeEvaluator.prepare(region, snapshot)`: the evaluator's `mob` field is `null` for the whole
   solve and every former `this.mob.…` read in `WalkNodeEvaluator`, `FlyNodeEvaluator`,
   `SwimNodeEvaluator`, `AmphibiousNodeEvaluator` and `Frog.FrogNodeEvaluator` is
   `this.subject().…`. The supplier submitted to the pool captures the solver, the two snapshots,
   the target set and three scalars; the `Mob` reference is captured only by the completion
   lambda, which runs on the owner. See "What the snapshot holds" below.

   The synchronous solve uses the same snapshot, captured lazily on the owner at the first
   `subject()` call (after the whole `prepare` chain, so `onPathfindingStart` and
   `AmphibiousNodeEvaluator`'s malus writes are included). On the owner the captured values are
   what vanilla reads live, the mob's own `RandomSource` is kept (`capture`, not
   `captureDetached`) and collision still goes through `noCollision(liveMob, box)`, so the
   feature-off path is unchanged in observable behaviour (`MobPathSnapshotTest`
   `theSynchronousSolveSeesWhatVanillaWouldAndKeepsTheMobsRandom`).

3. **Periodic recompute only** — `PathNavigation.recomputePath` offloads only when the mob already
   has a live, unfinished path. Snapshot + event validation happen on the owning region thread;
   the A* solve runs on the worker; completion is handed back through the entity owner scheduler.
   A path identity token prevents a stale result from overwriting a newer retarget/stop/recompute.

4. **Config**: `aurora.entity.async-pathfinding` (default `false`), with legacy
   `perf.ai.async-pathfinding` compatibility where still supported. When false, the vanilla
   synchronous path remains the active behavior.

## What the snapshot holds (ENG-8 member trace, 2026-10-20)

Traced from `PathFinder.findPath` → `NodeEvaluator.prepare` → `getStart`/`getNeighbors`
(materialized `sourbycraft-server/src/minecraft/java`, lines as of commit 1fc895f):

| Live member read by the solve | Where | Snapshot field |
|---|---|---|
| `getX/getY/getZ`, `getBlockY` | `WalkNodeEvaluator` 55-70, 83, 193-195; `FlyNodeEvaluator` 39-52 | `x,y,z`, `blockY` |
| `blockPosition()` | `NodeEvaluator` 27 (`PathfindingContext` 30), `WalkNodeEvaluator` 89, 433 | `blockPosition` |
| `getBoundingBox()` | `WalkNodeEvaluator` 91, 193; `FlyNodeEvaluator` 317; `SwimNodeEvaluator` 40; `AmphibiousNodeEvaluator` 42; `Frog` 362 | `boundingBox` |
| `getBbWidth/getBbHeight` | `NodeEvaluator` 30-32; `WalkNodeEvaluator` 173-174, 314, 324, 330 | `bbWidth`, `bbHeight` |
| `isInWater()`, `onGround()` | `WalkNodeEvaluator` 58, 67; `FlyNodeEvaluator` 38; `AmphibiousNodeEvaluator` 38; `Frog` 358 | `inWater`, `onGround` |
| `canStandOnFluid(FluidState)` (`LivingEntity` 3118, `Strider` 180) | `WalkNodeEvaluator` 57, 82; `EntityCollisionContext` 62 | `standsOnFluid[]` per fluid type |
| `getPathfindingMalus(PathType)` (`Mob` 186-196: `EnumMap` 148 + controlled-vehicle inheritance) | `WalkNodeEvaluator` 106, 112, 126, 242, 345, 359, 396-414; `FlyNodeEvaluator` 67, 270; `SwimNodeEvaluator` 89; `AmphibiousNodeEvaluator` 58 | `pathfindingMalus[]` resolved on the owner |
| `maxUpStep()` (`LivingEntity` 4941: attribute + controlling passenger) | `WalkNodeEvaluator` 127, 274; `AmphibiousNodeEvaluator` 59 | `maxUpStep` |
| `getMaxFallDistance()` (`Mob` 937: target, health, difficulty) | `WalkNodeEvaluator` 354 | `maxFallDistance` |
| `level().getMinY()` | `WalkNodeEvaluator` 339, 353 | read from the region (`PathNavigationRegion.getMinY` is the level's) |
| `level().getSeaLevel()` | `AmphibiousNodeEvaluator` 77 | `seaLevel` |
| `getRandom()` | `FlyNodeEvaluator` 333 | forked `RandomSource` seeded by one owner-side draw |
| `noCollision(mob, box)` → `isDescending()`, `getY()`, `getMainHandItem()`, border `getX/getZ` | `WalkNodeEvaluator` 373 → `CollisionGetter` 51-110, `EntityCollisionContext` 39-46, `WorldBorder` 130-132 | `descending`, `y`, `heldItem` copy; `MobPathSnapshot.noCollision` |
| `canPassDoors/canOpenDoors/canFloat/canWalkOverFences` | `NodeEvaluator` flags | on the isolated evaluator (ENG-2, unchanged) |

Still refused rather than snapshotted (the navigation runs the synchronous recompute):

- `Mob.sourbyPathfindingMutatesMob()` — `Sniffer` writes malus from `onPathfindingStart/Done`;
  an off-thread solve does not invoke the callbacks at all (`NodeEvaluator.prepare(region,
  snapshot)` has no mob), so a mob that depends on them keeps the synchronous path.
- `AmphibiousPathNavigation.sourbyAsyncSolveSafe() == false` — `AmphibiousNodeEvaluator.prepare`
  writes three malus entries on the live mob; `done()` is now guarded for a null mob but the
  offload stays refused.

Not held in `MobPathSnapshot` itself, but captured the same way (on the owner, read only by the
off-thread solve) is the Creaking's home position (ENG-8 follow-up, MC feature patch 0042):

- `Creaking.CreakingPathNavigation.createPathFinder` writes `Creaking.this.getHomePos()` onto the
  new `HomeNodeEvaluator.sourbyHomePos`. `sourbyKickAsyncRecompute` calls it on the owning thread
  through `sourbyIsolatedPathFinder` for every submission, so each off-thread solve gets a fresh
  evaluator carrying the home as of that submission; the live evaluator is not touched.
- `HomeNodeEvaluator` overrides `prepare(region, MobPathSnapshot)` to set a `sourbySnapshotSolve`
  flag and `done()` to clear it; `getPathType` reads `sourbyHomePos` only while that flag is set,
  and calls `getHomePos()` live otherwise. The snapshot overload of `prepare` is reached only from
  `PathFinder.findPath(region, MobPathSnapshot, …)`, whose one caller is the async submission; the
  flag lives on an evaluator confined to the worker that runs that solve. The first version keyed
  this on `this.mob == null`, which is also true on the live evaluator after `done()` and before any
  `prepare`, so owner-thread queries without a prepare (`MoveControl.isWalkable` →
  `getPathType(Mob, BlockPos)`) read the construction-time capture instead of the current home.
- That was the one synched member outside the `Mob` API the solve reads, so
  `CreakingPathNavigation` no longer overrides `sourbyAsyncSolveSafe()` to refuse the offload.

Known approximations of the off-thread path (the synchronous path has none of these):

- `canStandOnFluid` is tabulated per registered fluid type from the mob's answer for that fluid's
  default state. Exact for the vanilla overrides (they test a fluid tag); an override that
  depends on fluid level would be approximated.
- The snapshot collision context has no entity, so `PowderSnowBlock` and `LiquidBlock` shapes
  resolve entity-less (powder snow never solid, a Strider's lava never a floor) inside
  `hasCollisions`, which only affects partial-collision nodes (fence/door squeeze) and the
  small-mob jump check. Same class of inaccuracy as the region snapshot's missing block entities.
- The mob's position is as of submit, which is what a periodic recompute always races.

Evidence: `MobPathSnapshotTest` (a `Mob` fake sealed after capture fails on any call from any
thread; the worker solve completes with zero calls; the queued solve's node list is unchanged
after the mob moves, grows and blocks `WALKABLE`, while a fresh capture differs; `findPath(region,
mob, …)` equals `findPath(region, capture(mob), …)`; fluid table; border test from the captured
position). `AsyncPathEvaluatorIsolationTest` covers the Creaking home over a stubbed flat floor
with real A* solves (a mocked `Creaking` supplies only `getHomePos()`; solvers are built through
`sourbyIsolatedPathFinder`, not through `sourbyKickAsyncRecompute`; no server, no real Creaking
entity, no goal or `MoveControl` path): the navigation no longer refuses the offload; two isolated
solvers built after the home changes each capture the home current at their build while the live
evaluator, its capture and the synchronous `PathFinder` stay unchanged; solves on a worker thread
prepared with a `MobPathSnapshot` follow the captured home (reachable target with no home
captured, unreachable with a far one) while any live `getHomePos()` call fails the test; the
synchronous solve follows home changes made after construction; and the live evaluator's
`getPathType` on the owner reads the current home both before any prepare and after a synchronous
and a snapshot solve. The last two tests fail when the discriminator is reverted to
`this.mob == null`. `AsyncPathRefusalTest` remains green. Not covered: a flag-on server soak with
Creakings.

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
