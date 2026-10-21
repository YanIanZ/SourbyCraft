package dev.iyanz.sourbycraft.diagnostics;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Watches the global tick and every active region for a stall and has a {@link Dumper} write evidence.
 *
 * <p>A <b>stall</b> is any watched source that has completed no tick for {@code afterSeconds}. The
 * first poll that sees one writes dump 1 and one WARN line naming the file; while at least one source
 * stays stalled, a dump follows every {@code repeatSeconds}, up to {@code maxDumpsPerStall}; when no
 * source is stalled any more the stall ends (one INFO line). A stall that is still open when the JVM
 * shuts down gets one more, final dump from the shutdown hook, so a {@code SIGTERM} to a hung server
 * leaves the stacks of the moment it was told to stop. {@code SIGKILL} and the kernel OOM killer run no
 * hook: the periodic dumps already on disk are the evidence in that case.</p>
 *
 * <p>The poll runs on the recorder's own daemon thread, never on a tick thread, and the only data it
 * reads from the tick path are two volatile markers per source (see {@link Reading}). All time comes
 * from injected clocks so the state machine is testable without sleeping.</p>
 *
 * <p>While the server is stopping ({@code stopping} true) no <i>new</i> stall opens: ticks cease by
 * design and a long final save must not be reported as a hang. A stall that was already open stays open.</p>
 */
public final class StallRecorder implements AutoCloseable {

    /** Marker meaning "no tick in progress" in {@link Reading#activeTickStartNanos()}. */
    public static final long NO_ACTIVE_TICK = dev.iyanz.sourbycraft.perf.RegionTickMetrics.INACTIVE;
    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long JOIN_MILLIS = 1_500L;

    /**
     * One watched source at one instant.
     *
     * @param label what to call it in the log and the dump
     * @param global whether it is the global tick (false: a region)
     * @param lastTickEndNanos {@code System.nanoTime} at which its last tick completed, {@code 0} before the first
     * @param activeTickStartNanos {@code System.nanoTime} at which the tick in progress began, or {@link #NO_ACTIVE_TICK}
     */
    public record Reading(String label, boolean global, long lastTickEndNanos, long activeTickStartNanos) {}

    /**
     * A source that has gone too long without completing a tick.
     *
     * @param sinceLastTickNanos time since its last completed tick (the time since the tick in progress
     *     began when it has completed none yet)
     * @param activeForNanos how long the tick in progress has run, or {@code -1} when none is running
     */
    public record Stalled(String label, boolean global, long sinceLastTickNanos, long activeForNanos) {}

    /** The watched sources. Must be safe to call from any thread and must not block. */
    @FunctionalInterface
    public interface Sources {
        void read(Consumer<Reading> out);
    }

    /** Which dump of a stall a request is. */
    public enum Kind { FIRST, REPEAT, FINAL }

    /**
     * Everything the dumper is told about one dump.
     *
     * @param stallNumber 1 for the first stall since the recorder started
     * @param index 1-based dump number within the stall (the final dump continues the numbering)
     * @param maxDumps the per-stall cap
     * @param stallAgeNanos time from the first source crossing the threshold to now, at least {@code afterSeconds}
     * @param file the stall's file when an earlier dump created it, else {@code null}
     */
    public record DumpRequest(long stallNumber, int index, int maxDumps, Kind kind, List<Stalled> stalled,
                              long stallAgeNanos, Path file) {
        public DumpRequest {
            stalled = List.copyOf(stalled);
        }
    }

    /** Writes one dump and returns the file it went to. */
    @FunctionalInterface
    public interface Dumper {
        Path write(DumpRequest request) throws IOException;
    }

    /** Immutable view of the open stall, published volatile for the shutdown hook. */
    private record Open(long number, long startNanos, int dumps, Path file, List<Stalled> lastStalled) {}

    private final AuroraConfig.StallRecorder settings;
    private final long afterNanos;
    private final long repeatNanos;
    private final Sources sources;
    private final Dumper dumper;
    private final LongSupplier nanoClock;
    private final BooleanSupplier stopping;
    private final Consumer<String> warn;
    private final Consumer<String> info;

    private final Object pollLock = new Object();
    private volatile Open open;
    private long nextDumpNanos;
    private long stallCounter;
    private boolean loggedDumpFailure;
    private volatile boolean closed;
    private volatile boolean finalWritten;
    private Thread worker;
    private Thread hook;

    public StallRecorder(final AuroraConfig.StallRecorder settings, final Sources sources, final Dumper dumper,
                         final LongSupplier nanoClock, final BooleanSupplier stopping,
                         final Consumer<String> warn, final Consumer<String> info) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.dumper = Objects.requireNonNull(dumper, "dumper");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.info = Objects.requireNonNull(info, "info");
        this.afterNanos = TimeUnit.SECONDS.toNanos(settings.afterSeconds());
        this.repeatNanos = TimeUnit.SECONDS.toNanos(settings.repeatSeconds());
    }

    /** Starts the daemon thread and registers the shutdown hook. Does nothing when disabled or already started. */
    public synchronized void start() {
        if (!this.settings.enabled() || this.worker != null || this.closed) {
            return;
        }
        final Thread thread = new Thread(this::run, "SourbyCraft-StallRecorder");
        thread.setDaemon(true);
        final Thread shutdown = new Thread(this::onShutdown, "SourbyCraft-StallRecorder-Shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            this.hook = shutdown;
        } catch (final IllegalStateException shuttingDown) {
            return; // the JVM is already going down
        }
        this.worker = thread;
        thread.start();
    }

    private void run() {
        while (!this.closed) {
            try {
                this.poll();
            } catch (final Throwable failure) {
                // A recorder that dies on its first odd reading records nothing later; the cause is
                // not worth more than one line, and the next poll tries again.
                if (!this.loggedDumpFailure) {
                    this.loggedDumpFailure = true;
                    this.safe(this.warn, "stall recorder poll failed: " + failure);
                }
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    /** One watch cycle. Public for tests; production calls it from the recorder thread only. */
    public void poll() {
        final long now = this.nanoClock.getAsLong();
        final List<Stalled> stalled = this.evaluate(now);
        synchronized (this.pollLock) {
            final Open current = this.open;
            if (current == null) {
                if (stalled.isEmpty() || this.isStopping()) {
                    return;
                }
                final long age = oldest(stalled);
                final Open begun = new Open(++this.stallCounter, now - age, 0, null, stalled);
                this.open = begun;
                this.nextDumpNanos = now;
                this.dumpIfDue(begun, stalled, now);
                return;
            }
            if (stalled.isEmpty()) {
                this.open = null;
                this.safe(this.info, "Server stall #" + current.number() + " ended after "
                    + format(now - current.startNanos()) + "; " + current.dumps() + " dump(s)"
                    + (current.file() == null ? "" : " in " + current.file()));
                return;
            }
            this.dumpIfDue(current, stalled, now);
        }
    }

    private void dumpIfDue(final Open current, final List<Stalled> stalled, final long now) {
        if (current.dumps() >= this.settings.maxDumpsPerStall() || now - this.nextDumpNanos < 0L) {
            this.open = new Open(current.number(), current.startNanos(), current.dumps(), current.file(), stalled);
            return;
        }
        final int index = current.dumps() + 1;
        final DumpRequest request = new DumpRequest(current.number(), index, this.settings.maxDumpsPerStall(),
            index == 1 ? Kind.FIRST : Kind.REPEAT, stalled, now - current.startNanos(), current.file());
        Path file = current.file();
        try {
            file = this.dumper.write(request);
        } catch (final Throwable failure) {
            if (!this.loggedDumpFailure) {
                this.loggedDumpFailure = true;
                this.safe(this.warn, "stall recorder could not write its dump: " + failure);
            }
        }
        this.open = new Open(current.number(), current.startNanos(), index, file, stalled);
        this.nextDumpNanos = now + this.repeatNanos;
        if (index == 1 && file != null) {
            // After the write, so a console that blocks cannot delay the evidence.
            final Stalled first = stalled.get(0);
            this.safe(this.warn, "Server stall: " + describe(first) + ". Thread dump and memory figures written to "
                + file + " (up to " + this.settings.maxDumpsPerStall() + " dumps, one every "
                + this.settings.repeatSeconds() + " s while it lasts).");
        }
    }

    private List<Stalled> evaluate(final long now) {
        final List<Stalled> out = new ArrayList<>();
        try {
            this.sources.read(reading -> {
                final Stalled stalled = judge(reading, now, this.afterNanos);
                if (stalled != null) {
                    out.add(stalled);
                }
            });
        } catch (final Throwable engineNotReady) {
            return List.of();
        }
        // The global tick first: it is the one whose stall the operator feels.
        out.sort((a, b) -> a.global() != b.global() ? (a.global() ? -1 : 1)
            : Long.compare(b.sinceLastTickNanos(), a.sinceLastTickNanos()));
        return List.copyOf(out);
    }

    /**
     * Whether a reading is a stall. A source that has completed a tick is judged by the time since it;
     * one that never has is judged only while a tick is in progress, so the gap between plugin enable and
     * the first tick of a slow boot is not reported as a hang.
     */
    static Stalled judge(final Reading reading, final long now, final long afterNanos) {
        final long active = reading.activeTickStartNanos() == NO_ACTIVE_TICK
            ? -1L : Math.max(0L, now - reading.activeTickStartNanos());
        final long since;
        if (reading.lastTickEndNanos() != 0L) {
            since = now - reading.lastTickEndNanos();
        } else if (active >= 0L) {
            since = active;
        } else {
            return null;
        }
        return since >= afterNanos ? new Stalled(reading.label(), reading.global(), since, active) : null;
    }

    private static long oldest(final List<Stalled> stalled) {
        long max = 0L;
        for (final Stalled s : stalled) {
            max = Math.max(max, s.sinceLastTickNanos());
        }
        return max;
    }

    private boolean isStopping() {
        try {
            return this.stopping.getAsBoolean();
        } catch (final Throwable unknown) {
            return false;
        }
    }

    /** Shutdown hook body: one final dump when a stall is open. Public for tests. */
    public void onShutdown() {
        final Open current = this.open;
        if (current == null || this.finalWritten) {
            return;
        }
        this.finalWritten = true;
        try {
            this.dumper.write(new DumpRequest(current.number(), current.dumps() + 1, this.settings.maxDumpsPerStall(),
                Kind.FINAL, current.lastStalled(), this.nanoClock.getAsLong() - current.startNanos(), current.file()));
        } catch (final Throwable ignored) {
            // The JVM is going down; there is nowhere left to report this.
        }
    }

    /** True while a stall is open (a source has gone too long without a tick). */
    public boolean stallOpen() {
        return this.open != null;
    }

    /** Dumps written so far for the open stall, 0 when none is open. */
    public int dumpsInOpenStall() {
        final Open current = this.open;
        return current == null ? 0 : current.dumps();
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        final Thread thread = this.worker;
        this.worker = null;
        if (thread != null) {
            LockSupport.unpark(thread);
            try {
                thread.join(JOIN_MILLIS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        final Thread registered = this.hook;
        this.hook = null;
        // A stall still open at plugin disable keeps its hook: a hung server told to stop is the case
        // the final dump is for, and the hook is harmless otherwise (it returns at once).
        if (registered != null && this.open == null) {
            try {
                Runtime.getRuntime().removeShutdownHook(registered);
            } catch (final IllegalStateException shuttingDown) {
                // already running hooks
            }
        } else if (registered != null) {
            this.hook = registered;
        }
    }

    private void safe(final Consumer<String> sink, final String message) {
        try {
            sink.accept(message);
        } catch (final Throwable ignored) {
            // logging must never take the recorder down
        }
    }

    static String describe(final Stalled s) {
        final String what = s.global() ? "the global tick" : s.label();
        return what + " has completed no tick for " + format(s.sinceLastTickNanos())
            + (s.activeForNanos() >= 0L ? " (a tick has been running for " + format(s.activeForNanos()) + ")" : "");
    }

    static String format(final long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f s", nanos / 1e9);
    }
}
