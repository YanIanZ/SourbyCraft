# Aurora Task D — Entity/AI validation

Started 2026-09-21 on `26.2`. Scope: validate the existing entity-query, tracker,
and block-collision candidates before adding another NMS optimization.

## Evidence and completion gates

The [entity/AI profiles](hotspots-entity-ai.md) identify collision, entity section
queries, and tracking as candidates. Their 180-second windows and 22–27% foreign
CPU do not qualify as certified before/after baselines. Sampling changes alone
cannot establish a throughput improvement or justify changing plugin semantics.
Task D remains in progress; Chunk/World and Network work has not been completed.

## Tracker candidate 0018: callback boundary

`TrackedEntity.updatePlayer` synchronously calls `PlayerTrackEntityEvent` before
pairing a newly tracked entity. A plugin can change passengers during that event.
Pairing also reaches equipment-change callbacks, and removal reaches
`PlayerUntrackEntityEvent`. A listener-count check for the track event alone would
miss those paths. An independent read-only Claude review confirmed these call chains.
`getEffectiveRange` depends on the entity's indirect passengers and the server's
tracking-distance scale. Consequently, an earlier player's callback can change the
range required for a later player within the same broadcast.

Caching that range across either `updatePlayers` or `moonrise$tick` violates the
original per-recipient behavior. A lack of player dependence is insufficient:
range inputs must also remain invariant across callbacks. Empty broadcasts must
not introduce a new entity/passenger traversal either.

The safe correction is to withdraw patch 0018 and restore upstream range reads.
A future replacement needs explicit invalidation across every synchronous callback,
or evidence that recomputation itself can be optimized without crossing callbacks.
The earlier profile remains historical evidence of cost, not acceptance of this
candidate.

`TrackerBroadcastTest` runs the real broadcast loops and real range calculation,
with the per-player operation replaced by a callback that changes the range scale.
It covers both list and region broadcasts and the empty-list case. This models the
callback boundary; it is not a live Bukkit-plugin integration test.

## Collision candidate 0019: ownership and arithmetic

The six translated bounds are method-local primitives derived from an immutable
AABB and the current block coordinates. No entity/world scratch field, shared
collection, worker, or asynchronous callback is introduced. The existing owner
check, traversal order, block predicate, and check-only early return are retained.
A result AABB is still allocated for each accepted collecting-mode hit and belongs
to the caller's result list.

The full-block branch must use the strict vanilla intersection test. Other
single-box shapes must retain Moonrise's epsilon-aware test. These are deliberately
different for shallow overlap; unifying them would change collisions.
`BlockCollisionBoundsTest` compares scalar translations against `AABB.move` and
both original object-overload intersection rules, including negative coordinates,
world-edge coordinates, contact, misses, and overlap around epsilon. This is
arithmetic coverage, not a world-traversal or gameplay integration test.

## Reproduce verification

Run patch materialization separately from tests (Weaver uses nested build outputs):

```sh
./gradlew applyAllPatches
./gradlew :sourbycraft-server:test
python3.12 -m unittest discover -s scripts -p 'test_*policy.py'
```

`EntityOptimizationTestSuite` registers all five new cases with Gradle's suite-only
selection. Before withdrawal, all three tracker cases fail: both broadcasts observe
`[16, 16]` instead of `[16, 64]`, and the empty broadcast reads range unnecessarily.
The collision arithmetic cases pass on the retained candidate.

Validation result on 2026-09-21: patch regeneration succeeds; server XML reports contain
9,496 tests, zero failures/errors, and 22 skipped. All five Task D cases execute and
pass after withdrawal. The Python policy suite passes 60 tests. No certified throughput,
new gameplay boot, or soak claim is made by this validation increment.

## Remaining work

- Run certified entity-stress reference/candidate windows with connected players,
  identical world/configuration and acceptable foreign CPU; repeat both sides.
- Qualify collision behavior in gameplay, including block predicates and check-only
  callers, and plugin tracking callbacks in a live server.
- Profile the remaining Entity/AI targets; do not infer missing coverage from an
  aggregate ranking.
- Continue Chunk/World and Network only with their own measurements and persistence
  or event-loop safety checks.
