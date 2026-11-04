package dev.iyanz.aurora.engine.threadedregions;

import com.mojang.logging.LogUtils;
import dev.iyanz.aurora.engine.util.ticket.UnloadTicket;
import dev.iyanz.sourbycraft.api.world.WorldUnloadResult;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs a world unload (the engine's {@code WorldShutdownThread}, MC feature patch 0030) so that
 * its ticket's callback is always invoked: {@link WorldUnloadResult#SUCCESS} when the unload
 * returns, {@link WorldUnloadResult#FAIL_UNKNOWN} when it throws. A callback never
 * invoked left the unload's future incomplete forever, and with it the world operation that waited
 * on it (AuroraWorlds keeps the world locked until then).
 *
 * <p>A failure is rethrown, so the shutdown thread's handler stops the server, except
 * {@link WorldUnloadDrain.AwfUnloadNotConfirmed}: that unload did finish (the world is removed),
 * only an AWF storage's final commit was not confirmed, so it is logged as an ERROR and the server
 * keeps running.</p>
 */
public final class WorldUnloadCompletion {
    // Engine code, beside WorldShutdownThread: the feature patch calls it unqualified from the same
    // package, so the patch adds no engine or Canvas symbol (scripts/independence_policy.py).

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private WorldUnloadCompletion() {}

    /**
     * @param ticket the world's unload ticket, read when the outcome is known
     * @param unload the unload itself, which must not invoke the callback
     */
    public static void run(final Supplier<@Nullable UnloadTicket> ticket, final Runnable unload) {
        run(ticket, unload, () -> {});
    }

    /**
     * As {@link #run(Supplier, Runnable)}, and runs {@code release} before the callback whatever the
     * outcome: the Aurora Bridge's release of legacy tasks anchored in the world, which the unload
     * itself reaches only when it gets as far as the region drain (a failed chunk save throws first).
     *
     * @param release must not throw; it is only reported if it does
     */
    public static void run(final Supplier<@Nullable UnloadTicket> ticket, final Runnable unload, final Runnable release) {
        boolean releaseStarted = false;
        try {
            unload.run();
            releaseStarted = true;
            release.run();
        } catch (final WorldUnloadDrain.AwfUnloadNotConfirmed unconfirmed) {
            // Thrown by the unload itself once it finished, before the release.
            fail(ticket, releaseStarted ? null : release, unconfirmed);
            LOGGER.error("{}. The unload reports FAIL_UNKNOWN; the server keeps running", unconfirmed.getMessage(), unconfirmed);
            return;
        } catch (final Throwable failed) {
            fail(ticket, releaseStarted ? null : release, failed);
            throw failed;
        }
        Objects.requireNonNull(ticket.get(), "not propagated").callback().accept(WorldUnloadResult.SUCCESS);
    }

    /**
     * Runs {@code release} (unless {@code null}) and the ticket's callback with
     * {@link WorldUnloadResult#FAIL_UNKNOWN}; what either throws is attached to {@code failed}.
     */
    private static void fail(final Supplier<@Nullable UnloadTicket> ticket, final @Nullable Runnable release,
                             final Throwable failed) {
        try {
            if (release != null) release.run();
        } catch (final Throwable releaseFailed) {
            failed.addSuppressed(releaseFailed);
        }
        final UnloadTicket current = ticket.get();
        if (current != null) {
            try {
                current.callback().accept(WorldUnloadResult.FAIL_UNKNOWN);
            } catch (final Throwable callbackFailed) {
                failed.addSuppressed(callbackFailed);
            }
        }
    }
}
