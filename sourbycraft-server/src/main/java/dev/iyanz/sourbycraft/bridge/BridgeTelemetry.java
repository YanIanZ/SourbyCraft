package dev.iyanz.sourbycraft.bridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-plugin Aurora Bridge counters, as listed under Telemetry in
 * {@code aurora-plugin-bridge.md}: scheduler redirects, owner handoffs, rejected operations,
 * fatal violations, quarantine, startup duration and cache state, and the last failure.
 *
 * <p>Counters are monotonic and lock-free; {@link #snapshot()} copies them into immutable records
 * for rendering. Entries exist only for plugins the bridge admitted, so the map is bounded by the
 * plugin count.</p>
 */
public final class BridgeTelemetry {

    /** One plugin's counters at a point in time. */
    public record PluginStats(String plugin, long schedulerRedirects, long ownerHandoffs,
                              long rejectedOperations, long fatalViolations, boolean quarantined,
                              String lastFailure, long enableMillis, String startupCacheState) {}

    private static final class Counters {
        final AtomicLong redirects = new AtomicLong();
        final AtomicLong handoffs = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong fatal = new AtomicLong();
        final AtomicBoolean quarantined = new AtomicBoolean();
        final AtomicReference<String> lastFailure = new AtomicReference<>();
        final AtomicLong enableMillis = new AtomicLong(-1L);
        final AtomicReference<String> cacheState = new AtomicReference<>("unknown");
    }

    private final Map<String, Counters> plugins = new ConcurrentHashMap<>();

    private Counters of(final String plugin) {
        return this.plugins.computeIfAbsent(plugin, ignored -> new Counters());
    }

    void register(final String plugin) { of(plugin); }
    void redirect(final String plugin) { of(plugin).redirects.incrementAndGet(); }
    void handoff(final String plugin) { of(plugin).handoffs.incrementAndGet(); }
    void rejected(final String plugin) { of(plugin).rejected.incrementAndGet(); }
    void failure(final String plugin, final String description) { of(plugin).lastFailure.set(description); }
    void enableMillis(final String plugin, final long millis) { of(plugin).enableMillis.set(millis); }
    void cacheState(final String plugin, final String state) { of(plugin).cacheState.set(state); }

    /** Records a fatal violation and returns the new total. */
    long fatal(final String plugin, final String description) {
        final Counters c = of(plugin);
        c.lastFailure.set(description);
        return c.fatal.incrementAndGet();
    }

    /** Marks the plugin quarantined; returns true only for the call that changed it. */
    boolean quarantine(final String plugin) {
        return of(plugin).quarantined.compareAndSet(false, true);
    }

    boolean quarantined(final String plugin) {
        final Counters c = this.plugins.get(plugin);
        return c != null && c.quarantined.get();
    }

    boolean known(final String plugin) {
        return this.plugins.containsKey(plugin);
    }

    public PluginStats stats(final String plugin) {
        final Counters c = this.plugins.get(plugin);
        if (c == null) return null;
        return new PluginStats(plugin, c.redirects.get(), c.handoffs.get(), c.rejected.get(), c.fatal.get(),
            c.quarantined.get(), c.lastFailure.get(), c.enableMillis.get(), c.cacheState.get());
    }

    public List<PluginStats> snapshot() {
        return this.plugins.keySet().stream().sorted().map(this::stats).toList();
    }
}
