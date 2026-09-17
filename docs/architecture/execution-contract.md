# Aurora execution contract extraction

Phase 2 of [AURORA-INDEPENDENT-ENGINE.md](AURORA-INDEPENDENT-ENGINE.md).
This document extracts the current dependencies and specifies the first migration slice.
It does not introduce a scheduler, worker pool, or replacement backend.

Source baseline: local 42204c7; architecture proposal 1cb4eec. Existing configuration,
metrics, plugin compatibility, and completed tests remain in place.

## Current dependency map

| Domain / consumer | Current execution dependency | Required behavior | Migration boundary |
| --- | --- | --- | --- |
| Async path completion | `perf/AsyncPathCompletion.java` depends on `OwnerHandoff`; `execution/RegionOwnerHandoff` is the only backend adapter | Defer mutation to the current entity owner; handle rejection and retirement; release pending state | **Done** — owner identity delivered and checked; epoch and target validation outstanding |
| Path computation | `perf/AsyncPathProcessor.java`, bounded platform-thread pool | Snapshot-only CPU work; defined saturation, cancellation, shutdown | Separate CPU admission contract; do not conflate with owner dispatch |
| Player replies | `command/SourbyReply.java`, player scheduler | Deliver on current player owner; handle disconnect | Public compatibility API remains until a shared handoff contract proves useful |
| HUD | `hud/HudBars.java`, global periodic task plus player schedulers | One shared snapshot cadence; player-owned show/hide/preferences; cancellation on close | Separate aggregation cadence from player mutation |
| Metrics collection | `perf/PerformanceCollector.java`, direct `RegionizedServer` and `TickRegionScheduler` reads | Read counters/tick-rate cheaply; immutable publication; lifecycle-owned collector | Metrics-source adapter, not task dispatcher |
| Updater | `update/SourbyUpdater.java`, `UpdateApplier.java`, `UpdateNotifier.java`, public async/player schedulers | Blocking network/admin work away from gameplay; owner-bound player notification; shutdown cancellation | External I/O and notification contracts stay distinct |
| Engine configuration | `SourbyCraftConfig.java`, concrete Canvas global/world reloads | Explicit operator reload; preserve cached-setting caveats | Config bridge, not scheduling API |

Paths above are relative to `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/`.
The Minecraft integration source is `sourbycraft-server/minecraft-patches/features/0006-SourbyCraft-async-pathfinding-offload-periodic-path-.patch`.
Generated upstream code is read-only evidence, never the patch source of truth.

## What counts as coupling

The public Bukkit-facing Folia API — `io.papermc.paper.threadedregions.scheduler` — is not
coupling. Depending on a published contract is the goal. What matters is the internal surface:
`TickRegionScheduler`, `RegionizedServer`, `RegionizedWorldData`, `ThreadedRegionizer`,
`TickRegions`.

## Measured inventory

Counted by `scripts/independence_policy.py` and pinned by `scripts/test_independence_policy.py`,
so this list is designed against a fixed set and new coupling has to be added deliberately.

Five sites in SourbyCraft-owned code, across eighty files:

| Where | Symbol | Why |
| --- | --- | --- |
| `perf/PerformanceCollector.java` | `RegionizedServer.getGlobalTickData()` | read the global tick handle's metrics |
| `perf/PerformanceCollector.java` | `TickRegionScheduler::getTickRate` | the configured tick rate, for TPS |
| `execution/RegionOwnerHandoff.java` | `EntityScheduler` | the single adapter behind `OwnerHandoff`; hands work to an entity's owning region |

One site in the fourteen engine-integration patches:

| Patch | Symbol | Why |
| --- | --- | --- |
| `0006` async pathfinding | `TickRegionScheduler.getCurrentRegionizedWorldData() != null` | am I on a region thread, so is offload legal |

Two further patches (`0002`, `0013`) *edit* `TickRegionScheduler.java` and `TickRegions.java`
rather than call into them — a thread-count default and the tick metrics hook. Those are engine
modifications, not call-site coupling, and a contract does not remove them.

Canvas coupling is two sites, both config reload bridges in `SourbyCraftConfig.java`.

### What the count does and does not settle

The call sites are few, and it is tempting to read that as "the scheduler is nearly decoupled
already". It is not. A small number of call sites says the *mechanical* edit is small; it says
nothing about the semantics behind them, and the requirements below — owner identity across
dimension transfer, retirement, stale-result validation — are where the work actually is. Counting
imports measures the surface, not the contract.

## Progress against this document

The first slice is implemented. `OwnerHandoff` and `Admission` state the contract,
`RegionOwnerHandoff` is the only place `EntityScheduler` is named, and patch 0006 now
delivers through it and compares the delivered owner against the entity the solve was
computed for, discarding the result when a dimension transfer has replaced it.

Requirements 1 to 6 are met by the contract and its adapter. Requirement 7 is now met for
async pathfinding: owner identity and result applicability are both validated, from the
trace below. Requirements 8 and 9 are met for this slice, below.

### Traced invalidation events for the async path solve

Every write to `PathNavigation.path`, which is `protected` but written by no subclass:

| Event | Site | Effect on an in-flight solve |
| --- | --- | --- |
| Synchronous recompute | `recomputePath`, the fallback below the offload | The result overwrites a **newer** path |
| Retarget or clear | `moveTo(Path, double)` | The result undoes the retarget |
| Navigation stopped | `stop()` | The result resurrects a stopped navigation |

The first is not hypothetical. `recomputePath` is throttled to twenty ticks, and
`sourbyAsyncPathPending` only suppresses the *offload* — the synchronous recompute below
it still runs. A solve outliving the throttle therefore lands on top of a fresher path.

`targetPos` and `reachRange` are written only inside `createPath`, and every caller
assigns `this.path` in the same statement, so a change of target is visible as a change
of path. That makes the path object itself a sufficient validity token: the solve
captures the path it was computed against and discards its result if the navigation is
no longer following that object.

This is why no epoch field was added. An epoch would need incrementing at every one of
those sites, widening the diff against upstream, to derive a token the state already
provides. The trade is that two identical-but-distinct `Path` objects cannot be told
apart — but `moveTo` only reassigns when `!newPath.sameAs(this.path)`, so a path that
compares the same is one the mob is still following, and refreshing it is correct.

### Shutdown and cancellation (requirements 8 and 9)

Shutdown is terminal and refuses admission rather than degrading to an inline solve. The
two look alike and are not: a not-yet-started pool running the solve on the caller is a
deliberate slow path, but doing it *during shutdown* puts CPU-bound A* on a region thread
that is trying to stop. A refused submission still returns a completed future, so the
caller's release runs and nothing is left believing a solve is in flight. Every admitted
solve is cancelled on the way down, and `AsyncPathProcessor.outstanding()` reports how
many are unaccounted for.

That counter also makes this document's own caveat measurable: the pool's queue is
bounded at 1024, but each completed solve then enqueues a delivery on an entity's
scheduler, and *that* queue is not ours to bound. The count is the difference. No cap has
been imposed on it, because what the right cap is depends on a measurement nobody has
taken yet.

The four cancellation operations are separate, with separately tested outcomes:

| Operation | Mechanism | Tested by |
| --- | --- | --- |
| Stop admission | `shutdown()` — terminal until restarted | `admissionIsRefusedAfterShutdownRatherThanRunningInline` |
| Cancel a computation | `future.cancel(true)` | `cancellingAComputationIsNotShuttingThePoolDown` |
| Retire an owner | handoff retirement callback | `v12RetirementAfterAdmissionReleasesWithoutApplying` |
| Invalidate a result | path-identity check in patch 0006 | the trace above, not a unit test |

The last row is the honest one. That check lives in patched Minecraft code and needs a
bootstrapped server to exercise, so it rests on the traced invalidation set rather than
on a test. `setEnabled(false)` stops callers *offering* work and is distinct from
`shutdown()`, which refuses work already being offered.

## First contract: owner-result handoff

An Aurora contract must express these requirements independently of a concrete Folia class:

1. Admission may be requested from a background completion thread.
2. Admission returns a distinguishable accepted/rejected outcome; rejected work cannot later run.
3. Accepted work is deferred to an owning gameplay context; it never mutates gameplay inline
   on the submitting worker, including under saturation.
4. Delivery supplies the **current owner/entity identity**, not merely the object captured when
   work began. Entity replacement during dimension transfer is an explicit case.
5. Accepted work has mutually exclusive delivery or retirement paths while the backend remains
   operational. Process death is not a delivery guarantee.
6. Retirement cleanup cannot load worlds/chunks, modify entities, or block. It releases only
   request bookkeeping/resources that are safe for that callback context.
7. Before mutation, the gameplay callback validates request epoch, owner identity/lifecycle,
   and the state inputs relevant to the result. Invalid results are discarded.
8. Shutdown defines rejection and disposal of outstanding requests. Bounded CPU admission does
   not by itself prove the entity scheduler's pending queue is bounded.
9. Cancellation is explicit: stopping admission, cancelling computation, retiring an owner, and
   invalidating a result are separate operations with separately tested outcomes.

No generic `execute(Runnable)` facade is sufficient: it hides owner identity, rejection,
retirement, and stale-result validation. Do not introduce that facade as cosmetic independence.

## Evidence and current gaps

The current upstream `EntityScheduler` explicitly supplies the current NMS entity to callbacks.
Its class and schedule documentation explain that dimension transfer can replace the underlying
entity, and callbacks should use the supplied entity. It also documents critical retirement
callback restrictions. Its `schedule(..., 1L)` path is the current deferred-delivery backend.

`AsyncPathCompletion` correctly uses deferred scheduling and handles rejected/retired admission,
but discards the callback entity parameter. Patch 0006 captures the original navigation object
and applies a completed path whenever the result/target is non-null. It does not currently
validate a request epoch or compare the current delivered entity against the original owner.
The pending boolean prevents overlapping submissions through that path; it does not prove that
an old result is still valid after a target, navigation, or lifecycle change.

The worker also passes a live `Mob` into the path solver. That review is done, and the input
set was not read-only.

`PathFinder.findPath` hands the mob to `NodeEvaluator.prepare`, which stores it for the whole
solve. The evaluators then read position, bounding box, `onGround`, `isInWater`, step height
and fall distance off-thread — tolerable staleness, the same kind a path always races. Two
paths **write** to the mob instead:

* `AmphibiousNodeEvaluator.prepare` sets WATER, WALKABLE and WATER_BORDER malus and `done()`
  restores two of them. `Mob.pathfindingMalus` is a plain `EnumMap`. Worse than a racing write:
  it is save-and-restore, so a region-thread solve starting between the worker's `prepare` and
  `done` saves the already-overwritten 6.0F as the original and restores *that* — corrupting the
  mob's WALKABLE cost for its lifetime. Reaches **Axolotl** and **Drowned**.
* `Mob.onPathfindingStart` / `onPathfindingDone` are called from inside the solve. Both are empty
  on `Mob`, and **Sniffer** overrides both to write WATER malus.

Both are now refused rather than raced: `PathNavigation.sourbyAsyncSolveSafe` (false for
`AmphibiousPathNavigation`) and `Mob.sourbyPathfindingMutatesMob` (true for `Sniffer`) gate the
offload, which falls back to the synchronous recompute.

One hypothesis this review disproved: `FlyNodeEvaluator` calls `mob.getRandom()`, which looked
like a shared-RNG race. Folia already replaced `Entity.SHARED_RANDOM` with
`ThreadLocalRandomSource.INSTANCE`, so it is per-thread. The only consequence is that a solve
draws from the worker's stream rather than the region's — a determinism difference, not
corruption.

`SnapshotPathRegion` is now reviewed too. Its central claim holds: `PalettedContainer.copy()`
deep-copies both the palette and the bit storage (`Palette.copy()` clones its value array,
`SimpleBitStorage.copy()` clones the `long[]`, and `ZeroBitStorage` is stateless), so block and
fluid reads during the solve are served entirely from detached data. `getEntityCollisions`
returns an empty list and `getBlockEntity` returns null, so neither reaches live state.

The claim that the instance was *"fully immutable and safe to read from any thread"* was
overstated, and three reads escaped it:

* **World border** — `CollisionGetter.noCollision` reaches `borderCollision`, which calls
  `getWorldBorder()`. That was not overridden, so it dereferenced the live level from the worker.
  Now captured as a detached copy at construction, which also freezes a lerping border for the
  duration of one solve.
* **The live mob** — `noCollision(mob, box)` builds an `EntityCollisionContext` from
  `isDescending()`, `getY()` and `getMainHandItem()`, and the evaluators read position and
  bounding box directly. Left as-is and documented: these are reads of the same state a path
  always races, and a path is recomputed constantly against a position that has already moved.
  Evaluators that *write* to the mob are refused the offload entirely.
* **`mob.level()`** — used directly for `getMinY()` in `WalkNodeEvaluator` and `getSeaLevel()` in
  `AmphibiousNodeEvaluator`, bypassing the snapshot. Both are per-dimension constants fixed at
  world load, so they are safe; noted because they are easy to mistake for live reads.

The parent also retains its live `ChunkAccess` references. Every read path is overridden, so they
are never dereferenced off-thread, but they pin those chunks for the solve's duration.

The feature remains default-off. This review removes the live-level read and states the residual
mob reads precisely; it does not by itself qualify enabling it, which still wants a workload
measurement.

`SourbyReply` groups all non-player senders into a direct reply path, including a comment about
command-block senders. That comment alone is insufficient evidence for arbitrary gameplay-side
mutations. A future contract must distinguish a thread-safe message sink from an owned gameplay
command context.

## Implementation sequence

1. Preserve the four existing AsyncPathCompletion tests for reject-before-admission,
   retire-after-admission, deferred callback, and scheduler failure.
2. Add failing tests for entity replacement, stale request epoch, and changed target/navigation.
   Trace each invalidation event before choosing token lifetime/storage.
3. Define the smallest owner-handoff contract that can carry the current owner and validation
   data. Keep concrete backend access in one adapter; retain required compatibility entrypoints.
4. Route only async path result delivery through that contract first. Compare callback ordering,
   allocations, and latency against the existing implementation.
5. Reuse the contract for player notifications/HUD only if their lifetime semantics actually
   match. Metrics reads, periodic aggregation, and external I/O remain separate boundaries.
6. Consider alternative execution backends only after the contract suite, region-transfer tests,
   persistence/compatibility checks, and relevant workload measurements pass.

## Acceptance matrix for the first implementation

| Scenario | Required result |
| --- | --- |
| Owner already retired | Explicit rejection; pending bookkeeping released; no mutation |
| Owner retires after acceptance | Cleanup callback; no gameplay result application |
| Owner changes underlying entity | Deliver current identity; reject stale captured result |
| Target/navigation changes during computation | Epoch/state validation discards obsolete result |
| Same valid owner and request | Apply once on owning context after deferred admission |
| CPU queue saturation | Existing documented backpressure; no unbounded new queue |
| Cancellation/shutdown | Outstanding work accounted for; no silent retained request |
| Callback failure | Cause observable; cleanup semantics tested; no duplicate application |

## Status boundary

Stage 1 contract extraction has begun for a concrete handoff. Stage 2 dispatch API and Stage 3
backend isolation are not implemented by this document. Existing baseline/soak results describe
the tested workloads and revisions; they do not qualify a future scheduler or prove absence of
all leaks. Keep the independent-engine roadmap as the focus, with correctness preceding backend
replacement. The upstream Spark viewer remains in use as required by section 37.
