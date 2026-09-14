# Aurora execution contract extraction

Phase 2 of [AURORA-INDEPENDENT-ENGINE.md](AURORA-INDEPENDENT-ENGINE.md).
This document extracts the current dependencies and specifies the first migration slice.
It does not introduce a scheduler, worker pool, or replacement backend.

Source baseline: local 42204c7; architecture proposal 1cb4eec. Existing configuration,
metrics, plugin compatibility, and completed tests remain in place.

## Current dependency map

| Domain / consumer | Current execution dependency | Required behavior | Migration boundary |
| --- | --- | --- | --- |
| Async path completion | `perf/AsyncPathCompletion.java` directly accepts internal `EntityScheduler`; called from Minecraft patch 0006 | Defer mutation to the current entity owner; handle rejection and retirement; release pending state | First candidate: owner-result handoff |
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
| `perf/AsyncPathCompletion.java` | `EntityScheduler` | hand a finished path back to the owning entity's region |

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

The worker also passes a live `Mob` into the path solver. Calling that input read-only does not
prove snapshot safety. The block snapshot and the complete mob/evaluator input set require
separate review. The feature remains default-off; this extraction does not qualify enabling it.

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
