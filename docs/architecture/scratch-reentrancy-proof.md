# Entity-owned scalar scratch: reentrancy proof (DEV-A)

Status: **every entity-owned row is SAFE with evidence after one small fix (F1); the task owner
decides whether that closes `docs/DEVELOPMENT-TASKS.md:32` / `TODO.md:245`.** What is NOT covered
is listed in §3 (engine-baseline scratch, runtime/soak evidence, the chunk-system and NBT rows), and
the F1 fix is a materialized-repo commit whose feature patch has not been regenerated yet. Written
2026-10-20 by fable-fsync against branch `26.2`, feature patches `0001`–`0043`, materialized MC repo
`ced82b6` plus the F1 commit.
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

Proof technique: for R1, the set of call instructions between a scratch write and its read is taken
from the **bytecode** of the compiled server (`ScratchReentrancyTest`, ASM): a method whose only
calls are field getters cannot be re-entered, whatever a plugin registers. For R2, the readers are
enumerated from the materialized sources and each is placed on a thread using Folia's ownership
rules (`TickThread.ensureTickThread` guards, the regionizer's merge radius, the entity handoff).

## 1. Inventory

Every mutable field a SourbyCraft feature patch adds to or reuses on an entity-owned object, found
by `rg '^\+\s+(private|protected|public|volatile)[^(]*;'` over `minecraft-patches/features/*.patch`
plus the per-patch reads below. Rows marked *adjacent* are not entity-owned but are per-tick scratch
on objects a region owns; they are listed so the inventory is complete.

| # | Field | Patch | Owner object | Writers | Readers | Re-entry paths examined | Verdict | Evidence |
|---|-------|-------|--------------|---------|---------|-------------------------|---------|----------|
| F1 | `lastKnownSpeedX/Y/Z` (3 doubles replacing `Vec3 lastKnownSpeed`) + upstream `lastKnownPosition` | 0009 | `Entity` | `computeSpeed()` — `Entity.baseTick:964`, `AbstractMinecart.tick:309` | `hasMovedHorizontallyRecently()` ← `FollowPlayerRiddenEntityGoal.canUse/canContinueToUse` (Dolphin, reads **another** entity: the followed player); `getKnownSpeed()` ← `SulfurCube:825` (colliding player), `KineticWeapon:84` (attacker / root vehicle), `ServerPlayer.getKnownSpeed:3663` (own vehicle) | R1: calls inside `computeSpeed` = {`position()`}, a field read — **no re-entry possible**. R2: owner thread writes; `getKnownSpeed` readers are all within collision/passenger distance (same region, §2.1); `hasMovedHorizontallyRecently` reader `canContinueToUse` has **no distance bound** — a dolphin following a player-ridden boat can read the player's three doubles from another region | R1 **SAFE** (bytecode). R2: before this work **UNSAFE-benign** (§2.1: a torn x/z pair was possible where upstream's `Vec3` reference could not tear; consequence one wrong boolean for one goal evaluation; not reproduced by the probe). **Fixed** — the cross-region reader now reads one derived field (§2.1) → **SAFE** | `ScratchReentrancyTest.computeSpeedCannotBeReenteredBetweenItsStores`, `theReadersOfTheSpeedScalarsOnlyReadFields`, `theCrossRegionReaderReadsASingleField`, `speedScalarsMatchTheVec3Arithmetic`, `aConcurrentReaderNeverSeesATornHorizontalSpeed` (smoke) |
| F2 | `isInWallScratch` (`BlockPos.MutableBlockPos`) | 0012 | `Entity` | `isInWall()` (`Entity:3142`) — `setY/setZ/setX` per iteration | `isInWall()` only: `isSuffocating(world, scratch)`, `getCollisionShape(world, scratch)` | R1: the 28 calls the loop makes are scalar getters, section lookups and block-state queries with no entity and no `CollisionContext` (allowlist pinned in bytecode); the only user code reachable is a block's `isSuffocating` predicate (`Blocks::always`, `Blocks::never`, `NOT_CLOSED_SHULKER`, `NOT_EXTENDED_PISTON`, default `blocksMotion && isCollisionShapeFullBlock`) and `getCollisionShape(level,pos)` overrides — none ticks an entity or fires an event. Every coordinate is set before the first read of each iteration (bytecode). R2: sole caller `LivingEntity.baseTick:486` via `LivingEntity.isInWall:4790`, owner thread only; no other caller in the tree | **SAFE** (R1 by pinned call surface + predicate inventory; R2 by single owner-thread caller) | `isInWallCallsNothingThatCanReenterWhileItsScratchIsLive`, `theScratchPositionIsOwnerConfinedAndFullySetBeforeEveryRead`; `ScratchBufferConfinementTest.remainingPositionScratchIsAnOwnerConfinedInstanceField` |
| F3 | `sourbyAsyncPathPending` (`volatile boolean`) | 0006 | `PathNavigation` (per mob) | owner: `sourbyKickAsyncRecompute` (`true` before submit, `false` on refused submit); `AsyncPathCompletion.deliver` release callback (owner thread via `EntityScheduler`, or retirement) | owner: `recomputePath` | R1: `EntityPathfindEvent.callEvent()` runs **before** the flag is set and before `requested = this.path` is captured; a listener calling `stop()`/`moveTo` is seen by the token (§2.3). R2: written and read only on the owner; delivery is handed to the owner by `RegionOwnerHandoff` | **SAFE** — and, for a listener that re-enters the navigation during the event, equivalent to upstream (§2.3) | `AsyncPathValidity` javadoc; `AsyncPathCompletion.deliver` contract ("release before apply, exactly once") |
| F4 | `NodeEvaluator.mob` (now `@Nullable`), `NodeEvaluator.subject` (`MobPathSnapshot`) | 0039 | `NodeEvaluator` (one per navigation, plus one per off-thread solve) | `prepare(level, Mob)` / `prepare(level, snapshot)` / `done()`; `subject()` lazily | the evaluator's own `getPathType*` during a solve | R1: a sync solve is `PathFinder.findPath` → `prepare` … `done` with no event inside; `onPathfindingStart/Done` are the only mob callbacks and the mobs that override them refuse the offload (`sourbyPathfindingMutatesMob`). R2: the off-thread solve uses an **isolated** evaluator built on the owner (`sourbyIsolatedPathFinder`), published to the worker by the executor submission; the live evaluator never leaves the owner | **SAFE** | `AsyncPathEvaluatorIsolationTest` (ENG-2: live evaluator restored, isolated one distinct, flags copied); `MobPathSnapshotTest` |
| F5 | `HomeNodeEvaluator.sourbyHomePos`, `sourbySnapshotSolve` | 0042 | `Creaking.HomeNodeEvaluator` | `createPathFinder` (owner) writes the home; `prepare(level, snapshot)` / `done()` toggle the flag | `getPathType` | R1: owner-thread `getPathType` queries without `prepare` (e.g. `MoveControl.isWalkable`) read the live home because the flag is explicit, not `mob == null`. R2: the flag and home live on the isolated evaluator for an off-thread solve; the live evaluator's flag is never set | **SAFE**, with the open Codex P2 on the same file noted in §2.4 | `AsyncPathEvaluatorIsolationTest` (ENG-8 Creaking cases) |
| A1 *adjacent* | `sourbySkipComputedBits`, `sourbySkipBits` (two longs) | 0003 | `NaturalSpawner.SpawnState` (one per region per spawn pass; retained in `RegionizedWorldData.lastSpawnState` by 0014) | `sourbyCanCategorySpawnAnywhere` (lazy, once per category per state) | same method | R1: computed from `level.getLocalPlayers()` + `ChunkMap.getMobCountNear` — no event, no callback; the spawn pass that uses the state fires `PreCreatureSpawnEvent`/`CreatureSpawnEvent` **after** the bits are read, and a listener cannot re-enter `NaturalSpawner.spawnForChunk` for the same region tick. R2: `SpawnState` is created and read on the region tick thread; 0014 keeps the previous state only for `/debug`-style reads on that same thread | **SAFE** (not entity-owned; listed for completeness) | patch text; `0014` hunk `lastSpawnState` |
| A2 *out of scope* | `generationQueuedAt`, `measuredGeneration` | 0026 | `ChunkUpgradeGenericStatusTask` | task constructor / `run` | `run` | Chunk-system task object, written at schedule and read at run on the scheduler's threads; not entity scratch | not assessed here | — |
| A3 *out of scope* | `sourbycraft$nbtDepth` | 0038 | `NbtAccounter` | decoder | decoder | per-connection Netty state | not assessed here | — |
| — | `PathfindingContext.mutablePos` (upstream field) | 0006/0039 | `PathfindingContext` (one per `prepare`) | `getPathTypeFromState` | same | An off-thread solve gets its **own** context (`new PathfindingContext(level, blockPosition)`, no shared cache); never shared with the owner's context | **SAFE** (not a SourbyCraft field; listed because 0039 creates it for the worker) | 0039 hunk `PathfindingContext` |
| — | `optimiseRandomTick` `MutableBlockPos` | 0010 | method local on `ServerLevel` | — | — | A local, not a field; each activation has its own | **SAFE** (not scratch state) | `threading.md` §"0012 random-tick" (patch renumbered to 0010) |

Patches without any such field: 0007 (elytra: locals only), 0011, 0017 and 0018 (six hoisted
`double` locals each), 0005 (immutable `SnapshotPathRegion`), 0015, 0016.

## 2. Arguments

### 2.1 F1 — `lastKnownSpeedX/Y/Z`

**R1.** `Entity.computeSpeed()` compiles to: null-check and store of `lastKnownPosition`, one
`invokevirtual Entity.position()`, three `putfield` of the doubles, one `putfield` of the position.
`position()` is `return this.position;` and no subclass in the tree declares `position()`
(`LivingEntity`, `Mob`, `ServerPlayer`, `AbstractMinecart` checked reflectively; `rg "Vec3 position\(\)"`
finds only `Entity.java:6387`). Nothing between the three stores can run foreign code, so a
callback, event or listener cannot observe or interleave with a half-written triple on the writing
thread. The readers `hasMovedHorizontallyRecently` (calls: `Math.sqrt`) and `getKnownSpeed` (calls:
`getControllingPassenger`, `isAlive`, `Player.getKnownSpeed`, `Vec3.<init>`) read the fields and
return; they hold no scratch. `ScratchReentrancyTest` pins all of this on the bytecode, so a later
patch that adds a call into `computeSpeed` or `position()` fails the test.

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

**Fix status — applied (small).** `computeSpeed` now also stores `lastKnownSpeedHorizontalSq =
x² + z²` (one more `double` on the entity, one more store per tick), and
`hasMovedHorizontallyRecently` reads that single field — same `Math.sqrt(sum) > 1.0E-5D` arithmetic,
the sum formed from the same two doubles, so results are bit-identical on one thread. A cross-region
reader now reads one field: stale or fresh, never mixed, which is the guarantee the `Vec3` reference
gave. `getKnownSpeed()` still reads three fields; its readers are same-region (above). The change is
a commit in the materialized MC repo (`sourbycraft-server/src/minecraft/java`, Entity.java; sha
419d852) that becomes a feature patch on the next `rebuildMinecraftFeaturePatches`, which this
work did not run (another agent owned the repo's rebuilds). Test:
`theCrossRegionReaderReadsASingleField` (bytecode: exactly one `getfield`, `lastKnownSpeedHorizontalSq`);
mutation-checked by restoring the pre-fix `Entity.java` (the test fails: four field reads).

**What the race probe does and does not show.** `aConcurrentReaderNeverSeesATornHorizontalSpeed`
runs a writer alternating speeds (1,0,0)/(0,0,1) against a reader of
`hasMovedHorizontallyRecently` for 1.5 s on a Mockito-instantiated `Entity`. On Apple M2 it
managed ~35k writes and ~96k reads (reflection and the inline mock dispatch cost tens of
microseconds per call), so it samples a nanosecond store window every ~15 µs: it observed **no**
torn read on the pre-fix code either. It is a smoke test that would catch a gross regression (a
reader that reads two fields and a writer that stores them far apart), not a demonstration of the
interleaving. The R2 verdict rests on the memory model and the single-field bytecode test, not on
this probe. With the fix the verdict for F1 is **SAFE**: R1 by bytecode, R2 because the only
cross-region-reachable reader reads one field and every other reader is same-region.

### 2.2 F2 — `isInWallScratch`

The loop in `Entity.isInWall` (`Entity.java:3142-3261`) sets `Y`, then `Z`, then `X` on the scratch
before the first `isSuffocating` of every innermost iteration, so no coordinate ever survives from a
previous activation into a read; a re-entrant activation would therefore only matter if it existed.
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

### 2.3 F3 — `sourbyAsyncPathPending` and the validity token

`recomputePath` calls `sourbyKickAsyncRecompute` only when `path != null && !path.isDone() &&
!sourbyAsyncPathPending`. Inside, `EntityPathfindEvent.callEvent()` runs first; `requested =
this.path` is captured **after** it, then the flag is set and the solve submitted. A listener that
re-enters the navigation during the event:

* `stop()` → `path = null`; the token becomes `null`; on delivery `stillRequested(this.path, null)`
  is `true` only if nothing has assigned a path since, and the result is applied. Upstream's
  synchronous `createPath` assigns its result after the event too, so a `stop()` made inside the
  event is overwritten in both; the async form merely applies it a few ticks later.
* `moveTo(other)` → `path = P2` (synchronous `createPath` inside the listener); the token is `P2`;
  on delivery the old-target result replaces `P2` — again what upstream does (`this.path =
  this.createPath(this.targetPos, …)` after the event overwrites the listener's path).
* A listener that cancels the event makes the kick return `false` and the synchronous recompute
  runs, as upstream.

The flag itself is written only on the owner (`true` before submit, `false` on refusal, and by the
`release` callback that `AsyncPathCompletion.deliver` runs on the owner before `apply`, exactly once
on every path including retirement). **SAFE**, with the caveat that "equivalent to upstream" is the
standard applied, not "a listener's `stop()` during the event wins".

### 2.4 F4/F5 — evaluator state and the Creaking home

Covered by `AsyncPathEvaluatorIsolationTest` and the 0039/0042 design (an isolated evaluator per
off-thread solve, the live one never leaving the owner, the home captured on the owner at
`createPathFinder`). The Codex review of patch 0042 (AGENT-COORDINATION.md, 2026-10-20: "P2 sync
getPathType after done() uses stale snapshot (Creaking.java:617)") is tracked by the patch's owner
and is a staleness question on the live evaluator, not a re-entry or cross-thread write; it does
not change the verdict here but is why the row is not called "closed by this document".

## 3. What this document does not establish

* Anything about scratch state in the engine baseline (`minecraft-patches/sources/`): only
  SourbyCraft feature patches were inventoried, per the task.
* Runtime behaviour under a live server, a teleport or a region split: the R2 arguments are from
  Folia's ownership rules and the regionizer's merge radius, not from a soak.
* Rows A2/A3 (chunk-system task and NBT accounting fields) are not entity scratch and were not
  assessed.
* Mockito-instantiated `Entity` objects have no level; the behavioural tests exercise
  `computeSpeed`/`hasMovedHorizontallyRecently`/`getKnownSpeed` only.
