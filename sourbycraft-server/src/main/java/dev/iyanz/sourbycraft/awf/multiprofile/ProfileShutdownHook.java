package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * The single point where server shutdown hands MultiProfile the online players before the server saves and
 * removes them (PM-076).
 *
 * <p>Order at stop (region-threaded server): the stop request halts the region scheduler and starts the region
 * shutdown thread; that thread waits for the scheduler to halt, disables spark, then (plugins still enabled, their
 * state adapters registered, every player online) calls {@link #runBeforePluginsDisabled}, then disables plugins,
 * waits for async tasks and calls {@code SourbyCraftBootstrap.close()}. {@code close()} shuts SourbyMod mods down, closes every online
 * player's open container (as a quit does before its save; the carried item is dropped into the world), then calls
 * {@link #runBeforePlayersSaved} on the shutdown thread, and only after it returns starts the AWF shutdown deadline
 * and closes the services, MultiProfile among them (its queued I/O is drained there). The server sends the
 * disconnects, saves the worlds and writes vanilla playerdata afterwards.</p>
 *
 * <p>Handler contract: it runs on the region shutdown thread, after the region scheduler halted. No region, entity
 * or global tick runs any more, so a task scheduled on an entity or region scheduler never runs; the shutdown thread
 * passes every tick-thread check and is the only thread touching world state (before plugins are disabled, plugin
 * async tasks may still be running), so the handler captures each online player directly on it. It may block that thread waiting for its storage writes, but must return within the
 * millisecond budget it is given; a player not saved by then is left to recovery (pending intent or last committed
 * snapshot). For {@link #runBeforePlayersSaved} plugins are already disabled, so their state adapters are no longer
 * registered: adapter state is captured earlier by the {@link #runBeforePluginsDisabled} handler, under the same
 * thread rules, for the final capture to merge. The storage lane,
 * {@code VirtualExecutor} and the Redis fabric are still open while it runs.</p>
 *
 * <p>Nothing registered (MultiProfile disabled): both run methods do nothing and close() does not
 * close containers early, which is the Build 52 order exactly.</p>
 */
public final class ProfileShutdownHook {

    /** Budget used when the caller passes none (0 or less): the default of {@code shutdown.capture-deadline-ms}. */
    public static final long DEFAULT_DEADLINE_MILLIS = 10_000L;

    private static final AtomicReference<LongConsumer> HANDLER = new AtomicReference<>();
    private static final AtomicReference<LongConsumer> ADAPTER_HANDLER = new AtomicReference<>();
    /** The server's "close every online player's container" pass (feature patch 0081 registers it from the shutdown thread). */
    private static final AtomicReference<Runnable> INVENTORY_CLOSER = new AtomicReference<>();

    private ProfileShutdownHook() {}

    /**
     * Installs the shutdown capture, replacing any previous one. The handler receives a budget in milliseconds,
     * measured from its call, and must return within it (see the class contract).
     */
    public static void register(final LongConsumer captureAllWithDeadlineMillis) {
        HANDLER.set(Objects.requireNonNull(captureAllWithDeadlineMillis, "captureAllWithDeadlineMillis"));
    }

    /** Removes {@code handler} if it is still the installed one; {@code false} when another one replaced it. */
    public static boolean unregister(final LongConsumer handler) {
        return handler != null && HANDLER.compareAndSet(handler, null);
    }

    /** Whether a shutdown capture is installed. */
    public static boolean registered() {
        return HANDLER.get() != null;
    }

    /**
     * Installs the adapter capture that runs before plugins are disabled, replacing any previous one. Same budget
     * rule as {@link #register}.
     */
    public static void registerAdapterCapture(final LongConsumer captureAdaptersWithDeadlineMillis) {
        ADAPTER_HANDLER.set(Objects.requireNonNull(captureAdaptersWithDeadlineMillis, "captureAdaptersWithDeadlineMillis"));
    }

    /** Removes {@code handler} if it is still the installed adapter capture; {@code false} when another replaced it. */
    public static boolean unregisterAdapterCapture(final LongConsumer handler) {
        return handler != null && ADAPTER_HANDLER.compareAndSet(handler, null);
    }

    /** Whether an adapter capture is installed. */
    public static boolean adapterCaptureRegistered() {
        return ADAPTER_HANDLER.get() != null;
    }

    /**
     * Registers the pass that closes every online player's open container and drops the carried item, as the server's
     * own per-world shutdown pass does later; feature patch 0081 registers it from the region shutdown thread so no
     * SourbyCraft source names that thread. Replaces a previous registration.
     */
    public static void registerInventoryCloser(final Runnable closeInventories) {
        INVENTORY_CLOSER.set(Objects.requireNonNull(closeInventories, "closeInventories"));
    }

    /**
     * Runs the registered inventory closer once, on the calling (shutdown) thread, before the shutdown capture reads the
     * players; {@code false} when none is registered. A failure is logged and does not stop the capture.
     */
    public static boolean closeInventoriesBeforeCapture() {
        final Runnable closer = INVENTORY_CLOSER.getAndSet(null);
        if (closer == null) return false;
        try {
            closer.run();
            return true;
        } catch (final Exception failed) {
            SourbyLogger.warn("AWF MultiProfile: closing player inventories before the shutdown capture failed (" + failed
                + "); the capture reads the inventories as they are");
            return false;
        }
    }

    /**
     * Runs the installed adapter capture once, on the calling thread (the region shutdown thread, plugins still
     * enabled and their state adapters registered, every player online), with {@code deadlineMillis} as its budget.
     * Same rules as {@link #runBeforePlayersSaved}: removed before it runs, a throw is logged, an overrun is logged,
     * nothing registered does nothing.
     *
     * @return whether a handler ran and returned normally
     */
    public static boolean runBeforePluginsDisabled(final long deadlineMillis) {
        return runOnce(ADAPTER_HANDLER, deadlineMillis, "the shutdown adapter capture");
    }

    /**
     * Runs the installed capture once, on the calling thread, with {@code deadlineMillis} as its budget (a value of
     * 0 or less means {@link #DEFAULT_DEADLINE_MILLIS}). The handler is removed first, so a second call does
     * nothing. A handler that throws is logged and shutdown continues; one that overruns its budget is logged.
     *
     * @return whether a handler ran and returned normally
     */
    public static boolean runBeforePlayersSaved(final long deadlineMillis) {
        return runOnce(HANDLER, deadlineMillis, "the shutdown capture");
    }

    private static boolean runOnce(final AtomicReference<LongConsumer> slot, final long deadlineMillis, final String what) {
        final LongConsumer handler = slot.getAndSet(null);
        if (handler == null) return false;
        final long budget = deadlineMillis > 0L ? deadlineMillis : DEFAULT_DEADLINE_MILLIS;
        final long started = System.nanoTime();
        try {
            handler.accept(budget);
        } catch (final Exception failed) {
            SourbyLogger.error("AWF MultiProfile: " + what + " failed (" + failed + "); players it did not"
                + " save are left to recovery", failed);
            return false;
        } catch (final Error fatal) {
            // An Error (OutOfMemoryError, StackOverflowError, a linkage error) is not a save outcome to swallow: it is
            // logged with the same note and rethrown to the server's shutdown sequence (review E4).
            SourbyLogger.error("AWF MultiProfile: " + what + " hit " + fatal + "; players it did not save are left to recovery", fatal);
            throw fatal;
        }
        final long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        if (elapsedMillis > budget) {
            SourbyLogger.warn("AWF MultiProfile: " + what + " took " + elapsedMillis + " ms, past its "
                + budget + " ms budget (" + ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY + ")");
        }
        return true;
    }
}
