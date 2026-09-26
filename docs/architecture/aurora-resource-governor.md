# Aurora Resource Governor

Build 47 pillar "Aurora Execution Fabric and Resource Governor". This document is the
specification the release doc refers to. Before it existed the pillar had a name and no contract.

## What it governs

Only work SourbyCraft itself puts on the machine. The governor never resizes or reports on these:

- region tick threads (`aurora.cpu.cores`, sized by the region scheduler),
- chunk workers and the engine background pool,
- Netty event loops,
- Folia's own schedulers.

Upstream sizes those; `/perf lanes` attributes their CPU.

## Contract

1. **Static budgets.** Each governed lane has a fixed thread count and a fixed queue capacity. The
   budget comes from code defaults today. Nothing watches load and resizes a lane, because the
   Aurora no-auto-tuning rule forbids it.
2. **Reject, never caller-runs.** Work beyond threads plus queue throws
   `RejectedExecutionException`. Running overflow on the submitting thread would put it on a
   scheduler thread or a region owner, which is what the lane exists to protect.
3. **Bounded memory.** Queues are `ArrayBlockingQueue`s. Idle lanes release their threads.
4. **Attributable.** Lane threads carry a name prefix that `ExecutionLane` maps to an existing
   lane, so `/perf lanes` accounts for their CPU.
5. **Observable.** `/perf governor` shows, per lane that has been used: the budget, the active,
   queued and peak-queued counts, and the submitted, completed, failed and rejected counts.
6. **Shutdown.** The bootstrap stops every lane after services close. Each lane gets up to
   10 s to drain.

## Lanes

| Lane | Budget | Thread prefix → `ExecutionLane` | Used by |
| --- | --- | --- | --- |
| `BRIDGE_IO` | max(2, cores/4) threads, queue 256 | `SourbyCraft-BridgeIO-` → `PLUGIN_ASYNC` | Aurora Bridge: async tasks of bridged legacy plugins. Folia's async scheduler only times them. A rejection counts as the plugin's rejected operation. |
| `STORAGE` | 1 thread, queue 64 | `SourbyCraft-Storage-` → `WORLD_IO` | Aurora World Fabric commits (`AwfWorld#save`). Idle until AWF is wired into world saving. |

`cores` is the JVM's available-processor count, read once when the lane is created.

## Not in scope yet

- Operator-configurable budgets. When they are added, they are RESTART_REQUIRED, because a lane
  is created once and never resized.
- Moving existing executors under the governor: `AsyncPathProcessor`, `BoundedIoExecutor` and
  the startup-index pool. Each already has its own bound, documented in
  [executor-inventory.md](executor-inventory.md).
- Any cross-lane CPU arbitration. The governor bounds concurrency; it does not schedule CPU
  time.
