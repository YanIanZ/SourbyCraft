package dev.iyanz.sourbycraft.entity.teleport;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One log line per plugin per window when a synchronous {@code Entity#teleport} had to be turned
 * into {@code teleportAsync}, so admins can see which plugins rely on sync teleport without the
 * console flooding when a plugin teleports every tick. Keys are bounded; past the cap every new
 * plugin shares one overflow key.
 */
public final class AsyncFallbackLog {

    public static final String UNKNOWN_CALLER = "<no plugin on the stack>";
    static final String OVERFLOW = "<other plugins>";

    private static final class Window {
        long lastNanos;
        long suppressed;
    }

    private final long windowNanos;
    private final int maxKeys;
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongAdder fallbacks = new LongAdder();

    public AsyncFallbackLog(final long windowNanos, final int maxKeys, final LongSupplier clock, final Consumer<String> sink) {
        if (windowNanos <= 0L || maxKeys < 1) throw new IllegalArgumentException("window and key cap must be positive");
        this.windowNanos = windowNanos;
        this.maxKeys = maxKeys;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Counts one fallback by {@code plugin} (null: no plugin frame on the stack) and logs it unless
     * the same key logged within the window.
     *
     * @return whether a line was written
     */
    public boolean record(final String plugin, final String why) {
        this.fallbacks.increment();
        final String key = plugin == null ? UNKNOWN_CALLER : plugin;
        Window window = this.windows.get(key);
        if (window == null) {
            // Admission of a new key is serialised so the cap holds under concurrent callers.
            synchronized (this.windows) {
                window = this.windows.get(key);
                if (window == null) {
                    window = this.windows.size() >= this.maxKeys
                        ? this.windows.computeIfAbsent(OVERFLOW, k -> new Window())
                        : this.windows.computeIfAbsent(key, k -> new Window());
                }
            }
        }
        final String shown = this.windows.get(key) == window ? key : OVERFLOW;
        final long now = this.clock.getAsLong();
        final long suppressed;
        synchronized (window) {
            if (window.lastNanos != 0L && now - window.lastNanos < this.windowNanos) {
                window.suppressed++;
                return false;
            }
            window.lastNanos = now == 0L ? 1L : now;
            suppressed = window.suppressed;
            window.suppressed = 0L;
        }
        this.sink.accept("Plugin " + shown + " called Entity#teleport where it cannot complete synchronously ("
            + why + "); scheduled teleportAsync with the same cause and returned false (the move completes later)."
            + " Use teleportAsync. Further fallbacks by this plugin are logged at most once per "
            + java.util.concurrent.TimeUnit.NANOSECONDS.toMinutes(this.windowNanos) + " min"
            + (suppressed > 0L ? "; " + suppressed + " more since the last line." : "."));
        return true;
    }

    /** Fallbacks counted, logged or not. */
    public long fallbacks() {
        return this.fallbacks.sum();
    }

    /** Distinct keys held (at most the cap plus the overflow key). */
    public int keys() {
        return this.windows.size();
    }
}
