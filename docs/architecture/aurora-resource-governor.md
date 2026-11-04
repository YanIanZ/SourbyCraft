# Aurora Resource Governor

> **Role:** Resource Governor specification. **Status:** Active; feature is PARTIAL (three governed lanes, no CPU arbitration). Budget and STORAGE-lane sentences corrected against code on 2026-10-07; lanes, users, ledger rows and the executors outside the governor re-checked against the PR #62 tree (`build50/pm-completion`) on 2026-10-25. The PR #62 changes named below are code written and statically reviewed; not built; not tested by the owner.
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

Build 47 pillar "Aurora Execution Fabric and Resource Governor". This document is the
specification the release doc refers to. Before it existed the pillar had a name and no contract.

## What it governs

Only work SourbyCraft itself puts on the machine. The governor never resizes these:

- region tick threads (`aurora.cpu.cores`, sized by the region scheduler),
- chunk workers and the engine background pool,
- Netty event loops,
- Folia's own schedulers.

Upstream sizes those; `/perf lanes` attributes their CPU. The governor does not measure them
either: `/perf governor` (and the startup log) prints only their **configured** sizes, as the
thread ledger (`execution/ThreadLedger`, audit ENG-9): region tick, chunk workers, chunk I/O,
Netty event loops, the async scheduler core, async pathfinding, and the Bridge I/O and Storage
lanes, each with the setting its number comes from, against the processor count. The ledger is a
list of ceilings, not load, and it changes no default. (The class javadoc of
`execution/ResourceGovernor` still says the governor "neither resizes nor reports" these pools;
the ledger is the reporting it does.)

## Contract

1. **Static budgets.** Each governed lane has a fixed thread count and a fixed queue capacity. The
   budget comes from `aurora.scheduler.*` (`bridge-io-threads`, `bridge-io-queue`,
   `storage-threads`, `storage-queue`; RESTART_REQUIRED), whose defaults are the code defaults
   below. The first configuration read at boot sets them; a later `/sourbycraft reload` that
   changes them is only remembered and shown as pending (`ResourceGovernor#configure`,
   `#pendingRestart`). Nothing watches load and resizes a lane, because the Aurora no-auto-tuning
   rule forbids it. `PATHFINDING` is not configurable.
2. **Reject, never caller-runs.** Work beyond threads plus queue throws
   `RejectedExecutionException` (`AbortPolicy`). Running overflow on the submitting thread would
   put it on a scheduler thread or a region owner, which is what the lane exists to protect.
3. **Bounded memory.** Queues are `ArrayBlockingQueue`s. Idle lanes release their threads after
   30 s (`allowCoreThreadTimeOut`).
4. **Attributable.** Lane threads carry a name prefix that `ExecutionLane` maps to an existing
   lane, so `/perf lanes` accounts for their CPU.
5. **Observable.** `/perf governor` shows the thread ledger above, one `Pending restart` row per
   `aurora.scheduler.*` key a reload changed (running → pending value), then, per lane that has
   been used: the budget, the active, queued and peak-queued counts, and the submitted,
   completed, failed and rejected counts.
6. **Shutdown.** `SourbyCraftBootstrap#close()` stops every lane last, after the other services
   (and after MultiProfile's own stop); each lane gets up to 10 s to finish what it holds
   (`shutdown()` then `awaitTermination`). `close()` runs before the region saves, so the final
   AWF commits of a stop do not use the lane: `AwfEngine#closeAll()` commits and closes every
   storage on the calling (stopping) thread.

## Lanes

| Lane | Budget | Thread prefix → `ExecutionLane` | Used by |
| --- | --- | --- | --- |
| `BRIDGE_IO` | max(2, cores/4) threads, queue 256 | `SourbyCraft-BridgeIO-` → `PLUGIN_ASYNC` | Aurora Bridge: async tasks of bridged legacy plugins. Folia's async scheduler only times them. A rejection counts as the plugin's rejected operation. |
| `STORAGE` | 1 thread, queue 64 | `SourbyCraft-Storage-` → `WORLD_IO` | (1) Aurora World Fabric commits (`AwfWorld#save`, submitted by `awf/AwfEngine`; idle when no world uses AWF). (2) AWF MultiProfile repository and ownership steps (`awf/multiprofile/ProfileEngine`; off when MultiProfile is disabled). Since PR #62 they go through `ProfileIoQueue`, a FIFO of up to 4096 steps that hands the lane one step at a time, so profile traffic holds at most one lane slot; before, each step was submitted to the lane directly and a join or quit burst could fill its queue and get AWF commits rejected. On the default single thread a slow profile step still delays the AWF commit queued behind it. `/awf redis` diagnostics no longer use this lane: since PR #62 they run on the administrative I/O executor (`util/VirtualExecutor`) and answer `Busy: ...` when it refuses them. |
| `PATHFINDING` | max(1, cores/4) threads at `NORM_PRIORITY - 1`, queue 1024 (`PATHFINDING_QUEUE`); not configurable | `SourbyCraft-AsyncPath-` → `ASYNC_COMPUTE` | Async pathfinding solves (`perf/AsyncPathProcessor`, started only when `aurora.entity.async-pathfinding` is on). The navigation checks capacity before it builds a snapshot, so a full queue usually declines the offload without offering it to the lane (`/perf async` `declined`). A submission the lane still rejects (the queue filled in between, or shutdown) is counted once, as `/perf async` `refused`, and is excluded from the lane-rejection incident reading. Both run the vanilla synchronous recompute on the mob's region thread for that slot. Budget, priority and queue are the values the pool had before it moved here (2026-10-20). |

`cores` is the JVM's available-processor count, read once when the lane is created.

## Not in scope yet

- ~~Operator-configurable budgets~~ — implemented as `aurora.scheduler.*`, RESTART_REQUIRED
  because a lane is created once and never resized (`config/AuroraConfig.Scheduler`; corrected
  2026-10-07).
- Moving existing executors under the governor. These SourbyCraft-owned executors run outside
  it, each with its own bound (full table with names and shutdown owners in
  [executor-inventory.md](executor-inventory.md) §1): the administrative I/O executor
  (`util/BoundedIoExecutor`, 64 admitted virtual-thread tasks, no queue), the startup-index pool
  (at most 4 threads, boot stage only), the `level.dat` writer (1 thread, queue 1), the stall
  recorder's probe (1 thread, queue 1), the AWF maintenance driver, the AWF shutdown-deadline
  timer, the AWF Redis lease-renewal and watchdog schedulers (1 thread each), the MultiProfile
  idle sweeper and heartbeat (1 thread each), the AbyssLink event loop (1 thread), and the
  metrics collector and GC sampler (1 thread each). `AsyncPathProcessor` moved to the
  `PATHFINDING` lane on 2026-10-20. Any further move is a separate, owner-approved change
  (`docs/TODO.md`).
- Any cross-lane CPU arbitration. The governor bounds concurrency; it does not schedule CPU
  time.
- `execution/FairLane` (a per-owner fair-queue lane) exists in source but is **not wired**:
  `BRIDGE_IO` still runs on a plain `GovernedLane`. Nothing about it is measured.
