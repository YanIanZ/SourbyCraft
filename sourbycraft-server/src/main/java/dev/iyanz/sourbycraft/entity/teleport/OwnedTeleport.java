package dev.iyanz.sourbycraft.entity.teleport;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server glue for the owned synchronous {@code Entity#teleport} ({@link TeleportRoute} decides).
 * The feature patches only call in here:
 *
 * <ul>
 *   <li>Paper {@code CraftEntity#teleport0}: {@link #route}; {@code ASYNC} → {@link #scheduleAsync},
 *       {@code SYNC} → Paper's own body inside {@link #runSync}, {@code REFUSE} → the inherited
 *       refusal, kept as the fail-closed fallback.</li>
 *   <li>Minecraft {@code Entity#teleport(TeleportTransition)} / {@code ServerPlayer#teleport}: the
 *       inherited refusal stays for every caller except members of the current {@link #runSync}
 *       scope ({@link #admitted}); after the Bukkit teleport event, {@link #continueAfterEvents}
 *       re-checks the (possibly event-changed) destination and hands it to {@code teleportAsync}
 *       when it left this region.</li>
 * </ul>
 */
public final class OwnedTeleport {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft/Teleport");

    /**
     * Blocks added around the entity's bounding box at the destination before the ownership check
     * when the entity carries passengers (they move with it, at an offset). Without passengers the
     * box itself is checked, the same rule the engine's in-tick movement check ({@code Entity#move})
     * applies. Folia's {@code teleportAsync} same-region shortcut asks for 8 chunks around the
     * destination, which an idle server's spawn region does not own even 3 blocks from the entity
     * (seen on the test server: every near teleport fell back), so it is not used here.
     */
    static final double PASSENGER_MARGIN_BLOCKS = 16.0;

    static final AsyncFallbackLog FALLBACK_LOG =
        new AsyncFallbackLog(TimeUnit.MINUTES.toNanos(10L), 256, System::nanoTime, LOGGER::warn);

    private static final LongAdder SYNC = new LongAdder();
    private static final LongAdder ASYNC = new LongAdder();
    private static final LongAdder REROUTED = new LongAdder();
    private static final LongAdder ABORTED = new LongAdder();
    /** Relaxed thread checks: async teleports the caller waited for (LightingLuminol join semantics). */
    private static final LongAdder JOINED = new LongAdder();
    /** Of those, waits that gave up after {@link #JOIN_TIMEOUT_TICK_THREAD} / {@link #JOIN_TIMEOUT_OTHER}. */
    private static final LongAdder JOIN_TIMEOUTS = new LongAdder();

    /** A blocked tick thread is a stalled region (or the stalled global tick): bounded well under the region watchdog. */
    static final long JOIN_TIMEOUT_TICK_THREAD = TimeUnit.SECONDS.toMillis(30L);
    /** Threads that tick nothing may wait longer (a chunk load plus the destination's next tick). */
    static final long JOIN_TIMEOUT_OTHER = TimeUnit.SECONDS.toMillis(60L);

    private OwnedTeleport() {}

    public static TeleportRoute.Route route(final Entity entity, final Location to) {
        final ServerLevel destination = ((CraftWorld) to.getWorld()).getHandle();
        return TeleportRoute.decide(TeleportRoute.ENABLED, probe(entity, destination, new Vec3(to.getX(), to.getY(), to.getZ())));
    }

    private static TeleportRoute.Probe probe(final Entity entity, final ServerLevel destination, final Vec3 pos) {
        return new TeleportRoute.Probe() {
            @Override public boolean callerOwnsEntity() { return RegionOwnership.current().isTickThreadFor(entity); }
            @Override public boolean sameWorld() { return destination == entity.level(); }
            @Override public boolean callerOwnsDestination() {
                final net.minecraft.world.phys.AABB box = entity.getBoundingBoxAt(pos.x, pos.y, pos.z);
                return RegionOwnership.current().isTickThreadFor(destination, entity.getPassengers().isEmpty()
                    ? box : box.inflate(PASSENGER_MARGIN_BLOCKS, 0.0, PASSENGER_MARGIN_BLOCKS));
            }
            @Override public boolean destinationLoaded() {
                return destination.areChunksLoadedForMove(entity.getBoundingBoxAt(pos.x, pos.y, pos.z));
            }
        };
    }

    /** Runs Paper's synchronous teleport with the entity and its passengers admitted past the NMS refusal. */
    public static boolean runSync(final Entity root, final BooleanSupplier paperTeleport) {
        SYNC.increment();
        final List<Entity> members = new ArrayList<>();
        members.add(root);
        for (final Entity passenger : root.getIndirectPassengers()) {
            members.add(passenger);
        }
        return SyncTeleportScope.run(members, paperTeleport);
    }

    /**
     * Schedules the move with the caller's cause and velocity flags; always {@code false}: not
     * completed now. Off the entity's region nothing of the entity is read here: the move is
     * started from the entity's own scheduler, where its origin, velocity and vehicle are read.
     */
    public static boolean scheduleAsync(final CraftEntity entity, final Location to, final TeleportCause cause,
                                        final io.papermc.paper.entity.TeleportFlag... flags) {
        ASYNC.increment();
        final Entity handle = entity.getHandleRaw(); // raw: may run on any thread; only identity and ownership are used here
        final Location target = to.clone();
        final Set<Relative> relatives = FallbackMotion.relatives(flags);
        final TeleportCause finalCause = cause == null ? TeleportCause.UNKNOWN : cause;
        // Adapted from LightingLuminol (GPL-3.0, Bacteriawa, 2026-06-06, paper patch 0002 CraftPlayer#teleport0:
        // "return teleportAsync(location, cause).join();"): with relaxed thread checks the caller waits for the
        // move instead of getting "not completed now". Owner decision 2026-10-22 "Tiru penuh LightingLuminol".
        if (dev.iyanz.sourbycraft.guards.ThreadChecks.RELAXED) {
            final ServerLevel destination = ((CraftWorld) target.getWorld()).getHandle();
            final boolean tickThread = RegionOwnership.current().isTickThread();
            if (TeleportRoute.joinsWhenRelaxed(probe(handle, destination, new Vec3(target.getX(), target.getY(), target.getZ())), tickThread)) {
                return joinAsync(entity, target, finalCause, relatives, tickThread);
            }
        }
        FALLBACK_LOG.record(CallerPlugin.find(), why(handle, to));
        if (RegionOwnership.current().isTickThreadFor(handle)) {
            startAsync(handle, target, finalCause, relatives);
        } else {
            entity.taskScheduler.schedule((final Entity owned) -> startAsync(owned, target, finalCause, relatives),
                (final Entity retired) -> {}, 1L);
        }
        return false;
    }

    /**
     * LightingLuminol's {@code teleportAsync(...).join()}, bounded. The Bukkit {@code teleportAsync} runs the move on
     * the entity's own region (inline when the caller is that region) and completes on the destination's thread;
     * this thread only waits. {@code true} when the entity moved, {@code false} when the move failed, the wait
     * timed out (the move may still complete later) or the thread was interrupted.
     */
    private static boolean joinAsync(final CraftEntity entity, final Location target, final TeleportCause cause,
                                     final Set<Relative> relatives, final boolean tickThread) {
        JOINED.increment();
        final long timeoutMillis = tickThread ? JOIN_TIMEOUT_TICK_THREAD : JOIN_TIMEOUT_OTHER;
        final java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
        final java.util.function.Consumer<Entity> move = owned -> {
            try {
                startAsync(owned, target, cause, relatives, future);
            } catch (final Throwable failed) {
                future.completeExceptionally(failed);
                if (failed instanceof VirtualMachineError fatal) throw fatal;
            }
        };
        final Entity handle = entity.getHandleRaw();
        if (RegionOwnership.current().isTickThreadFor(handle)) {
            move.accept(handle);
        } else if (!entity.taskScheduler.schedule(move, retired -> future.complete(false), 1L)) {
            future.complete(false);
        }
        try {
            return Boolean.TRUE.equals(future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (final java.util.concurrent.TimeoutException timedOut) {
            JOIN_TIMEOUTS.increment();
            LOGGER.warn("Relaxed thread checks: {} waited {} ms for a teleport of {} to {} that has not completed; returning false "
                + "(the move may still finish later)", Thread.currentThread().getName(), timeoutMillis,
                entity.getUniqueId(), target);
            return false;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final java.util.concurrent.ExecutionException failed) {
            LOGGER.warn("Relaxed thread checks: a waited-for teleport of {} to {} failed", entity.getUniqueId(), target, failed.getCause());
            return false;
        }
    }

    /** On the entity's region: Paper's dismount rule, the flags' velocity, then the engine's teleportAsync. */
    private static void startAsync(final Entity entity, final Location to, final TeleportCause cause, final Set<Relative> relatives) {
        startAsync(entity, to, cause, relatives, null);
    }

    private static void startAsync(final Entity entity, final Location to, final TeleportCause cause, final Set<Relative> relatives,
                                   final java.util.concurrent.CompletableFuture<Boolean> completion) {
        final ServerLevel destination = ((CraftWorld) to.getWorld()).getHandle();
        final Vec3 pos = new Vec3(to.getX(), to.getY(), to.getZ());
        final Vec3 velocity = FallbackMotion.velocity(PositionMoveRotation.of(entity), pos, to.getYaw(), to.getPitch(), relatives);
        if (entity.isPassenger()) {
            entity.stopRiding(); // Paper's sync teleport dismounts the entity it moves (passengers of it stay)
        }
        final boolean started = entity.teleportAsync(destination, pos, to.getYaw(), to.getPitch(), velocity, cause,
            Entity.TELEPORT_FLAG_LOAD_CHUNK | Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS,
            completion == null ? null : moved -> completion.complete(true));
        if (!started && completion != null) completion.complete(false);
    }

    private static String why(final Entity entity, final Location to) {
        final ServerLevel destination = ((CraftWorld) to.getWorld()).getHandle();
        final TeleportRoute.Probe probe = probe(entity, destination, new Vec3(to.getX(), to.getY(), to.getZ()));
        // Same order as TeleportRoute.decide: the destination is only read once the caller owns it.
        if (!probe.callerOwnsEntity()) return "called off the entity's region";
        if (!probe.sameWorld()) return "destination in another world";
        if (!probe.callerOwnsDestination()) return "destination owned by another region";
        return "destination chunks not loaded";
    }

    /** CraftPlayer: Paper's "no connection: false", read only when the caller owns the player. */
    public static boolean playerMayProceed(final net.minecraft.server.level.ServerPlayer player) {
        return TeleportRoute.playerMayProceed(RegionOwnership.current().isTickThreadFor(player), () -> player.connection != null);
    }

    /**
     * Whether the NMS teleport of {@code entity} was admitted by the current API scope: a member, or
     * mounted on an admitted root vehicle (a listener may add a passenger after the snapshot), owned
     * by this thread, same level.
     */
    public static boolean admitted(final Entity entity, final TeleportTransition transition) {
        return SyncTeleportScope.admits(entity, e -> ((Entity) e).getRootVehicle())
            && RegionOwnership.current().isTickThreadFor(entity) && transition.newLevel() == entity.level();
    }

    /**
     * After the Bukkit teleport event ({@link TeleportRoute#afterEvents}): {@code true} to move now.
     * Otherwise nothing has moved yet: an obsolete teleport (entity removed, on another region, or
     * a listener started another transfer) is dropped; a root whose destination left this region
     * is handed to {@code teleportAsync} (the async path fires no Bukkit teleport event again); a
     * passenger stays seated.
     */
    public static boolean continueAfterEvents(final Entity entity, final TeleportTransition transition) {
        final ServerLevel destination = transition.newLevel();
        final PositionMoveRotation[] absolute = new PositionMoveRotation[1];
        final TeleportRoute.AfterEvents next = TeleportRoute.afterEvents(new TeleportRoute.AfterEventsProbe() {
            private TeleportRoute.Probe destinationProbe() {
                if (absolute[0] == null) {
                    absolute[0] = PositionMoveRotation.calculateAbsolute(
                        PositionMoveRotation.of(entity), PositionMoveRotation.of(transition), transition.relatives());
                }
                return probe(entity, destination, absolute[0].position());
            }
            @Override public boolean removed() { return entity.isRemoved(); }
            @Override public boolean callerOwnsEntity() { return RegionOwnership.current().isTickThreadFor(entity); }
            @Override public boolean inRegionTransfer() { return entity.getRegionTransferState() != null; }
            @Override public boolean asPassenger() { return transition.asPassenger(); }
            @Override public boolean sameWorld() { return destination == entity.level(); }
            @Override public boolean callerOwnsDestination() { return this.destinationProbe().callerOwnsDestination(); }
            @Override public boolean destinationLoaded() { return this.destinationProbe().destinationLoaded(); }
        });
        switch (next) {
            case CONTINUE:
                return true;
            case REROUTE:
                REROUTED.increment();
                FALLBACK_LOG.record(CallerPlugin.find(), "a teleport event moved the destination off this region");
                if (absolute[0] == null) {
                    absolute[0] = PositionMoveRotation.calculateAbsolute(
                        PositionMoveRotation.of(entity), PositionMoveRotation.of(transition), transition.relatives());
                }
                if (entity.isPassenger()) entity.stopRiding();
                // Paper's PlayerTeleportEvent already ran for this call: the async route must not fire it again.
                AsyncTeleportEvents.suppressPlayerTeleportEvent(() -> entity.teleportAsync(destination, absolute[0].position(),
                    absolute[0].yRot(), absolute[0].xRot(), absolute[0].deltaMovement(),
                    transition.cause() == null ? TeleportCause.UNKNOWN : transition.cause(),
                    Entity.TELEPORT_FLAG_LOAD_CHUNK | Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS, null));
                return false;
            default:
                ABORTED.increment();
                return false;
        }
    }

    /** Async teleports the caller waited for (relaxed thread checks only). */
    public static long joinedCount() { return JOINED.sum(); }

    /** Waits that timed out (relaxed thread checks only). */
    public static long joinTimeoutCount() { return JOIN_TIMEOUTS.sum(); }

    /** Synchronous teleports admitted. */
    public static long syncCount() { return SYNC.sum(); }

    /** Calls turned into {@code teleportAsync} before any event. */
    public static long asyncCount() { return ASYNC.sum(); }

    /** Sync teleports whose event moved the destination off the region, finished by {@code teleportAsync}. */
    public static long reroutedCount() { return REROUTED.sum(); }

    /** Sync teleports dropped after the event as obsolete (or a passenger that could not follow). */
    public static long abortedCount() { return ABORTED.sum(); }
}
