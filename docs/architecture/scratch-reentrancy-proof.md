# Entity-owned scalar scratch: reentrancy proof (DEV-A)

Status: **every entity-owned row is SAFE with the evidence and limits stated below, after three
fixes (F1 publication, F1 threshold, F3 validity); the task owner decides whether that closes
the `docs/TODO.md` item "Bukti reentrancy scratch milik entity (DEV-A)"** (`docs/DEVELOPMENT-TASKS.md`,
cited here originally, no longer exists). What is NOT covered is listed in §3. Written
2026-10-20 by fable-fsync against feature patches `0001`–`0043` (F1 fix = patch 0044); revised the
same day by opus-q after an independent Codex review found four problems (§4), fixed in MC feature
patches **0046** (MC `ae724d6`, F1) and **0047** (MC `6d0eabb`, F3; amended after a Codex
re-review, §4), all three regenerated as feature patches. Re-checked 2026-10-25 against the Build 50
branch (feature patches up to 0087, static reading only; see the end of §1, §2.3 and §3).
Predecessors: [threading.md](threading.md) (collection scratch, 2026-09) and
[reuse-audit.md](reuse-audit.md) §6 ("reachability, not proof"). The collection scratches those
reviewed (`collisionScratch*`, `itemEntitiesScratch`, `effectParticlesScratch`) were removed (SPEC
B14); `ScratchBufferConfinementTest` pins their absence. This document covers what is left: scalar
and position scratch state kept on entity-owned objects.

The two re-entry shapes asked for:

* **R1 — same thread, re-entered mid-computation**: a method that writes scratch state, then calls
  out (event, plugin listener, recursive collision/pushing, passenger handling), and the callee
  reaches the same method on the same entity before the first activation read its scratch back.
* **R2 — two threads**: the same entity touched from two region threads, during a region transition
  or teleport, or because another region's entity reads it.

Proof technique: for R1, the set of call instructions in a method that holds scratch live is taken
from the **bytecode** of the compiled server (`ScratchReentrancyTest`, ASM). These are **direct-call
guards**: they fail when a call is added to that method, and they do not follow callees. That a
listed callee acquires no callback is either checked separately (named below where it is) or rests
on the manual callee audit in §2; a callee could gain a callback without changing its signature and
the guard would still pass. The `position()` override check covers four subclasses (`LivingEntity`,
`Mob`, `ServerPlayer`, `AbstractMinecart`) plus the source search in §2.1, not every subclass by
reflection. Publication is asserted separately (the cross-region field is `volatile`), and the
F2 coordinate property is checked by a must-analysis over the method's real control-flow graph
(§2.2). For R2, the readers are enumerated from the materialized sources and each is placed on a
thread using Folia's ownership rules (`TickThread.ensureTickThread` guards, the regionizer's merge
radius, the entity handoff).

## 1. Inventory

Every mutable field a SourbyCraft feature patch adds to or reuses on an entity-owned object, found
by `rg '^\+\s+(private|protected|public|volatile)[^(]*;'` over `minecraft-patches/features/*.patch`
plus the per-patch reads below. Rows marked *adjacent* are not entity-owned but are per-tick scratch
on objects a region owns; they are listed so the inventory is complete.

| # | Field | Patch | Owner object | Writers | Readers | Re-entry paths examined | Verdict | Evidence |
|---|-------|-------|--------------|---------|---------|-------------------------|---------|----------|
| F1 | `lastKnownSpeedX/Y/Z` (3 doubles replacing `Vec3 lastKnownSpeed`) + upstream `lastKnownPosition` | 0009 | `Entity` | `computeSpeed()` — `Entity.baseTick:964`, `AbstractMinecart.tick:309` | `hasMovedHorizontallyRecently()` ← `FollowPlayerRiddenEntityGoal.canUse/canContinueToUse` (Dolphin, reads **another** entity: the followed player); `getKnownSpeed()` ← `SulfurCube:825` (colliding player), `KineticWeapon:84` (attacker / root vehicle), `ServerPlayer.getKnownSpeed:3663` (own vehicle) | R1: calls inside `computeSpeed` = {`position()`}, a field read — **no re-entry possible**. R2: owner thread writes; `getKnownSpeed` readers are all within collision/passenger distance (same region, §2.1); `hasMovedHorizontallyRecently` reader `canContinueToUse` has **no distance bound** — a dolphin following a player-ridden boat can read the player's three doubles from another region | R1 **SAFE** (direct-call guard + `position()` audit). R2: before 0044 **UNSAFE-benign** (x/z of different ticks); 0044's plain derived `double` was still not atomically published (JLS 17.7). **Fixed in 0046** — the cross-region reader reads one `volatile boolean` (row F1b) → **SAFE** for that reader; `getKnownSpeed` readers same-region (§2.1) | `ScratchReentrancyTest.computeSpeedCannotBeReenteredBetweenItsStores`, `theReadersOfTheSpeedScalarsOnlyReadFields`, `speedScalarsMatchTheVec3Arithmetic`, `aConcurrentReaderNeverSeesATornHorizontalSpeed` (smoke only) |
| F1b | `lastKnownMovedHorizontally` (`volatile boolean`; replaces 0044's plain `double lastKnownSpeedHorizontalSq`) | 0046 | `Entity` | `computeSpeed()` on the owner: `Math.sqrt(x*x + z*z) > 1.0E-5F` (upstream's arithmetic and **float** threshold), stored only when the value changes | `hasMovedHorizontallyRecently()` — one `getfield`, no call; cross-region from `FollowPlayerRiddenEntityGoal` (Dolphin) | R1: as F1. R2: a single volatile boolean cannot tear and is visible to the reader; the reader sees the predicate of the latest or an earlier tick, never a mixture — the guarantee upstream's `Vec3` reference gave | **SAFE** | `theCrossRegionReaderReadsASingleField` (bytecode: exactly one `getfield lastKnownMovedHorizontally:Z`), `theCrossRegionPredicateIsVolatile` (reflection: `volatile`, and the old plain-double field gone), `theMovementThresholdIsUpstreamsFloatAtTheBoundary`, `theMovementPredicateMatchesUpstreamOverManyDisplacements` (2,000 random displacements vs upstream's formula) |
| F2 | `isInWallScratch` (`BlockPos.MutableBlockPos`) | 0012 | `Entity` | `isInWall()` (`Entity:3142`) — `setY/setZ/setX` per iteration | `isInWall()` only: `isSuffocating(world, scratch)`, `getCollisionShape(world, scratch)` | R1: the 28 calls the loop makes are scalar getters, section lookups and block-state queries with no entity and no `CollisionContext` (direct-call guard); the only user code reachable is a block's `isSuffocating` predicate (`Blocks::always`, `Blocks::never`, `NOT_CLOSED_SHULKER`, `NOT_EXTENDED_PISTON`, default `blocksMotion && isCollisionShapeFullBlock`) and `getCollisionShape(level,pos)` overrides — none ticks an entity or fires an event (manual audit, §2.2). On every control-flow path from the method entry, X, Y and Z are each set before every scratch read (must-analysis over the CFG). R2: sole caller `LivingEntity.baseTick:486` via `LivingEntity.isInWall:4790`, owner thread only; no other caller in the tree | **SAFE** (R1 by direct-call guard + callee audit + CFG must-analysis; R2 by single owner-thread caller) | `isInWallCallsNothingThatCanReenterWhileItsScratchIsLive`, `everyPathSetsAllThreeScratchCoordinatesBeforeEachRead` (+ control `theMustAnalysisRejectsAPathThatSkipsASetter`), `theScratchPositionIsAnOwnerConfinedInstanceField`; `ScratchBufferConfinementTest.remainingPositionScratchIsAnOwnerConfinedInstanceField` |
| F3 | `sourbyAsyncPathPending` (`volatile boolean`) and, from 0047, `sourbyNavigationGeneration` (plain `int`) | 0006, 0047 | `PathNavigation` (per mob) | flag: owner (`true` before submit, `false` on refused submit) and the `AsyncPathCompletion.deliver` release callback, which runs on the owner on acceptance but **on the completing (worker) thread** when the handoff is refused (retired scheduler) — the flag is volatile, so that store is safe. Generation: owner only — `stop()` (always, even when the path is already null), `moveTo` assigning or clearing a path, `createPath` computing a new request (where it sets `targetPos`/`reachRange`), the synchronous recompute, an applied delivery | flag: owner `recomputePath`; generation: the delivery lambda, on the owner | R1: `EntityPathfindEvent.callEvent()` runs before the token and generation are taken. Before 0047 a listener's `stop()` made the token `null`, and a **later** `stop()` left it `null`, so `stillRequested(null, null)` passed and the result undid the later stop (Codex finding 2, §2.3). 0047: the delivery also requires the generation to be unchanged. R2: delivery is handed to the owner by `RegionOwnerHandoff`; a refused handoff never runs the apply | **SAFE for the invalidations enumerated in §2.3** after 0047, argued from the source and exercised on a model, not on the patched code; equivalent to upstream for a stop inside the event | `AsyncPathCancellationTest.aStopInsideThePathfindEventThenALaterStopIsNotUndoneByTheResult`, `aStopInsideThePathfindEventAloneIsOverwrittenByTheResultAsUpstreamDoes`, `aMoveToNullAfterAnEventStopAlsoInvalidatesTheResult`, `aRetargetWhoseRouteHasTheSameNodesStillMakesTheOldResultStale` (on `AsyncPathNavigationModel`, a model of the glue); `AsyncPathPatchShapeTest` pins 0047's text (generation taken with the token, checked after the path and before the content, six bumps incl. `stop()` and `createPath`); `AsyncPathValidityTest` |
| F4 | `NodeEvaluator.mob` (now `@Nullable`), `NodeEvaluator.subject` (`MobPathSnapshot`) | 0039 | `NodeEvaluator` (one per navigation, plus one per off-thread solve) | `prepare(level, Mob)` / `prepare(level, snapshot)` / `done()`; `subject()` lazily | the evaluator's own `getPathType*` during a solve | R1: a sync solve is `PathFinder.findPath` → `prepare` … `done` with no event inside; `onPathfindingStart/Done` are the only mob callbacks and the mobs that override them refuse the offload (`sourbyPathfindingMutatesMob`; since 2026-10-22 `AsyncPathRecompute.solveWritesMob`). R2: the off-thread solve uses an **isolated** evaluator built on the owner (`sourbyIsolatedPathFinder`), published to the worker by the executor submission; the live evaluator never leaves the owner | **SAFE** | `AsyncPathEvaluatorIsolationTest` (ENG-2: live evaluator restored, isolated one distinct, flags copied); `MobPathSnapshotTest` |
| F5 | `HomeNodeEvaluator.sourbyHomePos`, `sourbySnapshotSolve` | 0042 | `Creaking.HomeNodeEvaluator` | `createPathFinder` (owner) writes the home; `prepare(level, snapshot)` / `done()` toggle the flag | `getPathType` | R1: owner-thread `getPathType` queries without `prepare` (e.g. `MoveControl.isWalkable`) read the live home because the flag is explicit, not `mob == null`. R2: the flag and home live on the isolated evaluator for an off-thread solve; the live evaluator's flag is never set | **SAFE** (the Codex P2 on the same file, §2.4, is resolved by the explicit flag) | `AsyncPathEvaluatorIsolationTest` (ENG-8 Creaking cases) |
| A1 *adjacent* | `sourbySkipComputedBits`, `sourbySkipBits` (two longs) | 0003 | `NaturalSpawner.SpawnState` (one per region per spawn pass; retained in `RegionizedWorldData.lastSpawnState` by 0014) | `sourbyCanCategorySpawnAnywhere` (lazy, once per category per state) | same method | R1: computed from `level.getLocalPlayers()` + `ChunkMap.getMobCountNear` — no event, no callback; the spawn pass that uses the state fires `PreCreatureSpawnEvent`/`CreatureSpawnEvent` **after** the bits are read, and a listener cannot re-enter `NaturalSpawner.spawnForChunk` for the same region tick. R2: `SpawnState` is created and read on the region tick thread; 0014 keeps the previous state only for `/debug`-style reads on that same thread | **SAFE** (not entity-owned; listed for completeness) | patch text; `0014` hunk `lastSpawnState` |
| A2 *out of scope* | `generationQueuedAt`, `measuredGeneration` | 0026 | `ChunkUpgradeGenericStatusTask` | task constructor / `run` | `run` | Chunk-system task object, written at schedule and read at run on the scheduler's threads; not entity scratch | not assessed here | — |
| A3 *out of scope* | `sourbycraft$nbtDepth` | 0038 | `NbtAccounter` | decoder | decoder | per-connection Netty state | not assessed here | — |
| — | `PathfindingContext.mutablePos` (upstream field) | 0006/0039 | `PathfindingContext` (one per `prepare`) | `getPathTypeFromState` | same | An off-thread solve gets its **own** context (`new PathfindingContext(level, blockPosition)`, no shared cache); never shared with the owner's context | **SAFE** (not a SourbyCraft field; listed because 0039 creates it for the worker) | 0039 hunk `PathfindingContext` |
| — | `optimiseRandomTick` `MutableBlockPos` | 0010 | method local on `ServerLevel` | — | — | A local, not a field; each activation has its own | **SAFE** (not scratch state) | `threading.md` §"0012 random-tick" (patch renumbered to 0010) |

Patches without any such field: 0007 (elytra: locals only), 0011, 0017 and 0018 (six hoisted
`double` locals each), 0005 (immutable `SnapshotPathRegion`), 0015, 0016.

Re-check 2026-10-25 (same grep over feature patches 0044–0087, static reading): the entity-owned fields
found are those of 0044, 0046 and 0047, already rows F1, F1b and F3. The other added fields belong to
region, connection, command or server state, not to an entity: 0045 and 0049 (region task queue), 0050
(region file), 0052 (`RegionizedWorldData.sourbyEntityTicks`, written and zeroed on the region tick),
0055 (command-block execution), 0058 (`PrepareSpawnTask` join state), 0067 (persistent ticket storage), 0069
(data-command accessor), 0074 (server tick state), 0083 (global tick counter) and 0084 (relaxed thread
checks). No new entity-owned row.

## 2. Arguments

### 2.1 F1 — `lastKnownSpeedX/Y/Z`

**R1.** `Entity.computeSpeed()` compiles to: null-check and store of `lastKnownPosition`, one
`invokevirtual Entity.position()`, three `putfield` of the doubles, one `invokestatic Math.sqrt` and
a conditional `putfield` of the volatile predicate (0046), one `putfield` of the position.
`position()` is `return this.position;` and no subclass in the tree declares `position()`
(`LivingEntity`, `Mob`, `ServerPlayer`, `AbstractMinecart` checked reflectively; `rg "Vec3 position\(\)"`
finds only `Entity.java:6387`). Nothing between the three stores can run foreign code, so a
callback, event or listener cannot observe or interleave with a half-written triple on the writing
thread. The readers `hasMovedHorizontallyRecently` (no call: one field read) and `getKnownSpeed` (calls:
`getControllingPassenger`, `isAlive`, `Player.getKnownSpeed`, `Vec3.<init>`) read the fields and
return; they hold no scratch. `ScratchReentrancyTest` pins these direct call sets on the bytecode,
so a later patch that adds a call into `computeSpeed` or `position()` fails the test.

**R2.** The writer is the owner's tick (`Entity.baseTick`, `AbstractMinecart.tick`). A region
transition or teleport never ticks one entity on two threads: teleports are `ensureTickThread`
guarded (`Entity.java:4671, 4995, 5007, 5227`) and the entity is handed from one region's queue to
the other's, so the next writer sees every previous store. The readers:

* `getKnownSpeed()` — `SulfurCube:825` reads the player it is colliding with (bounding boxes
  intersect), `KineticWeapon:84` reads the attacker or its root vehicle, `ServerPlayer:3663` reads
  its own vehicle. All three pairs are within a few blocks or in one passenger chain. Folia keeps
  such pairs on one thread: a region merges every section within `emptySectionCreateRadius +
  regionSectionMergeRadius` sections of a chunk it owns (`ThreadedRegionizer.java:312-313`,
  constructed at `ServerLevel.java:724-732` with create radius `max(1, 8 >> shift)` sections and
  merge radius 1, section = `1 << TickRegions.getRegionChunkShift()` chunks), so two regions are
  never adjacent within a section, i.e. ≥ 16 blocks apart even at the smallest shift. **SAFE.**
* `hasMovedHorizontallyRecently()` — `FollowPlayerRiddenEntityGoal.canContinueToUse` (used by
  `Dolphin`) keeps reading `this.following.hasMovedHorizontallyRecently()` with **no distance
  check**; only `isPassenger()` and the boolean end the goal. A dolphin that falls behind a fast
  boat keeps reading the rider's speed from wherever the rider is. Once the rider is in another
  region, that is a cross-thread read of three separately stored doubles. Upstream reads one
  `Vec3` reference there: stale or fresh, never mixed. With three fields a reader can observe
  `x` from tick *n* and `z` from tick *n−1*. **This is a concrete interleaving upstream cannot
  produce.** Consequence: a wrong `true`/`false` for one evaluation of a follow goal — the dolphin
  stops or continues one tick early; nothing is corrupted and nothing crashes. The JLS caveat
  that non-`volatile` `double` stores may themselves tear (JLS 17.7) applies equally to every
  entity coordinate upstream reads this way and is not what this row is about.

**Fix status — applied in two steps.** Patch 0044 made the cross-region reader read one derived
`double`. The Codex review pointed out that a plain `double` is not atomically published (JLS 17.7
allows a non-`volatile` `double` to be written as two halves), so 0044 had not established "stale
or fresh, never mixed". Patch **0046** (MC `ae724d6`) replaces the derived double with
`private volatile boolean lastKnownMovedHorizontally`, decided in `computeSpeed` on the owner with
upstream's exact predicate — `Math.sqrt(x*x + z*z) > 1.0E-5F` (upstream:
`Math.abs(lastKnownSpeed.horizontalDistance()) > 1.0E-5F`; `horizontalDistance()` is that `sqrt`, and
`abs` of a `sqrt` changes nothing) — and `hasMovedHorizontallyRecently()` returns that one field.
A volatile boolean cannot tear and is visible to the reader without a further bound on staleness
than "an earlier tick's value". `getKnownSpeed()` still reads three fields; its readers are
same-region (above).

**Threshold (Codex finding 4).** 0009 and 0044 compared against `1.0E-5D`; upstream compares
against the float literal `1.0E-5F`, which widens to `9.999999747378752E-6`. A horizontal
displacement of exactly `1.0E-5` was therefore `true` upstream and `false` after 0009. 0046 restores
the float literal; `theMovementThresholdIsUpstreamsFloatAtTheBoundary` pins `1.0E-5` (true), the
widened threshold (false), and its `nextUp`/`nextDown`, and
`theMovementPredicateMatchesUpstreamOverManyDisplacements` compares 2,000 seeded displacements with
upstream's formula.

**Cost.** Per entity per tick: one `sqrt` (moved from the reader to the writer; a single hardware
instruction) and one volatile **read** of the boolean; the volatile **store** (a release store plus
a full fence on x86/ARM) happens only on the ticks where the predicate changes, i.e. when an entity
starts or stops moving horizontally. The entity loses one `double` (8 bytes) and gains a `boolean`.
Not benchmarked.

**What the race probe does and does not show.** `aConcurrentReaderNeverSeesATornHorizontalSpeed`
runs a writer alternating speeds (1,0,0)/(0,0,1) against a reader of
`hasMovedHorizontallyRecently` for 1.5 s on a Mockito-instantiated `Entity`. On Apple M2 it
managed ~35k writes and ~96k reads (reflection and the inline mock dispatch cost tens of
microseconds per call), so it samples a nanosecond store window every ~15 µs: it observed **no**
torn read on the pre-fix code either. It is a smoke test that would catch a gross regression (a
reader that reads two fields and a writer that stores them far apart), not a demonstration of the
interleaving. As the Codex review noted, its derived values (1.0 and, at wraparound, 5.0) have zero
low 32 bits, so it could not have exposed a half-written `double` either; with 0046 the reader reads
a boolean and the probe remains a smoke test only. The R2 verdict rests on the memory model (one
`volatile boolean`) and the bytecode/reflection tests, not on this probe. Verdict for F1/F1b:
**SAFE** — R1 by direct-call guard and the `position()` audit, R2 because the only
cross-region-reachable reader reads one volatile boolean and every other reader is same-region.

### 2.2 F2 — `isInWallScratch`

The loop in `Entity.isInWall` (`Entity.java:3142-3261`) sets `Y` in its outer loop, `Z` in the
middle one and `X` in the inner one; a read sees the enclosing loops' current coordinates. The
earlier test only checked that the three setters appear before the first `isSuffocating` in
instruction order, which a branch that bypasses a setter could pass (Codex finding 3).
`everyPathSetsAllThreeScratchCoordinatesBeforeEachRead` replaces it with a must-analysis over the
method's control-flow graph (fall-through, jumps, switches and exception edges): at every
`isSuffocating`/`getCollisionShape` call, every path from the method entry has set X, Y and Z at
least once; `theMustAnalysisRejectsAPathThatSkipsASetter` is its control (a hand-built method whose
branch skips `setZ` is reported). This proves the R1 property — no read in an activation can
observe a coordinate left over from an earlier activation — and not the functional correctness of
each read's coordinates, which is 0012's own concern. A re-entrant activation would only matter if
it existed.
The complete set of call instructions in the method is pinned by
`isInWallCallsNothingThatCanReenterWhileItsScratchIsLive`: scalar getters on the entity, `Mth.floor`,
`CollisionUtil.isEmpty(6 doubles)`, section lookups (`getChunk`, `getSections`, `hasOnlyAir`,
`getStates`, `PalettedContainer.get`), the three `set*` calls, and the block-state queries
`isSuffocating(BlockGetter, BlockPos)`, `getCollisionShape(BlockGetter, BlockPos)`,
`moonrise$emptyCollisionShape`, `moonrise$getSingleAABBRepresentation`, `VoxelShape.isEmpty`,
`voxelShapeIntersect*`, and `new AABB`. The only user-extensible calls are the two block-state
queries. `isSuffocating` dispatches to `BlockBehaviour.Properties.isSuffocating`
(`BlockBehaviour.java:965, 1175`), whose implementations in `Blocks.java` are `Blocks::always` (4),
`Blocks::never` (9), `NOT_CLOSED_SHULKER` (reads a block entity), `NOT_EXTENDED_PISTON` (a state
property) and the default `blocksMotion() && isCollisionShapeFullBlock(level, pos)`, which ends in
`getCollisionShape(level, pos)` with `CollisionContext.empty()` — no entity, no event. A plugin can
register neither a block predicate nor a collision shape. So no path from inside the loop leads to
`isInWall` on the same entity, or to any entity tick. R2: the only caller is `LivingEntity.baseTick`
through `LivingEntity.isInWall`, on the owner thread. The reuse-audit §6 capture analysis (a
`MutableBlockPos` handed to these consumers is read or copied with `immutable()`, never stored)
still holds for the same consumers. **SAFE.**

### 2.3 F3 — `sourbyAsyncPathPending`, the validity token and the navigation generation

`recomputePath` calls `sourbyKickAsyncRecompute` (since 2026-10-22 `perf/AsyncPathRecompute.offload`,
called from patch 0006) only when `path != null && !path.isDone() && !sourbyAsyncPathPending`. Since
*PR #62* (Build 50 branch; code written and statically reviewed, not built, not tested by the owner)
`offload` first returns `false` unless the calling thread owns the mob, so with relaxed thread checks a
foreign-thread recompute never writes the flag or takes the token; it runs the synchronous recompute. Inside, `EntityPathfindEvent.callEvent()` runs first; `requested =
this.path` and (0047) `requestedGeneration = this.sourbyNavigationGeneration` are taken **after** it,
then the flag is set and the solve submitted. On delivery (on the owner) the result is applied only
if the subject is the same entity, `this.path == requested`, **and** the generation is unchanged.

**The gap 0047 closes (Codex finding 2).** A listener calls `stop()` during the event, so the token
is `null`. Before delivery another owner-thread action calls `stop()` again (or `moveTo(null)`):
the path stays `null`, so the identity check `stillRequested(null, null)` passed and the result was
installed, undoing the later stop. Upstream's synchronous `createPath` would have installed its
result before that later stop, which then persists. The generation advances on every `stop()`
(even when the path is already null), on `moveTo` assigning or clearing a path, on the synchronous
recompute, and on an applied delivery, so the later stop is now seen.

**Retarget with an equal route (Codex re-review).** `moveTo(Path)` replaces the path only when
`!newPath.sameAs(this.path)`, and `Path.sameAs` compares node lists only. A retarget to B whose
route has the same nodes as the in-flight request for A therefore kept both the path object and,
in the first version of 0047, the generation; the old delivery passed and restored target A. 0047
(amended, MC `6d0eabb`) also advances the generation inside `createPath` where it takes a new
`targetPos`/`reachRange` from a computed path, so every new navigation request invalidates an
in-flight solve whether or not the path object changes. A `createPath` that returns the current
path (same target, path not done) is not a new request and does not advance it. The generation is a
32-bit counter: equality could repeat only after 2³² invalidations during one outstanding solve,
not considered further.

A listener that re-enters the navigation during the event, with nothing after it:

* `stop()` → token `null`, generation taken after the stop; on delivery both still match and the
  result is applied. Upstream's `createPath` assigns its result after the event too, so a `stop()`
  made inside the event is overwritten in both; the async form applies it a few ticks later.
* `moveTo(other)` → the token is `P2`; the old-target result replaces `P2` on delivery, as upstream's
  `this.path = this.createPath(this.targetPos, …)` after the event does.
* A listener that cancels the event makes the kick return `false` and the synchronous recompute
  runs, as upstream.

**Writers (corrected).** The earlier text said the pending flag is written only on the owner. Not
so: the release callback runs on the owner when the handoff is accepted, but when the owner refuses
it (a retired `EntityScheduler`), `AsyncPathCompletion.deliver` runs the release directly on the
thread completing the solve. That store is to a `volatile boolean` and nothing else is touched, so
it is safe; the apply never runs on a refused handoff. The generation is written and read on the
owner only.

Evidence and its limits: the behaviour tests run on `AsyncPathNavigationModel`, a hand-written
model of the patched glue (kick, delivery checks, `stop`, `moveTo` with `sameAs` semantics,
`createPath`'s bump) built on the real `AsyncPathProcessor`/`AsyncPathCompletion`/
`AsyncPathValidity`. It is not a line-by-line mirror and nothing checks it against the source
automatically: the first version modelled `moveTo` with reference inequality and so missed the
retarget case above. `AsyncPathPatchShapeTest` pins selected text of patches 0006 and 0047 (where
the token and generation are taken, the check order, the bump count and the `stop()`/`createPath`
bumps); it does not prove that no other invalidating path exists. The enumeration of navigation
writers (`stop`, `moveTo`, `createPath`, `recomputePath`, the delivery; no subclass assigns
`path`) is a source read of this branch. The patched lambda itself is not executed (it needs a
bootstrapped server and a real `Mob`). Since the move to `AsyncPathRecompute` (2026-10-22),
`AsyncPathRecomputeTest` runs `AsyncPathRecompute.deliver` and the live-evaluator restore on the real
`PathNavigation` methods (Mockito `CALLS_REAL_METHODS`, no world); the submit-and-complete lambda in
`offload` is still not executed. **SAFE for the enumerated invalidations**, with "equivalent
to upstream" as the standard; not a proof against writers added later.

### 2.4 F4/F5 — evaluator state and the Creaking home

Covered by `AsyncPathEvaluatorIsolationTest` and the 0039/0042 design (an isolated evaluator per
off-thread solve, the live one never leaving the owner, the home captured on the owner at
`createPathFinder`). The Codex review of patch 0042 (2026-10-20, recorded in the untracked agent
coordination file, which is not in the repository: "P2 sync getPathType after done() uses stale
snapshot (Creaking.java:617)") was a staleness question on the live evaluator, not a re-entry or
cross-thread write. **Resolved** in the current 0042: `HomeNodeEvaluator` reads the captured home only
while an explicit `sourbySnapshotSolve` flag is set, which only `prepare(region, MobPathSnapshot)` sets
and `done()` clears, so an owner-thread `getPathType` after `done()` reads the live home (spec, "What the
snapshot holds"; `AsyncPathEvaluatorIsolationTest`, whose last two cases fail when the discriminator is
reverted to `this.mob == null`).

## 3. What this document does not establish

* Anything about scratch state in the engine baseline (`minecraft-patches/sources/`, about 113
  entity patches): only SourbyCraft feature patches were inventoried, per the task. Still open (PM-251).
* Runtime behaviour under a live server, a teleport or a region split: the R2 arguments are from
  Folia's ownership rules and the regionizer's merge radius, not from a soak. A soak is owner work.
* R2 with relaxed thread checks: the R2 arguments place readers and writers with
  `TickThread.ensureTickThread` and the regionizer's ownership. With `aurora.bridge.mode = safe` and
  `aurora.bridge.thread-checks = relaxed` (the owner's default since 2026-10-22) or `bridged`, those
  checks are advisory: a plugin thread can reach an entity it does not own, and nothing here
  establishes R2 for that caller. Only the enforced mode (or the bridge off) keeps the R2 premise.
* Rows A2/A3 (chunk-system task and NBT accounting fields) are not entity scratch and were not
  assessed.
* Mockito-instantiated `Entity` objects have no level; the behavioural tests exercise
  `computeSpeed`/`hasMovedHorizontallyRecently`/`getKnownSpeed` only.
* The bytecode call-set tests are direct-call guards (§ proof technique): a callee that acquires a
  callback without a signature change is not caught by them; the callee audit is manual. The
  `position()` override check covers four named subclasses.
* The F3 fix is exercised through a hand-written model and pinned by selected patch-text checks,
  not by running the patched `PathNavigation` lambda; the writer enumeration is a source read.

## 4. Codex review (2026-10-20) and what changed

1. 0044's derived field was a plain `double` (JLS 17.7 tearing) → 0046: one `volatile boolean`,
   asserted by `theCrossRegionPredicateIsVolatile`.
2. F3's null token let a later `stop()` be undone → 0047: navigation request generation, checked on
   delivery; event-stop → later stop → completion tested. Re-review: a same-nodes retarget kept the
   generation (`Path.sameAs` ignores the target) → 0047 amended to bump in `createPath`; the model
   now uses `sameAs` semantics; same-nodes/different-target test added. F3 wording narrowed.
3. ASM tests overstated → described as direct-call guards; the linear-order coordinate check
   replaced by a CFG must-analysis with a control; publication asserted separately.
4. 0009 used `1.0E-5D` where upstream uses `1.0E-5F` → 0046 restores the float threshold, with
   boundary and randomized parity tests.
Also: the inventory gained row F1b for the derived field, and F3's "owner-only writers" claim was
corrected (refused-handoff release runs on the completing thread).
