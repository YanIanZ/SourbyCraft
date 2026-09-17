# Executor and common-pool audit

Covers task-matrix section E, "inventory all Sourby-owned executors" and "audit
implicit common-pool usage", and the SourbyClip half of section L, "thread/executor
ownership audit" and "concurrency/boundedness audit". PRD section 45 is the rule:
SourbyCraft-owned async work must not silently run on `ForkJoinPool.commonPool()`.

Sources of record: `dev.iyanz.sourbycraft` under `sourbycraft-server` and `sourbyapi`,
`dev.iyanz.sourbyclip`, and the downstream patch directories. Upstream Paper, Canvas,
Folia and Moonrise executors are out of scope except where a Sourby patch changed who
reaches them.

## Common pool: clean

No `ForkJoinPool.commonPool()`, no async call without an executor argument, and no
parallel stream in any Sourby-owned source or patch.

`scripts/concurrency_policy.py` enforces this going forward and is checked by
`test_concurrency_policy.py`. It parses argument lists by balancing parentheses rather
than matching lines, because these calls routinely span many lines and a line rule
reports the common, correct form as a violation:

```java
CompletableFuture.supplyAsync(() -> {
    ...
}, executor);          // the executor is four lines below the call
```

Its source roots include `sourbyclip` deliberately. The first pass of this audit
scoped the grep to the server package and silently skipped that project entirely.

## Inventory

| Executor / thread | Location | Bound | Named | Shutdown |
|---|---|---|---|---|
| `BoundedIoExecutor` | `util/BoundedIoExecutor.java:13` | 64-permit semaphore, no queue | `SourbyCraft-IO-N` | Explicit, 30 s grace then interrupt |
| `AsyncPathProcessor.pool` | `perf/AsyncPathProcessor.java:57` | cores/4 threads (min 1), queue 1024, caller-runs on reject | `SourbyCraft-AsyncPath-N` | Explicit; queued futures cancelled |
| `PerformanceCollector.worker` | `perf/PerformanceCollector.java:94` | One thread | `SourbyCraft-PerformanceCollector` | Explicit |
| `GcTracker` sampler | `perf/GcTracker.java:35` | One thread | Yes | Explicit `stop()` |
| `SourbyBootstrap` shutdown hook | `bootstrap/SourbyBootstrap.java:393` | One thread, runs at exit | — | JVM-managed |
| `UpdateApplier` shutdown hook | `update/UpdateApplier.java:152` | One thread, runs at exit | — | JVM-managed |
| `IPUtil` country lookup | `sourbyclip/.../IPUtil.java:19` | Fixed, one per API endpoint | No | `shutdownNow()` in `finally` |
| `Sourbyclip.DOWNLOAD_EXECUTOR` | `sourbyclip/.../Sourbyclip.java:48` | **Unbounded** | No | **None** |

Every Sourby server-side executor is bounded, named and explicitly shut down. The
naming matters beyond tidiness: the allocation ranking in a JFR recording attributes
by thread name, so an unnamed pool appears as `pool-N-thread-M` with nothing to tie it
to a subsystem.

`IPUtil` was initially misread during this audit as passing no executor to
`supplyAsync`. It does — the argument is on the line below the lambda body — and it
shuts the executor down in a `finally`. No defect.

## Finding — `DOWNLOAD_EXECUTOR` is unbounded and never shut down

```java
public static final Executor DOWNLOAD_EXECUTOR = Executors.newCachedThreadPool();
```

Three things follow.

**Unbounded.** A cached pool creates a new thread per task whenever no idle thread is
free. Library downloads are submitted per file, so peak thread count is set by how
many files are missing, not by any limit this code chooses. On a first boot that is
every externalized library at once.

**Not shut down.** The field is declared as `Executor`, which has no `shutdown` method,
so it cannot be shut down without a cast; nothing tries. Its threads are non-daemon
with the default 60-second idle timeout, so they do not block exit forever, but they
are outside anyone's lifecycle.

**Unnamed.** Its threads carry the default `pool-N-thread-M` names, so a thread dump or
an allocation ranking taken during bootstrap cannot attribute them.

None of this is a correctness defect and the downloader is otherwise careful — files
are hash-validated before reuse and after download. It is a boundedness and ownership
gap, which is what section L asks about. A bounded, named, explicitly-closed pool sized
to the download concurrency the bootstrap actually wants would close it.

## Not established here

* No measurement. Nothing in this audit says any of these pools is sized correctly, only
  that it is bounded and owned.
* Upstream executors were not inventoried. Paper, Canvas, Folia and Moonrise own many,
  and section E's "validate no region thread blocks on external I/O" is a separate
  question this does not answer.
* `AsyncPathProcessor`'s caller-runs rejection policy means a saturated queue executes
  the task on the submitting thread. Whether that thread can be a region thread, and
  what that would cost, belongs to the open async-pathfinding audit.

## Measured lane load

`LaneCpuSampler` attributes per-thread CPU to an `ExecutionLane` and emits it as
`dev.iyanz.sourbycraft.LaneLoad`. From a certified `players-10` run on an 8-core machine,
300 entities and 10 connected clients:

| Lane | Threads | Mean cores | Share |
| --- | ---: | ---: | ---: |
| `CHUNK_WORKER` | 4 | 1.43 | 85% |
| `REGION_TICK` | 4 | 0.22 | 13% |
| `NETWORK` | 4 | 0.02 | 1% |
| `WORLD_IO` | 5 | 0.01 | — |
| `TELEMETRY` | 6 | 0.00 | — |
| `PLUGIN_ASYNC` | 4 | 0.00 | — |
| `OTHER` | 23 | 0.00 | — |
| **Total** | **50** | **1.68** | **of 8 cores** |

Three things this settles.

Gameplay is not the cost. `REGION_TICK` — entities, blocks and the plugin handlers fired
from them — used 0.22 cores across four threads, roughly a twentieth of a core each.
Moving work off the region lane cannot help a lane that is already idle.

Chunk generation is nearly all of it, at 85%. That agrees with the soak's CPU ranking,
which put 61.6% of execution samples on the chunk workers, but by direct measurement of
consumed CPU rather than by attributing samples.

The machine is 79% idle. 1.68 of 8 cores at ten players means the earlier fifty-player
saturation was a property of that load, not of how the lanes are arranged.

Two caveats before anything is sized from this. Twenty-three of fifty threads land in
`OTHER`: they consume no measurable CPU so they do not distort the totals, but the
mapping only properly covers the lanes that are busy. And `CHUNK_WORKER` counts four
threads here because the classifier folds `Worker-Main` in with `Paper Common Worker` —
defensible, since Mojang's background executor does chunk work, but they are arguably two
lanes and should be split before the number is used for tuning.
