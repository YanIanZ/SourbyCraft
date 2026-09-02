# SourbyCraft Custom Tick Metrics Design

Date: 2026-09-02
Status: Approved design, pending implementation plan
Target: SourbyCraft 26.2 on Canvas

## Summary

SourbyCraft will replace Canvas's five per-region `TickData` histories with one bounded,
primitive telemetry owner per region generation. The new system records TPS and MSPT once on the
region tick path, publishes immutable per-region summaries, and aggregates them once per second into
one cached server snapshot.

`/tps`, `/mspt`, `/sys`, the TPS HUD, the public SourbyCraft metrics API, and Canvas's embedded spark
adapter will all consume that snapshot. None of those read paths may traverse every region, copy tick
history, or sort samples.

The design prioritizes accurate operator-facing short-window metrics and bounded production cost at
1,000 active regions. It preserves supported Bukkit/Canvas average metrics and the binary shape of
the internal `TickData` fields and report records, but it does not promise exact per-tick raw arrays
for bucketed 1m, 5m, or 15m NMS reports.

## Goals

- Report both the worst active region and an aggregate server view; never hide one bad region behind
  a healthy global average.
- Publish TPS/MSPT windows for 5s, 10s, 1m, 5m, and 15m at a one-second snapshot cadence.
- Use worst-region rolling-average MSPT as the headline. Expose percentile and maximum tick data as
  supporting diagnostics.
- Remove per-command and per-HUD region scans, history copies, and sorts.
- Replace the five duplicated per-region `TickData` histories with bounded primitive storage.
- Remain safe during region thread migration, inactivity, split, merge, destruction, world unload,
  stalled ticks, startup, and shutdown.
- Expose an NMS-free, read-only Java API from the published `sourbyapi` artifact.
- Feed the standard embedded spark profiler and `spark.lucko.me` viewer through spark's existing
  fields and protocol.
- Stay within 40 MiB of telemetry state for 1,000 continuously active regions.

## Non-Goals

- Changing `Bukkit.getTPS()` or the existing Paper/Canvas API behavior for unrelated plugins.
- Forking spark's profiler engine, protobuf schema, upload service, or website.
- Adding Prometheus, JSON, JMX, or another HTTP endpoint.
- Preserving exact raw per-tick data for NMS-only 1m, 5m, and 15m `TickReportData` consumers.
- Persisting metrics across a server restart.
- Adding automatic performance tuning or changing the server tick rate.
- Modifying or incorporating feature patch `0018`; that optimization remains separate.

## Existing Problems

Each `TickRegionScheduler.RegionScheduleHandle` currently owns five `TickData` instances for 5s,
15s, 1m, 5m, and 15m. Each completed `TickTime` is retained by all five deques. Generating a report
copies the selected deque, allocates three raw arrays, sorts all three arrays, and builds segmented
statistics while holding the handle monitor.

SourbyCraft then duplicates work at the consumer level:

- `PerfMetrics.snapshot()` walks all worlds and regions.
- `RegionMspt.worstMsptMs()` performs another all-region walk.
- `/tps` calls both paths and also reads Bukkit TPS separately.
- The TPS HUD performs another all-region MSPT survey every second while viewed.
- Canvas spark walks all regions independently for each requested TPS window.

This produces inconsistent sampling instants and scales poorly with active region count. At 1,000
continuously ticking regions, the existing 15-minute object history can theoretically retain more
than 1 GiB including `TickTime` objects and deque references. Report generation can add several MiB
of temporary arrays per all-region survey.

Two correctness defects were also confirmed:

- `TickData.computeSegmentedAverage` passes worst-1% and worst-5% segments into reversed record
  components.
- `PerfMetrics` labels a worst-tail average as `p95`; a tail average is not a percentile.

## Metric Semantics

### Time Windows

The cached snapshot exposes these windows:

| Window | Storage | Publication semantics |
|---|---|---|
| 5s | timestamped primitive raw ring | exact additive values; cached quantiles estimated |
| 10s | timestamped primitive raw ring | exact additive values; cached quantiles estimated |
| 15s | timestamped primitive raw ring | exact additive values; cached quantiles estimated |
| 1m | one-second aggregate buckets | aligned to one second; boundary error less than one second |
| 5m | five-second aggregate buckets | aligned to five seconds; freshness/boundary error less than five seconds |
| 15m | five-second aggregate buckets | aligned to five seconds; freshness/boundary error less than five seconds |

Short-window additive exactness means exact count, sum, average, and maximum over the retained
timestamped completed samples, with the current in-progress tick overlaid separately. Cached
percentiles remain histogram estimates; an explicit compatibility report may sort the raw ring to
produce exact short-window distribution fields. A raw ring is time-bounded, not fixed to an assumed 20 TPS.
Implementations may grow it to an explicit hard cap when a configured tick rate requires more
entries. Hitting that cap marks the affected report truncated rather than silently returning a
plausible complete result.

Long-window values are exact for complete included buckets. They are not arbitrary-nanosecond
sliding-window equivalents. The API reports coverage, alignment, and freshness so consumers cannot
mistake a bucketed value for an exact raw window.

### TPS

TPS remains independent of MSPT. It must never be derived as `1000 / MSPT`.

For one region:

```text
regionTPS = completedTickCount * 1e9 / sum(startToStartIntervalNanos)
```

The first sample in a generation uses the target interval recorded for that tick when there is no
previous start. Display values are capped by the target rate recorded in the snapshot, not by a
hard-coded 20 TPS.

Server views are:

```text
worstTPS     = minimum TPS among current active region generations
medianTPS    = median TPS among current active region generations
aggregateTPS = sum(completedTickCount) * 1e9 / sum(startToStartIntervalNanos)
```

`aggregateTPS` is a region-time-weighted scheduler rate. It is not total concurrent tick throughput
and is not the arithmetic mean of region TPS values.

The global region is measured and exposed separately. Operator output may include it in a server
health headline only when clearly labeled; it is not counted as a spatial region.

### MSPT And Utilisation

For one region:

```text
MSPT = sum(tickLengthNanos + intermediateTaskNanos) / completedTickCount / 1e6
```

The headline is the highest rolling-average MSPT among active regions. Supporting values include
median region MSPT, exact recent maximum, estimated p95, and estimated p99.

Utilisation is based on tick wall-time intersected with the requested wall-clock window. A tick that
crosses a bucket boundary contributes proportionally to every intersected bucket. Region utilisation
is bounded to a coherent range; summed server work may exceed 100% because regions execute in
parallel. The snapshot therefore reports busiest-region utilisation, average region utilisation,
and total work separately.

### Percentiles

Percentiles use nearest-rank quantile semantics:

```text
index = ceil(q * sampleCount) - 1
```

The 5s/10s/15s percentile can be derived exactly from recent primitive samples when a compatibility
report explicitly requests raw statistics. Cached hot-path percentiles use a 64-bin geometric
histogram covering approximately 0.25 ms through 16 seconds plus underflow and overflow accounting.
Counters are `int` or `long`, never `short`.

Long-window percentile expiration uses bounded histogram slices independent of the additive average
buckets. API names include `estimatedP95Mspt` and `estimatedP99Mspt`; no approximate value is labeled
as an exact percentile. Exact maximum duration is retained separately from the histogram.

## Per-Region Storage

Each live region generation owns one `RegionTickMetrics` with preallocated primitive storage:

- A timestamped raw ring covering at least 15 seconds.
- Sixty finalized one-second buckets plus one active bucket.
- One hundred sixty-eight finalized five-second buckets plus one active bucket, covering the older
  portion of the 15-minute horizon.
- Cached additive totals for 5s, 10s, 1m, 5m, and 15m.
- Bounded geometric histogram slices for percentile expiry.
- Exact maximum duration tracking.
- Sample count, coverage, overflow, truncation, and freshness metadata.

Each completed sample records only the primitive values needed by the formulas and compatibility
reports: monotonic timestamps, start-to-start interval, execution duration, missing CPU duration,
and target interval. Arithmetic is saturating. Overflow marks the summary unavailable for the
affected metric instead of wrapping or returning a misleading value.

The implementation target is at most 40 MiB of retained telemetry state for 1,000 continuously
active region generations. Arrays are structure-of-arrays or equivalently compact primitive storage;
there are no per-tick telemetry collections or boxed values.

## Tick Path And Publication

The region tick thread is the sole writer of a generation's mutable rings and buckets.

Tick start publishes one atomic stall marker:

```java
private static final long INACTIVE = Long.MIN_VALUE;
private volatile long activeTickStartNanos = INACTIVE;
```

No volatile flag or version is allowed to guard separately mutable plain in-progress fields. A
throwaway JMM stress test demonstrated that an odd/even volatile version with a plain start timestamp
can produce incoherent reads during rapid end/start turnover.

Tick-end order is fixed:

1. Record the completed primitive sample.
2. Update short raw history and aggregate buckets.
3. Clear `activeTickStartNanos`.
4. Construct a compact immutable `RegionSummary` when the publication second changes.
5. Publish that summary through one volatile reference.

Clearing the active marker before summary publication prevents one completed tick from being counted
as both completed history and a live stall. The collector treats a non-`INACTIVE` start value as a
valid point-in-time observation even if the tick completes immediately afterward.

Bucket slots carry absolute monotonic epochs. A long clock gap invalidates expired slots in O(1); the
implementation must not rotate once per missed interval or backfill zero summaries.

## Region Lifecycle

A telemetry generation represents one uninterrupted period in which a region has stable topology
and may tick. It does not belong to a replaceable scheduling handle.

States:

```text
UNPUBLISHED -> LIVE -> RETIRED -> EXPIRED
```

Transitions:

- `onRegionActive`: publish a fresh generation before scheduling starts.
- Completed tick: append exactly one sample to the current live generation.
- `onRegionInactive`: retire the live generation idempotently.
- Handle replacement: does not copy or own telemetry.
- Split: retire the parent; every child starts a fresh empty generation.
- Merge: retire every source and rotate an already-active target before merged data can tick.
- Destroy before activation: discard an empty unpublished generation.
- World unload: retire all generations for the primitive world telemetry ID.

Historical server windows retain retired generations until their samples expire. Retired generations
may win a labeled historical worst-generation metric. They never contribute to current active region
count, active median, or current worst-active-region metrics.

The canonical process-wide registry contains both live and retained generations. A retirement queue
contains expiry hints only; it is never a second source of metric data. Registry entries contain
primitive IDs and telemetry storage only. They must not retain `ServerLevel`, Bukkit `World`, region
objects, handles, callbacks, or lambdas capturing those objects.

Lifecycle callbacks run under critical regionizer locks. They may perform bounded state transitions
and registry publication only. They must not block, access world state, perform I/O, recurse into the
regionizer, or wait for the collector.

## Server Collector

A dedicated daemon collector runs on absolute one-second deadlines. It skips missed deadlines rather
than producing catch-up snapshots. It never executes on a region or global-region scheduler.

For each pass it:

1. Reads one volatile `RegionSummary` from every unexpired generation in the canonical registry.
2. Reads the single volatile active-tick marker to overlay a currently stalled tick.
3. Separates active topology metrics from retained historical metrics.
4. Computes worst, median, region-time-weighted aggregate, utilisation, missing CPU, and freshness.
5. Adds memory and GC data from the existing bounded runtime collectors.
6. Publishes one deeply immutable `PerformanceSnapshot` through a volatile reference.

Registry iteration is weakly point-in-time. A concurrent split or merge may affect either the current
or following active-region count, but every unexpired completed sample must eventually appear exactly
once. The snapshot exposes `sampledAtNanos`, collector lateness, scan duration, coverage, and state.

Consumer reads are one reference load. They never trigger collection.

## Public Java API

The published `sourbyapi` artifact gains tracked source under:

```text
sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/
```

This deliberately changes the module from a zero-custom-source republisher; `sourbyapi/README.md`
must be updated in the same change.

The API consists of:

- `SourbyMetrics`: read-only service with `snapshot()`.
- `PerformanceSnapshot`: immutable server snapshot.
- `MetricWindow`: `FIVE_SECONDS`, `TEN_SECONDS`, `ONE_MINUTE`, `FIVE_MINUTES`, and
  `FIFTEEN_MINUTES`.
- Immutable window, utilisation, runtime, and freshness value types.
- Explicit `WARMING`, `AVAILABLE`, `STALE`, and `UNAVAILABLE` states.

The API exposes no NMS, CraftBukkit, Canvas implementation, mutable arrays, spark internal types,
callbacks, listeners, or plugin-owned suppliers. It is safe to query from any thread.

The canonical provider instance is registered through Bukkit `ServicesManager` before normal plugin
loading so plugins can resolve it during `onLoad` and `onEnable`. External plugins discover the
service there. Built-in SourbyCraft consumers receive the canonical provider directly and never use
service-priority lookup.

API value types are grouped narrowly to reduce binary evolution pressure. New incompatible metrics
require a new interface version instead of changing existing record constructor descriptors.

## SourbyCraft Consumers

`/tps`, `/mspt`, `/sys`, and the TPS HUD read exactly one snapshot at method entry. They do not mix
sampling generations.

- `/tps` shows worst, median, and aggregate TPS; worst average MSPT; recent tail; active region count;
  global-region status; freshness; and existing runtime health context.
- `/mspt` shows worst active average MSPT as the headline and recent estimated p95/p99 plus exact max.
- `/sys` uses the same snapshot for region, CPU starvation, GC, heap, and load data.
- The TPS HUD uses the worst active TPS/MSPT headline from the cache and performs no region walk.

`PerfMetrics` and `RegionMspt` are removed or reduced to thin compatibility adapters over the
canonical provider. There must be no fallback path that silently restarts all-region scans.

## Spark Integration

Canvas's embedded spark remains the profiler implementation. These behaviors stay upstream:

- `/spark profiler start`, `stop`, `open`, and `cancel`.
- Async and region-selected profiling.
- Upload and live-viewer protocols.
- Protobuf schema and `spark.lucko.me` website.
- Command permissions and normal spark lifecycle.

Only Canvas's server adapter changes:

- Normal all-region `FoliaTickStatistics` reads the canonical cached snapshot.
- Spark's single all-region TPS value uses `aggregateTps`; worst-region TPS remains available in the
  SourbyCraft commands, HUD, and public API. This preserves spark's server-summary meaning without
  hiding the worst region from SourbyCraft's operator surfaces.
- 5s uses a true 5-second window.
- 10s uses a true 10-second window instead of Canvas's current 15-second approximation.
- 1m, 5m, and 15m use their matching cached windows.
- Duration support returns MSPT mean, minimum, maximum, median, and estimated p95 through spark's
  existing `DoubleAverageInfo` fields.
- The selected-region profiling branch remains region-specific and reads that region generation's
  compatibility view rather than the global aggregate.

The website receives only fields already understood by spark. SourbyCraft does not add custom
protobuf fields or require a custom viewer.

The spark patches belong under:

```text
sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark/
```

They must include the spark 1.10.172 compatibility changes currently visible only in generated
sources, including `gameTargetTps()` and permission access changes, so a clean patch application does
not lose them.

## TickData Compatibility Boundary

The compatibility boundary remains `TickRegionScheduler.RegionScheduleHandle`.

- The five `public final TickData tickTimes*` fields retain their names and JVM descriptors.
- `TickData` remains final and its public standalone constructor remains functional for the separate
  `MinecraftServer` histories.
- `TickData` gains an internal owner/view mode. Five lightweight views select windows from one shared
  per-region owner.
- The region tick path writes the owner exactly once. It must not send one sample through all five
  views and accidentally record it five times.
- Existing `getTickReport5s/15s/1m/5m/15m` signatures and nested record descriptors remain unchanged.
- Null behavior for an empty history and units for all fields remain unchanged.
- 5s and 15s compatibility reports can produce exact sorted raw arrays on explicit request.
- Long-window reports produce internally consistent aggregate segments from buckets. Their raw arrays
  are not a supported exact compatibility guarantee and must be documented as unavailable or
  truncated rather than fabricated.
- The supported Canvas/Bukkit region average methods continue to return the correct window ordering
  and finite average values.

The separate seven `MinecraftServer` `TickData` histories and vanilla-style recent tick array remain
outside this migration. This preserves `Bukkit.getTickTimes()`, `Bukkit.getAverageTickTime()`, Paper's
legacy MSPT command, and JMX behavior.

## Startup And Shutdown

Metrics initialization has explicit lifecycle state rather than sharing a one-way boolean that is set
before work succeeds.

Startup order:

1. Construct primitive registry, provider, and unavailable initial snapshot.
2. Register the exact provider instance with `ServicesManager`.
3. Start the collector only after registration succeeds.
4. Publish lifecycle state only after all steps succeed.
5. Roll back registration and worker creation on partial failure.

The provider never captures spark state. Spark obtains the canonical provider lazily or through direct
server-side injection when its adapter is constructed.

Shutdown is idempotent. Because the provider is independent of spark, it remains readable while spark
and plugins disable, then stops the collector and unregisters the exact provider instance after plugin
disable. No new snapshot is published after shutdown begins. The final immutable snapshot remains
safe for already-held references.

The CI boot test must distinguish natural JVM exit from forced termination; requiring `kill` after
`stop` is a failure for this feature.

## Failure Handling

- Before the first complete collection, snapshot state is `WARMING`; nominal TPS is never fabricated.
- A late collector or failed pass retains the previous values and marks them `STALE` with age and
  failure metadata.
- A source failure cannot turn the whole snapshot into apparently valid zeros.
- Collection errors are rate-limited in logs. The next deadline still runs.
- Overflow or raw-ring truncation marks only the affected metric unavailable/truncated.
- Long gaps reset expired bucket epochs in O(1).
- Collector shutdown uses a bounded join and interrupt handling.
- Registry expiry uses identity-aware removal so an old hint cannot remove a replacement generation.

## Performance Requirements

At 1,000 active regions and approximately 20 region ticks per second:

- No telemetry object or collection allocation per completed region tick.
- No sorting, history copying, world traversal, or monitor acquisition on command, HUD, public API,
  or normal all-region spark reads.
- At most one compact immutable per-region summary allocation per active region per second.
- One immutable server snapshot publication per second.
- Retained telemetry state at or below 40 MiB for 1,000 active generations in steady state.
- Collector scans do not acquire regionizer or region-handle locks.
- Lifecycle callbacks perform bounded, non-blocking telemetry operations only.

Throwaway Apple M2/JDK 25 prototypes demonstrated approximately 16 ns per primitive tick update and
0.18-0.53 ms for a 1,000-region scan depending on layout. These are directional design evidence, not
portable CI thresholds. CI verifies structural allocation and locking invariants; repeatable JMH or
JFR benchmarks provide performance evidence outside timing-sensitive unit assertions.

## Verification Plan

### Unit Tests

- 5s/10s/15s exact rotation and timestamp expiry.
- 1m one-second alignment and documented boundary error.
- 5m/15m five-second alignment and documented boundary error.
- Long clock gaps, near-overflow epochs, and no catch-up loops.
- TPS, MSPT, utilisation clipping, missing CPU, and heterogeneous-region aggregate formulas.
- Dynamic target-rate changes captured per sample.
- Geometric histogram underflow, overflow, saturation, p95, and p99.
- Warming, available, stale, unavailable, truncated, and overflow states.
- Immutable snapshot and window value types.

### Regression Invariant And Backprop Record

The architecture investigation found an existing correctness bug and records it for backpropagation:

| ID | Date | Root cause | Invariant |
|---|---|---|---|
| B1 | 2026-09-02 | `computeSegmentedAverage` supplied worst-1% and worst-5% values to reversed record components, while `PerfMetrics` mislabeled a tail mean as p95 | V1 |

V1 enforces:

```text
Percentile segment names map to their matching population fractions, and no worst-tail mean may be
labeled as a percentile. For 100 ordered samples, worst-1% contains one sample, worst-5% contains five,
and nearest-rank p95 selects sample index ceil(0.95 * count) - 1.
```

The regression test is named `TestV1_PercentileSegmentsMatchPopulation` and must fail against the
current swapped constructor mapping before the fix.

### Concurrency And Lifecycle Tests

- Ten million start/end transitions with no impossible active-marker observation.
- Stalled tick appears before completion and disappears without double-counting afterward.
- Immutable region and server snapshot publication cannot tear.
- Concurrent registry iteration and generation churn terminate without exceptions or deadlock.
- Inactive/reactivate, immediate merge, deferred merge, split, destroy, and world unload.
- Exactly one generation owns every completed tick.
- No writes occur after retirement.
- Retired samples remain in historical windows until expiry but never enter active topology metrics.
- Registry entries cannot retain unloaded world or plugin classloaders.

### Compatibility Tests

- ASM or reflection checks the five `TickData` field names and descriptors.
- The standalone `TickData(long)` path remains functional.
- Empty/in-progress report null behavior remains intentional.
- 5s/15s report raw arrays, units, sorting, min/median/max, inverse TPS, and utilisation invariants.
- Long report aggregate fields are internally consistent and carry explicit truncated/approximation
  status through the Sourby API.
- Canvas region API window ordering remains 5s, 15s, 1m, 5m, 15m.
- A plugin fixture compiles using only `:sourbyapi` and resolves the service in `onLoad` and `onEnable`.

### Spark Tests

- 5s and 10s methods map to distinct matching windows.
- 1m, 5m, and 15m map to their matching cached windows.
- Duration support exposes finite, ordered mean/min/max/median/p95 values when available.
- Selected-region profiling does not read the global snapshot.
- Normal accessor calls perform no region scan or report generation.
- Standard profile upload/open behavior remains unchanged.

### Build And Runtime Verification

Run from a clean materialization:

```bash
./gradlew applyAllPatches
./gradlew :sourbyapi:test :sourbycraft-server:test
./gradlew :sourbyapi:compileJava :sourbycraft-server:compileJava
./gradlew slimServerJar
```

The implementation must ensure these tests are actually discovered and executed by CI. The boot test
must boot to `Done`, resolve the API from a fixture plugin, execute representative `/tps`, `/mspt`,
and spark reads, issue `stop`, and fail if the JVM requires forced termination. After local success,
manually dispatch `.github/workflows/build.yml` for `release/26.2-canvas`.

## Source Of Truth

Expected tracked changes are limited to:

```text
build-data/canvas-dev-imports.txt
sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/
sourbyapi/src/test/java/dev/iyanz/sourbycraft/api/metrics/
sourbyapi/README.md
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/hud/HudBars.java
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/core/SourbyCraftBootstrap.java
sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/
sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark/
sourbycraft-server/minecraft-patches/features/
```

The tracked `Metal/src/main/java/ca/spottedleaf/common/time/TickData.java` mirrors LeafPile source but
is not the server runtime dependency. `build-data/canvas-dev-imports.txt` must import `TickData.java`
from LeafPile into the patchable Minecraft source set, and the `0019` Minecraft feature patch must
contain the runtime `TickData`, scheduler, lifecycle, and shutdown changes. Do not wire the server to
the `:Metal` project merely to make a source edit take effect.

Materialized `paper-server/`, `canvas-server/`, `paper-api/`, and `canvas-api/` files are validation
outputs only and must not be committed.

## Acceptance Criteria

The feature is complete when:

- All SourbyCraft performance surfaces read one cached snapshot.
- Worst, median, and aggregate TPS semantics match the formulas in this document.
- Worst average MSPT, estimated p95/p99, exact recent max, utilisation, and freshness are visible.
- A live stalled region is visible before its tick returns.
- Region topology changes conserve completed historical work without duplicating it.
- Per-region five-window object histories are replaced by one bounded primitive owner.
- Supported API behavior and required binary descriptors remain intact.
- External plugins can compile against and discover the read-only API.
- Standard spark profile/open/upload workflows continue to use `spark.lucko.me` with corrected cached
  tick statistics.
- The percentile mapping regression is fixed and tested.
- The performance, memory, lifecycle, build, boot, and natural-shutdown checks pass.
