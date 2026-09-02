package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.util.ContainerMemory;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Publishes the process-wide, read-only performance snapshot on a dedicated daemon. */
public final class PerformanceCollector implements AutoCloseable {

    private static final long PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long JOIN_MILLIS = 2_000L;
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final MetricWindow[] WINDOWS = MetricWindow.values();

    @FunctionalInterface
    interface GenerationSource {
        void forEach(long nowNanos, Consumer<RegionMetricsRegistry.GenerationView> consumer);
    }

    private final SourbyMetricsProvider provider;
    private final GenerationSource generations;
    private final Supplier<ImmutableRuntimeMetrics> runtimeSource;
    private final LongSupplier nanoClock;
    private final LongSupplier epochClock;
    private final double targetTps;
    private final WindowAccumulator[] accumulators = new WindowAccumulator[WINDOWS.length];
    private final Thread worker;
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;
    private long sequence;
    private double[] medianBuffer = new double[WINDOWS.length * 2 * 8];
    private int medianCapacity = 8;
    private int activeRegions;
    private int retainedGenerations;

    public PerformanceCollector(final SourbyMetricsProvider provider, final RegionMetricsRegistry registry,
                                final Supplier<ImmutableRuntimeMetrics> runtimeSource) {
        this(provider, registry::forEachUnexpired, runtimeSource,
            System::nanoTime, System::currentTimeMillis, 20.0);
    }

    PerformanceCollector(final SourbyMetricsProvider provider, final GenerationSource generations,
                         final Supplier<ImmutableRuntimeMetrics> runtimeSource,
                         final LongSupplier nanoClock, final LongSupplier epochClock,
                         final double targetTps) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.runtimeSource = Objects.requireNonNull(runtimeSource, "runtimeSource");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.epochClock = Objects.requireNonNull(epochClock, "epochClock");
        this.targetTps = targetTps;
        for (int i = 0; i < this.accumulators.length; ++i) {
            this.accumulators[i] = new WindowAccumulator(WINDOWS[i]);
        }
        this.worker = new Thread(this::run, "SourbyCraft-PerformanceCollector");
        this.worker.setDaemon(true);
    }

    static ImmutableRuntimeMetrics runtimeSnapshot() {
        final MemoryUsage heap = MEMORY.getHeapMemoryUsage();
        final GcTracker.Gc gc = GcTracker.snapshot();
        return new ImmutableRuntimeMetrics(heap.getUsed(), heap.getMax(), ContainerMemory.usagePercentOrNaN(),
            gc.hasData() ? gc.gcTimePercent() : Double.NaN,
            gc.hasData() ? gc.collectionsPerMin() : Double.NaN,
            gc.hasData() ? gc.avgPauseMs() : Double.NaN);
    }

    void start() {
        this.worker.start();
    }

    Thread worker() {
        return this.worker;
    }

    void collect(final long nowNanos, final long nowEpochMillis, final long latenessNanos) {
        if (this.closed) {
            return;
        }
        final long scanStarted = this.nanoClock.getAsLong();
        try {
            this.reset();
            this.generations.forEach(nowNanos, view -> this.accept(view, nowNanos));
            final ImmutableRuntimeMetrics runtime = this.runtimeSource.get();
            final ImmutableWindowMetrics[] windows = new ImmutableWindowMetrics[WINDOWS.length];
            boolean warming = false;
            for (int i = 0; i < windows.length; ++i) {
                windows[i] = this.accumulators[i].finish(i, this.activeRegions);
                warming |= windows[i].coverageMillis() < windowMillis(WINDOWS[i]);
            }
            final long duration = elapsed(this.nanoClock.getAsLong(), scanStarted);
            final long latenessMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, latenessNanos));
            final MetricState state = warming ? MetricState.WARMING : MetricState.AVAILABLE;
            final ImmutablePerformanceSnapshot next = new ImmutablePerformanceSnapshot(
                ++this.sequence, nowEpochMillis, this.targetTps, this.activeRegions,
                this.retainedGenerations,
                new ImmutableFreshness(state, 0L, latenessMillis, duration, ""),
                windows[0], windows[1], windows[2], windows[3], windows[4], runtime);
            this.publishUnlessClosed(next);
        } catch (final Throwable failure) {
            final long duration = elapsed(this.nanoClock.getAsLong(), scanStarted);
            final long latenessMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, latenessNanos));
            final String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getClass().getSimpleName() + ": " + failure.getMessage();
            final ImmutablePerformanceSnapshot previous = (ImmutablePerformanceSnapshot)this.provider.snapshot();
            final ImmutablePerformanceSnapshot stale = previous.stale(
                ++this.sequence, nowEpochMillis, latenessMillis, duration, message);
            this.publishUnlessClosed(stale);
        }
    }

    private void run() {
        long deadline = saturatingAdd(this.nanoClock.getAsLong(), PERIOD_NANOS);
        while (!this.closed) {
            final long before = this.nanoClock.getAsLong();
            final long wait = deadline - before;
            if (wait > 0L) {
                LockSupport.parkNanos(this, wait);
            }
            if (this.closed || Thread.interrupted()) {
                break;
            }
            final long now = this.nanoClock.getAsLong();
            this.collect(now, this.epochClock.getAsLong(), Math.max(0L, now - deadline));
            final long periods = Math.max(1L, Math.floorDiv(Math.max(0L, now - deadline), PERIOD_NANOS) + 1L);
            deadline = saturatingAdd(deadline, saturatingMultiply(periods, PERIOD_NANOS));
        }
    }

    private void reset() {
        this.activeRegions = 0;
        this.retainedGenerations = 0;
        for (final WindowAccumulator accumulator : this.accumulators) {
            accumulator.reset();
        }
    }

    private void accept(final RegionMetricsRegistry.GenerationView view, final long nowNanos) {
        final int activeIndex;
        ++this.retainedGenerations;
        if (view.active()) {
            activeIndex = this.activeRegions++;
            this.ensureMedianCapacity(this.activeRegions);
        } else {
            activeIndex = -1;
        }
        for (int i = 0; i < this.accumulators.length; ++i) {
            this.accumulators[i].accept(window(view.snapshot(), WINDOWS[i]), view.active(), activeIndex,
                i, view.snapshot().activeTickStartNanos(), nowNanos);
        }
    }

    private void ensureMedianCapacity(final int required) {
        if (required <= this.medianCapacity) {
            return;
        }
        int next = this.medianCapacity;
        while (next < required) {
            next = Math.multiplyExact(next, 2);
        }
        final double[] grown = new double[WINDOWS.length * 2 * next];
        for (int window = 0; window < WINDOWS.length; ++window) {
            System.arraycopy(this.medianBuffer, window * 2 * this.medianCapacity,
                grown, window * 2 * next, this.activeRegions - 1);
            System.arraycopy(this.medianBuffer, (window * 2 + 1) * this.medianCapacity,
                grown, (window * 2 + 1) * next, this.activeRegions - 1);
        }
        this.medianBuffer = grown;
        this.medianCapacity = next;
    }

    private static RegionTickMetrics.WindowSnapshot window(final RegionTickMetrics.Snapshot snapshot,
                                                           final MetricWindow window) {
        return switch (window) {
            case FIVE_SECONDS -> snapshot.fiveSeconds();
            case TEN_SECONDS -> snapshot.tenSeconds();
            case ONE_MINUTE -> snapshot.oneMinute();
            case FIVE_MINUTES -> snapshot.fiveMinutes();
            case FIFTEEN_MINUTES -> snapshot.fifteenMinutes();
        };
    }

    private static long windowNanos(final MetricWindow window) {
        return switch (window) {
            case FIVE_SECONDS -> TimeUnit.SECONDS.toNanos(5L);
            case TEN_SECONDS -> TimeUnit.SECONDS.toNanos(10L);
            case ONE_MINUTE -> TimeUnit.MINUTES.toNanos(1L);
            case FIVE_MINUTES -> TimeUnit.MINUTES.toNanos(5L);
            case FIFTEEN_MINUTES -> TimeUnit.MINUTES.toNanos(15L);
        };
    }

    private static long windowMillis(final MetricWindow window) {
        return TimeUnit.NANOSECONDS.toMillis(windowNanos(window));
    }

    private static long elapsed(final long end, final long start) {
        final long difference = end - start;
        return difference < 0L ? (end >= start ? Long.MAX_VALUE : 0L) : difference;
    }

    private static long saturatingAdd(final long first, final long second) {
        return first < 0L || second < 0L || first > Long.MAX_VALUE - second
            ? Long.MAX_VALUE : first + second;
    }

    private static long saturatingMultiply(final long first, final long second) {
        return first == 0L || second == 0L ? 0L
            : first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    private void publishUnlessClosed(final ImmutablePerformanceSnapshot next) {
        synchronized (this.lifecycleLock) {
            if (!this.closed) {
                this.provider.publish(next);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this.lifecycleLock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
        }
        this.worker.interrupt();
        if (Thread.currentThread() == this.worker || !this.worker.isAlive()) {
            return;
        }
        try {
            this.worker.join(JOIN_MILLIS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final class WindowAccumulator {
        private final MetricWindow window;
        private long count;
        private long interval;
        private long execution;
        private long missingCpu;
        private long coverageNanos;
        private long minimum;
        private long maximum;
        private double medianMspt;
        private double p95;
        private double p99;
        private double busiestUtilisation;
        private double utilisationSum;
        private int utilisationCount;
        private boolean approximate;
        private boolean truncated;
        private boolean overflow;

        private WindowAccumulator(final MetricWindow window) {
            this.window = window;
        }

        private void reset() {
            this.count = 0L;
            this.interval = 0L;
            this.execution = 0L;
            this.missingCpu = 0L;
            this.coverageNanos = 0L;
            this.minimum = Long.MAX_VALUE;
            this.maximum = 0L;
            this.medianMspt = Double.NaN;
            this.p95 = Double.NaN;
            this.p99 = Double.NaN;
            this.busiestUtilisation = Double.NaN;
            this.utilisationSum = 0.0;
            this.utilisationCount = 0;
            this.approximate = false;
            this.truncated = false;
            this.overflow = false;
        }

        private void accept(final RegionTickMetrics.WindowSnapshot source, final boolean active,
                            final int activeIndex, final int windowIndex,
                            final long activeTickStart, final long nowNanos) {
            this.count = this.add(this.count, source.sampleCount());
            this.interval = this.add(this.interval, source.intervalNanos());
            this.execution = this.add(this.execution, source.executionNanos());
            this.missingCpu = this.add(this.missingCpu, source.missingCpuNanos());
            this.coverageNanos = Math.max(this.coverageNanos,
                Math.min(windowNanos(this.window), source.intervalNanos()));
            if (source.sampleCount() > 0L) {
                this.minimum = Math.min(this.minimum, source.minimumNanos());
                this.maximum = Math.max(this.maximum, source.maximumNanos());
                this.medianMspt = maxFinite(this.medianMspt, source.medianNanos() * 1.0E-6);
                this.p95 = maxFinite(this.p95, source.estimatedP95Mspt());
                this.p99 = maxFinite(this.p99, source.estimatedP99Mspt());
            }
            this.approximate |= source.approximate();
            this.truncated |= source.truncated();
            if (!active) {
                return;
            }

            long activeExecution = source.executionNanos();
            long activeCount = source.sampleCount();
            double utilisation = source.utilisation();
            long activeMaximum = source.maximumNanos();
            if (activeTickStart != RegionTickMetrics.INACTIVE) {
                final long stalled = Math.min(windowNanos(this.window), elapsed(nowNanos, activeTickStart));
                activeExecution = saturatingAdd(activeExecution, stalled);
                activeCount = saturatingAdd(activeCount, 1L);
                activeMaximum = Math.max(activeMaximum, stalled);
                utilisation = Math.min(1.0, utilisation + (double)stalled / windowNanos(this.window));
                this.maximum = Math.max(this.maximum, activeMaximum);
            }
            final double tps = source.intervalNanos() > 0L && source.intervalNanos() != Long.MAX_VALUE
                ? (double)source.sampleCount() * 1.0E9 / source.intervalNanos() : Double.NaN;
            final double mspt = activeCount > 0L && activeExecution != Long.MAX_VALUE
                ? (double)activeExecution / activeCount * 1.0E-6 : Double.NaN;
            PerformanceCollector.this.medianBuffer[windowIndex * 2 * PerformanceCollector.this.medianCapacity + activeIndex] = tps;
            PerformanceCollector.this.medianBuffer[(windowIndex * 2 + 1) * PerformanceCollector.this.medianCapacity + activeIndex] = mspt;
            if ((source.sampleCount() > 0L || activeTickStart != RegionTickMetrics.INACTIVE)
                && Double.isFinite(utilisation)) {
                this.busiestUtilisation = maxFinite(this.busiestUtilisation, utilisation);
                this.utilisationSum += utilisation;
                ++this.utilisationCount;
            }
        }

        private long add(final long current, final long value) {
            final long result = saturatingAdd(current, value);
            this.overflow |= result == Long.MAX_VALUE && (current != Long.MAX_VALUE || value != 0L);
            return result;
        }

        private ImmutableWindowMetrics finish(final int windowIndex, final int activeCount) {
            final int tpsOffset = windowIndex * 2 * PerformanceCollector.this.medianCapacity;
            final int msptOffset = tpsOffset + PerformanceCollector.this.medianCapacity;
            final double worstTps = minimumFinite(PerformanceCollector.this.medianBuffer, tpsOffset, activeCount);
            final double medianTps = medianFinite(PerformanceCollector.this.medianBuffer, tpsOffset, activeCount);
            final double worstAverageMspt = maximumFinite(PerformanceCollector.this.medianBuffer, msptOffset, activeCount);
            final double medianAverageMspt = medianFinite(PerformanceCollector.this.medianBuffer, msptOffset, activeCount);
            final boolean invalid = this.overflow || this.interval == Long.MAX_VALUE || this.execution == Long.MAX_VALUE;
            final double aggregateTps = invalid || this.interval == 0L
                ? Double.NaN : (double)this.count * 1.0E9 / this.interval;
            final double averageUtilisation = this.utilisationCount == 0
                ? Double.NaN : this.utilisationSum / this.utilisationCount;
            return new ImmutableWindowMetrics(TimeUnit.NANOSECONDS.toMillis(this.coverageNanos), this.count,
                this.approximate, this.truncated || this.overflow,
                worstTps, medianTps, aggregateTps, worstAverageMspt, medianAverageMspt,
                this.count == 0L ? Double.NaN : this.minimum * 1.0E-6,
                this.count == 0L ? Double.NaN : this.maximum * 1.0E-6,
                this.truncated || this.overflow ? Double.NaN : this.medianMspt,
                this.truncated || this.overflow ? Double.NaN : this.p95,
                this.truncated || this.overflow ? Double.NaN : this.p99,
                this.busiestUtilisation, averageUtilisation,
                this.overflow || this.missingCpu == Long.MAX_VALUE ? Double.NaN : this.missingCpu * 1.0E-6);
        }
    }

    private static double minimumFinite(final double[] values, final int offset, final int length) {
        double result = Double.NaN;
        for (int i = 0; i < length; ++i) {
            final double value = values[offset + i];
            if (Double.isFinite(value)) result = Double.isNaN(result) ? value : Math.min(result, value);
        }
        return result;
    }

    private static double maximumFinite(final double[] values, final int offset, final int length) {
        double result = Double.NaN;
        for (int i = 0; i < length; ++i) {
            result = maxFinite(result, values[offset + i]);
        }
        return result;
    }

    private static double medianFinite(final double[] values, final int offset, final int length) {
        int finite = 0;
        for (int i = 0; i < length; ++i) {
            final double value = values[offset + i];
            if (Double.isFinite(value)) values[offset + finite++] = value;
        }
        if (finite == 0) return Double.NaN;
        Arrays.sort(values, offset, offset + finite);
        final int middle = finite >>> 1;
        return (finite & 1) == 0
            ? (values[offset + middle - 1] + values[offset + middle]) / 2.0
            : values[offset + middle];
    }

    private static double maxFinite(final double current, final double value) {
        if (!Double.isFinite(value)) return current;
        return Double.isNaN(current) ? value : Math.max(current, value);
    }
}
