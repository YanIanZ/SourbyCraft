# Aurora Executor Inventory

Roadmap items *"inventory all Sourby-owned executors"* and *"audit implicit common-pool usage"*
(`docs/AURORA-TASKS.md`). Measured over `sourbycraft-server/src/main/java` on 2026-09-20.

This exists because "the multithreading needs an overhaul" is a reasonable thing to suspect and
a bad thing to act on unchecked. Here is every thread Aurora creates.

## 1. Every Sourby-owned executor

| Owner | Threads | Bound | Runs |
|---|---|---|---|
| `perf/PerformanceCollector` | 1 platform, `SourbyCraft-PerformanceCollector` | one thread, one loop | Samples telemetry once a second |
| `perf/GcTracker` | 1 platform | one thread | GC notification listener |
| `util/VirtualExecutor` → `BoundedIoExecutor` | virtual, `SourbyCraft-IO-` | **Semaphore, ≤64 admitted, no queue** | Administrative blocking I/O only — never world data |
| `perf/AsyncPathProcessor` | `cores / 4` platform, `SourbyCraft-AsyncPath-` | **`LinkedBlockingQueue(1024)`**, inline on rejection | Off-region A*, **disabled by default** |

Two shutdown hooks exist (`bootstrap/SourbyBootstrap`, `update/UpdateApplier`); neither is a
running thread.

**At rest Aurora owns two threads.** Everything else is opt-in or idle-capable.

## 2. Implicit common-pool usage: none

`CompletableFuture`'s `*Async` methods without an explicit executor run on
`ForkJoinPool.commonPool()` — uncontrolled parallelism that competes with region ticking and
answers to no configuration. Aurora uses none:

- no `supplyAsync`, `runAsync`, `thenApplyAsync`, `thenComposeAsync` or `handleAsync` anywhere
  in Aurora's source,
- the only two `whenComplete` calls are the non-`Async` form, which runs on the thread that
  completes the future rather than handing work to the common pool,
- the sole mention of `ForkJoinPool` in the source is a comment in `ExecutionLane` explaining
  that upstream's `Util.backgroundExecutor()` is one, and that it is deliberately attributed to
  its own lane instead of being folded into the chunk-worker lane.

## 3. What this means for CPU efficiency

Aurora's own threading is small, bounded, named and attributable. There is no pool here that
grows without limit, no hidden common-pool work, and nothing that would be improved by being
rewritten. So when CPU sits idle under load, the cause is not in this table.

The three things that actually bound CPU use are documented in
[region-parallelism.md](region-parallelism.md):

1. **Region tick threads** — was `cores / 2`, now every processor, configurable through
   `aurora.cpu.cores`. Measured: 32% better mean MSPT, 62% better tail.
2. **Region count** — a region is a connected component of loaded chunks and ticks on exactly
   one thread, so parallelism is bounded by how many *disconnected* areas carry load. No thread
   count raises this, and no rewrite of the table above changes it.
3. **Heap configuration** — outside the server entirely. A container-relative maximum that
   leaves no room for metaspace, code cache, thread stacks and direct buffers produces stalls
   that look exactly like a threading problem.

The one lever Aurora owns that moves work *off* region threads is async pathfinding, which is
implemented, instrumented, and **default-off pending T10 qualification**. Enabling it without a
certified baseline is the thing §4.5 forbids, and the `inline` counter in `/perf async` exists
precisely because an undersized pool makes it cost more than it saves.
