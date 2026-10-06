# LeafPile-derived Aurora EDF backend

Status 2026-10-06: source implementation and six regression methods written;
build, tests, boot, ownership/shutdown acceptance and performance profiling are pending
with Claude. Codex ran no build, tests, boot or benchmark. Static inspection included
the pinned LeafPile 1.2.0 JAR bytecode and upstream source.

## Status update 2026-10-07: patch 0028 is HELD (parked, not in the build)

Sources: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md) ("OWNER DECISION 2026-10-07", "0028 hold executed"),
`sourbycraft-server/minecraft-patches/parked/README.md`, the materialized tree and the baseline patch
`SchedulerUtil.java.patch`. The sections below describe the **parked** patch and class, not the current build;
"switches `SchedulerUtil`", "startup identifies the derived backend" and "commit `ade3b24`" are not current.

- **Decision.** The owner held 0028 on 2026-10-07 until it passes its tests and a boot. It was parked the same day: the patch
  `0028-SourbyCraft-use-LeafPile-derived-Aurora-EDF-backend.patch` and `AuroraEdfSchedulerTest.java` live in
  `sourbycraft-server/minecraft-patches/parked/` (nothing there is read by `applyAllPatches`, and the test is no longer in
  `src/test`). The materialized HEAD was reset from `ade3b24` to `0569a90` (0027). `SchedulerUtil` is back to constructing
  LeafPile's `EDFSchedulerThreadPool` (baseline patch `SchedulerUtil.java.patch`, inspected 2026-10-07). The Codex-owned class
  `sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/threadedregions/scheduler/AuroraEdfScheduler.java` is still in the main
  tree, untracked, compiled and unselected. The patch is not applied by any current build.
- **What ran before the hold.** 0028 was compiled into the demo jars built 2026-10-07 00:00 and 00:36 and ran on Sourby Demo
  ("Starting Aurora EDF region scheduler" in the log); the `plugins-10` bench ran on it. No error from those runs is recorded in
  the log. No region split/merge, plugin-disable, cancellation-during-execution or shutdown acceptance, no soak, and no
  AFFINITY or WORK_STEALING boot is recorded, and no performance comparison was made.
- **Tests.** `AuroraEdfSchedulerTest` passed 6/6 when run once in isolation before the hold (`parked/README.md`; log). The
  independence policy test failed on it: `scripts/test_independence_policy.py` with 0028 applied was 308 run, 2 failures
  (`CanvasSourceCouplingTest` on `AuroraEdfScheduler`, `SchedulerCouplingTest` on `TickRegionScheduler` reached by a patch outside the
  recorded set); with 0028 parked the Python suite is 327/327 (log). No full Gradle suite with 0028 is recorded.
- **Unpark gate** (`parked/README.md`): the policy test green with the recorded set updated, the full Gradle suite, and a boot on
  Sourby Demo with the user's plugins. To unpark: `git am --3way` the patch onto `sourbycraft-server/src/minecraft/java`, move it
  back to `features/` with the next free number, restore the test from `parked/test/`, then run
  `python3 scripts/patch_surface.py --check-rebuild`. Not met as of 2026-10-07.
- Acceptance list, item by item: **1** test run done (6/6, isolated), binary compatibility of the volatile field with AFFINITY and
  WORK_STEALING not done; **2** boot and startup identification seen on the demo, ordinary dispatch ran under the bench; split/
  merge, plugin disable, cancellation during execution, server stop not recorded; **3** not done; **4** not done (the AWF spawn
  wait is still open, see SPEC B80/B85 and `aurora-world-fabric.md`); **5** not done as an EDF comparison (the only chunk-worker
  spark, <https://spark.lucko.me/C2D7rXIJGY>, is a worker-count observation, see
  [CHUNK-GENERATION-STUTTER.md](CHUNK-GENERATION-STUTTER.md)).

## Source change

`engine/threadedregions/scheduler/AuroraEdfScheduler` derives from
[Tuinity/LeafPile v1.2.0](https://github.com/Tuinity/LeafPile/blob/8c9fa2c5b8ac063075977efc048f6ec0299f24da/src/main/java/ca/spottedleaf/concurrentutil/scheduler/EDFSchedulerThreadPool.java).
The source revision is `8c9fa2c5b8ac063075977efc048f6ec0299f24da`.
Spottedleaf and Tuinity contributors retain attribution; `NOTICE_LEAFPILE` and the
upstream GPLv3 text are included in server resources. Related cancellation/publication
issues are also discussed in [upstream PR #6](https://github.com/Tuinity/LeafPile/pull/6)
by yunuservices, reviewed at `5348d20288bdad210750c5e5a66a67e1e0021cd2`, still open.
This implementation retains the public SourbyCraft task-state field and also repairs
handoff links and deadline rearming; it does not wholesale apply that PR.

Minecraft feature patch `0028-SourbyCraft-use-LeafPile-derived-Aurora-EDF-backend.patch`
(materialized commit `ade3b24`) switches `SchedulerUtil`'s existing **EDF** selection
and startup path to the derived class, and makes `SchedulableTick.state` volatile.
The public field descriptor and existing public/final deadline methods remain compatible
with the retained affinity and work-stealing backends. The LeafPile dependency stays
at 1.2.0; this does not upgrade every LeafPile component or adopt its newer module layout.
Editing Metal's duplicate EDF source would not change the pinned server dependency.

Changes in the derived backend:

- Running-task cancellation marks terminal state under the scheduler lock; the current
  callback may finish, but its next tick cannot reschedule. Repeated cancellation returns
  false. Finished callbacks are terminal too.
- Every queued-to-awaiting handoff registers its linked-list entry before assigning a
  runner, including cancellation replacements and deadline updates.
- A later deadline rearms the waiting runner even when the same task stays assigned.
  Self-replacement clears the previous owner before restoring it. Cancelling the last
  waiting task wakes its worker to release the old deadline blocker; idle parks identify
  the scheduler as their blocker.
- A task's immutable scheduler owner is initialized before publishing task state.
  Cross-thread cancellation reads the volatile task-state reference.
- Missing start deadlines, non-positive worker counts and admission after halt are refused.
  Halt and admission serialize under the existing scheduler lock.
  The locked task-take transition also refuses a callback when halt has already won that lock.

The queue/comparator policy, original blocking park loops, worker count, thread factory,
tick interval and region execution entry point are retained. No additional executor,
busy spin, shortened tick interval, region-read bypass or intermediate-task draining is
introduced. AFFINITY/WORK_STEALING selection remains unchanged; those backends also see
the compatible volatile task-state field. This is an owned derivative of LeafPile, not
an independently designed scheduler or a qualified faster backend.

The concrete EDF runtime class changes to `AuroraEdfScheduler`; a direct cast to the
original LeafPile EDF class is no longer equivalent. Standard `Scheduler` operations and
the existing region scheduler APIs remain the integration boundary to qualify.

There is no new LIVE toggle. Selecting EDF follows the existing boot-time engine setting;
the new implementation requires a **new build and restart**. Startup identifies the
derived backend and its thread count. The current running server was not changed.

## Profile supplied by the user

Report: [RyP3FlAYME](https://spark.lucko.me/RyP3FlAYME).
The HTML viewer returned HTTP 403 to the local request and was unavailable to the web tool;
the public data endpoint returned `application/x-spark-sampler` successfully. Data was
decoded using the viewer's
[protobuf schema](https://github.com/lucko/spark-viewer/blob/05d68bda52ef483122c1498794dbc2cb5e3ada78/proto/spark.proto).
No server command or new profiling session was run by Codex.

Full capture metadata: SourbyCraft Build 47 / Minecraft 26.2, 435.642 seconds from
2026-10-06 15:48:18.559 UTC, async engine 4.5, execution sampling, 10 ms interval.
One grouped node contains eight selected Folia region scheduler threads. CPU metadata:
Intel Xeon E3-1245 v5 at 3.50 GHz, eight available processors; one player. The last-minute
process/system CPU fields are approximately 10.33% / 14.17%. Those are rolling statistics,
not measurements of each worker during a particular stutter. CPU quota, generator,
movement route and certification/noise state are not established by this review.

Only that region-thread group is included. Chunk-generation, storage and network workers
are absent, so this report cannot establish which worker caused terrain-generation delays.
The screenshot's time selection/percentages also differ from the full downloaded capture;
do not interchange their denominators. Park frames indicate a blocked thread:
[JDK LockSupport documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/LockSupport.html).
Their percentage is not equivalent to consumed CPU or to a performance improvement target.

The full capture contains this console-command stack:

```text
RegionizedServer.globalTick
  DedicatedServer.handleConsoleInputs
    AwfCommand.create
      AuroraWorldsService.createAfter
        Bukkit.createWorld / CraftServer.createWorld
          MinecraftServer.initWorld / setInitialSpawn
            PlayerSpawnFinder.getLevelRespawnPos
              ChunkTaskScheduler.syncLoadNonFull
                LockSupport.parkNanos
```

`syncLoadNonFull` has 4,650 ms of recorded inclusive stack time; 4,570 ms is under
its `parkNanos` child. These are accumulated sample weights, not proof of a single
4.65-second pause, a call count, or CPU work. This is a concrete global-thread wait
observation during AWF world creation, distinct from normal EDF waiting between ticks.
The current source's null-region path polls/yields and parks while a non-FULL chunk is
generated. It has not been changed by this EDF increment. Profile metadata inspected
here does not identify the profiler's native sampling event; no CPU/wall-mode claim
is inferred solely from `samplerMode = EXECUTION`.

The earlier [ASP port review](ASP-26.2-AWF-PORT-REVIEW.md) covers `ready.join()` before
creation. Simply making that preparation future asynchronous would still leave this
spawn-generation wait inside `createWorld`. AWF lifecycle/startup is Claude-owned work;
this observation is handed off without editing those files or masking the wait by
skipping spawn safety/world generation.

Current `TickRegionScheduler.descheduleRegion` marks the region non-schedulable rather
than calling `Scheduler.cancel`; no current production call to `updateTickStartToMax`
was found in the inspected tree. The EDF repairs therefore harden lifecycle behavior;
they are not evidence that these defects caused the user's stutter.

## Claude acceptance — held (status per item in the 2026-10-07 section at the top)

1. Apply feature 0028 with the existing patch set, compile and run
   `AuroraEdfSchedulerTest` plus the relevant server suites. Six unexecuted methods
   cover running cancellation, awaiting replacement/completed-task terminal behavior,
   earlier-task handoff after retiming, self-retiming/preemption, parked-cancellation blocker release, and refused/foreign
   admission. Verify API/field binary compatibility after materialization.
2. Boot EDF and verify the new startup identification; exercise ordinary tick dispatch,
   region split/merge, plugin disable, cancellation during execution and server stop.
   In particular, inspect lifecycle producers racing with halt for expected refusal
   handling. Keep all existing region-ownership checks.
3. Stress live self-retiming while a worker is already parked, earlier arrivals,
   spurious wakes, idle reuse and repeated cancellations. Confirm no overlapping
   callbacks and no stale awaiting links or lost wakeups. Boot AFFINITY and
   WORK_STEALING for the shared state-field compatibility change.
4. Separately qualify the AWF creation/spawn wait: preparation blocked, preparation
   already complete, normal/void/plugin generator, create/load/clone and failure cleanup.
   A staged async spawn-initialization design must preserve metadata and region ownership;
   the final create-world call must not merely move to an arbitrary async thread.
5. Capture the relevant chunk workers together with region threads during a fixed fresh
   terrain route. Record hardware/quota, settings and profiler event/window. Compare
   identical workloads with mean and tail tick/chunk latency and certification/noise state.
   Use existing bounded generation-stage samples as complementary evidence. No measured
   speed gain, general stutter fix or release qualification is claimed here.

For the next capture, Claude can use the documented all-thread/per-thread view:

```text
/spark profiler start --thread * --not-combined --timeout 120
```

The flags are documented in [Spark command usage](https://spark.lucko.me/docs/Command-Usage).
Keep the same movement route, fresh-world state, profiler event and sampling settings
across a comparison. No such capture has been started by Codex. If a background profiler
is already running, inspect its status and handle it explicitly before starting another.
