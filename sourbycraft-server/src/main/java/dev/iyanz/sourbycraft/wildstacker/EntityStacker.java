package dev.iyanz.sourbycraft.wildstacker;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;

/**
 * Minimal entity stacker. Merges same-type living entities spawned within
 * {@link SourbyCraftConfig#stackerRadius} into a single representative carrying
 * a stack count in its PDC. On death, decrements the stack and respawns one
 * less entity until the stack drains.
 *
 * <p>Goals: kill mob-density TPS pressure on populated farms / SS2 islands
 * without external plugin dependency. Drop-multiplier respects vanilla loot
 * tables — death event runs once per real kill; we top up the death drop list
 * with N-1 extra copies of each drop.</p>
 *
 * <p>Not aiming for WildStacker feature parity. Operators wanting per-type
 * limits, custom names, sounds, etc — point to the upstream plugin.</p>
 */
public final class EntityStacker implements Listener {

    /** PDC key carrying the current stack count on a stacked representative. */
    private static NamespacedKey STACK_KEY;
    private static volatile boolean ENABLED = false;
    private static volatile double RADIUS_SQ = 100.0; // (10 blocks)^2 default
    private static volatile int MAX_STACK = 100;
    /** EntityTypes excluded from stacking. */
    private static final Set<EntityType> BLACKLIST = new HashSet<>();

    private EntityStacker() {}

    public static void register(Plugin plugin) {
        STACK_KEY = new NamespacedKey(plugin, "stack_count");
        reload();
        if (ENABLED) {
            Bukkit.getPluginManager().registerEvents(new EntityStacker(), plugin);
            plugin.getLogger().info("[stacker] entity stacker enabled (radius="
                    + Math.sqrt(RADIUS_SQ) + " maxStack=" + MAX_STACK + ")");
        }
    }

    public static void reload() {
        ENABLED = SourbyCraftConfig.stackerEnabled;
        double r = Math.max(1.0, SourbyCraftConfig.stackerRadius);
        RADIUS_SQ = r * r;
        MAX_STACK = Math.max(2, SourbyCraftConfig.stackerMaxStack);
        BLACKLIST.clear();
        for (String name : SourbyCraftConfig.stackerBlacklist) {
            try {
                BLACKLIST.add(EntityType.valueOf(name.toUpperCase().trim()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static int getStack(Entity e) {
        Integer v = e.getPersistentDataContainer().get(STACK_KEY, PersistentDataType.INTEGER);
        return v == null ? 1 : v;
    }

    private static void setStack(LivingEntity e, int count) {
        if (count <= 1) {
            e.getPersistentDataContainer().remove(STACK_KEY);
            e.customName(null);
            e.setCustomNameVisible(false);
        } else {
            e.getPersistentDataContainer().set(STACK_KEY, PersistentDataType.INTEGER, count);
            Component name = Component.text(count + "x ", NamedTextColor.GRAY)
                    .append(Component.text(prettyName(e.getType()), NamedTextColor.YELLOW));
            e.customName(name);
            e.setCustomNameVisible(true);
        }
    }

    private static String prettyName(EntityType t) {
        String raw = t.getKey().getKey();
        StringBuilder out = new StringBuilder(raw.length());
        boolean cap = true;
        for (char c : raw.toCharArray()) {
            if (c == '_') { out.append(' '); cap = true; continue; }
            out.append(cap ? Character.toUpperCase(c) : c);
            cap = false;
        }
        return out.toString();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!ENABLED) return;
        LivingEntity newEntity = e.getEntity();
        EntityType type = newEntity.getType();
        if (BLACKLIST.contains(type)) return;
        // Don't stack named-entity spawns (player-renamed, mythic mobs, etc).
        if (newEntity.customName() != null) {
            int existing = getStack(newEntity);
            if (existing <= 1) return; // not one of our stacked
        }
        Location loc = newEntity.getLocation();
        for (Entity nearby : newEntity.getWorld().getNearbyEntities(loc, Math.sqrt(RADIUS_SQ), Math.sqrt(RADIUS_SQ), Math.sqrt(RADIUS_SQ))) {
            if (nearby == newEntity) continue;
            if (nearby.getType() != type) continue;
            if (!(nearby instanceof LivingEntity existingLiving)) continue;
            if (BLACKLIST.contains(nearby.getType())) continue;
            int currentStack = getStack(nearby);
            if (currentStack >= MAX_STACK) continue;
            // Merge: increment existing stack, cancel new spawn.
            setStack(existingLiving, currentStack + 1);
            e.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!ENABLED) return;
        LivingEntity dead = e.getEntity();
        int stack = getStack(dead);
        if (stack <= 1) return;
        // Death of one — duplicate drops + xp for the remaining (stack-1) and
        // respawn a stack of (stack-1) at the same location.
        java.util.List<ItemStack> drops = e.getDrops();
        java.util.List<ItemStack> copies = new java.util.ArrayList<>(drops.size() * (stack - 1));
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            for (int i = 0; i < stack - 1; i++) copies.add(drop.clone());
        }
        drops.addAll(copies);
        e.setDroppedExp(e.getDroppedExp() * stack);

        int remaining = stack - 1;
        EntityType type = dead.getType();
        Location loc = dead.getLocation();
        // Schedule respawn one tick later so death is fully processed.
        Bukkit.getScheduler().runTaskLater(WildstackerManager.ownerPlugin(), () -> {
            Entity spawned = loc.getWorld().spawnEntity(loc, type);
            if (spawned instanceof LivingEntity living) {
                setStack(living, remaining);
            }
        }, 1L);
    }
}
