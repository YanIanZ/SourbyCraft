# Async pathfinding — snapshot correctness audit (DEV-E, ROADMAP M5)

Source audit, 2026-10-20, of what an off-thread path solve reads and whether the snapshot it
reads from answers like the live world the synchronous solve reads. Scope: the block-side
snapshot `SnapshotPathRegion` (MC feature patch 0005, `dev/iyanz/aurora/level/`) and the way MC
feature patch 0006 builds and hands it to `PathFinder`. The mob-side snapshot (`MobPathSnapshot`)
was traced member by member on the same date in `docs/async-pathfinding-spec.md` ("What the
snapshot holds") and is only cross-checked here where the two meet (`noCollision`). Lines refer to
the materialized tree at the audit date. No server run backs this document; the feature is OFF
by default (`aurora.entity.async-pathfinding`) and stays so.

## What the solve reads from the region

Every region member a solve reaches, from `PathFinder.findPath` → `NodeEvaluator.prepare` →
`getStart` / `getNeighbors` and the block code those call (grep of
`net/minecraft/world/level/pathfinder/*.java` for reads on `level`, `region`, `currentContext`):

| Member | Callers | Vanilla `PathNavigationRegion` | `SnapshotPathRegion` | Verdict |
|---|---|---|---|---|
| `getBlockState(pos)` | `PathfindingContext.getBlockState` (48), `WalkNodeEvaluator` 56-83, 217, `SwimNodeEvaluator` 119-133, `FlyNodeEvaluator` 42-44, `BlockCollisions` 89 via `getChunkForCollisions` | out of build height → `AIR`; chunk from the grid, missing chunk → `EmptyLevelChunk` → **`VOID_AIR`** | out of build height → `AIR`; copied section → its state; missing chunk / empty section / out of grid → **`AIR`** | Same except `VOID_AIR` vs `AIR` identity for a missing chunk: benign, see finding 2 |
| `getBlockStateIfLoaded(pos)` | `WalkNodeEvaluator.getPathTypeFromState` 525 (every path-type classification; `null` → `BLOCKED`) | `null` for an unloaded or out-of-grid chunk (Paper "do not load chunks during pathfinding") | At audit time `AIR` (a `null` section entry could not tell unloaded from empty); since MC 0048 `null` for an unloaded or out-of-grid chunk, as vanilla | Divergence found here (`OPEN` instead of `BLOCKED`), **fixed in MC 0048** the same day; parity pinned by `SnapshotPathRegionTest` (parity test enabled), see finding 1 |
| `getFluidState(pos)` / `getFluidIfLoaded(pos)` | `WalkNodeEvaluator` 212, `SwimNodeEvaluator` 94, `LiquidBlock.getCollisionShape` (reads `pos.above()`) | `chunk.getFluidState` = section state's `getFluidState()`; `EMPTY` for a missing chunk | copied state's `getFluidState()`; `EMPTY` for a missing chunk | Same (`IfLoaded` shares finding 1) |
| `getBlockEntity(pos)` | block shapes only (`ShulkerBoxBlock.getShape` and the like; no evaluator reads it) | live block entity | `null` | Documented approximation: a shulker box reads as a full cube either way (`getShape` falls back to the block cube when no block entity is found) |
| `getMinY()` / `getHeight()` | `WalkNodeEvaluator` 72, 339, 353, 476; `FlyNodeEvaluator` 291; `isOutsideBuildHeight` | the level's | inherited, the level's; `CollisionView` delegates | Same; immutable ints, safe off-thread |
| `getWorldBorder()` | `CollisionGetter.borderCollision` (live mob path), `MobPathSnapshot.noBorderCollision` (snapshot path) | the live, possibly lerping border | detached copy (center, size) taken on the region thread | Same geometry at capture; frozen for the solve. A lerping border moves at most its per-tick step during a one-tick solve |
| `getChunkForCollisions(x, z)` | `BlockCollisions.getChunk` 72 (only through `noCollision`) | `getChunk` → the live `ChunkAccess` or `EmptyLevelChunk` | one `CollisionView` whose reads route back to the copies | Same answers; the view is never cast to `ChunkAccess` (Moonrise's `CollisionUtil` fast path takes a `Level`, not a `CollisionGetter`) |
| `getEntityCollisions(..)` | `CollisionGetter.noCollision`, `MobPathSnapshot.noCollision` 191 | `List.of()` | inherited `List.of()` | Same |
| `noCollision(mob, box)` | `WalkNodeEvaluator` 376-377 | `noBlockCollision` + `noEntityCollision` + `noBorderCollision(entity)` | `MobPathSnapshot.noCollision`: same three steps; the border step recomputes `WorldBorder.isInsideCloseToBorder` from the captured `x`, `z` and box (`max(absMax(xsize, zsize), 1)`, `< bbMax * 2`, `isWithinBounds(x, z, bbMax)`) | Same, verified against `WorldBorder` 130-133 |
| `getPathTypeFromState(x, y, z)` | all evaluators via `PathfindingContext` | `PathTypeCache` when on a region tick thread, else static | always static (`cache = null` in the snapshot constructor, `PathfindingContext` 36-40, MC 0039; and since MC 0082 any context built on a thread that ticks no region uses no cache) | Same result: the cache memoises `WalkNodeEvaluator.getPathTypeFromState`; `BlockState.pathType` is a per-state constant (Canvas "optimize path type cache") |

Nothing else on the region is called. `PathFinder.findPath` touches the region only to hand it
to `prepare`; the profiler it reads is thread-local.

## How the snapshot is built (patch 0006, `sourbyKickAsyncRecompute`; since 2026-10-22 `perf/AsyncPathRecompute.offload`)

- Bounds: `fromPos ± (int)(getMaxPathLength() + 8)` — the same `followRange + 8` radius vanilla
  `createPath` uses for its `PathNavigationRegion`, from the same `mob.blockPosition()`.
- Thread: the constructor runs on the region tick thread that owns the mob, inside the
  recompute slot. `PathNavigationRegion`'s grid is filled with `getChunkNow` (full chunks only,
  never loading). Every occupied section's block palette is copied there and then
  (`PalettedContainer.copy()` → `data.copy()`, a deep copy of storage and palette). Sections that
  `hasOnlyAir()` are not copied; a `null` entry reads as `AIR`. The chunk references the parent
  keeps in `chunks` are never dereferenced after construction because every reading member is
  overridden (table above), but they do keep those chunks reachable for the solve.
- Cross-region reach: the region may extend into chunks another region thread ticks. Vanilla on
  this engine has exactly the same exposure (its `PathNavigationRegion` reads those chunks' live
  palettes during the synchronous solve), so the snapshot narrows the window to the copy itself;
  it does not add a new one.
- The Y range of the region (`start.getY()`..`end.getY()`) is not used to limit the copy: every
  occupied section of each column is copied. Cost only, not correctness.

## Findings

1. **Unloaded chunk reads `OPEN` instead of `BLOCKED` through `*IfLoaded`** — the one
   correctness divergence. Already in the spec's "Correctness notes" (2026-10-20) with the fix
   plan (per-chunk loaded flag; `null` from `getBlockStateIfLoaded` / `getFluidIfLoaded` for an
   unloaded or out-of-grid chunk). Pinned by `SnapshotPathRegionTest`
   (`todayTheSnapshotAnswersAirForAnUnloadedChunkWhereVanillaAnswersNull`; parity test
   `@Disabled` until the patch). Needs a patch slot; not changed here. **Update 2026-10-20 (opus-q):
   fixed in MC feature patch 0048; the parity test is enabled and the pin removed** (see
   `docs/async-pathfinding-spec.md`, Correctness notes).
2. **`VOID_AIR` vs `AIR` for a missing chunk in plain reads** — benign. Vanilla answers through
   `EmptyLevelChunk.getBlockState` (`VOID_AIR`); the snapshot answers `AIR`. The solve classifies
   states by `BlockState.pathType` (the cached ordinal of
   `WalkNodeEvaluator.canvas$getPathTypeFromBlockState`, filled by `canvas$initCaches` during
   `Blocks.rebuildCache`) and `isAir()`, both `OPEN`/true for either; collision shape and fluid are
   empty for both; `isPathfindable(LAND)` is equal. Pinned by
   `SnapshotPathRegionAuditTest.anUnloadedChunkAnswersAirWhereVanillaAnswersVoidAirAndTheSolveCannotTellThemApart`.
   The spec's sentence "Plain `getBlockState`/`getFluidState` reads (AIR for an unloaded chunk) are
   equal in both" should say "equivalent for the solve (`VOID_AIR` vs `AIR`)"; the spec file was
   locked by another agent at audit time, so the wording stays theirs to fix. **Resolved:** the spec
   now says "equivalent for the solve (`SnapshotPathRegionAuditTest`), not identical" (Correctness notes).
3. **Stale guard claim in the spec.** "Guarded: only snapshot occupied sections; skip when the
   region is `allEmpty`" — the first half is true (`hasOnlyAir` sections are not copied); the
   second is not: `PathNavigationRegion.allEmpty` is written by the constructor and read by
   nothing on this branch (`PathNavigation`, `PathFinder` and both patches have no reader). An
   all-empty region is snapshotted and solved like any other. Not a correctness issue (the solve
   over an all-air region returns no path either way); the spec sentence is wrong and should be
   corrected by its owner. **Resolved:** the spec now says the snapshot is not skipped for an
   `allEmpty` region and points here (Correctness notes).
4. **Block entities** — `null` on the snapshot (and on its `CollisionView`). Only block shapes
   read them; the evaluators never do. Same class of approximation the spec already lists.
5. **Fluids** — derived from the copied block state exactly as `LevelChunkSection.getFluidState`
   derives them from the live palette; no separate fluid snapshot is needed. Pinned by
   `SnapshotPathRegionAuditTest.fluidsAreDerivedFromTheCopiedBlockStateLikeAChunkSection`.
6. **No live palette read after construction** — every read goes to the copy; the live
   container is touched once (`copy()`). Pinned by
   `SnapshotPathRegionAuditTest.eachOccupiedSectionIsCopiedOnceAndTheLiveContainerIsNeverReadAfterward`
   (and, for the state served, `SnapshotPathRegionTest.aLoadedOccupiedSectionIsServedFromTheCopyNotTheLiveContainer`).
7. **Border** — detached copy; `MobPathSnapshot.noBorderCollision` reproduces
   `isInsideCloseToBorder` from the captured position. Verified by reading both; the border test
   is covered by `MobPathSnapshotTest` ("border test from the captured position").
8. **`PathTypeCache` not used off-thread** — correct by construction (`cache = null`), and
   necessary: the cache is regionized and belongs to the owning tick thread. MC 0082 extends this
   to any `PathfindingContext` built on a thread that ticks no region.

## What this audit does not cover

- A flag-on server run. Nothing here replaces the spec's verification gate 2 (mobs path
  normally over a soak with the flag on) or 3 (spark A/B).
- ~~Finding 1's fix and its parity test, which need the MC patch slot.~~ Done in MC 0048 (see finding 1).
- Collision shapes of modded/plugin-registered blocks whose `getCollisionShape` reads more of the
  level than vanilla blocks do (they get a `BlockGetter` that answers from the snapshot, so they
  cannot race the live world; they can only see the approximations above).

Tests added by this audit: `SnapshotPathRegionAuditTest` (3). Existing evidence relied on:
`SnapshotPathRegionTest` (another agent, in progress at audit time), `MobPathSnapshotTest`,
`AsyncPathEvaluatorIsolationTest`.
