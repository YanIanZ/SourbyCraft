package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;

import dev.iyanz.sourbycraft.core.PerWorldHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Event-gated per-chunk / per-world entity caps (entity.max-per-chunk,
 * entity.max-specials-per-chunk, item.max-per-chunk,
 * entity.max-arrows-per-world). Costs run on spawn events only; caps <= 0
 * disable a gate entirely. Natural/spawner reasons only for creatures —
 * breeding, plugins and commands are never blocked.
 *
 * <p><b>Folia adaptation (F2c).</b>
 * <ul>
 *   <li><b>Spawn-cap listeners</b> ({@code onCreatureSpawn} / {@code onEntitySpawn} /
 *       {@code onItemSpawn} / {@code onProjectileLaunch}) are unchanged: Bukkit spawn
 *       events fire on the owning region on Folia, and each handler only reads config +
 *       the event's own chunk/entity (all owned by the firing region) then cancels — no
 *       main-thread assumption.</li>
 *   <li><b>Arrow sweeper.</b> The Paper tag drove {@code sweepArrows} from
 *       {@code Bukkit.getScheduler().runTaskTimer(...)} (global main-thread scheduler,
 *       absent on Folia). On Folia the sweep is driven by the perf-engine bootstrap's
 *       global-region scheduler; each arrow's {@code remove()} is a cross-region entity
 *       mutation, so it is re-dispatched onto that arrow's own region scheduler via
 *       {@code arrow.getScheduler().run(...)} rather than being called from the sweep
 *       thread. The count map is a {@link PerWorldHolder} ({@link java.util.concurrent.ConcurrentHashMap}
 *       backed) so it is safe to publish from the sweep thread and read from region threads.</li>
 * </ul>
 */
public final class LagLimits implements Listener {

    /**
     * Arrow counts per world, refreshed by the 1 Hz sweeper.
     * PerWorldHolder centralizes eviction on WorldUnloadEvent.
     */
    private static final PerWorldHolder<Integer> ARROW_COUNT = new PerWorldHolder<>();

    private LagLimits() {}

    /**
     * Register the spawn-cap listeners. The arrow sweeper is driven separately by the
     * perf-engine bootstrap on the Folia global-region scheduler (see {@link #sweepArrows()}).
     */
    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new LagLimits(), plugin);
        plugin.getLogger().info("[lag-limits] active: entity/chunk=" + SourbyCraftConfig.maxEntityPerChunk
            + " specials/chunk=" + SourbyCraftConfig.maxSpecialsPerChunk
            + " items/chunk=" + SourbyCraftConfig.itemMaxPerChunk
            + " arrows/world=" + SourbyCraftConfig.maxArrowsPerWorld);
    }

    private static int chunkCount(Chunk chunk, Class<? extends Entity> type) {
        int n = 0;
        for (Entity e : chunk.getEntities()) {
            if (type.isInstance(e)) n++;
        }
        return n;
    }

    private static boolean isSpecial(Entity e) {
        return e instanceof TNTPrimed || e instanceof ExperienceOrb
            || e instanceof AreaEffectCloud || e instanceof EvokerFangs;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        int cap = SourbyCraftConfig.maxEntityPerChunk;
        if (cap <= 0) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        if (chunkCount(e.getLocation().getChunk(), LivingEntity.class) >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        int cap = SourbyCraftConfig.maxSpecialsPerChunk;
        if (cap <= 0 || !isSpecial(e.getEntity())) return;
        int n = 0;
        for (Entity other : e.getLocation().getChunk().getEntities()) {
            if (isSpecial(other)) n++;
        }
        if (n >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        int cap = SourbyCraftConfig.itemMaxPerChunk;
        if (cap <= 0) return;
        if (chunkCount(e.getLocation().getChunk(), Item.class) >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        int cap = SourbyCraftConfig.maxArrowsPerWorld;
        if (cap <= 0 || !(e.getEntity() instanceof AbstractArrow)) return;
        Integer count = ARROW_COUNT.get(e.getEntity().getWorld().getName());
        if (count != null && count >= cap) e.setCancelled(true);
    }

    /**
     * 1 Hz: refresh arrow counts; cull oldest grounded arrows beyond cap.
     *
     * <p>Folia: invoked from the bootstrap's global-region scheduler. World/entity
     * enumeration is read-only; each {@code arrow.remove()} is re-dispatched onto the
     * arrow's own region scheduler because entity removal must run on the owning region.
     */
    public static void sweepArrows() {
        int cap = SourbyCraftConfig.maxArrowsPerWorld;
        for (World world : Bukkit.getWorlds()) {
            List<AbstractArrow> arrows = new ArrayList<>(world.getEntitiesByClass(AbstractArrow.class));
            ARROW_COUNT.put(world.getName(), arrows.size());
            if (cap <= 0 || arrows.size() <= cap) continue;
            arrows.sort((a, b) -> Integer.compare(a.getEntityId(), b.getEntityId())); // oldest first
            int toRemove = arrows.size() - cap;
            for (AbstractArrow arrow : arrows) {
                if (toRemove <= 0) break;
                if (arrow.isInBlock() && !(arrow.getShooter() instanceof org.bukkit.entity.Player p && p.isOnline() && arrow.getTicksLived() < 100)) {
                    // Folia: hop to the arrow's owning region to remove it.
                    arrow.getScheduler().run(
                        org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
                        task -> { if (arrow.isValid()) arrow.remove(); },
                        null
                    );
                    toRemove--;
                }
            }
        }
    }
}
