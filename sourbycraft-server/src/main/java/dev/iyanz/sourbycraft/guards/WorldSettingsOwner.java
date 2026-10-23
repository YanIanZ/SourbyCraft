package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may change a world's server-wide settings through {@code CraftWorld}: time, weather, game rules, hardcore,
 * spawn limits and ticks-per-spawn (paper feature patch 0025).
 *
 * <p>The region-threading base required the global tick thread for these calls always. During startup the region
 * scheduler has not started yet ({@link RegionOwnership#regionsTicking()} is false: {@code RegionizedServer.init()}
 * runs after worlds load and plugins enable), nothing ticks, and the server thread that loads worlds and enables
 * plugins is their only owner, as on Paper. A plugin that applies per-world settings while it enables
 * (Multiverse-Core 5.8.1 loading each world's config, Sourby Demo panel 2026-10-22) was refused with "Cannot modify
 * server settings off of the global region", so those settings were never applied.
 *
 * <p>Owner, in order: the shutdown thread (owns everything once regions stop); once regions tick, only the global
 * tick thread (unchanged, {@link RegionOwnership#ensureGlobalTickThread}); before that, only the server's running
 * thread. Anything else is refused with the same {@link IllegalStateException} and message.
 */
public final class WorldSettingsOwner {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora");

    /** What the calling thread may do, for {@link #decide}. */
    enum Decision {
        /** The shutdown thread, or the startup thread before regions tick. */
        ALLOWED,
        /** Regions tick: the global tick rule decides. */
        GLOBAL_TICK_REQUIRED,
        /** Before regions tick, from a thread other than the server's running thread. */
        REFUSED
    }

    private WorldSettingsOwner() {
    }

    static Decision decide(final boolean shutdownThread, final boolean regionsTicking, final boolean startupThread) {
        if (shutdownThread) {
            return Decision.ALLOWED;
        }
        if (regionsTicking) {
            return Decision.GLOBAL_TICK_REQUIRED;
        }
        return startupThread ? Decision.ALLOWED : Decision.REFUSED;
    }

    /**
     * Throws {@link IllegalStateException} with {@code reason} unless the current thread owns server-wide world
     * settings now.
     */
    public static void ensure(final String reason) {
        final RegionOwnership ownership = RegionOwnership.current();
        final boolean ticking = ownership.regionsTicking();
        final MinecraftServer server = ticking ? null : MinecraftServer.getServer();
        final boolean startupThread = server != null && Thread.currentThread() == server.getRunningThread();
        switch (decide(ownership.isShutdownThread(), ticking, startupThread)) {
            case ALLOWED -> {
            }
            case GLOBAL_TICK_REQUIRED -> ownership.ensureGlobalTickThread(reason);
            case REFUSED -> {
                LOGGER.error("Thread failed startup thread check: {}, thread={}", reason, Thread.currentThread().getName(), new Throwable());
                throw new IllegalStateException(reason);
            }
        }
    }
}
