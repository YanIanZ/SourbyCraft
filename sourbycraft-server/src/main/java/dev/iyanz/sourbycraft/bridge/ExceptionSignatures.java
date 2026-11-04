package dev.iyanz.sourbycraft.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Dedupe and rate limit for "Bridged plugin X generated an exception while executing task N".
 *
 * <p>A repeating bridged task that throws every run used to log one full stack trace per run (WuretaMC Hub,
 * Build 56, 2026-11-04: 646 lines of the same Shopkeepers {@code NullPointerException} in one log). Here an
 * exception is keyed by its <em>signature</em> -- plugin, class of the task body, exception class and the
 * top stack frame -- and:</p>
 * <ul>
 *   <li>the first occurrence of a signature is logged in full ({@link Decision.Kind#FULL}, with the stack trace);</li>
 *   <li>further occurrences inside the window (60 s) are counted and not logged ({@link Decision.Kind#SUPPRESSED});</li>
 *   <li>the first occurrence after the window elapsed is logged as one summary line without a stack trace
 *       ({@link Decision.Kind#SUMMARY}: "repeated N times in the last 60 s"), which opens the next window.</li>
 * </ul>
 * <p>So one signature logs at most one line per minute after its first full trace. A signature that stops
 * throwing logs no closing summary; its total stays visible in {@code /plugins <name>} and {@code /perf plugins}
 * through {@link #snapshot()}.</p>
 *
 * <p>Bounded: at most {@code maxSignatures} entries, least recently seen evicted first (an insertion-ordered
 * map in access order). An evicted signature that throws again is logged in full again, as a new one.
 * Thread-safe: every operation takes the map's monitor; the bridge calls it only on the failure path, once per
 * failed body run.</p>
 */
public final class ExceptionSignatures {

    /** How long one logged line (full or summary) suppresses the next line of the same signature. */
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    /** Signatures remembered; past this the least recently seen one is evicted. */
    static final int MAX_SIGNATURES = 256;

    /** What identifies one kind of failure; every part is a plain string so the key never retains plugin objects. */
    record Signature(String plugin, String body, String exception, String frame) {
        Signature {
            plugin = plugin == null ? "unknown" : plugin;
            body = body == null ? "unknown" : body;
            exception = exception == null ? "unknown" : exception;
            frame = frame == null ? "unknown" : frame;
        }

        /** {@code "<exception> at <frame> (body <body>)"}, for log lines and the command views. */
        String describe() {
            return this.exception + " at " + this.frame + " (body " + this.body + ")";
        }

        /** The signature of a throwable thrown by a bridged body: exception class and its top frame. */
        static Signature of(final String plugin, final String body, final Throwable thrown) {
            final StackTraceElement[] trace = thrown.getStackTrace();
            final String frame = trace == null || trace.length == 0 ? "no stack trace" : trace[0].toString();
            return new Signature(plugin, body, thrown.getClass().getName(), frame);
        }
    }

    /** What the caller should log for one occurrence. */
    record Decision(Kind kind, long repeats, long total) {
        enum Kind {
            /** First occurrence of the signature (or first after eviction): log with the stack trace. */
            FULL,
            /** First occurrence after a window elapsed: log one line with {@link #repeats}, no stack trace. */
            SUMMARY,
            /** Inside the window: counted, not logged. */
            SUPPRESSED
        }

        boolean logs() {
            return this.kind != Kind.SUPPRESSED;
        }
    }

    /** One signature's counters at a point in time, for the command views. */
    public record Entry(String plugin, String body, String exception, String frame, long total, long suppressed) {
        public String describe() {
            return new Signature(this.plugin, this.body, this.exception, this.frame).describe();
        }
    }

    private static final class Window {
        /** When the last line (full or summary) for this signature was logged. */
        long lastLoggedNanos;
        /** Occurrences since the last logged line. */
        long sinceLogged;
        /** Occurrences over the signature's lifetime in the map. */
        long total;
    }

    private final long windowNanos;
    private final int maxSignatures;
    private final Map<Signature, Window> windows;

    ExceptionSignatures() {
        this(WINDOW_NANOS, MAX_SIGNATURES);
    }

    ExceptionSignatures(final long windowNanos, final int maxSignatures) {
        if (windowNanos <= 0L || maxSignatures < 1) {
            throw new IllegalArgumentException("window and signature limit must be positive");
        }
        this.windowNanos = windowNanos;
        this.maxSignatures = maxSignatures;
        this.windows = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Signature, Window> eldest) {
                return size() > ExceptionSignatures.this.maxSignatures;
            }
        };
    }

    /**
     * Records one occurrence of {@code signature} at {@code nowNanos} and says what to log for it.
     *
     * @return the decision; {@link Decision#repeats()} is the number of occurrences since the previous logged line
     *     (including this one) and {@link Decision#total()} the signature's lifetime count
     */
    Decision record(final Signature signature, final long nowNanos) {
        synchronized (this.windows) {
            Window window = this.windows.get(signature);
            if (window == null) {
                window = new Window();
                window.lastLoggedNanos = nowNanos;
                window.total = 1L;
                this.windows.put(signature, window);
                return new Decision(Decision.Kind.FULL, 1L, 1L);
            }
            window.total++;
            window.sinceLogged++;
            if (nowNanos - window.lastLoggedNanos < this.windowNanos) {
                return new Decision(Decision.Kind.SUPPRESSED, window.sinceLogged, window.total);
            }
            final long repeats = window.sinceLogged;
            window.sinceLogged = 0L;
            window.lastLoggedNanos = nowNanos;
            return new Decision(Decision.Kind.SUMMARY, repeats, window.total);
        }
    }

    /** {@code "repeated N times in the last S s"} for a summary decision. */
    String summaryNote(final Decision decision) {
        return "repeated " + decision.repeats() + " time(s) in the last "
            + TimeUnit.NANOSECONDS.toSeconds(this.windowNanos) + " s (" + decision.total() + " in total)";
    }

    /** Signatures remembered right now, most occurrences first; empty when nothing threw. */
    List<Entry> snapshot() {
        final List<Entry> entries = new ArrayList<>();
        synchronized (this.windows) {
            for (final Map.Entry<Signature, Window> entry : this.windows.entrySet()) {
                final Signature key = entry.getKey();
                final Window window = entry.getValue();
                entries.add(new Entry(key.plugin(), key.body(), key.exception(), key.frame(), window.total, window.sinceLogged));
            }
        }
        entries.sort((a, b) -> Long.compare(b.total(), a.total()));
        return List.copyOf(entries);
    }

    /** Of {@link #snapshot()}, the entries of one plugin. */
    List<Entry> snapshot(final String plugin) {
        return snapshot().stream().filter(entry -> entry.plugin().equals(plugin)).toList();
    }

    /** Signatures remembered right now. */
    int size() {
        synchronized (this.windows) {
            return this.windows.size();
        }
    }
}
