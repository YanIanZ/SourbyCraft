package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTickMetricsTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
    private static final long TARGET_20_TPS = 50L * MILLISECOND;

    @Test
    void steadyTwentyTpsUsesIntervalsForTpsAndExecutionForMspt() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        appendTicks(metrics, 100, 0L, TARGET_20_TPS, 10L * MILLISECOND);
        metrics.tickCompleted(tick(99L * TARGET_20_TPS, 5L * SECOND, 10L * MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(5L * SECOND + 10L * MILLISECOND).fiveSeconds();

        assertWindow(window, 101L, 5_050L * MILLISECOND, 1_010L * MILLISECOND);
        assertEquals(20.0, window.tps(), 1.0E-9);
        assertEquals(10.0, window.mspt(), 1.0E-9);
        assertEquals(0.2, window.utilisation(), 1.0E-9);
        assertEquals(10L * MILLISECOND, window.minimumNanos());
        assertEquals(10.0 * MILLISECOND, window.medianNanos(), 0.0);
        assertTrue(Double.isFinite(window.medianNanos()));
        assertEquals(10L * MILLISECOND, window.maximumNanos());
        assertTrue(window.approximate());
        assertFalse(window.truncated());
    }

    @Test
    void mixedDurationsAndCpuAccountingPreserveOrderedStatistics() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(cpuTick(TimeUtil.DEADLINE_NOT_SET, 0L, 49L * MILLISECOND, 40L * MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(cpuTick(0L, SECOND, 51L * MILLISECOND, 30L * MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(SECOND + 51L * MILLISECOND).fiveSeconds();

        assertWindow(window, 2L, 1_050L * MILLISECOND, 100L * MILLISECOND);
        assertEquals(50.0, window.mspt(), 1.0E-9);
        assertEquals(30L * MILLISECOND, window.missingCpuNanos());
        assertEquals(49L * MILLISECOND, window.minimumNanos());
        assertTrue(Double.isFinite(window.medianNanos()));
        assertTrue(window.minimumNanos() <= window.medianNanos());
        assertTrue(window.medianNanos() <= window.maximumNanos());
        assertEquals(51L * MILLISECOND, window.maximumNanos());
    }

    @Test
    void multiSecondTickClipsWindowUtilisation() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 8L * SECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot fiveSeconds = metrics.snapshot(8L * SECOND).fiveSeconds();

        assertEquals(1L, fiveSeconds.sampleCount());
        assertEquals(1.0, fiveSeconds.utilisation(), 1.0E-9);
        assertEquals(8_000.0, fiveSeconds.mspt(), 1.0E-9);
    }

    @Test
    void hourGapExpiresAllOldRawAndBucketStateWithoutCatchUp() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), TARGET_20_TPS);
        final long hour = TimeUnit.HOURS.toNanos(1L);
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, hour, MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.Snapshot snapshot = metrics.snapshot(hour + MILLISECOND);

        assertEquals(1L, snapshot.fiveSeconds().sampleCount());
        assertEquals(1L, snapshot.oneMinute().sampleCount());
        assertEquals(1L, snapshot.fifteenMinutes().sampleCount());
    }

    @Test
    void targetIntervalIsRetainedPerSampleWhenRateChanges() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), 50L * MILLISECOND);
        metrics.tickCompleted(tick(0L, 50L * MILLISECOND, MILLISECOND), 25L * MILLISECOND);
        metrics.tickCompleted(tick(50L * MILLISECOND, 75L * MILLISECOND, MILLISECOND), 25L * MILLISECOND);

        final TickData.TickReportData report = metrics.report(SECOND, null, 76L * MILLISECOND, 25L * MILLISECOND);

        assertEquals(3, report.collectedTicks());
        assertArrayEquals(new long[] {25L * MILLISECOND, 50L * MILLISECOND, 50L * MILLISECOND},
            report.tpsData().rawData());
    }

    @Test
    void shortWindowIncludesExactBoundaryAndExcludesOneNanosecondOlderSample() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 1L), TARGET_20_TPS);
        metrics.tickCompleted(tick(0L, 1L, 1L), TARGET_20_TPS);

        assertEquals(2, metrics.report(5L * SECOND, null, 5L * SECOND + 1L, TARGET_20_TPS).collectedTicks());
        assertEquals(1, metrics.report(5L * SECOND, null, 5L * SECOND + 2L, TARGET_20_TPS).collectedTicks());
    }

    @Test
    void longWindowsUseDeclaredOneAndFiveSecondAlignment() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 999L * MILLISECOND, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(999L * MILLISECOND, SECOND, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(SECOND, 61L * SECOND, 0L), TARGET_20_TPS);

        final RegionTickMetrics.Snapshot atBoundary = metrics.snapshot(61L * SECOND);
        metrics.tickCompleted(tick(61L * SECOND, 62L * SECOND + 1L, 0L), TARGET_20_TPS);
        final RegionTickMetrics.Snapshot pastBoundary = metrics.snapshot(62L * SECOND + 1L);

        assertEquals(3L, atBoundary.oneMinute().sampleCount());
        assertEquals(2L, pastBoundary.oneMinute().sampleCount());
        assertTrue(atBoundary.oneMinute().approximate());
        assertTrue(atBoundary.fiveMinutes().approximate());
        assertTrue(atBoundary.fifteenMinutes().approximate());
    }

    @Test
    void fiveAndFifteenMinuteWindowsCombineNewestMinuteWithDisjointOlderBuckets() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (long end = 0L; end <= 900L * SECOND; end += 5L * SECOND) {
            metrics.tickCompleted(tick(previous, end, 0L), 5L * SECOND);
            previous = end;
        }

        final RegionTickMetrics.Snapshot aligned = metrics.snapshot(900L * SECOND);
        assertEquals(61L, aligned.fiveMinutes().sampleCount());
        assertEquals(305L * SECOND, aligned.fiveMinutes().intervalNanos());
        assertEquals(181L, aligned.fifteenMinutes().sampleCount());
        assertEquals(905L * SECOND, aligned.fifteenMinutes().intervalNanos());

        metrics.tickCompleted(tick(previous, 903L * SECOND, 0L), 3L * SECOND);
        final RegionTickMetrics.Snapshot offset = metrics.snapshot(903L * SECOND);
        assertEquals(60L, offset.fiveMinutes().sampleCount());
        assertEquals(298L * SECOND, offset.fiveMinutes().intervalNanos());
        assertEquals(180L, offset.fifteenMinutes().sampleCount());
        assertEquals(898L * SECOND, offset.fifteenMinutes().intervalNanos());
    }

    @Test
    void shortWindowsMatchIndependentRawReferenceModel() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        final List<Sample> samples = new ArrayList<>();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 0; i <= 80; ++i) {
            final long start = i * 250L * MILLISECOND;
            final long duration = (1L + i % 5L) * MILLISECOND;
            metrics.tickCompleted(tick(previous, start, duration), 250L * MILLISECOND);
            samples.add(new Sample(start + duration, previous == TimeUtil.DEADLINE_NOT_SET
                ? 250L * MILLISECOND : start - previous, duration));
            previous = start;
        }
        final long now = 20L * SECOND + MILLISECOND;
        final RegionTickMetrics.Snapshot snapshot = metrics.snapshot(now);

        assertMatchesReference(snapshot.fiveSeconds(), samples, now, 5L * SECOND);
        assertMatchesReference(snapshot.tenSeconds(), samples, now, 10L * SECOND);
        assertMatchesReference(snapshot.fifteenSeconds(), samples, now, 15L * SECOND);
    }

    @Test
    void histogramUnderflowOverflowAndNearestRanksAreBounded() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 0; i < 19; ++i) {
            final long start = i * MILLISECOND;
            metrics.tickCompleted(tick(previous, start, 100L), MILLISECOND);
            previous = start;
        }
        metrics.tickCompleted(new TickTime(previous, SECOND, SECOND, 0L, SECOND + 1L, 0L,
            17L * SECOND - 1L, 0L, false), MILLISECOND);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(SECOND + 1L).fiveSeconds();
        assertEquals(100L, window.minimumNanos());
        assertEquals(17L * SECOND, window.maximumNanos());
        assertEquals(0.25, window.estimatedP95Mspt(), 1.0E-9);
        assertEquals(16_000.0, window.estimatedP99Mspt(), 1.0E-9);
    }

    @Test
    void sparseHistogramCompressesMoreThanFourBinsWithoutLosingRankMass() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 1; i <= 5; ++i) {
            final long start = i * 10L * MILLISECOND;
            metrics.tickCompleted(tick(previous, start, (1L << i) * MILLISECOND), TARGET_20_TPS);
            previous = start;
        }
        metrics.tickCompleted(tick(previous, SECOND, 2L * SECOND), TARGET_20_TPS);

        final RegionTickMetrics.Snapshot snapshot = metrics.refreshSnapshot(3L * SECOND);
        final RegionTickMetrics.WindowSnapshot window = snapshot.oneMinute();
        final long[] histogram = new long[64];
        metrics.mergeHistogram(TimeUnit.MINUTES.toNanos(1L), 3L * SECOND, histogram, 0);

        assertFalse(window.truncated());
        assertTrue(Double.isFinite(window.medianNanos()));
        assertTrue(Double.isFinite(window.estimatedP95Mspt()));
        assertTrue(Double.isFinite(window.estimatedP99Mspt()));
        assertEquals(6L, window.sampleCount());
        assertEquals(window.sampleCount(), java.util.Arrays.stream(histogram).sum());
    }

    @Test
    void refreshExpiresShortWindowsWhileTheRegionTickIsStalled() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), TARGET_20_TPS);
        metrics.tickStarted(2L * SECOND);

        final RegionTickMetrics.Snapshot active = metrics.refreshSnapshot(5L * SECOND);
        assertEquals(1L, active.fiveSeconds().sampleCount());
        assertEquals(2L * SECOND, active.activeTickStartNanos());
        assertEquals(0L, metrics.refreshSnapshot(6L * SECOND + MILLISECOND + 1L).fiveSeconds().sampleCount());
        final RegionTickMetrics.Snapshot expired = metrics.refreshSnapshot(11L * SECOND + MILLISECOND + 1L);
        assertEquals(0L, expired.tenSeconds().sampleCount());
        assertEquals(2L * SECOND, metrics.snapshot(11L * SECOND + MILLISECOND + 1L).activeTickStartNanos());
    }

    @Test
    void sameEpochRefreshReusesPublicationAndNextEpochExpiresData() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.Snapshot first = metrics.refreshSnapshot(5L * SECOND + MILLISECOND);
        assertSame(first, metrics.refreshSnapshot(5L * SECOND + 2L * MILLISECOND));
        final RegionTickMetrics.Snapshot next = metrics.refreshSnapshot(6L * SECOND + 2L * MILLISECOND);

        assertFalse(first == next);
        assertEquals(1L, first.fiveSeconds().sampleCount());
        assertEquals(0L, next.fiveSeconds().sampleCount());

        metrics.tickStarted(7L * SECOND);
        final RegionTickMetrics.Snapshot active = metrics.refreshSnapshot(7L * SECOND);
        assertSame(active, metrics.refreshSnapshot(7L * SECOND + MILLISECOND));
    }

    @Test
    void sealRacingTickStartNeverPublishesPostSealActivityOrAcceptsCompletion() throws Exception {
        for (int attempt = 0; attempt < 10_000; ++attempt) {
            final RegionTickMetrics metrics = new RegionTickMetrics();
            final CountDownLatch ready = new CountDownLatch(2);
            final CountDownLatch start = new CountDownLatch(1);
            final Thread starter = Thread.ofPlatform().start(() -> {
                ready.countDown();
                await(start);
                metrics.tickStarted(123L);
            });
            final Thread sealer = Thread.ofPlatform().start(() -> {
                ready.countDown();
                await(start);
                metrics.seal(124L);
            });
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
            starter.join();
            sealer.join();

            metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 123L, 1L), TARGET_20_TPS);
            final RegionTickMetrics.Snapshot sealed = metrics.refreshSnapshot(125L);
            assertEquals(RegionTickMetrics.INACTIVE, sealed.activeTickStartNanos());
            assertEquals(0L, sealed.fiveSeconds().sampleCount());
        }
    }

    @Test
    void completionClearsOnlyItsOwnActiveStart() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickStarted(123L);

        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 456L, 1L), TARGET_20_TPS);
        assertEquals(123L, metrics.snapshot(457L).activeTickStartNanos());

        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 123L, 1L), TARGET_20_TPS);
        assertEquals(RegionTickMetrics.INACTIVE, metrics.snapshot(458L).activeTickStartNanos());
    }

    @Test
    void activeAndSealedStateShareOneAtomicLongMarker() throws Exception {
        final var state = RegionTickMetrics.class.getDeclaredField("activeState");
        assertEquals(long.class, state.getType());
        assertTrue(Modifier.isVolatile(state.getModifiers()));
        assertThrows(NoSuchFieldException.class, () -> RegionTickMetrics.class.getDeclaredField("sealed"));

        final String resource = "/" + RegionTickMetrics.class.getName().replace('.', '/') + ".class";
        int compareAndSets = 0;
        try (InputStream input = RegionTickMetrics.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            for (final MethodModel method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!method.methodName().stringValue().equals("tickStarted")) continue;
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction invocation
                        && invocation.name().stringValue().equals("compareAndSet")) {
                        ++compareAndSets;
                    }
                }
            }
        }
        assertEquals(1, compareAndSets);
    }

    @Test
    void ownerAcquisitionKeepsEveryHistogramConsistentWithItsSnapshot() throws Exception {
        final CountDownLatch completionEntered = new CountDownLatch(1);
        final CountDownLatch releaseCompletion = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean blockCompletion = new java.util.concurrent.atomic.AtomicBoolean();
        final var constructor = RegionTickMetrics.class.getDeclaredConstructor(Runnable.class);
        constructor.setAccessible(true);
        final RegionTickMetrics metrics = constructor.newInstance((Runnable)() -> {
            if (blockCompletion.get()) {
                completionEntered.countDown();
                await(releaseCompletion);
            }
        });
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), TARGET_20_TPS);
        blockCompletion.set(true);
        final Thread completion = Thread.ofPlatform().start(() -> metrics.tickCompleted(
            tick(0L, 2L * MILLISECOND, MILLISECOND), TARGET_20_TPS));
        assertTrue(completionEntered.await(5L, TimeUnit.SECONDS));
        final long[] histograms = new long[5 * 64];
        final var acquired = new java.util.concurrent.atomic.AtomicReference<RegionTickMetrics.Snapshot>();
        final Thread collector = Thread.ofPlatform().start(() ->
            acquired.set(metrics.acquireSnapshot(3L * MILLISECOND, histograms, 0)));

        releaseCompletion.countDown();
        completion.join();
        collector.join();

        final RegionTickMetrics.Snapshot snapshot = acquired.get();
        assertNotNull(snapshot);
        final long[] counts = {
            snapshot.fiveSeconds().sampleCount(), snapshot.tenSeconds().sampleCount(),
            snapshot.oneMinute().sampleCount(), snapshot.fiveMinutes().sampleCount(),
            snapshot.fifteenMinutes().sampleCount()
        };
        for (int window = 0; window < counts.length; ++window) {
            long rankMass = 0L;
            for (int bin = 0; bin < 64; ++bin) rankMass += histograms[window * 64 + bin];
            assertEquals(counts[window], rankMass);
        }

        final long[] repeatedHistograms = new long[5 * 64];
        assertSame(snapshot, metrics.acquireSnapshot(3L * MILLISECOND, repeatedHistograms, 0));
        assertArrayEquals(histograms, repeatedHistograms);
    }

    @Test
    void sealPublishesSameSecondCompletionsAndIgnoresEveryLaterWrite() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 10L, 1L), TARGET_20_TPS);
        metrics.tickCompleted(tick(10L, 20L, 1L), TARGET_20_TPS);
        assertEquals(1L, metrics.snapshot(21L).fiveSeconds().sampleCount());

        final RegionTickMetrics.Snapshot sealed = metrics.seal(21L);
        metrics.tickStarted(30L);
        metrics.tickCompleted(tick(20L, 30L, 1L), TARGET_20_TPS);

        assertEquals(2L, sealed.fiveSeconds().sampleCount());
        assertEquals(2L, metrics.refreshSnapshot(31L).fiveSeconds().sampleCount());
        assertEquals(RegionTickMetrics.INACTIVE, metrics.snapshot(31L).activeTickStartNanos());
    }

    @Test
    void nearLongMaximumEpochRetainsOrderedSamples() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        final long start = Long.MAX_VALUE - 2L * SECOND;
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, start, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(start, start + SECOND, MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(start + SECOND + MILLISECOND).oneMinute();
        assertEquals(2L, window.sampleCount());
        assertEquals(1_050L * MILLISECOND, window.intervalNanos());
        assertEquals(2L * MILLISECOND, window.executionNanos());
    }

    @Test
    void publicationUsesPreallocatedHistogramScratchAndSameSecondPathAllocatesNoSnapshot() throws Exception {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), TARGET_20_TPS);
        final RegionTickMetrics.Snapshot first = metrics.snapshot(MILLISECOND);
        metrics.tickCompleted(tick(0L, 2L * MILLISECOND, MILLISECOND), TARGET_20_TPS);
        assertSame(first, metrics.snapshot(3L * MILLISECOND));

        assertNoAllocationOpcodes(RegionTickMetrics.class,
            Set.of("tickCompleted", "recordCompletedSample"), true);
        assertNoAllocationOpcodes(RegionTickMetrics.class,
            Set.of("publishSnapshotIfDue", "rawWindow", "bucketWindow"), false);
        assertNoAllocationOpcodes(Class.forName("ca.spottedleaf.common.time.RegionTickMetrics$BucketStore"),
            Set.of("add", "addUtilisation", "prepare"), true);
    }

    @Test
    void compatibilityAccessUsesTheWriterMonitor() throws Exception {
        assertFalse(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("tickStarted", long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("tickCompleted", TickTime.class, long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("report", long.class, TickTime.class, long.class, long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("tps", long.class, TickTime.class, long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("mspt", long.class, TickTime.class, long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("refreshSnapshot", long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("seal", long.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("mergeHistogram", long.class, long.class, long[].class, int.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(RegionTickMetrics.class
            .getDeclaredMethod("acquireSnapshot", long.class, long[].class, int.class).getModifiers()));
    }

    @Test
    void completionFailureAlwaysClearsActiveTickMarker() throws Exception {
        final var constructor = RegionTickMetrics.class.getDeclaredConstructor(Runnable.class);
        constructor.setAccessible(true);
        final RegionTickMetrics metrics = constructor.newInstance((Runnable)() -> {
            throw new IllegalStateException("injected completion failure");
        });
        metrics.tickStarted(123L);

        assertThrows(IllegalStateException.class,
            () -> metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 123L, 1L), TARGET_20_TPS));
        assertEquals(RegionTickMetrics.INACTIVE, metrics.snapshot(124L).activeTickStartNanos());
    }

    @Test
    void arithmeticOverflowSaturatesAndMarksWindowTruncated() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(new TickTime(TimeUtil.DEADLINE_NOT_SET, 0L, 0L, 0L, 1L, 0L,
            Long.MAX_VALUE, 0L, false), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(1L).fiveSeconds();

        assertEquals(Long.MAX_VALUE, window.executionNanos());
        assertTrue(window.truncated());
        assertTrue(Double.isNaN(window.mspt()));
    }

    @Test
    void rawRingHardCapIsExplicitlyReportedAsTruncation() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        appendTicks(metrics, 1_000, 0L, MILLISECOND, 1L);
        metrics.tickCompleted(tick(999L * MILLISECOND, SECOND, 1L), MILLISECOND);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(SECOND + 1L).fifteenSeconds();

        assertTrue(window.sampleCount() < 1_001L);
        assertTrue(window.truncated());
    }

    @Test
    void compatibilityReportSortsExactShortWindowValues() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 3L * MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(0L, TARGET_20_TPS, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(TARGET_20_TPS, 2L * TARGET_20_TPS, 2L * MILLISECOND), TARGET_20_TPS);

        final TickData.TickReportData report = metrics.report(5L * SECOND, null, 103L * MILLISECOND, TARGET_20_TPS);

        assertEquals(3, report.collectedTicks());
        assertArrayEquals(new long[] {MILLISECOND, 2L * MILLISECOND, 3L * MILLISECOND}, report.timePerTickData().rawData());
        assertEquals(2.0, metrics.mspt(5L * SECOND, null, TARGET_20_TPS).avg(), 1.0E-9);
        assertEquals(20.0, metrics.tps(5L * SECOND, null, TARGET_20_TPS), 1.0E-9);
    }

    private static void assertWindow(final RegionTickMetrics.WindowSnapshot window, final long count,
                                     final long intervalNanos, final long executionNanos) {
        assertEquals(count, window.sampleCount());
        assertEquals(intervalNanos, window.intervalNanos());
        assertEquals(executionNanos, window.executionNanos());
        assertEquals(count * 1_000_000_000.0 / intervalNanos, window.tps(), 1.0E-9);
        assertEquals((double)executionNanos / count / 1_000_000.0, window.mspt(), 1.0E-9);
    }

    private static void assertMatchesReference(final RegionTickMetrics.WindowSnapshot actual,
                                               final List<Sample> samples, final long now, final long window) {
        long count = 0L;
        long intervals = 0L;
        long executions = 0L;
        final long cutoff = now - window;
        for (final Sample sample : samples) {
            if (sample.endNanos() >= cutoff && sample.endNanos() <= now) {
                ++count;
                intervals += sample.intervalNanos();
                executions += sample.executionNanos();
            }
        }
        assertWindow(actual, count, intervals, executions);
    }

    private static void assertNoAllocationOpcodes(final Class<?> type, final Set<String> methodNames,
                                                  final boolean rejectObjects) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input);
            int matchedMethods = 0;
            for (final MethodModel method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!methodNames.contains(method.methodName().stringValue())) {
                    continue;
                }
                ++matchedMethods;
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (!(element instanceof Instruction instruction)) {
                        continue;
                    }
                    assertFalse(instruction.opcode() == java.lang.classfile.Opcode.NEWARRAY
                        || instruction.opcode() == java.lang.classfile.Opcode.ANEWARRAY
                        || instruction.opcode() == java.lang.classfile.Opcode.MULTIANEWARRAY
                        || rejectObjects && instruction.opcode() == java.lang.classfile.Opcode.NEW,
                        () -> type.getName() + "." + method.methodName() + " allocates via " + instruction.opcode());
                }
            }
            assertEquals(methodNames.size(), matchedMethods);
        }
    }

    private static void appendTicks(final RegionTickMetrics metrics, final int count, final long firstStart,
                                    final long interval, final long duration) {
        long previous = TimeUtil.DEADLINE_NOT_SET;
        long start = firstStart;
        for (int i = 0; i < count; ++i) {
            metrics.tickCompleted(tick(previous, start, duration), interval);
            previous = start;
            start += interval;
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static TickTime tick(final long previousStart, final long start, final long duration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, 0L, 0L, 0L, false);
    }

    private static TickTime cpuTick(final long previousStart, final long start, final long duration, final long cpuDuration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, cpuDuration, 0L, 0L, true);
    }

    private record Sample(long endNanos, long intervalNanos, long executionNanos) {}
}
