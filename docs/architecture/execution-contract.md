# Aurora execution contract extraction

> **Role:** execution contract and measured region-backend coupling. **Status:** Active; the current statement of scheduler isolation (supersedes the Phase 2 notes in [AURORA-INDEPENDENT-ENGINE.md](AURORA-INDEPENDENT-ENGINE.md) §38). Inventory, adapter files and stage status corrected on 2026-10-25 against `scripts/test_independence_policy.py`, `perf/AsyncPathValidity` and [AURORA.md *Current state*](AURORA.md) (PR #62 docs pass); "Evidence and current gaps" below is the 2026-10 review, kept as history with dated corrections.
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

Phase 2 of [AURORA-INDEPENDENT-ENGINE.md](AURORA-INDEPENDENT-ENGINE.md).
This document extracts the current dependencies and specifies the first migration slice.
It does not introduce a scheduler, worker pool, or replacement backend.

Source baseline: local 99801ba; architecture proposal dec5d3c. Existing configuration,
metrics, plugin compatibility, and completed tests remain in place.

## Current dependency map

| Domain / consumer | Current execution dependency | Required behavior | Migration boundary |
| --- | --- | --- | --- |
| Async path completion | `perf/AsyncPathCompletion.java` depends on `OwnerHandoff`; `execution/RegionOwnerHandoff` is the only backend adapter | Defer mutation to the current entity owner; handle rejection and retirement; release pending state | **Done** — before applying, `perf/AsyncPathRecompute.deliver` checks the delivered owner (`AsyncPathValidity.sameSubject`), path identity (`stillRequested`) and the navigation generation of MC feature patch 0047 (`sameGeneration`); each discard is counted in `/perf async` |
| Path computation | `perf/AsyncPathProcessor.java` on the Resource Governor's `PATHFINDING` lane (`execution/GovernedLane`, since 2026-10-20) | Snapshot-only CPU work; defined saturation, cancellation, shutdown | Threads/queue governed and shown in `/perf governor`; admission, telemetry and lifecycle stay in `AsyncPathProcessor`. Separate CPU admission contract; do not conflate with owner dispatch |
| Player replies | `command/SourbyReply.java`, player scheduler | Deliver on current player owner; handle disconnect | Public compatibility API remains until a shared handoff contract proves useful |
| HUD | `hud/HudBars.java`, global periodic task plus player schedulers (boss-bar show/hide, actionbar sends) | One shared snapshot cadence; player-owned show/hide/preferences and actionbar mode; cancellation on close | Separate aggregation cadence from player mutation |
| Metrics collection | `perf/PerformanceCollector.java` through the `execution/region/RegionBackend` adapter (`FoliaRegionBackend`); it names no scheduler internal | Read counters/tick-rate cheaply; immutable publication; lifecycle-owned collector | Metrics-source adapter, not task dispatcher |
| Updater | `update/SourbyUpdater.java`, `UpdateApplier.java`, `UpdateNotifier.java`, public async/player schedulers | Blocking network/admin work away from gameplay; owner-bound player notification; shutdown cancellation | External I/O and notification contracts stay distinct |
| Engine configuration | `SourbyCraftConfig.java` through `config/upstream/UpstreamConfigBridge` (`CanvasConfigBridge` calls the engine's global/world reloads) | Explicit operator reload; preserve cached-setting caveats | Config bridge, not scheduling API |

Paths above are relative to `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/`.
The Minecraft integration sources are feature patches 0006 (the flag-gated call into `perf/AsyncPathRecompute`
and the navigation seams), 0039 (the immutable mob snapshot), 0047 (the navigation generation) and 0082
(no shared path-type cache off a region thread) under `sourbycraft-server/minecraft-patches/features/`.
Generated upstream code is read-only evidence, never the patch source of truth.

## The Aurora region system

`execution/region` states what a region is in SourbyCraft's own terms. Most of it was already
Aurora-owned and mis-filed: `RegionMetricsRegistry` tracks region identity, merges, retirement and
enumeration without naming a single backend type, under `perf`, framed as telemetry.

* `AuroraRegion` — one region, identified by world, region **and generation**. The generation is
  not decoration: regions split and merge as players move, so a region id can outlive the region
  that bore it, and two readings are comparable only when the generation matches.
* `RegionTopology` — the regions that exist, including recently retired ones. Retired generations
  are kept deliberately: a region that merged away thirty seconds ago holds the only record of what
  those chunks were doing, and dropping it on retirement is how a fifteen-minute window ends up
  empty.
* `RegionBackend` / `FoliaRegionBackend` — the read-only questions the engine still answers: the
  global tick handle's metrics and the configured tick rate (`RegionTickRate`), plus, added later,
  the region the calling thread ticks (`CurrentRegion`), per-world population summed from the
  regions (`RegionPopulation`) and the engine pools' configured sizes (`EnginePools`).
  `MoonriseRegionOwnership` answers ownership queries and chunk-load continuations
  (`RegionOwnership`, `RegionChunkLoading`).

Every region runs on `ExecutionLane.REGION_TICK`, with no way to place one elsewhere. That is the
whole point: a region *is* the unit of gameplay ownership, so a region on another lane would be
gameplay mutated by a thread that does not own it. It is also why "world load on one core, plugins
on another" is not something a region system can offer — a region thread runs the entire tick for
what it owns, plugin handlers included.

With this in place the backend's internal scheduler is named in exactly **seven** pinned files
(`SchedulerCouplingTest.test_the_backend_is_named_only_inside_the_adapters`, recounted 2026-10-25;
"two" when this slice was written, "three" on 2026-10-07). Four adapters: `execution/RegionOwnerHandoff`,
`execution/region/FoliaRegionBackend`, `awf/world/AuroraWorldsService` (AWF world lifecycle on the global
tick) and the relaxed-thread-checks world-data pool, `compat/luminol/RegionizedWorldDataPool` with its lease
helper `WorldDataAccess` (owner decision 2026-10-22; dead code while thread checks are enforced). Two
read-only diagnostics views: `perf/LiveSchedulerSource` (`/perf scheduler`) and `perf/RegionLocator`
(`/perf region`). The rest of `perf` names no scheduler internal. The independence tests pin the file
set, not just the symbols, so replacing the backend stays an edit to a known list rather than a
search.

## What counts as coupling

The public Bukkit-facing Folia API — `io.papermc.paper.threadedregions.scheduler` — is not
coupling. Depending on a published contract is the goal. What matters is the internal surface:
`TickRegionScheduler`, `RegionizedServer`, `RegionizedWorldData`, `ThreadedRegionizer`,
`TickRegions`.

## Measured inventory

Counted by `scripts/independence_policy.py` and pinned by `scripts/test_independence_policy.py`,
so this list is designed against a fixed set and new coupling has to be added deliberately.

History: five sites in SourbyCraft-owned code, across eighty files, when this slice was written;
11 sites in three files on 2026-10-07. Recount on 2026-10-25 (`independence_policy.scheduler_sites`,
non-comment lines): **59 lines in the seven pinned files**, naming the symbols in
`SchedulerCouplingTest.APPROVED`:

| Where | Lines | Symbols | Why |
| --- | ---: | --- | --- |
| `execution/region/FoliaRegionBackend.java` | 15 | `RegionizedServer`, `TickRegionScheduler` | global tick handle metrics, tick rate, the calling thread's region, region populations |
| `execution/RegionOwnerHandoff.java` | 1 | `EntityScheduler` | the single adapter behind `OwnerHandoff`; hands work to an entity's owning region |
| `awf/world/AuroraWorldsService.java` | 5 | `RegionizedServer` | world creation, unload and per-region saves queued on the global tick |
| `compat/luminol/RegionizedWorldDataPool.java`, `WorldDataAccess.java` | 8 + 4 | `RegionizedWorldData` (and the region types) | the relaxed-thread-checks world-data pool and its lease |
| `perf/LiveSchedulerSource.java` | 23 | `TickRegions`, `TickRegionScheduler`, `RegionizedTaskQueue`, `ThreadedRegionizer`, `RegionizedServer` | `/perf scheduler`, read-only |
| `perf/RegionLocator.java` | 3 | `ThreadedRegionizer`, `TickRegions` | `/perf region <world> <x> <z>`, read-only |

Feature patches reach the scheduler only at recorded sites
(`test_integration_patches_reach_the_scheduler_only_where_recorded`): Paper 0005 and 0019
(`RegionizedServer.getGlobalTickData`), Paper 0014 and MC 0062 (`RegionizedServer.getInstance`),
MC 0082 (`TickRegionScheduler.getCurrentRegionizedWorldData() != null`: am I on a region thread,
so may the path-type cache be used; split from 0006 on 2026-10-22), and the relaxed-thread-checks
patches MC 0084, MC 0085 and Paper 0029 (`isGlobalTickThread`, `getCurrentRegion`,
`RegionizedWorldData` and the region types, recorded in the test as hunks that run only while thread
checks are relaxed; MC 0084's global tick counter answer for `getTickCount` applies in every mode).

Further patches *edit* `TickRegionScheduler.java`, `TickRegions.java` or `RegionizedTaskQueue.java`
rather than call into them — among them `0002` (thread-count default), `0013`/`0079` (tick metrics
hooks), `0025` (watchdog opt-in), `0031`/`0032` (save tickets and cancelled tasks across merge and
split), `0045`/`0049` (queue telemetry) and `0084` (relaxed checks). Those are engine modifications,
not call-site coupling, and a contract does not remove them.

Canvas/engine coupling was two sites, both config reload bridges in `SourbyCraftConfig.java`; five
sites in two files on 2026-10-07. On 2026-10-25 it is **four symbols in three files**
(`CanvasSourceCouplingTest.APPROVED`): the two reloads in `config/upstream/CanvasConfigBridge.java`,
`SaveAllTicket` in `awf/world/AuroraWorldsService.java` and `SchedulerUtil` in
`perf/LiveSchedulerSource.java`; plus three engine symbols added by MC feature patch 0086
(`PATCH_APPROVED`: `GlobalConfiguration.getInstance`, `EntityRegionTransferState`,
`EntityRegionTransferState.PluginTeleportAsyncState`).

### What the count does and does not settle

The call sites are few, and it is tempting to read that as "the scheduler is nearly decoupled
already". It is not. A small number of call sites says the *mechanical* edit is small; it says
nothing about the semantics behind them, and the requirements below — owner identity across
dimension transfer, retirement, stale-result validation — are where the work actually is. Counting
imports measures the surface, not the contract.

## Progress against this document

The first slice is implemented. `OwnerHandoff` and `Admission` state the contract,
`RegionOwnerHandoff` is the only place `EntityScheduler` is named, and the async path delivers
through it (since 2026-10-22 from `perf/AsyncPathRecompute`, which patch 0006 calls) and compares
the delivered owner against the entity the solve was computed for, discarding the result when a
dimension transfer has replaced it. The wandering-trader coordinator (MC 0068) is the second user of
`OwnerHandoff`; the other owned services use the published `threadedregions.scheduler` API directly
or the `execution/region` contracts (Stage 2 is therefore partial, see *Status boundary*).

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

This is why no epoch field was added at first: an epoch would need incrementing at every one of
those sites, widening the diff against upstream, to derive a token the state already
provides. The trade is that two identical-but-distinct `Path` objects cannot be told
apart — but `moveTo` only reassigns when `!newPath.sameAs(this.path)`, so a path that
compares the same is one the mob is still following, and refreshing it is correct.

*Correction (DEV-A F3, landed as MC feature patch 0047; re-checked 2026-10-25):* path identity is
not sufficient. A `stop()` that finds the path already `null`, or a second stop or retarget, leaves
no new path object for the check to see, and an old solve could be re-armed. Patch 0047 therefore
adds a navigation generation that advances on `stop()`, `moveTo` and the other invalidations; the
solve captures it, and delivery discards the result when it changed
(`AsyncPathValidity.sameGeneration`, checked after the subject and path-identity checks in
`AsyncPathRecompute.deliver`; `AsyncPathValidityTest`, `AsyncPathRecomputeTest`).

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
| Invalidate a result | subject, path-identity and navigation-generation checks in `AsyncPathRecompute.deliver` (generation from MC 0047) | `AsyncPathRecomputeTest` (`aReplacedEntityAStalePathOrAnInvalidationDiscardsTheResult`), `AsyncPathValidityTest` |

The last row was the weak one when this was written: the check lived in patched Minecraft code
and rested on the traced invalidation set rather than on a test. Since 2026-10-22 the checks are
Sourby source and `AsyncPathRecomputeTest` runs them on a real `PathNavigation`; no live-server run
of the invalidation paths is recorded. `setEnabled(false)` stops callers *offering* work and is
distinct from `shutdown()`, which refuses work already being offered.

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

> **Historical (the 2026-10 review, before the 2026-10-22 split of patch 0006).** Status on
> 2026-10-25: the gaps the next two paragraphs name are closed in code. Completion compares the
> delivered entity (`AsyncPathValidity.sameSubject`), the path the solve was requested for and the
> MC 0047 navigation generation before applying; the solver no longer receives the live `Mob`:
> MC 0039 captures an immutable `MobPathSnapshot` on the owner (`MobPathSnapshot.captureDetached`,
> called from `AsyncPathRecompute.offload`), and the solve reads only that and the region snapshot.
> The evaluators that write to the mob are still refused the offload (`solveWritesMob`). Since PR #62
> an offload is declined outright on a thread that does not own the mob (`RegionOwnership`), and every
> decline runs the synchronous recompute. Code written and statically reviewed; not built; not tested
> by the owner. The feature stays default off (`aurora.entity.async-pathfinding`).

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

Both are now refused rather than raced: `AsyncPathRecompute.solveWritesMob` (true for
`AmphibiousPathNavigation` and its subclasses, and for `Sniffer`; until 2026-10-22 per-class overrides
`PathNavigation.sourbyAsyncSolveSafe` / `Mob.sourbyPathfindingMutatesMob` in MC patch 0006) gates the
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

`SourbyReply` grouped all non-player senders into a direct reply path on the strength of a
comment. The comment was wrong. `BaseCommandBlock.sendSystemMessage` writes `lastOutput` on the
block entity and calls `onUpdated`, behind both `AsyncCatcher.catchOp` and Folia's
`threadCheck()` — so replying to a command block from an off-thread worker threw, and the
catch-all logged it and moved on. A command block running `/ping` or `/speedtest` silently got
no output.

The distinction the document asked for now exists as `SourbyReply.hopFor`: an entity sender goes
to its own scheduler, a `BlockCommandSender` to the region owning its block, and console and RCON
run directly because they write to a logger or a connection buffer and own no world state. It is
a separate function from `run` because the safety decision should be testable without reaching a
static scheduler, and it is pinned by tests — including that a non-player *entity* sender, which
`/execute as` produces, needs an entity hop rather than the old direct path.

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
   **Examined and declined — see below.**
6. Consider alternative execution backends only after the contract suite, region-transfer tests,
   persistence/compatibility checks, and relevant workload measurements pass.

## The HUD does not want this contract

Step 5 asks whether the owner handoff should be reused for the HUD. It should not, and the
reasons are the contract's own requirements failing to apply rather than a preference.

`HudBars` runs one global task every twenty ticks that reads a metrics snapshot and updates three
shared boss bars. Per-player work runs on each player's own scheduler: boss-bar visibility changes,
and in actionbar mode the line sent to each actionbar viewer on every refresh.
Against the nine requirements:

* There is no **pending bookkeeping** to release. Async pathfinding needed the contract because
  `sourbyAsyncPathPending` gates the next submission and a lost callback strands the mob forever.
  Nothing in the HUD is gated on a callback arriving.
* For the boss bars there is no **stale result** to invalidate. They recompute from the newest
  snapshot every tick; a delivery that arrives late simply carries what was true when it left, and
  one second later it is replaced anyway. The path-identity check exists because a path applies to
  a route the mob may have abandoned. A boss bar has no equivalent.
  Actionbar mode does have a stale delivery. A refresh is queued on the player's scheduler and can
  run after the player turned the bar off, switched mode, quit or the HUD closed, and would then
  restore a line that was just cleared. Each actionbar subscription therefore carries a session
  token from the `SESSIONS` counter. The updater captures the token when it queues the send, and the
  queued task sends only if the player is still a viewer and the entry still holds that token
  (`stillSubscribed`). Every transition replaces or removes the entry, so an older refresh fails the
  check. The check runs on the player's own scheduler, the same thread the player's commands use.
* **Retirement is benign for the boss bars.** `applyShown` passes no retirement callback to
  `player.getScheduler().run(..., null)` and ignores the returned task, so a player who leaves
  before the show/hide task runs is silently skipped. `applyPerf` is the exception: its retirement
  callback removes the player from `PERF_VIEWERS` and `PERF_ACTIONBAR`. Actionbar sends also pass
  `null`, and a send to a player who has left is skipped the same way. For gameplay that would be
  a defect; for showing a bar to someone who has gone, it is the correct outcome. This is
  understood, not overlooked.
* **Current identity** does not bite. The contract delivers the live owner because a dimension
  transfer replaces the underlying entity. Players are not replaced that way, and a player who
  reconnects is a new session that re-reads its own preferences.

One concurrency question was worth checking and came back clean. The global task mutates shared
`BossBar` objects while player-region threads add and remove viewers on the same objects. Adventure
keeps its listener list in a `CopyOnWriteArrayList`, so the structural part is safe; `name`,
`progress` and `color` are plain fields and can be read one update stale, which is invisible on a
bar that refreshes every second.

So the separation the dependency map asks for — aggregation cadence apart from player mutation —
already exists, and adopting the handoff would add admission and retirement semantics to a place
that needs neither. Doing it for symmetry is how a contract turns into ceremony.

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

Stage status (2026-10-25), the same statement as [AURORA.md *Current state*](AURORA.md) and
[AURORA-INDEPENDENT-ENGINE.md §10](AURORA-INDEPENDENT-ENGINE.md#10-aurora-scheduler-program):

| Stage | Status | Evidence |
| --- | --- | --- |
| 1. Contract extraction | IMPLEMENTED | this document, [dependency-ledger.md](dependency-ledger.md), the pinned inventories |
| 2. Aurora-owned dispatch | PARTIAL | `OwnerHandoff` carries async-path delivery and the wandering-trader handoff; the `execution/region` contracts carry owned teleports, command blocks, data commands and the guards; other owned services use the published `threadedregions.scheduler` API directly (HUD, replies, updater, bridge), which is not counted as coupling |
| 3. Backend isolation | IMPLEMENTED for SourbyCraft's own sources (pinned) | internal scheduler symbols only in the seven files above; feature patches only at the recorded sites; not a claim about the engine baseline, which *is* the backend |
| 4. Specialized execution | PARTIAL | the async-path lane and the Resource Governor's lanes exist, default off or idle; no benchmark shows a benefit |
| 5. Scheduler replacement | PLANNED, not started | the parked EDF backend would swap the pool inside the same region model and is not an independent scheduler |

Existing baseline/soak results describe the tested workloads and revisions; they do not qualify a
future scheduler or prove absence of all leaks. Keep the independent-engine roadmap as the focus,
with correctness preceding backend replacement. The upstream Spark viewer remains in use as
required by section 37.
