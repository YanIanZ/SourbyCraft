# Region-Safety Review — patches 0017, 0018, 0019

PRD §108 requires a region-thread safety review for every entity/NMS change. These three
touch entity query, entity tracking and block collision, so they need one. Reviewed 2026-09-21,
after the patches were written, which is later than it should have been.

The question each patch has to answer is not "does it work" but: does it change *which thread
touches what*, or *when*, in a way a region-threaded server cannot tolerate?

---

## Patch 0017 — hoisting query bounds in `ChunkEntitySlices`

**Change.** Read the query `AABB`'s six fields into locals above the section loop; call the
six-double `intersects` overload instead of `intersects(AABB)`.

**Thread access: unchanged.** The same objects are read, in the same order, on the same thread.
No field becomes shared that was not already, and no read moves across a thread boundary.

**Correctness precondition: `AABB` is immutable.** Verified — every field is
`public final double` (`net/minecraft/world/phys/AABB.java`). Hoisting a read of an immutable
object out of a loop cannot observe a different value than reading it per iteration, whatever
other threads are doing.

**Verdict: safe.** The transformation is one the JIT is permitted to make on its own; doing it
in source only guarantees it.

---

## Patch 0019 — deferring the collision-box allocation in `CollisionUtil`

**Change.** Compute the translated bounds as six doubles, test with the existing
non-allocating overloads, and construct an `AABB` only on the path that retains one.

**Thread access: unchanged, and strictly reduced.** Six local additions replace an allocation.
Nothing new is read or published.

**Correctness precondition: `move()` never returns `this`.** Verified — it is
`return new AABB(this.minX + xa, ...)` unconditionally. So the object added to `intoAABB` is
freshly allocated in both the old and new code, and the new `AABB(...)` carries the same six
values `move()` would have produced.

`AABB` is immutable, so retaining an instance does not by itself corrupt a shape cache.
Both paths preserve the same retained bounds and caller-owned collection semantics.

**Verdict: equivalent local arithmetic**, with boundary tests in
`BlockCollisionBoundsTest`. This does not replace gameplay integration or certified profiling.

---

## Patch 0018 — hoisting `getEffectiveRange()` out of the tracker broadcast

**Change.** Compute the effective range once per broadcast and pass it to `updatePlayer`,
instead of computing it inside each per-player call.

**Thread confinement: unchanged.** `updatePlayers` runs on the region thread that owns the
tracked entity, and `updatePlayer` opens with `AsyncCatcher.catchOp("player tracker update")`,
which is unchanged and still guards every entry.

**The precondition is the interesting part.** Hoisting is only equivalent if nothing *within*
the loop changes what the range depends on — `this.entity`'s passengers, via `getPassengers()`
and `getIndirectPassengers()`.

The original review overlooked synchronous callbacks reachable through pairing and removal:

| Call | Plugin callback reachable |
|---|---|
| `updatePlayer` before pairing | `PlayerTrackEntityEvent` |
| `removePairing` → `stopSeenByPlayer` | `PlayerUntrackEntityEvent` |
| `addPairing` → `sendPairingData` → `detectEquipmentUpdates` | `PlayerArmorChangeEvent`, `EntityEquipmentChangedEvent` |

Listeners may change passengers immediately through the Bukkit entity API. Reading the
range once before the loop therefore gives later recipients a stale value. Region confinement
does not make this same-thread callback behavior equivalent to upstream.

**Corrected verdict (2026-09-21): withdrawn.** Patch 0018 is removed. The two real broadcast
loops now recompute through the upstream per-player method. Regression tests reproduce the
old stale-range behavior and unnecessary range read for an empty broadcast. Checking only
`PlayerTrackEntityEvent` listeners would miss the other callback paths above.

An invalidation-aware replacement may be investigated later, but needs independent semantic
coverage and certified measurements. Exploratory sample reductions do not justify accepting
changed plugin behavior.

## Summary

| Patch | Ownership assessment | Status |
|---|---|---|
| 0017 | local immutable query bounds | retained; certified performance gate open |
| 0018 | same-thread callback invalidation overlooked | withdrawn; upstream per-recipient reads restored |
| 0019 | local scalar translation, caller-owned results | retained; arithmetic tests added, certified performance gate open |

See [Task D validation](aurora-task-d-validation.md) for scope, tests, and remaining gates.
