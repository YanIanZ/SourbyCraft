# Scratch-buffer confinement review (PRD Phase 1, action 2)

PRD section 108 requires a region-thread safety review for any performance change
touching entities, chunks, world, inventory or players. This is that review for the
six patches that replace a per-call allocation with a reused scratch buffer.

They were reviewed together because they share one mechanism and therefore one failure
mode. That failure mode is not a crash: a scratch buffer reached by two region threads,
or one whose contents outlive the call, corrupts state silently.

## The two rules

A scratch buffer is correct only while both hold.

**1. One region thread must be the only reader and writer.**

`ServerLevel.regioniser` subdivides a single level into many regions, and
`MinecraftServer#tickChildren(haveTime, region)` does `ServerLevel level = region.world;`
then `level.tick(haveTime, region)`. Every region thread therefore calls `tick` on the
*same* `ServerLevel` instance concurrently. A field on `ServerLevel` is shared mutable
state across threads, not per-tick scratch. Folia already provides the correct home:
`RegionizedWorldData`, reached with `ServerLevel#getCurrentWorldData()` — the region tick
loop clears `regionizedWorldData.explosionDensityCache` two lines after calling `tick`.

A field on an `Entity` is fine: an entity is owned by one region at a time, and its tick
runs on that region's thread. A `static` field is never fine.

**2. The contents must not outlive the call.**

If a callee stores the reference, the next call's `clear()` mutates whatever now holds
it. `SynchedEntityData.DataItem#setValue` stores the reference it is given, and
`SynchedEntityData#set` only marks an entry dirty when the candidate is `notEqual` to
the stored value, so publishing a scratch makes every later comparison a self-comparison.

## Findings

> **Superseded in part (2026-09-14).** This review covered *confinement* (which thread may
> touch the buffer) and *escape* (whether a callee stores it). It did not cover *lifetime* —
> whether a buffer retains its last contents after the call. A later audit,
> [reuse-audit.md](reuse-audit.md), found exactly that defect in three of the buffers marked
> "Safe" or "Fixed" below: each clears on *entry* to its next use, so it pins the previous
> query's objects indefinitely whenever that next use does not come. Patches 0009 and 0014
> and the `Mob` half of 0016 were consequently **removed**, restoring upstream's per-call
> collections, since no benchmark ever justified the reuse. The verdicts below are correct
> for the question this review asked and incomplete for the question the later audit asked.


| Patch | Buffer lives on | Escapes? | Verdict |
| --- | --- | --- | --- |
| 0009 collision scratch lists | `Entity` instance | No — `collide` returns a `Vec3` | **Safe** |
| 0012 random-tick `MutableBlockPos` | method local | No | **Safe** — not a shared buffer at all |
| 0013 world-border set | `ServerLevel` instance | — | **Unsafe: cross-region race.** Removed |
| 0014 effect-particle list | `LivingEntity` instance | **Yes — into `SynchedEntityData`** | **Unsafe: defeats dirty tracking.** Fixed |
| 0015 block-event list | `ServerLevel` instance | — | **Unsafe: cross-region race.** Removed |
| 0016 item-entity scratch | `Mob` instance | No — filled and iterated in place | **Safe** |

### 0013 and 0015 — removed

Both put the buffer on `ServerLevel`. Both patch comments justified it with *"called once
per region per tick"*, which is the reason it is wrong rather than the reason it is right:
once per region, on as many threads as there are regions, against one shared field.

`ReferenceOpenHashSet` and `ArrayList` are not thread-safe, and the accesses were
unsynchronised, so the Java memory model promises nothing about the result. An earlier
revision of this document asserted a specific outcome — a `ConcurrentModificationException`,
or entries lost during a rehash. **That claim was not supported and is withdrawn.**
Fail-fast detection is documented as best-effort and cannot be relied on, `ArrayList`
iteration may be indexed rather than iterator-based, and no particular failure was
reproduced here.

What can be said without measuring: two region threads could interleave `clear`, `add`
and iteration on one shared collection with no happens-before between them, so for 0015 a
block event could be missed, double-processed, or pushed to the wrong region's queue, and
for 0013 a world border could be skipped. Which of those occurs, and how often, was not
determined. The patches were removed because the access pattern is unsound and unjustified,
not because a specific failure was demonstrated.

They were removed rather than moved into `RegionizedWorldData`. Each saved exactly one
allocation per region per tick, roughly 20 per second per region, which is not
measurable; neither carried a benchmark, as PRD section 115 requires; and PRD section 116
lists "correctness risk exists" as grounds for removal. Removing them also reduces
downstream patch complexity, the section 114 KPI. `ServerLevel` is back to Folia's own
per-call locals.

If the allocation is ever shown to matter, the correct form is a field on
`RegionizedWorldData`, not on `ServerLevel`.

### 0014 — fixed, and now allocates less than before

The list was built in a `LivingEntity` field and then handed straight to
`entityData.set(DATA_EFFECT_PARTICLES, …)`. Confinement was fine — a living entity is
region-owned — but the contents escaped.

After the first publish, the stored value *is* the scratch. Every later call clears and
refills the object `SynchedEntityData` is holding, then asks it to store that same
object; `ObjectUtils.notEqual(list, list)` is false, so the entry is never marked dirty
again and `packDirty` never sends it. `updateInvisibilityStatus` calls this every tick
for every entity with an active effect, so the effect is continuous: after the first
update a client stops being told the particle set changed. Gaining a second effect does
not show, and particles from an expired effect never disappear until something forces a
full entity sync.

The fix publishes an immutable copy, and only when the contents actually differ from the
stored value. That is strictly less allocation than upstream, which built a fresh list
every tick for `set` to compare and usually discard: an unchanged effect set now
allocates nothing, and `List.copyOf` returns a shared instance when empty.

### 0009, 0012, 0016 — safe, with one caveat

`Entity#collide` passes its three buffers to `CollisionUtil.getEntityHardCollisions`,
`getCollisions` and `performCollisions`, and returns a `Vec3`; the lists never escape.
`Mob#aiStep` fills its buffer through the `Level#getEntitiesOfClass` overload that 0016
adds and iterates it in place. 0012's `MutableBlockPos` is a method local reused across
one loop, not a field, so it shares nothing.

The caveat is reentrancy: all three assume the method is not re-entered while the buffer
is live. No reentrant path was found, but "none found" is weaker than "none exists", and
nothing in the code states the invariant. That is the section 20 hidden-dependency risk.

## Regression test

`dev.iyanz.sourbycraft.entity.ScratchBufferConfinementTest` pins both rules:

* `ServerLevel` declares no field whose name contains "scratch"
* the entity-owned buffers are private, final and non-static
* `LivingEntity#updateSynchronizedMobEffectParticles` invokes `List.copyOf` and
  `SynchedEntityData#get`, so the copy-and-compare guard cannot be dropped silently

The test was mutation-checked: restoring the original `entityData.set(…, scratch)` makes
`effectParticlesAreNotPublishedFromTheScratchBuffer` fail.

A fourth test that tried to prove the scratch field is never the stored argument by
tracking the last field read before the `set` call was written and then **removed** — it
did not fail under the same mutation, because the compiler loads the field into a local
first. It would have been a test that looked stronger than it was.

## What this review did not establish

* No benchmark was run. The removals are justified on correctness and on the absence of
  any evidence for the optimizations, not on a measured regression.
* Reentrancy for 0009, 0012 and 0016 was argued by inspection, not proven. An assertion
  that the buffer is empty on entry would convert the argument into a check.
* Only these six patches were reviewed. Other reused state — object pools, `ThreadLocal`s
  (PRD sections 53 and 54) — is a separate audit.
