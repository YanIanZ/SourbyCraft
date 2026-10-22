package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.perf.IncidentDetector.Counter;
import dev.iyanz.sourbycraft.perf.IncidentDetector.External;
import dev.iyanz.sourbycraft.perf.IncidentDetector.Gauge;
import dev.iyanz.sourbycraft.perf.IncidentLog.Breach;
import dev.iyanz.sourbycraft.perf.IncidentLog.Incident;
import dev.iyanz.sourbycraft.perf.IncidentLog.Kind;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentDetectorTest {

    private static final long T0 = 1_700_000_000_000L;

    // ---- fakes: interface proxies answering by method name, NaN / -1 otherwise ----

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final Map<String, Object> values) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            if (values.containsKey(method.getName())) return values.get(method.getName());
            final Class<?> r = method.getReturnType();
            if (r == double.class) return Double.NaN;
            if (r == long.class) return -1L;
            if (r == int.class) return -1;
            if (r == boolean.class) return false;
            return null;
        });
    }

    private static PerformanceSnapshot snapshot(final double targetTps, final double globalMaxMspt,
                                                final RuntimeMetrics runtime) {
        final WindowMetrics global = proxy(WindowMetrics.class, Map.of("maximumMspt", globalMaxMspt));
        return proxy(PerformanceSnapshot.class, Map.of("targetTps", targetTps, "runtime", runtime,
            "globalWindow", global));
    }

    private static RuntimeMetrics runtime(final long gcMillis, final long heapUsed, final long heapMax) {
        return proxy(RuntimeMetrics.class, Map.of("gcCollectionTimeMillis", gcMillis,
            "heapUsedBytes", heapUsed, "heapMaxBytes", heapMax));
    }

    private static PerformanceSnapshot quiet() {
        return snapshot(20.0, 10.0, runtime(0L, 1L, 100L));
    }

    private static SlowRegion slow(final double mspt) {
        return new SlowRegion(1L, 7L, 3L, mspt, mspt * 2, 100L);
    }

    private static IncidentDetector detector() {
        return new IncidentDetector(new IncidentLog(), () -> External.NONE);
    }

    private static List<Kind> kinds(final List<Breach> breaches) {
        return breaches.stream().map(Breach::kind).toList();
    }

    // ---- snapshot signals and their threshold edges ----

    @Test
    void aRegionAtExactlyTheBudgetIsNotAnIncidentJustAboveItIs() {
        final IncidentDetector detector = detector();
        assertEquals(List.of(), detector.breaches(quiet(), slow(50.0), External.NONE));

        final List<Breach> over = detector.breaches(quiet(), slow(50.1), External.NONE);
        assertEquals(List.of(Kind.REGION_OVER_BUDGET), kinds(over));
        assertEquals("world #1 region #7", over.get(0).subject());
        assertEquals(50.0, over.get(0).threshold());
    }

    @Test
    void theBudgetFollowsTheTargetTpsAndFallsBackWhenItIsUnknown() {
        assertEquals(100.0, IncidentDetector.budgetMillis(10.0));
        assertEquals(50.0, IncidentDetector.budgetMillis(Double.NaN));
        assertEquals(50.0, IncidentDetector.budgetMillis(0.0));
        final List<Breach> slowServer = detector().breaches(snapshot(10.0, 0.0, runtime(0, 1, 100)), slow(60.0),
            External.NONE);
        assertEquals(List.of(), slowServer, "60 ms is inside a 10 TPS budget");
    }

    @Test
    void theGlobalTickIsSlowFromTheThresholdUp() {
        final IncidentDetector detector = detector();
        assertEquals(List.of(), detector.breaches(snapshot(20.0, 249.9, runtime(0, 1, 100)), null, External.NONE));
        assertEquals(List.of(Kind.GLOBAL_TICK_SLOW),
            kinds(detector.breaches(snapshot(20.0, 250.0, runtime(0, 1, 100)), null, External.NONE)));
    }

    @Test
    void gcTimeIsMeasuredAsGrowthBetweenPassesWithABaselineAndReBaselining() {
        final IncidentDetector detector = detector();
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(10_000, 1, 100)), null, External.NONE),
            "the first pass only takes the baseline");
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(10_199, 1, 100)), null, External.NONE),
            "199 ms is under the threshold");
        final List<Breach> gc = detector.breaches(snapshot(20, 0, runtime(10_399, 1, 100)), null, External.NONE);
        assertEquals(List.of(Kind.GC_TIME), kinds(gc));
        assertEquals(200.0, gc.get(0).value());
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(5, 1, 100)), null, External.NONE),
            "a counter going backwards is re-baselined, not a negative incident");
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(-1, 1, 100)), null, External.NONE),
            "an unavailable reading is ignored");
    }

    @Test
    void heapPressureStartsAtTheThresholdAndNeedsADefinedMaximum() {
        final IncidentDetector detector = detector();
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(0, 899, 1000)), null, External.NONE));
        assertEquals(List.of(Kind.HEAP_PRESSURE),
            kinds(detector.breaches(snapshot(20, 0, runtime(0, 900, 1000)), null, External.NONE)));
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(0, 900, -1)), null, External.NONE),
            "undefined maximum");
        assertEquals(List.of(), detector.breaches(snapshot(20, 0, runtime(0, -1, 1000)), null, External.NONE),
            "unavailable used");
    }

    // ---- external counters and gauges ----

    @Test
    void counterSignalsFireOnGrowthOnlyPerSubject() {
        final IncidentDetector detector = detector();
        final External first = new External(List.of(new Counter("storage", 4)), 10L,
            List.of(), List.of(new Counter("awf-world", 0)), List.of(new Counter("Essentials", 2)));
        assertEquals(List.of(), detector.breaches(null, null, first), "baseline");

        final External same = new External(List.of(new Counter("storage", 4), new Counter("chunk", 0)), 10L,
            List.of(), List.of(new Counter("awf-world", 0)), List.of(new Counter("Essentials", 2)));
        assertEquals(List.of(), detector.breaches(null, null, same), "no growth, a new lane only baselines");

        final External grew = new External(List.of(new Counter("storage", 7), new Counter("chunk", 0)), 11L,
            List.of(), List.of(new Counter("awf-world", 1)), List.of(new Counter("Essentials", 3)));
        final List<Breach> breaches = detector.breaches(null, null, grew);
        assertEquals(List.of(Kind.LANE_REJECTED, Kind.ASYNC_PATH_REFUSED, Kind.AWF_COMMIT_FAILURE,
            Kind.BRIDGE_FATAL_VIOLATION), kinds(breaches));
        assertEquals("storage", breaches.get(0).subject());
        assertEquals(3.0, breaches.get(0).value(), "the growth this pass");
    }

    @Test
    void anUnavailableAsyncPathCounterIsIgnored() {
        final IncidentDetector detector = detector();
        final External none = new External(List.of(), -1L, List.of(), List.of(), List.of());
        detector.breaches(null, null, none);
        assertEquals(List.of(), detector.breaches(null, null, none));
    }

    @Test
    void storageBacklogIsAGaugeFromTheThresholdUp() {
        final IncidentDetector detector = detector();
        final External backlog = new External(List.of(), -1L,
            List.of(new Gauge("world", 999), new Gauge("world_nether", 1_000)), List.of(), List.of());
        final List<Breach> breaches = detector.breaches(null, null, backlog);
        assertEquals(List.of(Kind.STORAGE_BACKLOG), kinds(breaches));
        assertEquals("world_nether", breaches.get(0).subject());
    }

    // ---- end to end with a fake clock ----

    @Test
    void aSustainedConditionBecomesOneIncidentWithItsDuration() {
        final IncidentLog log = new IncidentLog();
        final IncidentDetector detector = new IncidentDetector(log, () -> External.NONE);
        detector.observe(T0, quiet(), slow(70.0));
        detector.observe(T0 + 1_000, quiet(), slow(90.0));
        detector.observe(T0 + 2_000, quiet(), slow(65.0));
        detector.observe(T0 + 3_000, quiet(), slow(10.0));

        final List<Incident> incidents = log.view().incidents();
        assertEquals(1, incidents.size());
        assertEquals(Kind.REGION_OVER_BUDGET, incidents.get(0).kind());
        assertEquals(2_000L, incidents.get(0).durationMillis());
        assertEquals(90.0, incidents.get(0).peak());
        assertTrue(incidents.get(0).ongoing(), "closes only after the merge gap");

        detector.observe(T0 + 2_000 + IncidentLog.MERGE_GAP_MILLIS + 1, quiet(), null);
        assertEquals(false, log.view().incidents().get(0).ongoing());
    }

    @Test
    void aFailingSourceDoesNotStopTheSnapshotSignals() {
        final IncidentLog log = new IncidentLog();
        final IncidentDetector detector = new IncidentDetector(log, () -> {
            throw new IllegalStateException("governor not ready");
        });
        detector.observe(T0, quiet(), slow(80.0));
        assertEquals(1, log.view().incidents().size());
    }

    @Test
    void theGlobalWindowAskedForIsTheFiveSecondOne() {
        final MetricWindow[] asked = new MetricWindow[1];
        final WindowMetrics window = proxy(WindowMetrics.class, Map.of("maximumMspt", 10.0));
        final PerformanceSnapshot snapshot = (PerformanceSnapshot)Proxy.newProxyInstance(
            PerformanceSnapshot.class.getClassLoader(), new Class<?>[]{PerformanceSnapshot.class}, (p, m, a) -> {
                if (m.getName().equals("globalWindow")) { asked[0] = (MetricWindow)a[0]; return window; }
                if (m.getName().equals("targetTps")) return 20.0;
                return null;
            });
        detector().breaches(snapshot, null, External.NONE);
        assertEquals(MetricWindow.FIVE_SECONDS, asked[0]);
    }
}
