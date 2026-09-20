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

---

## Follow-up: patch 0017, hoisting the query bounds

Same workload, same 10 clients, same 180 s window, comparable noise (26.9% foreign CPU before,
26.3% after).

| | `getEntities` | `AABB.intersects` | cluster | share |
|---|---|---|---|---|
| before | 137 | 246 | 383 / 5364 | **7.14%** |
| after | **71** | 261 | 332 / 5440 | **6.10%** |

`getEntities`' own frame nearly halved. On these sample counts that is outside sampling noise —
137 carries a Poisson spread of about ±12 — so the change is real rather than chance, and it is
the frame the six field reads were removed from.

`AABB.intersects` rose slightly (246 → 261). That is the expected shape rather than a
contradiction: the comparison is now reached directly with primitives, so proportionally more
of the cluster's time is attributed to the comparison itself and less to the caller setting it
up. The cluster as a whole fell about one percentage point.

**What this is not.** Neither run is certified, so this says nothing about server throughput.
The correct claim is narrow: the profile shows less time in the frame the patch changed, in the
direction the patch intended. Whether that reaches MSPT needs the certified `entity-stress`
reference T10 is waiting on — produced by this exact command on a quiet machine.

---

## Examined and not patched: `optimiseRandomTick`

Second on the CPU list at 245 samples, so the obvious next target. It was examined and
deliberately left alone; this is recorded so the next reader does not spend the same hour
rediscovering it.

Of the 245 samples, only about 48 have any callee frame beneath them:

| Callee | Samples |
|---|---|
| `BlockStateBase.randomTick` | 19 |
| `FasterRandomSource.nextLong` | 14 |
| `ShortList.getRaw` | 12 |
| `ShortList.size`, `LevelChunkSection.getStates` | 3 |

The remaining ~197 are **self-time**: loop arithmetic, the palette lookup that JIT inlined, and
`pos.set`. There is no dominant callee to attack.

Reading the method against that, the usual candidates are already taken:

- the scratch `BlockPos` is allocated once per call and reused — **patch 0010**,
- the RNG is drawn once per section and split into 12-bit slices, not per trial,
- `isRandomlyTickingBlocks()` guards `getStates()`, and `tickingBlocks == 0` guards the RNG
  draw, so an empty section costs one virtual call,
- offsets are hoisted above the inner loop,
- the ticking-block list with index rejection is Moonrise's own optimisation and preserves
  vanilla's per-position probability. Changing the rejection would change which blocks tick.

The palette lookup cannot be removed: the tick list stores positions, not states, so the
`BlockState` has to be fetched to call `randomTick` on it.

**Conclusion: this method is at its floor for its current semantics.** Its cost scales with
`random_tick_speed` and the number of loaded, randomly-ticking sections — which is why raising
`random_tick_speed` to 480 was measurable at all. Reducing it is an operator decision about
load, not an optimisation available in this code.

Recorded as a negative result rather than left as an open target, because "second on the CPU
list" reads like opportunity until someone checks.
