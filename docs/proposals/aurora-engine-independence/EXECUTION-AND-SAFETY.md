# Aurora Execution: Region Ownership, Scheduling, Async Work and Safety

**Status: proposed deeper semantics for existing execution architecture.** Do not treat example types as compiled APIs.

## The fundamental boundary

Folia-style regions can merge, split and retire; the authority to mutate a chunk/entity is transient. The existing `AuroraRegion` identity includes generation; location and identity must be revalidated when a callback eventually executes. Never store `ThreadedRegion` references or mutable `ServerLevel` state inside long-lived off-thread work and then apply them without checking the current owner.

The correct operation pipeline:

~~~mermaid
flowchart TD
  A["Request on current thread"]
  B["Check admission, permissions, version"]
  C["Capture immutable snapshot on owner"]
  D["Async CPU/I/O on bounded lane"]
  E["Schedule result to live owner"]
  F{"Still same subject, generation, world, epoch?"}
  G["Apply mutation"]
  H["Discard safely + count stale result"]
  A --> B --> C --> D --> E --> F
  F -->|Yes| G
  F -->|No| H
~~~

## Typed work contracts (conceptual, NOT existing API)

~~~java
record AuroraWorkToken(
    UUID worldId, UUID entityId, long ownerGeneration,
    long requestRevision, long deadlineNanos
) {}

interface AuroraOwnerDispatcher {
    CompletionStage<DispatchOutcome> dispatchEntity(
        UUID entityId, AuroraWorkToken token, Runnable ownedAction);
    CompletionStage<DispatchOutcome> dispatchRegion(
        UUID worldId, int chunkX, int chunkZ,
        AuroraWorkToken token, Runnable ownedAction);
}
~~~

A full implementation must specify whether work is executed inline when already owned, how cancellation and retirement fire, which thread completes futures, and whether callbacks may reenter. No synchronous wait or arbitrary client task may force execution on another region.

## Strict guard and compatibility stance

- In **strict** mode, wrong-owner access rejects clearly with useful diagnostics. Do not weaken owner checks to support legacy plugins by silently writing region data via a ticket; it can lose data during merge/split.
- In **relaxed/legacy bridge** mode, **do not claim correctness or production qualification**. Inventory the exact unsupported operations, their failure semantics and safe fixes. Prefer dispatching to correct owner and isolating legacy calls over changing regionizer invariants.
- The documented `compat/luminol/RegionizedWorldDataPool` explicitly warns that ticketing does not guarantee atomic merge/split and can silently lose writes. That limitation remains unresolved by a new engine brand.

## Scheduler architecture

Use Folia's region/entity/global owner as the mutation scheduler. Aurora can own **admission and resource governance** for compute, I/O and plugin bridge tasks through existing `ResourceGovernor` and execution contracts. The parked EDF scheduler is **not active**. Replacing the tick scheduler is an independent research initiative requiring full queue, fairness, merge/split, watchdog, lag and cancellation proof; do not enable it merely because it is “independent”.

Existing `BRIDGE_IO`, `STORAGE` and `PATHFINDING` governed lanes have bounded queues. Proposed further quality work: per-account ordered fairness for profile I/O, distinct storage admission from expensive profile queries, bounded priority classes with starvation proof and cancellation propagation. Do not increase thread count without repeatable benchmark; past chunk-worker measurements show mean and tail may trade off.

## Cross-region operations

Never hold an owned-world lock while sending a message to another region and waiting. For moves, remote block actions or interactions across owners:
- capture immutable request and validate initiator;
- dispatch to target owner with lifetime and revision check;
- commit only on target owner;
- return immutable result and schedule initiator notification to its **current** owner;
- on cancellation, disconnect, retirement or version mismatch -> safe refusal/abandon with metrics.

Complex multi-region operations (e.g. two inventories) need a distinct transaction design or co-location; an ordinary task queue does not supply atomicity.

## Memory and visibility

A concurrent map makes access to the **map** thread-safe under its contract, not mutable entities reachable through its values. Use immutable data transfer, single-writer enforcement for SWMR structures, explicit revision snapshots, bounded arrays/buffers, no use-after-release and cache invalidation on owner-generation change. Avoid object pooling unless a real allocation profile shows benefit.

## Failure and shutdown

- Work has operation ID, target owner, admission timestamp, deadline, bounded payload and known rejection reason.
- No calling-thread fallback on rejected asynchronous work: tick thread must not accidentally execute long task.
- Shutdown stops admission, cancels/waits for safe work, publishes required durable state and only then closes necessary storage. Unknown durable outcomes remain UNKNOWN/RECOVERY, not SUCCESS.
- Observability includes queue wait p95/p99, rejected count, stale result count, owner mismatch, region merge/split, time blocked on owner threads and shutdown errors.

## Acceptance examples

Entity moves regions during snapshot compute; region merges while callback queued; player disconnects; plugin attempts synchronous world access off-owner; Redis down while world saves; heavy chunk generation saturates CPU pool. Every case must have a deterministic safe result under recorded plugin set and exact candidate SHA.
