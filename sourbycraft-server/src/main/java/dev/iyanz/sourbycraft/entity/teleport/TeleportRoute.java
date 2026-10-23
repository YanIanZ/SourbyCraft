package dev.iyanz.sourbycraft.entity.teleport;

import java.util.Locale;

/**
 * Where a synchronous Bukkit {@code Entity#teleport} goes under region threading. SourbyCraft owns
 * this decision; it replaces the inherited Canvas/Folia refusal
 * ({@code UnsupportedOperationException("Must use teleportAsync while in region threading")}), see
 * {@code docs/architecture/inherited-guards.md} row 2 ("Owned teleport and spawn-location event").
 *
 * <ul>
 *   <li>{@link Route#SYNC}: the calling thread owns the entity and the destination (same world,
 *       destination owned by this region, its chunks loaded). Paper's synchronous teleport runs:
 *       Bukkit teleport events, passenger rules, and {@code true} only after the entity moved.</li>
 *   <li>{@link Route#ASYNC}: anything else. {@code teleportAsync} is scheduled with the same cause
 *       and the call returns {@code false}: "not completed now", the move happens later on the
 *       owning region (or the future reports {@code false} if it cannot).</li>
 *   <li>{@link Route#REFUSE}: the switch is off ({@code -Daurora.compat.sync-teleport=false}); the
 *       inherited refusal stays.</li>
 * </ul>
 *
 * <p>The question order is part of the contract (pinned by {@code TeleportRouteTest}): the
 * destination's chunk state is read only after the caller is known to own the entity and the
 * destination, so a foreign region's state is never read from the wrong thread.</p>
 */
public final class TeleportRoute {

    /** JVM property; read once at class init (RESTART_REQUIRED). */
    public static final String PROPERTY = "aurora.compat.sync-teleport";

    /** {@code true} unless {@code -Daurora.compat.sync-teleport=false}. */
    public static final boolean ENABLED = enabledFrom(System.getProperty(PROPERTY));

    public enum Route { SYNC, ASYNC, REFUSE }

    /** The questions, asked lazily and in the order of {@link #decide}. */
    public interface Probe {
        /** The current thread owns the entity (and so its passengers and vehicle). */
        boolean callerOwnsEntity();
        /** The destination is in the entity's current world. */
        boolean sameWorld();
        /** The current thread owns the entity's box at the destination (plus a one-chunk margin when it carries passengers). */
        boolean callerOwnsDestination();
        /** The destination's chunks are loaded for the entity's bounding box. Only asked when owned. */
        boolean destinationLoaded();
    }

    private TeleportRoute() {}

    public static Route decide(final boolean enabled, final Probe probe) {
        if (!enabled) {
            return Route.REFUSE;
        }
        if (probe.callerOwnsEntity() && probe.sameWorld() && probe.callerOwnsDestination() && probe.destinationLoaded()) {
            return Route.SYNC;
        }
        return Route.ASYNC;
    }

    /**
     * Relaxed thread checks ({@code aurora.bridge.thread-checks = relaxed}, owner decision 2026-10-22): whether an
     * {@link Route#ASYNC} teleport waits for its result the way LightingLuminol's {@code CraftPlayer#teleport0}
     * does ({@code teleportAsync(location, cause).join()}, paper patch 0002 at revision 72d51c4d). The move itself
     * stays {@code teleportAsync} -- a correct region transfer -- only the caller blocks until it completes.
     *
     * <p>Deviation from LightingLuminol, to avoid Folia#280: a tick thread only waits when it owns the entity and
     * the destination is loaded in a region it does not own (so its completion needs another thread, never this
     * one). A tick thread whose destination is unloaded (the chunk's region may be created and merged into this
     * one, whose tick is blocked) schedules and returns {@code false} as before. Threads that tick nothing
     * (async plugin threads) always wait; the global tick thread waits too, as in LightingLuminol, which stalls
     * the global tick for the duration.</p>
     *
     * @param probe the same probe as {@link #decide}; only asked once the caller owns the entity
     * @param tickThread whether the calling thread ticks regions (a region or the global tick thread)
     */
    public static boolean joinsWhenRelaxed(final Probe probe, final boolean tickThread) {
        if (!tickThread) {
            return true;
        }
        return probe.callerOwnsEntity() && !probe.callerOwnsDestination() && probe.destinationLoaded();
    }

    /**
     * Paper's player-only precondition (no connection: {@code false}), asked only when the caller
     * owns the player; off its region nothing of the player is read and the route schedules.
     */
    public static boolean playerMayProceed(final boolean callerOwnsPlayer, final java.util.function.BooleanSupplier hasConnection) {
        return !callerOwnsPlayer || hasConnection.getAsBoolean();
    }

    /** What a synchronous teleport does once the Bukkit teleport event has run. */
    public enum AfterEvents {
        /** Destination still this region's, loaded, same world: move now. */
        CONTINUE,
        /** Destination moved off the region by a listener: finish with {@code teleportAsync}. */
        REROUTE,
        /** The teleport is obsolete (entity removed, left the region, or another transfer started), or a passenger cannot follow: do nothing. */
        ABORT
    }

    /** Post-event questions, asked lazily in the order of {@link #afterEvents}. */
    public interface AfterEventsProbe {
        boolean removed();
        boolean callerOwnsEntity();
        /** A listener started a region transfer (e.g. its own {@code teleportAsync}) of this entity. */
        boolean inRegionTransfer();
        /** This call moves a passenger as part of its vehicle's teleport. */
        boolean asPassenger();
        boolean sameWorld();
        boolean callerOwnsDestination();
        boolean destinationLoaded();
    }

    /**
     * Revalidates after the event: listeners run arbitrary code, so the entity may be gone, on
     * another region or mid-transfer; then the outer teleport is aborted rather than resumed against
     * it. Destination state is read only once the entity is still this caller's.
     */
    public static AfterEvents afterEvents(final AfterEventsProbe probe) {
        if (probe.removed() || !probe.callerOwnsEntity() || probe.inRegionTransfer()) {
            return AfterEvents.ABORT;
        }
        if (probe.sameWorld() && probe.callerOwnsDestination() && probe.destinationLoaded()) {
            return AfterEvents.CONTINUE;
        }
        return probe.asPassenger() ? AfterEvents.ABORT : AfterEvents.REROUTE;
    }

    /** Default on; only an explicit {@code false} (any case, trimmed) turns the owned route off. */
    static boolean enabledFrom(final String value) {
        return value == null || !value.trim().toLowerCase(Locale.ROOT).equals("false");
    }
}
