package dev.iyanz.sourbycraft.startup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wall time of each plugin's {@code onLoad} and {@code onEnable}, recorded by Paper feature
 * patch 0013 around the two calls.
 *
 * <p>Wall time, not CPU time: it includes anything the plugin waited on (I/O, locks, network).
 * The most recent run is kept, so a plugin enabled again after a disable shows its latest
 * enable. Bounded to {@link #MAX_PLUGINS} names; past that, new names are counted as dropped
 * and never stored. Plugin names are already public via {@code /plugins}. Thread-safe.</p>
 */
public final class PluginTimings {

    public static final int MAX_PLUGINS = 1024;
    public static final PluginTimings GLOBAL = new PluginTimings(MAX_PLUGINS);

    /** Nanoseconds; {@code 0} when that phase has not been recorded. */
    public record Timing(String plugin, long loadNanos, long enableNanos) {
        public long loadMillis() {
            return loadNanos / 1_000_000L;
        }

        public long enableMillis() {
            return enableNanos / 1_000_000L;
        }

        public long totalNanos() {
            return loadNanos + enableNanos;
        }

        /** {@code "load 3.2 ms, enable 7.0 ms"}; {@code "not recorded"} when neither phase ran. */
        public String describe() {
            if (loadNanos == 0L && enableNanos == 0L) return "not recorded";
            return "load " + millis(loadNanos) + ", enable " + millis(enableNanos);
        }

        private static String millis(final long nanos) {
            return String.format(java.util.Locale.ROOT, "%.1f ms", nanos / 1_000_000.0);
        }
    }

    private final int capacity;
    private final Map<String, Timing> timings = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();

    public PluginTimings(final int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        this.capacity = capacity;
    }

    /** Paper hook: wall time of {@code onLoad}, including a call that threw. */
    public static void recordLoad(final String plugin, final long nanos) {
        GLOBAL.load(plugin, nanos);
    }

    /** Paper hook: wall time of {@code onEnable}, including a call that threw. */
    public static void recordEnable(final String plugin, final long nanos) {
        GLOBAL.enable(plugin, nanos);
    }

    public void load(final String plugin, final long nanos) {
        update(plugin, nanos, true);
    }

    public void enable(final String plugin, final long nanos) {
        update(plugin, nanos, false);
    }

    private void update(final String plugin, final long nanos, final boolean load) {
        if (plugin == null) return;
        final long value = Math.max(0L, nanos);
        if (this.timings.containsKey(plugin)) {
            merge(plugin, value, load);
            return;
        }
        // Names are never removed, so serializing first insertions keeps the bound exact.
        synchronized (this.timings) {
            if (!this.timings.containsKey(plugin) && this.timings.size() >= this.capacity) {
                this.dropped.incrementAndGet();
                return;
            }
            merge(plugin, value, load);
        }
    }

    private void merge(final String plugin, final long value, final boolean load) {
        this.timings.compute(plugin, (name, old) -> {
            final long loadNanos = load ? value : old == null ? 0L : old.loadNanos();
            final long enableNanos = load ? old == null ? 0L : old.enableNanos() : value;
            return new Timing(name, loadNanos, enableNanos);
        });
    }

    /** A plugin's timing; zeroes when it was never recorded. */
    public Timing of(final String plugin) {
        final Timing timing = plugin == null ? null : this.timings.get(plugin);
        return timing != null ? timing : new Timing(plugin, 0L, 0L);
    }

    /** The {@code limit} slowest plugins by load + enable, slowest first; ties by name. */
    public List<Timing> slowest(final int limit) {
        final List<Timing> all = new ArrayList<>(this.timings.values());
        all.sort(Comparator.comparingLong(Timing::totalNanos).reversed().thenComparing(Timing::plugin));
        return List.copyOf(all.subList(0, Math.min(Math.max(0, limit), all.size())));
    }

    public int size() {
        return this.timings.size();
    }

    /** Names refused because the registry was full. */
    public long dropped() {
        return this.dropped.get();
    }
}
