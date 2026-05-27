package dev.iyanz.sourbycraft.wildstacker;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Bukkit wildstacker (v9.12):
 *   - Virtual stack count stored in Item PersistentDataContainer (key sourbycraft:stack_count, LONG).
 *   - TextDisplay hologram follows item via per-tick teleport.
 *   - Periodic 20-tick merge scan, chunk-bucketed, with optional LOS check.
 *   - No NMS patches; no paperweight involvement.
 */
public final class WildstackerManager implements Listener {

    // PDC key: sourbycraft:stack_count
    public static NamespacedKey KEY_STACK_COUNT;

    // item UUID -> hologram UUID
    private static final Map<UUID, UUID> ITEM_TO_HOLOGRAM = new ConcurrentHashMap<>();

    private static volatile boolean started = false;

    private WildstackerManager() {}

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    /**
     * Start the manager. Uses the first loaded plugin as the owner for
     * listener registration and task scheduling — same pattern used by
     * existing SourbyCraft commands (RamBarCommand, PingCommand, etc.).
     *
     * Safe to call multiple times (idempotent).
     */
    public static void start() {
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] start() invoked, started=" + started);
        if (started) return;
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] plugin count = " + plugins.length);
        if (plugins.length == 0) {
            Bukkit.getLogger().warning("[SourbyCraft:Wildstacker] No plugins loaded; deferred.");
            return;
        }
        Plugin plugin = plugins[0];
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] owner plugin = " + plugin.getName());
        KEY_STACK_COUNT = new NamespacedKey(plugin, "stack_count");
        Bukkit.getPluginManager().registerEvents(new WildstackerManager(), plugin);
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] events registered");
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!SourbyCraftConfig.wildstackerEnabled) return;
                for (World world : Bukkit.getWorlds()) {
                    tickWorld(world);
                }
            }
        }.runTaskTimer(plugin, 4L, 4L); // SourbyCraft v9.14: 4 ticks (5×/sec) so items merge before visual pile-up
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] scan task scheduled (4-tick interval)");
        started = true;
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] STARTED (PDC key: " + KEY_STACK_COUNT + ")");
    }

    public static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Wildstacker started=").append(started)
          .append(" enabled=").append(SourbyCraftConfig.wildstackerEnabled)
          .append(" hologram=").append(SourbyCraftConfig.wildstackerHologram)
          .append(" los=").append(SourbyCraftConfig.wildstackerLosCheck)
          .append(" radius=").append(SourbyCraftConfig.itemMergeRadius)
          .append("\n");
        if (KEY_STACK_COUNT != null) sb.append("PDC key: ").append(KEY_STACK_COUNT).append("\n");
        sb.append("Tracked holograms: ").append(ITEM_TO_HOLOGRAM.size()).append("\n");
        sb.append("Worlds:\n");
        for (World w : Bukkit.getWorlds()) {
            int items = w.getEntitiesByClass(Item.class).size();
            sb.append("  ").append(w.getName()).append(": ").append(items).append(" items\n");
        }
        return sb.toString();
    }

    public static void shutdown() {
        if (!started) return;
        for (UUID hUid : ITEM_TO_HOLOGRAM.values()) {
            Entity e = Bukkit.getEntity(hUid);
            if (e != null) e.remove();
        }
        ITEM_TO_HOLOGRAM.clear();
        started = false;
    }

    // =====================================================================
    //  PDC helpers
    // =====================================================================

    public static long getVirtualCount(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long stored = pdc.get(KEY_STACK_COUNT, PersistentDataType.LONG);
        return (stored == null) ? item.getItemStack().getAmount() : stored;
    }

    /**
     * Set virtual count. If count drops to zero the item is removed.
     * Also refreshes the hologram text.
     */
    /**
     * Called from NMS ItemEntity.tryToMerge (via the static merge overload) after a successful
     * vanilla merge. Updates PDC virtual count to match the new physical count and refreshes
     * the floating hologram when count > maxStackSize.
     *
     * SourbyCraft v9.17
     */
    public static void onMerged(org.bukkit.entity.Item item) {
        if (!SourbyCraftConfig.wildstackerEnabled) return;
        if (!started || KEY_STACK_COUNT == null) return;
        long phys = item.getItemStack().getAmount();
        item.getPersistentDataContainer().set(KEY_STACK_COUNT, PersistentDataType.LONG, phys);
        refreshHologram(item, phys);
    }

    public static void setVirtualCount(Item item, long count) {
        if (count <= 0) {
            removeHologram(item);
            item.remove();
            return;
        }
        item.getPersistentDataContainer().set(KEY_STACK_COUNT, PersistentDataType.LONG, count);
        refreshHologram(item, count);
    }

    // =====================================================================
    //  Hologram management
    // =====================================================================

    private static void refreshHologram(Item item, long count) {
        if (!SourbyCraftConfig.wildstackerHologram) {
            removeHologram(item);
            return;
        }
        int maxStack = item.getItemStack().getMaxStackSize();
        if (count <= maxStack) {
            removeHologram(item);
            return;
        }
        String itemName = item.getItemStack().getType().getKey().getKey()
            .replace('_', ' ');
        String label = itemName + " ×" + count; // ×
        UUID existingId = ITEM_TO_HOLOGRAM.get(item.getUniqueId());
        TextDisplay display = null;
        if (existingId != null) {
            Entity e = Bukkit.getEntity(existingId);
            if (e instanceof TextDisplay td && !td.isDead()) {
                display = td;
            }
        }
        if (display == null) {
            Location loc = item.getLocation().add(0, 0.6, 0);
            try {
                display = item.getWorld().spawn(loc, TextDisplay.class, td -> {
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setSeeThrough(false);
                    td.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                    td.setShadowed(false);
                    td.setShadowRadius(0f);
                });
                ITEM_TO_HOLOGRAM.put(item.getUniqueId(), display.getUniqueId());
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[SourbyCraft] Failed to spawn hologram for item " + item.getUniqueId() + ": " + t);
                return;
            }
        }
        display.text(Component.text(label));
    }

    private static void removeHologram(Item item) {
        UUID hUid = ITEM_TO_HOLOGRAM.remove(item.getUniqueId());
        if (hUid == null) return;
        Entity e = Bukkit.getEntity(hUid);
        if (e != null) e.remove();
    }

    // =====================================================================
    //  Periodic world tick (merge + hologram position sync)
    // =====================================================================

    private static void tickWorld(World world) {
        List<Item> items = new ArrayList<>(world.getEntitiesByClass(Item.class));

        // Sync hologram positions to follow moving items
        for (Item item : items) {
            if (!item.isValid()) continue;
            UUID hUid = ITEM_TO_HOLOGRAM.get(item.getUniqueId());
            if (hUid == null) continue;
            Entity e = Bukkit.getEntity(hUid);
            if (e instanceof TextDisplay td && !td.isDead()) {
                td.teleport(item.getLocation().add(0, 0.6, 0));
            }
        }

        // Merge pass — bucket by item type + meta only (no chunk constraint).
        // SourbyCraft v9.13: removed regionX/regionZ from key so items straddling chunk
        // boundaries can merge. O(n²) per bucket, but with 20-tick interval and radius 3 this
        // is fine for normal item counts (<500 ground items per world).
        Map<String, ArrayList<Item>> buckets = new HashMap<>();
        for (Item item : items) {
            if (!item.isValid() || item.isDead()) continue;
            // Skip items with very long pickup delay (freshly dispensed, etc.)
            if (item.getPickupDelay() > 1000) continue;
            ItemStack stack = item.getItemStack();
            String typeKey = stack.getType().getKey().toString();
            String metaKey = stack.hasItemMeta()
                ? Integer.toHexString(stack.getItemMeta().hashCode())
                : "nm";
            String bucketKey = typeKey + ":" + metaKey;
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(item);
        }
        for (ArrayList<Item> bucket : buckets.values()) {
            if (bucket.size() < 2) continue;
            mergeWithinBucket(bucket);
        }
    }

    private static void mergeWithinBucket(List<Item> bucket) {
        double radiusSq = (double) SourbyCraftConfig.itemMergeRadius
            * SourbyCraftConfig.itemMergeRadius;
        for (int i = 0; i < bucket.size(); i++) {
            Item a = bucket.get(i);
            if (!a.isValid() || a.isDead()) continue;
            for (int j = i + 1; j < bucket.size(); j++) {
                Item b = bucket.get(j);
                if (!b.isValid() || b.isDead()) continue;
                if (a.getLocation().distanceSquared(b.getLocation()) > radiusSq) continue;
                if (!a.getItemStack().isSimilar(b.getItemStack())) continue;
                if (SourbyCraftConfig.wildstackerLosCheck && !hasLineOfSight(a, b)) continue;
                // Merge b into a: add counts, average velocity, sync pickup delay
                long combined = getVirtualCount(a) + getVirtualCount(b);
                Vector avgVel = a.getVelocity().add(b.getVelocity()).multiply(0.5);
                a.setVelocity(avgVel);
                a.setPickupDelay(Math.max(a.getPickupDelay(), b.getPickupDelay()));
                removeHologram(b);
                b.remove();
                setVirtualCount(a, combined);
            }
        }
    }

    /**
     * Raytrace LOS check between two item entities.
     * Returns true if nothing blocks them (merge allowed).
     *
     * SourbyCraft v9.13: raised Y offset from 0.1 to 0.5 (item-centre, well above the floor
     * block). With offset 0.1 the scan origin lands inside the floor block (items sit ~0.13
     * above ground, floor(item.y) = ground level), causing the floor to falsely block the ray.
     * The manual scan also now skips blocks at the floor Y of either item for the same reason.
     */
    private static boolean hasLineOfSight(Item a, Item b) {
        // SourbyCraft v9.13 — 0.5 offset puts sample point at item-centre, above floor block
        Location from = a.getLocation().add(0, 0.5, 0);
        Location to   = b.getLocation().add(0, 0.5, 0);
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 0.01) return true;
        dir.normalize();
        // Primary raytrace for solid block outlines
        RayTraceResult result = a.getWorld().rayTraceBlocks(
            from, dir, dist, FluidCollisionMode.NEVER, true);
        if (result != null && result.getHitBlock() != null) {
            return false;
        }
        // Secondary linear scan to catch non-solid shapes (carpet, snow layer, tripwire string)
        // Skip blocks at the floor Y of either item so the floor block never voids the merge.
        int aFloorY = (int) Math.floor(a.getLocation().getY());
        int bFloorY = (int) Math.floor(b.getLocation().getY());
        int steps = (int) Math.ceil(dist);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double bx = from.getX() + (to.getX() - from.getX()) * t;
            double by = from.getY() + (to.getY() - from.getY()) * t;
            double bz = from.getZ() + (to.getZ() - from.getZ()) * t;
            int byInt = (int) Math.floor(by);
            // SourbyCraft v9.13 — floor blocks are under the items, not between them
            if (byInt == aFloorY || byInt == bFloorY) continue;
            org.bukkit.block.Block block = a.getWorld().getBlockAt(
                (int) Math.floor(bx), byInt, (int) Math.floor(bz));
            if (!block.getType().isAir()) return false;
        }
        return true;
    }

    // =====================================================================
    //  Event listeners
    // =====================================================================

    /** Initialize virtual count when an item entity first spawns. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] ItemSpawnEvent fired for "
            + event.getEntity().getItemStack().getType()
            + " count=" + event.getEntity().getItemStack().getAmount()
            + " at " + event.getLocation().toVector());
        if (!SourbyCraftConfig.wildstackerEnabled) return;
        Item item = event.getEntity();
        long initialCount = item.getItemStack().getAmount();
        item.getPersistentDataContainer().set(KEY_STACK_COUNT, PersistentDataType.LONG, initialCount);
        // SourbyCraft v9.13 — schedule an immediate merge attempt 2 ticks after spawn so
        // multiple items dropped at once merge without waiting for the 20-tick periodic scan.
        Plugin plugin = Bukkit.getPluginManager().getPlugins()[0];
        // SourbyCraft v9.14: multi-attempt merge — immediate + 2 ticks + 5 ticks
        // Catches: items dropped simultaneously (immediate), items still falling (+2),
        // items that landed apart and need a periodic catchup (+5)
        tryMergeNearby(item);
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryMergeNearby(item), 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryMergeNearby(item), 5L);
    }

    /**
     * Merge {@code item} against all nearby items of the same type within merge radius.
     * Called 2 ticks after spawn so freshly-dropped stacks consolidate immediately.
     *
     * SourbyCraft v9.13
     */
    private static void tryMergeNearby(Item item) {
        if (!SourbyCraftConfig.wildstackerEnabled) return;
        if (!item.isValid() || item.isDead()) return;
        double radius = SourbyCraftConfig.itemMergeRadius;
        var nearby = item.getNearbyEntities(radius, radius, radius);
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] tryMergeNearby item="
            + item.getItemStack().getType() + " nearby=" + nearby.size());
        int merged = 0;
        for (Entity e : nearby) {
            if (!(e instanceof Item other)) continue;
            if (!other.isValid() || other.isDead()) continue;
            if (!item.getItemStack().isSimilar(other.getItemStack())) continue;
            if (SourbyCraftConfig.wildstackerLosCheck && !hasLineOfSight(item, other)) {
                Bukkit.getLogger().info("[SourbyCraft:Wildstacker] LOS blocked merge");
                continue;
            }
            long combined = getVirtualCount(item) + getVirtualCount(other);
            Vector avgVel = item.getVelocity().add(other.getVelocity()).multiply(0.5);
            item.setVelocity(avgVel);
            item.setPickupDelay(Math.max(item.getPickupDelay(), other.getPickupDelay()));
            removeHologram(other);
            other.remove();
            setVirtualCount(item, combined);
            merged++;
        }
        if (merged > 0) {
            Bukkit.getLogger().info("[SourbyCraft:Wildstacker] merged " + merged
                + " entities into one (combined count = " + getVirtualCount(item) + ")");
        }
    }

    /**
     * Intercept pickup when the item has a virtual surplus.
     * Give the player one physical stack and decrement the virtual count.
     * The item entity remains in-world until virtual count reaches zero.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!SourbyCraftConfig.wildstackerEnabled) return;
        Item item = event.getItem();
        long virtual = getVirtualCount(item);
        int physical = item.getItemStack().getAmount();
        if (virtual <= physical) return; // vanilla is correct — let it proceed

        // Cancel default pickup; give one physical stack manually
        event.setCancelled(true);
        if (!(event.getEntity() instanceof HumanEntity human)) return;

        ItemStack give = item.getItemStack().clone();
        give.setAmount(physical);
        Map<Integer, ItemStack> leftovers = human.getInventory().addItem(give);
        int leftoverCount = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        int given = physical - leftoverCount;
        if (given <= 0) return; // inventory full

        long remaining = virtual - given;
        if (remaining <= 0) {
            removeHologram(item);
            item.remove();
        } else {
            // Keep a full physical stack in the item entity; update virtual count
            ItemStack newStack = item.getItemStack().clone();
            newStack.setAmount((int) Math.min(remaining, item.getItemStack().getMaxStackSize()));
            item.setItemStack(newStack);
            setVirtualCount(item, remaining);
        }
    }
}
