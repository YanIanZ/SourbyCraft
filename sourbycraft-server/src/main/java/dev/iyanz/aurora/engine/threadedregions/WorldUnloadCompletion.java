package dev.iyanz.aurora.engine.threadedregions;

import dev.iyanz.aurora.engine.util.ticket.UnloadTicket;
import io.canvasmc.canvas.WorldUnloadResult;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Runs a world unload (the engine's {@code WorldShutdownThread}, MC feature patch 0030) so that
 * its ticket's callback is always invoked: {@link WorldUnloadResult#SUCCESS} when the unload
 * returns, {@link WorldUnloadResult#FAIL_UNKNOWN} when it throws. A callback never
 * invoked left the unload's future incomplete forever, and with it the world operation that waited
 * on it (AuroraWorlds keeps the world locked until then).
 */
public final class WorldUnloadCompletion {
    // Engine code, beside WorldShutdownThread: the feature patch calls it unqualified from the same
    // package, so the patch adds no engine or Canvas symbol (scripts/independence_policy.py).

    private WorldUnloadCompletion() {}

    /**
     * @param ticket the world's unload ticket, read when the outcome is known
     * @param unload the unload itself, which must not invoke the callback
     */
    public static void run(final Supplier<@Nullable UnloadTicket> ticket, final Runnable unload) {
        try {
            unload.run();
        } catch (final Throwable failed) {
            final UnloadTicket current = ticket.get();
            if (current != null) {
                try {
                    current.callback().accept(WorldUnloadResult.FAIL_UNKNOWN);
                } catch (final Throwable callbackFailed) {
                    failed.addSuppressed(callbackFailed);
                }
            }
            throw failed;
        }
        Objects.requireNonNull(ticket.get(), "not propagated").callback().accept(WorldUnloadResult.SUCCESS);
    }
}
