# SourbyCraft Custom Tick Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace duplicated per-region tick histories and scan-on-read metrics with bounded primitive telemetry, a cached public metrics API, and an accurate server-side adapter for Canvas's bundled spark profiler.

**Architecture:** Each region scheduling handle writes exactly once into a primitive `RegionTickMetrics` generation. Region lifecycle hooks publish and retire generations in a server-owned registry; a daemon collector reduces immutable region summaries into one cached `PerformanceSnapshot` per second. SourbyCraft commands, HUD, plugins, and normal all-region spark statistics read the cache, while selected-region spark profiling uses the selected generation directly.

**Tech Stack:** Java 25, Gradle Kotlin DSL, Canvas/Folia region scheduler, LeafPile `TickData`, JUnit 6, Bukkit Services API, Adventure, embedded spark-paper 1.10.172, Weaver patch files.

**Spec:** `docs/superpowers/specs/2026-09-02-custom-tick-metrics-design.md`

## Global Constraints

- Use JDK 25 and the repository Gradle wrapper.
- Edit `sourbycraft-server/minecraft-patches`, `canvas-patches`, and tracked source only; never commit materialized `paper-server`, `canvas-server`, `paper-api`, `canvas-api`, or `sourbycraft-server/src/minecraft` changes.
- Leave the unrelated untracked patch `sourbycraft-server/minecraft-patches/features/0018-SourbyCraft-inline-AABB-and-reuse-MutableBlockPos-in-Entity-isInWall.patch` untouched.
- Use feature patch number `0019` for custom tick metrics.
- Preserve `Bukkit.getTPS()`, the separate `MinecraftServer` tick histories, spark protobuf/upload/viewer behavior, and the JVM descriptors of the five public `RegionScheduleHandle.tickTimes*` fields.
- Exact additive windows are 5s/10s/15s; 1m is one-second aligned; 5m/15m are five-second aligned. Approximate quantiles must be named `estimatedP95Mspt` and `estimatedP99Mspt`.
- Target retained telemetry memory is at most 40 MiB per 1,000 active region generations.
- No telemetry allocation occurs per completed region tick, and cache consumers never scan regions or sort history.
- Every task follows red-green-refactor and stages only its listed files.

---

### Task 1: Publish The Read-Only Metrics API

**Files:**
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/SourbyMetrics.java`
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/PerformanceSnapshot.java`
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/WindowMetrics.java`
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/RuntimeMetrics.java`
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/Freshness.java`
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/MetricWindow.java`
- Create: `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/MetricState.java`
- Create: `sourbyapi/src/test/java/dev/iyanz/sourbycraft/api/metrics/MetricsApiSurfaceTest.java`
- Modify: `sourbyapi/README.md:6-23,89-96`

**Interfaces:**
- Consumes: Bukkit's existing `ServicesManager`; no server or NMS classes.
- Produces: `SourbyMetrics#snapshot()`, immutable snapshot interfaces, window/state enums, and the plugin compilation contract used by Tasks 5 and 8.

- [ ] **Step 1: Write the API-surface test**

```java
package dev.iyanz.sourbycraft.api.metrics;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MetricsApiSurfaceTest {
    @Test
    void apiHasNoServerImplementationTypes() {
        assertEquals(PerformanceSnapshot.class, SourbyMetrics.class.getMethod("snapshot").getReturnType());
        for (Class<?> type : new Class<?>[]{SourbyMetrics.class, PerformanceSnapshot.class,
                WindowMetrics.class, RuntimeMetrics.class, Freshness.class}) {
            for (Method method : type.getMethods()) {
                String signature = method.toGenericString();
                assertFalse(signature.contains("net.minecraft"), signature);
                assertFalse(signature.contains("craftbukkit"), signature);
                assertFalse(signature.contains("me.lucko.spark"), signature);
            }
        }
    }

    @Test
    void windowsAreStableAndComplete() {
        assertArrayEquals(new MetricWindow[]{MetricWindow.FIVE_SECONDS, MetricWindow.TEN_SECONDS,
            MetricWindow.ONE_MINUTE, MetricWindow.FIVE_MINUTES, MetricWindow.FIFTEEN_MINUTES},
            MetricWindow.values());
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew :sourbyapi:test --tests dev.iyanz.sourbycraft.api.metrics.MetricsApiSurfaceTest --no-daemon`

Expected: compilation fails because the API types do not exist.

- [ ] **Step 3: Define the narrow interfaces and enums**

```java
public interface SourbyMetrics {
    PerformanceSnapshot snapshot();
}

public enum MetricWindow {
    FIVE_SECONDS, TEN_SECONDS, ONE_MINUTE, FIVE_MINUTES, FIFTEEN_MINUTES
}

public enum MetricState {
    WARMING, AVAILABLE, STALE, UNAVAILABLE
}

public interface PerformanceSnapshot {
    long sequence();
    long sampledAtEpochMillis();
    double targetTps();
    int activeRegionCount();
    int retainedGenerationCount();
    Freshness freshness();
    WindowMetrics window(MetricWindow window);
    RuntimeMetrics runtime();
}

public interface WindowMetrics {
    long coverageMillis();
    long sampleCount();
    boolean approximate();
    boolean truncated();
    double worstTps();
    double medianTps();
    double aggregateTps();
    double worstAverageMspt();
    double medianAverageMspt();
    double minimumMspt();
    double maximumMspt();
    double medianMspt();
    double estimatedP95Mspt();
    double estimatedP99Mspt();
    double busiestUtilisation();
    double averageUtilisation();
    double totalMissingCpuMs();
}

public interface Freshness {
    MetricState state();
    long ageMillis();
    long collectorLatenessMillis();
    long scanDurationNanos();
    String diagnostic();
}

public interface RuntimeMetrics {
    long heapUsedBytes();
    long heapMaxBytes();
    double rssPercent();
    double gcTimePercent();
    double gcCollectionsPerMinute();
    double averageGcPauseMs();
}
```

Use interfaces rather than constructor-heavy public records so new implementation fields do not break plugin bytecode. Document `NaN`, warming/stale behavior, window alignment, thread safety, and approximation semantics in Javadocs.

- [ ] **Step 4: Update the API README**

Replace the zero-custom-source claim with the exact distinction: the artifact republishes Paper/Canvas and additionally owns `dev.iyanz.sourbycraft.api.metrics`; show `ServicesManager.load(SourbyMetrics.class)` as the supported lookup.

- [ ] **Step 5: Run API tests and compilation**

Run: `./gradlew :sourbyapi:test :sourbyapi:compileJava --no-daemon`

Expected: `BUILD SUCCESSFUL`, with `MetricsApiSurfaceTest` executed.

- [ ] **Step 6: Commit the API contract**

```bash
git add sourbyapi/src sourbyapi/README.md
git commit -m "feat(api): expose immutable tick metrics service"
```

---

### Task 2: Import Runtime TickData And Backprop The Percentile Defect

**Files:**
- Modify: `build-data/canvas-dev-imports.txt`
- Create: `sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/TickDataCompatibilityTest.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`

**Interfaces:**
- Consumes: LeafPile `ca.spottedleaf.common.time.TickData` and its existing nested record descriptors.
- Produces: a patchable runtime `TickData`, corrected percentile mapping, and an always-discovered JUnit suite for all later metrics tests.

- [ ] **Step 1: Add the failing V1 regression test**

Create 100 ordered `TickTime` samples and assert:

```java
@Test
void TestV1_PercentileSegmentsMatchPopulation() {
    TickData.TickReportData report = reportForOrderedDurations(100);
    assertEquals(5, report.timePerTickData().segment5PercentWorst().count());
    assertEquals(1, report.timePerTickData().segment1PercentWorst().count());
    assertEquals(95_000_000.0,
        report.timePerTickData().rawData()[94]);
}

private static TickData.TickReportData reportForOrderedDurations(int count) {
    TickData data = new TickData(java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
    long previousStart = ca.spottedleaf.common.util.TimeUtil.DEADLINE_NOT_SET;
    long start = 0L;
    for (int i = 1; i <= count; i++) {
        long duration = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(i);
        data.addDataFrom(new TickTime(previousStart, start, start, 0L, start + duration, 0L,
            0L, 0L, false));
        previousStart = start;
        start += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);
    }
    return data.generateTickReport(null, start, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50));
}
```

Create the suite required by the server's restrictive test filter:

```java
@Suite
@SelectClasses({TickDataCompatibilityTest.class})
public class SourbyMetricsTestSuite {}
```

- [ ] **Step 2: Import the runtime class and verify the regression fails**

Append this exact import entry:

```text
leafpile ca/spottedleaf/common/time/TickData.java
```

Run: `./gradlew applyAllPatches :sourbycraft-server:test --no-daemon`

Expected: `TestV1_PercentileSegmentsMatchPopulation` fails because worst-1% and worst-5% are reversed.

- [ ] **Step 3: Correct the constructor mapping in patch 0019**

The `SegmentedAverage` constructor order must match its record components:

```java
return new SegmentedAverage(
    computeSegmentData(data, allStart, allEnd, inverse),
    computeSegmentData(data, percent99BestStart, percent99BestEnd, inverse),
    computeSegmentData(data, percent95BestStart, percent95BestEnd, inverse),
    computeSegmentData(data, percent5WorstStart, percent5WorstEnd, inverse),
    computeSegmentData(data, percent1WorstStart, percent1WorstEnd, inverse),
    data
);
```

Do not rename or reorder the record components; that would break descriptors.

- [ ] **Step 4: Add baseline compatibility assertions**

Test standalone `new TickData(long)`, null reports before data, in-progress-only reports, sorted raw arrays, nanosecond units, inverse TPS min/max, inclusive add-time expiry, and non-mutation of retained history when a caller mutates a returned raw array.

- [ ] **Step 5: Reapply and run the suite**

Run: `./gradlew applyAllPatches :sourbycraft-server:test --no-daemon`

Expected: the suite executes and all `TickDataCompatibilityTest` cases pass.

- [ ] **Step 6: Commit the imported runtime baseline and regression fix**

```bash
git add build-data/canvas-dev-imports.txt sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf
git commit -m "fix(metrics): correct TickData percentile segments"
```

---

### Task 3: Build The Primitive Region Accumulator

**Files:**
- Modify: `sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/RegionTickMetricsTest.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/RegionTickMetricsConcurrencyTest.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`

**Interfaces:**
- Consumes: `TickTime` primitive accessors and the corrected `TickData` report records.
- Produces: `ca.spottedleaf.common.time.RegionTickMetrics`, its immutable `Snapshot`, and one-write report methods used by the scheduler and collector.

- [ ] **Step 1: Write deterministic window tests against a raw reference model**

Cover steady 20 TPS, mixed 49/51 ms durations, a multi-second tick, an hour gap, changing target interval, exact bucket boundaries, and overflow. Assert these core formulas:

```java
assertEquals(count * 1_000_000_000.0 / intervalNanos, window.tps(), 1.0E-9);
assertEquals((double) executionNanos / count / 1_000_000.0, window.mspt(), 1.0E-9);
assertTrue(window.minimumNanos() <= window.medianNanos());
assertTrue(window.medianNanos() <= window.maximumNanos());
```

For long windows, assert alignment and declared maximum boundary error instead of arbitrary-time equality.

- [ ] **Step 2: Verify the new tests fail**

Run: `./gradlew :sourbycraft-server:test --no-daemon`

Expected: test compilation fails because `RegionTickMetrics` does not exist.

- [ ] **Step 3: Add the primitive owner in patch 0019**

Add `ca/spottedleaf/common/time/RegionTickMetrics.java` with this external shape:

```java
public final class RegionTickMetrics {
    public static final long INACTIVE = Long.MIN_VALUE;

    public record WindowSnapshot(long sampleCount, long intervalNanos, long executionNanos,
                                 long minimumNanos, long medianNanos, long maximumNanos,
                                 long missingCpuNanos, double tps, double mspt,
                                 double utilisation, double estimatedP95Mspt,
                                 double estimatedP99Mspt, boolean approximate,
                                 boolean truncated) {}
    public record Snapshot(long sequence, long sampledAtNanos, long activeTickStartNanos,
                           WindowSnapshot fiveSeconds, WindowSnapshot tenSeconds,
                           WindowSnapshot fifteenSeconds, WindowSnapshot oneMinute,
                           WindowSnapshot fiveMinutes, WindowSnapshot fifteenMinutes) {}

    public void tickStarted(long startNanos) { /* one volatile scalar write */ }
    public void tickCompleted(TickTime tick, long targetIntervalNanos) { /* primitive-only update */ }
    public Snapshot snapshot(long nowNanos) { /* immutable publication input */ }
    public TickData.TickReportData report(long windowNanos, TickTime inProgress, long nowNanos,
                                          long targetIntervalNanos) { /* compatibility view */ }
    public Double tps(long windowNanos, TickTime inProgress, long targetIntervalNanos);
    public TickData.MSPTData mspt(long windowNanos, TickTime inProgress, long targetIntervalNanos);
}
```

Implement a timestamped primitive short ring, 61 one-second slots, 169 five-second slots, geometric
histogram slices with `int`/`long` counters, exact max, absolute epoch tags, saturating sums, and O(1)
gap reset. Preserve target interval per sample. Do not allocate from `tickCompleted`.

- [ ] **Step 4: Add concurrency tests for the publication protocol**

Run at least 10 million active/inactive transitions and assert that the collector never observes an
active timestamp outside the published monotonic sequence. Block one writer past a 50 ms threshold,
assert the stall appears before completion, complete it, and assert it is not double-counted.

- [ ] **Step 5: Implement the single-scalar stall protocol and immutable snapshot**

Use this ordering exactly:

```java
this.activeTickStartNanos = startNanos;
// region work
this.recordCompletedSample(tick, targetIntervalNanos);
this.activeTickStartNanos = INACTIVE;
this.publishSnapshotIfDue(nowNanos);
```

Mutable arrays remain writer-owned. Readers may only read `activeTickStartNanos` and the volatile
immutable `Snapshot` reference.

- [ ] **Step 6: Run focused tests and inspect allocation-sensitive code**

Run: `./gradlew :sourbycraft-server:test --no-daemon`

Expected: deterministic and concurrency tests pass. Inspect bytecode/source to confirm no boxing,
stream, collection, or record creation occurs in `tickCompleted`.

- [ ] **Step 7: Commit the primitive accumulator**

```bash
git add sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf
git commit -m "perf(metrics): add bounded region tick accumulator"
```

---

### Task 4: Preserve TickData Views And Patch The Region Tick Path

**Files:**
- Modify: `sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/TickDataBinaryCompatibilityTest.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`

**Interfaces:**
- Consumes: `RegionTickMetrics` from Task 3.
- Produces: five binary-compatible `TickData` views, one owner write in `RegionScheduleHandle`, and exact 5s/15s compatibility reports.

- [ ] **Step 1: Write descriptor and owner/view tests**

```java
@Test
void regionHandleKeepsTickDataFieldDescriptors() throws Exception {
    for (String name : List.of("tickTimes5s", "tickTimes15s", "tickTimes1m", "tickTimes5m", "tickTimes15m")) {
        assertEquals(TickData.class, TickRegionScheduler.RegionScheduleHandle.class.getField(name).getType());
    }
}
```

Add tests proving one sample written to the shared owner appears once in every view, 5s/15s raw
arrays remain sorted and exact, long-window segment averages are internally consistent, and
standalone `TickData(long)` still uses its standalone collector.

- [ ] **Step 2: Verify tests fail before owner/view support**

Run: `./gradlew applyAllPatches :sourbycraft-server:test --no-daemon`

Expected: owner/view construction tests fail while descriptor tests still pass.

- [ ] **Step 3: Add native owner/view mode to TickData**

Keep `TickData` final and preserve every public descriptor. Add a constructor/factory used by NMS:

```java
public TickData(final RegionTickMetrics shared, final long intervalNanos) {
    this.interval = intervalNanos;
    this.shared = Objects.requireNonNull(shared);
}
```

Existing `TickData(long)` keeps standalone behavior. Query methods delegate when `shared != null`.
Do not fabricate exact long-window raw arrays; return documented empty/truncated long raw data while
keeping segment counts, averages, units, and null behavior coherent.

- [ ] **Step 4: Patch RegionScheduleHandle to write once**

In `TickRegionScheduler.RegionScheduleHandle`:

```java
public final RegionTickMetrics sourbyTickMetrics = new RegionTickMetrics();
public final TickData tickTimes5s = new TickData(this.sourbyTickMetrics, TimeUnit.SECONDS.toNanos(5));
// same shape for 15s, 1m, 5m, 15m
```

Call `sourbyTickMetrics.tickStarted(tickStart)` only after `tryMarkTicking()` succeeds and immediately
before region work begins; a failed acquisition must never leave an active marker. At tick end call
`sourbyTickMetrics.tickCompleted(time, getTimeBetweenTicks())` exactly once; that method performs the
record, clear, and conditional publish ordering tested by Task 3. Remove the five internal
`addDataFrom` calls.

- [ ] **Step 5: Reapply, compile, and run compatibility tests**

Run: `./gradlew applyAllPatches :sourbycraft-server:test :sourbycraft-server:compileJava --no-daemon`

Expected: all descriptor, report, and accumulator tests pass; server compilation succeeds.

- [ ] **Step 6: Commit the compatibility bridge and tick hook**

```bash
git add sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf
git commit -m "perf(metrics): replace duplicate region tick histories"
```

---

### Task 5: Implement Generation Lifecycle And Registry

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/RegionMetricsRegistry.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/RegionMetricsRegistryTest.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`
- Modify: `sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch`

**Interfaces:**
- Consumes: `RegionTickMetrics` and its immutable `Snapshot`.
- Produces: generation IDs, world telemetry IDs, `activate`, `retire`, `rotateForMerge`, `retireWorld`, and weakly consistent iteration for Task 6.

- [ ] **Step 1: Write lifecycle state-machine tests**

Test these invariants with fake primitive IDs and metric owners:

```text
inactive + destroy => one retirement
split => parent retired, children empty/live
merge => sources retired, active target rotated
retired samples remain until 15m expiry
retired entries never count as active
expiry removes by generation identity
world retirement leaves no object reference to a world fixture
```

- [ ] **Step 2: Verify registry tests fail**

Run: `./gradlew :sourbycraft-server:test --no-daemon`

Expected: compilation fails because `RegionMetricsRegistry` does not exist.

- [ ] **Step 3: Implement one canonical registry**

```java
public final class RegionMetricsRegistry {
    public enum RetirementReason { INACTIVE, SPLIT, MERGE, DESTROYED, WORLD_UNLOAD }
    public record GenerationView(long generationId, long worldId, long regionId, boolean active,
                                 RegionTickMetrics.Snapshot snapshot) {}

    public long newWorldId();
    public long activate(long worldId, long regionId, RegionTickMetrics metrics, long nowNanos);
    public void retire(long generationId, RetirementReason reason, long nowNanos);
    public long rotateForMerge(long generationId, long worldId, long regionId,
                               RegionTickMetrics successor, long nowNanos);
    public void retireWorld(long worldId, long nowNanos);
    public void forEachUnexpired(long nowNanos, Consumer<GenerationView> consumer);
}
```

Use one `ConcurrentHashMap<Long, Generation>` as authority. A queue may carry expiry hints but may not
be aggregated. Registry values contain primitive IDs and telemetry only; prohibit world, region,
handle, plugin, callback, and capturing-lambda fields.

- [ ] **Step 4: Patch TickRegions lifecycle callbacks**

Patch `onRegionActive`, `onRegionInactive`, `onRegionDestroy`, `preMerge`, `preSplit`,
`TickRegionData.split`, and `mergeInto` so every completed tick belongs to exactly one generation.
Publish before scheduling. Retire idempotently before handle replacement. Rotate an already-active
merge target even when Canvas does not issue inactive/active callbacks.

- [ ] **Step 5: Patch world retirement**

Assign a primitive telemetry ID to each `ServerLevel` and call `retireWorld(id, now)` from
`WorldShutdownThread` immediately before the world is removed. Do not put `ServerLevel` in the
registry.

- [ ] **Step 6: Run lifecycle and compile verification**

Run: `./gradlew applyAllPatches :sourbycraft-server:test :sourbycraft-server:compileJava --no-daemon`

Expected: lifecycle invariants pass and generated NMS compiles.

- [ ] **Step 7: Commit lifecycle integration**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/RegionMetricsRegistry.java sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch
git commit -m "feat(metrics): track region telemetry generations"
```

---

### Task 6: Add The Collector, Provider, And Managed Lifecycle

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerformanceCollector.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/SourbyMetricsProvider.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/MetricsRuntime.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/ImmutablePerformanceSnapshot.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/ImmutableWindowMetrics.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/ImmutableRuntimeMetrics.java`
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/ImmutableFreshness.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/PerformanceCollectorTest.java`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/MetricsRuntimeTest.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/core/SourbyCraftBootstrap.java:41-55,141-148`
- Modify: `sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch`

**Interfaces:**
- Consumes: Task 1 API and Task 5 registry.
- Produces: `MetricsRuntime.provider()`, one-second cached snapshots, service registration, and idempotent shutdown for all consumers.

- [ ] **Step 1: Write collector formula and state tests**

Construct region summaries with unequal coverage and assert:

```java
assertEquals(17.5, snapshot.window(MetricWindow.FIVE_SECONDS).aggregateTps(), 1.0E-9);
assertEquals(10.0, snapshot.window(MetricWindow.FIVE_SECONDS).worstTps(), 1.0E-9);
assertEquals(MetricState.WARMING, provider.snapshot().freshness().state());
```

Also test median, active versus retired separation, global-region separation, stalled overlay,
collector lateness, partial-source failure, stale retention, overflow propagation, and no healthy zero
fabrication.

- [ ] **Step 2: Write lifecycle tests before implementation**

Using a fake `ServicesManager`/registration seam, assert start publishes one provider, partial failure
rolls back, double start does not duplicate, close is idempotent, no publication occurs after close,
and close unregisters the exact provider instance.

- [ ] **Step 3: Verify the tests fail**

Run: `./gradlew :sourbycraft-server:test --no-daemon`

Expected: compilation fails because collector/runtime types do not exist.

- [ ] **Step 4: Implement immutable server value objects and collector**

Use private package records implementing the Task 1 interfaces. Keep one reusable primitive median
buffer that grows only when region cardinality exceeds capacity. Publish the final deeply immutable
snapshot through one volatile field. Run the daemon against absolute deadlines and skip missed
periods.

```java
public final class SourbyMetricsProvider implements SourbyMetrics {
    private volatile PerformanceSnapshot snapshot = ImmutablePerformanceSnapshot.warming();
    @Override public PerformanceSnapshot snapshot() { return this.snapshot; }
    void publish(PerformanceSnapshot next) { this.snapshot = next; }
}
```

- [ ] **Step 5: Implement transactional runtime lifecycle**

```java
public final class MetricsRuntime {
    public static SourbyMetrics provider();
    public static synchronized void start(ServicesManager services, Plugin owner);
    public static synchronized void close(ServicesManager services);
    public static RegionMetricsRegistry registry();
}
```

Set `RUNNING` only after provider registration and collector start both succeed. On failure, interrupt
and join a created worker, unregister the exact provider, publish `UNAVAILABLE`, and permit a clean
retry. Do not copy `GcTracker`'s unmanaged daemon lifecycle.

- [ ] **Step 6: Wire startup and shutdown**

Call `MetricsRuntime.start(Bukkit.getServicesManager(), MinecraftInternalPlugin.INSTANCE)` from
`SourbyCraftBootstrap.init()` before command registration. Add a patch hunk to
`MinecraftServer.stopServer()` after plugin async shutdown wait and before its early return:

```java
dev.iyanz.sourbycraft.perf.MetricsRuntime.close(org.bukkit.Bukkit.getServicesManager());
```

The provider does not read spark, so it remains available during spark/plugin disable and closes
after plugin disable as required by the spec.

- [ ] **Step 7: Run unit, compile, and repeated-lifecycle tests**

Run: `./gradlew applyAllPatches :sourbycraft-server:test :sourbycraft-server:compileJava --no-daemon`

Expected: collector and lifecycle tests pass; no non-daemon worker survives `close()`.

- [ ] **Step 8: Commit the runtime**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/core/SourbyCraftBootstrap.java sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf sourbycraft-server/minecraft-patches/features/0019-SourbyCraft-custom-tick-metrics.patch
git commit -m "feat(metrics): publish cached server performance snapshots"
```

---

### Task 7: Move Commands And HUD To One Cached Snapshot

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfMetrics.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/RegionMspt.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/TpsCommand.java:58-282`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/MsptCommand.java:38-76`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/SysCommand.java:60-239`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/hud/HudBars.java:116-166`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/MetricsConsumerTest.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`

**Interfaces:**
- Consumes: `MetricsRuntime.provider().snapshot()` from Task 6.
- Produces: one-snapshot command/HUD rendering and no remaining SourbyCraft all-region metric scan.

- [ ] **Step 1: Write consumer tests with two distinguishable snapshots**

Use a provider that increments sequence on every `snapshot()` call. Execute each renderer and assert
it calls `snapshot()` once, renders values from one sequence only, shows `WARMING`/`STALE` explicitly,
and scales TPS progress using `targetTps()` rather than 20.

- [ ] **Step 2: Verify tests fail against current consumers**

Run: `./gradlew :sourbycraft-server:test --no-daemon`

Expected: `/tps` and HUD perform multiple metric reads or bypass the provider.

- [ ] **Step 3: Replace direct scans and Bukkit metric reads**

At each public entry point:

```java
final PerformanceSnapshot snapshot = MetricsRuntime.provider().snapshot();
```

Pass that value through render helpers. Delete `TpsCommand.safeTps`, `safeMspt`, HUD calls to
`Bukkit.getTPS`, and any `RegionMspt` region traversal. Make `PerfMetrics`/`RegionMspt` thin adapters
only if an existing internal call remains; otherwise delete them after proving no references remain.

- [ ] **Step 4: Render the agreed semantics**

`/tps` renders worst/median/aggregate TPS, worst average MSPT, active count, global status, and
freshness. `/mspt` renders worst average, estimated p95/p99, exact recent max, and approximation
label. `/sys` reuses the same runtime/GC snapshot. HUD uses worst active TPS/MSPT and dynamic target
rate.

- [ ] **Step 5: Prove scan-on-read is gone**

Run repository search and require no consumer reference to `computeForAllRegions`,
`RegionMspt.worstMsptMs`, or `Bukkit.getTPS()`:

```bash
rg 'computeForAllRegions|RegionMspt\.worstMsptMs|Bukkit\.getTPS\(\)' sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/{command,hud,perf}
```

Expected: no scan/read match outside intentionally deprecated adapter tests.

- [ ] **Step 6: Run tests and compile**

Run: `./gradlew :sourbycraft-server:test :sourbycraft-server:compileJava --no-daemon`

Expected: all consumers pass and compile.

- [ ] **Step 7: Commit consumer migration**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/{command,hud,perf} sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf
git commit -m "perf(metrics): serve commands and HUD from cache"
```

---

### Task 8: Patch Canvas's Embedded Spark Adapter

**Files:**
- Create: `sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark/plugin/FoliaTickStatistics.java.patch`
- Create: `sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark/FoliaSparkPlugin.java.patch`
- Create: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/FoliaTickStatisticsTest.java`
- Modify: `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`

**Interfaces:**
- Consumes: cached `PerformanceSnapshot`, per-selected-generation compatibility views, spark-paper 1.10.172 `TickStatistics` and `DoubleAverageInfo`.
- Produces: accurate cached all-region spark statistics while preserving standard profiler/upload/open behavior.

- [ ] **Step 1: Write adapter contract tests**

Provide distinct values for every window and assert:

```java
assertEquals(w5.aggregateTps(), stats.tps5Sec());
assertEquals(w10.aggregateTps(), stats.tps10Sec());
assertEquals(w1m.aggregateTps(), stats.tps1Min());
assertEquals(w5m.aggregateTps(), stats.tps5Min());
assertEquals(w15m.aggregateTps(), stats.tps15Min());
assertTrue(stats.isDurationSupported());
assertEquals(w10.estimatedP95Mspt(), stats.duration10Sec().percentile95th());
```

Add a selected-region test proving `RegionProfiler.STATE` bypasses the global snapshot.

- [ ] **Step 2: Verify tests fail against the current Canvas adapter**

Run: `./gradlew applyAllPatches :sourbycraft-server:test --no-daemon`

Expected: 10s maps to 15s and duration support is false.

- [ ] **Step 3: Create source-of-truth Canvas patches**

Patch normal `FoliaTickStatistics` accessors to read one cached snapshot and return
`WindowMetrics.aggregateTps()` for spark's single all-region TPS value. Implement
`DoubleAverageInfo` from the matching window. Preserve the profiling-state branch and have it query
the selected handle's shared compatibility metrics.

Patch `FoliaSparkPlugin#createTickStatistics()` to inject the canonical provider. Preserve the
spark-paper 1.10.172 `gameTargetTps()` method and
`platform.getCommandManager().getAllSparkPermissions()` compatibility changes in the patch files.
Do not change `SparkPlatform`, protobuf, upload URLs, viewer sockets, or command names.

- [ ] **Step 4: Reapply patches and verify generated source**

Run: `./gradlew applyAllPatches :sourbycraft-server:test :sourbycraft-server:compileJava --no-daemon`

Expected: tests pass; generated `FoliaTickStatistics` contains no all-region traversal in its normal
branch; spark 1.10.172 compiles.

- [ ] **Step 5: Commit spark integration**

```bash
git add sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf
git commit -m "perf(spark): use cached SourbyCraft tick metrics"
```

---

### Task 9: Add External API And Shutdown Integration Coverage

**Files:**
- Modify: `test-plugin.settings.gradle.kts`
- Modify: `test-plugin/build.gradle.kts`
- Modify: `test-plugin/src/main/java/dev/iyanz/sourbycraft/testplugin/TestPlugin.java`
- Modify: `.github/workflows/build.yml:44-74,89-137`

**Interfaces:**
- Consumes: the published Task 1 API and Task 6 service lifecycle.
- Produces: a real external plugin compatibility fixture and CI evidence for discovery, spark access, and natural shutdown.

- [ ] **Step 1: Keep the fixture opt-in and extend it**

Replace the commented include with a property gate so ordinary builds remain unchanged:

```kotlin
if (providers.gradleProperty("includeTestPlugin").map(String::toBoolean).getOrElse(false)) {
    include(":test-plugin")
}
```

In the plugin, resolve the service in both lifecycle methods:

```java
@Override
public void onLoad() {
    requireMetrics("SOURBY_METRICS_ONLOAD_OK");
}

@Override
public void onEnable() {
    requireMetrics("SOURBY_METRICS_ONENABLE_OK");
}

private void requireMetrics(String marker) {
    SourbyMetrics metrics = getServer().getServicesManager().load(SourbyMetrics.class);
    if (metrics == null || metrics.snapshot() == null) {
        throw new IllegalStateException("SourbyMetrics service unavailable");
    }
    getLogger().info(marker);
}
```

Keep the plugin dependency limited to `project(":sourbyapi")`; do not add the server project.

- [ ] **Step 2: Build the fixture and verify its dependency boundary**

Run: `./gradlew -PincludeTestPlugin=true :test-plugin:compileJava :test-plugin:jar --no-daemon`

Expected: fixture compiles and packages without server implementation classes.

- [ ] **Step 3: Make CI execute tests and package the fixture**

After `applyAllPatches`, run:

```yaml
- name: Test API, metrics, and server
  run: ./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:jar --no-daemon --stacktrace
```

Upload the fixture jar with the server artifact or copy it into the boot-test `plugins/` directory.

- [ ] **Step 4: Strengthen the boot test**

Require both fixture markers and `Done (` before shutdown. Send representative `tps`, `mspt`, and
`spark profiler info` commands through the FIFO. After `stop`, poll for exit; if the process is still
alive at 60 seconds, print logs, terminate it for runner cleanup, and exit non-zero. Otherwise run
`wait "$PID"` and require exit code zero.

- [ ] **Step 5: Run workflow-equivalent local checks**

Run:

```bash
./gradlew applyAllPatches --no-daemon
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:jar --no-daemon
./gradlew :sourbyapi:compileJava :sourbycraft-server:compileJava --no-daemon
./gradlew slimServerJar --no-daemon
```

Expected: all tasks succeed and the intended metrics suite reports executed tests.

- [ ] **Step 6: Commit integration coverage**

```bash
git add test-plugin.settings.gradle.kts test-plugin/build.gradle.kts test-plugin/src/main/java/dev/iyanz/sourbycraft/testplugin/TestPlugin.java .github/workflows/build.yml
git commit -m "test(metrics): verify API and clean shutdown in CI"
```

---

### Task 10: Final Performance And Release Verification

**Files:**
- Modify only if measured failures require a spec-consistent correction; do not broaden scope.
- Verify: all files from Tasks 1-9.

**Interfaces:**
- Consumes: the complete implementation.
- Produces: reproducible correctness, compatibility, performance, boot, and CI evidence.

- [ ] **Step 1: Run a clean patch materialization**

Use an isolated worktree created through the `using-git-worktrees` skill. Run:

```bash
./gradlew applyAllPatches --no-daemon --stacktrace
```

Expected: all Paper, Canvas, and SourbyCraft patches apply without offsets or rejected hunks.

- [ ] **Step 2: Run all automated tests and compilation**

```bash
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:compileJava :sourbycraft-server:compileJava --no-daemon --stacktrace
```

Expected: `BUILD SUCCESSFUL`; V1, window, concurrency, lifecycle, API, consumer, and spark tests are
listed as executed rather than skipped.

- [ ] **Step 3: Measure structural performance constraints**

Run the deterministic 1,000-region benchmark fixture with JDK 25 and assert:

```text
per-tick telemetry allocations = 0
retained telemetry <= 40 MiB / 1,000 active generations
collector acquires no regionizer or handle monitor
consumer path invokes no report generation or sort
```

Record directional throughput and scan latency without making hardware-specific timing a CI pass/fail
threshold.

- [ ] **Step 4: Build and boot the slim jar**

```bash
./gradlew slimServerJar --no-daemon --stacktrace
java -Xmx2G -XX:+UseG1GC -jar build/libs/SourbyCraft-slim.jar --nogui
```

Expected: server reaches `Done`, API fixture markers appear, `/tps`, `/mspt`, and spark output contain
finite cached values after warming, and `stop` exits naturally without a forced signal.

- [ ] **Step 5: Review tracked diff and generated cleanliness**

```bash
git status --short
git diff --check
git diff --stat
```

Expected: only intended tracked sources, tests, docs, CI, and patch files changed. No generated
`paper-server`, `canvas-server`, `paper-api`, `canvas-api`, or `src/minecraft` files are staged. Patch
`0018` remains untouched.

- [ ] **Step 6: Request final code review**

Use the `requesting-code-review` skill against the complete branch diff. Resolve correctness,
compatibility, lifecycle, and test findings before release work.

- [ ] **Step 7: Commit any review-driven corrections separately**

```bash
git diff --name-only --diff-filter=ACMRTUXB -z | git add --pathspec-from-file=- --pathspec-file-nul
git commit -m "fix(metrics): address final integration findings"
```

Skip this commit when review requires no changes; never create an empty commit.

- [ ] **Step 8: Push and dispatch CI only after local evidence is green**

```bash
git push origin release/26.2-canvas
```

Monitor the dispatched run through build, boot-test, and Docker jobs. Do not claim completion until
all required jobs pass.
