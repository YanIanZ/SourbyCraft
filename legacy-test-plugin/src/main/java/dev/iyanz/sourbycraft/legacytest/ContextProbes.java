package dev.iyanz.sourbycraft.legacytest;

import dev.iyanz.sourbycraft.api.scheduler.EntityTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * The bridge's inferred entity anchor ({@code SchedulingContext}), as far as a headless server can show
 * it. There is no player in CI, so the player sources of the anchor (a player's event or command) are
 * covered by unit tests; what runs here is the part that does not need one:
 *
 * <ul>
 * <li>{@code LEGACY_CONTEXT_CHAIN_OK}: an {@code EntityTask} body (explicit anchor) schedules a Bukkit
 * async task, whose body schedules a plain {@code runTask} naming nothing. The async body carries the
 * anchor, so the plain task runs on the region owning the entity, not on the global region it would
 * otherwise fall to (the DeluxeMenus shape: anchored frame -> async -> sync).</li>
 * <li>{@code LEGACY_CONTEXT_GLOBAL_ISOLATED_OK}: an explicit {@code GlobalRegionScheduler} request made
 * from that same anchored frame publishes nothing, so a plain {@code runTask} from its body stays on the
 * global region.</li>
 * <li>{@code LEGACY_CONTEXT_MOB_EVENT_OK}: a plugin event about a non-player entity, dispatched on the
 * global tick, anchors nothing (players only): the plain {@code runTask} its listener schedules runs on
 * the global region.</li>
 * </ul>
 */
final class ContextProbes implements Listener {

    /** A plugin's own event about an entity, as e.g. a custom mob plugin fires. */
    public static final class FixtureEntityEvent extends EntityEvent {
        private static final HandlerList HANDLERS = new HandlerList();

        FixtureEntityEvent(final Entity entity) {
            super(entity);
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private final LegacyBridgePlugin plugin;

    ContextProbes(final LegacyBridgePlugin plugin) {
        this.plugin = plugin;
    }

    void run() {
        final World world = this.plugin.getServer().getWorlds().get(0);
        final BukkitScheduler scheduler = this.plugin.getServer().getScheduler();
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        final int chunkX = 7;
        final int chunkZ = 7;
        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
            world.addPluginChunkTicket(chunkX, chunkZ, this.plugin);
            final Location spot = new Location(world, 120.5, 100, 120.5);
            final ArmorStand stand = world.spawn(spot, ArmorStand.class, created -> {
                created.setGravity(false);
                created.setPersistent(false);
            });
            // Scheduled from the global region, so nothing but the explicit EntityTask names an owner.
            Bukkit.getGlobalRegionScheduler().run(this.plugin, global -> {
                scheduler.runTask(this.plugin, EntityTask.of(stand, () -> {
                    scheduler.runTaskAsynchronously(this.plugin, () -> scheduler.runTask(this.plugin, () ->
                        this.plugin.check(Bukkit.isOwnedByCurrentRegion(stand) && !Bukkit.isGlobalTickThread(),
                            "LEGACY_CONTEXT_CHAIN", "thread=" + Thread.currentThread().getName()
                                + " owned=" + Bukkit.isOwnedByCurrentRegion(stand))));
                    Bukkit.getGlobalRegionScheduler().run(this.plugin, explicit -> scheduler.runTask(this.plugin, () ->
                        this.plugin.check(Bukkit.isGlobalTickThread(), "LEGACY_CONTEXT_GLOBAL_ISOLATED",
                            "thread=" + Thread.currentThread().getName())));
                }));
                this.plugin.getServer().getPluginManager().callEvent(new FixtureEntityEvent(stand));
            });
        });
    }

    @EventHandler
    public void onFixtureEntity(final FixtureEntityEvent event) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.plugin.check(Bukkit.isGlobalTickThread(),
            "LEGACY_CONTEXT_MOB_EVENT", "thread=" + Thread.currentThread().getName() + " entity="
                + event.getEntityType()));
    }
}
