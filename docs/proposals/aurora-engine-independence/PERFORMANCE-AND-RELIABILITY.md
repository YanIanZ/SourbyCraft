# Aurora Performance and Reliability Qualification

**Role:** proposed performance engineering methodology. No new benchmark was run for this RFC and no percent improvement is asserted.

## Source baseline

[SourbyCraft baseline](../../BASELINE.md) already defines JFR artifacts, `scripts/run_baseline.py`, `scripts/compare_baseline.py`, and workload provenance checks; the default regression gate is ~3% for directly affected metrics. [PROFILING.md](../../PROFILING.md) owns profiler usage. This RFC builds on those tools rather than launching a competing benchmark system.

**Important historical counterexample:** the documented [chunk worker comparison](../../architecture/chunk-workers.md) finds a run where more workers worsen typical MSPT while improving p99; an even earlier contended run was invalid. This is why “more threads” and “more independent” are not performance metrics.

## Measurement workflow

1. Identify a production-like bottleneck using Spark/JFR and current `/perf` data; state a falsifiable hypothesis with a single proposed change.
2. Establish reference build SHA, Paper pin, Java 25 JVM flags, machine, warmup, plugin set and world seed. Backup or clone input world. Record cold vs warm cache.
3. Run baseline under existing harness; refuse results if provenance or noise certification fails.
4. Apply minimal isolated candidate; avoid changing JVM flags, storage and algorithms simultaneously.
5. Repeat baseline with the same equipment and workload at least three steady-state iterations; compare distributions, confidence/variance and negative cases.
6. Run plugin, region merge/split, shutdown, AWF, MultiProfile and network regression cases relevant to the change.
7. Accept/reject; if gain is ambiguous or p99/safety regresses, preserve previous implementation as default and document failure.

## What to measure

| Area | Performance signal | Correctness/failure signal |
|---|---|---|
| Region runtime | MSPT p50/p95/p99/max per region, late ticks | wrong-owner access, retired-region callbacks |
| Chunk pipeline | tickets, load/gen queue wait, holder lookup cost, save/unload latency | missing chunks, duplicate loads, orphan tickets |
| Entity AI | path solve latency, declines, stale/rejected callback, mob tick | invalid path after world move, data races |
| Resource Governor | queue wait, saturation, rejected count, executor active, per-lane CPU | unbounded work, caller-runs on region thread |
| AWF | oldest dirty, durable commit latency, UNKNOWN commits, recovery count | lost/duplicated world state |
| MultiProfile | per-account switch latency, head-of-line blocking, journal age | A->B->A leakage, stale profile callback |
| Network | bytes copied, packet queue depth, flush latency, entity fanout | client desync, invalid packet, DoS |
| Heap/native | allocation rate, GC p99, RSS/direct, thread stacks | OOM, use-after-release, memory leak |
| Lifecycle | startup/shutdown stage latency | half-started services, unconfirmed save |
| Plugin bridge | dispatch overhead, refusal, safe/relaxed mode | unlawful world access, plugin state corruption |

## Reliability gates (hard blockers)

- **P0:** item/world/profile loss or duplication, dual writers, authentication bypass, region wrong-owner write, deadlock, permanently hung shutdown, false durability acknowledgement.
- **P1:** major p99 regression, clear memory growth, missing cancellation, broken plugin behavior on officially supported mode, wrong lifecycle state.
- **P2:** minor cosmetic telemetry/UX issue, missing noncritical metric.

P0 blocks production regardless of benchmark gain. P1 is release policy decision only with documented impact and workaround; P2 can be accepted explicitly, never silently.

## Workload conditions

Use real vanilla terrain, not only flat worlds, for chunk-generation tests. Test 10/50 moving players, entity-dense and redstone-dense activity, plugins and legacy bridge, world save/unload, and a long soak once hardware permits. Record plugin JAR SHA and client/protocol behavior. Include positive and negative tests: synthetic network delay, saturated queues, Redis/database outages and node kill **on disposable test data**.

Distinguish clean tick performance from server start / generation spikes. An optimization can improve p99 while hurting p50, and vice versa; publish the tradeoff rather than cherry-picking.

## Promotion criteria

Correctness gate PASS at exact candidate commit; no unexplained baseline regressions; one measurable benefit or demonstrable bug/reliability fix; no new unbounded task/cache; backup and rollback tested; owner signs off. New defaults require separate owner decision. Source code reviewed without build/run evidence is **not** “qualified.”
