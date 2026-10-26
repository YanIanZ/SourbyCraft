package dev.iyanz.sourbycraft.bridge;

/**
 * Where the engine's tick guard reports a cross-region access it lets through.
 *
 * <p>With {@code region-scheduler.guard-severity: LOG} (the default) the guard logs a
 * cross-region access and lets it proceed instead of throwing, so the access never surfaces as an
 * exception the Aurora Bridge could classify. The guard calls {@link #report} first: when a bridged
 * plugin's task body is running on this thread the hit is recorded as that plugin's violation
 * through the bridge's quarantine path, and the bridge logs it rate-limited per (plugin, reason).
 * Anywhere else -- a native plugin, the server itself -- nothing is recorded and the guard logs
 * its own line; {@link #admitEngineLog} is the rate limit meant for that line. {@code TickGuard} calls
 * {@link #report} and {@link #admitEngineLog} only on a tick thread; a hit off a tick thread is decided by
 * {@code ThreadChecks} before either is reached.</p>
 *
 * <p>Attribution is by thread: a hit inside a bridged body is charged to that body's plugin, also
 * when the access was made by something it called (an event listener of another plugin, say).
 * The access itself is not refused; LOG mode decides that, not the bridge.</p>
 */
public final class GuardViolations {

    /** How long one engine guard line suppresses the next with the same key. */
    static final long ENGINE_LOG_WINDOW_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60L);
    /** (reason, plugin) keys kept for the engine guard line; past this, new keys share one window. */
    static final int MAX_ENGINE_LOG_KEYS = 256;

    private static final dev.iyanz.sourbycraft.guards.LogWindows ENGINE_LOG =
        new dev.iyanz.sourbycraft.guards.LogWindows(ENGINE_LOG_WINDOW_NANOS, MAX_ENGINE_LOG_KEYS);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private GuardViolations() {}

    /**
     * @param reason the guard's reason text
     * @return whether a bridged body was running on this thread and recorded the hit; when
     *     {@code false} the caller logs as it always has
     */
    public static boolean report(final String reason) {
        try {
            return BridgeRuntime.reportGuardViolation(reason);
        } catch (final RuntimeException unexpected) {
            // The guard runs inside block placement, entity tracking and the like: never throw there.
            return false;
        }
    }

    /**
     * For the guard's own LOG-mode line, after {@link #report} returned {@code false}: whether to log this hit now.
     * One line per (reason, innermost plugin on the stack, or the server when there is none) per 60 s, so a
     * plugin reading a neighbouring region in a loop logs once a minute, with a count, instead of one stack trace
     * per access; another plugin hitting the same guard keeps its own line. The key is found by walking the stack
     * to the first frame loaded by a plugin class loader, on every hit, suppressed or not (its cost has not been
     * measured). Never throws: on an unexpected failure the line is logged.
     *
     * @return {@code -1} to suppress the line, otherwise how many hits with that key were suppressed before it
     */
    public static long admitEngineLog(final String reason) {
        try {
            return ENGINE_LOG.admit((reason == null ? "unknown" : reason) + '|' + callerPlugin(), System.nanoTime());
        } catch (final RuntimeException unexpected) {
            return 0L;
        }
    }

    /** {@code " (N more suppressed in the last 60 s)"} for a positive count from {@link #admitEngineLog}, else empty. */
    public static String suppressedNote(final long suppressed) {
        return ENGINE_LOG.suppressedNote(suppressed);
    }

    /** The innermost plugin on the calling thread's stack, or {@code "server"}. */
    private static String callerPlugin() {
        return WALKER.walk(frames -> frames
            .map(frame -> frame.getDeclaringClass().getClassLoader())
            .filter(loader -> loader instanceof io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader)
            .map(loader -> ((io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader)loader).getConfiguration())
            .map(configuration -> configuration == null ? "unknown plugin" : configuration.getName())
            .findFirst()
            .orElse("server"));
    }
}
