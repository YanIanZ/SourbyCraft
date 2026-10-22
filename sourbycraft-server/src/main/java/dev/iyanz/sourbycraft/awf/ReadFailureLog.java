package dev.iyanz.sourbycraft.awf;

import java.util.function.LongSupplier;

/**
 * Rate-limited log of one storage's failed reads.
 *
 * <p>A chunk load makes the chunk system read every chunk within the ticket's radius: one
 * {@code forceload add} reads a 27x27 square, a player joining far more. While the backend is
 * down every one of those reads fails. On the harness (docs/TODO.md:58, server-w1-redis-1) that was 731
 * ERROR lines in one second from AWF alone. So per storage:</p>
 * <ul>
 *   <li>the first failure of a window is logged at once with its stack trace (never silenced);</li>
 *   <li>the rest of the window is only counted;</li>
 *   <li>when the window has ended (on the next failure, {@link #flush} or {@link #close}) one
 *       summary says how many reads failed and repeats the first message.</li>
 * </ul>
 * Only the log is limited. Every failure is still fenced and counted by {@link AwfRegionStorage};
 * nothing here decides what is refused.
 *
 * <p>The same windowing serves two other per-storage records that a backend outage repeats
 * (Build 49 gate "Backend failure", agent test server 2026-10-22): {@link #recoveryLog} for the
 * INFO line of each fenced position read back after the backend returned (529 lines in one second
 * when a 27x27 square loaded again), and {@link #commitFailureLog} for the WARN, with its stack, of
 * each failed periodic commit (one per commit interval per storage while the backend is down: 603
 * of 749 lines in a 75 s outage at a 5 s interval). Logging only: what is fenced, refused, retried
 * or committed does not change.</p>
 */
final class ReadFailureLog {

    /** How long one window lasts. */
    static final long WINDOW_NANOS = 10_000_000_000L;

    /** Where records go: {@code stack} is the throwable to print with its stack trace, or null. */
    @FunctionalInterface
    interface Sink {
        void log(String message, Throwable stack);
    }

    /** The server log, at ERROR. */
    static final Sink SERVER_LOG = (message, stack) -> {
        if (stack == null) dev.iyanz.sourbycraft.util.SourbyLogger.error(message);
        else dev.iyanz.sourbycraft.util.SourbyLogger.error(message, stack);
    };

    /** The text of a window's summary; {@code count} includes the window's first record. */
    @FunctionalInterface
    interface Summary {
        String text(String storage, long count, long seconds, String first);
    }

    /** The failed-read summary (the original record of this class). */
    static final Summary READ_FAILURES = (storage, count, seconds, first) -> "AWF reads of " + storage + " failed "
        + count + " time(s) in the last " + seconds + " s; only the first was logged with its stack. Every failed"
        + " position is fenced (its stored chunk is kept, writes of it are refused until it is read back). first"
        + " error: " + first;

    /** How long one window of {@link #commitFailureLog} lasts: several commit intervals. */
    static final long COMMIT_WINDOW_NANOS = 60_000_000_000L;

    private final String storage;
    private final long windowNanos;
    private final LongSupplier clock;
    private final Sink sink;
    private final Summary summary;

    private boolean open;
    private long windowStart;
    private String firstMessage;
    /** Failures in the open window after its first one. */
    private long repeats;
    /** Failures not logged on their own since this log was created. */
    private long suppressed;

    ReadFailureLog(final String storage, final long windowNanos, final LongSupplier clock, final Sink sink) {
        this(storage, windowNanos, clock, sink, READ_FAILURES);
    }

    ReadFailureLog(final String storage, final long windowNanos, final LongSupplier clock, final Sink sink,
                   final Summary summary) {
        this.storage = storage;
        this.windowNanos = windowNanos;
        this.clock = clock;
        this.sink = sink;
        this.summary = summary;
    }

    static ReadFailureLog serverLog(final String storage) {
        return new ReadFailureLog(storage, WINDOW_NANOS, System::nanoTime, SERVER_LOG);
    }

    /** The recovery summary: fenced positions read back on the chunk-load path. */
    static final Summary RECOVERIES = (storage, count, seconds, first) -> "AWF read " + count + " fenced chunk"
        + " position(s) of " + storage + " again in the last " + seconds + " s; their stored bytes were loaded and"
        + " writes of them are accepted again (only the first was logged on its own: " + first + ")";

    /** The periodic-commit failure summary. */
    static final Summary COMMIT_FAILURES = (storage, count, seconds, first) -> "AWF commits for " + storage
        + " failed " + count + " time(s) in the last " + seconds + " s; only the first was logged with its stack."
        + " The chunks stay in memory and every commit retries them. first error: " + first;

    /** Recovered fenced positions, at INFO: the first of a window on its own line, the rest in one summary. */
    static ReadFailureLog recoveryLog(final String storage) {
        return new ReadFailureLog(storage, WINDOW_NANOS, System::nanoTime,
            (message, stack) -> dev.iyanz.sourbycraft.util.SourbyLogger.info(message), RECOVERIES);
    }

    /** Failed periodic commits, at WARN: the first of a 60 s window with its stack, the rest in one summary. */
    static ReadFailureLog commitFailureLog(final String storage) {
        return new ReadFailureLog(storage, COMMIT_WINDOW_NANOS, System::nanoTime, (message, stack) -> {
            if (stack == null) dev.iyanz.sourbycraft.util.SourbyLogger.warn(message);
            else dev.iyanz.sourbycraft.util.SourbyLogger.warn(message, stack);
        }, COMMIT_FAILURES);
    }

    /**
     * Records one failed read. Returns whether it is the first of its window, which was logged with
     * {@code cause}'s stack; any other failure is only counted.
     */
    synchronized boolean failed(final String message, final Throwable cause) {
        final long now = this.clock.getAsLong();
        if (this.open && now - this.windowStart >= this.windowNanos) closeWindow(now);
        if (!this.open) {
            this.open = true;
            this.windowStart = now;
            this.firstMessage = message;
            this.repeats = 0;
            this.sink.log(message, cause);
            return true;
        }
        this.repeats++;
        this.suppressed++;
        return false;
    }

    /** Writes the summary of a window that has ended; called periodically. */
    synchronized void flush() {
        final long now = this.clock.getAsLong();
        if (this.open && now - this.windowStart >= this.windowNanos) closeWindow(now);
    }

    /** Writes the summary of the open window now (the storage is closing). */
    synchronized void close() {
        if (this.open) closeWindow(this.clock.getAsLong());
    }

    /** Failures that were counted but not logged on their own. */
    synchronized long suppressed() {
        return this.suppressed;
    }

    private void closeWindow(final long now) {
        this.open = false;
        if (this.repeats == 0) return;
        final long seconds = Math.max(1L, (Math.max(now - this.windowStart, 0L) + 999_999_999L) / 1_000_000_000L);
        this.sink.log(this.summary.text(this.storage, this.repeats + 1, seconds, this.firstMessage), null);
    }
}
