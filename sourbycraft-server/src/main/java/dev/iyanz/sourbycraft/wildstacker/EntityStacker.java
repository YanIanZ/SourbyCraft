package dev.iyanz.sourbycraft.wildstacker;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

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
    private static NamespacedKey HOLOGRAM_KEY;
    private static final java.util.concurrent.atomic.AtomicInteger DEBUG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private EntityStacker() {}

    public static void register(Plugin plugin) {
        OWNER = plugin;
        HOLOGRAM_KEY = new NamespacedKey(plugin, "stack_hologram");
        reload();
        Bukkit.getPluginManager().registerEvents(new EntityStacker(), plugin);
        plugin.getLogger().info("[stacker] item stacker " + (ENABLED ? "ENABLED" : "disabled")
                + " (stacker.enabled=" + ENABLED + " radius=" + RADIUS
                + " maxStackMultiplier=" + MAX_STACK_MULTIPLIER + ")");
    }

    /**
     * LOS check: any solid block between source and target stops a merge.
     * Prevents items in adjacent rooms / through walls from collapsing into
     * one stack — that'd let players siphon farms remotely.
     */
    private static boolean hasLineOfSight(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) return false;
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 1e-6) return true;
        dir.normalize();
        RayTraceResult result = from.getWorld().rayTraceBlocks(from, dir, dist, FluidCollisionMode.NEVER, true);
        return result == null || result.getHitBlock() == null;
    }

    private static String prettyName(org.bukkit.Material m) {
        String raw = m.getKey().getKey();
        StringBuilder out = new StringBuilder(raw.length());
        boolean cap = true;
        for (char c : raw.toCharArray()) {
            if (c == '_') { out.append(' '); cap = true; continue; }
            out.append(cap ? Character.toUpperCase(c) : c);
            cap = false;
        }
        return out.toString();
    }

    private static void updateHologram(Item item) {
        if (OWNER == null || HOLOGRAM_KEY == null) return;
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getAmount() <= 1) {
            removeHologram(item);
            return;
        }
        String storedUuid = item.getPersistentDataContainer().get(HOLOGRAM_KEY, PersistentDataType.STRING);
        TextDisplay holo = null;
        if (storedUuid != null) {
            try {
                Entity e = Bukkit.getEntity(UUID.fromString(storedUuid));
                if (e instanceof TextDisplay td && !td.isDead()) holo = td;
            } catch (Throwable ignored) {}
        }
        Component label = Component.text(prettyName(stack.getType()) + " ", NamedTextColor.YELLOW)
                .append(Component.text("x" + stack.getAmount(), NamedTextColor.GRAY));
        if (holo == null) {
            Location loc = item.getLocation().clone().add(0, 0.55, 0);
            TextDisplay td = item.getWorld().spawn(loc, TextDisplay.class, ent -> {
                ent.setBillboard(Billboard.CENTER);
                ent.setSeeThrough(false);  // hologram hidden behind solid blocks
                ent.setShadowed(true);
                ent.setPersistent(false);
                ent.setInvulnerable(true);
                ent.text(label);
            });
            // Mount on item so display tracks position automatically.
            item.addPassenger(td);
            item.getPersistentDataContainer().set(HOLOGRAM_KEY, PersistentDataType.STRING, td.getUniqueId().toString());
        } else {
            holo.text(label);
        }
    }

    private static void removeHologram(Item item) {
        if (HOLOGRAM_KEY == null) return;
        String storedUuid = item.getPersistentDataContainer().get(HOLOGRAM_KEY, PersistentDataType.STRING);
        if (storedUuid == null) return;
        try {
            Entity e = Bukkit.getEntity(UUID.fromString(storedUuid));
            if (e != null) e.remove();
        } catch (Throwable ignored) {}
        item.getPersistentDataContainer().remove(HOLOGRAM_KEY);
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
            // LOS: solid block between drops blocks merge — players can't
            // siphon adjacent farms by tossing items at walls.
            if (!hasLineOfSight(loc, nearItem.getLocation())) continue;
            near.setAmount(sum);
            nearItem.setItemStack(near);
            updateHologram(nearItem);
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
        // ensure the merged amount respects our extended cap. Then update
        // hologram on the surviving target entity.
        ItemStack merged = e.getTarget().getItemStack();
        int cap = merged.getMaxStackSize() * MAX_STACK_MULTIPLIER;
        if (cap > 0 && merged.getAmount() > cap) {
            merged.setAmount(cap);
            e.getTarget().setItemStack(merged);
        }
        // Source entity dies on vanilla merge → its hologram is mounted on
        // itself and would be removed by vanilla. Cleanup defensively.
        removeHologram(e.getEntity());
        updateHologram(e.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        removeHologram(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent e) {
        removeHologram(e.getItem());
    }
}
