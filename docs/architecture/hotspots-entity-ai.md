# CPU and Allocation Hot Spots Under Real AI Load

Roadmap items *"rank JFR CPU hot spots"* and *"rank JFR allocation hot spots"*
(`docs/AURORA-TASKS.md`). Captured 2026-09-20.

## How this was measured, and what it is not

`entity-stress`, **10 headless clients connected and in play**, JFR `profile` settings, 180 s
window, on the seed world. The clients matter more than anything else here: entity activation
range is computed around players, so every earlier entity profile in this project measured the
*inactive* path. This is the first ranking taken while mob AI, goal selection and pathfinding
were actually running.

**The run is not certified** — 26.9% foreign CPU against a 10% limit, and a 180 s window below
the 300 s minimum. That invalidates its absolute numbers as a regression reference. It does not
invalidate a *ranking*: which methods appear most often in 5,364 execution samples is robust to
a noisy neighbour in a way that "mean MSPT was 12.4 ms" is not. Nothing here should be quoted
as a baseline; everything here is a direction to look.

## CPU — top of 5,364 execution samples

| Samples | Method |
|---|---|
| 246 | `AABB.intersects(double×6)` |
| 245 | `ServerLevel.optimiseRandomTick(LevelChunk, int, RegionizedWorldData)` |
| 186 | `ChunkMap$TrackedEntity.updatePlayer(ServerPlayer)` |
| 137 | `ChunkEntitySlices$EntityCollectionBySection.getEntities(Entity, AABB, List, Predicate)` |
| 134 | `CollisionUtil.getCollisionsForBlocksOrWorldBorder(...)` |
| 107 | `Reference2IntOpenHashMap.containsKey(Object)` |
| 76 | `Level.findSupportingBlock(Entity, AABB)` |
| 62 | `TickThread.isTickThreadFor(Entity)` |

The first, fourth, fifth and seventh are one cluster: **entity collision and spatial queries**,
all of them passing `AABB` around. Taken together they are the largest single cost on this
server under AI load — larger than random ticking, which sits second on its own.

`ChunkMap$TrackedEntity.updatePlayer` at third is **entity tracking**, which §11.1 lists as an
Entity Engine responsibility Aurora does not own yet — see
[the entity domain](engine-entity-ai.md#3-what-aurora-does-not-own-yet). It only appears at all
because clients are connected; with no players there is nothing to track.

## Allocation — top of 16,298 allocation samples

By allocating method:

| Samples | Method |
|---|---|
| 1172 | `AABB.inflate(double, double, double)` |
| 1140 | `AABB.move(double, double, double)` |
| 1114 | `Arrays.copyOf(Object[], int)` |
| 569 | `PalettedContainer.reencodeContents(...)` |
| 558 | `Vec3.add(double, double, double)` |
| 480 | `CollisionUtil.getCollisionsForBlocksOrWorldBorder(...)` |

By type allocated:

| Samples | Type |
|---|---|
| **3343** | `net.minecraft.world.phys.AABB` |
| 1740 | `net.minecraft.world.phys.Vec3` |
| 1562 | `Object[]` |
| 673 | `BlockPos` |
| 548 | `BlockPos$MutableBlockPos` |

`AABB` is both the most-allocated type and the most-sampled CPU method. `inflate` and `move`
each return a new instance, and they sit directly under the collision path that is already the
top CPU cluster.

## What this points at

One target, supported by both rankings independently: **AABB churn in the entity collision and
spatial-query path**.

There is precedent in this codebase for exactly that shape of fix — patch 0012 already inlines
an AABB and reuses a `MutableBlockPos` in `Entity#isInWall`, and patch 0010 reuses a `BlockPos`
in `optimiseRandomTick`, the method sitting second on the CPU list. Both are local algorithm
changes in the class that owns the data, which is what §11's boundary rule asks for.

What this ranking does **not** establish is that such a change would be an improvement. §4.5
and PRD §115 need a benchmark, and this run cannot be one: a certified `entity-stress` reference
with clients attached is still required before any of these is optimised, and the same
configuration that produced this ranking can produce that reference on a quiet machine.
