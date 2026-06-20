package dev.iyanz.sourbycraft.wildstacker;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Stacker — items only.
 *
 * <p>User direction: do NOT stack mobs. Only items (+ sounds + particles in
 * future iterations). The previous mob-stacker prototype is removed; this
 * class now merges ground item entities of the same {@link ItemStack}
 * signature within a configured radius into a single representative whose
 * {@code amount} carries the combined total.</p>
 *
 * <p>Vanilla already merges items via {@link ItemMergeEvent} on tick but only
 * at ~1-block radius. This widens the radius via spawn-time scan + opens a
 * larger merge window via the same event. Stack size caps at material's
 * {@link ItemStack#getMaxStackSize()} × {@code stacker.max-stack} so an
 * operator can let a chest's worth of dropped diamonds compress to one entity.</p>
 */
public final class EntityStacker implements Listener {

    private static volatile boolean ENABLED = false;
    private static volatile double RADIUS = 10.0;
    private static volatile int MAX_STACK_MULTIPLIER = 100;
    private static volatile Plugin OWNER;
    private static final java.util.concurrent.atomic.AtomicInteger DEBUG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private EntityStacker() {}

    public static void register(Plugin plugin) {
        OWNER = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(new EntityStacker(), plugin);
        plugin.getLogger().info("[stacker] item stacker " + (ENABLED ? "ENABLED" : "disabled")
                + " (stacker.enabled=" + ENABLED + " radius=" + RADIUS
                + " maxStackMultiplier=" + MAX_STACK_MULTIPLIER + ")");
    }

    public static void reload() {
        ENABLED = SourbyCraftConfig.stackerEnabled;
        RADIUS = Math.max(0.5, SourbyCraftConfig.stackerRadius);
        MAX_STACK_MULTIPLIER = Math.max(1, SourbyCraftConfig.stackerMaxStack);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!ENABLED) return;
        Item newItem = e.getEntity();
        ItemStack newStack = newItem.getItemStack();
        if (newStack == null || newStack.getType().isAir()) return;
        int cap = newStack.getMaxStackSize() * MAX_STACK_MULTIPLIER;
        if (cap <= 0) cap = Integer.MAX_VALUE;

        Location loc = newItem.getLocation();
        int scanned = 0;
        for (Entity nearby : newItem.getWorld().getNearbyEntities(loc, RADIUS, RADIUS, RADIUS)) {
            scanned++;
            if (nearby == newItem) continue;
            if (!(nearby instanceof Item nearItem)) continue;
            if (nearItem.isDead()) continue;
            ItemStack near = nearItem.getItemStack();
            if (near == null || near.getType().isAir()) continue;
            if (!near.isSimilar(newStack)) continue;
            int sum = near.getAmount() + newStack.getAmount();
            if (sum > cap) continue;
            near.setAmount(sum);
            nearItem.setItemStack(near);
            // Cancel new spawn — merged into existing.
            e.setCancelled(true);
            if (OWNER != null) {
                int n = DEBUG_COUNTER.incrementAndGet();
                if (n <= 20) {
                    OWNER.getLogger().info("[stacker] merged " + newStack.getType()
                            + " into nearby item @ " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                            + " (newAmount=" + sum + " scanned=" + scanned + ")");
                }
            }
            return;
        }
        if (OWNER != null) {
            int n = DEBUG_COUNTER.incrementAndGet();
            if (n <= 20) {
                OWNER.getLogger().info("[stacker] no-match for " + newStack.getType()
                        + " @ " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                        + " (scanned=" + scanned + " radius=" + RADIUS + ")");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent e) {
        if (!ENABLED) return;
        // Vanilla merge already happens; we don't override the result, only
        // ensure the merged amount respects our extended cap.
        ItemStack merged = e.getTarget().getItemStack();
        int cap = merged.getMaxStackSize() * MAX_STACK_MULTIPLIER;
        if (cap > 0 && merged.getAmount() > cap) {
            merged.setAmount(cap);
            e.getTarget().setItemStack(merged);
        }
    }
}
