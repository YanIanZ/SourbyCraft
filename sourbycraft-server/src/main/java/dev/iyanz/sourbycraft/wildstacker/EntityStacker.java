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
    private static volatile boolean HOLOGRAM = true;
    private static volatile boolean LOS_CHECK = true;
    private static volatile java.util.Set<org.bukkit.Material> BLACKLIST = java.util.Set.of();
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
                + " maxStackMultiplier=" + MAX_STACK_MULTIPLIER + " hologram=" + HOLOGRAM + " losCheck=" + LOS_CHECK + " blacklist=" + BLACKLIST.size() + ")");

        // WildStacker parity: periodic merge sweep over loaded items.
        // ItemSpawnEvent only catches the *spawning* item, but two existing
        // items can drift within radius later (water push, piston, dispenser).
        // Every 40 ticks (~2s) walk loaded worlds, group items within radius,
        // collapse same-ItemStack groups into one entity.
        if (ENABLED) {
            Bukkit.getScheduler().runTaskTimer(plugin, EntityStacker::periodicMergeSweep, 80L, 40L);
            plugin.getLogger().info("[stacker] periodic merge sweep scheduled every 40 ticks");
        }
    }

    /** Periodic sweep: scan every loaded Item, merge clusters in-place. */
    private static void periodicMergeSweep() {
        if (!ENABLED) return;
        try {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                java.util.List<Item> items = new java.util.ArrayList<>();
                for (Entity ent : world.getEntities()) {
                    if (ent instanceof Item it && !it.isDead()) items.add(it);
                }
                if (items.size() < 2) continue;
                // O(n²) cluster pass — fine for typical drop counts < a few hundred.
                java.util.Set<Item> consumed = new java.util.HashSet<>();
                for (int i = 0; i < items.size(); i++) {
                    Item a = items.get(i);
                    if (consumed.contains(a) || a.isDead()) continue;
                    ItemStack sa = a.getItemStack();
                    if (sa == null || sa.getType().isAir()) continue;
                    if (BLACKLIST.contains(sa.getType())) continue;
                    int capA = sa.getMaxStackSize() * MAX_STACK_MULTIPLIER;
                    if (capA <= 0) capA = Integer.MAX_VALUE;
                    Location la = a.getLocation();
                    for (int j = i + 1; j < items.size(); j++) {
                        Item b = items.get(j);
                        if (consumed.contains(b) || b.isDead()) continue;
                        if (b == a) continue;
                        ItemStack sb = b.getItemStack();
                        if (sb == null || !sb.isSimilar(sa)) continue;
                        Location lb = b.getLocation();
                        if (la.distanceSquared(lb) > RADIUS * RADIUS) continue;
                        if (LOS_CHECK && !hasLineOfSight(la, lb)) continue;
                        int sum = sa.getAmount() + sb.getAmount();
                        if (sum > capA) continue;
                        sa.setAmount(sum);
                        a.setItemStack(sa);
                        // Source dies; its passenger hologram (if any) goes with it.
                        try { removeHologram(b); } catch (Throwable ignored) {}
                        b.remove();
                        consumed.add(b);
                    }
                    // Refresh hologram on representative.
                    if (sa.getAmount() > 1) {
                        try { updateHologram(a); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            if (OWNER != null) {
                OWNER.getLogger().warning("[stacker] periodic sweep error: " + t.getMessage());
            }
        }
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
        if (!HOLOGRAM) { removeHologram(item); return; }
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
        HOLOGRAM = SourbyCraftConfig.stackerHologram;
        LOS_CHECK = SourbyCraftConfig.stackerLosCheck;
        // Blacklist semantic: item materials excluded from stacking. Legacy
        // EntityType names (PLAYER, WITHER, ...) don't resolve to materials
        // and are ignored harmlessly.
        java.util.Set<org.bukkit.Material> parsed = new java.util.HashSet<>();
        for (String name : SourbyCraftConfig.stackerBlacklist) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(name);
            if (m != null) parsed.add(m);
        }
        BLACKLIST = java.util.Set.copyOf(parsed);
    }

    // Diagnostic-only MONITOR listener — fires regardless of cancel state.
    // If user sees this log but no `[stacker] merged/no-match` lines below
    // from the LOWEST listener, that means another plugin cancelled the
    // event before we ran. If even this MONITOR doesn't fire, the event
    // path itself is blocked (likely Paper plugin manager issue).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemSpawnMonitor(ItemSpawnEvent e) {
        if (!ENABLED || OWNER == null) return;
        int n = DEBUG_COUNTER.incrementAndGet();
        if (n <= 20) {
            ItemStack s = e.getEntity().getItemStack();
            OWNER.getLogger().info("[stacker:diag] ItemSpawnEvent fired type=" + (s == null ? "?" : s.getType())
                    + " cancelled=" + e.isCancelled() + " world=" + e.getEntity().getWorld().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!ENABLED) return;
        if (e.isCancelled()) return;
        Item newItem = e.getEntity();
        ItemStack newStack = newItem.getItemStack();
        if (newStack == null || newStack.getType().isAir()) return;
        if (BLACKLIST.contains(newStack.getType())) return;
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
            if (LOS_CHECK && !hasLineOfSight(loc, nearItem.getLocation())) continue;
            near.setAmount(sum);
            nearItem.setItemStack(near);
            // Cancel new spawn FIRST so client doesn't see the new entity.
            e.setCancelled(true);
            // Defer hologram update by 1 tick — addPassenger on a newly-spawned
            // item entity mid-event can throw; safer after the spawn settles.
            if (OWNER != null) {
                Bukkit.getScheduler().runTask(OWNER, () -> {
                    try { updateHologram(nearItem); } catch (Throwable t) {
                        OWNER.getLogger().warning("[stacker] hologram update failed: " + t.getMessage());
                    }
                });
            }
            if (OWNER != null) {
                int n = DEBUG_COUNTER.incrementAndGet();
                if (n <= 30) {
                    OWNER.getLogger().info("[stacker] merged " + newStack.getType()
                            + " into nearby item @ " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                            + " (newAmount=" + sum + " scanned=" + scanned + ")");
                }
            }
            return;
        }
        if (OWNER != null) {
            int n = DEBUG_COUNTER.incrementAndGet();
            if (n <= 30) {
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
        if (merged == null) return;
        if (BLACKLIST.contains(merged.getType())) return;
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
