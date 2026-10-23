package dev.iyanz.sourbycraft.entity.teleport;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * The legacy Bukkit teleport events on the region engine's asynchronous routes.
 *
 * <p>Adapted from LightingLuminol (GPL-3.0; Bacteriawa, 2026-06-06; revision 72d51c4d, minecraft patch
 * {@code 0001-Base-Dirty-Patches}, {@code Entity#teleportAsync} and {@code Entity#portalToAsync} hunks marked
 * {@code // LightingLuminol Start}). The region-threading base fires only its own
 * {@code EntityTeleportAsyncEvent} / {@code EntityPortalAsyncEvent} on these routes, so a plugin listening for
 * {@link PlayerTeleportEvent} or {@link PlayerPortalEvent} -- every pre-Folia plugin -- never hears about a
 * {@code /tp}, a plugin {@code teleportAsync}, the owned {@code ASYNC} route of {@code Entity#teleport} or a
 * nether/end portal. SourbyCraft fires them from the entity's owning thread, honours cancellation and
 * {@code getTo()}, and never fires twice for one call ({@link #suppressPlayerTeleportEvent}: Paper's synchronous
 * body already ran the event before it hands a moved destination to the async route). Minecraft feature patch
 * "legacy PlayerTeleportEvent and PlayerPortalEvent on the async teleport routes".</p>
 *
 * <p>Independent of {@code aurora.bridge.thread-checks}: firing an event on the owner thread is safe. Escape
 * hatch {@code -Daurora.compat.async-teleport-events=false} (RESTART_REQUIRED).</p>
 */
public final class AsyncTeleportEvents {

    /** JVM property; read once at class init. */
    public static final String PROPERTY = "aurora.compat.async-teleport-events";

    /** {@code true} unless {@code -Daurora.compat.async-teleport-events=false}. */
    public static final boolean ENABLED = enabledFrom(System.getProperty(PROPERTY));

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final LongAdder TELEPORT_FIRED = new LongAdder();
    private static final LongAdder TELEPORT_CANCELLED = new LongAdder();
    private static final LongAdder PORTAL_FIRED = new LongAdder();
    private static final LongAdder PORTAL_CANCELLED = new LongAdder();

    private AsyncTeleportEvents() {}

    /** Whether {@code Entity#teleportAsync} fires {@link PlayerTeleportEvent} for this call. */
    public static boolean firesPlayerTeleport(final boolean callEvents) {
        return fires(ENABLED, callEvents, SUPPRESSED.get(),
            PlayerTeleportEvent.getHandlerList().getRegisteredListeners().length > 0);
    }

    /** Whether the portal route fires {@link PlayerPortalEvent}. */
    public static boolean firesPlayerPortal() {
        return fires(ENABLED, true, false, PlayerPortalEvent.getHandlerList().getRegisteredListeners().length > 0);
    }

    /** The decision, pinned by {@code AsyncTeleportEventsTest}. */
    static boolean fires(final boolean enabled, final boolean callEvents, final boolean suppressed, final boolean hasListeners) {
        return enabled && callEvents && !suppressed && hasListeners;
    }

    /**
     * Fires {@link PlayerTeleportEvent} on the current (owning) thread.
     *
     * @return {@code null} when a listener cancelled it, else the destination to use (a listener may have moved it)
     */
    public static Location playerTeleport(final ServerPlayer player, final ServerLevel from, final Vec3 fromPos,
                                          final ServerLevel to, final Vec3 toPos, final Float yaw, final Float pitch,
                                          final TeleportCause cause) {
        final Location fromLocation = new Location(from.getWorld(), fromPos.x, fromPos.y, fromPos.z, player.getYRot(), player.getXRot());
        final Location toLocation = new Location(to.getWorld(), toPos.x, toPos.y, toPos.z,
            yaw == null ? player.getYRot() : yaw, pitch == null ? player.getXRot() : pitch);
        final PlayerTeleportEvent event = new PlayerTeleportEvent(player.getBukkitEntity(), fromLocation, toLocation,
            cause == null ? TeleportCause.UNKNOWN : cause);
        TELEPORT_FIRED.increment();
        if (!event.callEvent() || event.getTo() == null || event.getTo().getWorld() == null) {
            TELEPORT_CANCELLED.increment();
            return null;
        }
        return event.getTo();
    }

    /**
     * Fires {@link PlayerPortalEvent} on the destination's thread once the exit is known.
     *
     * @return {@code null} when a listener cancelled it, else the exit to use
     */
    public static Location playerPortal(final ServerPlayer player, final ServerLevel from, final Vec3 fromPos,
                                        final ServerLevel to, final Vec3 toPos, final float yaw, final float pitch, final TeleportCause cause) {
        final Location fromLocation = new Location(from.getWorld(), fromPos.x, fromPos.y, fromPos.z, player.getYRot(), player.getXRot());
        final Location toLocation = new Location(to.getWorld(), toPos.x, toPos.y, toPos.z, yaw, pitch);
        final PlayerPortalEvent event = new PlayerPortalEvent(player.getBukkitEntity(), fromLocation, toLocation, cause);
        PORTAL_FIRED.increment();
        if (!event.callEvent() || event.getTo() == null || event.getTo().getWorld() == null) {
            PORTAL_CANCELLED.increment();
            return null;
        }
        return event.getTo();
    }

    /** Where the portal route places the entity once the exit is known, and with which transition. */
    public record PortalOutcome(ServerLevel level, net.minecraft.world.level.portal.TeleportTransition transition) {}

    /**
     * Called by the portal waiter on the destination's thread. For a player with {@link PlayerPortalEvent}
     * listeners the event runs: a moved {@code getTo()} replaces the exit; a cancel places the player back in the
     * origin world where it stood (it has already been detached from that world, so "do nothing" would drop it).
     * Anyone else gets the found exit unchanged.
     */
    public static PortalOutcome portalOutcome(final net.minecraft.world.entity.Entity entity, final ServerLevel origin,
                                              final Vec3 initialPosition, final ServerLevel destination,
                                              final net.minecraft.world.level.portal.TeleportTransition found,
                                              final String portalType) {
        if (!(entity instanceof ServerPlayer player) || !firesPlayerPortal()) {
            return new PortalOutcome(destination, found);
        }
        final PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(
            PositionMoveRotation.of(player), PositionMoveRotation.of(found), found.relatives());
        final Location exit = playerPortal(player, origin, initialPosition, destination, absolute.position(),
            absolute.yRot(), absolute.xRot(), portalCause(portalType));
        if (exit == null) {
            return new PortalOutcome(origin, new net.minecraft.world.level.portal.TeleportTransition(origin, initialPosition, Vec3.ZERO,
                player.getYRot(), player.getXRot(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
        }
        final ServerLevel exitLevel = level(exit);
        return new PortalOutcome(exitLevel, redirectedPortalTransition(found, absolute, exitLevel, exit));
    }

    /** All movement is absolute after the listener; retain the original transition's other semantics. */
    static TeleportTransition redirectedPortalTransition(final TeleportTransition found, final PositionMoveRotation absolute,
                                                         final ServerLevel level, final Location exit) {
        return new TeleportTransition(level, new Vec3(exit.getX(), exit.getY(), exit.getZ()), absolute.deltaMovement(),
            exit.getYaw(), exit.getPitch(), found.missingRespawnBlock(), found.asPassenger(), java.util.Set.of(),
            found.postTeleportTransition(), found.cause(), found.passengerTeleportationMode());
    }

    /** Detached portal passengers already name the originally selected world; cancel/redirect may change it. */
    public static void retargetPortalEntity(final Entity entity, final ServerLevel destination) {
        if (entity.level() == destination) return;
        if (entity instanceof ServerPlayer player) {
            player.setServerLevel(destination);
            // The loading screen already announced the original destination before the event.
            // Cancellation returns through same-world placement, which sends no replacement respawn.
            if (dev.iyanz.aurora.engine.GlobalConfiguration.getInstance().displayWorldLoadScreenForCrossRegionTransfers) {
                player.sendRespawnClientbound(false);
            }
        } else {
            entity.setLevel(destination);
        }
    }

    /** Paper's teleport cause for a portal type. */
    public static TeleportCause portalCause(final String portalType) {
        return switch (portalType.toUpperCase(Locale.ROOT)) {
            case "END" -> TeleportCause.END_PORTAL;
            case "NETHER" -> TeleportCause.NETHER_PORTAL;
            default -> TeleportCause.UNKNOWN;
        };
    }

    /** The level a Bukkit location names. */
    public static ServerLevel level(final Location location) {
        return ((CraftWorld) location.getWorld()).getHandle();
    }

    /** Runs {@code call} with {@link PlayerTeleportEvent} suppressed on this thread (it already ran for this move). */
    public static void suppressPlayerTeleportEvent(final Runnable call) {
        final boolean before = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            call.run();
        } finally {
            SUPPRESSED.set(before);
        }
    }

    public static long teleportEventsFired() { return TELEPORT_FIRED.sum(); }
    public static long teleportEventsCancelled() { return TELEPORT_CANCELLED.sum(); }
    public static long portalEventsFired() { return PORTAL_FIRED.sum(); }
    public static long portalEventsCancelled() { return PORTAL_CANCELLED.sum(); }

    /** Default on; only an explicit {@code false} (any case, trimmed) turns the events off. */
    static boolean enabledFrom(final String value) {
        return value == null || !value.trim().toLowerCase(Locale.ROOT).equals("false");
    }
}
