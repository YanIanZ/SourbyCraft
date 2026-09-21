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

This one matters more than it looks. Had `move()` returned `this` for a zero offset, the old
code would have put a block's **cached** shape AABB into a caller-owned list, where a caller
mutating or retaining it would corrupt the shape cache for every thread. It does not, so
neither version has that defect — but the new version cannot acquire it.

**Verdict: safe**, and marginally safer than what it replaces.

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

Checked, in the loop body:

| Call | Touches passengers? |
|---|---|
| `seenBy.add` / `seenBy.remove` | no |
| `serverEntity.addPairing` → `sendPairingData`, `startSeenByPlayer` | no |
| `serverEntity.removePairing` | no |
| `debugSynchronizers()` register/start/drop | no |

**But the loop fires a plugin-visible event.** `ChunkMap.java:1401` constructs and calls
`PlayerTrackEntityEvent` before pairing. A listener holds a `Bukkit` entity and may do anything
a plugin can, including `addPassenger` or `eject`.

So the honest statement is not "nothing mutates passengers". It is:

- **No engine code in the loop mutates them.** The hoist is equivalent for a server without a
  plugin listening to that event.
- **A plugin listener can.** If one changes the tracked entity's passengers during the event,
  the remaining players in *that* broadcast are evaluated against the range computed before the
  change, where previously each player would have recomputed it.

**Bound on the consequence.** The staleness lasts one broadcast. Tracker updates run every
tick, so the next tick computes the range afresh and any visibility difference corrects itself.
The affected quantity is a visibility *distance*, so the worst case is an entity briefly shown
or hidden one tick later than it would have been for players after the listener in iteration
order.

**Verdict: safe, with a documented behavioural narrowing.** This is not a thread-safety defect
— the event runs on the same region thread as the loop, so there is no race — it is a
same-thread reentrancy question, and the answer is a one-tick staleness in a case that requires
a plugin to mutate passengers from a track event.

If that is judged too much, the fix is cheap and does not undo the optimisation: recompute the
range only when the event actually has listeners, which is already tested for on the same line.
It is not done here because it trades a measured saving against a hypothetical listener, and
the measurement is real while the listener is not yet known to exist.

---

## Summary

| Patch | Thread access | Precondition | Verdict |
|---|---|---|---|
| 0017 | unchanged | `AABB` immutable — verified | safe |
| 0018 | unchanged, still `AsyncCatcher`-guarded | no engine passenger mutation in loop — verified; plugin listener can — documented | safe, one-tick staleness in a plugin case |
| 0019 | reduced | `move()` always allocates — verified | safe, marginally safer |

None of the three moves work between threads, publishes an object to another thread, or changes
which thread owns any state. Two are pure local transformations over immutable data. The third
narrows a per-player recomputation to per-broadcast, and its one behavioural edge is written
down above rather than left for someone to find.
