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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NMS-only wildstacker (v9.19):
 *   - Virtual stack count stored in Item PersistentDataContainer (key sourbycraft:stack_count, LONG).
 *   - TextDisplay hologram follows item via per-tick teleport.
 *   - Periodic tick driven by MinecraftServer NMS hook (no Bukkit scheduler/plugin needed).
 *   - Pickup handled via ItemEntity.playerTouch NMS patch (no Bukkit listener needed).
 *   - Works with zero plugins loaded.
 */
public final class WildstackerManager {

    // PDC key: sourbycraft:stack_count
    public static NamespacedKey KEY_STACK_COUNT;

    // item UUID -> hologram UUID
    private static final Map<UUID, UUID> ITEM_TO_HOLOGRAM = new ConcurrentHashMap<>();

    private static volatile boolean started = false;

    private WildstackerManager() {}

    // =====================================================================
    //  Plugin owner helper (used by TpsBarCommand / RamBarCommand for scheduler)
    // =====================================================================

    /**
     * Returns an enabled plugin for scheduler registration.
     * Tries legacy SimplePluginManager first, then PaperPluginManagerImpl, then any.
     * Returns null only if literally no plugins are loaded.
     *
     * Paper 1.21+ separates PaperPluginManagerImpl from the legacy SimplePluginManager,
     * so getPlugins() can return an empty array even when plugins are loaded. This bridge
     * method ensures we always find an owner plugin regardless of which manager holds them.
     *
     * SourbyCraft v9.18 — kept for TpsBarCommand/RamBarCommand scheduler use after v9.19
     * wildstacker NMS-only refactor.
     */
    public static Plugin ownerPlugin() {
        Plugin[] legacy = Bukkit.getPluginManager().getPlugins();
        for (Plugin p : legacy) {
            if (p != null && p.isEnabled()) return p;
        }
        try {
            Plugin[] paper =
                io.papermc.paper.plugin.manager.PaperPluginManagerImpl.getInstance().getPlugins();
            for (Plugin p : paper) {
                if (p != null && p.isEnabled()) return p;
            }
        } catch (Throwable ignored) {}
        if (legacy.length > 0 && legacy[0] != null) return legacy[0];
        return null;
    }

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    /**
     * Start the manager in NMS-only mode. No Bukkit plugin, scheduler, or listener
     * is required. Periodic ticking is driven by MinecraftServer.tickChildren hook,
     * and pickup handling by ItemEntity.playerTouch patch.
     *
     * Safe to call multiple times (idempotent).
     *
     * SourbyCraft v9.19
     */
    // SourbyCraft v9.24 — flip to true only for diagnostics
    private static final boolean DEBUG = false;

    public static void start() {
        if (started) return;
        KEY_STACK_COUNT = NamespacedKey.fromString("sourbycraft:stack_count");
        started = true;
        Bukkit.getLogger().info("[SourbyCraft:Wildstacker] STARTED (NMS-only mode, PDC key: " + KEY_STACK_COUNT + ")");
    }

    /**
     * Called every 4 ticks from MinecraftServer.tickChildren NMS hook.
     * Handles hologram sync and merge scan across all worlds.
     *
     * SourbyCraft v9.19
     */
    public static void tickAll() {
        if (!started || !SourbyCraftConfig.wildstackerEnabled) return;
        for (World world : Bukkit.getWorlds()) {
            tickWorld(world);
        }
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

    /** Returns the number of active hologram TextDisplay entities. SourbyCraft v9.18 */
    public static int hologramCount() {
        return ITEM_TO_HOLOGRAM.size();
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
        if (DEBUG) Bukkit.getLogger().info("[SourbyCraft:Wildstacker] onMerged called for " + item.getUniqueId()
            + " amount=" + item.getItemStack().getAmount());
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
        if (DEBUG) Bukkit.getLogger().info("[SourbyCraft:Wildstacker] refreshHologram item=" + item.getUniqueId()
            + " count=" + count + " hologramFlag=" + SourbyCraftConfig.wildstackerHologram);
        if (!SourbyCraftConfig.wildstackerHologram) {
            removeHologram(item);
            return;
        }
        // SourbyCraft v9.24 — show hologram for ANY stacked item (count >= 2)
        if (count <= 1) {
            removeHologram(item);
            return;
        }
        // SourbyCraft v9.26 — bracket-wrapped format: [Dirt 7x]
        String raw = item.getItemStack().getType().getKey().getKey();
        StringBuilder titled = new StringBuilder();
        boolean upper = true;
        for (char ch : raw.toCharArray()) {
            if (ch == '_') { titled.append(' '); upper = true; }
            else if (upper) { titled.append(Character.toUpperCase(ch)); upper = false; }
            else titled.append(ch);
        }
        String itemName = titled.toString();
        // SourbyCraft v9.27 — clean format: Dirt x7 (no brackets, x prefix)
        Component label = Component.text()
            .append(Component.text(itemName, dev.iyanz.sourbycraft.SourbyCraftColors.HEADER))
            .append(Component.text(" x", dev.iyanz.sourbycraft.SourbyCraftColors.DIM))
            .append(Component.text(String.valueOf(count), dev.iyanz.sourbycraft.SourbyCraftColors.VALUE))
            .build();
        UUID existingId = ITEM_TO_HOLOGRAM.get(item.getUniqueId());
        TextDisplay display = null;
        if (existingId != null) {
            Entity e = Bukkit.getEntity(existingId);
            if (e instanceof TextDisplay td && !td.isDead()) {
                display = td;
            }
        }
        if (display == null) {
            // SourbyCraft v9.21 — Y offset raised 0.6 → 1.0, transparent bg, see-through, shadowed
            Location loc = item.getLocation().add(0, 0.4, 0);
            try {
                display = item.getWorld().spawn(loc, TextDisplay.class, td -> {
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setSeeThrough(true);
                    td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    td.setShadowed(true);
                    td.setShadowRadius(0f);
                });
                ITEM_TO_HOLOGRAM.put(item.getUniqueId(), display.getUniqueId());
                if (DEBUG) Bukkit.getLogger().info("[SourbyCraft:Wildstacker] spawned hologram " + display.getUniqueId()
                    + " for item " + item.getUniqueId() + " at " + loc);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[SourbyCraft] Failed to spawn hologram for item " + item.getUniqueId() + ": " + t);
                return;
            }
        }
        display.text(label);
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
        // SourbyCraft v9.25 — cleanup pass: remove holograms whose Item entity is gone (pickup, despawn, force-remove, world unload)
        java.util.Iterator<java.util.Map.Entry<UUID, UUID>> cleanupIt = ITEM_TO_HOLOGRAM.entrySet().iterator();
        while (cleanupIt.hasNext()) {
            java.util.Map.Entry<UUID, UUID> entry = cleanupIt.next();
            Entity bukkitItem = Bukkit.getEntity(entry.getKey());
            if (bukkitItem == null || !bukkitItem.isValid() || bukkitItem.isDead()) {
                Entity hologram = Bukkit.getEntity(entry.getValue());
                if (hologram != null) hologram.remove();
                cleanupIt.remove();
            }
        }

        List<Item> items = new ArrayList<>(world.getEntitiesByClass(Item.class));

        // Sync hologram positions to follow moving items
        for (Item item : items) {
            if (!item.isValid()) continue;
            UUID hUid = ITEM_TO_HOLOGRAM.get(item.getUniqueId());
            if (hUid == null) continue;
            Entity e = Bukkit.getEntity(hUid);
            if (e instanceof TextDisplay td && !td.isDead()) {
                // SourbyCraft v9.21 — Y offset 1.0 (matches refreshHologram)
                td.teleport(item.getLocation().add(0, 0.4, 0));
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
                // Merge b into a: add counts, zero velocity (v9.21 — was averaged, caused drift), sync pickup delay
                long combined = getVirtualCount(a) + getVirtualCount(b);
                // SourbyCraft v9.21 — zero velocity on merge to prevent knockback drift accumulation
                a.setVelocity(new Vector(0, 0, 0));
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

    /**
     * Called from ItemEntity.playerTouch NMS patch before vanilla pickup logic.
     * Returns true if wildstacker handled the pickup (caller should return immediately),
     * false to let vanilla proceed.
     *
     * Intercepts when the item has a virtual surplus: gives the player one physical
     * stack, decrements the virtual count, and keeps the item entity in-world until
     * virtual count reaches zero.
     *
     * SourbyCraft v9.19
     */
    public static boolean onPlayerPickup(
        net.minecraft.world.entity.item.ItemEntity nmsItem,
        net.minecraft.world.entity.player.Player nmsPlayer
    ) {
        if (!started || !SourbyCraftConfig.wildstackerEnabled) return false;
        if (KEY_STACK_COUNT == null) return false;
        Item item = (Item) nmsItem.getBukkitEntity();
        long virtual = getVirtualCount(item);
        int physical = item.getItemStack().getAmount();
        if (virtual <= physical) return false; // vanilla path handles it correctly

        if (!(nmsPlayer.getBukkitEntity() instanceof HumanEntity human)) return false;

        ItemStack give = item.getItemStack().clone();
        give.setAmount(physical);
        Map<Integer, ItemStack> leftovers = human.getInventory().addItem(give);
        int leftoverCount = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        int given = physical - leftoverCount;
        if (given <= 0) return true; // inventory full — we handled it, don't let vanilla double-give

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
        return true;
    }
}
