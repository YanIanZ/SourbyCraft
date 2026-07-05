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
 */
public final class LagLimits implements Listener {

    /**
     * Arrow counts per world, refreshed by the 1 Hz sweeper.
     * MT1: PerWorldHolder centralized eviction replaces the inline onWorldUnload handler.
     */
    private static final PerWorldHolder<Integer> ARROW_COUNT = new PerWorldHolder<>();

    private LagLimits() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new LagLimits(), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, LagLimits::sweepArrows, 100L, 20L);
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

    // MT1: onWorldUnload removed — PerWorldHolder.registerCleanup evicts both ARROW_COUNT
    // and SourbyCraftWorldConfig.BY_WORLD centrally. SourbyCraftWorldConfig.invalidate
    // also remains as a delegate for external callers.

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        int cap = SourbyCraftConfig.maxArrowsPerWorld;
        if (cap <= 0 || !(e.getEntity() instanceof AbstractArrow)) return;
        Integer count = ARROW_COUNT.get(e.getEntity().getWorld().getName());
        if (count != null && count >= cap) e.setCancelled(true);
    }

    /** 1 Hz: refresh arrow counts; cull oldest grounded arrows beyond cap. */
    private static void sweepArrows() {
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
                    arrow.remove();
                    toRemove--;
                }
            }
        }
    }
}
