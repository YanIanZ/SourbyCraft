package dev.iyanz.sourbycraft.perf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * How long chunk-system saves take at the region-file (.mca) I/O stage, per world and storage type.
 *
 * <p><b>What is timed.</b> Feature patch 0043 wraps the region-file part of
 * {@code RegionFileStorage.moonrise$finishWrite}: the write of the already serialized and
 * compressed chunk into its {@code .mca} file (sector allocation, file write, header update), or
 * the {@code clear} of a deleted chunk, on the chunk-system I/O thread. Not timed: the
 * serialization/compression stage ({@code moonrise$startWrite}, on the compression executor), the
 * optional {@code flush-regions-on-save} flush that follows in {@code MoonriseRegionFileIO},
 * vanilla's non-chunk-system {@code write(ChunkPos, CompoundTag)} (upgrade tools, AWF export),
 * and storages attached to Aurora World Fabric, whose writes never reach a region file (their
 * commit latency is {@code AwfMetrics}).</p>
 *
 * <p><b>Recording cost.</b> The caller reads {@code System.nanoTime()} twice. {@link Recorder#record}
 * then does one {@code getAndIncrement} on the recorder's sequence, one volatile store into a fixed
 * {@code AtomicLongArray} slot, one read (and only on a new maximum, a CAS) of the all-time maximum,
 * and on failure one {@code LongAdder} increment. No lock, no allocation, no map lookup: the
 * {@link Recorder} is resolved once per {@code RegionFileStorage}, in its constructor.</p>
 *
 * <p><b>Window.</b> Each recorder keeps its last {@link #CAPACITY} samples in a ring; a sample is a
 * single {@code long} packing the second (since the recorder's epoch) it ended in and its duration in
 * microseconds, so a reader never sees half of one sample. Percentiles and the window maximum cover
 * the retained samples that ended within the last {@link #WINDOW_SECONDS} seconds (second
 * granularity, so the window edge is fuzzy by up to one second). Durations saturate at 2^32-1 µs
 * (~71 minutes).</p>
 *
 * <p><b>What concurrency can lose.</b> {@code saves}, {@code failures} and {@code maxEverMillis} are
 * exact. The ring is approximate in three documented ways: a sample older than the last
 * {@link #CAPACITY} falls out of the window; a snapshot taken while a writer has claimed a slot but not
 * yet stored into it skips that slot (or reads the sample a full lap older, which the time filter then
 * usually drops); and if {@link #CAPACITY} or more saves are recorded between one writer's claim and its
 * store, two writers store into the same slot and one of those samples is lost from the window.</p>
 *
 * <p>Read-only to everyone but the patch: {@link #snapshot()} copies, nothing returned is live.</p>
 */
public final class RegionFileSaveLatency {

    /** Samples retained per recorder (a power of two; 8 KiB per recorder). */
    public static final int CAPACITY = 1024;
    /** The percentile window. */
    public static final int WINDOW_SECONDS = 60;

    public static final RegionFileSaveLatency GLOBAL = new RegionFileSaveLatency(System::nanoTime);

    private static final long MAX_MICROS = 0xFFFF_FFFFL;

    /**
     * One recorder's state at the moment of {@link #snapshot()}.
     *
     * @param world the level storage id ({@code RegionStorageInfo.level()}, the world folder name)
     * @param dimension the dimension key, e.g. {@code minecraft:overworld}
     * @param type the storage type: {@code chunk}, {@code entities} or {@code poi}
     * @param saves region-file saves timed since start (successful and failed)
     * @param failures saves that threw
     * @param windowSamples retained samples inside the window; the percentiles below cover these
     * @param p50Millis nearest-rank median over the window; 0 when {@code windowSamples == 0}
     * @param p99Millis nearest-rank 99th percentile over the window; 0 when empty
     * @param maxMillis largest sample in the window; 0 when empty
     * @param maxEverMillis largest save since start, exact
     */
    public record Snapshot(String world, String dimension, String type, long saves, long failures,
                           int windowSamples, double p50Millis, double p99Millis, double maxMillis,
                           double maxEverMillis) {}

    private final LongSupplier nanoClock;
    private final long epochNanos;
    private final ConcurrentMap<Key, Recorder> recorders = new ConcurrentHashMap<>();

    /** @param nanoClock the same clock the caller's start/end times come from */
    RegionFileSaveLatency(final LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.epochNanos = nanoClock.getAsLong();
    }

    private record Key(String world, String dimension, String type) {}

    /**
     * The recorder for one region storage. Called once per {@code RegionFileStorage} instance, not
     * per save; storages that share world, dimension and type (a world reloaded) share a recorder.
     */
    public Recorder recorder(final String world, final String dimension, final String type) {
        final Key key = new Key(Objects.requireNonNull(world, "world"),
            Objects.requireNonNull(dimension, "dimension"), Objects.requireNonNull(type, "type"));
        return this.recorders.computeIfAbsent(key, k -> new Recorder(this.epochNanos));
    }

    /** Every recorder that has timed at least one save, sorted by world, dimension, type. */
    public List<Snapshot> snapshot() {
        final long nowSecond = second(this.nanoClock.getAsLong(), this.epochNanos);
        final List<Snapshot> out = new ArrayList<>();
        for (final var entry : this.recorders.entrySet()) {
            final Recorder recorder = entry.getValue();
            if (recorder.saves() == 0L) {
                continue;
            }
            out.add(recorder.snapshot(entry.getKey(), nowSecond));
        }
        out.sort(Comparator.comparing(Snapshot::world).thenComparing(Snapshot::dimension).thenComparing(Snapshot::type));
        return List.copyOf(out);
    }

    private static long second(final long nanos, final long epochNanos) {
        return Math.max(0L, nanos - epochNanos) / TimeUnit.SECONDS.toNanos(1);
    }

    /** Lock-free, allocation-free sink for one storage's save times. */
    public static final class Recorder {
        private final long epochNanos;
        private final AtomicLongArray ring = new AtomicLongArray(CAPACITY);
        private final AtomicLong sequence = new AtomicLong();
        private final LongAdder failures = new LongAdder();
        private final AtomicLong maxEverNanos = new AtomicLong();

        private Recorder(final long epochNanos) {
            this.epochNanos = epochNanos;
        }

        /**
         * One save. Both times are {@code System.nanoTime()} readings taken by the caller around the
         * save; {@code failed} is true when it threw (the exception is the caller's to propagate).
         */
        public void record(final long startedNanos, final long endedNanos, final boolean failed) {
            final long elapsed = Math.max(0L, endedNanos - startedNanos);
            final long micros = Math.min(MAX_MICROS, (elapsed + 999L) / 1000L);
            // +1 so a stored sample is never 0, which marks a slot not written yet.
            final long packed = ((second(endedNanos, this.epochNanos) + 1L) << 32) | micros;
            this.ring.set((int) (this.sequence.getAndIncrement() & (CAPACITY - 1)), packed);
            long max;
            while (elapsed > (max = this.maxEverNanos.get()) && !this.maxEverNanos.compareAndSet(max, elapsed)) {
                // another writer raised the maximum; re-check against it
            }
            if (failed) {
                this.failures.increment();
            }
        }

        long saves() {
            return this.sequence.get();
        }

        private Snapshot snapshot(final Key key, final long nowSecond) {
            final long saves = this.sequence.get();
            final int retained = (int) Math.min(saves, CAPACITY);
            final long oldestSecond = nowSecond - WINDOW_SECONDS;
            final long[] micros = new long[retained];
            int n = 0;
            for (int i = 0; i < retained; i++) {
                final long packed = this.ring.get(i);
                if (packed == 0L) {
                    continue; // claimed, not yet stored
                }
                if ((packed >>> 32) - 1L < oldestSecond) {
                    continue; // outside the window
                }
                micros[n++] = packed & MAX_MICROS;
            }
            final long[] window = Arrays.copyOf(micros, n);
            Arrays.sort(window);
            return new Snapshot(key.world(), key.dimension(), key.type(), saves, this.failures.sum(), n,
                at(window, 0.50), at(window, 0.99), n == 0 ? 0.0 : window[n - 1] / 1000.0,
                this.maxEverNanos.get() / 1_000_000.0);
        }

        /** Nearest-rank percentile in milliseconds; 0 for no samples. */
        private static double at(final long[] sortedMicros, final double q) {
            if (sortedMicros.length == 0) {
                return 0.0;
            }
            final int rank = (int) Math.ceil(q * sortedMicros.length);
            return sortedMicros[Math.max(0, Math.min(sortedMicros.length - 1, rank - 1))] / 1000.0;
        }
    }
}
