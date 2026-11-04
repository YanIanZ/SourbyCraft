package dev.iyanz.sourbycraft.guards;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A rate limit for a repeated warning line: one line per key per window, with the number of lines
 * suppressed since the previous one, in bounded memory.
 *
 * <p>Used for region-ownership diagnostics ({@link ThreadChecks}, the Aurora Bridge's runtime and
 * {@code GuardViolations}), which fire on hot paths and, with relaxed checks, once per access. Callers key a
 * line by what it is about (a reason without its per-hit position, see {@link #baseReason}), never by
 * the full diagnostic text, or every position would get a window of its own.</p>
 *
 * <p>At most {@code maxKeys} windows are kept. When a new key arrives at the cap, windows whose last
 * line is older than the window length are dropped first (at most one such sweep per window length);
 * if the cap is still reached, the new key shares one overflow window with every other key past the
 * cap, so those lines are logged at most once per window together rather than once per hit. A dropped
 * window loses its suppressed count. Thread-safe: a known key only takes its own window's monitor; a
 * new key also takes the table's monitor while it is inserted.</p>
 */
public final class LogWindows {

    /** The text TickThread's diagnostics put between the reason and the hit's thread, world and position. */
    private static final String CONTEXT_MARKER = ", context=";

    /** Last logged time and lines suppressed since, for one key (or the overflow). */
    private static final class Window {
        long lastNanos;
        long suppressed;
        boolean logged;
    }

    private final long windowNanos;
    private final int maxKeys;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Window overflow = new Window();
    /** When expired windows were last dropped, and whether they ever were; guarded by {@link #windows}' monitor. */
    private long lastSweepNanos;
    private boolean swept;

    /**
     * @param windowNanos how long one line suppresses the next line with the same key
     * @param maxKeys     windows kept before new keys share the overflow window; at least 1
     */
    public LogWindows(final long windowNanos, final int maxKeys) {
        if (windowNanos <= 0L || maxKeys < 1) {
            throw new IllegalArgumentException("window and key limit must be positive");
        }
        this.windowNanos = windowNanos;
        this.maxKeys = maxKeys;
    }

    /**
     * The reason of a failed ownership check without its per-hit context: TickThread's diagnostics read
     * {@code "Thread failed main thread check: <reason>, context=<thread>, world=..., block_pos=..."}, and
     * only the part before {@code ", context="} names what was checked. Other reasons are returned unchanged.
     */
    public static String baseReason(final String reason) {
        if (reason == null) return "unknown";
        final int context = reason.indexOf(CONTEXT_MARKER);
        return context < 0 ? reason : reason.substring(0, context);
    }

    /**
     * Whether a line for {@code key} may be logged at {@code nowNanos}.
     *
     * @return {@code -1} to suppress it, otherwise how many lines this key's window suppressed before it
     */
    public long admit(final String key, final long nowNanos) {
        final Window window = window(key, nowNanos);
        synchronized (window) {
            if (window.logged && nowNanos - window.lastNanos < this.windowNanos) {
                window.suppressed++;
                return -1L;
            }
            final long suppressed = window.suppressed;
            window.logged = true;
            window.lastNanos = nowNanos;
            window.suppressed = 0L;
            return suppressed;
        }
    }

    /** {@code " (N more suppressed in the last S s)"} for a positive count from {@link #admit}, else empty. */
    public String suppressedNote(final long suppressed) {
        return suppressed > 0 ? " (" + suppressed + " more suppressed in the last "
            + TimeUnit.NANOSECONDS.toSeconds(this.windowNanos) + " s)" : "";
    }

    /** Forgets every window (test seams only). */
    public void clear() {
        synchronized (this.windows) {
            this.windows.clear();
            this.swept = false;
        }
        synchronized (this.overflow) {
            this.overflow.logged = false;
            this.overflow.suppressed = 0L;
        }
    }

    private Window window(final String key, final long nowNanos) {
        final Window known = this.windows.get(key);
        if (known != null) return known;
        synchronized (this.windows) {
            final Window raced = this.windows.get(key);
            if (raced != null) return raced;
            if (this.windows.size() >= this.maxKeys) dropExpired(nowNanos);
            if (this.windows.size() >= this.maxKeys) return this.overflow;
            final Window created = new Window();
            this.windows.put(key, created);
            return created;
        }
    }

    /** Under {@link #windows}' monitor. A window another thread still holds may be dropped; it then logs once more. */
    private void dropExpired(final long nowNanos) {
        if (this.swept && nowNanos - this.lastSweepNanos < this.windowNanos) return;
        this.swept = true;
        this.lastSweepNanos = nowNanos;
        this.windows.values().removeIf(window -> {
            synchronized (window) {
                return window.logged && nowNanos - window.lastNanos >= this.windowNanos;
            }
        });
    }
}
