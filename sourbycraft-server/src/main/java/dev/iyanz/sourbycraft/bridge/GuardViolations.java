package dev.iyanz.sourbycraft.bridge;

/**
 * Where the engine's tick guard reports a cross-region access it lets through.
 *
 * <p>With {@code region-scheduler.guard-severity: LOG} (the default) the guard logs a
 * cross-region access and lets it proceed instead of throwing, so the access never surfaces as an
 * exception the Aurora Bridge could classify. The guard calls {@link #report} first: when a bridged
 * plugin's task body is running on this thread the hit is recorded as that plugin's violation
 * through the bridge's quarantine path, and the bridge logs it rate-limited per (plugin, reason).
 * Anywhere else -- a native plugin, the server itself -- nothing is recorded and the guard keeps
 * its own log line, unchanged.</p>
 *
 * <p>Attribution is by thread: a hit inside a bridged body is charged to that body's plugin, also
 * when the access was made by something it called (an event listener of another plugin, say).
 * The access itself is not refused; LOG mode decides that, not the bridge.</p>
 */
public final class GuardViolations {

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
}
