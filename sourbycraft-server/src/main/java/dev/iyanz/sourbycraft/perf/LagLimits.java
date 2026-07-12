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
 *       global-region scheduler. That thread does not own the regionized per-entity state,
 *       so it must not read an arrow's fields or remove it directly — both the field reads
 *       ({@code isInBlock/getShooter/getTicksLived}) and the {@code remove()} are re-dispatched
 *       together onto that arrow's own region scheduler via {@code arrow.getScheduler().run(...)}
 *       (a copied snapshot of the world's arrow list is safe to hold; only the arrow's live
 *       fields are region-owned). The count map is a {@link PerWorldHolder}
 *       ({@link java.util.concurrent.ConcurrentHashMap} backed) so it is safe to publish from
 *       the sweep thread and read from region threads.</li>
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
        dev.iyanz.sourbycraft.util.SourbyLogger.info("lag-limits active: entity/chunk=" + SourbyCraftConfig.maxEntityPerChunk
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
        if (chunkCount(e.getEntity().getChunk(), LivingEntity.class) >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        int cap = SourbyCraftConfig.maxSpecialsPerChunk;
        if (cap <= 0 || !isSpecial(e.getEntity())) return;
        int n = 0;
        for (Entity other : e.getEntity().getChunk().getEntities()) {
            if (isSpecial(other)) n++;
        }
        if (n >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        int cap = SourbyCraftConfig.itemMaxPerChunk;
        if (cap <= 0) return;
        if (chunkCount(e.getEntity().getChunk(), Item.class) >= cap) e.setCancelled(true);
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
     * <p><b>Folia (region-safe).</b> Invoked from the bootstrap's global-region scheduler.
     * That thread does NOT own the regionized per-entity state, so it must not read arrow
     * fields ({@code isInBlock()} / {@code getShooter()} / {@code getTicksLived()}) nor iterate
     * the region's live entity list from here — doing so races the arrow's own region tick
     * (torn reads) and the entity-list mutation (intermittent
     * {@link java.util.ConcurrentModificationException}).
     *
     * <p>Instead we take a cheap snapshot of the world's arrow list ({@code getEntitiesByClass}
     * copies into a new collection, so the returned list itself is safe to hold), publish the
     * count, sort by id (id is immutable after spawn), then hop to <em>each</em> arrow's own
     * region via {@code arrow.getScheduler().run(...)} — matching {@link OwnerProtection} and
     * {@link TierBossBar}. Every field read AND the {@code remove()} run together on the owning
     * region thread. Because the per-arrow decision now happens off this thread, the cull budget
     * is enforced with an {@link java.util.concurrent.atomic.AtomicInteger} the region callbacks
     * decrement, preserving the "remove only the oldest {@code size-cap}" behaviour and the 1 Hz
     * cadence. A retired arrow (already gone) fires the scheduler's retired-callback and is a
     * no-op, freeing no budget — same best-effort semantics as before.
     */
    public static void sweepArrows() {
        int cap = SourbyCraftConfig.maxArrowsPerWorld;
        // Cap disabled => the whole sweep is dead weight: the full-world entity snapshot below
        // costs an EntityLookup copy + Bukkit wrappers per world per second, publishing a count
        // nothing reads (onProjectileLaunch early-returns when the cap is off).
        if (cap <= 0) return;
        for (World world : Bukkit.getWorlds()) {
            List<AbstractArrow> arrows = new ArrayList<>(world.getEntitiesByClass(AbstractArrow.class));
            ARROW_COUNT.put(world.getName(), arrows.size());
            if (arrows.size() <= cap) continue;
            arrows.sort((a, b) -> Integer.compare(a.getEntityId(), b.getEntityId())); // oldest first (id immutable)
            // Shared cull budget: region callbacks decrement it and only remove while it lasts, so
            // at most (size - cap) grounded arrows are culled even though decisions run off-thread.
            java.util.concurrent.atomic.AtomicInteger budget =
                new java.util.concurrent.atomic.AtomicInteger(arrows.size() - cap);
            // Fan out region tasks for 2x the budget of oldest candidates, not EVERY arrow in the
            // world (6000 tasks/s to cull 1000 is queue pressure exactly when the server hurts);
            // 2x covers ineligible candidates (airborne / fresh player arrows) and the 1 Hz repeat
            // mops up any shortfall next sweep.
            final int fanOut = Math.min(arrows.size(), 2 * (arrows.size() - cap));
            for (AbstractArrow arrow : arrows.subList(0, fanOut)) {
                // Hop to the arrow's OWNING region BEFORE touching any of its fields. All reads
                // (isInBlock/getShooter/getTicksLived) and the remove() run on that region thread.
                arrow.getScheduler().run(
                    org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
                    task -> {
                        if (budget.get() <= 0 || !arrow.isValid()) return;
                        if (arrow.isInBlock() && !(arrow.getShooter() instanceof org.bukkit.entity.Player p
                                && p.isOnline() && arrow.getTicksLived() < 100)) {
                            if (budget.getAndDecrement() > 0) arrow.remove();
                        }
                    },
                    null
                );
            }
        }
    }
}
